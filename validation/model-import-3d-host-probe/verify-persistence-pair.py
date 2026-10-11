#!/usr/bin/env python3
"""T014 cross-job persistence oracle: Job A (save) report + archived CMO3 +
post-save PNG vs Job B (reopen in a NEW process) report + re-sampled PNGs.

All cross-job invariants are checked here — identical manifests and persistent
GUIDs, four-way CMO3 sha agreement, different real process identities, and
pixel equality per bound pose. Producer booleans are never trusted: PNGs are
decoded through the same strict raster path before comparison.

Authoritative per-job admission (succeeded/PASS lifecycle, normal exit,
identity, immutable fixture, cleanup, job-bound containment, build provenance,
report↔runId binding, archive inventory) is NOT reimplemented here — the CLI
delegates each job to verify-host's ``job_result``/``verify_build``, which run
the identical complete checks used for every host verdict. ``load_result`` is
the raw extraction layer used by tests; it performs real-schema lifecycle JSON
parsing and strict archive inventory/budgets but does not replace admission.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import tarfile

import runpy
HOST = runpy.run_path(str(Path(__file__).with_name('verify-host.py')))
PIXEL = runpy.run_path(str(Path(__file__).with_name('verify-pixel-sampling.py')))

SECTION_PREFIX = 'state/dev.turboism.validation.model-import-3d/'
REPORT_MEMBER = SECTION_PREFIX + 'report.json'
PNG_PREFIX = SECTION_PREFIX + 'pixel-sampling/'
PERSISTENCE_PREFIX = SECTION_PREFIX + 'persistence/'
CMO3_MEMBER = PERSISTENCE_PREFIX + 'mi3d-t014-model.cmo3'
POST_MEMBER = PERSISTENCE_PREFIX + 'post-save-p0.png'
REPORT_MAX = 32 * 1024 * 1024
PNG_MEMBER_MAX = 8 * 1024 * 1024
CMO3_MAX = 32 * 1024 * 1024
STATE_TOTAL_MAX = REPORT_MAX + CMO3_MAX + 8 * PNG_MEMBER_MAX
REOPEN_POSES = ('bound-p0', 'bound-p1', 'bound-m1')
SAVE_POSES = ('baseline', 'bound-p0', 'bound-p1', 'bound-m1',
              'unbound', 'rebound', 'blank')


def require(value, reason):
    if not value:
        raise ValueError('T014: ' + reason)


def _pairs(rows):
    out = {}
    for key, value in rows:
        require(key not in out, 'duplicate JSON key: ' + str(key))
        out[key] = value
    return out


def _loads(payload):
    return json.loads(payload, object_pairs_hook=_pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(
                          ValueError('nonfinite JSON')))


def _member(tar, name, limit, label):
    members = [m for m in tar.getmembers() if m.name == name]
    require(len(members) == 1 and members[0].isreg(), label + ': missing/not regular')
    member = members[0]
    require(0 < member.size <= limit, label + ': size out of budget')
    with tar.extractfile(member) as stream:
        data = stream.read(member.size + 1)
    require(len(data) == member.size, label + ': length mismatch')
    return data


def _inventory(tar):
    """Whole-archive admission before any member is read: unique names,
    regular files or directories only (links/hardlinks/fifos rejected), no
    traversal, bounded aggregate of persisted state members."""
    members = tar.getmembers()
    names = [m.name for m in members]
    require(len(set(names)) == len(names), 'duplicate archive member names')
    require(all(m.isreg() or m.isdir() for m in members),
            'archive member not a regular file/dir (link rejected)')
    require(all(m.name and not m.name.startswith('/')
                and '..' not in m.name.split('/') for m in members),
            'archive member path traversal')
    state = [m for m in members if m.isreg() and m.name.startswith(SECTION_PREFIX)]
    require(sum(m.size for m in state) <= STATE_TOTAL_MAX,
            'persisted state aggregate out of bound')
    return members


def _extract(tar, members):
    """Mode-aware exact member inventory for report/pixels/persistence."""
    report = _loads(_member(tar, REPORT_MEMBER, REPORT_MAX, 'report'))
    mode = report.get('mi3dMode')
    poses = list(REOPEN_POSES if mode == 'persistence-reopen' else SAVE_POSES)
    pixel_members = [m for m in members if m.name.startswith(PNG_PREFIX)]
    require({m.name for m in pixel_members}
            == {PNG_PREFIX + p + '.png' for p in poses},
            'pixel artifact inventory missing/extra/aliased')
    require(all(m.isreg() and 0 < m.size <= PNG_MEMBER_MAX for m in pixel_members),
            'pixel artifact member type/size out of bound')
    frames = {p: _member(tar, PNG_PREFIX + p + '.png', PNG_MEMBER_MAX, 'png ' + p)
              for p in poses}
    persistence = [m for m in members if m.name.startswith(PERSISTENCE_PREFIX)]
    cmo3 = post = None
    if mode == 'persistence-save':
        require({m.name for m in persistence} == {CMO3_MEMBER, POST_MEMBER},
                'persistence artifact inventory missing/extra')
        cmo3 = _member(tar, CMO3_MEMBER, CMO3_MAX, 'cmo3')
        post = _member(tar, POST_MEMBER, PNG_MEMBER_MAX, 'post-save png')
    else:
        require(not persistence, 'unexpected persistence artifact in this mode')
    return report, frames, cmo3, post


def load_result(host_result):
    """Extract report/pixel frames/CMO3/post-save PNG/lifecycle/fixture sha
    from a real host-result evidence dir using the actual queue schema
    (``details.postContainmentChecks.fixtureCopySha256``). This is the IO
    layer only — authoritative admission happens in verify-host
    ``job_result`` (invoked by the CLI below for BOTH jobs)."""
    host_result = Path(host_result)
    require(host_result.is_dir() and not host_result.is_symlink(),
            'host-result directory missing/linked')
    lifecycle = _loads((host_result / 'lifecycle-result.json').read_bytes())
    require(lifecycle.get('schemaVersion') == 1
            and isinstance(lifecycle.get('runId'), str) and lifecycle['runId'],
            'lifecycle schema/run id missing')
    fixture_sha = lifecycle['details']['postContainmentChecks']['fixtureCopySha256']
    require(isinstance(fixture_sha, str) and len(fixture_sha) == 64
            and all(c in '0123456789abcdef' for c in fixture_sha),
            'fixture copy sha malformed')
    archive = host_result / 'final-task/turboism-home-logs-state.tar'
    require(archive.is_file() and not archive.is_symlink(),
            'state archive missing/linked')
    with tarfile.open(archive, 'r:') as tar:
        members = _inventory(tar)
        report, frames, cmo3, post = _extract(tar, members)
    return {'report': report, 'frames': frames, 'cmo3': cmo3, 'post': post,
            'lifecycle': lifecycle, 'fixtureSha256': fixture_sha}


def verify_pair(a_report, a_cmo3, a_frames, b_report, b_frames, b_seed_sha256):
    """All arguments are already-extracted bytes/dicts; callers own IO.
    ``a_frames`` must also carry the separately named 'post-save-p0' PNG
    bytes (its archive member lives under persistence/, not pixel-sampling/)."""
    require(isinstance(a_report, dict) and isinstance(b_report, dict),
            'report pair missing')
    a_post = a_frames.get('post-save-p0')
    require(isinstance(a_post, (bytes, bytearray)),
            'Job A post-save PNG missing from extracted frames')
    PIXEL['verify_persistence_save'](a_report, a_cmo3, a_post,
                                     {k: v for k, v in a_frames.items()
                                      if k != 'post-save-p0'})
    PIXEL['verify_persistence_reopen'](b_report, b_frames)
    save = a_report['persistenceSave']
    reopen = b_report['persistenceReopen']

    # Four-way artifact identity: Job A report sha == archived bytes ==
    # Job B's require-fixture-unchanged input sha.
    require(hashlib.sha256(bytes(a_cmo3)).hexdigest() == save['artifactSha256'],
            'Job A artifact sha mismatch')
    require(b_seed_sha256 == save['artifactSha256'],
            'Job B input sha differs from the saved artifact')

    # Real reopen: different task run AND a different actual process.
    require(a_report.get('runId') != b_report.get('runId'),
            'same run id on both sides — not a fresh job')
    pa, pb = save['process'], reopen['process']
    require(pa != pb, 'identical process identity — persistence may be in-memory')
    require(pa['pid'] != pb['pid'] or pa['startEpochMillis'] != pb['startEpochMillis'],
            'process start identity reused across the pair')

    # Persistent identities + frozen content: byte-identical manifests.
    require(save['manifestAfter'] == reopen['manifest'],
            'reopened manifest differs from the saved manifest')
    require(save['manifestBefore'] == save['manifestAfter'],
            'Job A manifest changed across SAVE_AS')

    # Same-pose native pixels must be pixel-equal — the reloaded image is the
    # only way the frozen patches can be reproduced byte-identically.
    for pose in REOPEN_POSES:
        require(PIXEL['_pixels'](a_frames[pose]) == PIXEL['_pixels'](b_frames[pose]),
                pose + ': reopened pixels differ from the pre-save render')
    return True


def _run_id(evidence, given):
    """The queue-claimed run id: an explicit CLI value binds the lifecycle;
    otherwise the lifecycle's own runId is used (job_result re-binds it
    against the report anyway)."""
    if given is not None:
        return given
    life = _loads((Path(evidence) / 'lifecycle-result.json').read_bytes())
    return life['runId']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-run', type=Path, required=True,
                        help='task build run directory (host-built bundle provenance)')
    parser.add_argument('--job-a', type=Path, required=True,
                        help='Job A evidence directory (persistence-save)')
    parser.add_argument('--job-b', type=Path, required=True,
                        help='Job B evidence directory (persistence-reopen)')
    parser.add_argument('--atlas', action='store_true', help='verify packed cube atlas save/reopen pair')
    parser.add_argument('--cube', action='store_true', help='verify the real imported cube pair')
    parser.add_argument('--run-id-a')
    parser.add_argument('--run-id-b')
    args = parser.parse_args()
    try:
        build = HOST['verify_build'](args.build_run)
        require(not (args.cube and args.atlas), 'choose only one pair mode')
        if args.atlas:
            a = HOST['job_result'](args.job_a, _run_id(args.job_a,args.run_id_a), build, 'cube-atlas-save')
            b = HOST['job_result'](args.job_b, _run_id(args.job_b,args.run_id_b), build, 'cube-atlas-reopen', atlas_reference=a['report']['cubeAtlas']['initial'])
            texture=build.get('verifiedPersistenceTexturePath')
            HOST['ATLAS_PERSISTENCE']['admitted_pair'](a,b,build['verifiedCubeInputPath'],texture=texture,padded=texture is not None)
            print('ATLAS PERSISTENCE PAIR EXACT_HOST PASS: 12150 forms, 9 poses, packed UVs and exact CMO3/new process; general input/UI NOT_COVERED')
            return 0
        if args.cube:
            a = HOST['job_result'](args.job_a, _run_id(args.job_a,args.run_id_a), build, 'cube-persistence-save')
            b = HOST['job_result'](args.job_b, _run_id(args.job_b,args.run_id_b), build, 'cube-persistence-reopen')
            result = HOST['WHOLE_IMPORT']['verify_cube_persistence_pair'](a,b)
            print('CUBE PERSISTENCE PAIR PASS: 12150 forms, 9 poses, exact CMO3/new process; atlas/export/UI NOT_COVERED')
            return 0
        # Full authoritative admission for each job — identical checks to the
        # single-job host verdict, never a weaker standalone variant.
        a = HOST['job_result'](args.job_a, _run_id(args.job_a, args.run_id_a), build)
        b = HOST['job_result'](args.job_b, _run_id(args.job_b, args.run_id_b), build)
        require(a['mode'] == 'persistence-save' and b['mode'] == 'persistence-reopen',
                'job pair modes are not save+reopen')
        require(a['lifecycle']['runId'] != b['lifecycle']['runId'],
                'same lifecycle run id on both jobs')
        seed_sha = b['lifecycle']['details']['postContainmentChecks']['fixtureCopySha256']
        a_frames = dict(a['frames'])
        a_frames['post-save-p0'] = a['post']
        verify_pair(a['report'], a['cmo3'], a_frames,
                    b['report'], b['frames'], seed_sha)
        print('T014 PERSISTENCE PAIR PASS; atlas/cube/export NOT_COVERED; stage A NOT_EVALUATED')
        return 0
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
