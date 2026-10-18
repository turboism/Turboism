"""Offline Viewer dispatcher and launch isolation contracts. Never starts Viewer."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'preview'))
import host_validation_official_viewer as viewer
from host_validation_queue import QueueError


def args():
    return ['--name','official-viewer','--version','5302','--bundle-root','/tmp/viewer-bundle',
            '--golden-prefix','/tmp/golden','--host-root','/tmp/viewer-host','--run-label','test-viewer',
            '--proton-wrapper','/usr/bin/shorin-proton-wrapper','--proton-runner','/tmp/proton','--transport','local']


class ViewerTests(unittest.TestCase):
    def test_canonical_options_and_rejections(self):
        self.assertEqual(viewer.options(args())['--name'],'official-viewer')
        variants=[args()+['--client-script','/tmp/custom'],args()+['--name','official-viewer'],args()[:-1],args()+['--local-evidence-dir','/tmp/evidence']]
        for key,value in [('--name','official-core'),('--version','5303'),('--transport','remote'),('--run-label','../escape'),('--bundle-root','/tmp/../escape')]:
            a=args();a[a.index(key)+1]=value;variants.append(a)
        for a in variants:
            with self.subTest(args=a),self.assertRaises(QueueError):viewer.options(a)

    def test_no_queue_admission_no_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            a=args()+['--local-evidence-dir',tmp+'/evidence'];a[a.index('--host-root')+1]=tmp+'/host'
            with patch.object(viewer,'validate_admission',side_effect=QueueError('no admission')):
                with self.assertRaises(QueueError):viewer.run(a)
            self.assertEqual(list(Path(tmp).iterdir()),[])

    def test_dispatch_cannot_impersonate_another_task(self):
        import types
        from host_validation_queue import PreparedStore
        with tempfile.TemporaryDirectory() as tmp:
            store = PreparedStore(types.SimpleNamespace(root=Path(tmp), production=False))
            request = dict(schemaVersion=1, argv=args(), environment={})
            with self.assertRaises(QueueError):
                store.capture(request, Path(__file__).resolve().parents[2], 'official-core:5302')
            a = args(); a[1] = 'official-core'; request['argv'] = a
            with self.assertRaises(QueueError):
                store.capture(request, Path(__file__).resolve().parents[2], 'official-viewer:5302')

    def test_frozen_finalizer_sibling_import_without_module_search_path(self):
        import types
        module=types.ModuleType('isolated_viewer_finalizer')
        source=Path(viewer.__file__);module.__file__=str(source)
        clean=[p for p in sys.path if p!=str(source.parent)]
        with patch.object(sys,'path',clean),patch.dict(sys.modules,{'host_validation_official_core':None}):
            exec(compile(source.read_bytes(),str(source),'exec'),module.__dict__)
        self.assertEqual(module.path.__globals__['__file__'],str(source.with_name('host_validation_official_core.py')))

    def test_real_runtime_command_fits_windows_limit(self):
        import json
        root=Path(__file__).resolve().parents[2]
        m=json.loads((root/'build/model-import-3d-offline/source-texture-viewer-bundle-r1-20261001/bundle.json').read_text())
        task=Path('/var/lib/turboism-validation/official-viewer/5302-source-texture-viewer-r1-20261001/queue-77b5c90ecb254b2d8b7cd712a9dcb808')
        script=viewer.launch_script(task,m)
        self.assertLess(max(map(len,script.splitlines())),8192)
        self.assertIn(b'cd /d "Z:',script)
        self.assertIn(b'app\\lib\\Live2D_Cubism.jar',script)
        self.assertNotIn((str(task/'bundle/app/lib')).replace('/','\\').encode(),script)

    def test_apple_launch_is_explicit(self):
        m=dict(captureProfile='apple-3-v1',nativePaths=['app/dll64','app/dll64/windows-amd64'],classpath=['app/lib/Live2D_Cubism.jar','official-viewer-capture.jar'])
        script=viewer.launch_script(Path('/tmp/owned-apple-viewer'),m)
        self.assertIn(b'model\\apple.model3.json',script)
        self.assertIn(b'apple-3-v1',script)
        self.assertNotIn(b'cube.model3.json',script)

    def test_full_xyz_launch_uses_all_imported_axes(self):
        axes=['Param3DYaw4a0514c79e84','Param3DPitch4a0514c79e84','Param3DRoll4a0514c79e84']
        m=dict(captureProfile='apple-xyz-3-v1',axes=axes,nativePaths=['app/dll64','app/dll64/windows-amd64'],
               classpath=['app/lib/Live2D_Cubism.jar','official-viewer-capture.jar'])
        script=viewer.launch_script(Path('/tmp/owned-xyz-viewer'),m)
        self.assertIn(b'apple-xyz-3-v1',script)
        self.assertIn(b'model\\apple.model3.json',script)
        for axis in axes:self.assertIn(axis.encode(),script)
        self.assertNotIn(b'Param3DYaw394e4cf740f3',script)

    def test_launch_isolation(self):
        task=Path('/tmp/owned-viewer-task');m=dict(nativePaths=['app/dll64','app/dll64/windows-amd64'],classpath=['app/lib/Live2D_Cubism.jar','official-viewer-capture.jar'])
        script=viewer.launch_script(task,m).decode()
        for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'):
            self.assertIn('set "'+key+'="\r\n',script)
        for key in ('USERPROFILE','APPDATA','LOCALAPPDATA'):
            self.assertIn('set "'+key+'=Z:\\tmp\\owned-viewer-task\\home"',script)
        self.assertIn('-Duser.home=Z:\\tmp\\owned-viewer-task\\home',script)
        self.assertIn('dev.modelimport.validation.OfficialViewerCapture',script)
        self.assertIn('model\\cube.model3.json',script)
        self.assertNotIn('-javaagent',script)
        self.assertNotIn('turboism-agent',script)
        self.assertEqual(viewer.launch_argv(viewer.options(args()),task)[-3:-1],['/d','/c'])

if __name__=='__main__':unittest.main()
