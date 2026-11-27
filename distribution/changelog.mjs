import changelogData from './changelog.json' with { type: 'json' };
import { compareVersions, jsonResponse, versionParts } from './protocol.mjs';

const LOCALES = ['en', 'zh', 'ja', 'ko'];
const DEFAULT_LOCALE = 'en';
const GROUP_BY_CATEGORY = 'category';
const GROUP_BY_VERSION = 'version';
const VALID_GROUP_BY = [GROUP_BY_CATEGORY, GROUP_BY_VERSION];
const VALID_FORMATS = ['json', 'markdown'];
const UNRELEASED = 'Unreleased';
const DEFAULT_REPOSITORY = 'https://github.com/turboism/Turboism';
const CHANNEL_STABLE = 'stable';
const CHANNEL_BETA = 'beta';
const CHANNEL_NIGHTLY = 'nightly';

const CATEGORY_HEADINGS = {
  en: {
    added: 'Added',
    changed: 'Changed',
    deprecated: 'Deprecated',
    removed: 'Removed',
    fixed: 'Fixed',
    security: 'Security',
    known_limitations: 'Known limitations',
  },
  zh: {
    added: '新增',
    changed: '变更',
    deprecated: '已弃用',
    removed: '移除',
    fixed: '修复',
    security: '安全',
    known_limitations: '已知限制',
  },
  ja: {
    added: '追加',
    changed: '変更',
    deprecated: '非推奨',
    removed: '削除',
    fixed: '修正',
    security: 'セキュリティ',
    known_limitations: '既知の制限',
  },
  ko: {
    added: '추가',
    changed: '변경',
    deprecated: '폐기 예정',
    removed: '제거',
    fixed: '수정',
    security: '보안',
    known_limitations: '알려진 제한 사항',
  },
};

const COMPARE_LABELS = {
  en: (base) => `Full changes since ${base}`,
  zh: (base) => `查看自 ${base} 以来的完整变化`,
  ja: (base) => `${base} 以降のすべての変更`,
  ko: (base) => `${base} 이후 전체 변경 사항`,
};

const SUMMARY_LABELS = {
  en: (from, to, count) =>
    count === 1
      ? `Changes from **${from}** to **${to}**.`
      : `Changes from **${from}** to **${to}** (${count} releases).`,
  zh: (from, to, count) =>
    count === 1
      ? `从 **${from}** 到 **${to}** 的变更。`
      : `从 **${from}** 到 **${to}** 的全部变更（共 ${count} 个版本）。`,
  ja: (from, to, count) =>
    count === 1
      ? `**${from}** から **${to}** への変更。`
      : `**${from}** から **${to}** までの変更（全 ${count} リリース）。`,
  ko: (from, to, count) =>
    count === 1
      ? `**${from}**에서 **${to}**으로의 변경 사항.`
      : `**${from}**에서 **${to}**까지의 변경 사항(총 ${count}개 릴리스).`,
};

export function normalizeVersion(raw) {
  const cleaned = String(raw ?? '').trim();
  if (cleaned.toLowerCase() === UNRELEASED.toLowerCase()) {
    return UNRELEASED;
  }
  return cleaned.startsWith('v') || cleaned.startsWith('V') ? cleaned.slice(1) : cleaned;
}

function localizedText(entry, lang) {
  return entry?.[lang] || entry?.[DEFAULT_LOCALE] || '';
}

function channelForVersion(version) {
  const { pre } = versionParts(version);
  if (pre.length === 0) {
    return CHANNEL_STABLE;
  }
  return pre.includes('nightly') ? CHANNEL_NIGHTLY : CHANNEL_BETA;
}

export function listVersions(data = changelogData) {
  return {
    format: data.format,
    schemaVersion: data.schemaVersion,
    repository: data.repository ?? DEFAULT_REPOSITORY,
    locales: data.locales,
    categories: data.categories,
    versions: data.releases.map(({ version, channel, date, previousVersion }) => ({
      version,
      channel: channel ?? channelForVersion(version),
      date,
      previousVersion,
    })),
  };
}

