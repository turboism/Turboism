import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import worker, { ReleaseRegistry } from '../worker.mjs';
import { synchronize } from '../github.mjs';
import { verifyGitHubIdentity } from '../identity.mjs';
import { fixture } from './fixture.mjs';

// Job credentials are opaque; their provider may issue longer, encoded token formats.
const TOKEN = 'offline.' + 'a'.repeat(1200) + '-encoded+/=';
const reply = value => new Response(JSON.stringify(value), {headers: {'Content-Type': 'application/json'}});
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const keys = await crypto.subtle.generateKey({name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256'}, true, ['sign', 'verify']);
const jwk = {...await crypto.subtle.exportKey('jwk', keys.publicKey), kid: 'sync-offline-key'};
async function identity(changes = {}) {
  const now = Math.floor(Date.now() / 1000);
  const claims = {iss: 'https://token.actions.githubusercontent.com', aud: 'https://api.turboism.dev', repository: 'turboism/Turboism', repository_id: '1291982209', ref: 'refs/heads/main', workflow_ref: 'turboism/Turboism/.github/workflows/notify-release-api.yml@refs/heads/main', event_name: 'workflow_dispatch', iat: now, exp: now + 300, ...changes};
  const input = encode({alg: 'RS256', kid: jwk.kid}) + '.' + encode(claims);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', keys.privateKey, new TextEncoder().encode(input));
  return input + '.' + Buffer.from(signature).toString('base64url');
}
async function setup(t) {
  const db = new DatabaseSync(':memory:');
  t.after(() => db.close());
  const storage = {
    sql: {exec(sql, ...args) {const rows = db.prepare(sql).all(...args); return {toArray: () => rows};}},
    transactionSync(fn) {db.exec('SAVEPOINT sync_test'); try {const value = fn(); db.exec('RELEASE sync_test'); return value;} catch (error) {db.exec('ROLLBACK TO sync_test'); db.exec('RELEASE sync_test'); throw error;}}
  };
  const registry = new ReleaseRegistry({storage, blockConcurrencyWhile: fn => fn()}, {});
  await registry.ready;
  const old = fixture('0.68.1-beta.1', true), latest = fixture('0.68.1-beta.5', true);
  const metadata = async (url, init) => {
    assert.equal(init.redirect, 'manual');
    const parsed = new URL(url);
    assert.equal(parsed.origin, 'https://api.github.com');
    assert.ok(parsed.pathname.startsWith('/repos/turboism/Turboism/'));
    if (parsed.pathname.endsWith('/releases')) return reply([latest, old]);
    if (parsed.pathname.includes('/git/ref/tags/')) return reply({object: {type: 'commit', sha: 'b'.repeat(40)}});
    throw new Error('Unexpected upstream path');
  };
  registry.snapshot = await synchronize(null, {}, async (url, init) => new URL(url).pathname.endsWith('/releases') ? reply([old]) : metadata(url, init));
  registry.snapshot.syncedAt = new Date(Date.now() - 20 * 60 * 1000).toISOString();
  registry.save();
  const env = {REGISTRY: {idFromName: () => 'registry', get: () => registry}};
  const refresh = async (changes = {}) => worker.fetch(new Request('https://api.turboism.dev/v1/sync', {method: 'POST', headers: {Authorization: 'Bearer ' + await identity(changes), 'Content-Type': 'application/json'}, body: JSON.stringify({githubToken: TOKEN})}), env);
  return {registry, env, db, metadata, refresh};
}

test('trusted notification recovers a stale release after upstream 403 without persisting its token', async t => {
  const x = await setup(t);
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === 'https://token.actions.githubusercontent.com/.well-known/jwks') return reply({keys: [jwk]});
    const auth = new Headers(init.headers).get('Authorization');
    calls.push(auth);
    return auth === 'Bearer ' + TOKEN ? x.metadata(url, init) : new Response(null, {status: 403});
  });
  assert.equal((await (await x.registry.fetch(new Request('https://api.turboism.dev/health'))).json()).status, 'degraded');
  assert.equal(x.registry.snapshot.releases[0].version, '0.68.1-beta.1');
  const refreshed = await x.refresh();
  assert.equal(refreshed.status, 200);
  assert.equal((await refreshed.json()).status, 'ready');
  assert.equal(x.registry.snapshot.releases[0].version, '0.68.1-beta.5');
  assert.ok(calls.includes('Bearer ' + TOKEN));
  const persisted = JSON.stringify(x.db.prepare('SELECT value FROM metadata UNION ALL SELECT value FROM known_releases').all());
  assert.ok(!persisted.includes(TOKEN));
  const count = calls.length;
  let scheduled;
  await worker.scheduled({}, x.env, {waitUntil: promise => {scheduled = promise;}});
  await scheduled;
  assert.equal(calls.length, count, 'cron must not replace a fresh authenticated snapshot with an anonymous failure');
});

