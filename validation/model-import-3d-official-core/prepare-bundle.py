#!/usr/bin/env python3
"""Freeze standalone Core runtime/input closure. Does not launch or enqueue anything."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import runpy

EXPORT_ADMITTED=runpy.run_path(str(Path(__file__).with_name('export-admission.py')))['admitted']

EDITOR_SHA='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
CORE_SHA='98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4'
NATIVES=('Live2DCubismCoreJNI.dll','vcruntime140.dll')


def require(ok,message):
    if not ok:raise ValueError('CORE_BUNDLE: '+message)


def plain(path):
    path=Path(path).absolute()
    require('..' not in path.parts and not any(p.is_symlink() for p in (path,*path.parents)), 'linked/traversal path')
    return path


def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def inventory(root):
    root=plain(root);result={};size=0
    for p in sorted(root.rglob('*')):
        plain(p)
        if p.is_dir():continue
        require(p.is_file(),'nonregular runtime member')
        size+=p.stat().st_size
        require(size<=512*1024*1024 and len(result)<4096,'bounded runtime closure')
        result[str(p.relative_to(root))]={'bytes':p.stat().st_size,'sha256':digest(p)}
    return result


def verify(bundle):
    bundle=plain(bundle);manifest=json.loads((bundle/'bundle.json').read_text())
    require(manifest['schemaVersion']=='mi3d-official-core-bundle-v1','bundle schema')
    actual=inventory(bundle);actual.pop('bundle.json')
    require(actual==manifest['files'],'frozen bundle inventory mismatch')
    require(manifest['coreSha256']==CORE_SHA and actual['Live2DCubismCore.jar']['sha256']==CORE_SHA,'exact Core')
    require(actual['model.moc3']['sha256']==manifest['mocSha256'],'MOC identity')
    require(actual['official-core-capture.jar']['sha256']==manifest['recorderSha256'],'recorder identity')
    require({'jre/bin/java.exe','jre/bin/server/jvm.dll','native/Live2DCubismCoreJNI.dll','native/vcruntime140.dll'}<=set(actual),
            'runtime entry/dependency closure')
    return manifest


def build(editor,recorder,acceptance,out,capture_profile=None):
    editor,recorder,acceptance,out=map(plain,(editor,recorder,acceptance,out))
    require(digest(editor/'app/lib/Live2D_Cubism.jar')==EDITOR_SHA,'exact official Editor installation')
    meta=json.loads((recorder/'build.json').read_text());accepted=json.loads(acceptance.read_text())
    require(meta['status']=='BUILT_NOT_NATIVE_VERIFIED' and meta['coreSha256']==CORE_SHA,'recorder build')
    apple=accepted.get('status')=='PASS_EXACT_FROZEN_APPLE_OFFICIAL_EXPORT_CAPTURE_ONLY'
    require((apple or EXPORT_ADMITTED(accepted)) and accepted['scope']==('EXACT_APPLE_HOST_OFFICIAL_MOC_EXPORT_CAPTURE_ONLY' if apple else 'EXACT_HOST_OFFICIAL_MOC_EXPORT_CAPTURE_ONLY')
            and accepted['normalExit'] and not accepted['cgroupKillWritten'],'accepted official export')
    require(set(accepted['exportArtifacts'])=={'model.moc3','texture-0.png'},'exact export artifact inventory')
    copies={}
    for name,key in [('Live2DCubismCore.jar','coreSha256'),('official-core-capture.jar','recorderSha256')]:
        source=plain(recorder/name);require(digest(source)==meta[key],'frozen recorder JAR');copies[name]=source
    for name,entry in accepted['exportArtifacts'].items():
        source=plain(entry['path']);require(source.stat().st_size==entry['bytes'] and digest(source)==entry['sha256'],'export artifact bytes')
        copies[name]=source
    for name in NATIVES:copies['native/'+name]=plain(editor/'app/dll64'/name)
    jre=plain(editor/'app/jre');jre_before=inventory(jre)
    require('JAVA_VERSION="17.' in (jre/'release').read_text(),'official Java17 runtime')
    before={name:digest(source) for name,source in copies.items()}
    out.mkdir(parents=True,exist_ok=False)
    for name,source in copies.items():
        target=out/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
        require(digest(target)==before[name] and digest(source)==before[name],'source changed during copy')
    shutil.copytree(jre,out/'jre')
    require(inventory(jre)==inventory(out/'jre')==jre_before,'runtime changed during copy')
    result=dict(schemaVersion='mi3d-official-core-bundle-v1',status='FROZEN_NOT_EXECUTED',hostExecuted=False,
        exportJobId=accepted['jobId'],exportRunId=accepted['runId'],exportAcceptanceSha256=digest(acceptance),
        editorSha256=EDITOR_SHA,coreSha256=CORE_SHA,recorderSha256=meta['recorderSha256'],
        mocSha256=accepted['exportArtifacts']['model.moc3']['sha256'],files=inventory(out))
    if apple:
        require(accepted['content']['status']=='APPLE_NATIVE_ATLAS_EXPORT_CONTENT_CHECKED_NOT_PROCESS_OR_CORE_ACCEPTANCE' and accepted['officialExportAccepted'] is False,'explicit apple content gate')
        report=json.loads((acceptance.parent/'artifacts/report.json').read_text())
        axes=report['sourceTextureRender']['axes']
        require(capture_profile in (None,'apple-11-v1','apple-domain-353-v1'),'explicit apple capture profile')
        result.update(captureProfile=capture_profile or 'apple-11-v1',axes=axes)
    with (out/'bundle.json').open('x') as f:json.dump(result,f,indent=2);f.write('\n')
    verify(out);return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('editor','recorder','export-acceptance','out'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--capture-profile',choices=['apple-11-v1','apple-domain-353-v1'])
    a=p.parse_args();r=build(a.editor,a.recorder,a.export_acceptance,a.out,a.capture_profile)
    print(json.dumps(dict(status=r['status'],files=len(r['files']),bytes=sum(v['bytes'] for v in r['files'].values()),mocSha256=r['mocSha256'])))
