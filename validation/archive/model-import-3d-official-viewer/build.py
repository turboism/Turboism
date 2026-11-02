#!/usr/bin/env python3
"""Build an observer for the exact installed official Viewer; never starts a GUI."""
import argparse,hashlib,json,os,shutil,subprocess
from pathlib import Path

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def build(editor,out):
    source=Path(__file__).resolve().parent/'src/dev/modelimport/validation/OfficialViewerCapture.java'
    editor,out=editor.absolute(),out.absolute();official=editor/'app/lib/Live2D_Cubism.jar'
    for p in (source,official,out):
        if '..' in p.parts or any(q.is_symlink() for q in (p,*p.parents)):raise ValueError('unsafe path')
    if sha(official)!='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21':raise ValueError('wrong official Viewer')
    out.mkdir(parents=True,exist_ok=False);(out/'classes').mkdir();shutil.copyfile(source,out/'OfficialViewerCapture.java')
    env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
    commands=[]
    for name,argv in [('compile',['javac','--release','17','-encoding','UTF-8','-cp',str(official),'-d',str(out/'classes'),str(out/'OfficialViewerCapture.java')]),
                      ('package',['jar','--create','--file',str(out/'official-viewer-capture.jar'),'-C',str(out/'classes'),'.'])]:
        with (out/(name+'.log')).open('xb') as log:result=subprocess.run(argv,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=120)
        commands.append(dict(argv=argv,exitCode=result.returncode))
        if result.returncode:raise RuntimeError(name+' failed')
    if sha(source)!=sha(out/'OfficialViewerCapture.java'):raise ValueError('source changed during build')
    report=dict(status='COMPILED_NOT_HOST_VERIFIED',sourceSha256=sha(source),jarSha256=sha(out/'official-viewer-capture.jar'),
                officialJarSha256=sha(official),commands=commands,hostExecuted=False,officialRendererExecuted=False,
                poseControl='UI_MOUSE_REQUESTED_NOT_VALUE_VERIFIED',modelNodeSelection='STANDARD_SWING_EVENT')
    (out/'build.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--editor',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();build(a.editor,a.out)
