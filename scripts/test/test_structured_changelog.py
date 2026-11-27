#!/usr/bin/env python3
"""Unit tests for structured changelog parsing, validation, range queries, and CLI."""
from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import changelog as cli  # noqa: E402
from turboism_release import changelog as structured  # noqa: E402


def _sample_document() -> dict:
    return {
        "format": structured.FORMAT,
        "schemaVersion": structured.SCHEMA_VERSION,
        "repository": structured.REPOSITORY_URL,
        "locales": list(structured.LOCALES),
        "categories": list(structured.CATEGORIES),
        "unreleased": {
            "sections": {
                "added": [{"en": "Pending feature.", "zh": "待发布功能。"}],
            }
        },
        "releases": [
            {
                "version": "1.2.0",
                "date": "2026-11-25",
                "previousVersion": "1.1.0",
                "compareBase": "1.1.0",
                "summary": {"en": "Summary 1.2.0.", "zh": "1.2.0 摘要。"},
                "sections": {
                    "added": [{"en": "Added C.", "zh": "新增 C。", "ja": "C 追加。", "ko": "C 추가."}],
                    "fixed": [{"en": "Fixed C only in English."}],
                },
            },
            {
                "version": "1.1.0",
                "date": "2026-11-20",
                "previousVersion": "1.0.0",
                "compareBase": "1.0.0",
                "sections": {
                    "added": [{"en": "Added B.", "zh": "新增 B。", "ja": "B 追加。", "ko": "B 추가."}],
                    "changed": [{"en": "Changed B.", "zh": "变更 B。", "ja": "B 変更。", "ko": "B 변경."}],
                },
            },
            {
                "version": "1.0.0",
                "date": "2026-11-10",
                "previousVersion": None,
                "sections": {
                    "added": [{"en": "Initial release.", "zh": "初始版本。"}],
                },
            },
        ],
    }


