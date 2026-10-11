#!/usr/bin/env python3
import json
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'preview'))
import host_validation_official_core as core
from host_validation_queue import QueueError,atomic_json,file_digest


class CoreQueueTest(unittest.TestCase):
    def test_large_xyz_launcher_allowance_keeps_other_profiles_bounded(self):
        self.assertEqual(core.launcher_timeout({'captureProfile':'apple-xyz-140-v1'}),600)
        self.assertEqual(core.launcher_timeout({'captureProfile':'apple-derived-xyz-140-v1'}),600)
        for profile in ('cube-377-v1','apple-11-v1','apple-domain-353-v1','surface-dense-49-v1'):
            self.assertEqual(core.launcher_timeout({'captureProfile':profile}),180)

    def test_shell_entrypoints_parse(self):
        for name in ('run-cubism-host-validation.sh','run-official-core-host-validation.sh'):
            subprocess.run(['bash','-n',str(Path(core.__file__).with_name(name))],check=True)

    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.argv=['--name','official-core','--version','5302','--transport','local','--run-label','test']
        for flag,name in [('--bundle-root','input'),('--golden-prefix','golden'),('--host-root','host'),('--proton-wrapper','wrapper'),('--proton-runner','runner')]:self.argv.extend([flag,str(self.root/name)])
        self.o=core.options(self.argv);self.run='queue-'+'a'*32;self.task=core.task_path(self.o,self.run)
        self.job=dict(job_id='job',attempt_id='attempt',run_id=self.run,digest='digest')
        self.identity=dict(jobId='job',attemptId='attempt',runId=self.run,preparedDigest='digest')
        self.descriptor=dict(argv=self.argv,hostDependencies=[])
        self.proof=dict(schemaVersion=1,cleanup='safe',cgroupPath='/test-scope',kernelProof=dict(cgroupKillWritten=False),**self.identity)
        self.pre=dict(schemaVersion=1,normalExit=True,**self.identity,details=dict(cleanupOwner='supervisor',taskDir=str(self.task),goldenJarBefore='gold',javaLauncherExit=0,gracefulDrain=True))
        self.meta=dict(mocSha256='moc',coreSha256='core',recorderSha256='recorder')
        (self.task/'capture').mkdir(parents=True)
        atomic_json(self.task/'process-drain.json',dict(schemaVersion=1,**self.identity,
            cgroupPath='/test-scope',drained=True,signalsSent=False,samples=[dict(processes=[]),dict(processes=[])]))
        atomic_json(self.task/'launch.json',dict(argv=core.launch_argv(self.o,self.task),identity=self.identity))
        with patch.object(core,'bundle',return_value=self.meta):
            (self.task/'core-launch.cmd').write_bytes(core.launch_script(self.task))
        self.capture=dict(schemaVersion='mi3d-official-core-capture-v2',status='CAPTURED_NOT_VERIFIED',mocSha256='moc',
            poseProtocol='mi3d-cube-geometry-377-v1',frames=[{}]*377,pid=42,processStart='2026-10-01T00:00:00Z',
            coreOrigin=dict(sha256='core',path=core.windows(self.task/'bundle/Live2DCubismCore.jar')),
            recorderOrigin=dict(sha256='recorder',path=core.windows(self.task/'bundle/official-core-capture.jar')),
            classpath=core.windows(self.task/'bundle/official-core-capture.jar')+';'+core.windows(self.task/'bundle/Live2DCubismCore.jar'),
            jvmArguments=['-Djava.library.path='+core.windows(self.task/'bundle/native')])
        atomic_json(self.task/'capture/core-capture.json',self.capture)

    def finish(self,pre=None,proof=None):
        with patch.object(core,'bundle',return_value=self.meta),patch.object(core,'official_runtime'),patch.object(core,'file_digest',return_value='gold'):
            return core.finalize(self.job,self.descriptor,self.root/'prepared',self.root/'job',pre or self.pre,proof or self.proof,0)

    def test_exact_options_and_launch(self):
        self.assertEqual(core.options(self.argv)['--name'],'official-core')
        command=core.launch_argv(self.o,self.task)
        with patch.object(core,'bundle',return_value=self.meta):
            self.assertIn(b'dev.modelimport.validation.OfficialCoreCapture',core.launch_script(self.task))
        self.assertTrue(command[6].endswith('/windows/system32/cmd.exe'))
        with self.assertRaises(QueueError):core.launch_script(self.root/'bad%path')
        self.assertFalse(any('-javaagent' in a or 'Live2D_Cubism.jar' in a for a in command))
        for extra in (['--agent','/tmp/agent.jar'],['--remote-pre-launch','/tmp/hook'],['--version','5302'],['--unknown','x']):
            with self.assertRaises(QueueError):core.options(self.argv+extra)
        bad=self.argv.copy();bad[bad.index('5302')]='5303'
        with self.assertRaises(QueueError):core.options(bad)

    def test_apple_profile_requires_exact_capture_protocol(self):
        self.meta.update(captureProfile='apple-11-v1',axes=['Param3DYaw394e4cf740f3','Param3DPitch394e4cf740f3'])
        with patch.object(core,'bundle',return_value=self.meta):
            script=core.launch_script(self.task)
        self.assertIn(b'apple-11-v1',script)
        self.assertIn(b'Param3DYaw394e4cf740f3',script)
        (self.task/'core-launch.cmd').write_bytes(script)
        self.assertEqual(self.finish()['terminalState'],'failed')
        self.capture.update(schemaVersion='mi3d-apple-official-core-capture-v1',poseProtocol='mi3d-apple-geometry-11-v1',frames=[{}]*11)
        atomic_json(self.task/'capture/core-capture.json',self.capture)
        self.assertEqual(self.finish()['terminalState'],'succeeded')
        for change in ({'frames':[{}]*10},{'poseProtocol':'mi3d-cube-geometry-377-v1'},{'schemaVersion':'mi3d-official-core-capture-v2'}):
            atomic_json(self.task/'capture/core-capture.json',{**self.capture,**change})
            self.assertEqual(self.finish()['terminalState'],'failed')

    def test_xyz_profile_requires_three_axes_and_complete_compressed_capture(self):
        self.meta.update(captureProfile='apple-xyz-140-v1',axes=['Param3DYaw394e4cf740f3','Param3DPitch394e4cf740f3','Param3DRoll394e4cf740f3'])
        with patch.object(core,'bundle',return_value=self.meta):script=core.launch_script(self.task)
        self.assertIn(b'Param3DRoll394e4cf740f3',script);self.assertIn(b'apple-xyz-140-v1',script)
        (self.task/'core-launch.cmd').write_bytes(script)
        atomic_json(self.task/'launch.json',dict(argv=core.launch_argv(self.o,self.task),identity=self.identity,
            display=':123',xvfbPid=123,xvfbSha256='gold'))
        self.pre['details']['displayStopped']=True
        self.assertEqual(self.finish()['terminalState'],'failed')
        (self.task/'capture/positions-f32.bin.gz').write_bytes(b'fixture')
        self.capture.update(schemaVersion='mi3d-apple-xyz-official-core-capture-v1',poseProtocol='mi3d-apple-xyz-140-v1',frames=[{}]*140,axes=self.meta['axes'],baselineDrawables=[{}]*98,
            coordinates=dict(artifact='positions-f32.bin.gz',verticesPerFrame=100258,uncompressedBytes=16+140*100258*8,bytes=7,sha256='gold'))
        atomic_json(self.task/'capture/core-capture.json',self.capture)
        self.assertEqual(self.finish()['terminalState'],'succeeded')
        for change in ({'frames':[{}]*139},{'axes':self.meta['axes'][:2]},{'poseProtocol':'mi3d-apple-layer-domain-353-v1'}):
            atomic_json(self.task/'capture/core-capture.json',{**self.capture,**change})
            self.assertEqual(self.finish()['terminalState'],'failed')

    def test_derived_xyz_profile_keeps_distinct_protocol_and_exact_slots(self):
        self.meta.update(captureProfile='apple-derived-xyz-140-v1',axes=['Param3DYaw394e4cf740f3','Param3DPitch394e4cf740f3','Param3DRoll394e4cf740f3'])
        with patch.object(core,'bundle',return_value=self.meta):script=core.launch_script(self.task)
        self.assertIn(b'Param3DRoll394e4cf740f3',script);self.assertIn(b'apple-derived-xyz-140-v1',script)
        (self.task/'core-launch.cmd').write_bytes(script)
        atomic_json(self.task/'launch.json',dict(argv=core.launch_argv(self.o,self.task),identity=self.identity,
            display=':123',xvfbPid=123,xvfbSha256='gold'))
        self.pre['details']['displayStopped']=True
        self.assertEqual(self.finish()['terminalState'],'failed')
        (self.task/'capture/positions-f32.bin.gz').write_bytes(b'fixture')
        self.capture.update(schemaVersion='mi3d-apple-derived-xyz-official-core-capture-v1',poseProtocol='mi3d-apple-derived-xyz-140-v1',frames=[{}]*140,axes=self.meta['axes'],baselineDrawables=[{}]*98,
            coordinates=dict(artifact='positions-f32.bin.gz',verticesPerFrame=80514,uncompressedBytes=16+140*80514*8,bytes=7,sha256='gold'))
        atomic_json(self.task/'capture/core-capture.json',self.capture)
        self.assertEqual(self.finish()['terminalState'],'succeeded')
        for change in ({'coordinates':{**self.capture['coordinates'],'verticesPerFrame':100258}}, {'schemaVersion':'mi3d-apple-xyz-official-core-capture-v1'}, {'frames':[{}]*139},{'axes':self.meta['axes'][:2]},{'poseProtocol':'mi3d-apple-layer-domain-353-v1'}):
            atomic_json(self.task/'capture/core-capture.json',{**self.capture,**change})
            self.assertEqual(self.finish()['terminalState'],'failed')

    def test_domain_profile_has_distinct_launch(self):
        self.meta.update(captureProfile='apple-domain-353-v1',axes=['Param3DYaw394e4cf740f3','Param3DPitch394e4cf740f3'])
        with patch.object(core,'bundle',return_value=self.meta):
            script=core.launch_script(self.task)
        self.assertIn(b'apple-domain-353-v1',script)
        self.assertNotIn(b'apple-11-v1',script)

    def test_surface_dense_profile_requires_exact_protocol_inventory_and_axes(self):
        axes=['Param3DYaw394e4cf740f3','Param3DPitch394e4cf740f3']
        self.meta.update(captureProfile='surface-dense-49-v1',axes=axes)
        with patch.object(core,'bundle',return_value=self.meta):
            script=core.launch_script(self.task)
        self.assertIn(b'surface-dense-49-v1',script)
        self.assertIn(axes[0].encode(),script)
        self.assertNotIn(b'ParamMI3DCubeYaw',script)
        (self.task/'core-launch.cmd').write_bytes(script)
        self.assertEqual(self.finish()['terminalState'],'failed')
        self.capture.update(schemaVersion='mi3d-surface-official-core-capture-v1',
                            poseProtocol='mi3d-surface-dense-9921-v1',frames=[{}]*9921,axes=axes)
        atomic_json(self.task/'capture/core-capture.json',self.capture)
        self.assertEqual(self.finish()['terminalState'],'succeeded')
        for change in ({'frames':[{}]*9920},{'frames':[{}]*9922},{'axes':list(reversed(axes))},
                       {'poseProtocol':'mi3d-apple-layer-domain-353-v1'},
                       {'schemaVersion':'mi3d-apple-official-core-capture-v1'}):
            atomic_json(self.task/'capture/core-capture.json',{**self.capture,**change})
            self.assertEqual(self.finish()['terminalState'],'failed')
        atomic_json(self.task/'capture/core-capture.json',self.capture)
        (self.task/'capture/unexpected.bin').write_bytes(b'extra')
        self.assertEqual(self.finish()['terminalState'],'failed')

    def test_admission_precedes_task_side_effects(self):
        with patch.object(core,'validate_admission',side_effect=QueueError('denied')),patch.object(core,'bundle') as bundle:
            with self.assertRaises(QueueError):core.run(self.argv+['--local-evidence-dir',str(self.root/'evidence')])
            bundle.assert_not_called()
        self.assertFalse((self.root/'evidence').exists())

    def test_natural_drain_waits_for_two_empty_samples(self):
        destination=self.root/'drain.json'
        with patch.object(core,'remaining_processes',side_effect=[[{'pid':12}],[],[{'pid':13}],[],[]]):
            self.assertTrue(core.wait_for_task_exit({'cgroupPath':'/test'},destination,self.identity,timeout=1,poll=.001))
        data=json.loads(destination.read_text())
        self.assertEqual(len(data['samples']),5)
        self.assertFalse(data['signalsSent'])

    def test_natural_drain_timeout_preserves_residual_identity(self):
        destination=self.root/'drain.json'
        with patch.object(core,'remaining_processes',return_value=[{'pid':12,'startTicks':'34'}]):
            self.assertFalse(core.wait_for_task_exit({'cgroupPath':'/test'},destination,self.identity,timeout=.01,poll=.001))
        data=json.loads(destination.read_text())
        self.assertFalse(data['drained']);self.assertFalse(data['signalsSent'])
        self.assertEqual(data['samples'][-1]['processes'][0]['startTicks'],'34')

    def test_missing_or_forged_drain_cannot_pass(self):
        p=self.task/'process-drain.json';original=json.loads(p.read_text())
        for change in ({'drained':False},{'signalsSent':True},{'attemptId':'another'},{'cgroupPath':'/other'},
                       {'samples':[dict(processes=[]),dict(processes=[{'pid':12}])]}):
            atomic_json(p,{**original,**change})
            self.assertEqual(self.finish()['terminalState'],'failed')
        p.unlink()
        self.assertEqual(self.finish()['terminalState'],'failed')

    def test_success_is_capture_only_and_supervisor_owned(self):
        result=self.finish()
        self.assertEqual(result['terminalState'],'succeeded')
        self.assertEqual(result['details']['postContainmentChecks']['terminalResult']['scope'],'STANDALONE_CORE_CAPTURE_ONLY')
        self.assertTrue((self.root/'job/evidence/standalone-core/capture/core-capture.json').is_file())

    def test_bad_proof_cannot_finalize(self):
        for change in ({'cleanup':'unknown'},{'attemptId':'different'}):
            with self.assertRaises(QueueError):self.finish(proof={**self.proof,**change})

    def test_kill_and_wrong_capture_never_pass(self):
        result=self.finish(proof={**self.proof,'kernelProof':{'cgroupKillWritten':True}})
        self.assertEqual(result['terminalState'],'failed')
        for key,value in [('mocSha256','other'),('classpath','C:\\agent.jar'),('jvmArguments',['-javaagent:x'])]:
            altered={**self.capture,key:value};atomic_json(self.task/'capture/core-capture.json',altered)
            self.assertEqual(self.finish()['terminalState'],'failed')

    def test_real_file_finalizer_rechecks_capture_and_runtime(self):
        import shutil
        source=self.root/'input';source.mkdir()
        data={'Live2DCubismCore.jar':b'core','official-core-capture.jar':b'recorder','model.moc3':b'MOC3',
              'texture-0.png':b'png','jre/bin/java.exe':b'java','native/Live2DCubismCoreJNI.dll':b'jni',
              'native/vcruntime140.dll':b'vcrt'}
        files={}
        for name,raw in data.items():
            f=source/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(raw)
            files[name]=dict(bytes=len(raw),sha256=file_digest(f))
        meta=dict(schemaVersion='mi3d-official-core-bundle-v1',status='FROZEN_NOT_EXECUTED',files=files,
                  coreSha256=files['Live2DCubismCore.jar']['sha256'],mocSha256=files['model.moc3']['sha256'],
                  recorderSha256=files['official-core-capture.jar']['sha256'])
        atomic_json(source/'bundle.json',meta);shutil.copytree(source,self.task/'bundle')
        app=self.root/'golden/pfx/drive_c/Program Files/Live2D Cubism 5.3/app'
        (app/'lib').mkdir(parents=True);(app/'lib/Live2D_Cubism.jar').write_bytes(b'editor')
        shutil.copytree(source/'jre',app/'jre');shutil.copytree(source/'native',app/'dll64')
        editor_hash=file_digest(app/'lib/Live2D_Cubism.jar');self.pre['details']['goldenJarBefore']=editor_hash
        self.capture['mocSha256']=meta['mocSha256'];self.capture['coreOrigin']['sha256']=meta['coreSha256']
        self.capture['recorderOrigin']['sha256']=meta['recorderSha256']
        atomic_json(self.task/'capture/core-capture.json',self.capture)
        def actual():
            return core.finalize(self.job,self.descriptor,self.root/'prepared',self.root/'job',self.pre,self.proof,0)
        with patch.object(core,'CORE_SHA',meta['coreSha256']),patch.object(core,'EDITOR_SHA',editor_hash):
            self.assertEqual(actual()['terminalState'],'succeeded')
            (self.task/'bundle/native/Live2DCubismCoreJNI.dll').write_bytes(b'tampered')
            self.assertEqual(actual()['terminalState'],'failed')

    def test_real_frozen_bundle_protocol(self):
        p=Path(__file__).resolve().parents[2]/'build/model-import-3d-offline/official-core-bundle-r1-20261001'
        if not p.exists():self.skipTest('optional exact local bundle absent')
        m=core.bundle(p);self.assertEqual(len(m['files']),323)


if __name__=='__main__':unittest.main()
