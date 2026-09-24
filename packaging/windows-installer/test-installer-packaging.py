#!/usr/bin/env python3
"""Exercise the real release generator; optionally repack existing Java binaries.

The optional repack is an NSIS/script integration artifact, NOT a fresh Java
build or a release. Its provenance and SHA-256 are printed explicitly.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "packaging/windows-installer"


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def production_blocks() -> tuple[str, str]:
    text = (PACKAGE / "assemble-release.sh").read_text(encoding="utf-8")
    blocks = [part.split("\nPYEOF", 1)[0] for part in text.split("<<'PYEOF'\n")[1:]]
    assert len(blocks) == 2, "release generator and ZIP builder must remain explicit"
    return blocks[0], blocks[1]


def generate(stage: Path, work: Path) -> tuple[Path, Path]:
    generated = work / "generated"
    generated.mkdir(parents=True, exist_ok=True)
    sections = work / "plugin-sections.nsh"
    subprocess.run(
        [sys.executable, "-", str(stage), str(sections), str(ROOT / "packaging/release-plugins.txt"), str(generated)],
        input=production_blocks()[0], text=True, check=True, capture_output=True,
    )
    return sections, generated


def synthetic_test() -> None:
    with tempfile.TemporaryDirectory(prefix="turboism-packaging-test-") as temp:
        work = Path(temp)
        stage = work / "stage"
        (stage / "plugins").mkdir(parents=True)
        (stage / "graal/lib").mkdir(parents=True)
        names = (
            "turboism-agent.jar install-jar-payload.ps1 launch-cubism-turboism.bat "
            "launch-cubism-turboism.ps1 configure_turboism.ps1 cubism-launch-common.ps1 "
            "install-managed-graal.ps1 install-script-engine.ps1 turboism.ico turboism.png "
            "README.txt README.zh.txt README.ja.txt README.ko.txt LICENSE.txt "
            "EULA.en.txt EULA.zh-Hans.txt EULA.ja.txt EULA.ko.txt config.template.json"
        ).split()
        for name in names:
            (stage / name).write_text("synthetic " + name, encoding="utf-8")
        modules = [line.split(":")[-1] for line in (ROOT / "packaging/release-plugins.txt").read_text().splitlines()]
        for module in modules:
            with zipfile.ZipFile(stage / "plugins" / (module + ".jar"), "w") as archive:
                archive.writestr("META-INF/turboism/plugin.json", json.dumps({"id": "dev.turboism.plugin." + module, "name": module, "version": "0.44.0"}))
                for locale in ("en", "zh_Hans", "ja", "ko"):
                    archive.writestr("META-INF/turboism/i18n/messages_" + locale + ".properties", "plugin.name=" + module + "\nplugin.description=synthetic fixture\n")
        manifest = json.loads((PACKAGE / "script-engine.json").read_text())
        for entry in manifest["artifacts"]:
            path = stage / "graal/lib" / entry["name"]
            path.write_bytes(("synthetic " + entry["name"]).encode())
            entry["bytes"] = path.stat().st_size
            entry["sha256"] = digest(path)
        (stage / "script-engine.json").write_text(json.dumps(manifest))
        for name in ("sdk-0.44.0.jar", "graal-host-0.44.0.jar", "polyglot-25.2.4.jar"):
            (stage / "graal/lib" / name).write_bytes(b"synthetic base library")
        _, generated = generate(stage, work)
        core = (generated / "payload-core.sha256").read_text()
        extractor = (generated / "payload-extract.nsh").read_text(encoding="utf-8-sig")
        for entry in manifest["artifacts"]:
            assert entry["name"] not in core and entry["name"] not in extractor, "heavy engine must not be embedded in the ordinary EXE"
        assert "graal/lib/sdk-0.44.0.jar" in core and "graal/lib/graal-host-0.44.0.jar" in core
        assert "install-script-engine.ps1" in core and "script-engine.json" in core
        # The unmodified production ZIP path remains an offline engine closure.
        full = work / "full.zip"
        subprocess.run([sys.executable, "-", str(stage), str(full), "0"], input=production_blocks()[1], text=True, check=True)
        with zipfile.ZipFile(full) as archive:
            for entry in manifest["artifacts"]:
                assert archive.read("graal/lib/" + entry["name"]) == (stage / "graal/lib" / entry["name"]).read_bytes()
        bad = stage / "graal/lib" / manifest["artifacts"][1]["name"]
        bad.write_bytes(b"tampered library")
        failed = False
        try:
            generate(stage, work)
        except subprocess.CalledProcessError:
            failed = True
        assert failed, "packaging must reject a staging file that differs from its download pin"
        print("INSTALLER_PACKAGING_PASS: thin core, SDK/base retained, offline closure preserved, tampered pin rejected")


def repack(source: Path, output: Path, version: str) -> None:
    source = source.resolve()
    output = output.resolve()
    if output.exists():
        raise ValueError("repack output must be new; never overwrite a prior verification artifact")
    output.mkdir(parents=True)
    stage = output / "staging"
    shutil.copytree(source, stage)
    # Java binaries are copied verbatim; only this checkout's installer inputs change.
    for name in (
        "configure_turboism.ps1", "cubism-launch-common.ps1", "install-jar-payload.ps1",
        "install-managed-graal.ps1", "launch-cubism-turboism.ps1", "launch-cubism-turboism.bat",
        "install-script-engine.ps1", "script-engine.json",
    ):
        shutil.copy2(PACKAGE / name, stage / name)
    for locale, target in (("en", "README.txt"), ("zh", "README.zh.txt"), ("ja", "README.ja.txt"), ("ko", "README.ko.txt")):
        template = (PACKAGE / ("README." + locale + ".txt.template")).read_text(encoding="utf-8")
        (stage / target).write_text(template.replace("__VERSION__", version), encoding="utf-8")
    _, generated = generate(stage, output)
    shutil.copy2(PACKAGE / "installer.nsi", output / "installer.nsi")
    eula = generated / "eula"
    eula.mkdir()
    for language in ("en", "zh-Hans", "ja"):
        name = "EULA." + language + ".txt"
        (eula / name).write_bytes(b"\xef\xbb\xbf" + (ROOT / "packaging/eula" / name).read_bytes())
    dist = output / "dist"
    dist.mkdir()
    subprocess.run([
        "makensis", "-WX", "-DVER=" + version, "-DSTAGING_DIR=" + str(stage),
        "-DGENERATED_DIR=" + str(generated), "-DOUT_DIR=" + str(dist),
        "-DLICENSE_FILE=" + str(ROOT / "LICENSE"), "-DEULA_DIR=" + str(eula),
        "-DICON_FILE=" + str(PACKAGE / "assets/turboism.ico"), str(output / "installer.nsi"),
    ], check=True)
    for mode in ("full", "lite"):
        archive = dist / ("turboism-" + version + "-" + mode + ".zip")
        subprocess.run([sys.executable, "-", str(stage), str(archive), "1" if mode == "lite" else "0"], input=production_blocks()[1], text=True, check=True)
    records = []
    for path in sorted(dist.iterdir()):
        checksum = digest(path)
        path.with_name(path.name + ".sha256").write_text(checksum + "  " + path.name + "\n")
        records.append({"name": path.name, "bytes": path.stat().st_size, "sha256": checksum})
    provenance = {"kind": "installer-repack-not-fresh-java-build", "binarySource": str(source), "agentSha256": digest(stage / "turboism-agent.jar"), "artifacts": records}
    (output / "repack-evidence.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(json.dumps(provenance, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repack-existing", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--version", default="0.44.0")
    args = parser.parse_args()
    synthetic_test()
    if args.repack_existing:
        if args.output is None:
            parser.error("--output is required for a repack")
        repack(args.repack_existing, args.output, args.version)
