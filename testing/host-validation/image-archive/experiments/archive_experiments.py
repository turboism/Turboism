"""Private evidence archive: explicit allowlist, no model/AppData/license files or host binaries."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): h.update(block)
    return h.hexdigest()


def archive(root, build, sources, output):
    receipt = json.loads((sources / "observations/screening.json").read_text(encoding="utf-8"))
    files, model_checks = {}, {}
    for path in sorted(root.glob("*/envelope.json")):
        run = path.parent; envelope = json.loads(path.read_text(encoding="utf-8"))
        expected = receipt["attempts"][run.name]["envelopeSha256"]
        if digest(path) != expected: raise AssertionError("envelope changed: " + run.name)
        copied = run / "fixture.cmo3"
        model_checks[run.name] = digest(copied)
        if model_checks[run.name] != envelope["before"]["fixture"]: raise AssertionError("model copy changed")
        for name in ("envelope.json", "cleanup.json", "failure.json", "console.log", "process.jsonl",
                     "official.bat", "launch.json", "containment/containment.json"):
            p = run / name
            if p.is_file(): files["runs/" + run.name + "/" + name] = p
        for directory in (run / "home/logs", run / "home/state/texture-upload"):
            if directory.exists():
                for p in directory.rglob("*"):
                    if p.is_file(): files["runs/" + p.relative_to(root).as_posix()] = p
        if envelope["status"] == "PASS":
            renderer = [line for line in (run / "console.log").read_text(errors="replace").splitlines()
                        if "Renderer   :" in line]
            if not any("NVIDIA GeForce RTX 4060 Laptop GPU" in line for line in renderer):
                raise AssertionError("unexpected actual renderer: " + run.name)
    audit = build / "post-measurement-copy-checks.json"
    audit.write_text(json.dumps(model_checks, indent=2), encoding="utf-8")
    for p in build.glob("*.log"): files["coordinator/" + p.name] = p
    for p in build.glob("*.json"): files["audit/" + p.name] = p
    for directory in (build / "source-snapshots",):
        for p in directory.rglob("*"):
            if p.is_file(): files["frozen-source/" + p.relative_to(directory).as_posix()] = p
    for directory in sorted(build.glob("bundle-*")):
        for name in ("hotspot-experiments.jar", "build-receipt.json"):
            p = directory / name
            if p.is_file(): files["probe-builds/" + directory.name + "/" + name] = p
    files["artifacts/control-agent.jar"] = root / "control-s3/agent.jar"
    files["artifacts/large-agent.jar"] = root / "large-shadow/agent.jar"
    files["artifacts/texture-validator.jar"] = root / "control-s3/auxiliary.jar"
    for p in sources.rglob("*"):
        if p.is_file() and p.suffix in (".java", ".py", ".md", ".csv", ".json") and "__pycache__" not in p.parts:
            if p.name != "archive-receipt.json": files["tools-and-report/" + p.relative_to(sources).as_posix()] = p
    for name in files:
        if name.endswith((".cmo3", ".can3")) or any(part in ("roaming", "local", "profile", "hotspot-static")
                                                    for part in Path(name).parts):
            raise AssertionError("private payload in allowlist")
    hashes = {name: digest(path) for name, path in sorted(files.items())}
    manifest = json.dumps(hashes, indent=2).encode("utf-8")
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as z:
        for name, path in sorted(files.items()): z.write(path, name)
        z.writestr("manifest.json", manifest)
    with zipfile.ZipFile(output) as z:
        for name, expected in hashes.items():
            if hashlib.sha256(z.read(name)).hexdigest() != expected: raise AssertionError("archive changed: " + name)
    result = {"archiveFile": output.name, "sha256": digest(output),
              "manifestSha256": hashlib.sha256(manifest).hexdigest(), "entryCount": len(files) + 1,
              "bytes": output.stat().st_size, "attempts": len(model_checks),
              "modelFilesIncluded": False, "appDataAndLicenseFilesIncluded": False,
              "hostJarOrJavapIncluded": False, "rawLogsIncluded": True,
              "privacy": "Local private archive only; raw logs can contain local paths/diagnostics."}
    (sources / "observations/archive-receipt.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("root", "build", "sources", "output"): parser.add_argument("--" + key, required=True, type=Path)
    args = parser.parse_args(); archive(args.root, args.build, args.sources, args.output)
