import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { CloudStoreError } from './room-store.mjs';

const CODE_PREFIX = 'MOTO';
const CROCKFORD_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const CODE_DATA_LEN = 10;
const CODE_CHECK_LEN = 2;
const CODE_BODY_LEN = CODE_DATA_LEN + CODE_CHECK_LEN;
const REBIND_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_REBINDS_PER_WINDOW = 2;
const REDEEM_RATE_WINDOW_MS = 10 * 60 * 1000;
const REDEEM_MAX_FAILURES = 5;
const DEFAULT_GLOBAL_MONTHLY_LIMIT_MINUTES = 8_500;
const DEFAULT_WARN_THRESHOLD_RATIO = 0.8;
const DEFAULT_TEST_QUOTA_MINUTES = 300;
const DEFAULT_TEST_VALID_DAYS = 14;
const DEFAULT_TEST_MAX_MEMBERS = 6;
const DEFAULT_MAX_CONCURRENT_ROOMS = 1;
const DEFAULT_MAX_SESSION_MINUTES = 120;
const IDEMPOTENCY_MEMORY_TTL_MS = 10 * 60 * 1000;

const VALID_CODE_TYPES = new Set(['PLAN', 'TOPUP']);
const DEVICE_ID_REGEX = /^[A-Za-z0-9._:-]{1,64}$/;

export function shanghaiMonthKey(nowMs) {
  return new Date(nowMs + 8 * 3_600_000).toISOString().slice(0, 7);
}

export function shanghaiDateKey(nowMs) {
  return new Date(nowMs + 8 * 3_600_000).toISOString().slice(0, 10);
}

function computeCheckChars(dataPart) {
  const digest = crypto.createHash('sha256').update(`${CODE_PREFIX}:${dataPart}`, 'ascii').digest();
  const value = ((digest[0] << 8) | digest[1]) & 0x3ff;
  return CROCKFORD_ALPHABET[(value >> 5) & 0x1f] + CROCKFORD_ALPHABET[value & 0x1f];
}

export function formatActivationCode(canonicalBody) {
  return `${CODE_PREFIX}-${canonicalBody.slice(0, 4)}-${canonicalBody.slice(4, 8)}-${canonicalBody.slice(8, 12)}`;
}

export function generateActivationCode() {
  let dataPart = '';
  for (let idx = 0; idx < CODE_DATA_LEN; idx += 1) {
    dataPart += CROCKFORD_ALPHABET[crypto.randomInt(0, CROCKFORD_ALPHABET.length)];
  }
  const checkPart = computeCheckChars(dataPart);
  return formatActivationCode(dataPart + checkPart);
}

export function normalizeActivationCode(raw) {
  if (typeof raw !== 'string') return null;
  const cleaned = raw
    .trim()
    .toUpperCase()
    .replace(/[\s-]+/g, '');
  let body = cleaned;
  if (body.startsWith(CODE_PREFIX)) {
    body = body.slice(CODE_PREFIX.length);
  }
  if (body.length !== CODE_BODY_LEN) return null;
  let mapped = '';
  for (const ch of body) {
    if (ch === 'O') mapped += '0';
    else if (ch === 'I' || ch === 'L') mapped += '1';
    else mapped += ch;
  }
  for (const ch of mapped) {
    if (!CROCKFORD_ALPHABET.includes(ch)) return null;
  }
  const dataPart = mapped.slice(0, CODE_DATA_LEN);
  const checkPart = mapped.slice(CODE_DATA_LEN);
  if (computeCheckChars(dataPart) !== checkPart) return null;
  return {
    canonical: `${CODE_PREFIX}${mapped}`,
    formatted: formatActivationCode(mapped),
    mask: `${CODE_PREFIX}-****-****-${mapped.slice(-4)}`
  };
}

function sha256Hex(value) {
  return crypto.createHash('sha256').update(String(value), 'utf8').digest('hex');
}

function hmacHex(secret, value) {
  return crypto.createHmac('sha256', secret).update(String(value), 'utf8').digest('hex');
}

function normalizeDeviceId(value) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new CloudStoreError(400, 'INVALID_INPUT', 'deviceId is required');
  }
  const trimmed = value.trim();
  if (!DEVICE_ID_REGEX.test(trimmed)) {
    throw new CloudStoreError(400, 'INVALID_INPUT', 'deviceId format is invalid');
  }
  return trimmed;
}

function sanitizeNote(value, maxLen = 160) {
  if (value === undefined || value === null) return '';
  return String(value).trim().slice(0, maxLen);
}

function parsePositiveInt(value, field, { min = 1, max = 1_000_000 } = {}) {
  const num = Number(value);
  if (!Number.isInteger(num) || num < min || num > max) {
    throw new CloudStoreError(400, 'INVALID_INPUT', `${field} must be an integer between ${min} and ${max}`);
  }
  return num;
}

function parseNonNegativeInt(value, field, { max = 10_000_000 } = {}) {
  const num = Number(value);
  if (!Number.isInteger(num) || num < 0 || num > max) {
    throw new CloudStoreError(400, 'INVALID_INPUT', `${field} must be an integer between 0 and ${max}`);
  }
  return num;
}

export class EntitlementStore {
  constructor(options = {}) {
    this.now = options.now ?? (() => Date.now());
    this.log = options.log ?? (() => {});
    this.dbPath = options.dbPath ?? ':memory:';
    this.codeHmacSecret = typeof options.codeHmacSecret === 'string' && options.codeHmacSecret.length >= 16
      ? options.codeHmacSecret
      : '';
    if (!this.codeHmacSecret) {
      throw new Error('EntitlementStore requires codeHmacSecret (>= 16 chars)');
    }
    this.codeVaultKey = Buffer.from(
      crypto.hkdfSync(
        'sha256',
        Buffer.from(this.codeHmacSecret, 'utf8'),
        Buffer.from('motocom-activation-code-vault-v1', 'utf8'),
        Buffer.from('aes-256-gcm', 'utf8'),
        32
      )
    );
    this.defaultGlobalMonthlyLimitSeconds =
      parseNonNegativeInt(
        options.globalMonthlyLimitMinutes ?? DEFAULT_GLOBAL_MONTHLY_LIMIT_MINUTES,
        'globalMonthlyLimitMinutes'
      ) * 60;
    this.defaultWarnThresholdRatio =
      typeof options.warnThresholdRatio === 'number' &&
      options.warnThresholdRatio > 0 &&
      options.warnThresholdRatio <= 1
        ? options.warnThresholdRatio
        : DEFAULT_WARN_THRESHOLD_RATIO;
    this.defaultRequireCodeForJoin = Boolean(options.requireCodeForJoin ?? false);
    this.rebindWindowMs = options.rebindWindowMs ?? REBIND_WINDOW_MS;
    this.maxRebindsPerWindow = options.maxRebindsPerWindow ?? MAX_REBINDS_PER_WINDOW;
    this.redeemRateWindowMs = options.redeemRateWindowMs ?? REDEEM_RATE_WINDOW_MS;
    this.redeemMaxFailures = options.redeemMaxFailures ?? REDEEM_MAX_FAILURES;

    if (this.dbPath !== ':memory:') {
      fs.mkdirSync(path.dirname(this.dbPath), { recursive: true });
    }
    this.db = new DatabaseSync(this.dbPath);
    this.closed = false;
    this.redeemFailures = new Map();
    this.batchIdemCache = new Map();
    this._initSchema();
  }

