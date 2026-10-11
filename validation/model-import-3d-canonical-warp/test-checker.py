from pathlib import Path
import runpy,copy,json,io,argparse
import numpy as np
from PIL import Image
parser=argparse.ArgumentParser(description='Synthetic strict checker regressions; no native or pixel acceptance')
parser.add_argument('--prepared',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[2];out=args.output;out.mkdir(parents=True,exist_ok=False)
checker=runpy.run_path(str(Path(__file__).with_name('verify-canonical-warp.py')))
prepared=args.prepared;meshes,controls=checker['prepared'](prepared)
sha=checker['sha'];poses=checker['POSES'];keys=checker['KEYS'];yaw=checker['YAW'];pitch=checker['PITCH'];forms=[]
for i,((y,p),digest) in enumerate(controls.items()):forms.append({'guid':f'form-{i}','coordinates':[['gy',y],['gp',p]],'xySha256':digest})
captures={};samples=[]
for pose in poses:
 for mesh,m in enumerate(meshes):
  expected=checker['source_projection'](m['xyz'],pose);actual=expected.astype('>f4');raw=actual.tobytes();name=f'canonical-warp-capture/sample-{len(samples)}.xy';captures[name]=raw
  state={'updateVersion':1,'parameters':[{'id':yaw,'guid':'gy','value':pose[0]},{'id':pitch,'guid':'gp','value':pose[1]}]}
  samples.append({'pose':list(pose),'mesh':mesh,'capture':name,'coordinateType':'Canvas','xySha256':sha(raw),'maxErrorPixels':float(np.linalg.norm(actual.astype(float)-expected,axis=1).max()),'live':state,'consumed':copy.deepcopy(state)})
b=io.BytesIO();Image.new('RGBA',(1024,1024),(0,0,0,0)).save(b,format='PNG');png=b.getvalue();pixels={};frames=[]
for i,pose in enumerate(poses):
 name=f'canonical-warp-pixels/{i}.png';pixels[name]=png;frames.append({'pose':list(pose),'artifact':name,'artifactBytes':len(png),'artifactSha256':sha(png),'status':'CAPTURED_NATIVE_NOT_YET_INDEPENDENTLY_VERIFIED','historyBefore':{'position':1},'historyAfter':{'position':1}})
s={'status':'PASS','scope':'CANONICAL_GLB_SOURCE_WARP_GEOMETRY_AND_CAPTURE_ONLY','visibilityAcceptance':False,'sourceSha256':checker['SOURCE_SHA'],'vertices':[65535,35555],'triangles':[97235,53549],'camera':{'center':[512,897.8898545213067],'scale':2.954462184876577,'pivot':[0,130.6125549674034,0]},'idealErrorBoundPixels':.6426009706290593,'stages':['IDENTITY','MATERIAL','ATTACHED','NEUTRAL','BOUND','BAKED'],'assemblyOutcome':'COMMITTED','sourceMeshSha256':[m['sourceMeshSha256'] for m in meshes],'storedForms':289,'controlPointsPerForm':28680,'forms':forms,'axes':[{'id':yaw,'guid':'gy','keys':keys},{'id':pitch,'guid':'gp','keys':keys}],'historyBefore':{'position':0},'historyAfter':{'position':1},'historyAfterUndo':{'position':0},'historyAfterRedo':{'position':1},'failureOutcome':'ROLLED_BACK','failureBefore':{'position':1},'failureAfter':{'position':1},'failedWriteRollbackExact':True,'authoredFormsAfterSamplingExact':True,'undoInventoryExact':True,'redoSourceMeshesWarpImagesExact':True,'baseline':{k:[] for k in ['meshes','deformers','parts','parameters','images']},'samples':samples,'pixelFrames':frames}
s.update(maximumParentLocalDrift=0,parentRoundTripErrorBoundPixels=0,totalErrorBoundPixels=s['idealErrorBoundPixels'])
report={'runId':'synthetic-schema-only','mi3dMode':'canonical-warp-textured','status':'PASS','failure':None,'finallyRestored':True,'initialCounts':{},'finallyCounts':{},'checks':[{'id':k,'status':'PASS','actual':True} for k in ['newCommand','newIdentity']],'canonicalWarp':s}
result=checker['verify'](report,report['runId'],prepared,captures,pixels);assert result['productionImportAccepted'] is False and result['visibilityAccepted'] is False
checks=[]
def reject(label,mutator):
 r=copy.deepcopy(report);c=dict(captures);p=dict(pixels);mutator(r,c,p)
 try:checker['verify'](r,report['runId'],prepared,c,p)
 except (ValueError,KeyError,IndexError,TypeError) as failure:checks.append({'case':label,'rejected':True,'reason':str(failure)});return
 raise AssertionError('accepted '+label)
reject('wrong run',lambda r,c,p:r.update(runId='other'))
reject('old mode',lambda r,c,p:r.update(mi3dMode='source-warp-textured'))
reject('claimed production scope',lambda r,c,p:r['canonicalWarp'].update(visibilityAcceptance=True))
reject('wrong source',lambda r,c,p:r['canonicalWarp'].update(sourceSha256='0'*64))
reject('missing original triangles',lambda r,c,p:r['canonicalWarp'].update(triangles=[97234,53549]))
reject('wrong camera',lambda r,c,p:r['canonicalWarp']['camera'].update(scale=3))
reject('invented ideal bound',lambda r,c,p:r['canonicalWarp'].update(idealErrorBoundPixels=0))
reject('large native parent drift',lambda r,c,p:r['canonicalWarp'].update(maximumParentLocalDrift=1e-5))
reject('parent drift pixel budget exceeded',lambda r,c,p:r['canonicalWarp'].update(parentRoundTripErrorBoundPixels=1,totalErrorBoundPixels=1.6426009706290593))
reject('inconsistent parent drift bound',lambda r,c,p:r['canonicalWarp'].update(totalErrorBoundPixels=0))
reject('missing stage',lambda r,c,p:r['canonicalWarp']['stages'].pop())
reject('old UV/image geometry',lambda r,c,p:r['canonicalWarp'].update(sourceMeshSha256=['0'*64,'0'*64]))
reject('missing form',lambda r,c,p:r['canonicalWarp']['forms'].pop())
reject('aliased form GUID',lambda r,c,p:r['canonicalWarp']['forms'][1].update(guid='form-0'))
reject('wrong control coordinates',lambda r,c,p:r['canonicalWarp']['forms'][0].update(xySha256='0'*64))
reject('partial GUID tuple',lambda r,c,p:r['canonicalWarp']['forms'][0]['coordinates'].pop())
reject('duplicate axis GUID',lambda r,c,p:r['canonicalWarp']['axes'][1].update(guid='gy'))
reject('three binding keys',lambda r,c,p:r['canonicalWarp']['axes'][0].update(keys=[-30,0,30]))
reject('wrong Undo position',lambda r,c,p:r['canonicalWarp']['historyAfterUndo'].update(position=1))
reject('wrong Redo position',lambda r,c,p:r['canonicalWarp']['historyAfterRedo'].update(position=0))
reject('failed write committed',lambda r,c,p:r['canonicalWarp'].update(failureOutcome='COMMITTED'))
for flag in ['failedWriteRollbackExact','authoredFormsAfterSamplingExact','undoInventoryExact','redoSourceMeshesWarpImagesExact']:
 reject(flag+' false',lambda r,c,p,flag=flag:r['canonicalWarp'].update({flag:False}))
reject('missing image inventory',lambda r,c,p:r['canonicalWarp']['baseline'].pop('images'))
reject('missing sample',lambda r,c,p:r['canonicalWarp']['samples'].pop())
reject('missing capture',lambda r,c,p:c.pop(next(iter(c))))
reject('extra capture',lambda r,c,p:c.update({'canonical-warp-capture/extra.xy':b''}))
reject('wrong sample space',lambda r,c,p:r['canonicalWarp']['samples'][0].update(coordinateType='Local'))
reject('wrong consumed pose',lambda r,c,p:r['canonicalWarp']['samples'][0]['consumed']['parameters'][0].update(value=0))
def corrupt(r,c,p,nan=False):
 name=next(iter(c));a=np.frombuffer(c[name],dtype='>f4').copy();a[0]=float('nan') if nan else a[0]+3;c[name]=a.tobytes();r['canonicalWarp']['samples'][0]['xySha256']=sha(c[name])
reject('coordinate error with updated hash',corrupt)
reject('nonfinite coordinate with updated hash',lambda r,c,p:corrupt(r,c,p,True))
reject('missing PNG',lambda r,c,p:p.pop(next(iter(p))))
reject('extra PNG',lambda r,c,p:p.update({'canonical-warp-pixels/extra.png':png}))
reject('wrong PNG pose',lambda r,c,p:r['canonicalWarp']['pixelFrames'][0].update(pose=[0,0]))
reject('render changed history',lambda r,c,p:r['canonicalWarp']['pixelFrames'][0]['historyAfter'].update(position=2))
def corrupt_png(r,c,p):
 name=next(iter(p));p[name]=p[name][:-2]+b'xx';f=r['canonicalWarp']['pixelFrames'][0];f['artifactSha256']=sha(p[name]);f['artifactBytes']=len(p[name])
reject('PNG corruption with updated hash',corrupt_png)
summary={'status':'PASS_SYNTHETIC_CHECKER_CASES_NOT_NATIVE','positiveCase':'full source-coordinate/blank-PNG schema fixture; no color/occlusion acceptance','negativeCases':checks,'negativeCount':len(checks),'maxIndependentSyntheticFloatErrorPixels':result['maxIndependentErrorPixels'],'hostExecuted':False,'goalComplete':False}
(out/'checker-tests.json').write_text(json.dumps(summary,indent=2)+'\n');print('PASS synthetic schema and',len(checks),'rejection cases; hostExecuted=false')
