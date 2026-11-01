#!/usr/bin/env python3
"""Byte-compare the tracked NSIS plugin sections against a fresh regeneration.

`packaging/windows-installer/plugin-sections.nsh` is a generated artifact kept in
Git because `installer.nsi` includes it and the packaging contract tests read it.
Until this gate existed nothing compared it with a regeneration, so a plugin
rename, a manifest change, or a plugin version bump could ship a stale
installer payload list.

The gate reproduces the assembly path rather than re-implementing it:

  1. Extract the production generator from `assemble-release.sh` (the same
     `<<'PYEOF'` block the assembler runs) and fail if the script no longer has
     exactly the two expected blocks.
  2. Build a synthetic staging tree whose `plugins/<module>.jar` entries carry
     the real repository metadata: the verbatim `META-INF/turboism/plugin.json`
     (plugin versions are independent of the framework version), the four
     installer locales, and the four README files.
  3. Run the extracted generator and byte-compare its `plugin-sections.nsh`
     output with the tracked file.

The synthetic payload documents, Graal libraries, and script-engine pins never
influence the plugin-sections output; they exist only because the same
generator block also writes the installer payload manifests.

Exit status: 0 when the tracked artifact is byte-identical, 1 otherwise.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

DEFAULT_PACKAGE = Path(__file__).resolve().parent
INSTALLER_LOCALES = ("en", "zh_Hans", "ja", "ko")
README_FILES = ("README.md", "README_zh.md", "README_ja.md", "README_ko.md")
# Staged payload entries the generator only checks for existence while writing the
# installer manifests. None of them feeds the plugin-sections output.
SYNTHETIC_STAGE_FILES = (
    "turboism-agent.jar",
    "bootstrap-receipt.ps1",
    "install-jar-payload.ps1",
    "launch-cubism-turboism.bat",
    "launch-cubism-turboism.ps1",
    "configure_turboism.ps1",
    "cubism-launch-common.ps1",
    "install-managed-graal.ps1",
    "install-script-engine.ps1",
    "turboism.ico",
    "turboism.png",
    "retired-plugins.txt",
    "managed-graal.json",
    "README.txt",
    "README.zh.txt",
    "README.ja.txt",
    "README.ko.txt",
    "LICENSE.txt",
    "EULA.en.txt",
    "EULA.zh-Hans.txt",
    "EULA.ja.txt",
    "EULA.ko.txt",
    "config.template.json",
)
SYNTHETIC_BASE_LIBRARIES = ("sdk-0.0.0.jar", "graal-host-0.0.0.jar", "polyglot-0.0.0.jar")


def fail(message: str) -> "NoReturn":  # noqa: F821 - string annotation keeps py3.10 compatibility
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def production_generator(assemble_script: Path) -> str:
    """Return the plugin-sections generator block exactly as the assembler runs it."""
    text = assemble_script.read_text(encoding="utf-8")
    blocks = [part.split("\nPYEOF", 1)[0] for part in text.split("<<'PYEOF'\n")[1:]]
    if len(blocks) != 2:
        fail(
            f"{assemble_script} must keep exactly two PYEOF blocks "
            f"(plugin-sections generator, ZIP builder); found {len(blocks)}"
        )
    return blocks[0]


def manifest_modules(repo: Path) -> list[str]:
    manifest = repo / "packaging" / "release-plugins.txt"
    if not manifest.is_file():
        fail(f"release plugin manifest missing: {manifest}")
    raw = manifest.read_text(encoding="utf-8").splitlines()
    entries = [line.strip() for line in raw if line.strip()]
    if any(line.startswith("#") for line in entries):
        fail("release plugin manifest forbids comment lines")
    if any(not entry.startswith(":plugins:") for entry in entries):
        fail("release plugin manifest contains non-plugin entries")
    return [entry[len(":plugins:"):] for entry in entries]


def stage_plugin(repo: Path, module: str, stage: Path) -> None:
    plugin_dir = repo / "plugins" / module
    resources = plugin_dir / "src" / "main" / "resources" / "META-INF" / "turboism"
    plugin_json = resources / "plugin.json"
    if not plugin_json.is_file():
        fail(f"plugin metadata missing: {plugin_json}")
    payload: dict[str, bytes] = {
        # Plugin versions are independent of the framework; plugin.json ships verbatim.
        "META-INF/turboism/plugin.json": plugin_json.read_bytes(),
    }
    for locale in INSTALLER_LOCALES:
        messages = resources / "i18n" / f"messages_{locale}.properties"
        if not messages.is_file():
            fail(f"installer localization missing: {messages}")
        payload[f"META-INF/turboism/i18n/messages_{locale}.properties"] = messages.read_bytes()
    for readme in README_FILES:
        source = plugin_dir / readme
        if not source.is_file():
            fail(f"plugin README missing: {source}")
        payload[f"META-INF/turboism/readme/{readme}"] = source.read_bytes()
    with zipfile.ZipFile(stage / "plugins" / f"{module}.jar", "w") as archive:
        for name, data in payload.items():
            archive.writestr(name, data)


def synthesize_stage(repo: Path, stage: Path, work: Path) -> str:
    """Create a complete synthetic staging tree and return the verification metadata path."""
    (stage / "plugins").mkdir(parents=True)
    (stage / "graal" / "lib").mkdir(parents=True)

    modules = manifest_modules(repo)
    if not modules:
        fail("release plugin manifest is empty")
    for module in modules:
        stage_plugin(repo, module, stage)

    for name in SYNTHETIC_STAGE_FILES:
        (stage / name).write_text(f"synthetic {name}", encoding="utf-8")

    script_engine = json.loads((DEFAULT_PACKAGE / "script-engine.json").read_text(encoding="utf-8"))
    for entry in script_engine["artifacts"]:
        path = stage / "graal" / "lib" / entry["name"]
        path.write_bytes(f"synthetic {entry['name']}".encode("utf-8"))
        entry["bytes"] = path.stat().st_size
        entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    (stage / "script-engine.json").write_text(json.dumps(script_engine), encoding="utf-8")
    for name in SYNTHETIC_BASE_LIBRARIES:
        (stage / "graal" / "lib" / name).write_bytes(b"synthetic base library")

    # The production generator pins script-engine.json against Gradle's dependency
    # verification metadata; mirror the recomputed digests so that check stays active.
    verification = work / "verification-metadata.xml"
    verification.write_text(
        "<verification-metadata><components>"
        + "".join(
            '<component><artifact name="%s"><sha256 value="%s"/></artifact></component>'
            % (entry["name"], entry["sha256"])
            for entry in script_engine["artifacts"]
        )
        + "</components></verification-metadata>",
        encoding="utf-8",
    )
    return str(verification)


def regenerate(repo: Path, work: Path, assemble_script: Path | None = None) -> Path:
    assemble = assemble_script or (DEFAULT_PACKAGE / "assemble-release.sh")
    if not assemble.is_file():
        fail(f"assembler missing: {assemble}")
    generator = production_generator(assemble)
    stage = work / "stage"
    verification = synthesize_stage(repo, stage, work)
    generated = work / "generated"
    generated.mkdir()
    output = work / "plugin-sections.nsh"
    result = subprocess.run(
        [
            sys.executable,
            "-",
            str(stage),
            str(output),
            str(repo / "packaging" / "release-plugins.txt"),
            str(generated),
        ],
        input=generator,
        text=True,
        encoding="utf-8",
        capture_output=True,
        env={**os.environ, "TURBOISM_VERIFICATION_METADATA": verification},
    )
    if result.returncode != 0:
        fail(
            "plugin-sections regeneration failed:\n"
            + (result.stderr.strip() or result.stdout.strip() or "(no output)")
        )
    if not output.is_file():
        fail(f"generator produced no output at {output}")
    return output


def report_mismatch(tracked: Path, fresh: Path) -> None:
    tracked_lines = tracked.read_bytes().decode("utf-8-sig").splitlines()
    fresh_lines = fresh.read_bytes().decode("utf-8-sig").splitlines()
    diff = list(difflib.unified_diff(tracked_lines, fresh_lines, "tracked", "regenerated", n=1, lineterm=""))
    print(f"error: {tracked} is stale: it differs from a fresh regeneration.", file=sys.stderr)
    print(
        f"error: regenerate it with packaging/windows-installer/assemble-release.sh <version> "
        f"and commit the result.",
        file=sys.stderr,
    )
    for line in diff[:60]:
        print(line, file=sys.stderr)
    if len(diff) > 60:
        print(f"... {len(diff) - 60} more diff line(s)", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", type=Path, default=DEFAULT_PACKAGE.parent.parent)
    parser.add_argument("--nsh", type=Path, default=DEFAULT_PACKAGE / "plugin-sections.nsh")
    parser.add_argument("--assemble", type=Path, default=DEFAULT_PACKAGE / "assemble-release.sh")
    args = parser.parse_args(argv)

    repo = args.repo.resolve()
    tracked = args.nsh if args.nsh.is_absolute() else (DEFAULT_PACKAGE / args.nsh)
    if not tracked.is_file():
        fail(f"tracked plugin sections missing: {tracked}")

    with tempfile.TemporaryDirectory(prefix="turboism-plugin-sections-") as temp:
        fresh = regenerate(repo, Path(temp), args.assemble)
        if tracked.read_bytes() == fresh.read_bytes():
            print(f"OK: {tracked.name} matches a fresh regeneration ({tracked.stat().st_size} bytes).")
            return 0
        report_mismatch(tracked, fresh)
        return 1


if __name__ == "__main__":
    sys.exit(main())
