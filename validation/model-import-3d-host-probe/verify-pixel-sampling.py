"""Independent T008/SC-003 pixel-sampling oracle. Frozen literals only; never imports
Java constants and never reads expected values from any rendered artifact.

Re-verifies the preserved native PNGs byte-for-byte: the frame inventory is an
exact ordered list of seven uniquely named poses; each frame binds an actual
protected-state tuple (history document/manager/generation/position/entry
content, model/instance/parameter/mesh/binding identities) read before and
after the render inside one observation window — producer booleans are never
trusted. PNGs are parsed strictly: signature, chunk framing, per-chunk CRC,
IHDR constraints, IDAT/IEND structure, bounded zlib decompression to the exact
scanline length and zero trailing bytes.
"""
import hashlib
import json
import struct
import zlib

CHECKS = {'canvasEstablished', 'fixtureCommitted', 'shiftKeysMaterialized',
          'keyformsCommitted', 'baselineCaptured', 'bindExecuted', 'boundPatchesP0',
          'boundDiffersFromBaseline', 'boundInputTyped', 'boundPatchesPos', 'boundPatchesNeg',
          'oracleRejectsStaleShift', 'oracleRejectsShiftedAtZero',
          'unboundEqualsBaseline', 'unboundInputEmpty',
          'reboundEqualsBound', 'reboundInputTyped',
          'historyPositionRestored', 'restoredObjectsAndCanvas', 'blankFrameAfterRestore'}
POSES = ('baseline', 'bound-p0', 'bound-p1', 'bound-m1', 'unbound', 'rebound', 'blank')
# Expected current parameter value bound to each pose, where the parameter exists.
POSE_PARAM = {'baseline': 0.0, 'bound-p0': 0.0, 'bound-p1': 1.0, 'bound-m1': -1.0,
              'unbound': 0.0, 'rebound': 0.0, 'blank': None}
# Poses expected to carry a typed MODEL_IMAGE texture input on the mesh.
BOUND_POSES = frozenset({'bound-p0', 'bound-p1', 'bound-m1', 'rebound'})
OUT = 1024
BX, BY = 256, 384
SHIFT = 80
PATCH = 2
TOL = 2
SAMPLES = (
    ('TL-near-corner', 16, 16, (226, 59, 59, 255)),
    ('TL-right', 104, 32, (226, 59, 59, 255)),
    ('TL-above-marker', 44, 12, (226, 59, 59, 255)),
    ('TL-below-marker', 44, 52, (226, 59, 59, 255)),
    ('marker-shaft', 40, 30, (16, 16, 16, 255)),
    ('marker-head', 72, 30, (16, 16, 16, 255)),
    ('TR-near-corner', 240, 16, (59, 178, 90, 255)),
    ('TR-center', 192, 32, (59, 178, 90, 255)),
    ('TR-inner', 148, 48, (59, 178, 90, 255)),
    ('BL-near-corner', 16, 112, (59, 92, 226, 255)),
    ('BL-center', 64, 96, (59, 92, 226, 255)),
    ('BL-inner', 108, 80, (59, 92, 226, 255)),
    ('BR-near-corner', 240, 112, (240, 200, 50, 255)),
    ('BR-center', 192, 96, (240, 200, 50, 255)),
    ('BR-inner', 148, 80, (240, 200, 50, 255)),
)
OUTSIDE = ((244, 448), (780, 448), (384, 372), (384, 652), (128, 128))
SECTION_MAX = 4 * 1024 * 1024
FRAME_PNG_MAX = 8 * 1024 * 1024   # principled bound: >2x incompressible 1024x1024 RGBA
HISTORY_KEYS = {'availability', 'generation', 'position', 'documentBindingId',
                'managerBindingId', 'canUndo', 'canRedo', 'entries'}
STATE_KEYS = {'history', 'model', 'canvas', 'instances', 'currentInstance',
              'parameter', 'mesh', 'textureInput', 'dirty'}
CANVAS_KEYS = {'width', 'height', 'originX', 'originY', 'pixelsPerUnit'}
COUNTS_KEYS = {'meshes', 'deformers', 'parameters', 'parts'}
FRAME_KEYS = {'pose', 'width', 'height', 'stateBefore', 'stateAfter',
              'historyRevisionBefore', 'historyRevisionAfter',
              'artifact', 'artifactSha256', 'artifactBytes'}
MESH_KEYS = {'id', 'sourcePositions', 'sourceUvs', 'triangles', 'forms', 'axes',
             'cells', 'morphKeys', 'evaluatedPositions'}
# T014 persistence legs (Job A save / Job B reopen).
CMO3_MAX = 32 * 1024 * 1024
SAVE_CHECKS = {'saveGrantAcquired', 'saveCommandExecuted', 'savedFileValid',
               'saveUnchanged', 'postSavePixelsEqual'}
REOPEN_CHECKS = {'reopenedCanvasFrozen', 'persistentParameterResolved',
                 'uniqueMeshResolved', 'reopenedContentFrozen', 'boundPatchesP0',
                 'boundPatchesPos', 'boundPatchesNeg', 'reopenedBoundInputTyped'}
