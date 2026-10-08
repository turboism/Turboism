"""Negative controls for the static research boundary, without host execution."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('inspect-native-mesh-edge-loop.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def fixture():
    code = [{'offset': 0, 'opcode': 42, 'bytes': '2a'},
            {'offset': 1, 'opcode': 180, 'operation': 'getfield',
             'owner': audit.MESH, 'name': 'cached_indices', 'bytes': 'b40001'}]
    for _ in range(3):
        for item in [dict(opcode=178, operation='getstatic', owner=audit.EDGE + '$EdgeType', name='NORMAL', bytes='b20002'),
                     dict(opcode=3, bytes='03'), dict(opcode=16, bytes='1008'), dict(opcode=1, bytes='01'),
                     dict(opcode=184, operation='invokestatic', owner=audit.MESH,
                          name='addEdgeIfNotExists$default', bytes='b80003')]:
            item['offset'] = code[-1]['offset'] + len(bytes.fromhex(code[-1]['bytes']))
            code.append(item)
    code.append(dict(offset=code[-1]['offset'] + 3, opcode=177, bytes='b1'))
    return {'instructions': code, 'exceptions': []}


class BoundaryTest(unittest.TestCase):
    def test_expected_direct_shape(self):
        self.assertEqual(audit.summarize_loop(fixture())['status'], 'DIRECT_SUFFIX_SHAPE_PASS')

    def test_reject_changes(self):
        mutations = [
            lambda m: m['instructions'][2].update(name='AUTO_TRIANGULATION'),
            lambda m: m['instructions'][3].update(bytes='04'),
            lambda m: m['instructions'][6].update(name='unexpectedCallback'),
            lambda m: m['instructions'][0].update(branchTarget=4),
            lambda m: m['instructions'][-1].update(branchTarget=0),
            lambda m: m['instructions'][3].update(opcode=170),
            lambda m: m['instructions'][3].update(operation='putfield'),
            lambda m: m['exceptions'].append((1, 4, 4, 0)),
            lambda m: m['instructions'].pop(6),
            lambda m: m['instructions'][1].update(name='different_array'),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutations.index(mutation)):
                method = copy.deepcopy(fixture())
                mutation(method)
                with self.assertRaises(ValueError):
                    audit.summarize_loop(method)


if __name__ == '__main__':
    unittest.main()
