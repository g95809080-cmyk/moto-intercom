import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { RelayService, createRelayServer, sha256, validateConfig } from './server.mjs';
import { execFileSync } from 'node:child_process';
import https from 'node:https';

const token = id => id.repeat(48);
const configuration = () => ({ devices: ['a','b','c','d'].map(deviceId => ({ deviceId, tokenSha256: sha256(token(deviceId)) })),
  pairs: [{ pairId: 'ab', members: ['a','b'] }, { pairId: 'cd', members: ['c','d'] }],
  turn: { secret: 'test-only-'.repeat(8), urls: ['turn:127.0.0.1:3478?transport=udp'] } });
const enter = (service, deviceId, pairId = 'ab', runtimeId = `run-${deviceId}`) => service.join({ deviceId, pairId, runtimeId }, token(deviceId));
const lease = (service, grant) => service.lease(grant.sessionId, grant.leaseToken);
const rejects = (fn, status, code) => assert.throws(fn, error => error.status === status && (!code || error.message === code));
function call(options) {
  const service = new RelayService(configuration(), options);
  const a = enter(service, 'a'); const b = enter(service, 'b');
  const sa = lease(service, a), sb = lease(service, b);
  return { service, a, b, sa, sb, callId: sa.callId };
}

test('provisioning rejects malformed token hashes, TURN URLs and three-member pairs', () => {
  const config = configuration(); config.pairs[0].members.push('c'); assert.throws(() => validateConfig(config));
  const invalidTurn = configuration(); invalidTurn.turn.urls = ['stun:example.org']; assert.throws(() => validateConfig(invalidTurn));
  const invalidHash = configuration(); invalidHash.devices[0].tokenSha256 = 'cleartext'; assert.throws(() => validateConfig(invalidHash));
});
test('identity authentication and pair authorization are independent', () => {
  const service = new RelayService(configuration());
  rejects(() => service.join({ deviceId: 'a', runtimeId: 'one', pairId: 'ab' }, token('b')), 401);
  rejects(() => enter(service, 'c'), 403);
  rejects(() => service.join({ deviceId: 'a', runtimeId: '../bad', pairId: 'ab' }, token('a')), 400);
});
test('join retry is idempotent but a second runtime cannot replace an owner', () => {
  const service = new RelayService(configuration()); const first = enter(service, 'a');
  assert.equal(enter(service, 'a').sessionId, first.sessionId);
  rejects(() => enter(service, 'a', 'ab', 'new-runtime'), 409);
});
test('both grants use expiring standard coturn REST HMAC credentials', () => {
  const { a, service } = call({ now: () => 1_000_000 }); const ice = a.iceServers[0];
  assert.equal(ice.expiresAt, a.expiresAt);
  assert.equal(ice.username, `${ice.expiresAt / 1000}:a`);
  assert.equal(ice.credential, createHmac('sha1', service.config.turn.secret).update(ice.username).digest('base64'));
  assert.ok(!JSON.stringify(a).includes(service.config.turn.secret));
});
test('ready binds self/peer runtime, deterministic roles and common call identity', () => {
  const { service, sa, sb } = call();
  const readyA = service.events(sa, 0).events[0], readyB = service.events(sb, 0).events[0];
  assert.equal(readyA.callId, readyB.callId); assert.equal(readyA.role, 'offerer'); assert.equal(readyB.role, 'answerer');
  assert.deepEqual(readyA.peer, { deviceId: 'b', runtimeId: 'run-b' });
});
test('messages are ordered, sender identity is server-derived and pairs are isolated', () => {
  const { service, sa, sb, callId } = call(); const c = enter(service, 'c', 'cd'); enter(service, 'd', 'cd');
  service.events(sb, 1);
  service.signal(sa, { callId, sequence: 1, kind: 'offer', payload: '{}', from: { deviceId: 'c' } });
  service.signal(sa, { callId, sequence: 2, kind: 'ice', payload: '{}' });
  const result = service.events(sb, 1);
  assert.deepEqual(result.events.map(e => e.sequence), [2,3]);
  assert.equal(result.events[0].from.deviceId, 'a');
  assert.equal(service.events(lease(service, c), 0).events.length, 1);
  rejects(() => service.signal(sa, { callId, sequence: 2, kind: 'ice', payload: '{}' }), 409);
  rejects(() => service.signal(sa, { callId: 'stale-call', sequence: 3, kind: 'ice', payload: '{}' }), 409);
  rejects(() => service.signal(sa, { callId, sequence: 3, kind: 'answer', payload: '{}' }), 403);
});
test('expiry revokes both call leases and permits a fresh runtime', () => {
  let now = 0; const { service, a, b } = call({ now: () => now, idleMs: 100, lifetimeMs: 1000 });
  now = 101;
  rejects(() => lease(service, a), 410); rejects(() => lease(service, b), 410);
  const fresh = enter(service, 'a', 'ab', 'replacement'); assert.notEqual(fresh.sessionId, a.sessionId);
});
test('hard lifetime expires even when actively polling', () => {
  let now = 0; const { service, a, b } = call({ now: () => now, idleMs: 100, lifetimeMs: 300 });
  for (now = 50; now < 300; now += 50) { lease(service, a); lease(service, b); }
  rejects(() => lease(service, a), 410);
});
test('bounded queue rejects instead of dropping signaling and terminal cleanup still succeeds', () => {
  const { service, sa, sb, callId } = call({ queueLimit: 2 });
  service.signal(sa, { callId, sequence: 1, kind: 'ice', payload: '{}' });
  rejects(() => service.signal(sa, { callId, sequence: 2, kind: 'ice', payload: '{}' }), 429);
  service.end(sa); assert.equal(sb.closed, true); assert.equal(service.events(sb, 0).events.at(-1).type, 'closed');
});
test('long poll wakes on signal, rejects simultaneous poll and supports cancellation', async () => {
  const { service, sa, sb, callId } = call(); const controller = new AbortController();
  const pending = service.poll(sb, 1, { signal: controller.signal });
  await assert.rejects(service.poll(sb, 1), /POLL_BUSY/);
  service.signal(sa, { callId, sequence: 1, kind: 'ice', payload: '{}' });
  assert.equal((await pending).events[0].type, 'signal');
  const cancellation = service.poll(sb, 2, { signal: controller.signal }); controller.abort();
  assert.equal((await cancellation).events.length, 0);
});
test('closed calls do not accept late messages and old leases cannot affect a replacement', () => {
  const { service, sa, sb, a, callId } = call(); service.end(sb);
  rejects(() => service.signal(sa, { callId, sequence: 1, kind: 'offer', payload: '{}' }), 410);
  const fresh = enter(service, 'a', 'ab', 'new-a'); enter(service, 'b', 'ab', 'new-b');
  service.end(sa); assert.equal(lease(service, fresh).closed, false);
  rejects(() => service.lease(a.sessionId, 'wrong'), 401);
});
test('cursor cannot move backwards or skip nonexistent events', () => {
  const { service, sa } = call(); service.events(sa, 1);
  rejects(() => service.events(sa, 0), 409); rejects(() => service.events(sa, 2), 409);
});
test('HTTPS enforces CA/hostname authentication, bearer auth and JSON limits', async t => {
  // Generate process-local PEM in memory; no private-key fixture or temp directory is created.
  const openssl = process.env.OPENSSL ?? (process.platform === 'win32' ? 'C:/Program Files/Git/usr/bin/openssl.exe' : 'openssl');
  const pem = execFileSync(openssl, ['req','-x509','-newkey','rsa:2048','-nodes','-keyout','-',
    '-out','-','-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1'],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  const cert = /-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/.exec(pem)?.[0];
  const key = /-----BEGIN PRIVATE KEY-----[\s\S]*?-----END PRIVATE KEY-----/.exec(pem)?.[0];
  assert.ok(cert && key);
  const { server, service } = createRelayServer(configuration(), { key, cert });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { service.close(); server.closeAllConnections(); server.close(); });
  function request(path, { method = 'POST', authorization = token('a'), data = '{}', ca = cert, hostname = '127.0.0.1', servername } = {}) {
    return new Promise((resolve, reject) => {
      const req = https.request({ hostname, port: server.address().port, path, method, ca, servername,
        headers: { authorization: `Bearer ${authorization}`, 'content-type': 'application/json' } }, res => {
        let output = ''; res.setEncoding('utf8'); res.on('data', d => output += d); res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(output) }));
      }); req.on('error', reject); req.end(data);
    });
  }
  await assert.rejects(request('/v1/sessions', { ca: null }));
  await assert.rejects(request('/v1/sessions', { servername: 'wrong-host.invalid' }), { code: 'ERR_TLS_CERT_ALTNAME_INVALID' });
  assert.equal((await request('/v1/sessions', { authorization: token('b'), data: JSON.stringify({ deviceId:'a',runtimeId:'ra',pairId:'ab' }) })).status, 401);
  assert.equal((await request('/v1/sessions', { data: '{' })).status, 400);
  assert.equal((await request('/v1/sessions', { data: 'x'.repeat(70_000) })).status, 413);
  const joined = await request('/v1/sessions', { data: JSON.stringify({ deviceId:'a',runtimeId:'ra',pairId:'ab' }) });
  assert.equal(joined.status, 201); assert.ok(joined.body.leaseToken);
});