class StructuredChangelogTest(unittest.TestCase):
    def test_repository_changelog_is_valid_and_in_sync(self) -> None:
        self.assertTrue(structured.check_sync(ROOT))
        doc = structured.load_changelog(ROOT / structured.STRUCTURED_CHANGELOG_PATH)
        self.assertGreaterEqual(len(doc["releases"]), 23)
        self.assertEqual(doc["releases"][-1]["version"], "0.42.0")

    def test_range_query_merges_categories_and_groups_by_version(self) -> None:
        doc = _sample_document()
        resolved = structured.resolve_version_range(doc, "v1.0.0", "v1.2.0")
        self.assertEqual(resolved["fromVersion"], "1.0.0")
        self.assertEqual(resolved["toVersion"], "1.2.0")
        self.assertEqual(resolved["includedVersions"], ["1.2.0", "1.1.0"])
        self.assertEqual(
            resolved["compareUrl"],
            "https://github.com/turboism/Turboism/compare/v1.0.0...v1.2.0",
        )
        self.assertEqual(
            [item["version"] for item in resolved["mergedSections"]["added"]],
            ["1.2.0", "1.1.0"],
        )

        by_category_zh = structured.render_range_markdown(resolved, lang="zh", group_by="category")
        self.assertIn("### 新增", by_category_zh)
        self.assertIn("- 新增 C。", by_category_zh)
        self.assertIn("- 新增 B。", by_category_zh)
        self.assertIn("- Fixed C only in English.", by_category_zh)
        self.assertIn("compare/v1.0.0...v1.2.0", by_category_zh)

        by_version_en = structured.render_range_markdown(resolved, lang="en", group_by="version")
        self.assertIn("## [1.2.0] - 2026-11-25", by_version_en)
        self.assertIn("## [1.1.0] - 2026-11-20", by_version_en)

    def test_range_query_supports_unreleased_and_rejects_invalid_bounds(self) -> None:
        doc = _sample_document()
        unreleased = structured.resolve_version_range(doc, "1.1.0", "unreleased")
        self.assertEqual(unreleased["includedVersions"], ["Unreleased", "1.2.0"])
        self.assertIsNone(unreleased["compareUrl"])

        for bad_from, bad_to in (
            ("1.2.0", "1.0.0"),
            ("1.1.0", "1.1.0"),
            ("9.9.9", "1.2.0"),
            ("1.0.0", "9.9.9"),
            ("Unreleased", "1.2.0"),
        ):
            with self.assertRaises(ValueError):
                structured.resolve_version_range(doc, bad_from, bad_to)

    def test_render_release_notes_json_and_cli_diff_json(self) -> None:
        doc = _sample_document()
        notes = structured.render_release_notes_json(doc, "1.1.0")
        self.assertEqual(notes["schemaVersion"], 1)
        self.assertEqual(notes["version"], "1.1.0")
        self.assertIn("### 新增", notes["locales"]["zh"])

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            structured.write_changelog(root / structured.STRUCTURED_CHANGELOG_PATH, doc)
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                rc = cli.main(
                    ["--root", str(root), "diff", "1.0.0", "1.2.0", "--lang", "en", "--format", "json"]
                )
            self.assertEqual(rc, 0)
            payload = json.loads(buffer.getvalue())
            self.assertEqual(payload["includedVersions"], ["1.2.0", "1.1.0"])
            self.assertIn("### Added", payload["markdown"])

    def test_channel_transitions_beta_to_beta_beta_to_stable_and_stable_to_beta(self) -> None:
        doc = {
            "format": structured.FORMAT,
            "schemaVersion": structured.SCHEMA_VERSION,
            "repository": structured.REPOSITORY_URL,
            "locales": list(structured.LOCALES),
            "categories": list(structured.CATEGORIES),
            "unreleased": {"sections": {}},
            "releases": [
                {
                    "version": "1.1.0",
                    "channel": "stable",
                    "date": "2026-11-26",
                    "previousVersion": "1.1.0-beta.5",
                    "sections": {"fixed": [{"en": "Stable polish.", "zh": "正式版打磨。"}]},
                },
                {
                    "version": "1.1.0-beta.5",
                    "channel": "beta",
                    "date": "2026-11-25",
                    "previousVersion": "1.1.0-beta.4",
                    "sections": {"added": [{"en": "Beta 5 feature.", "zh": "Beta 5 新功能。"}]},
                },
                {
                    "version": "1.1.0-beta.4",
                    "channel": "beta",
                    "date": "2026-11-24",
                    "previousVersion": "1.0.0",
                    "sections": {"added": [{"en": "Beta 4 feature.", "zh": "Beta 4 新功能。"}]},
                },
                {
                    "version": "1.0.0",
                    "channel": "stable",
                    "date": "2026-11-20",
                    "previousVersion": None,
                    "sections": {"added": [{"en": "Stable 1.0.", "zh": "正式版 1.0。"}]},
                },
            ],
        }
        beta_to_beta = structured.resolve_version_range(doc, "1.1.0-beta.4", "1.1.0-beta.5")
        self.assertEqual(beta_to_beta["includedVersions"], ["1.1.0-beta.5"])
        self.assertEqual(beta_to_beta["fromChannel"], "beta")
        self.assertEqual(beta_to_beta["toChannel"], "beta")

        beta_to_stable = structured.resolve_version_range(doc, "1.1.0-beta.4", "1.1.0")
        self.assertEqual(beta_to_stable["includedVersions"], ["1.1.0", "1.1.0-beta.5"])
        self.assertEqual(beta_to_stable["toChannel"], "stable")

        stable_to_beta = structured.resolve_version_range(doc, "1.0.0", "1.1.0-beta.5")
        self.assertEqual(stable_to_beta["includedVersions"], ["1.1.0-beta.5", "1.1.0-beta.4"])
        self.assertEqual(stable_to_beta["fromChannel"], "stable")
        self.assertEqual(stable_to_beta["toChannel"], "beta")

        # Downgrade from stable to its own beta is rejected.
        with self.assertRaises(ValueError):
            structured.resolve_version_range(doc, "1.1.0", "1.1.0-beta.5")


if __name__ == "__main__":
    unittest.main()
