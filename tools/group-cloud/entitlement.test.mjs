import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { EntitlementStore, normalizeActivationCode, shanghaiMonthKey } from './entitlement-store.mjs';
import { createGroupCloudServer } from './server.mjs';
import { GroupCloudStore } from './room-store.mjs';
import { validateAdminBaseUrl } from './codes-cli.mjs';

const TEST_APP_ID = 'test-volc-app-id';
const TEST_APP_KEY = 'test-volc-app-key-secret';
const TEST_HMAC_SECRET = '0123456789abcdef0123456789abcdef';
const TEST_ADMIN_TOKEN = 'admin-secret-token-0123456789abcdef';

async function startEntitlementServer({
  clock = { now: Date.UTC(2026, 10, 27, 4, 0, 0) },
  dbPath = ':memory:',
  globalMonthlyLimitMinutes = 8500,
  requireCodeForJoin = false,
  rtcTokenTtlMs = 10 * 60_000,
  lowQuotaWarnSeconds = 900,
  trustLoopbackProxy = false,
  onBeforeUnbanUser = null
} = {}) {
  const bans = [];
  const unbans = [];
  const entitlementStore = new EntitlementStore({
    dbPath,
    codeHmacSecret: TEST_HMAC_SECRET,
    globalMonthlyLimitMinutes,
    requireCodeForJoin,
    now: () => clock.now
  });
  const store = new GroupCloudStore({
    appId: TEST_APP_ID,
    appKey: TEST_APP_KEY,
    rtcTokenTtlMs,
    lowQuotaWarnSeconds,
    billingFlushSecondsThreshold: 10,
    billingFlushIntervalMs: 10_000,
    entitlementStore,
    now: () => clock.now,
    onRtcBanUser: async (rtcRoomId, userId, details) => {
      bans.push({ scope: 'USER', rtcRoomId, userId, ...details });
    },
    onRtcBanRoom: async (rtcRoomId, details) => {
      bans.push({ scope: 'ROOM', rtcRoomId, ...details });
    },
    onRtcUnbanUser: async (rtcRoomId, userId, details) => {
      if (typeof onBeforeUnbanUser === 'function') {
        await onBeforeUnbanUser({ rtcRoomId, userId, details, entitlementStore });
      }
      unbans.push({ rtcRoomId, userId, ...details });
    }
  });
  const { server } = createGroupCloudServer({
    host: '127.0.0.1',
    store,
    entitlementStore,
    adminToken: TEST_ADMIN_TOKEN,
    trustLoopbackProxy,
    sweepIntervalMs: 0,
    now: () => clock.now
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  return {
    baseUrl,
    store,
    entitlementStore,
    clock,
    bans,
    unbans,
    close: async () => {
      await new Promise((resolve) => server.close(resolve));
      entitlementStore.close();
    }
  };
}

async function api(baseUrl, method, endpoint, { body = null, bearer = '', entitlement = '', deviceId = '', headers = {} } = {}) {
  const reqHeaders = { ...headers };
  if (bearer) reqHeaders.Authorization = `Bearer ${bearer}`;
  if (entitlement) reqHeaders['X-Moto-Entitlement'] = entitlement;
  if (deviceId) reqHeaders['X-Moto-Device-Id'] = deviceId;
  if (body !== null) reqHeaders['Content-Type'] = 'application/json';
  const res = await fetch(`${baseUrl}${endpoint}`, {
    method,
    headers: reqHeaders,
    body: body !== null ? JSON.stringify(body) : undefined
  });
  const json = await res.json();
  return { status: res.status, json };
}

test('activation code format normalization, checksum validation, and rate limiting', async () => {
  const env = await startEntitlementServer({ trustLoopbackProxy: true });
  try {
    const minted = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      body: { count: 1, quotaMinutes: 300, validDays: 14, maxMembers: 6, batchId: 'b1' }
    });
    assert.equal(minted.status, 201);
    const rawCode = minted.json.codes[0].code;
    assert.match(rawCode, /^MOTO-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/);
    const parsed = normalizeActivationCode(rawCode.toLowerCase().replaceAll('-', ' '));
    assert.ok(parsed);
    assert.equal(parsed.formatted, rawCode);

    // Tampered checksum fails
    const tampered = rawCode.slice(0, -1) + (rawCode.endsWith('0') ? '1' : '0');
    assert.equal(normalizeActivationCode(tampered), null);

    // 5 bad attempts trigger 429 rate limit
    for (let i = 0; i < 5; i += 1) {
      const bad = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
        body: { code: tampered, deviceId: 'dev-brute' },
        headers: { 'X-Real-IP': '203.0.113.10' }
      });
      assert.equal(bad.status, 400);
    }
    const limited = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: rawCode, deviceId: 'dev-brute' },
      headers: { 'X-Real-IP': '203.0.113.10' }
    });
    assert.equal(limited.status, 429);
    assert.equal(limited.json.code, 'RATE_LIMITED');

    // Valid redeem from another IP/device succeeds and database never stores plaintext
    const ok = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: rawCode, deviceId: 'dev-clean' },
      headers: { 'X-Real-IP': '203.0.113.11' }
    });
    assert.equal(ok.status, 200);
    assert.equal(ok.json.action, 'ACTIVATED');
    assert.equal(ok.json.entitlement.remainingMinutes, 300);
    assert.equal(ok.json.entitlement.maxMembers, 6);

    const list = await api(env.baseUrl, 'GET', '/admin/v1/codes?batchId=b1', { bearer: TEST_ADMIN_TOKEN });
    assert.equal(list.status, 200);
    assert.equal(list.json.items[0].code, undefined);
    assert.match(list.json.items[0].codeMask, /^MOTO-\*{4}-\*{4}-[0-9A-Z]{4}$/);
  } finally {
    await env.close();
  }
});

