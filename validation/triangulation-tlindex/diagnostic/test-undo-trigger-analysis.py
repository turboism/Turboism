import csv
import datetime
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('undo_analysis', Path(__file__).with_name('analyze-undo-trigger.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class AnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.markers, self.undo, self.jfr, self.outcome = [self.root / n for n in ('markers', 'undo', 'jfr', 'outcome')]
        phases = [('baseline-start', 0), ('baseline-end', 0)]
        for n in (1, 2, 3):
            phases.extend((p, n) for p in ('operation-start', 'operation-end', 'diagnostic-undo-start', 'diagnostic-undo-end', 'retained-start', 'retained-end'))
        with self.markers.open('w') as f:
            writer = csv.writer(f, delimiter='\t'); writer.writerow(['phase', 'operation', 'epochMillis', 'monotonicNanos'])
            for i, (phase, n) in enumerate(phases): writer.writerow([phase, n, 100000+i*1000, 1000000000+i*1000000000])
        self.undo.write_text('1\t0\n2\t0\n3\t0\n')
        self.outcome.write_text(json.dumps(dict(validationStatus='PASS', cleanup='safe', normalExit=True, identityVerified=True, fixtureUnchanged=True)))

    def run_analysis(self, seconds):
        events = [{'type': 'jdk.ExecutionSample', 'values': {'startTime': datetime.datetime.fromtimestamp(s, datetime.timezone.utc).isoformat(), 'stackTrace': {'frames': [{'method': {'type': {'name':'com/live2d/graphics3d/editableMesh/triangulation/h'}}}]}}} for s in seconds]
        self.jfr.write_text(json.dumps({'recording': {'events': events}}))
        return m.analyze(self.markers, self.undo, self.jfr, self.outcome)

    def test_all_cycles(self):
        r = self.run_analysis([102.5,108.5,114.5])
        self.assertEqual(r['triggerVerdict'], 'REPEATED_TARGET_OBSERVED')
        self.assertEqual(r['perCycleOutputEquivalence'], 'NOT_PROVEN')

    def test_undo_and_retained_samples_do_not_count(self):
        r = self.run_analysis([102.5,110.5,118.5])
        self.assertEqual([x['nativeTriangulationSamples'] for x in r['operations']], [1,0,0])
        self.assertEqual(r['triggerVerdict'], 'REPEATED_TARGET_UNPROVEN')

    def test_missing_receipt_rejected(self):
        self.undo.write_text('1\t0\n2\t0\n')
        with self.assertRaisesRegex(ValueError, 'incomplete undo'): self.run_analysis([])

    def test_cursor_drift_rejected(self):
        self.undo.write_text('1\t0\n2\t1\n3\t0\n')
        with self.assertRaisesRegex(ValueError, 'position drift'): self.run_analysis([])

    def test_failed_host_rejected(self):
        self.outcome.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'host gates'): self.run_analysis([])

if __name__ == '__main__': unittest.main()
