#!/usr/bin/env python3
"""Synthetic visibility/fingerprint controls. No host or material acceptance claims."""
import copy
import hashlib
import struct
import zlib
import json
import math
import os
from pathlib import Path
import runpy
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
ORACLE = runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-cube-render.py'))
CUBE = Path(os.environ.get('MI3D_RENDER_CUBE_INPUT', ROOT/'build/model-import-3d-inputs/cube-985496727e50/cube.glb'))


def ray_face(x, y, pose, far=False):
    # Independent analytic ray/axis-aligned-box intersection. No triangles or
    # producer/oracle projection helper. Inverse rotates both origin and ray.
    yaw,pitch = map(math.radians,pose)
    cy,sy,cp,sp = math.cos(yaw),math.sin(yaw),math.cos(pitch),math.sin(pitch)
    sx,sy_screen = (x-512)/200, (512-y)/200
    origin = (cy*sx, sp*sy*sx+cp*sy_screen, cp*sy*sx-sp*sy_screen)
    direction = (-sy, sp*cy, cp*cy)
    lower,upper = -math.inf,math.inf
    near_face,far_face = None,None
    # Fixture's fixed material order: +Y,+Z,-X,-Y,+X,-Z.
    face_ids = ((2,4),(3,0),(5,1))
    for axis in range(3):
        o,d = origin[axis],direction[axis]
        if abs(d)<1e-12:
            if not -1 <= o <= 1:
                return None
            continue
        a,b = (-1-o)/d,(1-o)/d
        fa,fb = face_ids[axis]
        if a>b:
            a,b,fa,fb = b,a,fb,fa
        if a>lower:
            lower,near_face = a,fa
        if b<upper:
            upper,far_face = b,fb
    return (far_face if far else near_face) if lower <= upper else None


def raster(pose, source=False, far=False):
    rows = [bytearray(4096) for _ in range(1024)]
    for y in range(2,1024,4):
        for x in range(2,1024,4):
            face = ray_face(x+.5,y+.5,pose,far)
            if face is not None:
                rows[y][4*x:4*x+4] = bytes((231,231,231,255) if source else ORACLE['PALETTE'][face])
    return rows


class VisibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cube = CUBE.read_bytes()

    def test_all_predeclared_poses_match_independent_box_rays(self):
        for pose in ORACLE['POSES']:
            with self.subTest(pose=pose):
                for source in (False,True):
                    result = ORACLE['inspect_pixels'](raster(pose,source),self.cube,pose,source)
                    self.assertEqual(result['mismatches'],0,result['firstMismatch'])
                self.assertGreater(min(n for n in result['coverage'] if n),0)

    def test_back_faces_fail(self):
        for pose in ORACLE['POSES']:
            result = ORACLE['inspect_pixels'](raster(pose,far=True),self.cube,pose)
            self.assertGreater(result['mismatches'],1000)

    def test_stale_pose_fails(self):
        self.assertGreater(ORACLE['inspect_pixels'](raster((0,0)),self.cube,(30,30))['mismatches'],1000)

    def test_wrong_color_and_exterior_alpha_fail(self):
        rows=raster((0,0)); rows[502][4*510:4*510+4] = bytes((0,0,0,255))
        rows[2][4*2+3]=255
        self.assertEqual(ORACLE['inspect_pixels'](rows,self.cube,(0,0))['mismatches'],2)

    def test_missing_frames_rejected_before_artifact_read(self):
        section=dict(poses=[list(p) for p in ORACLE['POSES']],
                     palette=[int.from_bytes(bytes((255,*p[:3])),'big',signed=True) for p in ORACLE['PALETTE']],frames=[])
        with self.assertRaisesRegex(ValueError,'frame count'):
            ORACLE['verify_pixels'](section,CUBE,lambda _:self.fail('unexpected artifact read'))

    def test_complete_png_inventory_and_tamper_controls(self):
        def png(rows):
            def chunk(kind,data):
                return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
            return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1024,1024,8,6,0,0,0))
                    +chunk(b'IDAT',zlib.compress(b''.join(b'\0'+r for r in rows)))+chunk(b'IEND',b''))
        artifacts={};frames=[]
        for phase in ORACLE['PHASES']:
            for index,pose in enumerate(ORACLE['POSES']):
                name=f'cube-render/{phase}-{index}.png'
                raw=png(raster(pose,phase == 'source-on',phase == 'diagnostic-off'))
                artifacts[name]=raw
                frames.append(dict(phase=phase,index=index,pose=list(pose),artifact=name,
                    artifactBytes=len(raw),artifactSha256=hashlib.sha256(raw).hexdigest()))
        section=dict(poses=[list(p) for p in ORACLE['POSES']],
            palette=[int.from_bytes(bytes((255,*p[:3])),'big',signed=True) for p in ORACLE['PALETTE']],frames=frames)
        result=ORACLE['verify_pixels'](section,CUBE,artifacts.__getitem__)
        self.assertGreater(result['cullingOffMismatches'],1000)
        self.assertEqual(len(result['frames']),27)
        for key,value in (('pose',[30,30]),('artifact','../outside.png'),('artifactBytes',99999999),('artifactSha256','0'*64)):
            tampered=copy.deepcopy(section);tampered['frames'][0][key]=value
            with self.subTest(key=key), self.assertRaises(ValueError):
                ORACLE['verify_pixels'](tampered,CUBE,artifacts.__getitem__)
        # Consistent metadata cannot rescue an incorrect diagnostic-on image.
        tampered=copy.deepcopy(section);target=tampered['frames'][9]
        raw=artifacts['cube-render/diagnostic-off-0.png']
        target['artifactBytes']=len(raw);target['artifactSha256']=hashlib.sha256(raw).hexdigest()
        altered=dict(artifacts);altered[target['artifact']]=raw
        with self.assertRaisesRegex(ValueError,'incorrect nearest surface'):
            ORACLE['verify_pixels'](tampered,CUBE,altered.__getitem__)

    def test_partial_opacity_is_not_an_opaque_material(self):
        rows=raster((0,0));rows[502][4*510+3]=253
        self.assertEqual(ORACLE['inspect_pixels'](rows,self.cube,(0,0))['mismatches'],1)

    def test_input_hash_is_enforced(self):
        with self.assertRaises(ValueError):
            ORACLE['samples'](self.cube[:-1]+b'X',(0,0))