test('host-sponsored NETWORK & HYBRID creation, free member join, maxMembers cap, and concurrent room limit', async () => {
  const env = await startEntitlementServer();
  try {
    // Unactivated host blocked for both NETWORK and HYBRID
    const noAuthNet = await api(env.baseUrl, 'POST', '/v1/rooms', {
      body: { deviceId: 'host-1', runtimeId: 'run-1', nickname: 'Host', mode: 'NETWORK' }
    });
    assert.equal(noAuthNet.status, 401);
    assert.equal(noAuthNet.json.code, 'ENTITLEMENT_REQUIRED');

    const noAuthHybrid = await api(env.baseUrl, 'POST', '/v1/rooms', {
      body: { deviceId: 'host-1', runtimeId: 'run-1', nickname: 'Host', mode: 'HYBRID' }
    });
    assert.equal(noAuthHybrid.status, 401);
    assert.equal(noAuthHybrid.json.code, 'ENTITLEMENT_REQUIRED');

    // Mint code with maxMembers=3, maxConcurrentRooms=1
    const minted = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      body: { count: 1, quotaMinutes: 300, validDays: 14, maxMembers: 3, maxConcurrentRooms: 1 }
    });
    const redeemed = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: minted.json.codes[0].code, deviceId: 'host-1' }
    });
    const entToken = redeemed.json.entitlementToken;

    // Create NETWORK room succeeds with maxMembers capped at 3
    const created = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: entToken,
      body: { deviceId: 'host-1', runtimeId: 'run-1', nickname: 'Host', requireApproval: false, mode: 'NETWORK' }
    });
    assert.equal(created.status, 201);
    assert.equal(created.json.snapshot.maxMembers, 3);
    const inviteCode = created.json.snapshot.invite.code;

    // Second concurrent room rejected
    const secondRoom = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: entToken,
      body: { deviceId: 'host-1', runtimeId: 'run-2', nickname: 'Host', mode: 'HYBRID' }
    });
    assert.equal(secondRoom.status, 409);
    assert.equal(secondRoom.json.code, 'CONCURRENT_ROOM_LIMIT');

    // Members join WITHOUT activation code up to maxMembers=3
    const m2 = await api(env.baseUrl, 'POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'peer-2', runtimeId: 'run-p2', nickname: 'Peer2' }
    });
    assert.equal(m2.status, 200);
    const m3 = await api(env.baseUrl, 'POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'peer-3', runtimeId: 'run-p3', nickname: 'Peer3' }
    });
    assert.equal(m3.status, 200);
    const m4 = await api(env.baseUrl, 'POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'peer-4', runtimeId: 'run-p4', nickname: 'Peer4' }
    });
    assert.equal(m4.status, 409);
    assert.equal(m4.json.code, 'ROOM_FULL');
  } finally {
    await env.close();
  }
});

