#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function parseArgs(argv) {
  const positionals = [];
  const flags = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      positionals.push(arg);
      continue;
    }
    const eq = arg.indexOf('=');
    if (eq > 0) {
      flags[arg.slice(2, eq)] = arg.slice(eq + 1);
    } else if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) {
      flags[arg.slice(2)] = argv[i + 1];
      i += 1;
    } else {
      flags[arg.slice(2)] = 'true';
    }
  }
  return { command: positionals[0] ?? 'help', rest: positionals.slice(1), flags };
}

function isLoopbackHost(hostname) {
  const normalized = String(hostname ?? '').trim().toLowerCase();
  return normalized === '127.0.0.1' || normalized === 'localhost' || normalized === '::1' || normalized === '[::1]';
}

export function validateAdminBaseUrl(rawUrl) {
  const url = new URL(rawUrl);
  if (url.username || url.password) {
    throw new Error('Admin URL must not embed credentials');
  }
  if (url.protocol === 'https:') {
    return url.origin + url.pathname.replace(/\/+$/, '');
  }
  if (url.protocol === 'http:' && isLoopbackHost(url.hostname)) {
    return url.origin + url.pathname.replace(/\/+$/, '');
  }
  throw new Error(`Refusing to send admin bearer token over cleartext HTTP to non-loopback host "${url.hostname}". Use https:// or loopback http://127.0.0.1.`);
}

function loadDefaults(configArg) {
  const configPath = configArg || path.join(__dirname, '.local', 'config.json');
  if (!fs.existsSync(configPath)) return {};
  try {
    const raw = JSON.parse(fs.readFileSync(configPath, 'utf8'));
    const host = raw.host ?? '127.0.0.1';
    const port = Number(raw.port ?? 8443);
    const scheme = raw.tls?.certPath && raw.tls?.keyPath ? 'https' : 'http';
    return {
      baseUrl: `${scheme}://${host}:${port}`,
      adminToken: raw.adminToken ?? ''
    };
  } catch {
    return {};
  }
}

