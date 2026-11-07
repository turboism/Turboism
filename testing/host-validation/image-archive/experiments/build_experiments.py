"""Build private experiment artifacts; never overwrite installed/product files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

REPO = Path(__file__).resolve().parents[4]

# The historical large-image experiment derives its candidate from the frozen
# PRE-promotion converter source, which must contain exactly one old area-limit
# literal. Production has since been promoted to 67_108_864, so the current tree
# can no longer be used implicitly.
HISTORICAL_LIMIT = "pixels > 33_554_432L"
LARGE_LIMIT = "pixels > 67_108_864L"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(args):
    root, host, java, product, asm = (Path(p).resolve() for p in
                                     (args.output, args.host, args.java_home, args.product, args.asm))
    root.mkdir(parents=True, exist_ok=False)
    classes = root / "classes"
    jars = list((host / "app/lib").glob("*.jar")) + list((host / "app/lib/jogl").glob("*.jar")) + [asm]
    cp = os.pathsep.join(map(str, jars))
    src = Path(__file__).parent
    subprocess.run([str(java / "bin/javac.exe"), "--release", "17", "-encoding", "UTF-8", "-cp", cp,
                    "-d", str(classes), *map(str, src.glob("*.java"))], check=True)
    subprocess.run([str(java / "bin/java.exe"), "-Xverify:all", "-Djava.awt.headless=true", "-cp",
                    str(classes) + os.pathsep + cp, "dev.turboism.validation.experiments.HotspotOpsTest"], check=True)
    probe = root / "hotspot-experiments.jar"
    with zipfile.ZipFile(probe, "x", compression=zipfile.ZIP_DEFLATED) as out:
        out.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nPremain-Class: dev.turboism.validation.experiments.HotspotExperimentAgent\r\nCan-Retransform-Classes: true\r\n\r\n")
        for path in sorted(classes.rglob("*.class")):
            out.writestr(path.relative_to(classes).as_posix(), path.read_bytes())
        with zipfile.ZipFile(asm) as library:
            for name in library.namelist():
                if name.startswith("org/objectweb/asm/") and name.endswith(".class"):
                    out.writestr(name, library.read(name))
    subprocess.run([str(java / "bin/java.exe"), f"-javaagent:{probe}=selftest", "-cp",
                    str(probe) + os.pathsep + cp, "dev.turboism.validation.experiments.ExperimentLifecycleTest"], check=True)
    subprocess.run([str(java / "bin/java.exe"), "-Djava.awt.headless=true", "-cp",
                    str(probe) + os.pathsep + cp, "dev.turboism.validation.experiments.ExperimentWeaveTest",
                    str(host / "app/lib/Live2D_Cubism.jar")], check=True)
    with zipfile.ZipFile(probe) as check:
        if any(name.startswith("com/live2d/") for name in check.namelist()):
            raise AssertionError("licensed host classes must not be embedded")
    source = Path(args.converter_source).resolve() if args.converter_source else (
        REPO / "runtime/src/main/java/dev/turboism/adapter/cubism/optimization/image/CanonicalRgbaImage.java")
    if not source.is_file():
        raise SystemExit(
            f"converter source not found: {source}. After the production promotion the "
            "current tree no longer matches the frozen pre-promotion experiment; pass "
            "--converter-source pointing at the frozen PRE-promotion CanonicalRgbaImage.java.")
    original = source.read_text(encoding="utf-8")
    historical = original.count(HISTORICAL_LIMIT)
    promoted = original.count(LARGE_LIMIT)
    if historical != 1 or promoted != 0:
        raise SystemExit(
            f"{source} is not the frozen PRE-promotion converter: expected exactly one "
            f"'{HISTORICAL_LIMIT}' and no '{LARGE_LIMIT}', found {historical} historical "
            f"and {promoted} promoted limit literal(s). Refusing to build a silent "
            "same-policy variant; pass --converter-source with the frozen pre-promotion "
            "source (matching the frozen --product control) or use a current-product "
            "experiment design instead.")
    changed = original.replace(HISTORICAL_LIMIT, LARGE_LIMIT)
    variant_source = root / "large-src/CanonicalRgbaImage.java"
    variant_source.parent.mkdir()
    variant_source.write_text(changed, encoding="utf-8")
    variant_classes = root / "large-classes"
    subprocess.run([str(java / "bin/javac.exe"), "--release", "17", "-d", str(variant_classes), str(variant_source)], check=True)
    entry = "dev/turboism/adapter/cubism/optimization/image/CanonicalRgbaImage.class"
    large = root / "large-agent.jar"
    with zipfile.ZipFile(product) as source_zip, zipfile.ZipFile(large, "x") as out:
        for item in source_zip.infolist():
            out.writestr(item, (variant_classes / entry).read_bytes() if item.filename == entry else source_zip.read(item.filename))
    with zipfile.ZipFile(product) as a, zipfile.ZipFile(large) as b:
        changed_entries = [name for name in a.namelist() if a.read(name) != b.read(name)]
        assert a.namelist() == b.namelist() and changed_entries == [entry]
    receipt = {"productSha256": sha(product), "largeSha256": sha(large), "probeSha256": sha(probe),
               "asmSha256": sha(asm), "changedLargeEntries": changed_entries,
               "hostJarSha256": sha(host / "app/lib/Live2D_Cubism.jar"),
               "converterSource": str(source), "converterSourceSha256": sha(source),
               "sourceHashes": {p.name: sha(p) for p in sorted(src.glob("*.java"))}}
    (root / "build-receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("host", "java-home", "product", "asm", "output"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument(
        "--converter-source",
        help="Path to the frozen PRE-promotion CanonicalRgbaImage.java (containing the "
        "historical 33_554_432L area limit). Required for reproducing the historical "
        "large-image experiment now that production has been promoted; --product must "
        "be the frozen matching pre-promotion control agent.")
    build(parser.parse_args())
