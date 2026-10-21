#!/usr/bin/env python3
"""Compile/run the isolated kernel at the working tree or an immutable git ref."""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
KERNEL = Path("plugins/atlas-dalsoo-polygon/src/main/java/dev/turboism/plugin/atlasdalsoo/dalsoo")
SOURCES = [
    Path("sdk/src/main/java/dev/turboism/sdk/CubismEditor.java"),
    Path("sdk/src/main/java/dev/turboism/sdk/cubism/textureatlas/TextureAtlasRotationMode.java"),
] + [KERNEL / (name + ".java") for name in (
    "Bin", "Convex", "Geom", "PackedPoly", "SourcePoly", "PolygonPack",
    "PackOutcome", "PackingCancelledException", "StrictRingIndex",
)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", help="Read production sources from this git ref")
    parser.add_argument("--mode", choices=("measure", "fallback", "retention", "heavy"), default="measure")
    parser.add_argument("--heap-mb", type=int, default=512)
    parser.add_argument("--shape", choices=("lshape", "mixed"), default="mixed")
    parser.add_argument("--count", type=int, default=500)
    parser.add_argument("--rotation", choices=("NONE", "QUARTER", "FREE"), default="QUARTER")
    parser.add_argument("--abey", action="store_true")
    parser.add_argument("--budget-seconds", type=int, default=60)
    parser.add_argument("--fill", type=float, default=.75)
    parser.add_argument("--layout-json", help="Export a completed heavy layout for preview")
    args = parser.parse_args()
    if args.count < 1 or args.budget_seconds < 1 or not 0 < args.fill <= 1:
        parser.error("count and budget must be positive; fill must be in (0, 1]")
    if args.heap_mb < 32:
        parser.error("--heap-mb must be at least 32")
    with tempfile.TemporaryDirectory(prefix="atlas-kernel-bench-") as directory:
        scratch = Path(directory)
        files = []
        for source in SOURCES:
            if args.ref:
                if source.stem == "StrictRingIndex" and subprocess.run(
                        ["git", "cat-file", "-e", f"{args.ref}:{source.as_posix()}"],
                        cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                    continue
                local = scratch / source.name
                local.write_bytes(subprocess.check_output(
                    ["git", "show", f"{args.ref}:{source.as_posix()}"], cwd=ROOT))
            else:
                local = ROOT / source
            files.append(str(local))
        classes = scratch / "classes"
        classes.mkdir()
        subprocess.run(["javac", "-d", str(classes), *files,
                        str(Path(__file__).with_name("KernelAllocationBench.java")),
                        str(Path(__file__).with_name("HeavyKernelBench.java"))], check=True)
        command = ["java", "-Xms32m", f"-Xmx{args.heap_mb}m", "-cp", str(classes),
                   "dev.turboism.plugin.atlasdalsoo.dalsoo.KernelAllocationBench"]
        if args.mode == "heavy":
            command[-1] = "dev.turboism.plugin.atlasdalsoo.dalsoo.HeavyKernelBench"
            command.extend([args.shape, str(args.count), args.rotation, str(args.abey).lower(),
                            str(args.budget_seconds), str(args.fill)])
            if args.layout_json:
                command.append(args.layout_json)
        elif args.mode != "measure":
            command.append("--" + args.mode)
        try:
            return subprocess.run(command, timeout=args.budget_seconds + 45 if args.mode == "heavy" else None).returncode
        except subprocess.TimeoutExpired:
            print("heavy workload exceeded external wall-time limit", flush=True)
            return 124


if __name__ == "__main__":
    raise SystemExit(main())
