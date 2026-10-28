#!/usr/bin/env python3
"""Audit the precise notification primitive; does not infer point count from target membership."""
import hashlib,json,re,subprocess
from pathlib import Path
HERE=Path(__file__).resolve().parent
manifest=json.loads((HERE/'evidence/matrix/manifest.json').read_text())
results=[]
for run in manifest['runs']:
 version=run['version'];jar=Path('/opt/dev/projects/turboism/cubism-ref')/version/'Live2D_Cubism.jar'
 result=subprocess.run(['javap','-c','-p','-classpath',str(jar),'com.live2d.cubism.doc.model.ACForm'],capture_output=True,text=True,check=True,timeout=30)
 match=re.search(r'  public final void setUpdated\(\);\n(.*?)(?=\n  (?:public|private|protected))',result.stdout,re.S)
 if not match:raise AssertionError(version+' missing final setter')
 body=match.group(1);instructions=[]
 for line in body.splitlines():
  m=re.match(r'\s*\d+:\s*(.*)',line)
  if m:instructions.append(m.group(1))
 opcodes=[x.split()[0] for x in instructions]
 assert opcodes==['aload_0','getfield','istore_1','aload_0','iload_1','iconst_1','iadd','putfield','return'],(version,instructions)
 assert all('editVersion:I' in i for i in instructions if i.startswith(('getfield','putfield')))
 dest=HERE/'evidence/setter'/version;dest.mkdir(parents=True,exist_ok=True);(dest/'setUpdated.txt').write_text(match.group(0)+'\n')
 results.append(dict(version=version,jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),finalSetterOnlyEditVersionIncrement=True))
(HERE/'evidence/setter/summary.json').write_text(json.dumps(results,indent=2)+'\n');print('SETTER_AUDIT PASS versions='+str(len(results)))
