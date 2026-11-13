import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { createControlPlane, canonicalRequest, pairingTranscript } from '../src/control-plane.mjs';
import { createTestStore } from './test-store.mjs';
import { transact } from '../src/store.mjs';

async function fixture(registerPair = true) {
  let now = 1_800_000_000_000;
  const store = await createTestStore();
  const tickets = [];
  const api = createControlPlane({ store, now: () => now, issueTicket: async grant => {
    tickets.push(grant); return { ...grant, appId: 1400802321, authBuffer: 'test-only', expiresAt: Math.floor(now / 1000) + 300 };
  } });
  const device = id => {
    const key = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    return { id, key, publicKey: key.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') };
  };
  const a = device('device-a'), b = device('device-b'), outsider = device('outsider');
  function request(who, method, path, body = undefined, overrides = {}) {
    const raw = body === undefined ? '' : JSON.stringify(body);
    const timestamp = String(now), nonce = randomUUID();
    return { rawPath: path, requestContext: { http: { method } }, body: raw, headers: {
      'x-device-id': who.id, 'x-timestamp': timestamp, 'x-nonce': nonce,
      'x-signature': sign('sha256', Buffer.from(canonicalRequest(method, path, raw, timestamp, nonce)), who.key.privateKey).toString('base64'),
      ...overrides,
    } };
  }
  async function send(who, method, path, body, overrides) {
    const result = await api.http(request(who, method, path, body, overrides));
    return { status: result.statusCode, body: JSON.parse(result.body) };
  }
  const post = (who, path, body = {}) => send(who, 'POST', path, body);
  for (const who of [a, b, outsider]) assert.equal((await post(who, '/v1/devices/register', { publicKey: who.publicKey })).status, 200);
  const transcript = {
    protocolVersion: 1, pairId: 'pair-ab', deviceIdA: a.id, deviceIdB: b.id,
    publicKeyA: a.publicKey, publicKeyB: b.publicKey,
    createdAt: now, nonce: randomUUID(), expiresAt: now + 300_000,
  };
  const pairBody = { transcript,
    signatureA: sign('sha256', Buffer.from(pairingTranscript(transcript)), a.key.privateKey).toString('base64'),
    signatureB: sign('sha256', Buffer.from(pairingTranscript(transcript)), b.key.privateKey).toString('base64'),
  };
  if (registerPair) assert.equal((await post(a, '/v1/pairs/register', pairBody)).status, 200);
  async function online(who) {
    const token = await post(who, '/v1/ws-token');
    const event = { requestContext: { routeKey: '$connect', connectionId: `conn-${who.id}` }, queryStringParameters: { token: token.body.token } };
    assert.equal((await api.websocket(event)).statusCode, 200);
    return event;
  }
  async function ride(who = a, requestId = randomUUID()) {
    const result = await post(who, '/v1/rides/start', { pairId: 'pair-ab', requestId });
    assert.equal(result.status, 200); return result.body;
  }
  async function invite() {
    await online(b); const r = await ride();
    const i = await post(a, `/v1/rides/${r.rideId}/invite`);
    assert.equal(i.status, 200); return { ride: r, invite: i.body.invitation };
  }
  return { api, store, tickets, a, b, outsider, transcript, pairBody, request, send, post, online, ride, invite,
    advance: ms => { now += ms; }, time: () => now };
}

async function activeCloud(f) {
  const r=await f.ride();
  for(const who of [f.a,f.b]) await f.post(who,`/v1/rides/${r.rideId}/local-established`);
  await f.post(f.a,`/v1/rides/${r.rideId}/fallback`,{reason:'TRANSPORT_FAILURE'});
  for(const who of [f.a,f.b]) await f.post(who,`/v1/rides/${r.rideId}/cloud-status`,{status:'CONNECTED'});
  return r;
}
async function stableProbe(f,r,probeId='a'.repeat(64)) {
  let response;
  for(let i=0;i<=10;i++) {
    if(i) f.advance(1000);
    for(const who of [f.a,f.b]) response=await f.post(who,`/v1/rides/${r.rideId}/local-probe`,{probeId,ready:true});
  }
  return response.body;
}

test('回切要求双方同一探测连续稳定十秒，两端准备和完成后才停止云端计时',async()=>{
  const f=await fixture(),r=await activeCloud(f),probeId='a'.repeat(64),url=`/v1/rides/${r.rideId}/local-switch`;
  assert.equal((await f.post(f.outsider,`/v1/rides/${r.rideId}/local-probe`,{probeId,ready:true})).status,403);
  const ready=await stableProbe(f,r,probeId);
  assert.equal(ready.state,'SWITCHING_LOCAL'); assert.equal(ready.localSwitchAttempts,1);
  const body={switchId:ready.localSwitch.switchId,probeId};
  assert.equal((await f.post(f.a,url,{...body,stage:'COMPLETE'})).status,409);
  assert.equal((await f.post(f.a,url,{...body,stage:'PREPARED'})).body.localSwitch.phase,'PREPARING');
  assert.equal((await f.post(f.b,url,{...body,stage:'PREPARED'})).body.localSwitch.phase,'ARMED');
  assert.equal((await f.post(f.a,url,{...body,stage:'COMPLETE'})).body.state,'SWITCHING_LOCAL');
  f.advance(100);
  const done=(await f.post(f.b,url,{...body,stage:'COMPLETE'})).body;
  assert.equal(done.state,'LOCAL_ACTIVE'); assert.equal(done.roomId,r.roomId); assert.equal(done.cloudStartedAt,null);
  const used=done.cloudUsedMs; f.advance(5000);
  assert.equal((await f.send(f.a,'GET',`/v1/rides/${r.rideId}`)).body.cloudUsedMs,used);
  const fallback=(await f.post(f.a,`/v1/rides/${r.rideId}/fallback`,{reason:'TRANSPORT_FAILURE'})).body;
  assert.equal(fallback.roomId,r.roomId); assert.equal(fallback.cloudUsedMs,used);
});

test('单端、不同探测和中断后的旧稳定时间不能批准回切',async()=>{
  const f=await fixture(),r=await activeCloud(f),url=`/v1/rides/${r.rideId}/local-probe`;
  for(let i=0;i<15;i++) {
    f.advance(1000);
    await f.post(f.a,url,{probeId:'a'.repeat(64),ready:true});
    assert.equal((await f.post(f.b,url,{probeId:'b'.repeat(64),ready:true})).body.state,'CLOUD_ACTIVE');
  }
  await f.post(f.b,url,{probeId:'a'.repeat(64),ready:true}); f.advance(4000);
  assert.equal((await f.post(f.a,url,{probeId:'a'.repeat(64),ready:true})).body.state,'CLOUD_ACTIVE');
  assert.equal((await f.post(f.b,url,{probeId:'a'.repeat(64),ready:true})).body.state,'CLOUD_ACTIVE');
});

test('回切超时或失败留在云端，三次预算耗尽，旧请求不能覆盖新尝试或已结束骑行',async()=>{
  const f=await fixture(),r=await activeCloud(f),url=`/v1/rides/${r.rideId}/local-switch`;
  let first;
  for(let i=0;i<3;i++) {
    const ready=await stableProbe(f,r); assert.equal(ready.localSwitchAttempts,i+1);
    const body={switchId:ready.localSwitch.switchId,probeId:ready.localSwitch.probeId,stage:'FAIL'};
    if(!first) first=body; else assert.equal((await f.post(f.a,url,first)).status,409);
    if(i===1) {f.advance(8001); assert.equal((await f.send(f.a,'GET',`/v1/rides/${r.rideId}`)).body.state,'CLOUD_ACTIVE');}
    else assert.equal((await f.post(f.a,url,body)).body.state,'CLOUD_ACTIVE');
  }
  assert.equal((await stableProbe(f,r)).state,'CLOUD_ACTIVE');
  await f.post(f.a,`/v1/rides/${r.rideId}/end`);
  assert.equal((await f.post(f.b,url,first)).body.error,'RIDE_ENDED');
});

test('撤回尚未提交的双签证明后，迟到登记也不可恢复配对', async () => {
  const f = await fixture(false);
  const transcript = { ...f.transcript, pairId: 'pair-pending' };
  const proof = { transcript,
    signatureA: sign('sha256', Buffer.from(pairingTranscript(transcript)), f.a.key.privateKey).toString('base64'),
    signatureB: sign('sha256', Buffer.from(pairingTranscript(transcript)), f.b.key.privateKey).toString('base64') };
  assert.equal((await f.post(f.a, '/v1/pairs/pair-pending/revoke')).status, 200);
  assert.equal((await f.post(f.b, '/v1/pairs/register', proof)).body.error, 'PAIR_REVOKED');
});

test('无关设备撤回未知 pairId 不能阻止合法双方配对', async () => {
  const f = await fixture(false);
  const transcript = { ...f.transcript, pairId: 'pair-independent' };
  const proof = { transcript,
    signatureA: sign('sha256', Buffer.from(pairingTranscript(transcript)), f.a.key.privateKey).toString('base64'),
    signatureB: sign('sha256', Buffer.from(pairingTranscript(transcript)), f.b.key.privateKey).toString('base64') };
  assert.equal((await f.post(f.outsider, '/v1/pairs/pair-independent/revoke')).status, 200);
  assert.equal((await f.post(f.a, '/v1/pairs/register', proof)).status, 200);
});

test('签名绑定方法、路径、原始正文，nonce 不可重放，时间窗为 ±60 秒', async () => {
  const f = await fixture();
  const req = f.request(f.a, 'POST', '/v1/ws-token', {});
  assert.equal((await f.api.http(req)).statusCode, 200);
  assert.equal((await f.api.http(req)).statusCode, 401);
  const changed = f.request(f.a, 'POST', '/v1/ws-token', {});
  changed.body = '{ }';
  assert.equal((await f.api.http(changed)).statusCode, 401);
  const expired = f.request(f.a, 'POST', '/v1/ws-token', {});
  f.advance(60_001);
  assert.equal((await f.api.http(expired)).statusCode, 401);
});

test('设备公钥不能替换；双签不完整、过期证明和无关设备均不可登记配对', async () => {
  const f = await fixture();
  assert.equal((await f.post(f.a, '/v1/devices/register', { publicKey: f.b.publicKey })).status, 401);
  assert.equal((await f.post(f.outsider, '/v1/pairs/register', f.pairBody)).status, 403);
  const forged = { ...f.pairBody, signatureB: f.pairBody.signatureA };
  assert.equal((await f.post(f.a, '/v1/pairs/register', forged)).status, 403);
  const old = { ...f.transcript, pairId: 'pair-old', createdAt: f.time() - 400_000, expiresAt: f.time() - 100_000 };
  const oldBody = { transcript: old,
    signatureA: sign('sha256', Buffer.from(pairingTranscript(old)), f.a.key.privateKey).toString('base64'),
    signatureB: sign('sha256', Buffer.from(pairingTranscript(old)), f.b.key.privateKey).toString('base64') };
  assert.equal((await f.post(f.a, '/v1/pairs/register', oldBody)).status, 400);
  // Retrying the exact registered proof remains idempotent after proof expiration.
  f.advance(300_001);
  assert.equal((await f.post(f.a, '/v1/pairs/register', f.pairBody)).status, 200);
});

test('邀请前和等待接受期间不能取票；拒绝、超时、主动结束均不可 fallback', async () => {
  for (const action of ['decline', 'timeout', 'end']) {
    const f = await fixture(); const { ride: r, invite } = await f.invite();
    assert.equal((await f.post(f.a, `/v1/rides/${r.rideId}/ticket`)).status, 409);
    assert.equal(f.tickets.length, 0);
    if (action === 'decline') await f.post(f.b, `/v1/rides/${r.rideId}/invite/respond`, { inviteId: invite.inviteId, response: 'DECLINE' });
    if (action === 'timeout') f.advance(30_000);
    if (action === 'end') await f.post(f.a, `/v1/rides/${r.rideId}/end`);
    assert.equal((await f.post(f.a, `/v1/rides/${r.rideId}/fallback`, { reason: 'TRANSPORT_FAILURE' })).status, 409);
    assert.equal((await f.post(f.b, `/v1/rides/${r.rideId}/invite/respond`, { inviteId: invite.inviteId, response: 'ACCEPT' })).status, 409);
    assert.equal(f.tickets.length, 0);
  }
});

test('离线邀请不排队；30 秒邀请重复提交复用记录，接受后双方取同一随机房号', async () => {
  const f = await fixture(); const r = await f.ride();
  assert.equal((await f.post(f.a, `/v1/rides/${r.rideId}/invite`)).body.error, 'PEER_OFFLINE');
  await f.online(f.b);
  const first = await f.post(f.a, `/v1/rides/${r.rideId}/invite`);
  const again = await f.post(f.a, `/v1/rides/${r.rideId}/invite`);
  assert.equal(first.body.invitation.inviteId, again.body.invitation.inviteId);
  const accepted = await f.post(f.b, `/v1/rides/${r.rideId}/invite/respond`, { inviteId: first.body.invitation.inviteId, response: 'ACCEPT' });
  assert.equal(accepted.status, 200);
  const [ta, tb] = await Promise.all([f.post(f.a, `/v1/rides/${r.rideId}/ticket`), f.post(f.b, `/v1/rides/${r.rideId}/ticket`)]);
  assert.equal(ta.status, 200); assert.equal(tb.status, 200);
  assert.equal(ta.body.roomId, tb.body.roomId);
  assert.notEqual(ta.body.openId, tb.body.openId);
  assert.equal(ta.body.peerOpenId, tb.body.openId);
  assert.ok(BigInt(ta.body.openId) > 10_000n && BigInt(ta.body.openId) < 0x7fffffffffffffffn);
  assert.equal((await f.post(f.outsider, `/v1/rides/${r.rideId}/ticket`)).status, 403);
  assert.equal((await f.store.snapshot()).some(record => JSON.stringify(record).includes('test-only')), false);
  await f.post(f.a, `/v1/rides/${r.rideId}/end`);
  const next = await f.ride(); assert.notEqual(next.roomId, r.roomId);
});

test('只有双方确认的成功本地链路能降级；双向并发 fallback 只迁移一次', async () => {
  const f = await fixture(); const r = await f.ride();
  const fallback = who => f.post(who, `/v1/rides/${r.rideId}/fallback`, { reason: 'TRANSPORT_FAILURE' });
  assert.equal((await fallback(f.a)).status, 409);
  await f.post(f.a, `/v1/rides/${r.rideId}/local-established`);
  assert.equal((await fallback(f.a)).status, 409);
  assert.equal((await f.post(f.b, `/v1/rides/${r.rideId}/local-established`)).body.state, 'LOCAL_ACTIVE');
  assert.equal((await f.post(f.a, `/v1/rides/${r.rideId}/fallback`, { reason: 'USER_ENDED' })).status, 400);
  const results = await Promise.all([fallback(f.a), fallback(f.b)]);
  assert.deepEqual(results.map(r => r.status), [200, 200]);
  assert.equal(results[0].body.revision, results[1].body.revision);
  const ready = (await f.store.snapshot()).filter(r => r.event?.type === 'ride.cloud.ready');
  assert.equal(ready.length, 1);
});

test('发起骑行幂等且绑定正文；两个车友同时占用同一设备只允许一场骑行', async () => {
  const f = await fixture(); const requestId = randomUUID();
  const results = await Promise.all([f.ride(f.a, requestId), f.ride(f.a, requestId)]);
  assert.equal(results[0].rideId, results[1].rideId);
  assert.equal((await f.post(f.a, '/v1/rides/start', { pairId: 'not-the-same', requestId })).status, 409);
  assert.equal((await f.post(f.a, '/v1/rides/start', { pairId: 'pair-ab', requestId: randomUUID() })).body.error, 'BUSY');
});

test('WebSocket token 为 60 秒单次票，连接失去心跳不算在线', async () => {
  const f = await fixture();
  const event = await f.online(f.b);
  assert.equal((await f.api.websocket(event)).statusCode, 401);
  const late = await f.post(f.a, '/v1/ws-token'); f.advance(60_000);
  assert.equal((await f.api.websocket({ requestContext: { routeKey: '$connect', connectionId: 'late' }, queryStringParameters: { token: late.body.token } })).statusCode, 401);
  f.advance(30_001);
  const r = await f.ride();
  assert.equal((await f.post(f.a, `/v1/rides/${r.rideId}/invite`)).body.error, 'PEER_OFFLINE');
});

test('双方离线 10 分钟失效，单方仍有签名心跳则保留会话', async () => {
  const f = await fixture(); const r = await f.ride();
  f.advance(599_999);
  assert.equal((await f.post(f.a, '/v1/heartbeat', { rideId: r.rideId })).status, 200);
  f.advance(599_999); await f.api.sweep();
  assert.notEqual((await f.send(f.a, 'GET', `/v1/rides/${r.rideId}`)).body.state, 'ENDED');
  f.advance(1); await f.api.sweep();
  const ended = await f.send(f.a, 'GET', `/v1/rides/${r.rideId}`);
  assert.equal(ended.body.state, 'ENDED'); assert.equal(ended.body.endReason, 'BOTH_OFFLINE');
});

test('云端 3.5 小时提示、4 小时拒绝取票，显式延长后允许继续', async () => {
  const f = await fixture(); const { ride: r, invite } = await f.invite();
  await f.post(f.b, `/v1/rides/${r.rideId}/invite/respond`, { inviteId: invite.inviteId, response: 'ACCEPT' });
  for (let i = 0; i < 24; i++) {
    f.advance(525_000); await f.post(f.a, '/v1/heartbeat', { rideId: r.rideId });
  }
  await f.api.sweep();
  assert.equal((await f.store.snapshot()).filter(r => r.event?.type === 'ride.cloud.warning').length, 1);
  for (let i = 0; i < 4; i++) {
    f.advance(450_000); await f.post(f.a, '/v1/heartbeat', { rideId: r.rideId });
  }
  assert.equal((await f.post(f.a, `/v1/rides/${r.rideId}/ticket`)).body.error, 'CLOUD_LIMIT');
  await f.post(f.a, `/v1/rides/${r.rideId}/extend`);
  assert.equal((await f.post(f.a, `/v1/rides/${r.rideId}/ticket`)).status, 200);
});

test('事件信封带 eventId、revision；结束操作重复提交不会重复推送', async () => {
  const f = await fixture(); const r = await f.ride();
  await Promise.all([f.post(f.a, `/v1/rides/${r.rideId}/end`), f.post(f.b, `/v1/rides/${r.rideId}/end`)]);
  const events = (await f.store.snapshot()).filter(r => r.event?.type === 'ride.ended');
  assert.equal(events.length, 1); assert.ok(events[0].event.eventId);
  assert.ok(events[0].event.revision > r.revision); assert.equal(events[0].event.occurredAt, f.time());
});

test('重新连接后可从签名快照找回待处理邀请；结束后不再提供恢复入口', async () => {
  const f = await fixture(); const { ride: r, invite } = await f.invite();
  const state = await f.send(f.b, 'GET', '/v1/state');
  assert.equal(state.body.invitations[0].inviteId, invite.inviteId);
  assert.equal(state.body.activeRide, null);
  await f.post(f.b, `/v1/rides/${r.rideId}/invite/respond`, { inviteId: invite.inviteId, response: 'ACCEPT' });
  assert.equal((await f.send(f.b, 'GET', '/v1/state')).body.activeRide.rideId, r.rideId);
  await f.post(f.a, `/v1/rides/${r.rideId}/end`);
  const after = await f.send(f.b, 'GET', '/v1/state');
  assert.equal(after.body.activeRide, null); assert.deepEqual(after.body.invitations, []);
});

test('解除配对删除公钥和双签证明，尚未过期的旧证明不可重新建立关系', async () => {
  const f = await fixture(); const r = await f.ride();
  assert.equal((await f.post(f.a, '/v1/pairs/pair-ab/revoke')).status, 200);
  const remaining = await f.store.snapshot();
  assert.equal(remaining.some(r => r.kind === 'PAIR'), false);
  assert.equal(remaining.some(r => r.kind === 'DEVICE' && ['device-a', 'device-b'].includes(r.deviceId)), false);
  for (const who of [f.a, f.b]) assert.equal((await f.post(who, '/v1/devices/register', { publicKey: who.publicKey })).status, 200);
  assert.equal((await f.post(f.a, '/v1/pairs/register', f.pairBody)).body.error, 'PAIR_REVOKED');
  assert.equal((await f.post(f.a, `/v1/rides/${r.rideId}/ticket`)).status, 403);
});

test('邀请等待期间被另一骑行占用，接受时自动回复忙线且保留已有骑行', async () => {
  const f = await fixture();
  const t = { protocolVersion: 1, pairId: 'pair-bc', deviceIdA: f.b.id, deviceIdB: f.outsider.id,
    publicKeyA: f.b.publicKey, publicKeyB: f.outsider.publicKey, createdAt: f.time(), nonce: randomUUID(), expiresAt: f.time() + 300_000 };
  const registration = await f.post(f.b, '/v1/pairs/register', { transcript: t,
    signatureA: sign('sha256', Buffer.from(pairingTranscript(t)), f.b.key.privateKey).toString('base64'),
    signatureB: sign('sha256', Buffer.from(pairingTranscript(t)), f.outsider.key.privateKey).toString('base64') });
  assert.equal(registration.status, 200);
  const { ride: r, invite } = await f.invite();
  const other = await f.post(f.b, '/v1/rides/start', { pairId: 'pair-bc', requestId: randomUUID() });
  assert.equal(other.status, 200);
  const response = await f.post(f.b, `/v1/rides/${r.rideId}/invite/respond`, { inviteId: invite.inviteId, response: 'ACCEPT' });
  assert.equal(response.body.endReason, 'PEER_BUSY');
  assert.equal((await f.send(f.b, 'GET', '/v1/state')).body.activeRide.rideId, other.body.rideId);
});

test('验签后发生解除配对与身份重建，旧请求不能取得新身份的凭据', async () => {
  const f = await fixture();
  const request = f.request(f.a, 'POST', '/v1/ws-token', {});
  const nonceKey = `NONCE#${f.a.id}#${request.headers['x-nonce']}`;
  const originalCommit = f.store.commit.bind(f.store);
  let ready, resume;
  const paused = new Promise(resolve => { ready = resolve; });
  const resumed = new Promise(resolve => { resume = resolve; });
  f.store.commit = async (reads, writes) => {
    await originalCommit(reads, writes);
    if (writes.has(nonceKey)) { ready(); await resumed; }
  };
  const pending = f.api.http(request);
  await paused;
  try {
    assert.equal((await f.post(f.b, '/v1/pairs/pair-ab/revoke')).status, 200);
    const key = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    const replacement = { id: f.a.id, key, publicKey: key.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') };
    assert.equal((await f.post(replacement, '/v1/devices/register', { publicKey: replacement.publicKey })).status, 200);
  } finally { resume(); }
  assert.equal((await pending).statusCode, 401);
});

test('重新登记设备会撤销旧 WebSocket 票据，即使沿用同一公钥也不能重放', async () => {
  const f = await fixture();
  const token = await f.post(f.a, '/v1/ws-token');
  await f.post(f.b, '/v1/pairs/pair-ab/revoke');
  await f.post(f.a, '/v1/devices/register', { publicKey: f.a.publicKey });
  const result = await f.api.websocket({ requestContext: { routeKey: '$connect', connectionId: 'old-generation' }, queryStringParameters: { token: token.body.token } });
  assert.equal(result.statusCode, 401);
  assert.equal(JSON.parse(result.body).error, 'WS_TOKEN_REVOKED');
});

test('连接先被 TTL 删除，心跳清理悬空引用并要求重新连接', async () => {
  const f = await fixture(); await f.online(f.b);
  await transact(f.store, tx => tx.delete('CONN#conn-device-b'));
  await f.api.sweep();
  const heartbeat = await f.post(f.b, '/v1/heartbeat');
  assert.equal(heartbeat.body.connectionAlive, false);
  assert.deepEqual((await f.store.get('DEVICE#device-b')).connectionIds, []);
  assert.equal((await f.send(f.a, 'GET', '/v1/state')).body.peers[0].online, false);
});
