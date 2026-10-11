#!/usr/bin/env python3
"""Structural capture admission tests; pixel gate is explicitly stubbed here."""
import copy
import hashlib
import json
from pathlib import Path
import runpy
import unittest

HERE=Path(__file__).resolve().parent
M=runpy.run_path(str(HERE/'verify-texture-pixels.py'))
T=runpy.run_path(str(HERE/'test-report.py'))


def fixture(padded=False):
    r=T['fixture'](True,padded);r['renderVerification']='CAPTURED_REQUIRES_INDEPENDENT_ORACLE'
    protected=dict(r['imported'],parameters=[p[:4] for p in r['imported']['parameters']]);raw=json.dumps(protected)
    digest=hashlib.sha256(raw.encode()).hexdigest();axes=[p[0] for p in r['imported']['parameters'][1:]]
    history=dict(availability='AVAILABLE',position=2,revision=2,generation=1,document='doc',manager='manager',entries=['a','b'])
    section=dict(schemaVersion='mi3d-source-texture-render-v1',status='CAPTURED',restored=True,protectedStateJson=raw,
                 protectedStateSha256=digest,canvas=[1024,1024,0,0,1],axes=axes,entryHistory=history,restoredHistory=copy.deepcopy(history),frames=[])
    r['sourceTextureRender']=section;sources,_=M['ENTRY']['G']['cube_source'](T['CUBE'])
    for index,pose in enumerate(M['POSES']):
        observations=[];requested=[]
        for i,snapshot in enumerate(r['imported']['meshes']):
            values=[['standard-guid','standard',0],['axis0',axes[0],pose[0]],['axis1',axes[1],pose[1]]]
            obs=dict(artMeshId=snapshot['artMeshId'],sourceGuid=snapshot['sourceGuid'],passEntryTimeMillis=2,
                     live=dict(version=7,values=values),consumed=dict(version=3,values=values),
                     calculated=dict(positions=M['ENTRY']['G']['truth'](sources[i],*pose),interpolated=True,coordinateType='Canvas',drawOrder=500))
            observations.append(obs);req=copy.deepcopy(obs);req['consumed']['version']=2;req['passEntryTimeMillis']=1;requested.append(req)
        section['frames'].append(dict(index=index,pose=list(pose),waitReason='CONSUMED',elapsedMillis=25,attempts=1,
             canvasBefore=[1024,1024,0,0,1],canvasAfter=[1024,1024,0,0,1],stateBeforeSha256=digest,stateAfterSha256=digest,historyBefore=history,historyAfter=history,
             requested=requested,lastObservation=observations,evaluationBefore=copy.deepcopy(observations),evaluationAfter=copy.deepcopy(observations)))
    return r


class CaptureTest(unittest.TestCase):
    def test_padded_state_gate_retains_original_pixel_inputs(self):
        calls=[];g=M['verify_capture'].__globals__;original=g['verify_frames']
        def stub(frames,cube,texture,reader):
            self.assertEqual(cube,T['CUBE']);self.assertEqual(texture,T['TEXTURE'])
            calls.append(True);return {'status':'STUBBED_PIXEL_GATE_FOR_STATE_TEST_ONLY'}
        g['verify_frames']=stub
        try:
            r=fixture(True)
            M['verify_capture'](r,T['CUBE'],T['TEXTURE'],'run',None,padded=True)
            with self.assertRaises(ValueError):M['verify_capture'](r,T['CUBE'],T['TEXTURE'],'run',None)
            self.assertEqual(calls,[True])
        finally:g['verify_frames']=original

    def test_state_gate_and_adversaries(self):
        calls=[];globals_=M['verify_capture'].__globals__;original=globals_['verify_frames']
        globals_['verify_frames']=lambda *a: calls.append(True) or {'status':'STUBBED_PIXEL_GATE_FOR_STATE_TEST_ONLY'}
        try:
            r=fixture();M['verify_capture'](r,T['CUBE'],T['TEXTURE'],'run',None);self.assertEqual(len(calls),1)
            def stale(r):r['sourceTextureRender']['frames'][0]['requested'][0]['consumed']['version']=3
            def geometry(r):r['sourceTextureRender']['frames'][0]['lastObservation'][0]['calculated']['positions'][0][0]+=1
            def authored(r):r['sourceTextureRender']['frames'][0]['stateAfterSha256']='changed'
            def pose(r):r['sourceTextureRender']['frames'][0]['lastObservation'][0]['live']['values'][1][2]=21.9
            def restore(r):r['sourceTextureRender']['restoredHistory']['position']=1
            def timeout(r):r['sourceTextureRender']['frames'][0]['elapsedMillis']=15000
            def attempts(r):r['sourceTextureRender']['frames'][0]['attempts']=0
            def missing_id(r):r['sourceTextureRender']['entryHistory']['entries'][0]=None
            def duplicate_id(r):r['sourceTextureRender']['entryHistory']['entries'][1]='a'
            for mutate in (stale,geometry,authored,pose,restore,timeout,attempts,missing_id,duplicate_id):
                v=copy.deepcopy(r);mutate(v)
                with self.subTest(mutation=mutate.__name__),self.assertRaises(ValueError):M['verify_capture'](v,T['CUBE'],T['TEXTURE'],'run',None)
            self.assertEqual(len(calls),1)
        finally:globals_['verify_frames']=original


if __name__=='__main__':unittest.main()
