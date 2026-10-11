#!/usr/bin/env python3
"""Bind the three apple Viewer UI observations to their native isolated process."""
import argparse
import json
from pathlib import Path
import sys
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'scripts/preview'))
import host_validation_official_viewer as viewer
from host_validation_queue import file_digest


def require(ok, why):
    if not ok:
        raise ValueError('APPLE_VIEWER_JOB: '+why)


def read(p):
    require(p.is_file() and not p.is_symlink() and p.stat().st_size <= 4*1024*1024,
            'bounded regular JSON')
    return json.loads(p.read_text())


def verify(controller, bundle, core_acceptance, capture_profile='apple-3-v1'):
    derived=capture_profile=='apple-derived-xyz-3-v1'
    xyz=derived or capture_profile=='apple-xyz-3-v1'
    require(capture_profile in ('apple-3-v1','apple-xyz-3-v1','apple-derived-xyz-3-v1'), 'explicit Apple Viewer profile')
    submitted = read(controller/('submit.json' if xyz else '04-submit.json'))['job']; terminal = read(controller/'terminal-status.json')['jobs']
    rows = [r for r in terminal if r['job_id'] == submitted['job_id']]
    require(len(rows) == 1 and rows[0]['state'] == 'succeeded', 'successful unique native job')
    row = rows[0]; identity = dict(jobId=row['job_id'], runId=row['run_id'], attemptId=row['attempt_id'], preparedDigest=row['prepared_id'])
    admission = read(controller/'admission-review.json')
    require(admission['status'] == 'INPUT_REVIEW_PASS_NOT_EXECUTED'
            and admission['prepared'] == submitted['prepared_id'] == row['prepared_id']
            and len(admission['checks']) == 38 and all(r['passed'] is True for r in admission['checks']), 'prior exact input review')
    root = controller/'host-job'; o = read(root/'outcome.json'); proof = o['containment']; k = proof['kernelProof']
    require(all(o[key] == proof[key] == value for key, value in identity.items()), 'bound outcome and containment')
    require(o['terminalState'] == 'succeeded' and o['normalExit'] is True and o['runnerExitCode'] == 0
            and o['identityVerified'] is True and o['fixtureUnchanged'] is True and o['validationStatus'] == 'PASS'
            and o['cleanup'] == proof['cleanup'] == 'safe' and k['cgroupKillWritten'] is False
            and k['processTimedOut'] is False and k['originalCgroupBound'] is True
            and k['processReturncode'] == 0 and k['errors'] == [], 'normal unforced native lifecycle')
    for name, digest in read(controller/'collected-manifest.json').items():
        require(file_digest(root/name) == digest, 'frozen native evidence')
    for name, digest in read(controller/'source-manifest.json').items():
        require(file_digest(controller/'sources'/name) == digest, 'frozen observer/launcher source')
    m = viewer.bundle(bundle.absolute()); task = Path(o['details']['taskDir'])
    require(m == viewer.bundle(task/'bundle') and m['captureProfile'] == capture_profile
            and m['coreAcceptanceSha256'] == file_digest(core_acceptance), 'same accepted apple MOC/runtime')
    accepted = read(core_acceptance)
    require(accepted['status'] == ('PASS_NATIVE_DERIVED_APPLE_FINITE_XYZ_GEOMETRY' if derived else 'PASS_NATIVE_APPLE_FINITE_XYZ_GEOMETRY' if xyz else 'PASS') and accepted['officialCoreAccepted'] is True
            and accepted['normalExit'] is True and (xyz or accepted['cgroupKillWritten'] is False), 'native Core acceptance')
    e = root/'evidence/standalone-viewer'; capture = e/'capture'; launch = read(e/'launch.json')
    require(launch['identity'] == identity and launch['display'].startswith(':')
            and launch['xvfbPid'] > 0 and launch['xvfbSha256'] == file_digest(Path('/usr/bin/Xvfb')), 'private display identity')
    require((e/'viewer-launch.cmd').read_bytes() == viewer.launch_script(task, m), 'frozen Viewer launch')
    actual = read(capture/'launch.json')
    require(actual['officialJarSha256'] == viewer.EDITOR_SHA
            and actual['officialJar'] == viewer.windows(task/'bundle/app/lib/Live2D_Cubism.jar')
            and actual['model3Sha256'] == m['files']['model/apple.model3.json']['sha256']
            and actual['classpath'] == ';'.join(n.replace('/', '\\') for n in m['classpath'])
            and type(actual['pid']) is int and actual['pid'] > 0 and actual['processStart'], 'actual official Viewer input/process')
    expected_vm = ['-Djava.library.path='+';'.join(n.replace('/', '\\') for n in m['nativePaths']),
                   '-Djogamp.gluegen.UseTempJarCache=false', '-Dsun.java2d.d3d=false', '-Duser.language=zh',
                   '-Djava.locale.providers=COMPAT,SPI', '-Duser.home='+viewer.windows(task/'home')]
    require(actual['jvmArguments'] == expected_vm, 'no agents or alternate loader')
    observation = read(capture/'observation.json')
    if xyz:require(observation['axes']==m['axes'] and len(m['axes'])==3, 'actual requested full XYZ controls')
    require(observation['status'] == 'CAPTURED_NOT_RENDER_VERIFIED'
            and observation['poseControl'] == 'UI_MOUSE_REQUESTED_NOT_VALUE_VERIFIED'
            and len(observation['frames']) == 3, 'bounded honest observation scope')
    digests = []
    for i, frame in enumerate(observation['frames']):
        name = f'viewer-{i}.png'; p = capture/name
        require(frame['path'] == name and file_digest(p) == frame['sha256'] and p.stat().st_size <= 16*1024*1024, 'actual screenshot bytes')
        with Image.open(p) as image:
            require(list(image.size) == [frame['width'], frame['height']], 'actual screenshot size'); image.load()
        if i:
            text = (capture/f'components-{i}.txt').read_text()
            axes=m['axes'] if xyz else ['Param3DYaw394e4cf740f3','Param3DPitch394e4cf740f3']
            require(all(a in text for a in axes), 'actual apple UI parameter controls')
        digests.append(frame['sha256'])
    require(len(set(digests)) == 3, 'distinct captured Viewer observations')
    drain = read(e/'process-drain.json')
    require(all(drain[key] == value for key, value in identity.items()) and drain['cgroupPath'] == proof['cgroupPath']
            and drain['drained'] is True and drain['signalsSent'] is False and len(drain['samples']) >= 2
            and all(r['processes'] == [] for r in drain['samples'][-2:]), 'natural helper exit')
    require(o['details']['postContainmentChecks']['terminalResult'] == dict(passed=True, scope='OFFICIAL_VIEWER_OBSERVATION_ONLY', error=None), 'post-containment recheck')
    return dict(status='PASS_NATIVE_APPLE_XYZ_VIEWER_OBSERVATION_CAPTURE' if xyz else 'PASS_NATIVE_APPLE_VIEWER_OBSERVATION_CAPTURE', **identity, normalExit=True,
                cgroupKillWritten=False, officialRendererExecuted=True, frames=observation['frames'],
                preciseParameterValuesVerified=False, numericPixelCorrectnessAccepted=False,
                manualVisualReview='SEPARATE_REVIEW_REQUIRED', coreAcceptanceSha256=file_digest(core_acceptance))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('controller', 'bundle', 'core-acceptance', 'out'):
        p.add_argument('--'+name, required=True, type=Path)
    p.add_argument('--profile',choices=('apple-3-v1','apple-xyz-3-v1','apple-derived-xyz-3-v1'),default='apple-3-v1')
    a = p.parse_args(); result = verify(a.controller, a.bundle, a.core_acceptance,a.profile)
    with a.out.open('x') as f:
        json.dump(result, f, indent=2); f.write('\n')
    print(result['status'])
