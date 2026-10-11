#!/usr/bin/env python3
"""Audit isolated native AWT drag evidence; report measurements without a speed gate."""
import json
import math
import runpy
import sys
from pathlib import Path

PAIR = runpy.run_path(str(Path(__file__).with_name('analyze-derived-persistence.py')))
MODEL = '70983ff77d29ebb644f3f2bf5ac5ed5b663199d2433919dcfb795dbecdeeaba5'


def analyze(root):
    root = Path(root)
    job, report = PAIR['terminal'](root)
    short = report['schemaVersion'] == 'mi3d-cat-optimized-native-short-drag-v1'
    steps = 3 if short else 20
    assert report['schemaVersion'] in ('mi3d-cat-optimized-native-short-drag-v1', 'mi3d-cat-optimized-native-drag-v2')
    assert report['scope'] == ('ISOLATED_OFFICIAL_VIEW_THREE_STEP_MOUSE_EVENT_BASELINE_CANDIDATE_UNDO_NOT_OS_INPUT' if short else 'ISOLATED_OFFICIAL_VIEW_MOUSE_EVENT_BASELINE_CANDIDATE_UNDO_NOT_OS_INPUT')
    assert report['sourceUnchanged'] is True and report['osMouseInputTested'] is False
    admission = json.loads((root / 'admission-review.json').read_text())
    assert admission['prepared'] == job['prepared_id']
    assert admission['status'] == 'INPUT_REVIEW_PASS_NOT_EXECUTED'
    assert len(admission['checks']) >= 44 and all(c['passed'] is True for c in admission['checks'])
    assert MODEL in (root / 'host-job/evidence/fixture-before.properties').read_text()
    bank = report['beforeBank']
    assert bank['meshes'] == 512 and bank['slots'] == 35397 and bank['triangles'] == 48864
    assert len(report['phases']) == 2 and report['nativeMapping']['status'] == 'PASS'
    measurements = []
    moved = None
    for phase, action in zip(report['phases'], ('baseline', 'candidate')):
        assert phase['action'] == action and phase['restoredBank'] == bank
        assert phase['sourceGeometryUnchanged'] and phase['axesUnchanged'] and phase['cellsUnchanged']
        assert phase['changedForms'] and 0 < phase['historyDelta'] <= 4
        points = phase['movedPositions']
        assert points and all(len(p) == 2 and all(math.isfinite(v) for v in p) for p in points)
        if moved is None:
            moved = points
        else:
            assert moved == points, 'candidate changes actual dragged geometry'
        assert all(math.isfinite(v) for v in phase['deltaCanvas'])
        assert math.hypot(*phase['deltaCanvas']) > 0
        spread = phase['translationSpreadPixels']
        assert math.isfinite(spread) and spread >= 0
        assert phase['vertexDistortionObserved'] == (spread > .01)
        native = phase['native']
        assert native['status'] == 'PASS' and native['action'] == action
        assert native['schemaVersion'] == 'mi3d-cat-native-drag-v3', 'plain mouse wrapper lacks the official drag press origin'
        assert native['eventPath'] == 'OFFICIAL_VIEW_STATEFUL_DRAG_WRAPPER_FROM_TASK_COMPONENT_AWT_MOUSE_EVENT_NOT_OS_INPUT'
        expected = [float(native['expectedDeltaCanvasX']), float(native['expectedDeltaCanvasY'])]
        assert phase['expectedDeltaCanvas'] == expected
        assert math.dist(phase['deltaCanvas'], expected) <= .01, 'gesture moved from the wrong origin'
        assert int(native['mouseDragEvents']) == steps
        allocations = [int(native[f'step.{i}.allocatedBytes']) for i in range(1, steps + 1)]
        times = [int(native[f'step.{i}.ns']) for i in range(1, steps + 1)]
        assert all(v >= 0 for v in allocations) and all(v > 0 for v in times)
        assert sum(allocations) == int(native['dragEventEdtAllocatedBytes'])
        assert max(times) == int(native['maxDragEventDispatchNs'])
        edge_bytes = sum(int(native[k + '.allocatedBytes']) for k in ('press', 'release'))
        edge_times = sum(int(native[k + '.ns']) for k in ('press', 'release'))
        assert edge_bytes >= 0 and edge_times > 0
        assert int(native['wallNs']) >= sum(times) + edge_times
        assert int(native['heapSamples']) > 0 and int(native['sampledHeapPeakBytes']) > 0
        assert int(native['acceptedDelta']) > 0 if action == 'candidate' else int(native['acceptedDelta']) == 0
        assert int(native['refusedDelta']) == 0
        measurements.append(dict(action=action, dispatchNs=sum(times) + edge_times, dragDispatchNs=sum(times), wallNs=int(native['wallNs']),
                                 allocatedBytes=sum(allocations) + edge_bytes, dragAllocatedBytes=sum(allocations), sampledHeapPeakBytes=int(native['sampledHeapPeakBytes']),
                                 maxEventNs=max(times), translationSpreadPixels=spread,
                                 ellipseCoefficientCandidate=native.get('ellipseCoefficientCandidate', 'false') == 'true'))
    baseline, candidate = measurements
    assert baseline['ellipseCoefficientCandidate'] is False
    result = dict(status='PASS_MEASURED_NATIVE_AWT_DRAG_DIFFERENTIAL', jobId=job['job_id'],
                  modelSha256=MODEL, dragEventsPerPhase=steps, measurements=measurements, movedGeometryExact=True,
                  undoBankExact=True, osMouseInputTested=False, performanceFixAccepted=False,
                  scope='Single paired native AWT drag; cumulative EDT allocation and sampled heap, not RSS or stable latency',
                  candidateAllocationRatio=candidate['allocatedBytes'] / max(1, baseline['allocatedBytes']),
                  candidateDispatchTimeRatio=candidate['dispatchNs'] / baseline['dispatchNs'],
                  ellipseCoefficientCandidate=candidate['ellipseCoefficientCandidate'])
    (root / 'independent-optimized-drag-review.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    analyze(sys.argv[1])
