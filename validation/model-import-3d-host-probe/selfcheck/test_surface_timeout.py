"""Execute real wrapper in a disposable tree; runner/verifier substituted, no host."""
from pathlib import Path
import os,shutil,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[3]
class SurfaceTimeoutTest(unittest.TestCase):
    def test_fixed_mode_budgets_and_override_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);scripts=root/'scripts/preview';scripts.mkdir(parents=True)
            wrapper=scripts/'run-model-import-3d-host-validation.sh'
            shutil.copyfile(ROOT/'scripts/preview'/wrapper.name,wrapper)
            (scripts/'host-validation-env.sh').write_text('turboism_select_fixture() { fixture_src=/synthetic; fixture_sha256=synthetic; }\n')
            (scripts/'run-cubism-host-validation.sh').write_text('printf "%s\\n" "$@"\n')
            probe=root/'validation/model-import-3d-host-probe';probe.mkdir(parents=True)
            (probe/'verify-host.py').write_text('# Explicit synthetic build-admission substitution.\n')
            env=dict(os.environ,TURBOISM_MI3D_BUILD_RUN=str(root/'build'))
            env.pop('TURBOISM_MI3D_EXIT_DIAGNOSTIC',None);env.pop('TURBOISM_MI3D_REOPEN_CMO3',None)
            for mode,seconds in [('normal',600),('cube-geometry',600),('plane-geometry',3600),('plane-seam-geometry',3600),('plane-render',3600),('plane-seam-render',3600),('sheet-geometry',5400),('sheet-render',5400)]:
                with self.subTest(mode=mode):
                    p=subprocess.run(['bash',str(wrapper),'5302','test','--dry-run'],env=dict(env,TURBOISM_MI3D_MODE=mode),capture_output=True,text=True)
                    self.assertEqual(p.returncode,0,p.stderr);args=p.stdout.splitlines()
                    self.assertEqual(args[args.index('--result-timeout')+1],str(seconds))
                    self.assertEqual(args[args.index('--ready-timeout')+1],'300')
                    self.assertEqual(args[args.index('--exit-timeout')+1],'120')
                    self.assertIn('--require-fixture-unchanged',args)
            p=subprocess.run(['bash',str(wrapper),'5302','test','--result-timeout','999999'],env=env,capture_output=True)
            self.assertEqual(p.returncode,2)
if __name__=='__main__':unittest.main()
