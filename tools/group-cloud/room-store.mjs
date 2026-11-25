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

    this.rooms = new Map();
    this.inviteIndex = new Map();
    this.sessions = new Map();
    this.revokedTokens = new Map();
    this.pendingIndex = new Map();
    this.cancelledJoinReceipts = new Map();
    this.roomLocks = new Map();
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

  async _issueRtcCredentialsIfAllowed(room, member, nowMs) {
    if (this._isOwnerRecoveryExpired(room, nowMs)) {
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '群主恢复已超时，房间正在等待吊销结束');
    }
    if (!this._canIssueRtc(room, member)) return null;
    this._assertRtcRevocationReady();
    const rtcRoomId = this._rtcRoomIdForMember(room, member);
    const userId = member.rtcUserId;
    await this._unbanUserFromRtcRoom(room, rtcRoomId, userId, nowMs);
    const credentials = mintRtcCredentials({
      appId: this.appId,
      appKey: this.appKey,
      rtcRoomId,
      rtcUserId: userId,
      nowMs,
      ttlMs: this.rtcTokenTtlMs
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
    await this._revokeUserAllRtcGrants(room, deviceId, reason, nowMs, floorSec);
    const priorBans = [...room.bannedRtcPairs.entries()].filter(
      ([, record]) =>
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

  async _revokeRtcRoom(room, rtcRoomId, reason, nowMs, floorSec = this.banRoomForbiddenSeconds) {
    if (!rtcRoomId) return;
    const meta = room.issuedRtcRoomIds.get(rtcRoomId);
    if (!meta || meta.maxExpiresAtMs <= nowMs) {
      if (meta) room.issuedRtcRoomIds.delete(rtcRoomId);
      return;
    }
    this._assertRtcRevocationReady();
    const forbiddenIntervalSec = this._forbiddenSecondsForExpiry(meta.maxExpiresAtMs, nowMs, floorSec);
    try {
      await this.onRtcBanRoom(rtcRoomId, { reason, forbiddenIntervalSec });
    } catch (error) {
      this.log({ event: 'rtc_ban_room_failed', roomId: room.roomId.slice(-8), message: error?.message ?? 'unknown' });
      throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '无法吊销云端语音房间，请稍后重试');
    }
    room.issuedRtcRoomIds.delete(rtcRoomId);
    for (const [key, grant] of [...room.issuedRtcGrants.entries()]) {
      if (grant.rtcRoomId === rtcRoomId) room.issuedRtcGrants.delete(key);
    }
    for (const key of [...room.bannedRtcPairs.keys()]) {
      if (key.startsWith(`${rtcRoomId}\0`)) room.bannedRtcPairs.delete(key);
    }
  }

  async _revokeRoomRtcForEpoch(room, epoch, reason, nowMs) {
    const minFloorSec = Math.ceil(this.rtcTokenTtlMs / 1000) + 60;
    const targets = [...room.issuedRtcRoomIds.entries()]
      .filter(([, meta]) => meta.epoch === epoch && meta.maxExpiresAtMs > nowMs)
      .map(([rtcRoomId]) => rtcRoomId);
    for (const rtcRoomId of targets) {
      await this._revokeRtcRoom(room, rtcRoomId, reason, nowMs, minFloorSec);
    }
  }

  async _revokeAllRoomRtc(room, reason, nowMs) {
    const targets = [...room.issuedRtcRoomIds.keys()];
    for (const rtcRoomId of targets) {
      await this._revokeRtcRoom(room, rtcRoomId, reason, nowMs, this.banRoomForbiddenSeconds);
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
    }
    return changed;
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
    const transport = room.transport;
    const previous = transport.active;
    const previousEpoch = transport.epoch;
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
      if (FALLBACK_TRANSPORT_REASONS.has(reason)) {
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
        hostId = room.ownerId;
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
    await this._revokeAllRoomRtc(room, reason, nowMs);
    this._finalizeEndedRoom(room, reason, message, nowMs);
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

  async _sweepRoomUnlocked(room, nowMs = this.now(), { throwOnError = false } = {}) {
    if (!room || room.ended) return { changed: false, errors: [] };
    const errors = [];

    if (this._isOwnerRecoveryExpired(room, nowMs)) {
      try {
        await this._endRoom(room, 'OWNER_RECOVERY_EXPIRED', '群主断线超过 60 秒，网络房间已结束', nowMs);
        return { changed: true, errors };
      } catch (error) {
        this.log({
          event: 'rtc_sweep_revocation_failed',
          roomId: room.roomId.slice(-8),
          action: 'OWNER_RECOVERY_EXPIRED',
          message: error?.message ?? 'unknown'
        });
        if (throwOnError) throw error;
        errors.push(error);
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
        try {
          if (willFallbackToCloud) {
            this._assertRtcRevocationReady();
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
        if (room.mode === 'HYBRID') {
          this._clearPrivateStateForMember(room, deviceId);
        }
        room.members.delete(deviceId);
        if (room.mode === 'HYBRID') {
          if (room.transport) room.transport.localStableSinceMs = null;
          this._handleHybridLocalHostDeparture(room, deviceId, nowMs);
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

    if (room.mode === 'HYBRID' && !room.ended && room.transport?.active === 'CLOUD') {
      const admittedCount = [...room.members.values()].filter((item) => item.status === 'ADMITTED').length;
      if (room.transport.recoveryStage === 'PRECONNECTING') {
        if (admittedCount < 2) {
          if (this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'INSUFFICIENT_MEMBERS' })) {
            changed = true;
          }
        } else if (
          room.transport.preconnectStartedAtMs !== null &&
          nowMs - room.transport.preconnectStartedAtMs >= this.localPreconnectTimeoutMs
        ) {
          if (this._abortHybridPreconnect(room, nowMs, { advanceBackoff: true, reason: 'PRECONNECT_TIMEOUT' })) {
            changed = true;
          }
        }
      } else if (admittedCount < 2 && room.transport.recoveryStage === 'PROBING') {
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

  async sweepRoom(roomId, nowMs = this.now(), options = {}) {
    this._sweepGlobalExpired(nowMs);
    return this._sweepLockedRoom(roomId, nowMs, options);
  }

  async _sweepLockedRoom(roomId, nowMs, options) {
    if (!roomId || !this.rooms.has(roomId)) return { changed: false, errors: [] };
    return this._withRoomLock(roomId, async () => {
      const room = this.rooms.get(roomId);
      if (!room || room.ended) return { changed: false, errors: [] };
      return this._sweepRoomUnlocked(room, nowMs, options);
    });
  }

  async sweep(nowMs = this.now(), { throwOnError = false } = {}) {
    this._sweepGlobalExpired(nowMs);
    const roomIds = [...this.rooms.keys()];
    const errors = [];
    let changed = false;
    for (const roomId of roomIds) {
      const result = await this._sweepLockedRoom(roomId, nowMs, { throwOnError: false });
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
        await this._sweepRoomUnlocked(preRoom, nowMs, { throwOnError: false });
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

  async createRoom({ deviceId, runtimeId, nickname, requireApproval = true, mode = 'NETWORK', supportsFastP2p }, nowMs = this.now()) {
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
    const roomId = crypto.randomUUID();
    const roomEpoch = crypto.randomUUID();
    const rtcRoomId = `mcr-${crypto.randomUUID().replaceAll('-', '')}`;
    const isHybrid = normalizedMode === 'HYBRID';
    const roomMaxMembers = isHybrid ? Math.min(this.maxMembers, HYBRID_MAX_MEMBERS) : this.maxMembers;
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
      mode: normalizedMode,
      maxMembers: roomMaxMembers,
      ownerId: dev,
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
        if (prior.status === 'ADMITTED') return prior.resolution;
        if (prior.status !== 'PENDING') throw new CloudStoreError(410, 'REQUEST_ENDED', '加入申请已结束');
        return { status: 'PENDING', roomId: prior.roomId, requestId: id, requestSecret: prior.secret, expiresAtMs: prior.expiresAtMs };
      }
      return this._submitJoinRequest(params, { id, secret: params.joinSecret }, nowMs);
    });
  }

  async _submitJoinRequest({ inviteCode, deviceId, runtimeId, nickname, supportsFastP2p }, receipt, nowMs) {
    this._sweepGlobalExpired(nowMs);
    const code = normalizeInviteCode(inviteCode);
    const dev = normalizeId(deviceId, 'deviceId');
    const run = normalizeId(runtimeId, 'runtimeId');
    const name = normalizeNickname(nickname, dev);
    const roomId = this.inviteIndex.get(code);
    if (!roomId) {
      throw new CloudStoreError(404, 'INVALID_INVITE', '邀请码无效或已过期');
    }
    return this._withRoomLock(roomId, async () => {
      const effectiveNow = nowMs;
      const preRoom = this.rooms.get(roomId);
      if (preRoom && !preRoom.ended) {
        await this._sweepRoomUnlocked(preRoom, effectiveNow, { throwOnError: false });
      }
      const room = this.rooms.get(roomId);
      if (!room || room.ended || !room.invite || room.invite.expiresAtMs <= effectiveNow || !timingSafeStringEqual(room.invite.code, code)) {
        throw new CloudStoreError(404, 'INVALID_INVITE', '邀请码无效或已过期');
      }
      if (this._isOwnerRecoveryExpired(room, effectiveNow)) {
        throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '群主恢复已超时，房间正在等待吊销结束');
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
    await this._sweepLockedRoom(pending.roomId, nowMs, { throwOnError: false });
    if (pending.status !== 'PENDING') {
      const resolution = pending.resolution;
      if (pending.status !== 'ADMITTED') this.pendingIndex.delete(requestId);
      return resolution;
    }
    const clampedWait = Math.max(0, Math.min(20_000, Number(waitMs) || 0));
    if (clampedWait === 0) {
      return { status: 'PENDING', requestId, expiresAtMs: pending.expiresAtMs };
    }
    if (signal?.aborted) {
      return { status: 'PENDING', requestId, expiresAtMs: pending.expiresAtMs };
    }
    return new Promise((resolve) => {
      let settled = false;
      const cleanupAndResolve = (payload) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        pending.waiters.delete(onWake);
        signal?.removeEventListener('abort', onAbort);
        resolve(payload);
      };
      const onAbort = () => cleanupAndResolve({ status: 'PENDING', requestId, expiresAtMs: pending.expiresAtMs });
      const onWake = (payload) => {
        if (pending.status !== 'ADMITTED') this.pendingIndex.delete(requestId);
        cleanupAndResolve(payload);
      };
      const timer = setTimeout(() => {
        void this.sweepRoom(pending.roomId, this.now(), { throwOnError: false })
          .catch(() => {})
          .finally(() => {
            if (pending.status !== 'PENDING') {
              const res = pending.resolution;
              if (pending.status !== 'ADMITTED') this.pendingIndex.delete(requestId);
              cleanupAndResolve(res);
            } else {
              cleanupAndResolve({ status: 'PENDING', requestId, expiresAtMs: pending.expiresAtMs });
            }
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
        await this._sweepRoomUnlocked(room, nowMs, { throwOnError: false });
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
      if (this._isOwnerRecoveryExpired(room, nowMs)) {
        throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '群主恢复已超时，房间正在等待吊销结束');
      }
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
      if (alsoLeave) {
        if (room.mode === 'HYBRID' && member.channel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD') {
          const privateRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
          await this._revokeRtcRoom(room, privateRtcRoom, 'OWNER_TRANSFER_LEAVE', nowMs);
        }
        await this._revokeUserAllRtcGrants(room, member.deviceId, 'OWNER_TRANSFER_LEAVE', nowMs);
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
          this._handleHybridLocalHostDeparture(room, member.deviceId, nowMs);
        }
      } else if (room.mode === 'HYBRID') {
        this._handleHybridLocalHostDeparture(room, member.deviceId, nowMs);
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
      if (room.mode === 'HYBRID' && target.channel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD') {
        const privateRtcRoom = this._rtcRoomIdForChannel(room, target.channel, room.transport.epoch);
        await this._revokeRtcRoom(room, privateRtcRoom, 'REMOVED', nowMs);
      }
      await this._revokeUserAllRtcGrants(room, targetId, 'REMOVED', nowMs, this.banRemovedForbiddenSeconds);
      if (room.mode === 'HYBRID') {
        this._clearPrivateStateForMember(room, targetId);
      }
      room.members.delete(targetId);
      room.removed.set(targetId, target.nickname);
      if (room.mode === 'HYBRID') {
        if (room.transport) room.transport.localStableSinceMs = null;
        const hostDeparted = this._handleHybridLocalHostDeparture(room, targetId, nowMs);
        if (!hostDeparted) {
          if (room.transport?.active === 'CLOUD') {
            this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'MEMBER_REMOVED' });
          } else if (room.transport?.active === 'LOCAL') {
            this._transitionTransport(room, 'LOCAL', 'MEMBER_REMOVED', nowMs, { preservePreconnect: false });
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
        throw new CloudStoreError(
          409,
          'OWNER_TRANSFER_REQUIRED',
          '网络模式群主离开前必须先转让群主或解散全队'
        );
      }
      await this._endRoom(room, 'LAST_MEMBER_LEFT', '已结束并离开网络房间', nowMs);
      return { ended: true };
    }
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
    if (room.mode === 'HYBRID' && member.channel?.type === 'PRIVATE' && room.transport?.active === 'CLOUD') {
      const privateRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
      await this._revokeRtcRoom(room, privateRtcRoom, 'MEMBER_LEFT', nowMs);
    }
    await this._revokeUserAllRtcGrants(room, member.deviceId, 'MEMBER_LEFT', nowMs, this.banRemovedForbiddenSeconds);
    if (room.mode === 'HYBRID') {
      this._clearPrivateStateForMember(room, member.deviceId);
    }
    room.members.delete(member.deviceId);
    this._revokeDeviceSessions(room, member.deviceId);
    if (room.mode === 'HYBRID') {
      if (room.transport) room.transport.localStableSinceMs = null;
      this._handleHybridLocalHostDeparture(room, member.deviceId, nowMs);
      if (room.transport?.active === 'CLOUD' && admittedCountAfter < 2) {
        this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'INSUFFICIENT_MEMBERS' });
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
      if (this._isOwnerRecoveryExpired(room, nowMs)) {
        throw new CloudStoreError(502, 'RTC_REVOCATION_FAILED', '群主恢复已超时，房间正在等待吊销结束');
      }
      if (room.removed.has(member.deviceId)) {
        throw new CloudStoreError(403, 'REMOVED', '你已被移出该房间');
      }
      const wasReserved = member.status === 'RESERVED';
      const demoteFromRider =
        wasReserved &&
        room.mode === 'HYBRID' &&
        member.channel?.type === 'RIDER' &&
        !canEnterRider(member.role, room.riderIncludesFormation);
      if (demoteFromRider && room.transport?.active === 'CLOUD') {
        const riderRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
        await this._revokeUserFromRtcRoom(room, riderRtcRoom, member.deviceId, 'ROLE_DEMOTED', nowMs);
      }
      const nextChannel = demoteFromRider ? { type: 'PUBLIC' } : member.channel;
      const rtc = await this._issueRtcCredentialsIfAllowed(
        room,
        { ...member, status: 'ADMITTED', channel: nextChannel },
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
      member.lastSeenMs = nowMs;
      if (member.status !== 'ADMITTED') {
        throw new CloudStoreError(409, 'NOT_ADMITTED', '成员未处于在房状态');
      }
      this._assertExpectedRevision(room, expectedRevision);
      const normalized = typeof channel === 'string' ? channel.trim().toUpperCase() : '';
      if (normalized !== 'PUBLIC' && normalized !== 'RIDER') {
        throw new CloudStoreError(400, 'INVALID_CHANNEL', '频道仅支持 PUBLIC 或 RIDER');
      }
      if (normalized === 'RIDER' && !canEnterRider(member.role, room.riderIncludesFormation)) {
        throw new CloudStoreError(403, 'FORBIDDEN', '当前角色无权进入领队频道');
      }
      const previousChannel = member.channel;
      const changingChannel = previousChannel?.type !== normalized;
      if (changingChannel && room.transport?.active === 'CLOUD') {
        const oldRtcRoom = this._rtcRoomIdForChannel(room, previousChannel, room.transport.epoch);
        if (previousChannel?.type === 'PRIVATE') {
          await this._revokeRtcRoom(room, oldRtcRoom, 'CHANNEL_SWITCH', nowMs);
        } else {
          await this._revokeUserFromRtcRoom(room, oldRtcRoom, member.deviceId, 'CHANNEL_SWITCH', nowMs);
        }
      }
      const nextMember = member.channel?.type !== normalized ? { ...member, channel: { type: normalized } } : member;
      const rtc = await this._issueRtcCredentialsIfAllowed(room, nextMember, nowMs);
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
      if (room.transport?.active === 'CLOUD') {
        const callerOldRtcRoom = this._rtcRoomIdForChannel(room, caller.channel, room.transport.epoch);
        const calleeOldRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
        await this._revokeUserFromRtcRoom(room, callerOldRtcRoom, caller.deviceId, 'PRIVATE_ACCEPTED', nowMs);
        await this._revokeUserFromRtcRoom(room, calleeOldRtcRoom, member.deviceId, 'PRIVATE_ACCEPTED', nowMs);
      }
      room.privateInvites.delete(inviteId);
      const sessionId = crypto.randomUUID();
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
        rtc: await this._issueRtcCredentialsIfAllowed(room, member, nowMs)
      };
    });
  }

  async declinePrivate(token, roomId, inviteId, { expectedRevision } = {}, nowMs = this.now()) {
    return this._withRoomLock(roomId, async () => {
      const { room, member } = await this._authenticateUnlocked(token, roomId, nowMs);
      this._ensureHybridRoom(room);
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
      member.lastSeenMs = nowMs;
      this._assertExpectedRevision(room, expectedRevision);
      if (member.channel?.type === 'PRIVATE') {
        if (room.transport?.active === 'CLOUD') {
          const privateRtcRoom = this._rtcRoomIdForChannel(room, member.channel, room.transport.epoch);
          await this._revokeRtcRoom(room, privateRtcRoom, 'PRIVATE_CLOSED', nowMs);
        }
        const rtc = await this._issueRtcCredentialsIfAllowed(room, { ...member, channel: { type: 'PUBLIC' } }, nowMs);
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
      if (target === 'CLOUD') {
        this._assertRtcRevocationReady();
      } else if (room.transport.active === 'CLOUD') {
        await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'SWITCHED_TO_LOCAL', nowMs);
      }
      const effectiveReason = isAutomaticFallback
        ? normalizedReason
        : (target === 'CLOUD' ? 'MANUAL_CLOUD' : 'MANUAL_LOCAL');
      this._transitionTransport(room, target, effectiveReason, nowMs);
      const rtc = await this._issueRtcCredentialsIfAllowed(room, member, nowMs);
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
          const candidateHost = room.members.get(room.transport.localHostDeviceId);
          const host = candidateHost?.status === 'ADMITTED' ? candidateHost : room.members.get(room.ownerId);
          if (host && room.transport.localHostDeviceId !== host.deviceId) {
            room.transport.localHostDeviceId = host.deviceId;
            this._abortHybridPreconnect(room, nowMs, { advanceBackoff: false, reason: 'LOCAL_HOST_REASSIGNED' });
            room.revision += 1;
            this._broadcastSnapshot(room);
            return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
          }
          if (room.transport.recoveryStage === 'PRECONNECTING') {
            const timedOut =
              room.transport.preconnectStartedAtMs !== null &&
              nowMs - room.transport.preconnectStartedAtMs >= this.localPreconnectTimeoutMs;
            if (Boolean(preconnectFailed) || timedOut) {
              this._abortHybridPreconnect(room, nowMs, {
                advanceBackoff: true,
                reason: preconnectFailed ? 'PRECONNECT_FAILED' : 'PRECONNECT_TIMEOUT'
              });
              room.revision += 1;
              this._broadcastSnapshot(room);
              return { switched: false, snapshot: this._snapshotFor(room, member.deviceId) };
            }
            const startedAt = room.transport.preconnectStartedAtMs ?? room.transport.cooldownUntilMs;
            const allControlReady =
              Boolean(host) &&
              admitted.every(
                (item) =>
                  item.localStatus?.epoch === room.transport.epoch &&
                  item.localStatus.controlReady === true &&
                  item.localStatus.reportedAtMs >= startedAt &&
                  nowMs - item.localStatus.reportedAtMs <= this.localStatusFreshMs
              );
            if (allControlReady) {
              await this._revokeRoomRtcForEpoch(room, room.transport.epoch, 'LOCAL_RECOVERED', nowMs);
              this._transitionTransport(room, 'LOCAL', 'LOCAL_RECOVERED', nowMs, {
                nextLocalHostDeviceId: host.deviceId,
                preservePreconnect: true
              });
              room.revision += 1;
              this._broadcastSnapshot(room);
              return { switched: true, snapshot: this._snapshotFor(room, member.deviceId) };
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
}
