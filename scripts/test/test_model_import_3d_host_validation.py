#!/usr/bin/env python3
"""Isolated synthetic N2 protocol/safety tests. Never starts a host or production queue."""
import argparse
import copy
import hashlib
import importlib.util
import importlib
from functools import lru_cache
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import io
import zipfile
import tarfile

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / 'validation/model-import-3d-host-probe'
PRESERVED_BUILD = Path(os.environ.get('TURBOISM_MI3D_BUILD_RUN', ''))
HAS_PRESERVED_BUILD = bool(os.environ.get('TURBOISM_MI3D_BUILD_RUN')) and PRESERVED_BUILD.is_dir()
sys.dont_write_bytecode = True


def load(name, path):
    if path.name.startswith('test_model_import_3d_'):
        return importlib.import_module('scripts.test.' + path.stem)
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


VERIFY = load('mi3d_verify', PROBE / 'verify-host.py')
BUILD = load('mi3d_build', PROBE / 'build-host.py')


def fixture():
    # Deliberately synthetic and independent of the Java encoder. Never stored as a real host report.
    geometry = {'artMeshId': 'mesh', 'sourceGuid': 'source',
                'positions': [[16, 24], [272, 24], [272, 152], [16, 152]],
                'uvs': [[0, 0], [1, 0], [1, 1], [0, 1]], 'indices': [0, 1, 2, 0, 2, 3],
                'axes': [], 'cells': [{'formGuid': 'shared', 'coordinates': []}],
                'forms': [{'guid': 'shared', 'positions': [[16, 24], [272, 24], [272, 152], [16, 152]], 'drawOrder': 500}],
                'morphKeys': []}
    grid = copy.deepcopy(geometry)
    grid['axes'] = [{'guid': guid, 'id': id_, 'minimum': -1, 'default': 0, 'maximum': 1, 'keys': [-1, 0, 1]}
                    for guid, id_ in [('gx', 'ParamMI3DN2X'), ('gy', 'ParamMI3DN2Y')]]
    grid['cells'] = [{'formGuid': 'shared', 'coordinates': [{'guid': 'gx', 'value': x}, {'guid': 'gy', 'value': y}]}
                     for x in [-1, 0, 1] for y in [-1, 0, 1]]
    checks = ['newCommand', 'newIdentity', 'blankMesh', 'historyAvailable', 'createSingleUndo', 'createdGeometry',
              'createdReadNoChange', 'createUndo', 'deletedHandle', 'createRedo', 'gridTuples', 'gridReadNoChange',
              'allUndo', 'allRedo', 'restored']
    deferred = ['morphCreation', 'basicIdentity', 'absoluteVsDelta', 'targetWrite', 'atomicImport',
                'dirty', 'saveReopen', 'atlasRepack', 'pluginDisable', 'hostLifecycle']
    result = {'schemaVersion': 'mi3d-native-n2-v1', 'scope': 'N2_BOOTSTRAP_CREATE_GRID_READ', 'runId': 'synthetic',
              'status': 'PASS', 'failure': None, 'stageAStatus': 'NOT_EVALUATED', 'hostIdentity': None, 'requestedVersion': '5302',
              'checks': [{'id': id_, 'actual': True, 'expected': 'fixed', 'status': 'PASS', 'evidenceKind': 'EXACT_HOST'} for id_ in checks],
              'deferred': [{'id': id_, 'status': 'NOT_RUN', 'reason': 'outside N2'} for id_ in deferred],
              'seed': {'documentId': 'seed', 'modelId': 'seed-model', 'editorModelId': 'native-seed', 'kind': 'MODEL'},
              'document': {'documentId': 'task', 'modelId': 'task-model', 'editorModelId': 'native-task', 'kind': 'MODEL'},
              'created': geometry, 'createdRedo': copy.deepcopy(geometry), 'grid': grid, 'gridRedo': copy.deepcopy(grid),
              'finallyRestored': True}
    for key in ('initialCounts', 'undoCounts', 'restoredCounts', 'finallyCounts'):
        result[key] = {'meshes': 0, 'deformers': 0, 'parameters': 0, 'parts': 0}
    for key, pos in [('historyBefore', 0), ('historyCreated', 1), ('historyGrid', 4), ('historyRestored', 0)]:
        result[key] = {'availability': 'AVAILABLE', 'generation': 1, 'revision': 1, 'position': pos,
                       'entries': ['entry'] * pos, 'documentBindingId': 'task-doc', 'managerBindingId': 'task-manager'}
    result['createUndoCounts'] = copy.deepcopy(result['initialCounts'])
    result['createdCounts'] = {**result['initialCounts'], 'meshes': 1}
    result['gridCounts'] = {**result['initialCounts'], 'meshes': 1, 'parameters': 2}
    result['historyAfterCreatedRead'] = copy.deepcopy(result['historyCreated'])
    result['historyAfterGridRead'] = copy.deepcopy(result['historyGrid'])
    for phase, key in (('SEED', 'seed'), ('NEW', 'document')):
        result['documentWait' + phase] = {'phase': phase, 'attempts': 1, 'reason': 'READY',
            'documentId': result[key]['documentId'], 'snapshotModelId': result[key]['modelId'],
            'documentPresent': True, 'modelPresent': True, 'kind': 'MODEL',
            'editorModelId': result[key]['editorModelId']}
    return result


