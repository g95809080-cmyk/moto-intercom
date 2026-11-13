#!/usr/bin/env node
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { canonicalRequest, pairingTranscript } from '../../backend/gme-control-plane/src/control-plane.mjs';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key?.startsWith('--') || !value) throw new Error('Usage: smoke-control-plane.mjs --http-endpoint URL --ws-endpoint URL');
  args.set(key.slice(2), value);
}

const httpEndpoint = (args.get('http-endpoint') ?? process.env.GME_CONTROL_PLANE_URL ?? '').replace(/\/$/, '');
const wsEndpoint = args.get('ws-endpoint') ?? process.env.GME_WEBSOCKET_URL ?? '';
if (!/^https:\/\//.test(httpEndpoint) || !/^wss:\/\//.test(wsEndpoint)) {
  throw new Error('Secure HTTP and WebSocket endpoints are required');
}

const runId = `${Date.now()}_${randomUUID().slice(0, 8)}`;
const pairId = `smoke_pair_${runId}`;
const device = suffix => {
  const id = `smoke_${suffix}_${runId}`;
  const key = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return {
    id,
    key,
    publicKey: key.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
  };
};
const [deviceA, deviceB] = ['a', 'b'].map(device).sort((left, right) => left.id.localeCompare(right.id));

async function request(who, method, path, body) {
  const raw = body === undefined ? '' : JSON.stringify(body);
  const timestamp = String(Date.now());
  const nonce = randomUUID();
  const signature = sign(
    'sha256',
    Buffer.from(canonicalRequest(method, path, raw, timestamp, nonce)),
    who.key.privateKey,
  ).toString('base64');
  const response = await fetch(`${httpEndpoint}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      'x-device-id': who.id,
      'x-timestamp': timestamp,
      'x-nonce': nonce,
      'x-signature': signature,
    },
    body: raw || undefined,
  });
  const text = await response.text();
  const parsed = text ? JSON.parse(text) : {};
  if (!response.ok) throw new Error(`${method} ${path} failed with ${response.status}: ${parsed.error ?? 'UNKNOWN'}`);
  return parsed;
}

const post = (who, path, body = {}) => request(who, 'POST', path, body);
const get = (who, path) => request(who, 'GET', path);

async function openSocket(who) {
  const ticket = await post(who, '/v1/ws-token');
  const url = new URL(wsEndpoint);
  url.searchParams.set('token', ticket.token);
  const socket = new WebSocket(url);
  const events = [];
  const waiters = [];
  socket.addEventListener('message', message => {
    let event;
    try {
      event = JSON.parse(String(message.data));
    } catch {
      return;
    }
    events.push(event);
    for (const waiter of [...waiters]) {
      if (waiter.predicate(event)) {
        clearTimeout(waiter.timer);
        waiters.splice(waiters.indexOf(waiter), 1);
        waiter.resolve(event);
      }
    }
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`WebSocket open timeout for ${who.id}`)), 15_000);
    socket.addEventListener('open', () => {
      clearTimeout(timer);
      resolve();
    }, { once: true });
    socket.addEventListener('error', () => {
      clearTimeout(timer);
      reject(new Error(`WebSocket open failed for ${who.id}`));
    }, { once: true });
  });
  return {
    socket,
    events,
    waitFor(predicate, timeoutMs = 15_000) {
      const existing = events.find(predicate);
      if (existing) return Promise.resolve(existing);
      return new Promise((resolve, reject) => {
        const waiter = { predicate, resolve, timer: null };
        waiter.timer = setTimeout(() => {
          waiters.splice(waiters.indexOf(waiter), 1);
          reject(new Error(`WebSocket event timeout for ${who.id}`));
        }, timeoutMs);
        waiters.push(waiter);
      });
    },
    async close() {
      if (socket.readyState === WebSocket.CLOSED) return;
      await new Promise(resolve => {
        const timer = setTimeout(resolve, 3_000);
        socket.addEventListener('close', () => {
          clearTimeout(timer);
          resolve();
        }, { once: true });
        socket.close();
      });
    },
  };
}

async function closeAll(...probes) {
  await Promise.allSettled(probes.map(probe => probe?.close()));
}

let socketA;
let socketB;
let paired = false;
let firstRideId;
let fallbackRideId;

try {
  await post(deviceA, '/v1/devices/register', { publicKey: deviceA.publicKey });
  await post(deviceB, '/v1/devices/register', { publicKey: deviceB.publicKey });

  const transcript = {
    protocolVersion: 1,
    pairId,
    deviceIdA: deviceA.id,
    deviceIdB: deviceB.id,
    publicKeyA: deviceA.publicKey,
    publicKeyB: deviceB.publicKey,
    createdAt: Date.now(),
    nonce: randomUUID(),
    expiresAt: Date.now() + 4 * 60_000,
  };
  const proof = {
    transcript,
    signatureA: sign('sha256', Buffer.from(pairingTranscript(transcript)), deviceA.key.privateKey).toString('base64'),
    signatureB: sign('sha256', Buffer.from(pairingTranscript(transcript)), deviceB.key.privateKey).toString('base64'),
  };
  assert.equal((await post(deviceA, '/v1/pairs/register', proof)).status, 'VERIFIED');
  paired = true;

  socketA = await openSocket(deviceA);
  socketB = await openSocket(deviceB);
  for (const who of [deviceA, deviceB]) {
    assert.equal((await post(who, '/v1/heartbeat')).connectionAlive, true);
  }

  const firstRide = await post(deviceA, '/v1/rides/start', { pairId, requestId: `start_1_${runId}` });
  firstRideId = firstRide.rideId;
  const invitation = (await post(deviceA, `/v1/rides/${firstRideId}/invite`)).invitation;
  const createdEvent = await socketB.waitFor(
    event => event.type === 'invite.created' && event.payload?.invitation?.inviteId === invitation.inviteId,
  );
  assert.equal(createdEvent.rideId, firstRideId);

  const accepted = await post(deviceB, `/v1/rides/${firstRideId}/invite/respond`, {
    inviteId: invitation.inviteId,
    response: 'ACCEPT',
  });
  assert.equal(accepted.state, 'CLOUD_PENDING');
  await socketA.waitFor(event => event.type === 'invite.accepted' && event.rideId === firstRideId);
  await socketB.waitFor(event => event.type === 'ride.cloud.ready' && event.rideId === firstRideId);

  const [ticketA, ticketB] = await Promise.all([
    post(deviceA, `/v1/rides/${firstRideId}/ticket`),
    post(deviceB, `/v1/rides/${firstRideId}/ticket`),
  ]);
  assert.equal(ticketA.rideId, firstRideId);
  assert.equal(ticketA.roomId, ticketB.roomId);
  assert.equal(ticketA.appId, ticketB.appId);
  assert.equal(typeof ticketA.authBuffer, 'string');
  assert.ok(ticketA.authBuffer.length > 0);

  await post(deviceA, `/v1/rides/${firstRideId}/cloud-status`, { status: 'CONNECTED' });
  const active = await post(deviceB, `/v1/rides/${firstRideId}/cloud-status`, { status: 'CONNECTED' });
  assert.equal(active.state, 'CLOUD_ACTIVE');
  assert.equal((await post(deviceA, `/v1/rides/${firstRideId}/end`)).state, 'ENDED');

  const fallbackRide = await post(deviceA, '/v1/rides/start', { pairId, requestId: `start_2_${runId}` });
  fallbackRideId = fallbackRide.rideId;
  await Promise.all([
    post(deviceA, `/v1/rides/${fallbackRideId}/local-established`),
    post(deviceB, `/v1/rides/${fallbackRideId}/local-established`),
  ]);
  const fallback = await post(deviceA, `/v1/rides/${fallbackRideId}/fallback`, { reason: 'TRANSPORT_FAILURE' });
  assert.equal(fallback.state, 'CLOUD_PENDING');
  assert.equal(fallback.roomId, fallbackRide.roomId);
  const [fallbackTicketA, fallbackTicketB] = await Promise.all([
    post(deviceA, `/v1/rides/${fallbackRideId}/ticket`),
    post(deviceB, `/v1/rides/${fallbackRideId}/ticket`),
  ]);
  assert.equal(fallbackTicketA.rideId, fallbackRideId);
  assert.equal(fallbackTicketA.roomId, fallbackRide.roomId);
  assert.equal(fallbackTicketA.roomId, fallbackTicketB.roomId);
  await post(deviceA, `/v1/rides/${fallbackRideId}/cloud-status`, { status: 'CONNECTED' });
  assert.equal(
    (await post(deviceB, `/v1/rides/${fallbackRideId}/cloud-status`, { status: 'CONNECTED' })).state,
    'CLOUD_ACTIVE',
  );
  assert.equal((await get(deviceB, `/v1/rides/${fallbackRideId}`)).state, 'CLOUD_ACTIVE');
  assert.equal((await post(deviceB, `/v1/rides/${fallbackRideId}/end`)).state, 'ENDED');

  assert.equal((await post(deviceA, `/v1/pairs/${pairId}/revoke`)).status, 'REVOKED');
  paired = false;

  process.stdout.write(`${JSON.stringify({
    status: 'PASS',
    httpEndpoint,
    websocketEndpoint: wsEndpoint,
    signedHttp: true,
    devicesRegistered: 2,
    pairVerified: true,
    websocketConnections: 2,
    websocketEvents: [
      createdEvent.type,
      'invite.accepted',
      'ride.cloud.ready',
    ],
    inviteFlow: {
      accepted: true,
      sameRoom: true,
      sameAppId: true,
      authBufferPresent: true,
      cloudActive: true,
    },
    fallbackFlow: {
      bothLocalConfirmations: true,
      approved: true,
      sameRoomAfterFallback: true,
      cloudActive: true,
    },
    cleanup: {
      ridesEnded: true,
      pairRevoked: true,
    },
  }, null, 2)}\n`);
} finally {
  if (firstRideId) await post(deviceA, `/v1/rides/${firstRideId}/end`).catch(() => {});
  if (fallbackRideId) await post(deviceB, `/v1/rides/${fallbackRideId}/end`).catch(() => {});
  if (paired) await post(deviceA, `/v1/pairs/${pairId}/revoke`).catch(() => {});
  await closeAll(socketB, socketA);
}
