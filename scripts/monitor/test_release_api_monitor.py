"""Offline regression tests; never create real incidents or download binaries."""
import copy
import datetime as dt
import io
import json
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit, parse_qs

import release_api_monitor as monitor

NOW = dt.datetime(2026, 9, 11, 12, 0, tzinfo=dt.timezone.utc)
STAMP = NOW.isoformat()
RUN = 'https://github.com/turboism/Turboism/actions/runs/123'


def health(status='ready'):
    return {'service': 'turboism-release-api', 'status': status,
            'synchronizedAt': STAMP, 'mirrorConfigured': True,
            'channels': [{'channel': c, 'status': 'not_published' if c == 'beta' else 'ready'}
                         for c in ('stable', 'beta', 'nightly')]}


def release(channel='stable', status='ready'):
    return {'schemaVersion': 1, 'channel': channel, 'status': status, 'updatedAt': STAMP,
            'release': None if status == 'not_published' else {
                'channel': channel, 'version': '1.2.3', 'sourceRevision': 'a' * 40,
                'assets': [{'name': str(i), 'size': 10, 'sha256': 'b' * 64} for i in range(4)]}}


def report(ok):
    return {'healthy': ok, 'checkedAt': STAMP, 'results': [
        {'endpoint': p, 'ok': ok, 'http': 200 if ok else 503,
         'detail': 'OK' if ok else 'HTTP 503', 'attempts': 1 if ok else 3}
        for p in monitor.ENDPOINTS]}


class FakeGitHub:
    def __init__(self, issues=()):
        self.issues = copy.deepcopy(list(issues))
        self.writes = []
        self.reads = []

    def __call__(self, method, path, data=None):
        if method == 'GET':
            self.reads.append(path)
            return [x for x in self.issues if x['state'] == 'open']
        self.writes.append((method, path, data))
        if method == 'POST':
            issue = dict(data, number=len(self.issues) + 1, state='open',
                         user={'login': 'github-actions[bot]'})
            self.issues.append(issue)
            return issue
        number = int(path.rsplit('/', 1)[-1])
        issue = next(x for x in self.issues if x['number'] == number)
        issue.update(data)
        return issue


class DocumentTests(unittest.TestCase):
    def test_four_fixed_public_endpoints_only(self):
        self.assertEqual(monitor.ENDPOINTS, ('/health', '/v1/releases/stable.json',
                         '/v1/releases/beta.json', '/v1/releases/nightly.json'))

    def test_valid_health_and_available_stale_fallback_are_not_outages(self):
        for state in ('ready', 'degraded'):
            self.assertIsNone(monitor.document_error('/health', health(state), NOW))

    def test_beta_not_published_is_normal(self):
        self.assertIsNone(monitor.document_error('/v1/releases/beta.json',
                          release('beta', 'not_published'), NOW))

    def test_channel_mismatch_and_unavailable_are_rejected(self):
        for data in (release('nightly'), release('stable', 'unavailable'), None, []):
            self.assertIsNotNone(monitor.document_error('/v1/releases/stable.json', data, NOW))

    def test_missing_or_partial_assets_are_not_a_healthy_release(self):
        for change in (lambda r: r['release'].update(assets=[]),
                       lambda r: r['release'].update(sourceRevision='bad'),
                       lambda r: r['release']['assets'][0].update(sha256='bad'),
                       lambda r: r.update(schemaVersion=True)):
            data = release(); change(data)
            self.assertIsNotNone(monitor.document_error('/v1/releases/stable.json', data, NOW))

    def test_health_must_check_all_three_channels_and_the_mirror(self):
        for change in (lambda h: h.update(mirrorConfigured=False),
                       lambda h: h['channels'].pop(),
                       lambda h: h['channels'][0].update(status='unavailable'),
                       lambda h: h.update(service='unrelated'),
                       lambda h: h['channels'][0].update(channel=[])):
            data = health(); change(data)
            self.assertIsNotNone(monitor.document_error('/health', data, NOW))

    def test_old_invalid_and_future_sync_times_are_not_green(self):
        for stamp in ((NOW - dt.timedelta(hours=2)).isoformat(), 'broken',
                      (NOW + dt.timedelta(minutes=10)).isoformat(), '2026-09-11T12:00:00'):
            data = health('degraded'); data['synchronizedAt'] = stamp
            self.assertIsNotNone(monitor.document_error('/health', data, NOW))

    def test_probe_catches_503_html_and_network_failure_without_echoing_bodies(self):
        for get in (lambda _: (503, None), lambda _: (200, '<html>@someone</html>')):
            result = monitor.probe('/health', get=get, now=NOW)
            self.assertFalse(result['ok']); self.assertNotIn('@someone', result['detail'])
        with patch.object(monitor, 'request_json', side_effect=TimeoutError('secret-value')):
            result = monitor.probe('/health', now=NOW)
            self.assertFalse(result['ok']); self.assertNotIn('secret-value', json.dumps(result))

    def test_response_reader_bounds_bodies_and_rejects_html(self):
        class Reply(io.BytesIO):
            status = 200
            headers = {'Content-Type': 'application/json'}
        with patch.object(monitor, 'OPENER') as opener:
            opener.open.return_value = Reply(b'{' + b' ' * (monitor.MAX_BODY_BYTES + 1))
            with self.assertRaises(ValueError): monitor.request_json(monitor.ORIGIN + '/health')
            response = Reply(b'<html>challenge</html>')
            response.headers = {'Content-Type': 'text/html'}
            opener.open.return_value = response
            with self.assertRaises(ValueError): monitor.request_json(monitor.ORIGIN + '/health')


    def test_public_probe_never_receives_the_github_token(self):
        class Reply(io.BytesIO):
            status = 200
            headers = {'Content-Type': 'application/json'}
        with patch.dict(monitor.os.environ, {'GITHUB_TOKEN': 'must-stay-private'}):
            with patch.object(monitor, 'OPENER') as opener:
                opener.open.return_value = Reply(json.dumps(health()).encode())
                monitor.request_json(monitor.ORIGIN + '/health')
                request = opener.open.call_args.args[0]
                self.assertIsNone(request.get_header('Authorization'))

    def test_redirect_handler_does_not_forward_a_request(self):
        self.assertIsNone(monitor.NoRedirect().redirect_request(
            None, None, 302, '', {}, 'https://untrusted.invalid'))


