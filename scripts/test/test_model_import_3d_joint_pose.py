"""Joint-pose protocol controls. Synthetic fixtures never certify native evaluation."""
import copy
import itertools
import math
from pathlib import Path
import runpy
import struct
import unittest

ROOT = Path(__file__).resolve().parents[2]
VERIFY = runpy.run_path(str(ROOT / 'validation/model-import-3d-host-probe/verify-joint-pose.py'))


def f(value):
    return struct.unpack('f', struct.pack('f', value))[0]


def projection(source, yaw, pitch):
    out = []
    a, b = math.radians(pitch), math.radians(yaw)
    for x, y, z in source:
        pitched_y, pitched_z = y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a)
        rotated_x = x * math.cos(b) + pitched_z * math.sin(b)
        out.append([f(512 + 200 * rotated_x), f(512 - 200 * pitched_y)])
    return out


def fixture():
    keys = [f(30 * (2 * n / 22 - 1)) for n in range(23)]
    keys[11] = 0.0
    sources = [[[.3, .2, .1], [-.3, .2, .1], [0, -.2, -.1]],
               [[.3, .2, .1], [0, -.2, -.1], [.3, -.2, -.2]]]
    ids = ['ParamMI3DJointYaw', 'ParamMI3DJointPitch']
    history = dict(availability='AVAILABLE', generation=1, revision=1, position=0,
                   documentBindingId='document', managerBindingId='manager')
    counts = dict(meshes=0, deformers=0, parts=0, parameters=0)
    canvas = dict(width=1024, height=1024, originX=512, originY=512, pixelsPerUnit=100)
    e = dict(schemaVersion='mi3d-native-joint-v1', status='PASS', failure=None, finallyRestored=True,
             sources=sources, rotationOrder='Ry(yaw)*Rx(pitch)', camera=[512, 512, 200, 0, 0, 0],
             yawKeys=keys, pitchKeys=keys, idealErrorBoundPixels=200*math.sqrt(.17)*2*math.radians(60/22)**2/8,
             maxNativeErrorPixels=.1, initialHistory=history, initialCounts=counts, restoredCounts=counts.copy(),
             initialCanvas=canvas, restoredCanvas=canvas.copy(),
             writeBefore={**history, 'position': 5, 'revision': 6}, writeAfter={**history, 'position': 6, 'revision': 7},
             writeOutcome='COMMITTED', before=[], written=[], samples=[])
    for mesh, source in enumerate(sources):
        forms, cells = [], []
        for index, (y, p) in enumerate(itertools.product(keys, repeat=2)):
            forms.append(dict(guid=f'form{mesh}-{index}', positions=projection(source, 0, 0), drawOrder=500))
            cells.append(dict(formGuid=forms[-1]['guid'], coordinates=[dict(guid='yaw', value=y), dict(guid='pitch', value=p)]))
        s = dict(artMeshId=f'mesh{mesh}', sourceGuid=f'source{mesh}', positions=projection(source, 0, 0),
                 uvs=[[0, 0], [1, 0], [0, 1]], indices=[0, 1, 2], morphKeys=[], forms=forms, cells=cells,
                 axes=[dict(id=i, guid=g, minimum=-30, default=0, maximum=30, keys=keys)
                       for i, g in zip(ids, ['yaw', 'pitch'])])
        e['before'].append(s)
        after = copy.deepcopy(s)
        for form, (y, p) in zip(after['forms'], itertools.product(keys, repeat=2)):
            form['positions'] = projection(source, y, p)
        e['written'].append(after)
    e['meshIds'] = ['mesh0', 'mesh1']
    e['undone'] = copy.deepcopy(e['before']); e['redone'] = copy.deepcopy(e['written'])
    e['afterEvaluation'] = copy.deepcopy(e['written'])
    subkeys = [keys[0]]
    for a, b in zip(keys, keys[1:]):
        subkeys.extend([f(f(a+b)/2), b])
    poses = list(itertools.product(subkeys, repeat=2))
    seed = (4012026 ^ 25214903917) % (2**48)
    random_coords = []
    for _ in range(64):
        seed = (seed * 25214903917 + 11) % (2**48)
        first = seed // (2**22)
        seed = (seed * 25214903917 + 11) % (2**48)
        second = seed // (2**21)
        random_coords.append(f(-30 + 60 * (first * 2**27 + second) / 2**53))
    poses.extend(zip(random_coords[::2], random_coords[1::2]))
    position, prior_pose = 6, (0., 0.)
    for n, pose in enumerate(poses):
        position += sum(a != b for a, b in zip(prior_pose, pose))
        prior_pose = pose
        h = {**history, 'position': position, 'revision': 10+n}
        row = dict(index=n, pose=list(pose), status='PASS', historyBefore=h, historyAfter=h.copy(), meshes=[])
        for mesh, source in enumerate(sources):
            parameters = [dict(guid=g, id=i, value=v) for g, i, v in zip(['yaw', 'pitch'], ids, pose)]
            # Synthetic evaluator truth is supplied directly. Native behavior remains untested.
            actual = dict(artMeshId=f'mesh{mesh}', sourceGuid=f'source{mesh}',
                          live=dict(updateVersion=n+5, parameters=parameters),
                          consumed=dict(updateVersion=n+15, parameters=copy.deepcopy(parameters)), passEntryTimeMillis=n+100,
                          calculated=dict(positions=projection(source, *pose), drawOrder=500, coordinateType='Canvas', interpolated=True))
            requested = copy.deepcopy(actual); requested['consumed']['updateVersion'] -= 1
            row['meshes'].append(dict(requested=requested, actual=actual, maxErrorPixels=0., attempts=1,
                                      elapsedMillis=25, waitReason='CONSUMED', lastObservation=copy.deepcopy(actual)))
        e['samples'].append(row)
    e['restoredHistory'] = {**history, 'revision': len(poses)+20}
    return {'jointPose': e}


class JointPoseProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = fixture()

    def check_rejected(self, mutate):
        report = copy.deepcopy(self.base)
        mutate(report['jointPose'])
        with self.assertRaises(ValueError):
            VERIFY['verify_joint_pose'](report)

    def test_complete_fixture(self):
        VERIFY['verify_joint_pose'](copy.deepcopy(self.base))

    def test_missing_midpoint_and_random_samples(self):
        self.check_rejected(lambda e: e['samples'].pop(1))
        self.check_rejected(lambda e: e['samples'].pop())

    def test_three_key_plan_rejected(self):
        self.check_rejected(lambda e: e.update(yawKeys=[-30, 0, 30]))

    def test_frozen_source_camera(self):
        self.check_rejected(lambda e: e['sources'][0][0].__setitem__(2, .2))
        self.check_rejected(lambda e: e['camera'].__setitem__(2, 199))

    def test_wrong_joint_projection_despite_reported_zero_error(self):
        self.check_rejected(lambda e: e['samples'][1]['meshes'][0]['actual']['calculated']['positions'][0].__setitem__(0, 700))

    def test_stale_native_consumption(self):
        def stale(e):
            row = e['samples'][0]['meshes'][0]
            row['actual']['consumed']['updateVersion'] = row['requested']['consumed']['updateVersion']
        self.check_rejected(stale)

    def test_clamped_or_ignored_parameter(self):
        self.check_rejected(lambda e: e['samples'][0]['meshes'][0]['actual']['live']['parameters'][0].update(value=0))

    def test_alias_and_missing_tuple(self):
        self.check_rejected(lambda e: e['written'][0]['cells'][1].update(formGuid=e['written'][0]['cells'][0]['formGuid']))
        self.check_rejected(lambda e: e['written'][0]['cells'].pop())

    def test_default_uv_and_order_protection(self):
        self.check_rejected(lambda e: e['written'][0]['uvs'][0].__setitem__(0, .5))
        self.check_rejected(lambda e: e['written'][0]['forms'][264]['positions'][0].__setitem__(0, 700))
        self.check_rejected(lambda e: e['written'][0]['forms'][0].update(drawOrder=501))

    def test_undo_restore_and_history(self):
        self.check_rejected(lambda e: e['undone'][0]['forms'][0]['positions'][0].__setitem__(0, 700))
        self.check_rejected(lambda e: e['samples'][1]['historyBefore'].update(revision=0))
        self.check_rejected(lambda e: e['restoredHistory'].update(position=1))

    def test_nan_geometry(self):
        self.check_rejected(lambda e: e['samples'][0]['meshes'][0]['actual']['calculated']['positions'][0].__setitem__(0, math.nan))

    def test_parameter_setters_have_exact_history_cost_and_reads_have_none(self):
        for index in (0, 1, 45, 2056):
            for delta in (-1, 1):
                def mutate(e, index=index, delta=delta):
                    for field in ('historyBefore', 'historyAfter'):
                        e['samples'][index][field]['position'] += delta
                self.check_rejected(mutate)
        self.check_rejected(lambda e: e['samples'][1]['historyAfter'].update(
            position=e['samples'][1]['historyBefore']['position'] + 1))

    def test_complete_section_has_a_fixed_byte_budget(self):
        import json
        limit = 96 * 1024 * 1024
        report = copy.deepcopy(self.base)
        report['jointPose']['padding'] = ''
        size = len(json.dumps(report['jointPose'], separators=(',', ':')).encode('utf-8'))
        report['jointPose']['padding'] = ' ' * (limit - size)
        VERIFY['verify_joint_pose'](report)
        report['jointPose']['padding'] += 'x'
        with self.assertRaisesRegex(ValueError, 'section budget'):
            VERIFY['verify_joint_pose'](report)

    def test_float_json_shortest_decimal(self):
        report = copy.deepcopy(self.base)
        # Java serializes Float with shortest round-tripping decimal digits.
        report['jointPose']['yawKeys'] = [float(format(v, '.8g')) for v in report['jointPose']['yawKeys']]
        VERIFY['verify_joint_pose'](report)


if __name__ == '__main__':
    unittest.main()
