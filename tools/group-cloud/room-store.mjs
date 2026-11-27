import crypto from 'node:crypto';
import { mintRtcCredentials } from './volc-rtc.mjs';

export const MAX_MEMBERS = 50;
export const HYBRID_MAX_MEMBERS = 4;
export const MAX_PENDING_REQUESTS = 64;
export const MAX_EVENT_WINDOW = 256;
export const RESERVATION_MS = 60_000;
export const DEFAULT_DISCONNECT_TIMEOUT_MS = 25_000;
export const DEFAULT_INVITE_TTL_MS = 15 * 60_000;
export const DEFAULT_REQUEST_TTL_MS = 120_000;
export const DEFAULT_SESSION_TTL_MS = 60 * 60_000;
const TERMINAL_NOTIFICATION_RETENTION_MS = 5 * 60_000;
export const DEFAULT_RTC_TOKEN_TTL_MS = 10 * 60_000;
export const DEFAULT_PRIVATE_INVITE_TTL_MS = 30_000;
export const DEFAULT_TRANSPORT_COOLDOWN_MS = 15_000;
export const DEFAULT_MAX_TRANSPORT_COOLDOWN_MS = 60_000;
export const DEFAULT_LOCAL_STATUS_FRESH_MS = 25_000;
export const DEFAULT_LOCAL_STABLE_RESET_MS = 60_000;
export const DEFAULT_LOCAL_CONTROL_PORT = 8899;
export const DEFAULT_LOCAL_RSSI_THRESHOLD_DBM = -75;
export const DEFAULT_LOCAL_SIGNAL_STABLE_MS = 12_000;
export const DEFAULT_LOCAL_PRECONNECT_TIMEOUT_MS = 20_000;
export const DEFAULT_REVOCATION_RETRY_COOLDOWN_MS = 3_000;
export const DEFAULT_MAX_REVOCATION_RETRY_COOLDOWN_MS = 30_000;
export const MAX_NICKNAME_BYTES = 64;

const INVITE_LENGTH = 6;
const INVITE_REGEX = /^\d{6}$/;
const ID_REGEX = /^[A-Za-z0-9._:-]{1,64}$/;
const IPV4_REGEX = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;
const P2P_SSID_ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';
const P2P_PASS_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
const VALID_MODES = new Set(['NETWORK', 'HYBRID']);
const VALID_HYBRID_ROLES = new Set(['RIDING_OFFICER', 'HEAD', 'TAIL', 'MEMBER']);
const FALLBACK_TRANSPORT_REASONS = new Set(['LOCAL_LINK_LOST', 'LOCAL_HOST_LOST', 'LOCAL_UNAVAILABLE']);

function canEnterRider(role, riderIncludesFormation) {
  if (role === 'OWNER' || role === 'RIDING_OFFICER') return true;
  if (role === 'HEAD' || role === 'TAIL') return Boolean(riderIncludesFormation);
  return false;
}

function formatChannel(channel) {
  if (!channel || channel.type === 'PUBLIC') return 'PUBLIC';
  if (channel.type === 'RIDER') return 'RIDER';
  if (channel.type === 'PRIVATE') return `PRIVATE:${channel.sessionId}`;
  return 'PUBLIC';
}

function generateLocalCode(previous = null) {
  let code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
  while (previous && code === previous) {
    code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
  }
  return code;
}

function randomFromAlphabet(alphabet, length) {
  let out = '';
  for (let idx = 0; idx < length; idx += 1) {
    out += alphabet[crypto.randomInt(0, alphabet.length)];
  }
  return out;
}

function generateLocalP2pSsid(previous = null) {
  let ssid = `DIRECT-mc-${randomFromAlphabet(P2P_SSID_ALPHABET, 8)}`;
  while (previous && ssid === previous) {
    ssid = `DIRECT-mc-${randomFromAlphabet(P2P_SSID_ALPHABET, 8)}`;
  }
  return ssid;
}

function generateLocalP2pPassphrase(previous = null) {
  let pass = randomFromAlphabet(P2P_PASS_ALPHABET, 16);
  while (previous && pass === previous) {
    pass = randomFromAlphabet(P2P_PASS_ALPHABET, 16);
  }
  return pass;
}

function normalizeOptionalIpv4(value) {
  if (value === undefined || value === null || value === '') return null;
  if (typeof value !== 'string') {
    throw new CloudStoreError(400, 'INVALID_INPUT', 'localHostAddress must be a valid IPv4 string');
  }
  const trimmed = value.trim();
  const match = IPV4_REGEX.exec(trimmed);
  if (!match) {
    throw new CloudStoreError(400, 'INVALID_INPUT', 'localHostAddress format is invalid');
  }
  const octets = match.slice(1).map((part) => Number(part));
  if (octets.some((num) => !Number.isInteger(num) || num < 0 || num > 255)) {
    throw new CloudStoreError(400, 'INVALID_INPUT', 'localHostAddress octet out of range');
  }
  if (octets[0] === 0 || octets[0] === 127) {
    throw new CloudStoreError(400, 'INVALID_INPUT', 'localHostAddress cannot be loopback or zero');
  }
  return trimmed;
}

function normalizeOptionalFrequencyMhz(value) {
  if (value === undefined || value === null) return null;
  const freq = Number(value);
  if (!Number.isInteger(freq) || freq < 2400 || freq > 6000) {
    throw new CloudStoreError(400, 'INVALID_INPUT', 'localFrequencyMhz must be an integer between 2400 and 6000');
  }
  return freq;
}

function normalizeOptionalRssiDbm(value) {
  if (value === undefined || value === null) return null;
  const rssi = Number(value);
  if (!Number.isInteger(rssi) || rssi < -127 || rssi > 0) {
    throw new CloudStoreError(400, 'INVALID_INPUT', 'localRssiDbm must be an integer between -127 and 0');
  }
  return rssi;
}

export class CloudStoreError extends Error {
  constructor(status, code, message, extra = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

function normalizeId(value, label) {
  if (typeof value !== 'string') {
    throw new CloudStoreError(400, 'INVALID_INPUT', `${label} is required`);
  }
  const trimmed = value.trim();
  if (!ID_REGEX.test(trimmed)) {
    throw new CloudStoreError(400, 'INVALID_INPUT', `${label} format is invalid`);
  }
  return trimmed;
}

function normalizeNickname(value, fallbackId) {
  const raw = typeof value === 'string' ? value.trim() : '';
  const base = raw || `骑士 ${fallbackId.replace(/-/g, '').slice(-6).toUpperCase()}`;
  let out = base.slice(0, 64);
  while (Buffer.byteLength(out, 'utf8') > MAX_NICKNAME_BYTES && out.length > 1) {
    out = out.slice(0, -1);
  }
  return out;
}

function normalizeInviteCode(value) {
  if (typeof value !== 'string') {
    throw new CloudStoreError(400, 'INVALID_INVITE', '邀请码格式无效');
  }
  const cleaned = value.trim().replace(/[\s-]+/g, '');
  if (!INVITE_REGEX.test(cleaned)) {
    throw new CloudStoreError(400, 'INVALID_INVITE', '邀请码无效或已过期');
  }
  return cleaned;
}

function joinReceiptId(secret) {
  if (typeof secret !== 'string' || !/^[0-9a-f]{64}$/.test(secret)) {
    throw new CloudStoreError(400, 'INVALID_JOIN_RECEIPT', '加入申请凭证格式无效');
  }
  return 'j1-' + crypto.createHash('sha256').update(secret, 'ascii').digest('hex');
}

function timingSafeStringEqual(left, right) {
  const a = Buffer.from(String(left), 'utf8');
  const b = Buffer.from(String(right), 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

export class GroupCloudStore {
  constructor(options = {}) {
    this.now = options.now ?? (() => Date.now());
    this.appId = typeof options.appId === 'string' ? options.appId : '';
    this.appKey = typeof options.appKey === 'string' ? options.appKey : '';
    if (!this.appId || !this.appKey) {
      throw new Error('Volc RTC AppID and AppKey are required');
    }
    this.maxMembers = Number.isInteger(options.maxMembers) ? options.maxMembers : MAX_MEMBERS;
    if (this.maxMembers < 2 || this.maxMembers > MAX_MEMBERS) {
      throw new Error(`maxMembers must be between 2 and ${MAX_MEMBERS}`);
    }
    this.disconnectTimeoutMs = options.disconnectTimeoutMs ?? DEFAULT_DISCONNECT_TIMEOUT_MS;
    this.reservationMs = options.reservationMs ?? RESERVATION_MS;
    this.inviteTtlMs = options.inviteTtlMs ?? DEFAULT_INVITE_TTL_MS;
    this.requestTtlMs = options.requestTtlMs ?? DEFAULT_REQUEST_TTL_MS;
    this.sessionTtlMs = options.sessionTtlMs ?? DEFAULT_SESSION_TTL_MS;
    this.rtcTokenTtlMs = options.rtcTokenTtlMs ?? DEFAULT_RTC_TOKEN_TTL_MS;
    this.privateInviteTtlMs = options.privateInviteTtlMs ?? DEFAULT_PRIVATE_INVITE_TTL_MS;
    this.transportCooldownMs = options.transportCooldownMs ?? DEFAULT_TRANSPORT_COOLDOWN_MS;
    this.maxTransportCooldownMs = options.maxTransportCooldownMs ?? DEFAULT_MAX_TRANSPORT_COOLDOWN_MS;
    this.localStatusFreshMs = options.localStatusFreshMs ?? DEFAULT_LOCAL_STATUS_FRESH_MS;
    this.localStableResetMs = options.localStableResetMs ?? DEFAULT_LOCAL_STABLE_RESET_MS;
    this.localRssiThresholdDbm = Number.isInteger(options.localRssiThresholdDbm)
      ? options.localRssiThresholdDbm
      : DEFAULT_LOCAL_RSSI_THRESHOLD_DBM;
    this.localSignalStableMs = Number.isInteger(options.localSignalStableMs)
      ? options.localSignalStableMs
      : DEFAULT_LOCAL_SIGNAL_STABLE_MS;
    this.localPreconnectTimeoutMs = Number.isInteger(options.localPreconnectTimeoutMs)
      ? options.localPreconnectTimeoutMs
      : DEFAULT_LOCAL_PRECONNECT_TIMEOUT_MS;
    this.revocationRetryCooldownMs =
      Number.isInteger(options.revocationRetryCooldownMs) && options.revocationRetryCooldownMs >= 0
        ? options.revocationRetryCooldownMs
        : DEFAULT_REVOCATION_RETRY_COOLDOWN_MS;
    this.maxRevocationRetryCooldownMs =
      Number.isInteger(options.maxRevocationRetryCooldownMs) &&
      options.maxRevocationRetryCooldownMs >= this.revocationRetryCooldownMs
        ? options.maxRevocationRetryCooldownMs
        : Math.max(DEFAULT_MAX_REVOCATION_RETRY_COOLDOWN_MS, this.revocationRetryCooldownMs);
    this.banRemovedForbiddenSeconds = Number.isInteger(options.banRemovedForbiddenSeconds)
      ? options.banRemovedForbiddenSeconds
      : 300;
    this.banRoomForbiddenSeconds = Number.isInteger(options.banRoomForbiddenSeconds)
      ? options.banRoomForbiddenSeconds
      : 3600;
    const rtcAdmin = options.rtcAdmin ?? null;
    if (rtcAdmin) {
      this.rtcRevocationConfigured = Boolean(rtcAdmin.enabled) && options.rtcRevocationEnabled !== false;
      this.onRtcBanUser = (rtcRoomId, userId, details) =>
        rtcAdmin.banUser(rtcRoomId, userId, details?.forbiddenIntervalSec ?? this.banRemovedForbiddenSeconds);
      this.onRtcBanRoom = (rtcRoomId, details) =>
        rtcAdmin.banRoom(rtcRoomId, details?.forbiddenIntervalSec ?? this.banRoomForbiddenSeconds);
      this.onRtcUnbanUser = (rtcRoomId, userId) => rtcAdmin.unbanUser(rtcRoomId, userId);
    } else {
      const hasHandlers =
        typeof options.onRtcBanUser === 'function' &&
        typeof options.onRtcBanRoom === 'function' &&
        typeof options.onRtcUnbanUser === 'function';
      this.rtcRevocationConfigured = hasHandlers && options.rtcRevocationEnabled !== false;
      this.onRtcBanUser = hasHandlers ? options.onRtcBanUser : null;
      this.onRtcBanRoom = hasHandlers ? options.onRtcBanRoom : null;
      this.onRtcUnbanUser = hasHandlers ? options.onRtcUnbanUser : null;
    }
    this.log = options.log ?? (() => {});
    this.entitlementStore = options.entitlementStore ?? null;
    this.lowQuotaWarnSeconds = Number.isInteger(options.lowQuotaWarnSeconds) ? options.lowQuotaWarnSeconds : 900;
    this.sessionLimitWarnSeconds = Number.isInteger(options.sessionLimitWarnSeconds)
      ? options.sessionLimitWarnSeconds
      : 600;
    this.billingFlushIntervalMs = Number.isInteger(options.billingFlushIntervalMs)
      ? options.billingFlushIntervalMs
      : 30_000;
    this.billingFlushSecondsThreshold = Number.isInteger(options.billingFlushSecondsThreshold)
      ? options.billingFlushSecondsThreshold
      : 30;

    this.rooms = new Map();
    this.inviteIndex = new Map();
    this.sessions = new Map();
    this.revokedTokens = new Map();
    this.pendingIndex = new Map();
    this.cancelledJoinReceipts = new Map();
    this.roomLocks = new Map();
    this.inFlightRoomSweeps = new Map();
    this.pendingCreateByEntitlement = new Map();
  }

  _withRoomLock(roomId, fn) {
    const key = roomId || '__global__';
    const prev = this.roomLocks.get(key) ?? Promise.resolve();
    let release;
    const next = new Promise((resolve) => {
      release = resolve;
    });
    const chained = prev.then(() => next);
    this.roomLocks.set(key, chained);
    return prev
      .catch(() => {})
      .then(() => fn())
      .finally(() => {
        release();
        if (this.roomLocks.get(key) === chained) {
          this.roomLocks.delete(key);
        }
      });
  }

  _generateToken() {
    return crypto.randomBytes(32).toString('base64url');
  }

  _generateInviteCode() {
    for (let attempt = 0; attempt < 32; attempt += 1) {
      const code = String(crypto.randomInt(0, 1_000_000)).padStart(INVITE_LENGTH, '0');
      if (!this.inviteIndex.has(code)) return code;
    }
    throw new CloudStoreError(500, 'INVITE_GENERATION_FAILED', '无法生成邀请码，请重试');
  }

  _createInvite(room, nowMs) {
    if (room.invite) {
      this.inviteIndex.delete(room.invite.code);
    }
    const code = this._generateInviteCode();
    room.invite = {
      code,
      createdAtMs: nowMs,
      expiresAtMs: nowMs + this.inviteTtlMs
    };
    this.inviteIndex.set(code, room.roomId);
    return room.invite;
  }

  _clearInvite(room) {
    if (room.invite) {
      this.inviteIndex.delete(room.invite.code);
      room.invite = null;
    }
  }

  _issueSession(room, member, nowMs) {
    this._revokeDeviceSessions(room, member.deviceId);
    const token = this._generateToken();
    const expiresAtMs = nowMs + this.sessionTtlMs;
    const record = {
      token,
      roomId: room.roomId,
      deviceId: member.deviceId,
      runtimeId: member.runtimeId,
      incarnation: member.incarnation,
      controlGeneration: member.controlGeneration,
      issuedAtMs: nowMs,
      expiresAtMs,
      admissionConfirmed: false,
      revoked: false
    };
    this.sessions.set(token, record);
    room.activeTokens.set(member.deviceId, token);
    return record;
  }

  _canIssueRtc(room, member) {
    if (!room || room.ended || !member || member.status !== 'ADMITTED') return false;
    if (room.mode === 'HYBRID') {
      return room.transport?.active === 'CLOUD';
    }
    return true;
  }

  _rtcRoomIdForChannel(room, channel, epoch = room.transport?.epoch ?? 1) {
    if (room.mode !== 'HYBRID') return room.rtcRoomId;
    const prefix = `${room.rtcRoomId}_e${epoch}`;
    if (channel?.type === 'RIDER') return `${prefix}_rider`;
    if (channel?.type === 'PRIVATE' && channel.sessionId) {
      return `${prefix}_p_${channel.sessionId.replaceAll('-', '').slice(0, 12)}`;
    }
    return `${prefix}_pub`;
  }

  _rtcRoomIdForMember(room, member) {
    if (!this._canIssueRtc(room, member)) return null;
    return this._rtcRoomIdForChannel(room, member.channel, room.transport?.epoch ?? 1);
  }

  _countActiveSponsoredRooms(entitlementId) {
    if (!entitlementId) return 0;
    let count = this.pendingCreateByEntitlement.get(entitlementId) ?? 0;
    for (const room of this.rooms.values()) {
      if (!room.ended && room.sponsorEntitlementId === entitlementId) {
        count += 1;
      }
    }
    return count;
  }

  _countChannelAdmittedMembers(room, channel) {
    const key = formatChannel(channel);
    let count = 0;
    for (const item of room.members.values()) {
      if (item.status === 'ADMITTED' && formatChannel(item.channel) === key) {
        count += 1;
      }
    }
    return count;
  }

  _countActiveCloudRtcMembers(room) {
    if (!room || room.ended) return 0;
    if (room.mode === 'HYBRID' && room.transport?.active !== 'CLOUD') return 0;
    const perChannel = new Map();
    for (const item of room.members.values()) {
      if (item.status !== 'ADMITTED') continue;
      const key = formatChannel(item.channel);
      perChannel.set(key, (perChannel.get(key) ?? 0) + 1);
    }
    let total = 0;
    for (const count of perChannel.values()) {
      if (count >= 2) total += count;
    }
    return total;
  }

  _assertSponsorQuotaAvailable(room, nowMs) {
    if (!this.entitlementStore || !room?.sponsorEntitlementId) return null;
    const quota = this.entitlementStore.checkSponsorQuota(room.sponsorEntitlementId, nowMs);
    const pendingSec = room.billing?.pendingSeconds ?? 0;
    const effectiveRemainingSeconds = Math.max(0, quota.effectiveRemainingSeconds - pendingSec);
    if (!quota.allowed || effectiveRemainingSeconds <= 0) {
      throw new CloudStoreError(
        quota.allowed ? 402 : quota.status,
        quota.allowed ? 'QUOTA_EXHAUSTED' : quota.code,
        quota.message || '房主云端对讲额度已用尽，请兑换新激活码或改用本地模式'
      );
    }
    return { ...quota, effectiveRemainingSeconds };
  }

  _settleRoomBilling(room, nowMs, { forceFlush = false, reason = 'SWEEP', emitWarnings = true } = {}) {
    if (!this.entitlementStore || !room?.sponsorEntitlementId || !room.billing || room.ended) {
      return { terminate: null, effectiveRemainingSeconds: null };
    }
    const billing = room.billing;
    const elapsedMs = Math.max(0, nowMs - billing.lastSettledAtMs);
    const wholeSeconds = Math.floor(elapsedMs / 1000);
    const activeMembers = this._countActiveCloudRtcMembers(room);

    if (wholeSeconds > 0) {
      billing.lastSettledAtMs += wholeSeconds * 1000;
      if (activeMembers >= 2) {
        const delta = activeMembers * wholeSeconds;
        billing.pendingSeconds += delta;
        billing.totalBilledSeconds += delta;
        billing.peakRtcMembers = Math.max(billing.peakRtcMembers, activeMembers);
        if (billing.cloudSessionStartedAtMs === null) {
          billing.cloudSessionStartedAtMs = nowMs - wholeSeconds * 1000;
        }
      } else {
        billing.cloudSessionStartedAtMs = null;
        billing.sessionLimitWarned = false;
      }
    }

    const flushPending = (flushReason) => {
      const toBill = billing.pendingSeconds;
      billing.pendingSeconds = 0;
      billing.lastFlushedAtMs = nowMs;
      const peak = Math.max(2, billing.peakRtcMembers);
      billing.peakRtcMembers = activeMembers;
      return this.entitlementStore.deductUsage(
        {
          entitlementId: room.sponsorEntitlementId,
          roomId: room.roomId,
          mode: room.mode,
          deltaSeconds: toBill,
          peakRtcMembers: peak,
          reason: flushReason
        },
        nowMs
      );
    };

    const shouldFlush =
      billing.pendingSeconds > 0 &&
      (forceFlush ||
        billing.pendingSeconds >= this.billingFlushSecondsThreshold ||
        nowMs - billing.lastFlushedAtMs >= this.billingFlushIntervalMs);
    let quota = shouldFlush
      ? flushPending(reason)
      : this.entitlementStore.checkSponsorQuota(room.sponsorEntitlementId, nowMs);

    let effectiveRemainingSeconds = Math.max(0, quota.effectiveRemainingSeconds - billing.pendingSeconds);
    if (!quota.allowed || effectiveRemainingSeconds <= 0) {
      if (billing.pendingSeconds > 0) {
        quota = flushPending(`${reason}_EXHAUSTED`);
        effectiveRemainingSeconds = Math.max(0, quota.effectiveRemainingSeconds);
      }
      return {
        terminate: {
          reason: quota.reason || 'QUOTA_EXHAUSTED',
          message: quota.message || '房主云端对讲额度已用尽，通话已结束'
        },
        effectiveRemainingSeconds: 0
      };
    }

    const maxSessionSec = Math.max(60, (room.maxSessionMinutes || 120) * 60);
    if (billing.cloudSessionStartedAtMs !== null && activeMembers >= 2) {
      const sessionElapsedSec = Math.floor((nowMs - billing.cloudSessionStartedAtMs) / 1000);
      if (sessionElapsedSec >= maxSessionSec) {
        if (billing.pendingSeconds > 0) {
          flushPending(`${reason}_SESSION_LIMIT`);
        }
        return {
          terminate: {
            reason: 'SESSION_TIME_LIMIT',
            message: '单次云端通话已达防挂机时长上限，房间已自动结束'
          },
          effectiveRemainingSeconds
        };
      }
      if (
        emitWarnings &&
        !billing.sessionLimitWarned &&
        maxSessionSec - sessionElapsedSec <= this.sessionLimitWarnSeconds &&
        room.ownerId &&
        room.members.has(room.ownerId)
      ) {
        billing.sessionLimitWarned = true;
        const leftMin = Math.max(1, Math.ceil((maxSessionSec - sessionElapsedSec) / 60));
        this._pushEvent(room, room.ownerId, {
          type: 'QUOTA_WARNING',
          revision: room.revision,
          warningType: 'SESSION_LIMIT',
          remainingMinutes: leftMin,
          message: `本次云端通话将在约 ${leftMin} 分钟后达到防挂机时长上限`
        });
      }
    }

    if (
      emitWarnings &&
      !billing.lowQuotaWarned &&
      effectiveRemainingSeconds <= this.lowQuotaWarnSeconds &&
      room.ownerId &&
      room.members.has(room.ownerId)
    ) {
      billing.lowQuotaWarned = true;
      const leftMin = Math.max(1, Math.ceil(effectiveRemainingSeconds / 60));
      this._pushEvent(room, room.ownerId, {
        type: 'QUOTA_WARNING',
        revision: room.revision,
        warningType: 'QUOTA_LOW',
        remainingMinutes: leftMin,
        message: `云端对讲剩余额度约 ${leftMin} 人·分钟，请留意额度消耗`
      });
    }

    return { terminate: null, effectiveRemainingSeconds };
  }

  _assertRtcRevocationReady() {
    if (!this.rtcRevocationConfigured || !this.onRtcBanUser || !this.onRtcBanRoom || !this.onRtcUnbanUser) {
      throw new CloudStoreError(
        503,
        'RTC_REVOCATION_UNAVAILABLE',
        '服务端未配置 RTC 权威吊销能力（OpenAPI），禁止签发或变更云端语音凭证'
      );
    }
  }

  _forbiddenSecondsForExpiry(expiresAtMs, nowMs, floorSec = 10) {
    const remainingSec = Math.ceil(Math.max(0, expiresAtMs - nowMs) / 1000) + 15;
    return Math.max(10, floorSec, remainingSec);
  }

  _isOwnerRecoveryExpired(room, nowMs) {
    const owner = room?.members?.get(room.ownerId);
    return Boolean(
      owner &&
      owner.status === 'RESERVED' &&
      owner.reservedUntilMs !== null &&
      owner.reservedUntilMs <= nowMs
    );
  }

  _clearRtcRoomGrantRecords(room, rtcRoomId) {
    for (const [key, grant] of [...room.issuedRtcGrants.entries()]) {
      if (grant.rtcRoomId === rtcRoomId) room.issuedRtcGrants.delete(key);
    }
    for (const key of [...room.bannedRtcPairs.keys()]) {
      if (key.startsWith(`${rtcRoomId}\0`)) room.bannedRtcPairs.delete(key);
    }
  }

  _revocationRetryDelayMs(attempts) {
    if (this.revocationRetryCooldownMs <= 0) return 0;
    const exponent = Math.max(0, Math.min(10, Math.max(1, Number(attempts) || 1) - 1));
    return Math.min(this.maxRevocationRetryCooldownMs, this.revocationRetryCooldownMs * (2 ** exponent));
  }

  _isPendingRetryDue(pending, nowMs) {
    if (!pending) return true;
    return nowMs >= (pending.nextRetryAtMs ?? 0);
  }

  _isSweepKeyRetryDue(room, key, nowMs) {
    const record = room.sweepRetryAfterByKey?.get(key);
    return !record || nowMs >= record.nextRetryAtMs;
  }

  _recordSweepKeyRetryFailure(room, key, nowMs) {
    if (!room.sweepRetryAfterByKey) room.sweepRetryAfterByKey = new Map();
    const prev = room.sweepRetryAfterByKey.get(key);
    const attempts = (prev?.attempts ?? 0) + 1;
    const failedAtMs = Math.max(nowMs, this.now());
    room.sweepRetryAfterByKey.set(key, {
      attempts,
      nextRetryAtMs: failedAtMs + this._revocationRetryDelayMs(attempts),
      updatedAtMs: failedAtMs
    });
  }

  _clearSweepKeyRetry(room, key) {
    room.sweepRetryAfterByKey?.delete(key);
  }

  _recordPendingEnd(room, reason, message, nowMs, { failed = false } = {}) {
    const prev = room.pendingEnd;
    const effectiveReason = prev?.reason ?? reason;
    const effectiveMessage = prev?.message ?? message;
    const attempts = failed ? (prev?.attempts ?? 0) + 1 : (prev?.attempts ?? 0);
    const failedAtMs = Math.max(nowMs, this.now());
    room.pendingEnd = {
      reason: effectiveReason,
      message: effectiveMessage,
      requestedAtMs: prev?.requestedAtMs ?? nowMs,
      attempts,
      nextRetryAtMs: failed ? failedAtMs + this._revocationRetryDelayMs(attempts) : (prev?.nextRetryAtMs ?? 0),
      updatedAtMs: failedAtMs
    };
    return room.pendingEnd;
  }

  _recordPendingEpochRevocation(room, details, nowMs) {
    const prev = room.pendingEpochRevocation?.epoch === details.epoch ? room.pendingEpochRevocation : null;
    const attempts = (prev?.attempts ?? 0) + 1;
    const failedAtMs = Math.max(nowMs, this.now());
    room.pendingEpochRevocation = {
      epoch: details.epoch,
      intent: details.intent ?? prev?.intent ?? 'MANUAL_LOCAL',
      revokeReason: details.revokeReason ?? prev?.revokeReason ?? 'SWITCHED_TO_LOCAL',
      transportReason: details.transportReason ?? prev?.transportReason ?? 'MANUAL_LOCAL',
      nextLocalHostDeviceId:
        details.nextLocalHostDeviceId !== undefined
          ? details.nextLocalHostDeviceId
          : (prev?.nextLocalHostDeviceId ?? null),
      preservePreconnect:
        details.preservePreconnect !== undefined
          ? Boolean(details.preservePreconnect)
          : Boolean(prev?.preservePreconnect),
      advanceBackoff:
        details.advanceBackoff !== undefined
          ? Boolean(details.advanceBackoff)
          : Boolean(prev?.advanceBackoff),
      requestedAtMs: prev?.requestedAtMs ?? nowMs,
      attempts,
      nextRetryAtMs: failedAtMs + this._revocationRetryDelayMs(attempts),
      updatedAtMs: failedAtMs
    };
    return room.pendingEpochRevocation;
  }

  _updatePendingEpochIntentPreservingBackoff(room, details) {
    const prev = room.pendingEpochRevocation?.epoch === details.epoch ? room.pendingEpochRevocation : null;
    if (!prev) return null;
    room.pendingEpochRevocation = {
      ...prev,
      intent: details.intent ?? prev.intent,
      revokeReason: details.revokeReason ?? prev.revokeReason,
      transportReason: details.transportReason ?? prev.transportReason,
      nextLocalHostDeviceId:
        details.nextLocalHostDeviceId !== undefined
          ? details.nextLocalHostDeviceId
          : prev.nextLocalHostDeviceId,
      preservePreconnect:
        details.preservePreconnect !== undefined
          ? Boolean(details.preservePreconnect)
          : prev.preservePreconnect,
      advanceBackoff:
        details.advanceBackoff !== undefined
          ? Boolean(details.advanceBackoff)
          : prev.advanceBackoff
    };
    return room.pendingEpochRevocation;
  }

  _bumpPendingEpochRetry(room, epoch, nowMs) {
    const prev = room.pendingEpochRevocation?.epoch === epoch ? room.pendingEpochRevocation : null;
    return this._recordPendingEpochRevocation(
      room,
      {
        epoch,
        intent: prev?.intent ?? (room.transport?.recoveryStage === 'PRECONNECTING' ? 'PRECONNECT_LOCAL' : 'MANUAL_LOCAL'),
        revokeReason: prev?.revokeReason ?? 'SWITCHED_TO_LOCAL',
        transportReason: prev?.transportReason ?? 'MANUAL_LOCAL',
        nextLocalHostDeviceId: prev?.nextLocalHostDeviceId ?? null,
        preservePreconnect: prev?.preservePreconnect ?? false,
        advanceBackoff: prev?.advanceBackoff ?? false
      },
      nowMs
    );
  }

  _recordPendingPrivateRevocation(room, rtcRoomId, deviceId, reason, nowMs) {
    if (!rtcRoomId || !room.transport) return;
    if (!room.pendingPrivateRevocations) room.pendingPrivateRevocations = new Map();
    const prev = room.pendingPrivateRevocations.get(rtcRoomId);
    const attempts = (prev?.attempts ?? 0) + 1;
    const failedAtMs = Math.max(nowMs, this.now());
    room.pendingPrivateRevocations.set(rtcRoomId, {
      rtcRoomId,
      epoch: room.transport.epoch,
      deviceId: prev?.deviceId ?? deviceId,
      reason: prev?.reason ?? reason,
      requestedAtMs: prev?.requestedAtMs ?? nowMs,
      attempts,
      nextRetryAtMs: failedAtMs + this._revocationRetryDelayMs(attempts),
      updatedAtMs: failedAtMs
    });
  }

  _isBlockingRevocationRecord(record) {
    return Boolean(record && (record.status === 'REVOKED' || record.status === 'FAILED'));
  }

  _hasPendingEpochRevocation(room, epoch) {
    if (!room || room.ended) return false;
    if (room.pendingEpochRevocation?.epoch === epoch) return true;
    if (!room?.rtcRoomRevocations) return false;
    for (const record of room.rtcRoomRevocations.values()) {
      if (record.epoch === epoch && record.scope === 'EPOCH' && this._isBlockingRevocationRecord(record)) {
        return true;
      }
    }
    return false;
  }

  _hasPendingRoomRevocation(room) {
    if (!room || room.ended) return false;
    if (room.pendingEnd) return true;
    if (!room?.rtcRoomRevocations) return false;
    for (const record of room.rtcRoomRevocations.values()) {
      if (record.scope === 'ROOM' && this._isBlockingRevocationRecord(record)) {
        return true;
      }
    }
    return false;
  }

  _assertRoomNotPendingEnd(room, nowMs = this.now()) {
    if (this._isOwnerRecoveryExpired(room, nowMs) || this._hasPendingRoomRevocation(room)) {
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '房间正在等待云端语音吊销结束，请稍后重试');
    }
  }

  _assertRtcRoomIssuable(room, rtcRoomId, { epoch = undefined } = {}) {
    if (!rtcRoomId) return;
    if (this._hasPendingRoomRevocation(room)) {
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '房间正在等待云端语音吊销结束，请稍后重试');
    }
    const targetEpoch = epoch ?? (room.mode === 'HYBRID' ? room.transport?.epoch : 1);
    if (room.mode === 'HYBRID' && Number.isInteger(targetEpoch) && this._hasPendingEpochRevocation(room, targetEpoch)) {
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '当前传输纪元正在切回本地并吊销云端语音，请等待切换完成');
    }
    const revocation = room.rtcRoomRevocations?.get(rtcRoomId);
    if (this._isBlockingRevocationRecord(revocation)) {
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '当前云端语音频道已吊销或吊销状态未确认，禁止继续签发凭证');
    }
  }