test('HYBRID LOCAL zero billing, CLOUD person-seconds billing, dynamic TTL, low warning, owner transfer attribution, and BanRoomUser exhaustion', async () => {
  const clock = { now: Date.UTC(2026, 10, 27, 4, 0, 0) };
  const env = await startEntitlementServer({ clock, lowQuotaWarnSeconds: 180 });
  try {
    // 4 minutes total quota = 240 person-seconds
    const minted = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      body: { count: 1, quotaMinutes: 4, validDays: 14, maxMembers: 6 }
    });
    const redeemed = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: minted.json.codes[0].code, deviceId: 'host-h' }
    });
    const entToken = redeemed.json.entitlementToken;

    // Create HYBRID room (starts in LOCAL)
    const created = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: entToken,
      body: { deviceId: 'host-h', runtimeId: 'r-h', nickname: 'Host', requireApproval: false, mode: 'HYBRID' }
    });
    assert.equal(created.status, 201);
    const roomId = created.json.roomId;
    const hostToken = created.json.sessionToken;
    const inviteCode = created.json.snapshot.invite.code;

    const peer = await api(env.baseUrl, 'POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'peer-h', runtimeId: 'r-p', nickname: 'Peer' }
    });
    assert.equal(peer.status, 200);
    const peerToken = peer.json.sessionToken;

    // 5 minutes in HYBRID LOCAL must bill 0 seconds (keep heartbeats alive)
    for (let i = 0; i < 15; i += 1) {
      clock.now += 20_000;
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: hostToken });
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: peerToken });
      await env.store.sweep(clock.now);
    }
    let me = await api(env.baseUrl, 'GET', '/v1/entitlements/me', { entitlement: entToken, deviceId: 'host-h' });
    assert.equal(me.json.entitlement.usedSeconds, 0);
    assert.equal(me.json.entitlement.remainingSeconds, 240);

    // Switch to CLOUD -> dynamic TTL should shrink from 600s to 240/2 = 120s
    const switched = await api(env.baseUrl, 'POST', `/v1/rooms/${roomId}/transport/switch`, {
      bearer: hostToken,
      body: {
        targetTransport: 'CLOUD',
        reason: 'MANUAL_CLOUD',
        expectedRevision: peer.json.snapshot.revision,
        expectedTransportEpoch: 1
      }
    });
    assert.equal(switched.status, 200);
    assert.ok(switched.json.rtc);
    const ttlMs = switched.json.rtc.expiresAtMs - clock.now;
    assert.ok(ttlMs <= 125_000 && ttlMs >= 115_000, `expected dynamic TTL ~120s, got ${ttlMs}ms`);

    // Transfer ownership to unactivated peer-h -> room stays billed to original host entitlement
    const transferred = await api(env.baseUrl, 'POST', `/v1/rooms/${roomId}/owner-transfer`, {
      bearer: hostToken,
      body: { targetDeviceId: 'peer-h', alsoLeave: false, expectedRevision: switched.json.snapshot.revision }
    });
    assert.equal(transferred.status, 200);

    // Advance 40s in CLOUD with 2 members -> 80 person-seconds billed, remaining 160s <= 180s triggers QUOTA_WARNING
    for (let i = 0; i < 4; i += 1) {
      clock.now += 10_000;
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: hostToken });
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: peerToken });
      await env.store.sweep(clock.now);
    }
    const peerEvents = await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: peerToken });
    assert.ok(
      peerEvents.json.events.some((e) => e.type === 'QUOTA_WARNING' && e.warningType === 'QUOTA_LOW'),
      'expected QUOTA_LOW warning event to current owner'
    );

    // Advance 50s more (total 90s = 180 person-seconds used, 60 person-seconds left) and renew RTC token
    for (let i = 0; i < 5; i += 1) {
      clock.now += 10_000;
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: hostToken });
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: peerToken });
      await env.store.sweep(clock.now);
    }
    const renewed = await api(env.baseUrl, 'POST', `/v1/rooms/${roomId}/rtc-token`, { bearer: peerToken });
    assert.equal(renewed.status, 200);
    assert.ok(renewed.json.rtc.expiresAtMs - clock.now >= 59_000);

    // Advance another 40s -> total 260 person-seconds > 240s -> auto-end + BanRoomUser on still-valid renewed token
    const bansBefore = env.bans.length;
    for (let i = 0; i < 5; i += 1) {
      clock.now += 10_000;
      await env.store.sweep(clock.now);
      const active = env.store.listActiveRoomsSummary(clock.now);
      if (active.length === 0) break;
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: hostToken }).catch(() => {});
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: peerToken }).catch(() => {});
    }
    assert.equal(env.store.listActiveRoomsSummary(clock.now).length, 0);
    assert.ok(env.bans.length > bansBefore, 'expected BanRoomUser to be invoked on quota exhaustion');
    assert.ok(env.bans.some((b) => b.reason === 'QUOTA_EXHAUSTED'));

    me = await api(env.baseUrl, 'GET', '/v1/entitlements/me', { entitlement: entToken, deviceId: 'host-h' });
    assert.equal(me.json.entitlement.remainingSeconds, 0);

    // Creating another room fails with 402 QUOTA_EXHAUSTED
    const blockedCreate = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: entToken,
      body: { deviceId: 'host-h', runtimeId: 'r-h2', nickname: 'Host', mode: 'NETWORK' }
    });
    assert.equal(blockedCreate.status, 402);
    assert.equal(blockedCreate.json.code, 'QUOTA_EXHAUSTED');
  } finally {
    await env.close();
  }
});

