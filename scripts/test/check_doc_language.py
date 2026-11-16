#!/usr/bin/env python3
"""Keeps repository documentation and code comments in English.

Repository documentation, code comments, and log strings are English by
default, so a reviewer outside the author's language can read them. Product
localization is the only exception: translated docs, message bundles, installer
language packs, and localized release-note fields stay in their own language and
are never reported here.

What the check reads:

* documentation files are scanned line by line, and a line counts as a failure
  when it reads as translated prose rather than an identifier;
* source files are scanned for their comments, which must be English, so a
  translated comment fails while a quoted native caption inside it passes;
* string literals are not scanned at all, because a host probe must match the
  localized captions a real Cubism Editor renders.

``POLICY_SCOPE`` names the directories that already satisfy the policy. Add a
directory to it once its documents are converted, so the check stays a real gate
instead of a permanent list of known failures. ``--all`` audits the whole
repository for planning.

Usage: check_doc_language.py [--root PATH] [--paths PATH ...] [--all] [--report] [--quiet]
"""
from __future__ import annotations

import argparse
import io
import re
import sys
import tokenize
from pathlib import Path

# Directories that already satisfy the English-by-default policy.
POLICY_SCOPE = ("testing", "validation")

_CJK = re.compile(r"[\u3000-\u303f\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff01-\uff60\uffe0-\uffe6]")
_DOC_SUFFIXES = frozenset({".md", ".markdown", ".txt", ".rst", ".adoc"})
_CODE_SUFFIXES = frozenset({".java", ".kt", ".kts", ".py", ".sh", ".ps1", ".mjs", ".js", ".ts"})
_SKIP_DIRS = frozenset({".git", ".gradle", ".idea", ".codegraph", "build", "node_modules", "out", "dist"})
# Localized products: translated docs, message bundles, installer language packs,
# release-note translations, and end-user license translations.
_LOCALIZED_PATH = re.compile(
    r"(^|/)(docs/(zh|ja|ko)|i18n|eula|release-notes[^/]*|locales?|lang)(/|$)",
    re.IGNORECASE,
)
_LOCALIZED_NAME = re.compile(
    r"(_zh(_Hans|_Hant)?|_ja|_ko)(?=\.|$)|\.(zh|ja|ko)(?=\.|$)|zh[-_](CN|TW|Hans|Hant)|CustomLangPack",
    re.IGNORECASE,
)
# Multilingual content and localized data fields, documented per entry.
LOCALIZED_FILES = frozenset({
    # Startup motto catalog: the quotes are intentionally multilingual content.
    "bootstrap/src/main/resources/META-INF/turboism/startup-quotes.txt",
    # Localized compatibility-matrix string tables, one entry per language.
    "scripts/compat_matrix/strings.py",
    # Reviewed Cubism feature timeline carries localized display names per feature.
    "compatibility/cubism/official-feature-timeline.json",
    # Paseo workspace configuration addresses a Chinese-speaking operator.
    "paseo.json",
    # Verbatim capture of the localized plugin dialog the host rendered, kept as evidence.
    "validation/texture-atlas-current-page/real-heavy/layout-dialog.txt",
})
# A line whose CJK share dominates its Latin letters is translated prose. A quoted
# native caption inside an English sentence stays far below the threshold.
_PROSE_RATIO = 0.3
_MIN_PROSE_CHARS_DOC = 8
_MIN_PROSE_CHARS_COMMENT = 4

_JAVA_LINE_COMMENT = re.compile(r"//[^\n]*")
_JAVA_BLOCK_COMMENT = re.compile(r"/\*[\s\S]*?\*/")
_PY_COMMENT = re.compile(r"#[^\n]*")
_SHELL_COMMENT = re.compile(r"(?m)^\s*#[^\n]*")


def repo_root(anchor: Path | str) -> Path:
    """Return the repository root above ``anchor`` so the check never scans the wrong tree."""
    path = Path(anchor).resolve()
    candidate = path if path.is_dir() else path.parent
    for current in (candidate, *candidate.parents):
        if (current / "settings.gradle.kts").is_file() and (current / "scripts").is_dir():
            return current
    raise ValueError(f"no Turboism repository root above {path}")


def is_localized(relative: Path) -> bool:
    """Report whether a path belongs to a deliberate localization product."""
    text = relative.as_posix()
    if text in LOCALIZED_FILES:
        return True
    return bool(_LOCALIZED_PATH.search(text) or _LOCALIZED_NAME.search(relative.name))


def is_prose(text: str, minimum: int = _MIN_PROSE_CHARS_DOC) -> bool:
    """Report whether text reads as translated prose rather than an identifier or caption."""
    cjk = len(_CJK.findall(text))
    if cjk < minimum:
        return False
    latin = len(re.findall(r"[A-Za-z]", text))
    return cjk / (cjk + latin) >= _PROSE_RATIO