  async _unbanUserFromRtcRoom(room, rtcRoomId, userId, nowMs) {
    const pairKey = `${rtcRoomId}\0${userId}`;
    const record = room.bannedRtcPairs.get(pairKey);
    if (!record) return;
    if (record.bannedUntilMs <= nowMs) {
      room.bannedRtcPairs.delete(pairKey);
      return;
    }
    this._assertRtcRevocationReady();
    try {
      await this.onRtcUnbanUser(rtcRoomId, userId, { reason: 'AUTHORIZED_REENTRY' });
    } catch (error) {
      this.log({ event: 'rtc_unban_user_failed', roomId: room.roomId.slice(-8), message: error?.message ?? 'unknown' });
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '云端语音权限同步失败，请稍后重试');
    }
    room.bannedRtcPairs.delete(pairKey);
  }

  _mintAndRecordRtcCredentialsSync(room, member, rtcRoomId, quota, nowMs) {
    const userId = member.rtcUserId;
    let effectiveTtlMs = this.rtcTokenTtlMs;
    if (quota && Number.isFinite(quota.effectiveRemainingSeconds)) {
      const estimatedMembers = Math.max(2, this._countChannelAdmittedMembers(room, member.channel));
      const affordableMs = Math.floor((quota.effectiveRemainingSeconds / estimatedMembers) * 1000);
      const minSupportedTtl = Math.min(60_000, this.rtcTokenTtlMs);
      effectiveTtlMs = Math.max(minSupportedTtl, Math.min(this.rtcTokenTtlMs, affordableMs));
    }
    const credentials = mintRtcCredentials({
      appId: this.appId,
      appKey: this.appKey,
      rtcRoomId,
      rtcUserId: userId,
      nowMs,
      ttlMs: effectiveTtlMs
    });
    const grantKey = `${rtcRoomId}\0${userId}`;
    const prevGrant = room.issuedRtcGrants.get(grantKey);
    const epoch = room.mode === 'HYBRID' ? (room.transport?.epoch ?? 1) : 1;
    room.issuedRtcGrants.set(grantKey, {
      rtcRoomId,
      userId,
      expiresAtMs: Math.max(prevGrant?.expiresAtMs ?? 0, credentials.expiresAtMs),
      epoch
    });
    const prevRoom = room.issuedRtcRoomIds.get(rtcRoomId);
    room.issuedRtcRoomIds.set(rtcRoomId, {
      epoch,
      maxExpiresAtMs: Math.max(prevRoom?.maxExpiresAtMs ?? 0, credentials.expiresAtMs)
    });
    return credentials;
  }

  async _issueRtcCredentialsIfAllowed(room, member, nowMs) {
    if (this._isOwnerRecoveryExpired(room, nowMs)) {
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '群主恢复已超时，房间正在等待吊销结束');
    }
    if (!this._canIssueRtc(room, member)) return null;
    this._assertSponsorQuotaAvailable(room, nowMs);
    this._assertRtcRevocationReady();
    const rtcRoomId = this._rtcRoomIdForMember(room, member);
    this._assertRtcRoomIssuable(room, rtcRoomId);
    const userId = member.rtcUserId;
    await this._unbanUserFromRtcRoom(room, rtcRoomId, userId, nowMs);
    const postNowMs = Math.max(nowMs, this.now());
    this._assertRoomNotPendingEnd(room, postNowMs);
    if (!this._canIssueRtc(room, member)) return null;
    const finalQuota = this._assertSponsorQuotaAvailable(room, postNowMs);
    this._assertRtcRoomIssuable(room, rtcRoomId);
    return this._mintAndRecordRtcCredentialsSync(room, member, rtcRoomId, finalQuota, postNowMs);
  }

  async _revokeUserFromRtcRoom(room, rtcRoomId, deviceId, reason, nowMs, floorSec = 10) {
    const userId = room.members.get(deviceId)?.rtcUserId;
    if (!userId) return;
    if (!rtcRoomId) return;
    const grantKey = `${rtcRoomId}\0${userId}`;
    const grant = room.issuedRtcGrants.get(grantKey);
    if (!grant || grant.expiresAtMs <= nowMs) {
      if (grant) room.issuedRtcGrants.delete(grantKey);
      return;
    }
    this._assertRtcRevocationReady();
    const forbiddenIntervalSec = this._forbiddenSecondsForExpiry(grant.expiresAtMs, nowMs, floorSec);
    try {
      await this.onRtcBanUser(rtcRoomId, userId, { reason, forbiddenIntervalSec });
    } catch (error) {
      const prevBan = room.bannedRtcPairs.get(grantKey);
      room.bannedRtcPairs.set(grantKey, {
        rtcRoomId,
        userId,
        bannedUntilMs: Math.max(prevBan?.bannedUntilMs ?? 0, nowMs + forbiddenIntervalSec * 1000),
        grantExpiresAtMs: Math.max(prevBan?.grantExpiresAtMs ?? 0, grant.expiresAtMs)
      });
      this.log({ event: 'rtc_ban_user_failed', roomId: room.roomId.slice(-8), message: error?.message ?? 'unknown' });
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '无法立即吊销旧云端语音凭证，操作已中止，请重试');
    }
    room.issuedRtcGrants.delete(grantKey);
    const prevBan = room.bannedRtcPairs.get(grantKey);
    room.bannedRtcPairs.set(grantKey, {
      rtcRoomId,
      userId,
      bannedUntilMs: Math.max(prevBan?.bannedUntilMs ?? 0, nowMs + forbiddenIntervalSec * 1000),
      grantExpiresAtMs: Math.max(prevBan?.grantExpiresAtMs ?? 0, grant.expiresAtMs)
    });
  }

  async _revokeUserAllRtcGrants(room, deviceId, reason, nowMs, floorSec = this.banRemovedForbiddenSeconds) {
    const userId = room.members.get(deviceId)?.rtcUserId;
    const grants = [...room.issuedRtcGrants.values()].filter((g) => g.userId === userId);
    for (const grant of grants) {
      await this._revokeUserFromRtcRoom(room, grant.rtcRoomId, deviceId, reason, nowMs, floorSec);
    }
  }

  async _confirmUserRevokedAllRtcGrants(room, deviceId, reason, nowMs, floorSec = this.banRemovedForbiddenSeconds) {
    const userId = room.members.get(deviceId)?.rtcUserId;
    const justRevokedKeys = new Set(
      [...room.issuedRtcGrants.entries()]
        .filter(([, grant]) => grant.userId === userId && grant.expiresAtMs > nowMs)
        .map(([grantKey]) => grantKey)
    );
    await this._revokeUserAllRtcGrants(room, deviceId, reason, nowMs, floorSec);
    const priorBans = [...room.bannedRtcPairs.entries()].filter(
      ([grantKey, record]) =>
        !justRevokedKeys.has(grantKey) &&
        record.userId === userId &&
        record.grantExpiresAtMs > nowMs &&
        room.issuedRtcRoomIds.has(record.rtcRoomId)
    );
    for (const [grantKey, record] of priorBans) {
      this._assertRtcRevocationReady();
      const forbiddenIntervalSec = this._forbiddenSecondsForExpiry(record.grantExpiresAtMs, nowMs, floorSec);
      try {
        await this.onRtcBanUser(record.rtcRoomId, userId, { reason, forbiddenIntervalSec });
      } catch (error) {
        this.log({ event: 'rtc_ban_user_failed', roomId: room.roomId.slice(-8), message: error?.message ?? 'unknown' });
        throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '无法立即吊销旧云端语音凭证，操作已中止，请重试');
      }
      room.bannedRtcPairs.set(grantKey, {
        ...record,
        bannedUntilMs: Math.max(record.bannedUntilMs, nowMs + forbiddenIntervalSec * 1000)
      });
    }
  }

  async _revokeRtcRoom(
    room,
    rtcRoomId,
    reason,
    nowMs,
    floorSec = this.banRoomForbiddenSeconds,
    { scope = 'PRIVATE' } = {}
  ) {
    if (!rtcRoomId) return;
    const meta = room.issuedRtcRoomIds.get(rtcRoomId);
    if (!meta || meta.maxExpiresAtMs <= nowMs) {
      if (meta) {
        room.issuedRtcRoomIds.delete(rtcRoomId);
        this._clearRtcRoomGrantRecords(room, rtcRoomId);
        const existing = room.rtcRoomRevocations?.get(rtcRoomId);
        if (existing && existing.status !== 'REVOKED' && (existing.bannedUntilMs ?? 0) <= nowMs) {
          room.rtcRoomRevocations.delete(rtcRoomId);
        }
      }
      return;
    }
    this._assertRtcRevocationReady();
    const forbiddenIntervalSec = this._forbiddenSecondsForExpiry(meta.maxExpiresAtMs, nowMs, floorSec);
    try {
      await this.onRtcBanRoom(rtcRoomId, { reason, forbiddenIntervalSec });
    } catch (error) {
      const existing = room.rtcRoomRevocations?.get(rtcRoomId);
      if (existing?.status !== 'REVOKED') {
        room.rtcRoomRevocations.set(rtcRoomId, {
          rtcRoomId,
          epoch: meta.epoch,
          scope,
          reason,
          status: 'FAILED',
          bannedUntilMs: Math.max(existing?.bannedUntilMs ?? 0, nowMs + forbiddenIntervalSec * 1000),
          maxExpiresAtMs: Math.max(existing?.maxExpiresAtMs ?? 0, meta.maxExpiresAtMs),
          updatedAtMs: nowMs
        });
      }
      this.log({ event: 'rtc_ban_room_failed', roomId: room.roomId.slice(-8), message: error?.message ?? 'unknown' });
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '无法吊销云端语音房间，请稍后重试');
    }
    const bannedUntilMs = nowMs + forbiddenIntervalSec * 1000;
    const prevRevocation = room.rtcRoomRevocations?.get(rtcRoomId);
    room.rtcRoomRevocations.set(rtcRoomId, {
      rtcRoomId,
      epoch: meta.epoch,
      scope,
      reason,
      status: 'REVOKED',
      bannedUntilMs: Math.max(prevRevocation?.bannedUntilMs ?? 0, bannedUntilMs),
      maxExpiresAtMs: Math.max(prevRevocation?.maxExpiresAtMs ?? 0, meta.maxExpiresAtMs),
      updatedAtMs: nowMs
    });
    room.issuedRtcRoomIds.delete(rtcRoomId);
    this._clearRtcRoomGrantRecords(room, rtcRoomId);
  }

  async _revokeRoomRtcForEpoch(room, epoch, reason, nowMs) {
    const minFloorSec = Math.ceil(this.rtcTokenTtlMs / 1000) + 60;
    const targets = [...room.issuedRtcRoomIds.entries()]
      .filter(([, meta]) => meta.epoch === epoch)
      .map(([rtcRoomId]) => rtcRoomId);
    for (const rtcRoomId of targets) {
      await this._revokeRtcRoom(room, rtcRoomId, reason, nowMs, minFloorSec, { scope: 'EPOCH' });
    }
  }

  async _revokeAllRoomRtc(room, reason, nowMs) {
    const targets = [...room.issuedRtcRoomIds.keys()];
    for (const rtcRoomId of targets) {
      await this._revokeRtcRoom(room, rtcRoomId, reason, nowMs, this.banRoomForbiddenSeconds, { scope: 'ROOM' });
    }
  }

  _ensureHybridRoom(room) {
    if (room.mode !== 'HYBRID') {
      throw new CloudStoreError(400, 'UNSUPPORTED_IN_MODE', '仅双模房间支持该操作');
    }
  }

  _clearPrivateStateForMember(room, deviceId) {
    let changed = false;
    for (const [inviteId, invite] of [...room.privateInvites.entries()]) {
      if (invite.callerId === deviceId || invite.calleeId === deviceId) {
        room.privateInvites.delete(inviteId);
        changed = true;
      }
    }
    const target = room.members.get(deviceId);
    const sessionId = target?.channel?.type === 'PRIVATE' ? target.channel.sessionId : null;
    if (sessionId) {
      for (const candidate of room.members.values()) {
        if (candidate.channel?.type === 'PRIVATE' && candidate.channel.sessionId === sessionId) {
          candidate.channel = { type: 'PUBLIC' };
          candidate.channelRevision += 1;
          candidate.audioAvailable = false;
          changed = true;
        }
      }
      if (room.transport && room.rtcRoomRevocations) {
        const privateRtcRoom = this._rtcRoomIdForChannel(room, { type: 'PRIVATE', sessionId }, room.transport.epoch);
        if (room.rtcRoomRevocations.get(privateRtcRoom)?.scope === 'PRIVATE') {
          room.rtcRoomRevocations.delete(privateRtcRoom);
        }
        room.pendingPrivateRevocations?.delete(privateRtcRoom);
      }
    }
    return changed;
  }

  _clearPrivateStateForPrivateRtcRoom(room, privateRtcRoom) {
    if (!room.transport || !privateRtcRoom) return false;
    let changed = false;
    for (const member of room.members.values()) {
      if (
        member.channel?.type === 'PRIVATE' &&
        this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch) === privateRtcRoom
      ) {
        if (this._clearPrivateStateForMember(room, member.deviceId)) {
          changed = true;
        }
        break;
      }
    }
    if (room.rtcRoomRevocations?.get(privateRtcRoom)?.scope === 'PRIVATE') {
      room.rtcRoomRevocations.delete(privateRtcRoom);
    }
    room.pendingPrivateRevocations?.delete(privateRtcRoom);
    return changed;
  }

  _resolveAdmittedLocalHost(room) {
    if (!room?.transport) return null;
    const candidateHost = room.members.get(room.transport.localHostDeviceId);
    if (candidateHost?.status === 'ADMITTED') return candidateHost;
    const owner = room.members.get(room.ownerId);
    if (owner?.status === 'ADMITTED') return owner;
    return null;
  }

  _rotateRouteACredentials(transport) {
    transport.localP2pSsid = generateLocalP2pSsid(transport.localP2pSsid);
    transport.localP2pPassphrase = generateLocalP2pPassphrase(transport.localP2pPassphrase);
    transport.localHostAddress = null;
    transport.localFrequencyMhz = null;
  }

  _abortHybridPreconnect(room, nowMs, { advanceBackoff = false, reason = 'PRECONNECT_ABORTED' } = {}) {
    if (room.mode !== 'HYBRID' || !room.transport || room.transport.active !== 'CLOUD') return false;
    const transport = room.transport;
    const wasActive = transport.recoveryStage !== 'IDLE' || transport.recoverySignalStableSinceMs !== null || transport.preconnectStartedAtMs !== null;
    transport.recoveryStage = 'IDLE';
    transport.recoverySignalStableSinceMs = null;
    transport.preconnectStartedAtMs = null;
    transport.localCode = null;
    this._rotateRouteACredentials(transport);
    for (const item of room.members.values()) {
      item.localStatus = null;
    }
    if (advanceBackoff) {
      transport.fallbackStreak += 1;
      const backoff = Math.min(
        this.maxTransportCooldownMs,
        this.transportCooldownMs * (2 ** (transport.fallbackStreak - 1))
      );
      transport.cooldownUntilMs = nowMs + backoff;
    }
    if (wasActive || advanceBackoff) {
      this.log({
        event: 'hybrid_preconnect_aborted',
        roomId: room.roomId.slice(-8),
        epoch: transport.epoch,
        reason,
        advanceBackoff: Boolean(advanceBackoff)
      });
    }
    return wasActive || advanceBackoff;
  }

  _transitionTransport(room, targetActive, reason, nowMs, options = {}) {
    if (room.mode !== 'HYBRID' || !room.transport) return false;
    this._settleRoomBilling(room, nowMs, { forceFlush: true, reason: `TRANSPORT_${targetActive}`, emitWarnings: false });
    const transport = room.transport;
    const previous = transport.active;
    const previousEpoch = transport.epoch;
    room.pendingEpochRevocation = null;
    room.pendingPrivateRevocations?.clear();
    transport.active = targetActive;
    transport.epoch += 1;
    transport.reason = reason;
    transport.changedAtMs = nowMs;
    transport.localStableSinceMs = null;
    transport.recoveryStage = 'IDLE';
    transport.recoverySignalStableSinceMs = null;
    transport.preconnectStartedAtMs = null;
    for (const item of room.members.values()) {
      item.localStatus = null;
      item.audioAvailable = false;
    }
    if (targetActive === 'CLOUD') {
      transport.localCode = null;
      transport.handoverFromEpoch = null;
      this._rotateRouteACredentials(transport);
      if (options.nextLocalHostDeviceId) {
        transport.localHostDeviceId = options.nextLocalHostDeviceId;
      }
      if (FALLBACK_TRANSPORT_REASONS.has(reason) && options.advanceBackoff !== false) {
        transport.fallbackStreak += 1;
        const backoff = Math.min(
          this.maxTransportCooldownMs,
          this.transportCooldownMs * (2 ** (transport.fallbackStreak - 1))
        );
        transport.cooldownUntilMs = nowMs + backoff;
      } else {
        transport.cooldownUntilMs = nowMs + this.transportCooldownMs;
      }
    } else {
      let hostId = options.nextLocalHostDeviceId ?? transport.localHostDeviceId;
      const hostMember = hostId ? room.members.get(hostId) : null;
      if (!hostMember || hostMember.status !== 'ADMITTED') {
        hostId = this._resolveAdmittedLocalHost(room)?.deviceId ?? room.ownerId;
      }
      transport.localHostDeviceId = hostId;
      if (options.preservePreconnect && transport.localCode && transport.localP2pSsid && transport.localP2pPassphrase) {
        transport.handoverFromEpoch = previousEpoch;
      } else {
        transport.localCode = generateLocalCode(transport.lastLocalCode);
        this._rotateRouteACredentials(transport);
        transport.handoverFromEpoch = null;
      }
      transport.lastLocalCode = transport.localCode;
      transport.cooldownUntilMs = 0;
    }
    this.log({
      event: 'hybrid_transport_switched',
      roomId: room.roomId.slice(-8),
      from: previous,
      to: targetActive,
      epoch: transport.epoch,
      reason
    });
    return true;
  }

  _cloudRecoveryReason(room) {
    return room.transport?.reason === 'MANUAL_CLOUD' ? 'MANUAL_CLOUD' : 'LOCAL_UNAVAILABLE';
  }

  _rotatePartialEpochToFreshCloud(room, nowMs, { nextLocalHostDeviceId = undefined, advanceBackoff = false } = {}) {
    this._transitionTransport(room, 'CLOUD', this._cloudRecoveryReason(room), nowMs, {
      nextLocalHostDeviceId,
      advanceBackoff
    });
  }

  _handleHybridLocalHostDeparture(room, departingDeviceId, nowMs) {
    if (room.mode !== 'HYBRID' || !room.transport || room.ended) return false;
    if (room.transport.localHostDeviceId !== departingDeviceId) return false;
    const nextHost = room.ownerId;
    const admittedCount = [...room.members.values()].filter((m) => m.status === 'ADMITTED').length;
    if (room.transport.active === 'LOCAL') {
      if (admittedCount >= 2) {
        this._transitionTransport(room, 'CLOUD', 'LOCAL_HOST_LOST', nowMs, {
          nextLocalHostDeviceId: nextHost
        });
      } else {
        room.transport.localHostDeviceId = nextHost;
        room.transport.epoch += 1;
        room.transport.localCode = generateLocalCode(room.transport.lastLocalCode);
        room.transport.lastLocalCode = room.transport.localCode;
        this._rotateRouteACredentials(room.transport);
        room.transport.handoverFromEpoch = null;
        room.transport.reason = 'LOCAL_HOST_REASSIGNED';
        room.transport.changedAtMs = nowMs;
      }
      return true;
    }
    room.transport.localHostDeviceId = nextHost;
    this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'LOCAL_HOST_REASSIGNED' });
    return true;
  }

  _revokeDeviceSessions(room, deviceId, terminal = null, nowMs = this.now()) {
    const existing = room.activeTokens.get(deviceId);
    if (existing) {
      const rec = this.sessions.get(existing);
      if (rec) rec.revoked = true;
      const admission = this.pendingIndex.get(rec?.requestId);
      if (admission?.resolution?.sessionToken === existing) {
        admission.status = terminal?.code === 'REMOVED' ? 'REJECTED' : 'ENDED';
        admission.resolution = {
          status: admission.status,
          code: terminal?.code ?? 'SESSION_ENDED',
          message: terminal?.message ?? '原入房会话已结束'
        };
        admission.expiresAtMs = Math.min(admission.expiresAtMs, nowMs + TERMINAL_NOTIFICATION_RETENTION_MS);
      }
      this.sessions.delete(existing);
      room.activeTokens.delete(deviceId);
      if (terminal) {
        this.revokedTokens.set(existing, {
          ...terminal,
          expiresAtMs: nowMs + 5 * 60_000
        });
      }
    }
  }

  _ensureMailbox(room, deviceId) {
    let box = room.mailboxes.get(deviceId);
    if (!box) {
      box = {
        latestSeq: 0,
        minSeq: 1,
        events: [],
        waiters: new Set()
      };
      room.mailboxes.set(deviceId, box);
    }
    return box;
  }

  _pushEvent(room, deviceId, event) {
    const box = this._ensureMailbox(room, deviceId);
    const seq = ++box.latestSeq;
    const envelope = { seq, ...event };
    box.events.push(envelope);
    while (box.events.length > MAX_EVENT_WINDOW) {
      box.events.shift();
      box.minSeq = box.events[0].seq;
    }
    if (box.events.length > 0 && box.minSeq > box.events[0].seq) {
      box.minSeq = box.events[0].seq;
    }
    const waiters = [...box.waiters];
    box.waiters.clear();
    for (const wake of waiters) {
      wake();
    }
    return envelope;
  }

  _wakeRequestWaiters(pending, payload) {
    const waiters = [...pending.waiters];
    pending.waiters.clear();
    for (const wake of waiters) {
      wake(payload);
    }
  }

  _snapshotFor(room, viewerDeviceId) {
    const isOwner = !room.ended && viewerDeviceId != null && room.ownerId === viewerDeviceId;
    const viewerMember = viewerDeviceId != null ? room.members.get(viewerDeviceId) : null;
    const viewerAdmitted = !room.ended && viewerMember?.status === 'ADMITTED';
    const isHybrid = room.mode === 'HYBRID';
    const members = [...room.members.values()]
      .sort((a, b) => {
        if (a.deviceId === room.ownerId) return -1;
        if (b.deviceId === room.ownerId) return 1;
        return a.deviceId < b.deviceId ? -1 : 1;
      })
      .map((m) => ({
        deviceId: m.deviceId,
        runtimeId: m.runtimeId,
        rtcUserId: m.rtcUserId,
        ...(isHybrid ? { supportsFastP2p: m.supportsFastP2p === true } : {}),
        nickname: m.nickname,
        role: m.role,
        status: m.status,
        incarnation: m.incarnation,
        controlGeneration: m.controlGeneration,
        channel: isHybrid ? formatChannel(m.channel) : 'PUBLIC',
        channelRevision: isHybrid ? m.channelRevision : 1,
        audioAvailable: m.audioAvailable,
        reservedUntilMs: m.reservedUntilMs
      }));
    const pendingRequests = isOwner
      ? [...room.pendingRequests.values()]
          .filter((req) => req.status === 'PENDING')
          .sort((a, b) => a.createdAtMs - b.createdAtMs)
          .map((req) => ({
            requestId: req.requestId,
            deviceId: req.deviceId,
            nickname: req.nickname,
            createdAtMs: req.createdAtMs,
            expiresAtMs: req.expiresAtMs
          }))
      : [];
    const removed = isOwner
      ? [...room.removed.entries()].map(([deviceId, nickname]) => ({ deviceId, nickname }))
      : [];
    const invite = isOwner && room.invite
      ? {
          code: room.invite.code,
          expiresAtMs: room.invite.expiresAtMs
        }
      : null;
    const privateInvites = isHybrid && !room.ended
      ? [...room.privateInvites.values()]
          .sort((a, b) => a.expiresAtMs - b.expiresAtMs)
          .map((item) => ({
            inviteId: item.inviteId,
            callerId: item.callerId,
            calleeId: item.calleeId,
            expiresAtMs: item.expiresAtMs
          }))
      : [];
    let transport = null;
    if (isHybrid && room.transport) {
      const tr = room.transport;
      const isLocalHost = viewerAdmitted && tr.localHostDeviceId === viewerDeviceId;
      const autoRecoverableCloud = tr.active === 'CLOUD' && tr.reason !== 'MANUAL_CLOUD';
      const preconnecting = autoRecoverableCloud && tr.recoveryStage === 'PRECONNECTING';
      const exposeLocalCode = viewerAdmitted && (tr.active === 'LOCAL' || preconnecting);
      const usePreset = room.members.get(tr.localHostDeviceId)?.supportsFastP2p === true;
      const exposeP2pSsid = viewerAdmitted && usePreset && (tr.active === 'LOCAL' || autoRecoverableCloud);
      const exposeP2pPassphrase = viewerAdmitted && usePreset && (tr.active === 'LOCAL' || preconnecting || (autoRecoverableCloud && isLocalHost));
      const exposeHostNet = viewerAdmitted && (tr.active === 'LOCAL' || preconnecting || (autoRecoverableCloud && isLocalHost));
      transport = {
        active: tr.active,
        epoch: tr.epoch,
        reason: tr.reason,
        localHostDeviceId: room.ended ? '' : tr.localHostDeviceId,
        localRoomInstanceId: tr.localRoomInstanceId,
        localCode: exposeLocalCode ? tr.localCode : null,
        localP2pSsid: exposeP2pSsid ? tr.localP2pSsid : null,
        localP2pPassphrase: exposeP2pPassphrase ? tr.localP2pPassphrase : null,
        localControlPort: tr.localControlPort,
        localHostAddress: exposeHostNet ? tr.localHostAddress : null,
        localFrequencyMhz: exposeHostNet ? tr.localFrequencyMhz : null,
        recoveryStage: !room.ended && autoRecoverableCloud ? tr.recoveryStage : 'IDLE',
        handoverFromEpoch: !room.ended && tr.active === 'LOCAL' ? tr.handoverFromEpoch : null,
        changedAtMs: tr.changedAtMs,
        cooldownUntilMs: tr.cooldownUntilMs
      };
    }
    const hostDeviceId = isHybrid && !room.ended && room.transport?.active === 'LOCAL'
      ? room.transport.localHostDeviceId
      : null;
    const viewerRtcRoomId = isHybrid && viewerMember
      ? this._rtcRoomIdForMember(room, viewerMember)
      : (room.ended ? null : room.rtcRoomId);
    return {
      roomId: room.roomId,
      roomEpoch: room.roomEpoch,
      rtcRoomId: viewerRtcRoomId,
      mode: room.mode,
      channel: isHybrid && viewerMember ? formatChannel(viewerMember.channel) : 'PUBLIC',
      maxMembers: room.maxMembers,
      revision: room.revision,
      ownerId: room.ended ? '' : room.ownerId,
      hostDeviceId,
      ended: room.ended,
      endedReason: room.endedReason,
      requireApproval: room.requireApproval,
      riderIncludesFormation: isHybrid ? room.riderIncludesFormation : false,
      privateInvites,
      transport,
      invite,
      members,
      pendingRequests,
      removed
    };
  }

  _broadcastSnapshot(room) {
    for (const member of room.members.values()) {
      this._pushEvent(room, member.deviceId, {
        type: 'SNAPSHOT',
        revision: room.revision,
        snapshot: this._snapshotFor(room, member.deviceId)
      });
    }
  }

  _finalizeEndedRoom(room, reason, message, nowMs) {
    if (room.ended) return;
    const priorMemberIds = [...room.members.keys()];
    room.ended = true;
    room.endedReason = reason;
    room.endedAtMs = nowMs;
    room.revision += 1;
    this._clearInvite(room);
    for (const pending of room.pendingRequests.values()) {
      if (pending.status === 'PENDING') {
        pending.status = 'ENDED';
        pending.resolution = { status: 'ENDED', code: 'ROOM_ENDED', message };
        this._wakeRequestWaiters(pending, pending.resolution);
      }
    }
    room.pendingRequests.clear();
    room.members.clear();
    room.privateInvites.clear();
    room.pendingEnd = null;
    room.pendingEpochRevocation = null;
    room.pendingPrivateRevocations?.clear();
    room.sweepRetryAfterByKey?.clear();
    if (room.transport) {
      room.transport.localCode = null;
      room.transport.localP2pSsid = null;
      room.transport.localP2pPassphrase = null;
      room.transport.localHostAddress = null;
      room.transport.localFrequencyMhz = null;
      room.transport.recoveryStage = 'IDLE';
      room.transport.recoverySignalStableSinceMs = null;
      room.transport.preconnectStartedAtMs = null;
      room.transport.handoverFromEpoch = null;
      room.transport.localStableSinceMs = null;
    }
    for (const deviceId of priorMemberIds) {
      this._revokeDeviceSessions(
        room,
        deviceId,
        { status: 410, code: 'ROOM_ENDED', message, extra: { endedReason: reason } },
        nowMs
      );
      this._pushEvent(room, deviceId, {
        type: 'TERMINAL',
        revision: room.revision,
        reason,
        message,
        snapshot: this._snapshotFor(room, deviceId)
      });
    }
    this.log({ event: 'room_ended', roomId: room.roomId.slice(-8), reason, revision: room.revision, atMs: nowMs });
  }

  async _endRoom(room, reason, message, nowMs) {
    if (room.ended) return;
    this._settleRoomBilling(room, nowMs, { forceFlush: true, reason: `END_${reason}`, emitWarnings: false });
    const pending = this._recordPendingEnd(room, reason, message, nowMs, { failed: false });
    try {
      await this._revokeAllRoomRtc(room, pending.reason, nowMs);
    } catch (error) {
      this._recordPendingEnd(room, pending.reason, pending.message, nowMs, { failed: true });
      throw error;
    }
    room.pendingEnd = null;
    this._finalizeEndedRoom(room, pending.reason, pending.message, nowMs);
  }

  _assertExpectedRevision(room, expectedRevision) {
    if (!Number.isInteger(expectedRevision) || expectedRevision < 1) {
      throw new CloudStoreError(400, 'INVALID_REQUEST', '群主管理操作必须提供有效的整数 expectedRevision', {
        currentRevision: room.revision
      });
    }
    if (expectedRevision !== room.revision) {
      throw new CloudStoreError(409, 'REVISION_CONFLICT', '房间状态已更新，请重试', {
        currentRevision: room.revision
      });
    }
  }

  _sweepGlobalExpired(nowMs = this.now()) {
    for (const [id, expiresAtMs] of this.cancelledJoinReceipts) {
      if (expiresAtMs <= nowMs) this.cancelledJoinReceipts.delete(id);
    }
    for (const [token, tombstone] of [...this.revokedTokens.entries()]) {
      if (tombstone.expiresAtMs <= nowMs) {
        this.revokedTokens.delete(token);
      }
    }
    for (const [roomId, room] of this.rooms) {
      if (
        room.ended &&
        Number.isFinite(room.endedAtMs) &&
        room.endedAtMs + TERMINAL_NOTIFICATION_RETENTION_MS <= nowMs
      ) {
        this._clearInvite(room);
        room.mailboxes.clear();
        room.activeTokens.clear();
        room.pendingRequests.clear();
        this.rooms.delete(roomId);
      }
    }
    for (const [reqId, pending] of this.pendingIndex) {
      if (!this.rooms.has(pending.roomId) || (pending.expiresAtMs <= nowMs && pending.status !== 'PENDING')) {
        this.pendingIndex.delete(reqId);
      }
    }
  }

  async _sweepRoomUnlocked(
    room,
    nowMs = this.now(),
    {
      throwOnError = false,
      allowPendingRevocationRecovery = true,
      respectRetryCooldown = true
    } = {}
  ) {
    if (!room || room.ended) return { changed: false, errors: [] };
    const errors = [];

    const ownerRecoveryExpired = this._isOwnerRecoveryExpired(room, nowMs);
    const hasPendingRoomEnd = this._hasPendingRoomRevocation(room);
    if (ownerRecoveryExpired || hasPendingRoomEnd) {
      const isExistingPendingEnd = Boolean(room.pendingEnd) || hasPendingRoomEnd;
      const shouldAttemptEnd = isExistingPendingEnd
        ? allowPendingRevocationRecovery && (!respectRetryCooldown || this._isPendingRetryDue(room.pendingEnd, nowMs))
        : !respectRetryCooldown || this._isSweepKeyRetryDue(room, 'room:end', nowMs);
      if (shouldAttemptEnd) {
        const reason = room.pendingEnd?.reason ?? (ownerRecoveryExpired ? 'OWNER_RECOVERY_EXPIRED' : 'OWNER_ENDED');
        const message =
          room.pendingEnd?.message ??
          (ownerRecoveryExpired ? '群主断线超过 60 秒，网络房间已结束' : '群主已解散网络房间');
        try {
          await this._endRoom(room, reason, message, nowMs);
          this._clearSweepKeyRetry(room, 'room:end');
          return { changed: true, errors };
        } catch (error) {
          this._recordSweepKeyRetryFailure(room, 'room:end', nowMs);
          this.log({
            event: 'rtc_sweep_revocation_failed',
            roomId: room.roomId.slice(-8),
            action: reason,
            message: error?.message ?? 'unknown'
          });
          if (throwOnError) throw error;
          errors.push(error);
          return { changed: false, errors };
        }
      }
      return { changed: false, errors };
    }

    if (this.entitlementStore && room.sponsorEntitlementId) {
      const billingResult = this._settleRoomBilling(room, nowMs, { reason: 'SWEEP', emitWarnings: true });
      if (billingResult.terminate) {
        if (!respectRetryCooldown || this._isSweepKeyRetryDue(room, 'room:end', nowMs)) {
          try {
            await this._endRoom(room, billingResult.terminate.reason, billingResult.terminate.message, nowMs);
            this._clearSweepKeyRetry(room, 'room:end');
            return { changed: true, errors };
          } catch (error) {
            this._recordSweepKeyRetryFailure(room, 'room:end', nowMs);
            if (throwOnError) throw error;
            errors.push(error);
            return { changed: false, errors };
          }
        }
        return { changed: false, errors };
      }
    }

    let changed = false;

    if (room.invite && room.invite.expiresAtMs <= nowMs) {
      this._clearInvite(room);
      changed = true;
    }

    if (room.mode === 'HYBRID') {
      for (const [inviteId, privateInvite] of [...room.privateInvites.entries()]) {
        if (privateInvite.expiresAtMs <= nowMs) {
          room.privateInvites.delete(inviteId);
          changed = true;
        }
      }
      if (room.transport && room.rtcRoomRevocations) {
        const activePrivateRtcRooms = new Set();
        for (const item of room.members.values()) {
          if (item.channel?.type === 'PRIVATE' && item.channel.sessionId) {
            activePrivateRtcRooms.add(this._rtcRoomIdForChannel(room, item.channel, room.transport.epoch));
          }
        }
        for (const [revokedRoomId, record] of [...room.rtcRoomRevocations.entries()]) {
          if (
            record.scope === 'PRIVATE' &&
            !activePrivateRtcRooms.has(revokedRoomId) &&
            !room.pendingPrivateRevocations?.has(revokedRoomId)
          ) {
            room.rtcRoomRevocations.delete(revokedRoomId);
          } else if (
            record.scope === 'EPOCH' &&
            record.epoch < room.transport.epoch &&
            record.bannedUntilMs <= nowMs
          ) {
            room.rtcRoomRevocations.delete(revokedRoomId);
          }
        }
      }
    }
    for (const [pairKey, record] of [...room.bannedRtcPairs.entries()]) {
      if (record.bannedUntilMs <= nowMs && record.grantExpiresAtMs <= nowMs) {
        room.bannedRtcPairs.delete(pairKey);
      }
    }
    if (room.sweepRetryAfterByKey) {
      for (const [retryKey, record] of [...room.sweepRetryAfterByKey.entries()]) {
        if (retryKey.startsWith('reserve:')) {
          const deviceId = retryKey.slice('reserve:'.length);
          const targetMember = room.members.get(deviceId);
          if (
            !targetMember ||
            targetMember.status !== 'ADMITTED' ||
            nowMs - targetMember.lastSeenMs < this.disconnectTimeoutMs
          ) {
            room.sweepRetryAfterByKey.delete(retryKey);
          }
        } else if (retryKey.startsWith('expire:')) {
          const deviceId = retryKey.slice('expire:'.length);
          const targetMember = room.members.get(deviceId);
          if (!targetMember || targetMember.status !== 'RESERVED') {
            room.sweepRetryAfterByKey.delete(retryKey);
          }
        } else if ((record.updatedAtMs ?? 0) + this.maxRevocationRetryCooldownMs * 2 <= nowMs) {
          room.sweepRetryAfterByKey.delete(retryKey);
        }
      }
    }

    for (const [reqId, pending] of [...room.pendingRequests.entries()]) {
      if (pending.status === 'PENDING' && pending.expiresAtMs <= nowMs) {
        pending.status = 'EXPIRED';
        pending.resolution = { status: 'EXPIRED', code: 'REQUEST_EXPIRED', message: '加入申请已超时，请重新申请' };
        this._wakeRequestWaiters(pending, pending.resolution);
        room.pendingRequests.delete(reqId);
        this.pendingIndex.delete(reqId);
        changed = true;
      }
    }

    for (const [deviceId, member] of [...room.members.entries()]) {
      if (room.ended) break;
      const box = room.mailboxes.get(deviceId);
      const activelyPolling = box && box.waiters.size > 0;
      if (member.status === 'ADMITTED') {
        if (!activelyPolling && nowMs - member.lastSeenMs >= this.disconnectTimeoutMs) {
          const priorPrivateRtcRoom =
            room.mode === 'HYBRID' && member.channel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD'
              ? this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch)
              : null;
          const hasActiveGrants = [...room.issuedRtcGrants.values()].some(
            (grant) => grant.userId === member.rtcUserId && grant.expiresAtMs > nowMs
          );
          const reserveRetryKey = `reserve:${deviceId}`;
          const pendingPrivate = priorPrivateRtcRoom ? room.pendingPrivateRevocations?.get(priorPrivateRtcRoom) : null;
          const hasPendingPrivate = Boolean(
            priorPrivateRtcRoom &&
              (pendingPrivate || this._isBlockingRevocationRecord(room.rtcRoomRevocations?.get(priorPrivateRtcRoom)))
          );
          const hasPendingReserveRetry = Boolean(room.sweepRetryAfterByKey?.has(reserveRetryKey));
          if (
            (priorPrivateRtcRoom || hasActiveGrants) &&
            (hasPendingPrivate || hasPendingReserveRetry) &&
            !allowPendingRevocationRecovery
          ) {
            continue;
          }
          if (
            (priorPrivateRtcRoom || hasActiveGrants) &&
            respectRetryCooldown &&
            (!this._isSweepKeyRetryDue(room, reserveRetryKey, nowMs) ||
              (pendingPrivate && !this._isPendingRetryDue(pendingPrivate, nowMs)))
          ) {
            continue;
          }
          try {
            if (priorPrivateRtcRoom) {
              await this._revokeRtcRoom(room, priorPrivateRtcRoom, 'PRIVATE_DISCONNECTED', nowMs);
            }
            await this._revokeUserAllRtcGrants(
              room,
              deviceId,
              'MEMBER_RESERVED',
              nowMs,
              this.banRemovedForbiddenSeconds
            );
          } catch (error) {
            this._recordSweepKeyRetryFailure(room, reserveRetryKey, nowMs);
            if (priorPrivateRtcRoom && this._isBlockingRevocationRecord(room.rtcRoomRevocations?.get(priorPrivateRtcRoom))) {
              this._recordPendingPrivateRevocation(room, priorPrivateRtcRoom, deviceId, 'PRIVATE_DISCONNECTED', nowMs);
            }
            this.log({
              event: 'rtc_sweep_revocation_failed',
              roomId: room.roomId.slice(-8),
              member: deviceId.slice(-8),
              action: 'MEMBER_RESERVED',
              message: error?.message ?? 'unknown'
            });
            errors.push(error);
            continue;
          }
          this._clearSweepKeyRetry(room, reserveRetryKey);
          member.status = 'RESERVED';
          member.audioAvailable = false;
          member.localStatus = null;
          member.reservedUntilMs = nowMs + this.reservationMs;
          if (room.mode === 'HYBRID') {
            if (room.transport) room.transport.localStableSinceMs = null;
            this._clearPrivateStateForMember(room, deviceId);
            if (room.transport?.active === 'LOCAL' && deviceId === room.transport.localHostDeviceId) {
              if (this.rtcRevocationConfigured) {
                this._transitionTransport(room, 'CLOUD', 'LOCAL_HOST_LOST', nowMs);
              }
            }
          }
          changed = true;
          this.log({ event: 'member_reserved', roomId: room.roomId.slice(-8), member: deviceId.slice(-8) });
        }
      } else if (
        member.status === 'RESERVED' &&
        member.reservedUntilMs !== null &&
        member.reservedUntilMs <= nowMs &&
        deviceId !== room.ownerId
      ) {
        const priorPrivateRtcRoom =
          room.mode === 'HYBRID' && member.channel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD'
            ? this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch)
            : null;
        const admittedCountAfter = [...room.members.values()].filter(
          (m) => m.status === 'ADMITTED' && m.deviceId !== deviceId
        ).length;
        const willFallbackToCloud =
          room.mode === 'HYBRID' &&
          room.transport?.active === 'LOCAL' &&
          room.transport.localHostDeviceId === deviceId &&
          admittedCountAfter >= 2;
        const rotateCloudAfterExpiry =
          room.mode === 'HYBRID' &&
          room.transport?.active === 'CLOUD' &&
          this._hasPendingEpochRevocation(room, room.transport.epoch) &&
          (room.transport.localHostDeviceId === deviceId || admittedCountAfter < 2);
        const expireRetryKey = `expire:${deviceId}`;
        const pendingPrivate = priorPrivateRtcRoom ? room.pendingPrivateRevocations?.get(priorPrivateRtcRoom) : null;
        const hasPendingPrivate = Boolean(
          priorPrivateRtcRoom &&
            (pendingPrivate || this._isBlockingRevocationRecord(room.rtcRoomRevocations?.get(priorPrivateRtcRoom)))
        );
        const hasPendingExpireRetry = Boolean(room.sweepRetryAfterByKey?.has(expireRetryKey));
        if ((rotateCloudAfterExpiry || hasPendingPrivate || hasPendingExpireRetry) && !allowPendingRevocationRecovery) {
          continue;
        }
        if (
          respectRetryCooldown &&
          (!this._isSweepKeyRetryDue(room, expireRetryKey, nowMs) ||
            (pendingPrivate && !this._isPendingRetryDue(pendingPrivate, nowMs)))
        ) {
          continue;
        }
        try {
          if (willFallbackToCloud) {
            this._assertRtcRevocationReady();
          }
          if (rotateCloudAfterExpiry) {
            await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'RECOVERY_EXPIRED', nowMs);
          }
          if (priorPrivateRtcRoom) {
            await this._revokeRtcRoom(room, priorPrivateRtcRoom, 'RECOVERY_EXPIRED', nowMs);
          }
          await this._confirmUserRevokedAllRtcGrants(
            room,
            deviceId,
            'RECOVERY_EXPIRED',
            nowMs,
            this.banRemovedForbiddenSeconds
          );
        } catch (error) {
          this._recordSweepKeyRetryFailure(room, expireRetryKey, nowMs);
          if (rotateCloudAfterExpiry) {
            this._bumpPendingEpochRetry(room, room.transport.epoch, nowMs);
          }
          if (priorPrivateRtcRoom && this._isBlockingRevocationRecord(room.rtcRoomRevocations?.get(priorPrivateRtcRoom))) {
            this._recordPendingPrivateRevocation(room, priorPrivateRtcRoom, deviceId, 'RECOVERY_EXPIRED', nowMs);
          }
          this.log({
            event: 'rtc_sweep_revocation_failed',
            roomId: room.roomId.slice(-8),
            member: deviceId.slice(-8),
            action: 'RECOVERY_EXPIRED',
            message: error?.message ?? 'unknown'
          });
          errors.push(error);
          continue;
        }
        this._clearSweepKeyRetry(room, expireRetryKey);
        if (room.mode === 'HYBRID') {
          this._clearPrivateStateForMember(room, deviceId);
        }
        room.members.delete(deviceId);
        if (room.mode === 'HYBRID') {
          if (room.transport) room.transport.localStableSinceMs = null;
          if (rotateCloudAfterExpiry) {
            this._rotatePartialEpochToFreshCloud(room, nowMs, {
              nextLocalHostDeviceId: room.transport.localHostDeviceId === deviceId ? room.ownerId : undefined,
              advanceBackoff: false
            });
          } else {
            this._handleHybridLocalHostDeparture(room, deviceId, nowMs);
          }
        }
        this._revokeDeviceSessions(
          room,
          deviceId,
          { status: 410, code: 'RECOVERY_EXPIRED', message: '断线超过 60 秒，席位已释放' },
          nowMs
        );
        this._pushEvent(room, deviceId, {
          type: 'TERMINAL',
          revision: room.revision + 1,
          reason: 'RECOVERY_EXPIRED',
          message: '断线超过 60 秒，席位已释放'
        });
        changed = true;
        this.log({ event: 'member_reservation_expired', roomId: room.roomId.slice(-8), member: deviceId.slice(-8) });
      }
    }

    if (
      room.mode === 'HYBRID' &&
      !room.ended &&
      room.transport?.active === 'CLOUD' &&
      allowPendingRevocationRecovery &&
      room.pendingPrivateRevocations?.size > 0
    ) {
      const currentEpoch = room.transport.epoch;
      for (const [privateRtcRoom, pendingPrivate] of [...room.pendingPrivateRevocations.entries()]) {
        if (pendingPrivate.epoch !== currentEpoch) {
          room.pendingPrivateRevocations.delete(privateRtcRoom);
          continue;
        }
        const stillActive = [...room.members.values()].some(
          (item) =>
            item.channel?.type === 'PRIVATE' &&
            this._rtcRoomIdForChannel(room, item.channel, currentEpoch) === privateRtcRoom
        );
        if (!stillActive) {
          room.pendingPrivateRevocations.delete(privateRtcRoom);
          if (room.rtcRoomRevocations?.get(privateRtcRoom)?.scope === 'PRIVATE') {
            room.rtcRoomRevocations.delete(privateRtcRoom);
          }
          continue;
        }
        if (respectRetryCooldown && !this._isPendingRetryDue(pendingPrivate, nowMs)) {
          continue;
        }
        try {
          await this._revokeRtcRoom(room, privateRtcRoom, pendingPrivate.reason, nowMs, this.banRoomForbiddenSeconds, {
            scope: 'PRIVATE'
          });
          if (this._clearPrivateStateForPrivateRtcRoom(room, privateRtcRoom)) {
            changed = true;
          }
        } catch (error) {
          this._recordPendingPrivateRevocation(room, privateRtcRoom, pendingPrivate.deviceId, pendingPrivate.reason, nowMs);
          this.log({
            event: 'rtc_sweep_revocation_failed',
            roomId: room.roomId.slice(-8),
            action: pendingPrivate.reason,
            message: error?.message ?? 'unknown'
          });
          errors.push(error);
        }
      }
    }

    if (room.mode === 'HYBRID' && !room.ended && room.transport?.active === 'CLOUD') {
      const admitted = [...room.members.values()].filter((item) => item.status === 'ADMITTED');
      const admittedCount = admitted.length;
      const epoch = room.transport.epoch;
      const hasPendingEpochRevocation = this._hasPendingEpochRevocation(room, epoch);
      const pendingEpoch = room.pendingEpochRevocation?.epoch === epoch ? room.pendingEpochRevocation : null;
      const candidateHost = room.members.get(room.transport.localHostDeviceId);
      const host = this._resolveAdmittedLocalHost(room);
      const hostAdmitted = Boolean(host);

      if (hasPendingEpochRevocation) {
        if (allowPendingRevocationRecovery && (!respectRetryCooldown || this._isPendingRetryDue(pendingEpoch, nowMs))) {
          const intent =
            pendingEpoch?.intent ??
            (room.transport.recoveryStage === 'PRECONNECTING' ? 'PRECONNECT_LOCAL' : 'MANUAL_LOCAL');
          if (intent === 'MANUAL_LOCAL') {
            const currentHostValid = Boolean(candidateHost && candidateHost.status === 'ADMITTED');
            const canCompleteManualLocal = currentHostValid || (admittedCount <= 1 && hostAdmitted);
            const targetHostId = currentHostValid ? candidateHost.deviceId : (host?.deviceId ?? room.ownerId);
            try {
              if (canCompleteManualLocal) {
                await this._revokeRoomRtcForEpoch(room, epoch, pendingEpoch?.revokeReason ?? 'SWITCHED_TO_LOCAL', nowMs);
                this._transitionTransport(room, 'LOCAL', pendingEpoch?.transportReason ?? 'MANUAL_LOCAL', nowMs, {
                  nextLocalHostDeviceId: targetHostId,
                  preservePreconnect: false
                });
              } else {
                await this._revokeRoomRtcForEpoch(room, epoch, 'LOCAL_HOST_LOST', nowMs);
                this._rotatePartialEpochToFreshCloud(room, nowMs, {
                  nextLocalHostDeviceId: targetHostId,
                  advanceBackoff: false
                });
              }
              changed = true;
            } catch (error) {
              this._recordPendingEpochRevocation(
                room,
                {
                  epoch,
                  intent: 'MANUAL_LOCAL',
                  revokeReason: pendingEpoch?.revokeReason ?? 'SWITCHED_TO_LOCAL',
                  transportReason: pendingEpoch?.transportReason ?? 'MANUAL_LOCAL',
                  nextLocalHostDeviceId: targetHostId,
                  preservePreconnect: false,
                  advanceBackoff: false
                },
                nowMs
              );
              this.log({
                event: 'rtc_sweep_revocation_failed',
                roomId: room.roomId.slice(-8),
                action: canCompleteManualLocal ? 'MANUAL_LOCAL' : 'LOCAL_HOST_LOST',
                message: error?.message ?? 'unknown'
              });
              errors.push(error);
            }
          } else if (intent === 'CLOUD_ROTATE') {
            try {
              await this._revokeRoomRtcForEpoch(room, epoch, pendingEpoch?.revokeReason ?? 'PRECONNECT_ABORTED', nowMs);
              this._rotatePartialEpochToFreshCloud(room, nowMs, {
                nextLocalHostDeviceId: pendingEpoch?.nextLocalHostDeviceId ?? undefined,
                advanceBackoff: Boolean(pendingEpoch?.advanceBackoff)
              });
              changed = true;
            } catch (error) {
              this._recordPendingEpochRevocation(
                room,
                {
                  epoch,
                  intent: 'CLOUD_ROTATE',
                  revokeReason: pendingEpoch?.revokeReason ?? 'PRECONNECT_ABORTED',
                  transportReason: this._cloudRecoveryReason(room),
                  nextLocalHostDeviceId: pendingEpoch?.nextLocalHostDeviceId ?? null,
                  preservePreconnect: false,
                  advanceBackoff: Boolean(pendingEpoch?.advanceBackoff)
                },
                nowMs
              );
              this.log({
                event: 'rtc_sweep_revocation_failed',
                roomId: room.roomId.slice(-8),
                action: pendingEpoch?.revokeReason ?? 'PRECONNECT_ABORTED',
                message: error?.message ?? 'unknown'
              });
              errors.push(error);
            }
          } else {
            const startedAt = room.transport.preconnectStartedAtMs ?? room.transport.cooldownUntilMs;
            const allControlReady =
              admittedCount >= 2 &&
              hostAdmitted &&
              admitted.every(
                (item) =>
                  item.localStatus?.epoch === epoch &&
                  item.localStatus.controlReady === true &&
                  item.localStatus.reportedAtMs >= startedAt &&
                  nowMs - item.localStatus.reportedAtMs <= this.localStatusFreshMs
              );
            const hostChanged = Boolean(hostAdmitted && room.transport.localHostDeviceId !== host.deviceId);
            const timedOut =
              room.transport.preconnectStartedAtMs !== null &&
              nowMs - room.transport.preconnectStartedAtMs >= this.localPreconnectTimeoutMs;
            try {
              if (admittedCount < 2) {
                await this._revokeRoomRtcForEpoch(room, epoch, 'PRECONNECT_ABORTED', nowMs);
                this._rotatePartialEpochToFreshCloud(room, nowMs, { advanceBackoff: false });
                changed = true;
              } else if (!hostAdmitted) {
                await this._revokeRoomRtcForEpoch(room, epoch, 'LOCAL_HOST_LOST', nowMs);
                this._rotatePartialEpochToFreshCloud(room, nowMs, { advanceBackoff: false });
                changed = true;
              } else if (hostChanged) {
                await this._revokeRoomRtcForEpoch(room, epoch, 'LOCAL_HOST_REASSIGNED', nowMs);
                this._rotatePartialEpochToFreshCloud(room, nowMs, {
                  nextLocalHostDeviceId: host.deviceId,
                  advanceBackoff: false
                });
                changed = true;
              } else if (allControlReady) {
                await this._revokeRoomRtcForEpoch(room, epoch, 'LOCAL_RECOVERED', nowMs);
                this._transitionTransport(room, 'LOCAL', 'LOCAL_RECOVERED', nowMs, {
                  nextLocalHostDeviceId: host.deviceId,
                  preservePreconnect: true
                });
                changed = true;
              } else if (timedOut || room.transport.recoveryStage !== 'PRECONNECTING') {
                await this._revokeRoomRtcForEpoch(room, epoch, 'PRECONNECT_ABORTED', nowMs);
                this._rotatePartialEpochToFreshCloud(room, nowMs, { advanceBackoff: true });
                changed = true;
              }
            } catch (error) {
              this._recordPendingEpochRevocation(
                room,
                {
                  epoch,
                  intent: hostAdmitted && !hostChanged && admittedCount >= 2 ? 'PRECONNECT_LOCAL' : 'CLOUD_ROTATE',
                  revokeReason: !hostAdmitted
                    ? 'LOCAL_HOST_LOST'
                    : hostChanged
                      ? 'LOCAL_HOST_REASSIGNED'
                      : allControlReady
                        ? 'LOCAL_RECOVERED'
                        : 'PRECONNECT_ABORTED',
                  transportReason: allControlReady ? 'LOCAL_RECOVERED' : this._cloudRecoveryReason(room),
                  nextLocalHostDeviceId: host?.deviceId ?? null,
                  preservePreconnect: allControlReady,
                  advanceBackoff: !allControlReady && admittedCount >= 2 && hostAdmitted && !hostChanged
                },
                nowMs
              );
              this.log({
                event: 'rtc_sweep_revocation_failed',
                roomId: room.roomId.slice(-8),
                action: !hostAdmitted ? 'LOCAL_HOST_LOST' : (allControlReady ? 'LOCAL_RECOVERED' : 'PRECONNECT_ABORTED'),
                message: error?.message ?? 'unknown'
              });
              errors.push(error);
            }
          }
        }
      } else if (room.transport.recoveryStage === 'PRECONNECTING') {
        if (admittedCount < 2) {
          if (this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'INSUFFICIENT_MEMBERS' })) {
            changed = true;
          }
        } else if (!hostAdmitted) {
          if (this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'LOCAL_HOST_LOST' })) {
            changed = true;
          }
        } else if (hostAdmitted && room.transport.localHostDeviceId !== host.deviceId) {
          room.transport.localHostDeviceId = host.deviceId;
          this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'LOCAL_HOST_REASSIGNED' });
          changed = true;
        } else if (
          room.transport.preconnectStartedAtMs !== null &&
          nowMs - room.transport.preconnectStartedAtMs >= this.localPreconnectTimeoutMs
        ) {
          if (this._abortHybridPreconnect(room, nowMs, { advanceBackoff: true, reason: 'PRECONNECT_TIMEOUT' })) {
            changed = true;
          }
        }
      } else if ((admittedCount < 2 || !hostAdmitted) && room.transport.recoveryStage === 'PROBING') {
        room.transport.recoveryStage = 'IDLE';
        room.transport.recoverySignalStableSinceMs = null;
        changed = true;
      }
    }

    if (changed && !room.ended) {
      room.revision += 1;
      this._broadcastSnapshot(room);
    }
    if (throwOnError && errors.length > 0) {
      throw errors[0];
    }
    return { changed, errors };
  }

  async sweepRoom(roomId, nowMs = undefined, options = {}) {
    const sweepNow = nowMs ?? this.now();
    this._sweepGlobalExpired(sweepNow);
    const result = await this._sweepLockedRoom(roomId, nowMs, options);
    if (options.throwOnError && result.errors.length > 0) {
      throw result.errors[0];
    }
    return result;
  }

  async _sweepLockedRoom(roomId, nowMs, options = {}) {
    if (!roomId || !this.rooms.has(roomId)) return { changed: false, errors: [] };
    const allowPendingRevocationRecovery = options.allowPendingRevocationRecovery !== false;
    const respectRetryCooldown = options.respectRetryCooldown !== false;
    const existing = respectRetryCooldown ? this.inFlightRoomSweeps.get(roomId) : null;
    if (existing) {
      return existing;
    }
    const sweepPromise = this._withRoomLock(roomId, async () => {
      const room = this.rooms.get(roomId);
      if (!room || room.ended) return { changed: false, errors: [] };
      const effectiveNow = nowMs ?? this.now();
      return this._sweepRoomUnlocked(room, effectiveNow, {
        throwOnError: false,
        allowPendingRevocationRecovery,
        respectRetryCooldown
      });
    }).finally(() => {
      if (this.inFlightRoomSweeps.get(roomId) === sweepPromise) {
        this.inFlightRoomSweeps.delete(roomId);
      }
    });
    if (allowPendingRevocationRecovery) {
      if (respectRetryCooldown) {
        this.inFlightRoomSweeps.set(roomId, sweepPromise);
      }
    }
    return sweepPromise;
  }

  async sweep(nowMs = undefined, { throwOnError = false, respectRetryCooldown = true } = {}) {
    const sweepNow = nowMs ?? this.now();
    this._sweepGlobalExpired(sweepNow);
    const roomIds = [...this.rooms.keys()];
    const errors = [];
    let changed = false;
    for (const roomId of roomIds) {
      const result = await this._sweepLockedRoom(roomId, nowMs, {
        throwOnError: false,
        allowPendingRevocationRecovery: true,
        respectRetryCooldown
      });
      if (result.changed) changed = true;
      if (result.errors.length > 0) errors.push(...result.errors);
    }
    if (throwOnError && errors.length > 0) {
      throw errors[0];
    }
    return { changed, errors };
  }

  async _authenticateUnlocked(token, roomId = null, nowMs = this.now()) {
    this._sweepGlobalExpired(nowMs);
    if (typeof token !== 'string' || !token) {
      throw new CloudStoreError(401, 'UNAUTHORIZED', '缺少或无效的会话凭证');
    }
    const preSession = this.sessions.get(token);
    const targetRoomId = roomId ?? preSession?.roomId ?? null;
    if (targetRoomId) {
      const preRoom = this.rooms.get(targetRoomId);
      if (preRoom && !preRoom.ended) {
        await this._sweepRoomUnlocked(preRoom, nowMs, {
          throwOnError: false,
          allowPendingRevocationRecovery: false,
          respectRetryCooldown: true
        });
      }
    }
    const session = this.sessions.get(token);
    if (!session || session.revoked || session.expiresAtMs <= nowMs) {
      if (session) this.sessions.delete(token);
      const tombstone = this.revokedTokens.get(token);
      if (tombstone && tombstone.expiresAtMs > nowMs) {
        throw new CloudStoreError(tombstone.status, tombstone.code, tombstone.message, tombstone.extra ?? {});
      }
      throw new CloudStoreError(401, 'UNAUTHORIZED', '会话已失效，请重新加入');
    }
    if (roomId && session.roomId !== roomId) {
      throw new CloudStoreError(403, 'FORBIDDEN', '会话不属于目标房间');
    }
    const room = this.rooms.get(session.roomId);
    if (!room) {
      throw new CloudStoreError(404, 'ROOM_NOT_FOUND', '房间不存在');
    }
    if (room.ended) {
      throw new CloudStoreError(410, 'ROOM_ENDED', '房间已结束', { endedReason: room.endedReason });
    }
    const member = room.members.get(session.deviceId);
    if (!member || member.runtimeId !== session.runtimeId) {
      throw new CloudStoreError(401, 'UNAUTHORIZED', '成员状态已变更，请重新加入');
    }
    if (member.status === 'RESERVED' && member.reservedUntilMs !== null && member.reservedUntilMs <= nowMs) {
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '成员席位已过期但云端语音凭证吊销未完成，请稍后重试');
    }
    session.expiresAtMs = nowMs + this.sessionTtlMs;
    session.admissionConfirmed = true;
    return { room, member, session };
  }

  async authenticate(token, roomId = null, nowMs = this.now()) {
    if (typeof token !== 'string' || !token) {
      this._sweepGlobalExpired(nowMs);
      throw new CloudStoreError(401, 'UNAUTHORIZED', '缺少或无效的会话凭证');
    }
    const lockRoomId = roomId ?? this.sessions.get(token)?.roomId ?? null;
    if (!lockRoomId) {
      return this._authenticateUnlocked(token, roomId, nowMs);
    }
    return this._withRoomLock(lockRoomId, () => this._authenticateUnlocked(token, roomId, nowMs));
  }

  async createRoom(
    { deviceId, runtimeId, nickname, requireApproval = true, mode = 'NETWORK', supportsFastP2p, entitlementToken = '' },
    nowMs = this.now()
  ) {
    this._sweepGlobalExpired(nowMs);
    const normalizedMode = typeof mode === 'string' ? mode.trim().toUpperCase() : 'NETWORK';
    if (!VALID_MODES.has(normalizedMode)) {
      throw new CloudStoreError(400, 'INVALID_MODE', '房间模式仅支持 NETWORK 或 HYBRID');
    }
    if (normalizedMode === 'NETWORK') {
      this._assertRtcRevocationReady();
    }
    const dev = normalizeId(deviceId, 'deviceId');
    const run = normalizeId(runtimeId, 'runtimeId');
    const name = normalizeNickname(nickname, dev);
    let eligibility = null;
    if (this.entitlementStore) {
      eligibility = this.entitlementStore.checkCreateRoomEligibility(
        entitlementToken,
        {
          mode: normalizedMode,
          deviceId: dev,
          countActiveRooms: (entitlementId) => this._countActiveSponsoredRooms(entitlementId)
        },
        nowMs
      );
      this.pendingCreateByEntitlement.set(
        eligibility.entitlementId,
        (this.pendingCreateByEntitlement.get(eligibility.entitlementId) ?? 0) + 1
      );
    }
    try {
    const roomId = crypto.randomUUID();
    const roomEpoch = crypto.randomUUID();
    const rtcRoomId = `mcr-${crypto.randomUUID().replaceAll('-', '')}`;
    const isHybrid = normalizedMode === 'HYBRID';
    const entitlementMaxMembers = eligibility ? eligibility.maxMembers : this.maxMembers;
    const roomMaxMembers = isHybrid
      ? Math.min(this.maxMembers, HYBRID_MAX_MEMBERS, entitlementMaxMembers)
      : Math.min(this.maxMembers, entitlementMaxMembers);
    const owner = {
      deviceId: dev,
      rtcUserId: crypto.randomUUID(),
      runtimeId: run,
      nickname: name,
      supportsFastP2p: typeof supportsFastP2p === 'boolean' ? supportsFastP2p : null,
      role: 'OWNER',
      status: 'ADMITTED',
      incarnation: 1,
      controlGeneration: 1,
      channel: { type: 'PUBLIC' },
      channelRevision: 1,
      audioAvailable: false,
      localStatus: null,
      reservedUntilMs: null,
      lastSeenMs: nowMs
    };
    const initialLocalCode = isHybrid ? generateLocalCode() : null;
    const room = {
      roomId,
      roomEpoch,
      rtcRoomId,
      issuedRtcRoomIds: new Map(),
      issuedRtcGrants: new Map(),
      bannedRtcPairs: new Map(),
      rtcRoomRevocations: new Map(),
      pendingEnd: null,
      pendingEpochRevocation: null,
      pendingPrivateRevocations: new Map(),
      sweepRetryAfterByKey: new Map(),
      mode: normalizedMode,
      maxMembers: roomMaxMembers,
      ownerId: dev,
      sponsorEntitlementId: eligibility?.entitlementId ?? null,
      maxSessionMinutes: eligibility?.maxSessionMinutes ?? 120,
      createdAtMs: nowMs,
      billing: eligibility
        ? {
            lastSettledAtMs: nowMs,
            pendingSeconds: 0,
            totalBilledSeconds: 0,
            cloudSessionStartedAtMs: null,
            lowQuotaWarned: false,
            sessionLimitWarned: false,
            lastFlushedAtMs: nowMs,
            peakRtcMembers: 0
          }
        : null,
      revision: 1,
      ended: false,
      endedReason: null,
      endedAtMs: null,
      requireApproval: Boolean(requireApproval),
      riderIncludesFormation: isHybrid,
      privateInvites: new Map(),
      transport: isHybrid
        ? {
            active: 'LOCAL',
            epoch: 1,
            reason: 'INITIAL_LOCAL',
            localHostDeviceId: dev,
            localRoomInstanceId: roomId,
            localCode: initialLocalCode,
            lastLocalCode: initialLocalCode,
            localP2pSsid: generateLocalP2pSsid(),
            localP2pPassphrase: generateLocalP2pPassphrase(),
            localControlPort: DEFAULT_LOCAL_CONTROL_PORT,
            localHostAddress: null,
            localFrequencyMhz: null,
            recoveryStage: 'IDLE',
            recoverySignalStableSinceMs: null,
            preconnectStartedAtMs: null,
            handoverFromEpoch: null,
            changedAtMs: nowMs,
            cooldownUntilMs: 0,
            fallbackStreak: 0,
            localStableSinceMs: null
          }
        : null,
      invite: null,
      members: new Map([[dev, owner]]),
      removed: new Map(),
      pendingRequests: new Map(),
      mailboxes: new Map(),
      activeTokens: new Map(),
      nextIncarnation: 2
    };
    const rtc = await this._issueRtcCredentialsIfAllowed(room, owner, nowMs);
    this._createInvite(room, nowMs);
    this.rooms.set(roomId, room);
    const session = this._issueSession(room, owner, nowMs);
    this._ensureMailbox(room, dev);
    this.log({
      event: 'room_created',
      roomId: roomId.slice(-8),
      mode: normalizedMode,
      maxMembers: roomMaxMembers,
      requireApproval: room.requireApproval
    });
    return {
      status: 'ADMITTED',
      roomId,
      sessionToken: session.token,
      tokenExpiresAtMs: session.expiresAtMs,
      latestSeq: 0,
      snapshot: this._snapshotFor(room, dev),
      rtc
    };
    } finally {
      if (eligibility) {
        const remaining = (this.pendingCreateByEntitlement.get(eligibility.entitlementId) ?? 1) - 1;
        if (remaining <= 0) this.pendingCreateByEntitlement.delete(eligibility.entitlementId);
        else this.pendingCreateByEntitlement.set(eligibility.entitlementId, remaining);
      }
    }
  }

  async submitJoinRequest(params, nowMs = this.now()) {
    if (params.joinSecret === undefined) return this._submitJoinRequest(params, null, nowMs);
    const id = joinReceiptId(params.joinSecret);
    return this._withRoomLock(id, async () => {
      this._sweepGlobalExpired(nowMs);
      if (this.cancelledJoinReceipts.has(id)) {
        throw new CloudStoreError(410, 'CANCELLED', '加入申请已取消');
      }
      const prior = this.pendingIndex.get(id);
      if (prior) {
        if (prior.deviceId !== params.deviceId || prior.runtimeId !== params.runtimeId) {
          throw new CloudStoreError(409, 'REQUEST_CONFLICT', '加入申请与设备会话不匹配');
        }
        if (prior.status === 'ADMITTED') {
          return this._resolveJoinReceipt(prior, nowMs, { rejectEnded: true });
        }
        if (prior.status !== 'PENDING') throw new CloudStoreError(410, 'REQUEST_ENDED', '加入申请已结束');
        return { status: 'PENDING', roomId: prior.roomId, requestId: id, requestSecret: prior.secret, expiresAtMs: prior.expiresAtMs };
      }
      return this._submitJoinRequest(params, { id, secret: params.joinSecret }, nowMs);
    });
  }

  async _resolveJoinReceipt(pending, nowMs, { rejectEnded = false } = {}) {
    return this._withRoomLock(pending.roomId, async () => {
      const room = this.rooms.get(pending.roomId);
      if (room && !room.ended) {
        await this._sweepRoomUnlocked(room, nowMs, {
          throwOnError: false,
          allowPendingRevocationRecovery: false,
          respectRetryCooldown: true
        });
      }
      if (pending.status !== 'ADMITTED') {
        if (pending.status !== 'PENDING') {
          this.pendingIndex.delete(pending.requestId);
        }
        if (rejectEnded && pending.status !== 'PENDING') {
          throw new CloudStoreError(410, 'REQUEST_ENDED', '加入申请已结束');
        }
        return pending.status === 'PENDING'
          ? { status: 'PENDING', requestId: pending.requestId, expiresAtMs: pending.expiresAtMs }
          : pending.resolution;
      }
      const liveRoom = this.rooms.get(pending.roomId);
      const token = pending.resolution?.sessionToken ?? null;
      const session = token ? this.sessions.get(token) : null;
      const member = liveRoom?.members.get(pending.deviceId) ?? null;
      if (
        !liveRoom ||
        liveRoom.ended ||
        !session ||
        session.revoked ||
        session.expiresAtMs <= nowMs ||
        !member ||
        member.runtimeId !== pending.runtimeId ||
        liveRoom.activeTokens.get(pending.deviceId) !== token
      ) {
        const code = !liveRoom || liveRoom.ended ? 'ROOM_ENDED' : 'SESSION_ENDED';
        const message = !liveRoom || liveRoom.ended ? '房间已结束' : '原入房会话已结束';
        pending.status = 'ENDED';
        pending.resolution = { status: 'ENDED', code, message };
        this.pendingIndex.delete(pending.requestId);
        if (rejectEnded) {
          throw new CloudStoreError(410, 'REQUEST_ENDED', '加入申请已结束');
        }
        return pending.resolution;
      }
      this._assertRoomNotPendingEnd(liveRoom, nowMs);
      if (member.status === 'RESERVED') {
        if (member.reservedUntilMs !== null && member.reservedUntilMs <= nowMs) {
          throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '成员席位已过期但云端语音凭证吊销未完成，请稍后重试');
        }
        const demoteFromRider =
          liveRoom.mode === 'HYBRID' &&
          member.channel?.type === 'RIDER' &&
          !canEnterRider(member.role, liveRoom.riderIncludesFormation);
        const nextChannel = demoteFromRider ? { type: 'PUBLIC' } : member.channel;
        const nextMember = { ...member, status: 'ADMITTED', channel: nextChannel };
        if (this._canIssueRtc(liveRoom, nextMember)) {
          this._assertRtcRoomIssuable(liveRoom, this._rtcRoomIdForMember(liveRoom, nextMember));
        }
        if (demoteFromRider && liveRoom.transport?.active === 'CLOUD') {
          const riderRtcRoom = this._rtcRoomIdForChannel(liveRoom, member.channel, liveRoom.transport.epoch);
          await this._revokeUserFromRtcRoom(liveRoom, riderRtcRoom, member.deviceId, 'ROLE_DEMOTED', nowMs);
        }
        const freshRtc = await this._issueRtcCredentialsIfAllowed(liveRoom, nextMember, nowMs);
        member.status = 'ADMITTED';
        member.reservedUntilMs = null;
        member.lastSeenMs = nowMs;
        member.incarnation = liveRoom.nextIncarnation++;
        member.controlGeneration += 1;
        member.audioAvailable = false;
        member.localStatus = null;
        if (demoteFromRider) {
          member.channel = { type: 'PUBLIC' };
          member.channelRevision += 1;
        }
        session.incarnation = member.incarnation;
        session.controlGeneration = member.controlGeneration;
        session.expiresAtMs = nowMs + this.sessionTtlMs;
        if (liveRoom.transport) liveRoom.transport.localStableSinceMs = null;
        liveRoom.revision += 1;
        this._broadcastSnapshot(liveRoom);
        const box = this._ensureMailbox(liveRoom, member.deviceId);
        pending.expiresAtMs = session.expiresAtMs;
        pending.resolution = {
          ...pending.resolution,
          tokenExpiresAtMs: session.expiresAtMs,
          latestSeq: box.latestSeq,
          snapshot: this._snapshotFor(liveRoom, member.deviceId),
          rtc: freshRtc
        };
        return pending.resolution;
      }

      member.lastSeenMs = nowMs;
      let rtc = null;
      const cachedRtc = pending.resolution?.rtc ?? null;
      if (this._canIssueRtc(liveRoom, member)) {
        const currentRtcRoomId = this._rtcRoomIdForMember(liveRoom, member);
        const grantKey = `${currentRtcRoomId}\0${member.rtcUserId}`;
        const grant = liveRoom.issuedRtcGrants.get(grantKey);
        const ban = liveRoom.bannedRtcPairs.get(grantKey);
        const epochBlocked =
          liveRoom.mode === 'HYBRID' &&
          liveRoom.transport &&
          this._hasPendingEpochRevocation(liveRoom, liveRoom.transport.epoch);
        const channelBlocked = this._isBlockingRevocationRecord(liveRoom.rtcRoomRevocations?.get(currentRtcRoomId));
        const minCachedRtcRemainingMs =
          this.rtcTokenTtlMs >= 5_000
            ? Math.min(30_000, Math.max(5_000, Math.floor(this.rtcTokenTtlMs / 2)))
            : 1;
        if (epochBlocked || channelBlocked) {
          if (liveRoom.mode === 'NETWORK') {
            this._assertRtcRoomIssuable(liveRoom, currentRtcRoomId);
          }
          rtc = null;
        } else if (
          cachedRtc &&
          cachedRtc.rtcRoomId === currentRtcRoomId &&
          cachedRtc.rtcUserId === member.rtcUserId &&
          cachedRtc.expiresAtMs - nowMs >= minCachedRtcRemainingMs &&
          Boolean(grant && grant.expiresAtMs - nowMs >= minCachedRtcRemainingMs) &&
          !(ban && ban.bannedUntilMs > nowMs)
        ) {
          rtc = cachedRtc;
        } else {
          rtc = await this._issueRtcCredentialsIfAllowed(liveRoom, member, nowMs);
        }
      }
      const box = this._ensureMailbox(liveRoom, member.deviceId);
      const snapshot = this._snapshotFor(liveRoom, member.deviceId);
      if (pending.resolution) {
        pending.resolution.rtc = rtc;
        pending.resolution.tokenExpiresAtMs = session.expiresAtMs;
      }
      return {
        ...pending.resolution,
        tokenExpiresAtMs: session.expiresAtMs,
        latestSeq: box.latestSeq,
        snapshot,
        rtc
      };
    });
  }

  async _submitJoinRequest(
    { inviteCode, deviceId, runtimeId, nickname, supportsFastP2p, entitlementToken = '' },
    receipt,
    nowMs
  ) {
    this._sweepGlobalExpired(nowMs);
    const code = normalizeInviteCode(inviteCode);
    const dev = normalizeId(deviceId, 'deviceId');
    const run = normalizeId(runtimeId, 'runtimeId');
    const name = normalizeNickname(nickname, dev);
    if (this.entitlementStore) {
      this.entitlementStore.checkJoinEligibilityIfRequired(entitlementToken, { deviceId: dev }, nowMs);
    }
    const roomId = this.inviteIndex.get(code);
    if (!roomId) {
      throw new CloudStoreError(404, 'INVALID_INVITE', '邀请码无效或已过期');
    }
    return this._withRoomLock(roomId, async () => {
      const effectiveNow = nowMs;
      const preRoom = this.rooms.get(roomId);
      if (preRoom && !preRoom.ended) {
        await this._sweepRoomUnlocked(preRoom, effectiveNow, {
          throwOnError: false,
          allowPendingRevocationRecovery: false,
          respectRetryCooldown: true
        });
      }
      const room = this.rooms.get(roomId);
      if (!room || room.ended || !room.invite || room.invite.expiresAtMs <= effectiveNow || !timingSafeStringEqual(room.invite.code, code)) {
        throw new CloudStoreError(404, 'INVALID_INVITE', '邀请码无效或已过期');
      }
      this._assertRoomNotPendingEnd(room, effectiveNow);
      if (this.entitlementStore && room.sponsorEntitlementId) {
        this._assertSponsorQuotaAvailable(room, effectiveNow);
      }
      if (room.removed.has(dev)) {
        throw new CloudStoreError(403, 'REMOVED', '你已被移出该房间，需群主解除限制后才能重新加入');
      }

      const existing = room.members.get(dev);
      if (existing) {
        throw new CloudStoreError(
          409,
          'ALREADY_IN_ROOM',
          '该设备已在房间中；断线恢复请使用当前会话凭证调用 /resume，不可通过邀请码重签会话'
        );
      }

      if (room.members.size >= room.maxMembers) {
        throw new CloudStoreError(409, 'ROOM_FULL', `房间已满（最多 ${room.maxMembers} 人）`);
      }

      if (!room.requireApproval) {
        this._settleRoomBilling(room, effectiveNow, { forceFlush: false, reason: 'MEMBER_JOIN' });
        const member = {
          deviceId: dev,
          rtcUserId: crypto.randomUUID(),
          runtimeId: run,
          nickname: name,
          supportsFastP2p: typeof supportsFastP2p === 'boolean' ? supportsFastP2p : null,
          role: 'MEMBER',
          status: 'ADMITTED',
          incarnation: room.nextIncarnation++,
          controlGeneration: 1,
          channel: { type: 'PUBLIC' },
          channelRevision: 1,
          audioAvailable: false,
          localStatus: null,
          reservedUntilMs: null,
          lastSeenMs: effectiveNow
        };
        const rtc = await this._issueRtcCredentialsIfAllowed(room, member, effectiveNow);
        room.members.set(dev, member);
        if (room.transport) room.transport.localStableSinceMs = null;
        room.revision += 1;
        const session = this._issueSession(room, member, effectiveNow);
        this._broadcastSnapshot(room);
        const box = this._ensureMailbox(room, dev);
        this.log({ event: 'member_joined_direct', roomId: room.roomId.slice(-8), seats: room.members.size });
        const admitted = {
          status: 'ADMITTED',
          roomId: room.roomId,
          sessionToken: session.token,
          tokenExpiresAtMs: session.expiresAtMs,
          latestSeq: box.latestSeq,
          snapshot: this._snapshotFor(room, dev),
          rtc
        };
        if (receipt) {
          session.requestId = receipt.id;
          this.pendingIndex.set(receipt.id, {
            requestId: receipt.id, secret: receipt.secret, roomId: room.roomId, deviceId: dev, runtimeId: run,
            expiresAtMs: session.expiresAtMs, status: 'ADMITTED', resolution: admitted, waiters: new Set()
          });
        }
        return admitted;
      }

      for (const [oldId, existingReq] of [...room.pendingRequests.entries()]) {
        if (existingReq.deviceId === dev) {
          existingReq.status = 'CANCELLED';
          existingReq.resolution = { status: 'CANCELLED' };
          this._wakeRequestWaiters(existingReq, existingReq.resolution);
          room.pendingRequests.delete(oldId);
          this.pendingIndex.delete(oldId);
        }
      }
      if (room.pendingRequests.size >= MAX_PENDING_REQUESTS) {
        throw new CloudStoreError(429, 'TOO_MANY_REQUESTS', '待审批人数已达上限，请稍后再试');
      }

      const requestId = receipt?.id ?? crypto.randomUUID();
      const requestSecret = receipt?.secret ?? this._generateToken();
      const pending = {
        requestId,
        roomId: room.roomId,
        deviceId: dev,
        runtimeId: run,
        nickname: name,
        supportsFastP2p: typeof supportsFastP2p === 'boolean' ? supportsFastP2p : null,
        secret: requestSecret,
        createdAtMs: effectiveNow,
        expiresAtMs: effectiveNow + this.requestTtlMs,
        status: 'PENDING',
        resolution: null,
        waiters: new Set()
      };
      room.pendingRequests.set(requestId, pending);
      this.pendingIndex.set(requestId, pending);
      room.revision += 1;
      this._broadcastSnapshot(room);
      this.log({ event: 'join_request_created', roomId: room.roomId.slice(-8) });
      return {
        status: 'PENDING',
        roomId: room.roomId,
        requestId,
        requestSecret,
        expiresAtMs: pending.expiresAtMs
      };
    });
  }

  async pollJoinRequest({ requestId, requestSecret, waitMs = 0, signal = null }, nowMs = this.now()) {
    this._sweepGlobalExpired(nowMs);
    const pending = this.pendingIndex.get(requestId);
    if (!pending) {
      throw new CloudStoreError(404, 'REQUEST_NOT_FOUND', '申请记录不存在或已失效');
    }
    if (typeof requestSecret !== 'string' || !timingSafeStringEqual(pending.secret, requestSecret)) {
      throw new CloudStoreError(401, 'UNAUTHORIZED', '申请凭证无效');
    }
    const initial = await this._resolveJoinReceipt(pending, nowMs, { rejectEnded: false });
    if (initial.status !== 'PENDING') {
      return initial;
    }
    const clampedWait = Math.max(0, Math.min(20_000, Number(waitMs) || 0));
    if (clampedWait === 0) {
      return { status: 'PENDING', requestId, expiresAtMs: pending.expiresAtMs };
    }
    if (signal?.aborted) {
      return { status: 'PENDING', requestId, expiresAtMs: pending.expiresAtMs };
    }
    return new Promise((resolve, reject) => {
      let settled = false;
      const cleanupAndResolve = (payload) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        pending.waiters.delete(onWake);
        signal?.removeEventListener('abort', onAbort);
        resolve(payload);
      };
      const cleanupAndReject = (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        pending.waiters.delete(onWake);
        signal?.removeEventListener('abort', onAbort);
        reject(error);
      };
      const onAbort = () => cleanupAndResolve({ status: 'PENDING', requestId, expiresAtMs: pending.expiresAtMs });
      const onWake = (payload) => {
        if (pending.status !== 'ADMITTED') this.pendingIndex.delete(requestId);
        cleanupAndResolve(payload);
      };
      const timer = setTimeout(() => {
        void this._resolveJoinReceipt(pending, this.now(), { rejectEnded: false })
          .then((res) => {
            cleanupAndResolve(res);
          })
          .catch((error) => {
            cleanupAndReject(error);
          });
      }, clampedWait);
      pending.waiters.add(onWake);
      signal?.addEventListener('abort', onAbort, { once: true });
    });
  }

  async cancelJoinRequest(params, nowMs = this.now()) {
    const { requestId, requestSecret } = params;
    if (!requestId.startsWith('j1-')) return this._cancelJoinRequest(params, nowMs);
    if (joinReceiptId(requestSecret) !== requestId) throw new CloudStoreError(401, 'UNAUTHORIZED', '申请凭证无效');
    return this._withRoomLock(requestId, async () => {
      this._sweepGlobalExpired(nowMs);
      if (this.cancelledJoinReceipts.has(requestId)) return { status: 'CANCELLED' };
      if (this.cancelledJoinReceipts.size >= 4096) {
        throw new CloudStoreError(503, 'CANCEL_BUSY', '正在处理其他撤销申请，请重试');
      }
      const result = await this._cancelJoinRequest(params, nowMs);
      // A delayed POST must also observe a cancellation that arrived first.
      this.cancelledJoinReceipts.set(requestId, nowMs + this.inviteTtlMs);
      return result;
    });
  }

  async _cancelJoinRequest({ requestId, requestSecret }, nowMs) {
    this._sweepGlobalExpired(nowMs);
    const prePending = this.pendingIndex.get(requestId);
    if (!prePending) return { status: 'CANCELLED' };
    if (typeof requestSecret !== 'string' || !timingSafeStringEqual(prePending.secret, requestSecret)) {
      throw new CloudStoreError(401, 'UNAUTHORIZED', '申请凭证无效');
    }
    return this._withRoomLock(prePending.roomId, async () => {
      const pending = this.pendingIndex.get(requestId);
      if (!pending) return { status: 'CANCELLED' };
      const room = this.rooms.get(pending.roomId);
      if (room && !room.ended) {
        await this._sweepRoomUnlocked(room, nowMs, {
          throwOnError: false,
          allowPendingRevocationRecovery: false,
          respectRetryCooldown: true
        });
      }
      if (pending.status === 'ADMITTED' && room && !room.ended &&
          room.activeTokens.get(pending.deviceId) === pending.resolution.sessionToken) {
        const member = room.members.get(pending.deviceId);
        await this._leaveMemberUnlocked(room, member, nowMs);
      }
      if (pending.status === 'PENDING') {
        pending.status = 'CANCELLED';
        pending.resolution = { status: 'CANCELLED', message: '已取消加入申请' };
        this._wakeRequestWaiters(pending, pending.resolution);
        if (room && !room.ended) {
          room.pendingRequests.delete(requestId);
          room.revision += 1;
          this._broadcastSnapshot(room);
        }
      }
      this.pendingIndex.delete(requestId);
      return { status: 'CANCELLED' };
    });
  }

  async approveJoinRequest(token, roomId, requestId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可审批加入申请');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const pending = room.pendingRequests.get(requestId);
      if (!pending || pending.status !== 'PENDING') {
        throw new CloudStoreError(404, 'REQUEST_NOT_FOUND', '该加入申请已处理或已超时');
      }
      if (room.removed.has(pending.deviceId)) {
        pending.status = 'REJECTED';
        pending.resolution = { status: 'REJECTED', code: 'REMOVED', message: '你已被移出该房间，需群主解除限制' };
        room.pendingRequests.delete(requestId);
        room.revision += 1;
        this._wakeRequestWaiters(pending, pending.resolution);
        this._broadcastSnapshot(room);
        throw new CloudStoreError(403, 'REMOVED', '该成员处于移除限制中，请先解除限制');
      }
      if (room.members.size >= room.maxMembers) {
        throw new CloudStoreError(409, 'ROOM_FULL', `房间已满（最多 ${room.maxMembers} 人），无法再准入`);
      }
      if (room.members.has(pending.deviceId)) {
        room.pendingRequests.delete(requestId);
        this.pendingIndex.delete(requestId);
        throw new CloudStoreError(409, 'ALREADY_IN_ROOM', '该成员已在房间中');
      }
      if (this.entitlementStore && room.sponsorEntitlementId) {
        this._assertSponsorQuotaAvailable(room, nowMs);
      }
      this._settleRoomBilling(room, nowMs, { forceFlush: false, reason: 'APPROVE_JOIN' });
      const joined = {
        deviceId: pending.deviceId,
        rtcUserId: crypto.randomUUID(),
        runtimeId: pending.runtimeId,
        nickname: pending.nickname,
        supportsFastP2p: pending.supportsFastP2p,
        role: 'MEMBER',
        status: 'ADMITTED',
        incarnation: room.nextIncarnation++,
        controlGeneration: 1,
        channel: { type: 'PUBLIC' },
        channelRevision: 1,
        audioAvailable: false,
        localStatus: null,
        reservedUntilMs: null,
        lastSeenMs: nowMs
      };
      const rtc = await this._issueRtcCredentialsIfAllowed(room, joined, nowMs);
      room.members.set(joined.deviceId, joined);
      if (room.transport) room.transport.localStableSinceMs = null;
      room.pendingRequests.delete(requestId);
      room.revision += 1;
      const session = this._issueSession(room, joined, nowMs);
      session.requestId = requestId;
      const box = this._ensureMailbox(room, joined.deviceId);
      pending.status = 'ADMITTED';
      pending.expiresAtMs = nowMs + this.sessionTtlMs;
      pending.resolution = {
        status: 'ADMITTED',
        roomId: room.roomId,
        sessionToken: session.token,
        tokenExpiresAtMs: session.expiresAtMs,
        latestSeq: box.latestSeq,
        snapshot: this._snapshotFor(room, joined.deviceId),
        rtc
      };
      this._broadcastSnapshot(room);
      pending.resolution.latestSeq = box.latestSeq;
      this._wakeRequestWaiters(pending, pending.resolution);
      this.log({ event: 'join_request_approved', roomId: room.roomId.slice(-8), seats: room.members.size });
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async rejectJoinRequest(token, roomId, requestId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可审批加入申请');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const pending = room.pendingRequests.get(requestId);
      if (!pending || pending.status !== 'PENDING') {
        throw new CloudStoreError(404, 'REQUEST_NOT_FOUND', '该加入申请已处理或已超时');
      }
      pending.status = 'REJECTED';
      pending.resolution = { status: 'REJECTED', code: 'REJECTED', message: '群主已拒绝加入申请' };
      room.pendingRequests.delete(requestId);
      room.revision += 1;
      this._wakeRequestWaiters(pending, pending.resolution);
      this._broadcastSnapshot(room);
      this.log({ event: 'join_request_rejected', roomId: room.roomId.slice(-8) });
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async rotateInvite(token, roomId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可刷新邀请码');
      }
      this._assertExpectedRevision(room, expectedRevision);
      this._createInvite(room, nowMs);
      room.revision += 1;
      this._broadcastSnapshot(room);
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async setRequireApproval(token, roomId, { requireApproval, expectedRevision }, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可修改审批设置');
      }
      this._assertExpectedRevision(room, expectedRevision);
      room.requireApproval = Boolean(requireApproval);
      room.revision += 1;
      this._broadcastSnapshot(room);
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async transferOwner(token, roomId, { targetDeviceId, alsoLeave = false, expectedRevision }, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅当前群主可转让群主');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const targetId = normalizeId(targetDeviceId, 'targetDeviceId');
      if (targetId === member.deviceId) {
        throw new CloudStoreError(400, 'INVALID_TARGET', '不能将群主转让给自己');
      }
      const target = room.members.get(targetId);
      if (!target || target.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'TARGET_NOT_ADMITTED', '只能转让给当前在线的已准入成员');
      }
      // Polling an approval receipt can race cancellation. Only using the room session confirms entry.
      const targetSession = this.sessions.get(room.activeTokens.get(targetId));
      if (targetSession?.requestId && !targetSession.admissionConfirmed) {
        throw new CloudStoreError(409, 'TARGET_NOT_ADMITTED', '对方尚未确认入房，请稍后再转让群主');
      }
      const admittedCountAfter = [...room.members.values()].filter(
        (m) => m.status === 'ADMITTED' && (!alsoLeave || m.deviceId !== member.deviceId)
      ).length;
      const willFallbackToCloud =
        room.mode === 'HYBRID' &&
        room.transport?.active === 'LOCAL' &&
        room.transport.localHostDeviceId === member.deviceId &&
        admittedCountAfter >= 2;
      if (willFallbackToCloud) {
        this._assertRtcRevocationReady();
      }
      const rotateCloudAfterTransfer =
        room.mode === 'HYBRID' &&
        room.transport?.active === 'CLOUD' &&
        this._hasPendingEpochRevocation(room, room.transport.epoch) &&
        (room.transport.localHostDeviceId === member.deviceId || (alsoLeave && admittedCountAfter < 2));
      if (rotateCloudAfterTransfer) {
        try {
          await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'OWNER_TRANSFERRED', nowMs);
        } catch (error) {
          this._bumpPendingEpochRetry(room, room.transport.epoch, nowMs);
          throw error;
        }
      }
      if (alsoLeave) {
        if (room.mode === 'HYBRID' && member.channel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD') {
          const privateRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
          try {
            await this._revokeRtcRoom(room, privateRtcRoom, 'OWNER_TRANSFER_LEAVE', nowMs);
          } catch (error) {
            this._recordPendingPrivateRevocation(room, privateRtcRoom, member.deviceId, 'OWNER_TRANSFER_LEAVE', nowMs);
            throw error;
          }
          try {
            await this._revokeUserAllRtcGrants(room, member.deviceId, 'OWNER_TRANSFER_LEAVE', nowMs);
          } catch (error) {
            this._recordPendingPrivateRevocation(room, privateRtcRoom, member.deviceId, 'OWNER_TRANSFER_LEAVE', nowMs);
            throw error;
          }
        } else {
          await this._revokeUserAllRtcGrants(room, member.deviceId, 'OWNER_TRANSFER_LEAVE', nowMs);
        }
      } else if (room.mode === 'HYBRID' && member.channel?.type === 'RIDER' && room.transport?.active === 'CLOUD') {
        const riderRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
        await this._revokeUserFromRtcRoom(room, riderRtcRoom, member.deviceId, 'OWNER_DEMOTED', nowMs);
      }
      member.role = 'MEMBER';
      target.role = 'OWNER';
      room.ownerId = target.deviceId;
      if (room.mode === 'HYBRID' && !alsoLeave && member.channel?.type === 'RIDER') {
        member.channel = { type: 'PUBLIC' };
        member.channelRevision += 1;
        member.audioAvailable = false;
      }
      if (alsoLeave) {
        if (room.mode === 'HYBRID') {
          this._clearPrivateStateForMember(room, member.deviceId);
        }
        room.members.delete(member.deviceId);
        this._revokeDeviceSessions(room, member.deviceId);
        if (room.mode === 'HYBRID') {
          if (room.transport) room.transport.localStableSinceMs = null;
          if (rotateCloudAfterTransfer) {
            this._rotatePartialEpochToFreshCloud(room, nowMs, {
              nextLocalHostDeviceId: room.transport.localHostDeviceId === member.deviceId ? room.ownerId : undefined,
              advanceBackoff: false
            });
          } else {
            this._handleHybridLocalHostDeparture(room, member.deviceId, nowMs);
          }
        }
      } else if (room.mode === 'HYBRID') {
        if (rotateCloudAfterTransfer) {
          this._rotatePartialEpochToFreshCloud(room, nowMs, {
            nextLocalHostDeviceId: room.transport.localHostDeviceId === member.deviceId ? room.ownerId : undefined,
            advanceBackoff: false
          });
        } else {
          this._handleHybridLocalHostDeparture(room, member.deviceId, nowMs);
        }
      }
      room.revision += 1;
      this._broadcastSnapshot(room);
      this.log({ event: 'owner_transferred', roomId: room.roomId.slice(-8), alsoLeave: Boolean(alsoLeave) });
      return {
        left: Boolean(alsoLeave),
        snapshot: alsoLeave ? null : this._snapshotFor(room, member.deviceId)
      };
    });
  }

  async removeMember(token, roomId, targetDeviceId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可移除成员');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const targetId = normalizeId(targetDeviceId, 'targetDeviceId');
      if (targetId === room.ownerId) {
        throw new CloudStoreError(400, 'FORBIDDEN', '群主不能移除自己');
      }
      const target = room.members.get(targetId);
      if (!target) {
        throw new CloudStoreError(404, 'MEMBER_NOT_FOUND', '目标成员不在房间内');
      }
      this._settleRoomBilling(room, nowMs, { forceFlush: false, reason: 'MEMBER_REMOVE' });
      const admittedCountAfter = [...room.members.values()].filter(
        (m) => m.status === 'ADMITTED' && m.deviceId !== targetId
      ).length;
      const willFallbackToCloud =
        room.mode === 'HYBRID' &&
        room.transport?.active === 'LOCAL' &&
        room.transport.localHostDeviceId === targetId &&
        admittedCountAfter >= 2;
      if (willFallbackToCloud) {
        this._assertRtcRevocationReady();
      }
      const rotateCloudAfterRemove =
        room.mode === 'HYBRID' &&
        room.transport?.active === 'CLOUD' &&
        this._hasPendingEpochRevocation(room, room.transport.epoch);
      if (rotateCloudAfterRemove) {
        try {
          await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'MEMBER_REMOVED', nowMs);
        } catch (error) {
          this._bumpPendingEpochRetry(room, room.transport.epoch, nowMs);
          throw error;
        }
      }
      if (room.mode === 'HYBRID' && target.channel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD') {
        const privateRtcRoom = this._rtcRoomIdForChannel(room, target.channel, room.transport.epoch);
        try {
          await this._revokeRtcRoom(room, privateRtcRoom, 'REMOVED', nowMs);
        } catch (error) {
          this._recordPendingPrivateRevocation(room, privateRtcRoom, targetId, 'REMOVED', nowMs);
          throw error;
        }
        try {
          await this._revokeUserAllRtcGrants(room, targetId, 'REMOVED', nowMs, this.banRemovedForbiddenSeconds);
        } catch (error) {
          this._recordPendingPrivateRevocation(room, privateRtcRoom, targetId, 'REMOVED', nowMs);
          throw error;
        }
      } else {
        await this._revokeUserAllRtcGrants(room, targetId, 'REMOVED', nowMs, this.banRemovedForbiddenSeconds);
      }
      if (room.mode === 'HYBRID') {
        this._clearPrivateStateForMember(room, targetId);
      }
      room.members.delete(targetId);
      room.removed.set(targetId, target.nickname);
      if (room.mode === 'HYBRID') {
        if (room.transport) room.transport.localStableSinceMs = null;
        if (rotateCloudAfterRemove) {
          this._rotatePartialEpochToFreshCloud(room, nowMs, {
            nextLocalHostDeviceId: room.transport.localHostDeviceId === targetId ? room.ownerId : undefined,
            advanceBackoff: false
          });
        } else {
          const hostDeparted = this._handleHybridLocalHostDeparture(room, targetId, nowMs);
          if (!hostDeparted) {
            if (room.transport?.active === 'CLOUD') {
              this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'MEMBER_REMOVED' });
            } else if (room.transport?.active === 'LOCAL') {
              this._transitionTransport(room, 'LOCAL', 'MEMBER_REMOVED', nowMs, { preservePreconnect: false });
            }
          }
        }
      }
      this._revokeDeviceSessions(
        room,
        targetId,
        { status: 403, code: 'REMOVED', message: '你已被群主移出该网络房间' },
        nowMs
      );
      for (const [reqId, pending] of [...room.pendingRequests.entries()]) {
        if (pending.deviceId === targetId) {
          pending.status = 'REJECTED';
          pending.resolution = { status: 'REJECTED', code: 'REMOVED', message: '你已被群主移出该房间' };
          this._wakeRequestWaiters(pending, pending.resolution);
          room.pendingRequests.delete(reqId);
        }
      }
      room.revision += 1;
      this._pushEvent(room, targetId, {
        type: 'TERMINAL',
        revision: room.revision,
        reason: 'REMOVED',
        message: '你已被群主移出该网络房间'
      });
      this._broadcastSnapshot(room);
      this.log({ event: 'member_removed', roomId: room.roomId.slice(-8) });
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async unblockMember(token, roomId, targetDeviceId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可解除移除限制');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const targetId = normalizeId(targetDeviceId, 'targetDeviceId');
      if (room.removed.delete(targetId)) {
        room.revision += 1;
        this._broadcastSnapshot(room);
      }
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async leaveRoom(token, roomId, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      return this._leaveMemberUnlocked(room, member, nowMs);
    });
  }

  async _leaveMemberUnlocked(room, member, nowMs) {
    if (member.deviceId === room.ownerId) {
      if (room.members.size > 1) {
        this._assertRoomNotPendingEnd(room, nowMs);
        throw new CloudStoreError(
          409,
          'OWNER_TRANSFER_REQUIRED',
          '网络模式群主离开前必须先转让群主或解散全队'
        );
      }
      await this._endRoom(room, 'LAST_MEMBER_LEFT', '已结束并离开网络房间', nowMs);
      return { ended: true };
    }
    this._assertRoomNotPendingEnd(room, nowMs);
    this._settleRoomBilling(room, nowMs, { forceFlush: false, reason: 'MEMBER_LEAVE' });
    const admittedCountAfter = [...room.members.values()].filter(
      (m) => m.status === 'ADMITTED' && m.deviceId !== member.deviceId
    ).length;
    const willFallbackToCloud =
      room.mode === 'HYBRID' &&
      room.transport?.active === 'LOCAL' &&
      room.transport.localHostDeviceId === member.deviceId &&
      admittedCountAfter >= 2;
    if (willFallbackToCloud) {
      this._assertRtcRevocationReady();
    }
    const rotateCloudAfterLeave =
      room.mode === 'HYBRID' &&
      room.transport?.active === 'CLOUD' &&
      this._hasPendingEpochRevocation(room, room.transport.epoch) &&
      (room.transport.localHostDeviceId === member.deviceId || admittedCountAfter < 2);
    if (rotateCloudAfterLeave) {
      try {
        await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'MEMBER_LEFT', nowMs);
      } catch (error) {
        this._bumpPendingEpochRetry(room, room.transport.epoch, nowMs);
        throw error;
      }
    }
    if (room.mode === 'HYBRID' && member.channel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD') {
      const privateRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
      try {
        await this._revokeRtcRoom(room, privateRtcRoom, 'MEMBER_LEFT', nowMs);
      } catch (error) {
        this._recordPendingPrivateRevocation(room, privateRtcRoom, member.deviceId, 'MEMBER_LEFT', nowMs);
        throw error;
      }
      try {
        await this._revokeUserAllRtcGrants(room, member.deviceId, 'MEMBER_LEFT', nowMs, this.banRemovedForbiddenSeconds);
      } catch (error) {
        this._recordPendingPrivateRevocation(room, privateRtcRoom, member.deviceId, 'MEMBER_LEFT', nowMs);
        throw error;
      }
    } else {
      await this._revokeUserAllRtcGrants(room, member.deviceId, 'MEMBER_LEFT', nowMs, this.banRemovedForbiddenSeconds);
    }
    if (room.mode === 'HYBRID') {
      this._clearPrivateStateForMember(room, member.deviceId);
    }
    room.members.delete(member.deviceId);
    this._revokeDeviceSessions(room, member.deviceId);
    if (room.mode === 'HYBRID') {
      if (room.transport) room.transport.localStableSinceMs = null;
      if (rotateCloudAfterLeave) {
        this._rotatePartialEpochToFreshCloud(room, nowMs, {
          nextLocalHostDeviceId: room.transport.localHostDeviceId === member.deviceId ? room.ownerId : undefined,
          advanceBackoff: false
        });
      } else {
        this._handleHybridLocalHostDeparture(room, member.deviceId, nowMs);
        if (room.transport?.active === 'CLOUD' && admittedCountAfter < 2) {
          this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'INSUFFICIENT_MEMBERS' });
        }
      }
    }
    room.revision += 1;
    this._broadcastSnapshot(room);
    this.log({ event: 'member_left', roomId: room.roomId.slice(-8), seats: room.members.size });
    return { ended: false };
  }

  async endRoom(token, roomId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可解散全队');
      }
      this._assertExpectedRevision(room, expectedRevision);
      await this._endRoom(room, 'OWNER_ENDED', '群主已解散网络房间', nowMs);
      return { ended: true };
    });
  }

  async resumeMember(token, roomId, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member, session } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      if (room.removed.has(member.deviceId)) {
        throw new CloudStoreError(403, 'REMOVED', '你已被移出该房间');
      }
      const wasReserved = member.status === 'RESERVED';
      const demoteFromRider =
        wasReserved &&
        room.mode === 'HYBRID' &&
        member.channel?.type === 'RIDER' &&
        !canEnterRider(member.role, room.riderIncludesFormation);
      const nextChannel = demoteFromRider ? { type: 'PUBLIC' } : member.channel;
      const nextMember = { ...member, status: 'ADMITTED', channel: nextChannel };
      if (this._canIssueRtc(room, nextMember)) {
        this._assertRtcRoomIssuable(room, this._rtcRoomIdForMember(room, nextMember));
      }
      if (demoteFromRider && room.transport?.active === 'CLOUD') {
        const riderRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
        await this._revokeUserFromRtcRoom(room, riderRtcRoom, member.deviceId, 'ROLE_DEMOTED', nowMs);
      }
      const rtc = await this._issueRtcCredentialsIfAllowed(
        room,
        nextMember,
        nowMs
      );
      member.lastSeenMs = nowMs;
      if (wasReserved) {
        member.status = 'ADMITTED';
        member.reservedUntilMs = null;
        member.incarnation = room.nextIncarnation++;
        member.controlGeneration += 1;
        member.audioAvailable = false;
        member.localStatus = null;
        if (demoteFromRider) {
          member.channel = { type: 'PUBLIC' };
          member.channelRevision += 1;
        }
        session.incarnation = member.incarnation;
        session.controlGeneration = member.controlGeneration;
        if (room.transport) room.transport.localStableSinceMs = null;
        room.revision += 1;
        this._broadcastSnapshot(room);
        this.log({ event: 'member_resumed', roomId: room.roomId.slice(-8) });
      }
      const box = this._ensureMailbox(room, member.deviceId);
      return {
        resumed: wasReserved,
        latestSeq: box.latestSeq,
        snapshot: this._snapshotFor(room, member.deviceId),
        rtc
      };
    });
  }

  async setRole(token, roomId, { targetDeviceId, role, expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可分配成员角色');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const targetId = normalizeId(targetDeviceId, 'targetDeviceId');
      if (targetId === room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '不能修改群主角色');
      }
      const normalizedRole = typeof role === 'string' ? role.trim().toUpperCase() : '';
      if (!VALID_HYBRID_ROLES.has(normalizedRole)) {
        throw new CloudStoreError(400, 'INVALID_ROLE', '不支持的角色类型');
      }
      const target = room.members.get(targetId);
      if (!target) {
        throw new CloudStoreError(404, 'MEMBER_NOT_FOUND', '目标成员不在房间内');
      }
      const demotedFromRider = [];
      if (normalizedRole === 'HEAD' || normalizedRole === 'TAIL') {
        for (const item of room.members.values()) {
          if (item.deviceId !== targetId && item.role === normalizedRole) {
            if (item.channel?.type === 'RIDER' && !canEnterRider('MEMBER', room.riderIncludesFormation)) {
              demotedFromRider.push(item.deviceId);
            }
          }
        }
      }
      if (target.channel?.type === 'RIDER' && !canEnterRider(normalizedRole, room.riderIncludesFormation)) {
        demotedFromRider.push(target.deviceId);
      }
      if (room.transport?.active === 'CLOUD' && demotedFromRider.length > 0) {
        const riderRtcRoom = this._rtcRoomIdForChannel(room, { type: 'RIDER' }, room.transport.epoch);
        for (const deviceId of demotedFromRider) {
          await this._revokeUserFromRtcRoom(room, riderRtcRoom, deviceId, 'ROLE_DEMOTED', nowMs);
        }
      }
      if (normalizedRole === 'HEAD' || normalizedRole === 'TAIL') {
        for (const item of room.members.values()) {
          if (item.deviceId !== targetId && item.role === normalizedRole) {
            item.role = 'MEMBER';
            if (item.channel?.type === 'RIDER' && !canEnterRider(item.role, room.riderIncludesFormation)) {
              item.channel = { type: 'PUBLIC' };
              item.channelRevision += 1;
              item.audioAvailable = false;
            }
          }
        }
      }
      target.role = normalizedRole;
      if (target.channel?.type === 'RIDER' && !canEnterRider(target.role, room.riderIncludesFormation)) {
        target.channel = { type: 'PUBLIC' };
        target.channelRevision += 1;
        target.audioAvailable = false;
      }
      room.revision += 1;
      this._broadcastSnapshot(room);
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async setRiderIncludesFormation(token, roomId, { riderIncludesFormation, expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.deviceId !== room.ownerId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可修改领队频道编队策略');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const nextIncluded = Boolean(riderIncludesFormation);
      if (!nextIncluded && room.transport?.active === 'CLOUD') {
        const riderRtcRoom = this._rtcRoomIdForChannel(room, { type: 'RIDER' }, room.transport.epoch);
        for (const item of room.members.values()) {
          if (item.channel?.type === 'RIDER' && !canEnterRider(item.role, false)) {
            await this._revokeUserFromRtcRoom(room, riderRtcRoom, item.deviceId, 'RIDER_POLICY_UPDATED', nowMs);
          }
        }
      }
      room.riderIncludesFormation = nextIncluded;
      if (!room.riderIncludesFormation) {
        for (const item of room.members.values()) {
          if (item.channel?.type === 'RIDER' && !canEnterRider(item.role, false)) {
            item.channel = { type: 'PUBLIC' };
            item.channelRevision += 1;
            item.audioAvailable = false;
          }
        }
      }
      room.revision += 1;
      this._broadcastSnapshot(room);
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async switchChannel(token, roomId, { channel, expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'NOT_ADMITTED', '成员未处于在房状态');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const normalized = typeof channel === 'string' ? channel.trim().toUpperCase() : '';
      if (normalized !== 'PUBLIC' && normalized !== 'RIDER') {
        throw new CloudStoreError(400, 'INVALID_CHANNEL', '频道仅支持 PUBLIC 或 RIDER');
      }
      this._settleRoomBilling(room, nowMs, { forceFlush: false, reason: 'SWITCH_CHANNEL' });
      if (normalized === 'RIDER' && !canEnterRider(member.role, room.riderIncludesFormation)) {
        throw new CloudStoreError(403, 'FORBIDDEN', '当前角色无权进入领队频道');
      }
      const previousChannel = member.channel;
      const changingChannel = previousChannel?.type !== normalized;
      const nextMember = member.channel?.type !== normalized ? { ...member, channel: { type: normalized } } : member;
      if (this._canIssueRtc(room, nextMember)) {
        this._assertRtcRoomIssuable(room, this._rtcRoomIdForMember(room, nextMember));
      }
      if (changingChannel && room.transport?.active === 'CLOUD') {
        const oldRtcRoom = this._rtcRoomIdForChannel(room, previousChannel, room.transport.epoch);
        if (previousChannel?.type === 'PRIVATE') {
          try {
            await this._revokeRtcRoom(room, oldRtcRoom, 'CHANNEL_SWITCH', nowMs);
          } catch (error) {
            this._recordPendingPrivateRevocation(room, oldRtcRoom, member.deviceId, 'CHANNEL_SWITCH', nowMs);
            throw error;
          }
        } else {
          await this._revokeUserFromRtcRoom(room, oldRtcRoom, member.deviceId, 'CHANNEL_SWITCH', nowMs);
        }
      }
      let rtc;
      try {
        rtc = await this._issueRtcCredentialsIfAllowed(room, nextMember, nowMs);
      } catch (error) {
        if (previousChannel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD') {
          const oldRtcRoom = this._rtcRoomIdForChannel(room, previousChannel, room.transport.epoch);
          this._recordPendingPrivateRevocation(room, oldRtcRoom, member.deviceId, 'CHANNEL_SWITCH', nowMs);
        }
        throw error;
      }
      if (previousChannel?.type === 'PRIVATE') {
        this._clearPrivateStateForMember(room, member.deviceId);
      }
      if (member.channel?.type !== normalized) {
        member.channel = { type: normalized };
        member.channelRevision += 1;
        member.audioAvailable = false;
        room.revision += 1;
        this._broadcastSnapshot(room);
      } else if (previousChannel?.type === 'PRIVATE') {
        room.revision += 1;
        this._broadcastSnapshot(room);
      }
      return {
        snapshot: this._snapshotFor(room, member.deviceId),
        rtc
      };
    });
  }

  async requestPrivate(token, roomId, { targetDeviceId, expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'NOT_ADMITTED', '成员未处于在房状态');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const targetId = normalizeId(targetDeviceId, 'targetDeviceId');
      if (targetId === member.deviceId) {
        throw new CloudStoreError(400, 'INVALID_TARGET', '不能向自己发起私聊');
      }
      const target = room.members.get(targetId);
      if (!target || target.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'TARGET_NOT_ADMITTED', '对方当前不在线，无法发起私聊');
      }
      if (member.channel?.type === 'PRIVATE' || target.channel?.type === 'PRIVATE') {
        throw new CloudStoreError(409, 'PRIVATE_BUSY', '当前已有进行中的私聊');
      }
      for (const existing of room.privateInvites.values()) {
        if (
          existing.callerId === member.deviceId || existing.calleeId === member.deviceId ||
          existing.callerId === targetId || existing.calleeId === targetId
        ) {
          throw new CloudStoreError(409, 'PRIVATE_BUSY', '当前已有待处理的私聊邀请');
        }
      }
      const inviteId = crypto.randomUUID();
      room.privateInvites.set(inviteId, {
        inviteId,
        callerId: member.deviceId,
        calleeId: targetId,
        expiresAtMs: nowMs + this.privateInviteTtlMs
      });
      room.revision += 1;
      this._broadcastSnapshot(room);
      return { inviteId, snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async acceptPrivate(token, roomId, inviteId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'NOT_ADMITTED', '成员未处于在房状态');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const invite = room.privateInvites.get(inviteId);
      if (!invite || invite.expiresAtMs <= nowMs) {
        room.privateInvites.delete(inviteId);
        throw new CloudStoreError(404, 'INVITE_NOT_FOUND', '私聊邀请不存在或已超时');
      }
      if (invite.calleeId !== member.deviceId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅被呼叫方可以接受该私聊');
      }
      this._settleRoomBilling(room, nowMs, { forceFlush: false, reason: 'ACCEPT_PRIVATE' });
      const caller = room.members.get(invite.callerId);
      if (!caller || caller.status !== 'ADMITTED') {
        room.privateInvites.delete(inviteId);
        room.revision += 1;
        this._broadcastSnapshot(room);
        throw new CloudStoreError(409, 'CALLER_UNAVAILABLE', '发起方已离开或离线');
      }
      if (caller.channel?.type === 'PRIVATE' || member.channel?.type === 'PRIVATE') {
        room.privateInvites.delete(inviteId);
        throw new CloudStoreError(409, 'PRIVATE_BUSY', '成员已在私聊中');
      }
      const sessionId = crypto.randomUUID();
      const nextCalleeMember = { ...member, channel: { type: 'PRIVATE', sessionId } };
      if (room.transport?.active === 'CLOUD') {
        const nextPrivateRtcRoom = this._rtcRoomIdForChannel(room, nextCalleeMember.channel, room.transport.epoch);
        this._assertRtcRoomIssuable(room, nextPrivateRtcRoom);
        const callerOldRtcRoom = this._rtcRoomIdForChannel(room, caller.channel, room.transport.epoch);
        const calleeOldRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
        await this._revokeUserFromRtcRoom(room, callerOldRtcRoom, caller.deviceId, 'PRIVATE_ACCEPTED', nowMs);
        await this._revokeUserFromRtcRoom(room, calleeOldRtcRoom, member.deviceId, 'PRIVATE_ACCEPTED', nowMs);
      }
      const rtc = await this._issueRtcCredentialsIfAllowed(room, nextCalleeMember, nowMs);
      room.privateInvites.delete(inviteId);
      caller.channel = { type: 'PRIVATE', sessionId };
      caller.channelRevision += 1;
      caller.audioAvailable = false;
      member.channel = { type: 'PRIVATE', sessionId };
      member.channelRevision += 1;
      member.audioAvailable = false;
      room.revision += 1;
      this._broadcastSnapshot(room);
      return {
        snapshot: this._snapshotFor(room, member.deviceId),
        rtc
      };
    });
  }

  async declinePrivate(token, roomId, inviteId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      this._assertExpectedRevision(room, expectedRevision);
      const invite = room.privateInvites.get(inviteId);
      if (!invite) {
        throw new CloudStoreError(404, 'INVITE_NOT_FOUND', '私聊邀请不存在或已超时');
      }
      if (invite.calleeId !== member.deviceId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅被呼叫方可以拒绝该私聊');
      }
      room.privateInvites.delete(inviteId);
      room.revision += 1;
      this._broadcastSnapshot(room);
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async cancelPrivate(token, roomId, inviteId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      this._assertExpectedRevision(room, expectedRevision);
      const invite = room.privateInvites.get(inviteId);
      if (!invite) {
        throw new CloudStoreError(404, 'INVITE_NOT_FOUND', '私聊邀请不存在或已超时');
      }
      if (invite.callerId !== member.deviceId) {
        throw new CloudStoreError(403, 'FORBIDDEN', '仅发起方可以取消该私聊');
      }
      room.privateInvites.delete(inviteId);
      room.revision += 1;
      this._broadcastSnapshot(room);
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async closePrivate(token, roomId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      this._assertExpectedRevision(room, expectedRevision);
      this._settleRoomBilling(room, nowMs, { forceFlush: false, reason: 'CLOSE_PRIVATE' });
      if (member.channel?.type === 'PRIVATE') {
        const nextPublicMember = { ...member, channel: { type: 'PUBLIC' } };
        if (this._canIssueRtc(room, nextPublicMember)) {
          this._assertRtcRoomIssuable(room, this._rtcRoomIdForMember(room, nextPublicMember));
        }
        const privateRtcRoom =
          room.transport?.active === 'CLOUD'
            ? this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch)
            : null;
        if (room.transport?.active === 'CLOUD') {
          try {
            await this._revokeRtcRoom(room, privateRtcRoom, 'PRIVATE_CLOSED', nowMs);
          } catch (error) {
            this._recordPendingPrivateRevocation(room, privateRtcRoom, member.deviceId, 'PRIVATE_CLOSED', nowMs);
            throw error;
          }
        }
        let rtc;
        try {
          rtc = await this._issueRtcCredentialsIfAllowed(room, nextPublicMember, nowMs);
        } catch (error) {
          if (privateRtcRoom) {
            this._recordPendingPrivateRevocation(room, privateRtcRoom, member.deviceId, 'PRIVATE_CLOSED', nowMs);
          }
          throw error;
        }
        this._clearPrivateStateForMember(room, member.deviceId);
        room.revision += 1;
        this._broadcastSnapshot(room);
        return {
          snapshot: this._snapshotFor(room, member.deviceId),
          rtc
        };
      }
      return {
        snapshot: this._snapshotFor(room, member.deviceId),
        rtc: await this._issueRtcCredentialsIfAllowed(room, member, nowMs)
      };
    });
  }

  async switchTransport(
    token,
    roomId,
    { targetTransport, reason = '', expectedRevision, expectedTransportEpoch } = {},
    nowMs = this.now()
  ) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'NOT_ADMITTED', '成员未处于在房状态');
      }
      const target = typeof targetTransport === 'string' ? targetTransport.trim().toUpperCase() : '';
      if (target !== 'LOCAL' && target !== 'CLOUD') {
        throw new CloudStoreError(400, 'INVALID_TRANSPORT', '传输模式仅支持 LOCAL 或 CLOUD');
      }
      const normalizedReason = typeof reason === 'string' ? reason.trim().toUpperCase() : '';
      const isAutomaticFallback = target === 'CLOUD' && FALLBACK_TRANSPORT_REASONS.has(normalizedReason);
      if (!Number.isInteger(expectedTransportEpoch) || expectedTransportEpoch < 1) {
        throw new CloudStoreError(400, 'INVALID_REQUEST', '切换传输必须提供有效的 expectedTransportEpoch', {
          currentTransportEpoch: room.transport.epoch
        });
      }
      if (expectedTransportEpoch !== room.transport.epoch) {
        throw new CloudStoreError(409, 'TRANSPORT_CONFLICT', '传输状态已更新，请以最新快照为准', {
          currentTransportEpoch: room.transport.epoch,
          activeTransport: room.transport.active
        });
      }
      if (!isAutomaticFallback) {
        if (member.deviceId !== room.ownerId) {
          throw new CloudStoreError(403, 'FORBIDDEN', '仅群主可手动切换传输模式');
        }
        this._assertExpectedRevision(room, expectedRevision);
      }
      if (room.transport.active === target) {
        return {
          switched: false,
          snapshot: this._snapshotFor(room, member.deviceId),
          rtc: await this._issueRtcCredentialsIfAllowed(room, member, nowMs)
        };
      }
      let preUnbannedRtcRoomId = null;
      if (target === 'CLOUD') {
        this._assertSponsorQuotaAvailable(room, nowMs);
        this._assertRtcRevocationReady();
        const nextEpoch = room.transport.epoch + 1;
        const nextRtcRoomId = this._rtcRoomIdForChannel(room, member.channel, nextEpoch);
        this._assertRtcRoomIssuable(room, nextRtcRoomId, { epoch: nextEpoch });
        await this._unbanUserFromRtcRoom(room, nextRtcRoomId, member.rtcUserId, nowMs);
        preUnbannedRtcRoomId = nextRtcRoomId;
      } else if (room.transport.active === 'CLOUD') {
        try {
          await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'SWITCHED_TO_LOCAL', nowMs);
        } catch (error) {
          this._recordPendingEpochRevocation(
            room,
            {
              epoch: room.transport.epoch,
              intent: 'MANUAL_LOCAL',
              revokeReason: 'SWITCHED_TO_LOCAL',
              transportReason: 'MANUAL_LOCAL',
              nextLocalHostDeviceId: null,
              preservePreconnect: false,
              advanceBackoff: false
            },
            nowMs
          );
          throw error;
        }
      }
      const effectiveReason = isAutomaticFallback
        ? normalizedReason
        : (target === 'CLOUD' ? 'MANUAL_CLOUD' : 'MANUAL_LOCAL');
      const commitNowMs = Math.max(nowMs, this.now());
      let rtc = null;
      if (target === 'CLOUD') {
        this._assertRoomNotPendingEnd(room, commitNowMs);
        const finalQuota = this._assertSponsorQuotaAvailable(room, commitNowMs);
        this._transitionTransport(room, target, effectiveReason, commitNowMs);
        const activeRtcRoomId = this._rtcRoomIdForMember(room, member);
        if (activeRtcRoomId && activeRtcRoomId === preUnbannedRtcRoomId) {
          rtc = this._mintAndRecordRtcCredentialsSync(room, member, activeRtcRoomId, finalQuota, commitNowMs);
        }
      } else {
        this._transitionTransport(room, target, effectiveReason, commitNowMs);
      }
      room.revision += 1;
      this._broadcastSnapshot(room);
      return {
        switched: true,
        snapshot: this._snapshotFor(room, member.deviceId),
        rtc
      };
    });
  }

  async reportLocalStatus(
    token,
    roomId,
    {
      transportEpoch,
      localReady = false,
      localReachable = false,
      localProbeActive = false,
      localRssiDbm = null,
      localControlReady = false,
      localHostAddress = null,
      localFrequencyMhz = null,
      preconnectFailed = false
    } = {},
    nowMs = this.now()
  ) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'NOT_ADMITTED', '成员未处于在房状态');
      }
      const normalizedRssi = normalizeOptionalRssiDbm(localRssiDbm);
      const normalizedHostAddress = normalizeOptionalIpv4(localHostAddress);
      const normalizedFrequency = normalizeOptionalFrequencyMhz(localFrequencyMhz);
      if (!Number.isInteger(transportEpoch) || transportEpoch !== room.transport.epoch) {
        return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
      }
      member.localStatus = {
        epoch: transportEpoch,
        ready: Boolean(localReady),
        reachable: Boolean(localReachable),
        probeActive: Boolean(localProbeActive),
        rssiDbm: normalizedRssi,
        controlReady: Boolean(localControlReady),
        preconnectFailed: Boolean(preconnectFailed),
        reportedAtMs: nowMs
      };
      let hostMetadataChanged = false;
      if (member.deviceId === room.transport.localHostDeviceId) {
        if (normalizedHostAddress !== null && room.transport.localHostAddress !== normalizedHostAddress) {
          room.transport.localHostAddress = normalizedHostAddress;
          hostMetadataChanged = true;
        }
        if (normalizedFrequency !== null && room.transport.localFrequencyMhz !== normalizedFrequency) {
          room.transport.localFrequencyMhz = normalizedFrequency;
          hostMetadataChanged = true;
        }
      }
      if (room.transport.active === 'LOCAL') {
        const admitted = [...room.members.values()].filter((item) => item.status === 'ADMITTED');
        const allLocallyReady =
          admitted.length >= 2 &&
          admitted.every(
            (item) =>
              item.localStatus?.epoch === room.transport.epoch &&
              item.localStatus.ready === true &&
              nowMs - item.localStatus.reportedAtMs <= this.localStatusFreshMs
          );
        if (allLocallyReady) {
          if (room.transport.localStableSinceMs === null) {
            room.transport.localStableSinceMs = nowMs;
          } else if (
            room.transport.fallbackStreak > 0 &&
            nowMs - room.transport.localStableSinceMs >= this.localStableResetMs
          ) {
            room.transport.fallbackStreak = 0;
          }
        } else {
          room.transport.localStableSinceMs = null;
        }
        if (hostMetadataChanged) {
          room.revision += 1;
          this._broadcastSnapshot(room);
        }
        return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
      }
      if (
        room.transport.active === 'CLOUD' &&
        room.transport.reason !== 'MANUAL_CLOUD' &&
        nowMs >= room.transport.cooldownUntilMs
      ) {
        const admitted = [...room.members.values()].filter((item) => item.status === 'ADMITTED');
        if (admitted.length >= 2) {
          const host = this._resolveAdmittedLocalHost(room);
          const hostUnavailable = !host;
          const hostReassigned = Boolean(host && room.transport.localHostDeviceId !== host.deviceId);
          const pendingEpoch =
            room.pendingEpochRevocation?.epoch === room.transport.epoch ? room.pendingEpochRevocation : null;
          if (hostUnavailable || hostReassigned) {
            if (this._hasPendingEpochRevocation(room, room.transport.epoch)) {
              const revokeReason = hostUnavailable ? 'LOCAL_HOST_LOST' : 'LOCAL_HOST_REASSIGNED';
              if (pendingEpoch && !this._isPendingRetryDue(pendingEpoch, nowMs)) {
                this._updatePendingEpochIntentPreservingBackoff(room, {
                  epoch: room.transport.epoch,
                  intent: 'CLOUD_ROTATE',
                  revokeReason,
                  transportReason: this._cloudRecoveryReason(room),
                  nextLocalHostDeviceId: host?.deviceId ?? null,
                  preservePreconnect: false,
                  advanceBackoff: false
                });
                throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '云端语音吊销重试冷却中，请稍后重试');
              }
              try {
                await this._revokeRoomRtcForEpoch(room, room.transport.epoch, revokeReason, nowMs);
              } catch (error) {
                this._recordPendingEpochRevocation(
                  room,
                  {
                    epoch: room.transport.epoch,
                    intent: 'CLOUD_ROTATE',
                    revokeReason,
                    transportReason: this._cloudRecoveryReason(room),
                    nextLocalHostDeviceId: host?.deviceId ?? null,
                    preservePreconnect: false,
                    advanceBackoff: false
                  },
                  nowMs
                );
                throw error;
              }
              this._rotatePartialEpochToFreshCloud(room, nowMs, {
                nextLocalHostDeviceId: host?.deviceId ?? undefined,
                advanceBackoff: false
              });
              room.revision += 1;
              this._broadcastSnapshot(room);
              return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
            }
            let mutated = hostMetadataChanged;
            if (hostReassigned) {
              room.transport.localHostDeviceId = host.deviceId;
              mutated = true;
            }
            if (
              this._abortHybridPreconnect(room, nowMs, {
                advanceBackoff: false,
                reason: hostUnavailable ? 'LOCAL_HOST_LOST' : 'LOCAL_HOST_REASSIGNED'
              })
            ) {
              mutated = true;
            }
            if (mutated) {
              room.revision += 1;
              this._broadcastSnapshot(room);
            }
            return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
          }
          if (
            room.pendingEpochRevocation?.epoch === room.transport.epoch &&
            (room.pendingEpochRevocation.intent === 'MANUAL_LOCAL' ||
              room.pendingEpochRevocation.intent === 'CLOUD_ROTATE')
          ) {
            if (hostMetadataChanged) {
              room.revision += 1;
              this._broadcastSnapshot(room);
            }
            return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
          }
          if (room.transport.recoveryStage === 'PRECONNECTING') {
            const hasPartialRevocation = this._hasPendingEpochRevocation(room, room.transport.epoch);
            const timedOut =
              room.transport.preconnectStartedAtMs !== null &&
              nowMs - room.transport.preconnectStartedAtMs >= this.localPreconnectTimeoutMs;
            const startedAt = room.transport.preconnectStartedAtMs ?? room.transport.cooldownUntilMs;
            const allControlReady =
              Boolean(host && host.status === 'ADMITTED') &&
              admitted.every(
                (item) =>
                  item.localStatus?.epoch === room.transport.epoch &&
                  item.localStatus.controlReady === true &&
                  item.localStatus.reportedAtMs >= startedAt &&
                  nowMs - item.localStatus.reportedAtMs <= this.localStatusFreshMs
              );
            if (!Boolean(preconnectFailed) && allControlReady && (!timedOut || hasPartialRevocation)) {
              if (pendingEpoch?.intent === 'PRECONNECT_LOCAL' && !this._isPendingRetryDue(pendingEpoch, nowMs)) {
                throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '云端语音吊销重试冷却中，请稍后重试');
              }
              try {
                await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'LOCAL_RECOVERED', nowMs);
              } catch (error) {
                this._recordPendingEpochRevocation(
                  room,
                  {
                    epoch: room.transport.epoch,
                    intent: 'PRECONNECT_LOCAL',
                    revokeReason: 'LOCAL_RECOVERED',
                    transportReason: 'LOCAL_RECOVERED',
                    nextLocalHostDeviceId: host.deviceId,
                    preservePreconnect: true,
                    advanceBackoff: false
                  },
                  nowMs
                );
                throw error;
              }
              this._transitionTransport(room, 'LOCAL', 'LOCAL_RECOVERED', nowMs, {
                nextLocalHostDeviceId: host.deviceId,
                preservePreconnect: true
              });
              room.revision += 1;
              this._broadcastSnapshot(room);
              return { switched: true, snapshot: this._snapshotFor(room, member.deviceId) };
            }
            if (Boolean(preconnectFailed) || timedOut) {
              if (hasPartialRevocation) {
                if (pendingEpoch && !this._isPendingRetryDue(pendingEpoch, nowMs)) {
                  this._updatePendingEpochIntentPreservingBackoff(room, {
                    epoch: room.transport.epoch,
                    intent: 'CLOUD_ROTATE',
                    revokeReason: 'PRECONNECT_ABORTED',
                    transportReason: this._cloudRecoveryReason(room),
                    nextLocalHostDeviceId: null,
                    preservePreconnect: false,
                    advanceBackoff: true
                  });
                  if (hostMetadataChanged) {
                    room.revision += 1;
                    this._broadcastSnapshot(room);
                  }
                  return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
                }
                try {
                  await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'PRECONNECT_ABORTED', nowMs);
                } catch (error) {
                  this._recordPendingEpochRevocation(
                    room,
                    {
                      epoch: room.transport.epoch,
                      intent: 'CLOUD_ROTATE',
                      revokeReason: 'PRECONNECT_ABORTED',
                      transportReason: this._cloudRecoveryReason(room),
                      nextLocalHostDeviceId: null,
                      preservePreconnect: false,
                      advanceBackoff: true
                    },
                    nowMs
                  );
                  throw error;
                }
                this._rotatePartialEpochToFreshCloud(room, nowMs, { advanceBackoff: true });
                room.revision += 1;
                this._broadcastSnapshot(room);
                return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
              }
              this._abortHybridPreconnect(room, nowMs, {
                advanceBackoff: true,
                reason: preconnectFailed ? 'PRECONNECT_FAILED' : 'PRECONNECT_TIMEOUT'
              });
              room.revision += 1;
              this._broadcastSnapshot(room);
              return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
            }
            if (hostMetadataChanged) {
              room.revision += 1;
              this._broadcastSnapshot(room);
            }
            return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
          }
          const hostFresh =
            host &&
            host.status === 'ADMITTED' &&
            host.localStatus?.epoch === room.transport.epoch &&
            host.localStatus.probeActive === true &&
            host.localStatus.reportedAtMs >= room.transport.cooldownUntilMs &&
            nowMs - host.localStatus.reportedAtMs <= this.localStatusFreshMs;
          // localRssiDbm === null is the explicit high-power BLE proximity fallback path when a device
          // lacks fine-location permission for Wi-Fi scan results (client enforces BLE RSSI >= -75 dBm locally).
          // Whenever Wi-Fi RSSI is reported, enforce the >= -75 dBm margin to prevent edge ping-pong.
          const allPeersReachable =
            Boolean(hostFresh) &&
            admitted
              .filter((item) => item.deviceId !== host.deviceId)
              .every(
                (item) =>
                  item.localStatus?.epoch === room.transport.epoch &&
                  item.localStatus.reachable === true &&
                  (item.localStatus.rssiDbm === null || item.localStatus.rssiDbm >= this.localRssiThresholdDbm) &&
                  item.localStatus.reportedAtMs >= room.transport.cooldownUntilMs &&
                  nowMs - item.localStatus.reportedAtMs <= this.localStatusFreshMs
              );
          if (allPeersReachable) {
            if (room.transport.recoverySignalStableSinceMs === null) {
              room.transport.recoverySignalStableSinceMs = nowMs;
            }
            const signalStable = nowMs - room.transport.recoverySignalStableSinceMs >= this.localSignalStableMs;
            if (signalStable) {
              room.transport.recoveryStage = 'PRECONNECTING';
              room.transport.preconnectStartedAtMs = nowMs;
              room.transport.localCode = generateLocalCode(room.transport.lastLocalCode);
              for (const item of room.members.values()) {
                if (item.localStatus) {
                  item.localStatus.controlReady = false;
                  item.localStatus.preconnectFailed = false;
                }
              }
              room.revision += 1;
              this._broadcastSnapshot(room);
              return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
            }
            if (room.transport.recoveryStage !== 'PROBING' || hostMetadataChanged) {
              room.transport.recoveryStage = 'PROBING';
              room.revision += 1;
              this._broadcastSnapshot(room);
            }
          } else {
            room.transport.recoverySignalStableSinceMs = null;
            if (room.transport.recoveryStage === 'PROBING' && !hostFresh) {
              room.transport.recoveryStage = 'IDLE';
              room.revision += 1;
              this._broadcastSnapshot(room);
            } else if (hostMetadataChanged) {
              room.revision += 1;
              this._broadcastSnapshot(room);
            }
          }
        }
      } else if (hostMetadataChanged) {
        room.revision += 1;
        this._broadcastSnapshot(room);
      }
      return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async refreshRtcCredentials(token, roomId, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      if (member.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'NOT_ADMITTED', '成员未处于在房状态');
      }
      if (room.mode === 'HYBRID' && room.transport?.active !== 'CLOUD') {
        throw new CloudStoreError(
          409,
          'TRANSPORT_NOT_CLOUD',
          '当前房间处于本地直连模式，禁止签发云端 RTC 凭证'
        );
      }
      member.lastSeenMs = nowMs;
      return this._issueRtcCredentialsIfAllowed(room, member, nowMs);
    });
  }

  async updateAudioAvailability(token, roomId, { available }, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._assertRoomNotPendingEnd(room, nowMs);
      member.lastSeenMs = nowMs;
      if (member.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'NOT_ADMITTED', '成员未处于在房状态');
      }
      const next = Boolean(available);
      if (member.audioAvailable !== next) {
        member.audioAvailable = next;
        room.revision += 1;
        this._broadcastSnapshot(room);
      }
      return { snapshot: this._snapshotFor(room, member.deviceId) };
    });
  }

  async ackEvents(token, roomId, { sequence }, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      member.lastSeenMs = nowMs;
      const seq = Number(sequence);
      if (!Number.isInteger(seq) || seq < 0) {
        throw new CloudStoreError(400, 'INVALID_SEQUENCE', '确认序号无效');
      }
      const box = this._ensureMailbox(room, member.deviceId);
      while (box.events.length > 0 && box.events[0].seq <= seq) {
        box.events.shift();
      }
      box.minSeq = box.events.length > 0 ? box.events[0].seq : box.latestSeq + 1;
      return { acked: seq, latestSeq: box.latestSeq };
    });
  }

  async pollEvents(token, roomId, { after = 0, waitMs = 0, signal = null } = {}, nowMs = this.now()) {
    const afterSeq = Math.max(0, Number.isInteger(Number(after)) ? Number(after) : 0);
    const clampedWait = Math.max(0, Math.min(20_000, Number(waitMs) || 0));
    let waiterPromise = null;

    const immediate = await this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      member.lastSeenMs = nowMs;
      const box = this._ensureMailbox(room, member.deviceId);
      const collect = () => {
        if (afterSeq > box.latestSeq) {
          return {
            resync: true,
            latestSeq: box.latestSeq,
            snapshot: this._snapshotFor(room, member.deviceId),
            events: []
          };
        }
        if (box.events.length > 0 && afterSeq + 1 < box.events[0].seq) {
          return {
            resync: true,
            latestSeq: box.latestSeq,
            snapshot: this._snapshotFor(room, member.deviceId),
            events: []
          };
        }
        if (afterSeq < box.latestSeq && box.events.length === 0) {
          return {
            resync: true,
            latestSeq: box.latestSeq,
            snapshot: this._snapshotFor(room, member.deviceId),
            events: []
          };
        }
        const pending = box.events.filter((e) => e.seq > afterSeq);
        if (pending.length > 0) {
          return {
            resync: false,
            latestSeq: box.latestSeq,
            events: pending
          };
        }
        return null;
      };

      const batch = collect();
      if (batch) return batch;
      if (clampedWait === 0 || signal?.aborted) {
        return { resync: false, latestSeq: box.latestSeq, events: [] };
      }

      let resolveWaiter;
      waiterPromise = new Promise((resolve) => {
        resolveWaiter = resolve;
      });
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        box.waiters.delete(finish);
        signal?.removeEventListener('abort', onAbort);
        member.lastSeenMs = this.now();
        const nextBatch = collect();
        resolveWaiter(nextBatch ?? { resync: false, latestSeq: box.latestSeq, events: [] });
      };
      const onAbort = () => finish();
      const timer = setTimeout(finish, clampedWait);
      box.waiters.add(finish);
      signal?.addEventListener('abort', onAbort, { once: true });
      return null;
    });

    if (immediate) return immediate;
    return waiterPromise;
  }

  listActiveRoomsSummary(nowMs = this.now()) {
    const list = [];
    for (const room of this.rooms.values()) {
      if (room.ended) continue;
      const admittedMembers = [...room.members.values()].filter((m) => m.status === 'ADMITTED').length;
      const activeCloudMembers = this._countActiveCloudRtcMembers(room);
      const totalBilledSeconds = room.billing?.totalBilledSeconds ?? 0;
      list.push({
        roomId: room.roomId,
        mode: room.mode,
        activeTransport: room.mode === 'HYBRID' ? (room.transport?.active ?? 'LOCAL') : 'CLOUD',
        sponsorEntitlementId: room.sponsorEntitlementId ?? null,
        ownerId: room.ownerId,
        maxMembers: room.maxMembers,
        memberCount: room.members.size,
        admittedMembers,
        activeCloudMembers,
        totalBilledSeconds,
        totalBilledMinutes: Math.floor(totalBilledSeconds / 60),
        createdAtMs: room.createdAtMs ?? nowMs,
        durationMinutes: Math.floor(Math.max(0, nowMs - (room.createdAtMs ?? nowMs)) / 60_000)
      });
    }
    return list;
  }

  async forceEndRoomByAdmin(roomId, { reason = 'ADMIN_ENDED', message = '管理员已结束该云端房间' } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const room = this.rooms.get(roomId);
      if (!room || room.ended) {
        throw new CloudStoreError(404, 'ROOM_NOT_FOUND', '目标房间不存在或已结束');
      }
      await this._endRoom(room, reason, message, nowMs);
      return { roomId, ended: true, reason };
    });
  }

  async terminateRoomsForEntitlement(
    entitlementId,
    { reason = 'ENTITLEMENT_REVOKED', message = '房主云端对讲权限已失效，房间已结束' } = {},
    nowMs = this.now()
  ) {
    const targets = [];
    for (const room of this.rooms.values()) {
      if (!room.ended && room.sponsorEntitlementId === entitlementId) {
        targets.push(room.roomId);
      }
    }
    const endedRoomIds = [];
    const pendingRoomIds = [];
    for (const roomId of targets) {
      await this._withRoomLock(roomId, async () => {
        const room = this.rooms.get(roomId);
        if (!room || room.ended || room.sponsorEntitlementId !== entitlementId) return;
        try {
          await this._endRoom(room, reason, message, nowMs);
          endedRoomIds.push(roomId);
        } catch (error) {
          pendingRoomIds.push(roomId);
          this.log({
            event: 'terminate_entitlement_room_pending',
            roomId: roomId.slice(-8),
            reason,
            message: error?.message ?? 'unknown'
          });
        }
      });
    }
    return { endedRoomIds, pendingRoomIds };
  }

  async terminateAllActiveRooms(
    { reason = 'GLOBAL_QUOTA_EXHAUSTED', message = '本月云端测试总免费额度已用尽，通话已结束，请使用本地直连模式' } = {},
    nowMs = this.now()
  ) {
    const targets = [...this.rooms.values()].filter((r) => !r.ended).map((r) => r.roomId);
    const endedRoomIds = [];
    const pendingRoomIds = [];
    for (const roomId of targets) {
      await this._withRoomLock(roomId, async () => {
        const room = this.rooms.get(roomId);
        if (!room || room.ended) return;
        try {
          await this._endRoom(room, reason, message, nowMs);
          endedRoomIds.push(roomId);
        } catch (error) {
          pendingRoomIds.push(roomId);
          this.log({
            event: 'terminate_all_room_pending',
            roomId: roomId.slice(-8),
            reason,
            message: error?.message ?? 'unknown'
          });
        }
      });
    }
    return { endedRoomIds, pendingRoomIds };
  }

  flushAllPendingBilling(nowMs = this.now()) {
    if (!this.entitlementStore) return;
    for (const room of this.rooms.values()) {
      if (!room.ended && room.sponsorEntitlementId && room.billing) {
        this._settleRoomBilling(room, nowMs, { forceFlush: true, reason: 'SERVER_SHUTDOWN', emitWarnings: false });
      }
    }
  }
}
