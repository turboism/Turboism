"""Deterministic, frozen release notes. No translation service or moving publish baseline."""
from __future__ import annotations
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

REPO = 'https://github.com/turboism/Turboism'
MATRIX_URL = 'https://turboism.dev/docs/use/compatibility'
PLATFORMS = ('windows-x64',)
SHA = re.compile(r'[0-9a-f]{40}')
TAG = re.compile(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?')
CUBISM_VERSION = re.compile(r'^5\.\d{1,2}\.\d{2}$')
COMPATIBILITY_MATRIX = Path('compatibility/cubism/compatibility-matrix.json')
REVIEWED_HOST_ARTIFACTS = Path(
    'compatibility/cubism/reviewed-releases.json'
)
LOCALES = ('en', 'zh', 'ja', 'ko')
MAX_COMMITS = 60


def require(value, message):
    if not value:
        raise ValueError(message)


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], check=True,
                          capture_output=True, text=True, encoding='utf-8').stdout.strip()


def ancestor(root, base, head):
    require(SHA.fullmatch(base) and SHA.fullmatch(head), 'Invalid history revision')
    result = subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor', base, head],
                            capture_output=True)
    require(result.returncode in (0, 1), 'Cannot inspect complete source history')
    return result.returncode == 0


def history_context(root, source, base=None, tag=None):
    require(isinstance(source, str) and SHA.fullmatch(source), 'Invalid notes source')
    require((base is None and tag is None) or
            (isinstance(base, str) and SHA.fullmatch(base) and isinstance(tag, str) and TAG.fullmatch(tag)),
            'Invalid notes baseline')
    if base:
        require(base != source and ancestor(root, base, source), 'Notes baseline must be an earlier ancestor')
    revision = f'{base}..{source}' if base else source
    total = int(git(root, 'rev-list', '--count', revision))
    commits = []
    for line in git(root, 'log', '--reverse', f'--max-count={MAX_COMMITS}', '--format=%H%x00%s', revision).splitlines():
        sha, subject = line.split('\0', 1)
        require(SHA.fullmatch(sha), 'Invalid history entry')
        subject = re.sub(r'[\x00-\x1f\x7f]', ' ', subject).strip()
        commits.append({'sha': sha, 'subject': subject[:157]+'…' if len(subject)>160 else subject})
    return {'schemaVersion': 1, 'sourceRevision': source, 'baseRevision': base,
            'baseTag': tag, 'totalCommits': total, 'commits': commits}


def validate_context(root, receipt, context):
    require(isinstance(context, dict), 'Missing frozen notes context')
    expected = history_context(root, receipt['sourceRevision'], context.get('baseRevision'), context.get('baseTag'))
    require(context == expected, 'Frozen notes context differs from exact Git history')
    return expected


def resolve_tag(github, tag):
    require(TAG.fullmatch(tag), 'Invalid published baseline tag')
    obj = github.api('git/ref/tags/'+tag)['object']
    for _ in range(4):
        if obj.get('type') != 'tag':
            break
        require(SHA.fullmatch(obj.get('sha', '')), 'Invalid tag object')
        obj = github.api('git/tags/'+obj['sha'])['object']
    require(obj.get('type') == 'commit' and SHA.fullmatch(obj.get('sha', '')), 'Invalid tag target')
    return obj['sha']


def timestamp(text):
    parsed = datetime.fromisoformat(text.replace('Z', '+00:00'))
    require(parsed.tzinfo is not None, 'Publication time must have a timezone')
    return parsed


