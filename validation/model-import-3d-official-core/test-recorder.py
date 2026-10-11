#!/usr/bin/env python3
"""Offline recorder input/JSON checks; never claim native MOC evaluation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
import runpy


def test(build, sdk):
    build=build.absolute();metadata=json.loads((build/'build.json').read_text())
    core=build/'Live2DCubismCore.jar';recorder=build/'official-core-capture.jar'
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    assert sha(core)==metadata['coreSha256'] and sha(recorder)==metadata['recorderSha256']
    java=metadata['tools']['java'];javac=metadata['tools']['javac']
    env={k:v for k,v in os.environ.items() if k not in ('CLASSPATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
    cp=os.pathsep.join(map(str,(recorder,core)));checks=0
    with tempfile.TemporaryDirectory(prefix='mi3d-core-recorder-') as tmp:
        root=Path(tmp);target=root/'capture';moc=root/'input.moc3'
        def rejected(args,reason,classpath=cp):
            nonlocal checks
            run=subprocess.run([java,'-cp',classpath,'dev.modelimport.validation.OfficialCoreCapture',*map(str,args)],
                env=env,text=True,capture_output=True,timeout=30)
            assert run.returncode and reason in run.stderr,(run.stdout,run.stderr)
            assert not target.exists()
            checks+=1
        rejected([],'MOC output-directory')
        rejected([moc,target,'Yaw','Pitch','unknown-profile'],'unknown capture profile')
        moc.write_bytes(b'x'*64)
        rejected([moc,target,'Yaw','Pitch'],'MOC3 header/budget mismatch')
        moc.write_bytes(b'MOC3'+b'\0'*60)
        rejected([moc,target,'Yaw','Yaw'],'two distinct parameter IDs')
        rejected([moc,target,'Yaw','Pitch','Yaw','apple-xyz-140-v1'],'three distinct parameter IDs')
        rejected([moc,target,'Yaw','Pitch','Pitch','apple-derived-xyz-140-v1'],'three distinct parameter IDs')
        rejected([moc,target,'Yaw','Pitch','Roll','unknown-profile'],'unknown capture profile')
        link=root/'linked.moc3';link.symlink_to(moc)
        rejected([link,target,'Yaw','Pitch'],'linked input/output')
        with moc.open('wb') as f:f.truncate(32*1024*1024+1)
        rejected([moc,target,'Yaw','Pitch'],'bounded regular file')
        rejected([moc,target,'Yaw','Pitch','surface-dense-49-v1'],'bounded regular file')
        rejected([moc,target,'Yaw','Pitch','apple-11-v1'],'MOC3 header/budget mismatch')
        with moc.open('wb') as f:f.truncate(256*1024*1024+1)
        rejected([moc,target,'Yaw','Pitch','Roll','apple-xyz-140-v1'],'bounded regular file')
        rejected([moc,target,'Yaw','Pitch','Roll','apple-derived-xyz-140-v1'],'bounded regular file')
        moc.write_bytes(b'MOC3'+b'\0'*60)
        marker=root/'forbidden.jar'
        with zipfile.ZipFile(marker,'w') as z:z.writestr('dev/turboism/sdk/plugin/PluginContext.class',b'resource marker, never loaded')
        rejected([moc,target,'Yaw','Pitch'],'Turboism is on the recorder classpath',cp+os.pathsep+str(marker))
        if not sdk.is_file():raise ValueError('actual built SDK JAR required for exclusion regression')
        with zipfile.ZipFile(sdk) as z:assert 'dev/turboism/sdk/plugin/PluginContext.class' in z.namelist()
        rejected([moc,target,'Yaw','Pitch'],'Turboism is on the recorder classpath',cp+os.pathsep+str(sdk.absolute()))
        extra=root/'extra.jar'
        with zipfile.ZipFile(extra,'w') as z:z.writestr('unrelated.txt',b'not part of the declared recorder')
        rejected([moc,target,'Yaw','Pitch'],'exactly Core and recorder JARs',cp+os.pathsep+str(extra))
        rejected([moc,target,'Yaw','Pitch'],'exactly Core and recorder JARs',cp+os.pathsep+str(recorder))
        extended=root/'extended-recorder.jar'
        with zipfile.ZipFile(recorder) as src,zipfile.ZipFile(extended,'w') as dst:
            for entry in src.infolist():
                if entry.filename!='META-INF/MANIFEST.MF':dst.writestr(entry,src.read(entry))
            dst.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nClass-Path: extra.jar\r\n\r\n')
        rejected([moc,target,'Yaw','Pitch'],'must not extend the classpath',str(extended)+os.pathsep+str(core))
        harness=root/'JsonCheck.java'
        harness.write_text('''package dev.modelimport.validation;
public final class JsonCheck {
 public static void main(String[] args) {
  System.out.println(OfficialCoreCapture.json(java.util.Map.of(
   "quote", "\\\"\\\\\\n\\t", "unicode", "面", "array", new float[]{1.25f,-2f}, "boolean", true)));
  try { OfficialCoreCapture.json(Float.NaN); throw new AssertionError("NaN accepted"); }
  catch (IllegalArgumentException expected) { }
  try { OfficialCoreCapture.json(java.util.Map.of(1,2)); throw new AssertionError("non-string key accepted"); }
  catch (IllegalArgumentException expected) { }
 }
}''')
        subprocess.run([javac,'--release','17','-encoding','UTF-8','-cp',cp,'-d',str(root),str(harness)],env=env,check=True,timeout=30)
        run=subprocess.run([java,'-cp',cp+os.pathsep+str(root),'dev.modelimport.validation.JsonCheck'],
            env=env,text=True,capture_output=True,timeout=30,check=True)
        assert json.loads(run.stdout)==dict(quote='"\\\n\t',unicode='面',array=[1.25,-2],boolean=True)
        checks+=3
        sampler=root/'PoseCheck.java'
        sampler.write_text('''package dev.modelimport.validation;
public final class PoseCheck {
 public static void main(String[] args) {
  double[][] first=OfficialCoreCapture.poses();
  first[0][0]=999;
  System.out.println(OfficialCoreCapture.json(OfficialCoreCapture.poses()));
 }
}''')
        subprocess.run([javac,'--release','17','-cp',cp,'-d',str(root),str(sampler)],env=env,check=True,timeout=30)
        run=subprocess.run([java,'-cp',cp+os.pathsep+str(root),'dev.modelimport.validation.PoseCheck'],
            env=env,text=True,capture_output=True,timeout=30,check=True)
        actual=json.loads(run.stdout)
        oracle=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-host-probe/verify-cube-geometry.py'))
        expected=[list(p) for p in oracle['poses']()]
        assert len(actual)==377 and actual==expected,'Core capture pose inventory differs from independent geometry protocol'
        assert len({tuple(p) for p in actual})==377 and all(-30<=v<=30 for p in actual for v in p)
        checks+=2
        sampler.write_text('''package dev.modelimport.validation;
public final class PoseCheck {
 public static void main(String[] args) {
  double[][] first=OfficialCoreCapture.surfacePoses();
  first[0][0]=999;
  System.out.println(OfficialCoreCapture.json(OfficialCoreCapture.surfacePoses()));
 }
}''')
        subprocess.run([javac,'--release','17','-cp',cp,'-d',str(root),str(sampler)],env=env,check=True,timeout=30)
        run=subprocess.run([java,'-cp',cp+os.pathsep+str(root),'dev.modelimport.validation.PoseCheck'],
            env=env,text=True,capture_output=True,timeout=30,check=True)
        actual=json.loads(run.stdout)
        surface_oracle=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-host-probe/verify-surface-geometry.py'))
        _,expected=surface_oracle['inventory'](48)
        assert len(actual)==9921 and actual==[list(p) for p in expected], 'surface keys/midpoints/random differ from independent protocol'
        assert len({tuple(p) for p in actual})==9921 and all(-30<=v<=30 for p in actual for v in p)
        checks+=2
    print(json.dumps(dict(status='OFFLINE_PASS',checks=checks,nativeExecuted=False,
        coreSha256=sha(core),recorderSha256=sha(recorder))))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--build',type=Path,required=True)
    p.add_argument('--sdk',type=Path,required=True,help='actual built Turboism SDK JAR for absence regression')
    args=p.parse_args();test(args.build,args.sdk)
