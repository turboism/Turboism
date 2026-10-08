"""Decoder boundary checks, including operand bytes that resemble member opcodes."""
import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location('inspection', Path(__file__).with_name('inspect-command-return-bytecode.py'))
inspection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspection)
from sdk_api_baseline_common import BaselineError


class NoPool:
    def entry(self, *args):
        raise AssertionError('an operand was incorrectly decoded as a member opcode')


class DecoderTest(unittest.TestCase):
    def decode(self, data):
        return inspection.instructions(data, NoPool())

    def test_member_like_operand_is_not_an_instruction(self):
        result = self.decode(bytes([16, 184, 17, 180, 185, 18, 182, 177]))
        self.assertEqual([0, 2, 5, 7], [x['offset'] for x in result])

    def test_wide_and_long_branches(self):
        result = self.decode(bytes([196, 132, 0, 1, 0, 2, 200, 0, 0, 0, 5, 177]))
        self.assertEqual([0, 6, 11], [x['offset'] for x in result])
        self.assertEqual(11, result[1]['branchTarget'])
        self.assertEqual([0, 4], [x['offset'] for x in self.decode(bytes([196, 21, 0, 5, 177]))])

    def test_aligned_table_switch(self):
        data = bytes([170, 0, 0, 0]) + struct.pack('>iiii', 20, 4, 4, 20) + bytes([177])
        result = self.decode(data)
        self.assertEqual([20], result[0]['switchTargets'])
        self.assertEqual(20, result[0]['defaultTarget'])

    def test_unaligned_lookup_switch(self):
        data = bytes([0, 171, 0, 0]) + struct.pack('>iiii', 19, 1, -7, 19) + bytes([177])
        result = self.decode(data)
        self.assertEqual([20], result[1]['switchTargets'])

    def test_member_pool_resolution(self):
        class Pool:
            def entry(self, index, expected):
                values = {1: (10, 3, 4), 2: (11, 3, 4), 4: (12, 5, 6)}
                item = values[index]
                self_expected = (expected,) if isinstance(expected, int) else expected
                if item[0] not in self_expected:
                    raise ValueError('wrong member tag')
                return item
            def class_name(self, index):
                return 'fixture/Owner'
            def utf(self, index):
                return {5: 'method', 6: '()V'}[index]
        result = inspection.instructions(bytes([184, 0, 1, 185, 0, 2, 1, 0, 177]), Pool())
        self.assertEqual(['invokestatic', 'invokeinterface'], [x['operation'] for x in result[:2]])
        self.assertEqual(('fixture/Owner', 'method', '()V'),
                         (result[0]['owner'], result[0]['name'], result[0]['descriptor']))

    def test_invalid_boundaries_and_encodings_refuse(self):
        bad = [bytes([17, 1]), bytes([196, 177]), bytes([202]),
               bytes([167, 0, 1, 177]), bytes([171, 1, 0, 0]) + bytes(8),
               bytes([170, 0, 0, 0]) + struct.pack('>iii', 0, 2, 1),
               bytes([171, 0, 0, 0]) + struct.pack('>ii', 0, -1)]
        for data in bad:
            with self.subTest(data=data.hex()), self.assertRaises((ValueError, BaselineError)):
                # Both the shared class Reader's truncation exception and this
                # decoder's explicit refusals are ordinary parse failures.
                self.decode(data)


if __name__ == '__main__':
    unittest.main()
