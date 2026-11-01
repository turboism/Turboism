import { pathToFileURL } from 'node:url';

const ORIGIN = 'https://api.turboism.dev';
const CHANNELS = ['stable', 'beta', 'nightly'];

/** An OIDC-authenticated refresh borrows this job's read-only token only for its upstream reads. */
export async function notifyReleaseApi(env, {fetcher = fetch, delay = ms => new Promise(resolve => setTimeout(resolve, ms)), log = console.log} = {}) {
  if (!env.RELEASE_SYNC_GITHUB_TOKEN) throw new Error('Missing read-only workflow token');
  const url = new URL(env.ACTIONS_ID_TOKEN_REQUEST_URL);
  url.searchParams.set('audience', ORIGIN);
  const tokenResponse = await fetcher(url, {
    headers: {Authorization: `Bearer ${env.ACTIONS_ID_TOKEN_REQUEST_TOKEN}`},
    redirect: 'manual', signal: AbortSignal.timeout(15000)
  });
  if (!tokenResponse.ok) throw new Error(`OIDC request failed: HTTP ${tokenResponse.status}`);
  const {value} = await tokenResponse.json();
  if (typeof value !== 'string' || !value) throw new Error('Missing workflow identity');
  log(`::add-mask::${value}`);
  let failure;
  for (let attempt = 0; attempt < 3; attempt++) {
    const started = Date.now();
    try {
      const response = await fetcher(ORIGIN + '/v1/sync', {
        method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(60000),
        headers: {Authorization: `Bearer ${value}`, 'Content-Type': 'application/json'},
        body: JSON.stringify({githubToken: env.RELEASE_SYNC_GITHUB_TOKEN})
      });
      if (!response.ok) throw new Error(`Release refresh failed: HTTP ${response.status}`);
      const status = await response.json();
      const synchronized = Date.parse(status.synchronizedAt);
      const channels = status.channels;
      if (status.service !== 'turboism-release-api' || status.status !== 'ready' || !status.mirrorConfigured
          || !Number.isFinite(synchronized) || synchronized < started - 60000 || synchronized > Date.now() + 30000
          || !Array.isArray(channels) || channels.length !== CHANNELS.length
          || !CHANNELS.every(channel => channels.some(c => c.channel === channel && ['ready', 'not_published'].includes(c.status)))) {
        throw new Error('Release refresh did not produce a fresh, healthy snapshot');
      }
      log(JSON.stringify({status: status.status, synchronizedAt: status.synchronizedAt, channels}));
      return status;
    } catch (error) {
      failure = error;
      if (attempt < 2) await delay(15000);
    }
  }
  throw failure;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await notifyReleaseApi(process.env);
}
