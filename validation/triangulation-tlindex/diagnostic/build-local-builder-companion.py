"""T053 diagnostic-only exact return-capture admission; never a production build.

Production preparation rejects the observer-modified b() body. This companion
changes only its expected builder fingerprint to the two reviewed observer bodies.
All field shape, b code, other dependency and ownership checks remain mandatory.
The observed probe adds a caught onReturn call after lease close, preserving the
same returned object. This supports instrumented comparisons, not production
readiness or uninstrumented performance claims.
"""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import zipfile

root = Path.cwd()
source_jar, destination = map(Path, sys.argv[1:])
assert hashlib.sha256(source_jar.read_bytes()).hexdigest() == (
    "b1ea987a8014d2bfd4b8e20f0fd41219f64e802e427274401ab73d26e0107bd1")
assert not destination.exists()
work = destination.parent / "diagnostic-admission-build"
work.mkdir(parents=True, exist_ok=False)
source = root / "runtime/src/main/java/dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgePreparation.java"
text = source.read_text()
needle = "expected.put(TL, TriangleListEdgeBuilderPatcher.dependencyFingerprint(builtList));"
assert text.count(needle) == 1
text = text.replace(needle, """// DIAGNOSTIC ONLY: exact reviewed onReturn observation bodies.
            expected.put(TL, family52
                    ? "b3cc88389dfacd3057616e7405ad388b85658b6cdfacf6aeb608c690554b39cb"
                    : "7fc76eab42b932ce2ae5bd28803c69bf81981162dad6b0056c5cc8a86a53bcda");""")
text = text.replace("org.objectweb.asm.", "dev.turboism.agent.shaded.asm.")
java = work / "LazyTriangulationEdgePreparation.java"
java.write_text(text)
classes = work / "classes"
classes.mkdir()
subprocess.run(["javac", "--release", "17", "-Xlint:all", "-Werror", "-cp", str(source_jar),
                "-d", str(classes), str(java)], check=True)
family = "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgePreparation"
replacements = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")}
assert replacements and all(n.startswith(family) for n in replacements)
destination.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(source_jar) as src, zipfile.ZipFile(destination, "x") as dst:
    names = src.namelist()
    assert len(names) == len(set(names)) and set(replacements) <= set(names)
    for item in src.infolist():
        dst.writestr(item, replacements.get(item.filename, src.read(item.filename)))
with zipfile.ZipFile(source_jar) as src, zipfile.ZipFile(destination) as dst:
    assert src.namelist() == dst.namelist()
    changed = [n for n in src.namelist() if src.read(n) != dst.read(n)]
    assert changed and all(n.startswith(family) for n in changed)
record = {"inputSha256": hashlib.sha256(source_jar.read_bytes()).hexdigest(),
          "outputSha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
          "changed": changed, "productionModified": False,
          "admission": "EXACT_DIAGNOSTIC_RETURN_CAPTURE_ONLY",
          "projection5203": "b3cc88389dfacd3057616e7405ad388b85658b6cdfacf6aeb608c690554b39cb",
          "projection53": "7fc76eab42b932ce2ae5bd28803c69bf81981162dad6b0056c5cc8a86a53bcda"}
(destination.parent / "diagnostic-admission-review.json").write_text(json.dumps(record, indent=2) + "\n")
print(json.dumps(record, indent=2))
