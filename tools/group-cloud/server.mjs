import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CloudStoreError, GroupCloudStore } from './room-store.mjs';
import { createRtcAdmin } from './volc-openapi.mjs';

const MAX_BODY_BYTES = 128 * 1024;

export function isLoopbackHost(host) {
  if (typeof host !== 'string') return false;
  const normalized = host.trim().toLowerCase();
  return normalized === '127.0.0.1' || normalized === 'localhost' || normalized === '::1' || normalized === '[::1]';
}

function extractBearer(req) {
  const auth = req.headers.authorization;
  if (typeof auth !== 'string') return '';
  const match = /^Bearer\s+(\S+)$/i.exec(auth.trim());
  return match ? match[1] : '';
}

async function readJsonBody(req) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > MAX_BODY_BYTES) {
      throw new CloudStoreError(413, 'PAYLOAD_TOO_LARGE', '请求体超过限制');
    }
    chunks.push(chunk);
  }
  if (total === 0) return {};
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('object expected');
    }
    return parsed;
  } catch {
    throw new CloudStoreError(400, 'INVALID_JSON', 'JSON 格式无效');
  }
}

function sendJson(res, status, payload) {
  if (res.writableEnded) return;
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body, 'utf8')
  });
  res.end(body);
}

export function createGroupCloudServer(options = {}) {
  const configuredHost = options.host ?? '127.0.0.1';
  const hasTls = Boolean(options.tls);
  if (hasTls && (!options.tls.cert || !options.tls.key)) {
    throw new Error('TLS configuration requires both tls.cert and tls.key');
  }
  if (!hasTls && !isLoopbackHost(configuredHost)) {
    throw new Error(
      `Refusing to start cleartext HTTP group-cloud server on non-loopback host "${configuredHost}". Configure TLS 1.2+ (tls.cert/tls.key) or bind to loopback (127.0.0.1 / ::1).`
    );
  }
  const store = options.store ?? new GroupCloudStore(options);
  if (!store.rtcRevocationConfigured) {
    throw new Error(
      'Refusing to start group-cloud server without authoritative Volc RTC OpenAPI revocation (BanRoomUser / UpdateBanRoomUserRule). Configure volcOpenApiAccessKey and volcOpenApiSecretKey.'
    );
  }
  const sweepIntervalMs = options.sweepIntervalMs ?? 5_000;
  const log = options.log ?? (() => {});

  const handler = async (req, res) => {
    const controller = new AbortController();
    req.on('close', () => {
      if (!res.writableEnded) controller.abort();
    });

    try {
      const url = new URL(req.url ?? '/', 'http://localhost');
      const method = (req.method ?? 'GET').toUpperCase();
      const pathname = url.pathname;

      if (method === 'GET' && pathname === '/healthz') {
        sendJson(res, 200, {
          ok: true,
          mode: 'NETWORK',
          maxMembers: store.maxMembers,
          audioRoutingRequired: true,
          openApiRevocationEnforced: true
        });
        return;
      }

      if (method === 'POST' && pathname === '/v1/rooms') {
        const body = await readJsonBody(req);
        const result = await store.createRoom(body);
        sendJson(res, 201, result);
        return;
      }

      if (method === 'POST' && pathname === '/v1/rooms/join-requests') {
        const body = await readJsonBody(req);
        const result = await store.submitJoinRequest(body);
        sendJson(res, result.status === 'ADMITTED' ? 200 : 202, result);
        return;
      }

      let match = /^\/v1\/join-requests\/([^/]+)$/.exec(pathname);
      if (match) {
        const requestId = decodeURIComponent(match[1]);
        const secret = extractBearer(req);
        if (method === 'GET') {
          const waitMs = Number(url.searchParams.get('waitMs') ?? '0');
          const result = await store.pollJoinRequest({
            requestId,
            requestSecret: secret,
            waitMs,
            signal: controller.signal
          });
          sendJson(res, 200, result);
          return;
        }
        if (method === 'DELETE') {
          const result = await store.cancelJoinRequest({ requestId, requestSecret: secret });
          sendJson(res, 200, result);
          return;
        }
      }

      match = /^\/v1\/rooms\/([^/]+)\/join-requests\/([^/]+)\/(approve|reject)$/.exec(pathname);
      if (match && method === 'POST') {
        const roomId = decodeURIComponent(match[1]);
        const requestId = decodeURIComponent(match[2]);
        const action = match[3];
        const body = await readJsonBody(req);
        const token = extractBearer(req);
        const result = action === 'approve'
          ? await store.approveJoinRequest(token, roomId, requestId, body)
          : await store.rejectJoinRequest(token, roomId, requestId, body);
        sendJson(res, 200, result);
        return;
      }

      match = /^\/v1\/rooms\/([^/]+)\/members\/([^/]+)\/remove$/.exec(pathname);
      if (match && method === 'POST') {
        const roomId = decodeURIComponent(match[1]);
        const targetId = decodeURIComponent(match[2]);
        const body = await readJsonBody(req);
        const result = await store.removeMember(extractBearer(req), roomId, targetId, body);
        sendJson(res, 200, result);
        return;
      }

      match = /^\/v1\/rooms\/([^/]+)\/unblock\/([^/]+)$/.exec(pathname);
      if (match && method === 'POST') {
        const roomId = decodeURIComponent(match[1]);
        const targetId = decodeURIComponent(match[2]);
        const body = await readJsonBody(req);
        const result = await store.unblockMember(extractBearer(req), roomId, targetId, body);
        sendJson(res, 200, result);
        return;
      }

      match = /^\/v1\/rooms\/([^/]+)\/private-invites\/([^/]+)\/(accept|decline|cancel)$/.exec(pathname);
      if (match && method === 'POST') {
        const roomId = decodeURIComponent(match[1]);
        const inviteId = decodeURIComponent(match[2]);
        const action = match[3];
        const body = await readJsonBody(req);
        const token = extractBearer(req);
        const result = action === 'accept'
          ? await store.acceptPrivate(token, roomId, inviteId, body)
          : action === 'decline'
            ? await store.declinePrivate(token, roomId, inviteId, body)
            : await store.cancelPrivate(token, roomId, inviteId, body);
        sendJson(res, 200, result);
        return;
      }

      match = /^\/v1\/rooms\/([^/]+)\/private\/close$/.exec(pathname);
      if (match && method === 'POST') {
        const roomId = decodeURIComponent(match[1]);
        const body = await readJsonBody(req);
        sendJson(res, 200, await store.closePrivate(extractBearer(req), roomId, body));
        return;
      }

      match = /^\/v1\/rooms\/([^/]+)\/transport\/(switch|local-status)$/.exec(pathname);
      if (match && method === 'POST') {
        const roomId = decodeURIComponent(match[1]);
        const action = match[2];
        const body = await readJsonBody(req);
        const token = extractBearer(req);
        const result = action === 'switch'
          ? await store.switchTransport(token, roomId, body)
          : await store.reportLocalStatus(token, roomId, body);
        sendJson(res, 200, result);
        return;
      }

      match = /^\/v1\/rooms\/([^/]+)\/([^/]+)$/.exec(pathname);
      if (match) {
        const roomId = decodeURIComponent(match[1]);
        const sub = match[2];
        const token = extractBearer(req);

        if (method === 'GET' && sub === 'events') {
          const after = Number(url.searchParams.get('after') ?? '0');
          const waitMs = Number(url.searchParams.get('waitMs') ?? '0');
          const result = await store.pollEvents(token, roomId, { after, waitMs, signal: controller.signal });
          sendJson(res, 200, result);
          return;
        }
        if (method === 'POST' && sub === 'ack') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.ackEvents(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'audio') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.updateAudioAvailability(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'invites') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.rotateInvite(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'approval-policy') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.setRequireApproval(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'roles') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.setRole(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'rider-policy') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.setRiderIncludesFormation(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'channel') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.switchChannel(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'private-invites') {
          const body = await readJsonBody(req);
          sendJson(res, 201, await store.requestPrivate(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'owner-transfer') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.transferOwner(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'leave') {
          sendJson(res, 200, await store.leaveRoom(token, roomId));
          return;
        }
        if (method === 'POST' && sub === 'end') {
          const body = await readJsonBody(req);
          sendJson(res, 200, await store.endRoom(token, roomId, body));
          return;
        }
        if (method === 'POST' && sub === 'resume') {
          sendJson(res, 200, await store.resumeMember(token, roomId));
          return;
        }
        if (method === 'POST' && sub === 'rtc-token') {
          sendJson(res, 200, { rtc: await store.refreshRtcCredentials(token, roomId) });
          return;
        }
      }

      sendJson(res, 404, { code: 'NOT_FOUND', message: '接口不存在' });
    } catch (error) {
      if (error instanceof CloudStoreError) {
        sendJson(res, error.status, { code: error.code, message: error.message, ...error.extra });
        return;
      }
      log({ event: 'server_error', message: error?.message ?? 'unknown' });
      sendJson(res, 500, { code: 'INTERNAL_ERROR', message: '服务器内部错误' });
    }
  };

  const server = hasTls
    ? https.createServer({
        key: options.tls.key,
        cert: options.tls.cert,
        minVersion: 'TLSv1.2'
      }, handler)
    : http.createServer(handler);

  if (!hasTls) {
    const originalListen = server.listen.bind(server);
    server.listen = (...args) => {
      let listenHost;
      if (args.length > 0 && args[0] && typeof args[0] === 'object') {
        listenHost = args[0].host;
      } else if (typeof args[1] === 'string') {
        listenHost = args[1];
      }
      if (!isLoopbackHost(listenHost)) {
        throw new Error(
          `Refusing to listen cleartext HTTP group-cloud server on non-loopback host "${listenHost ?? '<default all interfaces>'}". Configure TLS 1.2+ or bind explicitly to 127.0.0.1 / ::1.`
        );
      }
      return originalListen(...args);
    };
  }

  let sweepInFlight = false;
  const timer = sweepIntervalMs > 0
    ? setInterval(() => {
        if (sweepInFlight) return;
        sweepInFlight = true;
        void store.sweep()
          .catch((error) => {
            log({ event: 'sweep_failed', message: error?.message ?? 'unknown' });
          })
          .finally(() => {
            sweepInFlight = false;
          });
      }, sweepIntervalMs)
    : null;
  timer?.unref?.();

  const originalClose = server.close.bind(server);
  server.close = (cb) => {
    if (timer) clearInterval(timer);
    return originalClose(cb);
  };

  return { server, store };
}

function loadConfigFromDisk(configPath) {
  const raw = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  const dir = path.dirname(configPath);
  let tls = null;
  if (raw.tls?.certPath || raw.tls?.keyPath) {
    if (!raw.tls.certPath || !raw.tls.keyPath) {
      throw new Error('TLS configuration requires both tls.certPath and tls.keyPath');
    }
    tls = {
      cert: fs.readFileSync(path.resolve(dir, raw.tls.certPath)),
      key: fs.readFileSync(path.resolve(dir, raw.tls.keyPath))
    };
  }
  return {
    host: raw.host ?? '127.0.0.1',
    port: Number(raw.port ?? 8443),
    maxMembers: Number(raw.maxMembers ?? 50),
    volcAppId: raw.volcAppId ?? '',
    volcAppKey: raw.volcAppKey ?? '',
    volcOpenApiAccessKey: raw.volcOpenApiAccessKey ?? '',
    volcOpenApiSecretKey: raw.volcOpenApiSecretKey ?? '',
    volcOpenApiRegion: raw.volcOpenApiRegion ?? 'cn-north-1',
    volcOpenApiEndpoint: raw.volcOpenApiEndpoint ?? '',
    volcOpenApiTimeoutMs: raw.volcOpenApiTimeoutMs !== undefined ? Number(raw.volcOpenApiTimeoutMs) : undefined,
    banRemovedForbiddenSeconds: Number(raw.banRemovedForbiddenSeconds ?? 300),
    banRoomForbiddenSeconds: Number(raw.banRoomForbiddenSeconds ?? 3600),
    tls
  };
}

function realEntryPath(value) {
  if (!value) return '';
  const absolute = path.resolve(value);
  try {
    return fs.realpathSync.native(absolute);
  } catch {
    return absolute;
  }
}

const entryPath = realEntryPath(process.argv[1]);
const modulePath = realEntryPath(fileURLToPath(import.meta.url));
const isMain = process.platform === 'win32'
  ? entryPath.toLowerCase() === modulePath.toLowerCase()
  : entryPath === modulePath;
if (isMain) {
  const configArg = process.argv.find((arg) => arg.startsWith('--config='))?.slice('--config='.length);
  const fileConfig = configArg ? loadConfigFromDisk(configArg) : {};
  const host = process.env.GROUP_CLOUD_HOST ?? fileConfig.host ?? '127.0.0.1';
  const port = Number(process.env.GROUP_CLOUD_PORT ?? fileConfig.port ?? 8443);
  const maxMembers = Number(process.env.GROUP_CLOUD_MAX_MEMBERS ?? fileConfig.maxMembers ?? 50);
  const volcAppId = process.env.GROUP_CLOUD_VOLC_APP_ID ?? fileConfig.volcAppId ?? '';
  const volcAppKey = process.env.GROUP_CLOUD_VOLC_APP_KEY ?? fileConfig.volcAppKey ?? '';
  const volcOpenApiAccessKey = process.env.GROUP_CLOUD_VOLC_OPENAPI_AK ?? fileConfig.volcOpenApiAccessKey ?? '';
  const volcOpenApiSecretKey = process.env.GROUP_CLOUD_VOLC_OPENAPI_SK ?? fileConfig.volcOpenApiSecretKey ?? '';
  const volcOpenApiRegion = process.env.GROUP_CLOUD_VOLC_OPENAPI_REGION ?? fileConfig.volcOpenApiRegion ?? 'cn-north-1';
  const volcOpenApiEndpoint = process.env.GROUP_CLOUD_VOLC_OPENAPI_ENDPOINT ?? fileConfig.volcOpenApiEndpoint ?? '';
  const volcOpenApiTimeoutMs = process.env.GROUP_CLOUD_VOLC_OPENAPI_TIMEOUT_MS !== undefined
    ? Number(process.env.GROUP_CLOUD_VOLC_OPENAPI_TIMEOUT_MS)
    : fileConfig.volcOpenApiTimeoutMs;
  const banRemovedForbiddenSeconds = Number(
    process.env.GROUP_CLOUD_BAN_REMOVED_SECONDS ?? fileConfig.banRemovedForbiddenSeconds ?? 300
  );
  const banRoomForbiddenSeconds = Number(
    process.env.GROUP_CLOUD_BAN_ROOM_SECONDS ?? fileConfig.banRoomForbiddenSeconds ?? 3600
  );
  const tls = fileConfig.tls ?? null;
  if (!volcAppId || !volcAppKey) {
    throw new Error('Volc RTC AppID/AppKey is required. Set GROUP_CLOUD_VOLC_APP_ID and GROUP_CLOUD_VOLC_APP_KEY or .local/config.json.');
  }
  if (!volcOpenApiAccessKey || !volcOpenApiSecretKey) {
    throw new Error(
      'Volc RTC OpenAPI AK/SK is required for authoritative RTC revocation (BanRoomUser / UpdateBanRoomUserRule). Set GROUP_CLOUD_VOLC_OPENAPI_AK and GROUP_CLOUD_VOLC_OPENAPI_SK or .local/config.json.'
    );
  }
  const rtcAdmin = createRtcAdmin({
    appId: volcAppId,
    accessKeyId: volcOpenApiAccessKey,
    secretKey: volcOpenApiSecretKey,
    region: volcOpenApiRegion,
    endpointUrl: volcOpenApiEndpoint || undefined,
    timeoutMs: volcOpenApiTimeoutMs
  });
  const log = (entry) => console.log(JSON.stringify({ ts: new Date().toISOString(), ...entry }));

  const { server } = createGroupCloudServer({
    host,
    appId: volcAppId,
    appKey: volcAppKey,
    maxMembers,
    banRemovedForbiddenSeconds,
    banRoomForbiddenSeconds,
    rtcAdmin,
    tls,
    log
  });

  server.listen(port, host, () => {
    const scheme = tls ? 'https' : 'http';
    log({
      event: 'group_cloud_listening',
      url: `${scheme}://${host}:${port}`,
      maxMembers,
      openApiBan: rtcAdmin.enabled
    });
  });
}
