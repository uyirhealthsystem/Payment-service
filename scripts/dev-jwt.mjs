// LOCAL TESTING ONLY. Mints RS256 JWTs shaped like the identity provider's (sub = user id, role = claim), signed
// with this repo's throw-away dev key, which the "local" Spring profile trusts (dev-keys/dev-public.pem).
// The key pair is created on first use and is git-ignored.
//
//   node scripts/dev-jwt.mjs <userId> <role> [name] [ttlSeconds]   ->  prints a token
//   node scripts/dev-jwt.mjs patient-42 PATIENT
//   node scripts/dev-jwt.mjs admin-1 ADMIN                         (refunds, any payment)
//
// The CLI default lifetime is 12 h.
import { createSign, generateKeyPairSync } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const keyDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'dev-keys');
const pubFile = join(keyDir, 'dev-public.pem');
const privFile = join(keyDir, 'dev-private.pem');

if (!existsSync(privFile) || !existsSync(pubFile)) {
  mkdirSync(keyDir, { recursive: true });
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  writeFileSync(pubFile, publicKey.export({ type: 'spki', format: 'pem' }));
  writeFileSync(privFile, privateKey.export({ type: 'pkcs8', format: 'pem' }));
  console.error('generated dev keys in dev-keys/ (restart the service if it is running)');
}

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');

export function mint(sub, role, name = 'Test User', ttlSeconds = 900) {
  const now = Math.floor(Date.now() / 1000);
  const data = `${b64({ alg: 'RS256', typ: 'JWT', kid: 'dev' })}.${b64({ sub, role, name, iat: now, exp: now + ttlSeconds })}`;
  const sig = createSign('RSA-SHA256').update(data).sign(readFileSync(privFile)).toString('base64url');
  return `${data}.${sig}`;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [, , sub, role, name, ttl] = process.argv;
  if (!sub || !role) {
    console.error('usage: node scripts/dev-jwt.mjs <userId> <role> [name] [ttlSeconds]');
    process.exit(1);
  }
  console.log(mint(sub, role, name, ttl ? Number(ttl) : 12 * 3600));
}