def _ordinary_fixture():
    # Independent synthetic protocol data: not native execution and never saved as host evidence.
    r = fixture(); baseline = copy.deepcopy(r['grid'])
    for i, cell in enumerate(baseline['cells']): cell['formGuid'] = 'form-' + str(i)
    baseline['forms'] = [dict(guid='form-' + str(i), positions=copy.deepcopy(baseline['positions']), drawOrder=500)
                         for i in range(9)]
    r['grid'] = copy.deepcopy(baseline); r['gridRedo'] = copy.deepcopy(baseline)
    # Iteration order in fixture is x then y: (+1,0)=7, (-1,0)=1, default=4.
    a = [[48, 40], [304, 40], [304, 168], [48, 168]]
    b = [[-4, 64], [252, 64], [252, 192], [-4, 192]]
    written = copy.deepcopy(baseline); written['forms'][7]['positions'] = a; written['forms'][1]['positions'] = b
    mutated = copy.deepcopy(baseline); mutated['forms'][7]['positions'] = b; mutated['forms'][1]['positions'] = a
    h0 = {**copy.deepcopy(r['historyGrid']), 'entryIds': ['i0', 'i1', 'i2', 'i3']}
    r['historyGridEntryIds'] = copy.deepcopy(h0['entryIds'])
    r['historyGridRedoEntryIds'] = copy.deepcopy(h0['entryIds'])
    r['historyGridRedo'] = {k: copy.deepcopy(v) for k, v in h0.items() if k != 'entryIds'}
    h1 = {**copy.deepcopy(h0), 'position': 5, 'revision': 2, 'entries': ['entry'] * 5,
          'entryIds': ['i0', 'i1', 'i2', 'i3', 'write-entry']}
    hu = {**copy.deepcopy(h1), 'position': 4, 'revision': 3}
    hr = {**copy.deepcopy(h1), 'revision': 4}
    hf = {**copy.deepcopy(hu), 'revision': 5}
    states = {}
    for name, s, h in [('before', baseline, h0), ('written', written, h1), ('noop', written, h1),
                       ('undone', baseline, hu), ('redone', written, hr), ('rollbackBefore', written, hr),
                       ('rollbackAfter', written, hr), ('rejectionsBefore', written, hr),
                       ('rejectionsAfter', written, hr), ('restored', baseline, hf)]:
        states[name] = copy.deepcopy(dict(snapshot=s, history=h, parameters=[0, 0]))
    ids = ['targetIdentity', 'valuesAtDefault', 'writeCommitted', 'singleUndo', 'writeIsolated', 'noop',
           'writeUndo', 'writeRedo', 'callbackRolledBack', 'rollbackState', 'defaultRejected',
           'incompleteRejected', 'topologyRejected', 'rejectionsUnchanged', 'restored', 'valuesUnchanged']
    o = dict(schemaVersion='mi3d-native-n3a-v1', status='PASS', failure=None, evaluationStatus='NOT_RUN',
             coordinateSpace='EDITOR_STORED_FORM', states=states, rollbackMutated=mutated,
             callbackFailure='MI3D_N3A_EXPECTED_ABORT', targets=[copy.deepcopy(baseline['cells'][i]) for i in (7, 1, 4)],
             checks=[dict(id=i, status='PASS', actual=True, expected='independent synthetic check', evidenceKind='EXACT_HOST') for i in ids],
             defaultRejected='java.lang.UnsupportedOperationException', incompleteRejected='java.lang.IllegalArgumentException',
             topologyRejected='java.lang.IllegalArgumentException')
    for name, outcome, before, after in [('write', 'COMMITTED', h0, h1), ('noop', 'NO_CHANGE', h1, h1),
                                        ('rollback', 'ROLLED_BACK', hr, hr)]:
        o[name] = dict(outcome=outcome, diagnosticId='synthetic-diagnostic' if name == 'rollback' else None,
                       receipt=dict(transactionId='tx-' + name, label=name, historyBefore=copy.deepcopy(before),
                                    historyAfter=copy.deepcopy(after), historyEntryId='write-entry' if name == 'write' else None))
    r['ordinaryWrite'] = o
    r = load('mi3d_evaluation_fixture', Path(__file__).with_name('test_model_import_3d_evaluation.py')).attach(r)
    r = load('mi3d_order_fixture', Path(__file__).with_name('test_model_import_3d_order.py')).attach(r)
    r = load('mi3d_morph_fixture', Path(__file__).with_name('test_model_import_3d_morph.py')).attach(r)
    r = load('mi3d_morph_order_fixture', Path(__file__).with_name('test_model_import_3d_morph_order.py')).attach(r)
    r = load('mi3d_image_binding_fixture', Path(__file__).with_name('test_model_import_3d_image_binding.py')).attach(r)
    r = load('mi3d_shape_import_fixture', Path(__file__).with_name('test_model_import_3d_shape_import.py')).attach(r)
    return load('mi3d_pixel_fixture', Path(__file__).with_name('test_model_import_3d_pixel_sampling.py')).attach(r)


@lru_cache(maxsize=1)
def _ordinary_json():
    return json.dumps(_ordinary_fixture())


def ordinary_fixture():
    # Each caller owns its mutable report, while deterministic construction happens once.
    return json.loads(_ordinary_json())


def pixel_entries():
    """The seven preserved PNG members required by the real verifier."""
    frames = load('mi3d_pixel_fixture', Path(__file__).with_name('test_model_import_3d_pixel_sampling.py')).blobs()
    return [(tarfile.REGTYPE, data,
             'state/dev.turboism.validation.model-import-3d/pixel-sampling/%s.png' % pose)
            for pose, data in frames.items()]


