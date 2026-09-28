#!/usr/bin/env python3
"""Self-test for render_localized_changelogs.py: JSON-covered sections render verbatim,
legacy/locale-missing sections pass through, drift detection works."""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "release"))
import render_localized_changelogs as rlc  # noqa: E402


def write_tree(root: Path) -> None:
    (root / "release-notes").mkdir(parents=True)
    (root / "CHANGELOG.md").write_text(
        "# Changelog\n\n## [Unreleased]\n\n### Added\n\n- WIP item.\n\n"
        "## [1.0.0] - 2030-01-01\n\n### Added\n\n- English one.\n\n"
        "## [0.9.0] - 2029-01-01\n\n### Fixed\n\n- Legacy English.\n",
        encoding="utf-8")
    (root / "release-notes" / "1.0.0.json").write_text(json.dumps({
        "schemaVersion": 1, "version": "1.0.0",
        "englishSha256": hashlib.sha256("### Added\n\n- English one.".encode()).hexdigest(),
        "locales": {"zh": "### 新增\n\n- 中文一。", "ja": "### 追加\n\n- 日本語一。",
                    "ko": "### 추가\n\n- 한국어一。"},
    }), encoding="utf-8")
    for lang, head, wip, legacy in (
        ("zh", "# 更新日志", "- 中文 WIP。", "- 中文旧版。"),
        ("ja", "# 更新履歴", "- 日文 WIP。", "- 日文旧版。"),
        ("ko", "# 변경 기록", "- 韩文 WIP。", "- 韩文旧版。"),
    ):
        (root / f"CHANGELOG_{lang}.md").write_text(
            f"{head}\n\n## [Unreleased]\n\n### X\n\n{wip}\n\n"
            f"## [1.0.0] - 2030-01-01\n\n### X\n\n- {lang} hand-written.\n\n"
            f"## [0.9.0] - 2029-01-01\n\n### X\n\n{legacy}\n",
            encoding="utf-8")


def run_case(check: bool) -> int:
    rc = 0
    for lang in rlc.LOCALES:
        rc |= rlc.render(lang, check=check)
    return rc


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        original_root, original_notes = rlc.ROOT, rlc.NOTES_DIR
        try:
            root = Path(tmp)
            write_tree(root)
            rlc.ROOT, rlc.NOTES_DIR = root, root / "release-notes"
            rlc.ENGLISH = root / "CHANGELOG.md"

            if run_case(check=True) != 1:
                print("FAIL: drift was not detected before rendering")
                return 1
            if run_case(check=False) != 0:
                print("FAIL: render returned nonzero")
                return 1
            zh = (root / "CHANGELOG_zh.md").read_text(encoding="utf-8")
            assert "- 中文一。" in zh, "JSON body not rendered for covered version"
            assert "- 中文 hand-written." not in zh, "committed body survived a covered version"
            assert "- 中文 WIP。" in zh, "unreleased section lost"
            assert "- 中文旧版。" in zh, "legacy pre-JSON section lost"
            ko = (root / "CHANGELOG_ko.md").read_text(encoding="utf-8")
            assert "### 추가" in ko, "ko JSON body missing"
            if run_case(check=True) != 0:
                print("FAIL: rendered output is not stable in --check")
                return 1

            # Locale missing from the JSON falls back to committed text.
            notes = json.loads((root / "release-notes" / "1.0.0.json").read_text())
            del notes["locales"]["ko"]
            (root / "release-notes" / "1.0.0.json").write_text(json.dumps(notes))
            assert run_case(check=False) == 0
            ko = (root / "CHANGELOG_ko.md").read_text(encoding="utf-8")
            assert "- ko hand-written." in ko or "- 韩文旧版。" in ko, \
                "ko fallback for locale-missing section lost"
        finally:
            rlc.ROOT, rlc.NOTES_DIR, rlc.ENGLISH = \
                original_root, original_notes, original_root / "CHANGELOG.md"
    print("render_localized_changelogs self-test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