REOPEN_POSES = ('bound-p0', 'bound-p1', 'bound-m1')
REOPEN_PARAM = {'bound-p0': 0.0, 'bound-p1': 1.0, 'bound-m1': -1.0}
MANIFEST_KEYS = {'canvas', 'meshId', 'sourceGuid', 'sourcePositions', 'sourceUvs',
                 'triangles', 'forms', 'axes', 'cells', 'morphKeys', 'parameter',
                 'textureInput', 'defaultForm',
                 'expectedMaterialSha256', 'observedMaterialSha256'}
FORM_KEYS = {'formGuid', 'positions', 'drawOrder'}
AXIS_KEYS = {'parameterGuid', 'parameterId', 'minimum', 'default', 'maximum', 'keys'}
CELL_KEYS = {'formGuid', 'coordinates', 'defaultKeyform'}
PARAM_DOMAIN_KEYS = {'id', 'value', 'minimum', 'maximum', 'default'}
PROCESS_KEYS = {'pid', 'startEpochMillis'}
# F4 honest split: the producer records the digest of the frozen INPUT recipe
# generator; the actual native image read-back digest stays UNAVAILABLE because
# no verified public SDK seam exists. The expected value is frozen here, so an
# arbitrary 64-hex or all-zero digest is rejected outright.
EXPECTED_MATERIAL_SHA256 = 'ec6e7a40115ad314f98e169757ebd92acf5a3219bc92d7bdde6c177edc5643b4'
POST_SAVE_POSE = 'post-save-p0'
POST_SAVE_ARTIFACT = 'persistence/post-save-p0.png'
# Stored/evaluated positions are compared point-wise in Euclidean distance.
POSITION_TOLERANCE = 0.1
# Across-frame invariants inside the mesh tuple (evaluated positions legitimately
# change with the pose parameter and are compared within a frame only).
MESH_INVARIANT = ('id', 'sourcePositions', 'sourceUvs', 'triangles', 'forms',
                  'axes', 'cells', 'morphKeys')
MESH_SOURCE_POSITIONS = [[256.0, 384.0], [768.0, 384.0], [768.0, 640.0], [256.0, 640.0]]
MESH_SOURCE_UVS = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]
MESH_TRIANGLES = [0, 1, 2, 0, 2, 3]
SHIFTED = {-80.0: [[x - 80.0, y] for x, y in MESH_SOURCE_POSITIONS],
           0.0: MESH_SOURCE_POSITIONS,
           80.0: [[x + 80.0, y] for x, y in MESH_SOURCE_POSITIONS]}


def require(condition, message):
    if not condition:
        raise ValueError('T008: ' + message)


def _chunk_rows(data, size=OUT):
    """Strict PNG chunk walk: signature, framing, lengths, CRC on every chunk,
    IHDR first, IEND last, no trailing bytes."""
    require(type(size) is int and 1 <= size <= OUT, 'PNG expected size out of budget')
    require(isinstance(data, (bytes, bytearray)) and 0 < len(data) <= FRAME_PNG_MAX,
            'PNG size out of bounded range')
    require(data[:8] == b'\x89PNG\r\n\x1a\n', 'not a PNG')
    pos = 8
    saw_ihdr = saw_idat = idat_ended = saw_iend = False
    idat = bytearray()
    while True:
        require(pos + 8 <= len(data), 'truncated PNG chunk header')
        length, kind = struct.unpack('>I4s', data[pos:pos + 8])
        require(all(65 <= b <= 90 or 97 <= b <= 122 for b in kind),
                'PNG chunk type not alphabetic')
        require(pos + 12 + length <= len(data), 'PNG chunk overruns buffer')
        chunk = data[pos + 8:pos + 8 + length]
        crc = struct.unpack('>I', data[pos + 8 + length:pos + 12 + length])[0]
        require((zlib.crc32(kind + chunk) & 0xFFFFFFFF) == crc,
                'PNG chunk CRC mismatch: ' + kind.decode('ascii'))
        if not saw_ihdr:
            require(kind == b'IHDR' and length == 13, 'IHDR not the first 13-byte chunk')
        require(not saw_iend, 'data after IEND')
        if kind == b'IHDR':
            require(not saw_ihdr, 'duplicate IHDR')
            saw_ihdr = True
            width, height, depth, color, comp, filt, interlace = struct.unpack('>IIBBBBB', chunk)
            require(width == size and height == size, 'PNG dimensions differ from expected size')
            require(depth == 8 and color == 6, 'PNG is not RGBA8')
            require(comp == 0 and filt == 0 and interlace == 0,
                    'unsupported PNG compression/filter/interlace')
        elif kind == b'IDAT':
            require(not idat_ended, 'non-consecutive IDAT')
            saw_idat = True
            idat += chunk
        elif kind == b'IEND':
            require(length == 0, 'IEND not empty')
            saw_iend = True
        else:
            # Unknown critical chunks (uppercase type letter) fail closed;
            # ancillary chunks are tolerated but still CRC-verified above.
            require((kind[0] & 0x20) != 0,
                    'unknown critical chunk: ' + kind.decode('ascii'))
            if saw_idat:
                idat_ended = True
        pos += 12 + length
        require(pos <= len(data), 'PNG chunk overrun')
        if saw_iend:
            require(pos == len(data), 'trailing bytes after IEND')
            break
    require(saw_ihdr and saw_iend and len(idat) > 0, 'missing IHDR/IDAT/IEND')
    return bytes(idat)