export function queryChangelogRange(
  fromVersion,
  toVersion,
  { lang = DEFAULT_LOCALE, groupBy = GROUP_BY_CATEGORY } = {},
  data = changelogData,
) {
  if (!LOCALES.includes(lang)) {
    throw new Error(`Unsupported locale: ${lang}`);
  }
  if (!VALID_GROUP_BY.includes(groupBy)) {
    throw new Error(`Unsupported groupBy: ${groupBy}`);
  }
  const start = normalizeVersion(fromVersion);
  const end = normalizeVersion(toVersion);
  if (!start || !end) {
    throw new Error('Both from and to versions are required');
  }
  if (start === UNRELEASED) {
    throw new Error('from version cannot be Unreleased');
  }
  let fromChannel;
  try {
    fromChannel = channelForVersion(start);
  } catch {
    throw new Error(`Invalid from version: ${fromVersion}`);
  }
  const knownVersions = new Set(data.releases.map((release) => release.version));
  const earliestVersion = data.releases.at(-1)?.version;
  if (end !== UNRELEASED && !knownVersions.has(end)) {
    throw new Error(`Unknown to version: ${toVersion}`);
  }
  if (end !== UNRELEASED && compareVersions(start, end) >= 0) {
    throw new Error(`to version (${end}) must be newer than from version (${start})`);
  }
  if (!knownVersions.has(start)) {
    if (fromChannel === CHANNEL_STABLE || !earliestVersion || compareVersions(start, earliestVersion) < 0) {
      throw new Error(`Unknown from version: ${fromVersion}`);
    }
  }

  let included;
  let compareUrl = null;
  let toChannel = null;
  if (end === UNRELEASED) {
    included = [];
    if (Object.keys(data.unreleased?.sections ?? {}).length > 0) {
      included.push({ version: UNRELEASED, channel: null, date: null, sections: data.unreleased.sections });
    }
    included.push(...data.releases.filter((release) => compareVersions(start, release.version) < 0));
  } else {
    included = data.releases.filter(
      (release) => compareVersions(start, release.version) < 0 && compareVersions(release.version, end) <= 0,
    );
    const repo = data.repository ?? DEFAULT_REPOSITORY;
    compareUrl = `${repo}/compare/v${start}...v${end}`;
    toChannel = channelForVersion(end);
  }

  const mergedSections = {};
  for (const category of data.categories) {
    const bucket = [];
    for (const release of included) {
      for (const item of release.sections?.[category] ?? []) {
        bucket.push({
          version: release.version,
          channel: release.channel ?? (release.version === UNRELEASED ? null : channelForVersion(release.version)),
          date: release.date ?? null,
          text: localizedText(item, lang),
          locales: item,
        });
      }
    }
    if (bucket.length > 0) {
      mergedSections[category] = bucket;
    }
  }

  const markdown = renderRangeMarkdown({
    fromVersion: start,
    toVersion: end,
    included,
    mergedSections,
    compareUrl,
    categories: data.categories,
    lang,
    groupBy,
  });

  return {
    format: data.format,
    schemaVersion: data.schemaVersion,
    fromVersion: start,
    fromChannel,
    toVersion: end,
    toChannel,
    lang,
    groupBy,
    includedVersions: included.map((release) => release.version),
    compareUrl,
    mergedSections,
    releases: included,
    markdown,
  };
}

function renderRangeMarkdown({
  fromVersion,
  toVersion,
  included,
  mergedSections,
  compareUrl,
  categories,
  lang,
  groupBy,
}) {
  const headings = CATEGORY_HEADINGS[lang] ?? CATEGORY_HEADINGS[DEFAULT_LOCALE];
  const blocks = [(SUMMARY_LABELS[lang] ?? SUMMARY_LABELS[DEFAULT_LOCALE])(fromVersion, toVersion, included.length)];

  if (groupBy === GROUP_BY_CATEGORY) {
    for (const category of categories) {
      const items = mergedSections[category] ?? [];
      if (items.length === 0) {
        continue;
      }
      blocks.push(`### ${headings[category]}\n\n${items.map((item) => `- ${item.text}`).join('\n')}`);
    }
  } else {
    for (const release of included) {
      const heading = release.date ? `## [${release.version}] - ${release.date}` : `## [${release.version}]`;
      const parts = [heading];
      if (release.summary) {
        parts.push(localizedText(release.summary, lang));
      }
      for (const category of categories) {
        const items = release.sections?.[category] ?? [];
        if (items.length === 0) {
          continue;
        }
        parts.push(
          `### ${headings[category]}\n\n${items.map((item) => `- ${localizedText(item, lang)}`).join('\n')}`,
        );
      }
      blocks.push(parts.join('\n\n'));
    }
  }

  if (compareUrl) {
    const label = (COMPARE_LABELS[lang] ?? COMPARE_LABELS[DEFAULT_LOCALE])(fromVersion);
    blocks.push(`[${label}](${compareUrl})`);
  }
  return `${blocks.join('\n\n')}\n`;
}

export async function handleChangelogRequest(request, data = changelogData) {
  const url = new URL(request.url);
  const from = url.searchParams.get('from');
  const to = url.searchParams.get('to');
  if (from === null && to === null) {
    return jsonResponse(request, listVersions(data));
  }
  if (!from || !to) {
    return jsonResponse(
      request,
      { error: { code: 'INVALID_RANGE', message: 'Both from and to query parameters are required' } },
      400,
    );
  }
  const lang = url.searchParams.get('lang') ?? DEFAULT_LOCALE;
  const groupBy = url.searchParams.get('groupBy') ?? GROUP_BY_CATEGORY;
  const format = url.searchParams.get('format') ?? 'json';
  if (!VALID_FORMATS.includes(format)) {
    return jsonResponse(
      request,
      { error: { code: 'INVALID_FORMAT', message: `Unsupported format: ${format}` } },
      400,
    );
  }
  let result;
  try {
    result = queryChangelogRange(from, to, { lang, groupBy }, data);
  } catch (failure) {
    return jsonResponse(request, { error: { code: 'INVALID_RANGE', message: failure.message } }, 400);
  }
  if (format === 'markdown') {
    return new Response(request.method === 'HEAD' ? null : result.markdown, {
      status: 200,
      headers: {
        'Content-Type': 'text/markdown; charset=utf-8',
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'public, max-age=300',
      },
    });
  }
  return jsonResponse(request, result);
}