test('code stacking, bounded 7-day device rebind with old room termination, and admin reset-rebind', async () => {
  const clock = { now: Date.UTC(2026, 10, 27, 4, 0, 0) };
  const env = await startEntitlementServer({ clock });
  try {
    const batch = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      body: { count: 2, quotaMinutes: 100, validDays: 14, maxMembers: 6 }
    });
    const code1 = batch.json.codes[0].code;
    const code2 = batch.json.codes[1].code;

    // Redeem code1 on dev-A
    const r1 = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: code1, deviceId: 'dev-A' }
    });
    assert.equal(r1.status, 200);
    const tokenA = r1.json.entitlementToken;
    const entId = r1.json.entitlement.entitlementId;

    // Stack code2 on dev-A -> total 200 minutes
    const r2 = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      entitlement: tokenA,
      body: { code: code2, deviceId: 'dev-A' }
    });
    assert.equal(r2.status, 200);
    assert.equal(r2.json.action, 'STACKED');
    assert.equal(r2.json.entitlement.remainingMinutes, 200);

    // dev-A creates an active room
    const roomA = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: tokenA,
      body: { deviceId: 'dev-A', runtimeId: 'run-A', nickname: 'A', mode: 'NETWORK' }
    });
    assert.equal(roomA.status, 201);

    // Rebind #1 on dev-B using code1 -> invalidates tokenA and terminates roomA
    const rb1 = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: code1, deviceId: 'dev-B' }
    });
    assert.equal(rb1.status, 200);
    assert.equal(rb1.json.action, 'REBOUND');
    assert.equal(rb1.json.rebound, true);
    assert.equal(rb1.json.entitlement.remainingMinutes, 200);
    assert.equal(env.store.listActiveRoomsSummary(clock.now).length, 0);

    const oldCheck = await api(env.baseUrl, 'GET', '/v1/entitlements/me', { entitlement: tokenA, deviceId: 'dev-A' });
    assert.equal(oldCheck.status, 401);

    // Rebind #2 on dev-C succeeds
    const rb2 = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: code1, deviceId: 'dev-C' }
    });
    assert.equal(rb2.status, 200);

    // Rebind #3 on dev-D within 7 days is rejected
    const rb3 = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: code1, deviceId: 'dev-D' }
    });
    assert.equal(rb3.status, 409);
    assert.equal(rb3.json.code, 'REBIND_LIMIT_EXCEEDED');

    // Admin resets rebind counter -> dev-D succeeds
    const reset = await api(env.baseUrl, 'POST', `/admin/v1/entitlements/${entId}/reset-rebind`, {
      bearer: TEST_ADMIN_TOKEN,
      body: { reason: 'user_changed_phone' }
    });
    assert.equal(reset.status, 200);
    const rb4 = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: code1, deviceId: 'dev-D' }
    });
    assert.equal(rb4.status, 200);
  } finally {
    await env.close();
  }
});

