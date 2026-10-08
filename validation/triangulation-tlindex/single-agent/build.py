#!/usr/bin/env python3
"""Build an isolated validation companion without changing the production premain."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def pinned(path, expected):
    if path.is_symlink() or not path.is_file() or digest(path) != expected:
        raise ValueError("pinned input rejected: " + str(path))
    return path.resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-agent", type=Path, required=True)
    parser.add_argument("--base-sha256", required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--probe-sha256", required=True)
    parser.add_argument("--scene", type=Path)
    parser.add_argument("--scene-sha256")
    parser.add_argument("--shadow", type=Path)
    parser.add_argument("--shadow-sha256")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    base = pinned(args.base_agent, args.base_sha256)
    probe = pinned(args.probe, args.probe_sha256)
    if bool(args.scene) != bool(args.scene_sha256):
        raise ValueError("scene path and SHA must be supplied together")
    scene = pinned(args.scene, args.scene_sha256) if args.scene else None
    if bool(args.shadow) != bool(args.shadow_sha256) or (args.shadow and not scene):
        raise ValueError("shadow requires its SHA and a scene sidecar")
    shadow = pinned(args.shadow, args.shadow_sha256) if args.shadow else None
    with zipfile.ZipFile(base) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n", "\n")
        if "Premain-Class: dev.turboism.bootstrap.TurboismAgent\n" not in manifest or "Boot-Class-Path: turboism-agent.jar\n" not in manifest:
            raise ValueError("base Agent entry or canonical boot path rejected")
        if "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge.class" not in jar.namelist():
            raise ValueError("base Agent lacks the production lazy bridge")
    src = Path(__file__).resolve().parent
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = out / "classes"
    classes.mkdir()
    argv = ["javac", "--release", "17", "-proc:none", "-Xlint:all", "-Werror", "-cp", str(base),
            "-d", str(classes), str(src / "TriangulationSingleAgentValidationHook.java"),
            str(src / "TriangulationSingleAgentShadowPreHook.java"), str(src / "SingleAgentPremainSelfCheck.java"),
            str(src / "SingleAgentShadowPremainSelfCheck.java")]
    result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (out / "compile.log").write_text(result.stdout)
    (out / "compile-command.json").write_text(json.dumps({"argv": argv, "exitCode": result.returncode}, indent=2) + "\n")
    if result.returncode:
        raise RuntimeError(result.stdout)
    target = out / "turboism-agent.jar"
    resource = "META-INF/turboism/hooks"
    contributor = "dev.turboism.bootstrap.TriangulationSingleAgentValidationHook"
    pre_contributor = "dev.turboism.bootstrap.TriangulationSingleAgentShadowPreHook"
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(target, "w") as destination:
        for entry in source.infolist():
            data = source.read(entry.filename)
            if entry.filename == resource:
                if contributor.encode() in data:
                    raise ValueError("base already contains this validation contributor")
                data = data.rstrip() + b"\n" + contributor.encode() + b"\n"
                if shadow:
                    if pre_contributor.encode() in data:
                        raise ValueError("base already contains shadow pre-contributor")
                    data = pre_contributor.encode() + b"\n" + data
            destination.writestr(entry, data)
        for file in sorted((classes / "dev/turboism/bootstrap").rglob("*.class")):
            destination.write(file, str(file.relative_to(classes)))
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(target) as destination:
        changed = [name for name in source.namelist() if source.read(name) != destination.read(name)]
        if changed != [resource]:
            raise ValueError("unexpected production entry mutation: " + repr(changed))
        added = set(destination.namelist()) - set(source.namelist())
        if not added or any(not name.startswith(("dev/turboism/bootstrap/TriangulationSingleAgentValidationHook",
                                                 "dev/turboism/bootstrap/TriangulationSingleAgentShadowPreHook")) for name in added):
            raise ValueError("unexpected companion entries")
    sidecars = out / "sidecars"
    sidecars.mkdir()
    shutil.copy2(probe, sidecars / "tri-weave-agent.jar")
    if scene:
        shutil.copy2(scene, sidecars / "atlas-image-shadow-scene-driver.jar")
    if shadow:
        shutil.copy2(shadow, sidecars / "t039-shadow-agent.jar")
    receipt = {"format": "turboism.triangulation.single-agent-candidate", "schemaVersion": 1,
               "agentSha256": digest(target), "baseAgentSha256": digest(base), "probeSha256": digest(probe),
               "sceneSha256": digest(scene) if scene else None, "premain": "dev.turboism.bootstrap.TurboismAgent",
               "shadowSha256": digest(shadow) if shadow else None,
               "changedBaseEntries": changed, "addedValidationEntries": sorted(added),
               "probeLoading": "pinned sidecar appended to system loader through owned Instrumentation",
               "sourceInputs": [{"path": str(file), "sha256": digest(file)} for file in sorted(src.glob("*.java"))],
               "hostJobsSubmitted": 0, "hostAcceptance": "NOT_VERIFIED"}
    (out / "candidate.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
