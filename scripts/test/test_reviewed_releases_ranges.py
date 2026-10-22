"""Range consumers must preserve command and contract coverage and fail closed."""
import tempfile
import unittest
from pathlib import Path

from compat_matrix.extract import load_commands, load_contract_gates
from scripts.test.reviewed_releases_fixture import table
from turboism_dev.versions import reviewed_range_versions


class RangeConsumerTest(unittest.TestCase):
    def test_literal_and_range_consumers_have_identical_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            releases = root / "compatibility/cubism/reviewed-releases.json"
            releases.parent.mkdir(parents=True)
            versions = ["5.2.03", "5.3.02", "5.3.03", "5.3.04"]
            releases.write_text(table(versions))
            command = root / "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorCommand.java"
            command.parent.mkdir(parents=True)
            contract = root / "runtime/src/main/java/Contract.java"
            contract.parent.mkdir(parents=True)
            declarations = []
            for selection in ('Set.of("5.3.02", "5.3.03", "5.3.04")',
                              'ReviewedVersions.since("5.3.02")'):
                command.write_text("enum EditorCommand {\n    OPEN(" + selection + ");\n}")
                contract.write_text('class Contract { static final String CAPABILITY_ID = "cubism.test"; '
                                    'static final Set<String> SUPPORTED_VERSIONS = ' + selection + '; }')
                declarations.append((load_commands(root, versions), load_contract_gates(root)))
            self.assertEqual(declarations[0], declarations[1])
            self.assertEqual(versions[1:], declarations[1][0][0]["versions"])

    def test_unknown_or_reversed_boundaries_are_rejected(self):
        for expression in ('ReviewedVersions.since("5.3.05")',
                           'ReviewedVersions.between("5.3.04", "5.3.02")'):
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                reviewed_range_versions(expression, {"5.3.02", "5.3.04"})

    def test_selection_never_invents_unreviewed_patches(self):
        self.assertEqual({"5.3.02", "5.3.04"}, reviewed_range_versions(
            'ReviewedVersions.between("5.3.02", "5.3.04")', {"5.3.02", "5.3.04"}))
