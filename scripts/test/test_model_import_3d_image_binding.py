#!/usr/bin/env python3
"""Synthetic N5a image registration/binding protocol controls; no host, queue, or exact-host evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / 'validation/model-import-3d-host-probe'

CHECKS = ['registerBindExecuted', 'handleIdentityFields', 'registrationProjection',
          'bindingHandleArtMesh', 'shareSecondMeshBound', 'rebindNoChange',
          'differentImageRejected', 'lockedTargetRejected', 'deformerParentRejected',
          'oversizePngRejected', 'undersizePngRejected', 'rejectionsZeroMutation',
          'undoRestoresProjection', 'redoRestoresProjection', 'handleFieldsStableAcrossRedo',
          'meshSnapshotUnchanged', 'decodeCountersUnavailable', 'historyPositionRestored',
          'finalStateClean', 'boundInputRegistered', 'boundInputUndoUnbound',
          'boundInputRedoSameImage', 'boundInputRebindUnchanged', 'boundInputShareSameImage',
          'boundInputSecondImage', 'boundInputRejectionsUnchanged']
B = [[16, 24], [272, 24], [272, 152], [16, 152]]
UVS = [[0, 0], [1, 0], [1, 1], [0, 1]]
INDICES = [0, 1, 2, 0, 2, 3]


def mesh_snapshot(mesh):
    return {'artMeshId': mesh, 'sourceGuid': 'src-' + mesh, 'positions': copy.deepcopy(B),
            'uvs': copy.deepcopy(UVS), 'indices': list(INDICES), 'axes': [],
            'cells': [{'formGuid': 'form-' + mesh, 'coordinates': []}],
            'forms': [{'guid': 'form-' + mesh, 'positions': copy.deepcopy(B), 'drawOrder': 500}],
            'morphKeys': []}


def attach(r):
    # Test-owned synthetic values; not the verifier's constants or any Java implementation.
    live = {'rev': 200, 'entries': [], 'entryIds': [], 'position': 0, 'seq': 0}

    def snap():
        return dict(availability='AVAILABLE', documentBindingId='task-doc',
                    managerBindingId='task-manager', generation=1, revision=live['rev'],
                    position=live['position'], entries=list(live['entries']),
                    entryIds=list(live['entryIds']))

    def commit():
        live['seq'] += 1
        live['entries'] = live['entries'][:live['position']] + ['n5a-%d' % live['seq']]
        live['entryIds'] = live['entryIds'][:live['position']] + ['n5a-e-%d' % live['seq']]
        live['position'] += 1; live['rev'] += 1
        return snap()

    def jump(position):
        live['position'] = position; live['rev'] += 1
        return snap()

    p0 = {'rawImages': [], 'modelImageGroups': [], 'textureAtlases': []}
    raw1 = {'id': 'raw-1', 'name': 'mi3d-n5a-a', 'width': 256, 'height': 128}
    img1 = {'id': 'img-1', 'name': 'mi3d-n5a-a', 'width': 256, 'height': 128}
    raw2 = {'id': 'raw-2', 'name': 'mi3d-n5a-b', 'width': 256, 'height': 128}
    img2 = {'id': 'img-2', 'name': 'mi3d-n5a-b', 'width': 256, 'height': 128}
    p1 = {'rawImages': [raw1],
          'modelImageGroups': [{'groupName': 'group-raw-1', 'memo': '', 'modelImages': [img1]}],
          'textureAtlases': []}
    p2 = {'rawImages': [raw1, raw2],
          'modelImageGroups': [{'groupName': 'group-raw-1', 'memo': '', 'modelImages': [img1]},
                               {'groupName': 'group-raw-2', 'memo': '', 'modelImages': [img2]}],
          'textureAtlases': []}

    def handle(mesh, image='img-1', raw='raw-1'):
        return {'scope': 'runtime.editor-model', 'modelId': 'native-task', 'modelGeneration': 11,
                'rawImageId': raw, 'modelImageId': image, 'artMeshId': mesh}

    def bound(mesh, image='img-1'):
        return {'artMeshId': mesh, 'requestedArtMeshId': mesh, 'bound': True,
                'kind': 'MODEL_IMAGE', 'modelId': 'native-task',
                'modelGeneration': 11, 'modelImageId': image, 'textureAtlasId': None}

    def unbound(mesh):
        return {'artMeshId': mesh, 'requestedArtMeshId': mesh, 'bound': False}

    initial = snap()
    setup = []
    for i in range(1, 6):
        before, after = snap(), commit()
        setup.append(dict(id='createMI3D_N5A_%d' % i, historyBefore=before, historyAfter=after))
    reason = ('Core evaluated data is unavailable: Verified Core structural selector '
              'failed safely: cubism.core.model.get-canvas-info (RESOLUTION).')
    before, after = snap(), commit()
    registration = dict(kind='registration', handle=handle('ArtMeshMI3DN5A1'), before=copy.deepcopy(p0),
                        after=copy.deepcopy(p1), historyBefore=before, historyAfter=after,
                        meshSnapshotBefore=mesh_snapshot('ArtMeshMI3DN5A1'), meshSnapshotAfter=mesh_snapshot('ArtMeshMI3DN5A1'),
                        textureIndexBefore='UNAVAILABLE', textureIndexAfter='UNAVAILABLE',
                        boundInputBefore=unbound('ArtMeshMI3DN5A1'), boundInputAfter=bound('ArtMeshMI3DN5A1'),
                        reason=reason)
    undo = dict(kind='undo', history=jump(5), projection=copy.deepcopy(p0),
                meshSnapshot=mesh_snapshot('ArtMeshMI3DN5A1'), textureIndex='UNAVAILABLE', reason=reason,
                boundInput=unbound('ArtMeshMI3DN5A1'))
    redo = dict(kind='redo', history=jump(6), projection=copy.deepcopy(p1),
                meshSnapshot=mesh_snapshot('ArtMeshMI3DN5A1'), textureIndex='UNAVAILABLE', reason=reason,
                boundInput=bound('ArtMeshMI3DN5A1'))
    same = snap()
    rebind = dict(kind='rebind', handle=handle('ArtMeshMI3DN5A1'), before=copy.deepcopy(p1),
                  after=copy.deepcopy(p1), historyBefore=copy.deepcopy(same),
                  historyAfter=copy.deepcopy(same),
                  boundInputBefore=bound('ArtMeshMI3DN5A1'), boundInputAfter=bound('ArtMeshMI3DN5A1'))
    before, after = snap(), commit()
    share = dict(kind='share', handle=handle('ArtMeshMI3DN5A2'), meshSnapshotBefore=mesh_snapshot('ArtMeshMI3DN5A2'),
                 meshSnapshotAfter=mesh_snapshot('ArtMeshMI3DN5A2'), textureIndexBefore='UNAVAILABLE',
                 textureIndexAfter='UNAVAILABLE', reason=reason,
                 boundInputBefore=unbound('ArtMeshMI3DN5A2'), boundInputAfter=bound('ArtMeshMI3DN5A2'),
                 historyBefore=before, historyAfter=after)
    before, after = snap(), commit()
    setup.append(dict(id='registerSecondImage', historyBefore=before, historyAfter=after))

    def rejection(name, exception, mesh=None, before_input=None, after_input=None,
                  other=None):
        state = snap()
        row = dict(kind='rejection', id=name, exception=exception, before=copy.deepcopy(p2),
                   after=copy.deepcopy(p2), historyBefore=copy.deepcopy(state),
                   historyAfter=copy.deepcopy(state))
        if mesh is not None:
            row['boundInputBefore'] = before_input if before_input is not None else unbound(mesh)
            row['boundInputAfter'] = after_input if after_input is not None else copy.deepcopy(
                row['boundInputBefore'])
        if other is not None:
            row['otherMeshBoundInput'] = other
        return row

    rejections = [rejection('differentImage', 'java.lang.IllegalStateException', mesh='ArtMeshMI3DN5A1',
                            before_input=bound('ArtMeshMI3DN5A1'), other=bound('ArtMeshMI3DN5A3', image='img-2'))]
    before, after = snap(), commit()
    setup.append(dict(id='lockMesh4', historyBefore=before, historyAfter=after))
    rejections.append(rejection('locked', 'java.lang.IllegalStateException', mesh='ArtMeshMI3DN5A4'))
    before, after = snap(), commit()
    setup.append(dict(id='createWarp', historyBefore=before, historyAfter=after))
    before, after = snap(), commit()
    setup.append(dict(id='reparentMesh5', historyBefore=before, historyAfter=after))
    rejections += [rejection('deformerParent', 'java.lang.IllegalStateException', mesh='ArtMeshMI3DN5A5'),
                   rejection('oversizePng', 'java.lang.IllegalArgumentException'),
                   rejection('undersizePng', 'java.lang.IllegalArgumentException')]
    counters = dict(kind='decodeCounters', resolution='UNAVAILABLE',
                    reason='private static decode counters have no public SDK channel', failure=None)
    e = dict(schemaVersion='mi3d-native-n5a-v1', status='PASS', failure=None, finallyRestored=True,
             # The host assigns opaque object ids distinct from the logical display
             # names given to create (real r9 handle: ArtMeshMI3DN5A1 for MI3D_N5A_1).
             fixtureMeshes=[{'name': 'MI3D_N5A_%d' % i, 'id': 'ArtMeshMI3DN5A%d' % i}
                            for i in range(1, 6)],
             checks=[dict(id=i, actual=True, status='PASS', evidenceKind='EXACT_HOST') for i in CHECKS],
             samples=[registration, undo, redo, rebind, share] + rejections + [counters],
             setupMutations=setup, initialHistory=initial, restoredHistory=jump(0),
             initialCounts=copy.deepcopy(r['morphOrder']['restoredCounts']),
             restoredCounts=copy.deepcopy(r['morphOrder']['restoredCounts']),
             fixtureCounts={**r['morphOrder']['restoredCounts'], 'meshes': 5},
             initialCanvas=copy.deepcopy(r['morphOrder']['restoredCanvas']),
             restoredCanvas=copy.deepcopy(r['morphOrder']['restoredCanvas']))
    r['textureBinding'] = e
    r['deferred'] = [d for d in r['deferred'] if d['id'] != 'textureBinding']
    return r


def load_protocol():
    spec = importlib.util.spec_from_file_location('mi3d_protocol_for_image_binding',
        Path(__file__).with_name('test_model_import_3d_host_validation.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


class ImageBindingProtocolTest(unittest.TestCase):
    def setUp(self):
        self.protocol = load_protocol(); self.verify = self.protocol.VERIFY
        self.fixture = attach(self.protocol.ordinary_fixture())

    def reject(self, mutation):
        r = copy.deepcopy(self.fixture); mutation(r)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_native(r, 'synthetic')

    def test_positive_and_missing_section(self):
        self.verify.verify_native(self.fixture, 'synthetic')
        r = copy.deepcopy(self.fixture); r.pop('textureBinding')
        with self.assertRaises((ValueError, KeyError)):
            self.verify.verify_native(r, 'synthetic')

    def test_old_report_without_section_never_upgraded(self):
        # A pre-N5a report lacks the section; whether it still claims textureBinding deferred
        # or not, verify_native must never upgrade it to PASS.
        r = self.protocol.ordinary_fixture(); r.pop('textureBinding')
        with self.assertRaises((ValueError, KeyError)):
            self.verify.verify_native(r, 'synthetic')
        r = self.protocol.ordinary_fixture(); r.pop('textureBinding')
        r['deferred'] = [d for d in r['deferred'] if d['id'] != 'textureBinding']
        r['deferred'].append({'id': 'textureBinding', 'status': 'NOT_RUN', 'reason': 'outside N2'})
        with self.assertRaises((ValueError, KeyError)):
            self.verify.verify_native(r, 'synthetic')

    def test_negative_matrix(self):
        mutations = [
            lambda r: r['textureBinding'].update(schemaVersion='mi3d-native-n5a-v0'),
            lambda r: r['textureBinding'].update(status='NOT_RUN'),
            lambda r: r['textureBinding'].update(failure='error'),
            lambda r: r['textureBinding'].update(finallyRestored=False),
            lambda r: r['textureBinding']['checks'].pop(),
            lambda r: r['textureBinding']['checks'][0].update(id='renamed'),
            lambda r: r['textureBinding']['checks'][0].update(status='FAIL'),
            lambda r: r['textureBinding']['checks'][0].update(evidenceKind='OFFLINE_FIXTURE'),
            lambda r: r['textureBinding']['samples'].pop(),
            lambda r: r['textureBinding']['samples'].reverse(),
            lambda r: r['textureBinding']['setupMutations'].pop(),
            lambda r: r['textureBinding']['setupMutations'].__setitem__(5,
                r['textureBinding']['setupMutations'][6]),
            lambda r: r['textureBinding']['initialHistory'].update(position=3),
            lambda r: r['textureBinding']['restoredHistory'].update(position=4),
            lambda r: r['textureBinding']['restoredCounts'].update(meshes=5),
            lambda r: r['textureBinding']['initialCanvas'].update(width=999),
            lambda r: r['textureBinding']['fixtureCounts'].update(meshes=4),
            lambda r: r['textureBinding']['samples'][0]['handle'].update(scope='other.scope'),
            lambda r: r['textureBinding']['samples'][0]['handle'].update(modelId='other-model'),
            lambda r: r['textureBinding']['samples'][0]['handle'].update(modelGeneration=0),
            lambda r: r['textureBinding']['samples'][0]['handle'].update(rawImageId='raw-9'),
            lambda r: r['textureBinding']['samples'][0].update(before={'rawImages': [],
                'modelImageGroups': [], 'textureAtlases': [{'id': 'a', 'name': 'a', 'width': 1,
                'height': 1, 'atlasVersion': 0, 'modelImageCount': 0}]}),
            lambda r: r['textureBinding']['samples'][0]['after']['rawImages'].pop(),
            lambda r: r['textureBinding']['samples'][0]['after']['rawImages'][0].update(width=512),
            lambda r: r['textureBinding']['samples'][0]['after']['modelImageGroups'][0]
                ['modelImages'][0].update(id='img-9'),
            lambda r: r['textureBinding']['samples'][0]['after'].update(textureAtlases=[{'id': 'a',
                'name': 'a', 'width': 1, 'height': 1, 'atlasVersion': 0, 'modelImageCount': 0}]),
            lambda r: r['textureBinding']['samples'][0]['historyAfter'].update(position=7),
            lambda r: r['textureBinding']['samples'][0].update(meshSnapshotAfter=mesh_snapshot('other')),
            lambda r: r['textureBinding']['samples'][1].update(projection={'rawImages': [1],
                'modelImageGroups': [], 'textureAtlases': []}),
            lambda r: r['textureBinding']['samples'][2].update(projection={'rawImages': [],
                'modelImageGroups': [], 'textureAtlases': []}),
            lambda r: r['textureBinding']['samples'][1].update(textureIndex=0),
            lambda r: r['textureBinding']['samples'][3]['handle'].update(artMeshId='ArtMeshMI3DN5A2'),
            lambda r: r['textureBinding']['samples'][3].update(after={'rawImages': [],
                'modelImageGroups': [], 'textureAtlases': []}),
            lambda r: r['textureBinding']['samples'][4]['handle'].update(artMeshId='ArtMeshMI3DN5A1'),
            lambda r: r['textureBinding']['samples'][4]['handle'].update(modelImageId='img-2'),
            lambda r: r['textureBinding']['samples'][5].update(exception='java.lang.IllegalArgumentException'),
            lambda r: r['textureBinding']['samples'][8].update(exception='java.lang.IllegalStateException'),
            lambda r: r['textureBinding']['samples'][5].update(after={'rawImages': [],
                'modelImageGroups': [], 'textureAtlases': []}),
            lambda r: r['textureBinding']['samples'][6]['historyAfter'].update(position=10),
            lambda r: r['textureBinding']['samples'][10].update(resolution='PASS'),
            lambda r: r['textureBinding']['samples'][10].update(reason=''),
            lambda r: r['textureBinding']['samples'][10].update(failure='fabricated'),
            lambda r: r['textureBinding']['samples'][10].update(resolution='UNAVAILABLE',
                reason='present', failure=None, decodeOnceObserved=1),
        ]
        for i, mutation in enumerate(mutations):
            with self.subTest(i=i):
                self.reject(mutation)

    def test_bound_input_observations_are_required_and_typed(self):
        # The typed ArtMeshTextureInput observation is the binding proof: registration
        # must go unbound->MODEL_IMAGE, undo must drop it, redo/share must restore the
        # same image, and fabricated identities/kinds/context must be rejected.
        samples = lambda r: r['textureBinding']['samples']
        mutations = [
            # Missing / fake observations.
            lambda r: samples(r)[0].pop('boundInputAfter'),
            lambda r: samples(r)[1].pop('boundInput'),
            lambda r: samples(r)[4].pop('boundInputAfter'),
            lambda r: samples(r)[0].update(boundInputBefore={
                'artMeshId': 'ArtMeshMI3DN5A1', 'bound': True, 'kind': 'MODEL_IMAGE',
                'modelId': 'native-task', 'modelGeneration': 11,
                'modelImageId': 'img-1', 'textureAtlasId': None}),
            lambda r: samples(r)[1].update(boundInput={
                'artMeshId': 'ArtMeshMI3DN5A1', 'bound': True, 'kind': 'MODEL_IMAGE',
                'modelId': 'native-task', 'modelGeneration': 11,
                'modelImageId': 'img-1', 'textureAtlasId': None}),
            # Wrong target / image / context on real observations.
            lambda r: samples(r)[0]['boundInputAfter'].update(artMeshId='ArtMeshMI3DN5A2'),
            lambda r: samples(r)[0]['boundInputAfter'].update(modelImageId='img-2'),
            lambda r: samples(r)[0]['boundInputAfter'].update(modelId='other-model'),
            lambda r: samples(r)[0]['boundInputAfter'].update(modelGeneration=99),
            lambda r: samples(r)[0]['boundInputAfter'].update(kind='TEXTURE_ATLAS_REGION'),
            lambda r: samples(r)[0]['boundInputAfter'].update(textureAtlasId='atlas-1'),
            lambda r: samples(r)[0]['boundInputAfter'].pop('modelId'),
            # Rebind must not drift; share must report the same image on mesh2.
            lambda r: samples(r)[3]['boundInputAfter'].update(modelImageId='img-2'),
            lambda r: samples(r)[3].update(boundInputBefore={'artMeshId': 'ArtMeshMI3DN5A1',
                'bound': False}),
            lambda r: samples(r)[4]['boundInputAfter'].update(modelImageId='img-2'),
            lambda r: samples(r)[4]['boundInputAfter'].update(artMeshId='ArtMeshMI3DN5A1'),
            # Mesh rejections must leave the target input untouched; png legs must not invent one.
            lambda r: samples(r)[5]['boundInputAfter'].update(modelImageId='img-9'),
            lambda r: samples(r)[5].pop('otherMeshBoundInput'),
            lambda r: samples(r)[5]['otherMeshBoundInput'].update(modelImageId='img-1'),
            lambda r: samples(r)[8].update(boundInputBefore={'artMeshId': 'x',
                'bound': False}),
            # An ERROR observation needs its exception string.
            lambda r: samples(r)[1].update(boundInput={'artMeshId': 'ArtMeshMI3DN5A1',
                'bound': 'ERROR'}),
        ]
        for i, mutation in enumerate(mutations):
            with self.subTest(i=i):
                self.reject(mutation)

    def test_fixture_mesh_inventory_is_required_and_coherent(self):
        # The role->native-id map is the only legitimate source of expected target
        # ids: missing, incomplete, duplicated, or tampered maps must be rejected,
        # and native ids must not be assumed equal to the logical display names.
        meshes = lambda r: r['textureBinding']['fixtureMeshes']
        mutations = [
            lambda r: r['textureBinding'].pop('fixtureMeshes'),
            lambda r: r['textureBinding'].update(fixtureMeshes=[]),
            lambda r: r['textureBinding'].update(fixtureMeshes=meshes(r)[:4]),
            lambda r: r['textureBinding'].update(fixtureMeshes=meshes(r) + [
                {'name': 'MI3D_N5A_6', 'id': 'ArtMeshMI3DN5A6'}]),
            lambda r: meshes(r)[0].update(id=meshes(r)[1]['id']),
            lambda r: meshes(r)[0].update(name='MI3D_N5A_2'),
            lambda r: meshes(r)[0].update(id='forged-other'),
            lambda r: meshes(r)[3].update(name='MI3D_N5A_X'),
            lambda r: meshes(r)[0].pop('id'),
            lambda r: meshes(r).__setitem__(0, 'not-a-row'),
            # A tampered map must not launder a wrong-target observation: point the
            # locked-rejection role at the already-bound first mesh and the leg's
            # still-bound required observation must refuse it.
            lambda r: meshes(r)[3].update(id='ArtMeshMI3DN5A1'),
            # Ids equal to logical names (the old bug shape) still verify only when
            # every observation consistently carries them — mixed truth fails.
            lambda r: [m.update(id=m['name']) for m in meshes(r)],
        ]
        for i, mutation in enumerate(mutations):
            with self.subTest(i=i):
                self.reject(mutation)

    def test_opaque_host_ids_rename_cleanly(self):
        # Metamorphic positive: consistently rewriting every native target id is
        # semantically identical and must keep verifying (controller r13 control).
        r = copy.deepcopy(self.fixture)
        changes = {}
        def rewrite(value):
            if isinstance(value, dict):
                for key, item in list(value.items()):
                    if key in ('artMeshId', 'requestedArtMeshId') and isinstance(item, str):
                        changes[item] = 'HostAssigned-' + item
                        value[key] = 'HostAssigned-' + item
                    else:
                        rewrite(item)
            elif isinstance(value, list):
                for item in value:
                    rewrite(item)
        rewrite(r['textureBinding'])
        for row in r['textureBinding']['fixtureMeshes']:
            if row['id'] in changes:
                row['id'] = changes[row['id']]
        self.verify.verify_native(r, 'synthetic')

    def test_java_serialized_report_passes_oracle(self):
        # Real NativeReport.json output of the SDK-stub probe run feeds the real oracle:
        # compile the probe + fixture test against sdk sources, dump the section via the
        # mi3d.imageBindingReport property, then run verify_image_binding on it.
        if subprocess.run(['javac', '-version'], capture_output=True).returncode != 0:
            self.fail('javac required for the Java->oracle serialization loop')
        with tempfile.TemporaryDirectory() as temp:
            classes = Path(temp) / 'classes'; classes.mkdir()
            dump = Path(temp) / 'image-binding-report.json'
            sources = [str(p) for base in ('native-src', 'native-test', 'input-src', 'input-test')
                       for p in (PROBE / base).rglob('*.java')]
            build = subprocess.run(
                ['javac', '--release', '17', '-encoding', 'UTF-8',
                 '-cp', str(ROOT / 'sdk/src/main/java'), '-d', str(classes), *sources],
                capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            run = subprocess.run(
                ['java', '-Djava.awt.headless=true',
                 '-Dmi3d.imageBindingReport=' + str(dump), '-cp', str(classes),
                 'dev.turboism.validation.modelimport3d.host.HostAssertionsTest'],
                capture_output=True, text=True, timeout=300)
            self.assertEqual(0, run.returncode, run.stderr + run.stdout)
            report = json.loads(dump.read_text())
        self.protocol.VERIFY.IMAGE_BINDING['verify_image_binding'](
            report, self.protocol.VERIFY.snapshot, self.protocol.VERIFY.EVALUATION)
        forged = copy.deepcopy(report)
        forged['textureBinding']['samples'][1]['boundInput'] = {
            'artMeshId': report['textureBinding']['samples'][0]['handle']['artMeshId'],
            'bound': True, 'kind': 'MODEL_IMAGE', 'modelId': 'editor-fixture',
            'modelGeneration': 11, 'modelImageId': 'image-1', 'textureAtlasId': None}
        with self.assertRaises(ValueError):
            self.protocol.VERIFY.IMAGE_BINDING['verify_image_binding'](
                forged, self.protocol.VERIFY.snapshot, self.protocol.VERIFY.EVALUATION)

    def test_decode_counters_never_fabricated(self):
        # The frozen contract: the only accepted value is an honest UNAVAILABLE observation.
        for bad in ('PASS', 'OBSERVED', 'EXACT_HOST'):
            self.reject(lambda r, bad=bad: r['textureBinding']['samples'][10].update(resolution=bad))

    def test_texture_index_is_never_observable_on_the_editor_path(self):
        # Host finding (run queue-7003eea1): the borrowed model is the Editor document
        # CModel, never a public CubismModel, so Core textureIndex cannot be observed
        # through this probe. Any int leg is fabricated data, not an observation:
        # every leg must report 'UNAVAILABLE' with a non-empty reason.
        self.verify.verify_native(self.fixture, 'synthetic')
        for fake in (-1, 0, 999999, 'not-an-int'):
            self.reject(lambda r, fake=fake: r['textureBinding']['samples'][0].update(
                textureIndexBefore=fake))
            self.reject(lambda r, fake=fake: r['textureBinding']['samples'][1].update(
                textureIndex=fake))
            self.reject(lambda r, fake=fake: r['textureBinding']['samples'][4].update(
                textureIndexAfter=fake))
        self.reject(lambda r: r['textureBinding']['samples'][0].update(reason=''))
        self.reject(lambda r: r['textureBinding']['samples'][0].pop('reason'))


if __name__ == '__main__':
    unittest.main()
