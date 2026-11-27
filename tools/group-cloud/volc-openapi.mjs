import crypto from 'node:crypto';

const DEFAULT_ENDPOINT_URL = 'https://rtc.volcengineapi.com';
const REGION = 'cn-north-1';
const SERVICE = 'rtc';
const ALGORITHM = 'HMAC-SHA256';
const DEFAULT_TIMEOUT_MS = 5_000;
const MAX_OPENAPI_RESPONSE_BYTES = 64 * 1024;

function isLoopbackHost(hostname) {
  const normalized = String(hostname ?? '').trim().toLowerCase();
  return normalized === '127.0.0.1' || normalized === 'localhost' || normalized === '::1' || normalized === '[::1]';
}

function parseEndpointUrl(rawUrl) {
  const url = new URL(rawUrl || DEFAULT_ENDPOINT_URL);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && isLoopbackHost(url.hostname))) {
    throw new Error(`Refusing insecure non-loopback Volc OpenAPI endpoint: ${url.origin}`);
  }
  return url;
}

function hash(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

function hmac(key, value) {
  return crypto.createHmac('sha256', key).update(value, 'utf8').digest();
}

function escapeQuery(value) {
  return encodeURIComponent(String(value)).replace(/[^A-Za-z0-9_.~\-%]+/g, (ch) =>
    [...Buffer.from(ch, 'utf8')].map((byte) => `%${byte.toString(16).toUpperCase().padStart(2, '0')}`).join('')
  );
}

function canonicalQuery(query) {
  return Object.keys(query)
    .sort()
    .map((key) => `${escapeQuery(key)}=${escapeQuery(query[key])}`)
    .join('&');
}

function xDate(now = new Date()) {
  return now.toISOString().replace(/[:-]|\.\d{3}/g, '');
}

export function signVolcOpenApiRequest({
  accessKeyId,
  secretKey,
  action,
  version,
  body,
  region = REGION,
  service = SERVICE,
  endpointUrl = DEFAULT_ENDPOINT_URL,
  date = new Date()
}) {
  if (!accessKeyId || !secretKey) throw new Error('Volc OpenAPI AK/SK is required');
  const parsedUrl = parseEndpointUrl(endpointUrl);
  const host = parsedUrl.host;
  const bodyText = JSON.stringify(body ?? {});
  const datetime = xDate(date);
  const shortDate = datetime.slice(0, 8);
  const bodySha = hash(bodyText);
  const headers = {
    Host: host,
    'Content-Type': 'application/json',
    'X-Date': datetime,
    'X-Content-Sha256': bodySha
  };
  const signedHeaders = 'host;x-content-sha256;x-date';
  const canonicalHeaders = [
    `host:${host}`,
    `x-content-sha256:${bodySha}`,
    `x-date:${datetime}`
  ].join('\n');
  const canonicalRequest = [
    'POST',
    '/',
    canonicalQuery({ Action: action, Version: version }),
    `${canonicalHeaders}\n`,
    signedHeaders,
    bodySha
  ].join('\n');
  const scope = `${shortDate}/${region}/${service}/request`;
  const stringToSign = [
    ALGORITHM,
    datetime,
    scope,
    hash(canonicalRequest)
  ].join('\n');
  const kDate = hmac(secretKey, shortDate);
  const kRegion = hmac(kDate, region);
  const kService = hmac(kRegion, service);
  const kSigning = hmac(kService, 'request');
  const signature = hmac(kSigning, stringToSign).toString('hex');
  headers.Authorization = [
    `${ALGORITHM} Credential=${accessKeyId}/${scope}`,
    `SignedHeaders=${signedHeaders}`,
    `Signature=${signature}`
  ].join(', ');
  return {
    url: `${parsedUrl.origin}/?Action=${escapeQuery(action)}&Version=${escapeQuery(version)}`,
    headers,
    body: bodyText
  };
}

function confirmedOpenApiMessage(payload, action, body) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new Error('RTC OpenAPI returned an invalid response payload');
  }
  if (!payload.ResponseMetadata || typeof payload.ResponseMetadata !== 'object' || Array.isArray(payload.ResponseMetadata)) {
    throw new Error('RTC OpenAPI response is missing ResponseMetadata');
  }
  const error = payload?.ResponseMetadata?.Error;
  if (error) {
    const code = error.Code || error.CodeN || 'RTC_OPENAPI_ERROR';
    const message = error.Message || 'RTC OpenAPI request failed';
    throw new Error(`${code}: ${message}`);
  }
  const message = payload.Result?.Message;
  const target = body.UserId ? 'User' : 'Room';
  if (message === 'success') return message;
  if (action === 'BanRoomUser' && message === `${target} has been banned.`) return message;
  if (action === 'UpdateBanRoomUserRule' && body.ForbiddenInterval === 0 &&
      message === `${target} is not banned.`) return message;
  throw new Error('RTC OpenAPI response is missing a valid Result confirmation');
}

