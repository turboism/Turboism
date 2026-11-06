#!/usr/bin/env python3
"""Unit tests for the shared alias-vocabulary derivation in ``turboism_dev.alias_vocabulary``."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from turboism_dev.alias_vocabulary import VocabularyError
from turboism_dev.alias_vocabulary import constant_name, constants, declaration


def aliases_block(class_name: str = "FixtureAliases", prefix: object = "cubism.fixture.") -> dict:
    return {"className": class_name, "prefix": prefix}


class DeclarationTest(unittest.TestCase):
    """The ``aliases`` block normalises to ``(class name, tuple of prefixes)``."""

    def test_single_string_prefix_normalises_to_a_one_tuple(self):
        self.assertEqual(
            ("FixtureAliases", ("cubism.fixture.",)),
            declaration(aliases_block(), "fixture.json"))

    def test_prefix_list_normalises_to_a_tuple_in_declaration_order(self):
        block = aliases_block(prefix=["cubism.fixture.", "cubism.fixture-hint."])
        self.assertEqual(
            ("FixtureAliases", ("cubism.fixture.", "cubism.fixture-hint.")),
            declaration(block, "fixture.json"))

    def test_empty_prefix_list_fails_closed(self):
        with self.assertRaisesRegex(VocabularyError, "non-empty list"):
            declaration(aliases_block(prefix=[]), "fixture.json")

    def test_prefix_of_another_type_fails_closed(self):
        with self.assertRaisesRegex(VocabularyError, "dotted lowercase namespace"):
            declaration(aliases_block(prefix=7), "fixture.json")

    def test_every_listed_prefix_must_be_a_dotted_namespace(self):
        with self.assertRaisesRegex(VocabularyError, "dotted lowercase namespace"):
            declaration(aliases_block(prefix=["cubism.fixture.", "cubism/other."]), "fixture.json")

    def test_a_duplicated_prefix_fails_closed(self):
        block = aliases_block(prefix=["cubism.fixture.", "cubism.fixture."])
        with self.assertRaisesRegex(VocabularyError, "declares a namespace twice"):
            declaration(block, "fixture.json")

    def test_class_name_still_must_be_a_vocabulary_name(self):
        with self.assertRaisesRegex(VocabularyError, "must be a <Family>Aliases name"):
            declaration(aliases_block(class_name="FixtureVocabulary"), "fixture.json")


class ConstantsTest(unittest.TestCase):
    """Constant derivation: one prefix-relative suffix per alias, injective, fail-closed."""

    def test_single_prefix_keeps_the_prefix_relative_derivation(self):
        self.assertEqual(
            {"APP_CONTROLLER_INSTANCE": "cubism.fixture.app-controller.instance"},
            constants(["cubism.fixture.app-controller.instance"], "cubism.fixture."))

    def test_alias_outside_every_declared_prefix_names_the_alias_and_prefixes(self):
        with self.assertRaisesRegex(VocabularyError, "other.alpha.*cubism.fixture.") as caught:
            constants(["other.alpha"], ("cubism.fixture.", "cubism.fixture-hint."))
        self.assertIn("exactly one", str(caught.exception))

    def test_alias_inside_two_declared_prefixes_fails_closed(self):
        with self.assertRaisesRegex(VocabularyError, "exactly one"):
            constants(
                ["cubism.fixture.a.b"],
                ("cubism.fixture.", "cubism.fixture.a."))

    def test_cross_prefix_collision_fails_closed(self):
        # ``cubism.a.x`` and ``cubism.b.x`` both derive ``X``; the rule is prefix-relative, so the
        # collision across two declared prefixes fails like one inside a single namespace.
        with self.assertRaisesRegex(VocabularyError, "both derive the constant name 'X'"):
            constants(["cubism.a.x", "cubism.b.x"], ("cubism.a.", "cubism.b."))

    def test_distinct_prefixes_derive_distinct_constants(self):
        table = constants(
            ["cubism.a.x", "cubism.b.y"], ("cubism.a.", "cubism.b."))
        self.assertEqual({"X": "cubism.a.x", "Y": "cubism.b.y"}, table)

    def test_empty_alias_table_fails_closed(self):
        with self.assertRaisesRegex(VocabularyError, "no alias"):
            constants([], ("cubism.fixture.",))

    def test_constant_name_rejects_an_alias_that_is_only_the_prefix(self):
        with self.assertRaisesRegex(VocabularyError, "not a member of"):
            constant_name("cubism.fixture.", "cubism.fixture.")


if __name__ == "__main__":
    unittest.main(verbosity=2)