def _pixels(png_bytes, size=OUT):
    """Decode one preserved PNG into strict RGBA rows; bounded decompression to
    the exact expected scanline payload, no arbitrary megabyte ceiling."""
    idat = _chunk_rows(png_bytes, size)
    stride = size * 4
    expected = (stride + 1) * size
    decompressor = zlib.decompressobj()
    src = decompressor.decompress(idat, expected + 1)
    # Reject over-budget output or unconsumed compressed tail BEFORE any flush —
    # an unbounded flush would release the excess regardless of the read cap.
    require(len(src) <= expected, 'PNG raster exceeds bounded payload')
    require(decompressor.unconsumed_tail == b'',
            'PNG raster exceeds bounded payload (unconsumed compressed tail)')
    src += decompressor.flush(expected - len(src) + 1)
    require(len(src) == expected, 'PNG raster length mismatch')
    require(decompressor.flush(1) == b'', 'PNG raster exceeds bounded payload')
    require(decompressor.eof, 'zlib stream truncated')
    require(decompressor.unconsumed_tail == b'' and decompressor.unused_data == b'',
            'zlib stream has trailing data')
    rows = []
    prev = bytearray(stride)
    off = 0
    for _ in range(size):
        f = src[off]
        line = bytearray(src[off + 1:off + 1 + stride])
        off += stride + 1
        if f == 1:
            for i in range(4, stride):
                line[i] = (line[i] + line[i - 4]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - 4] if i >= 4 else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - 4] if i >= 4 else 0
                b = prev[i]
                c = prev[i - 4] if i >= 4 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        else:
            require(f == 0, 'PNG filter unsupported: ' + str(f))
        rows.append(bytes(line))
        prev = line
    return rows


def _argb(rows, x, y):
    i = 4 * x
    r, g, b, a = rows[y][i], rows[y][i + 1], rows[y][i + 2], rows[y][i + 3]
    return (a, r, g, b)


def patch_failures(rows, shift_x):
    failures = []
    for name, lx, ly, rgba in SAMPLES:
        cx, cy = BX + 2 * lx + 1 + shift_x, BY + 2 * ly + 1
        for py in range(cy - PATCH, cy + PATCH + 1):
            bad = False
            for px in range(cx - PATCH, cx + PATCH + 1):
                a, r, g, b = _argb(rows, px, py)
                if (abs(r - rgba[0]) > TOL or abs(g - rgba[1]) > TOL
                        or abs(b - rgba[2]) > TOL or a != 255):
                    failures.append('%s@%d,%d' % (name, px, py))
                    bad = True
                    break
            if bad:
                break
    return failures


def outside_alpha(rows, shift_x):
    return all(_argb(rows, x + shift_x, y)[0] == 0 for x, y in OUTSIDE)


def all_transparent(rows):
    return all(v == 0 for row in rows for i, v in enumerate(row) if i % 4 == 3)


def _positions_close(actual, expected, where):
    """Point-wise Euclidean comparison within POSITION_TOLERANCE — never a
    set comparison, so vertex order and per-vertex drift are both bound."""
    import math
    require(isinstance(actual, list) and len(actual) == len(expected),
            where + ': position count differs')
    for i, pair in enumerate(zip(actual, expected)):
        a, e = pair
        require(isinstance(a, (list, tuple)) and len(a) == 2
                and all(type(v) in (int, float) for v in a),
                where + ': malformed position ' + str(i))
        require(math.hypot(a[0] - e[0], a[1] - e[1]) <= POSITION_TOLERANCE,
                where + ': position %d outside 0.1px: %r vs %r' % (i, a, e))


def _history_tuple(h, where):
    require(isinstance(h, dict) and set(h) == HISTORY_KEYS, where + ': history field inventory')
    require(h['availability'] == 'AVAILABLE', where + ': history unavailable')
    require(type(h['generation']) is int and h['generation'] >= 0
            and type(h['position']) is int and h['position'] >= 0,
            where + ': invalid history counters')
    require(type(h['canUndo']) is bool and type(h['canRedo']) is bool,
            where + ': history flags not boolean')
    require(isinstance(h['documentBindingId'], str) and h['documentBindingId']
            and isinstance(h['managerBindingId'], str) and h['managerBindingId'],
            where + ': history binding identities missing')
    require(isinstance(h['entries'], list) and h['position'] <= len(h['entries']),
            where + ': history position outside entries')
    for entry in h['entries']:
        require(isinstance(entry, dict)
                and set(entry) == {'index', 'label', 'significant', 'entryId', 'transactionId'}
                and type(entry['index']) is int and isinstance(entry['label'], str)
                and type(entry['significant']) is bool,
                where + ': malformed history entry payload')
    return h


def _canvas(c, where):
    require(isinstance(c, dict) and set(c) == CANVAS_KEYS,
            where + ': canvas tuple inventory')
    require(all(type(c[k]) in (int, float) for k in CANVAS_KEYS),
            where + ': canvas metrics not numeric')
    return c


def _counts(c, where):
    require(isinstance(c, dict) and set(c) == COUNTS_KEYS
            and all(type(c[k]) is int and c[k] >= 0 for k in COUNTS_KEYS),
            where + ': object counts inventory')
    return c