async function requestJson(baseUrl, adminToken, method, endpoint, body = null, idempotencyKey = '') {
  const safeBase = validateAdminBaseUrl(baseUrl);
  const headers = {
    Authorization: `Bearer ${adminToken}`
  };
  if (body !== null) {
    headers['Content-Type'] = 'application/json';
  }
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  const res = await fetch(`${safeBase}${endpoint}`, {
    method,
    headers,
    body: body !== null ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    payload = { raw: text };
  }
  if (!res.ok) {
    console.error(`HTTP ${res.status}:`, JSON.stringify(payload, null, 2));
    process.exit(1);
  }
  return payload;
}

function printUsage() {
  console.log(`Usage: node codes-cli.mjs <command> [options]

Global options:
  --url <baseUrl>            Default from GROUP_CLOUD_ADMIN_URL or .local/config.json
  --token <adminToken>       Default from GROUP_CLOUD_ADMIN_TOKEN or .local/config.json
  --config <path>            Optional config.json path

Commands:
  overview
  mint [--count 10] [--minutes 300] [--days 14] [--max-members 6] [--max-rooms 1] [--max-session 120] [--type PLAN|TOPUP] [--batch id] [--note text] [--idem key]
  codes [--batch id] [--status UNUSED|REDEEMED|REVOKED] [--mask text] [--limit 50]
  revoke-code <codeId> [--reason text]
  entitlements [--status ACTIVE|REVOKED] [--limit 50]
  topup <entitlementId> [--minutes 300] [--days 30] [--max-members 10] [--reason text] [--idem key]
  reset-rebind <entitlementId> [--reason text]
  revoke-entitlement <entitlementId> [--reason text]
  rooms
  force-end <roomId> [--reason ADMIN_ENDED] [--message text]
  set-global-quota [--limit-minutes 8500] [--require-join-code true|false]
  audit [--limit 50]
`);
}

async function main() {
  const { command, rest, flags } = parseArgs(process.argv.slice(2));
  if (command === 'help' || flags.help === 'true') {
    printUsage();
    return;
  }
  const defaults = loadDefaults(flags.config);
  const baseUrl = flags.url || process.env.GROUP_CLOUD_ADMIN_URL || defaults.baseUrl || 'http://127.0.0.1:8443';
  const adminToken = flags.token || process.env.GROUP_CLOUD_ADMIN_TOKEN || defaults.adminToken || '';
  if (!adminToken) {
    console.error('Missing admin token. Pass --token or set GROUP_CLOUD_ADMIN_TOKEN.');
    process.exit(1);
  }

  let result;
  switch (command) {
    case 'overview':
      result = await requestJson(baseUrl, adminToken, 'GET', '/admin/v1/overview');
      break;
    case 'mint':
      result = await requestJson(
        baseUrl,
        adminToken,
        'POST',
        '/admin/v1/codes/batch',
        {
          count: flags.count !== undefined ? Number(flags.count) : 1,
          codeType: flags.type ?? 'PLAN',
          quotaMinutes: flags.minutes !== undefined ? Number(flags.minutes) : 300,
          validDays: flags.days !== undefined ? Number(flags.days) : 14,
          maxMembers: flags['max-members'] !== undefined ? Number(flags['max-members']) : 6,
          maxConcurrentRooms: flags['max-rooms'] !== undefined ? Number(flags['max-rooms']) : 1,
          maxSessionMinutes: flags['max-session'] !== undefined ? Number(flags['max-session']) : 120,
          batchId: flags.batch ?? '',
          note: flags.note ?? ''
        },
        flags.idem ?? ''
      );
      break;
    case 'codes': {
      const params = new URLSearchParams();
      if (flags.batch) params.set('batchId', flags.batch);
      if (flags.status) params.set('status', flags.status);
      if (flags.mask) params.set('codeMask', flags.mask);
      if (flags.limit) params.set('limit', flags.limit);
      if (flags.offset) params.set('offset', flags.offset);
      result = await requestJson(baseUrl, adminToken, 'GET', `/admin/v1/codes?${params.toString()}`);
      break;
    }
    case 'revoke-code':
      if (!rest[0]) throw new Error('codeId is required');
      result = await requestJson(
        baseUrl,
        adminToken,
        'POST',
        `/admin/v1/codes/${encodeURIComponent(rest[0])}/revoke`,
        { reason: flags.reason ?? '' }
      );
      break;
    case 'entitlements': {
      const params = new URLSearchParams();
      if (flags.status) params.set('status', flags.status);
      if (flags.limit) params.set('limit', flags.limit);
      if (flags.offset) params.set('offset', flags.offset);
      result = await requestJson(baseUrl, adminToken, 'GET', `/admin/v1/entitlements?${params.toString()}`);
      break;
    }
    case 'topup':
      if (!rest[0]) throw new Error('entitlementId is required');
      result = await requestJson(
        baseUrl,
        adminToken,
        'POST',
        `/admin/v1/entitlements/${encodeURIComponent(rest[0])}/topup`,
        {
          addMinutes: flags.minutes !== undefined ? Number(flags.minutes) : 0,
          extendDays: flags.days !== undefined ? Number(flags.days) : 0,
          maxMembers: flags['max-members'] !== undefined ? Number(flags['max-members']) : undefined,
          reason: flags.reason ?? ''
        },
        flags.idem ?? ''
      );
      break;
    case 'reset-rebind':
      if (!rest[0]) throw new Error('entitlementId is required');
      result = await requestJson(
        baseUrl,
        adminToken,
        'POST',
        `/admin/v1/entitlements/${encodeURIComponent(rest[0])}/reset-rebind`,
        { reason: flags.reason ?? '' }
      );
      break;
    case 'revoke-entitlement':
      if (!rest[0]) throw new Error('entitlementId is required');
      result = await requestJson(
        baseUrl,
        adminToken,
        'POST',
        `/admin/v1/entitlements/${encodeURIComponent(rest[0])}/revoke`,
        { reason: flags.reason ?? '' }
      );
      break;
    case 'rooms':
      result = await requestJson(baseUrl, adminToken, 'GET', '/admin/v1/rooms/active');
      break;
    case 'force-end':
      if (!rest[0]) throw new Error('roomId is required');
      result = await requestJson(
        baseUrl,
        adminToken,
        'POST',
        `/admin/v1/rooms/${encodeURIComponent(rest[0])}/force-end`,
        { reason: flags.reason ?? 'ADMIN_ENDED', message: flags.message ?? '' }
      );
      break;
    case 'set-global-quota': {
      const payload = {};
      if (flags['limit-minutes'] !== undefined) payload.hardLimitMinutes = Number(flags['limit-minutes']);
      if (flags['require-join-code'] !== undefined) {
        payload.requireCodeForJoin = flags['require-join-code'] === 'true';
      }
      result = await requestJson(baseUrl, adminToken, 'PUT', '/admin/v1/config/global-quota', payload);
      break;
    }
    case 'audit': {
      const params = new URLSearchParams();
      if (flags.limit) params.set('limit', flags.limit);
      if (flags.offset) params.set('offset', flags.offset);
      result = await requestJson(baseUrl, adminToken, 'GET', `/admin/v1/audit-logs?${params.toString()}`);
      break;
    }
    default:
      printUsage();
      process.exit(1);
  }
  console.log(JSON.stringify(result, null, 2));
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (isMain) {
  main().catch((error) => {
    console.error(error?.message ?? error);
    process.exit(1);
  });
}
