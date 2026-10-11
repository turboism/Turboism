#!/usr/bin/env python3
"""Bind Core content checks to one admitted export. Not native process admission."""
import argparse
import hashlib
import json
from pathlib import Path, PureWindowsPath
import runpy
import tarfile

HERE=Path(__file__).resolve().parent
EXPORT_ADMITTED=runpy.run_path(str(HERE/'export-admission.py'))['admitted']
GEOMETRY=runpy.run_path(str(HERE/'verify-geometry.py'))['verify']
RASTER=runpy.run_path(str(HERE/'verify-raster.py'))['verify']
REPORT='state/dev.turboism.validation.model-import-3d/report.json'
EXPORT='state/dev.turboism.validation.model-import-3d/official-export/'


def require(ok,message):
    if not ok:raise ValueError('CORE_CONTENT: '+message)


def sha(raw):return hashlib.sha256(raw).hexdigest()


def pairs(items):
    result={}
    for k,v in items:
        require(k not in result,'duplicate JSON field '+k);result[k]=v
    return result


def decode(raw):
    def invalid(value):raise ValueError('nonfinite JSON '+value)
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=invalid)


def read(path,limit):
    path=Path(path).absolute()
    require(not any(p.is_symlink() for p in (path,*path.parents)),'linked input')
    require(path.is_file() and 0<path.stat().st_size<=limit,'input size/type')
    with path.open('rb') as f:raw=f.read(limit+1)
    require(len(raw)<=limit,'input grew beyond limit');return raw


def bind(capture,acceptance,host,artifacts,build, *, source_texture=None):
    require(EXPORT_ADMITTED(acceptance) and acceptance['scope']=='EXACT_HOST_OFFICIAL_MOC_EXPORT_CAPTURE_ONLY'
            and acceptance['normalExit'] is True and acceptance['cgroupKillWritten'] is False,'export admission')
    require(host['runId']==acceptance['runId'] and host['status']=='PASS','export report identity/status')
    section=host['cubeExport']
    if acceptance['status'] != 'PASS':
        require(source_texture is not None,'deviation receipt requires explicit source texture')
    require(section['status']=='CAPTURED_NOT_CORE_VERIFIED' and section['coreVerification']=='NOT_RUN','export capture scope')
    if source_texture is None:
        require(section.get('inputSha256') in (None, '985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630'),'source texture requires explicit GLB')
    if source_texture is not None:
        require(sha(read(source_texture,16*1024*1024))==section.get('inputSha256')==
                'dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                and section.get('textureLayout')=='EDGE_PADDED_POT','explicit source texture binding')
    require(section['settings']==[512,512,100,False,False,False,False,False],'explicit export settings')
    require(set(artifacts)==set(acceptance['exportArtifacts'])=={'model.moc3','texture-0.png'},'export artifact set')
    for name,raw in artifacts.items():
        evidence=acceptance['exportArtifacts'][name]
        require(len(raw)==evidence['bytes'] and sha(raw)==evidence['sha256'],'accepted export bytes '+name)
    moc=artifacts['model.moc3']
    require(capture['mocSha256']==sha(moc) and capture['mocBytes']==len(moc),'Core input is not admitted MOC')
    require(capture['status']=='CAPTURED_NOT_VERIFIED','Core recorder status')
    require(build['status']=='BUILT_NOT_NATIVE_VERIFIED' and build['hostExecuted'] is False,'recorder build status')
    require(capture['coreOrigin']['sha256']==build['coreSha256']
            and capture['recorderOrigin']['sha256']==build['recorderSha256'],'recorded Core/recorder identity')
    require(type(capture['pid']) is int and capture['pid']>0 and isinstance(capture['processStart'],str)
            and capture['processStart'],'recorded process identity')
    arguments=capture['jvmArguments']
    require(isinstance(arguments,list) and all(isinstance(a,str) and not a.startswith(
        ('-javaagent:','-agentlib:','-agentpath:','-Xbootclasspath','-Djava.system.class.loader=')) for a in arguments),
        'recorded JVM agents or classloader overrides')
    origins=[capture[key]['path'] for key in ('coreOrigin','recorderOrigin')]
    # The native recorder runs Windows Java in isolated Proton; do not infer POSIX separators.
    actual=capture['classpath'].split(';')
    normalize=lambda p:str(PureWindowsPath(p)).casefold()
    require(len(actual)==2 and all(PureWindowsPath(p).is_absolute() for p in actual+origins)
            and len(set(map(normalize,actual)))==2 and set(map(normalize,actual))==set(map(normalize,origins)),
            'recorded two-JAR Windows classpath')
    snapshots=section['before']['snapshots']
    ids=[s['artMeshId'] for s in snapshots]
    require(len(ids)==6 and len(set(ids))==6,'export source-face identities')
    return ids


def verify(args):
    acceptance_raw=read(args.export_acceptance,2*1024*1024);acceptance=decode(acceptance_raw)
    archive=Path(args.export_archive);raw=read(archive,256*1024*1024)
    require(sha(raw)==acceptance['archiveSha256'],'admitted export archive hash')
    import io
    with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as tar:
        names=[REPORT,EXPORT+'model.moc3',EXPORT+'texture-0.png'];selected={}
        for m in tar:
            if m.name in names:
                require(m.name not in selected and m.isfile() and m.size<=96*1024*1024,'archive member ambiguity/budget')
                selected[m.name]=tar.extractfile(m).read()
        require(set(selected)==set(names),'missing export report/artifacts')
    host=decode(selected[REPORT]);artifacts={name:selected[EXPORT+name] for name in ('model.moc3','texture-0.png')}
    capture_raw=read(args.capture,64*1024*1024);capture=decode(capture_raw)
    build_raw=read(Path(args.recorder_build)/'build.json',2*1024*1024);build=decode(build_raw)
    for name,key in [('Live2DCubismCore.jar','coreSha256'),('official-core-capture.jar','recorderSha256')]:
        require(sha(read(Path(args.recorder_build)/name,32*1024*1024))==build[key],'frozen recorder artifact')
    source_texture=getattr(args,"source_texture",None)
    ids=bind(capture,acceptance,host,artifacts,build,source_texture=source_texture)
    geometry=GEOMETRY(capture,args.cube,ids,host['cubeExport']['before']['state']['parameters'])
    raster=RASTER(capture,args.cube,ids,artifacts['texture-0.png'],source_texture=source_texture)
    result=dict(status='BOUND_CORE_CONTENT_CHECKED_NOT_PROCESS_ADMITTED',exportJobId=acceptance['jobId'],
        exportRunId=acceptance['runId'],captureSha256=sha(capture_raw),exportAcceptanceSha256=sha(acceptance_raw),
        recorderBuildSha256=sha(build_raw),archiveSha256=acceptance['archiveSha256'],geometry=geometry,raster=raster,
        notCovered=['independent native process/containment admission','official renderer execution'],goalComplete=False)
    with Path(args.out).open('x') as f:json.dump(result,f,indent=2);f.write('\n')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('capture','cube','export-acceptance','export-archive','recorder-build','out'):p.add_argument('--'+name,required=True,type=Path)
    p.add_argument('--source-texture',type=Path)
    result=verify(p.parse_args());print(result['status'])
