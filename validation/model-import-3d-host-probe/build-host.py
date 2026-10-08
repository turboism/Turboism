#!/usr/bin/env python3
"""Build one exclusive N2/N3a/N3b/N3c SDK-only probe/bundle; never launch or enqueue a host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
PROBE = Path('validation/model-import-3d-host-probe')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def no_links(path):
    if '..' in path.parts:
        raise ValueError('traversal is not allowed')
    path = Path(os.path.abspath(path))
    for component in (path, *path.parents):
        if component.is_symlink():
            raise ValueError('symlink input/output is not allowed')
    return path


def write_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write('\n')


def build(args):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}', args.run):
        raise ValueError('invalid unique run name')
    run = no_links(ROOT / 'build/model-import-3d-host' / args.run)
    if run.exists():
        raise ValueError('run already exists; no evidence may be replaced')
    sdk, agent = no_links(Path(args.sdk)), no_links(Path(args.agent))
    for file in (sdk, agent):
        if not file.is_file() or not file.is_relative_to(ROOT / 'build'):
            raise ValueError('SDK/agent must be explicit existing worktree build files')
    sources = sorted(p for base in ('native-src', 'native-test', 'packaging-test', 'input-src', 'input-test')
                     for p in (ROOT / PROBE / base).rglob('*') if p.is_file())
    sources += [ROOT / PROBE / name for name in ('build-host.py', 'verify-host.py', 'verify-evaluation.py', 'verify-order.py',
                'verify-morph.py', 'verify-morph-order.py', 'verify-image-binding.py',
                'verify-pixel-sampling.py', 'verify-persistence-pair.py',
                'verify-atlas-persistence.py', 'test-atlas-persistence.py',
                'verify-shape-import.py', 'verify-joint-pose.py', 'verify-warp-authoring.py', 'verify-source-warp-candidate.py', 'verify-cube-geometry.py', 'verify-cube-render.py', 'verify-whole-import.py', 'README.md')]
    sources += [ROOT / ('plugins/model-import-3d/src/test/java/dev/turboism/validation/modelimport3d/host/'+name+'.java') for name in ('EmbeddedTextureTest','PlanarImportTest')]
    if getattr(args,'persistence_texture',None):
        sources += [ROOT / 'validation/model-import-3d-user-entry' / name for name in ('verify-report.py','verify-texture-pixels.py')]
    if getattr(args,'sheet_input',None):
        sources += [ROOT / PROBE / 'verify-surface-geometry.py']
    if getattr(args,'sheet_render',False):
        sources += [ROOT / PROBE / n for n in ('verify-sheet-order.py','verify-sheet-pixels.py')]
    if getattr(args,'pose_drive_geometry',False) or getattr(args,'surface_render',False):
        sources += [ROOT / PROBE / 'verify-pose-drive.py']
    if getattr(args,'surface_render',False):
        sources += [ROOT / PROBE / 'verify-planar-pixels.py']
    sources += [ROOT / name for name in ('scripts/preview/run-model-import-3d-host-validation.sh',
                'scripts/preview/host-validation-tasks.json', 'scripts/test/test_model_import_3d_host_validation.py',
                'scripts/test/test_model_import_3d_evaluation.py', 'scripts/test/test_model_import_3d_order.py',
                'scripts/test/test_model_import_3d_morph.py', 'scripts/test/test_model_import_3d_morph_order.py',
                'scripts/test/test_model_import_3d_image_binding.py',
                'scripts/test/test_model_import_3d_pixel_sampling.py',
                'scripts/test/test_model_import_3d_persistence.py',
                'scripts/test/test_model_import_3d_shape_import.py',
                'scripts/test/test_model_import_3d_joint_pose.py', 'scripts/test/test_model_import_3d_cube_geometry.py', 'scripts/test/test_model_import_3d_cube_render.py', 'scripts/test/test_model_import_3d_whole_import.py')]
    canonical = no_links(Path(args.canonical_warp)) if getattr(args,'canonical_warp',None) else None
    layered=bool(getattr(args,'canonical_layered',False))
    if layered and canonical is None:raise ValueError('layered requires canonical GLB')
    canonical_names=('SurfaceWarpSpatialFit','SurfaceQuadChartDiscovery','SurfaceWarpChartPlan','SurfaceWarpImagePlan','SurfaceWarpAuthoring','SurfaceWarpImportCandidate')
    if layered:canonical_names+=('SurfaceWarpLayerPlan','SurfaceWarpLayerAuthoring')
    if canonical is not None:
        if not canonical.is_file() or not 0<canonical.stat().st_size<=16*1024*1024 or digest(canonical)!='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999':
            raise ValueError('canonical Warp requires exact authorized source texture GLB')
        if any(getattr(args,k,None) for k in ('source_warp_fixture','source_warp_texture','warp_authoring','cube_input','persistence_texture','sheet_input','planar_inputs','surface_render','sheet_render','pose_drive_geometry')):
            raise ValueError('canonical Warp cannot mix unrelated profiles')
        sources += [ROOT / ('plugins/model-import-3d/src/main/java/dev/turboism/validation/modelimport3d/host/'+n+'.java') for n in canonical_names]
        sources += [ROOT / ('validation/model-import-3d-canonical-warp/src/dev/turboism/validation/modelimport3d/host/'+n+'.java') for n in (('CanonicalWarpProbe','CanonicalWarpPreparationTest','CanonicalWarpLayerProbe','CanonicalWarpLayerPreparationTest') if layered else ('CanonicalWarpProbe','CanonicalWarpPreparationTest'))]
        sources += [ROOT / 'validation/model-import-3d-canonical-warp' / n for n in (('verify-canonical-warp.py','test-checker.py','verify-canonical-warp-layered.py','test-layered-checker.py') if layered else ('verify-canonical-warp.py','test-checker.py'))]
    for source in sources:
        no_links(source)
        if not source.is_file():
            raise ValueError('missing declared dependency: ' + str(source))
    source_warp = no_links(Path(args.source_warp_fixture)) if getattr(args,'source_warp_fixture',None) else None
    if source_warp is not None:
        if source_warp.stat().st_size != 73575376 or digest(source_warp) != '9320e7ae7a44f905cfab5827fe84f334b370e7e0f3ea9057fe05c01e4bd69360':
            raise ValueError('source Warp requires the exact authorized frozen candidate')
        if getattr(args,'warp_authoring',False) or any(getattr(args,k,None) for k in ('cube_input','persistence_texture','sheet_input','planar_inputs','surface_render','sheet_render','pose_drive_geometry')):
            raise ValueError('source Warp candidate cannot be combined with unrelated input profiles')
    source_texture = no_links(Path(args.source_warp_texture)) if getattr(args,'source_warp_texture',None) else None
    if source_texture is not None:
        if source_warp is None or not source_texture.is_file() or not 0 < source_texture.stat().st_size <= 16*1024*1024 or digest(source_texture) != '3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999':
            raise ValueError('textured source Warp requires exact original geometry and frozen source texture GLB')
    cube = no_links(Path(args.cube_input)) if getattr(args, 'cube_input', None) else None
    cube_sha = None
    if cube is not None:
        if not cube.is_file() or cube.stat().st_size != 1936:
            raise ValueError('cube mode requires the exact authorized bounded GLB')
        cube_sha = digest(cube)
        if cube_sha != '985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630':
            raise ValueError('cube input differs from the authorized fixture')
    texture = no_links(Path(args.persistence_texture)) if getattr(args, 'persistence_texture', None) else None
    texture_sha = None
    if texture is not None:
        if cube is None or not texture.is_file() or not 0 < texture.stat().st_size <= 16*1024*1024:
            raise ValueError('persistence texture requires original cube and bounded source GLB')
        texture_sha = digest(texture)
        if texture_sha != 'dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859':
            raise ValueError('persistence texture differs from authorized source GLB')
    sheets = no_links(Path(args.sheet_input)) if getattr(args,'sheet_input',None) else None
    sheet_sha = None
    if sheets is not None:
        if texture is None or not sheets.is_file() or not 0 < sheets.stat().st_size <= 1024*1024:
            raise ValueError('sheet stage requires source persistence profile and bounded GLB')
        sheet_sha = digest(sheets)
        if sheet_sha != '0c0f716da082c9987ba580565a97ec81237c4e0333ae4023042b9c17b41a9984':
            raise ValueError('sheet input differs from frozen fixture')
    sheet_render = getattr(args,'sheet_render',False)
    if sheet_render and sheets is None:
        raise ValueError('sheet render requires exact sheet input')
    planar_dir = no_links(Path(args.planar_inputs)) if getattr(args,'planar_inputs',None) else None
    planar = {}
    if planar_dir is not None:
        if not sheet_render:
            raise ValueError('planar geometry stage requires the complete sheet-render baseline')
        for name, expected in (('plane.glb','8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997'),
                               ('plane-seam.glb','89d1617c9555250d504136c081abec11b2f3e00fde45237d633bdb2096585f34')):
            file = no_links(planar_dir / name)
            if not file.is_file() or not 0 < file.stat().st_size <= 1024*1024 or digest(file) != expected:
                raise ValueError('planar fixture identity/budget: ' + name)
            planar[name] = expected
    surface_render = getattr(args,'surface_render',False)
    pose_drive_geometry = getattr(args,'pose_drive_geometry',False) or surface_render
    if pose_drive_geometry and not planar:
        raise ValueError('pose drive geometry requires frozen planar and sheet baselines')
    manifest = {str(p.relative_to(ROOT)): digest(p) for p in sources}
    plan = {'run': str(run), 'sdk': str(sdk), 'agent': str(agent), 'sources': manifest,
            'hostExecuted': False, 'dryRun': args.dry_run, 'cubeInputSha256': cube_sha,
            'persistenceTextureSha256': texture_sha, 'sheetInputSha256': sheet_sha, 'planarInputs': planar}
    if args.dry_run:
        print(json.dumps(plan, indent=2)); return
    run.parent.mkdir(parents=True, exist_ok=True)
    run.mkdir()  # Atomic ownership. Never rm/reuse an existing run, even after failure.
    commands = []
    terminal = {'status': 'FAIL', 'hostExecuted': False}

    def command(purpose, argv, env=None):
        log = 'command-%02d.log' % len(commands)
        record = {'purpose': purpose, 'argv': list(map(str, argv)), 'start': time.time(), 'log': log}
        if env is not None:
            keys = ('MI3D_WHOLE_TEST_CLASSPATH', 'MI3D_WHOLE_CUBE_INPUT') if purpose == 'whole-import-oracle-tests' else ('MI3D_RENDER_TEST_CLASSPATH', 'MI3D_RENDER_CUBE_INPUT')
            record['testEnvironment'] = {k: env[k] for k in keys}
        with (run / log).open('xb') as output:
            result = subprocess.run(record['argv'], cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, env=env)
        record.update(exitCode=result.returncode, end=time.time(), logSha256=digest(run / log))
        commands.append(record)
        if result.returncode:
            raise RuntimeError('build command failed: ' + str(record))
        return (run / log).read_text(encoding='utf-8')

    try:
        evidence = run / 'provenance'; evidence.mkdir()
        tools = {name: str(Path(shutil.which(name)).resolve()) for name in ('java', 'javac', 'jar')}
        versions = {name: command(name + '-version', [tools[name], '-version']) for name in ('java', 'javac')}
        for source in sources:
            dest = evidence / 'source' / source.relative_to(ROOT)
            dest.parent.mkdir(parents=True, exist_ok=True)
            with dest.open('xb') as out:
                out.write(source.read_bytes())
            if digest(dest) != manifest[str(source.relative_to(ROOT))]:
                raise ValueError('source changed during snapshot')
            dest.chmod(0o444)
        sdk_copy = evidence / 'sdk.jar'; shutil.copyfile(sdk, sdk_copy)
        write_json(evidence / 'source-manifest.json', manifest)
        bundle = run / 'bundle'; (bundle / 'plugins').mkdir(parents=True)
        shutil.copyfile(agent, bundle / 'turboism-agent.jar')
        inputs = {'sdkSha256': digest(sdk_copy), 'agentSha256': digest(bundle / 'turboism-agent.jar'),
                  'sourceManifestSha256': digest(evidence / 'source-manifest.json'),
                  'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  'gitStatus': subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True),
                  'run': args.run, 'sdkOriginal': str(sdk), 'agentOriginal': str(agent),
                  'toolchain': {'executables': tools, 'versions': versions}}
        if digest(sdk) != inputs['sdkSha256'] or digest(agent) != inputs['agentSha256']:
            raise ValueError('build artifact changed during snapshot')
        if getattr(args, 'warp_authoring', False):
            inputs['sourceInventoryVersion'] = 'mi3d-warp-authoring-sources-v1'
        if source_warp is not None:
            inputs['sourceInventoryVersion'] = 'mi3d-source-warp-candidate-sources-v2'
            inputs['sourceWarpFixtureSha256'] = digest(source_warp)
            shutil.copyfile(source_warp,evidence / 'apple-warp.bin')
            if digest(evidence / 'apple-warp.bin') != inputs['sourceWarpFixtureSha256']:
                raise ValueError('source Warp fixture changed during snapshot')
        if source_texture is not None:
            inputs['sourceWarpTextureSha256']=digest(source_texture)
            shutil.copyfile(source_texture,evidence / 'apple-textured.glb')
            if digest(evidence / 'apple-textured.glb') != inputs['sourceWarpTextureSha256']:
                raise ValueError('source texture changed during snapshot')
        if canonical is not None:
            inputs['sourceInventoryVersion']='mi3d-canonical-layered-sources-v1' if layered else 'mi3d-canonical-warp-sources-v3'
            inputs['sourceWarpTextureSha256']=digest(canonical)
            shutil.copyfile(canonical,evidence / 'apple-textured.glb')
            if digest(evidence / 'apple-textured.glb')!=inputs['sourceWarpTextureSha256']:raise ValueError('canonical source drift')
        inputs['inputStageVersion'] = 'mi3d-surface-render-v2' if surface_render else 'mi3d-pose-drive-geometry-v2' if pose_drive_geometry else 'mi3d-planar-geometry-v1' if planar else 'mi3d-sheet-render-v1' if sheet_render else 'mi3d-sheet-geometry-v1' if sheets is not None else 'mi3d-source-texture-persistence-v1' if texture is not None else 'mi3d-planar-quad-v1'
        if cube is not None:
            shutil.copyfile(cube, evidence / 'cube.glb')
            if digest(evidence / 'cube.glb') != cube_sha:
                raise ValueError('cube input changed during snapshot')
            inputs['cubeInputSha256'] = cube_sha
            inputs['cubeOriginal'] = str(cube)
        if texture is not None:
            shutil.copyfile(texture,evidence / 'cube-textured.glb')
            if digest(evidence / 'cube-textured.glb') != texture_sha:
                raise ValueError('persistence texture changed during snapshot')
            inputs['persistenceTextureSha256'] = texture_sha
        if sheets is not None:
            shutil.copyfile(sheets,evidence / 'sheets.glb')
            if digest(evidence / 'sheets.glb') != sheet_sha:
                raise ValueError('sheet input changed during snapshot')
            inputs['sheetInputSha256'] = sheet_sha
        if planar:
            inputs['planarInputs'] = planar
            for name, expected in planar.items():
                shutil.copyfile(planar_dir / name, evidence / name)
                if digest(evidence / name) != expected:
                    raise ValueError('planar fixture changed during snapshot')
        native = evidence / 'source' / PROBE / 'native-src' 
        input_sources = evidence / 'source' / PROBE / 'input-src'
        input_tests = evidence / 'source' / PROBE / 'input-test'
        tests = evidence / 'source' / PROBE / 'native-test'
        classes = run / 'classes'; classes.mkdir()
        canonical_sources = [evidence / 'source' / p.relative_to(ROOT) for p in sources if p.suffix=='.java' and ('model-import-3d-canonical-warp' in p.parts or p.stem in canonical_names)] if canonical is not None else []
        command('compile-probe', [tools['javac'], '--release', '17', '-encoding', 'UTF-8', '-cp', sdk_copy,
                 '-d', classes, *sorted(native.rglob('*.java')), *sorted(input_sources.rglob('*.java')), *canonical_sources])
        test_classes = run / 'test-classes'; test_classes.mkdir()
        command('compile-tests', [tools['javac'], '--release', '17', '-encoding', 'UTF-8', '-cp', str(sdk_copy) + os.pathsep + str(classes),
                 '-d', test_classes, *sorted(tests.rglob('*.java')), *sorted(input_tests.rglob('*.java'))])
        command('synthetic-tests', [tools['java'], '-Djava.awt.headless=true', '-cp', os.pathsep.join(map(str, (sdk_copy, classes, test_classes))),
                 'dev.turboism.validation.modelimport3d.host.HostAssertionsTest'])
        if cube is not None:
            command('cube-producer-tests', [tools['java'], '-Xmx768m', '-Djava.awt.headless=true', '-cp',
                    os.pathsep.join(map(str, (sdk_copy, classes, test_classes))),
                    'dev.turboism.validation.modelimport3d.host.CubeGeometryProducerIntegrationTest',
                    evidence / 'cube.glb', run / 'cube-producer'])
            command('material-consumer-tests', [tools['java'], '-Xmx768m', '-Djava.awt.headless=true', '-cp',
                    os.pathsep.join(map(str, (sdk_copy, classes, test_classes))),
                    'dev.turboism.validation.modelimport3d.host.SurfaceImportAppearanceTest', evidence / 'cube.glb'])
            command('whole-import-consumer-tests', [tools['java'], '-Xmx768m', '-Djava.awt.headless=true', '-cp',
                    os.pathsep.join(map(str, (sdk_copy, classes, test_classes))),
                    'dev.turboism.validation.modelimport3d.host.SurfaceImportTest', evidence / 'cube.glb'])
            render_env = os.environ.copy()
            render_env['MI3D_RENDER_TEST_CLASSPATH'] = os.pathsep.join(map(str, (sdk_copy, classes, test_classes)))
            render_env['MI3D_WHOLE_TEST_CLASSPATH'] = os.pathsep.join(map(str, (sdk_copy, classes, test_classes)))
            render_env['MI3D_WHOLE_CUBE_INPUT'] = str(evidence / 'cube.glb')
            command('whole-import-oracle-tests', [sys.executable, '-B',
                    evidence / 'source/scripts/test/test_model_import_3d_whole_import.py'], env=render_env)
            # Test input is an explicit frozen path; source tests never search Downloads.
            render_env['MI3D_RENDER_CUBE_INPUT'] = str(evidence / 'cube.glb')
            command('render-oracle-tests', [sys.executable, '-B',
                    evidence / 'source/scripts/test/test_model_import_3d_cube_render.py'], env=render_env)
            fixture_dir = classes / 'dev/turboism/validation/modelimport3d/host/fixtures'
            fixture_dir.mkdir(parents=True)
            shutil.copyfile(evidence / 'cube.glb', fixture_dir / 'cube.glb')
            if texture is not None:
                shutil.copyfile(evidence / 'cube-textured.glb',fixture_dir / 'cube-textured.glb')
        if sheets is not None:
            shutil.copyfile(evidence / 'sheets.glb',fixture_dir / 'sheets.glb')
        for name in planar:
            shutil.copyfile(evidence / name, fixture_dir / name)
        shutil.copytree(native / 'META-INF', classes / 'META-INF')
        if cube is not None:
            descriptor = classes / 'META-INF/turboism/plugin.json'
            metadata = json.loads(descriptor.read_text(encoding='utf-8'))
            if metadata['resources'] != []:
                raise ValueError('unexpected base probe resources')
            metadata['resources'] = ['dev/turboism/validation/modelimport3d/host/fixtures/']
            descriptor.chmod(0o644)  # Only the generated package copy; frozen sources stay read-only.
            descriptor.write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
            inputs['packagedDescriptorSha256'] = digest(descriptor)
        if source_warp is not None:
            fixture_dir=classes / 'dev/turboism/validation/modelimport3d/host/fixtures'
            fixture_dir.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(evidence / 'apple-warp.bin',fixture_dir / 'apple-warp.bin')
            if source_texture is not None:shutil.copyfile(evidence / 'apple-textured.glb',fixture_dir / 'apple-textured.glb')
            descriptor=classes / 'META-INF/turboism/plugin.json'
            metadata=json.loads(descriptor.read_text(encoding='utf-8'))
            if metadata['resources'] != []:raise ValueError('unexpected source Warp base resources')
            metadata['resources']=['dev/turboism/validation/modelimport3d/host/fixtures/']
            descriptor.chmod(0o644)
            descriptor.write_text(json.dumps(metadata,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
            inputs['packagedDescriptorSha256']=digest(descriptor)
        if canonical is not None:
            fixture_dir=classes / 'dev/turboism/validation/modelimport3d/host/fixtures';fixture_dir.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(evidence / 'apple-textured.glb',fixture_dir / 'apple-textured.glb')
            descriptor=classes / 'META-INF/turboism/plugin.json';metadata=json.loads(descriptor.read_text(encoding='utf-8'))
            if metadata['resources']!=[]:raise ValueError('unexpected canonical resources')
            metadata['resources']=['dev/turboism/validation/modelimport3d/host/fixtures/'];descriptor.chmod(0o644);descriptor.write_text(json.dumps(metadata,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
            inputs['packagedDescriptorSha256']=digest(descriptor)
        jar = bundle / 'plugins/model-import-3d-host-probe.jar' 
        command('package-probe', [tools['jar'], '--create', '--file', jar, '-C', classes, '.'])
        packaging = evidence / 'source' / PROBE / 'packaging-test'
        packaging_classes = evidence / 'packaging-classes'; packaging_classes.mkdir()
        command('compile-packaging-check', [tools['javac'], '--release', '17', '-encoding', 'UTF-8',
                 '-cp', bundle / 'turboism-agent.jar', '-d', packaging_classes, *sorted(packaging.rglob('*.java'))])
        command('packaging-contract', [tools['java'], '-Djava.awt.headless=true', '-cp',
                 os.pathsep.join(map(str, (bundle / 'turboism-agent.jar', packaging_classes))),
                 'dev.turboism.validation.modelimport3d.packaging.PluginJarCheck', jar])
        command('cube-save-file-tests', [tools['java'], '-Djava.awt.headless=true', '-cp',
                 os.pathsep.join(map(str, (sdk_copy, classes, test_classes))),
                 'dev.turboism.validation.modelimport3d.host.CubePersistenceFileTest', run / 'cube-save-file-tests'])
        if texture is not None:
            command('source-persistence-profile-tests',[tools['java'],'-Xmx768m','-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                'dev.turboism.validation.modelimport3d.host.CubePersistenceInputTest',evidence / 'cube.glb',evidence / 'cube-textured.glb'])
        if sheets is not None:
            command('sheet-input-tests',[tools['java'],'-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                'dev.turboism.validation.modelimport3d.host.SheetGeometryInputTest',evidence / 'sheets.glb'])
        if sheet_render:
            command('sheet-pixel-encoding-tests',[tools['java'],'-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                'dev.turboism.validation.modelimport3d.host.SheetPixelEncodingTest'])
        if planar:
            command('planar-input-tests',[tools['java'],'-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                'dev.turboism.validation.modelimport3d.host.PlanarGeometryInputTest',evidence])
        if pose_drive_geometry:
            command('pose-drive-history-tests',[tools['java'],'-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                'dev.turboism.validation.modelimport3d.host.PoseDriveHistoryTest'])
        if surface_render:
            command('planar-pixel-encoding-tests',[tools['java'],'-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                'dev.turboism.validation.modelimport3d.host.PlanarPixelEncodingTest'])
        if source_warp is not None:
            command('source-warp-fixture-tests',[tools['java'],'-Xmx512m','-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                'dev.turboism.validation.modelimport3d.host.SourceWarpFixtureTest',evidence / 'apple-warp.bin'])
            command('source-warp-provenance-tests',[tools['java'],'-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                'dev.turboism.validation.modelimport3d.host.SourceWarpAuthoringTest'])
            if source_texture is not None:
                command('source-warp-appearance-binding-tests',[tools['java'],'-Xmx512m','-Djava.awt.headless=true','-cp',
                    os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                    'dev.turboism.validation.modelimport3d.host.SourceWarpAppearanceBindingTest',evidence / 'apple-textured.glb',evidence / 'apple-warp.bin'])
                command('source-warp-appearance-tests',[tools['java'],'-Xmx512m','-Djava.awt.headless=true','-cp',
                    os.pathsep.join(map(str,(sdk_copy,classes,test_classes))),
                    'dev.turboism.validation.modelimport3d.host.SourceWarpAppearanceTest',evidence / 'apple-textured.glb',evidence / 'apple-warp.bin',run / 'appearance-check'])
                if digest(source_texture)!=inputs['sourceWarpTextureSha256']:raise ValueError('source texture changed during build')
            if digest(source_warp)!=inputs['sourceWarpFixtureSha256']:raise ValueError('source Warp input changed during build')
        if canonical is not None:
            prepared=run / 'canonical-prepare';prepared.mkdir()
            command('canonical-warp-prepare-tests',[tools['java'],'-Xmx1g','-Djava.awt.headless=true','-cp',os.pathsep.join(map(str,(sdk_copy,classes))),
                'dev.turboism.validation.modelimport3d.host.'+('CanonicalWarpLayerPreparationTest' if layered else 'CanonicalWarpPreparationTest'),evidence / 'apple-textured.glb',prepared])
            inputs['canonicalPreparedArtifacts']={p.name:digest(p) for p in sorted(prepared.iterdir()) if p.is_file()}
            command('canonical-warp-checker-tests',[sys.executable,'-B',evidence / ('source/validation/model-import-3d-canonical-warp/'+('test-layered-checker.py' if layered else 'test-checker.py')),
                '--prepared',prepared,'--output',run / 'canonical-checker-tests'])
            inputs['canonicalCheckerReportSha256']=digest(run / 'canonical-checker-tests/checker-tests.json')
            if digest(canonical)!=inputs['sourceWarpTextureSha256']:raise ValueError('canonical source changed during build')
        inputs['packagingClasses'] = {str(p.relative_to(packaging_classes)): digest(p)
                                     for p in sorted(packaging_classes.rglob('*.class'))}
        for source in sources:
            if digest(source) != manifest[str(source.relative_to(ROOT))]:
                raise ValueError('live source drifted during build')
        if cube is not None and digest(cube) != cube_sha:
            raise ValueError('cube input changed during build')
        if texture is not None and digest(texture) != texture_sha:
            raise ValueError('persistence texture changed during build')
        if sheets is not None and digest(sheets) != sheet_sha:
            raise ValueError('sheet input changed during build')
        for name, expected in planar.items():
            if digest(planar_dir / name) != expected:
                raise ValueError('planar fixture changed during build')
        inputs['probeSha256'] = digest(jar)
        inputs['classes'] = {str(p.relative_to(classes)): digest(p) for p in sorted(classes.rglob('*.class'))}
        write_json(evidence / 'build.json', inputs)
        terminal.update(status='PASS', inputsUnchanged=True, probeSha256=inputs['probeSha256'])
        print(json.dumps({'bundle': str(bundle), 'provenance': str(evidence), **terminal}, indent=2))
    finally:
        write_json(run / 'commands.json', commands)
        write_json(run / 'terminal.json', terminal)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--canonical-layered', action='store_true', help='98 charts with independent joint draw-order binding')
    parser.add_argument('--canonical-warp', help='GLB-only canonical production Warp validation; separate inventory')
    parser.add_argument('--source-warp-texture', help='exact frozen authored source GLB textures for candidate capture')
    parser.add_argument('--source-warp-fixture', help='exact frozen full-source Warp candidate; validation only')
    parser.add_argument('--warp-authoring', action='store_true', help='freeze exact Warp authoring source inventory')
    parser.add_argument('--sdk', required=True); parser.add_argument('--agent', required=True)
    parser.add_argument('--cube-input', help='exact authorized cube GLB to freeze and bundle')
    parser.add_argument('--persistence-texture', help='optional exact textured GLB for cube persistence/atlas validation; requires original cube')
    parser.add_argument('--planar-inputs', help='directory of exact plane.glb and plane-seam.glb; requires sheet-render baseline')
    parser.add_argument('--surface-render', action='store_true', help='planar and sheet pixels with bounded pose history; requires planar baseline')
    parser.add_argument('--pose-drive-geometry', action='store_true', help='bounded SDK pose history; geometry-only admission')
    parser.add_argument('--sheet-render', action='store_true', help='package sheet native pixel capture stage')
    parser.add_argument('--sheet-input', help='exact disjoint sheets GLB; requires persistence texture profile')
    parser.add_argument('--run', required=True); parser.add_argument('--dry-run', action='store_true')
    try:
        build(parser.parse_args())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print('FAIL: ' + str(error), file=sys.stderr); return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
