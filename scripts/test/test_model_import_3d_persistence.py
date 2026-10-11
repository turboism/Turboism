#!/usr/bin/env python3
"""Synthetic T014 persistence oracle controls; no host, queue, or real CMO3.
The Job A/Job B reports and PNGs below are built to the exact frozen contract
so every gate in verify_persistence_save / verify_persistence_reopen /
verify_pair is exercised positively and negatively."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import unittest

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / 'validation/model-import-3d-host-probe'

from scripts.test import test_model_import_3d_pixel_sampling as px

CMO3 = b'FAKE-CMO3 persistence fixture bytes'
# The frozen expected input-recipe digest (must equal the oracle's literal).
EXPECTED_MATERIAL_SHA256 = ('ec6e7a40115ad314f98e169757ebd92acf5a3219bc'
                            '92d7bdde6c177edc5643b4')


def load(name):
    spec = importlib.util.spec_from_file_location('mi3d_' + name, PROBE / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def manifest():
    return {'canvas': dict(px.SETUP_CANVAS), 'meshId': px.MESH_ID,
            'sourceGuid': 'source-' + px.MESH_ID,
            'sourcePositions': copy.deepcopy(px.SOURCE_POSITIONS),
            'sourceUvs': copy.deepcopy(px.SOURCE_UVS),
            'triangles': [0, 1, 2, 0, 2, 3],
            'forms': [{'formGuid': 'form-default',
                       'positions': copy.deepcopy(px.SOURCE_POSITIONS), 'drawOrder': 500},
                      {'formGuid': 'form-neg',
                       'positions': [[x - 80.0, y] for x, y in px.SOURCE_POSITIONS],
                       'drawOrder': 500},
                      {'formGuid': 'form-pos',
                       'positions': [[x + 80.0, y] for x, y in px.SOURCE_POSITIONS],
                       'drawOrder': 500}],
            'axes': [{'parameterGuid': 'axis-shift-guid',
                      'parameterId': 'ParamMI3DPixShift', 'minimum': -1.0,
                      'default': 0.0, 'maximum': 1.0, 'keys': [-1.0, 0.0, 1.0]}],
            'cells': copy.deepcopy(px._mesh(0.0)['cells']),
            'morphKeys': 0,
            'defaultForm': {'formGuid': 'form-default',
                            'positions': copy.deepcopy(px.SOURCE_POSITIONS),
                            'drawOrder': 500, 'defaultKeyform': True},
            'parameter': {'id': 'ParamMI3DPixShift', 'value': 0.0, 'minimum': -1.0,
                          'maximum': 1.0, 'default': 0.0},
            'textureInput': {'kind': 'MODEL_IMAGE', 'artMeshId': px.MESH_ID,
                             'modelId': px.MODEL_ID, 'modelImageId': px.IMAGE_ID,
                             'textureAtlasId': None},
            'expectedMaterialSha256': EXPECTED_MATERIAL_SHA256,
            'observedMaterialSha256': 'UNAVAILABLE'}


def save_report(cmo3):
    base, frames = px.fixture()
    m = manifest()
    # The distinct bounded post-save p0 sample: a separate artifact name and a
    # real PNG (pixel-identical to the pre-save bound-p0 in this fixture).
    frames['post-save-p0'] = frames['bound-p0']
    post_row = {'pose': 'post-save-p0', 'width': px.OUT, 'height': px.OUT,
                'stateBefore': px._state('bound-p0'),
                'stateAfter': px._state('bound-p0'),
                'historyRevisionBefore': 9, 'historyRevisionAfter': 9,
                'artifact': 'persistence/post-save-p0.png',
                'artifactSha256': hashlib.sha256(frames['post-save-p0']).hexdigest(),
                'artifactBytes': len(frames['post-save-p0'])}
    return ({'mi3dMode': 'persistence-save', 'runId': 'run-a',
             'pixelSampling': base['pixelSampling'],
             'persistenceSave': {
                 'schemaVersion': 'mi3d-t014-save-v1', 'status': 'PASS',
                 'failure': None,
                 'checks': [{'id': i, 'actual': True, 'status': 'PASS',
                             'evidenceKind': 'EXACT_HOST'} for i in (
                                 'saveGrantAcquired', 'saveCommandExecuted',
                                 'savedFileValid', 'saveUnchanged',
                                 'postSavePixelsEqual')],
                 'process': {'pid': 4242, 'startEpochMillis': 1759000000000},
                 'requestStatus': 'GRANTED', 'commandStatus': 'EXECUTED',
                 'artifact': 'persistence/mi3d-t014-model.cmo3',
                 'artifactBytes': len(cmo3),
                 'artifactSha256': hashlib.sha256(cmo3).hexdigest(),
                 'manifestBefore': copy.deepcopy(m),
                 'manifestAfter': copy.deepcopy(m),
                 'postSaveFrame': post_row,
                 'pngShas': {row['pose']: row['artifactSha256']
                             for row in base['pixelSampling']['frames']
                             if row['pose'] != 'blank'}}},
            frames)


def _reopen_state(pose):
    state = px._state(pose)
    state['canvas'] = dict(px.SETUP_CANVAS)
    state['history'] = {'availability': 'AVAILABLE', 'generation': 1,
                        'position': 0, 'documentBindingId': 'doc-t014-reopen',
                        'managerBindingId': 'mgr-t014-reopen',
                        'canUndo': False, 'canRedo': False, 'entries': []}
    return state


def reopen_report(frames):
    poses = ('bound-p0', 'bound-p1', 'bound-m1')
    rows = [{'pose': p, 'width': px.OUT, 'height': px.OUT,
             'stateBefore': _reopen_state(p), 'stateAfter': _reopen_state(p),
             'historyRevisionBefore': 3, 'historyRevisionAfter': 3,
             'artifact': 'pixel-sampling/%s.png' % p,
             'artifactSha256': hashlib.sha256(frames[p]).hexdigest(),
             'artifactBytes': len(frames[p])} for p in poses]
    return {'mi3dMode': 'persistence-reopen', 'runId': 'run-b',
            'status': 'PASS', 'failure': None,
            'persistenceReopen': {
                'schemaVersion': 'mi3d-t014-reopen-v1', 'mode': 'persistence-reopen',
                'status': 'PASS', 'failure': None,
                'checks': [{'id': i, 'actual': True, 'status': 'PASS',
                            'evidenceKind': 'EXACT_HOST'} for i in (
                                'reopenedCanvasFrozen', 'persistentParameterResolved',
                                'uniqueMeshResolved', 'reopenedContentFrozen',
                                'boundPatchesP0', 'boundPatchesPos',
                                'boundPatchesNeg', 'reopenedBoundInputTyped')],
                'process': {'pid': 5151, 'startEpochMillis': 1759000001000},
                'reopenedCanvas': dict(px.SETUP_CANVAS),
                'manifest': manifest(), 'frames': rows}}


class PersistenceSaveTest(unittest.TestCase):
    def setUp(self):
        self.verify = load('verify-pixel-sampling')
        self.report, self.frames = save_report(CMO3)

    def accept(self):
        self.assertTrue(self.verify.verify_persistence_save(
            self.report, CMO3, self.frames['post-save-p0'],
            {k: v for k, v in self.frames.items() if k != 'post-save-p0'}))

    def reject(self, mutate=None, cmo3=CMO3, post=None):
        r = copy.deepcopy(self.report)
        if mutate:
            mutate(r)
        post = self.frames['post-save-p0'] if post is None else post
        blobs = {k: v for k, v in self.frames.items() if k != 'post-save-p0'}
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_persistence_save(r, cmo3, post, blobs)

    def test_positive_save_section_passes(self):
        self.accept()

    def test_cmo3_sha_mismatch_rejected(self):
        self.reject(cmo3=CMO3 + b'x')

    def test_save_check_missing_rejected(self):
        self.reject(mutate=lambda r: r['persistenceSave']['checks'].pop())

    def test_save_command_not_executed_rejected(self):
        def m(r):
            r['persistenceSave']['commandStatus'] = 'FAILED'
        self.reject(mutate=m)

    def test_manifest_mutation_across_save_rejected(self):
        def m(r):
            r['persistenceSave']['manifestAfter']['sourceUvs'][0] = [1.0, 1.0]
        self.reject(mutate=m)

    def test_saved_manifest_differs_from_bound_frame_rejected(self):
        def m(r):
            r['persistenceSave']['manifestBefore']['textureInput']['modelImageId'] = 'other'
            r['persistenceSave']['manifestAfter']['textureInput']['modelImageId'] = 'other'
        self.reject(mutate=m)

    def test_wrong_canvas_rejected(self):
        def m(r):
            for key in ('manifestBefore', 'manifestAfter'):
                r['persistenceSave'][key]['canvas']['width'] = 1000.0
        self.reject(mutate=m)

    def test_wrong_artifact_path_rejected(self):
        def m(r):
            r['persistenceSave']['artifact'] = 'persistence/other.cmo3'
        self.reject(mutate=m)

    def test_oversized_cmo3_rejected(self):
        def m(r):
            r['persistenceSave']['artifactBytes'] = 33 * 1024 * 1024
        self.reject(mutate=m)

    def test_artifact_bytes_not_actual_rejected(self):
        # Controller counterexample: claimed count must equal the archive length.
        def m(r):
            r['persistenceSave']['artifactBytes'] = 1
        self.reject(mutate=m)

    def test_swapped_cell_forms_rejected(self):
        # Tuple→form swap with identical PNGs must still fail.
        def m(r):
            for key in ('manifestBefore', 'manifestAfter'):
                cells = r['persistenceSave'][key]['cells']
                cells[0]['formGuid'], cells[1]['formGuid'] = (
                    cells[1]['formGuid'], cells[0]['formGuid'])
        self.reject(mutate=m)

    def test_default_keyform_flag_swapped_rejected(self):
        def m(r):
            for key in ('manifestBefore', 'manifestAfter'):
                r['persistenceSave'][key]['cells'][0]['defaultKeyform'] = True
        self.reject(mutate=m)

    def test_default_form_not_default_cell_rejected(self):
        def m(r):
            for key in ('manifestBefore', 'manifestAfter'):
                r['persistenceSave'][key]['defaultForm']['formGuid'] = 'form-neg'
        self.reject(mutate=m)

    def test_fabricated_material_digest_rejected(self):
        def m(r):
            for key in ('manifestBefore', 'manifestAfter'):
                r['persistenceSave'][key]['expectedMaterialSha256'] = '0' * 64
        self.reject(mutate=m)

    def test_observed_material_fabricated_rejected(self):
        def m(r):
            for key in ('manifestBefore', 'manifestAfter'):
                r['persistenceSave'][key]['observedMaterialSha256'] = 'a' * 64
        self.reject(mutate=m)

    def test_missing_post_save_frame_rejected(self):
        def m(r):
            r['persistenceSave'].pop('postSaveFrame')
        self.reject(mutate=m)

    def test_post_save_pixels_differ_rejected(self):
        mat = px.material()
        self.reject(post=px.png(px.OUT, px.OUT, px.frame(mat, 80)))

    def test_post_save_wrong_pose_rejected(self):
        def m(r):
            r['persistenceSave']['postSaveFrame']['pose'] = 'bound-p0'
        self.reject(mutate=m)


class PersistenceReopenTest(unittest.TestCase):
    def setUp(self):
        self.verify = load('verify-pixel-sampling')
        self.frames = {p: px.blobs()[p] for p in ('bound-p0', 'bound-p1', 'bound-m1')}
        self.report = reopen_report(self.frames)

    def accept(self):
        self.assertTrue(self.verify.verify_persistence_reopen(self.report, self.frames))

    def reject(self, mutate=None, mutate_frames=None):
        r = copy.deepcopy(self.report)
        f = dict(self.frames)
        if mutate:
            mutate(r)
        if mutate_frames:
            mutate_frames(f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_persistence_reopen(r, f)

    def test_positive_reopen_passes(self):
        self.accept()

    def test_wrong_mode_rejected(self):
        self.reject(mutate=lambda r: r.update(mi3dMode='normal'))

    def test_missing_reopen_pose_rejected(self):
        self.reject(mutate_frames=lambda f: f.pop('bound-m1'))

    def test_reopen_canvas_not_persisted_rejected(self):
        def m(r):
            r['persistenceReopen']['reopenedCanvas']['width'] = 1000.0
        self.reject(mutate=m)

    def test_persistent_mesh_id_drift_rejected(self):
        def m(r):
            r['persistenceReopen']['manifest']['meshId'] = 'artmesh-other'
        self.reject(mutate=m)

    def test_axis_persistent_guid_drift_rejected(self):
        def m(r):
            r['persistenceReopen']['manifest']['axes'][0]['parameterGuid'] = 'axis-other'
        self.reject(mutate=m)

    def test_form_position_rewrite_rejected(self):
        def m(r):
            r['persistenceReopen']['manifest']['forms'][0]['positions'][0] = [0.0, 0.0]
        self.reject(mutate=m)

    def test_frame_axis_extra_rejected(self):
        def m(r):
            for row in r['persistenceReopen']['frames']:
                for key in ('stateBefore', 'stateAfter'):
                    row[key]['mesh']['axes'].append(
                        {'parameterGuid': 'extra', 'parameterId': 'ParamX',
                         'keys': [0.0]})
        self.reject(mutate=m)

    def test_param_pose_mismatch_rejected(self):
        def m(r):
            row = r['persistenceReopen']['frames'][1]
            for key in ('stateBefore', 'stateAfter'):
                row[key]['parameter']['value'] = 0.0
        self.reject(mutate=m)

    def test_wrong_pose_png_rejected(self):
        # Re-sign the mutated frame so the rejection reaches the pixel oracle.
        f = dict(self.frames)
        f['bound-p1'] = f['bound-p0']
        r = reopen_report(f)  # rebuilt rows carry fresh shas
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_persistence_reopen(r, f)

    def test_stale_frame_identity_rejected(self):
        def m(r):
            row = r['persistenceReopen']['frames'][0]
            row['stateAfter'] = copy.deepcopy(row['stateBefore'])
            row['stateAfter']['history']['documentBindingId'] = 'other-doc'
        self.reject(mutate=m)

    def test_process_identity_missing_rejected(self):
        def m(r):
            r['persistenceReopen']['process'] = {'pid': 0, 'startEpochMillis': -1}
        self.reject(mutate=m)

    def test_cross_document_frames_rejected(self):
        # Controller counterexample: each frame on a different document/model/
        # manager must fail even though every frame's before==after holds.
        def m(r):
            for index, frame in enumerate(r['persistenceReopen']['frames']):
                for key in ('stateBefore', 'stateAfter'):
                    state = frame[key]
                    state['model'] = 'different-document-' + str(index)
                    state['textureInput']['modelId'] = state['model']
                    state['history']['documentBindingId'] = 'different-history-' + str(index)
                    state['history']['managerBindingId'] = 'different-manager-' + str(index)
        self.reject(mutate=m)

    def test_cross_document_single_frame_rejected(self):
        def m(r):
            frame = r['persistenceReopen']['frames'][1]
            for key in ('stateBefore', 'stateAfter'):
                frame[key]['history']['documentBindingId'] = 'doc-other'
        self.reject(mutate=m)

    def test_typed_input_model_drift_rejected(self):
        def m(r):
            frame = r['persistenceReopen']['frames'][0]
            for key in ('stateBefore', 'stateAfter'):
                frame[key]['textureInput']['modelId'] = 'model-other'
        self.reject(mutate=m)

    def test_evaluated_param_mismatch_rejected(self):
        # Evaluated positions must equal the frozen rect for the ACTUAL
        # parameter value — p1 claims +1 but evaluated stays at 0.
        def m(r):
            frame = r['persistenceReopen']['frames'][1]
            for key in ('stateBefore', 'stateAfter'):
                frame[key]['mesh']['evaluatedPositions'] = copy.deepcopy(px.SOURCE_POSITIONS)
        self.reject(mutate=m)

    def test_reopen_manifest_cell_swap_rejected(self):
        def m(r):
            cells = r['persistenceReopen']['manifest']['cells']
            cells[0]['formGuid'], cells[1]['formGuid'] = (
                cells[1]['formGuid'], cells[0]['formGuid'])
            for frame in r['persistenceReopen']['frames']:
                for key in ('stateBefore', 'stateAfter'):
                    fcells = frame[key]['mesh']['cells']
                    fcells[0]['formGuid'], fcells[1]['formGuid'] = (
                        fcells[1]['formGuid'], fcells[0]['formGuid'])
        self.reject(mutate=m)

    def test_manifest_extra_key_rejected(self):
        def m(r):
            r['persistenceReopen']['manifest']['materialSha256'] = '0' * 64
        self.reject(mutate=m)


class PersistencePairTest(unittest.TestCase):
    def setUp(self):
        self.pixel = load('verify-pixel-sampling')
        self.pair = load('verify-persistence-pair')
        self.a_report, self.a_frames = save_report(CMO3)
        self.b_frames = {p: self.a_frames[p] for p in ('bound-p0', 'bound-p1', 'bound-m1')}
        self.b_report = reopen_report(self.b_frames)
        self.seed_sha = hashlib.sha256(CMO3).hexdigest()

    def accept(self):
        self.assertTrue(self.pair.verify_pair(
            self.a_report, CMO3, self.a_frames,
            self.b_report, self.b_frames, self.seed_sha))

    def reject(self, a=None, b=None, cmo3=CMO3, a_frames=None, b_frames=None, seed=None):
        a = copy.deepcopy(self.a_report) if a is None else a
        b = copy.deepcopy(self.b_report) if b is None else b
        af = self.a_frames if a_frames is None else a_frames
        bf = self.b_frames if b_frames is None else b_frames
        seed = self.seed_sha if seed is None else seed
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.pair.verify_pair(a, cmo3, af, b, bf, seed)

    def test_positive_pair_passes(self):
        self.accept()

    def test_post_save_png_missing_rejected(self):
        self.reject(a_frames={k: v for k, v in self.a_frames.items()
                              if k != 'post-save-p0'})

    def test_cross_job_cell_swap_rejected(self):
        # The original controller counterexample: swap tuple→form in every
        # manifest and every frame — PNGs untouched, must still fail.
        a, af = save_report(CMO3)
        b_frames = {p: af[p] for p in ('bound-p0', 'bound-p1', 'bound-m1')}
        b = reopen_report(b_frames)

        def swap(m):
            cells = m['cells']
            cells[0]['formGuid'], cells[1]['formGuid'] = (
                cells[1]['formGuid'], cells[0]['formGuid'])
        for key in ('manifestBefore', 'manifestAfter'):
            swap(a['persistenceSave'][key])
        swap(b['persistenceReopen']['manifest'])
        for report, section in ((a, 'pixelSampling'), (b, 'persistenceReopen')):
            for frame in report[section]['frames']:
                for key in ('stateBefore', 'stateAfter'):
                    if frame[key]['mesh'] is not None:
                        swap(frame[key]['mesh'])
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.pair.verify_pair(a, CMO3, af, b, b_frames, self.seed_sha)

    def test_same_process_pair_rejected(self):
        # The e2e single-JVM pair must NOT be accepted as a real reopen.
        a = copy.deepcopy(self.a_report)
        b = copy.deepcopy(self.b_report)
        b['persistenceReopen']['process'] = copy.deepcopy(
            a['persistenceSave']['process'])
        self.reject(a=a, b=b)

    def test_same_run_id_rejected(self):
        b = copy.deepcopy(self.b_report)
        b['runId'] = 'run-b'
        a = copy.deepcopy(self.a_report)
        a['runId'] = 'run-b'
        self.reject(a=a, b=b)

    def test_wrong_seed_sha_rejected(self):
        self.reject(seed=hashlib.sha256(b'other').hexdigest())

    def test_manifest_drift_rejected(self):
        b = copy.deepcopy(self.b_report)
        b['persistenceReopen']['manifest']['sourceGuid'] = 'source-other'
        self.reject(b=b)

    def test_pixel_drift_rejected(self):
        mat = px.material()
        different = {p: px.png(px.OUT, px.OUT, px.frame(mat, 80))
                     for p in ('bound-p0', 'bound-p1', 'bound-m1')}
        # Rebuild the report rows so shas match but pixels differ on bound-p0.
        bf = dict(self.b_frames)
        bf['bound-p0'] = different['bound-p0']
        b = reopen_report(bf)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.pair.verify_pair(self.a_report, CMO3, self.a_frames, b, bf,
                                  self.seed_sha)

    def test_missing_save_artifact_rejected(self):
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.pair.verify_pair(self.a_report, b'', self.a_frames,
                                  self.b_report, self.b_frames, self.seed_sha)


# --- Real-queue-layout CLI regression (F1): drives verify-persistence-pair.py
# as a subprocess against on-disk evidence directories with the actual
# lifecycle/containment/tar schema — the same admission the real Job A/B run
# will go through. The build run is the preserved implementation build.
JAR_SHA = '988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
BUILD_ROOT = ROOT / 'build/model-import-3d-host'


def _latest_build():
    candidates = [d for d in BUILD_ROOT.iterdir()
                  if d.is_dir() and not d.name.startswith('controller-')
                  and (d / 'provenance/build.json').is_file()]
    if not candidates:
        return None
    return max(candidates, key=lambda d: (d / 'terminal.json').stat().st_mtime)


def _lifecycle(run_id, fixture_sha, agent_sha, probe_sha):
    containment = {'schemaVersion': 1, 'cleanup': 'safe', 'jobId': 'job-x',
                   'attemptId': 'attempt-x', 'runId': run_id,
                   'preparedDigest': 'prepared-x', 'scopeUnit': 'mi3d.scope',
                   'cgroupPath': '/sys/fs/cgroup/mi3d', 'cgroupDevice': 'dev0',
                   'cgroupInode': 'ino0', 'bootId': 'boot-x',
                   'kernelProof': {'originalCgroupBound': True, 'errors': [],
                                   'processTimedOut': False, 'processReturncode': 0,
                                   'finalReading': {'kind': 'destroyed'},
                                   'cgroupPath': '/sys/fs/cgroup/mi3d',
                                   'cgroupDevice': 'dev0', 'cgroupInode': 'ino0',
                                   'bootId': 'boot-x'}}
    return {'schemaVersion': 1, 'runId': run_id,
            'finalizedBy': 'contained-supervisor', 'terminalState': 'succeeded',
            'validationStatus': 'PASS', 'cleanup': 'safe',
            'identityVerified': True, 'fixtureUnchanged': True,
            'normalExit': True, 'runnerExitCode': 0,
            'jobId': 'job-x', 'attemptId': 'attempt-x', 'preparedDigest': 'prepared-x',
            'containment': containment,
            'details': {'taskOwnedCleanup': True, 'validationComplete': True,
                        'goldenBatBeforeSha256': 'bat0', 'clonedBatBeforeSha256': 'bat0',
                        'sourceFixtureBeforeSha256': fixture_sha,
                        'fixtureBeforeSha256': fixture_sha,
                        'postContainmentChecks': {
                            'goldenJarSha256': JAR_SHA, 'clonedJarSha256': JAR_SHA,
                            'goldenBatSha256': 'bat0', 'clonedBatSha256': 'bat0',
                            'fixtureSourceSha256': fixture_sha,
                            'fixtureCopySha256': fixture_sha,
                            'stagedArtifacts': [
                                {'sourceSha256': agent_sha, 'stagedSha256': agent_sha},
                                {'sourceSha256': probe_sha, 'stagedSha256': probe_sha}],
                            'terminalResult': {'passed': True}}}}


def _evidence_dir(root, run_id, report, members, fixture_sha, agent_sha, probe_sha,
                  extra_members=None):
    """Lay out a real evidence directory: <job>/evidence/{lifecycle-result.json,
    final-task/{turboism-home-logs-state.tar,cubism-console.txt}} and
    <job>/containment.json."""
    evidence = root / 'evidence'
    final = evidence / 'final-task'
    final.mkdir(parents=True)
    life = _lifecycle(run_id, fixture_sha, agent_sha, probe_sha)
    (evidence / 'lifecycle-result.json').write_text(json.dumps(life))
    bound = {k: life['containment'][k] for k in
             ('jobId', 'attemptId', 'runId', 'preparedDigest', 'scopeUnit',
              'cgroupPath', 'cgroupDevice', 'cgroupInode', 'bootId')}
    (root / 'containment.json').write_text(json.dumps(bound))
    (final / 'cubism-console.txt').write_text('synthetic console\n')
    with tarfile.open(final / 'turboism-home-logs-state.tar', 'w',
                      format=tarfile.USTAR_FORMAT) as tar:
        entries = dict(members)
        entries['state/dev.turboism.validation.model-import-3d/report.json'] = \
            json.dumps(report).encode()
        entries['logs/launcher.log'] = b'launch log\n'
        if extra_members:
            entries.update(extra_members)
        for name, data in entries.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return evidence


def _save_report_full(cmo3):
    """A save-mode report satisfying the COMPLETE native contract (all host
    sections plus the persistence section) — built on the shared host fixture."""
    from scripts.test import test_model_import_3d_host_validation as hv
    save_r, save_frames = save_report(cmo3)
    r = hv.ordinary_fixture()
    r['mi3dMode'] = 'persistence-save'
    r['runId'] = 'run-a-cli'
    r['pixelSampling'] = save_r['pixelSampling']
    r['persistenceSave'] = save_r['persistenceSave']
    return r, save_frames


class PersistencePairCliTest(unittest.TestCase):
    """CLI-level regression through the real evidence directory layout."""

    def setUp(self):
        self.pair = load('verify-persistence-pair')
        self.script = PROBE / 'verify-persistence-pair.py'
        self.build = _latest_build()
        self.a_report, self.a_frames = save_report(CMO3)
        self.b_frames = {p: self.a_frames[p]
                         for p in ('bound-p0', 'bound-p1', 'bound-m1')}
        self.b_report = reopen_report(self.b_frames)
        self.seed_sha = hashlib.sha256(CMO3).hexdigest()

    def test_load_result_real_schema(self):
        # Raw loader on a minimal real-schema directory: the actual
        # details.postContainmentChecks path, never life['checks'].
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            job = Path(tmp) / 'job-b'
            members = {'state/dev.turboism.validation.model-import-3d/'
                       'pixel-sampling/%s.png' % p: self.b_frames[p]
                       for p in ('bound-p0', 'bound-p1', 'bound-m1')}
            evidence = _evidence_dir(job, 'run-b', self.b_report, members,
                                     self.seed_sha, 'agent0', 'probe0')
            out = self.pair.load_result(evidence)
            self.assertEqual(out['fixtureSha256'], self.seed_sha)
            self.assertEqual(out['report']['runId'], 'run-b')
            self.assertIsNone(out['cmo3'])

    def test_load_result_rejects_extra_persistence_member(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            job = Path(tmp) / 'job-b'
            members = {'state/dev.turboism.validation.model-import-3d/'
                       'pixel-sampling/%s.png' % p: self.b_frames[p]
                       for p in ('bound-p0', 'bound-p1', 'bound-m1')}
            extra = {'state/dev.turboism.validation.model-import-3d/'
                     'persistence/stowaway.bin': b'x'}
            evidence = _evidence_dir(job, 'run-b', self.b_report, members,
                                     self.seed_sha, 'agent0', 'probe0',
                                     extra_members=extra)
            with self.assertRaises((ValueError, KeyError, TypeError)):
                self.pair.load_result(evidence)

    def test_load_result_rejects_link_member(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            job = Path(tmp) / 'job-b'
            evidence = _evidence_dir(job, 'run-b', self.b_report, {},
                                     self.seed_sha, 'agent0', 'probe0')
            archive = evidence / 'final-task/turboism-home-logs-state.tar'
            with tarfile.open(archive, 'a') as tar:  # append a symlink member
                info = tarfile.TarInfo('state/evil-link')
                info.type = tarfile.SYMTYPE
                info.linkname = '/etc/passwd'
                tar.addfile(info)
            with self.assertRaises((ValueError, KeyError, TypeError)):
                self.pair.load_result(evidence)

    def test_cli_runs_and_rejects_wrong_modes(self):
        # The pair CLI must fail closed: two reopen-mode jobs are not a pair.
        if self.build is None:
            self.skipTest('no preserved build run available')
        import tempfile
        build = json.loads((self.build / 'provenance/build.json').read_text())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            members_b = {'state/dev.turboism.validation.model-import-3d/'
                         'pixel-sampling/%s.png' % p: self.b_frames[p]
                         for p in ('bound-p0', 'bound-p1', 'bound-m1')}
            ra = copy.deepcopy(self.b_report); ra['runId'] = 'run-a'
            rb = copy.deepcopy(self.b_report); rb['runId'] = 'run-b'
            ja = _evidence_dir(root / 'job-a', 'run-a', ra, members_b,
                               self.seed_sha, build['agentSha256'],
                               build['probeSha256'])
            jb = _evidence_dir(root / 'job-b', 'run-b', rb, members_b,
                               self.seed_sha, build['agentSha256'],
                               build['probeSha256'])
            result = subprocess.run(
                [sys.executable, '-B', str(self.script), '--build-run',
                 str(self.build), '--job-a', str(ja), '--job-b', str(jb)],
                capture_output=True, text=True, cwd=ROOT)
            self.assertEqual(result.returncode, 1)
            self.assertIn('FAIL', result.stderr)

    def test_cli_accepts_valid_pair_on_real_layout(self):
        # Positive CLI regression: Job A save + Job B reopen evidence dirs,
        # full lifecycle/containment/build/archive admission, then the pair
        # invariants — all through the real CLI subprocess.
        if self.build is None:
            self.skipTest('no preserved build run available')
        import tempfile
        build = json.loads((self.build / 'provenance/build.json').read_text())
        a_full, a_frames = _save_report_full(CMO3)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prefix = 'state/dev.turboism.validation.model-import-3d/'
            members_a = {prefix + 'pixel-sampling/%s.png' % p: a_frames[p]
                         for p in ('baseline', 'bound-p0', 'bound-p1',
                                   'bound-m1', 'unbound', 'rebound', 'blank')}
            members_a[prefix + 'persistence/mi3d-t014-model.cmo3'] = CMO3
            members_a[prefix + 'persistence/post-save-p0.png'] = a_frames['post-save-p0']
            ja = _evidence_dir(root / 'job-a', 'run-a-cli', a_full, members_a,
                               hashlib.sha256(b'job-a-seed').hexdigest(),
                               build['agentSha256'], build['probeSha256'])
            members_b = {prefix + 'pixel-sampling/%s.png' % p: self.b_frames[p]
                         for p in ('bound-p0', 'bound-p1', 'bound-m1')}
            rb = copy.deepcopy(self.b_report); rb['runId'] = 'run-b-cli'
            jb = _evidence_dir(root / 'job-b', 'run-b-cli', rb, members_b,
                               self.seed_sha, build['agentSha256'],
                               build['probeSha256'])
            result = subprocess.run(
                [sys.executable, '-B', str(self.script), '--build-run',
                 str(self.build), '--job-a', str(ja), '--job-b', str(jb)],
                capture_output=True, text=True, cwd=ROOT)
            self.assertEqual(result.returncode, 0,
                             'expected PASS, got: ' + result.stderr)
            self.assertIn('T014 PERSISTENCE PAIR PASS', result.stdout)

    def test_cli_rejects_lifecycle_mutation(self):
        # Negative CLI regression: Job B lifecycle claims a different seed than
        # the CMO3 Job A actually produced → four-way sha agreement fails.
        if self.build is None:
            self.skipTest('no preserved build run available')
        import tempfile
        build = json.loads((self.build / 'provenance/build.json').read_text())
        a_full, a_frames = _save_report_full(CMO3)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prefix = 'state/dev.turboism.validation.model-import-3d/'
            members_a = {prefix + 'pixel-sampling/%s.png' % p: a_frames[p]
                         for p in ('baseline', 'bound-p0', 'bound-p1',
                                   'bound-m1', 'unbound', 'rebound', 'blank')}
            members_a[prefix + 'persistence/mi3d-t014-model.cmo3'] = CMO3
            members_a[prefix + 'persistence/post-save-p0.png'] = a_frames['post-save-p0']
            ja = _evidence_dir(root / 'job-a', 'run-a-cli', a_full, members_a,
                               hashlib.sha256(b'job-a-seed').hexdigest(),
                               build['agentSha256'], build['probeSha256'])
            members_b = {prefix + 'pixel-sampling/%s.png' % p: self.b_frames[p]
                         for p in ('bound-p0', 'bound-p1', 'bound-m1')}
            wrong_sha = hashlib.sha256(b'not-the-saved-cmo3').hexdigest()
            rb = copy.deepcopy(self.b_report); rb['runId'] = 'run-b-cli'
            jb = _evidence_dir(root / 'job-b', 'run-b-cli', rb, members_b,
                               wrong_sha, build['agentSha256'],
                               build['probeSha256'])
            result = subprocess.run(
                [sys.executable, '-B', str(self.script), '--build-run',
                 str(self.build), '--job-a', str(ja), '--job-b', str(jb)],
                capture_output=True, text=True, cwd=ROOT)
            self.assertEqual(result.returncode, 1)
            self.assertIn('FAIL', result.stderr)


if __name__ == '__main__':
    unittest.main()
