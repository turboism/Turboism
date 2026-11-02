#!/usr/bin/env python3
"""Freeze the official Viewer runtime/model closure; never launch a process."""
import argparse
import hashlib
import json
from pathlib import Path, PureWindowsPath
import shutil

EDITOR_SHA='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'


def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def plain(p):
    p=Path(p).absolute()
    if '..' in p.parts or any(q.is_symlink() for q in (p,*p.parents)):
        raise ValueError('linked/traversal path')
    return p


def official_files(editor):
    editor=plain(editor)
    if sha(editor/'app/lib/Live2D_Cubism.jar')!=EDITOR_SHA:
        raise ValueError('unexpected official Viewer version')
    bat=editor/'CubismViewer5.bat'
    lines=bat.read_text().splitlines()
    def setting(name):
        values=[line[len('set '+name+'='):] for line in lines if line.startswith('set '+name+'=')]
        if len(values)!=1:raise ValueError('ambiguous official BAT '+name)
        return values[0]
    def relative(value):
        p=PureWindowsPath(value)
        if p.is_absolute() or p.drive or '..' in p.parts:raise ValueError('nonrelative official runtime member')
        path='/'.join(p.parts)
        if not path.startswith('app/'):raise ValueError('unexpected official runtime root')
        return path
    classpath=[relative(v) for v in setting('CLASS_PATH').split(';')]
    native=[relative(v) for v in setting('NATIVE_PATH').split(';')]
    if len(classpath)!=len(set(classpath)) or relative(setting('JAVA_EXE'))!='app/jre/bin/java.exe':
        raise ValueError('official runtime entry inventory')
    files={name:plain(editor/name) for name in classpath}
    files['CubismViewer5.bat']=bat
    for directory in ['app/jre',*native]:
        for file in sorted((editor/directory).rglob('*')):
            plain(file)
            if file.is_file():files[str(file.relative_to(editor))]=file
    total=0
    for name,p in files.items():
        if not p.is_file():raise ValueError('missing official runtime dependency '+name)
        total+=p.stat().st_size
    if len(files)>4096 or total>1024*1024*1024:raise ValueError('official runtime budget')
    return files,classpath,native


def prepare(editor,model,observer,core_acceptance,out):
    model,observer,core_acceptance,out=map(plain,(model,observer,core_acceptance,out))
    meta=json.loads((model/'provenance.json').read_text());core=json.loads(core_acceptance.read_text())
    derived=core['status']=='PASS_NATIVE_DERIVED_APPLE_FINITE_XYZ_GEOMETRY'
    textures=['texture-0.png','texture-1.png'] if derived else ['texture-0.png']
    xyz=meta['status']=='PACKAGED_FINITE_XYZ_CORE_VERIFIED_VIEWER_NOT_EXECUTED'
    apple=xyz or meta['status']=='PACKAGED_CORE_VERIFIED_VIEWER_NOT_EXECUTED'
    descriptor='apple.model3.json' if apple else 'cube.model3.json'
    if xyz:
        if (core['status']!=('PASS_NATIVE_DERIVED_APPLE_FINITE_XYZ_GEOMETRY' if derived else 'PASS_NATIVE_APPLE_FINITE_XYZ_GEOMETRY') or core['officialCoreAccepted'] is not True
                or core['normalExit'] is not True or meta['coreAcceptanceSha256']!=sha(core_acceptance)
                or any(meta[k]!=core[k] for k in ('jobId','runId','preparedDigest'))
                or len(meta['parameters'])!=3 or meta['range']!=[-180,180]):
            raise ValueError('bound full XYZ standalone Core required')
    elif apple:
        if core['status']!='PASS' or core['scope']!='EXACT_APPLE_STANDALONE_OFFICIAL_CORE_GEOMETRY' or not core['officialCoreAccepted'] or meta['coreAcceptanceSha256']!=sha(core_acceptance) or meta['exportAcceptanceSha256']!=core['exportAcceptanceSha256']:
            raise ValueError('bound standalone apple Core required')
    else:
        if meta['status']!='PACKAGED_NOT_VIEWER_VERIFIED' or core['status']!='PASS' or core['scope']!='EXACT_HOST_STANDALONE_OFFICIAL_CORE_CONTENT_ONLY':
            raise ValueError('standalone Core must admit the packaged model first')
        if meta['exportJobId']!=core['exportJobId'] or meta['exportRunId']!=core['exportRunId']:
            raise ValueError('Core/Viewer export origins differ')
        if core['mocSha256']!=meta['files']['model.moc3']['sha256']:
            raise ValueError('Core input differs from Viewer MOC')
    files,classpath,native=official_files(editor)
    if set(meta['files'])!={descriptor,'model.moc3',*textures}:
        raise ValueError('model resource roles')
    for name,entry in meta['files'].items():
        p=plain(model/name)
        if p.stat().st_size!=entry['bytes'] or sha(p)!=entry['sha256']:
            raise ValueError('model member changed '+name)
        files['model/'+name]=p
    description=json.loads((model/descriptor).read_text())
    if description!={'Version':3,'FileReferences':{'Moc':'model.moc3','Textures':textures}}:
        raise ValueError('unexpected Viewer model dependencies')
    build=json.loads((observer/'build.json').read_text())
    jar=plain(observer/'official-viewer-capture.jar')
    if build['status']!='COMPILED_NOT_HOST_VERIFIED' or sha(jar)!=build['jarSha256']:
        raise ValueError('observer build identity')
    files['official-viewer-capture.jar']=jar
    inventory={name:dict(bytes=p.stat().st_size,sha256=sha(p)) for name,p in files.items()}
    out.mkdir(parents=True,exist_ok=False)
    for name,p in files.items():
        dest=out/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
        if sha(dest)!=inventory[name]['sha256'] or sha(p)!=inventory[name]['sha256']:
            raise ValueError('runtime changed during copy')
    result=dict(schemaVersion='mi3d-official-viewer-bundle-v1',status='FROZEN_NOT_EXECUTED',
                exportJobId=meta['exportJobId'],exportRunId=meta.get('exportRunId'),
                coreAcceptanceSha256=sha(core_acceptance),editorSha256=EDITOR_SHA,
                classpath=classpath+['official-viewer-capture.jar'],nativePaths=native,
                files=inventory,hostExecuted=False)
    if apple:result.update(captureProfile='apple-derived-xyz-3-v1' if derived else 'apple-xyz-3-v1' if xyz else 'apple-3-v1',modelDescriptor=descriptor)
    if xyz:result['axes']=meta['parameters']
    (out/'bundle.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('editor','model','observer','core-acceptance','out'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();r=prepare(a.editor,a.model,a.observer,a.core_acceptance,a.out)
    print(json.dumps(dict(status=r['status'],files=len(r['files']),hostExecuted=False)))