def select_context(github, root, receipt, cutoff=None):
    # Only invoked while preparing a candidate, never while verifying/publishing it.
    from . import nightly
    cutoff_time = timestamp(cutoff) if cutoff else datetime.now(timezone.utc)
    published = [r for r in nightly.list_releases(github)
                 if r.get('draft') is False and r.get('published_at') and timestamp(r['published_at']) <= cutoff_time]
    previous = []
    for raw in published:
        if nightly.NIGHTLY.fullmatch(str(raw.get('tag_name', ''))[1:]):
            prior = nightly.validate_published(github, raw)
            if prior['sourceRevision'] != receipt['sourceRevision'] and ancestor(root, prior['sourceRevision'], receipt['sourceRevision']):
                previous.append((prior['buildNumber'], prior['sourceRevision'], raw['tag_name']))
    if previous:
        _, base, tag = max(previous)
        return history_context(root, receipt['sourceRevision'], base, tag)
    # Bootstrap from a published, ancestral stable, not a future release or a bare tag.
    stable = sorted((r for r in published if r.get('prerelease') is False and
                     re.fullmatch(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', r.get('tag_name', ''))),
                    key=lambda r: timestamp(r['published_at']), reverse=True)
    for raw in stable:
        base = resolve_tag(github, raw['tag_name'])
        if base != receipt['sourceRevision'] and ancestor(root, base, receipt['sourceRevision']):
            return history_context(root, receipt['sourceRevision'], base, raw['tag_name'])
    return history_context(root, receipt['sourceRevision'])


def escape(text):
    return re.sub(r'([\\`*_{}\[\]<>])', r'\\\1', text)


def render_nightly(receipt, context):
    require(context['sourceRevision'] == receipt['sourceRevision'], 'Notes source differs from build')
    labels = {
        'en': ('Nightly development build; back up projects before use.', 'Changes since', 'Initial development history', 'Commit subjects are shown in their original language.', 'Full changes', 'Showing the latest {n} of {total} commits.'),
        'zh': ('Nightly 开发构建，使用前请备份工程', '相比以下版本的改动', '初始开发历史', '提交标题保留原文', '完整变更', '显示全部 {total} 次提交中的最近 {n} 次'),
        'ja': ('Nightly 開発ビルドです。使用前にプロジェクトをバックアップしてください。', '次のバージョンからの変更', '最初の開発履歴', 'コミットの件名は原文で表示しています。', 'すべての変更', '全 {total} 件のうち直近 {n} 件を表示しています。'),
        'ko': ('Nightly 개발 빌드입니다. 사용 전에 프로젝트를 백업하세요.', '다음 버전 이후 변경 사항', '최초 개발 이력', '커밋 제목은 원문으로 표시합니다.', '전체 변경 사항', '전체 {total}개 커밋 중 최근 {n}개를 표시합니다.'),
    }
    url = (f"{REPO}/compare/{context['baseRevision']}...{receipt['sourceRevision']}" if context['baseRevision']
           else f"{REPO}/commits/{receipt['sourceRevision']}")
    lines = []
    for c in reversed(context['commits']):
        line = f"- {escape(c['subject'])} ([{c['sha'][:8]}]({REPO}/commit/{c['sha']}))"
        if len(('\n'.join(lines+[line])).encode()) > 7000:
            break
        lines.append(line)
    lines.reverse()
    result = {}
    for lang, (warning, changes, initial, original, full, limited) in labels.items():
        heading = f"{changes} {context['baseTag']}" if context['baseTag'] else initial
        limit = limited.format(n=len(lines), total=context['totalCommits'])+'\n\n' if context['totalCommits']>len(lines) else ''
        result[lang] = (f"# Turboism {receipt['version']}\n\n{warning}\n\n### {heading}\n\n{original}\n\n"
                        + limit+'\n'.join(lines)+f'\n\n[{full}]({url})\n')
    return result


def reviewed_locales(root, version, english):
    """Translations must bind to the exact English section, avoiding stale reuse."""
    result = {'en': english.strip()}
    path = Path(root)/'release-notes'/f'{version}.json'
    if not path.is_file():
        return result
    require(not path.is_symlink() and path.stat().st_size < 90000, 'Invalid translations file')
    data = json.loads(path.read_text(encoding='utf-8'))
    require(data.get('schemaVersion') == 1 and data.get('version') == version and
            data.get('englishSha256') == hashlib.sha256(english.strip().encode()).hexdigest(), 'Translations are stale or for another release')
    locales = data.get('locales', {})
    require(isinstance(locales, dict) and set(locales) <= set(LOCALES),
            'Translations contain a language outside the reviewed matrix')
    for lang in ('zh', 'ja', 'ko'):
        text = data.get('locales', {}).get(lang)
        if text is not None:
            require(isinstance(text, str) and 0 < len(text.strip()) <= 18000, 'Invalid translated release notes')
            result[lang] = text.strip()
    return result


def _cubism_version_key(version):
    require(isinstance(version, str) and CUBISM_VERSION.fullmatch(version), 'Invalid Cubism version')
    major, minor, patch = version.split('.')
    return int(major), int(minor), int(patch)


def compatibility_versions(root):
    root = Path(root)
    matrix_path = root / COMPATIBILITY_MATRIX
    artifacts_path = root / REVIEWED_HOST_ARTIFACTS
    require(artifacts_path.is_file() and not artifacts_path.is_symlink(), 'Missing or invalid reviewed releases table')
    from .candidate import _load_script
    reader = _load_script('release_reviewed_releases',
                          Path(__file__).resolve().parents[2] / 'preview/reviewed_host_artifacts.py')
    artifact_versions = [r['version'] for r in reader.load_releases(artifacts_path)]
    require(0 < len(artifact_versions) <= 64, 'Invalid reviewed host artifact versions')
    matrix_versions = None
    if matrix_path.is_file():
        require(not matrix_path.is_symlink() and matrix_path.stat().st_size < 2 * 1024 * 1024,
                'Invalid compatibility matrix file')
        matrix = json.loads(matrix_path.read_text(encoding='utf-8'))
        require(isinstance(matrix, dict) and matrix.get('format') == 'turboism.compatibility-matrix/v1',
                'Invalid compatibility matrix format')
        raw_matrix = matrix.get('reviewedVersions')
        require(isinstance(raw_matrix, list) and 0 < len(raw_matrix) <= 64,
                'Invalid compatibility matrix versions')
        ordered_matrix = sorted(set(raw_matrix), key=_cubism_version_key)
        require(raw_matrix == ordered_matrix,
                'Compatibility matrix versions must be unique and sorted')
        matrix_versions = ordered_matrix
    if matrix_versions is not None:
        require(artifact_versions == matrix_versions,
                'Compatibility matrix reviewedVersions drifted from reviewed-releases.json')
    return artifact_versions


def collapse_ranges(versions, reviewed=None):
    require(isinstance(versions, (list, tuple)) and 0 < len(versions) <= 64,
            'Invalid Cubism version list')
    ordered = sorted(set(versions), key=_cubism_version_key)
    require(list(versions) == ordered, 'Cubism versions must be unique and sorted')
    if reviewed is not None:
        require(isinstance(reviewed, (list, tuple)) and len(reviewed) > 0,
                'Invalid reviewed version list')
        ordered_reviewed = sorted(set(reviewed), key=_cubism_version_key)
        require(list(reviewed) == ordered_reviewed and set(ordered) <= set(ordered_reviewed),
                'Cubism versions must be a sorted subset of reviewed versions')
        index = {v: i for i, v in enumerate(ordered_reviewed)}
        runs = [[ordered[0]]]
        for v in ordered[1:]:
            if index[v] == index[runs[-1][-1]] + 1:
                runs[-1].append(v)
            else:
                runs.append([v])
    else:
        repo_matrix = Path(__file__).resolve().parents[3] / REVIEWED_HOST_ARTIFACTS
        canonical = None
        if repo_matrix.is_file() and not repo_matrix.is_symlink():
            data = json.loads(repo_matrix.read_text(encoding='utf-8'))
            rv = [r['version'] for r in data['releases']]
            if isinstance(rv, list) and set(ordered) <= set(rv):
                canonical = {v: i for i, v in enumerate(rv)}
        runs = [[ordered[0]]]
        for v in ordered[1:]:
            prev = runs[-1][-1]
            maj1, min1, pat1 = _cubism_version_key(prev)
            maj2, min2, pat2 = _cubism_version_key(v)
            consecutive = maj1 == maj2 and (
                (min1 == min2 and pat2 == pat1 + 1)
                or (min2 == min1 + 1 and pat2 == 0)
            )
            contiguous = (canonical[v] == canonical[prev] + 1 and consecutive) if canonical is not None else consecutive
            if contiguous:
                runs[-1].append(v)
            else:
                runs.append([v])
    return [{'from': run[0], 'to': run[-1]} for run in runs]


def compatibility_marker(root):
    versions = compatibility_versions(root)
    ranges = collapse_ranges(versions)
    payload = {
        'schemaVersion': 1,
        'cubismEditor': {
            'versions': versions,
            'ranges': ranges,
        },
        'platforms': list(PLATFORMS),
        'matrixUrl': MATRIX_URL,
    }
    encoded = json.dumps(payload, ensure_ascii=False, separators=(',', ':'))
    encoded = encoded.replace('<', '\\u003c').replace('>', '\\u003e')
    require(len(encoded.encode('utf-8')) <= 4096, 'Compatibility metadata exceeds budget')
    return '<!-- turboism-compatibility-v1 ' + encoded + ' -->\n'


def metadata_marker(version, source, locales, context=None):
    require(all(lang in LOCALES and isinstance(text,str) and 0<len(text)<=20000 for lang,text in locales.items()), 'Invalid localized notes')
    payload = {'schemaVersion': 1, 'version': version, 'sourceRevision': source, 'locales': locales}
    if context is not None:
        payload['baseRevision'] = context['baseRevision']
        payload['baseTag'] = context['baseTag']
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    encoded = encoded.replace('<', '\\u003c').replace('>', '\\u003e')
    require(len(encoded.encode()) < 58000, 'Localized notes exceed release metadata budget')
    return '<!-- turboism-notes-v1 '+encoded+' -->\n'


def candidate_notes(root, receipt, context):
    if receipt['channel'] == 'nightly':
        validate_context(root, receipt, context)
        locales = render_nightly(receipt, context)
    else:
        from .candidate import _load_script
        from .versions import framework_version
        from .channels import resolve_version
        base = framework_version(root)
        resolve_version(base, 'beta', receipt['version'])
        extractor = _load_script('localized_beta_notes', Path(__file__).parent.parent/'extract-release-notes.py')
        english = extractor.extract((Path(root)/'CHANGELOG.md').read_text(encoding='utf-8'),base)
        locales = reviewed_locales(root, base, english)
        # Candidates publish to all four reviewed languages; stable_metadata stays
        # lenient so it can still reproduce the binding of pre-existing releases.
        require({'zh', 'ja', 'ko'} <= set(locales),
                'Reviewed release notes must include zh, ja and ko translations')
    body = locales['en'].rstrip()+f"\n\nSource: {receipt['sourceRevision']}\nBuild: {receipt['buildNumber']}\n\n"
    body += metadata_marker(receipt['version'], receipt['sourceRevision'], locales, context)
    body += compatibility_marker(root)
    body += '<!-- turboism-build-v1 '+json.dumps(receipt,sort_keys=True,separators=(',',':'))+' -->\n'
    require(len(body.encode()) < 65000, 'Release notes exceed publication budget')
    return body


def stable_metadata(root, version, source, english):
    # Candidates from before this feature must reproduce the original tag/body binding.
    if not (Path(root)/'scripts/release/turboism_release/release_notes.py').is_file():
        return ''
    return (metadata_marker(version, source, reviewed_locales(root, version, english))
            + compatibility_marker(root))
