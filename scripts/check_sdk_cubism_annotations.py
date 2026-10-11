#!/usr/bin/env python3
"""Fail when a public SDK type in the Cubism tree lacks ``@CubismEditor``.

Every public type under ``dev.turboism.sdk.cubism`` declares host-facing API:
reaching it means the plugin touched a Cubism capability surface, so the type
must carry an explicit ``@CubismEditor`` availability declaration — even a bare
``@CubismEditor`` that selects every reviewed version. "New API forgets the
annotation" is how an undocumented full-version grant ships.

A type counts as annotated when:

- it carries ``@CubismEditor`` on its own declaration;
- a lexically enclosing type carries it (a nested type ships inside its
  owner's availability contract); or
- an interface it extends (``extends``) or a class's implemented interface
  (``implements``) is annotated — mirroring the interface-parent walk in
  ``CubismEditorAvailabilityPolicy``. A superclass declaration deliberately
  does not count: the runtime policy never reads it.

Coverage is declared at type granularity: once a type is annotated, its
methods are covered ("或其所属类型带"), so the baseline lists types only.
Method-level ``@CubismEditor`` annotations on an otherwise unannotated type
still leave the type unrestricted and do not satisfy the rule.

``sdk/api-contracts/cubism-annotation-baseline.json`` freezes the legacy gap:
every currently unannotated public type is registered there. The check fails
when a new unannotated type appears, when a registered type gains an
annotation (the entry is stale — remove it), and when a registered type
disappears. ``--update`` rewrites the baseline from the current sources; use
it only when deliberately shrinking or re-baselining the backlog.

Usage:

    check_sdk_cubism_annotations.py [repo-root]
    check_sdk_cubism_annotations.py --update [repo-root]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SDK_MAIN = Path("sdk/src/main/java")
SCOPE = "dev.turboism.sdk.cubism"
BASELINE = Path("sdk/api-contracts/cubism-annotation-baseline.json")
BASELINE_FORMAT = "turboism.sdk-cubism-annotation-baseline/v1"

ANNOTATION = re.compile(r"@(?:dev\.turboism\.sdk\.)?CubismEditor\b")
TYPE_DECL = re.compile(r"\b(class|interface|enum|@interface|record)\s+(\w+)")
PACKAGE = re.compile(r"^package\s+([\w.]+)\s*;", re.MULTILINE)
MODIFIER = re.compile(
    r"(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s*$"
)
ANNOTATION_TAIL = re.compile(r"@[\w.]+(?:\s*\([^()]*\))?\s*$")


class AnnotationCheckError(Exception):
    """Raised when a checked surface cannot be parsed at all."""


def strip_java(text: str) -> str:
    """Blank comments and literals while preserving offsets."""
    def repl(match: re.Match) -> str:
        return "".join("\n" if c == "\n" else " " for c in match.group(0))

    return re.sub(
        r"//[^\n]*|/\*.*?\*/|\"(?:\\.|[^\"\\])*\"|\'(?:\\.|[^\'\\])*\'",
        repl,
        text,
        flags=re.DOTALL,
    )


def _skip_generics(stripped: str, pos: int) -> int:
    if pos < len(stripped) and stripped[pos] == "<":
        depth = 0
        while pos < len(stripped):
            if stripped[pos] == "<":
                depth += 1
            elif stripped[pos] == ">":
                depth -= 1
                if depth == 0:
                    return pos + 1
            pos += 1
    return pos


def _skip_parens(stripped: str, pos: int) -> int:
    if pos < len(stripped) and stripped[pos] == "(":
        depth = 0
        while pos < len(stripped):
            if stripped[pos] == "(":
                depth += 1
            elif stripped[pos] == ")":
                depth -= 1
                if depth == 0:
                    return pos + 1
            pos += 1
    return pos


def type_spans(stripped: str) -> list[tuple[str, str, int, int]]:
    """(kind, name, decl start, body end) for every type declaration."""
    spans: list[tuple[str, str, int, int]] = []
    for match in TYPE_DECL.finditer(stripped):
        kind, name = match.group(1), match.group(2)
        pos = _skip_generics(stripped, match.end())
        if kind == "record":
            pos = _skip_parens(stripped, pos)
            pos = _skip_generics(stripped, pos)
        brace = stripped.find("{", pos)
        semi = stripped.find(";", pos)
        if brace == -1 or (semi != -1 and semi < brace):
            continue
        depth = 0
        i = brace
        while i < len(stripped):
            if stripped[i] == "{":
                depth += 1
            elif stripped[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        if i >= len(stripped):
            raise AnnotationCheckError(f"unbalanced type body for {name}")
        spans.append((kind, name, match.start(), i))
    return spans


def decl_head(stripped: str, start: int) -> str:
    """The modifiers and annotations immediately preceding a declaration."""
    i = start
    while i > 0:
        match = ANNOTATION_TAIL.search(stripped, max(0, i - 400), i)
        if match and match.end() == i:
            i = match.start()
            continue
        match = MODIFIER.search(stripped, max(0, i - 60), i)
        if match and match.end() == i:
            i = match.start()
            continue
        break
    return stripped[i:start]


def _super_type_names(segment: str) -> list[str]:
    """Simple names named by extends/implements clauses of a declaration."""
    names: list[str] = []
    for keyword in ("extends", "implements"):
        match = re.search(rf"\b{keyword}\s+([\w.,\s<>]+?)(?:\bextends\b|\bimplements\b|$)", segment)
        if match:
            names.extend(
                entry.strip().split("<")[0].split()[-1]
                for entry in match.group(1).split(",")
                if entry.strip()
            )
    return names


class SdkTypeIndex:
    """Public type inventory of the ``dev.turboism.sdk.cubism`` tree."""

    def __init__(self) -> None:
        self.types: dict[str, dict] = {}
        self.supers: dict[str, list[str]] = {}

    def load(self, root: Path) -> None:
        base = root / SDK_MAIN
        if not base.is_dir():
            raise AnnotationCheckError(f"{SDK_MAIN}: directory missing")
        for path in sorted(base.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            package_match = PACKAGE.search(text)
            if package_match is None:
                continue
            package = package_match.group(1)
            if package != SCOPE and not package.startswith(SCOPE + "."):
                continue
            stripped = strip_java(text)
            spans = type_spans(stripped)
            relative = path.relative_to(root).as_posix()
            for kind, name, start, end in spans:
                enclosing = sorted(
                    (s for s in spans if s[2] < start < s[3]), key=lambda s: s[2]
                )
                chain = [s[1] for s in enclosing] + [name]
                fqn = package + "." + ".".join(chain)
                head = decl_head(stripped, start)
                public = bool(re.search(r"\bpublic\b", head))
                segment = stripped[start:stripped.find("{", start)]
                self.types[fqn] = {
                    "kind": kind,
                    "public": public,
                    "file": relative,
                    "enclosing": (
                        package + "." + ".".join(chain[:-1]) if len(chain) > 1 else None
                    ),
                    "annotated": bool(ANNOTATION.search(head)),
                }
                self.supers[fqn] = _super_type_names(segment)

    def _resolve_super(self, fqn: str, name: str) -> str | None:
        if name in self.types:
            return name
        package = fqn.rsplit(".", 1)[0]
        same_package = package + "." + name
        if same_package in self.types:
            return same_package
        matches = [t for t in self.types if t.rsplit(".", 1)[-1] == name]
        if len(matches) == 1:
            return matches[0]
        return None

    def effectively_annotated(self, fqn: str, seen: set[str] | None = None) -> bool:
        seen = seen or set()
        if fqn in seen:
            return False
        seen.add(fqn)
        info = self.types.get(fqn)
        if info is None:
            return False
        if info["annotated"]:
            return True
        enclosing = info["enclosing"]
        if enclosing and self.effectively_annotated(enclosing, seen):
            return True
        for name in self.supers.get(fqn, []):
            resolved = self._resolve_super(fqn, name)
            # The runtime policy walks interface parents only; a superclass
            # never transfers its declaration. Resolved parents in this index
            # are interfaces by construction: `extends` on an interface names
            # interfaces and `implements` names interfaces; a class's
            # superclass is collected too but filtered here by kind.
            if (
                resolved
                and self.types[resolved]["kind"] in ("interface", "@interface")
                and self.effectively_annotated(resolved, seen)
            ):
                return True
        return False

    def unannotated_public_types(self) -> list[str]:
        return sorted(
            fqn
            for fqn, info in self.types.items()
            if info["public"] and not self.effectively_annotated(fqn)
        )


def load_baseline(root: Path) -> list[str]:
    path = root / BASELINE
    if not path.is_file():
        raise AnnotationCheckError(
            f"{BASELINE}: baseline missing; create it with --update"
        )
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("format") != BASELINE_FORMAT:
        raise AnnotationCheckError(f"{BASELINE}: unexpected format {document.get('format')!r}")
    entries = document.get("unannotatedTypes")
    if not isinstance(entries, list) or not all(
        isinstance(entry, str) for entry in entries
    ):
        raise AnnotationCheckError(f"{BASELINE}: 'unannotatedTypes' must be a string list")
    if entries != sorted(entries) or len(set(entries)) != len(entries):
        raise AnnotationCheckError(f"{BASELINE}: entries must be unique and sorted")
    return entries


def write_baseline(root: Path, unannotated: list[str]) -> None:
    document = {
        "format": BASELINE_FORMAT,
        "comment": (
            "Frozen backlog of public dev.turboism.sdk.cubism types that predate the "
            "@CubismEditor requirement. Regenerate with "
            "`python3 scripts/check_sdk_cubism_annotations.py --update` only when "
            "annotating entries or deliberately re-baselining; new unannotated "
            "public types fail checkSdkCubismEditorAnnotations until annotated."
        ),
        "scope": SCOPE,
        "unannotatedTypes": sorted(unannotated),
    }
    path = root / BASELINE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def run(root: Path, update: bool) -> int:
    index = SdkTypeIndex()
    index.load(root)
    observed = index.unannotated_public_types()
    if update:
        write_baseline(root, observed)
        print(f"updated {BASELINE} with {len(observed)} unannotated public type(s)")
        return 0
    baseline = set(load_baseline(root))
    failures: list[str] = []
    for fqn in observed:
        if fqn not in baseline:
            failures.append(
                f"new unannotated public Cubism SDK type: {fqn} "
                f"(declare @CubismEditor on it or an interface parent)"
            )
    for fqn in sorted(baseline - set(observed)):
        info = index.types.get(fqn)
        if info is None:
            failures.append(
                f"stale baseline entry: {fqn} no longer exists; remove it from {BASELINE}"
            )
        else:
            failures.append(
                f"stale baseline entry: {fqn} is now annotated; remove it from {BASELINE}"
            )
    for failure in failures:
        print(f"FAIL: {failure}")
    if failures:
        print(f"\n{len(failures)} annotation-baseline finding(s)", file=sys.stderr)
        return 1
    print(
        f"PASS: every public {SCOPE} type is @CubismEditor-annotated or baselined "
        f"({len(baseline)} legacy entrant(s) frozen)"
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("root", nargs="?", default=str(ROOT))
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args(argv)
    try:
        return run(Path(args.root).resolve(), args.update)
    except AnnotationCheckError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
