#!/usr/bin/env python3
"""Compare SHA-bound F5 collections. Matching observed fields is not full F5 acceptance."""
import argparse
import hashlib
import json
from pathlib import Path
import re

HOST_SHA = "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
STAGES = ("before", "after", "undo", "redo", "saved")
DECLARATIONS = {
    "scope": "current-keyform geometry, authoring state, public texture relations",
    "colors.scope": "official Editor current-keyform RGBA; not evaluated Core colors",
    "rawInputTransformAndClipping": "SDK_DETAILS_UNAVAILABLE",
    "excluded": "observation revision/binding, import/source timestamps, generated GUID values",
}
FIELDS = {
    "raw": ("name", "size", "replaced", "sourceKind", "treeVisible"),
    "image": ("name", "size", "bindings", "current", "linked", "users"),
    "mesh": ("guidRole", "name", "geometry", "part", "deformer", "visible", "locked",
             "opacity", "order", "masks", "invertedMask", "culling", "multiply", "screen"),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def unescape(value):
    result = []
    pos = 0
    while pos < len(value):
        char = value[pos]
        pos += 1
        if char != "\\":
            result.append(char)
            continue
        require(pos < len(value), "trailing Properties escape/continuation is not supported")
        char = value[pos]
        pos += 1
        if char == "u":
            digits = value[pos:pos + 4]
            require(len(digits) == 4 and all(c in "0123456789abcdefABCDEF" for c in digits),
                    "invalid Properties Unicode escape")
            result.append(chr(int(digits, 16)))
            pos += 4
        else:
            require(char in "trnf\\ :=#!", "unsupported Properties escape")
            result.append({"t": "\t", "r": "\r", "n": "\n", "f": "\f"}.get(char, char))
    # Java Properties.store emits supplementary characters as two UTF-16 escapes.
    return "".join(result).encode("utf-16-le", "surrogatepass").decode("utf-16-le")


def properties(data):
    """Strict reader for the unwrapped key=value format emitted by Properties.store."""
    result = {}
    for line in data.decode("iso-8859-1").split("\n"):
        line = line.removesuffix("\r")
        if not line or line.startswith(("#", "!")):
            continue
        escaped = False
        split = None
        for pos, char in enumerate(line):
            if char == "=" and not escaped:
                split = pos
                break
            escaped = char == "\\" and not escaped
        require(split is not None, "missing Properties key/value separator")
        key, value = unescape(line[:split]), unescape(line[split + 1:])
        require(key and key not in result, "empty/duplicate Properties key")
        result[key] = value
    return result


def load_collection(directory, phase):
    require(phase in ("structure-native", "structure-sdk"), "unknown collection phase")
    directory = Path(directory)
    job = json.loads((directory / "bound-job.json").read_text())
    life = json.loads((directory / "lifecycle-result.json").read_text())
    require(json.loads(job["evidence_json"]) == life, "job and lifecycle evidence differ")
    for column, field in (("job_id", "jobId"), ("attempt_id", "attemptId"),
                          ("run_id", "runId"), ("digest", "preparedDigest")):
        require(job.get(column) and job[column] == life.get(field), "job identity mismatch: " + field)
    require(job["state"] == life["terminalState"] == "succeeded", "collection task did not succeed")
    require(life["finalizedBy"] == "contained-supervisor" and life["validationStatus"] == "PASS"
            and life["runnerExitCode"] == 0 and life["cleanup"] == "safe", "unsafe/incomplete lifecycle")
    require(all(life.get(k) is True for k in ("normalExit", "identityVerified", "fixtureUnchanged")),
            "lifecycle identity/fixture/exit gate failed")
    detail = life["details"]
    require(all(detail.get(k) is True for k in ("validationComplete", "goldenUnchanged", "taskOwnedCleanup")),
            "collection cleanup or identity is incomplete")
    require(detail["identityActualJarSha256"] == detail["identityExpectedJarSha256"] == HOST_SHA,
            "wrong official JAR")
    fixture = detail["sourceFixtureBeforeSha256"]
    require(sha(fixture) and fixture == detail["sourceFixtureAfterSha256"]
            == detail["fixtureBeforeSha256"] == detail["fixtureAfterSha256"], "fixture SHA mismatch")
    post = detail["postContainmentChecks"]
    require(post["fixtureCopySha256"] == post["fixtureSourceSha256"] == fixture
            and post["clonedJarSha256"] == post["goldenJarSha256"] == HOST_SHA,
            "post-containment fixture/JAR mismatch")
    artifacts = {}
    for artifact in post["stagedArtifacts"]:
        name = Path(artifact["staged"]).name
        require(name not in artifacts and sha(artifact["sourceSha256"])
                and artifact["sourceSha256"] == artifact["stagedSha256"], "staged artifact SHA mismatch")
        artifacts[name] = artifact["stagedSha256"]
    require(all(name in artifacts for name in ("turboism-agent.jar", "external-psd-edit-host-probe.jar")),
            "missing collection artifacts")
    terminal = detail.get("terminalResult") or detail["postContainmentChecks"]["terminalResult"]
    require(terminal.get("passed") is True and terminal.get("passSeen") is True
            and terminal.get("failSeen") is False and terminal.get("failureMarkers") == [],
            "terminal markers did not pass")
    data = (directory / "external-psd-edit-result.properties").read_bytes()
    require(hashlib.sha256(data).hexdigest() == terminal["sha256"], "terminal SHA mismatch")
    observed = properties(data)
    require(observed.get("runId") == job["run_id"] and observed.get("phase") == phase,
            "terminal phase/run identity mismatch")
    require(observed.get("status") == observed.get("structure.collection") == "PASS",
            "structural collection did not pass")
    require(observed.get("contentProfile") == "f1", "wrong structural content profile")
    require(observed.get("structure.variant") in ("add", "delete", "merge", "canvas"), "unknown variant")
    require(sha(observed.get("structure.source.sha256")), "missing structural source SHA")
    if phase == "structure-native":
        require(observed.get("structure.native.chooserComplete") == "true"
                and observed.get("structure.native.command.invocations") == "1"
                and observed.get("structure.native.host.jar.sha256") == HOST_SHA,
                "native command proof missing")
    else:
        require(observed.get("structure.sdk.importCompletion.status") == "APPLIED"
                and observed.get("structure.sdk.importCompletion.consumedRevision") == "true",
                "SDK did not apply and consume its revision")
    stages = {}
    identities = []
    for stage in STAGES:
        prefix = "structure." + stage + "."
        values = {k[len(prefix):]: v for k, v in observed.items() if k.startswith(prefix)}
        for key, value in DECLARATIONS.items():
            require(values.pop(key, None) == value, "missing/changed observation scope: " + stage + "." + key)
        identities.append((values.pop("historyDocument", ""), values.pop("historyManager", "")))
        require(all(identities[-1]), "unbound history")
        for key in ("raw.count", "image.count", "mesh.count", "historyPosition", "historyEntries"):
            require(key in values and values[key].isdigit(), "missing structural count: " + stage + "." + key)
        for key in ("canvas", "groups"):
            require(key in values, "missing structural field: " + stage + "." + key)
        for kind, fields in FIELDS.items():
            suffix = "." + fields[0]
            objects = [key[:-len(suffix)] for key in values
                       if key.startswith(kind + ".") and key.endswith(suffix)]
            require(len(objects) == int(values[kind + ".count"]), "incomplete " + kind + " identities")
            for obj in objects:
                require(all(obj + "." + field in values for field in fields),
                        "incomplete " + kind + " observation")
                if kind == "mesh":
                    require("inputs." + obj[len("mesh."):] in values, "missing ArtMesh inputs")
        stages[stage] = values
    require(len(set(identities)) == 1, "history identity changed inside a collection")
    before_position = int(stages["before"]["historyPosition"])
    for stage in STAGES:
        expected = before_position + (stage not in ("before", "undo"))
        require(int(stages[stage]["historyPosition"]) == expected, "wrong history position: " + stage)
        require(int(stages[stage]["historyEntries"]) == before_position + (stage != "before"),
                "wrong history entry count: " + stage)
    return {"jobId": job["job_id"], "runId": job["run_id"], "terminalSha256": terminal["sha256"],
            "fixtureSha256": fixture, "variant": observed["structure.variant"],
            "sourceSha256": observed["structure.source.sha256"], "artifacts": artifacts, "stages": stages}


def compare(native_dir, sdk_dir):
    native = load_collection(native_dir, "structure-native")
    sdk = load_collection(sdk_dir, "structure-sdk")
    require(native["jobId"] != sdk["jobId"] and native["runId"] != sdk["runId"], "tasks must be independent")
    for key in ("fixtureSha256", "variant", "sourceSha256"):
        require(native[key] == sdk[key], "comparison inputs differ: " + key)
    require(native["artifacts"]["external-psd-edit-host-probe.jar"]
            == sdk["artifacts"]["external-psd-edit-host-probe.jar"], "collection probe differs")
    differences = {}
    for stage in STAGES:
        left, right = native["stages"][stage], sdk["stages"][stage]
        differences[stage] = {k: {"native": left.get(k), "sdk": right.get(k)}
                              for k in sorted(left.keys() | right.keys()) if left.get(k) != right.get(k)}
    return {"status": "OBSERVED_FIELDS_MISMATCH" if any(differences.values()) else "OBSERVED_FIELDS_MATCH",
            "F5": "NOT_CLAIMED", "SC006": "NOT_CLAIMED",
            "unobserved": ["live input affine/clipping", "dirty state", "final viewport presentation"],
            "native": {k: v for k, v in native.items() if k != "stages"},
            "sdk": {k: v for k, v in sdk.items() if k != "stages"},
            "fieldCounts": {s: len(native["stages"][s]) for s in STAGES}, "differences": differences}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("native_directory", type=Path)
    parser.add_argument("sdk_directory", type=Path)
    args = parser.parse_args()
    try:
        result = compare(args.native_directory, args.sdk_directory)
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(json.dumps({"status": "REJECTED", "reason": str(error), "F5": "NOT_CLAIMED"}))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "OBSERVED_FIELDS_MATCH" else 1


if __name__ == "__main__":
    raise SystemExit(main())
