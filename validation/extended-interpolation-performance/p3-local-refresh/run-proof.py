#!/usr/bin/env python3
"""Validation-only official-common interception. Never launches Editor or changes production hooks."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,zipfile
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--versions',nargs='+',default=['5.3.04']);p.add_argument('--omit-notifications',action='store_true');a=p.parse_args()
out=a.out.resolve();out.mkdir(exist_ok=False,parents=True);classes=out/'classes';classes.mkdir()
ref=Path('/opt/dev/projects/turboism/cubism-ref')
asm=Path.home()/'.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar'
sources=[HERE/'LocalRefreshAgent.java',HERE/'LocalRefreshProof.java',HERE.parent/'p3-dependencies/src/dev/turboism/validation/p3/P3DependencyProbe.java']
cp=os.pathsep.join([str(ref/'5.3.04/*'),str(asm)])
r=subprocess.run(['javac','--release','17','-cp',cp,'-d',str(classes),*map(str,sources)],capture_output=True,text=True,timeout=60)
(out/'compile.log').write_text(r.stdout+r.stderr);r.check_returncode()
agent=out/'proof-agent.jar'
with zipfile.ZipFile(agent,'w') as z:
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.local.LocalRefreshAgent\n\n')
 for f in classes.rglob('*.class'):z.write(f,str(f.relative_to(classes)))
manifest={'scope':'experimental local common skip; no production installation or Editor UI claim','sources':{str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sources},'omitNotifications':a.omit_notifications,'runs':[]}
for version in dict.fromkeys(['5.3.04',*a.versions]):
 d=out/version;d.mkdir();cp=os.pathsep.join([str(agent),str(ref/version/'*'),str(asm)])
 cmd=['java','-XX:-UsePerfData','-Xmx1g','-Djava.awt.headless=true','-javaagent:'+str(agent),'-cp',cp,'dev.turboism.validation.local.LocalRefreshProof']
 if a.omit_notifications:cmd.insert(1,'-Dlocal.proof.omitNotifications=true')
 with (d/'proof.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,cwd=d,timeout=180)
 manifest['runs'].append(dict(version=version,exitCode=r.returncode,command=cmd,jarSha256=hashlib.sha256((ref/version/'Live2D_Cubism.jar').read_bytes()).hexdigest()))
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(version,r.returncode,flush=True)
 if r.returncode:raise SystemExit(r.returncode)
