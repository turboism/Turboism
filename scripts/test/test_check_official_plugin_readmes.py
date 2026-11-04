"""README contract: the primary README is fully gated, translations only lightly.

Two regression axes matter here:

* the documented version may lag the descriptor but never lead it, and
* a translated README is no longer rejected for translated wording, merged or
  reordered sections, or a missing changelog entry.
"""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("readme_check", ROOT / "scripts/test/check_official_plugin_readmes.py")
CHECK = importlib.util.module_from_spec(spec)
spec.loader.exec_module(CHECK)

DESCRIPTOR = {
    "id": "dev.turboism.plugin.demo",
    "name": "Demo Plugin",
    "version": "0.68.2",
    "category": "editing",
    "tags": ["alpha", "beta"],
    "turboismApi": "0.68",
    "environment": {"requiresCubism": True, "ui": "panel"},
    "permissions": [],
    "capabilities": [],
}


def descriptor(**overrides):
    merged = dict(DESCRIPTOR)
    merged.update(overrides)
    return merged


class ModuleFixture:
    """A throwaway plugin directory holding only the README files a case needs."""

    def __init__(self, **files):
        self._dir = tempfile.TemporaryDirectory()
        self.path = Path(self._dir.name)
        for name, text in files.items():
            (self.path / name.replace("__", ".")).write_text(text, encoding="utf-8")

    def __enter__(self):
        return self.path

    def __exit__(self, *_):
        self._dir.cleanup()


TRANSLATED = """# 演示插件

这是一份翻译过的说明，段落顺序与英文版不同，也没有逐条列出权限与能力。

## 功能概述

把演示用语本地化。

## 使い方のような順序

顺序故意与原版不一致。
"""

TRANSLATED_FRONT_MATTER = """---
turboismReadmeSchema: "1"
pluginId: dev.turboism.plugin.demo
version: 0.68.2
---

# 演示插件

本地化说明。
"""


class DocumentedVersionTest(unittest.TestCase):
    def test_equal_or_older_documented_version_is_accepted(self):
        self.assertEqual(CHECK.documented_version_errors("0.68.0", "0.68.0"), [])
        self.assertEqual(CHECK.documented_version_errors("0.1.0", "0.68.2"), [])

    def test_newer_or_malformed_documented_version_is_rejected(self):
        self.assertTrue(CHECK.documented_version_errors("0.68.1", "0.68.0"))
        self.assertTrue(CHECK.documented_version_errors("0.9.0", "0.10.0") == [])
        self.assertTrue(CHECK.documented_version_errors("abc", "0.68.0"))
        self.assertTrue(CHECK.documented_version_errors("0.68", "0.68.0"))


class LocalizedReadmeTest(unittest.TestCase):
    def test_missing_translation_is_not_an_error(self):
        with ModuleFixture() as module:
            self.assertEqual(CHECK.validate_localized_readme(module, "README_zh.md", DESCRIPTOR), [])

    def test_reordered_or_merged_sections_are_accepted(self):
        # The old gate failed this file: translated headings were matched by rigid
        # regexes and had to appear in the English section order.
        with ModuleFixture(README_zh__md=TRANSLATED) as module:
            self.assertEqual(CHECK.validate_localized_readme(module, "README_zh.md", DESCRIPTOR), [])

    def test_translation_may_reuse_the_full_front_matter(self):
        with ModuleFixture(README_ja__md=TRANSLATED_FRONT_MATTER) as module:
            self.assertEqual(CHECK.validate_localized_readme(module, "README_ja.md", DESCRIPTOR), [])

    def test_translation_may_omit_front_matter_entirely(self):
        with ModuleFixture(README_ko__md=TRANSLATED) as module:
            self.assertEqual(CHECK.validate_localized_readme(module, "README_ko.md", DESCRIPTOR), [])

    def test_translation_may_not_document_a_newer_version(self):
        text = TRANSLATED_FRONT_MATTER.replace("version: 0.68.2", "version: 0.69.0")
        with ModuleFixture(README_zh__md=text) as module:
            errors = CHECK.validate_localized_readme(module, "README_zh.md", DESCRIPTOR)
        self.assertTrue(any("not at or below descriptor" in error for error in errors), errors)

    def test_translation_must_match_plugin_id_when_it_declares_one(self):
        text = TRANSLATED_FRONT_MATTER.replace("dev.turboism.plugin.demo", "dev.turboism.plugin.other")
        with ModuleFixture(README_zh__md=text) as module:
            errors = CHECK.validate_localized_readme(module, "README_zh.md", DESCRIPTOR)
        self.assertTrue(any("pluginId" in error for error in errors), errors)

    def test_translation_must_have_a_title_and_body(self):
        with ModuleFixture(README_zh__md="## 只有标题\n") as module:
            errors = CHECK.validate_localized_readme(module, "README_zh.md", DESCRIPTOR)
        self.assertIn("README title is missing", errors)
        self.assertIn("translated README has no body text", errors)

    def test_translation_with_placeholder_text_is_rejected(self):
        with ModuleFixture(README_zh__md="# 演示插件\n\nTODO 翻译。\n") as module:
            errors = CHECK.validate_localized_readme(module, "README_zh.md", DESCRIPTOR)
        self.assertTrue(any("placeholder text remains" in error for error in errors), errors)

    def test_unreadable_translation_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            (module / "README_zh.md").write_bytes(b"# \xff\xfe not utf-8\n")
            errors = CHECK.validate_localized_readme(module, "README_zh.md", DESCRIPTOR)
        self.assertEqual(errors, ["README_zh.md is not valid UTF-8"])


class PrimaryReadmeTest(unittest.TestCase):
    def test_missing_primary_readme_is_an_error(self):
        expected_role = ("feature", "preview", "store-candidate")
        with ModuleFixture() as module:
            errors = CHECK.validate_primary_readme(module, DESCRIPTOR, expected_role)
        self.assertEqual(errors, ["README.md is missing"])

    def test_primary_readme_still_requires_front_matter(self):
        expected_role = ("feature", "preview", "store-candidate")
        with ModuleFixture(README__md="# Demo Plugin\n\nNo front matter here.\n") as module:
            errors = CHECK.validate_primary_readme(module, DESCRIPTOR, expected_role)
        self.assertEqual(errors, ["README must start with YAML-style front matter"])

    def test_primary_headings_cover_the_english_contract_only(self):
        # Section-order enforcement is a primary-README rule; translations are not
        # part of the tuple any more, which is the whole point of the relaxation.
        self.assertEqual(len(CHECK.PRIMARY_HEADINGS), 11)
        self.assertTrue(all(heading.isascii() for heading in CHECK.PRIMARY_HEADINGS))
        self.assertNotIn("README.md", CHECK.LOCALIZED_READMES)


if __name__ == "__main__":
    unittest.main()
