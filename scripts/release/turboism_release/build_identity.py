"""A source-bound, cross-channel product build sequence. Never assigns history numbers."""
from __future__ import annotations
import base64, io, json, re, time, zipfile
from pathlib import Path
BRANCH = 'build-ledger'
VERSION = re.compile(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?')

def identity(version, source, run_id, attempt):
    match = VERSION.fullmatch(version)
    if not match or any(re.fullmatch(r'0\d+', x) for x in (match[4] or '').split('.')):
        raise ValueError('Invalid canonical build version')
    if not re.fullmatch('[a-f0-9]{40}', source) or not re.fullmatch('[1-9][0-9]{0,19}', str(run_id)) or type(attempt) is not int or attempt < 1:
        raise ValueError('Invalid candidate identity')
    channel = 'stable' if not match[4] else 'nightly' if 'nightly' in match[4].split('.') else 'beta'
    return dict(schemaVersion=1, version=version, channel=channel, sourceRevision=source, runId=str(run_id), runAttempt=attempt)

def reserve(counter, existing, wanted):
    number = counter.get('nextBuildNumber')
    if counter.get('schemaVersion') != 1 or type(number) is not int or not 1 <= number < 9007199254740991:
        raise ValueError('Invalid build counter; refusing to reset')
    if existing is not None:
        if any(existing.get(k) != v for k, v in wanted.items()) or type(existing.get('buildNumber')) is not int or not 0 < existing['buildNumber'] < number:
            raise ValueError('Candidate already has a different build identity')
        return existing, counter
    return {**wanted, 'buildNumber': number}, {'schemaVersion': 1, 'nextBuildNumber': number + 1}

def decode_file(value):
    if not isinstance(value, dict) or value.get('encoding') != 'base64':
        raise ValueError('Invalid ledger document')
    data = base64.b64decode(value['content'])
    if len(data) > 8192:
        raise ValueError('Oversized ledger document')
    return json.loads(data)

def allocate(github, wanted, *, nightly=False):
    """Fast-forward-only ref updates are the CAS. A sibling commit can never overwrite a winner."""
    if nightly and not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", wanted['version']):
        raise ValueError('Nightly allocation needs a stable base version')
    path = f"entries/{wanted['runId']}-{wanted['runAttempt']}.json"
    for retry in range(12):
        ref = github.api(f'git/ref/heads/{BRANCH}', optional=True)
        head = ref['object']['sha'] if ref else None
        tree = github.api(f'git/commits/{head}')['tree']['sha'] if head else None
        entry = github.api(f'contents/{path}?ref={head}', optional=True) if head else None
        counter = decode_file(github.api(f'contents/counter.json?ref={head}')) if head else {'schemaVersion': 1, 'nextBuildNumber': 1}
        existing = decode_file(entry) if entry else None
        candidate = wanted
        if nightly:
            number = existing.get('buildNumber') if existing else counter.get('nextBuildNumber')
            candidate = identity(f"{wanted['version']}-0.nightly.{number}", wanted['sourceRevision'], wanted['runId'], wanted['runAttempt'])
        record, updated = reserve(counter, existing, candidate)
        if entry:
            return record
        payload = {'tree': [{'path': name, 'type': 'blob', 'mode': '100644', 'content': json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n'} for name, value in [('counter.json', updated), (path, record)]]}
        if tree:
            payload['base_tree'] = tree
        new_tree = github.api('git/trees', method='POST', data=payload)['sha']
        commit = github.api('git/commits', method='POST', data={'message': f"Allocate product build {record['buildNumber']}", 'tree': new_tree, 'parents': [head] if head else []})['sha']
        try:
            if head:
                github.api(f'git/refs/heads/{BRANCH}', method='PATCH', data={'sha': commit, 'force': False})
            else:
                github.api('git/refs', method='POST', data={'ref': f'refs/heads/{BRANCH}', 'sha': commit})
        except Exception:
            # Retry only through a fresh read. Lost responses return the persisted identity.
            if retry == 11:
                raise
            time.sleep(min(0.2 * (retry + 1), 2))
            continue
        return record
    raise ValueError('Could not reserve a build identity')

def read_optional_receipt(path):
    path = Path(path)
    if not path.exists():
        return None
    if path.is_symlink() or path.stat().st_size > 8192:
        raise ValueError('Invalid build receipt')
    return json.loads(path.read_text())

def verify_receipt(github, source_root, bundle_root, version, source, run_id, attempt):
    files = list(Path(bundle_root).rglob('build-identity.json'))
    required = (Path(source_root) / 'scripts/release/turboism_release/build_identity.py').is_file()
    if not files and not required:
        return None  # Candidates created before this feature retain their original identity.
    if len(files) != 1:
        raise ValueError('Candidate requires one build identity receipt')
    receipt = read_optional_receipt(files[0])
    expected = identity(version, source, run_id, attempt)
    registered = decode_file(github.api(f'contents/entries/{run_id}-{attempt}.json?ref={BRANCH}'))
    if receipt != registered or any(receipt.get(k) != v for k, v in expected.items()):
        raise ValueError('Build receipt differs from allocated candidate')
    number = receipt.get('buildNumber')
    if type(number) is not int or not 0 < number < 9007199254740991:
        raise ValueError('Invalid build number')
    extended = (Path(source_root) / 'runtime/src/main/java/dev/turboism/core/FrameworkBuildInfo.java').is_file()
    dist = next(Path(bundle_root).rglob(f'TurboismInstaller-{version}.jar')).parent
    def check_jar(data):
        with zipfile.ZipFile(data) as jar:
            if 'META-INF/MANIFEST.MF' not in jar.namelist():
                return False
            text = jar.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '').replace('\r\n', '\n')
            fields = dict(line.split(': ', 1) for line in text.splitlines() if ': ' in line)
            if 'Turboism-Build-Number' not in fields:
                return False
            if fields['Turboism-Build-Number'] != str(number) or fields.get('Turboism-Source-Revision') != source:
                raise ValueError('JAR build identity mismatch')
            if extended:
                if fields.get('Turboism-Version') != version or fields.get('Turboism-Channel') != receipt['channel']:
                    raise ValueError('JAR version/channel does not match its build receipt')
                resource = 'META-INF/turboism/framework-version.properties'
                if resource in jar.namelist():
                    properties = dict(line.split('=', 1) for line in jar.read(resource).decode('utf-8').splitlines() if '=' in line)
                    for key, value in {'version':version, 'channel':receipt['channel'], 'buildNumber':str(number), 'sourceRevision':source, 'buildKind':'ci', 'dirty':'false'}.items():
                        if properties.get(key) != value:
                            raise ValueError('Runtime version resource disagrees with the verified build receipt: ' + key)
            return True
    if not check_jar(dist / f'TurboismInstaller-{version}.jar'):
        raise ValueError('Installer JAR has no build identity')
    for variant in ('full', 'lite'):
        with zipfile.ZipFile(dist / f'turboism-{version}-{variant}.zip') as archive:
            if extended:
                with zipfile.ZipFile(io.BytesIO(archive.read('turboism-agent.jar'))) as agent:
                    if 'META-INF/turboism/framework-version.properties' not in agent.namelist():
                        raise ValueError('Product runtime is missing its embedded build identity')
            checked = sum(check_jar(io.BytesIO(archive.read(n))) for n in archive.namelist() if n.endswith('.jar') and ('turboism' in n.lower()))
            if not checked:
                raise ValueError('Portable archive has no identifiable product JAR')
    return receipt