async function readBoundedOpenApiText(response, maxBytes, action) {
  const declaredLength = Number(response.headers?.get?.('content-length') ?? NaN);
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    throw new Error(`RTC ${action} response exceeded ${maxBytes} bytes`);
  }
  if (!response.body || typeof response.body[Symbol.asyncIterator] !== 'function') {
    const text = await response.text();
    if (Buffer.byteLength(text, 'utf8') > maxBytes) {
      throw new Error(`RTC ${action} response exceeded ${maxBytes} bytes`);
    }
    return text;
  }
  const chunks = [];
  let total = 0;
  for await (const chunk of response.body) {
    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += buf.length;
    if (total > maxBytes) {
      throw new Error(`RTC ${action} response exceeded ${maxBytes} bytes`);
    }
    chunks.push(buf);
  }
  return Buffer.concat(chunks, total).toString('utf8');
}

export function createRtcAdmin({
  appId,
  accessKeyId,
  secretKey,
  region = REGION,
  endpointUrl = DEFAULT_ENDPOINT_URL,
  fetchImpl = fetch,
  now = () => new Date(),
  timeoutMs = DEFAULT_TIMEOUT_MS
}) {
  if (!appId) throw new Error('Volc RTC AppID is required for RTC admin');
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) {
    throw new Error('Volc RTC OpenAPI timeoutMs must be a positive integer');
  }
  const parsedUrl = parseEndpointUrl(endpointUrl);
  const enabled = Boolean(accessKeyId && secretKey);

  async function invoke(action, body) {
    if (!enabled) {
      throw new Error(`Volc RTC OpenAPI AK/SK is not configured; refusing to skip ${action}`);
    }
    const signed = signVolcOpenApiRequest({
      accessKeyId,
      secretKey,
      region,
      endpointUrl: parsedUrl.origin,
      action,
      version: '2023-11-01',
      body,
      date: now()
    });
    const controller = new AbortController();
    let timer = null;
    const timeoutError = new Error(`RTC ${action} timed out after ${timeoutMs}ms`);
    const deadlinePromise = new Promise((_, reject) => {
      timer = setTimeout(() => {
        controller.abort(timeoutError);
        reject(timeoutError);
      }, timeoutMs);
    });
    let response;
    let rawText;
    try {
      const executePromise = (async () => {
        const res = await fetchImpl(signed.url, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
          signal: controller.signal
        });
        const text = await readBoundedOpenApiText(res, MAX_OPENAPI_RESPONSE_BYTES, action);
        return { res, text };
      })();
      ({ res: response, text: rawText } = await Promise.race([executePromise, deadlinePromise]));
    } catch (error) {
      if (controller.signal.aborted) {
        throw timeoutError;
      }
      throw error;
    } finally {
      if (timer) clearTimeout(timer);
    }
    let payload;
    try {
      payload = JSON.parse(rawText);
    } catch {
      throw new Error(`RTC ${action} returned invalid JSON`);
    }
    if (response.status !== 200) {
      throw new Error(`RTC ${action} HTTP ${response.status}`);
    }
    return { skipped: false, action, message: confirmedOpenApiMessage(payload, action, body) };
  }

  async function ban({ roomId, userId = null, forbiddenIntervalSec = 0 }) {
    const body = {
      AppId: appId,
      RoomId: roomId,
      ForbiddenInterval: forbiddenIntervalSec
    };
    if (userId) body.UserId = userId;
    return invoke('BanRoomUser', body);
  }

  async function unban({ roomId, userId }) {
    const body = {
      AppId: appId,
      RoomId: roomId,
      UserId: userId,
      ForbiddenInterval: 0
    };
    return invoke('UpdateBanRoomUserRule', body);
  }

  return {
    enabled,
    banUser: (roomId, userId, forbiddenIntervalSec) => ban({ roomId, userId, forbiddenIntervalSec }),
    banRoom: (roomId, forbiddenIntervalSec) => ban({ roomId, forbiddenIntervalSec }),
    unbanUser: (roomId, userId) => unban({ roomId, userId })
  };
}