def _state(s, pose, where):
    require(isinstance(s, dict) and set(s) == STATE_KEYS,
            where + ': state tuple inventory')
    require(s['dirty'] == 'UNAVAILABLE',
            where + ': dirty fabricated as a readable value')
    _history_tuple(s['history'], where)
    if s['model'] is None:
        require(s['canvas'] is None and s['instances'] is None
                and s['currentInstance'] is None and s['parameter'] is None,
                where + ': model absent but model-bound fields present')
    else:
        _canvas(s['canvas'], where)
        # SDK projections are fresh value objects; only the ordered renderType
        # sequence and the current renderType value are observable here.
        require(isinstance(s['instances'], list)
                and all(isinstance(i, str) and i for i in s['instances']),
                where + ': instance renderType sequence missing')
        current = s['currentInstance']
        require(current is None or (isinstance(current, str) and current),
                where + ': malformed current-instance renderType')
    return s


def _mesh_invariant_tuple(mesh):
    require(isinstance(mesh, dict) and set(mesh) == MESH_KEYS, 'mesh tuple inventory')
    require(isinstance(mesh['id'], str) and mesh['id'], 'mesh identity missing')
    require(mesh['sourcePositions'] == MESH_SOURCE_POSITIONS
            and mesh['sourceUvs'] == MESH_SOURCE_UVS
            and mesh['triangles'] == MESH_TRIANGLES,
            'frozen source geometry/UV/topology differs')
    require(isinstance(mesh['forms'], list) and len(mesh['forms']) >= 1
            and isinstance(mesh['evaluatedPositions'], list)
            and len(mesh['evaluatedPositions']) == len(mesh['sourcePositions']),
            'stored form/evaluated payload malformed')
    return {k: mesh[k] for k in MESH_INVARIANT}


