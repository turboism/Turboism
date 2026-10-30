#!/usr/bin/env python3
"""Opt-in W2 cross/native MinGW build. No downloads or tool installation."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePath
import re
import shutil
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native/update-files"
OUTPUT = ROOT / "build/native/windows-receipt-validation"
NATIVES = {"openRoot", "identity", "openFile", "size", "read", "finish", "closeToken"}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


BUNDLE_FORMAT = "turboism-w2-validation-bundle-v1"
BUNDLE_MANIFEST = "bundle-manifest.json"
BUNDLE_REQUIRED = {"native-build.json", "turboism-update-files.dll", "windows-fixture-probe.exe", "windows-receipt-fixture.jar"}
BUNDLE_MAX_FILE = 64 * 1024 * 1024
BUNDLE_MAX_TOTAL = 128 * 1024 * 1024


def record_path(path: PurePath, root: PurePath) -> str:
    """Protocol-relative paths always use '/', including on native Windows."""
    return path.relative_to(root).as_posix()


def source_records(paths: list[Path], root: PurePath) -> dict[str, str]:
    return {record_path(path, root): digest(path) for path in sorted(paths)}


def bundle_names(names: list[str]) -> None:
    if len(names) != 7 or len({name.casefold() for name in names}) != len(names):
        raise ValueError("bundle requires exactly seven distinct files")
    extras = set(names) - BUNDLE_REQUIRED
    if not BUNDLE_REQUIRED.issubset(names) or len(extras) != 3:
        raise ValueError("bundle required files missing or extra files")
    for component in ("annotations", "core", "databind"):
        if sum(bool(re.fullmatch(r"jackson-" + component + r"-[0-9][A-Za-z0-9._-]*\.jar", name)) for name in extras) != 1:
            raise ValueError("bundle classpath must contain exactly three Jackson components")
    if any(".." in name or not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", name) for name in names):
        raise ValueError("unsafe bundle filename")


def plain_files(directory: Path) -> dict[str, Path]:
    # This is an operator-owned test package, not the W4 production loading sandbox.
    for parent in (directory, *directory.parents):
        attributes = parent.lstat()
        if not stat.S_ISDIR(attributes.st_mode) or getattr(attributes, "st_file_attributes", 0) & 0x400:
            raise ValueError("bundle directory link rejected")
    files = {}
    for path in directory.iterdir():
        attributes = path.lstat()
        if not stat.S_ISREG(attributes.st_mode) or attributes.st_nlink != 1 or getattr(attributes, "st_file_attributes", 0) & 0x400:
            raise ValueError("bundle directory/link/nonregular file rejected")
        files[path.name] = path
    return files


def seal_bundle(directory: Path) -> dict:
    files = plain_files(directory)
    files.pop(BUNDLE_MANIFEST, None)
    bundle_names(list(files))
    records = []
    total = 0
    for name, path in sorted(files.items()):
        size = path.stat().st_size
        total += size
        if not 0 < size <= BUNDLE_MAX_FILE or total > BUNDLE_MAX_TOTAL:
            raise ValueError("bundle size budget exceeded")
        records.append({"path": name, "bytes": size, "sha256": digest(path)})
    payload = {"format": BUNDLE_FORMAT, "schemaVersion": 1, "files": records}
    manifest = directory / BUNDLE_MANIFEST
    manifest.write_bytes((json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"))
    return {"bundleManifestSha256": digest(manifest), "manifest": record_path(manifest, directory)}


def verify_bundle(directory: Path, expected: str) -> dict:
    """Same contract as the PowerShell consumer; no bundle code is executed here."""
    if not re.fullmatch(r"[a-f0-9]{64}", expected):
        raise ValueError("independent full bundle manifest digest required")
    files = plain_files(directory)
    manifest = files.pop(BUNDLE_MANIFEST)
    if manifest.stat().st_size > 16384 or digest(manifest) != expected:
        raise ValueError("bundle manifest digest mismatch/limit")
    payload = json.loads(manifest.read_bytes())
    if set(payload) != {"format", "schemaVersion", "files"} or payload["format"] != BUNDLE_FORMAT or type(payload["schemaVersion"]) is not int or payload["schemaVersion"] != 1:
        raise ValueError("unknown bundle manifest")
    records = payload["files"]
    if not isinstance(records, list) or any(not isinstance(r, dict) or set(r) != {"path", "bytes", "sha256"} or not isinstance(r["path"], str) for r in records):
        raise ValueError("invalid bundle file record")
    bundle_names([r["path"] for r in records])
    if set(files) != {r["path"] for r in records}:
        raise ValueError("bundle inventory mismatch")
    total = 0
    for r in records:
        if type(r["bytes"]) is not int or not 0 < r["bytes"] <= BUNDLE_MAX_FILE or not isinstance(r["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", r["sha256"]):
            raise ValueError("invalid bundle size/digest")
        total += r["bytes"]
        path = files[r["path"]]
        if total > BUNDLE_MAX_TOTAL or path.stat().st_size != r["bytes"] or digest(path) != r["sha256"]:
            raise ValueError("bundle content mismatch")
    return payload


def check_sources() -> dict:
    policy = json.loads((SOURCE / "sources.json").read_text())
    if policy["schemaVersion"] != 1 or policy["abi"] != 1 or policy["platform"] != "windows-x64":
        raise ValueError("unknown native contract")
    for item in policy["headers"]:
        path = Path(item["path"])
        if path.is_absolute() or ".." in path.parts or digest(SOURCE / path) != item["sha256"]:
            raise ValueError("pinned header/license drift")
    java = (ROOT / "runtime/src/main/java/dev/turboism/update/WindowsInstallationFiles.java").read_text()
    native = (SOURCE / "jni_bridge.c").read_text()
    if set(re.findall(r"private static native \w+(?:\[\])? (\w+)\(", java)) != NATIVES:
        raise ValueError("Java ABI changed without gate update")
    if set(re.findall(r"Java_dev_turboism_update_WindowsInstallationFiles_(\w+)\(", native)) != NATIVES:
        raise ValueError("JNI ABI mismatch")
    return policy


def run(command: list[str]) -> str:
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=120)


def verify_pe(path: Path, objdump: str, policy: dict) -> None:
    text = run([objdump, "-p", str(path)])
    if "pei-x86-64" not in text or not re.search(r"Time/Date stamp\s+0\b", text):
        raise ValueError("requires timestamp-free PE x64")
    imports = set(re.findall(r"DLL Name: (\S+)", text))
    if imports != set(policy["imports"]):
        raise ValueError(f"unknown/missing DLL imports: {sorted(imports)}")
    exports = set(re.findall(r"Java_dev_turboism_update_WindowsInstallationFiles_(\w+)", text))
    if exports != NATIVES:
        raise ValueError("missing/unknown JNI export")
    if "__chkstk" in text or "crt-private" in text.lower():
        raise ValueError("unexpected runtime support")


def build(cc: str, jdk: Path, output: Path, objdump: str) -> dict:
    policy = check_sources()
    if not re.match(r"x86_64-.*mingw32", run([cc, "-dumpmachine"]).strip()):
        raise ValueError("requires MinGW Windows x64 compiler")
    version = run([str(jdk / "bin/javac"), "-version"]).strip()
    if not re.match(r"javac 17(?:[.\s]|$)", version):
        raise ValueError("JDK17 required")
    # Always use the recorded Windows jni_md.h, never host Linux jni_md.h.
    output = output.resolve()
    if not output.is_relative_to((ROOT / "build").resolve()):
        raise ValueError("native output must be inside repository build/")
    output.mkdir(parents=True, exist_ok=True)
    dll = output / "turboism-update-files.dll"
    flags = policy["compilerFlags"]
    command = [cc, *flags, "-I" + str(jdk / "include"), "-I" + str(SOURCE / "include/win32"),
               str(SOURCE / "windows_files.c"), str(SOURCE / "jni_bridge.c"),
               "-ladvapi32", "-lntdll", "-lkernel32"]
    with tempfile.TemporaryDirectory(dir=output, prefix="repro-") as temp:
        second = Path(temp) / dll.name
        run([*command, "-o", str(dll)])
        run([*command, "-o", str(second)])
        if dll.read_bytes() != second.read_bytes():
            raise ValueError("native build not reproducible within same toolchain")
    verify_pe(dll, objdump, policy)
    probe = output / "windows-fixture-probe.exe"
    probe_flags = [f for f in flags if f not in {"-shared", "-Wl,--entry,DllMain", "-Wl,--image-base,0x180000000"}]
    run([cc, *probe_flags, "-Wl,--entry,wf_fixture_entry", "-o", str(probe),
         str(SOURCE / "windows_files.c"), str(SOURCE / "windows_fixture_probe.c"),
         "-ladvapi32", "-lntdll", "-lkernel32"])
    probe_imports = set(re.findall(r"DLL Name: (\S+)", run([objdump, "-p", str(probe)])))
    if probe_imports != set(policy["imports"]):
        raise ValueError("fixture probe unknown DLL import")
    sources = source_records([p for p in SOURCE.rglob("*") if p.is_file()], ROOT)
    includes = run([cc, "-E", "-H", "-I" + str(jdk / "include"), "-I" + str(SOURCE / "include/win32"), str(SOURCE / "jni_bridge.c")])
    headers = {}
    for line in includes.splitlines():
        match = re.match(r"^[.]+ (.+)$", line)
        if match and Path(match[1]).is_file():
            headers[Path(match[1]).resolve().as_posix()] = digest(Path(match[1]))
    libraries = {}
    for name in ("libkernel32.a", "libadvapi32.a", "libntdll.a"):
        path = Path(run([cc, "-print-file-name=" + name]).strip())
        libraries[name] = digest(path)
    report = {"schemaVersion": 1, "abi": 1, "platform": "windows-x64", "nativeExecution": "NOT_RUN",
              "compiler": run([cc, "--version"]).splitlines()[0], "jdk": version,
              "jniHeaderSha256": digest(jdk / "include/jni.h"), "toolchainHeaders": headers,
              "importLibraries": libraries, "sources": sources,
              "sourceRevision": run(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip(),
              "dirty": bool(run(["git", "-C", str(ROOT), "status", "--porcelain"])),
              "dllSha256": digest(dll), "probeSha256": digest(probe), "imports": policy["imports"], "reproducibleSameToolchain": True}
    (output / "native-build.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--seal-bundle", action="store_true")
    parser.add_argument("--cc", default="x86_64-w64-mingw32-gcc")
    parser.add_argument("--objdump", default="x86_64-w64-mingw32-objdump")
    parser.add_argument("--jdk", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk")))
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if args.seal_bundle:
        output = args.output.resolve()
        if not output.is_relative_to((ROOT / "build").resolve()):
            raise ValueError("bundle output must be inside repository build/")
        print(json.dumps(seal_bundle(output), indent=2))
    elif args.check_only:
        check_sources()
        print("W2 source/pin/ABI contract checked; native execution NOT_RUN")
    else:
        print(json.dumps(build(shutil.which(args.cc) or args.cc, args.jdk, args.output,
                               shutil.which(args.objdump) or args.objdump), indent=2))


if __name__ == "__main__":
    main()
