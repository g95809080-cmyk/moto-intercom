import { createHash, createPublicKey, randomBytes, randomUUID, verify } from 'node:crypto';
import { transact, TransactionConflict } from './store.mjs';
import { expireLocalSwitch, localProbe, localSwitchCommand } from './local-switch.mjs';

const MINUTE = 60_000, OFFLINE_MS = 10 * MINUTE, PRESENCE_MS = 90_000;
const CLOUD_MS = 4 * 60 * MINUTE, WARNING_MS = 30 * MINUTE;
const hash = data => createHash('sha256').update(data).digest('hex');
const id = (value, name = 'id') => {
  need(typeof value === 'string' && /^[a-zA-Z0-9_-]{1,128}$/.test(value), 400, `INVALID_${name.toUpperCase()}`);
  return value;
};
class ApiError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
function need(condition, status, code) { if (!condition) throw new ApiError(status, code); }
const reply = (statusCode, body) => ({ statusCode, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' }, body: JSON.stringify(body) });
const failure = error => reply(error instanceof ApiError ? error.status : error instanceof TransactionConflict ? 409 : 500,
  { error: error instanceof ApiError ? error.code : error instanceof TransactionConflict ? 'RETRY_COMMAND' : 'INTERNAL_ERROR' });
function base64(text, max = 1024) {
  need(typeof text === 'string' && text.length > 0 && text.length <= max && /^[A-Za-z0-9+/]+={0,2}$/.test(text), 400, 'INVALID_BASE64');
  const decoded = Buffer.from(text, 'base64');
  need(decoded.toString('base64') === text, 400, 'INVALID_BASE64'); return decoded;
}
function publicKey(text) {
  try {
    const key = createPublicKey({ key: base64(text, 256), format: 'der', type: 'spki' });
    need(key.asymmetricKeyType === 'ec' && key.asymmetricKeyDetails?.namedCurve === 'prime256v1', 400, 'INVALID_PUBLIC_KEY');
    need(key.export({ format: 'der', type: 'spki' }).toString('base64') === text, 400, 'INVALID_PUBLIC_KEY');
    return key;
  } catch { throw new ApiError(400, 'INVALID_PUBLIC_KEY'); }
}
function signatureValid(key, message, signature) {
  try { return verify('sha256', Buffer.from(message, 'utf8'), publicKey(key), base64(signature, 256)); }
  catch { return false; }
}

export function canonicalRequest(method, path, rawBody, timestamp, nonce) {
  return [method, path, hash(rawBody), timestamp, nonce].join('\n');
}

// Canonical UTF-8 JSON with exactly this property order; both Android peers sign it.
export function pairingTranscript(t) {
  return JSON.stringify({ protocolVersion: t.protocolVersion, pairId: t.pairId,
    deviceIdA: t.deviceIdA, deviceIdB: t.deviceIdB, publicKeyA: t.publicKeyA, publicKeyB: t.publicKeyB,
    createdAt: t.createdAt, nonce: t.nonce, expiresAt: t.expiresAt });
}

export function createControlPlane({ store, issueTicket, now = Date.now }) {
  const ttl = (time, ms) => Math.ceil((time + ms) / 1000);
  const devKey = deviceId => `DEVICE#${deviceId}`;
  const rideKey = rideId => `RIDE#${rideId}`;
  const pairKey = pairId => `PAIR#${pairId}`;
  const openIdFor = d => (10_001n + BigInt(`0x${hash(base64(d.publicKey)).slice(0, 16)}`) % (0x7fffffffffffffffn - 10_001n)).toString();

  async function event(tx, type, ride, payload, targets = ride.members, time = now()) {
    const eventId = randomUUID();
    await tx.put(`EVENT#${eventId}`, { kind: 'EVENT', targets,
      event: { eventId, type, rideId: ride.rideId ?? null, revision: ride.revision, occurredAt: time, payload },
      ttl: ttl(time, type.startsWith('invite.') || type === 'presence.changed' ? 24 * 60 * MINUTE : 7 * 24 * 60 * MINUTE) });
  }

  async function device(tx, deviceId) {
    const value = await tx.get(devKey(deviceId));
    need(value && !value.revokedAt, 401, 'DEVICE_NOT_REGISTERED'); return value;
  }
  async function pair(tx, pairId, actor) {
    const value = await tx.get(pairKey(pairId));
    need(value?.members.includes(actor), 403, 'PAIR_NOT_AUTHORIZED'); return value;
  }
  async function authorizedRide(tx, rideId, actor) {
    const value = await tx.get(rideKey(rideId));
    need(value?.members.includes(actor), 403, 'RIDE_NOT_AUTHORIZED');
    await pair(tx, value.pairId, actor); await device(tx, actor); return value;
  }
  const live = ride => need(ride.state !== 'ENDED', 409, 'RIDE_ENDED');
  function cloudUsed(ride, time) { return ride.cloudUsedMs + (ride.cloudStartedAt === null ? 0 : Math.max(0, time - ride.cloudStartedAt)); }
  async function saveRide(tx, ride, time) {
    ride.updatedAt = time;
    ride.ttl = ttl(time, 7 * 24 * 60 * MINUTE);
    delete ride.indexPk; delete ride.indexAt;
    if (ride.state !== 'ENDED') {
      ride.indexPk = 'ACTIVE_RIDES';
      const due = [Math.max(...Object.values(ride.lastHeartbeatAt)) + OFFLINE_MS];
      if (ride.state === 'INVITE_PENDING') due.push(ride.inviteExpiresAt);
      if (ride.state === 'SWITCHING_LOCAL') due.push(ride.localSwitch.expiresAt);
      if (ride.cloudStartedAt !== null) {
        due.push(time + Math.max(0, ride.cloudAllowanceMs - cloudUsed(ride, time)));
        if (!ride.cloudWarningSent) due.push(time + Math.max(0, ride.cloudAllowanceMs - WARNING_MS - cloudUsed(ride, time)));
      }
      ride.indexAt = Math.min(...due);
    }
    await tx.put(rideKey(ride.rideId), ride);
  }
  async function endRide(tx, ride, reason, time) {
    if (ride.state === 'ENDED') return;
    ride.cloudUsedMs = cloudUsed(ride, time); ride.cloudStartedAt = null;
    ride.state = 'ENDED'; ride.endReason = reason; ride.endedAt = time; ride.revision++;
    if (ride.inviteId) {
      const invite = await tx.get(`INVITE#${ride.inviteId}`);
      if (invite?.state === 'PENDING') {
        invite.state = reason === 'INVITE_TIMEOUT' ? 'EXPIRED' : 'CANCELLED';
        await tx.put(invite.pk, invite);
        await event(tx, reason === 'INVITE_TIMEOUT' ? 'invite.expired' : 'invite.cancelled', ride, { inviteId: invite.inviteId }, ride.members, time);
      }
    }
    for (const member of ride.members) {
      const d = await tx.get(devKey(member));
      if (d?.activeRideId === ride.rideId) { d.activeRideId = null; await tx.put(d.pk, d); }
    }
    const p = await tx.get(pairKey(ride.pairId));
    if (p?.activeRideId === ride.rideId) { p.activeRideId = null; await tx.put(p.pk, p); }
    await saveRide(tx, ride, time);
    await event(tx, 'ride.ended', ride, { reason }, ride.members, time);
  }
  async function refreshRide(rideId, time) {
    await transact(store, async tx => {
      const r = await tx.get(rideKey(rideId)); if (!r || r.state === 'ENDED') return;
      if (time - Math.max(...Object.values(r.lastHeartbeatAt)) >= OFFLINE_MS) return endRide(tx, r, 'BOTH_OFFLINE', time);
      if (r.state === 'INVITE_PENDING' && time >= r.inviteExpiresAt) return endRide(tx, r, 'INVITE_TIMEOUT', time);
      const used = cloudUsed(r, time);
      let changed = expireLocalSwitch(r, time);
      if (changed) await event(tx, 'ride.local.switch', r, {}, r.members, time);
      if (r.cloudStartedAt !== null && used >= r.cloudAllowanceMs) {
        r.cloudUsedMs = used; r.cloudStartedAt = null; r.state = 'CLOUD_PAUSED'; r.revision++; changed = true;
        await event(tx, 'ride.cloud.failed', r, { reason: 'CLOUD_LIMIT', canExtend: true }, r.members, time);
      } else if (r.cloudStartedAt !== null && used >= r.cloudAllowanceMs - WARNING_MS && !r.cloudWarningSent) {
        r.cloudWarningSent = true; r.revision++; changed = true;
        await event(tx, 'ride.cloud.warning', r, { remainingMs: r.cloudAllowanceMs - used }, r.members, time);
      }
      if (changed) await saveRide(tx, r, time);
    });
  }
  async function isOnline(tx, d, time) {
    for (const connectionId of d.connectionIds) {
      const c = await tx.get(`CONN#${connectionId}`);
      if (c && c.deviceId === d.deviceId && c.registrationId === d.registrationId && time - c.lastHeartbeatAt < PRESENCE_MS) return true;
    }
    return false;
  }
  async function presenceEvent(tx, d, online, time) {
    const targets = new Set();
    for (const pairId of d.pairIds) {
      const p = await tx.get(pairKey(pairId)); p?.members.filter(m => m !== d.deviceId).forEach(m => targets.add(m));
    }
    if (targets.size) await event(tx, 'presence.changed', { revision: (d.version ?? 0) + 1 }, { deviceId: d.deviceId, online }, [...targets], time);
  }
  async function claim(tx, deviceId, rideId) {
    const d = await device(tx, deviceId);
    need(!d.activeRideId || d.activeRideId === rideId, 409, 'BUSY');
    if (d.activeRideId !== rideId) { d.activeRideId = rideId; await tx.put(d.pk, d); }
    return d;
  }

  async function authenticate(input, method, path, raw, body, time) {
    const h = Object.fromEntries(Object.entries(input.headers ?? {}).map(([k, v]) => [k.toLowerCase(), v]));
    const actor = id(h['x-device-id'], 'device_id'), timestamp = h['x-timestamp'], nonce = h['x-nonce'];
    need(typeof timestamp === 'string' && /^\d{13}$/.test(timestamp) && Math.abs(time - Number(timestamp)) <= MINUTE, 401, 'SIGNATURE_EXPIRED');
    need(typeof nonce === 'string' && /^[A-Za-z0-9_-]{16,128}$/.test(nonce), 401, 'INVALID_NONCE');
    const registered = await store.get(devKey(actor));
    const key = path === '/v1/devices/register' ? body.publicKey : registered?.publicKey;
    need(key && signatureValid(key, canonicalRequest(method, path, raw, timestamp, nonce), h['x-signature']), 401, 'INVALID_SIGNATURE');
    need(!registered?.revokedAt && (!registered || registered.publicKey === key), 401, 'DEVICE_KEY_MISMATCH');
    await transact(store, async tx => {
      const pk = `NONCE#${actor}#${nonce}`;
      need(!(await tx.get(pk)), 401, 'NONCE_REPLAY');
      await tx.put(pk, { kind: 'NONCE', ttl: ttl(time, 120_000) });
    });
    return { actor, publicKey: key, registrationId: registered?.registrationId ?? null };
  }

  async function registerDevice(tx, actor, body, time) {
    publicKey(body.publicKey);
    const previous = await tx.get(devKey(actor));
    need(!previous || previous.publicKey === body.publicKey, 409, 'DEVICE_KEY_MISMATCH');
    if (!previous) await tx.put(devKey(actor), { kind: 'DEVICE', deviceId: actor, publicKey: body.publicKey,
      registrationId: randomUUID(), createdAt: time, pairIds: [], connectionIds: [], activeRideId: null });
    return { deviceId: actor };
  }
  async function registerPair(tx, actor, body, time) {
    const t = body.transcript;
    need(t && Object.keys(t).sort().join(',') === 'createdAt,deviceIdA,deviceIdB,expiresAt,nonce,pairId,protocolVersion,publicKeyA,publicKeyB' && t.protocolVersion === 1, 400, 'INVALID_PAIRING_TRANSCRIPT');
    id(t.pairId, 'pair_id'); id(t.deviceIdA); id(t.deviceIdB); id(t.nonce, 'nonce');
    need(t.deviceIdA < t.deviceIdB && [t.deviceIdA, t.deviceIdB].includes(actor), 403, 'PAIR_NOT_AUTHORIZED');
    const a = await device(tx, t.deviceIdA), b = await device(tx, t.deviceIdB);
    need(a.publicKey === t.publicKeyA && b.publicKey === t.publicKeyB, 403, 'PAIR_KEY_MISMATCH');
    const canonical = pairingTranscript(t);
    need(signatureValid(a.publicKey, canonical, body.signatureA) && signatureValid(b.publicKey, canonical, body.signatureB), 403, 'INVALID_PAIR_SIGNATURE');
    need(!(await tx.get(`REVOKED_PAIR#${t.pairId}`)), 403, 'PAIR_REVOKED');
    for (const key of [a.publicKey, b.publicKey]) {
      need(!(await tx.get(`REVOKED_PAIR#${t.pairId}#${hash(base64(key))}`)), 403, 'PAIR_REVOKED');
    }
    const existing = await tx.get(pairKey(t.pairId));
    if (existing) {
      need(existing.proofHash === hash(canonical), 409, 'PAIR_ID_CONFLICT'); return { pairId: t.pairId, status: 'VERIFIED' };
    }
    need(Number.isSafeInteger(t.createdAt) && Number.isSafeInteger(t.expiresAt) && t.createdAt <= time + MINUTE && t.expiresAt > time && t.expiresAt > t.createdAt && t.expiresAt - t.createdAt <= 5 * MINUTE, 400, 'PAIR_PROOF_EXPIRED');
    need(a.pairIds.length < 20 && b.pairIds.length < 20, 409, 'PAIR_LIMIT');
    // Pairing the same two keys again is idempotent only for the original pairId.
    for (const pid of a.pairIds) { const p = await tx.get(pairKey(pid)); need(!p?.members.includes(b.deviceId), 409, 'ALREADY_PAIRED'); }
    await tx.put(pairKey(t.pairId), { kind: 'PAIR', pairId: t.pairId, members: [a.deviceId, b.deviceId],
      transcript: t, signatures: [body.signatureA, body.signatureB], proofHash: hash(canonical), createdAt: time, activeRideId: null });
    for (const d of [a, b]) { d.pairIds.push(t.pairId); await tx.put(d.pk, d); }
    return { pairId: t.pairId, status: 'VERIFIED' };
  }
  async function startRide(tx, actor, body, time) {
    id(body.requestId, 'request_id'); id(body.pairId, 'pair_id');
    const requestKey = `REQUEST#${actor}#START#${body.requestId}`, requestHash = hash(JSON.stringify(body));
    const previous = await tx.get(requestKey);
    if (previous) { need(previous.requestHash === requestHash, 409, 'IDEMPOTENCY_CONFLICT'); return previous.response; }
    const p = await pair(tx, body.pairId, actor), d = await device(tx, actor);
    need(!d.activeRideId, 409, 'BUSY');
    let r = p.activeRideId ? await tx.get(rideKey(p.activeRideId)) : null;
    if (r && r.state !== 'ENDED') {
      need(r.state === 'LOCAL_PENDING' && !r.localEstablishedBy.includes(actor), 409, 'BUSY');
      await claim(tx, actor, r.rideId);
    } else {
      const rideId = `ride_${randomUUID()}`;
      // Decimal opaque IDs fit the GME room-ID string contract and expose no pairing identifiers.
      const roomId = (randomBytes(8).readBigUInt64BE() & 0x7fffffffffffffffn).toString();
      r = { kind: 'RIDE', rideId, pairId: p.pairId, members: p.members, state: 'LOCAL_PENDING', roomId,
        revision: 1, createdAt: time, updatedAt: time, initiator: actor,
        lastHeartbeatAt: Object.fromEntries(p.members.map(m => [m, time])), localEstablishedBy: [], everLocalActive: false,
        cloudReason: null, cloudStartedAt: null, cloudUsedMs: 0, cloudAllowanceMs: CLOUD_MS, cloudWarningSent: false, cloudJoined: [] };
      await claim(tx, actor, rideId); p.activeRideId = rideId; await tx.put(p.pk, p); await saveRide(tx, r, time);
    }
    await tx.put(requestKey, { kind: 'REQUEST', requestHash, response: r, ttl: ttl(time, 7 * 24 * 60 * MINUTE) });
    return r;
  }

  async function rideCommand(tx, actor, rideId, action, body, time) {
    const r = await authorizedRide(tx, rideId, actor);
    if (action === 'get') return r;
    if (action === 'end') { await endRide(tx, r, 'USER_ENDED', time); return r; }
    live(r);
    if (action === 'local-probe' || action === 'local-switch') {
      const changed = action === 'local-probe' ? localProbe(r, actor, body, time, need)
        : localSwitchCommand(r, actor, body, time, need, cloudUsed);
      await saveRide(tx, r, time);
      if (changed) await event(tx, 'ride.local.switch', r, {}, r.members, time);
      return r;
    }
    if (action === 'local-established') {
      need(['LOCAL_PENDING', 'LOCAL_ACTIVE'].includes(r.state), 409, 'INVALID_RIDE_STATE');
      await claim(tx, actor, rideId);
      if (!r.localEstablishedBy.includes(actor)) {
        r.localEstablishedBy.push(actor); r.revision++;
        if (r.localEstablishedBy.length === 2) { r.state = 'LOCAL_ACTIVE'; r.everLocalActive = true; }
        await saveRide(tx, r, time);
      }
      return r;
    }
    if (action === 'invite') {
      if (r.state === 'INVITE_PENDING') {
        const i = await tx.get(`INVITE#${r.inviteId}`);
        need(i.fromDeviceId === actor, 409, 'INVITE_ALREADY_PENDING'); return { ride: r, invitation: i };
      }
      need(r.state === 'LOCAL_PENDING' && !r.everLocalActive, 409, 'INVALID_RIDE_STATE');
      const peerId = r.members.find(m => m !== actor), peer = await device(tx, peerId);
      need(!peer.activeRideId || peer.activeRideId === rideId, 409, 'PEER_BUSY');
      need(await isOnline(tx, peer, time), 409, 'PEER_OFFLINE');
      await claim(tx, actor, rideId);
      const inviteId = `invite_${randomUUID()}`;
      const invitation = { kind: 'INVITATION', inviteId, rideId, fromDeviceId: actor, toDeviceId: peerId,
        state: 'PENDING', createdAt: time, expiresAt: time + 30_000, ttl: ttl(time, 24 * 60 * MINUTE) };
      r.state = 'INVITE_PENDING'; r.inviteId = inviteId; r.inviteExpiresAt = invitation.expiresAt; r.revision++;
      await tx.put(`INVITE#${inviteId}`, invitation); await saveRide(tx, r, time);
      await event(tx, 'invite.created', r, { invitation }, [peerId], time);
      return { ride: r, invitation };
    }
    if (action === 'invite/respond') {
      need(['ACCEPT', 'DECLINE', 'BUSY'].includes(body.response), 400, 'INVALID_INVITE_RESPONSE');
      id(body.inviteId, 'invite_id');
      const i = await tx.get(`INVITE#${body.inviteId}`);
      need(i && i.rideId === rideId && i.toDeviceId === actor && r.inviteId === i.inviteId, 403, 'INVITE_NOT_AUTHORIZED');
      if (i.state === 'ACCEPTED' && body.response === 'ACCEPT') return r;
      need(r.state === 'INVITE_PENDING' && i.state === 'PENDING' && i.expiresAt > time, 409, 'INVITE_EXPIRED');
      const respondingDevice = await device(tx, actor);
      const busy = body.response === 'BUSY' || (respondingDevice.activeRideId && respondingDevice.activeRideId !== rideId);
      if (body.response !== 'ACCEPT' || busy) {
        i.state = busy ? 'BUSY' : 'DECLINED'; await tx.put(i.pk, i); r.revision++;
        await event(tx, 'invite.declined', r, { inviteId: i.inviteId, reason: i.state }, r.members, time);
        await endRide(tx, r, busy ? 'PEER_BUSY' : 'INVITE_DECLINED', time); return r;
      }
      await claim(tx, actor, rideId);
      i.state = 'ACCEPTED'; await tx.put(i.pk, i);
      r.state = 'CLOUD_PENDING'; r.cloudReason = 'INVITE_ACCEPTED'; r.cloudStartedAt = time; r.revision++;
      await saveRide(tx, r, time);
      await event(tx, 'invite.accepted', r, { inviteId: i.inviteId }, r.members, time);
      await event(tx, 'ride.cloud.ready', r, { roomId: r.roomId, reason: r.cloudReason }, r.members, time); return r;
    }
    if (action === 'fallback') {
      need(body.reason === 'TRANSPORT_FAILURE', 400, 'INVALID_FALLBACK_REASON');
      if (['CLOUD_PENDING', 'CLOUD_ACTIVE'].includes(r.state) && r.cloudReason === 'LOCAL_FAILURE') return r;
      need(r.state === 'LOCAL_ACTIVE' && r.everLocalActive, 409, 'LOCAL_NEVER_ESTABLISHED');
      r.state = 'CLOUD_PENDING'; r.cloudReason = 'LOCAL_FAILURE'; r.cloudStartedAt = time; r.revision++;
      await saveRide(tx, r, time);
      await event(tx, 'ride.cloud.ready', r, { roomId: r.roomId, reason: r.cloudReason }, r.members, time); return r;
    }
    if (action === 'ticket') {
      need(r.state !== 'CLOUD_PAUSED', 409, 'CLOUD_LIMIT');
      need(['CLOUD_PENDING', 'CLOUD_ACTIVE'].includes(r.state) && r.cloudReason, 409, 'CLOUD_NOT_APPROVED');
      const d = await device(tx, actor);
      const peer = await device(tx, r.members.find(m => m !== actor));
      // The secret and authBuffer exist only in this invocation, never in a DynamoDB item.
      return issueTicket({ rideId, roomId: r.roomId, openId: openIdFor(d), peerOpenId: openIdFor(peer), revision: r.revision });
    }
    if (action === 'cloud-status') {
      need(['CLOUD_PENDING', 'CLOUD_ACTIVE'].includes(r.state), 409, 'INVALID_RIDE_STATE');
      need(['CONNECTED', 'FAILED'].includes(body.status), 400, 'INVALID_CLOUD_STATUS');
      if (body.status === 'FAILED') {
        await event(tx, 'ride.cloud.failed', r, { reason: 'MEDIA_FAILED' }, r.members, time);
        await endRide(tx, r, 'CLOUD_FAILED', time);
      } else if (!r.cloudJoined.includes(actor)) {
        r.cloudJoined.push(actor); r.revision++; if (r.cloudJoined.length === 2) r.state = 'CLOUD_ACTIVE';
        await saveRide(tx, r, time);
        if(r.state==='CLOUD_ACTIVE') await event(tx,'ride.cloud.active',r,{},r.members,time);
      }
      return r;
    }
    if (action === 'extend') {
      need(r.state === 'CLOUD_PAUSED', 409, 'CLOUD_NOT_PAUSED');
      r.cloudAllowanceMs += CLOUD_MS; r.cloudStartedAt = time; r.cloudWarningSent = false;
      r.state = 'CLOUD_PENDING'; r.cloudJoined = []; r.revision++;
      await saveRide(tx, r, time);
      await event(tx, 'ride.cloud.ready', r, { roomId: r.roomId, reason: 'MANUAL_EXTENSION' }, r.members, time); return r;
    }
    throw new ApiError(404, 'NOT_FOUND');
  }

  async function http(input) {
    try {
      const time = now(), method = input.requestContext?.http?.method, path = input.rawPath;
      need(['POST', 'GET'].includes(method) && typeof path === 'string' && !input.rawQueryString, 400, 'INVALID_REQUEST');
      const raw = input.isBase64Encoded ? Buffer.from(input.body ?? '', 'base64').toString('utf8') : input.body ?? '';
      need(Buffer.byteLength(raw) <= 32_768, 413, 'BODY_TOO_LARGE');
      let body;
      try { body = raw ? JSON.parse(raw) : {}; } catch { throw new ApiError(400, 'INVALID_JSON'); }
      need(body && typeof body === 'object' && !Array.isArray(body), 400, 'INVALID_JSON');
      const authentication = await authenticate(input, method, path, raw, body, time);
      const { actor } = authentication;
      const match = /^\/v1\/rides\/([a-zA-Z0-9_-]{1,128})(?:\/(invite\/respond|[a-z-]+))?$/.exec(path);
      const rideId = match?.[1] ?? (path === '/v1/heartbeat' ? body.rideId : undefined);
      if (rideId) { id(rideId, 'ride_id'); await refreshRide(rideId, time); }
      if (path === '/v1/rides/start' || path === '/v1/state') {
        const d = await store.get(devKey(actor));
        if (d?.activeRideId) await refreshRide(d.activeRideId, time);
        if (body.pairId) {
          const p = await store.get(pairKey(id(body.pairId, 'pair_id')));
          if (p?.members.includes(actor) && p.activeRideId) await refreshRide(p.activeRideId, time);
        }
      }
      const result = await transact(store, async tx => {
        // Validate the same identity incarnation on every retry, and condition-check
        // it at commit. A previously verified signature cannot cross a re-register.
        if (authentication.registrationId !== null) {
          const current = await device(tx, actor);
          need(current.registrationId === authentication.registrationId && current.publicKey === authentication.publicKey, 401, 'DEVICE_IDENTITY_CHANGED');
        }
        if (method === 'POST' && path === '/v1/devices/register') return registerDevice(tx, actor, body, time);
        const d = await device(tx, actor);
        if (method === 'POST' && path === '/v1/pairs/register') return registerPair(tx, actor, body, time);
        if (method === 'POST' && path === '/v1/rides/start') return startRide(tx, actor, body, time);
        if (method === 'GET' && path === '/v1/state') {
          const peers = [], invitations = [];
          for (const pairId of d.pairIds) {
            const p = await pair(tx, pairId, actor), peerId = p.members.find(m => m !== actor);
            const peer = await device(tx, peerId);
            peers.push({ pairId, deviceId: peerId, online: await isOnline(tx, peer, time) });
            const r = p.activeRideId ? await tx.get(rideKey(p.activeRideId)) : null;
            if (r?.state === 'INVITE_PENDING' && r.inviteExpiresAt > time) {
              const i = await tx.get(`INVITE#${r.inviteId}`);
              if (i?.toDeviceId === actor) invitations.push(i);
            }
          }
          return { activeRide: d.activeRideId ? await authorizedRide(tx, d.activeRideId, actor) : null, invitations, peers };
        }
        if (method === 'POST' && path === '/v1/ws-token') {
          const token = randomBytes(32).toString('base64url');
          await tx.put(`WSTOKEN#${hash(token)}`, { kind: 'WSTOKEN', deviceId: actor, registrationId: d.registrationId, expiresAt: time + MINUTE, ttl: ttl(time, MINUTE) });
          return { token, expiresAt: time + MINUTE };
        }
        if (method === 'POST' && path === '/v1/heartbeat') {
          const wasOnline = await isOnline(tx, d, time);
          const validConnections = [];
          for (const connectionId of d.connectionIds) {
            const c = await tx.get(`CONN#${connectionId}`);
            if (c && c.deviceId === actor && c.registrationId === d.registrationId) {
              c.lastHeartbeatAt = time; c.indexAt = time + PRESENCE_MS; c.ttl = ttl(time, OFFLINE_MS);
              await tx.put(c.pk, c); validConnections.push(connectionId);
            }
          }
          if (validConnections.length !== d.connectionIds.length) {
            d.connectionIds = validConnections; await tx.put(d.pk, d);
          }
          if (!wasOnline && validConnections.length) await presenceEvent(tx, d, true, time);
          if (body.rideId) {
            const r = await authorizedRide(tx, body.rideId, actor); live(r);
            r.lastHeartbeatAt[actor] = time; await saveRide(tx, r, time);
          }
          return { serverTime: time, connectionAlive: validConnections.length > 0 };
        }
        if (match && ((method === 'GET' && !match[2]) || (method === 'POST' && match[2]))) {
          return rideCommand(tx, actor, match[1], match[2] ?? 'get', body, time);
        }
        const revoke = /^\/v1\/pairs\/([a-zA-Z0-9_-]{1,128})\/revoke$/.exec(path);
        if (method === 'POST' && revoke) {
          const p = await tx.get(pairKey(revoke[1]));
          if (!p) {
            // Withdrawing an in-flight local proof must win even if the peer
            // submits its completed dual proof later. Bind this tombstone to the
            // requester's key so unrelated devices cannot veto another pair.
            await tx.put(`REVOKED_PAIR#${revoke[1]}#${hash(base64(d.publicKey))}`, { kind: 'REVOKED_PAIR', ttl: ttl(time, OFFLINE_MS) });
            return { pairId: revoke[1], status: 'REVOKED' };
          }
          need(p.members.includes(actor), 403, 'PAIR_NOT_AUTHORIZED');
          if (p.activeRideId) { const r = await tx.get(rideKey(p.activeRideId)); if (r) await endRide(tx, r, 'PAIR_REVOKED', time); }
          await tx.delete(p.pk);
          // A still-valid old dual signature cannot undo an explicit unpair.
          // This short tombstone contains no device IDs, public keys or signatures.
          await tx.put(`REVOKED_PAIR#${p.pairId}`, { kind: 'REVOKED_PAIR', ttl: ttl(time, OFFLINE_MS) });
          for (const member of p.members) {
            const m = await device(tx, member); m.pairIds = m.pairIds.filter(v => v !== p.pairId);
            if (m.pairIds.length) await tx.put(m.pk, m);
            else {
              for (const cid of m.connectionIds) await tx.delete(`CONN#${cid}`);
              await tx.delete(m.pk);
            }
          }
          return { pairId: p.pairId, status: 'REVOKED' };
        }
        throw new ApiError(404, 'NOT_FOUND');
      });
      return reply(200, result);
    } catch (error) { return failure(error); }
  }

  async function websocket(input) {
    try {
      const time = now(), route = input.requestContext?.routeKey, cid = input.requestContext?.connectionId;
      need(typeof cid === 'string' && cid.length <= 128, 400, 'INVALID_CONNECTION');
      if (route === '$connect') {
        const token = input.queryStringParameters?.token;
        need(typeof token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(token), 401, 'INVALID_WS_TOKEN');
        await transact(store, async tx => {
          const t = await tx.get(`WSTOKEN#${hash(token)}`);
          need(t && t.expiresAt > time, 401, 'WS_TOKEN_EXPIRED');
          const d = await device(tx, t.deviceId);
          need(t.registrationId === d.registrationId, 401, 'WS_TOKEN_REVOKED');
          const liveConnections = [];
          for (const priorId of d.connectionIds) {
            const c = await tx.get(`CONN#${priorId}`);
            if (c && c.registrationId === d.registrationId && time - c.lastHeartbeatAt < PRESENCE_MS) liveConnections.push(priorId);
            else await tx.delete(`CONN#${priorId}`);
          }
          d.connectionIds = liveConnections;
          need(d.connectionIds.length < 3 || d.connectionIds.includes(cid), 429, 'CONNECTION_LIMIT');
          await tx.delete(t.pk);
          if (!d.connectionIds.includes(cid)) d.connectionIds.push(cid);
          await tx.put(`CONN#${cid}`, { kind: 'CONNECTION', connectionId: cid, deviceId: d.deviceId, registrationId: d.registrationId,
            lastHeartbeatAt: time, indexPk: 'LIVE_CONNECTIONS', indexAt: time + PRESENCE_MS, ttl: ttl(time, OFFLINE_MS) });
          await tx.put(d.pk, d); await presenceEvent(tx, d, true, time);
        });
      } else if (route === '$disconnect') {
        await transact(store, async tx => {
          const c = await tx.get(`CONN#${cid}`); if (!c) return;
          await tx.delete(c.pk); const d = await tx.get(devKey(c.deviceId)); if (!d) return;
          d.connectionIds = d.connectionIds.filter(v => v !== cid); await tx.put(d.pk, d);
          if (!(await isOnline(tx, d, time))) await presenceEvent(tx, d, false, time);
        });
      } else throw new ApiError(400, 'HTTP_COMMANDS_ONLY');
      return reply(200, { ok: true });
    } catch (error) { return failure(error); }
  }
  async function sweep() {
    const time = now();
    for (const r of await store.query('ACTIVE_RIDES', time)) await refreshRide(r.rideId, time);
    for (const c of await store.query('LIVE_CONNECTIONS', time)) {
      await transact(store, async tx => {
        const current = await tx.get(c.pk);
        if (!current || time - current.lastHeartbeatAt < PRESENCE_MS) return;
        await tx.delete(c.pk); const d = await tx.get(devKey(current.deviceId)); if (!d) return;
        d.connectionIds = d.connectionIds.filter(v => v !== current.connectionId); await tx.put(d.pk, d);
        if (!(await isOnline(tx, d, time))) await presenceEvent(tx, d, false, time);
      });
    }
  }
  return { http, websocket, sweep };
}
