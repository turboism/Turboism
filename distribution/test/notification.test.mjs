import test from 'node:test';
import assert from 'node:assert/strict';
import { notifyReleaseApi } from '../../scripts/monitor/notify-release-api.mjs';

const env = {RELEASE_SYNC_GITHUB_TOKEN: 'offline-read-only-token', ACTIONS_ID_TOKEN_REQUEST_URL: 'https://oidc.invalid/token', ACTIONS_ID_TOKEN_REQUEST_TOKEN: 'offline-request-token'};
const ready = () => ({service: 'turboism-release-api', status: 'ready', mirrorConfigured: true, synchronizedAt: new Date().toISOString(), channels: ['stable', 'beta', 'nightly'].map(channel => ({channel, status: 'ready'}))});
const reply = value => new Response(JSON.stringify(value), {headers: {'Content-Type': 'application/json'}});

test('notification fails on HTTP 200 degraded, stale or incomplete health instead of reporting success', async () => {
  for (const broken of [{status: 'degraded'}, {synchronizedAt: '2020-01-01T00:00:00Z'}, {channels: []}, {mirrorConfigured: false}]) {
    let syncs = 0;
    await assert.rejects(() => notifyReleaseApi(env, {
      fetcher: async url => {
        if (new URL(url).hostname === 'oidc.invalid') return reply({value: 'offline-oidc-token'});
        syncs++;
        return reply({...ready(), ...broken});
      }, delay: async () => {}, log: () => {}
    }), /fresh, healthy snapshot/);
    assert.equal(syncs, 3);
  }
});

test('notification retries temporary failure, rejects redirects and sends the job token only to the fixed sync route', async () => {
  let syncs = 0;
  const logs = [];
  const result = await notifyReleaseApi(env, {
    fetcher: async (url, init) => {
      assert.equal(init.redirect, 'manual');
      const headers = new Headers(init.headers);
      if (new URL(url).hostname === 'oidc.invalid') {
        assert.equal(headers.get('X-Turboism-GitHub-Token'), null);
        return reply({value: 'offline-oidc-token'});
      }
      assert.equal(url, 'https://api.turboism.dev/v1/sync');
      assert.ok(!JSON.stringify([...headers]).includes(env.RELEASE_SYNC_GITHUB_TOKEN));
      assert.deepEqual(JSON.parse(init.body), {githubToken: env.RELEASE_SYNC_GITHUB_TOKEN});
      assert.equal(headers.get('Authorization'), 'Bearer offline-oidc-token');
      if (++syncs === 1) return new Response(null, {status: 302, headers: {Location: 'https://untrusted.invalid'}});
      return reply(ready());
    }, delay: async () => {}, log: value => logs.push(value)
  });
  assert.equal(syncs, 2);
  assert.equal(result.status, 'ready');
  assert.ok(!logs.join('\n').includes(env.RELEASE_SYNC_GITHUB_TOKEN));
});
