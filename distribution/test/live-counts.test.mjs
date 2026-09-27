import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { ReleaseRegistry } from '../worker.mjs';
import { parseRelease } from '../protocol.mjs';
import { fixture } from './fixture.mjs';

async function setup(t) {
  const db = new DatabaseSync(':memory:');
  t.after(() => db.close());
  const storage = {
    sql: {exec(sql, ...args) {const rows = db.prepare(sql).all(...args); return {toArray: () => rows};}},
    transactionSync(fn) {db.exec('SAVEPOINT counter_test'); try {const result = fn(); db.exec('RELEASE counter_test'); return result;} catch (error) {db.exec('ROLLBACK TO counter_test'); db.exec('RELEASE counter_test'); throw error;}}
  };
  const raw = fixture(); raw.assets.forEach(asset => {asset.download_count = 2;});
  const release = parseRelease(raw, 'b'.repeat(40));
  const asset = release.assets[0];
  const bucket = {
    head: async () => ({size: asset.size, checksums: {sha256: Uint8Array.from(asset.sha256.match(/../g), hex => parseInt(hex, 16))}}),
    get: async (_key, options) => ({size: asset.size, body: new Uint8Array(options?.range?.length ?? asset.size)})
  };
  const registry = new ReleaseRegistry({storage, blockConcurrencyWhile: fn => fn()}, {DOWNLOADS: bucket});
  await registry.ready;
  registry.snapshot = {syncedAt: new Date().toISOString(), errors: {}, releases: [release], known: {[release.tag]: {active: true, countsAsOf: new Date().toISOString(), release}}};
  const stats = options => registry.fetch(new Request('https://api.turboism.dev/v1/downloads/1.2.3.json', options));
  const download = (headers = {}, method = 'GET') => registry.fetch(new Request('https://api.turboism.dev/' + asset.key, {method, headers: {'CF-Connecting-IP': '192.0.2.5', 'User-Agent': 'Download regression', ...headers}}));
  return {registry, stats, download};
}

test('live counts never enter shared caches, including HEAD and conditional responses', async t => {
  const {stats} = await setup(t);
  const initial = await stats();
  assert.equal(initial.headers.get('Cache-Control'), 'private, no-store');
  assert.equal((await initial.json()).official.count, 0);
  for (const options of [{method: 'HEAD'}, {headers: {'If-None-Match': initial.headers.get('ETag')}}]) {
    const response = await stats(options);
    assert.equal(response.headers.get('Cache-Control'), 'private, no-store');
    assert.equal(await response.text(), '');
  }
});

test('file serving changes persistent counts immediately; retries and probes do not double count', async t => {
  const {stats, download} = await setup(t);
  const before = await stats();
  assert.equal((await before.json()).official.count, 0);
  const file = await download();
  assert.equal(file.status, 200); await file.arrayBuffer();
  const after = await stats({headers: {'If-None-Match': before.headers.get('ETag')}});
  assert.equal(after.status, 200);
  const counts = await after.json();
  assert.equal(counts.official.count, 1); assert.equal(counts.total, 9); assert.equal(counts.assets[0].official, 1);
  for (const [headers, method] of [[{}, 'GET'], [{Range: 'bytes=0-3'}, 'GET'], [{Range: 'bytes=4-7'}, 'GET'], [{}, 'HEAD'], [{'User-Agent': 'Probe', 'X-Turboism-Download-Purpose': 'verification'}, 'GET']]) {
    const response = await download(headers, method); await response.arrayBuffer();
  }
  assert.equal((await (await stats()).json()).official.count, 1);
  await (await download({'User-Agent': 'Another browser'})).arrayBuffer();
  assert.equal((await (await stats()).json()).official.count, 2);
});