test('admin HTTP API auth, idempotency, topup, revoke with BanRoomUser, global quota emergency stop, and SQLite persistence', async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'moto-ent-test-'));
  const dbPath = path.join(tmpDir, 'entitlements.db');
  const clock = { now: Date.UTC(2026, 10, 27, 4, 0, 0) };
  let env = await startEntitlementServer({ clock, dbPath });
  try {
    assert.equal(shanghaiMonthKey(clock.now), '2026-11');

    // Unauthorized admin request rejected
    const noAdmin = await api(env.baseUrl, 'GET', '/admin/v1/overview');
    assert.equal(noAdmin.status, 401);

    // Idempotent batch minting
    const m1 = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'Idempotency-Key': 'idem-batch-1' },
      body: { count: 2, quotaMinutes: 120, validDays: 7, maxMembers: 8, batchId: 'wx-1' }
    });
    const m2 = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'Idempotency-Key': 'idem-batch-1' },
      body: { count: 2, quotaMinutes: 120, validDays: 7, maxMembers: 8, batchId: 'wx-1' }
    });
    assert.equal(m1.status, 201);
    assert.equal(m2.status, 201);
    assert.equal(m2.json.idempotentReplay, true);
    assert.deepEqual(m2.json.codes, m1.json.codes);

    // Redeem and topup idempotently
    const r = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: m1.json.codes[0].code, deviceId: 'dev-admin-test' }
    });
    const entId = r.json.entitlement.entitlementId;
    const entToken = r.json.entitlementToken;

    const top1 = await api(env.baseUrl, 'POST', `/admin/v1/entitlements/${entId}/topup`, {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'Idempotency-Key': 'idem-topup-1' },
      body: { addMinutes: 200, extendDays: 30, maxMembers: 20, reason: 'vip' }
    });
    const top2 = await api(env.baseUrl, 'POST', `/admin/v1/entitlements/${entId}/topup`, {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'Idempotency-Key': 'idem-topup-1' },
      body: { addMinutes: 200, extendDays: 30, maxMembers: 20, reason: 'vip' }
    });
    assert.equal(top1.status, 200);
    assert.equal(top1.json.entitlement.remainingMinutes, 320);
    assert.equal(top1.json.entitlement.maxMembers, 20);
    assert.equal(top2.json.idempotentReplay, true);
    assert.equal(top2.json.entitlement.remainingMinutes, 320);

    // Create room -> visible in active rooms -> admin force-end
    const room = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: entToken,
      body: { deviceId: 'dev-admin-test', runtimeId: 'r1', nickname: 'Tester', mode: 'NETWORK' }
    });
    assert.equal(room.status, 201);
    const active = await api(env.baseUrl, 'GET', '/admin/v1/rooms/active', { bearer: TEST_ADMIN_TOKEN });
    assert.equal(active.json.total, 1);

    const ended = await api(env.baseUrl, 'POST', `/admin/v1/rooms/${room.json.roomId}/force-end`, {
      bearer: TEST_ADMIN_TOKEN,
      body: { reason: 'ADMIN_TEST_STOP' }
    });
    assert.equal(ended.status, 200);
    assert.ok(env.bans.some((b) => b.reason === 'ADMIN_TEST_STOP'));

    // Global emergency stop (set hardLimitMinutes = 0) terminates active rooms and blocks new rooms
    const room2 = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: entToken,
      body: { deviceId: 'dev-admin-test', runtimeId: 'r2', nickname: 'Tester', mode: 'NETWORK' }
    });
    assert.equal(room2.status, 201);
    const stopAll = await api(env.baseUrl, 'PUT', '/admin/v1/config/global-quota', {
      bearer: TEST_ADMIN_TOKEN,
      body: { hardLimitMinutes: 0 }
    });
    assert.equal(stopAll.status, 200);
    assert.equal(stopAll.json.terminatedRoomIds.length, 1);

    const blockedGlobal = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: entToken,
      body: { deviceId: 'dev-admin-test', runtimeId: 'r3', nickname: 'Tester', mode: 'NETWORK' }
    });
    assert.equal(blockedGlobal.status, 503);
    assert.equal(blockedGlobal.json.code, 'GLOBAL_QUOTA_EXHAUSTED');

    // Restore global limit and verify audit logs
    await api(env.baseUrl, 'PUT', '/admin/v1/config/global-quota', {
      bearer: TEST_ADMIN_TOKEN,
      body: { hardLimitMinutes: 8500 }
    });
    const audits = await api(env.baseUrl, 'GET', '/admin/v1/audit-logs', { bearer: TEST_ADMIN_TOKEN });
    assert.ok(audits.json.total >= 5);

    // Restart server from same SQLite DB file and confirm state survived
    await env.close();
    env = await startEntitlementServer({ clock, dbPath });
    const afterRestart = await api(env.baseUrl, 'GET', '/v1/entitlements/me', { entitlement: entToken, deviceId: 'dev-admin-test' });
    assert.equal(afterRestart.status, 200);
    assert.equal(afterRestart.json.entitlement.remainingMinutes, 320);
    const overview = await api(env.baseUrl, 'GET', '/admin/v1/overview', { bearer: TEST_ADMIN_TOKEN });
    assert.equal(overview.status, 200);
    assert.equal(overview.json.codeStats.REDEEMED, 1);
    assert.equal(overview.json.codeStats.UNUSED, 1);
  } finally {
    await env.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('requireCodeForJoin toggle and anti-idle maxSessionMinutes cutoff work end to end', async () => {
  const clock = { now: Date.UTC(2026, 10, 27, 4, 0, 0) };
  const env = await startEntitlementServer({ clock, requireCodeForJoin: true });
  try {
    const batch = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      body: { count: 2, quotaMinutes: 300, validDays: 14, maxMembers: 6, maxSessionMinutes: 5 }
    });
    const hostRedeem = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: batch.json.codes[0].code, deviceId: 'host-idle' }
    });
    const peerRedeem = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: batch.json.codes[1].code, deviceId: 'peer-idle' }
    });

    const room = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: hostRedeem.json.entitlementToken,
      body: { deviceId: 'host-idle', runtimeId: 'r1', nickname: 'Host', requireApproval: false, mode: 'NETWORK' }
    });
    const inviteCode = room.json.snapshot.invite.code;
    const roomId = room.json.roomId;
    const hostToken = room.json.sessionToken;

    const blockedJoin = await api(env.baseUrl, 'POST', '/v1/rooms/join-requests', {
      body: { inviteCode, deviceId: 'peer-idle', runtimeId: 'p1', nickname: 'Peer' }
    });
    assert.equal(blockedJoin.status, 401);

    const okJoin = await api(env.baseUrl, 'POST', '/v1/rooms/join-requests', {
      entitlement: peerRedeem.json.entitlementToken,
      body: { inviteCode, deviceId: 'peer-idle', runtimeId: 'p1', nickname: 'Peer' }
    });
    assert.equal(okJoin.status, 200);
    const peerToken = okJoin.json.sessionToken;

    for (let i = 0; i < 31; i += 1) {
      clock.now += 10_000;
      await env.store.sweep(clock.now);
      if (env.store.listActiveRoomsSummary(clock.now).length === 0) break;
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: hostToken }).catch(() => {});
      await api(env.baseUrl, 'GET', `/v1/rooms/${roomId}/events?after=0&waitMs=0`, { bearer: peerToken }).catch(() => {});
    }
    assert.equal(env.store.listActiveRoomsSummary(clock.now).length, 0);
    assert.ok(env.bans.some((b) => b.reason === 'SESSION_TIME_LIMIT'));
  } finally {
    await env.close();
  }
});