class RetryTests(unittest.TestCase):
    def test_transient_failure_recovers_without_alarm_and_healthy_endpoints_are_not_retried(self):
        calls = {p: 0 for p in monitor.ENDPOINTS}; sleeps = []
        def probe(p):
            calls[p] += 1
            return {'endpoint': p, 'ok': p != '/health' or calls[p] > 1,
                    'http': 200, 'detail': 'test'}
        data = monitor.check_all(probe_fn=probe, sleep=sleeps.append)
        self.assertTrue(data['healthy']); self.assertEqual(calls['/health'], 2)
        self.assertEqual(calls['/v1/releases/beta.json'], 1); self.assertEqual(sleeps, [20])

    def test_persistent_outage_requires_three_failed_probes(self):
        data = monitor.check_all(probe_fn=lambda p: {'endpoint': p, 'ok': False,
                                 'http': 503, 'detail': 'HTTP 503'}, sleep=lambda _: None)
        self.assertFalse(data['healthy']); self.assertEqual(len(data['results']), 4)
        self.assertTrue(all(r['attempts'] == 3 for r in data['results']))


class NotificationTests(unittest.TestCase):
    def test_healthy_baseline_does_not_create_issue(self):
        api = FakeGitHub()
        self.assertEqual(monitor.notify(report(True), api, RUN)['action'], 'none')
        self.assertFalse(api.writes)

    def test_one_issue_per_outage_and_recovery_is_atomic(self):
        api = FakeGitHub()
        self.assertEqual(monitor.notify(report(False), api, RUN)['action'], 'opened')
        self.assertEqual(api.issues[0]['assignees'], ['RainTrap341'])
        self.assertIn('@RainTrap341', api.issues[0]['body'])
        self.assertIn(RUN, api.issues[0]['body'])
        self.assertEqual(monitor.notify(report(False), api, RUN)['action'], 'ongoing')
        self.assertEqual(len(api.writes), 1)
        self.assertEqual(monitor.notify(report(True), api, RUN)['action'], 'recovered')
        self.assertEqual(api.issues[0]['state'], 'closed')
        self.assertIn('恢复', api.issues[0]['body'])
        self.assertEqual(len(api.writes), 2)
        monitor.notify(report(True), api, RUN)
        self.assertEqual(len(api.writes), 2)
        monitor.notify(report(False), api, RUN)
        self.assertEqual(len(api.issues), 2)

    def test_does_not_close_human_issues_or_pull_requests(self):
        api = FakeGitHub([{'number': 5, 'state': 'open', 'body': monitor.MARKER,
                          'user': {'login': 'RainTrap341'}},
                         {'number': 6, 'state': 'open', 'body': monitor.MARKER,
                          'user': {'login': 'github-actions[bot]'}, 'pull_request': {}}])
        monitor.notify(report(True), api, RUN)
        self.assertFalse(api.writes)

    def test_listing_error_must_not_create_duplicate_issue(self):
        api = FakeGitHub()
        def broken(*_): raise RuntimeError('API unavailable')
        with self.assertRaises(RuntimeError): monitor.notify(report(False), broken, RUN)
        self.assertFalse(api.writes)

    def test_pagination_is_exhausted_before_creating_an_incident(self):
        issue = {'number': 123, 'state': 'open', 'body': monitor.MARKER,
                 'user': {'login': 'github-actions[bot]'}}
        calls = []
        def api(method, path, data=None):
            calls.append((method, path))
            if parse_qs(urlsplit(path).query).get('page') == ['1']: return [{'number': i, 'body': ''} for i in range(100)]
            if parse_qs(urlsplit(path).query).get('page') == ['2']: return [issue]
            raise AssertionError('unexpected write')
        self.assertEqual(monitor.notify(report(False), api, RUN)['action'], 'ongoing')
        self.assertEqual(len(calls), 2)

    def test_notification_requires_trusted_main_workflow(self):
        for event in ('pull_request', 'pull_request_target'):
            env = {'GITHUB_REPOSITORY': monitor.REPOSITORY, 'GITHUB_REF': 'refs/heads/main',
                   'GITHUB_EVENT_NAME': event, 'GITHUB_RUN_ID': '123', 'GITHUB_TOKEN': 'secret'}
            with self.assertRaises(ValueError): monitor.notification_context(env)
        env['GITHUB_EVENT_NAME'] = 'schedule'
        self.assertEqual(monitor.notification_context(env)[1], RUN)


if __name__ == '__main__':
    unittest.main()
