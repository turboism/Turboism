#!/usr/bin/env python3
"""Offline mutations of an already-admitted atlas capture; no new native acceptance."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import runpy
import tarfile
import unittest
from unittest.mock import patch

M=runpy.run_path(str(Path(__file__).with_name('verify-atlas-persistence.py')))
p=argparse.ArgumentParser();p.add_argument('--archive',required=True,type=Path);p.add_argument('--cube',required=True,type=Path)
args,rest=p.parse_known_args()
with tarfile.open(args.archive) as t:
    report=json.load(t.extractfile('state/dev.turboism.validation.model-import-3d/report.json'))
s=report['cubeAtlas'];raw=s['initial'];layout=copy.deepcopy(s['secondLayoutAfter'])
layout.pop('documentId');layout.pop('modelId')
capture=dict(cube=s['redone'],atlas=s['repackedAtlases'][0],layout=layout)


class AtlasPersistenceTest(unittest.TestCase):
    def test_original_admitted_packed_capture(self):
        self.assertEqual(M['packed'](capture,raw,args.cube)['forms'],12150)

    def test_mutations(self):
        def uv(c):c['cube']['snapshots'][0]['uvs'][0][0]+=.01
        def geometry(c):c['cube']['snapshots'][0]['forms'][100]['positions'][0][0]+=1
        def raw_binding(c):c['cube']['state']['meshes'][0]['input']['kind']='MODEL_IMAGE'
        def guid(c):c['atlas'][0]='other'
        def page_name(c):c['layout']['atlasId']=c['atlas'][0]
        def placement(c):c['layout']['plan']['placements'][0][2]-=1
        def rotated(c):c['layout']['plan']['placements'][0][-1]=True
        def frame_source(c):c['cube']['state']['parameters'][0][3]=123
        for mutate in (uv,geometry,raw_binding,guid,page_name,placement,rotated,frame_source):
            c=copy.deepcopy(capture);mutate(c)
            # Keep producer snapshot hashes self-consistent; the independent
            # source/UV transform and whole-state comparisons must still reject.
            for row,snapshot in zip(c['cube']['state']['meshes'],c['cube']['snapshots']):
                row['snapshotSha256']=M['R']['fingerprint'](snapshot)
            with self.subTest(mutation=mutate.__name__),self.assertRaises(ValueError):M['packed'](c,raw,args.cube)

    def test_pair_rejects_same_process_and_wrong_seed_before_pixels(self):
        h=s['redoHistory'];common=dict(schemaVersion='mi3d-cube-atlas-persistence-v1',status='PASS',failure=None,
            renderStatus='CAPTURED',inputSha256=M['C']['INPUT_SHA256'],historyBefore=h,historyAfter=h)
        a=dict(common,mode='atlas-save',process=dict(pid=1,startEpochMillis=1),artifact='persistence/mi3d-cube-model.cmo3',
            artifactBytes=4,artifactSha256=hashlib.sha256(b'test').hexdigest())
        b=dict(common,mode='atlas-reopen',process=dict(pid=1,startEpochMillis=1))
        with self.assertRaisesRegex(ValueError,'fresh process'):
            M['pair'](a,b,b'test',a['artifactSha256'],a['artifactSha256'],raw,args.cube,{}, {})
        b['process']=dict(pid=2,startEpochMillis=2)
        with self.assertRaisesRegex(ValueError,'exact saved file'):
            M['pair'](a,b,b'test','wrong',a['artifactSha256'],raw,args.cube,{}, {})

    def test_leg_wiring_and_admitted_pair_with_stubbed_raster_only(self):
        # Synthetic leg protocol built from admitted authored state. Pixel
        # verification is covered independently; this test proves wiring and
        # rejection order, not a real save/reopen or new raster acceptance.
        h=s['redoHistory'];sha=hashlib.sha256(b'test').hexdigest()
        common=dict(schemaVersion='mi3d-cube-atlas-persistence-v1',status='PASS',failure=None,
            renderStatus='CAPTURED',inputSha256=M['C']['INPUT_SHA256'],historyBefore=h,historyAfter=h)
        a=copy.deepcopy(report);a.update(mi3dMode='cube-atlas-save',runId='save-test',cubeExport={'status':'NOT_RUN'})
        b=copy.deepcopy(a);b.update(mi3dMode='cube-atlas-reopen',runId='reopen-test',cubeAtlas={'status':'NOT_RUN'})
        left=dict(common,mode='atlas-save',process=dict(pid=1,startEpochMillis=1),beforeSave=capture,afterSave=capture,
            artifact='persistence/mi3d-cube-model.cmo3',artifactBytes=4,artifactSha256=sha)
        right=dict(common,mode='atlas-reopen',process=dict(pid=2,startEpochMillis=2),reopened=capture,
            afterDisplay=capture,afterObservation=capture)
        a['cubeAtlasPersistence']=left;b['cubeAtlasPersistence']=right
        af={f'cube-persistence-render/{phase}-{i}.png':b'png' for phase in
            ('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone') for i in range(9)};bf={}
        for section,phase,key,frames in ((left,'atlas-before-save','beforeSaveFrames',af),
                (left,'atlas-after-save','afterSaveFrames',af),(right,'atlas-reopen','reopenFrames',bf)):
            section[key]=[];section['renderProgress-'+phase]=dict(stage='complete',entryState=capture['cube']['state'],
                restoredState=capture['cube']['state'],entryHistory=h,restoredHistory=h)
            frames.update({f'cube-persistence-render/{phase}-{i}.png':b'png' for i in range(9)})
        with patch.dict(M['R'],verify_persistence_frames=lambda *args:None,PNG=lambda data:data):
            self.assertEqual(M['report_leg'](a,'save-test','cube-atlas-save',args.cube,af,b'test')['frames'],54)
            self.assertEqual(M['report_leg'](b,'reopen-test','cube-atlas-reopen',args.cube,bf,raw_reference=raw)['frames'],9)
            with self.assertRaisesRegex(ValueError,'admitted save reference'):
                M['report_leg'](b,'reopen-test','cube-atlas-reopen',args.cube,bf)
            with self.assertRaisesRegex(ValueError,'saved artifact'):
                M['report_leg'](a,'save-test','cube-atlas-save',args.cube,af,b'wrong')
            with self.assertRaisesRegex(ValueError,'frame inventory'):
                M['report_leg'](b,'reopen-test','cube-atlas-reopen',args.cube,{},raw_reference=raw)
            bad=copy.deepcopy(b);bad['cubeAtlasPersistence']['afterDisplay']['layout']['atlasId']='changed'
            with self.assertRaises(ValueError):M['report_leg'](bad,'reopen-test','cube-atlas-reopen',args.cube,bf,raw_reference=raw)
            def admitted(r,frames,j):return dict(mode=r['mi3dMode'],report=r,cmo3=b'test' if j==1 else None,
                cubePersistenceFrames=frames,lifecycle=dict(jobId=str(j),attemptId=str(j),runId=r['runId'],
                    details=dict(sourceFixtureBeforeSha256=sha,fixtureBeforeSha256=sha,
                        postContainmentChecks=dict(fixtureSourceSha256=sha,fixtureCopySha256=sha))))
            ar,br=admitted(a,af,1),admitted(b,bf,2)
            self.assertEqual(M['admitted_pair'](ar,br,args.cube)['status'],'EXACT_HOST_ATLAS_PERSISTENCE_PAIR_PASS')
            br['lifecycle']['attemptId']='1'
            with self.assertRaisesRegex(ValueError,'distinct job'):M['admitted_pair'](ar,br,args.cube)


if __name__=='__main__':unittest.main(argv=['test-atlas-persistence.py',*rest])