test('P0-1 regression: entitlement token bound to device-A cannot be used by device-B to create or join rooms', async () => {
  const env = await startEntitlementServer({ requireCodeForJoin: true });
  try {
    const minted = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      body: { count: 1, quotaMinutes: 300, validDays: 14, maxMembers: 6 }
    });
    const redeemed = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: minted.json.codes[0].code, deviceId: 'device-A' }
    });
    const tokenA = redeemed.json.entitlementToken;

    // device-B attempting to create room with device-A token must fail 403
    const hijackCreate = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: tokenA,
      body: { deviceId: 'device-B', runtimeId: 'run-B', nickname: 'Hijacker', mode: 'NETWORK' }
    });
    assert.equal(hijackCreate.status, 403);
    assert.equal(hijackCreate.json.code, 'ENTITLEMENT_DEVICE_MISMATCH');

    // device-A succeeds in creating room
    const okCreate = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: tokenA,
      body: { deviceId: 'device-A', runtimeId: 'run-A', nickname: 'Owner', requireApproval: false, mode: 'NETWORK' }
    });
    assert.equal(okCreate.status, 201);

    // device-B attempting to join with device-A token when requireCodeForJoin=true must also fail 403
    const hijackJoin = await api(env.baseUrl, 'POST', '/v1/rooms/join-requests', {
      entitlement: tokenA,
      body: { inviteCode: okCreate.json.snapshot.invite.code, deviceId: 'device-B', runtimeId: 'run-B2', nickname: 'Hijacker' }
    });
    assert.equal(hijackJoin.status, 403);
    assert.equal(hijackJoin.json.code, 'ENTITLEMENT_DEVICE_MISMATCH');

    // GET /v1/entitlements/me without X-Moto-Device-Id or with blank header must fail 400
    const missingHeaderMe = await api(env.baseUrl, 'GET', '/v1/entitlements/me', {
      entitlement: tokenA
    });
    assert.equal(missingHeaderMe.status, 400);
    assert.equal(missingHeaderMe.json.code, 'DEVICE_ID_REQUIRED');

    const blankHeaderMe = await api(env.baseUrl, 'GET', '/v1/entitlements/me', {
      entitlement: tokenA,
      headers: { 'X-Moto-Device-Id': '   ' }
    });
    assert.equal(blankHeaderMe.status, 400);
    assert.equal(blankHeaderMe.json.code, 'DEVICE_ID_REQUIRED');

    // Direct store call without expectedDeviceId must also fail closed
    assert.throws(
      () => env.entitlementStore.getEntitlementStatus(tokenA, Date.now()),
      (err) => err?.status === 400 && err?.code === 'INVALID_INPUT'
    );

    // GET /v1/entitlements/me with mismatched X-Moto-Device-Id fails 403
    const mismatchMe = await api(env.baseUrl, 'GET', '/v1/entitlements/me', {
      entitlement: tokenA,
      deviceId: 'device-B'
    });
    assert.equal(mismatchMe.status, 403);
    assert.equal(mismatchMe.json.code, 'ENTITLEMENT_DEVICE_MISMATCH');

    // GET /v1/entitlements/me with matching X-Moto-Device-Id succeeds 200
    const okMe = await api(env.baseUrl, 'GET', '/v1/entitlements/me', {
      entitlement: tokenA,
      deviceId: 'device-A'
    });
    assert.equal(okMe.status, 200);
    assert.equal(okMe.json.entitlement.ownerDeviceId, 'device-A');
  } finally {
    await env.close();
  }
});

