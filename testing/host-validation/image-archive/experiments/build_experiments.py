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
HISTORICAL_LONG = 33_554_432
LARGE_LONG = 67_108_864
CONVERTER_ENTRY = "dev/turboism/adapter/cubism/optimization/image/CanonicalRgbaImage.class"


def long_constants(class_bytes):
    """Returns the Long constants in a class file without loading or trusting it."""
    if class_bytes[:4] != bytes.fromhex("cafebabe"):
        raise ValueError("not a class file")
    try:
        count = int.from_bytes(class_bytes[8:10], "big")
        position = 10
        values = set()
        index = 1
        while index < count:
            tag = class_bytes[position]
            position += 1
            if tag == 1:
                position += 2 + int.from_bytes(class_bytes[position:position + 2], "big")
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                position += 4
            elif tag in (5, 6):
                raw = class_bytes[position:position + 8]
                if len(raw) != 8:
                    raise ValueError("truncated constant pool")
                if tag == 5:
                    values.add(int.from_bytes(raw, "big", signed=True))
                position += 8
                index += 1
            elif tag in (7, 8, 16, 19, 20):
                position += 2
            elif tag == 15:
                position += 3
            else:
                raise ValueError(f"unsupported constant pool tag {tag}")
            if position > len(class_bytes):
                raise ValueError("truncated constant pool")
            index += 1
        return values
    except (IndexError, TypeError) as failure:
        raise ValueError("malformed constant pool") from failure


def validate_converter_source(text, label="converter source"):
    """Admits only the frozen pre-promotion source and returns the large-image variant."""
    historical = text.count(HISTORICAL_LIMIT)
    promoted = text.count(LARGE_LIMIT)
    if historical != 1 or promoted != 0:
        raise SystemExit(
            f"{label} is not the frozen PRE-promotion converter: expected exactly one "
            f"'{HISTORICAL_LIMIT}' and no '{LARGE_LIMIT}', found {historical} historical "
            f"and {promoted} promoted limit literal(s). Refusing to build a silent "
            "same-policy variant; pass --converter-source with the frozen pre-promotion "
            "source (matching the frozen --product control) or use a current-product "
            "experiment design instead.")
    return text.replace(HISTORICAL_LIMIT, LARGE_LIMIT)


def control_policy_constants(product):
    """Checks the supplied control agent itself is the frozen pre-promotion policy."""
    with zipfile.ZipFile(product) as archive:
        try:
            class_bytes = archive.read(CONVERTER_ENTRY)
        except KeyError as failure:
            raise SystemExit(f"control product lacks {CONVERTER_ENTRY}") from failure
    try:
        constants = long_constants(class_bytes)
    except ValueError as failure:
        raise SystemExit(f"control product has an unreadable converter class: {failure}") from failure
    if HISTORICAL_LONG not in constants or LARGE_LONG in constants:
        raise SystemExit(
            "control product is not the frozen PRE-promotion converter: expected the "
            f"{HISTORICAL_LONG} pixel limit and no {LARGE_LONG} limit. Pass the matching "
            "frozen pre-promotion --product agent.")
    return constants


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
    changed = validate_converter_source(original, str(source))
    control_constants = control_policy_constants(product)
    frozen_source = root / "frozen-src/CanonicalRgbaImage.java"
    frozen_source.parent.mkdir()
    frozen_source.write_text(original, encoding="utf-8")
    frozen_classes = root / "frozen-classes"
    subprocess.run([str(java / "bin/javac.exe"), "--release", "17", "-d", str(frozen_classes), str(frozen_source)], check=True)
    variant_source = root / "large-src/CanonicalRgbaImage.java"
    variant_source.parent.mkdir()
    variant_source.write_text(changed, encoding="utf-8")
    variant_classes = root / "large-classes"
    subprocess.run([str(java / "bin/javac.exe"), "--release", "17", "-d", str(variant_classes), str(variant_source)], check=True)
    frozen_constants = long_constants((frozen_classes / CONVERTER_ENTRY).read_bytes())
    variant_constants = long_constants((variant_classes / CONVERTER_ENTRY).read_bytes())
    if HISTORICAL_LONG not in frozen_constants or LARGE_LONG in frozen_constants:
        raise SystemExit("frozen source did not compile to the pre-promotion pixel policy")
    if LARGE_LONG not in variant_constants or HISTORICAL_LONG in variant_constants:
        raise SystemExit("large-image variant did not compile to the promoted pixel policy")
    if control_constants != frozen_constants:
        raise SystemExit(
            "control agent's converter long-constant set does not match the frozen source; "
            "the supplied --product/--converter-source pair is not a verified reproduction")
    entry = CONVERTER_ENTRY
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
