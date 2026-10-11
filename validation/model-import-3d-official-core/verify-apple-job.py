#!/usr/bin/env python3
"""Bind apple Core source oracle to exact frozen inputs and contained native process."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'scripts/preview'))
import host_validation_official_core as core
import host_validation_queue as queue

GEOMETRY = runpy.run_path(str(Path(__file__).with_name('verify-apple-geometry.py')))['verify']


def require(ok, why):
    if not ok:
        raise ValueError('APPLE_CORE_JOB: '+why)


def sha(path):
    return queue.file_digest(path)


def read(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 256*1024*1024,
            'bounded regular input')
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, 'duplicate JSON field'); result[key] = value
        return result
    return json.loads(path.read_bytes(), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def process(controller, bundle, export_acceptance, recorder, capture_profile='apple-11-v1'):
    derived = capture_profile == 'apple-derived-xyz-140-v1'
    xyz = derived or capture_profile == 'apple-xyz-140-v1'
    submission = read(controller/('submit.json' if xyz else '04-submit.json'))['job']
    status = read(controller/'terminal-status.json')['jobs']
    rows = [row for row in status if row['job_id'] == submission['job_id']]
    require(len(rows) == 1 and rows[0]['state'] == 'succeeded', 'unique successful queue job')
    row = rows[0]; admission = read(controller/'admission-review.json')
    identity = dict(jobId=row['job_id'], runId=row['run_id'], attemptId=row['attempt_id'],
                    preparedDigest=row['prepared_id'])
    require(submission['prepared_id'] == admission['prepared'] == row['prepared_id']
            and admission['status'] == 'INPUT_REVIEW_PASS_NOT_EXECUTED'
            and len(admission['checks']) == (274 if xyz else 24) and all(r['passed'] is True for r in admission['checks']),
            'prior exact prepared input review')
    root = controller/'host-job'; outcome = read(root/'outcome.json')
    require(all(outcome[k] == v for k, v in identity.items()), 'outcome identity')
    require(outcome['terminalState'] == 'succeeded' and outcome['normalExit'] is True
            and outcome['cleanup'] == 'safe' and outcome['runnerExitCode'] == 0
            and outcome['identityVerified'] is True and outcome['fixtureUnchanged'] is True
            and outcome['validationStatus'] == 'PASS', 'successful normal lifecycle')
    proof = outcome['containment']; kernel = proof['kernelProof']
    require(all(proof[k] == v for k, v in identity.items()) and proof['cleanup'] == 'safe'
            and kernel['cgroupKillWritten'] is False and kernel['processTimedOut'] is False
            and kernel['processReturncode'] == 0 and kernel['originalCgroupBound'] is True
            and kernel['errors'] == [], 'independent kernel containment proof')
    for name, digest in read(controller/'collected-manifest.json').items():
        require(sha(root/name) == digest, 'frozen complete native evidence')
    for name, digest in read(controller/'source-manifest.json').items():
        require(sha(controller/'sources'/name) == digest, 'frozen verifier/launcher source')
    evidence = root/'evidence/standalone-core'
    launch = read(evidence/'launch.json')
    require(launch['identity'] == identity, 'launcher identity')
    task = Path(outcome['details']['taskDir']); staged = core.bundle(task/'bundle')
    manifest = core.bundle(bundle.absolute())
    require(manifest == staged and manifest['captureProfile'] == capture_profile
            and manifest['exportAcceptanceSha256'] == sha(export_acceptance), 'exact accepted input closure')
    accepted = read(export_acceptance)
    require(accepted['status'] == ('PASS_DERIVED_EXPORT_BYTES_BANKS_AND_ORIGINAL_FACES_ONLY' if derived else 'OFFICIAL_XYZ_MOC_CAPTURE_VERIFIED_CORE_PENDING' if xyz else 'PASS_EXACT_FROZEN_APPLE_OFFICIAL_EXPORT_CAPTURE_ONLY')
            and accepted['normalExit'] is True and (xyz or accepted['cgroupKillWritten'] is False)
            and manifest['exportJobId'] == accepted['jobId'] and manifest['exportRunId'] == accepted['runId'],
            'bound normal native export')
    if derived:
        export_status=read(export_acceptance.parent/'terminal-status.json')
        export_job=export_status.get('job') or export_status['jobs'][0]
        exported=json.loads(export_job['evidence_json'])
        kernel_export=exported['containment']['kernelProof']
        require(export_job['state']=='succeeded' and exported['jobId']==accepted['jobId']
                and exported['runId']==accepted['runId'] and exported['normalExit']
                and not kernel_export['cgroupKillWritten'] and not kernel_export['processTimedOut']
                and kernel_export['processReturncode']==0 and accepted['atlasUvPixelAccepted'],
                'derived export lifecycle and texture mapping')
    elif xyz:
        exported = read(export_acceptance.parent/'native-result/queue/outcome.json')
        kernel_export = exported['containment']['kernelProof']
        require(exported['jobId'] == accepted['jobId'] and exported['runId'] == accepted['runId']
                and exported['preparedDigest'] == accepted['prepared'] and exported['terminalState'] == 'succeeded'
                and exported['normalExit'] is True and kernel_export['cgroupKillWritten'] is False
                and kernel_export['processTimedOut'] is False and kernel_export['processReturncode'] == 0
                and accepted['sourceUnchanged'] is True and accepted['historyUnchanged'] is True
                and accepted['sourceRestored'] is True, 'XYZ original export containment and source protections')
    build = read(recorder/'build.json')
    require(build['coreSha256'] == manifest['coreSha256'] == core.CORE_SHA
            and build['recorderSha256'] == manifest['recorderSha256']
            and sha(recorder/'official-core-capture.jar') == build['recorderSha256'], 'frozen official recorder')
    capture = read(evidence/'capture/core-capture.json')
    post = outcome['details']['postContainmentChecks']
    require(post['terminalResult'] == dict(passed=True, scope='STANDALONE_CORE_CAPTURE_ONLY', error=None)
            and post['captureSha256'] == sha(evidence/'capture/core-capture.json')
            and post['bundleManifestSha256'] == sha(task/'bundle/bundle.json'), 'post-containment capture identity')
    require(capture['axes'] == manifest['axes'] and capture['coreOrigin']['sha256'] == core.CORE_SHA
            and capture['recorderOrigin']['sha256'] == manifest['recorderSha256']
            and capture['mocSha256'] == manifest['mocSha256'], 'loaded Core and exact MOC')
    require(capture['coreOrigin']['path'] == core.windows(task/'bundle/Live2DCubismCore.jar')
            and capture['recorderOrigin']['path'] == core.windows(task/'bundle/official-core-capture.jar')
            and capture['classpath'] == core.windows(task/'bundle/official-core-capture.jar')+';'+core.windows(task/'bundle/Live2DCubismCore.jar')
            and capture['jvmArguments'] == ['-Djava.library.path='+core.windows(task/'bundle/native')],
            'two official standalone JARs and no agent')
    require(type(capture['pid']) is int and capture['pid'] > 0 and capture['processStart'], 'recorded process')
    require((evidence/'core-launch.cmd').read_bytes() == core.launch_script(task), 'actual frozen Java script')
    drain = read(evidence/'process-drain.json')
    require(all(drain[k] == v for k, v in identity.items()) and drain['cgroupPath'] == proof['cgroupPath']
            and drain['drained'] is True and drain['signalsSent'] is False and len(drain['samples']) >= 2
            and all(r['processes'] == [] for r in drain['samples'][-2:]), 'natural helper exit')
    return identity, capture, accepted


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('controller', 'bundle', 'export-acceptance', 'recorder', 'prepared', 'out'):
        parser.add_argument('--'+name, required=True, type=Path)
    a = parser.parse_args()
    identity, capture, accepted = process(a.controller, a.bundle, a.export_acceptance, a.recorder)
    artifacts = a.export_acceptance.parent/'artifacts'
    geometry = GEOMETRY(capture, read(artifacts/'report.json'), a.prepared, artifacts)
    result = dict(status='PASS', scope='EXACT_APPLE_STANDALONE_OFFICIAL_CORE_GEOMETRY', **identity,
                  normalExit=True, cgroupKillWritten=False, geometry=geometry,
                  exportAcceptanceSha256=sha(a.export_acceptance),
                  captureSha256=sha(a.controller/'host-job/evidence/standalone-core/capture/core-capture.json'),
                  officialCoreAccepted=True, officialRendererAccepted=False, goalComplete=False)
    with a.out.open('x') as stream:
        json.dump(result, stream, indent=2); stream.write('\n')
    print(json.dumps(result, indent=2))
