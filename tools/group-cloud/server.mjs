import crypto from 'node:crypto';
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { EntitlementStore } from './entitlement-store.mjs';
import { CloudStoreError, GroupCloudStore } from './room-store.mjs';
import { createRtcAdmin } from './volc-openapi.mjs';

const MAX_BODY_BYTES = 128 * 1024;
const ADMIN_RATE_WINDOW_MS = 10 * 60 * 1000;
const ADMIN_MAX_FAILURES = 10;
const ADMIN_GLOBAL_MAX_FAILURES = 20;
const ADMIN_UI_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'admin-ui');
const ADMIN_UI_CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "connect-src 'self'",
  "img-src 'self' data:",
  "font-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "form-action 'none'"
].join('; ');
const ADMIN_STATIC_ROUTES = new Map([
  ['/admin', { file: 'index.html', contentType: 'text/html; charset=utf-8' }],
  ['/admin/', { file: 'index.html', contentType: 'text/html; charset=utf-8' }],
  ['/admin/assets/admin.css', { file: 'admin.css', contentType: 'text/css; charset=utf-8' }],
  ['/admin/assets/admin.js', { file: 'admin.js', contentType: 'application/javascript; charset=utf-8' }]
]);

function loadAdminUiAssets() {
  const cache = new Map();
  for (const [route, meta] of ADMIN_STATIC_ROUTES) {
    const filePath = path.join(ADMIN_UI_DIR, meta.file);
    if (!fs.existsSync(filePath)) continue;
    cache.set(route, {
      body: fs.readFileSync(filePath),
      contentType: meta.contentType
    });
  }
  return cache;
}

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

function extractEntitlementToken(req) {
  const raw = req.headers['x-moto-entitlement'];
  if (typeof raw !== 'string') return '';
  return raw.trim();
}

function extractDeviceIdHeader(req) {
  const raw = req.headers['x-moto-device-id'];
  if (typeof raw !== 'string') return '';
  return raw.trim();
}

function extractIdempotencyKey(req) {
  const raw = req.headers['idempotency-key'];
  if (typeof raw !== 'string') return '';
  return raw.trim();
}

function isLoopbackAddress(addr) {
  if (typeof addr !== 'string') return false;
  const normalized = addr.trim().toLowerCase();
  return normalized === '127.0.0.1' || normalized === '::1' || normalized === '::ffff:127.0.0.1';
}

function extractClientIp(req, trustLoopbackProxy = false) {
  const remote = req.socket?.remoteAddress ?? 'unknown';
  if (trustLoopbackProxy && isLoopbackAddress(remote)) {
    const realIp = req.headers['x-real-ip'];
    if (typeof realIp === 'string' && realIp.trim()) {
      return realIp.trim().slice(0, 64);
    }
  }
  return String(remote).slice(0, 64);
}

