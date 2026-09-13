#!/usr/bin/env python3
"""Historical pre-queue A/B implementation, retained for interpreting old evidence.
Execution is disabled. All new host validation must use the unified local
scripts/preview/host_validation.py queue; see README-atlas-queue-validation.md.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = Path('${GOLDEN_PREFIX}')  # Redacted historical path; execution is disabled.
RUNNER = Path('${PROTON_RUNNER}')  # Redacted historical path; execution is disabled.
BUILD = ROOT / 'build/worktree/investigate-texture-sort-native-legacy-performance'
SOURCE = ROOT / 'test-assets/texture-atlas-layout/cmo3'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def windows(path):
    return 'Z:' + str(path).replace('/', '\\')


def main():
    raise SystemExit(
        'Historical direct-host runner is disabled. Use scripts/preview/host_validation.py; '
        'see scripts/preview/README-atlas-queue-validation.md.'
    )
    # Historical code below is intentionally unreachable; do not restore this launcher.
    parser = argparse.ArgumentParser()
    parser.add_argument('--layers', type=int, choices=[100, 500, 1000, 2500], required=True)
    parser.add_argument('--dataset', choices=['circle', 'geometry'], default='circle')
    parser.add_argument('--host-timeout', type=int, choices=range(180, 3601), default=900)
    parser.add_argument('--call-timeout', type=int, choices=range(30, 601), default=120)
    parser.add_argument('--rounds', type=int, default=4)
    parser.add_argument('--implementation', choices=['both', 'native', 'new'], default='both')
    parser.add_argument('--page-size', type=int, choices=[512, 1024, 2048])
    parser.add_argument('--parallel', action='store_true', help='Enable region parallelism for new implementation')
    parser.add_argument('--fixed-scale-one', action='store_true')
    parser.add_argument('--no-rotation', action='store_true')
    args = parser.parse_args()
    assert 1 <= args.rounds <= 20
    comm = subprocess.check_output(['ps', '-eo', 'comm='], text=True)
    assert not any(s.strip().startswith(('wineserver', 'winedevice', 'java.exe')) for s in comm.splitlines()), 'Existing Wine process: refuse snapshot'
    assert not Path('/tmp/.X11-unix/X97').exists(), 'Display :97 already owned'
    task = Path(tempfile.mkdtemp(prefix=f'texture-ab-{args.dataset}-{args.layers}-', dir=ROOT / 'build'))
    evidence = task / 'evidence'
    evidence.mkdir()
    (task / 'home/plugins').mkdir(parents=True)
    (task / 'classes').mkdir()
    stem = f'atlas_mapping_{args.layers}' if args.dataset == 'circle' else f'atlas_mapping_geometry_{args.layers}'
    fixture = SOURCE / args.dataset / f'{stem}.cmo3'
    assert fixture.is_file(), f'Missing fixture: {fixture}'
    app = GOLDEN / 'pfx/drive_c/Program Files/Live2D Cubism 5.3.03'
    originals = [fixture, app / 'CubismEditor5.bat', app / 'app/lib/Live2D_Cubism.jar']
    identities = {str(f): digest(f) for f in originals}
    assert identities[str(originals[-1])] == 'bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166'
    (evidence / 'source-hashes.json').write_text(json.dumps(identities, indent=2))
    subprocess.run(['cp', '-a', '--reflink=always', str(GOLDEN), str(task / 'prefix')], check=True)
    (task / 'prefix/pfx.lock').unlink(missing_ok=True)
    shutil.copy2(fixture, task / fixture.name)
    shutil.copy2(BUILD / 'bootstrap/libs/turboism-agent.jar', task / 'turboism-agent.jar')
    for module in ('atlas-maxrects-bssf', 'mcp'):
        jars = list((BUILD / module / 'libs').glob('*.jar'))
        assert len(jars) == 1
        shutil.copy2(jars[0], task / 'home/plugins' / jars[0].name)
    sources = [ROOT / 'validation/texture-atlas-current-page' / f'{name}.java' for name in ('HostUiProbe', 'HostTimingProbe')]
    subprocess.run(['javac', '-cp', str(task / 'turboism-agent.jar'), '-d', str(task / 'classes'), *map(str, sources)], check=True)
    for name in ('HostUiProbe', 'HostTimingProbe'):
        manifest = task / f'{name}.mf'
        manifest.write_text(f'Premain-Class: dev.turboism.validation.texture.{name}\nCan-Retransform-Classes: true\n\n')
        subprocess.run(['jar', 'cfm', str(task / f'{name}.jar'), str(manifest), '-C', str(task / 'classes'), '.'], check=True)
    options = f'-Xmx4096m -Djava.locale.providers=CLDR,SPI -Dturboism.home={windows(task / "home")}'
    options += f' -javaagent:{windows(task / "turboism-agent.jar")}=home={windows(task / "home")};timeoutSeconds=180;hostClass=com.live2d.cubism.CEAppCtrl'
    for name in ('HostUiProbe', 'HostTimingProbe'):
        options += f' -javaagent:{windows(task / f"{name}.jar")}={windows(evidence)}'
    bat = '\r\n'.join(['@echo off', 'setlocal', f'set "JAVA_TOOL_OPTIONS={options}"',
        f'call "C:\\Program Files\\Live2D Cubism 5.3.03\\CubismEditor5.bat" "{windows(task / fixture.name)}" > "{windows(evidence / "console.txt")}" 2>&1',
        'exit /b %ERRORLEVEL%', ''])
    (task / 'launch.bat').write_bytes(bat.encode())
    (evidence / 'build-hashes.json').write_text(json.dumps({str(f.relative_to(task)): digest(f) for f in task.glob('*.jar')}, indent=2))
    (evidence / 'run-config.json').write_text(json.dumps(vars(args), indent=2))
    (evidence / 'plugin-hashes.json').write_text(json.dumps({f.name: digest(f) for f in (task / 'home/plugins').glob('*.jar')}, indent=2))
    Path('/tmp/texture-ab-task-path').write_text(str(task))
    print('TASK', task, flush=True)
    xvfb = None
    host = None
    env = {**os.environ, 'DISPLAY': ':97', 'PROTON_USE_XALIA': '0'}

    def request(text):
        tmp = evidence / 'ui-request.tmp'
        tmp.write_text(text)
        tmp.replace(evidence / 'ui-request.txt')
        time.sleep(.3)

    def tree():
        f = evidence / 'ui-tree.txt'
        old = f.stat().st_mtime_ns if f.exists() else 0
        request('dump')
        for _ in range(100):
            if f.exists() and f.stat().st_mtime_ns != old:
                return [line.split('\t') for line in f.read_text().splitlines()]
            if host.poll() is not None:
                raise RuntimeError('Host exited before snapshot')
            time.sleep(.1)
        raise TimeoutError('No fresh UI tree')

    def click(label, visible=True, last=False):
        deadline = time.monotonic() + 25
        while True:
            rows = [r for r in tree() if len(r) == 4 and r[3] == label and r[2].endswith('/true') and (not visible or r[2] == 'true/true')]
            if rows:
                assert last or len(rows) == 1, (label, len(rows))
                break
            if time.monotonic() >= deadline:
                raise TimeoutError(f'Control remains unavailable: {label}')
            time.sleep(.25)
        request('click\t' + rows[-1 if last else 0][0] + '\t' + label)

    try:
        xvfb = subprocess.Popen(['Xvfb', ':97', '-screen', '0', '1600x1000x24', '-nolisten', 'tcp'],
            stdout=open(evidence / 'xvfb.txt', 'wb'), stderr=subprocess.STDOUT, start_new_session=True)
        time.sleep(1)
        assert xvfb.poll() is None
        command = ['timeout', '--signal=TERM', '--kill-after=10s', f'{args.host_timeout}s', 'shorin-proton-wrapper', '-p', str(task / 'prefix'),
            '--runner', str(RUNNER / 'proton'), str(task / 'prefix/pfx/drive_c/windows/system32/cmd.exe'), '/c', windows(task / 'launch.bat')]
        host = subprocess.Popen(command, env=env, stdout=open(evidence / 'wrapper.txt', 'wb'), stderr=subprocess.STDOUT, start_new_session=True)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            time.sleep(2)
            if (evidence / 'timer-error.txt').exists():
                raise RuntimeError((evidence / 'timer-error.txt').read_text())
            if (evidence / 'timer-ready.txt').exists():
                rows = tree()
                if rows and fixture.name in rows[0][-1]:
                    break
            if host.poll() is not None:
                raise RuntimeError('Startup failed')
        else:
            raise TimeoutError('Startup readiness timeout')
        click('编辑纹理集...', visible=False)
        time.sleep(3)
        if args.page_size:
            click('更改纹理尺寸...', visible=False)
            time.sleep(.5)
            rows = tree()
            (evidence / 'resize-dialog.txt').write_text('\n'.join('\t'.join(r) for r in rows))
            target = str(args.page_size) + ' px'
            sizes = [r for r in rows if len(r) == 4 and r[2] == 'true/true'
                and '; options=[' in r[3] and target in r[3].split('; options=[', 1)[1][:-1].split(', ')]
            assert len(sizes) == 2, ('Expected width and height selectors', sizes)
            for size in sizes:
                request('select\t' + size[0] + '\t' + target)
            click('OK', last=True)
            time.sleep(1)
        baseline_hash = None
        samples = []
        for index in range(args.rounds * (2 if args.implementation == 'both' else 1)):
            # Balanced order: native/new, new/native, ...; undo before the next invocation.
            implementation = ('native', 'new', 'new', 'native')[index % 4] if args.implementation == 'both' else args.implementation
            if index:
                click('复原', visible=False)
                time.sleep(.5)
            click('自动排版...')
            time.sleep(.4)
            rows = tree()
            combos = [r for r in rows if len(r) == 4 and 'JComboBox' in r[1] and 'MaxRects-BSSF' in r[3]]
            assert len(combos) == 1, combos
            combo = combos[0]
            # Use labels actually emitted by the Swing model, not guessed indexes.
            options = combo[3].split('; options=[', 1)[1][:-1].split(', ')
            native = [v for v in options if v != 'MaxRects-BSSF']
            assert len(native) == 1, options
            selected = 'MaxRects-BSSF' if implementation == 'new' else native[0]
            request('select\t' + combo[0] + '\t' + selected)
            if args.parallel and implementation == 'new':
                rows = tree()
                checks = [r for r in rows if len(r) == 4 and 'JCheckBox' in r[1] and '启用并行搜索' in r[3]]
                assert len(checks) == 1
                request('check\t' + checks[0][0] + '\ttrue')
                selected_rows = tree()
                checks = [r for r in selected_rows if len(r) == 4 and 'JCheckBox' in r[1] and '启用并行搜索' in r[3]]
                states = dict(line.split('\t') for line in (evidence / 'ui-selected.txt').read_text().splitlines())
                assert len(checks) == 1 and states[checks[0][0]] == 'true', 'Parallel checkbox not confirmed'
                shutil.copy2(evidence / 'ui-selected.txt', evidence / f'case-{index+1:03d}-selected.txt')
            if args.fixed_scale_one:
                click('用户指定')
            if args.no_rotation:
                rows = tree()
                checks = [r for r in rows if len(r) == 4 and 'JCheckBox' in r[1] and r[3] == '' and r[2] == 'true/true']
                assert len(checks) == 1, ('Expected rotation checkbox', checks)
                request('check\t' + checks[0][0] + '\tfalse')
            (evidence / f'case-{index+1:03d}-dialog.txt').write_text('\n'.join('\t'.join(r) for r in tree()))
            click('OK', last=True)
            result_file = evidence / f'timing-{index+1:03d}.json'
            deadline = time.monotonic() + args.call_timeout
            while time.monotonic() < deadline:
                if result_file.exists():
                    try:
                        result = json.loads(result_file.read_text())
                        break
                    except json.JSONDecodeError:
                        pass
                if host.poll() is not None:
                    raise RuntimeError('Host exited during layout')
                time.sleep(.2)
            else:
                raise TimeoutError('No completed timing')
            assert result['branch'] == ('handled' if implementation == 'new' else 'native'), result['branch']
            assert result['returned']
            if baseline_hash is None:
                baseline_hash = result['inputHash']
            assert result['inputHash'] == baseline_hash, 'Input differs after Undo; refuse A/B ratio'
            output = result['output']
            if args.page_size:
                assert result['input']['width'] == args.page_size and result['input']['height'] == args.page_size
            result['parallel'] = args.parallel and implementation == 'new'
            if implementation == 'new':
                assert result['plannerParallel'] == args.parallel, 'Actual planner parallel argument differs from requested UI state'
            result['finalScale'] = output['dataScale']
            if args.fixed_scale_one:
                assert result['input']['requestedScale'] == 1.0 and output['dataScale'] == 1.0, 'Fixed scale changed'
            if args.no_rotation:
                assert result['input']['rotate'] is False
            result['overflowCount'] = len(output['overflow'])
            assert output['finite'] and output['inside'] and output['layerMatch'] and output['overlaps'] == 0, {k:v for k,v in output.items() if k!='items'}
            result['implementation'] = implementation
            result['dataset'] = args.dataset
            samples.append({k:v for k,v in result.items() if k not in ('input','output')})
            (evidence / 'samples.json').write_text(json.dumps(samples, indent=2))
            print(implementation, 'ms=', result['methodMs'], 'scale=', output['dataScale'], 'overflow=', len(output['overflow']), flush=True)
            time.sleep(.5)
        (evidence / 'status.txt').write_text('PASS repeated input identity, branches and basic geometry\n')
    finally:
        if host is not None:
            subprocess.run([str(RUNNER / 'files/bin/wineserver'), '-k'], env={**env, 'WINEPREFIX': str(task / 'prefix/pfx')}, timeout=15)
            try:
                host.wait(timeout=10)
            except subprocess.TimeoutExpired:
                host.terminate()
                host.wait(timeout=10)
        if xvfb is not None and xvfb.poll() is None:
            xvfb.terminate()
            xvfb.wait(timeout=10)
        assert all(digest(Path(f)) == value for f, value in identities.items()), 'Source identity changed'
        assert digest(task / fixture.name) == digest(fixture), 'Unrequested save changed model copy'
        (evidence / 'cleanup.txt').write_text('Owned host/display stopped; recorded source hashes and unsaved fixture copy unchanged.\n')


if __name__ == '__main__':
    main()