def state_fixture():
    # Only exercises the render cross-binding gate; does not claim the geometry
    # fixture satisfies the separate full cubeGeometry admission contract.
    sources,_ = ORACLE['GEOMETRY']['cube_source'](CUBE)
    snapshots=[]
    for i,vertices in enumerate(sources):
        snapshots.append(dict(artMeshId=f'mesh-{i}',sourceGuid=f'source-{i}',
            positions=ORACLE['GEOMETRY']['truth'](vertices,0,0),uvs=[[0,0]]*4,
            indices=[0,1,2,0,2,3],axes=[],cells=[],
            forms=[dict(guid=f'form-{i}',positions=[[0,0]]*4,drawOrder=500)],morphKeys=[]))
    hashes=[ORACLE['fingerprint'](s) for s in snapshots]
    history=dict(availability='AVAILABLE',generation=1,position=0,revision=10,
                 documentBindingId='doc',managerBindingId='history',canUndo=False,canRedo=False,entries=[])
    def bindings(source):
        result=[]
        for i in range(6):
            color=(231,231,231,255) if source else ORACLE['PALETTE'][i]
            suffix='source' if source else str(i)
            result.append(dict(meshId=f'mesh-{i}',surface=0,material=0,
                argb=int.from_bytes(bytes((255,*color[:3])),'big',signed=True),
                imageId='image-'+suffix,rawId='raw-'+suffix,modelId='model',generation=5))
        return result
    diagnostic,source=bindings(False),bindings(True)
    def state(pose,phase=None):
        bound=source if phase == 'source-on' else diagnostic
        meshes=[]
        for i,vertices in enumerate(sources):
            b=bound[i]
            meshes.append(dict(id=f'mesh-{i}',snapshotSha256=hashes[i],culling=bool(phase and phase != 'diagnostic-off'),
                visible=True,opacity=dict(status='AVAILABLE',value=1),positions=ORACLE['GEOMETRY']['truth'](vertices,*pose),drawOrder=500,
                input=None if phase is None else dict(kind='MODEL_IMAGE',modelId='model',generation=5,
                                                      meshId=f'mesh-{i}',imageId=b['imageId'])))
        unique=list({b['imageId']:b for b in bound}.values()) if phase else []
        return dict(model='model',meshes=meshes,pose=list(pose),canvas=[1024,1024,512,512,100],
            instances=['EDITOR'],currentInstance='EDITOR',
            rawImages=[[b['rawId'],'image',256,128] for b in unique],
            imageGroups=[dict(name='group',images=[[b['imageId'],'image',256,128] for b in unique])] if phase else [])
    initial=state((0,0))
    section=dict(schemaVersion='mi3d-cube-render-v1',status='CAPTURED',failure=None,
        pixelAcceptance='REQUIRES_INDEPENDENT_ORACLE',finallyRestored=True,
        inputSha256=ORACLE['GEOMETRY']['INPUT_SHA256'],fingerprintVersion='mi3d-snapshot-fingerprint-v1',
        geometryFingerprints=hashes,initialState=initial,restoredState=copy.deepcopy(initial),
        initialHistory=history,restoredHistory=copy.deepcopy(history),
        diagnosticBindings=diagnostic,sourceBindings=source,phases={},frames=[])
    for phase in ORACLE['PHASES']:
        section['phases'][phase]=dict(history=copy.deepcopy(history),state=state((0,0),phase))
        for index,pose in enumerate(ORACLE['POSES']):
            before=state(pose,phase);observations=[]
            for i in range(6):
                values=[dict(guid=p,id=p,value=v) for p,v in zip(ORACLE['GEOMETRY']['PARAMETERS'],pose)]
                actual=dict(artMeshId=f'mesh-{i}',sourceGuid=f'source-{i}',passEntryTimeMillis=2,
                    live=dict(updateVersion=2,parameters=values),consumed=dict(updateVersion=2,parameters=values),
                    calculated=dict(positions=before['meshes'][i]['positions'],drawOrder=500,interpolated=True,coordinateType='Canvas'))
                requested=copy.deepcopy(actual);requested['consumed']['updateVersion']=1
                observations.append(dict(requested=requested,actual=actual,lastObservation=copy.deepcopy(actual),
                    waitReason='CONSUMED',attempts=1,elapsedMillis=1))
            section['frames'].append(dict(phase=phase,index=index,pose=list(pose),stateBefore=before,stateAfter=copy.deepcopy(before),
                historyBefore=copy.deepcopy(history),historyAfter=copy.deepcopy(history),consumption=observations))
    return dict(document=dict(editorModelId='model'),cubeRender=section,
        cubeGeometry=dict(written=snapshots,meshIds=[s['artMeshId'] for s in snapshots],sources=sources,
                          samples=[dict(pose=[0,0],historyAfter=history)]))


