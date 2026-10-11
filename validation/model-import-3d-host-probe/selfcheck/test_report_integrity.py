#!/usr/bin/env python3
"""T021 report counterexamples. Clone evidence into a fresh audit; never edit input.

Every case first verifies its unmodified control, then changes only its own copy.
Checksum-repaired semantic fakes must still fail. No Java/host runs in this test.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys


def payload(d):
    # Independent test-side encoder, not imported from the verifier.
    out = bytearray()
    def add(*items):
        for value in items:
            data = None if value is None else str(value).encode()
            out.extend(struct.pack(">i", -1 if data is None else len(data)))
            if data is not None:
                out.extend(data)
    o, n = d["offlineSelfcheck"], d["native"]
    for group in (o["checks"], o["rejectionChecks"], n["checks"], n["blocked"]):
        for c in group:
            add("CHECK", *(c[k] for k in ("id", "expected", "actual", "status", "evidenceKind", "requiredEvidenceKind")),
                str(c["evidenceProvided"]).lower(), c["reason"], c["identitySource"], c.get("operationStatus"))
    for k, v in sorted(d["domainDigests"].items()):
        add("DIGEST", k, v)
    for s in d["materialSamples"]:
        add("SAMPLE", s)
    add("SCOPE", *(d["scope"][k] for k in ("sessionId", "documentId", "modelId", "generation", "ownerId")))
    add("MATERIAL", d["materialSha256"])
    return hashlib.sha256(out).hexdigest()


def reseal(d):
    d["payloadSha256"] = payload(d)
    b = d["build"]
    message = "\n".join((b["executionId"], d["payloadSha256"], b["sourceSha256"], b["classesSha256"])) + "\n"
    d["runId"] = "t006-offline-" + hashlib.sha256(message.encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    report = args.report.absolute()
    run = report.parent.parent
    for p in (report, *report.parents):
        if p.is_symlink():
            raise ValueError("symlink input")
    if (run.parent.name != "runs" or run.parent.parent.name != "model-import-3d-offline"
            or (run / ".terminal").exists() or (run / ".terminal").is_symlink()
            or (run / ".offline-run").read_bytes() != b"t006-offline-run-v1\n"):
        raise ValueError("expected owned offline run")
    audit = run / "report-integrity"
    audit.mkdir()  # Fresh only, even an empty previous directory is rejected.
    verifier = run / "inputs/selfcheck/verify_report.py"
    # Validate all input inventory before copytree (which otherwise follows links).
    if subprocess.run([sys.executable, str(verifier), str(report)], timeout=30).returncode != 0:
        raise ValueError("input control is not valid clean evidence")
    source = json.loads(report.read_text())
    records = []

    def invoke(p, label):
        argv = [sys.executable, str(verifier), str(p)]
        cp = subprocess.run(argv, capture_output=True, timeout=30)
        (audit / (label + ".stdout.txt")).write_bytes(cp.stdout)
        (audit / (label + ".stderr.txt")).write_bytes(cp.stderr)
        return dict(argv=argv, exitCode=cp.returncode, stderr=cp.stderr.decode())

    def check(key, d):
        return next(c for c in d["offlineSelfcheck"]["checks"] if c["id"] == key)

    def replace_file(p, value):
        p.chmod(0o600)  # Owned copy only; original readonly inputs are untouched.
        p.write_bytes(value)

    def case(label, mutate, repair=False):
        root = audit / label / run.name
        root.mkdir(parents=True)
        for directory in ("inputs", "classes"):
            shutil.copytree(run / directory, root / directory)
        for name in ("sources.sha256", "classes.sha256", "git-head.txt", "git-status.txt", "build.properties"):
            shutil.copyfile(run / name, root / name)
        target = root / report.parent.name
        target.mkdir()
        shutil.copyfile(report, target / "report.json")
        shutil.copyfile(report.parent / "material.png", target / "material.png")
        p = target / "report.json"
        control = invoke(p, label + "-control")
        d = copy.deepcopy(source)
        special = mutate(d, root, p)
        if repair:
            reseal(d)
        if special != "raw":
            p.write_text(json.dumps(d, ensure_ascii=False) + "\n")
        negative = invoke(p, label + "-negative")
        passed = control["exitCode"] == 0 and negative["exitCode"] == 1 and "Traceback" not in negative["stderr"]
        records.append(dict(id=label, control=control, negative=negative, passed=passed))
        print(f'{label}: {"PASS" if passed else "FAIL"}')

    case("unobserved-host-jar", lambda d, r, p: d["hostIdentity"].update(jarSha256="a" * 64))
    case("missing-host-field", lambda d, r, p: d["hostIdentity"].pop("jarSha256"))
    case("bogus-build-fields", lambda d, r, p: d["build"].update(head="invented", classesSha256="not-a-sha", javaVersion=""))
    case("mismatched-run-id", lambda d, r, p: d.update(runId="t006-offline-h0"))
    case("alias-false-writable", lambda d, r, p: check("alias.sharedForm.blocked", d).update(actual="alias=false writable=READY"), True)
    case("default-first-key", lambda d, r, p: check("loc.default.tuple", d).update(actual="syn-form-0-0"), True)
    case("morph-zero-classified", lambda d, r, p: check("morph.explicitZero.unknown", d).update(actual="syn-morph-form-00 basic=BASIC"), True)
    case("snapshot-false-claim", lambda d, r, p: check("snap.getterMutationImmune", d).update(actual="pos[1]=888.0"), True)
    case("wrong-rejection-reason", lambda d, r, p: d["offlineSelfcheck"]["rejectionChecks"][0].update(expected="operation rejected with reason=WRONG_OWNER", actual="rejected with reason=WRONG_OWNER"), True)
    case("removed-native", lambda d, r, p: d["native"].update(checks=[], blocked=[]), True)
    case("missing-check", lambda d, r, p: d["offlineSelfcheck"]["checks"].pop(), True)
    case("duplicate-check", lambda d, r, p: d["offlineSelfcheck"]["checks"].append(copy.deepcopy(d["offlineSelfcheck"]["checks"][0])), True)
    case("false-evidence", lambda d, r, p: check("loc.default.tuple", d).update(evidenceProvided=False), True)
    case("unexpected-check", lambda d, r, p: check("loc.default.tuple", d).update(id="extra.fake"), True)
    case("malformed-entry", lambda d, r, p: d["offlineSelfcheck"]["checks"].__setitem__(0, None))
    case("boolean-count", lambda d, r, p: d["native"]["summary"].update(executed=0))
    case("bad-json", lambda d, r, p: (p.write_text("{"), "raw")[1])
    case("duplicate-json-key", lambda d, r, p: (p.write_text('{"hostExecuted":true,' + p.read_text()[1:]), "raw")[1])
    case("wrong-payload", lambda d, r, p: d.update(payloadSha256="0" * 64))
    case("wrong-domain-digest", lambda d, r, p: d["domainDigests"].update({k: "0" * 64 for k in d["domainDigests"]}), True)
    case("bad-png", lambda d, r, p: replace_file(p.parent / "material.png", b"not PNG"))
    case("png-crc", lambda d, r, p: replace_file(p.parent / "material.png", (p.parent / "material.png").read_bytes()[:-1] + b"x"))
    case("source-drift", lambda d, r, p: replace_file(r / "inputs/README.md", b"changed snapshot\n"))
    case("class-drift", lambda d, r, p: replace_file(next((r / "classes").rglob("Main.class")), b"changed class\n"))
    case("extra-class", lambda d, r, p: (r / "classes/Extra.class").write_bytes(b"extra"))
    case("status-drift", lambda d, r, p: replace_file(r / "git-status.txt", b"changed status\n"))
    case("manifest-drift", lambda d, r, p: replace_file(r / "sources.sha256", b"0" * 64 + b"  fake.java\n"))
    result = dict(hostExecuted=False, inputReport=str(report), inputReportSha256=hashlib.sha256(report.read_bytes()).hexdigest(), cases=records)
    (audit / "report.json").write_text(json.dumps(result, indent=2) + "\n")
    print("audit:", audit)
    return 0 if all(x["passed"] for x in records) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as e:
        print("report integrity test refused:", e, file=sys.stderr)
        raise SystemExit(1)