test('P0-2 regression: switchTransport(CLOUD) stays atomic on LOCAL when global quota is exhausted during OpenAPI unban await', async () => {
  const clock = { now: Date.UTC(2026, 10, 27, 4, 0, 0) };
  let exhaustOnUnban = false;
  const env = await startEntitlementServer({
    clock,
    onBeforeUnbanUser: async ({ entitlementStore }) => {
      if (exhaustOnUnban) {
        exhaustOnUnban = false;
        entitlementStore.updateGlobalQuotaConfig({ hardLimitMinutes: 0, actorIp: '127.0.0.1' }, clock.now);
      }
    }
  });
  try {
    const minted = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      body: { count: 1, quotaMinutes: 300, validDays: 14, maxMembers: 4 }
    });
    const redeemed = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: minted.json.codes[0].code, deviceId: 'host-atomic' }
    });
    const entToken = redeemed.json.entitlementToken;
    const created = await api(env.baseUrl, 'POST', '/v1/rooms', {
      entitlement: entToken,
      body: { deviceId: 'host-atomic', runtimeId: 'r-1', nickname: 'Host', requireApproval: false, mode: 'HYBRID' }
    });
    assert.equal(created.status, 201);
    const roomId = created.json.roomId;
    const hostToken = created.json.sessionToken;
    const initialRevision = created.json.snapshot.revision;

    // Seed a prior ban record on next epoch's RTC room so switchTransport awaits OpenAPI unban
    const roomObj = env.store.rooms.get(roomId);
    const hostMember = roomObj.members.get('host-atomic');
    const nextRtcRoomId = `${roomObj.rtcRoomId}_e2_pub`;
    roomObj.bannedRtcPairs.set(`${nextRtcRoomId}\0${hostMember.rtcUserId}`, {
      rtcRoomId: nextRtcRoomId,
      userId: hostMember.rtcUserId,
      bannedUntilMs: clock.now + 60_000,
      grantExpiresAtMs: clock.now + 60_000
    });
    exhaustOnUnban = true;

    const switched = await api(env.baseUrl, 'POST', `/v1/rooms/${roomId}/transport/switch`, {
      bearer: hostToken,
      body: {
        targetTransport: 'CLOUD',
        reason: 'MANUAL_CLOUD',
        expectedRevision: initialRevision,
        expectedTransportEpoch: 1
      }
    });
    assert.equal(switched.status, 503);
    assert.equal(switched.json.code, 'GLOBAL_QUOTA_EXHAUSTED');

    // Room transport state must remain cleanly LOCAL at epoch 1 and original revision (no split-brain)
    assert.equal(roomObj.transport.active, 'LOCAL');
    assert.equal(roomObj.transport.epoch, 1);
    assert.equal(roomObj.revision, initialRevision);
  } finally {
    await env.close();
  }
});