test('an authenticated notification waits for an anonymous refresh and then retries with its own credential', {timeout: 2000}, async t => {
  const x = await setup(t);
  let releaseAnonymous;
  const pending = new Promise(resolve => {releaseAnonymous = resolve;});
  t.after(() => releaseAnonymous());
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === 'https://token.actions.githubusercontent.com/.well-known/jwks') return reply({keys: [jwk]});
    if (new Headers(init.headers).get('Authorization') === 'Bearer ' + TOKEN) return x.metadata(url, init);
    await pending;
    return new Response(null, {status: 403});
  });
  const anonymous = x.registry.refresh();
  let reachedNotification;
  const joined = new Promise(resolve => {reachedNotification = resolve;});
  x.env.REGISTRY.get = () => ({fetch(request) {reachedNotification(); return x.registry.fetch(request);}});
  const notification = x.refresh();
  await joined;
  releaseAnonymous();
  await anonymous;
  assert.equal((await (await notification).json()).status, 'ready');
  assert.equal(x.registry.snapshot.releases[0].version, '0.68.1-beta.5');
});

test('a supplied upstream credential cannot bypass OIDC or be forwarded from public reads', async t => {
  const x = await setup(t);
  let upstream = 0;
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === 'https://token.actions.githubusercontent.com/.well-known/jwks') return reply({keys: [jwk]});
    upstream++;
    assert.equal(new Headers(init.headers).get('Authorization'), null);
    return new Response(null, {status: 403});
  });
  assert.equal((await x.refresh({repository_id: '1'})).status, 401);
  assert.equal(upstream, 0);
  await worker.fetch(new Request('https://api.turboism.dev/health', {headers: {'X-Turboism-GitHub-Token': TOKEN}}), x.env);
  assert.equal(upstream, 1);
});

test('scheduled OIDC is accepted only for the main sync notifier, never framework publication', async () => {
  const fetcher = async () => reply({keys: [jwk]});
  assert.ok(await verifyGitHubIdentity(await identity({event_name: 'schedule'}), fetcher));
  await assert.rejects(() => identity({event_name: 'schedule', ref: 'refs/tags/v1.2.3', workflow_ref: 'turboism/Turboism/.github/workflows/notify-release-api.yml@refs/tags/v1.2.3'}).then(token => verifyGitHubIdentity(token, fetcher)));
  await assert.rejects(() => identity({event_name: 'schedule', environment: 'production-release', workflow_ref: 'turboism/Turboism/.github/workflows/release-github-only.yml@refs/heads/main'}).then(token => verifyGitHubIdentity(token, fetcher, {workflow: 'release-github-only.yml', environment: 'production-release'})));
});

test('malformed or oversized credentials are rejected before any upstream request', async t => {
  const x = await setup(t);
  t.mock.method(globalThis, 'fetch', async url => {
    assert.equal(url, 'https://token.actions.githubusercontent.com/.well-known/jwks');
    return reply({keys: [jwk]});
  });
  const authorization = 'Bearer ' + await identity();
  for (const githubToken of ['', 'a\r\nb', 'a\u00ffb', 'a'.repeat(8193), 'a'.repeat(17000)]) {
    const response = await worker.fetch(new Request('https://api.turboism.dev/v1/sync', {method: 'POST', headers: {Authorization: authorization, 'Content-Type': 'application/json'}, body: JSON.stringify({githubToken})}), x.env);
    assert.equal(response.status, 400);
  }
});

test('upstream credentials never enter mirror downloads, and channel fallback is reported as degraded', async t => {
  const x = await setup(t);
  const downloads = [];
  const snapshot = await synchronize(null, {DOWNLOADS: {get: async () => null, head: async () => null}}, async (url, init) => {
    const auth = new Headers(init.headers).get('Authorization');
    if (new URL(url).origin === 'https://api.github.com') {
      assert.equal(auth, 'Bearer ' + TOKEN);
      return x.metadata(url, init);
    }
    downloads.push({url, auth});
    throw new Error('Offline download probe');
  }, TOKEN);
  assert.equal(downloads.length, 1);
  assert.equal(downloads[0].auth, null);
  assert.equal(snapshot.errors.beta, 'Offline download probe');
  x.registry.snapshot.syncedAt = new Date().toISOString();
  x.registry.snapshot.errors.beta = 'GitHub HTTP 403';
  assert.equal((await (await x.registry.fetch(new Request('https://api.turboism.dev/health'))).json()).status, 'degraded');
});
