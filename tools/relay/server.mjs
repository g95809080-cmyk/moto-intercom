import https from 'node:https';
import { readFileSync } from 'node:fs';
import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const idPattern = /^[A-Za-z0-9_-]{1,64}$/;
const turnPattern = /^turns?:[A-Za-z0-9.\[\]:-]+(?:\?transport=(?:udp|tcp))?$/;
export const sha256 = value => createHash('sha256').update(value).digest('hex');
class HttpError extends Error { constructor(status, code) { super(code); this.status = status; } }
const reject = (status, code) => { throw new HttpError(status, code); };
const validId = value => typeof value === 'string' && idPattern.test(value);
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' &&
  a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));

export function validateConfig(config) {
  if (!config || typeof config !== 'object') throw new Error('Missing configuration');
  if (!Array.isArray(config.devices) || config.devices.length < 2 || config.devices.length > 1000)
    throw new Error('Provision 2..1000 devices');
  const ids = new Set();
  for (const device of config.devices) {
    if (!validId(device.deviceId) || ids.has(device.deviceId) || !/^[a-f0-9]{64}$/.test(device.tokenSha256))
      throw new Error('Invalid device configuration');
    ids.add(device.deviceId);
  }
  const pairs = new Set();
  if (!Array.isArray(config.pairs) || config.pairs.length > 1000) throw new Error('Invalid pairs');
  for (const pair of config.pairs) {
    if (!validId(pair.pairId) || pairs.has(pair.pairId) || !Array.isArray(pair.members) || pair.members.length !== 2 ||
        pair.members[0] === pair.members[1] || pair.members.some(id => !ids.has(id))) throw new Error('Invalid pair');
    pairs.add(pair.pairId);
  }
  if (!config.turn || typeof config.turn.secret !== 'string' || config.turn.secret.length < 32 ||
      !Array.isArray(config.turn.urls) || !config.turn.urls.length || config.turn.urls.length > 8 ||
      config.turn.urls.some(url => typeof url !== 'string' || !turnPattern.test(url))) throw new Error('Invalid TURN settings');
  return config;
}

