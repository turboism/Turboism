"""Verify class-file data preservation without defining any official class."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


HOST = "com/live2d/graphics3d/editableMesh/triangulation/h"


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class Reader:
    def __init__(self, data):
        self.data = data
        self.at = 0

    def take(self, count):
        result = self.data[self.at:self.at + count]
        require(len(result) == count, "truncated class data")
        self.at += count
        return result

    def number(self, count):
        return int.from_bytes(self.take(count), "big")


def parse(data):
    reader = Reader(data)
    require(reader.take(4) == b"\xca\xfe\xba\xbe", "class magic")
    version = reader.take(4)
    pool_count = reader.number(2)
    pool_start = reader.at
    utf8 = {}
    index = 1
    while index < pool_count:
        tag = reader.number(1)
        if tag == 1:
            utf8[index] = reader.take(reader.number(2)).decode("utf-8", errors="replace")
        elif tag in (3, 4):
            reader.take(4)
        elif tag in (5, 6):
            reader.take(8)
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            reader.take(2)
        elif tag in (9, 10, 11, 12, 17, 18):
            reader.take(4)
        elif tag == 15:
            reader.take(3)
        else:
            raise ValueError("unknown constant-pool tag")
        index += 1
    pool = data[pool_start:reader.at]
    header = reader.take(6)
    interfaces = reader.take(2 * reader.number(2))

    def attributes():
        result = []
        for _ in range(reader.number(2)):
            name = utf8[reader.number(2)]
            result.append((name, reader.take(reader.number(4))))
        return result

    def members():
        result = {}
        for _ in range(reader.number(2)):
            access = reader.number(2)
            name, desc = utf8[reader.number(2)], utf8[reader.number(2)]
            key = (name, desc)
            require(key not in result, "duplicate member")
            result[key] = (access, attributes())
        return result

    fields = members()
    methods = members()
    class_attributes = attributes()
    require(reader.at == len(data), "class trailing bytes")
    return {"version": version, "pool": pool, "header": header, "interfaces": interfaces,
            "fields": fields, "methods": methods, "classAttributes": class_attributes}


def code_metadata(data):
    reader = Reader(data)
    max_stack, max_locals = reader.number(2), reader.number(2)
    code = reader.take(reader.number(4))
    handlers = reader.take(8 * reader.number(2))
    return {"maxStack": max_stack, "maxLocals": max_locals,
            "codeLength": len(code), "handlersBytes": len(handlers)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--baseline", type=Path,
                        help="optional SHA-bound bytes after the reviewed existing transforms")
    args = parser.parse_args()
    pins_path = args.candidate / "pins.txt"
    pins = dict(line.split("=", 1) for line in pins_path.read_text().splitlines())
    require(file_sha(args.jar) == pins["jarSha"], "official JAR binding drift")
    with zipfile.ZipFile(args.jar) as jar:
        original = jar.read(HOST + ".class")
    candidate_path = args.candidate / "h.class"
    candidate = candidate_path.read_bytes()
    require(sha(original) == pins[HOST] and sha(candidate) == pins["hOutput"], "class pin drift")
    baseline = original
    if args.baseline is not None:
        baseline = args.baseline.read_bytes()
        require(sha(baseline) == pins["hBeforeLazy"], "pre-lazy baseline binding drift")
    before, after = parse(baseline), parse(candidate)
    require(after["pool"].startswith(before["pool"]), "original constant pool changed")
    for key in ("version", "header", "interfaces", "fields", "classAttributes"):
        require(before[key] == after[key], key + " changed")
    require(before["methods"].keys() == after["methods"].keys(), "method signatures changed")
    unchanged = []
    for key, method in before["methods"].items():
        if key == ("c", "()V"):
            require(method[0] == after["methods"][key][0], "c access changed")
            require([(n, b) for n, b in method[1] if n != "Code"] ==
                    [(n, b) for n, b in after["methods"][key][1] if n != "Code"], "c metadata changed")
            continue
        require(method == after["methods"][key], "non-c method bytes changed " + str(key))
        unchanged.append(list(key))
    before_code = code_metadata(dict(before["methods"][("c", "()V")][1])["Code"])
    after_code = code_metadata(dict(after["methods"][("c", "()V")][1])["Code"])
    require(after_code["maxLocals"] == before_code["maxLocals"] + 8, "temporary local count")
    require(before_code["handlersBytes"] == after_code["handlersBytes"] == 0, "unexpected c handlers")
    inputs = (args.jar, candidate_path, pins_path, Path(__file__))
    if args.baseline is not None:
        inputs += (args.baseline,)
    report = {"status": "STATIC_CLASS_DATA_PRESERVATION_CHECKED", "version": pins["version"],
              "officialClassesExecuted": False,
              "baseline": "official" if args.baseline is None else "explicit-pre-lazy-transform",
              "inputs": {str(p): file_sha(p) for p in inputs},
              "unchangedMethods": unchanged, "unchangedBaselineConstantPool": True,
              "unchangedOriginalConstantPool": baseline == original,
              "originalCode": before_code, "candidateCode": after_code,
              "limitations": ["Class-file data comparison, not official-class definition or native execution.",
                              "The selected c body requires generated-fixture control-flow/exception tests and later actual dependency admission."]}
    with args.out.open("x") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print("LAZY_EDGE_STATIC_PRESERVATION PASS", pins["version"], "unchangedMethods=" + str(len(unchanged)))


if __name__ == "__main__":
    main()
