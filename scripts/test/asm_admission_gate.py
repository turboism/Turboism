#!/usr/bin/env python3
"""Fail-closed BT4 ASM admission checks. Uses only local files and Gradle caches."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET

COORDINATE = "org.ow2.asm:asm:9.7.1"
JAR_SHA = "8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281"
POM_SHA = "7229b03b30a73ee91008072d9e4569a51d8547fae8c50f527841aef4c1b0baa8"
LICENSE = "BSD-3-Clause"
LICENSE_URL = "https://asm.ow2.io/license.html"
JAR_URL = "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
POM_URL = "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.pom"
SCM_URL = "https://gitlab.ow2.org/asm/asm/"
RELEASE_TAG = "ASM_9_7_1"
RELEASE_EVIDENCE_URL = "https://gitlab.ow2.org/asm/asm/-/tags/ASM_9_7_1"
RETRIEVAL_DATE = "2026-07-11"
HEADER = ("component\tversion\tjarSha256\tpomSha256\tlicenseSpdx\t"
          "licenseEvidenceUrl\ttransitiveDependencies\tmavenCentralArtifactUrl\t"
          "mavenCentralPomUrl\tscmUrl\treleaseTag\treleaseEvidenceUrl\tretrievalDate\t"
          "retrievalSourceEvidence")


def fail(message: str) -> None:
    raise SystemExit(f"ASM supply-chain admission gate: {message}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def strip_comments(text: str) -> str:
    out: list[str] = []
    i = 0
    state = "code"
    quote = ""
    while i < len(text):
        if state == "code":
            if text.startswith("//", i):
                state = "line"; out.extend("  "); i += 2
            elif text.startswith("/*", i):
                state = "block"; out.extend("  "); i += 2
            elif text[i] in "\"'":
                quote = text[i]; state = "string"; out.append(text[i]); i += 1
            else:
                out.append(text[i]); i += 1
        elif state == "line":
            if text[i] == "\n": state = "code"; out.append("\n")
            else: out.append(" ")
            i += 1
        elif state == "block":
            if text.startswith("*/", i): state = "code"; out.extend("  "); i += 2
            else: out.append("\n" if text[i] == "\n" else " "); i += 1
        else:
            out.append(text[i])
            if text[i] == "\\" and i + 1 < len(text): out.append(text[i + 1]); i += 2
            else:
                if text[i] == quote: state = "code"
                i += 1
    return "".join(out)


def source_files(root: Path) -> list[Path]:
    result: list[Path] = []
    ignored = {".git", ".gradle", "build", "dist", "release"}
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ignored]
        for name in files:
            path = Path(base, name)
            rel = path.relative_to(root).as_posix()
            if name.endswith((".gradle", ".gradle.kts")) or rel.endswith("libs.versions.toml"):
                result.append(path)
    return sorted(result)


def extract_blocks(text: str, token: str) -> list[tuple[int, int, str]]:
    blocks: list[tuple[int, int, str]] = []
    pattern = re.compile(rf"\b{re.escape(token)}\s*\{{")
    for match in pattern.finditer(text):
        start = text.find("{", match.start())
        depth = 0; quote = None; escaped = False
        for i in range(start, len(text)):
            char = text[i]
            if quote:
                if escaped: escaped = False
                elif char == "\\": escaped = True
                elif char == quote: quote = None
            elif char in "\"'": quote = char
            elif char == "{": depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    blocks.append((match.start(), i + 1, text[start + 1:i])); break
        else: fail(f"unterminated {token} block")
    return blocks


def static_check(root: Path) -> None:
    files = source_files(root)
    if not files: fail("no Gradle build/settings/catalog files found")
    bytebuddy_hits: list[str] = []
    repository_sites: list[tuple[str, str]] = []
    root_repo_ok = False
    all_asm_occurrences: list[str] = []
    for path in files:
        rel = path.relative_to(root).as_posix()
        clean = strip_comments(path.read_text(encoding="utf-8"))
        compact = re.sub(r"\s+", " ", clean)
        dep_text = " ".join(body for _, _, body in extract_blocks(clean, "dependencies")) if rel == "build.gradle.kts" else compact
        if re.search(r"(?i)(byte\s*buddy|bytebuddy|net\.bytebuddy)", dep_text): bytebuddy_hits.append(rel)
        asm_text = dep_text if rel == "build.gradle.kts" else clean
        all_asm_occurrences.extend(rel for _ in re.finditer(r"org\.(?:ow2\.asm|objectweb\.asm)", asm_text))

        blocks = extract_blocks(clean, "repositories")
        calls = list(re.finditer(r"\b(?:mavenCentral|mavenLocal|google|ivy|flatDir|maven)\s*(?:\(|\{)", clean))
        for start, end, body in blocks:
            repository_sites.append((rel, "repositories block"))
            normalized = re.sub(r"\s+", "", body)
            if rel == "build.gradle.kts" and normalized == "mavenCentral()" and not root_repo_ok:
                root_repo_ok = True
            else:
                fail(f"repository calls are forbidden outside the existing root repositories {{ mavenCentral() }}; found {rel}")
            calls = [m for m in calls if not (start <= m.start() < end)]
        if calls:
            fail(f"repository calls are forbidden outside the existing root repositories {{ mavenCentral() }}; found {rel}")

    if bytebuddy_hits: fail("Byte Buddy is not admitted; found in " + ", ".join(sorted(set(bytebuddy_hits))))
    runtime = root / "runtime/build.gradle.kts"
    if not runtime.is_file(): fail("runtime/build.gradle.kts is missing")
    exact = re.findall(r"\bimplementation\s*\(\s*[\"']org\.ow2\.asm:asm:9\.7\.1[\"']\s*\)",
                       strip_comments(runtime.read_text(encoding="utf-8")))
    if len(exact) != 1: fail(f"expected exactly one literal runtime implementation({COORDINATE}), found {len(exact)}")
    if all_asm_occurrences != ["runtime/build.gradle.kts"]:
        fail("ASM may occur only once in runtime/build.gradle.kts; found in " + ", ".join(all_asm_occurrences))
    if not root_repo_ok or repository_sites != [("build.gradle.kts", "repositories block")]:
        fail("repository policy requires exactly the existing root repositories { mavenCentral() }")


def evidence_check(root: Path, gradle_home: Path) -> None:
    record = root / "validation/supply-chain/asm-9.7.1-supply-chain-admission.tsv"
    if not record.is_file(): fail(f"admission record is missing: {record.relative_to(root)}")
    lines = record.read_text(encoding="utf-8").splitlines()
    if len(lines) != 2 or lines[0] != HEADER: fail("admission TSV must contain the exact header and one evidence row")
    expected = ["dev.turboism:runtime -> org.ow2.asm:asm", "9.7.1", JAR_SHA, POM_SHA,
                LICENSE, LICENSE_URL, "none", JAR_URL, POM_URL, SCM_URL, RELEASE_TAG,
                RELEASE_EVIDENCE_URL, RETRIEVAL_DATE, "manual-maven-central-urls-and-cached-content"]
    if lines[1].split("\t") != expected: fail("admission TSV provenance/checksum/license/dependency evidence is inconsistent")

    cache = gradle_home / "caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1"
    jars = list(cache.glob("*/asm-9.7.1.jar")); poms = list(cache.glob("*/asm-9.7.1.pom"))
    if len(jars) != 1 or len(poms) != 1:
        fail(f"expected one resolved cached JAR and POM under {cache}; found {len(jars)} JAR/{len(poms)} POM")
    if sha256(jars[0]) != JAR_SHA or sha256(poms[0]) != POM_SHA:
        fail("cached JAR/POM content checksum does not match the TSV (cache location does not prove origin)")

    ns = {"m": "http://maven.apache.org/POM/4.0.0"}; pom = ET.parse(poms[0]).getroot()
    value = lambda name: pom.findtext(f"m:{name}", namespaces=ns)
    if (value("groupId"), value("artifactId"), value("version")) != ("org.ow2.asm", "asm", "9.7.1"):
        fail("cached POM coordinate does not match the admitted coordinate")
    licenses = [(n.findtext("m:name", namespaces=ns), n.findtext("m:url", namespaces=ns))
                for n in pom.findall("m:licenses/m:license", ns)]
    if licenses != [(LICENSE, LICENSE_URL)]: fail("cached POM license does not match the TSV")
    if pom.findall("m:dependencies/m:dependency", ns): fail("cached POM declares dependencies but TSV says none")
    if pom.findtext("m:scm/m:url", namespaces=ns) != SCM_URL: fail("cached POM SCM URL does not match the TSV")


class ClassReader:
    def __init__(self, data: bytes, path: Path):
        self.data = data
        self.path = path
        self.pos = 0

    def take(self, size: int) -> bytes:
        if size < 0 or self.pos + size > len(self.data):
            fail(f"truncated class file at offset {self.pos}: {self.path}")
        value = self.data[self.pos:self.pos + size]
        self.pos += size
        return value

    def u1(self) -> int:
        return self.take(1)[0]

    def u2(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u4(self) -> int:
        return struct.unpack(">I", self.take(4))[0]


def api_strings(path: Path) -> tuple[int, set[str]]:
    r = ClassReader(path.read_bytes(), path)
    if r.u4() != 0xCAFEBABE: fail(f"invalid class file: {path}")
    r.take(4); count = r.u2(); cp: list[object] = [None] * count; i = 1
    while i < count:
        tag = r.u1()
        if tag == 1: cp[i] = r.take(r.u2()).decode("utf-8", "replace")
        elif tag in (3, 4): cp[i] = (tag, None); r.take(4)
        elif tag in (5, 6): cp[i] = (tag, None); r.take(8); i += 1
        elif tag in (7, 8, 16, 19, 20): cp[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18): r.take(4)
        elif tag == 15: r.take(3)
        else: fail(f"unsupported constant-pool tag {tag}: {path}")
        i += 1
    def entry(index: int) -> object:
        if not 0 < index < count:
            fail(f"invalid constant-pool index {index}: {path}")
        value = cp[index]
        if value is None:
            fail(f"invalid unusable constant-pool index {index}: {path}")
        return value
    def utf(index: int) -> str:
        value = entry(index)
        if not isinstance(value, str):
            fail(f"constant-pool entry {index} is not a direct UTF-8 entry: {path}")
        return value
    def indirect_utf(index: int, expected_tag: int) -> str:
        value = entry(index)
        if not isinstance(value, tuple) or value[0] != expected_tag:
            fail(f"constant-pool entry {index} has invalid tag; expected {expected_tag}: {path}")
        return utf(value[1])
    def constant(index: int, allowed_tags: tuple[int, ...]) -> None:
        value = entry(index)
        if not isinstance(value, tuple) or value[0] not in allowed_tags:
            fail(f"constant-pool entry {index} has an invalid constant type: {path}")
        if value[0] == 8:
            # JVMS CONSTANT_String.string_index must directly name CONSTANT_Utf8.
            utf(value[1])

    access = r.u2(); strings: set[str] = set()
    strings.add(indirect_utf(r.u2(), 7)); super_index = r.u2()
    if super_index: strings.add(indirect_utf(super_index, 7))
    for _ in range(r.u2()): strings.add(indirect_utf(r.u2(), 7))

    def element_value() -> None:
        tag = chr(r.u1())
        if tag in "BCDFIJSZ": constant(r.u2(), (3, 4, 5, 6))
        elif tag == "s": strings.add(utf(r.u2()))
        elif tag == "e": strings.update((utf(r.u2()), utf(r.u2())))
        elif tag == "c": strings.add(utf(r.u2()))
        elif tag == "@": annotation()
        elif tag == "[":
            for _ in range(r.u2()): element_value()
        else: fail(f"invalid annotation element tag {tag}: {path}")
    def annotation() -> None:
        strings.add(utf(r.u2()))
        for _ in range(r.u2()): strings.add(utf(r.u2())); element_value()
    def annotations(parameter: bool = False) -> None:
        groups = r.u1() if parameter else 1
        for _ in range(groups):
            for _ in range(r.u2()): annotation()
    def type_annotation() -> None:
        target_type = r.u1()
        if target_type in (0x00, 0x01, 0x16):
            r.u1()
        elif target_type in (0x10, 0x17, 0x42, 0x43, 0x44, 0x45, 0x46):
            r.u2()
        elif target_type in (0x11, 0x12):
            r.take(2)
        elif target_type in (0x13, 0x14, 0x15):
            pass
        elif target_type in (0x40, 0x41):
            for _ in range(r.u2()):
                r.take(6)
        elif 0x47 <= target_type <= 0x4B:
            r.u2(); r.u1()
        else:
            fail(f"invalid type annotation target_type 0x{target_type:02x}: {path}")
        for _ in range(r.u1()):
            type_path_kind = r.u1(); type_argument_index = r.u1()
            if type_path_kind > 3 or (type_path_kind != 3 and type_argument_index != 0):
                fail(f"invalid type annotation type_path: {path}")
        annotation()
    def type_annotations() -> None:
        for _ in range(r.u2()): type_annotation()
    def attrs(collect: bool) -> None:
        for _ in range(r.u2()):
            name = utf(r.u2()); length = r.u4(); end = r.pos + length
            if end > len(r.data): fail(f"malformed {name} attribute: {path}")
            if collect and name == "Signature": strings.add(utf(r.u2()))
            elif collect and name == "ConstantValue":
                constant(r.u2(), (3, 4, 5, 6, 8))
            elif collect and name == "Exceptions":
                for _ in range(r.u2()): strings.add(indirect_utf(r.u2(), 7))
            elif collect and name in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"): annotations()
            elif collect and name in ("RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations"): annotations(True)
            elif collect and name in ("RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations"): type_annotations()
            elif collect and name == "AnnotationDefault": element_value()
            elif collect and name == "Record":
                for _ in range(r.u2()):
                    utf(r.u2())
                    strings.add(utf(r.u2()))
                    attrs(True)
            else:
                r.pos = end
            if r.pos != end: fail(f"malformed {name} attribute: {path}")
    for _ in range(r.u2()):
        member_access = r.u2(); utf(r.u2()); descriptor = utf(r.u2()); exposed = bool(member_access & 0x0005)
        if exposed: strings.add(descriptor)
        attrs(exposed)
    for _ in range(r.u2()):
        member_access = r.u2(); utf(r.u2()); descriptor = utf(r.u2()); exposed = bool(member_access & 0x0005)
        if exposed: strings.add(descriptor)
        attrs(exposed)
    attrs(bool(access & 0x0005))
    if r.pos != len(r.data): fail(f"trailing bytes after class file structure: {path}")
    return access, strings


def api_check(class_roots: list[Path]) -> None:
    missing = [str(root) for root in class_roots if not root.is_dir()]
    if missing: fail("expected compiled production class directories are missing: " + ", ".join(missing))
    class_files = sorted({p for root in class_roots for p in root.rglob("*.class")})
    if not class_files: fail("expected compiled production classes, found 0")
    for class_file in class_files:
        access, strings = api_strings(class_file)
        if access & 0x0005 and any("org/objectweb/asm" in value or "org.objectweb.asm" in value for value in strings):
            fail(f"public/protected production API exposes ASM: {class_file}")


def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("command", choices=["static", "evidence", "api"])
    parser.add_argument("--root", type=Path, default=Path.cwd()); parser.add_argument("--gradle-home", type=Path, default=Path.home() / ".gradle")
    parser.add_argument("class_roots", nargs="*", type=Path); args = parser.parse_args()
    if args.command == "static": static_check(args.root.resolve())
    elif args.command == "evidence": evidence_check(args.root.resolve(), args.gradle_home.resolve())
    else:
        if not args.class_roots: fail("api check requires compiled production class directories")
        api_check([p.resolve() for p in args.class_roots])

if __name__ == "__main__": main()
