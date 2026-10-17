import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { isIP } from 'node:net';
import { sha256 } from './server.mjs';

const args = process.argv.slice(2);
function argument(name, fallback) { const index = args.indexOf(name); return index < 0 ? fallback : args[index + 1]; }
const turnHost = argument('--turn-host');
if (!isIP(turnHost ?? '') || turnHost.includes(':')) throw new Error('Supply --turn-host with a reachable IPv4 address for local coturn');
const endpoint = argument('--endpoint', 'https://10.0.2.2:8443');
const url = new URL(endpoint);
if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
  throw new Error('Endpoint must be an HTTPS origin');
const local = fileURLToPath(new URL('.local/', import.meta.url));
mkdirSync(local); // Deliberately refuse to overwrite active credentials.
const openssl = process.env.OPENSSL ?? (process.platform === 'win32' ? 'C:/Program Files/Git/usr/bin/openssl.exe' : 'openssl');
const sans = new Set(['DNS:localhost', 'IP:127.0.0.1', 'IP:10.0.2.2', `IP:${turnHost}`,
  `${isIP(url.hostname) ? 'IP' : 'DNS'}:${url.hostname}`]);
execFileSync(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', join(local, 'tls-key.pem'),
  '-out', join(local, 'tls-cert.pem'), '-days', '3', '-subj', '/CN=MotoCom local validation',
  '-addext', `subjectAltName=${[...sans].join(',')}`], { stdio: 'ignore' });
const secret = randomBytes(32).toString('hex');
const devices = ['probe-a','probe-b'].map(deviceId => ({ deviceId, token: randomBytes(32).toString('base64url') }));
const caPem = readFileSync(join(local, 'tls-cert.pem'), 'utf8');
function privateFile(name, text) { writeFileSync(join(local, name), text, { mode: 0o600, flag: 'wx' }); }
privateFile('server.json', JSON.stringify({ host: '127.0.0.1', port: Number(url.port || 443),
  tls: { key: join(local, 'tls-key.pem'), cert: join(local, 'tls-cert.pem') },
  devices: devices.map(d => ({ deviceId: d.deviceId, tokenSha256: sha256(d.token) })),
  pairs: [{ pairId: 'local-validation', members: devices.map(d => d.deviceId) }],
  turn: { secret, urls: [`turn:${turnHost}:3478?transport=udp`, `turn:${turnHost}:3478?transport=tcp`] },
}, null, 2));
for (const [index, device] of devices.entries()) privateFile(`${device.deviceId}.json`, JSON.stringify({
  endpoint: url.origin, caPem, deviceId: device.deviceId, peerId: devices[1 - index].deviceId,
  deviceToken: device.token, pairId: 'local-validation', sendHz: index === 0 ? 500 : 1000,
  receiveHz: index === 0 ? 1000 : 500, requireCellular: false,
}, null, 2));
privateFile('turnserver.conf', [
  `listening-ip=${turnHost}`, `relay-ip=${turnHost}`, 'listening-port=3478', 'min-port=47000', 'max-port=47100',
  'realm=motocom.local', 'fingerprint', 'use-auth-secret', `static-auth-secret=${secret}`,
  'user-quota=4', 'total-quota=16', 'max-bps=128000', 'no-cli', 'no-tls', 'no-dtls',
  'no-tcp-relay', 'no-multicast-peers', 'denied-peer-ip=127.0.0.0-127.255.255.255',
  'denied-peer-ip=169.254.0.0-169.254.255.255', 'log-file=stdout', 'simple-log',
].join('\n') + '\n');
console.log('Created private tools/relay/.local configuration. Credentials were not printed.');