def verify_pixel_sampling(r, frames):
    """frames: {pose: png bytes} extracted by the caller from the state archive."""
    e = r['pixelSampling']
    require(isinstance(e, dict), 'pixelSampling section missing')
    require(len(json.dumps(e)) <= SECTION_MAX, 'pixelSampling section too large')
    require(e.get('schemaVersion') == 'mi3d-native-t008-v1', 'pixelSampling schema')
    require(e.get('status') == 'PASS' and e.get('failure') is None, 'pixelSampling not PASS')
    require(e.get('finallyRestored') is True, 'pixelSampling restoration flag')
    require(e.get('planeBounds') == [256, 384, 768, 640], 'plane bounds drifted')
    require(e.get('channelTolerance') == [2, 2, 2, 0] and e.get('patchRadius') == 2,
            'frozen tolerance drifted')
    # The frozen canvas is a precondition of the leg, not a fitted observation:
    # the typed command must have executed, the read-back canvas must be
    # 1024x1024, and Undo must have restored the initial canvas object-verbatim.
    require(e.get('canvasCommand') == 'EXECUTED', 'canvas command not executed')
    initial_canvas = _canvas(e.get('initialCanvas'), 'initialCanvas')
    setup_canvas = _canvas(e.get('setupCanvas'), 'setupCanvas')
    require(setup_canvas['width'] == OUT and setup_canvas['height'] == OUT,
            'setup canvas not 1024x1024')
    _counts(e.get('initialCounts'), 'initialCounts')
    _counts(e.get('restoredCounts'), 'restoredCounts')
    require(e.get('restoredCounts') == e.get('initialCounts'),
            'restored object counts differ')
    _canvas(e.get('restoredCanvas'), 'restoredCanvas')
    require(e.get('restoredCanvas') == initial_canvas,
            'restored canvas differs from initial')
    checks = e['checks']
    require({c['id'] for c in checks} == CHECKS and len(checks) == len(CHECKS),
            'pixelSampling check inventory')
    require(all(c['status'] == 'PASS' and c['actual'] is True
                and c['evidenceKind'] == 'EXACT_HOST' for c in checks), 'pixel check failed')

    rows = e['frames']
    # Raw list, exact order and count — never collapsed through a dict.
    require(isinstance(rows, list) and len(rows) == len(POSES),
            'frame list not exactly seven entries')
    require([f['pose'] for f in rows] == list(POSES),
            'frame poses missing/duplicated/out of order')
    require(len(set(f['pose'] for f in rows)) == len(POSES), 'duplicate frame pose')
    require(set(frames) == set(POSES) and len(frames) == len(POSES),
            'PNG blob inventory missing/extra')

    manifest = {}
    mesh_invariant = None
    bound_image = None
    model_id = document_id = manager_id = None
    for row in rows:
        require(isinstance(row, dict) and set(row) == FRAME_KEYS,
                'frame field inventory')
        pose = row['pose']
        require(row['width'] == OUT and row['height'] == OUT, pose + ' dimensions')
        require(row['artifact'] == 'pixel-sampling/%s.png' % pose, pose + ' artifact path')
        png = frames[pose]
        require(hashlib.sha256(png).hexdigest() == row['artifactSha256'],
                pose + ' PNG sha mismatch')
        require(row['artifactBytes'] == len(png), pose + ' PNG size mismatch')
        # Independent before/after comparison of actual captured tuples.
        before = _state(row.get('stateBefore'), pose, pose + '/before')
        after = _state(row.get('stateAfter'), pose, pose + '/after')
        require(before == after, pose + ' protected state changed across render')
        require(type(row.get('historyRevisionBefore')) is int
                and row['historyRevisionBefore'] >= 0
                and type(row.get('historyRevisionAfter')) is int
                and row['historyRevisionAfter'] >= 0,
                pose + ' history revision counters missing')
        history = before['history']
        # Whole-document identities must be stable across every frame that
        # still sees the fixture model (blank is post-restore: model absent).
        for key, value in (('document', history['documentBindingId']),
                           ('manager', history['managerBindingId'])):
            require(isinstance(value, str) and bool(value),
                    pose + ' ' + key + ' identity missing')
        if pose != 'blank':
            require(isinstance(before['model'], str) and bool(before['model']),
                    pose + ' model identity missing')
            require(before['canvas']['width'] == OUT
                    and before['canvas']['height'] == OUT,
                    pose + ' canvas not the frozen 1024x1024')
            if model_id is None:
                model_id, document_id, manager_id = (before['model'],
                    history['documentBindingId'], history['managerBindingId'])
            else:
                require(before['model'] == model_id
                        and history['documentBindingId'] == document_id
                        and history['managerBindingId'] == manager_id,
                        pose + ' document/model identity drifted between frames')
        else:
            require(before['canvas'] == initial_canvas,
                    'blank frame canvas not the restored initial canvas')
        # Pose-to-parameter binding: the captured current value IS the pose.
        parameter = before['parameter']
        expected_value = POSE_PARAM[pose]
        if expected_value is None:
            require(parameter is None, pose + ' unexpected parameter observation')
        else:
            require(isinstance(parameter, dict)
                    and parameter.get('id') == 'ParamMI3DPixShift'
                    and parameter.get('value') == expected_value,
                    pose + ' parameter value does not match the expected pose')
        # Mesh invariants + typed binding expectations per pose.
        mesh = before['mesh']
        binding = before['textureInput']
        if pose == 'blank':
            require(mesh is None and binding is None and parameter is None,
                    'blank frame still sees fixture objects')
        else:
            require(binding is not None if pose in BOUND_POSES else binding is None,
                    pose + ' textureInput kind/identity wrong for pose')
            invariant = _mesh_invariant_tuple(mesh)
            # Evaluated positions must equal the frozen rect shifted by exactly
            # 80x the captured parameter value — not merely "differ from source".
            _positions_close(mesh['evaluatedPositions'],
                             SHIFTED[80.0 * expected_value], pose + ' evaluated')
            if mesh_invariant is None:
                mesh_invariant = invariant
            else:
                require(invariant == mesh_invariant,
                        pose + ' protected mesh state drifted between frames')
            if pose in BOUND_POSES:
                require(binding['kind'] == 'MODEL_IMAGE'
                        and binding['artMeshId'] == mesh['id']
                        and isinstance(binding['modelImageId'], str)
                        and binding['modelImageId']
                        and binding['textureAtlasId'] is None,
                        pose + ' typed binding identity malformed')
                if bound_image is None:
                    bound_image = binding['modelImageId']
                require(binding['modelImageId'] == bound_image,
                        pose + ' bound image identity changed between frames')
        manifest[pose] = _pixels(png)

    # Independent frozen patch re-verification on the preserved native PNGs.
    require(not patch_failures(manifest['bound-p0'], 0)
            and outside_alpha(manifest['bound-p0'], 0),
            'bound param-0 PNG fails frozen patches')
    require(not patch_failures(manifest['bound-p1'], SHIFT)
            and outside_alpha(manifest['bound-p1'], SHIFT),
            'bound +1 PNG fails +80px patches')
    require(not patch_failures(manifest['bound-m1'], -SHIFT)
            and outside_alpha(manifest['bound-m1'], -SHIFT),
            'bound -1 PNG fails -80px patches')
    require(patch_failures(manifest['bound-p0'], SHIFT),
            'oracle wrongly accepts stale pose')
    require(patch_failures(manifest['bound-p1'], 0),
            'oracle wrongly accepts shifted at zero')
    # Undo/Redo equality is a pixel equality, not a byte equality of whatever
    # encoder wrote the file.
    require(manifest['unbound'] == manifest['baseline'], 'undo pixels differ from baseline')
    require(manifest['rebound'] == manifest['bound-p0'], 'redo pixels differ from bound')
    require(manifest['baseline'] != manifest['bound-p0'], 'bound frame equals baseline')
    require(all_transparent(manifest['blank']), 'post-restore frame not fully transparent')
    return True


def _process(p, where):
    require(isinstance(p, dict) and set(p) == PROCESS_KEYS,
            where + ': process identity inventory')
    require(type(p['pid']) is int and p['pid'] > 0
            and type(p['startEpochMillis']) is int and p['startEpochMillis'] > 0,
            where + ': process identity missing')


