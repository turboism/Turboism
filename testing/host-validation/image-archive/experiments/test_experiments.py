"""Offline regression tests for the frozen experiment builder's admission checks."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

MODULE_PATH = Path(__file__).with_name("build_experiments.py")
spec = importlib.util.spec_from_file_location("texture_experiment_builder", MODULE_PATH)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def class_file(*entries):
    """Builds only a class-file constant pool for policy parsing tests."""
    pool = b""
    slots = 0
    for tag, value in entries:
        if tag == 1:
            encoded = value.encode("utf-8")
            pool += b"\x01" + len(encoded).to_bytes(2, "big") + encoded
            slots += 1
        elif tag == 3:
            pool += b"\x03" + value.to_bytes(4, "big", signed=True)
            slots += 1
        elif tag == 5:
            pool += b"\x05" + value.to_bytes(8, "big", signed=True)
            slots += 2
        else:
            raise ValueError(tag)
    return bytes.fromhex("cafebabe") + b"\x00\x00\x00\x3d" + (slots + 1).to_bytes(2, "big") + pool


def agent_with(class_bytes, entry=builder.CONVERTER_ENTRY):
    temporary = tempfile.TemporaryDirectory()
    path = Path(temporary.name) / "control-agent.jar"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(entry, class_bytes)
    return temporary, path


class FrozenConverterSourceTest(unittest.TestCase):
    def test_frozen_source_is_rewritten_to_the_promoted_limit(self):
        source = "long pixels = (long) width * height;\nif (pixels > 33_554_432L) return null;\n"
        changed = builder.validate_converter_source(source)
        self.assertIn(builder.LARGE_LIMIT, changed)
        self.assertNotIn(builder.HISTORICAL_LIMIT, changed)

    def test_current_source_is_rejected(self):
        with self.assertRaises(SystemExit):
            builder.validate_converter_source("if (pixels > 67_108_864L) return null;\n")

    def test_ambiguous_or_missing_limit_is_rejected(self):
        for source in (
            "",
            "if (pixels > 33_554_432L || pixels > 33_554_432L) return null;\n",
            "if (pixels > 33_554_432L && pixels > 67_108_864L) return null;\n",
        ):
            with self.assertRaises(SystemExit):
                builder.validate_converter_source(source)


class ControlPolicyTest(unittest.TestCase):
    def test_reads_long_constants_but_ignores_integer_constants(self):
        parsed = builder.long_constants(
            class_file((1, "fixture"), (3, builder.HISTORICAL_LONG), (5, builder.LARGE_LONG))
        )
        self.assertEqual({builder.LARGE_LONG}, parsed)

    def test_malformed_class_is_rejected(self):
        with self.assertRaises(ValueError):
            builder.long_constants(b"not-a-class")

    def test_frozen_control_is_accepted(self):
        temporary, path = agent_with(class_file((5, builder.HISTORICAL_LONG), (1, "fixture")))
        try:
            self.assertIn(builder.HISTORICAL_LONG, builder.control_policy_constants(path))
        finally:
            temporary.cleanup()

    def test_promoted_control_is_rejected(self):
        for constants in (
            ((5, builder.LARGE_LONG),),
            ((5, builder.HISTORICAL_LONG), (5, builder.LARGE_LONG)),
        ):
            temporary, path = agent_with(class_file(*constants))
            try:
                with self.assertRaises(SystemExit):
                    builder.control_policy_constants(path)
            finally:
                temporary.cleanup()

    def test_missing_or_malformed_converter_entry_is_rejected(self):
        temporary, path = agent_with(class_file((5, builder.HISTORICAL_LONG)), entry="other.class")
        try:
            with self.assertRaises(SystemExit):
                builder.control_policy_constants(path)
        finally:
            temporary.cleanup()
        temporary, path = agent_with(b"broken")
        try:
            with self.assertRaises(SystemExit):
                builder.control_policy_constants(path)
        finally:
            temporary.cleanup()


if __name__ == "__main__":
    unittest.main()
