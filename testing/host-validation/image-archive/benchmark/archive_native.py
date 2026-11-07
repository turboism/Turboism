"""Archive local native evidence with an explicit allowlist; no models or license state."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from run_native import digest, save


def archive(root: Path, receipt_dir: Path) -> dict:
    root = root.resolve()
    files = {}
    for run in root.iterdir():
        if not run.is_dir() or not (run / "envelope.json").exists():
            continue
        for name in ("envelope.json", "cleanup.json", "failure.json", "console.log", "process.jsonl",
                     "official.bat", "launch.json", "containment/containment.json"):
            path = run / name
            if path.is_file():
                files[path.relative_to(root).as_posix()] = path
        for directory in (run / "home/logs", run / "home/state/texture-upload"):
            if directory.is_dir():
                for path in directory.rglob("*"):
                    if path.is_file():
                        files[path.relative_to(root).as_posix()] = path
    for name in ("machine.json", "variant-proof.json", "summary.json"):
        path = root / name
        if path.exists():
            files[name] = path
    for path in root.glob("*-coordinator.log"):
        files[path.name] = path
    for directory in (root / "measurement-source", root / "offline-checks", root / "legacy-src"):
        if directory.exists():
            for path in directory.rglob("*"):
                if path.is_file():
                    files[path.relative_to(root).as_posix()] = path
    receipt = json.loads((receipt_dir / "native-receipt.json").read_text(encoding="utf-8"))
    measured_runner = root / "measurement-source/testing/host-validation/image-archive/benchmark/run_native.py"
    if digest(measured_runner) != receipt["runnerSha256"]:
        raise ValueError("measurement-time runner snapshot differs from run receipts")
    files["report/NATIVE-WINDOWS.md"] = Path(__file__).with_name("NATIVE-WINDOWS.md")
    for name in ("native-loads.csv", "native-receipt.json"):
        files["summary/" + name] = receipt_dir / name
    artifacts = {"direct-agent.jar": root / "shadow-s5/agent.jar",
                 "legacy-agent.jar": root / "legacy-agent.jar",
                 "probe.jar": root / "shadow-s5/auxiliary.jar"}
    expected = {"direct-agent.jar": receipt["agentHashes"]["direct"],
                "legacy-agent.jar": receipt["agentHashes"]["legacy"],
                "probe.jar": receipt["sharedHashes"]["auxiliary"]}
    for name, path in artifacts.items():
        if digest(path) != expected[name]:
            raise ValueError("archived artifact differs: " + name)
        files["artifacts/" + name] = path
    for name in files:
        if name.lower().endswith((".cmo3", ".can3")) or any(part.lower() in
                ("roaming", "local", "profile", "license") for part in Path(name).parts):
            raise ValueError("private model/profile data in archive allowlist")
    hashes = {name: digest(path) for name, path in sorted(files.items())}
    manifest = json.dumps(hashes, indent=2).encode("utf-8")
    destination = root / "texture-host-evidence.zip"
    if destination.exists():
        raise FileExistsError("refusing to overwrite an existing archive")
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED) as output:
        for name, path in sorted(files.items()):
            output.write(path, name)
        output.writestr("manifest.json", manifest)
    with zipfile.ZipFile(destination) as check:
        for name, expected_hash in hashes.items():
            if hashlib.sha256(check.read(name)).hexdigest() != expected_hash:
                raise ValueError("archive verification failed: " + name)
    result = {"archiveFile": destination.name, "sha256": digest(destination),
              "manifestSha256": hashlib.sha256(manifest).hexdigest(), "entryCount": len(files) + 1,
              "bytes": destination.stat().st_size, "modelFilesIncluded": False,
              "licenseStateIncluded": False, "measurementRunnerSha256": receipt["runnerSha256"]}
    save(receipt_dir / "archive-receipt.json", result)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("receipt_dir", type=Path)
    args = parser.parse_args()
    print(json.dumps(archive(args.root, args.receipt_dir), indent=2))
