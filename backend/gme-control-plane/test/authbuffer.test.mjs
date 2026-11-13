import test from 'node:test';
import assert from 'node:assert/strict';
import { createAuthBuffer } from '../src/authbuffer.mjs';

// Independent inverse of the wire cipher checks embedded expiration, identity,
// room binding and padding, rather than merely checking a non-empty signature.
function decrypt(cipher, key) {
  const words = [0, 4, 8, 12].map(i => key.readUInt32BE(i));
  const plain = Buffer.alloc(cipher.length); let previous = Buffer.alloc(8);
  for (let offset = 0; offset < cipher.length; offset += 8) {
    let y = cipher.readUInt32BE(offset), z = cipher.readUInt32BE(offset + 4), sum = (0x9e3779b9 * 16) >>> 0;
    for (let i = 0; i < 16; i++) {
      z = (z - (((y << 4) + words[2]) ^ (y + sum) ^ ((y >>> 5) + words[3]))) >>> 0;
      y = (y - (((z << 4) + words[0]) ^ (z + sum) ^ ((z >>> 5) + words[1]))) >>> 0;
      sum = (sum - 0x9e3779b9) >>> 0;
    }
    const block = Buffer.alloc(8); block.writeUInt32BE(y); block.writeUInt32BE(z, 4);
    for (let i = 0; i < 8; i++) plain[offset + i] = block[i] ^ previous[i];
    previous = cipher.subarray(offset, offset + 8);
  }
  assert.deepEqual(plain.subarray(-7), Buffer.alloc(7));
  return plain.subarray(3 + (plain[0] & 7), -7);
}
test('GME AuthBuffer 内嵌 300 秒过期时间，绑定本次随机房号和公钥派生身份', () => {
  const grant = { appId: 1400802321, appKey: '0123456789abcdef', openId: '100000001', roomId: '90807060504030201', expiresAt: 1800000300 };
  const first = createAuthBuffer(grant), second = createAuthBuffer(grant);
  assert.notDeepEqual(first, second);
  const body = decrypt(first, Buffer.from(grant.appKey));
  assert.equal(body[0], 1); const userLength = body.readUInt16BE(1);
  assert.equal(body.subarray(3, 3 + userLength).toString(), grant.openId);
  assert.equal(body.readUInt32BE(3 + userLength), grant.appId);
  assert.equal(body.readUInt32BE(11 + userLength), grant.expiresAt);
  assert.equal(body.readUInt32BE(15 + userLength), 0xffffffff);
  const roomLength = body.readUInt16BE(23 + userLength);
  assert.equal(body.subarray(25 + userLength, 25 + userLength + roomLength).toString(), grant.roomId);
  assert.throws(() => createAuthBuffer({ ...grant, appKey: 'invalid' }));
  assert.throws(() => createAuthBuffer({ ...grant, expiresAt: 2 ** 32 }));
});
