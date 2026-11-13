import { randomBytes } from 'node:crypto';

// Restored from the existing experimental Lambda. Wire format follows Tencent's
// AuthBuffer sample (v1, 16-round TEA-CBC), with a real short expiration time.
// Reference: https://cloud.tencent.com/document/product/607/32156
export function createAuthBuffer({ appId, appKey, openId, roomId, expiresAt }) {
  const key = Buffer.from(appKey, 'utf8');
  if (key.length !== 16) throw new Error('Invalid GME key length');
  if (!Number.isInteger(appId) || appId <= 0 || appId > 0x7fffffff ||
      !Number.isInteger(expiresAt) || expiresAt <= 0 || expiresAt > 0xffffffff ||
      !/^[A-Za-z0-9_-]{1,127}$/.test(openId) || !/^[A-Za-z0-9_-]{1,127}$/.test(roomId)) throw new Error('Invalid GME grant');
  const user = Buffer.from(openId), room = Buffer.from(roomId);
  const body = Buffer.alloc(25 + user.length + room.length);
  let offset = body.writeUInt8(1);
  offset = body.writeUInt16BE(user.length, offset);
  offset += user.copy(body, offset);
  offset = body.writeUInt32BE(appId, offset);
  offset = body.writeUInt32BE(0, offset);
  offset = body.writeUInt32BE(expiresAt, offset);
  offset = body.writeUInt32BE(0xffffffff, offset);
  offset = body.writeUInt32BE(0, offset);
  offset = body.writeUInt16BE(room.length, offset);
  room.copy(body, offset);
  const padding = (8 - (body.length + 10) % 8) % 8;
  const prefix = randomBytes(3 + padding); prefix[0] = (prefix[0] & 0xf8) | padding;
  const plain = Buffer.concat([prefix, body, Buffer.alloc(7)]);
  const output = Buffer.alloc(plain.length), words = [0, 4, 8, 12].map(i => key.readUInt32BE(i));
  let previous = Buffer.alloc(8);
  for (let block = 0; block < plain.length; block += 8) {
    const mixed = Buffer.alloc(8);
    for (let i = 0; i < 8; i++) mixed[i] = plain[block + i] ^ previous[i];
    let y = mixed.readUInt32BE(), z = mixed.readUInt32BE(4), sum = 0;
    for (let i = 0; i < 16; i++) {
      sum = (sum + 0x9e3779b9) >>> 0;
      y = (y + (((z << 4) + words[0]) ^ (z + sum) ^ ((z >>> 5) + words[1]))) >>> 0;
      z = (z + (((y << 4) + words[2]) ^ (y + sum) ^ ((y >>> 5) + words[3]))) >>> 0;
    }
    previous = Buffer.alloc(8); previous.writeUInt32BE(y); previous.writeUInt32BE(z, 4); previous.copy(output, block);
  }
  key.fill(0); return output;
}