def _manifest(m, where):
    """The persisted fixture manifest shared by Job A save and Job B reopen:
    persistent identities + frozen content. Runtime-generation fields are
    deliberately absent — they are process-local, not persistent."""
    require(isinstance(m, dict) and set(m) == MANIFEST_KEYS,
            where + ': manifest inventory')
    canvas = _canvas(m['canvas'], where)
    require(canvas['width'] == OUT and canvas['height'] == OUT,
            where + ': persisted canvas not frozen 1024x1024')
    require(isinstance(m['meshId'], str) and m['meshId']
            and isinstance(m['sourceGuid'], str) and m['sourceGuid'],
            where + ': persistent mesh identities missing')
    require(m['sourcePositions'] == MESH_SOURCE_POSITIONS
            and m['sourceUvs'] == MESH_SOURCE_UVS
            and m['triangles'] == MESH_TRIANGLES,
            where + ': frozen source geometry/UV/topology differs')
    forms = m['forms']
    require(isinstance(forms, list) and len(forms) == 3, where + ': form inventory')
    require(len({f.get('formGuid') for f in forms}) == 3,
            where + ': form identities not unique')
    by_guid = {}
    for form in forms:
        require(isinstance(form, dict) and set(form) == FORM_KEYS
                and isinstance(form['formGuid'], str) and form['formGuid']
                and type(form['drawOrder']) is int,
                where + ': malformed stored form')
        by_guid[form['formGuid']] = form
    axes = m['axes']
    require(isinstance(axes, list) and len(axes) == 1, where + ': axis inventory')
    axis = axes[0]
    require(isinstance(axis, dict) and set(axis) == AXIS_KEYS
            and isinstance(axis['parameterGuid'], str) and axis['parameterGuid']
            and axis['parameterId'] == 'ParamMI3DPixShift'
            and sorted(axis['keys']) == [-1.0, 0.0, 1.0]
            and axis['minimum'] == -1.0 and axis['default'] == 0.0
            and axis['maximum'] == 1.0,
            where + ': persisted axis domain/keys differ')
    cells = m['cells']
    require(isinstance(cells, list) and len(cells) == 3, where + ': cell inventory')
    require(len({c.get('formGuid') for c in cells}) == 3,
            where + ': cells share a form (tuple→form not unique)')
    # Exact tuple→unique-form→frozen-positions linkage: a cell at key value v
    # must reference a distinct form whose stored positions are the frozen
    # 80v-shifted rectangle (Euclidean <=0.1px per vertex). Swapping cell→form
    # associations while keeping rendered PNGs identical is rejected here.
    bound = []
    default_cell_form = None
    for cell in cells:
        require(isinstance(cell, dict) and set(cell) == CELL_KEYS
                and type(cell['defaultKeyform']) is bool
                and cell['formGuid'] in by_guid
                and len(cell['coordinates']) == 1
                and cell['coordinates'][0].get('parameterGuid') == axis['parameterGuid'],
                where + ': malformed parameter cell')
        value = cell['coordinates'][0]['value']
        bound.append(value)
        require(value in (-1.0, 0.0, 1.0), where + ': cell key outside frozen set')
        form = by_guid[cell['formGuid']]
        _positions_close(form['positions'], SHIFTED[80.0 * value],
                         where + ': cell ' + repr(value) + ' form')
        require(cell['defaultKeyform'] == (value == 0.0),
                where + ': defaultKeyform flag on the wrong tuple')
        if value == 0.0:
            default_cell_form = cell['formGuid']
    require(sorted(bound) == [-1.0, 0.0, 1.0], where + ': bound keys differ')
    # The real ordinaryKeyform(default-tuple) observation recorded by the
    # producer: default flag, resolved GUID, stored positions and draw order
    # must all agree with the 0-key cell's form.
    default_form = m['defaultForm']
    require(isinstance(default_form, dict) and set(default_form) == FORM_KEYS | {'defaultKeyform'},
            where + ': default form inventory')
    require(default_form['defaultKeyform'] is True,
            where + ': ordinary-keyform default observation absent')
    require(default_form['formGuid'] == default_cell_form,
            where + ': default form GUID is not the 0-key cell form')
    _positions_close(default_form['positions'], SHIFTED[0.0],
                     where + ': default form')
    require(default_form['drawOrder'] == by_guid[default_cell_form]['drawOrder'],
            where + ': default form draw order differs')
    require(m['morphKeys'] == 0, where + ': unexpected morph keys')
    parameter = m['parameter']
    require(isinstance(parameter, dict) and set(parameter) == PARAM_DOMAIN_KEYS
            and parameter['id'] == 'ParamMI3DPixShift'
            and parameter['value'] == 0.0 and parameter['minimum'] == -1.0
            and parameter['maximum'] == 1.0 and parameter['default'] == 0.0,
            where + ': persisted parameter domain differs')
    binding = m['textureInput']
    require(isinstance(binding, dict)
            and binding.get('kind') == 'MODEL_IMAGE'
            and binding.get('artMeshId') == m['meshId']
            and isinstance(binding.get('modelId'), str) and binding['modelId']
            and isinstance(binding.get('modelImageId'), str) and binding['modelImageId']
            and binding.get('textureAtlasId') is None,
            where + ': persisted typed binding differs')
    require(m['expectedMaterialSha256'] == EXPECTED_MATERIAL_SHA256,
            where + ': expected input-recipe digest is not the frozen value')
    require(m['observedMaterialSha256'] == 'UNAVAILABLE',
            where + ': native image readback must stay UNAVAILABLE, not fabricated')
    return m


