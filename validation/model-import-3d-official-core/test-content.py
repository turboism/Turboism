#!/usr/bin/env python3
"""Offline artifact-join adversaries; native process trust is deliberately separate."""
import copy
from pathlib import Path
import runpy
import unittest

M=runpy.run_path(str(Path(__file__).with_name('verify-content.py')))


def fixture():
    artifacts={'model.moc3':b'MOC3'+bytes(60),'texture-0.png':b'fixture-only'}
    hashes={n:dict(bytes=len(b),sha256=M['sha'](b)) for n,b in artifacts.items()}
    acceptance=dict(status='PASS',scope='EXACT_HOST_OFFICIAL_MOC_EXPORT_CAPTURE_ONLY',normalExit=True,
        cgroupKillWritten=False,runId='run-a',exportArtifacts=hashes)
    host=dict(runId='run-a',status='PASS',cubeExport=dict(status='CAPTURED_NOT_CORE_VERIFIED',coreVerification='NOT_RUN',
        settings=[512,512,100,False,False,False,False,False],before=dict(snapshots=[dict(artMeshId='face'+str(i)) for i in range(6)])))
    build=dict(status='BUILT_NOT_NATIVE_VERIFIED',hostExecuted=False,coreSha256='core-sha',recorderSha256='recorder-sha')
    capture=dict(status='CAPTURED_NOT_VERIFIED',mocSha256=M['sha'](artifacts['model.moc3']),mocBytes=64,
        coreOrigin=dict(path='C:\\task\\core.jar',sha256='core-sha'),recorderOrigin=dict(path='C:\\task\\recorder.jar',sha256='recorder-sha'),
        pid=42,processStart='2026-10-01T00:00:00Z',jvmArguments=[],classpath='C:\\task\\recorder.jar;C:\\task\\core.jar')
    return capture,acceptance,host,artifacts,build


class ContentTest(unittest.TestCase):
    def test_matching_artifacts(self):
        self.assertEqual(M['bind'](*fixture()),['face'+str(i) for i in range(6)])

    def test_source_texture_profile(self):
        args=list(copy.deepcopy(fixture()))
        texture=Path(__file__).resolve().parents[2]/'artifacts/cube-textured/cube-textured.glb'
        section=args[2]['cubeExport'];section.update(inputSha256=M['sha'](texture.read_bytes()),textureLayout='EDGE_PADDED_POT')
        section['settings'][:2]=[512,512]
        self.assertEqual(len(M['bind'](*args,source_texture=texture)),6)
        with self.assertRaises(ValueError):M['bind'](*args)
        section['settings'][:2]=[1024,1024]
        with self.assertRaises(ValueError):M['bind'](*args,source_texture=texture)
        section['settings'][:2]=[512,512]
        section['inputSha256']='wrong'
        with self.assertRaises(ValueError):M['bind'](*args,source_texture=texture)

    def test_explicit_sampling_deviation_receipt(self):
        args=list(copy.deepcopy(fixture()))
        texture=Path(__file__).resolve().parents[2]/'artifacts/cube-textured/cube-textured.glb'
        sha=M['sha'](texture.read_bytes())
        args[2]['cubeExport'].update(inputSha256=sha,textureLayout='EDGE_PADDED_POT')
        args[2]['cubeExport']['settings'][:2]=[512,512]
        phases=('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone')
        checks=[dict(phase=p,index=i,samples=65000,mismatches=9) for p in phases for i in range(9)]
        checks += [dict(phase=p,index=i,comparison='baseline-all-pixels',samples=1048576,mismatches=0) for p in phases[1:] for i in range(9)]
        args[1].update(status='ACCEPTED_WITH_USER_APPROVED_PIXEL_DEVIATION',
            warnings=['persistence pixels differ: recorded'],pixelDeviationPolicy=dict(
                sourceTextureSha256=sha,maxFraction=.001,channelTolerance=2,alphaExact=True,checks=checks))
        self.assertEqual(len(M['bind'](*args,source_texture=texture)),6)
        bundle=runpy.run_path(str(Path(__file__).with_name('prepare-bundle.py')))
        self.assertTrue(bundle['EXPORT_ADMITTED'](args[1]))
        with self.assertRaises(ValueError):M['bind'](*args)
        def missing(a):a['pixelDeviationPolicy']['checks'].pop()
        def duplicate(a):a['pixelDeviationPolicy']['checks'][1]=a['pixelDeviationPolicy']['checks'][0]
        def large(a):a['pixelDeviationPolicy']['checks'][0]['mismatches']=66
        def alpha(a):a['pixelDeviationPolicy']['alphaExact']=False
        def other_source(a):a['pixelDeviationPolicy']['sourceTextureSha256']='wrong'
        def widened(a):a['pixelDeviationPolicy']['maxFraction']=.1
        def geometry(a):a['warnings']=['geometry failed']
        def missing_policy(a):a.pop('pixelDeviationPolicy')
        for mutate in (missing,duplicate,large,alpha,other_source,widened,geometry,missing_policy):
            with self.subTest(mutation=mutate.__name__):
                changed=copy.deepcopy(args);mutate(changed[1])
                with self.assertRaises(ValueError):M['bind'](*changed,source_texture=texture)
                self.assertFalse(bundle['EXPORT_ADMITTED'](changed[1]))

    def test_cross_run_and_tamper(self):
        def moc(a):a[0]['mocSha256']='other'
        def bytes_changed(a):a[3]['model.moc3']+=b'x'
        def host_run(a):a[2]['runId']='other-run'
        def failed(a):a[1]['normalExit']=False
        def killed(a):a[1]['cgroupKillWritten']=True
        def core(a):a[0]['coreOrigin']['sha256']='other-core'
        def agent(a):a[0]['jvmArguments']=['-javaagent:C:\\runtime.jar']
        def custom_loader(a):a[0]['jvmArguments']=['-Djava.system.class.loader=Custom']
        def extra(a):a[0]['classpath']+=';C:\\extra.jar'
        def hidden(a):a[0]['classpath']='C:\\task\\recorder.jar;C:\\other\\core.jar'
        def duplicate(a):a[2]['cubeExport']['before']['snapshots'][1]['artMeshId']='face0'
        for mutate in (moc,bytes_changed,host_run,failed,killed,core,agent,custom_loader,extra,hidden,duplicate):
            with self.subTest(mutation=mutate.__name__):
                a=list(copy.deepcopy(fixture()));mutate(a)
                with self.assertRaises(ValueError):M['bind'](*a)

    def test_json_ambiguity(self):
        for raw in (b'{"a":1,"a":2}',b'{"v":NaN}',b'{"v":Infinity}'):
            with self.assertRaises(ValueError):M['decode'](raw)


if __name__=='__main__':unittest.main()