/** In-memory development service. Provisioning is deliberately out of band. */
export class RelayService {
  constructor(config, { now = Date.now, idleMs = 60_000, lifetimeMs = 3_600_000, queueLimit = 128 } = {}) {
    this.config = validateConfig(config);
    if (!(idleMs > 0 && lifetimeMs >= idleMs && lifetimeMs <= 3_600_000 && queueLimit >= 2))
      throw new Error('Invalid service limits');
    this.now = now; this.idleMs = idleMs; this.lifetimeMs = lifetimeMs; this.queueLimit = queueLimit;
    this.sessions = new Map(); this.owners = new Map();
  }
  authenticate(deviceId, token) {
    const device = this.config.devices.find(d => d.deviceId === deviceId);
    if (typeof token !== 'string' || token.length < 32 || token.length > 256 || !device || !equal(device.tokenSha256, sha256(token)))
      reject(401, 'UNAUTHORIZED');
    return device;
  }
  sweep() {
    for (const session of this.sessions.values()) {
      if (!session.closed && (session.expiresAt <= this.now() || session.lastSeen + this.idleMs <= this.now()))
        this.end(session, 'EXPIRED');
      if (session.closed && session.closedAt + this.idleMs <= this.now()) this.sessions.delete(session.id);
    }
  }
  join(body, token) {
    this.sweep();
    if (!body || !validId(body.deviceId) || !validId(body.runtimeId) || !validId(body.pairId)) reject(400, 'INVALID_JOIN');
    this.authenticate(body.deviceId, token);
    const pair = this.config.pairs.find(p => p.pairId === body.pairId);
    if (!pair || !pair.members.includes(body.deviceId)) reject(403, 'PAIR_FORBIDDEN');
    const existing = this.owners.get(body.deviceId);
    if (existing) {
      if (existing.runtimeId !== body.runtimeId || existing.pairId !== body.pairId) reject(409, 'RUNTIME_BUSY');
      existing.lastSeen = this.now();
      return this.grant(existing);
    }
    // Bound retained records even when a valid device repeatedly joins/leaves.
    for (const [id, old] of this.sessions) if (old.closed && old.deviceId === body.deviceId) this.sessions.delete(id);
    const session = {
      id: randomUUID(), token: randomBytes(32).toString('base64url'), deviceId: body.deviceId,
      runtimeId: body.runtimeId, pairId: body.pairId, peerId: pair.members.find(id => id !== body.deviceId),
      role: [...pair.members].sort()[0] === body.deviceId ? 'offerer' : 'answerer',
      expiresAt: this.now() + this.lifetimeMs, lastSeen: this.now(), events: [], sequence: 0,
      acknowledged: 0, signalSequence: 0, callId: null, closed: false, wait: null,
      rateStart: this.now(), rateCount: 0,
    };
    this.sessions.set(session.id, session); this.owners.set(session.deviceId, session);
    const peer = this.owners.get(session.peerId);
    if (peer?.pairId === session.pairId && !peer.closed) {
      session.callId = peer.callId = randomUUID();
      for (const [a, b] of [[session, peer], [peer, session]]) this.emit(a, {
        type: 'ready', callId: a.callId, role: a.role,
        self: { deviceId: a.deviceId, runtimeId: a.runtimeId },
        peer: { deviceId: b.deviceId, runtimeId: b.runtimeId },
        expiresAt: Math.min(a.expiresAt, b.expiresAt),
      });
    }
    return this.grant(session);
  }
  grant(session) {
    const expiresAt = Math.floor(session.expiresAt / 1000) * 1000;
    const username = `${expiresAt / 1000}:${session.deviceId}`;
    return { sessionId: session.id, leaseToken: session.token, deviceId: session.deviceId,
      runtimeId: session.runtimeId, pairId: session.pairId, peerId: session.peerId,
      role: session.role, expiresAt: session.expiresAt,
      iceServers: [{ urls: [...this.config.turn.urls], username,
        credential: createHmac('sha1', this.config.turn.secret).update(username).digest('base64'), expiresAt }],
    };
  }
  lease(id, token, allowClosed = false) {
    this.sweep();
    const session = this.sessions.get(id);
    if (!session || !equal(session.token, token)) reject(401, 'INVALID_LEASE');
    if (session.expiresAt <= this.now() || (session.closed && !allowClosed)) reject(410, 'SESSION_CLOSED');
    if (!session.closed) session.lastSeen = this.now();
    return session;
  }
  emit(session, event) {
    if (session.events.length >= this.queueLimit) reject(429, 'QUEUE_FULL');
    session.events.push({ ...event, sequence: ++session.sequence });
    session.wait?.();
  }
  signal(session, body) {
    this.lease(session.id, session.token);
    if (!body || session.closed || !session.callId || body.callId !== session.callId ||
        !Number.isSafeInteger(body.sequence) || body.sequence !== session.signalSequence + 1 ||
        !['offer', 'answer', 'ice'].includes(body.kind) || typeof body.payload !== 'string' ||
        Buffer.byteLength(body.payload) > 60_000) reject(409, 'INVALID_SIGNAL');
    if ((body.kind === 'offer' && session.role !== 'offerer') || (body.kind === 'answer' && session.role !== 'answerer'))
      reject(403, 'WRONG_ROLE');
    const peer = this.owners.get(session.peerId);
    if (!peer || peer.closed || peer.pairId !== session.pairId || peer.callId !== session.callId) reject(410, 'PEER_GONE');
    if (session.rateStart + 30_000 <= this.now()) { session.rateStart = this.now(); session.rateCount = 0; }
    if (++session.rateCount > 256) reject(429, 'SIGNAL_RATE_LIMIT');
    this.emit(peer, { type: 'signal', callId: session.callId,
      from: { deviceId: session.deviceId, runtimeId: session.runtimeId }, kind: body.kind, payload: body.payload });
    session.signalSequence = body.sequence;
    return { accepted: body.sequence };
  }
  events(session, after) {
    if (!Number.isSafeInteger(after) || after < session.acknowledged || after > session.sequence) reject(409, 'INVALID_CURSOR');
    session.acknowledged = after;
    session.events = session.events.filter(event => event.sequence > after);
    return { events: [...session.events], closed: session.closed };
  }
  async poll(session, after, { timeoutMs = 20_000, signal } = {}) {
    if (session.wait) reject(409, 'POLL_BUSY');
    let result = this.events(session, after);
    if (!result.events.length && !result.closed && !signal?.aborted) {
      await new Promise(resolve => {
        const finish = () => { clearTimeout(timer); signal?.removeEventListener('abort', finish); session.wait = null; resolve(); };
        const timer = setTimeout(finish, timeoutMs);
        session.wait = finish;
        signal?.addEventListener('abort', finish, { once: true });
      });
      this.sweep();
      result = this.events(session, after);
    }
    return result;
  }
  end(session, reason = 'LEFT') {
    if (session.closed) return;
    const peer = this.owners.get(session.peerId);
    const targets = peer && peer.pairId === session.pairId && peer.callId === session.callId && session.callId ? [session, peer] : [session];
    for (const target of targets) {
      target.closed = true; target.closedAt = this.now(); this.owners.delete(target.deviceId);
      // A terminal event replaces a full queue so cleanup cannot fail.
      if (target.events.length >= this.queueLimit) target.events = [];
      this.emit(target, { type: 'closed', callId: target.callId, reason });
    }
  }
  close() { for (const session of this.sessions.values()) this.end(session, 'SERVER_STOP'); }
}

