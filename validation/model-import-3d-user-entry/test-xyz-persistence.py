"""Synthetic rejection tests for the persistence pair; not native acceptance."""
import copy
import hashlib
from pathlib import Path
import runpy
import unittest
O=runpy.run_path(str(Path(__file__).with_name('verify-xyz-persistence.py')))

class PairTest(unittest.TestCase):
    def pair(self):
        artifact=b'native-test-artifact'
        stored=dict(forms=['full bank'],parameters=['XYZ'],images=['source'])
        frames=[dict(poseXYZ=list(p),sha256='image',evaluated=[dict(id=str(i),order=i,positions=[[1,2],[3,4],[5,6]]) for i in range(6)]) for p in O['O']['POSES']]
        a=dict(schemaVersion='mi3d-cube-xyz360-v1',status='PASS',runId='queue-save',inputSha256='source',axes=dict(X='x',Y='y',Z='z'),imported=stored,frames=frames,
            persistenceSave=dict(commandStatus='EXECUTED',historyUnchanged=True,artifact='persistence/mi3d-xyz-model.cmo3',artifactBytes=len(artifact),artifactSha256=hashlib.sha256(artifact).hexdigest(),before=stored,after=stored))
        b=copy.deepcopy(a);b.update(schemaVersion='mi3d-cube-xyz360-reopen-v1',runId='queue-reopen',scope='OBSERVE_REOPENED_XYZ_SEED_ONLY')
        return a,b,artifact
    def test_complete_pair(self):
        self.assertEqual(12,len(O['paired'](*self.pair())['frames']))
    def test_reject_session_reuse_form_loss_and_geometry_corruption(self):
        for change in ('process','bank','geometry','order','axis','status','count','target'):
            a,b,data=self.pair()
            if change=='process':b['runId']=a['runId']
            if change=='bank':b['imported']['forms']=[]
            if change=='geometry':b['frames'][10]['evaluated'][0]['positions'][0][0]+=.1
            if change=='order':b['frames'][10]['evaluated'][0]['order']+=1
            if change=='axis':b['axes']['Z']='yaw'
            if change=='status':b['status']='FAIL'
            if change=='count':b['frames'].pop()
            if change=='target':a['persistenceSave']['artifact']='foreign.cmo3'
            with self.subTest(change=change),self.assertRaises(ValueError):O['paired'](a,b,data)
        a,b,data=self.pair()
        with self.assertRaises(ValueError):O['paired'](a,b,data+b'changed')

if __name__=='__main__':unittest.main()
