import datetime
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('auto_connect', Path(__file__).with_name('analyze-auto-connect.py'))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


class AutoConnectAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.paths = [Path(self.temp.name) / n for n in ('markers', 'selected', 'results', 'protocol', 'jfr', 'outcome')]
        phases = [('mesh-enter-start', 0), ('mesh-enter-end', 0), ('mesh-baseline-start', 0), ('mesh-baseline-end', 0)]
        for n in range(1, 4):
            phases += [(p, n) for p in ('auto-connect-start', 'auto-connect-returned', 'auto-connect-end', 'mesh-retained-start', 'mesh-retained-end')]
        phases += [('mesh-cancel-start', 0), ('mesh-cancel-end', 0)]
        self.paths[0].write_text('phase\toperation\tepochMillis\tmonotonicNanos\n' + ''.join(
            f'{p}\t{n}\t{100000+i*1000}\t{1000000000+i*1000000000}\n' for i, (p, n) in enumerate(phases)))
        self.paths[1].write_text('bWVzaA==\n')
        self.paths[2].write_text(''.join(f'{n}\tbWVzaA==\t3\t{n}\t6\t3\t'+ 'a'*64+'\t'+'b'*64+'\n' for n in range(1, 4)))
        self.paths[3].write_text('scope=DIAGNOSTIC_ONLY\nrebuild=true\npreserveBorder=true\ncycles=3\n')
        self.events = [{'type': 'jdk.ExecutionSample', 'values': {
            'startTime': datetime.datetime.fromtimestamp(100+i+.5, datetime.timezone.utc).isoformat(),
            'stackTrace': {'frames': [{'method': None}, {'method': {'type': {'name':
                'com/live2d/graphics3d/editableMesh/triangulation/h'}}}]}}}
            for i, (p, n) in enumerate(phases) if p == 'auto-connect-start']
        self.paths[5].write_text(json.dumps(dict(validationStatus='PASS', cleanup='safe', normalExit=True, identityVerified=True, fixtureUnchanged=True)))

    def run_analysis(self):
        self.paths[4].write_text(json.dumps({'recording': {'events': self.events}}))
        return a.analyze(*self.paths)

    def test_three_commands_and_unknown_frames(self):
        r = self.run_analysis()
        self.assertEqual(r['triggerVerdict'], 'REPEATED_COMMAND_TARGET_OBSERVED')
        self.assertEqual([c['unknownFrames'] for c in r['cycles']], [1, 1, 1])
        self.assertEqual(r['performanceAcceptance'], 'NOT_APPLICABLE_DIAGNOSTIC')
        self.assertEqual(r['crossRunOutputEquivalence'], 'NOT_ASSESSED')

    def test_missing_command_sample_not_proven(self):
        self.events.pop()
        self.assertEqual(self.run_analysis()['triggerVerdict'], 'REPEATED_TARGET_UNPROVEN')

    def test_settling_sample_does_not_prove_command(self):
        v = self.events[2]['values']
        dt = datetime.datetime.fromisoformat(v['startTime']) + datetime.timedelta(seconds=1)
        v['startTime'] = dt.isoformat()
        r = self.run_analysis()
        self.assertEqual(r['triggerVerdict'], 'REPEATED_TARGET_UNPROVEN')
        self.assertEqual(r['cycles'][2]['settlingTargetSamples'], 1)

    def test_partial_host_run_rejected(self):
        self.paths[5].write_text('{"validationStatus":"FAIL","cleanup":"safe"}')
        with self.assertRaisesRegex(ValueError, 'host gates'):
            self.run_analysis()

    def test_missing_result_rejected(self):
        p = self.paths[2]
        p.write_text('\n'.join(p.read_text().splitlines()[:2])+'\n')
        with self.assertRaisesRegex(ValueError, 'cycle result'):
            self.run_analysis()

    def test_missing_cancel_rejected(self):
        p = self.paths[0]
        p.write_text('\n'.join(p.read_text().splitlines()[:-1])+'\n')
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.run_analysis()


if __name__ == '__main__':
    unittest.main()