  _initSchema() {
    if (this.dbPath !== ':memory:') {
      this.db.exec('PRAGMA journal_mode = WAL;');
      this.db.exec('PRAGMA synchronous = NORMAL;');
    }
    this.db.exec('PRAGMA foreign_keys = ON;');
    this.db.exec('PRAGMA busy_timeout = 5000;');
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS entitlements (
        entitlement_id TEXT PRIMARY KEY,
        owner_device_id TEXT NOT NULL,
        account_id TEXT,
        token_hash TEXT NOT NULL UNIQUE,
        status TEXT NOT NULL,
        rebind_count_7d INTEGER NOT NULL DEFAULT 0,
        rebind_window_start_ms INTEGER NOT NULL DEFAULT 0,
        created_at_ms INTEGER NOT NULL,
        updated_at_ms INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_entitlements_device ON entitlements(owner_device_id);
      CREATE INDEX IF NOT EXISTS idx_entitlements_status ON entitlements(status);

      CREATE TABLE IF NOT EXISTS activation_codes (
        code_id TEXT PRIMARY KEY,
        code_hash TEXT NOT NULL UNIQUE,
        code_mask TEXT NOT NULL,
        code_ciphertext TEXT,
        batch_id TEXT NOT NULL,
        code_type TEXT NOT NULL,
        quota_seconds INTEGER NOT NULL,
        valid_days INTEGER NOT NULL,
        max_members INTEGER NOT NULL,
        max_concurrent_rooms INTEGER NOT NULL,
        max_session_minutes INTEGER NOT NULL,
        status TEXT NOT NULL,
        redeemed_entitlement_id TEXT,
        redeemed_at_ms INTEGER,
        note TEXT NOT NULL DEFAULT '',
        created_at_ms INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_codes_batch ON activation_codes(batch_id);
      CREATE INDEX IF NOT EXISTS idx_codes_status ON activation_codes(status);
      CREATE INDEX IF NOT EXISTS idx_codes_entitlement ON activation_codes(redeemed_entitlement_id);

      CREATE TABLE IF NOT EXISTS quota_grants (
        grant_id TEXT PRIMARY KEY,
        entitlement_id TEXT NOT NULL,
        source_code_id TEXT,
        source_type TEXT NOT NULL,
        total_seconds INTEGER NOT NULL,
        used_seconds INTEGER NOT NULL DEFAULT 0,
        max_members INTEGER NOT NULL,
        max_concurrent_rooms INTEGER NOT NULL,
        max_session_minutes INTEGER NOT NULL,
        starts_at_ms INTEGER NOT NULL,
        expires_at_ms INTEGER NOT NULL,
        status TEXT NOT NULL,
        note TEXT NOT NULL DEFAULT '',
        created_at_ms INTEGER NOT NULL,
        FOREIGN KEY (entitlement_id) REFERENCES entitlements(entitlement_id)
      );
      CREATE INDEX IF NOT EXISTS idx_grants_entitlement ON quota_grants(entitlement_id, status, expires_at_ms);
      CREATE INDEX IF NOT EXISTS idx_grants_source_code ON quota_grants(source_code_id);

      CREATE TABLE IF NOT EXISTS usage_ledger (
        ledger_id INTEGER PRIMARY KEY AUTOINCREMENT,
        month_key TEXT NOT NULL,
        room_id TEXT NOT NULL,
        mode TEXT NOT NULL,
        entitlement_id TEXT NOT NULL,
        grant_id TEXT,
        delta_seconds INTEGER NOT NULL,
        peak_rtc_members INTEGER NOT NULL,
        reason TEXT NOT NULL,
        recorded_at_ms INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_ledger_month ON usage_ledger(month_key, recorded_at_ms);
      CREATE INDEX IF NOT EXISTS idx_ledger_entitlement ON usage_ledger(entitlement_id, month_key);
      CREATE INDEX IF NOT EXISTS idx_ledger_recorded ON usage_ledger(recorded_at_ms);

      CREATE TABLE IF NOT EXISTS global_monthly_usage (
        month_key TEXT PRIMARY KEY,
        used_seconds INTEGER NOT NULL DEFAULT 0,
        hard_limit_seconds INTEGER NOT NULL,
        warn_threshold_ratio REAL NOT NULL DEFAULT 0.8,
        warned_at_ms INTEGER,
        updated_at_ms INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS runtime_config (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at_ms INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS admin_audit_logs (
        audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
        action TEXT NOT NULL,
        target_type TEXT NOT NULL,
        target_id TEXT NOT NULL,
        actor_ip TEXT NOT NULL,
        payload_summary TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_audit_created ON admin_audit_logs(created_at_ms DESC);

      CREATE TABLE IF NOT EXISTS admin_idempotency (
        idem_key TEXT PRIMARY KEY,
        action TEXT NOT NULL,
        request_hash TEXT NOT NULL DEFAULT '',
        response_json TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL
      );
    `);
    const idemCols = this.db.prepare('PRAGMA table_info(admin_idempotency)').all();
    if (!idemCols.some((c) => c.name === 'request_hash')) {
      this.db.exec("ALTER TABLE admin_idempotency ADD COLUMN request_hash TEXT NOT NULL DEFAULT ''");
    }
    const codeCols = this.db.prepare('PRAGMA table_info(activation_codes)').all();
    if (!codeCols.some((c) => c.name === 'code_ciphertext')) {
      this.db.exec('ALTER TABLE activation_codes ADD COLUMN code_ciphertext TEXT');
    }
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.db.close();
  }

  _runInTransaction(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const result = fn();
      this.db.exec('COMMIT');
      return result;
    } catch (error) {
      try {
        this.db.exec('ROLLBACK');
      } catch {
        // ignore rollback error
      }
      throw error;
    }
  }

  _getConfig(key, fallback) {
    const row = this.db.prepare('SELECT value FROM runtime_config WHERE key = ?').get(key);
    return row ? row.value : fallback;
  }

  _setConfig(key, value, nowMs) {
    this.db
      .prepare(
        `INSERT INTO runtime_config (key, value, updated_at_ms)
         VALUES (?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at_ms = excluded.updated_at_ms`
      )
      .run(key, String(value), nowMs);
  }

  isRequireCodeForJoin() {
    const stored = this._getConfig('require_code_for_join', null);
    if (stored === null) return this.defaultRequireCodeForJoin;
    return stored === 'true';
  }

  getDefaultHardLimitSeconds() {
    const stored = this._getConfig('default_hard_limit_seconds', null);
    if (stored === null) return this.defaultGlobalMonthlyLimitSeconds;
    const parsed = Number(stored);
    return Number.isInteger(parsed) && parsed >= 0 ? parsed : this.defaultGlobalMonthlyLimitSeconds;
  }

  _ensureGlobalMonthRow(nowMs) {
    const monthKey = shanghaiMonthKey(nowMs);
    let row = this.db.prepare('SELECT * FROM global_monthly_usage WHERE month_key = ?').get(monthKey);
    if (!row) {
      const defaultLimit = this.getDefaultHardLimitSeconds();
      this.db
        .prepare(
          `INSERT OR IGNORE INTO global_monthly_usage
           (month_key, used_seconds, hard_limit_seconds, warn_threshold_ratio, warned_at_ms, updated_at_ms)
           VALUES (?, 0, ?, ?, NULL, ?)`
        )
        .run(monthKey, defaultLimit, this.defaultWarnThresholdRatio, nowMs);
      row = this.db.prepare('SELECT * FROM global_monthly_usage WHERE month_key = ?').get(monthKey);
    }
    return row;
  }

  _hashCode(canonicalCode) {
    return hmacHex(this.codeHmacSecret, canonicalCode);
  }

  _encryptCodePlaintext(codeId, formattedCode) {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', this.codeVaultKey, iv);
    cipher.setAAD(Buffer.from(String(codeId), 'utf8'));
    const encrypted = Buffer.concat([cipher.update(String(formattedCode), 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();
    return `v1:${iv.toString('hex')}:${tag.toString('hex')}:${encrypted.toString('hex')}`;
  }

  _decryptCodePlaintext(codeId, ciphertext) {
    if (typeof ciphertext !== 'string' || !ciphertext.startsWith('v1:')) return null;
    const parts = ciphertext.split(':');
    if (parts.length !== 4) return null;
    try {
      const iv = Buffer.from(parts[1], 'hex');
      const tag = Buffer.from(parts[2], 'hex');
      const data = Buffer.from(parts[3], 'hex');
      if (iv.length !== 12 || tag.length !== 16 || data.length === 0) return null;
      const decipher = crypto.createDecipheriv('aes-256-gcm', this.codeVaultKey, iv);
      decipher.setAAD(Buffer.from(String(codeId), 'utf8'));
      decipher.setAuthTag(tag);
      return Buffer.concat([decipher.update(data), decipher.final()]).toString('utf8');
    } catch {
      return null;
    }
  }

  _hashToken(token) {
    return sha256Hex(`ent-token:${token}`);
  }

  _generateEntitlementToken() {
    return `mte_${crypto.randomBytes(32).toString('base64url')}`;
  }

  _checkRedeemRateLimit(keys, nowMs) {
    const cutoff = nowMs - this.redeemRateWindowMs;
    for (const key of keys) {
      if (!key) continue;
      const list = this.redeemFailures.get(key);
      if (!list) continue;
      const fresh = list.filter((ts) => ts > cutoff);
      if (fresh.length === 0) {
        this.redeemFailures.delete(key);
      } else {
        this.redeemFailures.set(key, fresh);
        if (fresh.length >= this.redeemMaxFailures) {
          throw new CloudStoreError(429, 'RATE_LIMITED', '激活码尝试次数过多，请 10 分钟后再试');
        }
      }
    }
  }

  _recordRedeemFailure(keys, nowMs) {
    const cutoff = nowMs - this.redeemRateWindowMs;
    for (const key of keys) {
      if (!key) continue;
      const fresh = (this.redeemFailures.get(key) ?? []).filter((ts) => ts > cutoff);
      fresh.push(nowMs);
      this.redeemFailures.set(key, fresh);
    }
    if (this.redeemFailures.size > 4096) {
      for (const [k, timestamps] of this.redeemFailures) {
        if (timestamps.every((ts) => ts <= cutoff)) {
          this.redeemFailures.delete(k);
        }
      }
    }
  }

  _refreshGrantStatuses(entitlementId, nowMs) {
    this.db
      .prepare(
        `UPDATE quota_grants
         SET status = 'EXPIRED'
         WHERE entitlement_id = ? AND status = 'ACTIVE' AND expires_at_ms <= ?`
      )
      .run(entitlementId, nowMs);
    this.db
      .prepare(
        `UPDATE quota_grants
         SET status = 'DEPLETED'
         WHERE entitlement_id = ? AND status = 'ACTIVE' AND used_seconds >= total_seconds`
      )
      .run(entitlementId);
  }

  _buildEntitlementSummary(entitlementRow, nowMs) {
    this._refreshGrantStatuses(entitlementRow.entitlement_id, nowMs);
    const grants = this.db
      .prepare(
        `SELECT * FROM quota_grants
         WHERE entitlement_id = ?
         ORDER BY expires_at_ms ASC, created_at_ms ASC`
      )
      .all(entitlementRow.entitlement_id);
    let remainingSeconds = 0;
    let totalSeconds = 0;
    let usedSeconds = 0;
    let maxMembers = 0;
    let maxConcurrentRooms = 0;
    let maxSessionMinutes = 0;
    let nearestExpiresAtMs = null;
    let furthestExpiresAtMs = null;
    let activeGrantCount = 0;
    let hasExpiredGrant = false;
    let hasDepletedGrant = false;

    for (const grant of grants) {
      if (grant.status === 'REVOKED') continue;
      totalSeconds += grant.total_seconds;
      usedSeconds += grant.used_seconds;
      if (grant.status === 'ACTIVE' && grant.expires_at_ms > nowMs && grant.used_seconds < grant.total_seconds) {
        activeGrantCount += 1;
        remainingSeconds += grant.total_seconds - grant.used_seconds;
        maxMembers = Math.max(maxMembers, grant.max_members);
        maxConcurrentRooms = Math.max(maxConcurrentRooms, grant.max_concurrent_rooms);
        maxSessionMinutes = Math.max(maxSessionMinutes, grant.max_session_minutes);
        nearestExpiresAtMs = nearestExpiresAtMs === null ? grant.expires_at_ms : Math.min(nearestExpiresAtMs, grant.expires_at_ms);
        furthestExpiresAtMs = furthestExpiresAtMs === null ? grant.expires_at_ms : Math.max(furthestExpiresAtMs, grant.expires_at_ms);
      } else if (grant.status === 'EXPIRED') {
        hasExpiredGrant = true;
      } else if (grant.status === 'DEPLETED') {
        hasDepletedGrant = true;
      }
    }

    const globalRow = this._ensureGlobalMonthRow(nowMs);
    const globalRemainingSeconds = Math.max(0, globalRow.hard_limit_seconds - globalRow.used_seconds);

    return {
      entitlementId: entitlementRow.entitlement_id,
      ownerDeviceId: entitlementRow.owner_device_id,
      status: entitlementRow.status,
      remainingSeconds,
      remainingMinutes: Math.floor(remainingSeconds / 60),
      totalSeconds,
      totalMinutes: Math.floor(totalSeconds / 60),
      usedSeconds,
      usedMinutes: Math.floor(usedSeconds / 60),
      maxMembers,
      maxConcurrentRooms,
      maxSessionMinutes,
      nearestExpiresAtMs,
      furthestExpiresAtMs,
      activeGrantCount,
      hasExpiredGrant,
      hasDepletedGrant,
      rebindCount7d:
        entitlementRow.rebind_window_start_ms > 0 && nowMs - entitlementRow.rebind_window_start_ms < this.rebindWindowMs
          ? entitlementRow.rebind_count_7d
          : 0,
      globalMonthlyRemainingMinutes: Math.floor(globalRemainingSeconds / 60),
      globalMonthlyHardLimitMinutes: Math.floor(globalRow.hard_limit_seconds / 60),
      serverNowMs: nowMs
    };
  }

  _findEntitlementByToken(token) {
    if (typeof token !== 'string' || !token.trim()) return null;
    const tokenHash = this._hashToken(token.trim());
    return this.db.prepare('SELECT * FROM entitlements WHERE token_hash = ?').get(tokenHash) ?? null;
  }

  _writeAuditLog({ action, targetType, targetId, actorIp = 'local', payloadSummary = {} }, nowMs) {
    this.db
      .prepare(
        `INSERT INTO admin_audit_logs (action, target_type, target_id, actor_ip, payload_summary, created_at_ms)
         VALUES (?, ?, ?, ?, ?, ?)`
      )
      .run(
        String(action),
        String(targetType),
        String(targetId),
        String(actorIp || 'unknown').slice(0, 64),
        JSON.stringify(payloadSummary).slice(0, 2000),
        nowMs
      );
  }

  getEntitlementStatus(token, nowMs = this.now(), { expectedDeviceId = '' } = {}) {
    if (typeof token !== 'string' || !token.trim()) {
      throw new CloudStoreError(401, 'ENTITLEMENT_REQUIRED', '请先输入激活码开通网络对讲权益');
    }
    const row = this._findEntitlementByToken(token);
    if (!row) {
      throw new CloudStoreError(401, 'ENTITLEMENT_INVALID', '激活凭证无效或已在新设备上换绑，请重新兑换激活码');
    }
    if (row.status === 'REVOKED') {
      throw new CloudStoreError(401, 'ENTITLEMENT_REVOKED', '该激活码权益已被停用');
    }
    const normalizedDev = normalizeDeviceId(expectedDeviceId);
    if (row.owner_device_id !== normalizedDev) {
      throw new CloudStoreError(
        403,
        'ENTITLEMENT_DEVICE_MISMATCH',
        '该激活凭证已绑定其他设备，请在本机兑换或换绑激活码'
      );
    }
    return this._buildEntitlementSummary(row, nowMs);
  }

  redeemActivationCode({ code, deviceId, existingToken = '', clientIp = 'unknown' }, nowMs = this.now()) {
    const dev = normalizeDeviceId(deviceId);
    const rateKeys = [`ip:${String(clientIp || 'unknown')}`, `dev:${dev}`];
    this._checkRedeemRateLimit(rateKeys, nowMs);

    const parsed = normalizeActivationCode(code);
    if (!parsed) {
      this._recordRedeemFailure(rateKeys, nowMs);
      throw new CloudStoreError(400, 'INVALID_ACTIVATION_CODE', '激活码格式或校验位无效，请检查后重试');
    }
    const codeHash = this._hashCode(parsed.canonical);

    return this._runInTransaction(() => {
      const codeRow = this.db.prepare('SELECT * FROM activation_codes WHERE code_hash = ?').get(codeHash);
      if (!codeRow) {
        this._recordRedeemFailure(rateKeys, nowMs);
        throw new CloudStoreError(404, 'INVALID_ACTIVATION_CODE', '激活码不存在，请核对后重试');
      }
      if (codeRow.status === 'REVOKED') {
        throw new CloudStoreError(403, 'CODE_REVOKED', '该激活码已被停用');
      }

      const existingEntitlement = existingToken ? this._findEntitlementByToken(existingToken) : null;

      if (codeRow.status === 'UNUSED') {
        const startsAtMs = nowMs;
        const expiresAtMs = nowMs + codeRow.valid_days * 24 * 60 * 60 * 1000;

        if (codeRow.code_type === 'TOPUP') {
          if (!existingEntitlement || existingEntitlement.status !== 'ACTIVE' || existingEntitlement.owner_device_id !== dev) {
            throw new CloudStoreError(401, 'ENTITLEMENT_REQUIRED', '加油包激活码需要先激活基础网络对讲权益');
          }
          const grantId = `gr_${crypto.randomUUID().replaceAll('-', '')}`;
          this.db
            .prepare(
              `INSERT INTO quota_grants
               (grant_id, entitlement_id, source_code_id, source_type, total_seconds, used_seconds, max_members, max_concurrent_rooms, max_session_minutes, starts_at_ms, expires_at_ms, status, note, created_at_ms)
               VALUES (?, ?, ?, 'CODE', ?, 0, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)`
            )
            .run(
              grantId,
              existingEntitlement.entitlement_id,
              codeRow.code_id,
              codeRow.quota_seconds,
              codeRow.max_members,
              codeRow.max_concurrent_rooms,
              codeRow.max_session_minutes,
              startsAtMs,
              expiresAtMs,
              codeRow.note,
              nowMs
            );
          this.db
            .prepare(
              `UPDATE activation_codes
               SET status = 'REDEEMED', redeemed_entitlement_id = ?, redeemed_at_ms = ?
               WHERE code_id = ?`
            )
            .run(existingEntitlement.entitlement_id, nowMs, codeRow.code_id);
          this.db
            .prepare('UPDATE entitlements SET updated_at_ms = ? WHERE entitlement_id = ?')
            .run(nowMs, existingEntitlement.entitlement_id);
          const updated = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(existingEntitlement.entitlement_id);
          return {
            action: 'STACKED',
            rebound: false,
            entitlementToken: existingToken.trim(),
            codeMask: codeRow.code_mask,
            entitlement: this._buildEntitlementSummary(updated, nowMs)
          };
        }

        if (existingEntitlement && existingEntitlement.status === 'ACTIVE' && existingEntitlement.owner_device_id === dev) {
          const grantId = `gr_${crypto.randomUUID().replaceAll('-', '')}`;
          this.db
            .prepare(
              `INSERT INTO quota_grants
               (grant_id, entitlement_id, source_code_id, source_type, total_seconds, used_seconds, max_members, max_concurrent_rooms, max_session_minutes, starts_at_ms, expires_at_ms, status, note, created_at_ms)
               VALUES (?, ?, ?, 'CODE', ?, 0, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)`
            )
            .run(
              grantId,
              existingEntitlement.entitlement_id,
              codeRow.code_id,
              codeRow.quota_seconds,
              codeRow.max_members,
              codeRow.max_concurrent_rooms,
              codeRow.max_session_minutes,
              startsAtMs,
              expiresAtMs,
              codeRow.note,
              nowMs
            );
          this.db
            .prepare(
              `UPDATE activation_codes
               SET status = 'REDEEMED', redeemed_entitlement_id = ?, redeemed_at_ms = ?
               WHERE code_id = ?`
            )
            .run(existingEntitlement.entitlement_id, nowMs, codeRow.code_id);
          this.db
            .prepare('UPDATE entitlements SET updated_at_ms = ? WHERE entitlement_id = ?')
            .run(nowMs, existingEntitlement.entitlement_id);
          const updated = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(existingEntitlement.entitlement_id);
          return {
            action: 'STACKED',
            rebound: false,
            entitlementToken: existingToken.trim(),
            codeMask: codeRow.code_mask,
            entitlement: this._buildEntitlementSummary(updated, nowMs)
          };
        }

        const entitlementId = `ent_${crypto.randomUUID().replaceAll('-', '')}`;
        const entitlementToken = this._generateEntitlementToken();
        const tokenHash = this._hashToken(entitlementToken);
        this.db
          .prepare(
            `INSERT INTO entitlements
             (entitlement_id, owner_device_id, account_id, token_hash, status, rebind_count_7d, rebind_window_start_ms, created_at_ms, updated_at_ms)
             VALUES (?, ?, NULL, ?, 'ACTIVE', 0, 0, ?, ?)`
          )
          .run(entitlementId, dev, tokenHash, nowMs, nowMs);
        const grantId = `gr_${crypto.randomUUID().replaceAll('-', '')}`;
        this.db
          .prepare(
            `INSERT INTO quota_grants
             (grant_id, entitlement_id, source_code_id, source_type, total_seconds, used_seconds, max_members, max_concurrent_rooms, max_session_minutes, starts_at_ms, expires_at_ms, status, note, created_at_ms)
             VALUES (?, ?, ?, 'CODE', ?, 0, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)`
          )
          .run(
            grantId,
            entitlementId,
            codeRow.code_id,
            codeRow.quota_seconds,
            codeRow.max_members,
            codeRow.max_concurrent_rooms,
            codeRow.max_session_minutes,
            startsAtMs,
            expiresAtMs,
            codeRow.note,
            nowMs
          );
        this.db
          .prepare(
            `UPDATE activation_codes
             SET status = 'REDEEMED', redeemed_entitlement_id = ?, redeemed_at_ms = ?
             WHERE code_id = ?`
          )
          .run(entitlementId, nowMs, codeRow.code_id);
        const created = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(entitlementId);
        return {
          action: 'ACTIVATED',
          rebound: false,
          entitlementToken,
          codeMask: codeRow.code_mask,
          entitlement: this._buildEntitlementSummary(created, nowMs)
        };
      }

      // Already redeemed: allow same-code recovery / bounded device rebind
      const boundEntitlement = codeRow.redeemed_entitlement_id
        ? this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(codeRow.redeemed_entitlement_id)
        : null;
      if (!boundEntitlement) {
        throw new CloudStoreError(409, 'CODE_ALREADY_REDEEMED', '该激活码已被使用');
      }
      if (boundEntitlement.status === 'REVOKED') {
        throw new CloudStoreError(403, 'ENTITLEMENT_REVOKED', '该激活码对应的权益已被停用');
      }

      if (
        existingEntitlement &&
        existingEntitlement.entitlement_id === boundEntitlement.entitlement_id &&
        boundEntitlement.owner_device_id === dev
      ) {
        return {
          action: 'ALREADY_ACTIVE',
          rebound: false,
          entitlementToken: existingToken.trim(),
          codeMask: codeRow.code_mask,
          entitlement: this._buildEntitlementSummary(boundEntitlement, nowMs)
        };
      }

      const inWindow =
        boundEntitlement.rebind_window_start_ms > 0 &&
        nowMs - boundEntitlement.rebind_window_start_ms < this.rebindWindowMs;
      const currentCount = inWindow ? boundEntitlement.rebind_count_7d : 0;
      const nextWindowStart = inWindow ? boundEntitlement.rebind_window_start_ms : nowMs;
      if (currentCount >= this.maxRebindsPerWindow) {
        throw new CloudStoreError(
          409,
          'REBIND_LIMIT_EXCEEDED',
          `该激活码 7 天内已换绑 ${this.maxRebindsPerWindow} 次，已达上限，请联系管理员重置`
        );
      }

      const nextToken = this._generateEntitlementToken();
      const nextTokenHash = this._hashToken(nextToken);
      const previousDeviceId = boundEntitlement.owner_device_id;
      this.db
        .prepare(
          `UPDATE entitlements
           SET owner_device_id = ?, token_hash = ?, rebind_count_7d = ?, rebind_window_start_ms = ?, updated_at_ms = ?
           WHERE entitlement_id = ?`
        )
        .run(dev, nextTokenHash, currentCount + 1, nextWindowStart, nowMs, boundEntitlement.entitlement_id);
      const updated = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(boundEntitlement.entitlement_id);
      return {
        action: 'REBOUND',
        rebound: true,
        previousDeviceId,
        entitlementId: boundEntitlement.entitlement_id,
        entitlementToken: nextToken,
        codeMask: codeRow.code_mask,
        entitlement: this._buildEntitlementSummary(updated, nowMs)
      };
    });
  }

  checkCreateRoomEligibility(
    token,
    { mode, deviceId, activeSponsoredRoomsCount = 0, countActiveRooms = null },
    nowMs = this.now()
  ) {
    const globalRow = this._ensureGlobalMonthRow(nowMs);
    const globalRemainingSeconds = Math.max(0, globalRow.hard_limit_seconds - globalRow.used_seconds);
    if (globalRemainingSeconds <= 0) {
      throw new CloudStoreError(503, 'GLOBAL_QUOTA_EXHAUSTED', '本月云端测试总免费额度已用尽，请改用本地直连模式');
    }

    const dev = normalizeDeviceId(deviceId);
    const summary = this.getEntitlementStatus(token, nowMs, { expectedDeviceId: dev });
    if (summary.activeGrantCount <= 0 || summary.remainingSeconds <= 0) {
      if (summary.hasDepletedGrant && !summary.hasExpiredGrant) {
        throw new CloudStoreError(402, 'QUOTA_EXHAUSTED', '云端对讲人·分钟额度已用尽，请兑换新激活码或改用本地模式');
      }
      if (summary.hasExpiredGrant && !summary.hasDepletedGrant) {
        throw new CloudStoreError(402, 'ENTITLEMENT_EXPIRED', '激活码权益已过期，请兑换新激活码或改用本地模式');
      }
      throw new CloudStoreError(402, 'QUOTA_EXHAUSTED', '暂无可用的云端对讲额度，请兑换新激活码或改用本地模式');
    }

    const activeCount =
      typeof countActiveRooms === 'function' ? countActiveRooms(summary.entitlementId) : activeSponsoredRoomsCount;
    if (activeCount >= summary.maxConcurrentRooms) {
      throw new CloudStoreError(
        409,
        'CONCURRENT_ROOM_LIMIT',
        `当前激活码最多同时开启 ${summary.maxConcurrentRooms} 个云端房间，请先结束已有房间`
      );
    }

    return {
      entitlementId: summary.entitlementId,
      ownerDeviceId: summary.ownerDeviceId,
      maxMembers: mode === 'HYBRID' ? Math.min(4, summary.maxMembers) : summary.maxMembers,
      maxConcurrentRooms: summary.maxConcurrentRooms,
      maxSessionMinutes: summary.maxSessionMinutes,
      remainingSeconds: summary.remainingSeconds,
      globalRemainingSeconds,
      effectiveRemainingSeconds: Math.min(summary.remainingSeconds, globalRemainingSeconds)
    };
  }

  checkJoinEligibilityIfRequired(token, { deviceId = '' } = {}, nowMs = this.now()) {
    if (!this.isRequireCodeForJoin()) return null;
    const globalRow = this._ensureGlobalMonthRow(nowMs);
    if (globalRow.used_seconds >= globalRow.hard_limit_seconds) {
      throw new CloudStoreError(503, 'GLOBAL_QUOTA_EXHAUSTED', '本月云端测试总免费额度已用尽，请改用本地直连模式');
    }
    const summary = this.getEntitlementStatus(token, nowMs, { expectedDeviceId: deviceId });
    if (summary.activeGrantCount <= 0 || summary.remainingSeconds <= 0) {
      throw new CloudStoreError(402, 'QUOTA_EXHAUSTED', '当前开启了全员内测门槛，你的激活码已过期或无可用额度');
    }
    return summary;
  }

  checkSponsorQuota(entitlementId, nowMs = this.now()) {
    const globalRow = this._ensureGlobalMonthRow(nowMs);
    const globalRemainingSeconds = Math.max(0, globalRow.hard_limit_seconds - globalRow.used_seconds);
    if (globalRemainingSeconds <= 0) {
      return {
        allowed: false,
        status: 503,
        code: 'GLOBAL_QUOTA_EXHAUSTED',
        reason: 'GLOBAL_QUOTA_EXHAUSTED',
        message: '本月云端测试总免费额度已用尽，通话已结束，请使用本地直连模式',
        remainingSeconds: 0,
        globalRemainingSeconds: 0,
        effectiveRemainingSeconds: 0,
        maxSessionMinutes: DEFAULT_MAX_SESSION_MINUTES
      };
    }
    const row = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(entitlementId);
    if (!row || row.status === 'REVOKED') {
      return {
        allowed: false,
        status: 401,
        code: 'ENTITLEMENT_REVOKED',
        reason: 'ENTITLEMENT_REVOKED',
        message: '房主云端对讲权限已失效，房间已结束',
        remainingSeconds: 0,
        globalRemainingSeconds,
        effectiveRemainingSeconds: 0,
        maxSessionMinutes: DEFAULT_MAX_SESSION_MINUTES
      };
    }
    const summary = this._buildEntitlementSummary(row, nowMs);
    if (summary.activeGrantCount <= 0 || summary.remainingSeconds <= 0) {
      const expiredOnly = summary.hasExpiredGrant && !summary.hasDepletedGrant;
      return {
        allowed: false,
        status: 402,
        code: expiredOnly ? 'ENTITLEMENT_EXPIRED' : 'QUOTA_EXHAUSTED',
        reason: expiredOnly ? 'ENTITLEMENT_EXPIRED' : 'QUOTA_EXHAUSTED',
        message: expiredOnly
          ? '房主激活码权益已过期，云端通话已结束'
          : '房主云端对讲额度已用尽，通话已结束',
        remainingSeconds: 0,
        globalRemainingSeconds,
        effectiveRemainingSeconds: 0,
        maxSessionMinutes: DEFAULT_MAX_SESSION_MINUTES
      };
    }
    return {
      allowed: true,
      status: 200,
      code: 'OK',
      reason: null,
      message: null,
      remainingSeconds: summary.remainingSeconds,
      globalRemainingSeconds,
      effectiveRemainingSeconds: Math.min(summary.remainingSeconds, globalRemainingSeconds),
      maxSessionMinutes: summary.maxSessionMinutes || DEFAULT_MAX_SESSION_MINUTES
    };
  }

  deductUsage({ entitlementId, roomId, mode, deltaSeconds, peakRtcMembers = 2, reason = 'SWEEP' }, nowMs = this.now()) {
    const billSeconds = Math.max(0, Math.floor(Number(deltaSeconds) || 0));
    if (billSeconds <= 0) {
      return this.checkSponsorQuota(entitlementId, nowMs);
    }

    return this._runInTransaction(() => {
      const monthKey = shanghaiMonthKey(nowMs);
      const globalRow = this._ensureGlobalMonthRow(nowMs);
      this._refreshGrantStatuses(entitlementId, nowMs);

      const activeGrants = this.db
        .prepare(
          `SELECT * FROM quota_grants
           WHERE entitlement_id = ? AND status = 'ACTIVE' AND expires_at_ms > ? AND used_seconds < total_seconds
           ORDER BY expires_at_ms ASC, created_at_ms ASC`
        )
        .all(entitlementId, nowMs);

      let remainingToDeduct = billSeconds;
      for (const grant of activeGrants) {
        if (remainingToDeduct <= 0) break;
        const available = grant.total_seconds - grant.used_seconds;
        const take = Math.min(available, remainingToDeduct);
        const nextUsed = grant.used_seconds + take;
        const nextStatus = nextUsed >= grant.total_seconds ? 'DEPLETED' : 'ACTIVE';
        this.db
          .prepare('UPDATE quota_grants SET used_seconds = ?, status = ? WHERE grant_id = ?')
          .run(nextUsed, nextStatus, grant.grant_id);
        this.db
          .prepare(
            `INSERT INTO usage_ledger
             (month_key, room_id, mode, entitlement_id, grant_id, delta_seconds, peak_rtc_members, reason, recorded_at_ms)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
          )
          .run(monthKey, roomId, mode, entitlementId, grant.grant_id, take, peakRtcMembers, reason, nowMs);
        remainingToDeduct -= take;
      }

      if (remainingToDeduct > 0) {
        this.db
          .prepare(
            `INSERT INTO usage_ledger
             (month_key, room_id, mode, entitlement_id, grant_id, delta_seconds, peak_rtc_members, reason, recorded_at_ms)
             VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?)`
          )
          .run(monthKey, roomId, mode, entitlementId, remainingToDeduct, peakRtcMembers, `${reason}_OVERAGE`, nowMs);
      }

      const nextGlobalUsed = globalRow.used_seconds + billSeconds;
      const warnCutoff = Math.floor(globalRow.hard_limit_seconds * globalRow.warn_threshold_ratio);
      let warnedAtMs = globalRow.warned_at_ms;
      let globalWarnedNow = false;
      if (warnedAtMs === null && globalRow.hard_limit_seconds > 0 && nextGlobalUsed >= warnCutoff) {
        warnedAtMs = nowMs;
        globalWarnedNow = true;
      }
      this.db
        .prepare(
          `UPDATE global_monthly_usage
           SET used_seconds = ?, warned_at_ms = ?, updated_at_ms = ?
           WHERE month_key = ?`
        )
        .run(nextGlobalUsed, warnedAtMs, nowMs, monthKey);

      if (globalWarnedNow) {
        this.log({
          event: 'global_monthly_quota_warn',
          monthKey,
          usedMinutes: Math.floor(nextGlobalUsed / 60),
          hardLimitMinutes: Math.floor(globalRow.hard_limit_seconds / 60)
        });
      }

      const status = this.checkSponsorQuota(entitlementId, nowMs);
      return { ...status, globalWarnedNow };
    });
  }

  // -------- Admin HTTP Operations --------

  _pruneIdempotencyCache(nowMs) {
    for (const [key, entry] of this.batchIdemCache) {
      if (entry.expiresAtMs <= nowMs) this.batchIdemCache.delete(key);
    }
  }

  mintCodeBatch(
    {
      count = 1,
      codeType = 'PLAN',
      quotaMinutes = DEFAULT_TEST_QUOTA_MINUTES,
      validDays = DEFAULT_TEST_VALID_DAYS,
      maxMembers = DEFAULT_TEST_MAX_MEMBERS,
      maxConcurrentRooms = DEFAULT_MAX_CONCURRENT_ROOMS,
      maxSessionMinutes = DEFAULT_MAX_SESSION_MINUTES,
      batchId = '',
      note = '',
      actorIp = 'local',
      idempotencyKey = ''
    } = {},
    nowMs = this.now()
  ) {
    const normalizedType = String(codeType ?? 'PLAN').trim().toUpperCase();
    if (!VALID_CODE_TYPES.has(normalizedType)) {
      throw new CloudStoreError(400, 'INVALID_INPUT', 'codeType must be PLAN or TOPUP');
    }
    const validCount = parsePositiveInt(count, 'count', { min: 1, max: 200 });
    const validQuotaMinutes = parsePositiveInt(quotaMinutes, 'quotaMinutes', { min: 1, max: 1_000_000 });
    const validDaysInt = parsePositiveInt(validDays, 'validDays', { min: 1, max: 3650 });
    const validMaxMembers = parsePositiveInt(maxMembers, 'maxMembers', { min: 2, max: 50 });
    const validMaxRooms = parsePositiveInt(maxConcurrentRooms, 'maxConcurrentRooms', { min: 1, max: 20 });
    const validMaxSession = parsePositiveInt(maxSessionMinutes, 'maxSessionMinutes', { min: 5, max: 1440 });
    const resolvedBatchId =
      sanitizeNote(batchId, 64) || `batch-${shanghaiMonthKey(nowMs)}-${crypto.randomBytes(3).toString('hex')}`;
    const resolvedNote = sanitizeNote(note, 160);
    const idem = sanitizeNote(idempotencyKey, 128);
    const batchFingerprint = sha256Hex(
      JSON.stringify({
        action: 'MINT_CODES',
        count: validCount,
        codeType: normalizedType,
        quotaMinutes: validQuotaMinutes,
        validDays: validDaysInt,
        maxMembers: validMaxMembers,
        maxConcurrentRooms: validMaxRooms,
        maxSessionMinutes: validMaxSession,
        batchId: sanitizeNote(batchId, 64),
        note: resolvedNote
      })
    );

    this._pruneIdempotencyCache(nowMs);
    if (idem) {
      const cached = this.batchIdemCache.get(idem);
      if (cached) {
        if (cached.requestHash !== batchFingerprint) {
          throw new CloudStoreError(409, 'IDEMPOTENCY_CONFLICT', 'Idempotency-Key 已被用于不同的批量发码参数');
        }
        return { ...cached.payload, idempotentReplay: true };
      }
      const dbIdem = this.db
        .prepare('SELECT action, request_hash, response_json FROM admin_idempotency WHERE idem_key = ?')
        .get(`batch:${idem}`);
      if (dbIdem) {
        if (dbIdem.action !== 'MINT_CODES' || dbIdem.request_hash !== batchFingerprint) {
          throw new CloudStoreError(409, 'IDEMPOTENCY_CONFLICT', 'Idempotency-Key 已被用于不同的批量发码参数');
        }
        return { ...JSON.parse(dbIdem.response_json), idempotentReplay: true };
      }
    }

    const created = this._runInTransaction(() => {
      const generated = [];
      const insertStmt = this.db.prepare(
        `INSERT INTO activation_codes
         (code_id, code_hash, code_mask, code_ciphertext, batch_id, code_type, quota_seconds, valid_days, max_members, max_concurrent_rooms, max_session_minutes, status, redeemed_entitlement_id, redeemed_at_ms, note, created_at_ms)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNUSED', NULL, NULL, ?, ?)`
      );
      for (let idx = 0; idx < validCount; idx += 1) {
        let formatted;
        let parsed;
        let codeHash;
        for (let attempt = 0; attempt < 8; attempt += 1) {
          formatted = generateActivationCode();
          parsed = normalizeActivationCode(formatted);
          codeHash = this._hashCode(parsed.canonical);
          const exists = this.db.prepare('SELECT 1 FROM activation_codes WHERE code_hash = ?').get(codeHash);
          if (!exists) break;
          codeHash = null;
        }
        if (!codeHash) {
          throw new CloudStoreError(500, 'CODE_GENERATION_FAILED', '激活码生成冲突，请重试');
        }
        const codeId = `cd_${crypto.randomUUID().replaceAll('-', '')}`;
        const codeCiphertext = this._encryptCodePlaintext(codeId, formatted);
        insertStmt.run(
          codeId,
          codeHash,
          parsed.mask,
          codeCiphertext,
          resolvedBatchId,
          normalizedType,
          validQuotaMinutes * 60,
          validDaysInt,
          validMaxMembers,
          validMaxRooms,
          validMaxSession,
          resolvedNote,
          nowMs
        );
        generated.push({
          codeId,
          code: formatted,
          codeMask: parsed.mask
        });
      }
      this._writeAuditLog(
        {
          action: 'MINT_CODES',
          targetType: 'BATCH',
          targetId: resolvedBatchId,
          actorIp,
          payloadSummary: {
            count: validCount,
            codeType: normalizedType,
            quotaMinutes: validQuotaMinutes,
            validDays: validDaysInt,
            maxMembers: validMaxMembers,
            maxConcurrentRooms: validMaxRooms,
            maxSessionMinutes: validMaxSession,
            note: resolvedNote
          }
        },
        nowMs
      );
      const persistentReplayPayload = {
        batchId: resolvedBatchId,
        codeType: normalizedType,
        count: validCount,
        quotaMinutes: validQuotaMinutes,
        validDays: validDaysInt,
        maxMembers: validMaxMembers,
        maxConcurrentRooms: validMaxRooms,
        maxSessionMinutes: validMaxSession,
        note: resolvedNote,
        createdAtMs: nowMs,
        codes: generated.map(({ codeId, codeMask }) => ({ codeId, codeMask }))
      };
      if (idem) {
        this.db
          .prepare(
            `INSERT INTO admin_idempotency (idem_key, action, request_hash, response_json, created_at_ms)
             VALUES (?, 'MINT_CODES', ?, ?, ?)`
          )
          .run(`batch:${idem}`, batchFingerprint, JSON.stringify(persistentReplayPayload), nowMs);
      }
      return {
        ...persistentReplayPayload,
        codes: generated
      };
    });

    if (idem) {
      this.batchIdemCache.set(idem, {
        expiresAtMs: nowMs + IDEMPOTENCY_MEMORY_TTL_MS,
        requestHash: batchFingerprint,
        payload: created
      });
    }
    return created;
  }

  listCodes({ batchId = '', status = '', codeMask = '', limit = 50, offset = 0 } = {}, nowMs = this.now()) {
    const safeLimit = parsePositiveInt(limit, 'limit', { min: 1, max: 200 });
    const safeOffset = parseNonNegativeInt(offset, 'offset', { max: 100_000 });
    const clauses = [];
    const params = [];
    if (batchId) {
      clauses.push('c.batch_id = ?');
      params.push(String(batchId).trim());
    }
    if (status) {
      clauses.push('c.status = ?');
      params.push(String(status).trim().toUpperCase());
    }
    if (codeMask) {
      clauses.push('c.code_mask LIKE ?');
      params.push(`%${String(codeMask).trim().toUpperCase()}%`);
    }
    const whereSql = clauses.length > 0 ? `WHERE ${clauses.join(' AND ')}` : '';
    const total = this.db.prepare(`SELECT COUNT(*) AS cnt FROM activation_codes c ${whereSql}`).get(...params).cnt;
    const rows = this.db
      .prepare(
        `SELECT c.*, g.used_seconds AS grant_used_seconds, g.total_seconds AS grant_total_seconds,
                g.expires_at_ms AS grant_expires_at_ms, g.status AS grant_status
         FROM activation_codes c
         LEFT JOIN quota_grants g ON g.source_code_id = c.code_id
         ${whereSql}
         ORDER BY c.created_at_ms DESC
         LIMIT ? OFFSET ?`
      )
      .all(...params, safeLimit, safeOffset);

    return {
      total,
      limit: safeLimit,
      offset: safeOffset,
      items: rows.map((row) => {
        const remainingSeconds =
          row.grant_total_seconds != null
            ? Math.max(0, row.grant_total_seconds - (row.grant_used_seconds ?? 0))
            : row.quota_seconds;
        return {
          codeId: row.code_id,
          codeMask: row.code_mask,
          canReveal: Boolean(row.code_ciphertext),
          batchId: row.batch_id,
          codeType: row.code_type,
          quotaMinutes: Math.floor(row.quota_seconds / 60),
          remainingMinutes: row.status === 'REVOKED' ? 0 : Math.floor(remainingSeconds / 60),
          validDays: row.valid_days,
          maxMembers: row.max_members,
          maxConcurrentRooms: row.max_concurrent_rooms,
          maxSessionMinutes: row.max_session_minutes,
          status: row.status,
          grantStatus: row.grant_status ?? null,
          expiresAtMs: row.grant_expires_at_ms ?? null,
          redeemedEntitlementId: row.redeemed_entitlement_id ?? null,
          redeemedAtMs: row.redeemed_at_ms ?? null,
          note: row.note,
          createdAtMs: row.created_at_ms
        };
      }),
      serverNowMs: nowMs
    };
  }

  revokeCode(codeId, { reason = '', actorIp = 'local' } = {}, nowMs = this.now()) {
    const id = sanitizeNote(codeId, 80);
    if (!id) throw new CloudStoreError(400, 'INVALID_INPUT', 'codeId is required');
    return this._runInTransaction(() => {
      const codeRow = this.db.prepare('SELECT * FROM activation_codes WHERE code_id = ?').get(id);
      if (!codeRow) {
        throw new CloudStoreError(404, 'CODE_NOT_FOUND', '激活码不存在');
      }
      this.db.prepare("UPDATE activation_codes SET status = 'REVOKED' WHERE code_id = ?").run(id);
      this.db.prepare("UPDATE quota_grants SET status = 'REVOKED' WHERE source_code_id = ?").run(id);
      let entitlementId = codeRow.redeemed_entitlement_id ?? null;
      let entitlementRemainingSeconds = 0;
      if (entitlementId) {
        const entRow = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(entitlementId);
        if (entRow) {
          const summary = this._buildEntitlementSummary(entRow, nowMs);
          entitlementRemainingSeconds = summary.remainingSeconds;
        }
      }
      this._writeAuditLog(
        {
          action: 'REVOKE_CODE',
          targetType: 'CODE',
          targetId: id,
          actorIp,
          payloadSummary: {
            codeMask: codeRow.code_mask,
            entitlementId,
            reason: sanitizeNote(reason, 160)
          }
        },
        nowMs
      );
      return {
        codeId: id,
        codeMask: codeRow.code_mask,
        status: 'REVOKED',
        entitlementId,
        shouldTerminateRooms: Boolean(entitlementId && entitlementRemainingSeconds <= 0)
      };
    });
  }

  revealCode(codeId, { actorIp = 'local' } = {}, nowMs = this.now()) {
    const id = sanitizeNote(codeId, 80);
    if (!id) throw new CloudStoreError(400, 'INVALID_INPUT', 'codeId is required');
    return this._runInTransaction(() => {
      const row = this.db.prepare('SELECT * FROM activation_codes WHERE code_id = ?').get(id);
      if (!row) {
        throw new CloudStoreError(404, 'CODE_NOT_FOUND', '激活码不存在');
      }
      if (!row.code_ciphertext) {
        throw new CloudStoreError(404, 'CODE_PLAINTEXT_UNAVAILABLE', '该激活码未保存加密原码，无法查看明文');
      }
      const plaintext = this._decryptCodePlaintext(row.code_id, row.code_ciphertext);
      const parsed = plaintext ? normalizeActivationCode(plaintext) : null;
      if (!parsed || this._hashCode(parsed.canonical) !== row.code_hash) {
        throw new CloudStoreError(500, 'CODE_VAULT_CORRUPTED', '激活码密文校验失败，已拒绝输出');
      }
      this._writeAuditLog(
        {
          action: 'REVEAL_CODE',
          targetType: 'CODE',
          targetId: row.code_id,
          actorIp,
          payloadSummary: {
            codeMask: row.code_mask,
            batchId: row.batch_id,
            status: row.status
          }
        },
        nowMs
      );
      return {
        codeId: row.code_id,
        code: parsed.formatted,
        codeMask: row.code_mask,
        batchId: row.batch_id,
        codeType: row.code_type,
        status: row.status,
        quotaMinutes: Math.floor(row.quota_seconds / 60),
        validDays: row.valid_days,
        maxMembers: row.max_members,
        maxConcurrentRooms: row.max_concurrent_rooms,
        maxSessionMinutes: row.max_session_minutes,
        redeemedEntitlementId: row.redeemed_entitlement_id ?? null,
        note: row.note,
        createdAtMs: row.created_at_ms
      };
    });
  }

  listEntitlements({ status = '', limit = 50, offset = 0 } = {}, nowMs = this.now()) {
    const safeLimit = parsePositiveInt(limit, 'limit', { min: 1, max: 200 });
    const safeOffset = parseNonNegativeInt(offset, 'offset', { max: 100_000 });
    const clauses = [];
    const params = [];
    if (status) {
      clauses.push('status = ?');
      params.push(String(status).trim().toUpperCase());
    }
    const whereSql = clauses.length > 0 ? `WHERE ${clauses.join(' AND ')}` : '';
    const total = this.db.prepare(`SELECT COUNT(*) AS cnt FROM entitlements ${whereSql}`).get(...params).cnt;
    const rows = this.db
      .prepare(
        `SELECT * FROM entitlements ${whereSql}
         ORDER BY updated_at_ms DESC
         LIMIT ? OFFSET ?`
      )
      .all(...params, safeLimit, safeOffset);

    return {
      total,
      limit: safeLimit,
      offset: safeOffset,
      items: rows.map((row) => ({
        ...this._buildEntitlementSummary(row, nowMs),
        createdAtMs: row.created_at_ms,
        updatedAtMs: row.updated_at_ms
      })),
      serverNowMs: nowMs
    };
  }

  topupEntitlement(
    entitlementId,
    {
      addMinutes = 0,
      extendDays = 30,
      maxMembers = null,
      maxConcurrentRooms = null,
      maxSessionMinutes = null,
      reason = '',
      actorIp = 'local',
      idempotencyKey = ''
    } = {},
    nowMs = this.now()
  ) {
    const id = sanitizeNote(entitlementId, 80);
    if (!id) throw new CloudStoreError(400, 'INVALID_INPUT', 'entitlementId is required');
    const validAddMinutes = parseNonNegativeInt(addMinutes, 'addMinutes', { max: 1_000_000 });
    const validExtendDays = parseNonNegativeInt(extendDays, 'extendDays', { max: 3650 });
    if (validAddMinutes === 0 && validExtendDays === 0) {
      throw new CloudStoreError(400, 'INVALID_INPUT', 'addMinutes 和 extendDays 不能同时为 0');
    }
    const idem = sanitizeNote(idempotencyKey, 128);
    const topupFingerprint = sha256Hex(
      JSON.stringify({
        action: 'TOPUP_ENTITLEMENT',
        entitlementId: id,
        addMinutes: validAddMinutes,
        extendDays: validExtendDays,
        maxMembers: maxMembers ?? null,
        maxConcurrentRooms: maxConcurrentRooms ?? null,
        maxSessionMinutes: maxSessionMinutes ?? null,
        reason: sanitizeNote(reason, 160)
      })
    );
    if (idem) {
      const existing = this.db
        .prepare('SELECT action, request_hash, response_json FROM admin_idempotency WHERE idem_key = ?')
        .get(`topup:${idem}`);
      if (existing) {
        if (existing.action !== 'TOPUP_ENTITLEMENT' || existing.request_hash !== topupFingerprint) {
          throw new CloudStoreError(409, 'IDEMPOTENCY_CONFLICT', 'Idempotency-Key 已被用于不同的目标或充值参数');
        }
        return { ...JSON.parse(existing.response_json), idempotentReplay: true };
      }
    }

    return this._runInTransaction(() => {
      const entRow = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(id);
      if (!entRow) {
        throw new CloudStoreError(404, 'ENTITLEMENT_NOT_FOUND', '权益主体不存在');
      }
      if (entRow.status === 'REVOKED') {
        throw new CloudStoreError(409, 'ENTITLEMENT_REVOKED', '已停用的权益不能充值');
      }
      const currentSummary = this._buildEntitlementSummary(entRow, nowMs);
      const targetMaxMembers =
        maxMembers !== null && maxMembers !== undefined
          ? parsePositiveInt(maxMembers, 'maxMembers', { min: 2, max: 50 })
          : currentSummary.maxMembers || DEFAULT_TEST_MAX_MEMBERS;
      const targetMaxRooms =
        maxConcurrentRooms !== null && maxConcurrentRooms !== undefined
          ? parsePositiveInt(maxConcurrentRooms, 'maxConcurrentRooms', { min: 1, max: 20 })
          : currentSummary.maxConcurrentRooms || DEFAULT_MAX_CONCURRENT_ROOMS;
      const targetMaxSession =
        maxSessionMinutes !== null && maxSessionMinutes !== undefined
          ? parsePositiveInt(maxSessionMinutes, 'maxSessionMinutes', { min: 5, max: 1440 })
          : currentSummary.maxSessionMinutes || DEFAULT_MAX_SESSION_MINUTES;

      if (validAddMinutes > 0) {
        const grantId = `gr_${crypto.randomUUID().replaceAll('-', '')}`;
        const baseExpiry = Math.max(nowMs, currentSummary.furthestExpiresAtMs ?? nowMs);
        const expiresAtMs =
          validExtendDays > 0
            ? baseExpiry + validExtendDays * 24 * 60 * 60 * 1000
            : Math.max(nowMs + 24 * 60 * 60 * 1000, currentSummary.furthestExpiresAtMs ?? nowMs + 30 * 24 * 60 * 60 * 1000);
        this.db
          .prepare(
            `INSERT INTO quota_grants
             (grant_id, entitlement_id, source_code_id, source_type, total_seconds, used_seconds, max_members, max_concurrent_rooms, max_session_minutes, starts_at_ms, expires_at_ms, status, note, created_at_ms)
             VALUES (?, ?, NULL, 'ADMIN_TOPUP', ?, 0, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)`
          )
          .run(
            grantId,
            id,
            validAddMinutes * 60,
            targetMaxMembers,
            targetMaxRooms,
            targetMaxSession,
            nowMs,
            expiresAtMs,
            sanitizeNote(reason, 160),
            nowMs
          );
      } else if (validExtendDays > 0) {
        const extendMs = validExtendDays * 24 * 60 * 60 * 1000;
        const grants = this.db
          .prepare(
            `SELECT * FROM quota_grants
             WHERE entitlement_id = ? AND status IN ('ACTIVE', 'EXPIRED') AND used_seconds < total_seconds
             ORDER BY expires_at_ms DESC`
          )
          .all(id);
        if (grants.length === 0) {
          throw new CloudStoreError(409, 'NO_REMAINING_GRANT', '没有剩余人·分钟可供延期，请同时传入 addMinutes');
        }
        const target = grants[0];
        const nextExpiresAtMs = Math.max(nowMs, target.expires_at_ms) + extendMs;
        this.db
          .prepare(
            `UPDATE quota_grants
             SET expires_at_ms = ?, status = 'ACTIVE', max_members = ?, max_concurrent_rooms = ?, max_session_minutes = ?
             WHERE grant_id = ?`
          )
          .run(nextExpiresAtMs, targetMaxMembers, targetMaxRooms, targetMaxSession, target.grant_id);
      }

      this.db.prepare('UPDATE entitlements SET updated_at_ms = ? WHERE entitlement_id = ?').run(nowMs, id);
      const updated = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(id);
      const summary = this._buildEntitlementSummary(updated, nowMs);
      this._writeAuditLog(
        {
          action: 'TOPUP_ENTITLEMENT',
          targetType: 'ENTITLEMENT',
          targetId: id,
          actorIp,
          payloadSummary: {
            addMinutes: validAddMinutes,
            extendDays: validExtendDays,
            maxMembers: targetMaxMembers,
            reason: sanitizeNote(reason, 160)
          }
        },
        nowMs
      );
      const payload = { entitlement: summary };
      if (idem) {
        this.db
          .prepare(
            `INSERT INTO admin_idempotency (idem_key, action, request_hash, response_json, created_at_ms)
             VALUES (?, 'TOPUP_ENTITLEMENT', ?, ?, ?)`
          )
          .run(`topup:${idem}`, topupFingerprint, JSON.stringify(payload), nowMs);
      }
      return payload;
    });
  }

  resetRebindCount(entitlementId, { reason = '', actorIp = 'local' } = {}, nowMs = this.now()) {
    const id = sanitizeNote(entitlementId, 80);
    if (!id) throw new CloudStoreError(400, 'INVALID_INPUT', 'entitlementId is required');
    return this._runInTransaction(() => {
      const entRow = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(id);
      if (!entRow) {
        throw new CloudStoreError(404, 'ENTITLEMENT_NOT_FOUND', '权益主体不存在');
      }
      this.db
        .prepare(
          `UPDATE entitlements
           SET rebind_count_7d = 0, rebind_window_start_ms = 0, updated_at_ms = ?
           WHERE entitlement_id = ?`
        )
        .run(nowMs, id);
      this._writeAuditLog(
        {
          action: 'RESET_REBIND',
          targetType: 'ENTITLEMENT',
          targetId: id,
          actorIp,
          payloadSummary: { reason: sanitizeNote(reason, 160) }
        },
        nowMs
      );
      const updated = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(id);
      return { entitlement: this._buildEntitlementSummary(updated, nowMs) };
    });
  }

  revokeEntitlement(entitlementId, { reason = '', actorIp = 'local' } = {}, nowMs = this.now()) {
    const id = sanitizeNote(entitlementId, 80);
    if (!id) throw new CloudStoreError(400, 'INVALID_INPUT', 'entitlementId is required');
    return this._runInTransaction(() => {
      const entRow = this.db.prepare('SELECT * FROM entitlements WHERE entitlement_id = ?').get(id);
      if (!entRow) {
        throw new CloudStoreError(404, 'ENTITLEMENT_NOT_FOUND', '权益主体不存在');
      }
      this.db
        .prepare("UPDATE entitlements SET status = 'REVOKED', updated_at_ms = ? WHERE entitlement_id = ?")
        .run(nowMs, id);
      this.db.prepare("UPDATE quota_grants SET status = 'REVOKED' WHERE entitlement_id = ?").run(id);
      this.db
        .prepare("UPDATE activation_codes SET status = 'REVOKED' WHERE redeemed_entitlement_id = ?")
        .run(id);
      this._writeAuditLog(
        {
          action: 'REVOKE_ENTITLEMENT',
          targetType: 'ENTITLEMENT',
          targetId: id,
          actorIp,
          payloadSummary: { reason: sanitizeNote(reason, 160) }
        },
        nowMs
      );
      return { entitlementId: id, status: 'REVOKED' };
    });
  }

  getGlobalQuotaConfig(nowMs = this.now()) {
    const row = this._ensureGlobalMonthRow(nowMs);
    return {
      monthKey: row.month_key,
      usedSeconds: row.used_seconds,
      usedMinutes: Math.floor(row.used_seconds / 60),
      hardLimitSeconds: row.hard_limit_seconds,
      hardLimitMinutes: Math.floor(row.hard_limit_seconds / 60),
      remainingSeconds: Math.max(0, row.hard_limit_seconds - row.used_seconds),
      remainingMinutes: Math.floor(Math.max(0, row.hard_limit_seconds - row.used_seconds) / 60),
      warnThresholdRatio: row.warn_threshold_ratio,
      warnedAtMs: row.warned_at_ms ?? null,
      requireCodeForJoin: this.isRequireCodeForJoin(),
      updatedAtMs: row.updated_at_ms
    };
  }

  updateGlobalQuotaConfig(
    { hardLimitMinutes = undefined, requireCodeForJoin = undefined, warnThresholdRatio = undefined, actorIp = 'local' } = {},
    nowMs = this.now()
  ) {
    return this._runInTransaction(() => {
      const row = this._ensureGlobalMonthRow(nowMs);
      let nextHardLimitSec = row.hard_limit_seconds;
      if (hardLimitMinutes !== undefined) {
        nextHardLimitSec = parseNonNegativeInt(hardLimitMinutes, 'hardLimitMinutes', { max: 10_000_000 }) * 60;
        this._setConfig('default_hard_limit_seconds', nextHardLimitSec, nowMs);
      }
      let nextWarnRatio = row.warn_threshold_ratio;
      if (warnThresholdRatio !== undefined) {
        const num = Number(warnThresholdRatio);
        if (!Number.isFinite(num) || num <= 0 || num > 1) {
          throw new CloudStoreError(400, 'INVALID_INPUT', 'warnThresholdRatio must be between 0 and 1');
        }
        nextWarnRatio = num;
      }
      if (requireCodeForJoin !== undefined) {
        this._setConfig('require_code_for_join', Boolean(requireCodeForJoin) ? 'true' : 'false', nowMs);
      }
      this.db
        .prepare(
          `UPDATE global_monthly_usage
           SET hard_limit_seconds = ?, warn_threshold_ratio = ?, updated_at_ms = ?
           WHERE month_key = ?`
        )
        .run(nextHardLimitSec, nextWarnRatio, nowMs, row.month_key);

      this._writeAuditLog(
        {
          action: 'UPDATE_GLOBAL_QUOTA',
          targetType: 'GLOBAL_CONFIG',
          targetId: row.month_key,
          actorIp,
          payloadSummary: {
            hardLimitMinutes: Math.floor(nextHardLimitSec / 60),
            warnThresholdRatio: nextWarnRatio,
            requireCodeForJoin: this.isRequireCodeForJoin()
          }
        },
        nowMs
      );
      return this.getGlobalQuotaConfig(nowMs);
    });
  }

  getOverview(activeRooms = [], nowMs = this.now()) {
    const globalQuota = this.getGlobalQuotaConfig(nowMs);
    const monthKey = globalQuota.monthKey;
    const codeCounts = this.db
      .prepare('SELECT status, COUNT(*) AS cnt FROM activation_codes GROUP BY status')
      .all();
    const activeEntitlementCount = this.db
      .prepare("SELECT COUNT(*) AS cnt FROM entitlements WHERE status = 'ACTIVE'")
      .get().cnt;
    const dayStartMs = nowMs - 24 * 60 * 60 * 1000;
    const topConsumersToday = this.db
      .prepare(
        `SELECT u.entitlement_id, e.owner_device_id, SUM(u.delta_seconds) AS used_seconds
         FROM usage_ledger u
         LEFT JOIN entitlements e ON e.entitlement_id = u.entitlement_id
         WHERE u.recorded_at_ms >= ?
         GROUP BY u.entitlement_id
         ORDER BY used_seconds DESC
         LIMIT 10`
      )
      .all(dayStartMs)
      .map((item) => ({
        entitlementId: item.entitlement_id,
        ownerDeviceId: item.owner_device_id ?? '',
        usedSeconds: item.used_seconds,
        usedMinutes: Math.floor(item.used_seconds / 60)
      }));
    const topConsumersMonth = this.db
      .prepare(
        `SELECT u.entitlement_id, e.owner_device_id, SUM(u.delta_seconds) AS used_seconds
         FROM usage_ledger u
         LEFT JOIN entitlements e ON e.entitlement_id = u.entitlement_id
         WHERE u.month_key = ?
         GROUP BY u.entitlement_id
         ORDER BY used_seconds DESC
         LIMIT 10`
      )
      .all(monthKey)
      .map((item) => ({
        entitlementId: item.entitlement_id,
        ownerDeviceId: item.owner_device_id ?? '',
        usedSeconds: item.used_seconds,
        usedMinutes: Math.floor(item.used_seconds / 60)
      }));

    const dailyWindowDays = 14;
    const shanghaiNowMs = nowMs + 8 * 3_600_000;
    const shanghaiTodayStartUtcMs =
      Math.floor(shanghaiNowMs / 86_400_000) * 86_400_000 - 8 * 3_600_000;
    const dailyStartMs = shanghaiTodayStartUtcMs - (dailyWindowDays - 1) * 86_400_000;
    const dailyBuckets = new Map();
    for (let idx = 0; idx < dailyWindowDays; idx += 1) {
      const key = shanghaiDateKey(dailyStartMs + idx * 86_400_000);
      dailyBuckets.set(key, {
        dateKey: key,
        networkSeconds: 0,
        hybridSeconds: 0,
        totalSeconds: 0
      });
    }
    const dailyRows = this.db
      .prepare(
        `SELECT mode, delta_seconds, recorded_at_ms
         FROM usage_ledger
         WHERE recorded_at_ms >= ?`
      )
      .all(dailyStartMs);
    for (const row of dailyRows) {
      const key = shanghaiDateKey(row.recorded_at_ms);
      const bucket = dailyBuckets.get(key);
      if (!bucket) continue;
      if (row.mode === 'HYBRID') bucket.hybridSeconds += row.delta_seconds;
      else bucket.networkSeconds += row.delta_seconds;
      bucket.totalSeconds += row.delta_seconds;
    }
    const dailyUsage = [...dailyBuckets.values()].map((bucket) => ({
      ...bucket,
      networkMinutes: Math.round((bucket.networkSeconds / 60) * 10) / 10,
      hybridMinutes: Math.round((bucket.hybridSeconds / 60) * 10) / 10,
      totalMinutes: Math.round((bucket.totalSeconds / 60) * 10) / 10
    }));

    const codeStats = { UNUSED: 0, REDEEMED: 0, REVOKED: 0 };
    for (const row of codeCounts) {
      codeStats[row.status] = row.cnt;
    }
    const activeRtcMembersTotal = activeRooms.reduce((sum, room) => sum + (room.activeCloudMembers ?? 0), 0);

    return {
      globalQuota,
      activeRoomsCount: activeRooms.length,
      activeCloudRoomsCount: activeRooms.filter((r) => r.activeCloudMembers >= 2).length,
      activeRtcMembersTotal,
      activeEntitlementCount,
      codeStats,
      topConsumersToday,
      topConsumersMonth,
      dailyUsage,
      serverNowMs: nowMs
    };
  }

  listAuditLogs({ limit = 50, offset = 0 } = {}, nowMs = this.now()) {
    const safeLimit = parsePositiveInt(limit, 'limit', { min: 1, max: 200 });
    const safeOffset = parseNonNegativeInt(offset, 'offset', { max: 100_000 });
    const total = this.db.prepare('SELECT COUNT(*) AS cnt FROM admin_audit_logs').get().cnt;
    const rows = this.db
      .prepare(
        `SELECT * FROM admin_audit_logs
         ORDER BY created_at_ms DESC, audit_id DESC
         LIMIT ? OFFSET ?`
      )
      .all(safeLimit, safeOffset);
    return {
      total,
      limit: safeLimit,
      offset: safeOffset,
      items: rows.map((row) => ({
        auditId: row.audit_id,
        action: row.action,
        targetType: row.target_type,
        targetId: row.target_id,
        actorIp: row.actor_ip,
        payloadSummary: JSON.parse(row.payload_summary),
        createdAtMs: row.created_at_ms
      })),
      serverNowMs: nowMs
    };
  }

  recordAdminAction({ action, targetType, targetId, actorIp = 'local', payloadSummary = {} }, nowMs = this.now()) {
    this._writeAuditLog({ action, targetType, targetId, actorIp, payloadSummary }, nowMs);
  }
}
