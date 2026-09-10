"""Source-bound Beta/Nightly candidates and immutable prerelease publication."""
from __future__ import annotations
import hashlib
import json
import re
import subprocess
from pathlib import Path
from .build_identity import identity, read_optional_receipt, verify_receipt
from .candidate import _load_script
from .channels import channel_for, require_version, resolve_version, NIGHTLY
from .promotion import ensure_tag, tag_binding
from .versions import framework_version

SCRIPTS = Path(__file__).resolve().parents[1]
RECEIPT = re.compile(r'<!-- turboism-build-v1 (.*?) -->', re.DOTALL)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def receipt_from_body(body, expected_channel=None):
    matches = RECEIPT.findall(str(body or ''))
    require(len(matches) == 1, 'Prerelease requires exactly one build receipt')
    record = json.loads(matches[0])
    require(isinstance(record, dict), 'Invalid build receipt')
    expected = identity(record['version'], record['sourceRevision'], record['runId'], record['runAttempt'])
    channel = channel_for(record['version'])
    require(channel in ('beta','nightly') and (expected_channel is None or channel == expected_channel), 'Prerelease channel mismatch')
    number = record.get('buildNumber')
    require(type(number) is int and 0 < number < 9007199254740991, 'Invalid allocated build number')
    require(record == {**expected, 'buildNumber':number}, 'Build receipt has changed fields')
    if channel == 'nightly':
        require(int(NIGHTLY.fullmatch(record['version'])[4]) == number, 'Nightly version/build identity mismatch')
    return record


def names_for(version):
    require(channel_for(version) in ('beta','nightly'), 'Not a prerelease version')
    primary = [f'TurboismInstaller-{version}.exe', f'TurboismInstaller-{version}.jar',
               f'turboism-{version}-full.zip', f'turboism-{version}-lite.zip']
    return {name for item in primary for name in (item, item+'.sha256')}


def missing_assets(raw, expected, tag):
    require(isinstance(raw,dict) and raw.get('tag_name') == tag and raw.get('prerelease') is True
            and type(raw.get('draft')) is bool and type(raw.get('id')) is int and raw['id'] > 0,
            'Invalid prerelease identity')
    require(set(expected) == names_for(tag[1:]), 'Invalid canonical asset set')
    require(isinstance(raw.get('assets'),list), 'Invalid remote asset list')
    seen = set()
    for asset in raw['assets']:
        name = asset.get('name')
        require(name in expected and name not in seen and asset.get('state') == 'uploaded', 'Unexpected or unfinished asset')
        wanted = expected[name]
        require(type(wanted.get('size')) is int and wanted['size'] > 0
                and re.fullmatch('[a-f0-9]{64}',wanted.get('sha256','')), 'Invalid expected digest')
        require(asset.get('size') == wanted['size'] and asset.get('digest') == 'sha256:'+wanted['sha256'], 'Same version has changed bytes')
        seen.add(name)
    missing = sorted(set(expected)-seen)
    require(raw['draft'] or not missing, 'Published prerelease is incomplete')
    return missing


def notes_for(source_root, receipt):
    channel, version = receipt['channel'], receipt['version']
    heading = f"# Turboism {version}\n\n{channel.capitalize()} development build; back up projects before use.\n"
    if channel == 'beta':
        base = framework_version(source_root)
        resolve_version(base, 'beta', version)
        extractor = _load_script('prerelease_notes', SCRIPTS/'extract-release-notes.py')
        heading += '\n' + extractor.extract((Path(source_root)/'CHANGELOG.md').read_text(encoding='utf-8'), base)
    return (heading + f"\nSource: {receipt['sourceRevision']}\nBuild: {receipt['buildNumber']}\n"
            + '\n<!-- turboism-build-v1 '+json.dumps(receipt,sort_keys=True,separators=(',',':'))+' -->\n')


def verify_files(github, source_root, bundle_root, source_sha, run_id, attempt, version):
    channel = channel_for(version)
    require(channel in ('beta','nightly'), 'Not a prerelease candidate')
    root, bundle = Path(source_root), Path(bundle_root)
    require(bundle.is_dir() and not bundle.is_symlink() and not any(p.is_symlink() for p in bundle.rglob('*')), 'Unsafe candidate bundle')
    actual = subprocess.run(['git','-C',str(root),'rev-parse','HEAD'],capture_output=True,text=True,check=True).stdout.strip()
    require(actual == source_sha, 'Source checkout differs from candidate')
    clean = subprocess.run(['git','-C',str(root),'diff','--quiet','HEAD','--'],capture_output=True)
    require(clean.returncode == 0, 'Source changed after checkout')
    if channel == 'beta':
        resolve_version(framework_version(root),channel,version)
    verifier = _load_script('prerelease_payload_verifier',SCRIPTS/'verify-release.py')
    manifest = verifier.artifact_manifest(bundle/'dist',version,root/'packaging/release-plugins.txt',channel=channel)
    receipt = verify_receipt(github,root,bundle,version,source_sha,str(run_id),int(attempt))
    require(receipt['channel'] == channel, 'Artifact channel mismatch')
    receipt_from_body('<!-- turboism-build-v1 '+json.dumps(receipt)+' -->',channel)
    return manifest, receipt