function timingSafeTokenMatch(expected, actual) {
  if (typeof expected !== 'string' || typeof actual !== 'string' || !expected || !actual) return false;
  const a = Buffer.from(expected, 'utf8');
  const b = Buffer.from(actual, 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
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

function sendStaticAdminAsset(res, method, asset) {
  if (res.writableEnded) return;
  res.writeHead(200, {
    'Content-Type': asset.contentType,
    'Content-Length': asset.body.length,
    'Cache-Control': 'no-store',
    'Content-Security-Policy': ADMIN_UI_CSP,
    'X-Frame-Options': 'DENY',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Cross-Origin-Opener-Policy': 'same-origin'
  });
  if (method === 'HEAD') {
    res.end();
    return;
  }
  res.end(asset.body);
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
  const entitlementStore = options.entitlementStore ?? options.store?.entitlementStore ?? null;
  const store = options.store ?? new GroupCloudStore({ ...options, entitlementStore });
  const adminToken = typeof options.adminToken === 'string' ? options.adminToken.trim() : '';
  const adminUiAssets = adminToken && entitlementStore ? loadAdminUiAssets() : new Map();
  const trustLoopbackProxy = Boolean(options.trustLoopbackProxy ?? false);
  const adminAuthFailures = new Map();
  let adminGlobalAuthFailures = [];
  const nowFn = options.now ?? store.now ?? (() => Date.now());
  if (!store.rtcRevocationConfigured) {
    throw new Error(
      'Refusing to start group-cloud server without authoritative Volc RTC OpenAPI revocation (BanRoomUser / UpdateBanRoomUserRule). Configure volcOpenApiAccessKey and volcOpenApiSecretKey.'
    );
  }
  const sweepIntervalMs = options.sweepIntervalMs ?? 5_000;
  const log = options.log ?? (() => {});

  function assertAdminAuthorized(req, clientIp) {
    if (!entitlementStore || !adminToken) {
      throw new CloudStoreError(404, 'ADMIN_DISABLED', '后台管理接口未启用');
    }
    const nowMs = nowFn();
    const cutoff = nowMs - ADMIN_RATE_WINDOW_MS;
    adminGlobalAuthFailures = adminGlobalAuthFailures.filter((ts) => ts > cutoff);
    if (adminGlobalAuthFailures.length >= ADMIN_GLOBAL_MAX_FAILURES) {
      throw new CloudStoreError(429, 'RATE_LIMITED', '管理接口认证失败次数过多，请稍后再试');
    }
    const failures = (adminAuthFailures.get(clientIp) ?? []).filter((ts) => ts > cutoff);
    if (failures.length >= ADMIN_MAX_FAILURES) {
      adminAuthFailures.set(clientIp, failures);
      throw new CloudStoreError(429, 'RATE_LIMITED', '管理认证失败次数过多，请稍后再试');
    }
    const provided = extractBearer(req);
    if (!timingSafeTokenMatch(adminToken, provided)) {
      failures.push(nowMs);
      adminAuthFailures.set(clientIp, failures);
      adminGlobalAuthFailures.push(nowMs);
      throw new CloudStoreError(401, 'ADMIN_UNAUTHORIZED', '管理员认证失败');
    }
    if (failures.length > 0) adminAuthFailures.delete(clientIp);
  }

  const handler = async (req, res) => {
    const controller = new AbortController();
    req.on('close', () => {
      if (!res.writableEnded) controller.abort();
    });

    try {
      const url = new URL(req.url ?? '/', 'http://localhost');
      const method = (req.method ?? 'GET').toUpperCase();
      const pathname = url.pathname;
      const clientIp = extractClientIp(req, trustLoopbackProxy);

      if (method === 'GET' && pathname === '/healthz') {
        const payload = {
          ok: true,
          mode: 'NETWORK',
          maxMembers: store.maxMembers,
          audioRoutingRequired: true,
          openApiRevocationEnforced: true
        };
        if (entitlementStore) {
          payload.entitlementEnabled = true;
          payload.adminApiEnabled = Boolean(adminToken);
        }
        sendJson(res, 200, payload);
        return;
      }

      if (method === 'POST' && pathname === '/v1/entitlements/redeem') {
        if (!entitlementStore) {
          throw new CloudStoreError(404, 'ENTITLEMENT_DISABLED', '激活码权益服务未启用');
        }
        const body = await readJsonBody(req);
        const result = entitlementStore.redeemActivationCode({
          code: body.code,
          deviceId: body.deviceId,
          existingToken: extractEntitlementToken(req),
          clientIp
        });
        if (result.rebound && result.entitlementId) {
          try {
            await store.terminateRoomsForEntitlement(result.entitlementId, {
              reason: 'ENTITLEMENT_REBOUND',
              message: '该激活码已在新设备换绑，原云端房间已结束'
            });
          } catch (error) {
            log({ event: 'rebind_terminate_rooms_failed', message: error?.message ?? 'unknown' });
          }
        }
        sendJson(res, 200, {
          action: result.action,
          rebound: result.rebound,
          entitlementToken: result.entitlementToken,
          codeMask: result.codeMask,
          entitlement: result.entitlement
        });
        return;
      }

      if (method === 'GET' && pathname === '/v1/entitlements/me') {
        if (!entitlementStore) {
          throw new CloudStoreError(404, 'ENTITLEMENT_DISABLED', '激活码权益服务未启用');
        }
        const token = extractEntitlementToken(req);
        if (!token) {
          throw new CloudStoreError(401, 'ENTITLEMENT_REQUIRED', '请先输入激活码开通网络对讲权益');
        }
        const deviceId = extractDeviceIdHeader(req);
        if (!deviceId) {
          throw new CloudStoreError(400, 'DEVICE_ID_REQUIRED', '缺少设备标识请求头 X-Moto-Device-Id');
        }
        const summary = entitlementStore.getEntitlementStatus(
          token,
          nowFn(),
          { expectedDeviceId: deviceId }
        );
        sendJson(res, 200, { entitlement: summary });
        return;
      }

      if ((method === 'GET' || method === 'HEAD') && ADMIN_STATIC_ROUTES.has(pathname)) {
        if (!entitlementStore || !adminToken) {
          throw new CloudStoreError(404, 'ADMIN_DISABLED', '后台管理面板未启用');
        }
        const asset = adminUiAssets.get(pathname);
        if (!asset) {
          throw new CloudStoreError(404, 'ADMIN_UI_MISSING', '后台管理面板静态资源未部署');
        }
        sendStaticAdminAsset(res, method, asset);
        return;
      }

      if (pathname.startsWith('/admin/v1/')) {
        assertAdminAuthorized(req, clientIp);

        if (method === 'GET' && pathname === '/admin/v1/overview') {
          const activeRooms = store.listActiveRoomsSummary();
          sendJson(res, 200, entitlementStore.getOverview(activeRooms));
          return;
        }

        if (method === 'POST' && pathname === '/admin/v1/codes/batch') {
          const body = await readJsonBody(req);
          const result = entitlementStore.mintCodeBatch({
            ...body,
            actorIp: clientIp,
            idempotencyKey: extractIdempotencyKey(req)
          });
          sendJson(res, 201, result);
          return;
        }

        if (method === 'GET' && pathname === '/admin/v1/codes') {
          const result = entitlementStore.listCodes({
            batchId: url.searchParams.get('batchId') ?? '',
            status: url.searchParams.get('status') ?? '',
            codeMask: url.searchParams.get('codeMask') ?? '',
            limit: url.searchParams.get('limit') ?? 50,
            offset: url.searchParams.get('offset') ?? 0
          });
          sendJson(res, 200, result);
          return;
        }

        let adminMatch = /^\/admin\/v1\/codes\/([^/]+)\/revoke$/.exec(pathname);
        if (adminMatch && method === 'POST') {
          const codeId = decodeURIComponent(adminMatch[1]);
          const body = await readJsonBody(req);
          const result = entitlementStore.revokeCode(codeId, {
            reason: body.reason ?? '',
            actorIp: clientIp
          });
          let termination = { endedRoomIds: [], pendingRoomIds: [] };
          if (result.shouldTerminateRooms && result.entitlementId) {
            termination = await store.terminateRoomsForEntitlement(result.entitlementId, {
              reason: 'ENTITLEMENT_REVOKED',
              message: '房主激活码已被管理员停用，云端房间已结束'
            });
          }
          sendJson(res, 200, {
            ...result,
            terminatedRoomIds: termination.endedRoomIds,
            pendingTerminationRoomIds: termination.pendingRoomIds
          });
          return;
        }

        if (method === 'GET' && pathname === '/admin/v1/entitlements') {
          const result = entitlementStore.listEntitlements({
            status: url.searchParams.get('status') ?? '',
            limit: url.searchParams.get('limit') ?? 50,
            offset: url.searchParams.get('offset') ?? 0
          });
          const activeRooms = store.listActiveRoomsSummary();
          const activeByEntitlement = new Map();
          for (const room of activeRooms) {
            if (!room.sponsorEntitlementId) continue;
            activeByEntitlement.set(
              room.sponsorEntitlementId,
              (activeByEntitlement.get(room.sponsorEntitlementId) ?? 0) + 1
            );
          }
          sendJson(res, 200, {
            ...result,
            items: result.items.map((item) => ({
              ...item,
              activeSponsoredRoomsCount: activeByEntitlement.get(item.entitlementId) ?? 0
            }))
          });
          return;
        }

        adminMatch = /^\/admin\/v1\/entitlements\/([^/]+)\/(topup|reset-rebind|revoke)$/.exec(pathname);
        if (adminMatch && method === 'POST') {
          const entitlementId = decodeURIComponent(adminMatch[1]);
          const action = adminMatch[2];
          const body = await readJsonBody(req);
          if (action === 'topup') {
            const result = entitlementStore.topupEntitlement(entitlementId, {
              ...body,
              actorIp: clientIp,
              idempotencyKey: extractIdempotencyKey(req)
            });
            sendJson(res, 200, result);
            return;
          }
          if (action === 'reset-rebind') {
            const result = entitlementStore.resetRebindCount(entitlementId, {
              reason: body.reason ?? '',
              actorIp: clientIp
            });
            sendJson(res, 200, result);
            return;
          }
          const result = entitlementStore.revokeEntitlement(entitlementId, {
            reason: body.reason ?? '',
            actorIp: clientIp
          });
          const termination = await store.terminateRoomsForEntitlement(entitlementId, {
            reason: 'ENTITLEMENT_REVOKED',
            message: '房主云端对讲权限已被管理员停用，房间已结束'
          });
          sendJson(res, 200, {
            ...result,
            terminatedRoomIds: termination.endedRoomIds,
            pendingTerminationRoomIds: termination.pendingRoomIds
          });
          return;
        }

        if (method === 'GET' && pathname === '/admin/v1/rooms/active') {
          const rooms = store.listActiveRoomsSummary();
          sendJson(res, 200, { total: rooms.length, items: rooms, serverNowMs: nowFn() });
          return;
        }

        adminMatch = /^\/admin\/v1\/rooms\/([^/]+)\/force-end$/.exec(pathname);
        if (adminMatch && method === 'POST') {
          const roomId = decodeURIComponent(adminMatch[1]);
          const body = await readJsonBody(req);
          const reason = typeof body.reason === 'string' && body.reason.trim() ? body.reason.trim().slice(0, 64) : 'ADMIN_ENDED';
          const message =
            typeof body.message === 'string' && body.message.trim()
              ? body.message.trim().slice(0, 120)
              : '管理员已结束该云端房间';
          const result = await store.forceEndRoomByAdmin(roomId, { reason, message });
          entitlementStore.recordAdminAction({
            action: 'FORCE_END_ROOM',
            targetType: 'ROOM',
            targetId: roomId,
            actorIp: clientIp,
            payloadSummary: { reason, message }
          });
          sendJson(res, 200, result);
          return;
        }

        if (method === 'PUT' && pathname === '/admin/v1/config/global-quota') {
          const body = await readJsonBody(req);
          const updated = entitlementStore.updateGlobalQuotaConfig({
            hardLimitMinutes: body.hardLimitMinutes,
            requireCodeForJoin: body.requireCodeForJoin,
            warnThresholdRatio: body.warnThresholdRatio,
            actorIp: clientIp
          });
          let termination = { endedRoomIds: [], pendingRoomIds: [] };
          if (updated.remainingSeconds <= 0) {
            termination = await store.terminateAllActiveRooms({
              reason: 'GLOBAL_QUOTA_EXHAUSTED',
              message: '本月云端测试总免费额度已用尽，通话已结束，请使用本地直连模式'
            });
          }
          sendJson(res, 200, {
            globalQuota: updated,
            terminatedRoomIds: termination.endedRoomIds,
            pendingTerminationRoomIds: termination.pendingRoomIds
          });
          return;
        }

        if (method === 'GET' && pathname === '/admin/v1/audit-logs') {
          const result = entitlementStore.listAuditLogs({
            limit: url.searchParams.get('limit') ?? 50,
            offset: url.searchParams.get('offset') ?? 0
          });
          sendJson(res, 200, result);
          return;
        }

        sendJson(res, 404, { code: 'NOT_FOUND', message: '后台管理接口不存在' });
        return;
      }

      if (method === 'POST' && pathname === '/v1/rooms') {
        const body = await readJsonBody(req);
        const result = await store.createRoom({
          ...body,
          entitlementToken: extractEntitlementToken(req)
        });
        sendJson(res, 201, result);
        return;
      }

      if (method === 'POST' && pathname === '/v1/rooms/join-requests') {
        const body = await readJsonBody(req);
        const result = await store.submitJoinRequest({
          ...body,
          entitlementToken: extractEntitlementToken(req)
        });
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
    try {
      store.flushAllPendingBilling();
    } catch (error) {
      log({ event: 'shutdown_billing_flush_failed', message: error?.message ?? 'unknown' });
    }
    return originalClose(cb);
  };

  return { server, store, entitlementStore };
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
    entitlementEnforced: raw.entitlementEnforced !== undefined ? Boolean(raw.entitlementEnforced) : undefined,
    dbPath: raw.dbPath ? path.resolve(dir, raw.dbPath) : '',
    codeHmacSecret: raw.codeHmacSecret ?? '',
    adminToken: raw.adminToken ?? '',
    globalMonthlyLimitMinutes:
      raw.globalMonthlyLimitMinutes !== undefined ? Number(raw.globalMonthlyLimitMinutes) : undefined,
    requireCodeForJoin: raw.requireCodeForJoin !== undefined ? Boolean(raw.requireCodeForJoin) : undefined,
    trustLoopbackProxy: raw.trustLoopbackProxy !== undefined ? Boolean(raw.trustLoopbackProxy) : undefined,
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
  const entitlementEnforcedEnv = process.env.GROUP_CLOUD_ENTITLEMENT_ENFORCED;
  const entitlementEnforced =
    entitlementEnforcedEnv !== undefined
      ? entitlementEnforcedEnv.toLowerCase() !== 'false'
      : (fileConfig.entitlementEnforced ?? true);
  const dbPath =
    process.env.GROUP_CLOUD_DB_PATH ??
    fileConfig.dbPath ??
    path.join(path.dirname(modulePath), '.local', 'entitlements.db');
  const codeHmacSecret = process.env.GROUP_CLOUD_CODE_HMAC_SECRET ?? fileConfig.codeHmacSecret ?? '';
  const adminToken = process.env.GROUP_CLOUD_ADMIN_TOKEN ?? fileConfig.adminToken ?? '';
  const globalMonthlyLimitMinutes = Number(
    process.env.GROUP_CLOUD_GLOBAL_MONTHLY_LIMIT_MINUTES ?? fileConfig.globalMonthlyLimitMinutes ?? 8500
  );
  const requireCodeForJoin =
    process.env.GROUP_CLOUD_REQUIRE_CODE_FOR_JOIN !== undefined
      ? process.env.GROUP_CLOUD_REQUIRE_CODE_FOR_JOIN.toLowerCase() === 'true'
      : Boolean(fileConfig.requireCodeForJoin ?? false);
  const trustLoopbackProxy =
    process.env.GROUP_CLOUD_TRUST_LOOPBACK_PROXY !== undefined
      ? process.env.GROUP_CLOUD_TRUST_LOOPBACK_PROXY.toLowerCase() === 'true'
      : Boolean(fileConfig.trustLoopbackProxy ?? false);
  const tls = fileConfig.tls ?? null;
  if (!volcAppId || !volcAppKey) {
    throw new Error('Volc RTC AppID/AppKey is required. Set GROUP_CLOUD_VOLC_APP_ID and GROUP_CLOUD_VOLC_APP_KEY or .local/config.json.');
  }
  if (!volcOpenApiAccessKey || !volcOpenApiSecretKey) {
    throw new Error(
      'Volc RTC OpenAPI AK/SK is required for authoritative RTC revocation (BanRoomUser / UpdateBanRoomUserRule). Set GROUP_CLOUD_VOLC_OPENAPI_AK and GROUP_CLOUD_VOLC_OPENAPI_SK or .local/config.json.'
    );
  }
  const log = (entry) => console.log(JSON.stringify({ ts: new Date().toISOString(), ...entry }));
  let entitlementStore = null;
  if (entitlementEnforced) {
    if (!codeHmacSecret || codeHmacSecret.length < 16) {
      throw new Error(
        'Entitlement enforcement requires codeHmacSecret (>= 16 chars). Set GROUP_CLOUD_CODE_HMAC_SECRET or .local/config.json.'
      );
    }
    entitlementStore = new EntitlementStore({
      dbPath,
      codeHmacSecret,
      globalMonthlyLimitMinutes,
      requireCodeForJoin,
      log
    });
  }
  const rtcAdmin = createRtcAdmin({
    appId: volcAppId,
    accessKeyId: volcOpenApiAccessKey,
    secretKey: volcOpenApiSecretKey,
    region: volcOpenApiRegion,
    endpointUrl: volcOpenApiEndpoint || undefined,
    timeoutMs: volcOpenApiTimeoutMs
  });

  const { server, store } = createGroupCloudServer({
    host,
    appId: volcAppId,
    appKey: volcAppKey,
    maxMembers,
    banRemovedForbiddenSeconds,
    banRoomForbiddenSeconds,
    rtcAdmin,
    entitlementStore,
    adminToken,
    trustLoopbackProxy,
    tls,
    log
  });

  server.listen(port, host, () => {
    const scheme = tls ? 'https' : 'http';
    log({
      event: 'group_cloud_listening',
      url: `${scheme}://${host}:${port}`,
      maxMembers,
      openApiBan: rtcAdmin.enabled,
      entitlementEnforced: Boolean(entitlementStore),
      adminApiEnabled: Boolean(entitlementStore && adminToken)
    });
  });

  const shutdown = (signal) => {
    log({ event: 'group_cloud_shutdown', signal });
    store.flushAllPendingBilling();
    server.close(() => {
      entitlementStore?.close();
      process.exit(0);
    });
    setTimeout(() => {
      entitlementStore?.close();
      process.exit(0);
    }, 5000).unref();
  };
  process.once('SIGTERM', () => shutdown('SIGTERM'));
  process.once('SIGINT', () => shutdown('SIGINT'));
}