def verify_persistence_save(r, cmo3, post_png, frame_blobs):
    """Job A: the native SAVE_AS artifact + manifest invariance proof, plus the
    independently named post-save re-sample. ``frame_blobs`` maps every frozen
    pose AND 'post-save-p0' to its PNG bytes (already archive-admitted)."""
    s = r.get('persistenceSave')
    require(isinstance(s, dict) and s.get('schemaVersion') == 'mi3d-t014-save-v1',
            'persistenceSave section missing/schema')
    require(r.get('mi3dMode') == 'persistence-save', 'report mode is not save')
    require(s.get('status') == 'PASS' and s.get('failure') is None,
            'persistenceSave not PASS')
    checks = s.get('checks')
    require(isinstance(checks, list)
            and {c.get('id') for c in checks} == SAVE_CHECKS
            and all(c.get('status') == 'PASS' and c.get('actual') is True
                    and c.get('evidenceKind') == 'EXACT_HOST' for c in checks),
            'persistenceSave check inventory')
    require(s.get('requestStatus') == 'GRANTED', 'save grant not acquired')
    require(s.get('commandStatus') == 'EXECUTED', 'native SAVE_AS not executed')
    require(s.get('artifact') == 'persistence/mi3d-t014-model.cmo3',
            'save artifact path drifted')
    require(type(s.get('artifactBytes')) is int
            and 0 < s['artifactBytes'] <= CMO3_MAX,
            'saved CMO3 out of micro-fixture budget')
    require(isinstance(cmo3, (bytes, bytearray)) and 0 < len(cmo3) <= CMO3_MAX,
            'archived CMO3 missing/out of budget')
    require(s['artifactBytes'] == len(cmo3),
            'claimed CMO3 byte count differs from the actual archive length')
    require(hashlib.sha256(bytes(cmo3)).hexdigest() == s.get('artifactSha256'),
            'archived CMO3 sha differs from report')
    _process(s.get('process'), 'save')
    before = _manifest(s.get('manifestBefore'), 'manifestBefore')
    after = _manifest(s.get('manifestAfter'), 'manifestAfter')
    require(before == after, 'SAVE_AS mutated the authoring/resource manifest')
    # The saved manifest must equal what the bound frames actually observed —
    # the full stored tuple, not only four identities.
    rows = r['pixelSampling']['frames']
    bound = next(f for f in rows if f['pose'] == 'bound-p0')['stateBefore']
    rebound = next(f for f in rows if f['pose'] == 'rebound')['stateBefore']
    for where, state in (('bound', bound), ('rebound', rebound)):
        require(state['mesh']['id'] == before['meshId']
                and state['mesh']['cells'] == before['cells']
                and state['mesh']['forms'] == before['forms']
                and state['parameter']['id'] == before['parameter']['id']
                and state['parameter']['value'] == 0.0
                and state['model'] == before['textureInput']['modelId']
                and state['textureInput']['modelImageId']
                == before['textureInput']['modelImageId'],
                'saved manifest does not match the ' + where + ' frame state')
    # Pre-save PNG artifact SHAs recorded at save time must equal the pixel
    # leg's own frame rows (blank is captured after save, in the restore leg).
    shas = s.get('pngShas')
    require(isinstance(shas, dict)
            and shas == {f['pose']: f['artifactSha256'] for f in rows
                         if f['pose'] != 'blank'},
            'pre-save PNG artifact shas differ from the pixel frame rows')
    # Post-save p0: a separately named bounded PNG re-sampled AFTER SAVE_AS in
    # the same live document. Same one-window state tuple, frozen canvas, param
    # 0, typed binding — and decoded pixels identical to the pre-save bound-p0.
    post = s.get('postSaveFrame')
    require(isinstance(post, dict) and set(post) == FRAME_KEYS,
            'post-save frame inventory')
    require(post['pose'] == POST_SAVE_POSE
            and post['artifact'] == POST_SAVE_ARTIFACT
            and post['width'] == OUT and post['height'] == OUT,
            'post-save frame identity/path drifted')
    require(isinstance(post_png, (bytes, bytearray)),
            'post-save PNG bytes missing')
    require(hashlib.sha256(bytes(post_png)).hexdigest() == post['artifactSha256']
            and post['artifactBytes'] == len(post_png),
            'post-save PNG sha/bytes differ from the archived artifact')
    p_before = _state(post.get('stateBefore'), POST_SAVE_POSE, 'postSave/before')
    p_after = _state(post.get('stateAfter'), POST_SAVE_POSE, 'postSave/after')
    require(p_before == p_after, 'post-save state changed across render')
    require(p_before['canvas']['width'] == OUT and p_before['canvas']['height'] == OUT,
            'post-save canvas not frozen 1024x1024')
    require(p_before['parameter']['id'] == 'ParamMI3DPixShift'
            and p_before['parameter']['value'] == 0.0,
            'post-save parameter value is not the rebound 0')
    require(p_before['model'] == bound['model']
            and p_before['history']['documentBindingId'] == bound['history']['documentBindingId']
            and p_before['history']['managerBindingId'] == bound['history']['managerBindingId'],
            'post-save frame is not the same live document/model/manager')
    require(p_before['mesh']['id'] == before['meshId']
            and p_before['mesh']['cells'] == before['cells']
            and p_before['mesh']['forms'] == before['forms']
            and p_before['textureInput'] == bound['textureInput'],
            'post-save fixture state differs from the saved manifest/bound frame')
    _positions_close(p_before['mesh']['evaluatedPositions'], SHIFTED[0.0],
                     'postSave evaluated')
    require(_pixels(bytes(post_png)) == _pixels(frame_blobs['bound-p0']),
            'post-save pixels differ from the pre-save bound render')
    return True


