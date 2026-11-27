#!/usr/bin/env python3
"""CLI for structured changelog sync, validation, single-release inspection, and range queries."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

_RELEASE_DIR = str(Path(__file__).resolve().parent)
if _RELEASE_DIR not in sys.path:
    sys.path.insert(0, _RELEASE_DIR)

from turboism_release import changelog as structured  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]


def _cmd_sync(root: Path) -> int:
    document = structured.parse_markdown_changelogs(root)
    target = root / structured.STRUCTURED_CHANGELOG_PATH
    structured.write_changelog(target, document)
    print(f"wrote {structured.STRUCTURED_CHANGELOG_PATH} ({len(document['releases'])} releases)")
    return 0


def _cmd_check(root: Path) -> int:
    target = root / structured.STRUCTURED_CHANGELOG_PATH
    if not target.is_file():
        print(
            f"missing {structured.STRUCTURED_CHANGELOG_PATH}; run scripts/release/changelog.py sync",
            file=sys.stderr,
        )
        return 1
    committed = structured.load_changelog(target)
    rebuilt = structured.parse_markdown_changelogs(root)
    if committed != rebuilt:
        print(
            f"drift: {structured.STRUCTURED_CHANGELOG_PATH} does not match docs/*/CHANGELOG.md; "
            "run scripts/release/changelog.py sync",
            file=sys.stderr,
        )
        return 1
    return 0


def _cmd_versions(root: Path, output_format: str) -> int:
    document = structured.load_changelog(root / structured.STRUCTURED_CHANGELOG_PATH)
    entries = [
        {
            "version": release["version"],
            "date": release["date"],
            "previousVersion": release["previousVersion"],
        }
        for release in document["releases"]
    ]
    if output_format == "json":
        print(json.dumps(entries, ensure_ascii=False, indent=2))
    else:
        for entry in entries:
            print(f"{entry['version']}\t{entry['date']}")
    return 0


def _cmd_show(root: Path, version: str, lang: str, output_format: str) -> int:
    document = structured.load_changelog(root / structured.STRUCTURED_CHANGELOG_PATH)
    normalized = structured.normalize_version(version)
    release = next((item for item in document["releases"] if item["version"] == normalized), None)
    if release is None:
        raise ValueError(f"unknown release version: {version!r}")
    if output_format == "json":
        print(json.dumps(release, ensure_ascii=False, indent=2))
    else:
        print(
            structured.render_release_markdown(
                release,
                lang=lang,
                repository=document.get("repository", structured.REPOSITORY_URL),
            ),
            end="",
        )
    return 0


def _cmd_diff(
    root: Path,
    from_version: str,
    to_version: str,
    lang: str,
    group_by: str,
    output_format: str,
) -> int:
    document = structured.load_changelog(root / structured.STRUCTURED_CHANGELOG_PATH)
    resolved = structured.resolve_version_range(document, from_version, to_version)
    if output_format == "json":
        payload = {
            **resolved,
            "lang": lang,
            "groupBy": group_by,
            "markdown": structured.render_range_markdown(resolved, lang=lang, group_by=group_by),
        }
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(structured.render_range_markdown(resolved, lang=lang, group_by=group_by), end="")
    return 0


def _cmd_render_release_notes(root: Path, version: str) -> int:
    document = structured.load_changelog(root / structured.STRUCTURED_CHANGELOG_PATH)
    payload = structured.render_release_notes_json(document, version)
    target = root / structured.RELEASE_NOTES_DIR / f"{payload['version']}.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {target.relative_to(root)}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    """Creates the command-line parser for structured changelog operations."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO_ROOT, help="repository root path")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("sync", help="rebuild distribution/changelog.json from docs/*/CHANGELOG.md")
    sub.add_parser("check", help="validate distribution/changelog.json and check for markdown drift")

    versions_cmd = sub.add_parser("versions", help="list available release versions")
    versions_cmd.add_argument("--format", choices=("text", "json"), default="text")

    show_cmd = sub.add_parser("show", help="display one release from the structured changelog")
    show_cmd.add_argument("version", help="release version (e.g. 0.70.3 or v0.70.3)")
    show_cmd.add_argument("--lang", choices=structured.LOCALES, default=structured.DEFAULT_LOCALE)
    show_cmd.add_argument("--format", choices=("markdown", "json"), default="markdown")

    diff_cmd = sub.add_parser("diff", help="query structured changes between two versions (from, to]")
    diff_cmd.add_argument("from_version", help="exclusive base version (e.g. 0.70.1)")
    diff_cmd.add_argument("to_version", help="inclusive target version (e.g. 0.70.3 or Unreleased)")
    diff_cmd.add_argument("--lang", choices=structured.LOCALES, default=structured.DEFAULT_LOCALE)
    diff_cmd.add_argument(
        "--group-by",
        choices=structured.VALID_GROUP_BY,
        default=structured.GROUP_BY_CATEGORY,
    )
    diff_cmd.add_argument("--format", choices=("markdown", "json"), default="markdown")

    render_notes = sub.add_parser(
        "render-release-notes",
        help="generate distribution/release-notes/<version>.json from distribution/changelog.json",
    )
    render_notes.add_argument("version", help="release version to render")
    return parser


def main(argv: list[str] | None = None) -> int:
    """Executes the requested structured changelog CLI subcommand."""
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "sync":
            return _cmd_sync(args.root)
        if args.command == "check":
            return _cmd_check(args.root)
        if args.command == "versions":
            return _cmd_versions(args.root, args.format)
        if args.command == "show":
            return _cmd_show(args.root, args.version, args.lang, args.format)
        if args.command == "diff":
            return _cmd_diff(
                args.root,
                args.from_version,
                args.to_version,
                args.lang,
                args.group_by,
                args.format,
            )
        if args.command == "render-release-notes":
            return _cmd_render_release_notes(args.root, args.version)
    except ValueError as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 2
    return 1


if __name__ == "__main__":
    sys.exit(main())