async function readJson(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? '')) reject(415, 'JSON_REQUIRED');
  const chunks = []; let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 65_536) reject(413, 'BODY_TOO_LARGE');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { reject(400, 'INVALID_JSON'); }
}

export function createRelayServer(config, tls, options) {
  const service = new RelayService(config, options);
  const server = https.createServer({ ...tls, minVersion: 'TLSv1.2' }, async (request, response) => {
    const send = (status, body) => {
      if (!response.destroyed) response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify(body));
    };
    try {
      const url = new URL(request.url, 'https://relay.invalid');
      const token = /^Bearer ([A-Za-z0-9_-]{32,256})$/.exec(request.headers.authorization ?? '')?.[1];
      if (!token) reject(401, 'UNAUTHORIZED');
      if (url.pathname === '/v1/sessions' && request.method === 'POST') return send(201, service.join(await readJson(request), token));
      const match = /^\/v1\/sessions\/([a-f0-9-]{36})(?:\/(events|signals))?$/.exec(url.pathname);
      if (!match) reject(404, 'NOT_FOUND');
      const session = service.lease(match[1], token, match[2] === 'events');
      if (request.method === 'GET' && match[2] === 'events') {
        const afterText = url.searchParams.get('after');
        if (!/^\d{1,10}$/.test(afterText ?? '')) reject(400, 'INVALID_CURSOR');
        const controller = new AbortController();
        response.on('close', () => controller.abort());
        return send(200, await service.poll(session, Number(afterText), { signal: controller.signal }));
      }
      if (request.method === 'POST' && match[2] === 'signals') return send(200, service.signal(session, await readJson(request)));
      if (request.method === 'DELETE' && !match[2]) { service.end(session); return send(200, { closed: true }); }
      reject(405, 'METHOD_NOT_ALLOWED');
    } catch (error) { send(error instanceof HttpError ? error.status : 500, { error: error instanceof HttpError ? error.message : 'INTERNAL_ERROR' }); }
  });
  server.requestTimeout = 30_000; server.headersTimeout = 10_000; server.maxHeadersCount = 32;
  server.on('close', () => service.close());
  return { server, service };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const file = process.env.MOTOCOM_RELAY_CONFIG;
  if (!file) throw new Error('Set MOTOCOM_RELAY_CONFIG to a private JSON configuration file');
  const config = JSON.parse(readFileSync(file, 'utf8'));
  const { server, service } = createRelayServer(config, { key: readFileSync(config.tls.key), cert: readFileSync(config.tls.cert) });
  const interval = setInterval(() => service.sweep(), 5_000).unref();
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { clearInterval(interval); service.close(); server.close(); });
  server.listen(config.port ?? 8443, config.host ?? '127.0.0.1', () => console.log(`MotoCom relay HTTPS listening on port ${server.address().port}`));
}
