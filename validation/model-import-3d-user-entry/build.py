#!/usr/bin/env python3
"""Freeze an SDK-only UI driver. Does not launch, prepare or submit any host job."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
import io
import struct

ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
CUBE=ROOT/'artifacts/cube-textured/cube-textured.glb'
CAT=ROOT/'artifacts/cat-textured/source-r2/cat-textured.glb'
CAT_SHA='4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'
APPLE=ROOT/'artifacts/apple-textured/source-r2/apple-textured.glb'
APPLE_SHA='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999'
CUBE_SHA='dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'


def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()


def build(args):
    if args.derived_source_fixture and (not args.apple or not args.apple_editability or args.equivalent_chart_fixture):raise ValueError('derived fixture requires dedicated Apple seed driver')
    if args.equivalent_chart_fixture and (not args.apple or not args.apple_editability):raise ValueError('equivalent chart fixture requires apple source and source image')
    if (args.surface_save or args.surface_reopen or args.surface_atlas_save or args.surface_atlas_reopen) and not args.surface_fixture:raise ValueError('surface save requires surface fixture')
    if args.apple_editability and not args.apple:raise ValueError('apple editability requires apple source')
    if args.cat_persistence and (not args.cat or args.cat_frames_only):raise ValueError("dedicated Cat persistence profile requires Cat source")
    if args.cat_frames_only and not args.cat:raise ValueError('Cat frames-only requires Cat source')
    surface_paths={'plane':(ROOT/'artifacts/plane-textured/plane.glb','8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997'),'seam':(ROOT/'artifacts/plane-textured/seam-discontinuous-r1/plane-seam.glb','c9605e030d4f174501d8f98f798dcad3a5a3851254b347b1b80e882fee8484dd')}
    optimized=args.cat_persistence in ('optimized-save','adaptive-save','cost-save','cost-drag','optimized-reopen','optimized-drag','optimized-short-drag','adaptive-short-drag','native-serialization','default-serialization')
    source,source_sha=surface_paths[args.surface_fixture] if args.surface_fixture else (CAT,CAT_SHA) if args.cat else (APPLE,APPLE_SHA) if args.apple else (CUBE,CUBE_SHA)
    if optimized:source,source_sha=ROOT/'artifacts/cat-textured/package-source-r1/cat.zip','ed1a27af5ee2724849d8c6ed3e76322579c3c88d92ecb11ffd99213ee0125d11'
    out=args.out.absolute();sdk=args.sdk.absolute();agent=args.agent.absolute()
    for p in (out,sdk,agent,source):
        if '..' in p.parts or any(q.is_symlink() for q in (p,*p.parents)):raise ValueError('unsafe path')
    if not out.is_relative_to(ROOT/'build') or out.exists():raise ValueError('new worktree build output required')
    if sha(source)!=source_sha:raise ValueError('authorized input changed')
    sources=list((HERE/'src').rglob('*.java'))+[ROOT/'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host'/n for n in ('NativeAssertions.java','NativeReport.java')]
    resources=sorted(p for p in (HERE/'resources').rglob('*') if p.is_file())
    packaging=ROOT/'validation/model-import-3d-host-probe/packaging-test/dev/turboism/validation/modelimport3d/packaging/PluginJarCheck.java'
    fixture_paths=[ROOT/'artifacts/intersecting-sheets/sheets.glb',ROOT/'artifacts/cyclic-sheets/sheets.glb'] if args.reject_fixtures else []
    inputs=[source]+fixture_paths+sources+resources+[Path(__file__),packaging,HERE/'selftest/dev/turboism/validation/modelimport3d/host/ApplePersistenceFileTest.java',HERE/'selftest/dev/turboism/validation/modelimport3d/host/UserEntryProbeSelfTest.java']
    surface_test=HERE/'selftest/dev/turboism/validation/modelimport3d/host/SurfacePersistenceFileTest.java'
    inputs.insert(0,surface_test)
    if args.equivalent_chart_fixture:
        candidate=args.equivalent_chart_fixture.absolute()
        if '..' in candidate.parts or any(q.is_symlink() for q in (candidate,*candidate.parents)):raise ValueError('unsafe candidate path')
        if not candidate.is_relative_to(ROOT/'build') or sha(candidate) not in ('adebbb4e934804d9642da62f2383d813aae1b4378e912426e5055ab35f412daa','906624bccb601f507a1531a9518c4efe9ab58027ac5a7eb9755ac8a0ba6fb1e1'):raise ValueError('exact audited chart26 candidate required')
        inputs.insert(0,candidate)
    if args.derived_source_fixture:
        candidate=args.derived_source_fixture.absolute()
        if '..' in candidate.parts or any(q.is_symlink() for q in (candidate,*candidate.parents)):raise ValueError('unsafe derived candidate path')
        if not candidate.is_relative_to(ROOT/'build') or sha(candidate) not in ('f93fc84f9a71351977ecd1d85e82d39793278b3683ffb3c8b1b32d5049ef3520','00a706b57d43b33e9042c0f1fb8b66692c0be44e7525a5b4ef4739be393656cc','cd891e99c35c3d0e6302c591a98fa020f2ae32dc6bba0a856a282f361afc54f2') or candidate.stat().st_size>32*1024*1024:raise ValueError('exact all98 derived source fixture required')
        inputs.insert(0,candidate)
    out.mkdir(parents=True);snapshot=out/'source';snapshot.mkdir()
    for p in inputs:
        target=snapshot/p.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
    shutil.copyfile(sdk,out/'sdk.jar');shutil.copyfile(agent,out/'agent.jar');classes=out/'classes';classes.mkdir()
    def run(argv):
        with (out/'build.log').open('ab') as log:subprocess.run(list(map(str,argv)),check=True,stdout=log,stderr=subprocess.STDOUT)
    run(['javac','--release','17','-encoding','UTF-8','-cp',out/'sdk.jar','-d',classes,*[snapshot/p.relative_to(ROOT) for p in sources]])
    shutil.copytree(snapshot/(HERE/'resources').relative_to(ROOT),classes,dirs_exist_ok=True)
    fixture=classes/('dev/turboism/validation/modelimport3d/host/'+('surface.glb' if args.surface_fixture else 'cat.zip' if optimized else 'cat.glb' if args.cat else 'apple.glb' if args.apple else 'cube.glb'));shutil.copyfile(source,fixture)
    if args.apple_editability:
        # Lossless source material copy for the isolated mesh-edit document.
        # Both source images use unit factors and the importer preserves texels at (1,1).
        from PIL import Image
        raw=source.read_bytes();length=struct.unpack_from('<I',raw,12)[0]
        document=json.loads(raw[20:20+length]);binary=raw[28+length:]
        if len(document['materials'])!=2 or any(m['pbrMetallicRoughness']['baseColorFactor']!=[1,1,1,1] for m in document['materials']):
            raise ValueError('exact apple source material layout required')
        image=Image.new('RGBA',(1024,256))
        for tile,material in enumerate(document['materials']):
            texture=document['textures'][material['pbrMetallicRoughness']['baseColorTexture']['index']]
            view=document['bufferViews'][document['images'][texture['source']]['bufferView']]
            start=view.get('byteOffset',0);pixels=Image.open(io.BytesIO(binary[start:start+view['byteLength']])).convert('RGBA')
            if pixels.size!=(256,128) or any(p[3]!=255 for p in pixels.getdata()):raise ValueError('opaque exact source image required')
            for y in range(256):
                for x in range(512):image.putpixel((tile*512+x,y),pixels.getpixel((max(0,min(255,x-1)),max(0,min(127,y-1)))))
        image.save(classes/'dev/turboism/validation/modelimport3d/host/mesh-edit-copy.png')
    if args.equivalent_chart_fixture:shutil.copyfile(args.equivalent_chart_fixture,classes/'dev/turboism/validation/modelimport3d/host/equivalent-chart26.bin')
    if args.derived_source_fixture:shutil.copyfile(args.derived_source_fixture,classes/'dev/turboism/validation/modelimport3d/host/derived-bound-all98.bin')
    if args.reject_fixtures:
        for p,name,digest in zip(fixture_paths,('intersecting-sheets.glb','cyclic-sheets.glb'),('dfae2a74e43fb58ac7faf76ab43230fa84293df8f1dc16017665a147da6232fc','6374ee20102596be9f0d9fb25536b9631ccb0202471acf125ffedbd2002f474a')):
            require_file=classes/'dev/turboism/validation/modelimport3d/host'/name
            if sha(p)!=digest:raise ValueError('rejection input identity')
            shutil.copyfile(p,require_file)
    if sha(fixture)!=source_sha or sha(source)!=source_sha:raise ValueError('input changed during freeze')
    jar=out/'model-import-3d-user-entry-probe.jar';run(['jar','--create','--file',jar,'-C',classes,'.'])
    with zipfile.ZipFile(jar) as z:
        names=z.namelist()
        if any(n.endswith('.class') and not n.startswith('dev/turboism/validation/modelimport3d/host/') for n in names):raise ValueError('non-SDK consumer packaging')
        if any(n.endswith(('ModelImportPlugin.class','SurfaceImport.class','HostProbe.class')) for n in names):raise ValueError('production engine must not be embedded in driver')
    tests=out/'test-classes';tests.mkdir()
    run(['javac','--release','17','-encoding','UTF-8','-cp',out/'agent.jar','-d',tests,snapshot/packaging.relative_to(ROOT)])
    run(['java','-cp',str(tests)+':'+str(out/'agent.jar'),'dev.turboism.validation.modelimport3d.packaging.PluginJarCheck',jar])
    run(['javac','--release','17','-encoding','UTF-8','-cp',str(classes)+':'+str(out/'sdk.jar'),'-d',tests,snapshot/inputs[-1].relative_to(ROOT)])
    run(['java','-Djava.awt.headless=true','-cp',str(tests)+':'+str(classes)+':'+str(out/'sdk.jar'),'dev.turboism.validation.modelimport3d.host.UserEntryProbeSelfTest'])
    run(['javac','--release','17','-encoding','UTF-8','-cp',str(classes)+':'+str(out/'sdk.jar'),'-d',tests,snapshot/inputs[-2].relative_to(ROOT)])
    run(['java','-Djava.awt.headless=true','-cp',str(tests)+':'+str(classes)+':'+str(out/'sdk.jar'),'dev.turboism.validation.modelimport3d.host.ApplePersistenceFileTest',out/'apple-save-tests'])
    run(['javac','--release','17','-encoding','UTF-8','-cp',str(classes)+':'+str(out/'sdk.jar'),'-d',tests,snapshot/surface_test.relative_to(ROOT)])
    run(['java','-Djava.awt.headless=true','-cp',str(tests)+':'+str(classes)+':'+str(out/'sdk.jar'),'dev.turboism.validation.modelimport3d.host.SurfacePersistenceFileTest',out/'surface-save-tests'])
    manifest={str(p.relative_to(ROOT)):sha(snapshot/p.relative_to(ROOT)) for p in inputs}
    if any(sha(ROOT/name)!=digest for name,digest in manifest.items()):raise ValueError('sources changed during build')
    if sha(agent)!=sha(out/'agent.jar') or sha(sdk)!=sha(out/'sdk.jar'):raise ValueError('build dependencies changed')
    report=dict(profile=args.surface_fixture+'-menu'+('-save' if args.surface_save else '-reopen' if args.surface_reopen else '-atlas-save' if args.surface_atlas_save else '-atlas-reopen' if args.surface_atlas_reopen else '') if args.surface_fixture else 'menu-reject' if args.reject_fixtures else 'apple-menu-editability-compact' if args.apple_editability else 'cat-'+args.cat_persistence if optimized else 'cat-vertex-'+args.cat_persistence if args.cat_persistence else 'cat-vertex-frames' if args.cat_frames_only else 'cat-vertex-menu' if args.cat else 'apple-layered' if args.apple else 'cube',status='BUILT_NOT_HOST_VERIFIED',hostExecuted=False,sources=manifest,sdkSha256=sha(out/'sdk.jar'),agentSha256=sha(out/'agent.jar'),inputSha256=sha(fixture),probeSha256=sha(jar),jarEntries=names)
    (out/'build.json').write_text(json.dumps(report,indent=2)+'\n');print(report['status'])


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);group=p.add_mutually_exclusive_group();group.add_argument('--apple',action='store_true');group.add_argument('--cat',action='store_true');group.add_argument('--reject-fixtures',action='store_true');group.add_argument('--surface-fixture',choices=('plane','seam'));p.add_argument('--sdk',required=True,type=Path);p.add_argument('--agent',required=True,type=Path);p.add_argument('--out',required=True,type=Path)
    p.add_argument('--cat-frames-only',action='store_true');p.add_argument('--cat-persistence',choices=('save','reopen','bank','edit','drag-layout','drag','optimized-save','adaptive-save','cost-save','cost-drag','optimized-reopen','optimized-drag','optimized-short-drag','adaptive-short-drag','native-serialization','default-serialization'))
    p.add_argument('--derived-source-fixture',type=Path);p.add_argument('--equivalent-chart-fixture',type=Path);p.add_argument('--apple-editability',action='store_true');actions=p.add_mutually_exclusive_group();actions.add_argument('--surface-save',action='store_true');actions.add_argument('--surface-reopen',action='store_true');actions.add_argument('--surface-atlas-save',action='store_true');actions.add_argument('--surface-atlas-reopen',action='store_true');build(p.parse_args())
