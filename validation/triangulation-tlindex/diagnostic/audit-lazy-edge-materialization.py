"""Audit pinned class files as data; never execute official Cubism classes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

P = "com.live2d.graphics3d.editableMesh.triangulation."
V = "com/live2d/graphics3d/type/GVector2"
S = "com/live2d/graphics3d/editableMesh/triangulation/"
JAR_SHA256 = {
    "5203": "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
    "5302": "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
    "5303": "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166",
}

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def method(dump, signature):
    blocks = re.split(r"\n(?=  (?:public|private|protected|static) )", dump)
    matches = [b for b in blocks if b.splitlines()[0].strip() == signature]
    require(len(matches) == 1, "missing/duplicate method: " + signature)
    return matches[0]

def instructions(block):
    result = []
    for line in block.splitlines():
        match = re.match(r"\s+(\d+):\s+([a-z0-9_]+)(.*)", line)
        if match:
            result.append((int(match[1]), match[2], match[3].strip()))
    return result

def pure_getter(block, member):
    code = instructions(block)
    require([x[1] for x in code] == ["aload_0", "getfield", "areturn"], member + " getter opcode")
    require(code[1][2].endswith("// Field " + member), member + " getter field")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    for version in JAR_SHA256:
        parser.add_argument("--jar-" + version, type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True)
    versions = {}
    for version, expected_sha in JAR_SHA256.items():
        jar = getattr(args, "jar_" + version)
        require(digest(jar) == expected_sha, version + " JAR drift")
        dumps = {}
        class_hashes = {}
        with zipfile.ZipFile(jar) as archive:
            for symbol in ("h", "j", "r", "TriPoint", "l"):
                data = archive.read(S + symbol + ".class")
                class_hashes[symbol] = hashlib.sha256(data).hexdigest()
                dump = subprocess.run(["javap", "-c", "-p", "-s", "-classpath", str(jar),
                                       P + symbol], text=True, capture_output=True, check=True).stdout
                dumps[symbol] = dump
                (args.out / (version + "-" + symbol + ".javap.txt")).write_text(dump)
        for symbol in ("j", "r", "TriPoint", "l"):
            require(dumps[symbol].splitlines()[0].startswith("public final class "), symbol + " final type")
        require("  static {};" not in dumps["j"], "edge class initializer")
        for getter, field in (("a", "a"), ("b", "b")):
            pure_getter(method(dumps["j"], "public final " + P + "TriPoint " + getter + "();"),
                        field + ":L" + S + "TriPoint;")
        for field in ("a", "b", "c"):
            pure_getter(method(dumps["l"], "public final " + P + "TriPoint " + field + "();"),
                        field + ":L" + S + "TriPoint;")
        index = instructions(method(dumps["TriPoint"], "public final int getIndex();"))
        require([x[1] for x in index] == ["aload_0", "getfield", "ireturn"]
                and index[1][2].endswith("// Field index:I"), "index getter side effect")
        ctor = method(dumps["j"], "public " + P + "j(" + P + "TriPoint, " + P + "TriPoint);")
        ctor_calls = [x[2].split("// ", 1)[-1] for x in instructions(ctor) if x[1].startswith("invoke")]
        require(len(ctor_calls) == 6 and sum("TriPoint.getIndex:()I" in c for c in ctor_calls) == 2
                and sum("checkNotNullParameter" in c for c in ctor_calls) == 2
                and sum('java/lang/Object."<init>":()V' in c for c in ctor_calls) == 1
                and sum('java/lang/AssertionError."<init>"' in c for c in ctor_calls) == 1,
                "unexpected constructor calls")
        require("kotlin/_Assertions.ENABLED:Z" in ctor, "missing constructor assertion gate")
        pair_signature = "public final com.live2d.graphics3d.type.GVector2 a(" + P + "j, " + P + "j);"
        pair = method(dumps["r"], pair_signature)
        calls = [x[2].split("// ", 1)[-1] for x in instructions(pair) if x[1].startswith("invoke")]
        endpoint_symbol = "Method a:(L" + V + ";L" + V + ";L" + V + ";L" + V + ";)L" + V + ";"
        require(len(calls) == 7 and calls[-1] == endpoint_symbol
                and [c.split("// ")[-1] for c in calls[2:6]] ==
                ["Method " + S + "j." + g + ":()L" + S + "TriPoint;" for g in ("a", "b", "a", "b")],
                "edge overload forwarding")
        require(not any(x[1].startswith(("put", "if", "monitor", "new")) for x in instructions(pair)),
                "edge forwarding side effect")
        endpoint = method(dumps["r"], "public final com.live2d.graphics3d.type.GVector2 a(" +
                          ", ".join(["com.live2d.graphics3d.type.GVector2"] * 4) + ");")
        require(not any(x[1].startswith(("put", "monitor", "invokedynamic")) for x in instructions(endpoint)),
                "endpoint writes/monitor")
        endpoint_calls = [x[2].split("// ", 1)[-1] for x in instructions(endpoint) if x[1].startswith("invoke")]
        require(all(c in {"Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V",
                           "Method " + V + ".getX:()F", "Method " + V + ".getY:()F",
                           "Method " + V + '."<init>":(FF)V'} for c in endpoint_calls),
                "endpoint unknown calls")
        phase = method(dumps["h"], "public final void c();")
        code = instructions(phase)
        constructors = [x for x in code if x[1] == "invokespecial" and S + 'j."<init>"' in x[2]]
        intersections = [x for x in code if S + "r.a:(L" + S + "j;L" + S + "j;)L" + V + ";" in x[2]]
        require(len(constructors) == len(intersections) == 4, "full c site inventory")
        primary_constructors, primary_intersections = constructors[:3], intersections[:3]
        require(max(x[0] for x in primary_constructors) < min(x[0] for x in primary_intersections),
                "primary construction/evaluation order")
        stores = [code[code.index(x) + 1] for x in primary_constructors]
        require([x[1:] for x in stores] == [("astore", "12"), ("astore", "13"), ("astore", "14")],
                "primary edge locals")
        contains = [x for x in code if "java/util/ArrayList.contains:(Ljava/lang/Object;)Z" in x[2]]
        require(len(contains) == 3 and max(x[0] for x in primary_intersections) < contains[0][0],
                "all three primary results precede append checks")
        require(constructors[3][0] > contains[2][0] and intersections[3][0] > constructors[3][0],
                "additional phase must stay outside candidate")
        versions[version] = {"jarSha256": expected_sha, "classSha256": class_hashes,
                             "primaryConstructorBcis": [x[0] for x in primary_constructors],
                             "primaryIntersectionBcis": [x[0] for x in primary_intersections],
                             "untouchedAdditionalConstructorBcis": [x[0] for x in constructors[3:]],
                             "untouchedAdditionalIntersectionBcis": [x[0] for x in intersections[3:]],
                             "checks": "PINNED_ENDPOINT_FORWARDING_AND_PURE_GETTERS_CHECKED"}
    report = {"status": "STATIC_LAZY_EDGE_FEASIBILITY_CHECKED", "versions": versions,
              "officialClassesExecuted": False, "productionPatched": False,
              "requirements": ["Retain all three null/index/assertion checks before any intersection.",
                               "Call the original endpoint overload with identical endpoint order.",
                               "Compute all three intersections before conditional processing.",
                               "Allocate a distinct fresh edge before its first subsequent edge use.",
                               "Preserve insertion order, duplicates, native private predicate and append logic."],
              "limitations": ["Static class-file audit, not proof of actual dependency definitions.",
                              "Runtime gates and bytecode/control-flow/exception verification remain unimplemented.",
                              "No host correctness, performance or theoretical-limit acceptance."]}
    (args.out / "static-review.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
