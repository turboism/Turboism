"""Outer archive dispatch tests; lifecycle and semantic oracles substituted explicitly."""
from pathlib import Path
import importlib.util,io,json,tarfile,tempfile,unittest
from unittest.mock import patch
P=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('surface_admission',P/'verify-host.py')
V=importlib.util.module_from_spec(spec);spec.loader.exec_module(V)
class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.e=self.root/'evidence';(self.e/'final-task').mkdir(parents=True)
        self.bound={k:'synthetic' for k in ('jobId','attemptId','runId','preparedDigest','scopeUnit','cgroupPath','cgroupDevice','cgroupInode','bootId')}
        (self.e/'lifecycle-result.json').write_text(json.dumps({'containment':self.bound}))
        (self.root/'containment.json').write_text(json.dumps(self.bound));(self.e/'final-task/cubism-console.txt').write_text('synthetic')
        src=self.root/'source.glb';src.write_bytes(b'synthetic fixture')
        self.build=dict(inputStageVersion='mi3d-surface-render-v1',verifiedPlanarInputPaths={'plane.glb':str(src),'plane-seam.glb':str(src)})
    def tearDown(self):self.tmp.cleanup()
    def run_archive(self,mode='plane-render',mutation=None,protocol='POSE_DRIVER_UNDO_V1'):
        report=dict(mi3dMode=mode,planarGeometry=dict(capture=dict(historyProtocol=protocol),pixels={'synthetic':True}))
        entries=[(V.SECTION_PREFIX+'report.json',json.dumps(report).encode())]+[(V.SECTION_PREFIX+f'planar-render/{i}.png',str(i).encode()) for i in range(9)]
        if mutation:mutation(entries)
        with tarfile.open(self.e/'final-task/turboism-home-logs-state.tar','w') as tar:
            for name,data in entries:
                member=tarfile.TarInfo(name)
                if data is None:member.type=tarfile.SYMTYPE;member.linkname='foreign';tar.addfile(member)
                else:member.size=len(data);tar.addfile(member,io.BytesIO(data))
        calls=[]
        def semantics(*args,**kw):calls.append(('geometry',kw['expected_mode']))
        def pixels(section,geometry,raw,reader):
            self.assertEqual([reader(f'planar-render/{i}.png') for i in range(9)],[str(i).encode() for i in range(9)])
            calls.append(('pixels',9))
        with patch.object(V,'verify_lifecycle'),patch.dict(V.EVALUATION,verify_console=lambda _:None),patch.object(V.runpy,'run_path',return_value={'verify_planar_report':semantics,'verify_frames':pixels}):
            result=V.job_result(self.e,'synthetic',self.build,mode)
        self.assertEqual(calls,[('geometry',mode),('pixels',9)]);return result
    def test_modes_dispatch_exact_nine(self):
        for mode in ('plane-render','plane-seam-render'):self.assertEqual(self.run_archive(mode)['mode'],mode)
    def test_v2_requires_return_protocol(self):
        self.build['inputStageVersion']='mi3d-surface-render-v2'
        self.assertEqual(self.run_archive(protocol='POSE_DRIVER_UNDO_RETURN_V2')['mode'],'plane-render')
        with self.assertRaisesRegex(ValueError,'protocol'):self.run_archive()
    def test_v1_rejects_return_protocol(self):
        with self.assertRaisesRegex(ValueError,'protocol'):self.run_archive(protocol='POSE_DRIVER_UNDO_RETURN_V2')
    def test_missing_frame(self):
        with self.assertRaisesRegex(ValueError,'inventory'):self.run_archive(mutation=lambda e:e.pop())
    def test_extra_frame(self):
        with self.assertRaisesRegex(ValueError,'inventory'):self.run_archive(mutation=lambda e:e.append((V.SECTION_PREFIX+'planar-render/9.png',b'x')))
    def test_duplicate_frame(self):
        with self.assertRaisesRegex(ValueError,'duplicate'):self.run_archive(mutation=lambda e:e.append(e[-1]))
    def test_link_frame(self):
        with self.assertRaisesRegex(ValueError,'link'):self.run_archive(mutation=lambda e:e.__setitem__(-1,(e[-1][0],None)))
    def test_wrong_frame_namespace(self):
        with self.assertRaisesRegex(ValueError,'inventory'):self.run_archive(mutation=lambda e:e.__setitem__(-1,(V.SECTION_PREFIX+'sheet-render/8.png',b'x')))
    def test_history_marker_required(self):
        with self.assertRaisesRegex(ValueError,'protocol'):self.run_archive(protocol=None)
    def test_geometry_stage_cannot_render(self):
        self.build['inputStageVersion']='mi3d-pose-drive-geometry-v1'
        with self.assertRaisesRegex(ValueError,'geometry only'):self.run_archive()
    def test_surface_stage_cannot_save(self):
        with self.assertRaisesRegex(ValueError,'only frozen'):V.job_result(self.e,'synthetic',self.build,'cube-persistence-save')
if __name__=='__main__':unittest.main()
