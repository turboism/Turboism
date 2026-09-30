#!/usr/bin/env python3
"""Renders localized CHANGELOG_<lang>.md files from release-notes JSON.

`release-notes/<version>.json` is the single reviewed source for localized
release notes: each entry carries the reviewed zh/ja/ko bodies keyed to the
English section it was translated from (`englishSha256`). This script walks the
version list of the canonical English CHANGELOG.md and rewrites every
JSON-covered section in the localized changelogs to the JSON body verbatim.

Sections a JSON does not cover — the header, `## [Unreleased]`, and releases
older than the JSON scheme — pass through from the committed localized file,
so history predating the scheme is preserved exactly.

Usage:
    render_localized_changelogs.py            # rewrite localized changelogs
    render_localized_changelogs.py --check    # verify committed files match; exit 1 on drift
"""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
LOCALES = ("zh", "ja", "ko")
ENGLISH = ROOT / "CHANGELOG.md"
NOTES_DIR = ROOT / "release-notes"

SECTION_RE = re.compile(r"^## \[(?P<version>[^\]]+)\][^\n]*$", re.M)


def sections(text):
    """Split a changelog into (header, [(version, header_line, body)])."""
    marks = [(m.group("version"), m.start(), m.end()) for m in SECTION_RE.finditer(text)]
    if not marks:
        raise ValueError("changelog has no version sections")
    header = text[: marks[0][1]]
    out = []
    for i, (version, start, end) in enumerate(marks):
        stop = marks[i + 1][1] if i + 1 < len(marks) else len(text)
        out.append((version, text[start:end], text[end:stop].strip()))
    return header, out


def load_notes(version):
    path = NOTES_DIR / f"{version}.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def render(lang, check=False):
    target = ROOT / f"CHANGELOG_{lang}.md"
    committed = target.read_text(encoding="utf-8")
    committed_header, committed_sections = sections(committed)
    committed_by_version = {v: (h, b) for v, h, b in committed_sections}

    en_header_unused, en_sections = sections(ENGLISH.read_text(encoding="utf-8"))

    rendered = [committed_header]
    warnings = []
    missing = []
    for version, header_line, en_body in en_sections:
        if version == "Unreleased":
            # Hand-translated work-in-progress notes; keep the committed section.
            rendered.append(header_line + "\n\n" +
                            (committed_by_version.get(version, ("", ""))[1] or ""))
            continue
        notes = load_notes(version)
        if notes is None:
            # Legacy release predating the JSON scheme; keep committed text.
            committed_entry = committed_by_version.get(version)
            if committed_entry is None:
                missing.append(version)
            rendered.append(
                header_line + "\n\n" + (committed_entry[1] if committed_entry else ""))
            continue
        anchor = hashlib.sha256(en_body.encode()).hexdigest()
        if anchor != notes.get("englishSha256"):
            warnings.append(
                f"{version}: English section drifted from the reviewed release-notes anchor "
                f"({notes.get('englishSha256', '')[:16]}… -> {anchor[:16]}…)")
        body = notes.get("locales", {}).get(lang)
        committed_entry = committed_by_version.get(version)
        if not body:
            # The reviewed matrix grew a locale mid-history (e.g. ko arrived in 0.44.0);
            # sections the JSON never covered stay as committed.
            if committed_entry is None:
                raise ValueError(f"{version}: release-notes JSON has no '{lang}' locale "
                                 "and the committed changelog has no section to keep")
            rendered.append(header_line + "\n\n" + committed_entry[1])
            continue
        rendered.append(header_line + "\n\n" + body.strip())
    result = "\n\n".join(part.strip() for part in rendered if part.strip()) + "\n"

    for warning in warnings:
        print(f"warning: {warning}", file=sys.stderr)
    if missing:
        print(f"warning: versions without localized sections: {missing}", file=sys.stderr)
    if check:
        if result != committed:
            print(f"drift: {target.name} does not match the release-notes JSON; "
                  "run scripts/release/render_localized_changelogs.py", file=sys.stderr)
            return 1
        return 0
    if result != committed:
        target.write_text(result, encoding="utf-8")
        print(f"rendered {target.name}")
    else:
        print(f"{target.name} already in sync")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="verify committed localized changelogs instead of rewriting")
    args = parser.parse_args()
    status = 0
    for lang in LOCALES:
        status |= render(lang, check=args.check)
    return status


if __name__ == "__main__":
    sys.exit(main())