def prepare(github, source_root, bundle_root, source_sha, run_id, attempt, version):
    manifest, receipt = verify_files(github,source_root,bundle_root,source_sha,run_id,attempt,version)
    notes = notes_for(Path(source_root), receipt)
    candidate = {'schemaVersion':1,'type':'turboism.prerelease-candidate','receipt':receipt,
                 'manifest':manifest,'notesSha256':hashlib.sha256(notes.encode()).hexdigest()}
    bundle = Path(bundle_root)
    (bundle/'release-notes.md').write_text(notes,encoding='utf-8')
    (bundle/'prerelease-candidate.json').write_text(json.dumps(candidate,sort_keys=True,separators=(',',':'))+'\n')
    return candidate


def verify_candidate(github, source_root, bundle_root, source_sha, run_id, attempt, expected_channel=None):
    bundle = Path(bundle_root)
    path = bundle/'prerelease-candidate.json'
    require(path.is_file() and not path.is_symlink() and path.stat().st_size < 128*1024, 'Missing or invalid prerelease candidate')
    value = json.loads(path.read_text())
    require(set(value) == {'schemaVersion','type','receipt','manifest','notesSha256'}
            and value['schemaVersion'] == 1 and value['type'] == 'turboism.prerelease-candidate', 'Invalid candidate schema')
    receipt = receipt_from_body('<!-- turboism-build-v1 '+json.dumps(value['receipt'])+' -->',expected_channel)
    manifest, actual = verify_files(github,source_root,bundle_root,source_sha,run_id,attempt,receipt['version'])
    notes = notes_for(Path(source_root),actual)
    require(receipt == actual and value['manifest'] == manifest, 'Candidate manifest/receipt changed')
    require((bundle/'release-notes.md').read_bytes() == notes.encode()
            and value['notesSha256'] == hashlib.sha256(notes.encode()).hexdigest(), 'Candidate notes are not source-bound')
    return manifest, actual, notes


def publish(github, dist, receipt, manifest, notes):
    version, source = receipt['version'], receipt['sourceRevision']
    tag = 'v'+version
    require(receipt_from_body(notes,receipt['channel']) == receipt, 'Notes have a different build receipt')
    require(manifest['version'] == version, 'Manifest version mismatch')
    expected = {a['name']:{'size':a['size'],'sha256':a['sha256']} for a in manifest['artifacts']}
    require(set(expected) == names_for(version) and len(manifest['artifacts']) == 8, 'Prerelease requires eight verified files')
    binding = hashlib.sha256(json.dumps({'receipt':receipt,'assets':expected,'notes':notes},sort_keys=True).encode()).hexdigest()
    bound = tag_binding(github,tag,source,binding)
    raw = github.api(f'releases/tags/{tag}',optional=True)
    if raw is not None:
        require(bound and raw.get('body') == notes, 'Existing release has a different source/notes binding')
        missing_assets(raw,expected,tag)
    # All observations precede the first mutation; tags/files are never overwritten.
    ensure_tag(github,tag,source,binding)
    if raw is None:
        raw = github.api('releases',method='POST',data={'tag_name':tag,'target_commitish':source,
                         'name':f'Turboism {version}','body':notes,'draft':True,'prerelease':True,"make_latest":"false"})
    for name in missing_assets(raw,expected,tag):
        github.upload(tag,Path(dist)/name)
    raw = github.api(f'releases/tags/{tag}')
    require(not missing_assets(raw,expected,tag) and tag_binding(github,tag,source,binding), 'Prerelease upload verification failed')
    if raw['draft']:
        github.api(f"releases/{raw['id']}",method='PATCH',data={'draft':False,'prerelease':True,"make_latest":"false"})
    final = github.api(f'releases/tags/{tag}')
    require(final['draft'] is False and final.get('published_at') and not missing_assets(final,expected,tag), 'Prerelease publication not confirmed')
    return tag