class ProtocolTest(unittest.TestCase):
    def test_packaging_catalog_and_entrypoint_controls(self):
        base = 'META-INF/turboism/i18n/messages'
        meta = {'id': 'dev.turboism.validation.model-import-3d', 'entrypoints': ['example.Probe'],
                'i18n': {'baseName': base, 'locales': []}}

        def check(metadata, entries):
            contents = io.BytesIO()
            with zipfile.ZipFile(contents, 'w') as jar:
                jar.writestr('META-INF/turboism/plugin.json', json.dumps(metadata))
                for name in entries: jar.writestr(name, b'synthetic packaging control, never executed')
            with zipfile.ZipFile(contents) as jar: VERIFY.verify_probe_metadata(jar)

        entries = ['example/Probe.class', base + '.properties']
        check(meta, entries)  # Empty locales still requires the implicit base catalog.
        with self.assertRaisesRegex(ValueError, 'missing declared i18n catalog'):
            check(meta, entries[:1])
        with self.assertRaisesRegex(ValueError, 'missing plugin entrypoint'):
            check(meta, entries[1:])
        with self.assertRaisesRegex(ValueError, 'undeclared i18n catalog'):
            check(meta, entries + [base + '_zh_CN.properties'])
        localized = copy.deepcopy(meta); localized['i18n']['locales'] = ['zh-CN', 'base']
        with self.assertRaisesRegex(ValueError, 'missing declared i18n catalog'):
            check(localized, entries)
        check(localized, entries + [base + '_zh_CN.properties'])
        # A directory is not a catalog file.
        with self.assertRaisesRegex(ValueError, 'missing declared i18n catalog'):
            check(meta, [entries[0], base + '.properties/'])
    def test_fixed_alias_grid_is_observable_not_writable(self):
        VERIFY.verify_observations(fixture(), 'synthetic')

    def test_ordinary_report_and_missing_extension(self):
        VERIFY.verify_native(ordinary_fixture(), 'synthetic')
        with self.assertRaises(KeyError): VERIFY.verify_native(fixture(), 'synthetic')

    def test_post_redo_history_anchor_is_not_pre_undo_detail(self):
        r = ordinary_fixture()
        before = r['ordinaryWrite']['states']['before']['history']
        r['historyGridEntryIds'] = copy.deepcopy(before['entryIds'])
        r['historyGridRedoEntryIds'] = copy.deepcopy(before['entryIds'])
        r['historyGridRedo'] = {k: copy.deepcopy(v) for k, v in before.items() if k != 'entryIds'}
        # Native undo objects may acquire redoData during the N2 Undo/Redo round trip.
        r['historyGrid']['entries'][2] = 'entry: post-state-unavailable before first Undo'
        r['historyGrid']['revision'] = 0
        r['historyAfterGridRead'] = copy.deepcopy(r['historyGrid'])
        VERIFY.verify_native(r, 'synthetic')

    def test_transaction_receipt_has_provider_local_counters(self):
        r = ordinary_fixture()
        # A separately instantiated history provider has its own generation/revision clocks.
        for name in ('write', 'noop', 'rollback'):
            for side in ('historyBefore', 'historyAfter'):
                h = r['ordinaryWrite'][name]['receipt'][side]
                h['generation'] = 7
                h['revision'] += 100
        VERIFY.verify_native(r, 'synthetic')

    def test_history_anchor_and_local_clock_negative_matrix(self):
        mutations = [
            lambda r: r.pop('historyGridRedo'),
            lambda r: r.pop('historyGridEntryIds'),
            lambda r: r.pop('historyGridRedoEntryIds'),
            lambda r: r['historyGridRedo'].__setitem__('revision', 999),
            lambda r: r['historyGridRedo']['entries'].__setitem__(2, 'changed detail'),
            lambda r: r['historyGridRedoEntryIds'].__setitem__(2, 'replacement'),
            lambda r: r['historyGridEntryIds'].__setitem__(2, 'replacement'),
            lambda r: r['historyGridEntryIds'].__setitem__(2, 'i1'),
            lambda r: r['historyGridRedoEntryIds'].pop(),
            lambda r: r['historyGridRedo'].__setitem__('managerBindingId', 'other'),
            lambda r: r['ordinaryWrite']['write']['receipt']['historyBefore'].__setitem__('generation', -1),
            lambda r: r['ordinaryWrite']['write']['receipt']['historyAfter'].__setitem__('generation', 99),
            lambda r: r['ordinaryWrite']['write']['receipt']['historyAfter'].__setitem__('revision', 1),
            lambda r: r['ordinaryWrite']['noop']['receipt']['historyBefore'].__setitem__('revision', 0),
            lambda r: r['ordinaryWrite']['noop']['receipt']['historyAfter'].__setitem__('revision', 3),
            lambda r: r['ordinaryWrite']['rollback']['receipt']['historyAfter'].__setitem__('revision', 5),
            lambda r: r['ordinaryWrite']['write']['receipt']['historyAfter']['entries'].__setitem__(2, 'changed detail'),
            lambda r: r['ordinaryWrite']['write']['receipt']['historyAfter']['entryIds'].__setitem__(2, 'replacement'),
            lambda r: r['ordinaryWrite']['write']['receipt']['historyBefore'].__setitem__('documentBindingId', 'seed'),
            lambda r: r['ordinaryWrite']['noop']['receipt']['historyBefore'].__setitem__('revision', True),
            lambda r: r['ordinaryWrite']['noop']['receipt']['historyBefore'].pop('generation'),
        ]
        for i, mutation in enumerate(mutations):
            with self.subTest(i=i):
                r = ordinary_fixture(); mutation(r)
                with self.assertRaises((ValueError, KeyError)): VERIFY.verify_native(r, 'synthetic')

    def test_ordinary_negative_matrix(self):
        mutations = [
            lambda o: o.__setitem__('evaluationStatus', 'PASS'),
            lambda o: o.__setitem__('coordinateSpace', 'EVALUATED'),
            lambda o: o.__setitem__('status', 'NOT_RUN'),
            lambda o: o.__setitem__('failure', 'ignored'),
            lambda o: o['checks'].pop(),
            lambda o: o['checks'][0].__setitem__('evidenceKind', 'OFFLINE_FIXTURE'),
            lambda o: o['checks'][0].__setitem__('actual', False),
            lambda o: o['targets'][0].__setitem__('formGuid', 'form-4'),
            lambda o: o['targets'][0]['coordinates'].pop(),
            lambda o: o['states']['written']['snapshot']['forms'][7]['positions'][0].__setitem__(0, 49),
            lambda o: o['states']['written']['snapshot']['forms'][4]['positions'][0].__setitem__(0, 999),
            lambda o: o['states']['written']['snapshot']['forms'][0].__setitem__('drawOrder', 501),
            lambda o: o['states']['written']['snapshot']['positions'][0].__setitem__(0, 999),
            lambda o: o['states']['noop']['history'].__setitem__('revision', 999),
            lambda o: o['states']['redone']['parameters'].__setitem__(0, 1),
            lambda o: o['states']['rollbackAfter']['history'].__setitem__('revision', 999),
            lambda o: o['states']['rollbackAfter']['snapshot']['forms'][1]['positions'][0].__setitem__(0, 999),
            lambda o: o['states']['rejectionsAfter']['history'].__setitem__('revision', 999),
            lambda o: o['states']['restored']['history'].__setitem__('position', 5),
            lambda o: o['states']['undone']['history'].__setitem__('documentBindingId', 'seed'),
            lambda o: o['states']['redone']['history']['entryIds'].__setitem__(4, 'different'),
            lambda o: o['rollbackMutated']['forms'][7]['positions'][0].__setitem__(0, 999),
            lambda o: o.__setitem__('callbackFailure', 'failed before writes'),
            lambda o: o['write'].__setitem__('outcome', 'NO_CHANGE'),
            lambda o: o['rollback'].__setitem__('outcome', 'RECOVERY_FAILED'),
            lambda o: o['rollback'].__setitem__('diagnosticId', None),
            lambda o: o['write']['receipt'].__setitem__('historyEntryId', 'wrong'),
            lambda o: o['noop']['receipt'].__setitem__('historyEntryId', 'extra'),
            lambda o: o['noop']['receipt'].__setitem__('transactionId', 'tx-write'),
            lambda o: o['write']['receipt']['historyAfter'].__setitem__('revision', 999),
            lambda o: o.__setitem__('defaultRejected', 'NOT_REJECTED'),
            lambda o: o.__setitem__('incompleteRejected', 'java.lang.UnsupportedOperationException'),
            lambda o: o.__setitem__('topologyRejected', 'NOT_REJECTED'),
        ]
        for i, mutation in enumerate(mutations):
            with self.subTest(i=i):
                r = ordinary_fixture(); mutation(r['ordinaryWrite'])
                with self.assertRaises((ValueError, KeyError)): VERIFY.verify_native(r, 'synthetic')
        r = ordinary_fixture(); r['grid'] = fixture()['grid']; r['gridRedo'] = copy.deepcopy(r['grid'])
        r['ordinaryWrite']['states']['before']['snapshot'] = copy.deepcopy(r['grid'])
        with self.assertRaisesRegex(ValueError, 'nine independent forms'): VERIFY.verify_native(r, 'synthetic')

    def test_native_report_negative_matrix(self):
        mutations = [lambda r: r['checks'].pop(), lambda r: r['deferred'].pop(),
            lambda r: r['grid']['cells'].pop(), lambda r: r['grid']['positions'][0].__setitem__(0, 999),
            lambda r: r['createdRedo'].__setitem__('sourceGuid', 'new'),
            lambda r: r.__setitem__('hostIdentity', {'jar': 'claimed'}),
            lambda r: r.__setitem__('stageAStatus', 'PASS'), lambda r: r.__setitem__('finallyRestored', False),
            lambda r: r.__setitem__('seed', r['document']), lambda r: r['checks'][0].__setitem__('actual', False),
            lambda r: r['restoredCounts'].__setitem__('meshes', 1),
            lambda r: r['historyRestored'].__setitem__('documentBindingId', 'seed'),
            lambda r: r['grid']['morphKeys'].append({'keyValue': 0}),
            lambda r: r['historyAfterGridRead'].__setitem__('revision', 999),
            lambda r: r['createdCounts'].__setitem__('meshes', 2),
            lambda r: r['grid']['cells'].__setitem__(0, r['grid']['cells'][1]),
            lambda r: r['document'].__setitem__('editorModelId', r['seed']['editorModelId']),
            lambda r: r['document'].pop('editorModelId'),
            lambda r: r.pop('documentWaitSEED'),
            lambda r: r['documentWaitNEW'].__setitem__('reason', 'IDENTITY_CHANGED'),
            lambda r: r['documentWaitNEW'].__setitem__('snapshotModelId', 'wrong-namespace'),
            lambda r: r['documentWaitSEED'].__setitem__('attempts', 0),
            lambda r: r['documentWaitNEW'].__setitem__('documentPresent', False),
            lambda r: r['documentWaitNEW'].__setitem__('kind', 'OTHER')]
        for i, mutation in enumerate(mutations):
            with self.subTest(i=i):
                value = fixture(); mutation(value)
                with self.assertRaises((ValueError, KeyError)): VERIFY.verify_observations(value, 'synthetic')

    def test_unobserved_or_wrong_lifecycle_cannot_pass(self):
        for value in ({}, {'status': 'PASS'}, {'schemaVersion': 1, 'runId': 'wrong'},
                      {'schemaVersion': 1, 'runId': 'synthetic', 'finalizedBy': 'runner'}):
            with self.assertRaises((ValueError, KeyError)): VERIFY.verify_lifecycle(value, 'synthetic', {})

    def test_json_ambiguity_rejected(self):
        for text in ('{"a":1,"a":2}', '{"x":NaN}', '/dev/null', '{'):
            with self.assertRaises(ValueError): VERIFY.loads(text)

    def _archive_main(self, entries, truncate=False, pixel=False, expected_mode=None, cube_transport=False, build_override=None):
        # Isolate transport from build/host lifecycle. Native 72-check semantics still run for valid JSON.
        name = 'state/dev.turboism.validation.model-import-3d/report.json'
        reads = []; real_extract = tarfile.TarFile.extractfile
        class ObservedReader:
            def __init__(self, inner): self.inner = inner
            def __enter__(self): return self
            def __exit__(self, *args): self.inner.close()
            def read(self, size=-1): reads.append(size); return self.inner.read(size)
        def extract(tar, member): return ObservedReader(real_extract(tar, member))
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); evidence = root / 'evidence'; final = evidence / 'final-task'; final.mkdir(parents=True)
            bound = {k: 'synthetic' for k in ('jobId', 'attemptId', 'runId', 'preparedDigest', 'scopeUnit',
                                            'cgroupPath', 'cgroupDevice', 'cgroupInode', 'bootId')}
            (root / 'containment.json').write_text(json.dumps(bound))
            (evidence / 'lifecycle-result.json').write_text(json.dumps({'containment': bound}))
            (final / 'cubism-console.txt').write_text('Synthetic transport-only console, no host execution.\n')
            archive = final / 'turboism-home-logs-state.tar'
            if pixel:
                entries = entries + pixel_entries()
            with tarfile.open(archive, 'w', format=tarfile.USTAR_FORMAT) as tar:
                for kind, data, member_name in entries:
                    info = tarfile.TarInfo(name if member_name is None else member_name); info.type = kind
                    info.size = len(data) if kind == tarfile.REGTYPE else 0
                    if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE): info.linkname = 'not-a-report'
                    tar.addfile(info, io.BytesIO(data) if kind == tarfile.REGTYPE else None)
            if truncate:
                with archive.open('r+b') as stream: stream.truncate(1024)
            argv = ['verify-host.py', '--build-run', str(root / 'build'), '--evidence', str(evidence), '--run-id', 'synthetic']
            if expected_mode is not None:
                argv += ['--expected-mode', expected_mode]
            with patch.object(sys, 'argv', argv), patch.object(VERIFY, 'verify_build', return_value=build_override if build_override is not None else {'verifiedCubeInputPath':'synthetic'} if cube_transport else {}), \
                 patch.dict(VERIFY.WHOLE_IMPORT, {'verify_cube_persistence_report':lambda *a,**kw: None,'verify_cube_atlas_report':lambda *a,**kw:None,'verify_cube_export':lambda *a,**kw:None} if cube_transport else {}), \
                 patch.dict(VERIFY.ATLAS_PERSISTENCE, {'report_leg':lambda *a,**kw:None} if cube_transport else {}), \
                 patch.object(VERIFY, 'verify_lifecycle'), patch('builtins.print'), \
                 patch.object(VERIFY, 'verify_native', wraps=VERIFY.verify_native) as semantic, \
                 patch.object(tarfile.TarFile, 'extractfile', autospec=True, side_effect=extract):
                if expected_mode == 'cube-atlas-reopen':
                    try:
                        VERIFY.job_result(evidence,'synthetic',{'verifiedCubeInputPath':'synthetic'},expected_mode,atlas_reference={})
                        rc=0
                    except (OSError,ValueError,KeyError,TypeError,tarfile.TarError):rc=1
                else:rc = VERIFY.main()
                return rc, semantic.call_count, reads

    def test_canonical_archive_inventory_and_strict_inventory_routing(self):
        prefix='state/dev.turboism.validation.model-import-3d/'
        mode='canonical-warp-textured'
        entries=[(tarfile.REGTYPE,json.dumps(dict(mi3dMode=mode)).encode(),None)]
        entries += [(tarfile.REGTYPE,b'xy',prefix+f'canonical-warp-capture/sample-{i}.xy') for i in range(22)]
        entries += [(tarfile.REGTYPE,b'png',prefix+f'canonical-warp-pixels/{i}.png') for i in range(11)]
        build=dict(sourceInventoryVersion='mi3d-canonical-warp-sources-v3',verifiedSourceWarpTexturePath='/frozen/provenance/apple-textured.glb')
        admitted=[]
        def strict(*args): admitted.append(args)
        def run(rows,b=build,requested=mode):
            with patch.object(VERIFY.runpy,'run_path',return_value={'verify':strict}):
                return self._archive_main(rows,expected_mode=requested,build_override=b)[0]
        self.assertEqual(run(entries),0)
        self.assertEqual(len(admitted),1)
        self.assertEqual(admitted[0][2],Path('/frozen/canonical-prepare'))
        self.assertEqual(len(admitted[0][3]),22)
        self.assertEqual(len(admitted[0][4]),11)
        bad=[entries[:-1],entries+[entries[1]],entries+pixel_entries(),
             entries+[(tarfile.REGTYPE,b'x',prefix+'source-warp-capture/sample-0.xy')],
             entries+[(tarfile.REGTYPE,b'x',prefix+'source-warp-pixels/0.png')],
             entries+[(tarfile.REGTYPE,b'x',prefix+'canonical-warp-capture/extra.xy')],
             entries+[(tarfile.REGTYPE,b'x',prefix+'canonical-warp-pixels/extra.png')]]
        for kind in (tarfile.SYMTYPE,tarfile.LNKTYPE):
            rows=list(entries);rows[1]=(kind,b'',rows[1][2]);bad.append(rows)
        rows=list(entries);rows[1]=(tarfile.REGTYPE,b'x'*(65535*8+1),rows[1][2]);bad.append(rows)
        for rows in bad:
            with self.subTest(archiveCase=bad.index(rows)): self.assertEqual(run(rows),1)
        self.assertEqual(run(entries,{**build,'sourceInventoryVersion':'mi3d-canonical-warp-sources-v1'}),1)
        self.assertEqual(run(entries,{**build,'sourceInventoryVersion':'mi3d-canonical-warp-sources-v2'}),1)
        self.assertEqual(run(entries,{'sourceInventoryVersion':'mi3d-canonical-warp-sources-v3'}),1)
        self.assertEqual(run(entries,requested='source-warp-textured'),1)
        self.assertEqual(len(admitted),1)  # Transport failures never reach the strict checker.

    def test_layered_archive_inventory_and_strict_inventory_routing(self):
        prefix='state/dev.turboism.validation.model-import-3d/'
        mode='canonical-warp-layered-textured'
        entries=[(tarfile.REGTYPE,json.dumps(dict(mi3dMode=mode)).encode(),None)]
        entries += [(tarfile.REGTYPE,b'xy',prefix+f'canonical-warp-capture/sample-{i}.xy') for i in range(1078)]
        entries += [(tarfile.REGTYPE,b'xy',prefix+f'canonical-warp-capture/layer-local-{i}.xy') for i in range(98)]
        entries += [(tarfile.REGTYPE,b'png',prefix+f'canonical-warp-pixels/{i}.png') for i in range(11)]
        build=dict(sourceInventoryVersion='mi3d-canonical-layered-sources-v1',verifiedSourceWarpTexturePath='/frozen/provenance/apple-textured.glb')
        admitted=[]
        def strict(*args): admitted.append(args)
        def run(rows,b=build,requested=mode):
            with patch.object(VERIFY.runpy,'run_path',return_value={'verify':strict}):
                return self._archive_main(rows,expected_mode=requested,build_override=b)[0]
        self.assertEqual(run(entries),0)
        self.assertEqual(len(admitted),1)
        self.assertEqual(admitted[0][2],Path('/frozen/canonical-prepare'))
        self.assertEqual(len(admitted[0][3]),1176)
        self.assertEqual(len(admitted[0][4]),11)
        bad=[entries[:1079]+entries[1080:], entries[:-1],entries+[entries[1]],entries+pixel_entries(),
             entries+[(tarfile.REGTYPE,b'x',prefix+'source-warp-capture/sample-0.xy')],
             entries+[(tarfile.REGTYPE,b'x',prefix+'source-warp-pixels/0.png')],
             entries+[(tarfile.REGTYPE,b'x',prefix+'canonical-warp-capture/extra.xy')],
             entries+[(tarfile.REGTYPE,b'x',prefix+'canonical-warp-pixels/extra.png')]]
        for kind in (tarfile.SYMTYPE,tarfile.LNKTYPE):
            rows=list(entries);rows[1]=(kind,b'',rows[1][2]);bad.append(rows)
        rows=list(entries);rows[1]=(tarfile.REGTYPE,b'x'*(65535*8+1),rows[1][2]);bad.append(rows)
        for rows in bad:
            with self.subTest(archiveCase=bad.index(rows)): self.assertEqual(run(rows),1)
        self.assertEqual(run(entries,{**build,'sourceInventoryVersion':'mi3d-canonical-warp-sources-v1'}),1)
        self.assertEqual(run(entries,{**build,'sourceInventoryVersion':'mi3d-canonical-warp-sources-v2'}),1)
        self.assertEqual(run(entries,{'sourceInventoryVersion':'mi3d-canonical-layered-sources-v1'}),1)
        self.assertEqual(run(entries,{**build,'sourceInventoryVersion':'mi3d-canonical-warp-sources-v3'}),1)
        self.assertEqual(run(entries,requested='source-warp-textured'),1)
        self.assertEqual(len(admitted),1)  # Transport failures never reach the strict checker.

    def test_cube_persistence_archive_inventory(self):
        # Transport only: report/geometry/PNG semantics have independent positive/negative tests.
        prefix='state/dev.turboism.validation.model-import-3d/'
        for mode,phases in [('cube-persistence-save',('before-save','after-save')),('cube-persistence-reopen',('reopen',)),('cube-atlas',('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone')),('cube-export',('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone')),('cube-atlas-save',('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone','atlas-before-save','atlas-after-save')),('cube-atlas-reopen',('atlas-reopen',))]:
            entries=[(tarfile.REGTYPE,json.dumps(dict(mi3dMode=mode)).encode(),None)]
            entries += [(tarfile.REGTYPE,b'png',prefix+f'cube-persistence-render/{p}-{i}.png') for p in phases for i in range(9)]
            if mode == 'cube-export':entries += [(tarfile.REGTYPE,b'moc',prefix+'official-export/model.moc3'),(tarfile.REGTYPE,b'png',prefix+'official-export/texture-0.png')]
            if mode.endswith('save'):entries.append((tarfile.REGTYPE,b'cmo3',prefix+'persistence/mi3d-cube-model.cmo3'))
            def run(rows):return self._archive_main(rows,expected_mode=mode,cube_transport=True)[0]
            self.assertEqual(run(entries),0)
            self.assertEqual(run(entries[:-1]),1)
            self.assertEqual(run(entries+[entries[1]]),1)
            self.assertEqual(run(entries+[(tarfile.REGTYPE,b'extra',prefix+'cube-persistence-render/extra.png')]),1)
            altered=list(entries);kind,data,name=altered[1];altered[1]=(tarfile.SYMTYPE,data,name)
            self.assertEqual(run(altered),1)
            self.assertEqual(run(entries+pixel_entries()),1)
            if mode.endswith('reopen') or mode in ('cube-atlas','cube-export'):
                self.assertEqual(run(entries+[(tarfile.REGTYPE,b'extra',prefix+'persistence/mi3d-cube-model.cmo3')]),1)

    def test_current_source_export_stage_routing(self):
        prefix='state/dev.turboism.validation.model-import-3d/'
        entries=[(tarfile.REGTYPE,json.dumps(dict(mi3dMode='cube-export')).encode(),None)]
        entries += [(tarfile.REGTYPE,b'png',prefix+f'cube-persistence-render/{p}-{i}.png')
                    for p in ('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone') for i in range(9)]
        entries += [(tarfile.REGTYPE,b'moc',prefix+'official-export/model.moc3'),
                    (tarfile.REGTYPE,b'png',prefix+'official-export/texture-0.png')]
        build=dict(inputStageVersion='mi3d-surface-render-v2',verifiedCubeInputPath='cube',
                   verifiedPersistenceTexturePath='texture',
                   persistenceTextureSha256='dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859')
        def check(b):
            return self._archive_main(entries,expected_mode='cube-export',cube_transport=True,build_override=b)[0]
        self.assertEqual(check(build),0)
        for key,value in (('inputStageVersion','mi3d-surface-render-v1'),
                          ('inputStageVersion','mi3d-pose-drive-geometry-v2'),
                          ('persistenceTextureSha256','wrong'),('verifiedPersistenceTexturePath',None)):
            with self.subTest(field=key,value=value):
                changed=dict(build);changed[key]=value;self.assertEqual(check(changed),1)

    def test_archive_report_budget_accepts_complete_large_report(self):
        base = json.dumps(ordinary_fixture(), separators=(',', ':')).encode()
        # Real main()/tar route at observed native report sizes, using synthetic values only.
        for size in (3_162_699, 8_405_488):
            with self.subTest(size=size):
                payload = base + b' ' * (size - len(base))
                rc, calls, _ = self._archive_main([(tarfile.REGTYPE, payload, None)], pixel=True)
                self.assertEqual((0, 1), (rc, calls))

    def test_archive_budget_boundary_and_bounded_read(self):
        payload = json.dumps(ordinary_fixture(), separators=(',', ':')).encode()
        limit = 32 * 1024 * 1024  # Test-owned declared resource budget, not imported from verifier.
        png_reads = [len(data) + 1 for _, data, _ in pixel_entries()]
        for size in (len(payload), limit):
            with self.subTest(size=size):
                rc, calls, reads = self._archive_main(
                    [(tarfile.REGTYPE, payload + b' ' * (size - len(payload)), None)], pixel=True)
                self.assertEqual((0, 1), (rc, calls))
                self.assertEqual([limit + 1] + png_reads, reads)
        rc, calls, reads = self._archive_main([(tarfile.REGTYPE, payload + b' ' * (limit + 1 - len(payload)), None)])
        self.assertEqual((1, 0, []), (rc, calls, reads))

    def test_joint_archive_budget_requires_explicit_mode_and_keeps_bounded_read(self):
        joint = load('mi3d_joint_fixture', ROOT / 'scripts/test/test_model_import_3d_joint_pose.py').fixture()['jointPose']
        report = ordinary_fixture()
        report.update(mi3dMode='joint-pose', jointPose=joint)
        payload = json.dumps(report, separators=(',', ':')).encode()
        limit = 128 * 1024 * 1024  # Independent joint-only budget; ordinary budget stays 32 MiB.
        observed_size = 77_228_895
        large = payload + b' ' * (observed_size - len(payload))
        rc, calls, reads = self._archive_main([(tarfile.REGTYPE, large, None)], pixel=True,
                                              expected_mode='joint-pose')
        self.assertEqual((0, 1), (rc, calls))
        self.assertEqual(limit + 1, reads[0])
        for mode in (None, 'normal'):
            rc, calls, reads = self._archive_main([(tarfile.REGTYPE, large, None)], expected_mode=mode)
            self.assertEqual((1, 0, []), (rc, calls, reads))
        # A different report mode must not gain semantic admission from the larger budget.
        wrong = json.dumps(ordinary_fixture(), separators=(',', ':')).encode()
        rc, calls, reads = self._archive_main([(tarfile.REGTYPE, wrong, None)], expected_mode='joint-pose')
        self.assertEqual((1, 0, [limit + 1]), (rc, calls, reads))
        for size, expected in ((limit, (0, 1)), (limit + 1, (1, 0))):
            rc, calls, reads = self._archive_main(
                [(tarfile.REGTYPE, payload + b' ' * (size - len(payload)), None)], pixel=True,
                expected_mode='joint-pose')
            self.assertEqual(expected, (rc, calls))
            if size > limit:
                self.assertEqual([], reads)

    def test_pixel_archive_inventory_rejections(self):
        payload = json.dumps(ordinary_fixture(), separators=(',', ':')).encode()
        report = (tarfile.REGTYPE, payload, None)
        good = pixel_entries()
        pose_names = [name for _, _, name in good]
        cases = [
            good[:-1],                                                     # missing member
            good + [good[0]],                                              # duplicate name
            good + [(tarfile.REGTYPE, b'x', pose_names[0][:-4] + '-x.png')],  # extra PNG
            good + [(tarfile.REGTYPE, b'x', pose_names[0] + '.alias.png')],   # alias PNG
            [(kind, b'', pose_names[0]) for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE)][0:1]
            + good[1:],                                                    # symlink member
            [(kind, b'', pose_names[0]) for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE)][1:2]
            + good[1:],                                                    # hardlink member
        ]
        for i, entries in enumerate(cases):
            with self.subTest(i=i):
                rc, calls, _ = self._archive_main([report] + entries)
                self.assertEqual((1, 0), (rc, calls))

    def test_archive_identity_type_and_json_rejections(self):
        payload = json.dumps(ordinary_fixture()).encode(); report = (tarfile.REGTYPE, payload, None)
        cases = [[], [report, report], [(tarfile.REGTYPE, payload, 'other/report.json')],
                 *[[(kind, b'', None)] for kind in (tarfile.DIRTYPE, tarfile.SYMTYPE, tarfile.LNKTYPE)],
                 *[[(tarfile.REGTYPE, data, None)] for data in (b'', b'{', b'{"x":1,"x":2}', b'{"x":NaN}')]]
        for i, entries in enumerate(cases):
            with self.subTest(i=i):
                rc, calls, _ = self._archive_main(entries); self.assertEqual((1, 0), (rc, calls))
        rc, calls, _ = self._archive_main([report], truncate=True); self.assertEqual((1, 0), (rc, calls))
        # A well-formed but semantically incomplete report reaches the unchanged oracle and fails.
        incomplete = ordinary_fixture(); incomplete.pop('order')
        rc, calls, _ = self._archive_main([(tarfile.REGTYPE, json.dumps(incomplete).encode(), None)],
                                         pixel=True)
        self.assertEqual((1, 1), (rc, calls))

    def test_catalog_is_only_5302(self):
        task = json.loads((ROOT / 'scripts/preview/host-validation-tasks.json').read_text())['tasks']['model-import-3d']
        matrix = json.loads((ROOT / 'compatibility/cubism/compatibility-matrix.json').read_text())
        expected = [v.replace('.', '') for v in matrix['reviewedVersions']]
        self.assertEqual(expected, task['versions']); self.assertEqual({'host-slot': 1}, task['resources'])
        self.assertEqual(['{version}', '{runLabel}'], task['arguments'])

    def test_wrapper_rejects_overrides_before_any_runner(self):
        wrapper = ROOT / 'scripts/preview/run-model-import-3d-host-validation.sh'
        for args in (['9999', 'test'], ['5302', '../test'], ['5302', 'test', '--version', '5303'],
                     ['5302', 'test', '--fixture-host', '/tmp/other'], ['5302', 'test', '--prepare-dir']):
            result = subprocess.run(['bash', str(wrapper), *args], capture_output=True)
            self.assertEqual(2, result.returncode, result.stderr)

    def test_wrapper_exit_diagnostic_env_gate(self):
        wrapper = ROOT / 'scripts/preview/run-model-import-3d-host-validation.sh'
        with tempfile.TemporaryDirectory() as temp:
            temp = Path(temp)
            fixture = temp / 'fixture.cmo3'; fixture.write_bytes(b'fixture')
            cmo3 = temp / 'saved.cmo3'; cmo3.write_bytes(b'cmo3')
            base_env = dict(os.environ,
                            TURBOISM_ENV_FILE=str(temp / 'missing.env'),
                            TURBOISM_HOST_VALIDATION_FIXTURE_5302=str(fixture),
                            TURBOISM_MI3D_BUILD_RUN=str(temp / 'build-run'),
                            TURBOISM_MI3D_REOPEN_CMO3=str(cmo3))
            base_env.pop('TURBOISM_MI3D_EXIT_DIAGNOSTIC', None)
            for label, env_extra in (
                ('bad-value-yes', {'TURBOISM_MI3D_EXIT_DIAGNOSTIC': 'yes'}),
                ('bad-value-0', {'TURBOISM_MI3D_EXIT_DIAGNOSTIC': '0'}),
                ('normal-mode', {'TURBOISM_MI3D_EXIT_DIAGNOSTIC': '1'}),
            ):
                env = dict(base_env, **env_extra)
                env.pop('TURBOISM_MI3D_MODE', None)
                result = subprocess.run(['bash', str(wrapper), '5302', 'test', '--dry-run'],
                                        env=env, capture_output=True, text=True)
                self.assertEqual(2, result.returncode, f'{label}: {result.stderr}')
            for mode in ('persistence-save', 'persistence-reopen', 'cube-persistence-save', 'cube-persistence-reopen'):
                env = dict(base_env, TURBOISM_MI3D_MODE=mode,
                           TURBOISM_MI3D_EXIT_DIAGNOSTIC='1')
                result = subprocess.run(['bash', str(wrapper), '5302', 'test', '--dry-run'],
                                        env=env, capture_output=True, text=True)
                self.assertNotEqual(2, result.returncode,
                                    f'{mode} accepted path must not be rejected: {result.stderr}')
            env = dict(base_env, TURBOISM_MI3D_MODE='persistence-save',
                       TURBOISM_MI3D_EXIT_DIAGNOSTIC='1')
            env.pop('TURBOISM_MI3D_REOPEN_CMO3', None)
            result = subprocess.run(['bash', str(wrapper), '5302', 'test', '--dry-run'],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(2, result.returncode, result.stderr)

    @unittest.skipUnless(HAS_PRESERVED_BUILD,
                         'set TURBOISM_MI3D_BUILD_RUN to an authorized preserved build run')
    def test_wrapper_exit_diagnostic_forwarded_on_persistence_leg(self):
        run_dir = PRESERVED_BUILD
        wrapper = ROOT / 'scripts/preview/run-model-import-3d-host-validation.sh'
        with tempfile.TemporaryDirectory() as temp:
            temp = Path(temp)
            fixture = temp / 'fixture.cmo3'; fixture.write_bytes(b'fixture')
            env = dict(os.environ,
                       TURBOISM_ENV_FILE=str(temp / 'missing.env'),
                       TURBOISM_HOST_VALIDATION_FIXTURE_5302=str(fixture),
                       TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX=str(temp / 'golden'),
                       TURBOISM_HOST_VALIDATION_HOST_ROOT=str(temp / 'host'),
                       TURBOISM_HOST_VALIDATION_PROTON_RUNNER=str(temp / 'proton'),
                       TURBOISM_MI3D_BUILD_RUN=str(run_dir),
                       TURBOISM_MI3D_MODE='persistence-save',
                       TURBOISM_MI3D_EXIT_DIAGNOSTIC='1')
            result = subprocess.run(['bash', str(wrapper), '5302', 'test', '--dry-run'],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('windowsEnvironmentCount=1\n', result.stdout)
            self.assertIn('windowsEnvironment.0=TURBOISM_MI3D_EXIT_DIAGNOSTIC=1\n',
                          result.stdout)
            self.assertIn('jvmOption.', result.stdout)
            self.assertIn('-Dturboism.validation.mi3d.persistenceSave=true', result.stdout)

    def test_build_output_old_traversal_symlink_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); out = root / 'build/model-import-3d-host'; out.mkdir(parents=True)
            (out / 'old').mkdir(); sentinel = out / 'old/sentinel'; sentinel.write_bytes(b'preserve')
            (out / 'link').symlink_to(out / 'old', target_is_directory=True)
            (out / 'dangling').symlink_to(out / 'absent', target_is_directory=True)
            with patch.object(BUILD, 'ROOT', root):
                for name in ('old', '../old', 'link', 'dangling'):
                    with self.subTest(name=name), self.assertRaises(ValueError):
                        BUILD.build(argparse.Namespace(run=name, sdk='/no/sdk', agent='/no/agent', dry_run=False))
            self.assertEqual(b'preserve', sentinel.read_bytes())

    def test_build_dry_run_no_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); sdk = root / 'build/sdk.jar'; sdk.parent.mkdir(); sdk.write_bytes(b'synthetic SDK')
            agent = root / 'build/agent.jar'; agent.write_bytes(b'synthetic agent')
            relative = Path('validation/model-import-3d-host-probe')
            for name in ('native-src/Test.java', 'native-test/Test.java', 'packaging-test/Test.java', 'build-host.py', 'verify-host.py', 'verify-evaluation.py', 'verify-order.py', 'verify-morph.py', 'verify-morph-order.py', 'verify-image-binding.py', 'verify-pixel-sampling.py', 'verify-persistence-pair.py', 'verify-atlas-persistence.py', 'test-atlas-persistence.py', 'verify-shape-import.py', 'verify-joint-pose.py', 'verify-warp-authoring.py', 'verify-source-warp-candidate.py', 'verify-cube-geometry.py', 'verify-cube-render.py', 'verify-whole-import.py', 'README.md'):
                p = root / relative / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text('synthetic')
            for name in ('plugins/model-import-3d/src/test/java/dev/turboism/validation/modelimport3d/host/PlanarImportTest.java', 'plugins/model-import-3d/src/test/java/dev/turboism/validation/modelimport3d/host/EmbeddedTextureTest.java', 'scripts/preview/run-model-import-3d-host-validation.sh', 'scripts/preview/host-validation-tasks.json',
                         'scripts/test/test_model_import_3d_host_validation.py', 'scripts/test/test_model_import_3d_evaluation.py',
                         'scripts/test/test_model_import_3d_order.py', 'scripts/test/test_model_import_3d_morph.py',
                         'scripts/test/test_model_import_3d_morph_order.py',
                         'scripts/test/test_model_import_3d_image_binding.py',
                         'scripts/test/test_model_import_3d_pixel_sampling.py',
                         'scripts/test/test_model_import_3d_persistence.py',
                         'scripts/test/test_model_import_3d_shape_import.py',
                         'scripts/test/test_model_import_3d_joint_pose.py', 'scripts/test/test_model_import_3d_cube_geometry.py', 'scripts/test/test_model_import_3d_cube_render.py', 'scripts/test/test_model_import_3d_whole_import.py'):
                p = root / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text('synthetic')
            before = sorted(str(p.relative_to(root)) for p in root.rglob('*'))
            with patch.object(BUILD, 'ROOT', root), patch('builtins.print'):
                BUILD.build(argparse.Namespace(run='dry', sdk=str(sdk), agent=str(agent), dry_run=True))
            self.assertEqual(before, sorted(str(p.relative_to(root)) for p in root.rglob('*')))

    @unittest.skipUnless(HAS_PRESERVED_BUILD,
                         'set TURBOISM_MI3D_BUILD_RUN to an authorized preserved build run')
    def test_build_only_real_run_and_missing_t008_dependency(self):
        # Real build output, not dry-run/mock: verify_build must accept the
        # newest actual PASS run and must reject a copy missing a T008 file.
        host = PRESERVED_BUILD.parent
        real = PRESERVED_BUILD
        self.assertIsNotNone(VERIFY.verify_build(real))
        dropped = 'validation/model-import-3d-host-probe/verify-pixel-sampling.py'
        pixel_class = 'dev/turboism/validation/modelimport3d/host/PixelSamplingProbe.class'

        def mutant_copy():
            # A copied run is a DIFFERENT run directory: b['run'] must be
            # rebound or the copy dies at the build-identity gate before the
            # inventory/T008 gates under test are even reached.
            m = Path(tempfile.mkdtemp(prefix='verify-regression-', dir=host))
            shutil.copytree(real, m, dirs_exist_ok=True)
            build_json_path = m / 'provenance/build.json'
            b = json.loads(build_json_path.read_text())
            b['run'] = m.name
            build_json_path.write_text(json.dumps(b))
            return m

        # (a) provenance source file removed with manifest intact: strict
        # set/count gate (the controller's failing check) must reject.
        mutant = mutant_copy()
        try:
            (mutant / 'provenance/source' / dropped).unlink()
            with self.assertRaisesRegex(ValueError, 'source inventory incomplete'):
                VERIFY.verify_build(mutant)
        finally:
            shutil.rmtree(mutant, ignore_errors=True)

        # (b) T008 manifest entry swapped for a non-T008 file (count still 43,
        # set/hash consistent): explicit T008 source requirement must reject.
        mutant = mutant_copy()
        try:
            manifest_path = mutant / 'provenance/source-manifest.json'
            manifest = json.loads(manifest_path.read_text())
            src = mutant / 'provenance/source' / dropped
            dummy = 'validation/model-import-3d-host-probe/dummy-non-t008.py'
            (mutant / 'provenance/source' / dummy).write_bytes(src.read_bytes())
            manifest[dummy] = manifest.pop(dropped)
            src.unlink()
            manifest_path.write_text(json.dumps(manifest))
            build_json_path = mutant / 'provenance/build.json'
            b = json.loads(build_json_path.read_text())
            b['sourceManifestSha256'] = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
            build_json_path.write_text(json.dumps(b))
            # The exact Warp inventory rejects substituted sources before the legacy T008 gate.
            rejection = 'warp source inventory missing/extra' if b.get('sourceInventoryVersion') else 'T008 source missing'
            with self.assertRaisesRegex(ValueError, rejection):
                VERIFY.verify_build(mutant)
        finally:
            shutil.rmtree(mutant, ignore_errors=True)

        # (c) probe JAR without PixelSamplingProbe.class must be rejected at the
        # T008 class gate: the packaging hash/class-inventory precedents are
        # re-attested so the refusal is attributable, not an earlier hash miss.
        mutant = mutant_copy()
        try:
            jar = mutant / 'bundle/plugins/model-import-3d-host-probe.jar'
            with zipfile.ZipFile(jar) as zin:
                keep = {e.filename: zin.read(e.filename)
                        for e in zin.infolist() if e.filename != pixel_class}
            self.assertNotIn(pixel_class, keep)
            jar.unlink()
            with zipfile.ZipFile(jar, 'w') as out:
                for name, data in keep.items():
                    out.writestr(name, data)
            build_json_path = mutant / 'provenance/build.json'
            b = json.loads(build_json_path.read_text())
            b['probeSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
            b['classes'] = {n: hashlib.sha256(d).hexdigest() for n, d in keep.items()
                            if n.endswith('.class')}
            build_json_path.write_text(json.dumps(b))
            terminal_path = mutant / 'terminal.json'
            t = json.loads(terminal_path.read_text())
            t['probeSha256'] = b['probeSha256']
            terminal_path.write_text(json.dumps(t))
            with self.assertRaisesRegex(ValueError, 'T008 class missing'):
                VERIFY.verify_build(mutant)
        finally:
            shutil.rmtree(mutant, ignore_errors=True)

    def test_final_lifecycle_positive_and_mutations(self):
        identity = {'jobId': 'job', 'attemptId': 'attempt', 'runId': 'synthetic', 'preparedDigest': 'digest'}
        binding = {'cgroupPath': '/synthetic.scope', 'cgroupDevice': 1, 'cgroupInode': 2, 'bootId': 'boot'}
        kernel = {**binding, 'originalCgroupBound': True, 'errors': [], 'processTimedOut': False,
                  'processReturncode': 0, 'finalReading': {'kind': 'same', 'populated': 0}}
        artifacts = [{'sourceSha256': s, 'stagedSha256': s} for s in ('agent-hash', 'probe-hash')]
        checks = {'goldenJarSha256': VERIFY.JAR_SHA, 'clonedJarSha256': VERIFY.JAR_SHA,
                  'goldenBatSha256': 'bat', 'clonedBatSha256': 'bat', 'fixtureSourceSha256': 'fixture',
                  'fixtureCopySha256': 'fixture', 'stagedArtifacts': artifacts, 'terminalResult': {'passed': True}}
        life = {'schemaVersion': 1, **identity, 'finalizedBy': 'contained-supervisor', 'terminalState': 'succeeded',
                'validationStatus': 'PASS', 'cleanup': 'safe', 'identityVerified': True, 'fixtureUnchanged': True,
                'normalExit': True, 'runnerExitCode': 0,
                'containment': {'schemaVersion': 1, **identity, **binding, 'cleanup': 'safe', 'scopeUnit': 'synthetic.scope', 'kernelProof': kernel},
                'details': {'taskOwnedCleanup': True, 'validationComplete': True, 'postContainmentChecks': checks,
                            'goldenBatBeforeSha256': 'bat', 'clonedBatBeforeSha256': 'bat',
                            'sourceFixtureBeforeSha256': 'fixture', 'fixtureBeforeSha256': 'fixture'}}
        build = {'agentSha256': 'agent-hash', 'probeSha256': 'probe-hash'}
        VERIFY.verify_lifecycle(life, 'synthetic', build)
        changes = [lambda doc: doc.__setitem__('finalizedBy', 'runner'), lambda doc: doc.__setitem__('normalExit', False),
                   lambda doc: doc['containment'].__setitem__('jobId', 'other'),
                   lambda doc: doc['containment']['kernelProof']['finalReading'].__setitem__('populated', 1),
                   lambda doc: doc['details']['postContainmentChecks'].__setitem__('clonedJarSha256', 'wrong'),
                   lambda doc: doc['details']['postContainmentChecks'].__setitem__('fixtureCopySha256', 'changed'),
                   lambda doc: doc['details']['postContainmentChecks'].__setitem__('stagedArtifacts', [])]
        for mutation in changes:
            value = copy.deepcopy(life); mutation(value)
            with self.assertRaises(ValueError): VERIFY.verify_lifecycle(value, 'synthetic', build)

    def test_prepared_inputs_freeze_probe_agent_and_provenance(self):
        sys.path.insert(0, str(ROOT / 'scripts/preview'))
        import host_validation_queue as queue
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); store = queue.Store(root / 'queue'); prepared = queue.PreparedStore(store)
            agent = root / 'agent.jar'; agent.write_bytes(b'synthetic agent, never executed')
            probe = root / 'probe.jar'; probe.write_bytes(b'synthetic probe, never executed')
            evidence = root / 'provenance'; evidence.mkdir()
            sdk = evidence / 'sdk.jar'; sdk.write_bytes(b'synthetic sdk')
            manifest = evidence / 'source-manifest.json'; manifest.write_text('{"source":"synthetic"}')
            request = {'schemaVersion': 1, 'environment': {}, 'argv': ['--name', 'model-import-3d-n2', '--version', '5302',
                '--agent', str(agent), '--plugin', str(probe) + ':model-import-3d-host-probe.jar',
                '--home-dir', str(evidence) + ':state/mi3d-n2-build', '--require-fixture-unchanged']}
            descriptor = prepared.capture(request, ROOT, 'model-import-3d:5302')
            self.assertEqual(3, len(descriptor['sourceInputs']))
            command = prepared.command(descriptor['digest'], root / 'evidence')
            fixed = Path(command[command.index('--home-dir') + 1].split(':', 1)[0])
            sdk.write_bytes(b'changed live SDK'); probe.write_bytes(b'changed live probe')
            self.assertEqual(b'synthetic sdk', (fixed / 'sdk.jar').read_bytes())
            self.assertEqual(descriptor, prepared.load(descriptor['digest']))
            self.assertEqual([], store.jobs())


