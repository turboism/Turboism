"""Archive dispatch only; native semantics and lifecycle deliberately substituted."""
import io,json,tarfile,unittest
from unittest.mock import Mock,patch
import test_surface_render_admission as surface
V=surface.V

class AtlasArchiveTest(unittest.TestCase):
    tearDown=surface.ArchiveTest.tearDown
    def setUp(self):
        surface.ArchiveTest.setUp(self)
        self.build.update(inputStageVersion='mi3d-surface-render-v2',
            persistenceTextureSha256='dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859',
            verifiedPersistenceTexturePath=str(self.root/'source.glb'),verifiedCubeInputPath=str(self.root/'source.glb'))
    def archive(self,mode,mutate=None):
        phases=('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone','atlas-before-save','atlas-after-save') if mode=='cube-atlas-save' else ('atlas-reopen',)
        # No planar history payload: these modes must reach their own oracle.
        entries=[(V.SECTION_PREFIX+'report.json',json.dumps(dict(mi3dMode=mode)).encode())]
        entries += [(V.SECTION_PREFIX+f'cube-persistence-render/{p}-{i}.png',b'synthetic pixel') for p in phases for i in range(9)]
        if mode=='cube-atlas-save':entries.append((V.SECTION_PREFIX+'persistence/mi3d-cube-model.cmo3',b'synthetic cmo3'))
        if mutate:mutate(entries)
        with tarfile.open(self.e/'final-task/turboism-home-logs-state.tar','w') as tar:
            for name,data in entries:
                member=tarfile.TarInfo(name);member.size=len(data);tar.addfile(member,io.BytesIO(data))
        oracle=Mock()
        with patch.object(V,'verify_lifecycle'),patch.dict(V.EVALUATION,verify_console=lambda _:None),patch.dict(V.ATLAS_PERSISTENCE,report_leg=oracle):
            result=V.job_result(self.e,'synthetic',self.build,mode,atlas_reference={'admitted':'reference'})
        oracle.assert_called_once()
        args=oracle.call_args.args
        self.assertEqual(args[2],mode);self.assertEqual(len(args[4]),len(phases)*9)
        self.assertEqual(args[5],b'synthetic cmo3' if mode=='cube-atlas-save' else None)
        self.assertEqual(args[6],{'admitted':'reference'})
        self.assertEqual(oracle.call_args.kwargs,dict(texture=str(self.root/'source.glb'),padded=True))
        return result
    def test_atlas_modes_reach_own_oracle(self):
        for mode in ('cube-atlas-save','cube-atlas-reopen'):self.assertEqual(self.archive(mode)['mode'],mode)
    def test_missing_saved_artifact_rejected(self):
        with self.assertRaisesRegex(ValueError,'artifact inventory'):self.archive('cube-atlas-save',lambda e:e.pop())
    def test_missing_reopen_frame_rejected(self):
        with self.assertRaisesRegex(ValueError,'PNG inventory'):self.archive('cube-atlas-reopen',lambda e:e.pop())
    def test_old_stage_rejected(self):
        self.build['inputStageVersion']='mi3d-surface-render-v1'
        for mode in ('cube-atlas-save','cube-atlas-reopen'):
            with self.assertRaisesRegex(ValueError,'frozen source texture'):self.archive(mode)
    def test_wrong_source_rejected(self):
        self.build['persistenceTextureSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'frozen source texture'):self.archive('cube-atlas-save')
    def test_missing_texture_path_rejected(self):
        del self.build['verifiedPersistenceTexturePath']
        with self.assertRaisesRegex(ValueError,'frozen source texture'):self.archive('cube-atlas-reopen')

if __name__=='__main__':unittest.main()
