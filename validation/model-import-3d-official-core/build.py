#!/usr/bin/env python3
"""Build the standalone official-Core recorder; never run a MOC or launch an Editor."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(core, out):
    core=core.absolute();out=out.absolute()
    for path in (core,out):
        if any(p.is_symlink() for p in (path,*path.parents)):
            raise ValueError('linked artifact paths are not accepted')
    if not core.is_file() or not 0<core.stat().st_size<=32*1024*1024:
        raise ValueError('explicit bounded official Core JAR required')
    source=Path(__file__).resolve().parent/'src/dev/modelimport/validation/OfficialCoreCapture.java'
    out.mkdir(parents=True,exist_ok=False)
    (out/'classes').mkdir()
    shutil.copyfile(source,out/'OfficialCoreCapture.java')
    shutil.copyfile(core,out/'Live2DCubismCore.jar')
    if sha(core)!=sha(out/'Live2DCubismCore.jar'):
        raise ValueError('Core artifact changed during copy')
    tools={name:str(Path(shutil.which(name)).resolve()) for name in ('java','javac','jar','jdeps')}
    env={k:v for k,v in os.environ.items() if k not in ('CLASSPATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
    records=[]
    def run(name,args):
        result=subprocess.run(args,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120)
        log=out/(name+'.log');log.write_text(result.stdout)
        records.append(dict(name=name,argv=list(map(str,args)),exitCode=result.returncode,logSha256=sha(log)))
        if result.returncode:raise RuntimeError(name+' failed: '+result.stdout)
        return result.stdout
    run('java-version',[tools['java'],'-version'])
    run('javac-version',[tools['javac'],'-version'])
    run('compile',[tools['javac'],'--release','17','-encoding','UTF-8','-cp',str(out/'Live2DCubismCore.jar'),
        '-d',str(out/'classes'),str(out/'OfficialCoreCapture.java')])
    jar=out/'official-core-capture.jar'
    run('package',[tools['jar'],'--create','--file',str(jar),'-C',str(out/'classes'),'.'])
    deps=run('dependencies',[tools['jdeps'],'--class-path',str(out/'Live2DCubismCore.jar'),str(jar)])
    if 'not found' in deps or 'turboism' in deps:
        raise ValueError('recorder dependency closure is incomplete or contains Turboism')
    with zipfile.ZipFile(jar) as z:
        classes=[n for n in z.namelist() if n.endswith('.class')]
        if classes!=['dev/modelimport/validation/OfficialCoreCapture.class']:
            raise ValueError('unexpected recorder classes')
    report=dict(status='BUILT_NOT_NATIVE_VERIFIED',hostExecuted=False,sourceSha256=sha(source),
        builderSha256=sha(Path(__file__)),coreOriginal=str(core),coreSha256=sha(out/'Live2DCubismCore.jar'),
        recorderSha256=sha(jar),tools=tools,commands=records)
    (out/'build.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--core',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    args=p.parse_args();build(args.core,args.out)
