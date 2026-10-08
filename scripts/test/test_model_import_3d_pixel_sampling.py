#!/usr/bin/env python3
"""Synthetic T008 pixel-sampling oracle controls; no host, queue, or exact-host evidence."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import unittest
import zlib

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / 'validation/model-import-3d-host-probe'

OUT = 1024
BX, BY = 256, 384
POSES = ('baseline', 'bound-p0', 'bound-p1', 'bound-m1', 'unbound', 'rebound', 'blank')
CHECKS = ['canvasEstablished', 'fixtureCommitted', 'shiftKeysMaterialized',
          'keyformsCommitted', 'baselineCaptured', 'bindExecuted', 'boundPatchesP0',
          'boundDiffersFromBaseline', 'boundInputTyped', 'boundPatchesPos', 'boundPatchesNeg',
          'oracleRejectsStaleShift', 'oracleRejectsShiftedAtZero',
          'unboundEqualsBaseline', 'unboundInputEmpty',
          'reboundEqualsBound', 'reboundInputTyped',
          'historyPositionRestored', 'restoredObjectsAndCanvas', 'blankFrameAfterRestore']
# Canvas contract: the frozen 1024x1024 canvas is established by the typed
# command BEFORE fixture geometry; the whole-leg Undo restores the initial one.
INITIAL_CANVAS = {'width': 1000.0, 'height': 2000.0, 'originX': -500.0,
                  'originY': 1000.0, 'pixelsPerUnit': 1.0}
SETUP_CANVAS = {'width': 1024.0, 'height': 1024.0, 'originX': -512.0,
                'originY': 512.0, 'pixelsPerUnit': 1.0}
COUNTS = {'meshes': 0, 'deformers': 0, 'parameters': 0, 'parts': 0}
QUADS = ((0, 0, 128, 64, (226, 59, 59, 255)), (128, 0, 256, 64, (59, 178, 90, 255)),
         (0, 64, 128, 128, (59, 92, 226, 255)), (128, 64, 256, 128, (240, 200, 50, 255)))
POSE_PARAM = {'baseline': 0.0, 'bound-p0': 0.0, 'bound-p1': 1.0, 'bound-m1': -1.0,
              'unbound': 0.0, 'rebound': 0.0, 'blank': None}
POSE_POSITION = {'baseline': 2, 'bound-p0': 4, 'bound-p1': 4, 'bound-m1': 4,
                 'unbound': 3, 'rebound': 4, 'blank': 0}
BOUND_POSES = frozenset({'bound-p0', 'bound-p1', 'bound-m1', 'rebound'})
SOURCE_POSITIONS = [[256.0, 384.0], [768.0, 384.0], [768.0, 640.0], [256.0, 640.0]]
SOURCE_UVS = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]
MESH_ID = 'artmesh-t008-fixture'
MODEL_ID = 'model-t008-fixture'
IMAGE_ID = 'modelimage-t008-fixture'
DOC_ID = 'doc-t008-fixture'
MGR_ID = 'mgr-t008-fixture'


def material():
    img = bytearray(256 * 128 * 4)
    for x0, y0, x1, y1, c in QUADS:
        for y in range(y0, y1):
            for x in range(x0, x1):
                i = (y * 256 + x) * 4
                img[i:i + 4] = bytes(c)
    for y in range(24, 37):
        for x in range(28, 65):
            i = (y * 256 + x) * 4
            img[i:i + 4] = b'\x10\x10\x10\xff'
    # Coarse right arrow head (64,12)-(88,30)-(64,48), rasterized by scanline.
    for y in range(12, 49):
        d = abs(y - 30)
        x1 = 88 - int(round(24 * d / 18.0))
        for x in range(64, x1 + 1):
            i = (y * 256 + x) * 4
            img[i:i + 4] = b'\x10\x10\x10\xff'
    return img


def png(width, height, rgba):
    def chunk(kind, data):
        c = struct.pack('>I', len(data)) + kind + data
        return c + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
    raw = b''.join(b'\x00' + bytes(rgba[y * width * 4:(y + 1) * width * 4])
                   for y in range(height))
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


def frame(material_img, shift=0, blank=False):
    img = bytearray(OUT * OUT * 4)
    if blank:
        return img
    for sy in range(128):
        for sx in range(256):
            si = (sy * 256 + sx) * 4
            px, py = sx * 2, sy * 2
            for dy in range(2):
                for dx in range(2):
                    x, y = BX + px + dx + shift, BY + py + dy
                    i = (y * OUT + x) * 4
                    img[i:i + 4] = material_img[si:si + 4]
    return img


def _history(position):
    return {'availability': 'AVAILABLE', 'generation': 1,
            'position': position, 'documentBindingId': DOC_ID,
            'managerBindingId': MGR_ID,
            'canUndo': position > 0, 'canRedo': position < 4,
            'entries': [{'index': i, 'label': 'entry-%d' % i, 'significant': True,
                         'entryId': 'history-%d' % i, 'transactionId': 'tx-%d' % i}
                        for i in range(4)]}


def _mesh(param_value):
    shift = 80.0 * (param_value or 0.0)
    evaluated = [[x + shift, y] for x, y in SOURCE_POSITIONS]
    return {'id': MESH_ID,
            'sourcePositions': copy.deepcopy(SOURCE_POSITIONS),
            'sourceUvs': copy.deepcopy(SOURCE_UVS),
            'triangles': [0, 1, 2, 0, 2, 3],
            'forms': [{'formGuid': 'form-default',
                       'positions': copy.deepcopy(SOURCE_POSITIONS), 'drawOrder': 500},
                      {'formGuid': 'form-neg', 'positions': [[x - 80.0, y] for x, y in SOURCE_POSITIONS],
                       'drawOrder': 500},
                      {'formGuid': 'form-pos', 'positions': [[x + 80.0, y] for x, y in SOURCE_POSITIONS],
                       'drawOrder': 500}],
            'axes': [{'parameterGuid': 'axis-shift-guid', 'parameterId': 'ParamMI3DPixShift',
                      'keys': [-1.0, 0.0, 1.0]}],
            'cells': [{'formGuid': 'form-neg', 'coordinates': [{'parameterGuid': 'axis-shift-guid', 'value': -1.0}],
                       'defaultKeyform': False},
                      {'formGuid': 'form-default', 'coordinates': [{'parameterGuid': 'axis-shift-guid', 'value': 0.0}],
                       'defaultKeyform': True},
                      {'formGuid': 'form-pos', 'coordinates': [{'parameterGuid': 'axis-shift-guid', 'value': 1.0}],
                       'defaultKeyform': False}],
            'morphKeys': 0,
            'evaluatedPositions': evaluated}


def _state(pose):
    param_value = POSE_PARAM[pose]
    bound = pose in BOUND_POSES
    absent = pose == 'blank'
    return {'history': _history(POSE_POSITION[pose]),
            # The restored model object is still observable during the blank
            # frame; its canvas is the restored initial one.
            'model': MODEL_ID,
            'canvas': copy.deepcopy(INITIAL_CANVAS if absent else SETUP_CANVAS),
            # SDK projections are fresh value objects; only renderType order/value
            # is observable — no native identity is claimed.
            'instances': ['MODELING'],
            'currentInstance': 'MODELING',
            'parameter': (None if param_value is None else
                          {'id': 'ParamMI3DPixShift', 'value': param_value}),
            'mesh': (None if absent else _mesh(param_value)),
            'textureInput': ({'kind': 'MODEL_IMAGE', 'artMeshId': MESH_ID,
                              'modelId': MODEL_ID, 'modelGeneration': 1,
                              'modelImageId': IMAGE_ID, 'textureAtlasId': None}
                             if bound else None),
            'dirty': 'UNAVAILABLE'}


def _frame_row(pose, blob):
    return {'pose': pose, 'width': OUT, 'height': OUT,
            'stateBefore': _state(pose), 'stateAfter': _state(pose),
            'historyRevisionBefore': 9, 'historyRevisionAfter': 9,
            'artifact': 'pixel-sampling/%s.png' % pose,
            'artifactSha256': hashlib.sha256(blob).hexdigest(),
            'artifactBytes': len(blob)}


def section():
    return {'schemaVersion': 'mi3d-native-t008-v1', 'status': 'PASS', 'failure': None,
            'finallyRestored': True, 'coordinateSpace': 'NATIVE_1024_OUTPUT_ARGB',
            'planeBounds': [256, 384, 768, 640], 'channelTolerance': [2, 2, 2, 0],
            'patchRadius': 2, 'canvasCommand': 'EXECUTED',
            'initialCanvas': dict(INITIAL_CANVAS), 'setupCanvas': dict(SETUP_CANVAS),
            'initialCounts': dict(COUNTS), 'restoredCounts': dict(COUNTS),
            'restoredCanvas': dict(INITIAL_CANVAS),
            'checks': [dict(id=i, actual=True, status='PASS', evidenceKind='EXACT_HOST')
                       for i in CHECKS],
            'frames': []}


def blobs():
    mat = material()
    frames = {
        'baseline': png(OUT, OUT, frame(mat, blank=True)),
        'bound-p0': png(OUT, OUT, frame(mat, 0)),
        'bound-p1': png(OUT, OUT, frame(mat, 80)),
        'bound-m1': png(OUT, OUT, frame(mat, -80)),
        'unbound': png(OUT, OUT, frame(mat, blank=True)),
        'rebound': png(OUT, OUT, frame(mat, 0)),
        'blank': png(OUT, OUT, frame(mat, blank=True)),
    }
    # unbound must be pixel-equal to baseline; rebound pixel-equal to bound-p0.
    frames['unbound'] = frames['baseline']
    frames['rebound'] = frames['bound-p0']
    return frames


def fixture():
    frames = blobs()
    r = {'pixelSampling': section()}
    for pose in POSES:
        r['pixelSampling']['frames'].append(_frame_row(pose, frames[pose]))
    return r, frames


def attach(r):
    """N5a-style attach used by the host-validation fixture; r gets the section."""
    report, _ = fixture()
    r['pixelSampling'] = report['pixelSampling']
    return r


def resign(report, blobs_):
    for row in report['pixelSampling']['frames']:
        data = blobs_[row['pose']]
        row['artifactSha256'] = hashlib.sha256(data).hexdigest()
        row['artifactBytes'] = len(data)


def load_verifier():
    spec = importlib.util.spec_from_file_location('mi3d_verify_pixel_sampling',
        PROBE / 'verify-pixel-sampling.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


class PixelSamplingOracleTest(unittest.TestCase):
    def setUp(self):
        self.verify = load_verifier()
        self.report, self.frames = fixture()

    def accept(self):
        self.assertTrue(self.verify.verify_pixel_sampling(self.report, self.frames))

    def reject(self, mutate_report=None, mutate_frames=None):
        r = copy.deepcopy(self.report)
        f = dict(self.frames)
        if mutate_report:
            mutate_report(r)
        if mutate_frames:
            mutate_frames(f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_positive_fixture_passes(self):
        self.accept()

    def test_missing_section_rejected(self):
        self.reject(mutate_report=lambda r: r.pop('pixelSampling'))

    def test_missing_check_rejected(self):
        self.reject(mutate_report=lambda r: r['pixelSampling']['checks'].pop())

    def test_missing_pose_rejected(self):
        self.reject(mutate_frames=lambda f: f.pop('bound-p0'))

    def test_duplicate_frame_list_rejected(self):
        def m(r):
            r['pixelSampling']['frames'].append(copy.deepcopy(r['pixelSampling']['frames'][0]))
        self.reject(mutate_report=m)

    def test_frame_list_reordered_rejected(self):
        def m(r):
            rows = r['pixelSampling']['frames']
            rows[0], rows[1] = rows[1], rows[0]
        self.reject(mutate_report=m)

    def test_history_state_drift_rejected(self):
        def m(r):
            row = r['pixelSampling']['frames'][0]
            row['stateAfter'] = copy.deepcopy(row['stateBefore'])
            row['stateAfter']['history']['documentBindingId'] = 'other-document'
        self.reject(mutate_report=m)

    def test_history_position_only_drift_rejected(self):
        # Same position but a different document — the F3 same-position counterexample.
        def m(r):
            row = r['pixelSampling']['frames'][0]
            row['stateAfter'] = copy.deepcopy(row['stateBefore'])
            row['stateAfter']['history']['documentBindingId'] = 'doc-B'
            row['stateAfter']['history']['managerBindingId'] = 'mgr-B'
        self.reject(mutate_report=m)

    def test_model_drift_rejected(self):
        def m(r):
            row = r['pixelSampling']['frames'][2]
            row['stateAfter'] = copy.deepcopy(row['stateBefore'])
            row['stateAfter']['model'] = 'other-model'
        self.reject(mutate_report=m)

    def test_parameter_pose_mismatch_rejected(self):
        def m(r):
            row = r['pixelSampling']['frames'][2]
            wrong = copy.deepcopy(row['stateBefore'])
            wrong['parameter']['value'] = 0.0
            row['stateBefore'] = wrong
            row['stateAfter'] = copy.deepcopy(wrong)
        self.reject(mutate_report=m)

    def test_mesh_drift_rejected(self):
        def m(r):
            row = r['pixelSampling']['frames'][3]
            drift = copy.deepcopy(row['stateBefore'])
            drift['mesh']['sourceUvs'] = [[0, 0], [0, 0], [0, 0], [0, 0]]
            row['stateBefore'] = drift
            row['stateAfter'] = copy.deepcopy(drift)
        self.reject(mutate_report=m)

    def test_unbound_typed_input_rejected(self):
        def m(r):
            row = r['pixelSampling']['frames'][4]
            bad = copy.deepcopy(row['stateBefore'])
            bad['textureInput'] = {'kind': 'MODEL_IMAGE', 'artMeshId': MESH_ID,
                                   'modelImageId': IMAGE_ID, 'textureAtlasId': None}
            row['stateBefore'] = bad
            row['stateAfter'] = copy.deepcopy(bad)
        self.reject(mutate_report=m)

    def test_dirty_fabricated_rejected(self):
        def m(r):
            row = r['pixelSampling']['frames'][1]
            bad = copy.deepcopy(row['stateBefore'])
            bad['dirty'] = False
            row['stateBefore'] = bad
            row['stateAfter'] = copy.deepcopy(bad)
        self.reject(mutate_report=m)

    def test_sha_mismatch_rejected(self):
        def m(f):
            f['bound-p0'] = f['bound-p1']
        self.reject(mutate_frames=m)

    def test_mirrored_u_rejected(self):
        mat = material()
        flipped = bytearray(mat)
        for y in range(128):
            for x in range(128):
                src = (y * 256 + x) * 4
                dst = (y * 256 + (255 - x)) * 4
                flipped[dst:dst + 4] = mat[src:src + 4]
        def m(f):
            f['bound-p0'] = png(OUT, OUT, frame(flipped, 0))
        self.reject(mutate_frames=m)

    def test_alpha_254_at_sampled_pixel_rejected(self):
        # Re-signed semantic mutation INSIDE a frozen patch — reaches pixel rules.
        mat = material()
        rgba = frame(mat, 0)
        # TL-near-corner patch center: output (289, 417).
        i = ((BY + 2 * 16 + 1) * OUT + (BX + 2 * 16 + 1)) * 4
        rgba[i + 3] = 254
        def m(f):
            f['bound-p0'] = f['rebound'] = png(OUT, OUT, rgba)
        r = copy.deepcopy(self.report)
        f = dict(self.frames); m(f); resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_invalid_ihdr_crc_rejected(self):
        data = bytearray(self.frames['baseline']); data[29] ^= 1
        def m(f):
            f['baseline'] = f['unbound'] = bytes(data)
        r = copy.deepcopy(self.report)
        f = dict(self.frames); m(f); resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_png_trailing_bytes_rejected(self):
        def m(f):
            f['blank'] = f['blank'] + b'JUNK'
        r = copy.deepcopy(self.report)
        f = dict(self.frames); m(f); resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_stale_frame_at_shift_rejected(self):
        def m(f):
            f['bound-p1'] = f['bound-p0']
        r = copy.deepcopy(self.report)
        f = dict(self.frames); m(f); resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_unbound_different_from_baseline_rejected(self):
        mat = material()
        self.reject(mutate_frames=lambda f: f.update(
            {'unbound': png(OUT, OUT, frame(mat, 0))}))

    def test_rebound_different_rejected(self):
        def m(f):
            f['rebound'] = f['bound-p1']
        r = copy.deepcopy(self.report)
        f = dict(self.frames); m(f); resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_blank_not_transparent_rejected(self):
        mat = material()
        self.reject(mutate_frames=lambda f: f.update(
            {'blank': png(OUT, OUT, frame(mat, 0))}))

    def test_check_failed_status_rejected(self):
        def m(r):
            r['pixelSampling']['checks'][5]['status'] = 'FAIL'
        self.reject(mutate_report=m)

    def test_wrong_canvas_rejected(self):
        # A non-blank frame rendered on a non-1024 canvas must be rejected even
        # when the PNG itself would pass — the canvas is part of the protected
        # state tuple, not something the oracle adapts to.
        def m(r):
            for row in r['pixelSampling']['frames']:
                if row['pose'] == 'bound-p0':
                    wrong = copy.deepcopy(SETUP_CANVAS); wrong['width'] = 1000.0
                    row['stateBefore']['canvas'] = wrong
                    row['stateAfter']['canvas'] = wrong
        self.reject(mutate_report=m)

    def test_blank_canvas_not_restored_rejected(self):
        def m(r):
            for row in r['pixelSampling']['frames']:
                if row['pose'] == 'blank':
                    row['stateBefore']['canvas'] = copy.deepcopy(SETUP_CANVAS)
                    row['stateAfter']['canvas'] = copy.deepcopy(SETUP_CANVAS)
        self.reject(mutate_report=m)

    def test_wrong_dimensions_rejected(self):
        self.reject(mutate_frames=lambda f: f.update(
            {'bound-p0': png(512, 512, bytearray(512 * 512 * 4))}))

    def test_oversized_decompression_rejected(self):
        # Small compressed payload that inflates past the exact scanline budget;
        # the oracle must reject before any unbounded flush.
        expected = (OUT * 4 + 1) * OUT
        over = zlib.compress(b'\x00' * (expected + 64), 9)
        def chunk(kind, data):
            c = struct.pack('>I', len(data)) + kind + data
            return c + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
        blob = (b'\x89PNG\r\n\x1a\n'
                + chunk(b'IHDR', struct.pack('>IIBBBBB', OUT, OUT, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', over)
                + chunk(b'IEND', b''))
        r = copy.deepcopy(self.report)
        f = dict(self.frames); f['blank'] = blob; resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_duplicate_ihdr_rejected(self):
        data = bytearray(self.frames['blank'])
        ihdr = bytes(data[8:8 + 25])  # whole first IHDR chunk (12-byte header + 13 data + 4 crc)
        blob = bytes(data[:8]) + ihdr + bytes(data[8:])
        r = copy.deepcopy(self.report)
        f = dict(self.frames); f['blank'] = blob; resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_nonconsecutive_idat_rejected(self):
        def chunk(kind, data):
            c = struct.pack('>I', len(data)) + kind + data
            return c + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
        mat = material()
        rgba = frame(mat, 0)
        raw = b''.join(b'\x00' + bytes(rgba[y * OUT * 4:(y + 1) * OUT * 4])
                       for y in range(OUT))
        packed = zlib.compress(raw, 9)
        half = len(packed) // 2
        blob = (b'\x89PNG\r\n\x1a\n'
                + chunk(b'IHDR', struct.pack('>IIBBBBB', OUT, OUT, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', packed[:half])
                + chunk(b'tEXt', b'x\x00y')           # ancillary gap
                + chunk(b'IDAT', packed[half:])
                + chunk(b'IEND', b''))
        r = copy.deepcopy(self.report)
        f = dict(self.frames); f['blank'] = blob; resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)

    def test_unknown_critical_chunk_rejected(self):
        def chunk(kind, data):
            c = struct.pack('>I', len(data)) + kind + data
            return c + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
        data = self.frames['blank']
        blob = bytes(data[:len(data) - 12]) + chunk(b'FAKE', b'x') + bytes(data[len(data) - 12:])
        r = copy.deepcopy(self.report)
        f = dict(self.frames); f['blank'] = blob; resign(r, f)
        with self.assertRaises((ValueError, KeyError, TypeError, IndexError)):
            self.verify.verify_pixel_sampling(r, f)


if __name__ == '__main__':
    unittest.main()
