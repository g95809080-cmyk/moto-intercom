import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const localDir = path.join(__dirname, '.local');

if (fs.existsSync(localDir)) {
  console.error(`Refusing to overwrite existing directory: ${localDir}`);
  process.exit(1);
}

fs.mkdirSync(localDir, { recursive: true });

const config = {
  host: '127.0.0.1',
  port: 8443,
  maxMembers: 50,
  volcAppId: '',
  volcAppKey: '',
  volcOpenApiAccessKey: '',
  volcOpenApiSecretKey: '',
  volcOpenApiRegion: 'cn-north-1',
  banRemovedForbiddenSeconds: 300,
  banRoomForbiddenSeconds: 3600,
  entitlementEnforced: true,
  dbPath: './entitlements.db',
  codeHmacSecret: crypto.randomBytes(32).toString('hex'),
  adminToken: crypto.randomBytes(32).toString('hex'),
  globalMonthlyLimitMinutes: 8500,
  requireCodeForJoin: false,
  trustLoopbackProxy: false
};

fs.writeFileSync(path.join(localDir, 'config.json'), JSON.stringify(config, null, 2) + '\n', { mode: 0o600 });

console.log(`Generated local config under ${localDir}`);
console.log('Fill volcAppId / volcAppKey and volcOpenApiAccessKey / volcOpenApiSecretKey locally (OpenAPI AK/SK is required for authoritative RTC revocation).');
console.log('Start control plane: node server.mjs --config=.local/config.json');
