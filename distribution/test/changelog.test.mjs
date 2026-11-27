import test from 'node:test';
import assert from 'node:assert/strict';
import { handleChangelogRequest, listVersions, queryChangelogRange } from '../changelog.mjs';
import worker from '../worker.mjs';

test('listVersions exposes structured changelog index in descending order', () => {
  const index = listVersions();
  assert.equal(index.format, 'turboism.changelog/v1');
  assert.equal(index.schemaVersion, 1);
  assert.ok(index.versions.length >= 23);
  assert.equal(index.versions.at(-1).version, '0.42.0');
});

test('queryChangelogRange merges multi-version changes by category and by version', () => {
  const merged = queryChangelogRange('v0.70.1', 'v0.70.3', { lang: 'zh', groupBy: 'category' });
  assert.deepEqual(merged.includedVersions, ['0.70.3', '0.70.2']);
  assert.equal(merged.compareUrl, 'https://github.com/turboism/Turboism/compare/v0.70.1...v0.70.3');
  assert.ok(merged.mergedSections.added.some((item) => item.version === '0.70.2'));
  assert.ok(merged.mergedSections.added.some((item) => item.version === '0.70.3'));
  assert.match(merged.markdown, /### 新增/);
  assert.match(merged.markdown, /compare\/v0\.70\.1\.\.\.v0\.70\.3/);

  const grouped = queryChangelogRange('0.70.1', '0.70.3', { lang: 'en', groupBy: 'version' });
  assert.match(grouped.markdown, /## \[0\.70\.3\] - 2026-11-23/);
  assert.match(grouped.markdown, /## \[0\.70\.2\] - 2026-11-22/);
});

test('worker serves /v1/changelog index, JSON range, Markdown range, and rejects invalid bounds', async () => {
  const indexResponse = await worker.fetch(new Request('https://api.turboism.dev/v1/changelog'), {});
  assert.equal(indexResponse.status, 200);
  const indexBody = await indexResponse.json();
  assert.ok(Array.isArray(indexBody.versions) && indexBody.versions.length >= 23);

  const rangeResponse = await worker.fetch(
    new Request('https://api.turboism.dev/v1/changelog?from=0.70.1&to=0.70.3&lang=en&format=markdown'),
    {},
  );
  assert.equal(rangeResponse.status, 200);
  assert.match(rangeResponse.headers.get('Content-Type') ?? '', /^text\/markdown/);
  const markdown = await rangeResponse.text();
  assert.match(markdown, /### Added/);
  assert.match(markdown, /Cubism Editor 5\.3\.05/);

  const badResponse = await handleChangelogRequest(
    new Request('https://api.turboism.dev/v1/changelog?from=0.70.3&to=0.70.1'),
  );
  assert.equal(badResponse.status, 400);
});

test('queryChangelogRange supports beta->beta, beta->stable, and stable->beta transitions', () => {
  const fixture = {
    format: 'turboism.changelog/v1',
    schemaVersion: 1,
    repository: 'https://github.com/turboism/Turboism',
    locales: ['en', 'zh', 'ja', 'ko'],
    categories: ['added', 'changed', 'deprecated', 'removed', 'fixed', 'security', 'known_limitations'],
    unreleased: { sections: {} },
    releases: [
      {
        version: '1.1.0',
        channel: 'stable',
        date: '2026-11-26',
        previousVersion: '1.1.0-beta.5',
        sections: { fixed: [{ en: 'Stable fix.', zh: '正式版修复。' }] },
      },
      {
        version: '1.1.0-beta.5',
        channel: 'beta',
        date: '2026-11-25',
        previousVersion: '1.1.0-beta.4',
        sections: { added: [{ en: 'Beta 5 item.', zh: 'Beta 5 新增。' }] },
      },
      {
        version: '1.1.0-beta.4',
        channel: 'beta',
        date: '2026-11-24',
        previousVersion: '1.0.0',
        sections: { added: [{ en: 'Beta 4 item.', zh: 'Beta 4 新增。' }] },
      },
      {
        version: '1.0.0',
        channel: 'stable',
        date: '2026-11-20',
        previousVersion: null,
        sections: { added: [{ en: 'Stable 1.0.', zh: '正式版 1.0。' }] },
      },
    ],
  };

  const b2b = queryChangelogRange('1.1.0-beta.4', '1.1.0-beta.5', { lang: 'zh' }, fixture);
  assert.deepEqual(b2b.includedVersions, ['1.1.0-beta.5']);
  assert.equal(b2b.fromChannel, 'beta');
  assert.equal(b2b.toChannel, 'beta');

  const b2s = queryChangelogRange('1.1.0-beta.4', '1.1.0', { lang: 'zh' }, fixture);
  assert.deepEqual(b2s.includedVersions, ['1.1.0', '1.1.0-beta.5']);
  assert.equal(b2s.toChannel, 'stable');

  const s2b = queryChangelogRange('1.0.0', '1.1.0-beta.5', { lang: 'zh' }, fixture);
  assert.deepEqual(s2b.includedVersions, ['1.1.0-beta.5', '1.1.0-beta.4']);
  assert.equal(s2b.fromChannel, 'stable');
  assert.equal(s2b.toChannel, 'beta');

  assert.throws(() => queryChangelogRange('1.1.0', '1.1.0-beta.5', { lang: 'en' }, fixture));
});
