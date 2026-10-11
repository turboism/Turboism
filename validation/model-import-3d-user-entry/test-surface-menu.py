"""Independent inverse affine rasters and synthetic authoring corruption controls; not native acceptance."""
import copy,json,math,runpy
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2];API=runpy.run_path(str(Path(__file__).with_name('verify-surface-menu.py')))
inputs=[ROOT/'artifacts/plane-textured/plane.glb',ROOT/'artifacts/plane-textured/seam-discontinuous-r1/plane-seam.glb']
axes=['Param3DYaw0123456789ab','Param3DPitch0123456789ab'];guids=['y-guid','p-guid'];run='queue-'+'0'*32;counter=0
for file in inputs:
    raw,vertices,patches=API['sources'](file);snapshots=[]
    for mesh,(points,patch) in enumerate(zip(vertices,patches)):
        forms=[];cells=[]
        for y in API['KEYS']:
            for p in API['KEYS']:
                guid=f'm{mesh}f{len(forms)}';forms.append(dict(guid=guid,positions=[[API['G']['f32'](v) for v in point] for point in API['truth'](points,y,p)],drawOrder=500))
                cells.append(dict(formGuid=guid,coordinates=[dict(guid=guids[0],value=y),dict(guid=guids[1],value=p)]))
        snapshots.append(dict(artMeshId=f'mesh{mesh}',sourceGuid=f'source{mesh}',positions=[[u*512,v*256] for u,v in patch['uvs']],uvs=patch['uvs'],indices=patch['indices'],axes=[dict(id=a,guid=g,minimum=-30,default=0,maximum=30,keys=API['KEYS']) for a,g in zip(axes,guids)],forms=forms,cells=cells,morphKeys=[]))
    baseline=dict(meshes=[],meshMetadata=[],parameters=[],rawImages=[],imageGroups=[],parts=[],deformers=[],atlases=[],canvas=[1024,1024]);state=dict(baseline,meshes=snapshots,meshMetadata=[dict(id=m['artMeshId'],visible=True,culling=True,parent='part',input=dict(kind='MODEL_IMAGE',image='image',atlas='')) for m in snapshots],parameters=[[a,-30,0,30,0] for a in axes],rawImages=[['raw','source',512,256]],imageGroups=[dict(name='source',images=[['image','source',512,256]])],parts=[['part','source','']])
    report=dict(schemaVersion='mi3d-surface-menu-v1',scope='REAL_MENU_IMPORT_UNDO_REDO_SOURCE_RENDER',runId=run,status='PASS',textureLayout='EDGE_PADDED_POT',inputSha256=__import__('hashlib').sha256(raw).hexdigest(),appearance='SOURCE_MATERIAL',seedDocumentId='seed',targetDocumentId='new',menuInvoked=True,realChooserApproved=True,completionMessage='导入完成。',confirmation=f'{len(snapshots)} 个网格 {len(snapshots)*2401} 49 / 49',baseline=baseline,imported=state,afterUndo=baseline,afterRedo=state,undoRestored=True,redoRestored=True,historyBefore=0,historyAfter=1,historyAfterUndo=0,historyAfterRedo=1)
    base=json.loads(json.dumps(report));API['verify_ui'](base,file,run)
    mutations=[lambda r:r.update(inputSha256='0'*64),lambda r:r.update(realChooserApproved=False),lambda r:r.update(afterUndo={}),lambda r:r.update(historyAfter=2),
               lambda r:r['imported']['meshes'][0]['uvs'][0].__setitem__(0,.25),lambda r:r['imported']['meshes'][0]['indices'].__setitem__(0,2),
               lambda r:r['imported']['meshes'][0]['forms'].pop(),lambda r:r['imported']['meshes'][0]['axes'][0]['keys'].pop(),
               lambda r:r['imported']['meshes'][0]['forms'][0]['positions'][0].__setitem__(0,100),lambda r:r['imported']['rawImages'][0].__setitem__(2,256)]
    for mutate in mutations:
        bad=copy.deepcopy(base);mutate(bad)
        if bad['imported']!=base['imported']:bad['afterRedo']=copy.deepcopy(bad['imported'])
        try:API['verify_ui'](bad,file,run)
        except ValueError:counter+=1;continue
        raise AssertionError('accepted synthetic geometry corruption')
    # Whole stored interpolation error and raster inverse map are independent from Java.
    def raster(pose,weld=False,flip=False):
        image=API['PIXELS']['source'](raw)[3];y,p=map(math.radians,pose);cy,sy,cp,sp=math.cos(y),math.sin(y),math.cos(p),math.sin(p);scale=400/math.sqrt(1.25);rows=[bytearray(4096) for _ in range(1024)]
        for iy in range(2,1024,4):
            v=(512-(iy+.5))/(scale*cp)
            for ix in range(2,1024,4):
                x=((ix+.5-512)/scale-sy*sp*v)/cy
                if -1<=x<=1 and -.5<=v<=.5:
                    u=(x+1)/2;t=.5-v
                    if len(vertices)==2 and not weld:u=.45*u+(0 if v<=.5*x else .55)
                    color=API['PIXELS']['linear'](image,u,1-t if flip else t);rows[iy][4*ix:4*ix+4]=bytes(color)
        return rows
    for pose in API['POSES']:
        result=API['PIXELS']['inspect'](raster(pose),raw,pose,framed=True);assert result['mismatches']==0,result
    assert API['PIXELS']['inspect'](raster((0,0),flip=True),raw,(0,0),framed=True)['mismatches']>100
    if len(vertices)==2:assert API['PIXELS']['inspect'](raster((0,0),weld=True),raw,(0,0),framed=True)['mismatches']>100
print('PASS20 stored authoring corruption refusals +18 inverse affine raster poses +UV flip/welding controls; NOT_NATIVE_ACCEPTANCE')
