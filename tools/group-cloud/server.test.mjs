import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import http from 'node:http';
import { createGroupCloudServer, isLoopbackHost } from './server.mjs';
import { GroupCloudStore, MAX_EVENT_WINDOW } from './room-store.mjs';
import { createVolcAccessToken, mintRtcCredentials } from './volc-rtc.mjs';
import { createRtcAdmin, signVolcOpenApiRequest } from './volc-openapi.mjs';

const APP_ID = 'test-app-id-1234567890ab';
const APP_KEY = 'test-app-key-never-sent-to-clients';
const FETCH_BLOCKED_PORTS = new Set([
  1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95,
  101, 102, 103, 104, 109, 110, 111, 113, 115, 117, 119, 123, 135, 137, 139, 143, 161, 179,
  389, 427, 465, 512, 513, 514, 515, 526, 530, 531, 532, 540, 548, 554, 556, 563, 587, 601,
  636, 989, 990, 993, 995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061, 6000, 6566,
  6665, 6666, 6667, 6668, 6669, 6679, 6697, 10080
]);

async function listenOnFetchSafePort(server) {
  while (true) {
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    if (!FETCH_BLOCKED_PORTS.has(server.address().port)) return;
    await new Promise((resolve) => server.close(resolve));
  }
}

async function startTestServer(options = {}) {
  const logs = [];
  const bannedUsers = [];
  const bannedRooms = [];
  const unbannedUsers = [];
  const bannedRoomUntil = new Map();
  const bannedUserUntil = new Map();
  const faults = {
    banUser: null,
    banRoom: null,
    unbanUser: null
  };
  let nowMs = options.startMs ?? 1_750_000_000_000;
  const store = new GroupCloudStore({
    now: () => nowMs,
    appId: APP_ID,
    appKey: APP_KEY,
    maxMembers: options.maxMembers,
    disconnectTimeoutMs: options.disconnectTimeoutMs ?? 15_000,
    reservationMs: options.reservationMs ?? 60_000,
    inviteTtlMs: options.inviteTtlMs ?? 600_000,
    requestTtlMs: options.requestTtlMs ?? 120_000,
    sessionTtlMs: options.sessionTtlMs,
    localStableResetMs: options.localStableResetMs,
    localSignalStableMs: options.localSignalStableMs,
    localPreconnectTimeoutMs: options.localPreconnectTimeoutMs,
    onRtcBanUser: async (roomId, userId, details) => {
      if (faults.banUser) {
        const err = faults.banUser;
        faults.banUser = null;
        throw err;
      }
      const sec = details?.forbiddenIntervalSec ?? 300;
      bannedUsers.push({ roomId, userId, reason: details?.reason ?? '', forbiddenIntervalSec: sec });
      bannedUserUntil.set(`${roomId}\0${userId}`, nowMs + sec * 1000);
    },
    onRtcBanRoom: async (roomId, details) => {
      if (typeof faults.banRoom === 'function') {
        const err = await faults.banRoom(roomId, details);
        if (err) throw err;
      } else if (faults.banRoom) {
        const err = faults.banRoom;
        faults.banRoom = null;
        throw err;
      }
      const sec = details?.forbiddenIntervalSec ?? 3600;
      bannedRooms.push({ roomId, reason: details?.reason ?? '', forbiddenIntervalSec: sec });
      bannedRoomUntil.set(roomId, nowMs + sec * 1000);
    },
    onRtcUnbanUser: async (roomId, userId, details) => {
      if (faults.unbanUser) {
        const err = faults.unbanUser;
        faults.unbanUser = null;
        throw err;
      }
      unbannedUsers.push({ roomId, userId, reason: details?.reason ?? '' });
      bannedUserUntil.delete(`${roomId}\0${userId}`);
    },
    log: (entry) => logs.push(JSON.stringify(entry))
  });
  const { server } = createGroupCloudServer({ store, sweepIntervalMs: 0, log: (entry) => logs.push(JSON.stringify(entry)) });
  await listenOnFetchSafePort(server);
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  async function api(method, pathname, { token = null, body = undefined } = {}) {
    const headers = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(`${baseUrl}${pathname}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined
    });
    const json = await response.json();
    return { status: response.status, json };
  }

  return {
    baseUrl,
    store,
    logs,
    bannedUsers,
    bannedRooms,
    unbannedUsers,
    faults,
    canJoinRtc: (rtc) => {
      if (!rtc || rtc.expiresAtMs <= nowMs) return false;
      const roomBan = bannedRoomUntil.get(rtc.rtcRoomId) ?? 0;
      if (roomBan > nowMs) return false;
      const userBan = bannedUserUntil.get(`${rtc.rtcRoomId}\0${rtc.rtcUserId}`) ?? 0;
      if (userBan > nowMs) return false;
      return true;
    },
    api,
    advance: (deltaMs) => {
      nowMs += deltaMs;
      return nowMs;
    },
    getNow: () => nowMs,
    close: () => new Promise((resolve) => server.close(resolve))
  };
}

function decodeToken(token) {
  assert.equal(token.slice(0, 3), '001');
  assert.equal(token.slice(3, 3 + APP_ID.length), APP_ID);
  const content = Buffer.from(token.slice(3 + APP_ID.length), 'base64');
  const messageLength = content.readUInt16LE(0);
  const message = content.subarray(2, 2 + messageLength);
  const signatureLength = content.readUInt16LE(2 + messageLength);
  const signature = content.subarray(4 + messageLength, 4 + messageLength + signatureLength);
  return { message, signature };
}

test('formal v001 token uses the documented binary layout and HMAC-SHA256 signature', () => {
  const issuedAtSec = 1_750_000_000;
  const expiresAtSec = issuedAtSec + 600;
  const token = createVolcAccessToken({
    appId: APP_ID,
    appKey: APP_KEY,
    roomId: 'mcr-room',
    userId: 'dev-a',
    issuedAtSec,
    expiresAtSec,
    nonce: 0x12345678
  });
  const { message, signature } = decodeToken(token);
  assert.equal(message.readUInt32LE(0), 0x12345678);
  assert.equal(message.readUInt32LE(4), issuedAtSec);
  assert.equal(message.readUInt32LE(8), expiresAtSec);
  let offset = 12;
  const roomLength = message.readUInt16LE(offset);
  offset += 2;
  assert.equal(message.subarray(offset, offset + roomLength).toString(), 'mcr-room');
  offset += roomLength;
  const userLength = message.readUInt16LE(offset);
  offset += 2;
  assert.equal(message.subarray(offset, offset + userLength).toString(), 'dev-a');
  offset += userLength;
  const privilegeCount = message.readUInt16LE(offset);
  assert.equal(privilegeCount, 5);
  assert.equal(signature.toString('hex'), crypto.createHmac('sha256', APP_KEY).update(message).digest('hex'));
  assert.equal(JSON.stringify(token).includes(APP_KEY), false);
  assert.equal(mintRtcCredentials({
    appId: APP_ID,
    appKey: APP_KEY,
    rtcRoomId: 'mcr-room',
    rtcUserId: 'dev-a',
    nowMs: 1_750_000_000_500,
    ttlMs: 600_000
  }).toString().includes(APP_KEY), false);
});

test('RTC admin signing includes host, body hash and date without exposing secrets', () => {
  const signed = signVolcOpenApiRequest({
    accessKeyId: 'ak-test',
    secretKey: 'sk-test',
    action: 'BanRoomUser',
    version: '2023-11-01',
    body: { AppId: APP_ID, RoomId: 'mcr-room', UserId: 'dev-a', ForbiddenInterval: 300 },
    date: new Date('2026-11-22T10:20:30.000Z')
  });
  assert.match(signed.url, /Action=BanRoomUser/);
  assert.equal(signed.headers['X-Date'], '20261122T102030Z');
  assert.match(signed.headers['X-Content-Sha256'], /^[0-9a-f]{64}$/);
  assert.match(signed.headers.Authorization, /SignedHeaders=host;x-content-sha256;x-date/);
  assert.equal(signed.headers.Authorization.includes('sk-test'), false);
});

test('RTC admin rejects malformed, empty, or unconfirmed 200 responses', async () => {
  const cases = [
    new Response('', { status: 200 }),
    new Response('{"ResponseMetadata":', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    Response.json({}, { status: 200 }),
    Response.json({ ResponseMetadata: {} }, { status: 200 }),
    Response.json({ ResponseMetadata: {}, Result: null }),
    Response.json({ ResponseMetadata: {}, Result: {} }),
    Response.json({ ResponseMetadata: {}, Result: { Message: '' } }),
    Response.json({ ResponseMetadata: {}, Result: { Message: 'User is not found.' } }),
    Response.json({ ResponseMetadata: {}, Result: { Message: 'User is not banned.' } }),
    Response.json({ ResponseMetadata: {}, Result: { Message: 'Room has been banned.' } })
  ];
  for (const response of cases) {
    const admin = createRtcAdmin({
      appId: APP_ID,
      accessKeyId: 'test-access-id',
      secretKey: 'test-secret-value',
      fetchImpl: async () => response
    });
    await assert.rejects(
      () => admin.banUser('room-1', 'user-1', 300),
      /invalid JSON|ResponseMetadata|valid Result/
    );
  }
});

test('RTC admin accepts documented confirmations matching the action and target', async () => {
  for (const [message, invoke] of [
    ['success', admin => admin.banUser('room', 'user', 300)],
    ['Room has been banned.', admin => admin.banRoom('room', 300)],
    ['User has been banned.', admin => admin.banUser('room', 'user', 300)],
    ['User is not banned.', admin => admin.unbanUser('room', 'user')]
  ]) {
    const admin = createRtcAdmin({
      appId: APP_ID, accessKeyId: 'test-id', secretKey: 'test-secret',
      fetchImpl: async () => Response.json({ ResponseMetadata: {}, Result: { Message: message } })
    });
    assert.equal((await invoke(admin)).message, message);
  }
});

test('network room is RTC-only, reports 50 visible seats, and no longer exposes TURN or signaling relays', async () => {
  const env = await startTestServer();
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: true }
    });
    assert.equal(created.status, 201);
    assert.equal(created.json.rtc.appId, APP_ID);
    assert.equal(created.json.rtc.rtcUserId, created.json.snapshot.members[0].rtcUserId);
    assert.ok(created.json.rtc.expiresAtMs > env.getNow());
    assert.equal(created.json.turn, undefined);
    assert.equal(created.json.snapshot.maxMembers, 50);
    assert.equal(created.json.snapshot.links, undefined);
    assert.equal(created.json.snapshot.hostDeviceId, null);
    assert.equal(created.json.snapshot.rtcRoomId, created.json.rtc.rtcRoomId);
    assert.match(created.json.snapshot.invite.code, /^\d{6}$/);

    const legacyAlpha = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: 'AB23CD45', deviceId: 'dev-bad', runtimeId: 'run-bad', nickname: 'Bad' }
    });
    assert.equal(legacyAlpha.status, 400);
    assert.equal(legacyAlpha.json.code, 'INVALID_INVITE');

    const nonDigit = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: '12345A', deviceId: 'dev-bad', runtimeId: 'run-bad', nickname: 'Bad' }
    });
    assert.equal(nonDigit.status, 400);
    assert.equal(nonDigit.json.code, 'INVALID_INVITE');

    const health = await env.api('GET', '/healthz');
    assert.deepEqual(health.json, {
      ok: true,
      mode: 'NETWORK',
      maxMembers: 50,
      audioRoutingRequired: true,
      openApiRevocationEnforced: true
    });

    for (const pathname of [
      `/v1/rooms/${created.json.roomId}/signals`,
      `/v1/rooms/${created.json.roomId}/turn-credentials`,
      `/v1/rooms/${created.json.roomId}/confirm-link`,
      `/v1/rooms/${created.json.roomId}/restart-link`
    ]) {
      const response = await env.api('POST', pathname, {
        token: created.json.sessionToken,
        body: {}
      });
      assert.equal(response.status, 404);
    }
  } finally {
    await env.close();
  }
});

test('approval queue issues distinct RTC identities and refreshes the formal token', async () => {
  const env = await startTestServer();
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: true }
    });
    const { roomId, sessionToken: ownerToken, snapshot } = created.json;
    const inviteCode = snapshot.invite.code;
    assert.match(inviteCode, /^\d{6}$/);
    const formattedInvite = `${inviteCode.slice(0, 3)}-${inviteCode.slice(3)}`;
    const submitted = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: formattedInvite, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    assert.equal(submitted.status, 202);
    const approved = await env.api('POST', `/v1/rooms/${roomId}/join-requests/${submitted.json.requestId}/approve`, {
      token: ownerToken,
      body: { expectedRevision: snapshot.revision + 1 }
    });
    assert.equal(approved.status, 200);
    const polled = await env.api('GET', `/v1/join-requests/${submitted.json.requestId}?waitMs=0`, {
      token: submitted.json.requestSecret
    });
    assert.equal(polled.status, 200);
    assert.equal(polled.json.status, 'ADMITTED');
    assert.equal(polled.json.rtc.rtcUserId, approved.json.snapshot.members.find((m) => m.deviceId === 'dev-peer').rtcUserId);
    assert.equal(polled.json.rtc.rtcRoomId, created.json.rtc.rtcRoomId);
    assert.notEqual(polled.json.rtc.rtcToken, created.json.rtc.rtcToken);

    env.advance(1_000);
    const refreshed = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: polled.json.sessionToken,
      body: {}
    });
    assert.equal(refreshed.status, 200);
    assert.equal(refreshed.json.rtc.rtcUserId, polled.json.rtc.rtcUserId);
    assert.ok(refreshed.json.rtc.expiresAtMs > env.getNow());
    assert.notEqual(refreshed.json.rtc.rtcToken, polled.json.rtc.rtcToken);
  } finally {
    await env.close();
  }
});

test('cancelling a join releases the seat and RTC grant in either approval order', async (t) => {
  for (const approveFirst of [false, true]) {
    await t.test(approveFirst ? 'approval wins the room lock' : 'cancel wins the room lock', async () => {
      const env = await startTestServer();
      try {
        const { store } = env;
        const owner = await store.createRoom({ deviceId: 'owner', runtimeId: 'run-owner' });
        const peer = { inviteCode: owner.snapshot.invite.code, deviceId: 'peer', runtimeId: 'run-peer' };
        const pending = await store.submitJoinRequest(peer);
        const approve = (request, expectedRevision) => store.approveJoinRequest(
          owner.sessionToken, owner.roomId, request.requestId, { expectedRevision });
        const cancel = () => store.cancelJoinRequest(pending);
        let oldRtc;
        if (approveFirst) {
          await approve(pending, owner.snapshot.revision + 1);
          const grant = await store.pollJoinRequest(pending);
          oldRtc = grant.rtc;
          env.faults.banUser = new Error('RTC unavailable');
          await assert.rejects(cancel(), { code: 'RTC_REVOCATION_FAILED' });
          assert.equal((await store.pollJoinRequest(pending)).sessionToken, grant.sessionToken);
          assert.equal(env.canJoinRtc(grant.rtc), true);
          assert.equal((await cancel()).status, 'CANCELLED');
          assert.equal(env.canJoinRtc(grant.rtc), false);
          await assert.rejects(store.resumeMember(grant.sessionToken, owner.roomId), { code: 'UNAUTHORIZED' });
        } else {
          assert.equal((await cancel()).status, 'CANCELLED');
          await assert.rejects(approve(pending, owner.snapshot.revision + 2), { code: 'REQUEST_NOT_FOUND' });
          assert.equal(env.bannedUsers.length, 0);
        }
        const replacement = await store.submitJoinRequest({ ...peer, runtimeId: 'run-peer-again' });
        assert.equal(replacement.status, 'PENDING');
        const current = await store.resumeMember(owner.sessionToken, owner.roomId);
        await approve(replacement, current.snapshot.revision);
        const admitted = await store.pollJoinRequest(replacement);
        if (oldRtc) {
          assert.notEqual(admitted.rtc.rtcUserId, oldRtc.rtcUserId);
          assert.equal(env.canJoinRtc(oldRtc), false);
          assert.equal(env.canJoinRtc(admitted.rtc), true);
        }
        await cancel();
        assert.equal((await store.resumeMember(admitted.sessionToken, owner.roomId)).snapshot.members.length, 2);
      } finally {
        await env.close();
      }
    });
  }
});

test('ownership transfer waits for the joining client to confirm its seat', async () => {
  const env = await startTestServer();
  try {
    for (const requireApproval of [true, false]) {
      const { store } = env;
      const owner = await store.createRoom({ deviceId: 'owner', runtimeId: 'owner-run', requireApproval });
      const join = async (run) => {
        const requestSecret = crypto.randomBytes(32).toString('hex');
        const receipt = { requestSecret, requestId: 'j1-' + crypto.createHash('sha256').update(requestSecret).digest('hex') };
        const result = await store.submitJoinRequest({
          inviteCode: owner.snapshot.invite.code, deviceId: 'peer', runtimeId: run, joinSecret: requestSecret
        });
        if (result.status === 'ADMITTED') return { receipt, admission: result };
        const current = await store.resumeMember(owner.sessionToken, owner.roomId);
        await store.approveJoinRequest(owner.sessionToken, owner.roomId, result.requestId, { expectedRevision: current.snapshot.revision });
        return { receipt, admission: await store.pollJoinRequest(receipt) };
      };
      const transfer = (admission, alsoLeave) => store.transferOwner(owner.sessionToken, owner.roomId, {
        targetDeviceId: 'peer', expectedRevision: admission.snapshot.revision, alsoLeave
      });
      const first = await join('first');
      for (const alsoLeave of [false, true]) {
        await assert.rejects(transfer(first.admission, alsoLeave), { code: 'TARGET_NOT_ADMITTED' });
      }
      assert.equal((await store.cancelJoinRequest(first.receipt)).status, 'CANCELLED');
      assert.equal(env.canJoinRtc(first.admission.rtc), false);
      const remaining = await store.resumeMember(owner.sessionToken, owner.roomId);
      assert.equal(remaining.snapshot.ownerId, 'owner');
      assert.equal(remaining.snapshot.members.length, 1);
      const second = await join('second');
      await store.pollEvents(second.admission.sessionToken, owner.roomId);
      assert.equal((await transfer(second.admission, false)).snapshot.ownerId, 'peer');
    }
  } finally {
    await env.close();
  }
});

test('a client receipt cancels a delayed submission and survives a lost direct admission response', async () => {
  const env = await startTestServer();
  try {
    const owner = await env.store.createRoom({ deviceId: 'owner', runtimeId: 'owner-run', requireApproval: false });
    const peer = { deviceId: 'peer', runtimeId: 'peer-run', inviteCode: owner.snapshot.invite.code };
    const makeReceipt = () => {
      const requestSecret = crypto.randomBytes(32).toString('hex');
      return { requestSecret, requestId: 'j1-' + crypto.createHash('sha256').update(requestSecret).digest('hex') };
    };
    const early = makeReceipt();
    await env.store.cancelJoinRequest(early);
    await assert.rejects(env.store.submitJoinRequest({ ...peer, joinSecret: early.requestSecret }), { code: 'CANCELLED' });
    const late = makeReceipt();
    const body = { ...peer, joinSecret: late.requestSecret };
    const [first, repeat] = await Promise.all([env.store.submitJoinRequest(body), env.store.submitJoinRequest(body)]);
    assert.equal(first.sessionToken, repeat.sessionToken);
    await env.store.cancelJoinRequest(late);
    assert.equal(env.canJoinRtc(first.rtc), false);
    await assert.rejects(env.store.submitJoinRequest(body), { code: 'CANCELLED' });
    const fresh = await env.store.submitJoinRequest({ ...peer, joinSecret: makeReceipt().requestSecret });
    assert.equal(env.canJoinRtc(fresh.rtc), true);
    assert.equal(env.canJoinRtc(first.rtc), false);
  } finally {
    await env.close();
  }
});

test('hybrid peers receive usable local bootstrap parameters for the hosting device', async () => {
  const env = await startTestServer();
  try {
    for (const [capability, peerCapability] of [[false, true], [undefined, true], [undefined, undefined], [true, false], [true, true]]) {
      const owner = await env.store.createRoom({
        deviceId: 'host', runtimeId: 'run-host', mode: 'HYBRID', requireApproval: true, supportsFastP2p: capability
      });
      const pending = await env.store.submitJoinRequest({
        deviceId: 'peer', runtimeId: 'run-peer', inviteCode: owner.snapshot.invite.code, supportsFastP2p: peerCapability
      });
      await env.store.approveJoinRequest(owner.sessionToken, owner.roomId, pending.requestId, {
        expectedRevision: owner.snapshot.revision + 1
      });
      const { snapshot } = await env.store.pollJoinRequest(pending);
      assert.equal(snapshot.members.find((m) => m.deviceId === 'peer').supportsFastP2p, peerCapability === true);
      assert.equal(snapshot.transport.active, 'LOCAL');
      assert.ok(snapshot.transport.localCode);
      if (capability === true) {
        assert.ok(snapshot.transport.localP2pSsid);
        assert.ok(snapshot.transport.localP2pPassphrase);
      } else {
        assert.equal(snapshot.transport.localP2pSsid, null);
        assert.equal(snapshot.transport.localP2pPassphrase, null);
      }
    }
  } finally {
    await env.close();
  }
});

test('approved join result is idempotently retrievable until its retention window expires', async () => {
  const env = await startTestServer({ sessionTtlMs: 60_000 });
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: true }
    });
    const submitted = await env.api('POST', '/v1/rooms/join-requests', {
      body: {
        inviteCode: created.json.snapshot.invite.code,
        deviceId: 'dev-peer',
        runtimeId: 'run-peer',
        nickname: 'Peer'
      }
    });
    const approved = await env.api('POST', `/v1/rooms/${created.json.roomId}/join-requests/${submitted.json.requestId}/approve`, {
      token: created.json.sessionToken,
      body: { expectedRevision: created.json.snapshot.revision + 1 }
    });
    assert.equal(approved.status, 200);

    const first = await env.api('GET', `/v1/join-requests/${submitted.json.requestId}?waitMs=0`, {
      token: submitted.json.requestSecret
    });
    const retry = await env.api('GET', `/v1/join-requests/${submitted.json.requestId}?waitMs=0`, {
      token: submitted.json.requestSecret
    });
    assert.equal(first.status, 200);
    assert.equal(retry.status, 200);
    assert.equal(retry.json.status, 'ADMITTED');
    assert.equal(retry.json.sessionToken, first.json.sessionToken);

    env.advance(60_001);
    const expired = await env.api('GET', `/v1/join-requests/${submitted.json.requestId}?waitMs=0`, {
      token: submitted.json.requestSecret
    });
    assert.equal(expired.status, 404);
    assert.equal(expired.json.code, 'REQUEST_NOT_FOUND');
  } finally {
    await env.close();
  }
});

test('authenticated polling slides session expiry while an idle session still expires', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 7_300_000 });
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: false }
    });
    for (let elapsed = 10_000; elapsed <= 3_610_000; elapsed += 10_000) {
      env.advance(10_000);
      const poll = await env.api('GET', `/v1/rooms/${created.json.roomId}/events?after=0&waitMs=0`, {
        token: created.json.sessionToken
      });
      assert.equal(poll.status, 200);
    }

    env.advance(3_600_001);
    const expired = await env.api('GET', `/v1/rooms/${created.json.roomId}/events?after=0&waitMs=0`, {
      token: created.json.sessionToken
    });
    assert.equal(expired.status, 401);
    assert.equal(expired.json.code, 'UNAUTHORIZED');
  } finally {
    await env.close();
  }
});

test('ended rooms and terminal queues are released after notification retention', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 600_000 });
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: true }
    });
    const pending = await env.api('POST', '/v1/rooms/join-requests', {
      body: {
        inviteCode: created.json.snapshot.invite.code,
        deviceId: 'dev-peer',
        runtimeId: 'run-peer',
        nickname: 'Peer'
      }
    });
    const ended = await env.api('POST', `/v1/rooms/${created.json.roomId}/end`, {
      token: created.json.sessionToken,
      body: { expectedRevision: created.json.snapshot.revision + 1 }
    });
    assert.equal(ended.status, 200);
    const room = env.store.rooms.get(created.json.roomId);
    assert.equal(room.ended, true);
    assert.ok(room.mailboxes.size > 0);

    env.advance(300_001);
    await env.store.sweep();
    assert.equal(env.store.rooms.has(created.json.roomId), false);
    assert.equal(room.mailboxes.size, 0);
    assert.equal(env.store.pendingIndex.has(pending.json.requestId), false);
  } finally {
    await env.close();
  }
});

test('fifty visible members are admitted atomically and the fifty-first is rejected', async () => {
  const env = await startTestServer();
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-0', runtimeId: 'run-0', nickname: 'Owner', requireApproval: false }
    });
    const { roomId, snapshot } = created.json;
    const inviteCode = snapshot.invite.code;
    const tokens = new Set();
    for (let index = 1; index < 50; index += 1) {
      const joined = await env.api('POST', '/v1/rooms/join-requests', {
        body: {
          inviteCode,
          deviceId: `dev-${index}`,
          runtimeId: `run-${index}`,
          nickname: `Rider ${index}`
        }
      });
      assert.equal(joined.status, 200);
      assert.equal(joined.json.status, 'ADMITTED');
      assert.equal(joined.json.rtc.rtcUserId, joined.json.snapshot.members.find((m) => m.deviceId === `dev-${index}`).rtcUserId);
      tokens.add(joined.json.rtc.rtcToken);
    }
    assert.equal(tokens.size, 49);
    const full = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-50', runtimeId: 'run-50', nickname: 'Rider 50' }
    });
    assert.equal(full.status, 409);
    assert.equal(full.json.code, 'ROOM_FULL');
    const snapshotAfter = await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, {
      token: created.json.sessionToken
    });
    assert.equal(snapshotAfter.status, 200);
  } finally {
    await env.close();
  }
});

test('owner removal and disband invoke RTC ban hooks and invalidate bearer access', async () => {
  const env = await startTestServer();
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: true }
    });
    const { roomId, sessionToken: ownerToken, snapshot } = created.json;
    const submitted = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    const approved = await env.api('POST', `/v1/rooms/${roomId}/join-requests/${submitted.json.requestId}/approve`, {
      token: ownerToken,
      body: { expectedRevision: snapshot.revision + 1 }
    });
    const polled = await env.api('GET', `/v1/join-requests/${submitted.json.requestId}?waitMs=0`, {
      token: submitted.json.requestSecret
    });
    const peerRtc = polled.json.rtc;
    assert.equal(env.canJoinRtc(peerRtc), true);
    const removed = await env.api('POST', `/v1/rooms/${roomId}/members/dev-peer/remove`, {
      token: ownerToken,
      body: { expectedRevision: approved.json.snapshot.revision }
    });
    assert.equal(removed.status, 200);
    const banEntry = env.bannedUsers.at(-1);
    assert.equal(banEntry.roomId, created.json.rtc.rtcRoomId);
    assert.equal(banEntry.userId, peerRtc.rtcUserId);
    assert.equal(banEntry.reason, 'REMOVED');
    assert.ok(banEntry.forbiddenIntervalSec >= 600);
    assert.equal(env.canJoinRtc(peerRtc), false);
    const reclaimed = await env.api('GET', `/v1/join-requests/${submitted.json.requestId}?waitMs=0`, {
      token: submitted.json.requestSecret
    });
    assert.equal(reclaimed.json.status, 'REJECTED');
    assert.equal(reclaimed.json.code, 'REMOVED');
    assert.equal(reclaimed.json.sessionToken, undefined);

    const ended = await env.api('POST', `/v1/rooms/${roomId}/end`, {
      token: ownerToken,
      body: { expectedRevision: removed.json.snapshot.revision }
    });
    assert.equal(ended.status, 200);
    const roomBanEntry = env.bannedRooms.at(-1);
    assert.equal(roomBanEntry.roomId, created.json.rtc.rtcRoomId);
    assert.equal(roomBanEntry.reason, 'OWNER_ENDED');
    assert.ok(roomBanEntry.forbiddenIntervalSec >= 600);
    assert.equal(env.canJoinRtc(created.json.rtc), false);
  } finally {
    await env.close();
  }
});

test('owner leave requires transfer or disband and transfer+leave is atomic', async () => {
  const env = await startTestServer();
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: false }
    });
    const { roomId, sessionToken: ownerToken, snapshot } = created.json;
    const joined = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    const denied = await env.api('POST', `/v1/rooms/${roomId}/leave`, { token: ownerToken });
    assert.equal(denied.status, 409);
    assert.equal(denied.json.code, 'OWNER_TRANSFER_REQUIRED');
    const transfer = await env.api('POST', `/v1/rooms/${roomId}/owner-transfer`, {
      token: ownerToken,
      body: { targetDeviceId: 'dev-peer', alsoLeave: true, expectedRevision: snapshot.revision + 1 }
    });
    assert.equal(transfer.status, 200);
    assert.equal(transfer.json.left, true);
    assert.equal(env.bannedUsers.at(-1).userId, created.json.rtc.rtcUserId);
  } finally {
    await env.close();
  }
});

test('sixty-second reservation resumes with a fresh RTC token while owner expiry ends the room', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 10_000, reservationMs: 60_000 });
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: false }
    });
    const { roomId, sessionToken: ownerToken, snapshot } = created.json;
    const joined = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    const peerToken = joined.json.sessionToken;
    env.advance(11_000);
    await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: ownerToken });
    env.advance(6_000);
    await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: ownerToken });
    const resumed = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: peerToken });
    assert.equal(resumed.status, 200);
    assert.equal(resumed.json.resumed, true);
    assert.equal(resumed.json.rtc.rtcUserId, joined.json.rtc.rtcUserId);

    for (let step = 0; step < 10; step += 1) {
      env.advance(7_500);
      const poll = await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken });
      if (poll.status === 410) {
        assert.equal(poll.json.code, 'ROOM_ENDED');
        assert.equal(poll.json.endedReason, 'OWNER_RECOVERY_EXPIRED');
        assert.equal(env.bannedRooms.at(-1).reason, 'OWNER_RECOVERY_EXPIRED');
        return;
      }
    }
    assert.fail('owner reservation did not expire');
  } finally {
    await env.close();
  }
});

test('all owner management endpoints strictly require integer expectedRevision CAS', async () => {
  const env = await startTestServer();
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: 'Owner', requireApproval: true }
    });
    const { roomId, sessionToken: ownerToken, snapshot } = created.json;
    const submitted = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: snapshot.invite.code, deviceId: 'dev-2', runtimeId: 'run-2', nickname: 'Peer2' }
    });
    for (const badBody of [{}, { expectedRevision: '2' }, { expectedRevision: 0 }]) {
      for (const [pathname, token] of [
        [`/v1/rooms/${roomId}/join-requests/${submitted.json.requestId}/approve`, ownerToken],
        [`/v1/rooms/${roomId}/join-requests/${submitted.json.requestId}/reject`, ownerToken],
        [`/v1/rooms/${roomId}/invites`, ownerToken],
        [`/v1/rooms/${roomId}/approval-policy`, ownerToken],
        [`/v1/rooms/${roomId}/end`, ownerToken]
      ]) {
        const body = pathname.endsWith('approval-policy')
          ? { requireApproval: false, ...badBody }
          : badBody;
        const response = await env.api('POST', pathname, { token, body });
        assert.equal(response.status, 400);
        assert.equal(response.json.code, 'INVALID_REQUEST');
      }
    }
    const approved = await env.api('POST', `/v1/rooms/${roomId}/join-requests/${submitted.json.requestId}/approve`, {
      token: ownerToken,
      body: { expectedRevision: snapshot.revision + 1 }
    });
    assert.equal(approved.status, 200);
    const revision = approved.json.snapshot.revision;
    const badRemove = await env.api('POST', `/v1/rooms/${roomId}/members/dev-2/remove`, {
      token: ownerToken,
      body: {}
    });
    assert.equal(badRemove.status, 400);
    const staleUnblock = await env.api('POST', `/v1/rooms/${roomId}/unblock/dev-2`, {
      token: ownerToken,
      body: { expectedRevision: revision - 1 }
    });
    assert.equal(staleUnblock.status, 409);
    assert.equal(staleUnblock.json.code, 'REVISION_CONFLICT');
  } finally {
    await env.close();
  }
});

test('event mailbox still resyncs after the bounded window without media signaling', async () => {
  const env = await startTestServer();
  try {
    const created = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-owner', nickname: 'Owner', requireApproval: false }
    });
    const { roomId, sessionToken: ownerToken, snapshot } = created.json;
    await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    for (let index = 0; index < MAX_EVENT_WINDOW + 4; index += 1) {
      env.advance(1);
      await env.api('POST', `/v1/rooms/${roomId}/audio`, {
        token: ownerToken,
        body: { available: index % 2 === 0 }
      });
    }
    const resynced = await env.api('GET', `/v1/rooms/${roomId}/events?after=1&waitMs=0`, {
      token: ownerToken
    });
    assert.equal(resynced.status, 200);
    assert.equal(resynced.json.resync, true);
    assert.equal(resynced.json.snapshot.maxMembers, 50);
  } finally {
    await env.close();
  }
});

test('cleartext HTTP refuses non-loopback host at both creation and listen() while loopback works', async () => {
  assert.equal(isLoopbackHost('127.0.0.1'), true);
  assert.equal(isLoopbackHost('localhost'), true);
  assert.equal(isLoopbackHost('::1'), true);
  assert.equal(isLoopbackHost('0.0.0.0'), false);
  const base = {
    appId: APP_ID,
    appKey: APP_KEY,
    sweepIntervalMs: 0,
    onRtcBanUser: async () => {},
    onRtcBanRoom: async () => {},
    onRtcUnbanUser: async () => {}
  };
  assert.throws(() => createGroupCloudServer({ ...base, host: '0.0.0.0' }), /Refusing to start cleartext HTTP/);
  assert.throws(
    () => createGroupCloudServer({ ...base, host: '0.0.0.0', tls: { cert: 'only-cert' } }),
    /requires both/
  );
  const { server } = createGroupCloudServer(base);
  try {
    assert.throws(() => server.listen(0, '0.0.0.0'), /Refusing to listen cleartext HTTP/);
    assert.throws(() => server.listen(0), /Refusing to listen cleartext HTTP/);
    await listenOnFetchSafePort(server);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('HYBRID mode enforces 4-seat hard cap, rejects HYBRID endpoints on NETWORK rooms, and isolates localCode', async () => {
  const env = await startTestServer({ maxMembers: 50 });
  try {
    const netRoom = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'net-owner', runtimeId: 'run-net', nickname: 'NetOwner', requireApproval: false }
    });
    assert.equal(netRoom.status, 201);
    assert.equal(netRoom.json.snapshot.mode, 'NETWORK');
    assert.equal(netRoom.json.snapshot.maxMembers, 50);
    assert.equal(netRoom.json.snapshot.transport, null);
    const deniedOnNet = await env.api('POST', `/v1/rooms/${netRoom.json.roomId}/channel`, {
      token: netRoom.json.sessionToken,
      body: { channel: 'RIDER', expectedRevision: netRoom.json.snapshot.revision }
    });
    assert.equal(deniedOnNet.status, 400);
    assert.equal(deniedOnNet.json.code, 'UNSUPPORTED_IN_MODE');

    const created = await env.api('POST', '/v1/rooms', {
      body: {
        deviceId: 'hyb-owner',
        runtimeId: 'run-owner',
        nickname: '领队A',
        requireApproval: false,
        mode: 'HYBRID'
      }
    });
    assert.equal(created.status, 201);
    const { roomId, snapshot } = created.json;
    assert.equal(snapshot.mode, 'HYBRID');
    assert.equal(snapshot.maxMembers, 4);
    assert.equal(snapshot.hostDeviceId, 'hyb-owner');
    assert.equal(snapshot.transport.active, 'LOCAL');
    assert.equal(snapshot.transport.epoch, 1);
    assert.match(snapshot.transport.localCode, /^[0-9]{6}$/);
    assert.equal(created.json.rtc, null);
    assert.equal(snapshot.rtcRoomId, null);
    assert.equal(env.logs.join('\n').includes(snapshot.transport.localCode), false);

    for (let index = 2; index <= 4; index += 1) {
      const joined = await env.api('POST', '/v1/rooms/join-requests', {
        body: {
          inviteCode: snapshot.invite.code,
          deviceId: `hyb-rider-${index}`,
          runtimeId: `run-${index}`,
          nickname: `骑士${index}`
        }
      });
      assert.equal(joined.status, 200);
      assert.equal(joined.json.snapshot.transport.localCode, snapshot.transport.localCode);
      assert.equal(joined.json.rtc, null);
      assert.equal(joined.json.snapshot.rtcRoomId, null);
    }

    const deniedLocalRtc = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: created.json.sessionToken
    });
    assert.equal(deniedLocalRtc.status, 409);
    assert.equal(deniedLocalRtc.json.code, 'TRANSPORT_NOT_CLOUD');

    const fifth = await env.api('POST', '/v1/rooms/join-requests', {
      body: {
        inviteCode: snapshot.invite.code,
        deviceId: 'hyb-rider-5',
        runtimeId: 'run-5',
        nickname: '骑士5'
      }
    });
    assert.equal(fifth.status, 409);
    assert.equal(fifth.json.code, 'ROOM_FULL');
  } finally {
    await env.close();
  }
});

test('HYBRID roles, rider/private channels, channel-scoped RTC rooms, and multi-room BanRoomUser work atomically', async () => {
  const env = await startTestServer();
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-a', runtimeId: 'run-a', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    const peerB = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-b', runtimeId: 'run-b', nickname: '头车' }
    });
    const peerC = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-c', runtimeId: 'run-c', nickname: '普通' }
    });
    let rev = peerC.json.snapshot.revision;

    const deniedRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: peerC.json.sessionToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(deniedRider.status, 403);

    const promoted = await env.api('POST', `/v1/rooms/${roomId}/roles`, {
      token: ownerToken,
      body: { targetDeviceId: 'dev-b', role: 'HEAD', expectedRevision: rev }
    });
    assert.equal(promoted.status, 200);
    rev = promoted.json.snapshot.revision;

    const bToRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: peerB.json.sessionToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(bToRider.status, 200);
    assert.equal(bToRider.json.snapshot.channel, 'RIDER');
    assert.equal(bToRider.json.rtc, null);
    rev = bToRider.json.snapshot.revision;

    // Switch whole room to CLOUD to verify channel-scoped RTC issuance & authoritative revocation.
    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    const ownerPubToken = toCloud.json.rtc;
    assert.ok(ownerPubToken.rtcRoomId.endsWith('_e2_pub'));
    assert.equal(env.canJoinRtc(ownerPubToken), true);
    rev = toCloud.json.snapshot.revision;

    const bRiderTokenRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerB.json.sessionToken
    });
    assert.equal(bRiderTokenRes.status, 200);
    const bRiderToken = bRiderTokenRes.json.rtc;
    assert.ok(bRiderToken.rtcRoomId.endsWith('_e2_rider'));
    assert.equal(env.canJoinRtc(bRiderToken), true);

    const excludeFormation = await env.api('POST', `/v1/rooms/${roomId}/rider-policy`, {
      token: ownerToken,
      body: { riderIncludesFormation: false, expectedRevision: rev }
    });
    assert.equal(excludeFormation.status, 200);
    const memberB = excludeFormation.json.snapshot.members.find((m) => m.deviceId === 'dev-b');
    assert.equal(memberB.channel, 'PUBLIC');
    assert.equal(env.canJoinRtc(bRiderToken), false);
    rev = excludeFormation.json.snapshot.revision;

    const bPubTokenRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerB.json.sessionToken
    });
    assert.equal(bPubTokenRes.status, 200);
    const bPubToken = bPubTokenRes.json.rtc;
    const cPubTokenRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerC.json.sessionToken
    });
    assert.equal(cPubTokenRes.status, 200);
    const cPubToken = cPubTokenRes.json.rtc;
    assert.equal(env.canJoinRtc(bPubToken), true);
    assert.equal(env.canJoinRtc(cPubToken), true);

    const privReq = await env.api('POST', `/v1/rooms/${roomId}/private-invites`, {
      token: peerB.json.sessionToken,
      body: { targetDeviceId: 'dev-c', expectedRevision: rev }
    });
    assert.equal(privReq.status, 201);
    rev = privReq.json.snapshot.revision;

    const privAccept = await env.api('POST', `/v1/rooms/${roomId}/private-invites/${privReq.json.inviteId}/accept`, {
      token: peerC.json.sessionToken,
      body: { expectedRevision: rev }
    });
    assert.equal(privAccept.status, 200);
    assert.ok(privAccept.json.snapshot.channel.startsWith('PRIVATE:'));
    assert.ok(privAccept.json.rtc.rtcRoomId.includes('_e2_p_'));
    assert.equal(env.canJoinRtc(bPubToken), false);
    assert.equal(env.canJoinRtc(cPubToken), false);
    assert.equal(env.canJoinRtc(privAccept.json.rtc), true);
    rev = privAccept.json.snapshot.revision;

    const removedC = await env.api('POST', `/v1/rooms/${roomId}/members/dev-c/remove`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(removedC.status, 200);
    const memberBAfterRemove = removedC.json.snapshot.members.find((m) => m.deviceId === 'dev-b');
    assert.equal(memberBAfterRemove.channel, 'PUBLIC');
    assert.equal(env.canJoinRtc(privAccept.json.rtc), false);
    assert.ok(env.bannedRooms.some((entry) => entry.roomId.includes('_e2_p_')));

    // B returns to PUBLIC and receives an authorized token (server calls UpdateBanRoomUserRule to unban _e2_pub).
    const bRePub = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerB.json.sessionToken
    });
    assert.equal(bRePub.status, 200);
    assert.equal(env.canJoinRtc(bRePub.json.rtc), true);
    assert.ok(env.unbannedUsers.some((entry) => entry.userId === bPubToken.rtcUserId && entry.roomId.endsWith('_e2_pub')));

    const ended = await env.api('POST', `/v1/rooms/${roomId}/end`, {
      token: ownerToken,
      body: { expectedRevision: removedC.json.snapshot.revision }
    });
    assert.equal(ended.status, 200);
    assert.equal(env.canJoinRtc(ownerPubToken), false);
    assert.equal(env.canJoinRtc(bRePub.json.rtc), false);
    assert.ok(env.bannedRooms.some((entry) => entry.roomId.endsWith('_e2_pub')));
    assert.ok(env.bannedRooms.some((entry) => entry.roomId.endsWith('_e2_rider')));
    assert.ok(env.bannedRooms.some((entry) => entry.roomId.includes('_e2_p_')));
  } finally {
    await env.close();
  }
});

test('HYBRID whole-group LOCAL -> CLOUD fallback, cooldown, all-peer proximity auto-recovery, and MANUAL_CLOUD lock', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-host', runtimeId: 'run-host', nickname: 'Host', requireApproval: false, mode: 'HYBRID', supportsFastP2p: true }
    });
    const roomId = owner.json.roomId;
    const hostToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    const peerB = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-b', runtimeId: 'run-b', nickname: 'RiderB' }
    });
    const peerC = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-c', runtimeId: 'run-c', nickname: 'RiderC' }
    });
    const initialLocalCode = owner.json.snapshot.transport.localCode;

    const fallback = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerB.json.sessionToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 1 }
    });
    assert.equal(fallback.status, 200);
    assert.equal(fallback.json.switched, true);
    assert.equal(fallback.json.snapshot.transport.active, 'CLOUD');
    assert.equal(fallback.json.snapshot.transport.epoch, 2);
    assert.equal(fallback.json.snapshot.transport.localCode, null);
    assert.equal(fallback.json.snapshot.hostDeviceId, null);

    const staleFallback = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerC.json.sessionToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 1 }
    });
    assert.equal(staleFallback.status, 409);
    assert.equal(staleFallback.json.code, 'TRANSPORT_CONFLICT');

    // During cooldown, proximity reports must NOT flip back to LOCAL.
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerB.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true }
    });
    const stillCooldown = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerC.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true }
    });
    assert.equal(stillCooldown.json.switched, false);
    assert.equal(stillCooldown.json.snapshot.transport.active, 'CLOUD');

    // After cooldown, partial proximity (only B, not C) still stays CLOUD.
    env.advance(16_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true, localHostAddress: '192.168.49.1', localFrequencyMhz: 2437 }
    });
    const partial = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerB.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -70 }
    });
    assert.equal(partial.json.switched, false);
    assert.equal(partial.json.snapshot.transport.recoveryStage, 'IDLE');
    assert.equal(partial.json.snapshot.transport.localCode, null);
    assert.equal(partial.json.snapshot.transport.localP2pPassphrase, null);
    assert.match(partial.json.snapshot.transport.localP2pSsid, /^DIRECT-mc-[a-z0-9]{8}$/);

    // Once C also reports fresh reachability (>= -75 dBm), server enters PROBING and holds for 12s signal stability.
    const probing = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerC.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -72 }
    });
    assert.equal(probing.json.switched, false);
    assert.equal(probing.json.snapshot.transport.active, 'CLOUD');
    assert.equal(probing.json.snapshot.transport.recoveryStage, 'PROBING');
    assert.equal(probing.json.snapshot.transport.localCode, null);

    // After 12s of stable signal, server enters PRECONNECTING while keeping CLOUD audio uninterrupted.
    env.advance(12_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerB.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -68 }
    });
    const preconnect = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerC.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -71 }
    });
    assert.equal(preconnect.json.switched, false);
    assert.equal(preconnect.json.snapshot.transport.active, 'CLOUD');
    assert.equal(preconnect.json.snapshot.transport.recoveryStage, 'PRECONNECTING');
    assert.match(preconnect.json.snapshot.transport.localCode, /^[0-9]{6}$/);
    assert.notEqual(preconnect.json.snapshot.transport.localCode, initialLocalCode);
    assert.match(preconnect.json.snapshot.transport.localP2pPassphrase, /^[A-Za-z0-9]{16}$/);
    assert.equal(preconnect.json.snapshot.transport.localHostAddress, '192.168.49.1');
    assert.equal(preconnect.json.snapshot.transport.localFrequencyMhz, 2437);

    // Partial localControlReady (Host + B only) MUST NOT cut over yet.
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true, localControlReady: true }
    });
    const partialControl = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerB.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localControlReady: true }
    });
    assert.equal(partialControl.json.switched, false);
    assert.equal(partialControl.json.snapshot.transport.active, 'CLOUD');

    // Once C also confirms localControlReady, whole group atomically revokes RTC and hands over to LOCAL.
    const recovered = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerC.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localControlReady: true }
    });
    assert.equal(recovered.json.switched, true);
    assert.equal(recovered.json.snapshot.transport.active, 'LOCAL');
    assert.equal(recovered.json.snapshot.transport.epoch, 3);
    assert.equal(recovered.json.snapshot.transport.reason, 'LOCAL_RECOVERED');
    assert.equal(recovered.json.snapshot.transport.handoverFromEpoch, 2);
    assert.equal(recovered.json.snapshot.transport.localCode, preconnect.json.snapshot.transport.localCode);
    assert.equal(recovered.json.snapshot.transport.localP2pSsid, preconnect.json.snapshot.transport.localP2pSsid);
    assert.equal(recovered.json.snapshot.transport.localP2pPassphrase, preconnect.json.snapshot.transport.localP2pPassphrase);
    assert.equal(recovered.json.snapshot.hostDeviceId, 'dev-host');
    assert.equal(env.logs.join('\n').includes(initialLocalCode), false);
    assert.equal(env.logs.join('\n').includes(recovered.json.snapshot.transport.localCode), false);
    assert.equal(env.logs.join('\n').includes(recovered.json.snapshot.transport.localP2pPassphrase), false);

    // Manual switch to CLOUD suppresses auto-recovery until owner explicitly switches back.
    const manualCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: hostToken,
      body: {
        targetTransport: 'CLOUD',
        reason: 'MANUAL_CLOUD',
        expectedRevision: recovered.json.snapshot.revision,
        expectedTransportEpoch: 3
      }
    });
    assert.equal(manualCloud.status, 200);
    assert.equal(manualCloud.json.snapshot.transport.epoch, 4);
    env.advance(20_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 4, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerB.json.sessionToken,
      body: { transportEpoch: 4, localReachable: true }
    });
    const suppressed = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerC.json.sessionToken,
      body: { transportEpoch: 4, localReachable: true }
    });
    assert.equal(suppressed.json.switched, false);
    assert.equal(suppressed.json.snapshot.transport.active, 'CLOUD');

    const manualLocal = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: hostToken,
      body: {
        targetTransport: 'LOCAL',
        reason: 'MANUAL_LOCAL',
        expectedRevision: suppressed.json.snapshot.revision,
        expectedTransportEpoch: 4
      }
    });
    assert.equal(manualLocal.status, 200);
    assert.equal(manualLocal.json.snapshot.transport.active, 'LOCAL');
    assert.equal(manualLocal.json.snapshot.transport.epoch, 5);
  } finally {
    await env.close();
  }
});

test('HYBRID host disconnect timeout automatically switches whole room to CLOUD', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 10_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-host', runtimeId: 'run-host', nickname: 'Host', requireApproval: false, mode: 'HYBRID', supportsFastP2p: true }
    });
    const roomId = owner.json.roomId;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    assert.equal(peer.json.snapshot.transport.active, 'LOCAL');

    // Peer stays alive while host goes silent past disconnectTimeoutMs.
    env.advance(6_000);
    await env.api('POST', `/v1/rooms/${roomId}/audio`, {
      token: peer.json.sessionToken,
      body: { available: true }
    });
    env.advance(5_000);
    const events = await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, {
      token: peer.json.sessionToken
    });
    assert.equal(events.status, 200);
    const latest = events.json.events.at(-1).snapshot;
    assert.equal(latest.transport.active, 'CLOUD');
    assert.equal(latest.transport.reason, 'LOCAL_HOST_LOST');
    assert.equal(latest.transport.epoch, 2);
  } finally {
    await env.close();
  }
});

test('OpenAPI revocation fails closed when disabled or failing and never silently skips', async () => {
  // Missing AK/SK: createRtcAdmin is disabled and every admin action rejects (no { skipped: true }).
  const disabledAdmin = createRtcAdmin({ appId: APP_ID, accessKeyId: '', secretKey: '' });
  assert.equal(disabledAdmin.enabled, false);
  await assert.rejects(() => disabledAdmin.banUser('room-1', 'user-1', 300), /OpenAPI AK\/SK is not configured/);
  await assert.rejects(() => disabledAdmin.banRoom('room-1', 600), /OpenAPI AK\/SK is not configured/);
  await assert.rejects(() => disabledAdmin.unbanUser('room-1', 'user-1'), /OpenAPI AK\/SK is not configured/);

  // createGroupCloudServer MUST refuse to start when revocation is not configured or disabled.
  assert.throws(
    () => createGroupCloudServer({ appId: APP_ID, appKey: APP_KEY, sweepIntervalMs: 0 }),
    /Refusing to start group-cloud server without authoritative Volc RTC OpenAPI revocation/
  );
  assert.throws(
    () => createGroupCloudServer({ appId: APP_ID, appKey: APP_KEY, rtcAdmin: disabledAdmin, sweepIntervalMs: 0 }),
    /Refusing to start group-cloud server without authoritative Volc RTC OpenAPI revocation/
  );

  // Direct GroupCloudStore with disabled admin refuses NETWORK createRoom and HYBRID LOCAL->CLOUD transition.
  const disabledStore = new GroupCloudStore({ appId: APP_ID, appKey: APP_KEY, rtcAdmin: disabledAdmin });
  await assert.rejects(
    () => disabledStore.createRoom({ deviceId: 'dev-1', runtimeId: 'run-1', nickname: 'Owner', mode: 'NETWORK' }),
    (err) => err.status === 503 && err.code === 'RTC_REVOCATION_UNAVAILABLE'
  );
  const hyb = await disabledStore.createRoom({
    deviceId: 'dev-1',
    runtimeId: 'run-1',
    nickname: 'Owner',
    mode: 'HYBRID'
  });
  assert.equal(hyb.rtc, null);
  await assert.rejects(
    () => disabledStore.switchTransport(hyb.sessionToken, hyb.roomId, {
      targetTransport: 'CLOUD',
      reason: 'MANUAL_CLOUD',
      expectedRevision: hyb.snapshot.revision,
      expectedTransportEpoch: 1
    }),
    (err) => err.status === 503 && err.code === 'RTC_REVOCATION_UNAVAILABLE'
  );

  // Transient OpenAPI failure during removeMember / switchChannel / CLOUD->LOCAL fails closed (502) without mutating state.
  const env = await startTestServer({ disconnectTimeoutMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-1', runtimeId: 'run-1', nickname: 'Owner', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-2', runtimeId: 'run-2', nickname: 'Peer' }
    });
    let rev = peer.json.snapshot.revision;
    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    rev = toCloud.json.snapshot.revision;

    const peerRtcRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peer.json.sessionToken });
    assert.equal(peerRtcRes.status, 200);
    const peerToken = peerRtcRes.json.rtc;
    assert.equal(env.canJoinRtc(peerToken), true);

    // Fault BanRoomUser during removeMember -> 502 and member is NOT silently removed while token lives.
    env.faults.banUser = new Error('Volc OpenAPI HTTP 500');
    const failedRemove = await env.api('POST', `/v1/rooms/${roomId}/members/dev-2/remove`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(failedRemove.status, 502);
    assert.equal(failedRemove.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.canJoinRtc(peerToken), true);

    // Retry succeeds and immediately invalidates peer's RTC token.
    const okRemove = await env.api('POST', `/v1/rooms/${roomId}/members/dev-2/remove`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(okRemove.status, 200);
    assert.equal(env.canJoinRtc(peerToken), false);
    rev = okRemove.json.snapshot.revision;

    // Fault BanRoomUser during CLOUD -> LOCAL switch -> 502 and transport remains CLOUD.
    env.faults.banRoom = new Error('Volc OpenAPI timeout');
    const failedLocal = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(failedLocal.status, 502);
    assert.equal(failedLocal.json.code, 'RTC_REVOCATION_FAILED');

    // Retry CLOUD -> LOCAL succeeds, returns rtc=null, and bans old epoch-2 RTC room immediately.
    const okLocal = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(okLocal.status, 200);
    assert.equal(okLocal.json.rtc, null);
    assert.equal(okLocal.json.snapshot.transport.active, 'LOCAL');
    assert.equal(env.canJoinRtc(toCloud.json.rtc), false);
  } finally {
    await env.close();
  }
});

test('HYBRID channel switch, private close, and CLOUD -> LOCAL enforce server-side old token rejection and clean re-entry', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-head', runtimeId: 'run-h', nickname: '头车' }
    });
    let rev = peer.json.snapshot.revision;
    const promoted = await env.api('POST', `/v1/rooms/${roomId}/roles`, {
      token: ownerToken,
      body: { targetDeviceId: 'dev-head', role: 'HEAD', expectedRevision: rev }
    });
    rev = promoted.json.snapshot.revision;

    // Switch to CLOUD (epoch 2).
    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    rev = toCloud.json.snapshot.revision;

    const headPubRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peer.json.sessionToken });
    const headPubToken = headPubRes.json.rtc;
    assert.equal(env.canJoinRtc(headPubToken), true);

    // PUBLIC -> RIDER immediately revokes old PUBLIC token on simulated Volc RTC server.
    const toRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: peer.json.sessionToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(toRider.status, 200);
    const headRiderToken = toRider.json.rtc;
    assert.equal(env.canJoinRtc(headPubToken), false);
    assert.equal(env.canJoinRtc(headRiderToken), true);
    rev = toRider.json.snapshot.revision;

    // RIDER -> PUBLIC immediately revokes old RIDER token AND unbans PUBLIC for authorized re-entry.
    const backToPub = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: peer.json.sessionToken,
      body: { channel: 'PUBLIC', expectedRevision: rev }
    });
    assert.equal(backToPub.status, 200);
    const headPubToken2 = backToPub.json.rtc;
    assert.equal(env.canJoinRtc(headRiderToken), false);
    assert.equal(env.canJoinRtc(headPubToken2), true);
    rev = backToPub.json.snapshot.revision;

    // Private invite -> accept -> close revokes PRIVATE room for both participants.
    const inv = await env.api('POST', `/v1/rooms/${roomId}/private-invites`, {
      token: ownerToken,
      body: { targetDeviceId: 'dev-head', expectedRevision: rev }
    });
    rev = inv.json.snapshot.revision;
    const accepted = await env.api('POST', `/v1/rooms/${roomId}/private-invites/${inv.json.inviteId}/accept`, {
      token: peer.json.sessionToken,
      body: { expectedRevision: rev }
    });
    assert.equal(accepted.status, 200);
    const headPrivToken = accepted.json.rtc;
    assert.equal(env.canJoinRtc(toCloud.json.rtc), false);
    assert.equal(env.canJoinRtc(headPubToken2), false);
    assert.equal(env.canJoinRtc(headPrivToken), true);
    rev = accepted.json.snapshot.revision;

    const ownerPrivRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken });
    const ownerPrivToken = ownerPrivRes.json.rtc;
    assert.equal(env.canJoinRtc(ownerPrivToken), true);

    const closed = await env.api('POST', `/v1/rooms/${roomId}/private/close`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(closed.status, 200);
    assert.equal(env.canJoinRtc(ownerPrivToken), false);
    assert.equal(env.canJoinRtc(headPrivToken), false);
    assert.equal(env.canJoinRtc(closed.json.rtc), true);
    rev = closed.json.snapshot.revision;

    // Switch CLOUD (epoch 2) -> LOCAL (epoch 3) -> CLOUD (epoch 4) uses fresh epoch-scoped RTC rooms.
    const toLocal = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(toLocal.status, 200);
    assert.equal(toLocal.json.rtc, null);
    assert.equal(env.canJoinRtc(closed.json.rtc), false);
    rev = toLocal.json.snapshot.revision;

    const toCloud4 = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 3 }
    });
    assert.equal(toCloud4.status, 200);
    assert.ok(toCloud4.json.rtc.rtcRoomId.endsWith('_e4_pub'));
    assert.notEqual(toCloud4.json.rtc.rtcRoomId, closed.json.rtc.rtcRoomId);
    assert.equal(env.canJoinRtc(toCloud4.json.rtc), true);
  } finally {
    await env.close();
  }
});

test('HYBRID backoff reliably escalates 15s -> 30s -> 60s across rapid flaps even with ready reports and resets only after sustained stability', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 120_000, localStableResetMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-host', runtimeId: 'run-host', nickname: 'Host', requireApproval: false, mode: 'HYBRID', supportsFastP2p: true }
    });
    const roomId = owner.json.roomId;
    const hostToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    const peerToken = peer.json.sessionToken;

    async function reportProximity(epoch) {
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: hostToken,
        body: { transportEpoch: epoch, localProbeActive: true }
      });
      return env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerToken,
        body: { transportEpoch: epoch, localReachable: true, localRssiDbm: -68 }
      });
    }

    async function completeTwoPhaseRecovery(epoch) {
      const startProbe = await reportProximity(epoch);
      assert.equal(startProbe.json.switched, false);
      assert.equal(startProbe.json.snapshot.transport.recoveryStage, 'PROBING');
      env.advance(12_000);
      const preconnect = await reportProximity(epoch);
      assert.equal(preconnect.json.switched, false);
      assert.equal(preconnect.json.snapshot.transport.recoveryStage, 'PRECONNECTING');
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: hostToken,
        body: { transportEpoch: epoch, localProbeActive: true, localControlReady: true }
      });
      return env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerToken,
        body: { transportEpoch: epoch, localReachable: true, localControlReady: true }
      });
    }

    async function reportLocalReady(epoch) {
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: hostToken,
        body: { transportEpoch: epoch, localReady: true }
      });
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerToken,
        body: { transportEpoch: epoch, localReady: true }
      });
    }

    // Flap #1: cooldown = 15s (epoch 1 -> 2).
    const fb1 = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 1 }
    });
    assert.equal(fb1.json.snapshot.transport.epoch, 2);
    assert.equal(fb1.json.snapshot.transport.cooldownUntilMs - env.getNow(), 15_000);
    env.advance(14_000);
    assert.equal((await reportProximity(2)).json.switched, false);
    env.advance(1_000);
    const rec1 = await completeTwoPhaseRecovery(2);
    assert.equal(rec1.json.switched, true);
    assert.equal(rec1.json.snapshot.transport.epoch, 3);

    // Peers immediately report localReady=true, then drop 5s later (< 60s stability window!).
    env.advance(1_000);
    await reportLocalReady(3);
    env.advance(5_000);

    // Flap #2: cooldown MUST escalate to 30s (epoch 3 -> 4), NOT reset to 15s!
    const fb2 = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 3 }
    });
    assert.equal(fb2.json.snapshot.transport.epoch, 4);
    assert.equal(fb2.json.snapshot.transport.cooldownUntilMs - env.getNow(), 30_000);
    env.advance(29_000);
    assert.equal((await reportProximity(4)).json.switched, false);
    env.advance(1_000);
    const rec2 = await completeTwoPhaseRecovery(4);
    assert.equal(rec2.json.switched, true);
    assert.equal(rec2.json.snapshot.transport.epoch, 5);

    // Immediate localReady=true then drop 10s later (< 60s stability window!).
    env.advance(1_000);
    await reportLocalReady(5);
    env.advance(10_000);

    // Flap #3: cooldown MUST escalate to 60s (epoch 5 -> 6).
    const fb3 = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 5 }
    });
    assert.equal(fb3.json.snapshot.transport.epoch, 6);
    assert.equal(fb3.json.snapshot.transport.cooldownUntilMs - env.getNow(), 60_000);
    env.advance(59_000);
    assert.equal((await reportProximity(6)).json.switched, false);
    env.advance(1_000);
    const rec3 = await completeTwoPhaseRecovery(6);
    assert.equal(rec3.json.switched, true);
    assert.equal(rec3.json.snapshot.transport.epoch, 7);

    // Sustain continuous localReady across >= 60s stability window (refreshing within localStatusFreshMs).
    await reportLocalReady(7);
    for (let step = 0; step < 4; step += 1) {
      env.advance(16_000);
      await reportLocalReady(7);
    }

    // After sustained >= 60s stability, next fallback resets cleanly back to 15s!
    const fbAfterStable = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 7 }
    });
    assert.equal(fbAfterStable.json.snapshot.transport.epoch, 8);
    assert.equal(fbAfterStable.json.snapshot.transport.cooldownUntilMs - env.getNow(), 15_000);
  } finally {
    await env.close();
  }
});

async function startFakeVolcRtcServer(getNow) {
  const calls = [];
  const bannedRoomUntil = new Map();
  const bannedUserUntil = new Map();
  let failStatus = 0;
  let failOnceStatus = 0;
  let delayAfterBanOnceMs = 0;
  let delayAfterBanMs = 0;
  let nextGate = null;
  let onBanApplied = null;

  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    const action = url.searchParams.get('Action') ?? '';
    const body = chunks.length > 0 ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {};
    const call = {
      action,
      roomId: body.RoomId ?? '',
      userId: body.UserId ?? null,
      forbiddenInterval: Number(body.ForbiddenInterval ?? 0)
    };
    calls.push(call);

    if (nextGate) {
      const gate = nextGate;
      nextGate = null;
      gate.notifyHit(call);
      await gate.promise;
    }

    const statusToFail = failOnceStatus || failStatus;
    if (failOnceStatus) failOnceStatus = 0;
    if (statusToFail) {
      const payload = JSON.stringify({
        ResponseMetadata: { Error: { Code: 'InternalError', Message: `Simulated HTTP ${statusToFail}` } }
      });
      res.writeHead(statusToFail, { 'Content-Type': 'application/json' });
      res.end(payload);
      return;
    }

    const nowMs = getNow();
    let resultMessage = 'success';
    if (action === 'BanRoomUser') {
      const untilMs = nowMs + call.forbiddenInterval * 1000;
      if (call.userId) {
        const pairKey = `${call.roomId}\0${call.userId}`;
        if ((bannedUserUntil.get(pairKey) ?? 0) > nowMs) {
          resultMessage = 'User has been banned.';
        }
        bannedUserUntil.set(pairKey, Math.max(bannedUserUntil.get(pairKey) ?? 0, untilMs));
      } else {
        if ((bannedRoomUntil.get(call.roomId) ?? 0) > nowMs) {
          resultMessage = 'Room has been banned.';
        }
        bannedRoomUntil.set(call.roomId, Math.max(bannedRoomUntil.get(call.roomId) ?? 0, untilMs));
      }
      onBanApplied?.(call);
      const postBanDelay = delayAfterBanOnceMs || delayAfterBanMs;
      if (delayAfterBanOnceMs) delayAfterBanOnceMs = 0;
      if (postBanDelay > 0) {
        await new Promise((resolve) => setTimeout(resolve, postBanDelay));
        if (res.destroyed || req.destroyed) return;
      }
    } else if (action === 'UpdateBanRoomUserRule') {
      if (call.userId && call.forbiddenInterval === 0) {
        const pairKey = `${call.roomId}\0${call.userId}`;
        if ((bannedUserUntil.get(pairKey) ?? 0) <= nowMs) {
          resultMessage = 'User is not banned.';
        }
        bannedUserUntil.delete(pairKey);
      } else if (call.userId) {
        bannedUserUntil.set(`${call.roomId}\0${call.userId}`, nowMs + call.forbiddenInterval * 1000);
      }
    }

    const payload = JSON.stringify({ ResponseMetadata: {}, Result: { Message: resultMessage } });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(payload);
  });

  await listenOnFetchSafePort(server);
  const { port } = server.address();
  const url = `http://127.0.0.1:${port}`;

  return {
    url,
    calls,
    setFailStatus: (status) => {
      failStatus = status;
    },
    failNextWith: (status) => {
      failOnceStatus = status;
    },
    delayNextBanResponseMs: (ms) => {
      delayAfterBanOnceMs = ms;
    },
    setBanResponseDelayMs: (ms) => {
      delayAfterBanMs = ms;
    },
    setOnBanApplied: (fn) => {
      onBanApplied = fn;
    },
    armGate: () => {
      let notifyHit;
      const hit = new Promise((resolve) => {
        notifyHit = resolve;
      });
      let release;
      const promise = new Promise((resolve) => {
        release = resolve;
      });
      nextGate = { promise, notifyHit };
      return { hit, release };
    },
    canJoin: (rtc) => {
      const nowMs = getNow();
      if (!rtc || rtc.expiresAtMs <= nowMs) return false;
      if ((bannedRoomUntil.get(rtc.rtcRoomId) ?? 0) > nowMs) return false;
      if ((bannedUserUntil.get(`${rtc.rtcRoomId}\0${rtc.rtcUserId}`) ?? 0) > nowMs) return false;
      return true;
    },
    close: () => new Promise((resolve) => server.close(resolve))
  };
}

async function startFakeVolcEnv(options = {}) {
  let nowMs = options.startMs ?? 1_750_000_000_000;
  const fakeVolc = await startFakeVolcRtcServer(() => nowMs);
  const rtcAdmin = createRtcAdmin({
    appId: APP_ID,
    accessKeyId: 'test-ak-fake-volc',
    secretKey: 'test-sk-fake-volc',
    endpointUrl: fakeVolc.url,
    timeoutMs: options.rtcTimeoutMs,
    now: () => new Date(nowMs)
  });
  const store = new GroupCloudStore({
    now: () => nowMs,
    appId: APP_ID,
    appKey: APP_KEY,
    disconnectTimeoutMs: options.disconnectTimeoutMs ?? 15_000,
    reservationMs: options.reservationMs ?? 60_000,
    localSignalStableMs: options.localSignalStableMs,
    localPreconnectTimeoutMs: options.localPreconnectTimeoutMs,
    revocationRetryCooldownMs: options.revocationRetryCooldownMs,
    rtcAdmin
  });
  const { server } = createGroupCloudServer({ store, sweepIntervalMs: 0 });
  await listenOnFetchSafePort(server);
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  async function api(method, pathname, { token = null, body = undefined } = {}) {
    const headers = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(`${baseUrl}${pathname}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined
    });
    const json = await response.json();
    return { status: response.status, json };
  }

  return {
    store,
    fakeVolc,
    api,
    advance: (deltaMs) => {
      nowMs += deltaMs;
      return nowMs;
    },
    getNow: () => nowMs,
    close: async () => {
      await new Promise((resolve) => server.close(resolve));
      await fakeVolc.close();
    }
  };
}

test('sweep() ADMITTED -> RESERVED awaits OpenAPI BanRoomUser fail-closed (delay & 500), rejects old token before commit, and /resume unbans via UpdateBanRoomUserRule', async () => {
  const env = await startFakeVolcEnv({ disconnectTimeoutMs: 15_000, reservationMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: 'Owner', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: 'Peer' }
    });
    const peerToken = peer.json.sessionToken;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: peer.json.snapshot.revision, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    const peerRtcRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(peerRtcRes.status, 200);
    const peerOldRtc = peerRtcRes.json.rtc;
    assert.equal(env.fakeVolc.canJoin(peerOldRtc), true);

    // Drain owner's existing mailbox so we can detect any new SNAPSHOT broadcast precisely.
    const initialPoll = await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: ownerToken });
    const ownerSeqBefore = initialPoll.json.latestSeq;
    const room = env.store.rooms.get(roomId);
    const revBefore = room.revision;

    // Advance past disconnect timeout for peer while keeping owner alive.
    env.advance(10_000);
    await env.api('POST', `/v1/rooms/${roomId}/ack`, { token: ownerToken, body: { sequence: ownerSeqBefore } });
    env.advance(6_000);

    // 1) OpenAPI 500 failure MUST NOT commit RESERVED, bump revision, or broadcast SNAPSHOT.
    env.fakeVolc.failNextWith(500);
    const failedSweep = await env.store.sweep();
    assert.equal(failedSweep.changed, false);
    assert.equal(failedSweep.errors.length, 1);
    assert.equal(failedSweep.errors[0].code, 'RTC_REVOCATION_FAILED');
    assert.equal(room.members.get('dev-peer').status, 'ADMITTED');
    assert.equal(room.members.get('dev-peer').reservedUntilMs, null);
    assert.equal(room.revision, revBefore);
    assert.equal(env.fakeVolc.canJoin(peerOldRtc), true);

    // Immediate retry within cooldown is throttled without hammering OpenAPI.
    const callsAfterFailure = env.fakeVolc.calls.length;
    const throttledSweep = await env.store.sweep();
    assert.equal(throttledSweep.changed, false);
    assert.equal(throttledSweep.errors.length, 0);
    assert.equal(env.fakeVolc.calls.length, callsAfterFailure);

    // 2) OpenAPI delayed (hanging Promise) MUST hold state uncommitted until BanRoomUser completes,
    //    and old token MUST already be rejected by FakeVolcRtcServer BEFORE RESERVED commits.
    env.advance(3_000);
    let stateAtBanMoment = null;
    env.fakeVolc.setOnBanApplied(() => {
      stateAtBanMoment = {
        peerStatus: room.members.get('dev-peer').status,
        revision: room.revision,
        canJoinAfterBan: env.fakeVolc.canJoin(peerOldRtc)
      };
    });
    const gate = env.fakeVolc.armGate();
    const sweepPromise = env.store.sweep();
    const hitCall = await gate.hit;
    assert.equal(hitCall.action, 'BanRoomUser');
    assert.equal(hitCall.userId, peerOldRtc.rtcUserId);
    assert.equal(hitCall.roomId, peerOldRtc.rtcRoomId);

    // While OpenAPI call is suspended, peer is still ADMITTED and revision has not advanced.
    assert.equal(room.members.get('dev-peer').status, 'ADMITTED');
    assert.equal(room.members.get('dev-peer').reservedUntilMs, null);
    assert.equal(room.revision, revBefore);
    assert.equal(room.mailboxes.get('dev-owner').latestSeq, ownerSeqBefore);

    gate.release();
    const okSweep = await sweepPromise;
    env.fakeVolc.setOnBanApplied(null);
    assert.equal(okSweep.changed, true);
    assert.equal(okSweep.errors.length, 0);
    assert.deepEqual(stateAtBanMoment, {
      peerStatus: 'ADMITTED',
      revision: revBefore,
      canJoinAfterBan: false
    });
    assert.equal(env.fakeVolc.canJoin(peerOldRtc), false);
    assert.equal(room.members.get('dev-peer').status, 'RESERVED');
    assert.equal(room.revision, revBefore + 1);

    const afterSweepPoll = await env.api('GET', `/v1/rooms/${roomId}/events?after=${ownerSeqBefore}&waitMs=0`, { token: ownerToken });
    assert.equal(afterSweepPoll.status, 200);
    assert.equal(afterSweepPoll.json.events.length, 1);
    assert.equal(afterSweepPoll.json.events[0].type, 'SNAPSHOT');
    const peerView = afterSweepPoll.json.events[0].snapshot.members.find((m) => m.deviceId === 'dev-peer');
    assert.equal(peerView.status, 'RESERVED');

    // 3) Within 60s, /resume invokes UpdateBanRoomUserRule to unban peer and restores RTC join eligibility.
    env.advance(10_000);
    const resumed = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: peerToken });
    assert.equal(resumed.status, 200);
    assert.equal(resumed.json.resumed, true);
    assert.ok(
      env.fakeVolc.calls.some(
        (c) => c.action === 'UpdateBanRoomUserRule' && c.roomId === peerOldRtc.rtcRoomId && c.userId === peerOldRtc.rtcUserId && c.forbiddenInterval === 0
      )
    );
    assert.equal(env.fakeVolc.canJoin(resumed.json.rtc), true);
  } finally {
    await env.close();
  }
});

test('sweep() non-owner RESERVED recovery expiry awaits OpenAPI BanRoomUser fail-closed (delay & 500) and blocks expired /resume', async () => {
  const env = await startFakeVolcEnv({ disconnectTimeoutMs: 15_000, reservationMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: 'Owner', requireApproval: false, mode: 'NETWORK' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: 'Peer' }
    });
    const peerToken = peer.json.sessionToken;
    const peerOldRtc = peer.json.rtc;
    assert.equal(env.fakeVolc.canJoin(peerOldRtc), true);

    // Move peer into RESERVED while keeping owner alive.
    env.advance(10_000);
    await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: ownerToken });
    env.advance(6_000);
    const reserveSweep = await env.store.sweep();
    assert.equal(reserveSweep.changed, true);
    assert.equal(env.fakeVolc.canJoin(peerOldRtc), false);

    // Advance past 60s reservation window while keeping owner alive.
    for (let step = 0; step < 5; step += 1) {
      env.advance(10_500);
      await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: ownerToken });
    }
    const room = env.store.rooms.get(roomId);
    // Keep owner fresh right before peer's reservation expires.
    const remaining = room.members.get('dev-peer').reservedUntilMs - env.getNow();
    if (remaining > 0) env.advance(remaining);
    const revBefore = room.revision;

    // 1) Persistent HTTP 500 on OpenAPI MUST keep peer uncommitted, revision unchanged, and refuse expired /resume.
    env.fakeVolc.setFailStatus(500);
    const failedSweep = await env.store.sweep();
    assert.equal(failedSweep.changed, false);
    assert.equal(failedSweep.errors.length, 1);
    assert.equal(failedSweep.errors[0].code, 'RTC_REVOCATION_FAILED');
    assert.equal(room.members.has('dev-peer'), true);
    assert.equal(room.members.get('dev-peer').status, 'RESERVED');
    assert.equal(room.revision, revBefore);

    const blockedResume = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: peerToken });
    assert.equal(blockedResume.status, 502);
    assert.equal(blockedResume.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(room.members.has('dev-peer'), true);
    assert.equal(room.revision, revBefore);

    // 2) Delayed OpenAPI (hanging Promise) MUST hold peer deletion until BanRoomUser completes.
    env.fakeVolc.setFailStatus(0);
    env.advance(3_000);
    let stateAtBanMoment = null;
    env.fakeVolc.setOnBanApplied(() => {
      stateAtBanMoment = {
        stillMember: room.members.has('dev-peer'),
        revision: room.revision,
        canJoinAfterBan: env.fakeVolc.canJoin(peerOldRtc)
      };
    });
    const gate = env.fakeVolc.armGate();
    const sweepPromise = env.store.sweep();
    const hitCall = await gate.hit;
    assert.equal(hitCall.action, 'BanRoomUser');
    assert.equal(hitCall.userId, peerOldRtc.rtcUserId);
    assert.equal(room.members.has('dev-peer'), true);
    assert.equal(room.revision, revBefore);

    gate.release();
    const okSweep = await sweepPromise;
    env.fakeVolc.setOnBanApplied(null);
    assert.equal(okSweep.changed, true);
    assert.equal(okSweep.errors.length, 0);
    assert.deepEqual(stateAtBanMoment, {
      stillMember: true,
      revision: revBefore,
      canJoinAfterBan: false
    });
    assert.equal(env.fakeVolc.canJoin(peerOldRtc), false);
    assert.equal(room.members.has('dev-peer'), false);
    assert.equal(room.revision, revBefore + 1);

    const expiredResume = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: peerToken });
    assert.equal(expiredResume.status, 410);
    assert.equal(expiredResume.json.code, 'RECOVERY_EXPIRED');
  } finally {
    await env.close();
  }
});

test('sweep() owner RESERVED recovery expiry awaits room-level BanRoomUser fail-closed (delay & 500) before ending room', async () => {
  const env = await startFakeVolcEnv({ disconnectTimeoutMs: 15_000, reservationMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: 'Owner', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: 'Peer' }
    });
    const peerToken = peer.json.sessionToken;
    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: peer.json.snapshot.revision, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    const peerRtcRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    const peerRtc = peerRtcRes.json.rtc;
    assert.equal(env.fakeVolc.canJoin(peerRtc), true);

    // Let owner time out into RESERVED while peer stays alive.
    env.advance(10_000);
    await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken });
    env.advance(6_000);
    const reserveSweep = await env.store.sweep();
    assert.equal(reserveSweep.changed, true);
    const room = env.store.rooms.get(roomId);
    assert.equal(room.members.get('dev-owner').status, 'RESERVED');
    assert.equal(env.fakeVolc.canJoin(peerRtc), true);

    // Advance right to owner's reservation expiry while keeping peer alive.
    await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken });
    for (let step = 0; step < 5; step += 1) {
      env.advance(10_500);
      await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken });
    }
    const remaining = room.members.get('dev-owner').reservedUntilMs - env.getNow();
    if (remaining > 0) env.advance(remaining);
    const revBefore = room.revision;

    // 1) Persistent HTTP 500 on OpenAPI MUST keep room unended, refuse owner /resume and peer /rtc-token.
    env.fakeVolc.setFailStatus(500);
    const failedSweep = await env.store.sweep();
    assert.equal(failedSweep.changed, false);
    assert.equal(failedSweep.errors.length, 1);
    assert.equal(failedSweep.errors[0].code, 'RTC_REVOCATION_FAILED');
    assert.equal(room.ended, false);
    assert.equal(room.members.size, 2);
    assert.equal(room.revision, revBefore);

    const blockedOwnerResume = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: ownerToken });
    assert.equal(blockedOwnerResume.status, 502);
    assert.equal(blockedOwnerResume.json.code, 'RTC_REVOCATION_FAILED');

    const blockedPeerRefresh = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(blockedPeerRefresh.status, 502);
    assert.equal(blockedPeerRefresh.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(room.ended, false);

    // 2) Delayed OpenAPI (hanging Promise) MUST keep room.ended === false until room-level BanRoomUser completes,
    //    and peer's token MUST be rejected by FakeVolcRtcServer BEFORE room ends.
    env.fakeVolc.setFailStatus(0);
    env.advance(3_000);
    let stateAtBanMoment = null;
    env.fakeVolc.setOnBanApplied(() => {
      stateAtBanMoment = {
        ended: room.ended,
        revision: room.revision,
        canJoinPeerAfterBan: env.fakeVolc.canJoin(peerRtc)
      };
    });
    const gate = env.fakeVolc.armGate();
    const sweepPromise = env.store.sweep();
    const hitCall = await gate.hit;
    assert.equal(hitCall.action, 'BanRoomUser');
    assert.equal(hitCall.userId, null);
    assert.equal(hitCall.roomId, peerRtc.rtcRoomId);
    assert.equal(room.ended, false);
    assert.equal(room.revision, revBefore);

    gate.release();
    const okSweep = await sweepPromise;
    env.fakeVolc.setOnBanApplied(null);
    assert.equal(okSweep.changed, true);
    assert.equal(okSweep.errors.length, 0);
    assert.deepEqual(stateAtBanMoment, {
      ended: false,
      revision: revBefore,
      canJoinPeerAfterBan: false
    });
    assert.equal(env.fakeVolc.canJoin(peerRtc), false);
    assert.equal(room.ended, true);
    assert.equal(room.endedReason, 'OWNER_RECOVERY_EXPIRED');

    const endedPoll = await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken });
    assert.equal(endedPoll.status, 410);
    assert.equal(endedPoll.json.code, 'ROOM_ENDED');
    assert.equal(endedPoll.json.endedReason, 'OWNER_RECOVERY_EXPIRED');
  } finally {
    await env.close();
  }
});

test('HYBRID 50m RSSI margin (-75 dBm) and silent preconnect failure/timeout back off without interrupting CLOUD RTC', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 120_000, localSignalStableMs: 12_000, localPreconnectTimeoutMs: 20_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-host', runtimeId: 'run-host', nickname: 'Host', requireApproval: false, mode: 'HYBRID', supportsFastP2p: true }
    });
    const roomId = owner.json.roomId;
    const hostToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    const peerToken = peer.json.sessionToken;

    const fb = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 1 }
    });
    assert.equal(fb.json.snapshot.transport.active, 'CLOUD');
    assert.equal(fb.json.snapshot.transport.epoch, 2);
    assert.equal(fb.json.snapshot.transport.localP2pPassphrase, null);
    assert.equal(fb.json.snapshot.transport.localCode, null);

    // Host gets Route A preset passphrase during auto-recoverable CLOUD so it can bring up the 2.4GHz beacon GO.
    const hostSnap = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: false }
    });
    assert.match(hostSnap.json.snapshot.transport.localP2pSsid, /^DIRECT-mc-[a-z0-9]{8}$/);
    assert.match(hostSnap.json.snapshot.transport.localP2pPassphrase, /^[A-Za-z0-9]{16}$/);
    assert.equal(hostSnap.json.snapshot.transport.localCode, null);

    const bansBefore = env.bannedRooms.length;
    env.advance(16_000);

    // Fringe Wi-Fi signal (-85 dBm < -75 dBm) MUST NOT enter PROBING or PRECONNECTING.
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true, localHostAddress: '192.168.49.1', localFrequencyMhz: 2412 }
    });
    const weak = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -85 }
    });
    assert.equal(weak.json.snapshot.transport.recoveryStage, 'IDLE');
    env.advance(15_000);
    const stillWeak = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -76 }
    });
    assert.equal(stillWeak.json.snapshot.transport.recoveryStage, 'IDLE');

    // Strong signal (-75 dBm boundary) starts 12s stability window, then enters PRECONNECTING.
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    const probeStart = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -75 }
    });
    assert.equal(probeStart.json.snapshot.transport.recoveryStage, 'PROBING');
    env.advance(12_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    const preconnect1 = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -73 }
    });
    assert.equal(preconnect1.json.snapshot.transport.recoveryStage, 'PRECONNECTING');
    const preconnect1Ssid = preconnect1.json.snapshot.transport.localP2pSsid;
    const preconnect1Pass = preconnect1.json.snapshot.transport.localP2pPassphrase;
    assert.match(preconnect1Pass, /^[A-Za-z0-9]{16}$/);

    // Member reports preconnectFailed=true -> silent abort + rotated credentials + 30s backoff; CLOUD RTC untouched!
    const failed = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, preconnectFailed: true }
    });
    assert.equal(failed.json.switched, false);
    assert.equal(failed.json.snapshot.transport.active, 'CLOUD');
    assert.equal(failed.json.snapshot.transport.epoch, 2);
    assert.equal(failed.json.snapshot.transport.recoveryStage, 'IDLE');
    assert.equal(failed.json.snapshot.transport.localCode, null);
    assert.equal(failed.json.snapshot.transport.localP2pPassphrase, null);
    assert.notEqual(failed.json.snapshot.transport.localP2pSsid, preconnect1Ssid);
    assert.equal(failed.json.snapshot.transport.cooldownUntilMs - env.getNow(), 30_000);
    assert.equal(env.bannedRooms.length, bansBefore);

    // After 30s backoff, re-enter PRECONNECTING and verify timeout (20s) also silently backs off to 60s without BanRoomUser.
    env.advance(31_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true, localHostAddress: '192.168.49.1', localFrequencyMhz: 2462 }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -69 }
    });
    env.advance(12_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    const preconnect2 = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -69 }
    });
    assert.equal(preconnect2.json.snapshot.transport.recoveryStage, 'PRECONNECTING');

    env.advance(20_000);
    const sweepRes = await env.store.sweep();
    assert.equal(sweepRes.changed, true);
    const afterTimeout = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -69 }
    });
    assert.equal(afterTimeout.json.snapshot.transport.active, 'CLOUD');
    assert.equal(afterTimeout.json.snapshot.transport.recoveryStage, 'IDLE');
    assert.equal(afterTimeout.json.snapshot.transport.cooldownUntilMs - env.getNow(), 60_000);
    assert.equal(env.bannedRooms.length, bansBefore);
    assert.equal(env.logs.join('\n').includes(preconnect1Pass), false);
  } finally {
    await env.close();
  }
});

test('HYBRID member removal during PRECONNECTING aborts preconnect and rotates Route A credentials without touching CLOUD RTC', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 120_000, localSignalStableMs: 12_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-host', runtimeId: 'run-host', nickname: 'Host', requireApproval: false, mode: 'HYBRID', supportsFastP2p: true }
    });
    const roomId = owner.json.roomId;
    const hostToken = owner.json.sessionToken;
    const peerB = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-b', runtimeId: 'run-b', nickname: 'B' }
    });
    const peerC = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-c', runtimeId: 'run-c', nickname: 'C' }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerB.json.sessionToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 1 }
    });
    env.advance(16_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true, localHostAddress: '192.168.49.1', localFrequencyMhz: 2437 }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerB.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -68 }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerC.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -68 }
    });
    env.advance(12_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerB.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -68 }
    });
    const pre = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerC.json.sessionToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -68 }
    });
    assert.equal(pre.json.snapshot.transport.recoveryStage, 'PRECONNECTING');
    const leakedSsid = pre.json.snapshot.transport.localP2pSsid;
    const leakedPass = pre.json.snapshot.transport.localP2pPassphrase;
    const rev = pre.json.snapshot.revision;

    const removed = await env.api('POST', `/v1/rooms/${roomId}/members/dev-c/remove`, {
      token: hostToken,
      body: { expectedRevision: rev }
    });
    assert.equal(removed.status, 200);
    assert.equal(removed.json.snapshot.transport.active, 'CLOUD');
    assert.equal(removed.json.snapshot.transport.recoveryStage, 'IDLE');
    assert.equal(removed.json.snapshot.transport.localCode, null);
    assert.notEqual(removed.json.snapshot.transport.localP2pSsid, leakedSsid);
    assert.notEqual(removed.json.snapshot.transport.localP2pPassphrase, leakedPass);
  } finally {
    await env.close();
  }
});

test('HYBRID non-host member removal during LOCAL rotates epoch, localCode, and Route A credentials without issuing RTC or logging secrets', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 120_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-host', runtimeId: 'run-host', nickname: 'Host', requireApproval: false, mode: 'HYBRID', supportsFastP2p: true }
    });
    const roomId = owner.json.roomId;
    const hostToken = owner.json.sessionToken;
    const peerB = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-b', runtimeId: 'run-b', nickname: 'B' }
    });
    const peerC = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-c', runtimeId: 'run-c', nickname: 'C' }
    });
    const hostReported = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 1, localReady: true, localHostAddress: '192.168.49.1', localFrequencyMhz: 2437 }
    });
    assert.equal(hostReported.status, 200);
    const before = hostReported.json.snapshot.transport;
    assert.equal(before.active, 'LOCAL');
    assert.equal(before.epoch, 1);
    assert.equal(before.localHostAddress, '192.168.49.1');
    assert.equal(before.localFrequencyMhz, 2437);

    const removed = await env.api('POST', `/v1/rooms/${roomId}/members/dev-c/remove`, {
      token: hostToken,
      body: { expectedRevision: hostReported.json.snapshot.revision }
    });
    assert.equal(removed.status, 200);
    assert.equal(removed.json.snapshot.rtcRoomId, null);
    const after = removed.json.snapshot.transport;
    assert.equal(after.active, 'LOCAL');
    assert.equal(after.epoch, 2);
    assert.equal(after.reason, 'MEMBER_REMOVED');
    assert.equal(after.localHostDeviceId, 'dev-host');
    assert.equal(after.localHostAddress, null);
    assert.equal(after.localFrequencyMhz, null);
    assert.equal(after.handoverFromEpoch, null);
    assert.notEqual(after.localCode, before.localCode);
    assert.notEqual(after.localP2pSsid, before.localP2pSsid);
    assert.notEqual(after.localP2pPassphrase, before.localP2pPassphrase);
    assert.match(after.localCode, /^\d{6}$/);
    assert.match(after.localP2pSsid, /^DIRECT-mc-[a-z0-9]{8}$/);
    assert.match(after.localP2pPassphrase, /^[A-Za-z0-9]{16}$/);

    // Remaining admitted member still cannot obtain CLOUD RTC credentials during LOCAL.
    const deniedRtc = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerB.json.sessionToken
    });
    assert.equal(deniedRtc.status, 409);
    assert.equal(deniedRtc.json.code, 'TRANSPORT_NOT_CLOUD');

    const allLogs = env.logs.join('\n');
    for (const secret of [
      before.localCode,
      after.localCode,
      before.localP2pPassphrase,
      after.localP2pPassphrase,
      owner.json.snapshot.invite.code
    ]) {
      assert.equal(allLogs.includes(secret), false);
    }
  } finally {
    await env.close();
  }
});

test('HYBRID BLE-only fallback (localRssiDbm = null) enforces cooldown, 12s stability window, and PRECONNECTING control-ready handover', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 120_000, localSignalStableMs: 12_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-host', runtimeId: 'run-host', nickname: 'Host', requireApproval: false, mode: 'HYBRID', supportsFastP2p: true }
    });
    const roomId = owner.json.roomId;
    const hostToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-peer', nickname: 'Peer' }
    });
    const peerToken = peer.json.sessionToken;

    await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: peerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedTransportEpoch: 1 }
    });

    // Before 15s cooldown elapses, BLE-only fallback (localRssiDbm = null) must remain IDLE.
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    const duringCooldown = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: null }
    });
    assert.equal(duringCooldown.json.snapshot.transport.recoveryStage, 'IDLE');

    // After cooldown, localRssiDbm = null enters PROBING and requires 12s stability before PRECONNECTING.
    env.advance(16_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true, localHostAddress: '192.168.49.1', localFrequencyMhz: 2412 }
    });
    const probing = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: null }
    });
    assert.equal(probing.json.snapshot.transport.recoveryStage, 'PROBING');

    env.advance(10_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    const notYetStable = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: null }
    });
    assert.equal(notYetStable.json.snapshot.transport.recoveryStage, 'PROBING');

    env.advance(2_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    const preconnecting = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: null }
    });
    assert.equal(preconnecting.json.snapshot.transport.recoveryStage, 'PRECONNECTING');
    assert.equal(preconnecting.json.switched, false);

    // Only switches CLOUD -> LOCAL after all admitted members report localControlReady = true.
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: hostToken,
      body: { transportEpoch: 2, localProbeActive: true, localControlReady: true }
    });
    const switched = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: null, localControlReady: true }
    });
    assert.equal(switched.json.switched, true);
    assert.equal(switched.json.snapshot.transport.active, 'LOCAL');
    assert.equal(switched.json.snapshot.transport.epoch, 3);
    assert.equal(switched.json.snapshot.transport.handoverFromEpoch, 2);
  } finally {
    await env.close();
  }
});

test('RTC admin enforces request/body timeout and releases per-room lock on stalled OpenAPI', async () => {
  let signalAborted = false;
  const stalledHeadersAdmin = createRtcAdmin({
    appId: APP_ID,
    accessKeyId: 'test-access-id',
    secretKey: 'test-secret-value',
    timeoutMs: 40,
    fetchImpl: async (_url, init) => {
      init.signal.addEventListener('abort', () => {
        signalAborted = true;
      }, { once: true });
      return new Promise(() => {});
    }
  });
  await assert.rejects(
    () => stalledHeadersAdmin.banUser('room-timeout-1', 'user-timeout-1', 300),
    /timed out after 40ms/
  );
  assert.equal(signalAborted, true);

  let bodySignalAborted = false;
  const stalledBodyAdmin = createRtcAdmin({
    appId: APP_ID,
    accessKeyId: 'test-access-id',
    secretKey: 'test-secret-value',
    timeoutMs: 40,
    fetchImpl: async (_url, init) => {
      init.signal.addEventListener('abort', () => {
        bodySignalAborted = true;
      }, { once: true });
      const stream = new ReadableStream({
        start(controller) {
          controller.enqueue(new TextEncoder().encode('{"ResponseMetadata":'));
        }
      });
      return new Response(stream, { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
  });
  await assert.rejects(
    () => stalledBodyAdmin.banUser('room-timeout-2', 'user-timeout-2', 300),
    /timed out after 40ms/
  );
  assert.equal(bodySignalAborted, true);

  let stallNextCall = true;
  const storeAdmin = createRtcAdmin({
    appId: APP_ID,
    accessKeyId: 'test-access-id',
    secretKey: 'test-secret-value',
    timeoutMs: 40,
    fetchImpl: async () => {
      if (stallNextCall) {
        stallNextCall = false;
        return new Promise(() => {});
      }
      return Response.json({ ResponseMetadata: {}, Result: { Message: 'success' } });
    }
  });
  const store = new GroupCloudStore({
    appId: APP_ID,
    appKey: APP_KEY,
    rtcAdmin: storeAdmin
  });
  const created = await store.createRoom({
    deviceId: 'host-dev',
    runtimeId: 'host-run',
    nickname: '队长',
    requireApproval: false
  });
  const joined = await store.submitJoinRequest({
    inviteCode: created.snapshot.invite.code,
    deviceId: 'peer-dev',
    runtimeId: 'peer-run',
    nickname: '队员'
  });
  assert.equal(joined.status, 'ADMITTED');

  await assert.rejects(
    () => store.removeMember(created.sessionToken, created.roomId, 'peer-dev', {
      expectedRevision: joined.snapshot.revision
    }),
    (error) => error?.code === 'RTC_REVOCATION_FAILED' && error?.status === 502
  );

  const retrySnap = await store.removeMember(created.sessionToken, created.roomId, 'peer-dev', {
    expectedRevision: joined.snapshot.revision
  });
  assert.equal(retrySnap.snapshot.members.some((member) => member.deviceId === 'peer-dev'), false);
  await store.sweep();

  const declaredOversizeAdmin = createRtcAdmin({
    appId: APP_ID,
    accessKeyId: 'test-access-id',
    secretKey: 'test-secret-value',
    fetchImpl: async () => new Response('{}', {
      status: 200,
      headers: { 'Content-Type': 'application/json', 'Content-Length': String(128 * 1024) }
    })
  });
  await assert.rejects(
    () => declaredOversizeAdmin.banUser('room-oversize-1', 'user-oversize-1', 300),
    /exceeded 65536 bytes/
  );

  const streamedOversizeAdmin = createRtcAdmin({
    appId: APP_ID,
    accessKeyId: 'test-access-id',
    secretKey: 'test-secret-value',
    fetchImpl: async () => {
      const chunk = new Uint8Array(40 * 1024).fill(0x78);
      const stream = new ReadableStream({
        start(controller) {
          controller.enqueue(chunk);
          controller.enqueue(chunk);
          controller.close();
        }
      });
      return new Response(stream, { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
  });
  await assert.rejects(
    () => streamedOversizeAdmin.banUser('room-oversize-2', 'user-oversize-2', 300),
    /exceeded 65536 bytes/
  );
});

test('HYBRID CLOUD -> LOCAL partial channel revocation failure blocks re-issuance on banned channel and re-enters cleanly', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: '队员' }
    });
    assert.equal(peer.status, 200);
    const peerToken = peer.json.sessionToken;
    let rev = peer.json.snapshot.revision;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    rev = toCloud.json.snapshot.revision;

    const peerPubRes = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(peerPubRes.status, 200);
    const peerPubRtc = peerPubRes.json.rtc;
    assert.equal(env.canJoinRtc(peerPubRtc), true);

    const toRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(toRider.status, 200);
    const ownerRiderRtc = toRider.json.rtc;
    assert.notEqual(ownerRiderRtc.rtcRoomId, peerPubRtc.rtcRoomId);
    assert.equal(env.canJoinRtc(ownerRiderRtc), true);
    rev = toRider.json.snapshot.revision;

    let banRoomAttempts = 0;
    let failedSecondChannel = false;
    env.faults.banRoom = () => {
      banRoomAttempts += 1;
      if (banRoomAttempts === 2 && !failedSecondChannel) {
        failedSecondChannel = true;
        return new Error('Volc OpenAPI 500 on second channel');
      }
      return null;
    };

    const failedSwitch = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(failedSwitch.status, 502);
    assert.equal(failedSwitch.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.bannedRooms.length, 1);
    assert.equal(env.bannedRooms[0].roomId, peerPubRtc.rtcRoomId);
    assert.equal(env.canJoinRtc(peerPubRtc), false);
    assert.equal(env.canJoinRtc(ownerRiderRtc), true);

    const roomRecord = env.store.rooms.get(roomId);
    assert.equal(roomRecord.transport.active, 'CLOUD');
    assert.equal(roomRecord.transport.epoch, 2);
    assert.equal(roomRecord.revision, rev);
    assert.equal(roomRecord.issuedRtcRoomIds.has(peerPubRtc.rtcRoomId), false);
    assert.equal(roomRecord.issuedRtcRoomIds.has(ownerRiderRtc.rtcRoomId), true);
    assert.equal(roomRecord.rtcRoomRevocations.get(peerPubRtc.rtcRoomId)?.status, 'REVOKED');
    assert.equal(roomRecord.rtcRoomRevocations.get(ownerRiderRtc.rtcRoomId)?.status, 'FAILED');

    const blockedPeerRefresh = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(blockedPeerRefresh.status, 502);
    assert.equal(blockedPeerRefresh.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(blockedPeerRefresh.json.rtc, undefined);
    assert.equal(roomRecord.issuedRtcRoomIds.has(peerPubRtc.rtcRoomId), false);

    const blockedPeerResume = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: peerToken });
    assert.equal(blockedPeerResume.status, 502);
    assert.equal(blockedPeerResume.json.code, 'RTC_REVOCATION_FAILED');

    const blockedOwnerRefresh = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken });
    assert.equal(blockedOwnerRefresh.status, 502);
    assert.equal(blockedOwnerRefresh.json.code, 'RTC_REVOCATION_FAILED');

    const blockedChannelSwitch = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'PUBLIC', expectedRevision: rev }
    });
    assert.equal(blockedChannelSwitch.status, 502);
    assert.equal(blockedChannelSwitch.json.code, 'RTC_REVOCATION_FAILED');

    const blockedJoin = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-late', runtimeId: 'run-l', nickname: '后入队员' }
    });
    assert.equal(blockedJoin.status, 502);
    assert.equal(blockedJoin.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(roomRecord.members.has('dev-late'), false);

    env.faults.banRoom = null;
    const retrySwitch = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(retrySwitch.status, 200);
    assert.equal(retrySwitch.json.switched, true);
    assert.equal(retrySwitch.json.rtc, null);
    assert.equal(retrySwitch.json.snapshot.transport.active, 'LOCAL');
    assert.equal(retrySwitch.json.snapshot.transport.epoch, 3);
    assert.equal(env.bannedRooms.length, 2);
    assert.equal(env.bannedRooms[1].roomId, ownerRiderRtc.rtcRoomId);
    assert.equal(env.canJoinRtc(peerPubRtc), false);
    assert.equal(env.canJoinRtc(ownerRiderRtc), false);
    assert.equal(roomRecord.rtcRoomRevocations.get(ownerRiderRtc.rtcRoomId)?.status, 'REVOKED');
    rev = retrySwitch.json.snapshot.revision;

    const backToCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 3 }
    });
    assert.equal(backToCloud.status, 200);
    assert.equal(backToCloud.json.snapshot.transport.epoch, 4);
    assert.equal(env.canJoinRtc(backToCloud.json.rtc), true);
    const peerNewCloudRtc = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(peerNewCloudRtc.status, 200);
    assert.equal(env.canJoinRtc(peerNewCloudRtc.json.rtc), true);
    assert.notEqual(peerNewCloudRtc.json.rtc.rtcRoomId, peerPubRtc.rtcRoomId);
  } finally {
    await env.close();
  }
});

test('HYBRID auto-recovery PRECONNECTING partial revocation failure completes on retry and rotates cleanly on preconnect abort', async () => {
  const env = await startTestServer({
    disconnectTimeoutMs: 120_000,
    localSignalStableMs: 5_000,
    localPreconnectTimeoutMs: 10_000
  });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: '队员' }
    });
    const peerToken = peer.json.sessionToken;
    let rev = peer.json.snapshot.revision;

    const fallbackCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(fallbackCloud.status, 200);
    rev = fallbackCloud.json.snapshot.revision;

    const peerPub = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(peerPub.status, 200);
    const ownerRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(ownerRider.status, 200);
    rev = ownerRider.json.snapshot.revision;

    env.advance(16_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -62 }
    });
    env.advance(5_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    const preconnect = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
    });
    assert.equal(preconnect.json.snapshot.transport.recoveryStage, 'PRECONNECTING');

    env.advance(8_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true, localControlReady: true }
    });

    let attempts = 0;
    env.faults.banRoom = () => {
      attempts += 1;
      return attempts === 2 ? new Error('OpenAPI timeout on 2nd channel') : null;
    };
    const failedHandover = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60, localControlReady: true }
    });
    assert.equal(failedHandover.status, 502);
    assert.equal(failedHandover.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.bannedRooms.length, 1);

    const deniedRefresh = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(deniedRefresh.status, 502);
    assert.equal(deniedRefresh.json.code, 'RTC_REVOCATION_FAILED');

    // Even after the 10s preconnect window elapses during OpenAPI recovery, retry finishes LOCAL_RECOVERED.
    env.advance(4_000);
    env.faults.banRoom = null;
    const completedHandover = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60, localControlReady: true }
    });
    assert.equal(completedHandover.status, 200);
    assert.equal(completedHandover.json.switched, true);
    assert.equal(completedHandover.json.snapshot.transport.active, 'LOCAL');
    assert.equal(completedHandover.json.snapshot.transport.epoch, 3);
    assert.equal(completedHandover.json.snapshot.transport.reason, 'LOCAL_RECOVERED');
    assert.equal(completedHandover.json.snapshot.transport.handoverFromEpoch, 2);
    assert.equal(env.bannedRooms.length, 2);
    assert.equal(env.canJoinRtc(peerPub.json.rtc), false);
    assert.equal(env.canJoinRtc(ownerRider.json.rtc), false);
    rev = completedHandover.json.snapshot.revision;

    // Second cycle: partial revocation during PRECONNECTING followed by preconnectFailed rotates to fresh CLOUD epoch.
    const secondFallback = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedRevision: rev, expectedTransportEpoch: 3 }
    });
    assert.equal(secondFallback.status, 200);
    const epoch4OwnerRtc = secondFallback.json.rtc;
    const epoch4PeerRtc = (await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken })).json.rtc;
    assert.notEqual(epoch4OwnerRtc.rtcRoomId, epoch4PeerRtc.rtcRoomId);

    env.advance(31_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 4, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 4, localReachable: true, localRssiDbm: -62 }
    });
    env.advance(5_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 4, localProbeActive: true }
    });
    const preconnect2 = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 4, localReachable: true, localRssiDbm: -60 }
    });
    assert.equal(preconnect2.json.snapshot.transport.recoveryStage, 'PRECONNECTING');

    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 4, localProbeActive: true, localControlReady: true }
    });
    let attempts2 = 0;
    env.faults.banRoom = () => {
      attempts2 += 1;
      return attempts2 === 2 ? new Error('OpenAPI 502 on 2nd channel') : null;
    };
    const failedHandover2 = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 4, localReachable: true, localRssiDbm: -60, localControlReady: true }
    });
    assert.equal(failedHandover2.status, 502);
    assert.equal(env.bannedRooms.length, 3);

    // Reporting preconnectFailed inside cooldown records CLOUD_ROTATE preserving nextRetryAtMs without calling OpenAPI.
    const roomBeforeAbort = env.store.rooms.get(roomId);
    const preservedRetryAt = roomBeforeAbort.pendingEpochRevocation.nextRetryAtMs;
    env.faults.banRoom = null;
    const abortDuringCooldown = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 4, localReachable: true, preconnectFailed: true }
    });
    assert.equal(abortDuringCooldown.status, 200);
    assert.equal(abortDuringCooldown.json.switched, false);
    assert.equal(abortDuringCooldown.json.snapshot.transport.epoch, 4);
    assert.equal(env.bannedRooms.length, 3);
    assert.equal(roomBeforeAbort.pendingEpochRevocation.intent, 'CLOUD_ROTATE');
    assert.equal(roomBeforeAbort.pendingEpochRevocation.nextRetryAtMs, preservedRetryAt);

    // Once retry cooldown elapses, background sweep finishes CLOUD_ROTATE into fresh CLOUD epoch 5.
    env.advance(3_000);
    const sweptRotate = await env.store.sweep();
    assert.equal(sweptRotate.changed, true);
    assert.equal(sweptRotate.errors.length, 0);
    assert.equal(roomBeforeAbort.transport.active, 'CLOUD');
    assert.equal(roomBeforeAbort.transport.epoch, 5);
    assert.equal(roomBeforeAbort.transport.recoveryStage, 'IDLE');
    assert.equal(env.bannedRooms.length, 4);
    assert.equal(env.canJoinRtc(epoch4OwnerRtc), false);
    assert.equal(env.canJoinRtc(epoch4PeerRtc), false);

    const epoch5Peer = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    const epoch5Owner = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken });
    assert.equal(epoch5Peer.status, 200);
    assert.equal(epoch5Owner.status, 200);
    assert.equal(env.canJoinRtc(epoch5Peer.json.rtc), true);
    assert.equal(env.canJoinRtc(epoch5Owner.json.rtc), true);
  } finally {
    await env.close();
  }
});

test('HYBRID closePrivate partial failure blocks banned private room re-issue without blocking PUBLIC members', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 60_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    const peerA = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-a', runtimeId: 'run-a', nickname: 'A' }
    });
    const peerB = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-b', runtimeId: 'run-b', nickname: 'B' }
    });
    let rev = peerB.json.snapshot.revision;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    rev = toCloud.json.snapshot.revision;

    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerA.json.sessionToken })).status, 200);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerB.json.sessionToken })).status, 200);

    const invite = await env.api('POST', `/v1/rooms/${roomId}/private-invites`, {
      token: ownerToken,
      body: { targetDeviceId: 'dev-a', expectedRevision: rev }
    });
    assert.equal(invite.status, 201);
    rev = invite.json.snapshot.revision;

    const accepted = await env.api('POST', `/v1/rooms/${roomId}/private-invites/${invite.json.inviteId}/accept`, {
      token: peerA.json.sessionToken,
      body: { expectedRevision: rev }
    });
    assert.equal(accepted.status, 200);
    const privateRtc = accepted.json.rtc;
    rev = accepted.json.snapshot.revision;

    env.faults.unbanUser = new Error('Volc OpenAPI unban failed');
    const failedClose = await env.api('POST', `/v1/rooms/${roomId}/private/close`, {
      token: peerA.json.sessionToken,
      body: { expectedRevision: rev }
    });
    assert.equal(failedClose.status, 502);
    assert.equal(failedClose.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.bannedRooms.length, 1);
    assert.equal(env.bannedRooms[0].roomId, privateRtc.rtcRoomId);
    assert.equal(env.canJoinRtc(privateRtc), false);

    const blockedPrivateRefresh = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerA.json.sessionToken
    });
    assert.equal(blockedPrivateRefresh.status, 502);
    assert.equal(blockedPrivateRefresh.json.code, 'RTC_REVOCATION_FAILED');
    const roomRecord = env.store.rooms.get(roomId);
    assert.equal(roomRecord.rtcRoomRevocations.get(privateRtc.rtcRoomId)?.status, 'REVOKED');
    // Sweeping while private close is still uncommitted keeps the PRIVATE/REVOKED guard active.
    await env.store.sweep();
    assert.equal(roomRecord.rtcRoomRevocations.get(privateRtc.rtcRoomId)?.status, 'REVOKED');

    // Peer B in PUBLIC is unaffected by PRIVATE channel revocation.
    const healthyPublicRefresh = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerB.json.sessionToken
    });
    assert.equal(healthyPublicRefresh.status, 200);
    assert.equal(env.canJoinRtc(healthyPublicRefresh.json.rtc), true);

    // Retrying closePrivate skips re-banning privateRtc and returns peerA cleanly to PUBLIC.
    const okClose = await env.api('POST', `/v1/rooms/${roomId}/private/close`, {
      token: peerA.json.sessionToken,
      body: { expectedRevision: rev }
    });
    assert.equal(okClose.status, 200);
    assert.equal(env.bannedRooms.length, 1);
    assert.equal(env.canJoinRtc(okClose.json.rtc), true);
    assert.equal(roomRecord.rtcRoomRevocations.size, 0);
    rev = okClose.json.snapshot.revision;

    // Repeated private sessions in the same long-lived CLOUD epoch must not accumulate PRIVATE/REVOKED entries.
    for (let round = 0; round < 5; round += 1) {
      const loopInvite = await env.api('POST', `/v1/rooms/${roomId}/private-invites`, {
        token: ownerToken,
        body: { targetDeviceId: 'dev-a', expectedRevision: rev }
      });
      assert.equal(loopInvite.status, 201);
      rev = loopInvite.json.snapshot.revision;
      const loopAccept = await env.api('POST', `/v1/rooms/${roomId}/private-invites/${loopInvite.json.inviteId}/accept`, {
        token: peerA.json.sessionToken,
        body: { expectedRevision: rev }
      });
      assert.equal(loopAccept.status, 200);
      rev = loopAccept.json.snapshot.revision;
      const loopClose = await env.api('POST', `/v1/rooms/${roomId}/private/close`, {
        token: peerA.json.sessionToken,
        body: { expectedRevision: rev }
      });
      assert.equal(loopClose.status, 200);
      rev = loopClose.json.snapshot.revision;
    }
    await env.store.sweep();
    assert.equal(roomRecord.rtcRoomRevocations.size, 0);
    const bannedBeforeEnd = env.bannedRooms.length;

    // Also verify endRoom partial multi-channel revocation failure blocks re-issuance and completes on retry.
    const ownerRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(ownerRider.status, 200);
    rev = ownerRider.json.snapshot.revision;
    let endAttempts = 0;
    env.faults.banRoom = () => {
      endAttempts += 1;
      return endAttempts === 2 ? new Error('OpenAPI 502 during endRoom') : null;
    };
    const failedEnd = await env.api('POST', `/v1/rooms/${roomId}/end`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(failedEnd.status, 502);
    assert.equal(failedEnd.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.bannedRooms.length, bannedBeforeEnd + 1);

    const blockedDuringEnd = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerB.json.sessionToken
    });
    assert.equal(blockedDuringEnd.status, 502);
    assert.equal(blockedDuringEnd.json.code, 'RTC_REVOCATION_FAILED');

    env.faults.banRoom = null;
    const okEnd = await env.api('POST', `/v1/rooms/${roomId}/end`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(okEnd.status, 200);
    assert.equal(okEnd.json.ended, true);
    assert.equal(env.bannedRooms.length, bannedBeforeEnd + 2);
  } finally {
    await env.close();
  }
});

test('sweep completes timed-out PRECONNECTING handover when partial revocation already started and allControlReady is true', async () => {
  const env = await startTestServer({
    disconnectTimeoutMs: 120_000,
    localSignalStableMs: 5_000,
    localPreconnectTimeoutMs: 10_000
  });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: '队员' }
    });
    const peerToken = peer.json.sessionToken;
    let rev = peer.json.snapshot.revision;

    const fallbackCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(fallbackCloud.status, 200);
    rev = fallbackCloud.json.snapshot.revision;

    const peerPub = (await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken })).json.rtc;
    const ownerRiderRes = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    const ownerRider = ownerRiderRes.json.rtc;
    rev = ownerRiderRes.json.snapshot.revision;

    env.advance(16_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -62 }
    });
    env.advance(5_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    const preconnect = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
    });
    assert.equal(preconnect.json.snapshot.transport.recoveryStage, 'PRECONNECTING');

    env.advance(8_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true, localControlReady: true }
    });
    let attempts = 0;
    env.faults.banRoom = () => {
      attempts += 1;
      return attempts === 2 ? new Error('OpenAPI timeout on 2nd channel') : null;
    };
    const failedHandover = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60, localControlReady: true }
    });
    assert.equal(failedHandover.status, 502);
    assert.equal(env.bannedRooms.length, 1);

    // Advance past preconnect timeout with NO further client reports; background sweep must complete LOCAL_RECOVERED.
    env.advance(4_000);
    env.faults.banRoom = new Error('OpenAPI still failing during sweep');
    const failedSweep = await env.store.sweep();
    assert.equal(failedSweep.changed, false);
    assert.equal(failedSweep.errors.length, 1);
    assert.equal(env.store.rooms.get(roomId).transport.active, 'CLOUD');

    // Within retry backoff window, another sweep does not re-attempt OpenAPI.
    const throttledSweep = await env.store.sweep();
    assert.equal(throttledSweep.changed, false);
    assert.equal(throttledSweep.errors.length, 0);

    env.advance(6_000);
    const recoveredSweep = await env.store.sweep();
    assert.equal(recoveredSweep.changed, true);
    assert.equal(recoveredSweep.errors.length, 0);
    const roomRecord = env.store.rooms.get(roomId);
    assert.equal(roomRecord.transport.active, 'LOCAL');
    assert.equal(roomRecord.transport.epoch, 3);
    assert.equal(roomRecord.transport.reason, 'LOCAL_RECOVERED');
    assert.equal(roomRecord.transport.handoverFromEpoch, 2);
    assert.equal(env.bannedRooms.length, 2);
    assert.equal(env.canJoinRtc(peerPub), false);
    assert.equal(env.canJoinRtc(ownerRider), false);
  } finally {
    await env.close();
  }
});

test('interleaved remove, ownership transfer, leave, and host reassignment recover cleanly after partial EPOCH revocation', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 20_000 });
  try {
    const triggerPartialEpochRevocation = async ({ roomId, ownerToken, peerToken, epoch, rev }) => {
      const peerRtc = (await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken })).json.rtc;
      assert.equal(env.canJoinRtc(peerRtc), true);
      const ownerRtc = (await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken })).json.rtc;
      assert.equal(env.canJoinRtc(ownerRtc), true);
      assert.notEqual(peerRtc.rtcRoomId, ownerRtc.rtcRoomId);

      let calls = 0;
      env.faults.banRoom = () => {
        calls += 1;
        return calls === 2 ? new Error('OpenAPI 502 on 2nd channel') : null;
      };
      const failedSwitch = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
        token: ownerToken,
        body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: epoch }
      });
      assert.equal(failedSwitch.status, 502);
      assert.equal(failedSwitch.json.code, 'RTC_REVOCATION_FAILED');
      env.faults.banRoom = null;
      return { peerRtc, ownerRtc };
    };

    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    const peerA = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-a', runtimeId: 'run-a', nickname: 'A' }
    });
    const peerB = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-b', runtimeId: 'run-b', nickname: 'B' }
    });
    const peerC = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-c', runtimeId: 'run-c', nickname: 'C' }
    });
    let rev = peerC.json.snapshot.revision;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    rev = toCloud.json.snapshot.revision;

    const ownerToRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(ownerToRider.status, 200);
    rev = ownerToRider.json.snapshot.revision;

    // 1. Interleaved removeMember after partial EPOCH revocation: fails closed if OpenAPI down, rotates CLOUD epoch on recovery.
    const firstPartial = await triggerPartialEpochRevocation({
      roomId, ownerToken, peerToken: peerA.json.sessionToken, epoch: 2, rev
    });
    env.faults.banRoom = new Error('OpenAPI still failing during removeMember');
    const failedRemove = await env.api('POST', `/v1/rooms/${roomId}/members/dev-c/remove`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(failedRemove.status, 502);
    assert.equal(env.store.rooms.get(roomId).members.has('dev-c'), true);

    const okRemove = await env.api('POST', `/v1/rooms/${roomId}/members/dev-c/remove`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(okRemove.status, 200);
    assert.equal(okRemove.json.snapshot.transport.active, 'CLOUD');
    assert.equal(okRemove.json.snapshot.transport.epoch, 3);
    assert.equal(env.canJoinRtc(firstPartial.peerRtc), false);
    assert.equal(env.canJoinRtc(firstPartial.ownerRtc), false);
    rev = okRemove.json.snapshot.revision;
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerA.json.sessionToken })).status, 200);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken })).status, 200);

    // 2. Interleaved transferOwnership (local host departs) after partial EPOCH revocation rotates CLOUD epoch cleanly.
    const secondPartial = await triggerPartialEpochRevocation({
      roomId, ownerToken, peerToken: peerA.json.sessionToken, epoch: 3, rev
    });
    const transferred = await env.api('POST', `/v1/rooms/${roomId}/owner-transfer`, {
      token: ownerToken,
      body: { targetDeviceId: 'dev-a', alsoLeave: false, expectedRevision: rev }
    });
    assert.equal(transferred.status, 200);
    assert.equal(transferred.json.snapshot.ownerId, 'dev-a');
    assert.equal(transferred.json.snapshot.transport.active, 'CLOUD');
    assert.equal(transferred.json.snapshot.transport.epoch, 4);
    assert.equal(transferred.json.snapshot.transport.localHostDeviceId, 'dev-a');
    assert.equal(env.canJoinRtc(secondPartial.peerRtc), false);
    assert.equal(env.canJoinRtc(secondPartial.ownerRtc), false);
    rev = transferred.json.snapshot.revision;

    // New owner dev-a enters RIDER; old owner dev-owner stays in PUBLIC.
    const newOwnerRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: peerA.json.sessionToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(newOwnerRider.status, 200);
    rev = newOwnerRider.json.snapshot.revision;
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken })).status, 200);

    // 3. Interleaved host reassignment in reportLocalStatus after partial EPOCH revocation rotates CLOUD epoch.
    const thirdHostPartial = await triggerPartialEpochRevocation({
      roomId, ownerToken: peerA.json.sessionToken, peerToken: ownerToken, epoch: 4, rev
    });
    const roomBeforeReassign = env.store.rooms.get(roomId);
    roomBeforeReassign.transport.localHostDeviceId = 'dev-b';
    roomBeforeReassign.members.get('dev-b').status = 'RESERVED';
    roomBeforeReassign.members.get('dev-b').reservedUntilMs = env.getNow() + 60_000;
    env.advance(16_000);
    const reassigned = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerA.json.sessionToken,
      body: { transportEpoch: 4, localProbeActive: true }
    });
    assert.equal(reassigned.status, 200);
    assert.equal(reassigned.json.switched, false);
    assert.equal(reassigned.json.snapshot.transport.active, 'CLOUD');
    assert.equal(reassigned.json.snapshot.transport.epoch, 5);
    assert.equal(reassigned.json.snapshot.transport.localHostDeviceId, 'dev-a');
    assert.equal(env.canJoinRtc(thirdHostPartial.peerRtc), false);
    assert.equal(env.canJoinRtc(thirdHostPartial.ownerRtc), false);
    rev = reassigned.json.snapshot.revision;
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken })).status, 200);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerA.json.sessionToken })).status, 200);

    // 4. Interleaved leaveRoom reducing admitted count below 2 after partial EPOCH revocation rotates CLOUD epoch.
    const resumedB = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: peerB.json.sessionToken });
    assert.equal(resumedB.status, 200);
    rev = resumedB.json.snapshot.revision;
    const transferToB = await env.api('POST', `/v1/rooms/${roomId}/owner-transfer`, {
      token: peerA.json.sessionToken,
      body: { targetDeviceId: 'dev-b', alsoLeave: false, expectedRevision: rev }
    });
    assert.equal(transferToB.status, 200);
    rev = transferToB.json.snapshot.revision;
    const bToRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: peerB.json.sessionToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(bToRider.status, 200);
    rev = bToRider.json.snapshot.revision;
    const rmOldOwner = await env.api('POST', `/v1/rooms/${roomId}/members/dev-owner/remove`, {
      token: peerB.json.sessionToken,
      body: { expectedRevision: rev }
    });
    assert.equal(rmOldOwner.status, 200);
    rev = rmOldOwner.json.snapshot.revision;

    const thirdPartial = await triggerPartialEpochRevocation({
      roomId, ownerToken: peerB.json.sessionToken, peerToken: peerA.json.sessionToken, epoch: 5, rev
    });
    const peerLeft = await env.api('POST', `/v1/rooms/${roomId}/leave`, {
      token: peerA.json.sessionToken
    });
    assert.equal(peerLeft.status, 200);
    const roomAfterLeave = env.store.rooms.get(roomId);
    assert.equal(roomAfterLeave.transport.active, 'CLOUD');
    assert.equal(roomAfterLeave.transport.epoch, 6);
    assert.equal(env.canJoinRtc(thirdPartial.peerRtc), false);
    assert.equal(env.canJoinRtc(thirdPartial.ownerRtc), false);
    const soleOwnerRtc = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, {
      token: peerB.json.sessionToken
    });
    assert.equal(soleOwnerRtc.status, 200);
    assert.equal(env.canJoinRtc(soleOwnerRtc.json.rtc), true);
  } finally {
    await env.close();
  }
});

test('timed-out BanRoomUser after RTC applied ban blocks re-issuance on single/first channel and recovers idempotently', async () => {
  const env = await startFakeVolcEnv({
    disconnectTimeoutMs: 120_000,
    rtcTimeoutMs: 80,
    revocationRetryCooldownMs: 3_000
  });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: '队员' }
    });
    const peerToken = peer.json.sessionToken;
    let rev = peer.json.snapshot.revision;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    const ownerPubRtc = toCloud.json.rtc;
    rev = toCloud.json.snapshot.revision;
    const peerPubRtc = (await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken })).json.rtc;
    assert.equal(peerPubRtc.rtcRoomId, ownerPubRtc.rtcRoomId);
    assert.equal(env.fakeVolc.canJoin(peerPubRtc), true);

    // 1) Single-channel CLOUD -> LOCAL: FakeVolc applies room ban first, then stalls response past rtcTimeoutMs.
    env.fakeVolc.delayNextBanResponseMs(220);
    const timedOutSwitch = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(timedOutSwitch.status, 502);
    assert.equal(timedOutSwitch.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.fakeVolc.canJoin(peerPubRtc), false);

    const room = env.store.rooms.get(roomId);
    assert.equal(room.transport.active, 'CLOUD');
    assert.equal(room.transport.epoch, 2);
    assert.equal(room.rtcRoomRevocations.get(peerPubRtc.rtcRoomId)?.status, 'FAILED');
    assert.equal(room.pendingEpochRevocation?.intent, 'MANUAL_LOCAL');

    // Re-issuance on that uncertain/banned single channel MUST fail closed (never clear FAILED or return broken token).
    const blockedRefresh = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(blockedRefresh.status, 502);
    assert.equal(blockedRefresh.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(room.rtcRoomRevocations.get(peerPubRtc.rtcRoomId)?.status, 'FAILED');

    const blockedResume = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: peerToken });
    assert.equal(blockedResume.status, 502);
    assert.equal(blockedResume.json.code, 'RTC_REVOCATION_FAILED');

    const blockedJoin = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-c', runtimeId: 'run-c', nickname: 'C' }
    });
    assert.equal(blockedJoin.status, 502);
    assert.equal(blockedJoin.json.code, 'RTC_REVOCATION_FAILED');

    // Background sweep after cooldown re-confirms ban ("Room has been banned.") and finishes LOCAL switch.
    env.advance(3_000);
    const recovered = await env.store.sweep();
    assert.equal(recovered.changed, true);
    assert.equal(recovered.errors.length, 0);
    assert.equal(room.transport.active, 'LOCAL');
    assert.equal(room.transport.epoch, 3);
    assert.equal(room.transport.reason, 'MANUAL_LOCAL');
    assert.equal(room.pendingEpochRevocation, null);
    assert.equal(room.rtcRoomRevocations.get(peerPubRtc.rtcRoomId)?.status, 'REVOKED');
    rev = room.revision;

    // 2) Switch back to fresh CLOUD epoch 4; verify timed-out user BanRoomUser in switchChannel unbans on /rtc-token.
    const backCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 3 }
    });
    assert.equal(backCloud.status, 200);
    rev = backCloud.json.snapshot.revision;
    const ownerEpoch4Pub = backCloud.json.rtc;
    assert.equal(env.fakeVolc.canJoin(ownerEpoch4Pub), true);

    env.fakeVolc.delayNextBanResponseMs(220);
    const timedOutChannelSwitch = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    assert.equal(timedOutChannelSwitch.status, 502);
    assert.equal(timedOutChannelSwitch.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.fakeVolc.canJoin(ownerEpoch4Pub), false);

    // Refreshing token on PUBLIC detects uncertain user ban and invokes UpdateBanRoomUserRule to restore access.
    const unbannedRefresh = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken });
    assert.equal(unbannedRefresh.status, 200);
    assert.equal(env.fakeVolc.canJoin(unbannedRefresh.json.rtc), true);

    // 3) Private channel close timeout after RTC ban blocks private refresh without affecting PUBLIC peer,
    //    and background sweep closes private cleanly.
    const peerC = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-c', runtimeId: 'run-c', nickname: 'C' }
    });
    assert.equal(peerC.status, 200);
    rev = peerC.json.snapshot.revision;
    const privInvite = await env.api('POST', `/v1/rooms/${roomId}/private-invites`, {
      token: ownerToken,
      body: { targetDeviceId: 'dev-peer', expectedRevision: rev }
    });
    assert.equal(privInvite.status, 201);
    rev = privInvite.json.snapshot.revision;
    const privAccepted = await env.api('POST', `/v1/rooms/${roomId}/private-invites/${privInvite.json.inviteId}/accept`, {
      token: peerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(privAccepted.status, 200);
    const privRtc = privAccepted.json.rtc;
    rev = privAccepted.json.snapshot.revision;

    env.fakeVolc.delayNextBanResponseMs(220);
    const timedOutPrivateClose = await env.api('POST', `/v1/rooms/${roomId}/private/close`, {
      token: peerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(timedOutPrivateClose.status, 502);
    assert.equal(env.fakeVolc.canJoin(privRtc), false);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken })).status, 502);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerC.json.sessionToken })).status, 200);

    env.advance(3_000);
    const privSwept = await env.store.sweep();
    assert.equal(privSwept.changed, true);
    assert.equal(privSwept.errors.length, 0);
    assert.equal(room.members.get('dev-peer').channel.type, 'PUBLIC');
    assert.equal(room.members.get('dev-owner').channel.type, 'PUBLIC');
    const peerBackPub = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(peerBackPub.status, 200);
    assert.equal(env.fakeVolc.canJoin(peerBackPub.json.rtc), true);
  } finally {
    await env.close();
  }
});

test('MANUAL_CLOUD -> MANUAL_LOCAL partial failure recovers via background sweep without owner retrying switch', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 120_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: '队员' }
    });
    const peerToken = peer.json.sessionToken;
    let rev = peer.json.snapshot.revision;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    rev = toCloud.json.snapshot.revision;

    const peerPub = (await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken })).json.rtc;
    const ownerRiderRes = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    const ownerRider = ownerRiderRes.json.rtc;
    rev = ownerRiderRes.json.snapshot.revision;

    const peerEventsBefore = await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken });
    const peerSeqBefore = peerEventsBefore.json.latestSeq;

    let attempts = 0;
    env.faults.banRoom = () => {
      attempts += 1;
      if (attempts >= 2) return new Error('Volc OpenAPI 503 on second channel');
      return null;
    };
    const failedManualLocal = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(failedManualLocal.status, 502);
    assert.equal(failedManualLocal.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.bannedRooms.length, 1);

    const room = env.store.rooms.get(roomId);
    assert.equal(room.transport.active, 'CLOUD');
    assert.equal(room.transport.reason, 'MANUAL_CLOUD');
    assert.equal(room.transport.recoveryStage, 'IDLE');
    assert.equal(room.pendingEpochRevocation?.intent, 'MANUAL_LOCAL');

    // Owner NEVER retries switch; polling & token refresh must NOT trigger OpenAPI retries or issue tokens.
    const attemptsAfterSwitch = attempts;
    assert.equal((await env.api('GET', `/v1/rooms/${roomId}/events?after=${peerSeqBefore}&waitMs=0`, { token: peerToken })).status, 200);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken })).status, 502);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken })).status, 502);
    assert.equal(attempts, attemptsAfterSwitch);

    // Sweep within initial 3s cooldown does not call OpenAPI.
    env.advance(1_000);
    assert.deepEqual(await env.store.sweep(), { changed: false, errors: [] });
    assert.equal(attempts, attemptsAfterSwitch);

    // Sweep at 3s while OpenAPI still fails records backoff (+6s).
    env.advance(2_000);
    const stillFailingSweep = await env.store.sweep();
    assert.equal(stillFailingSweep.changed, false);
    assert.equal(stillFailingSweep.errors.length, 1);
    assert.equal(attempts, attemptsAfterSwitch + 1);

    // OpenAPI recovers; sweep before +6s skips, sweep at +6s finishes MANUAL_LOCAL and broadcasts SNAPSHOT.
    env.faults.banRoom = null;
    env.advance(3_000);
    assert.deepEqual(await env.store.sweep(), { changed: false, errors: [] });
    env.advance(3_000);
    const recoveredSweep = await env.store.sweep();
    assert.equal(recoveredSweep.changed, true);
    assert.equal(recoveredSweep.errors.length, 0);
    assert.equal(room.transport.active, 'LOCAL');
    assert.equal(room.transport.epoch, 3);
    assert.equal(room.transport.reason, 'MANUAL_LOCAL');
    assert.equal(room.pendingEpochRevocation, null);
    assert.equal(env.bannedRooms.length, 2);
    assert.equal(env.canJoinRtc(peerPub), false);
    assert.equal(env.canJoinRtc(ownerRider), false);

    const peerEventsAfter = await env.api('GET', `/v1/rooms/${roomId}/events?after=${peerSeqBefore}&waitMs=0`, {
      token: peerToken
    });
    assert.equal(peerEventsAfter.status, 200);
    assert.equal(peerEventsAfter.json.events.at(-1).snapshot.transport.active, 'LOCAL');
    assert.equal(peerEventsAfter.json.events.at(-1).snapshot.transport.epoch, 3);

    // Variant: if host disconnects (RESERVED) while MANUAL_LOCAL is pending, sweep rotates to fresh CLOUD instead of dead LOCAL.
    rev = room.revision;
    const secondCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 3 }
    });
    assert.equal(secondCloud.status, 200);
    rev = secondCloud.json.snapshot.revision;
    await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    let secondAttempts = 0;
    env.faults.banRoom = () => {
      secondAttempts += 1;
      return secondAttempts === 2 ? new Error('OpenAPI down on 2nd channel') : null;
    };
    const secondFailed = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 4 }
    });
    assert.equal(secondFailed.status, 502);
    env.faults.banRoom = null;
    room.members.get('dev-owner').status = 'RESERVED';
    room.members.get('dev-owner').reservedUntilMs = env.getNow() + 60_000;
    env.advance(3_000);
    const rotatedSweep = await env.store.sweep();
    assert.equal(rotatedSweep.changed, true);
    assert.equal(room.transport.active, 'CLOUD');
    assert.equal(room.transport.epoch, 5);
    const freshPeerRtc = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    assert.equal(freshPeerRtc.status, 200);
    assert.equal(env.canJoinRtc(freshPeerRtc.json.rtc), true);
  } finally {
    await env.close();
  }
});

test('partial endRoom takes precedence over transport switches, keeps switchTransport atomic, and completes via sweep', async () => {
  const env = await startTestServer({ disconnectTimeoutMs: 120_000 });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: '队员' }
    });
    const peerToken = peer.json.sessionToken;
    let rev = peer.json.snapshot.revision;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    rev = toCloud.json.snapshot.revision;
    const peerPub = (await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken })).json.rtc;
    const ownerRiderRes = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    const ownerRider = ownerRiderRes.json.rtc;
    rev = ownerRiderRes.json.snapshot.revision;

    let endAttempts = 0;
    env.faults.banRoom = () => {
      endAttempts += 1;
      return endAttempts === 2 ? new Error('OpenAPI 502 on 2nd channel during endRoom') : null;
    };
    const failedEnd = await env.api('POST', `/v1/rooms/${roomId}/end`, {
      token: ownerToken,
      body: { expectedRevision: rev }
    });
    assert.equal(failedEnd.status, 502);
    assert.equal(failedEnd.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(env.bannedRooms.length, 1);

    const room = env.store.rooms.get(roomId);
    assert.equal(room.ended, false);
    assert.equal(room.pendingEnd?.reason, 'OWNER_ENDED');

    // Interleaved switchTransport(LOCAL), reportLocalStatus, switchChannel, join, and governance MUST fail closed
    // without mutating transport, epoch, or revision.
    const blockedToLocal = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(blockedToLocal.status, 502);
    assert.equal(blockedToLocal.json.code, 'RTC_REVOCATION_FAILED');
    assert.equal(room.transport.active, 'CLOUD');
    assert.equal(room.transport.epoch, 2);
    assert.equal(room.revision, rev);

    const blockedLocalStatus = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
    });
    assert.equal(blockedLocalStatus.status, 502);

    const blockedChannel = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'PUBLIC', expectedRevision: rev }
    });
    assert.equal(blockedChannel.status, 502);

    const blockedJoin = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-c', runtimeId: 'run-c', nickname: 'C' }
    });
    assert.equal(blockedJoin.status, 502);

    // Even if a LOCAL room has a ROOM-level revocation, switchTransport(CLOUD) MUST fail BEFORE mutating transport/epoch.
    const localOwner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-lo', runtimeId: 'run-lo', nickname: 'L', requireApproval: false, mode: 'HYBRID' }
    });
    const localRoom = env.store.rooms.get(localOwner.json.roomId);
    localRoom.rtcRoomRevocations.set('synthetic-room-ban', {
      rtcRoomId: 'synthetic-room-ban',
      epoch: 1,
      scope: 'ROOM',
      reason: 'OWNER_ENDED',
      status: 'REVOKED',
      bannedUntilMs: env.getNow() + 60_000,
      maxExpiresAtMs: env.getNow() + 60_000,
      updatedAtMs: env.getNow()
    });
    const revBeforeLocal = localRoom.revision;
    const blockedLocalToCloud = await env.api('POST', `/v1/rooms/${localRoom.roomId}/transport/switch`, {
      token: localOwner.json.sessionToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: revBeforeLocal, expectedTransportEpoch: 1 }
    });
    assert.equal(blockedLocalToCloud.status, 502);
    assert.equal(localRoom.transport.active, 'LOCAL');
    assert.equal(localRoom.transport.epoch, 1);
    assert.equal(localRoom.revision, revBeforeLocal);

    // Ordinary event polling still works while pending end waits for background sweep recovery.
    const pollWhilePending = await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken });
    assert.equal(pollWhilePending.status, 200);

    // OpenAPI recovers -> background sweep completes OWNER_ENDED without owner retrying endRoom.
    env.faults.banRoom = null;
    env.advance(3_000);
    const sweptEnd = await env.store.sweep();
    assert.equal(sweptEnd.changed, true);
    assert.equal(room.ended, true);
    assert.equal(room.endedReason, 'OWNER_ENDED');
    assert.equal(env.canJoinRtc(peerPub), false);
    assert.equal(env.canJoinRtc(ownerRider), false);

    const endedPoll = await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken });
    assert.equal(endedPoll.status, 410);
    assert.equal(endedPoll.json.code, 'ROOM_ENDED');
    assert.equal(endedPoll.json.endedReason, 'OWNER_ENDED');
  } finally {
    await env.close();
  }
});

test('polling and token refresh do not amplify room-lock OpenAPI waits and concurrent sweeps coalesce per room', async () => {
  const env = await startFakeVolcEnv({
    disconnectTimeoutMs: 120_000,
    localSignalStableMs: 5_000,
    localPreconnectTimeoutMs: 10_000,
    rtcTimeoutMs: 120,
    revocationRetryCooldownMs: 3_000
  });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const peer = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: owner.json.snapshot.invite.code, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: '队员' }
    });
    const peerToken = peer.json.sessionToken;
    let rev = peer.json.snapshot.revision;

    const fallbackCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(fallbackCloud.status, 200);
    rev = fallbackCloud.json.snapshot.revision;
    await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken });
    const ownerRider = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
      token: ownerToken,
      body: { channel: 'RIDER', expectedRevision: rev }
    });
    rev = ownerRider.json.snapshot.revision;

    // Reach PRECONNECTING and fail handover on timeout.
    env.advance(16_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
    });
    env.advance(5_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
    });
    env.advance(8_000);
    await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true, localControlReady: true }
    });

    env.fakeVolc.setFailStatus(500);
    const failedHandover = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: peerToken,
      body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60, localControlReady: true }
    });
    assert.equal(failedHandover.status, 502);

    // Immediate duplicate local-status reports within cooldown MUST fail fast without re-hitting OpenAPI.
    const callsAfterFailedHandover = env.fakeVolc.calls.length;
    const duplicateLocalStatus = await Promise.all([
      env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: ownerToken,
        body: { transportEpoch: 2, localProbeActive: true, localControlReady: true }
      }),
      env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerToken,
        body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60, localControlReady: true }
      })
    ]);
    assert.equal(duplicateLocalStatus[0].status, 502);
    assert.equal(duplicateLocalStatus[1].status, 502);
    assert.equal(env.fakeVolc.calls.length, callsAfterFailedHandover);

    // Advance past preconnect timeout AND past initial retry cooldown; ordinary requests must STILL trigger ZERO OpenAPI calls!
    env.advance(4_000);
    const callsBeforeBurst = env.fakeVolc.calls.length;
    const burst = await Promise.all([
      env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: ownerToken }),
      env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerToken }),
      env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken }),
      env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerToken }),
      env.api('POST', `/v1/rooms/${roomId}/ack`, { token: ownerToken, body: { sequence: 1 } }),
      env.api('POST', `/v1/rooms/${roomId}/audio`, { token: peerToken, body: { available: false } })
    ]);
    assert.equal(burst[0].status, 200);
    assert.equal(burst[1].status, 200);
    assert.equal(burst[2].status, 502);
    assert.equal(burst[3].status, 502);
    assert.equal(burst[4].status, 200);
    assert.equal(burst[5].status, 200);
    assert.equal(env.fakeVolc.calls.length, callsBeforeBurst);

    // Concurrent sweep() calls for the same room must coalesce into ONE in-flight OpenAPI recovery attempt.
    env.fakeVolc.setFailStatus(0);
    const gate = env.fakeVolc.armGate();
    const sweepA = env.store.sweep();
    const sweepB = env.store.sweep();
    const sweepC = env.store.sweepRoom(roomId);
    const hit = await gate.hit;
    assert.equal(hit.action, 'BanRoomUser');
    gate.release();
    const [resA, resB, resC] = await Promise.all([sweepA, sweepB, sweepC]);
    assert.equal(resA.changed, true);
    assert.equal(resB.changed, true);
    assert.equal(resC.changed, true);
    // Two channels in epoch 2 -> exactly 2 BanRoomUser calls total across all 3 concurrent sweeps.
    assert.equal(env.fakeVolc.calls.length, callsBeforeBurst + 2);
    assert.equal(env.store.rooms.get(roomId).transport.active, 'LOCAL');
    assert.equal(env.store.rooms.get(roomId).transport.epoch, 3);
  } finally {
    await env.close();
  }
});

test('PRECONNECT_LOCAL recovery never switches to hostless LOCAL when owner/host becomes RESERVED', async () => {
  for (const mode of ['reportLocalStatus', 'sweep']) {
    const env = await startTestServer({
      disconnectTimeoutMs: 120_000,
      localSignalStableMs: 5_000,
      localPreconnectTimeoutMs: 20_000
    });
    try {
      const owner = await env.api('POST', '/v1/rooms', {
        body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
      });
      const roomId = owner.json.roomId;
      const ownerToken = owner.json.sessionToken;
      const inviteCode = owner.json.snapshot.invite.code;
      const peerA = await env.api('POST', '/v1/rooms/join-requests', {
        body: { inviteCode, deviceId: 'dev-a', runtimeId: 'run-a', nickname: 'A' }
      });
      const peerB = await env.api('POST', '/v1/rooms/join-requests', {
        body: { inviteCode, deviceId: 'dev-b', runtimeId: 'run-b', nickname: 'B' }
      });
      let rev = peerB.json.snapshot.revision;

      const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
        token: ownerToken,
        body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedRevision: rev, expectedTransportEpoch: 1 }
      });
      assert.equal(toCloud.status, 200);
      rev = toCloud.json.snapshot.revision;

      const peerARtc = (await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerA.json.sessionToken })).json.rtc;
      const ownerRiderRes = await env.api('POST', `/v1/rooms/${roomId}/channel`, {
        token: ownerToken,
        body: { channel: 'RIDER', expectedRevision: rev }
      });
      const ownerRiderRtc = ownerRiderRes.json.rtc;
      rev = ownerRiderRes.json.snapshot.revision;

      // Enter PRECONNECTING with all 3 members ready.
      env.advance(16_000);
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: ownerToken,
        body: { transportEpoch: 2, localProbeActive: true }
      });
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerA.json.sessionToken,
        body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
      });
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerB.json.sessionToken,
        body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
      });
      env.advance(5_000);
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: ownerToken,
        body: { transportEpoch: 2, localProbeActive: true }
      });
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerA.json.sessionToken,
        body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
      });
      const preconnect = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerB.json.sessionToken,
        body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
      });
      assert.equal(preconnect.json.snapshot.transport.recoveryStage, 'PRECONNECTING');

      env.advance(2_000);
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: ownerToken,
        body: { transportEpoch: 2, localProbeActive: true, localControlReady: true }
      });
      await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerA.json.sessionToken,
        body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60, localControlReady: true }
      });

      let banCalls = 0;
      env.faults.banRoom = () => {
        banCalls += 1;
        return banCalls === 2 ? new Error('OpenAPI 502 on 2nd channel') : null;
      };
      const failedHandover = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerB.json.sessionToken,
        body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60, localControlReady: true }
      });
      assert.equal(failedHandover.status, 502);
      env.faults.banRoom = null;

      // Owner (also localHostDeviceId) transitions to RESERVED while peerA and peerB remain ADMITTED & controlReady.
      const room = env.store.rooms.get(roomId);
      room.members.get('dev-owner').status = 'RESERVED';
      room.members.get('dev-owner').reservedUntilMs = env.getNow() + 60_000;
      env.advance(3_000);

      if (mode === 'reportLocalStatus') {
        const reported = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
          token: peerA.json.sessionToken,
          body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60, localControlReady: true }
        });
        assert.equal(reported.status, 200);
        assert.equal(reported.json.switched, false);
        assert.equal(reported.json.snapshot.transport.active, 'CLOUD');
        assert.equal(reported.json.snapshot.transport.epoch, 3);
      } else {
        const swept = await env.store.sweep();
        assert.equal(swept.changed, true);
        assert.equal(swept.errors.length, 0);
        assert.equal(room.transport.active, 'CLOUD');
        assert.equal(room.transport.epoch, 3);
      }

      assert.equal(env.canJoinRtc(peerARtc), false);
      assert.equal(env.canJoinRtc(ownerRiderRtc), false);
      const freshA = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerA.json.sessionToken });
      const freshB = await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerB.json.sessionToken });
      assert.equal(freshA.status, 200);
      assert.equal(freshB.status, 200);
      assert.equal(env.canJoinRtc(freshA.json.rtc), true);
      assert.equal(env.canJoinRtc(freshB.json.rtc), true);
    } finally {
      await env.close();
    }
  }
});

test('approved join receipt replay filters revoked or stale RTC credentials across poll and resubmission', async () => {
  const env = await startFakeVolcEnv({
    disconnectTimeoutMs: 120_000,
    rtcTimeoutMs: 80,
    revocationRetryCooldownMs: 3_000
  });
  try {
    // 1) Approval queue receipt polled after timed-out BanRoomUser and after LOCAL transition.
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: true, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    let rev = owner.json.snapshot.revision;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    rev = toCloud.json.snapshot.revision;

    const pendingJoin = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-peer', runtimeId: 'run-p', nickname: '队员' }
    });
    assert.equal(pendingJoin.status, 202);
    rev += 1;
    const approved = await env.api(
      'POST',
      `/v1/rooms/${roomId}/join-requests/${pendingJoin.json.requestId}/approve`,
      { token: ownerToken, body: { expectedRevision: rev } }
    );
    assert.equal(approved.status, 200);
    rev = approved.json.snapshot.revision;

    // Before peer polls its approval receipt, owner switches CLOUD -> LOCAL and RTC applies ban then times out.
    env.fakeVolc.delayNextBanResponseMs(220);
    const timedOutLocal = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(timedOutLocal.status, 502);

    const polledDuringRevocation = await env.api('GET', `/v1/join-requests/${pendingJoin.json.requestId}?waitMs=0`, {
      token: pendingJoin.json.requestSecret
    });
    assert.equal(polledDuringRevocation.status, 200);
    assert.equal(polledDuringRevocation.json.status, 'ADMITTED');
    assert.equal(polledDuringRevocation.json.rtc, null);
    assert.equal(polledDuringRevocation.json.snapshot.transport.epoch, 2);

    // Complete switch to LOCAL via sweep; polling receipt again returns live LOCAL snapshot with rtc=null.
    env.advance(3_000);
    assert.equal((await env.store.sweep()).changed, true);
    const polledAfterLocal = await env.api('GET', `/v1/join-requests/${pendingJoin.json.requestId}?waitMs=0`, {
      token: pendingJoin.json.requestSecret
    });
    assert.equal(polledAfterLocal.status, 200);
    assert.equal(polledAfterLocal.json.rtc, null);
    assert.equal(polledAfterLocal.json.snapshot.transport.active, 'LOCAL');
    assert.equal(polledAfterLocal.json.snapshot.transport.epoch, 3);

    // 2) Direct joinSecret replay after partial endRoom filters stale RTC and rejects once ended.
    const directOwner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-o2', runtimeId: 'run-o2', nickname: 'O2', requireApproval: false, mode: 'HYBRID' }
    });
    const room2Id = directOwner.json.roomId;
    const owner2Token = directOwner.json.sessionToken;
    const invite2 = directOwner.json.snapshot.invite.code;
    let rev2 = directOwner.json.snapshot.revision;
    const toCloud2 = await env.api('POST', `/v1/rooms/${room2Id}/transport/switch`, {
      token: owner2Token,
      body: { targetTransport: 'CLOUD', reason: 'MANUAL_CLOUD', expectedRevision: rev2, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud2.status, 200);
    rev2 = toCloud2.json.snapshot.revision;

    const joinSecret = crypto.randomBytes(32).toString('hex');
    const directBody = { inviteCode: invite2, deviceId: 'dev-p2', runtimeId: 'run-p2', nickname: 'P2', joinSecret };
    const firstDirect = await env.api('POST', '/v1/rooms/join-requests', { body: directBody });
    assert.equal(firstDirect.status, 200);
    assert.equal(env.fakeVolc.canJoin(firstDirect.json.rtc), true);
    rev2 = firstDirect.json.snapshot.revision;

    env.fakeVolc.delayNextBanResponseMs(220);
    const timedOutEnd = await env.api('POST', `/v1/rooms/${room2Id}/end`, {
      token: owner2Token,
      body: { expectedRevision: rev2 }
    });
    assert.equal(timedOutEnd.status, 502);

    // While room end is pending, receipt replay MUST fail closed with 502 instead of admitting into a dying room.
    const replayedDirect = await env.api('POST', '/v1/rooms/join-requests', { body: directBody });
    assert.equal(replayedDirect.status, 502);
    assert.equal(replayedDirect.json.code, 'RTC_REVOCATION_FAILED');

    env.advance(3_000);
    assert.equal((await env.store.sweep()).changed, true);
    const replayedAfterEnd = await env.api('POST', '/v1/rooms/join-requests', { body: directBody });
    assert.equal(replayedAfterEnd.status, 410);
    assert.equal(replayedAfterEnd.json.code, 'REQUEST_ENDED');

    // 3) Approval followed by sweep() moving seat to RESERVED before applicant polls MUST auto-resume to ADMITTED
    //    with a fresh usable RTC token (satisfying Android enterAdmittedSession without ALREADY_IN_ROOM lockout).
    const netOwner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-net-o', runtimeId: 'run-net-o', nickname: 'NetOwner', requireApproval: true, mode: 'NETWORK' }
    });
    const netRoomId = netOwner.json.roomId;
    const netOwnerToken = netOwner.json.sessionToken;
    const netInvite = netOwner.json.snapshot.invite.code;
    const netJoin = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: netInvite, deviceId: 'dev-net-p', runtimeId: 'run-net-p', nickname: 'NetPeer' }
    });
    assert.equal(netJoin.status, 202);
    const netApproved = await env.api(
      'POST',
      `/v1/rooms/${netRoomId}/join-requests/${netJoin.json.requestId}/approve`,
      { token: netOwnerToken, body: { expectedRevision: netOwner.json.snapshot.revision + 1 } }
    );
    assert.equal(netApproved.status, 200);
    const netRoom = env.store.rooms.get(netRoomId);
    const originalNetRtc = env.store.pendingIndex.get(netJoin.json.requestId).resolution.rtc;

    // Keep owner alive while approved applicant goes idle past disconnectTimeoutMs (120s) -> RESERVED.
    for (let step = 0; step < 3; step += 1) {
      env.advance(50_000);
      await env.api('GET', `/v1/rooms/${netRoomId}/events?after=0&waitMs=0`, { token: netOwnerToken });
    }
    const reserveSweep = await env.store.sweep();
    assert.equal(reserveSweep.changed, true);
    assert.equal(netRoom.members.get('dev-net-p').status, 'RESERVED');
    assert.equal(env.fakeVolc.canJoin(originalNetRtc), false);

    const polledAfterReserved = await env.api('GET', `/v1/join-requests/${netJoin.json.requestId}?waitMs=0`, {
      token: netJoin.json.requestSecret
    });
    assert.equal(polledAfterReserved.status, 200);
    assert.equal(polledAfterReserved.json.status, 'ADMITTED');
    const selfView = polledAfterReserved.json.snapshot.members.find((m) => m.deviceId === 'dev-net-p');
    assert.equal(selfView.status, 'ADMITTED');
    assert.ok(polledAfterReserved.json.rtc, 'NETWORK ADMITTED receipt MUST include non-null RTC token');
    assert.equal(env.fakeVolc.canJoin(polledAfterReserved.json.rtc), true);

    // 4) NETWORK mode receipt whose cached RTC expired MUST mint a fresh non-null RTC token (never rtc=null),
    //    and while endRoom is pending MUST fail closed with 502 then ENDED after sweep.
    const netRoomRev = polledAfterReserved.json.snapshot.revision;
    env.store.pendingIndex.get(netJoin.json.requestId).resolution.rtc = {
      ...polledAfterReserved.json.rtc,
      expiresAtMs: env.getNow() + 2_000
    };
    const polledNearRtcExpiry = await env.api('GET', `/v1/join-requests/${netJoin.json.requestId}?waitMs=0`, {
      token: netJoin.json.requestSecret
    });
    assert.equal(polledNearRtcExpiry.status, 200);
    assert.equal(polledNearRtcExpiry.json.status, 'ADMITTED');
    assert.ok(polledNearRtcExpiry.json.rtc);
    assert.ok(polledNearRtcExpiry.json.rtc.expiresAtMs - env.getNow() >= 30_000);
    assert.equal(env.fakeVolc.canJoin(polledNearRtcExpiry.json.rtc), true);

    env.store.pendingIndex.get(netJoin.json.requestId).resolution.rtc = {
      ...polledAfterReserved.json.rtc,
      expiresAtMs: env.getNow() - 1
    };
    const polledAfterRtcExpiry = await env.api('GET', `/v1/join-requests/${netJoin.json.requestId}?waitMs=0`, {
      token: netJoin.json.requestSecret
    });
    assert.equal(polledAfterRtcExpiry.status, 200);
    assert.equal(polledAfterRtcExpiry.json.status, 'ADMITTED');
    assert.ok(polledAfterRtcExpiry.json.rtc);
    assert.ok(polledAfterRtcExpiry.json.rtc.expiresAtMs - env.getNow() >= 30_000);
    assert.equal(env.fakeVolc.canJoin(polledAfterRtcExpiry.json.rtc), true);

    env.fakeVolc.delayNextBanResponseMs(220);
    const timedOutNetEnd = await env.api('POST', `/v1/rooms/${netRoomId}/end`, {
      token: netOwnerToken,
      body: { expectedRevision: netRoomRev }
    });
    assert.equal(timedOutNetEnd.status, 502);

    const polledNetDuringEnd = await env.api('GET', `/v1/join-requests/${netJoin.json.requestId}?waitMs=0`, {
      token: netJoin.json.requestSecret
    });
    assert.equal(polledNetDuringEnd.status, 502);
    assert.equal(polledNetDuringEnd.json.code, 'RTC_REVOCATION_FAILED');

    env.advance(3_000);
    assert.equal((await env.store.sweep()).changed, true);
    const polledNetEnded = await env.api('GET', `/v1/join-requests/${netJoin.json.requestId}?waitMs=0`, {
      token: netJoin.json.requestSecret
    });
    assert.equal(polledNetEnded.status, 200);
    assert.equal(polledNetEnded.json.status, 'ENDED');
    assert.equal(polledNetEnded.json.code, 'ROOM_ENDED');

    // 5) HYBRID approval followed by RESERVED also auto-resumes to ADMITTED when polled.
    const hyOwner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-hy-o', runtimeId: 'run-hy-o', nickname: 'HyOwner', requireApproval: true, mode: 'HYBRID' }
    });
    const hyRoomId = hyOwner.json.roomId;
    const hyOwnerToken = hyOwner.json.sessionToken;
    const hyJoin = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode: hyOwner.json.snapshot.invite.code, deviceId: 'dev-hy-p', runtimeId: 'run-hy-p', nickname: 'HyPeer' }
    });
    assert.equal(hyJoin.status, 202);
    const hyApproved = await env.api(
      'POST',
      `/v1/rooms/${hyRoomId}/join-requests/${hyJoin.json.requestId}/approve`,
      { token: hyOwnerToken, body: { expectedRevision: hyOwner.json.snapshot.revision + 1 } }
    );
    assert.equal(hyApproved.status, 200);
    for (let step = 0; step < 3; step += 1) {
      env.advance(50_000);
      await env.api('GET', `/v1/rooms/${hyRoomId}/events?after=0&waitMs=0`, { token: hyOwnerToken });
    }
    assert.equal((await env.store.sweep()).changed, true);
    assert.equal(env.store.rooms.get(hyRoomId).members.get('dev-hy-p').status, 'RESERVED');
    const hyPolled = await env.api('GET', `/v1/join-requests/${hyJoin.json.requestId}?waitMs=0`, {
      token: hyJoin.json.requestSecret
    });
    assert.equal(hyPolled.status, 200);
    assert.equal(hyPolled.json.status, 'ADMITTED');
    assert.equal(hyPolled.json.snapshot.members.find((m) => m.deviceId === 'dev-hy-p').status, 'ADMITTED');
    assert.equal(hyPolled.json.rtc, null);
  } finally {
    await env.close();
  }
});

test('host-reassignment local-status reports and pending private disconnect revocations obey unified backoff and scheduling', async () => {
  const env = await startFakeVolcEnv({
    disconnectTimeoutMs: 15_000,
    revocationRetryCooldownMs: 3_000
  });
  try {
    const owner = await env.api('POST', '/v1/rooms', {
      body: { deviceId: 'dev-owner', runtimeId: 'run-o', nickname: '队长', requireApproval: false, mode: 'HYBRID' }
    });
    const roomId = owner.json.roomId;
    const ownerToken = owner.json.sessionToken;
    const inviteCode = owner.json.snapshot.invite.code;
    const peerA = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-a', runtimeId: 'run-a', nickname: 'A' }
    });
    const peerB = await env.api('POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'dev-b', runtimeId: 'run-b', nickname: 'B' }
    });
    let rev = peerB.json.snapshot.revision;

    const toCloud = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'CLOUD', reason: 'LOCAL_LINK_LOST', expectedRevision: rev, expectedTransportEpoch: 1 }
    });
    assert.equal(toCloud.status, 200);
    rev = toCloud.json.snapshot.revision;
    await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerA.json.sessionToken });
    await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerB.json.sessionToken });

    // 1) Host-reassignment branch in reportLocalStatus enforces retry cooldown during pending epoch revocation.
    env.fakeVolc.setFailStatus(500);
    const failedManualLocal = await env.api('POST', `/v1/rooms/${roomId}/transport/switch`, {
      token: ownerToken,
      body: { targetTransport: 'LOCAL', reason: 'MANUAL_LOCAL', expectedRevision: rev, expectedTransportEpoch: 2 }
    });
    assert.equal(failedManualLocal.status, 502);

    const room = env.store.rooms.get(roomId);
    room.transport.localHostDeviceId = 'dev-b';
    room.members.get('dev-b').status = 'RESERVED';
    room.members.get('dev-b').reservedUntilMs = env.getNow() + 60_000;
    room.transport.cooldownUntilMs = env.getNow();

    const callsBeforeHostBurst = env.fakeVolc.calls.length;
    const immediateReassignBurst = await Promise.all([
      env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: ownerToken,
        body: { transportEpoch: 2, localProbeActive: true }
      }),
      env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
        token: peerA.json.sessionToken,
        body: { transportEpoch: 2, localReachable: true, localRssiDbm: -60 }
      })
    ]);
    assert.equal(immediateReassignBurst[0].status, 502);
    assert.equal(immediateReassignBurst[1].status, 502);
    assert.equal(env.fakeVolc.calls.length, callsBeforeHostBurst);

    // Recover host reassignment into CLOUD epoch 3 cleanly.
    env.fakeVolc.setFailStatus(0);
    env.advance(3_000);
    const reassigned = await env.api('POST', `/v1/rooms/${roomId}/transport/local-status`, {
      token: ownerToken,
      body: { transportEpoch: 2, localProbeActive: true }
    });
    assert.equal(reassigned.status, 200);
    assert.equal(reassigned.json.snapshot.transport.active, 'CLOUD');
    assert.equal(reassigned.json.snapshot.transport.epoch, 3);
    rev = reassigned.json.snapshot.revision;

    // 2) Pending private disconnect revocation is NOT retried by ordinary requests even AFTER cooldown elapses.
    const resumedB = await env.api('POST', `/v1/rooms/${roomId}/resume`, { token: peerB.json.sessionToken });
    assert.equal(resumedB.status, 200);
    rev = resumedB.json.snapshot.revision;
    const privInvite = await env.api('POST', `/v1/rooms/${roomId}/private-invites`, {
      token: ownerToken,
      body: { targetDeviceId: 'dev-a', expectedRevision: rev }
    });
    assert.equal(privInvite.status, 201);
    rev = privInvite.json.snapshot.revision;
    const privAccepted = await env.api(
      'POST',
      `/v1/rooms/${roomId}/private-invites/${privInvite.json.inviteId}/accept`,
      { token: peerA.json.sessionToken, body: { expectedRevision: rev } }
    );
    assert.equal(privAccepted.status, 200);
    const privRtc = privAccepted.json.rtc;

    // Let dev-a time out past disconnectTimeoutMs while keeping owner and peerB alive.
    env.advance(10_000);
    await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: ownerToken });
    await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerB.json.sessionToken });
    env.advance(6_000);

    env.fakeVolc.setFailStatus(500);
    const failedPrivSweep = await env.store.sweep();
    assert.equal(failedPrivSweep.changed, false);
    assert.equal(failedPrivSweep.errors.length, 1);
    assert.equal(room.pendingPrivateRevocations.has(privRtc.rtcRoomId), true);

    // Advance PAST the 3s retry cooldown while OpenAPI is still down; ordinary polling/token refresh MUST trigger ZERO OpenAPI calls.
    env.advance(4_000);
    const callsBeforePolls = env.fakeVolc.calls.length;
    assert.equal((await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: peerB.json.sessionToken })).status, 200);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: peerB.json.sessionToken })).status, 200);
    assert.equal((await env.api('GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { token: ownerToken })).status, 200);
    assert.equal((await env.api('POST', `/v1/rooms/${roomId}/rtc-token`, { token: ownerToken })).status, 502);
    assert.equal(env.fakeVolc.calls.length, callsBeforePolls);

    // Background sweep completes private disconnect revocation once OpenAPI recovers.
    env.fakeVolc.setFailStatus(0);
    const recoveredPrivSweep = await env.store.sweep();
    assert.equal(recoveredPrivSweep.changed, true);
    assert.equal(recoveredPrivSweep.errors.length, 0);
    assert.equal(room.members.get('dev-a').status, 'RESERVED');
    assert.equal(room.members.get('dev-owner').channel.type, 'PUBLIC');
    assert.equal(env.fakeVolc.canJoin(privRtc), false);
    assert.equal(room.pendingPrivateRevocations.size, 0);
  } finally {
    await env.close();
  }
});