def python_comment_spans(text: str) -> list[tuple[int, int]]:
    """Return the real comment spans of one Python file, so a hash inside a literal is data."""
    offsets = [0]
    for line in text.splitlines(keepends=True):
        offsets.append(offsets[-1] + len(line))

    def offset(row: int, column: int) -> int:
        return offsets[row - 1] + column

    spans: list[tuple[int, int]] = []
    try:
        for token in tokenize.generate_tokens(io.StringIO(text).readline):
            if token.type == tokenize.COMMENT:
                spans.append((offset(*token.start), offset(*token.end)))
    except (tokenize.TokenError, IndentationError, SyntaxError):
        return [match.span() for match in _PY_COMMENT.finditer(text)]
    return spans


def comment_spans(text: str, suffix: str) -> list[tuple[int, int]]:
    """Return the comment spans of one source file, the only code text the policy reads."""
    if suffix == ".py":
        return python_comment_spans(text)
    if suffix == ".ps1":
        patterns = (_PY_COMMENT,)
    elif suffix == ".sh":
        patterns = (_SHELL_COMMENT,)
    else:
        patterns = (_JAVA_LINE_COMMENT, _JAVA_BLOCK_COMMENT)
    return [match.span() for pattern in patterns for match in pattern.finditer(text)]


def comment_prose_lines(text: str, suffix: str) -> list[int]:
    """Return the comment lines that read as translated prose rather than English prose."""
    lines = text.splitlines()
    numbers: set[int] = set()
    for start, end in comment_spans(text, suffix):
        first = text.count("\n", 0, start) + 1
        last = text.count("\n", 0, end) + 1
        numbers.update(range(first, last + 1))
    return sorted(
        number
        for number in numbers
        if _CJK.search(lines[number - 1]) and is_prose(lines[number - 1], _MIN_PROSE_CHARS_COMMENT)
    )


def offending_lines(text: str, suffix: str) -> list[int]:
    """Return the 1-based lines of one file that carry forbidden non-English text."""
    lines = text.splitlines()
    if suffix in _DOC_SUFFIXES:
        return [number for number, line in enumerate(lines, 1) if _CJK.search(line) and is_prose(line)]
    if suffix not in _CODE_SUFFIXES:
        return []
    return comment_prose_lines(text, suffix)


def iter_candidates(root: Path, seeds: list[Path]) -> list[Path]:
    """List every file below the seeds, skipping build output and dependency trees."""
    candidates: list[Path] = []
    for seed in seeds:
        if seed.is_file():
            candidates.append(seed)
            continue
        for path in sorted(seed.rglob("*")):
            if not path.is_file():
                continue
            if any(part in _SKIP_DIRS for part in path.relative_to(root).parts):
                continue
            candidates.append(path)
    return candidates


def scan(root: Path, seeds: list[Path]) -> list[tuple[Path, list[int]]]:
    """Return every covered file that still holds non-English text."""
    failures: list[tuple[Path, list[int]]] = []
    for path in iter_candidates(root, seeds):
        relative = path.relative_to(root)
        suffix = path.suffix.lower()
        if suffix not in _DOC_SUFFIXES | _CODE_SUFFIXES:
            continue
        if is_localized(relative):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        lines = offending_lines(text, suffix)
        if lines:
            failures.append((relative, lines))
    return failures


def resolve_seeds(root: Path, arguments: argparse.Namespace) -> list[Path]:
    """Resolve the directories to scan from the arguments, the policy scope, or the whole tree."""
    if arguments.paths:
        return [Path(path) if Path(path).is_absolute() else root / path for path in arguments.paths]
    if arguments.all:
        return [root]
    return [root / name for name in POLICY_SCOPE]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Fail when repository documentation or code comments are not English.")
    parser.add_argument("root", nargs="?", default=None, help="repository root, default: this checkout")
    parser.add_argument("--paths", nargs="*", default=None,
                        help="only scan these repository-relative files or directories")
    parser.add_argument("--all", action="store_true",
                        help="audit the whole repository instead of the converted policy scope")
    parser.add_argument("--report", action="store_true", help="print every offending line number")
    parser.add_argument("--quiet", action="store_true", help="print nothing on success")
    arguments = parser.parse_args(argv)
    try:
        root = repo_root(arguments.root or Path(__file__).resolve().parents[2])
    except ValueError as failure:
        print(f"Documentation language check failed: {failure}", file=sys.stderr)
        return 1
    seeds = resolve_seeds(root, arguments)
    missing = [seed for seed in seeds if not seed.exists()]
    if missing:
        print(
            "Documentation language check failed: " + ", ".join(str(seed) for seed in missing) + " does not exist",
            file=sys.stderr,
        )
        return 1
    failures = scan(root, seeds)
    if failures:
        total = sum(len(lines) for _, lines in failures)
        print(
            f"Documentation language check failed: {total} line(s) in {len(failures)} file(s) carry "
            "non-English text. Write documentation, comments, and log strings in English, and keep "
            "translations in a localized product path.",
            file=sys.stderr,
        )
        for relative, lines in failures:
            detail = ", ".join(str(line) for line in lines) if arguments.report else f"{len(lines)} line(s)"
            print(f"- {relative}: {detail}", file=sys.stderr)
        return 1
    if not arguments.quiet:
        scope = ", ".join(str(seed.relative_to(root)) for seed in seeds)
        print(f"PASS: default documentation is English under {scope}; localized products are exempt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