class StateTests(unittest.TestCase):
    def test_cross_binding_positive_control(self):
        self.assertEqual(ORACLE['verify_state'](state_fixture())['frames'],27)

    def test_callback_pose_compares_java_float_values(self):
        report=state_fixture();short=[11.007778,29.268293]
        expanded=[11.00777816772461,29.268293380737305]
        report['cubeGeometry']['samples'][-1]['pose']=expanded
        for field in ('initialState','restoredState'):
            state=report['cubeRender'][field];state['pose']=list(short)
            for mesh,source in zip(state['meshes'],report['cubeGeometry']['sources']):
                mesh['positions']=ORACLE['GEOMETRY']['truth'](source,*short)
        self.assertEqual(ORACLE['verify_state'](report)['frames'],27)
        report['cubeGeometry']['samples'][-1]['pose'][0]+=.001
        with self.assertRaisesRegex(ValueError,'callback initial pose'):
            ORACLE['verify_state'](report)

    def test_explicit_unavailable_opacity_is_preserved(self):
        report=state_fixture();section=report['cubeRender']
        states=[section['initialState'],section['restoredState']]
        states += [p['state'] for p in section['phases'].values()]
        states += [f[k] for f in section['frames'] for k in ('stateBefore','stateAfter')]
        for state in states:
            for mesh in state['meshes']:
                mesh['opacity']=dict(status='UNAVAILABLE',reason='CURRENT_AUTHORED_KEYFORM_ABSENT')
        self.assertEqual(ORACLE['verify_state'](report)['frames'],27)
        section['frames'][0]['stateBefore']['meshes'][0]['opacity']['reason']='UNEXPLAINED'
        with self.assertRaises(ValueError): ORACLE['verify_state'](report)

    def test_mutated_state_rejected(self):
        def wrong_history(r): r['frames'][0]['historyAfter']['position']=1
        def wrong_snapshot(r): r['frames'][0]['stateBefore']['meshes'][0]['snapshotSha256']='0'*64
        def wrong_binding(r): r['sourceBindings'][0]['generation']=99
        def stale_consumption(r): r['frames'][0]['consumption'][0]['actual']['consumed']['updateVersion']=1
        def wrong_pose(r): r['frames'][1]['stateBefore']['pose']=[0,0]
        def wrong_culling(r):
            for field in ('stateBefore','stateAfter'): r['frames'][9][field]['meshes'][0]['culling']=False
        def wrong_recovery(r): r['restoredState']['rawImages']=[['leak','image',256,128]]
        for mutate in (wrong_history,wrong_snapshot,wrong_binding,stale_consumption,wrong_pose,wrong_culling,wrong_recovery):
            with self.subTest(mutation=mutate.__name__):
                report=state_fixture();mutate(report['cubeRender'])
                with self.assertRaises(ValueError): ORACLE['verify_state'](report)


class FingerprintTests(unittest.TestCase):
    def test_java_cross_language_vectors_and_field_mutations(self):
        classpath=os.environ.get('MI3D_RENDER_TEST_CLASSPATH')
        self.assertTrue(classpath,'compile Java sources and set MI3D_RENDER_TEST_CLASSPATH; no silent skip')
        result=subprocess.run(['java','-cp',classpath,
            'dev.turboism.validation.modelimport3d.host.SurfaceSnapshotFingerprintTest'],
            check=True,text=True,capture_output=True)
        vectors=[json.loads(line) for line in result.stdout.splitlines()]
        self.assertEqual(len(vectors),5)
        self.assertEqual(len({r['sha256'] for r in vectors}),5)
        for vector in vectors:
            self.assertEqual(ORACLE['fingerprint'](vector['snapshot']),vector['sha256'])
        base=vectors[0]['snapshot']
        mutations=[('artMeshId',None,None,'other'),('sourceGuid',None,None,'other'),
                   ('positions',0,0,17),('uvs',0,0,.25),('indices',0,None,1),
                   ('axes',0,'guid','other'),('axes',0,'id','other'),('axes',0,'minimum',-2),
                   ('axes',0,'default',.25),('axes',0,'maximum',2),('axes',0,'keys',[-1,0,.5,1]),
                   ('cells',0,'formGuid','other'),('cells',0,'coordinates',[dict(guid='轴-guid',value=1)]),
                   ('forms',0,'guid','other'),('forms',0,'drawOrder',499),
                   ('forms',0,'positions',[[17,24],*base['positions'][1:]])]
        for field,index,key,value in mutations:
            changed=copy.deepcopy(base)
            if index is None: changed[field]=value
            elif key is None: changed[field][index]=value
            else: changed[field][index][key]=value
            self.assertNotEqual(ORACLE['fingerprint'](changed),vectors[0]['sha256'],(field,key))


if __name__ == '__main__':
    unittest.main()