def verify_persistence_reopen(r, frames):
    """Job B: the reopened-document section — three poses re-sampled through the
    same frozen contract, objects re-resolved by persistent identity."""
    require(r.get('mi3dMode') == 'persistence-reopen', 'report mode is not reopen')
    require(r.get('status') == 'PASS' and r.get('failure') is None,
            'reopen report not PASS')
    s = r.get('persistenceReopen')
    require(isinstance(s, dict) and s.get('schemaVersion') == 'mi3d-t014-reopen-v1'
            and s.get('mode') == 'persistence-reopen',
            'persistenceReopen section missing/schema')
    require(s.get('status') == 'PASS' and s.get('failure') is None,
            'persistenceReopen not PASS')
    checks = s.get('checks')
    require(isinstance(checks, list)
            and {c.get('id') for c in checks} == REOPEN_CHECKS
            and all(c.get('status') == 'PASS' and c.get('actual') is True
                    and c.get('evidenceKind') == 'EXACT_HOST' for c in checks),
            'persistenceReopen check inventory')
    _process(s.get('process'), 'reopen')
    canvas = _canvas(s.get('reopenedCanvas'), 'reopenedCanvas')
    require(canvas['width'] == OUT and canvas['height'] == OUT,
            'reopened canvas not frozen 1024x1024')
    manifest = _manifest(s.get('manifest'), 'reopenManifest')

    rows = s.get('frames')
    require(isinstance(rows, list) and len(rows) == len(REOPEN_POSES)
            and [f.get('pose') for f in rows] == list(REOPEN_POSES),
            'reopen frame list not exactly bound-p0/p1/m1')
    require(isinstance(frames, dict) and set(frames) == set(REOPEN_POSES),
            'reopen PNG blob inventory missing/extra')
    mesh_invariant = None
    image = manifest['textureInput']['modelImageId']
    # F3: all three frames must come from ONE selected document, ONE model and
    # ONE history manager — sampled cross-document/cross-manager mutations are
    # rejected even when each frame's own before/after tuple is consistent.
    model_id = document_id = manager_id = generation = model_generation = None
    for row in rows:
        require(isinstance(row, dict) and set(row) == FRAME_KEYS,
                'reopen frame field inventory')
        pose = row['pose']
        require(row['width'] == OUT and row['height'] == OUT, pose + ' dimensions')
        require(row['artifact'] == 'pixel-sampling/%s.png' % pose, pose + ' artifact path')
        png = frames[pose]
        require(hashlib.sha256(png).hexdigest() == row['artifactSha256'],
                pose + ' PNG sha mismatch')
        require(row['artifactBytes'] == len(png), pose + ' PNG size mismatch')
        before = _state(row.get('stateBefore'), pose, pose + '/before')
        after = _state(row.get('stateAfter'), pose, pose + '/after')
        require(before == after, pose + ' protected state changed across render')
        require(before['canvas']['width'] == OUT and before['canvas']['height'] == OUT,
                pose + ' frame not rendered on the reopened 1024 canvas')
        parameter = before['parameter']
        require(isinstance(parameter, dict)
                and parameter.get('id') == 'ParamMI3DPixShift'
                and parameter.get('value') == REOPEN_PARAM[pose],
                pose + ' reopened parameter value does not match the pose')
        require(before['mesh'] is not None and before['textureInput'] is not None,
                pose + ' reopened fixture objects missing')
        binding = before['textureInput']
        # The typed input's model association must be the frame's selected
        # model — and the same persisted model id recorded in the manifest.
        require(isinstance(before['model'], str) and before['model']
                and binding.get('modelId') == before['model']
                and binding['modelId'] == manifest['textureInput']['modelId']
                and binding.get('artMeshId') == manifest['meshId'],
                pose + ' typed input model/target association broken')
        if model_id is None:
            model_id, document_id, manager_id = (
                before['model'], before['history']['documentBindingId'],
                before['history']['managerBindingId'])
            generation = before['history']['generation']
            model_generation = binding.get('modelGeneration')
        else:
            require(before['model'] == model_id
                    and before['history']['documentBindingId'] == document_id
                    and before['history']['managerBindingId'] == manager_id
                    and before['history']['generation'] == generation
                    and binding.get('modelGeneration') == model_generation,
                    pose + ' cross-document/history identity drifted between frames')
        invariant = _mesh_invariant_tuple(before['mesh'])
        require(len(before['mesh']['axes']) == 1 and len(before['mesh']['forms']) == 3,
                pose + ' reopened axis/form inventory differs')
        frame_axis = before['mesh']['axes'][0]
        manifest_axis = manifest['axes'][0]
        require(before['mesh']['id'] == manifest['meshId']
                and invariant['forms'] == manifest['forms']
                and invariant['cells'] == manifest['cells']
                and frame_axis['parameterGuid'] == manifest_axis['parameterGuid']
                and frame_axis['parameterId'] == manifest_axis['parameterId']
                and sorted(frame_axis['keys']) == sorted(manifest_axis['keys'])
                and binding['kind'] == 'MODEL_IMAGE'
                and binding['modelImageId'] == image,
                pose + ' reopened persistent identity/content drifted')
        # Evaluated positions must match the ACTUAL parameter value, not merely
        # differ from source.
        _positions_close(before['mesh']['evaluatedPositions'],
                         SHIFTED[80.0 * REOPEN_PARAM[pose]],
                         pose + ' evaluated')
        if mesh_invariant is None:
            mesh_invariant = invariant
        else:
            require(invariant == mesh_invariant,
                    pose + ' mesh state drifted between reopened frames')
        decoded = _pixels(png)
        shift = int(REOPEN_PARAM[pose] * SHIFT)
        require(not patch_failures(decoded, shift) and outside_alpha(decoded, shift),
                pose + ' reopened PNG fails frozen patches')
    return True