test('P1-1, P1-3, P1-4 regressions: idempotency conflict, CLI cleartext rejection, and proxy-header-immune admin rate limit', async () => {
  // P1-3: Admin CLI rejects cleartext HTTP to non-loopback hosts and embedded credentials
  assert.throws(() => validateAdminBaseUrl('http://group.toneride.top'), /cleartext HTTP/i);
  assert.throws(() => validateAdminBaseUrl('https://user:pass@group.toneride.top'), /credentials/i);
  assert.equal(validateAdminBaseUrl('https://group.toneride.top/'), 'https://group.toneride.top');
  assert.equal(validateAdminBaseUrl('http://127.0.0.1:8443/'), 'http://127.0.0.1:8443');

  // Default trustLoopbackProxy = false ignores spoofed X-Real-IP / X-Forwarded-For headers
  const env = await startEntitlementServer({ trustLoopbackProxy: false });
  try {
    // P1-1: Same Idempotency-Key with different batch or topup parameters must return 409 IDEMPOTENCY_CONFLICT
    const b1 = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'Idempotency-Key': 'k-batch' },
      body: { count: 1, quotaMinutes: 100, validDays: 7, maxMembers: 6 }
    });
    assert.equal(b1.status, 201);
    const bConflict = await api(env.baseUrl, 'POST', '/admin/v1/codes/batch', {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'Idempotency-Key': 'k-batch' },
      body: { count: 2, quotaMinutes: 100, validDays: 7, maxMembers: 6 }
    });
    assert.equal(bConflict.status, 409);
    assert.equal(bConflict.json.code, 'IDEMPOTENCY_CONFLICT');

    const r1 = await api(env.baseUrl, 'POST', '/v1/entitlements/redeem', {
      body: { code: b1.json.codes[0].code, deviceId: 'dev-idem-1' }
    });
    const ent1 = r1.json.entitlement.entitlementId;
    const t1 = await api(env.baseUrl, 'POST', `/admin/v1/entitlements/${ent1}/topup`, {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'Idempotency-Key': 'k-topup' },
      body: { addMinutes: 50, extendDays: 10 }
    });
    assert.equal(t1.status, 200);
    const tConflict = await api(env.baseUrl, 'POST', `/admin/v1/entitlements/${ent1}/topup`, {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'Idempotency-Key': 'k-topup' },
      body: { addMinutes: 999, extendDays: 10 }
    });
    assert.equal(tConflict.status, 409);
    assert.equal(tConflict.json.code, 'IDEMPOTENCY_CONFLICT');

    // P1-4: Spoofing rotating X-Real-IP / X-Forwarded-For headers from loopback cannot bypass admin rate limit
    for (let i = 0; i < 10; i += 1) {
      const bad = await api(env.baseUrl, 'GET', '/admin/v1/overview', {
        bearer: 'wrong-admin-token',
        headers: { 'X-Real-IP': `198.51.100.${i + 1}`, 'X-Forwarded-For': `198.51.100.${i + 1}` }
      });
      assert.equal(bad.status, 401);
    }
    const blocked = await api(env.baseUrl, 'GET', '/admin/v1/overview', {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'X-Real-IP': '198.51.100.250' }
    });
    assert.equal(blocked.status, 429);
    assert.equal(blocked.json.code, 'RATE_LIMITED');
  } finally {
    await env.close();
  }

  // Even when trustLoopbackProxy = true (behind Nginx), global aggregate cap stops distributed admin brute force
  const proxyEnv = await startEntitlementServer({ trustLoopbackProxy: true });
  try {
    for (let i = 0; i < 20; i += 1) {
      const bad = await api(proxyEnv.baseUrl, 'GET', '/admin/v1/overview', {
        bearer: 'wrong-token',
        headers: { 'X-Real-IP': `203.0.113.${i + 1}` }
      });
      assert.equal(bad.status, 401);
    }
    const globalBlocked = await api(proxyEnv.baseUrl, 'GET', '/admin/v1/overview', {
      bearer: TEST_ADMIN_TOKEN,
      headers: { 'X-Real-IP': '203.0.113.250' }
    });
    assert.equal(globalBlocked.status, 429);
    assert.equal(globalBlocked.json.code, 'RATE_LIMITED');
  } finally {
    await proxyEnv.close();
  }
});
