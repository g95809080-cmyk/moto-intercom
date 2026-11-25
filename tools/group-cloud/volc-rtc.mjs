import crypto from 'node:crypto';

const TOKEN_VERSION = '001';
const PUBLISH_STREAM = 0;
const PUBLISH_AUDIO_STREAM = 1;
const PUBLISH_VIDEO_STREAM = 2;
const PUBLISH_DATA_STREAM = 3;
const SUBSCRIBE_STREAM = 4;

class ByteBuf {
  constructor() {
    this.buffer = Buffer.alloc(256);
    this.position = 0;
  }

  _ensure(size) {
    if (this.position + size <= this.buffer.length) return;
    const next = Buffer.alloc(Math.max(this.buffer.length * 2, this.position + size));
    this.buffer.copy(next, 0, 0, this.position);
    this.buffer = next;
  }

  uint16(value) {
    this._ensure(2);
    this.buffer.writeUInt16LE(value, this.position);
    this.position += 2;
    return this;
  }

  uint32(value) {
    this._ensure(4);
    this.buffer.writeUInt32LE(value, this.position);
    this.position += 4;
    return this;
  }

  bytes(value) {
    this.uint16(value.length);
    this._ensure(value.length);
    value.copy(this.buffer, this.position);
    this.position += value.length;
    return this;
  }

  string(value) {
    return this.bytes(Buffer.from(value, 'utf8'));
  }

  treeMapUInt32(values) {
    const entries = [...values.entries()].sort(([a], [b]) => a - b);
    this.uint16(entries.length);
    for (const [key, value] of entries) {
      this.uint16(key);
      this.uint32(value);
    }
    return this;
  }

  pack() {
    return this.buffer.subarray(0, this.position);
  }
}

export function createVolcAccessToken({
  appId,
  appKey,
  roomId,
  userId,
  issuedAtSec,
  expiresAtSec,
  nonce
}) {
  if (typeof appId !== 'string' || appId.length === 0) throw new Error('Volc RTC AppID is required');
  if (typeof appKey !== 'string' || appKey.length === 0) throw new Error('Volc RTC AppKey is required');
  if (!Number.isInteger(issuedAtSec) || !Number.isInteger(expiresAtSec) || expiresAtSec <= issuedAtSec) {
    throw new Error('Volc RTC token expiry must be after issue time');
  }
  const nonceValue = nonce ?? crypto.randomBytes(4).readUInt32LE(0);
  if (!Number.isInteger(nonceValue) || nonceValue < 0 || nonceValue > 0xffffffff) {
    throw new Error('Volc RTC token nonce is invalid');
  }

  const privileges = new Map();
  privileges.set(PUBLISH_STREAM, expiresAtSec);
  privileges.set(PUBLISH_AUDIO_STREAM, expiresAtSec);
  privileges.set(PUBLISH_VIDEO_STREAM, expiresAtSec);
  privileges.set(PUBLISH_DATA_STREAM, expiresAtSec);
  privileges.set(SUBSCRIBE_STREAM, expiresAtSec);

  const message = new ByteBuf()
    .uint32(nonceValue)
    .uint32(issuedAtSec)
    .uint32(expiresAtSec)
    .string(roomId)
    .string(userId)
    .treeMapUInt32(privileges)
    .pack();
  const signature = crypto.createHmac('sha256', appKey).update(message).digest();
  const content = new ByteBuf().bytes(message).bytes(signature).pack();
  return `${TOKEN_VERSION}${appId}${content.toString('base64')}`;
}

export function mintRtcCredentials({
  appId,
  appKey,
  rtcRoomId,
  rtcUserId,
  nowMs,
  ttlMs
}) {
  if (typeof rtcRoomId !== 'string' || rtcRoomId.length === 0 || rtcRoomId.length > 128) {
    throw new Error('Volc RTC roomId is invalid');
  }
  if (typeof rtcUserId !== 'string' || !/^[A-Za-z0-9_@.-]{1,128}$/.test(rtcUserId)) {
    throw new Error('Volc RTC userId is invalid');
  }
  if (!Number.isInteger(ttlMs) || ttlMs < 60_000) {
    throw new Error('Volc RTC token TTL must be at least 60 seconds');
  }
  const issuedAtSec = Math.floor(nowMs / 1000);
  const expiresAtSec = Math.floor((nowMs + ttlMs) / 1000);
  const rtcToken = createVolcAccessToken({
    appId,
    appKey,
    roomId: rtcRoomId,
    userId: rtcUserId,
    issuedAtSec,
    expiresAtSec
  });
  return {
    appId,
    rtcRoomId,
    rtcUserId,
    rtcToken,
    expiresAtMs: expiresAtSec * 1000
  };
}
