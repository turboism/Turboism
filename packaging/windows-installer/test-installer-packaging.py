#!/usr/bin/env python3
"""Exercise the real release generator; optionally repack existing Java binaries.

The optional repack is an NSIS/script integration artifact, NOT a fresh Java
build or a release. Its provenance and SHA-256 are printed explicitly.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
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


def payload_upgrade_test() -> None:
    """Run the actual copy helper against installer/marketplace filename collisions."""
    powershell = os.environ.get("POWERSHELL") or shutil.which("pwsh") or shutil.which("powershell")
    if not powershell:
        print("PAYLOAD_UPGRADE_SKIP: set POWERSHELL or install pwsh to exercise the copy helper")
        return
    with tempfile.TemporaryDirectory(prefix="turboism-payload-upgrade-") as temp:
        work = Path(temp)
        source, destination = work / "source", work / "installed"
        (source / "plugins").mkdir(parents=True)
        (destination / "plugins").mkdir(parents=True)
        plugin_id = "dev.turboism.plugin.model-import-3d"

        def jar(path: Path, identity: str, content: str) -> None:
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("META-INF/turboism/plugin.json", json.dumps(
                    {"id": identity, "version": "0.67.5"}))
                archive.writestr("behavior.txt", content)

        current = source / "plugins/model-import-3d.jar"
        jar(current, plugin_id, "presets")
        stale = destination / "plugins" / (plugin_id + ".jar")
        jar(stale, plugin_id, "old viewer")
        stale_bytes = stale.read_bytes()
        unrelated = destination / "plugins/unrelated.jar"
        jar(unrelated, "dev.turboism.plugin.other", "keep")
        unrelated_bytes = unrelated.read_bytes()
        broken = destination / "plugins/broken.jar"
        broken.write_bytes(b"not a zip")
        manifest = work / "plugins.sha256"
        manifest.write_text(digest(current) + "  plugins/model-import-3d.jar\n")

        def invoke(*extra: str, success: bool = True) -> subprocess.CompletedProcess:
            result = subprocess.run([
                powershell, "-NoProfile", "-NonInteractive", "-File",
                str(PACKAGE / "install-jar-payload.ps1"),
                "-SourceRoot", str(source), "-DestinationRoot", str(destination),
                "-ManifestPath", str(manifest), *extra,
            ], text=True, capture_output=True)
            assert (result.returncode == 0) == success, result.stdout + result.stderr
            return result

        invoke("-PlanOnly", "-PlanRoot", str(work / "plan"))
        assert stale.read_bytes() == stale_bytes, "planning must not retire a plugin"
        invoke()
        assert not stale.exists(), "old ID-named JAR must no longer compete with the installer payload"
        assert (destination / "plugins/model-import-3d.jar").read_bytes() == current.read_bytes()
        assert any(path.read_bytes() == stale_bytes for path in
                   (destination / "backups/plugins").rglob("*.jar")), "old package must remain recoverable"
        assert unrelated.read_bytes() == unrelated_bytes
        assert broken.read_bytes() == b"not a zip"

        # Repair must also run when the canonical JAR already has the expected hash.
        renamed = destination / "plugins/arbitrary-name.jar"
        renamed.write_bytes(stale_bytes)
        invoke()
        assert not renamed.exists(), "duplicate detection must use embedded ID, including on checksum skip"
        invoke()  # Repeated installs are harmless.

        # Unselected plugins marked with .skip in PlanRoot must not be planned or installed.
        plan_dir = work / "plan"
        plan_dir.mkdir(exist_ok=True)
        (plan_dir / "0000.skip").write_text("", encoding="utf-8")
        (destination / "plugins/model-import-3d.jar").unlink()
        invoke("-PlanOnly", "-PlanRoot", str(plan_dir))
        assert not (plan_dir / "0000.need").exists(), "skipped plugin must not emit a .need marker"
        invoke("-PlanRoot", str(plan_dir))
        assert not (destination / "plugins/model-import-3d.jar").exists(), "skipped plugin must not be copied"
        (plan_dir / "0000.skip").unlink()
        invoke("-PlanOnly", "-PlanRoot", str(plan_dir))
        assert (plan_dir / "0000.need").exists(), "selected plugin must emit a .need marker"
        invoke("-PlanRoot", str(plan_dir))
        assert (destination / "plugins/model-import-3d.jar").read_bytes() == current.read_bytes()

        # A damaged new payload must not retire the working old package.
        stale.write_bytes(stale_bytes)
        current.write_bytes(b"damaged download")
        (destination / "plugins/model-import-3d.jar").unlink()
        invoke(success=False)
        assert stale.read_bytes() == stale_bytes
        print("PAYLOAD_UPGRADE_PASS: duplicate ID backup, checksum skip, unrelated files and failed-copy recovery")


def production_blocks() -> tuple[str, str]:
    text = (PACKAGE / "assemble-release.sh").read_text(encoding="utf-8")
    blocks = [part.split("\nPYEOF", 1)[0] for part in text.split("<<'PYEOF'\n")[1:]]
    assert len(blocks) == 2, "release generator and ZIP builder must remain explicit"
    return blocks[0], blocks[1]


def generate(stage: Path, work: Path, verification: Path | None = None) -> tuple[Path, Path]:
    generated = work / "generated"
    generated.mkdir(parents=True, exist_ok=True)
    sections = work / "plugin-sections.nsh"
    subprocess.run(
        [sys.executable, "-", str(stage), str(sections), str(ROOT / "packaging/release-plugins.txt"), str(generated)],
        input=production_blocks()[0], text=True, encoding="utf-8", check=True, capture_output=True,
        env={**os.environ, "TURBOISM_VERIFICATION_METADATA": str(verification)} if verification else None,
    )
    return sections, generated


def synthetic_test() -> None:
    with tempfile.TemporaryDirectory(prefix="turboism-packaging-test-") as temp:
        work = Path(temp)
        stage = work / "stage"
        (stage / "plugins").mkdir(parents=True)
        (stage / "graal/lib").mkdir(parents=True)
        names = (
            "turboism-agent.jar bootstrap-receipt.ps1 install-jar-payload.ps1 launch-cubism-turboism.bat "
            "launch-cubism-turboism.ps1 configure_turboism.ps1 cubism-launch-common.ps1 "
            "install-managed-graal.ps1 install-script-engine.ps1 turboism.ico turboism.png "
            "retired-plugins.txt managed-graal.json "
            "README.txt README.zh.txt README.ja.txt README.ko.txt LICENSE.txt "
            "EULA.en.txt EULA.zh-Hans.txt EULA.ja.txt EULA.ko.txt config.template.json"
        ).split()
        for name in names:
            (stage / name).write_text("synthetic " + name, encoding="utf-8")
        modules = [line.split(":")[-1] for line in (ROOT / "packaging/release-plugins.txt").read_text(encoding="utf-8").splitlines()]
        for module in modules:
            with zipfile.ZipFile(stage / "plugins" / (module + ".jar"), "w") as archive:
                archive.writestr("META-INF/turboism/plugin.json", json.dumps({"id": "dev.turboism.plugin." + module, "name": module, "version": "0.67.0"}))
                for locale in ("en", "zh_Hans", "ja", "ko"):
                    archive.writestr("META-INF/turboism/i18n/messages_" + locale + ".properties", "plugin.name=" + module + "\nplugin.description=synthetic fixture\n")
        manifest = json.loads((PACKAGE / "script-engine.json").read_text(encoding="utf-8"))
        for entry in manifest["artifacts"]:
            path = stage / "graal/lib" / entry["name"]
            path.write_bytes(("synthetic " + entry["name"]).encode())
            entry["bytes"] = path.stat().st_size
            entry["sha256"] = digest(path)
        (stage / "script-engine.json").write_text(json.dumps(manifest))
        # The production generator also pins the manual manifest against
        # Gradle's verification metadata; point it at a synthetic mirror of the
        # recomputed digests so the consistency check exercises its pass path.
        verification = work / "verification-metadata.xml"
        verification.write_text(
            "<verification-metadata><components>"
            + "".join(
                '<component><artifact name="%s"><sha256 value="%s"/></artifact></component>'
                % (entry["name"], entry["sha256"])
                for entry in manifest["artifacts"])
            + "</components></verification-metadata>",
            encoding="utf-8")
        for name in ("sdk-0.67.0.jar", "graal-host-0.67.0.jar", "polyglot-25.2.4.jar"):
            (stage / "graal/lib" / name).write_bytes(b"synthetic base library")
        _, generated = generate(stage, work, verification)
        core = (generated / "payload-core.sha256").read_text(encoding="utf-8")
        extractor = (generated / "payload-extract.nsh").read_text(encoding="utf-8-sig")
        for entry in manifest["artifacts"]:
            assert entry["name"] in core and entry["name"] in extractor, "standard EXE must embed the verified offline engine"
        assert "graal/lib/sdk-0.67.0.jar" in core and "graal/lib/graal-host-0.67.0.jar" in core
        assert "install-script-engine.ps1" in core and "script-engine.json" in core
        readme_dir = generated / "plugin-readmes"
        for index in range(len(modules)):
            for locale in ("eng", "chn", "jpn", "kor"):
                rtf_file = readme_dir / f"plugin-{index:04d}-{locale}.rtf"
                assert rtf_file.is_file(), f"missing generated plugin README RTF: {rtf_file}"
                rtf_text = rtf_file.read_text(encoding="ascii")
                assert rtf_text.startswith("{\\rtf1") and "dev.turboism.plugin." in rtf_text
        standard = work / "standard.zip"
        subprocess.run([sys.executable, "-", str(stage), str(standard), "0.67.0"], input=production_blocks()[1], text=True, encoding="utf-8", check=True)
        with zipfile.ZipFile(standard) as archive:
            for entry in manifest["artifacts"]:
                assert archive.read("graal/lib/" + entry["name"]) == (stage / "graal/lib" / entry["name"]).read_bytes()
            assert any(name.startswith("plugins/") for name in archive.namelist())
            assert "graal/lib/sdk-0.67.0.jar" in archive.namelist()
            assert json.loads(archive.read("installed-build.json"))["variant"] == "full"
        # Exercise the verifier against the real production ZIP path.
        import importlib.util
        spec = importlib.util.spec_from_file_location("verify_release", ROOT / "scripts/release/verify-release.py")
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        shutil.copy2(standard, work / "turboism-0.67.0.zip")
        verifier.verify_bundled_engine(work, "0.67.0", stage)
        bad = stage / "graal/lib" / manifest["artifacts"][1]["name"]
        bad.write_bytes(b"tampered library")
        failed = False
        try:
            generate(stage, work, verification)
        except subprocess.CalledProcessError:
            failed = True
        assert failed, "packaging must reject a staging file that differs from its download pin"
        print("INSTALLER_PACKAGING_PASS: standard plugins, SDK/base and offline engine verified, tampered pin rejected")


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
    for name in ("retired-plugins.txt", "managed-graal.json"):
        shutil.copy2(PACKAGE.parent / name, stage / name)
    for locale, target in (("en", "README.txt"), ("zh", "README.zh.txt"), ("ja", "README.ja.txt"), ("ko", "README.ko.txt")):
        template = (PACKAGE / ("README." + locale + ".txt.template")).read_text(encoding="utf-8")
        (stage / target).write_text(template.replace("__VERSION__", version), encoding="utf-8")
    _, generated = generate(stage, output)
    shutil.copy2(PACKAGE / "installer.nsi", output / "installer.nsi")
    eula = generated / "eula"
    eula.mkdir()
    for language in ("en", "zh-Hans", "ja", "ko"):
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
    archive = dist / ("turboism-" + version + ".zip")
    subprocess.run([sys.executable, "-", str(stage), str(archive), version], input=production_blocks()[1], text=True, check=True)
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
    parser.add_argument("--version", default="0.67.0")
    args = parser.parse_args()
    payload_upgrade_test()
    synthetic_test()
    if args.repack_existing:
        if args.output is None:
            parser.error("--output is required for a repack")
        repack(args.repack_existing, args.output, args.version)