def load_tests(loader, tests, pattern):
    # Discovery already collects these modules. Preserve the catalog's single-file entrypoint.
    if pattern is not None:
        return tests
    module = load('mi3d_evaluation_tests', Path(__file__).with_name('test_model_import_3d_evaluation.py'))
    tests.addTests(loader.loadTestsFromTestCase(module.EvaluationProtocolTest))
    module = load('mi3d_order_tests', Path(__file__).with_name('test_model_import_3d_order.py'))
    tests.addTests(loader.loadTestsFromTestCase(module.OrderProtocolTest))
    module = load('mi3d_morph_tests', Path(__file__).with_name('test_model_import_3d_morph.py'))
    tests.addTests(loader.loadTestsFromTestCase(module.MorphProtocolTest))
    module = load('mi3d_morph_order_tests', Path(__file__).with_name('test_model_import_3d_morph_order.py'))
    tests.addTests(loader.loadTestsFromTestCase(module.MorphOrderProtocolTest))
    module = load('mi3d_image_binding_tests', Path(__file__).with_name('test_model_import_3d_image_binding.py'))
    tests.addTests(loader.loadTestsFromTestCase(module.ImageBindingProtocolTest))
    module = load('mi3d_shape_import_tests', Path(__file__).with_name('test_model_import_3d_shape_import.py'))
    tests.addTests(loader.loadTestsFromTestCase(module.ShapeImportProtocolTest))
    return tests


if __name__ == '__main__':
    unittest.main()
