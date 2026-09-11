"""Read-only public API probes, with deduplicated GitHub incident notifications.

No third-party packages, Cloudflare credentials, sync calls, or file downloads.
Only the trusted main-branch Actions workflow may enable notification writes.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request

ORIGIN = 'https://api.turboism.dev'
REPOSITORY = 'turboism/Turboism'
RECIPIENT = 'RainTrap341'
CHANNELS = ('stable', 'beta', 'nightly')
ENDPOINTS = ('/health', *(f'/v1/releases/{c}.json' for c in CHANNELS))
MARKER = '<!-- turboism-release-api-monitor:v1 -->'
MAX_BODY_BYTES = 1024 * 1024
MAX_SYNC_AGE_SECONDS = 3600
REQUEST_TIMEOUT_SECONDS = 15


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(NoRedirect())


def utcnow():
    return dt.datetime.now(dt.timezone.utc)


def request_json(url, *, method='GET', headers=None, data=None):
    """Bound response size and timeout; never forward credentials on redirects."""
    request_headers = {'Accept': 'application/json', 'User-Agent': 'Turboism-API-Monitor',
                       'Cache-Control': 'no-cache', **(headers or {})}
    payload = None if data is None else json.dumps(data).encode('utf-8')
    if payload is not None:
        request_headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(url, data=payload, headers=request_headers, method=method)
    try:
        with OPENER.open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            if response.status == 204:
                return 204, None
            content_type = response.headers.get('Content-Type', '').split(';', 1)[0].lower()
            if content_type != 'application/json':
                raise ValueError('Non-JSON response')
            body = response.read(MAX_BODY_BYTES + 1)
            if len(body) > MAX_BODY_BYTES:
                raise ValueError('Response too large')
            return response.status, json.loads(body.decode('utf-8'))
    except urllib.error.HTTPError as error:
        # Do not log response bodies, cookies, or arbitrary error-page markup.
        code = error.code
        error.close()
        return code, None


def sync_error(value, now):
    try:
        timestamp = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
        if timestamp.tzinfo is None:
            return 'Missing synchronization timezone'
        age = (now - timestamp).total_seconds()
    except (ValueError, TypeError, AttributeError):
        return 'Invalid synchronization time'
    if age < -300:
        return 'Synchronization time is in the future'
    if age > MAX_SYNC_AGE_SECONDS:
        return 'No successful synchronization for over 60 minutes'
    return None


def document_error(path, data, now=None):
    """An unpublished Beta is healthy; stale fallback is usable, not fully fresh."""
    now = now or utcnow()
    if not isinstance(data, dict):
        return 'Invalid JSON object'
    if path == '/health':
        if data.get('service') != 'turboism-release-api':
            return 'Unexpected service identity'
        if data.get('status') not in ('ready', 'degraded'):
            return 'Health status unavailable'
        if data.get('mirrorConfigured') is not True:
            return 'Official mirror is not configured'
        channels = data.get('channels')
        if (not isinstance(channels, list) or len(channels) != 3
                or any(not isinstance(c, dict) for c in channels)
                or any(c.get('channel') not in CHANNELS for c in channels)
                or {c.get('channel') for c in channels} != set(CHANNELS)
                or any(c.get('status') not in ('ready', 'not_published') for c in channels)):
            return 'One or more channel health states are unavailable'
        return sync_error(data.get('synchronizedAt'), now)
    if path not in ENDPOINTS:
        raise ValueError('Unexpected monitor endpoint')
    channel = path.rsplit('/', 1)[-1].removesuffix('.json')
    if type(data.get('schemaVersion')) is not int or data['schemaVersion'] != 1:
        return 'Invalid release schema'
    if data.get('channel') != channel:
        return 'Release channel mismatch'
    if data.get('status') not in ('ready', 'not_published'):
        return 'Release channel unavailable'
    if data['status'] == 'not_published':
        if data.get('release') is not None:
            return 'Unpublished channel contains a release'
    else:
        release = data.get('release')
        if (not isinstance(release, dict) or release.get('channel') != channel
                or not isinstance(release.get('version'), str) or not release['version']
                or not re.fullmatch(r'[a-f0-9]{40}', str(release.get('sourceRevision', '')))):
            return 'Invalid release identity'
        assets = release.get('assets')
        if (not isinstance(assets, list) or len(assets) != 4
                or any(not isinstance(a, dict) or type(a.get('size')) is not int
                       or a['size'] <= 0 or not re.fullmatch(r'[a-f0-9]{64}', str(a.get('sha256', '')))
                       for a in assets)):
            return 'Missing or invalid download assets'
    return sync_error(data.get('updatedAt'), now)


def probe(path, *, get=None, now=None):
    if path not in ENDPOINTS:
        raise ValueError('Unexpected monitor endpoint')
    result = {'endpoint': path, 'http': None, 'ok': False, 'detail': 'Network error or timeout'}
    try:
        code, data = (get or request_json)(ORIGIN + path)
        result['http'] = code
        error = f'HTTP {code}' if code != 200 else document_error(path, data, now)
        result.update(ok=error is None, detail=error or 'OK')
    except (OSError, ValueError, TimeoutError):
        # Fixed text prevents an upstream response from injecting mentions into an issue.
        result['detail'] = 'Network, timeout, or invalid JSON response'
    return result


def check_all(*, probe_fn=probe, sleep=time.sleep, attempts=3, delay=20):
    results = {}
    pending = list(ENDPOINTS)
    for attempt in range(1, attempts + 1):
        with ThreadPoolExecutor(max_workers=4) as executor:
            rows = list(executor.map(probe_fn, pending))
        for row in rows:
            results[row['endpoint']] = {**row, 'attempts': attempt}
        pending = [p for p in pending if not results[p]['ok']]
        if not pending:
            break
        if attempt < attempts:
            sleep(delay)
    return {'healthy': all(r['ok'] for r in results.values()), 'checkedAt': utcnow().isoformat(),
            'results': [results[p] for p in ENDPOINTS]}


def open_incidents(api):
    found = []
    for page in range(1, 21):
        rows = api('GET', f'/issues?state=open&creator=github-actions%5Bbot%5D&per_page=100&page={page}')
        if not isinstance(rows, list):
            raise ValueError('Invalid issue listing')
        found.extend(r for r in rows if 'pull_request' not in r
                     and r.get('user', {}).get('login') == 'github-actions[bot]'
                     and str(r.get('body') or '').startswith(MARKER))
        if len(rows) < 100:
            return found
    raise RuntimeError('Incomplete incident enumeration; no issue was created')


def summary(report):
    lines = ['## Turboism API health', '', f"Checked at (UTC): {report['checkedAt']}", '',
             '| Endpoint | HTTP | Result | Attempts |', '| --- | --- | --- | --- |']
    for row in report['results']:
        lines.append(f"| `{row['endpoint']}` | {row['http'] or '—'} | {row['detail']} | {row['attempts']} |")
    return '\n'.join(lines)


def notify(report, api, run_url):
    incidents = open_incidents(api)
    if not report['healthy']:
        if incidents:
            return {'action': 'ongoing', 'issues': [r['number'] for r in incidents]}
        body = (f'{MARKER}\n@{RECIPIENT} 检测到发布 API 异常，失败端点已重试确认。\n\n'
                + summary(report) + f'\n\n[巡检运行]({run_url})\n\n'
                '同一次故障不重复发送提醒；恢复后自动关闭此记录。'
                '仅检查公开 JSON，不下载文件，也不会自动重启、部署或修改发布数据。')
        issue = api('POST', '/issues', {'title': '[API monitor] api.turboism.dev 异常',
                                       'body': body, 'assignees': [RECIPIENT]})
        return {'action': 'opened', 'issues': [issue['number']]}
    for issue in incidents:
        body = (issue['body'] + f"\n\n## 已恢复\n\n{report['checkedAt']} (UTC)\n\n"
                + summary(report) + f'\n\n[恢复检查]({run_url})')
        # Body and close state are updated in one request, without duplicate comments.
        api('PATCH', f"/issues/{issue['number']}",
            {'body': body, 'state': 'closed', 'state_reason': 'completed'})
    return {'action': 'recovered' if incidents else 'none', 'issues': [r['number'] for r in incidents]}


def notification_context(env):
    if (env.get('GITHUB_REPOSITORY') != REPOSITORY or env.get('GITHUB_REF') != 'refs/heads/main'
            or env.get('GITHUB_EVENT_NAME') not in ('schedule', 'workflow_dispatch', 'push')
            or not re.fullmatch(r'[1-9][0-9]*', env.get('GITHUB_RUN_ID', ''))
            or not env.get('GITHUB_TOKEN')):
        raise ValueError('Notifications require the trusted main-branch workflow')
    return env['GITHUB_TOKEN'], f"https://github.com/{REPOSITORY}/actions/runs/{env['GITHUB_RUN_ID']}"


def github_client(token):
    def api(method, path, data=None):
        if not re.fullmatch(r'/issues(?:\?[^#]*|/[1-9][0-9]*)?', path):
            raise ValueError('Unexpected notification API path')
        code, value = request_json(f'https://api.github.com/repos/{REPOSITORY}{path}', method=method,
                                   headers={'Authorization': 'Bearer ' + token,
                                            'X-GitHub-Api-Version': '2022-11-28'}, data=data)
        if not 200 <= code < 300:
            raise RuntimeError(f'GitHub notification API HTTP {code}')
        return value
    return api


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--notify', action='store_true', help='Enable main-branch GitHub incident writes')
    args = parser.parse_args()
    context = notification_context(os.environ) if args.notify else None
    report = check_all()
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    notification = notify(report, github_client(context[0]), context[1]) if context else {'action': 'disabled'}
    print(json.dumps({'notification': notification}, ensure_ascii=False), flush=True)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as output:
            output.write(summary(report) + '\n\nNotification: `' + notification['action'] + '`\n')
    # A successfully reported ongoing outage must not send repeated Actions failure emails.
    # Monitor execution / notification failures still exit nonzero and remain visible.
    return 0 if args.notify or report['healthy'] else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print('Monitor execution or notification failed (' + type(error).__name__ +
              '); no recovery was assumed.', file=sys.stderr)
        sys.exit(2)
