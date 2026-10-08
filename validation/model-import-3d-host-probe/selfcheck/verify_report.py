#!/usr/bin/env python3
"""Independent verifier for the T006 offline report JSON (stdlib only).

Re-implements the digest encoding and decodes the sibling material.png
itself; expected colors, coordinates, and digests are frozen constants
stated here, never imported from generator code.

usage: verify_report.py <report.json>
exit 0 = report conforms; exit 1 = violations listed on stderr.
"""
import hashlib
import json
import re
import struct
import sys
import zlib
from pathlib import Path


# --- frozen expected material (independent restatement, not generator code) ---
MAT_W, MAT_H = 256, 128
ARGB_TL, ARGB_TR, ARGB_BL, ARGB_BR = 0xFFE23B3B, 0xFF3BB25A, 0xFF3B5CE2, 0xFFF0C832
ARGB_MARK = 0xFF101010
MX, MY = 24, 16
SAMPLES = [  # (x, y, argb, label) — corners are edge pixels, not "far" samples
    (64, 32, ARGB_TL, "quadrant-TL-center"),
    (192, 32, ARGB_TR, "quadrant-TR-center"),
    (64, 96, ARGB_BL, "quadrant-BL-center"),
    (192, 96, ARGB_BR, "quadrant-BR-center"),
    (0, 0, ARGB_TL, "edge-NW"),
    (255, 0, ARGB_TR, "edge-NE"),
    (0, 127, ARGB_BL, "edge-SW"),
    (255, 127, ARGB_BR, "edge-SE"),
    (MX + 12, MY + 3, ARGB_MARK, "marker-arrow-head"),
    (MX + 2, MY + 3, ARGB_MARK, "marker-arrow-shaft"),
    (MX + 12, MY + 0, ARGB_TL, "marker-asymmetry-above-head"),
    (MX + 12, MY + 7, ARGB_TL, "marker-asymmetry-below-head"),
]

REQUIRED_CHECKS = {
    *[f"loc.cell.{a}_{b}" for a in (-1, 0, 1) for b in (-1, 0, 1)],
    "loc.cell.distinct", "loc.default.tuple", "loc.default.guidConsistent",
    "loc.default.notFirstKey", "loc.tuple.normalized",
    "morph.keys.distinct", "morph.explicitZero.unknown",
    "alias.sharedForm.blocked",
    "snap.externalMutationImmune", "snap.getterMutationImmune",
    "snap.digestContentDerived", "snap.domainSeparation",
    "snap.domainIsolationAll", "snap.drawOrderPresence",
    "snap.digestKnownVector",
    "mat.png.sizeOpaque", "mat.png.markerAsymmetric",
    "rpt.jsonEscaping",
}
REQUIRED_REJECTIONS = {
    "rej.missingDim", "rej.extraDim", "rej.duplicateDim",
    "rej.keyNotDeclared", "rej.nan", "rej.posInf", "rej.signedZero",
    "rej.cellMissing", "rej.formMissing", "rej.staleGeneration",
    "rej.wrongOwner", "rej.morphNullKey", "rej.morphNotFound",
    "rej.morphMissingForm", "rej.morphNullFormGuid",
    "rej.topologyOddPositions", "rej.topologyIndexRange",
    "rej.uvLengthMismatch", "rej.sourceNaN", "rej.uvInfinite",
    "rej.morphDuplicateIdentity", "rej.duplicateKeyInBinding",
    "rej.bindingMinGtMax", "rej.bindingKeyOutOfRange",
    "rej.bindingDefaultOutOfRange", "rej.duplicateParamId",
    "rej.cellDuplicateDim", "rej.cellMissingDim", "rej.cellUndeclaredKey",
    "rej.cellUnboundParam", "rej.duplicateCell", "rej.missingDefaultKey",
}
REQUIRED_NATIVE = {
    "native.eval.s0", "native.eval.s05", "native.eval.s1",
    "native.undoRedo", "native.dirty", "native.saveReopen",
    "native.atlasRepack", "native.staleHost", "native.uVsUb",
    "native.morphBasicIdentity", "native.morphIsolation",
    "native.textureBinding", "native.historyAdmission",
}
REQUIRED_SEAM = {
    "seam.grid.bindings", "seam.grid.accessKeyLocate", "seam.morph.find",
    "seam.form.read", "seam.textureInput.read",
}



# ---------- digest re-implementation (mirror of Digest.java) ----------
def digest_of(domain, positions, uvs, indices, draw_order):
    b = bytearray()
    label = domain.encode("utf-8")
    b += struct.pack("<i", len(label)) + label

    def put_floats(a):
        if a is None:
            b.extend(struct.pack("<i", -1))
        else:
            b.extend(struct.pack("<i", len(a)))
            for v in a:
                b.extend(struct.pack("<f", v))

    def put_ints(a):
        if a is None:
            b.extend(struct.pack("<i", -1))
        else:
            b.extend(struct.pack("<i", len(a)))
            for v in a:
                b.extend(struct.pack("<i", v))

    put_floats(positions)
    put_floats(uvs)
    put_ints(indices)
    b.append(0 if draw_order is None else 1)
    if draw_order is not None:
        b += struct.pack("<i", draw_order)
    return hashlib.sha256(bytes(b)).hexdigest()


# Frozen fixture restatement (Python-side constants; drift = violation).
def expected_digests():
    src_pos = [0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0]
    src_uv = list(src_pos)
    src_ix = [0, 1, 2, 1, 2, 3]
    d = {
        "source.positions": digest_of("source.positions", src_pos, None, None, None),
        "source.uvs": digest_of("source.uvs", None, src_uv, None, None),
        "source.indices": digest_of("source.indices", None, None, src_ix, None),
    }
    base = 0.0
    for va in (-1, 0, 1):
        for vb in (-1, 0, 1):
            guid = f"syn-form-{va + 1}-{vb + 1}"
            base += 10.0
            p = [base + x for x in (0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5)]
            d[f"form.{guid}"] = digest_of(f"form:{guid}", p, None, None,
                                          500 + round(base))
    for kv, guid, base in ((0.0, "syn-morph-form-00", 400.0),
                           (0.5, "syn-morph-form-05", 500.0),
                           (1.0, "syn-morph-form-10", 600.0)):
        p = [base + x for x in (0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5)]
        d[f"form.{guid}"] = digest_of(f"morph:{guid}", p, None, None,
                                      500 + round(base))
    d["form.default.syn-form-1-1"] = d["form.syn-form-1-1"]
    return d


EXPECTED_VECTOR = digest_of("vector", [1.0, -1.0, 0.5, 2.5],
                            [0.0, 0.25, 0.5, 0.75], [0, 1, 2], 7)


# ---------- minimal PNG decoder (RGBA8 only) ----------
def decode_png(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n" or len(data) > 2_000_000:
        raise ValueError("not a bounded PNG")
    pos, w, h, ended = 8, None, None, False
    idat = bytearray()
    while pos + 12 <= len(data):
        ln, typ = struct.unpack(">I4s", data[pos:pos + 8])
        end = pos + 12 + ln
        if end > len(data):
            raise ValueError("truncated chunk")
        body = data[pos + 8:end - 4]
        if zlib.crc32(typ + body) != struct.unpack(">I", data[end - 4:end])[0]:
            raise ValueError("PNG CRC mismatch")
        if pos == 8 and typ != b"IHDR":
            raise ValueError("IHDR must be first")
        if typ == b"IHDR":
            if w is not None or len(body) != 13:
                raise ValueError("invalid IHDR")
            w, h, bd, ct, cm, fm, interlace = struct.unpack(">IIBBBBB", body)
            if (w, h, bd, ct, cm, fm, interlace) != (MAT_W, MAT_H, 8, 6, 0, 0, 0):
                raise ValueError("expected 256x128 non-interlaced RGBA8")
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            if body or end != len(data):
                raise ValueError("invalid IEND or trailing data")
            ended = True
            break
        elif not typ[0] & 32:
            raise ValueError("unknown critical PNG chunk")
        pos = end
    if not ended or w is None or not idat:
        raise ValueError("incomplete PNG")
    decoder = zlib.decompressobj()
    expected_size = (w * 4 + 1) * h
    raw = decoder.decompress(bytes(idat), expected_size + 1)
    if len(raw) != expected_size or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("invalid PNG decompressed size/stream")
    stride = w * 4
    px = bytearray(w * h * 4)
    prev = bytearray(stride)
    off = 0
    for y in range(h):
        f = raw[off]
        if f not in range(5):
            raise ValueError("invalid PNG filter")
        off += 1
        row = bytearray(raw[off:off + stride])
        off += stride
        for i in range(stride):
            a = row[i - 4] if i >= 4 else 0
            b_ = prev[i]
            c = prev[i - 4] if i >= 4 else 0
            if f == 1:
                row[i] = (row[i] + a) & 0xFF
            elif f == 2:
                row[i] = (row[i] + b_) & 0xFF
            elif f == 3:
                row[i] = (row[i] + (a + b_) // 2) & 0xFF
            elif f == 4:
                p = a + b_ - c
                pa, pb, pc = abs(p - a), abs(p - b_), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b_ if pb <= pc else c)
                row[i] = (row[i] + pr) & 0xFF
        px[y * stride:(y + 1) * stride] = row
        prev = row
    return w, h, bytes(px)


def argb_at(px, w, x, y):
    i = (y * w + x) * 4
    return (px[i + 3] << 24) | (px[i] << 16) | (px[i + 1] << 8) | px[i + 2]


# Clean-run oracle: constants are an independent restatement of the fixture.
# A checksum alone never establishes that the observation is correct.
def expected_samples():
    return [f"sample[{('far-' + label) if label.startswith('edge-') else label}] "
            f"@({x},{y}) expected=0x{color:08X} actual=0x{color:08X} tol=0 match"
            for x, y, color, label in SAMPLES] + ["alpha: full-scan opaque pixels=32768/32768"]


def expected_checks():
    pairs = {}
    for a in (-1, 0, 1):
        for b in (-1, 0, 1):
            guid = f"syn-form-{a + 1}-{b + 1}"
            pairs[f"loc.cell.{a}_{b}"] = (guid, guid + (" alias" if (a, b) == (1, 1) else ""))
    probe_digest = digest_of("probe.positions", [0., 0., 1., 0., 0., 1., 1., 1.], None, None, None)
    pairs.update({
        "loc.cell.distinct": ("9 distinct formGuids", "9"),
        "loc.default.tuple": ("syn-form-1-1", "syn-form-1-1"),
        "loc.default.guidConsistent": ("defaultCell == fixture.defaultFormGuid", "syn-form-1-1 vs syn-form-1-1"),
        "loc.default.notFirstKey": ("default cell != first-key cell", "syn-form-1-1 vs syn-form-0-0"),
        "loc.tuple.normalized": ("syn-form-0-2", "syn-form-0-2"),
        "morph.keys.distinct": ("three morph keys -> three distinct forms", "syn-morph-form-00 | syn-morph-form-05 | syn-morph-form-10"),
        "morph.explicitZero.unknown": ("explicit 0 target locates; basicIdentity stays UNKNOWN", "syn-morph-form-00 basic=UNKNOWN"),
        "alias.sharedForm.blocked": ("cell (1,1) form aliased -> writable BLOCKED", "alias=true writable=BLOCKED_ALIAS"),
        "snap.externalMutationImmune": ("digest/content unchanged after input array mutation", f"digest={probe_digest[:16]}... pos[0]=0.0"),
        "snap.getterMutationImmune": ("digest/content unchanged after mutating returned array", "pos[1]=0.0"),
        "snap.digestContentDerived": ("digest recomputed from content equals stored digest", "match"),
        "snap.domainSeparation": ("identical payload in different domains -> different digest", "true"),
        "snap.domainIsolationAll": ("mutating positions input changes only its domain digest", "true"),
        "snap.drawOrderPresence": ("absent drawOrder digest != present Integer.MIN_VALUE", "true"),
        "snap.digestKnownVector": (EXPECTED_VECTOR, EXPECTED_VECTOR),
        "mat.png.sizeOpaque": ("256x128 fully opaque", "ok"),
        "mat.png.markerAsymmetric": ("right-pointing arrow pixels verified, mirror positions plain", "[" + ", ".join(s for s in expected_samples() if "marker" in s) + "]"),
        "rpt.jsonEscaping": ("reason string survives JSON escape round-trip", 'carries "quote" \\ backslash \n newline and unicode 日本語'),
    })
    return pairs


REJECTION_REASONS = dict(zip((
    "missingDim extraDim duplicateDim keyNotDeclared nan posInf signedZero "
    "cellMissing formMissing staleGeneration wrongOwner morphNullKey morphNotFound "
    "morphMissingForm morphNullFormGuid topologyOddPositions topologyIndexRange "
    "uvLengthMismatch sourceNaN uvInfinite morphDuplicateIdentity duplicateKeyInBinding "
    "bindingMinGtMax bindingKeyOutOfRange bindingDefaultOutOfRange duplicateParamId "
    "cellDuplicateDim cellMissingDim cellUndeclaredKey cellUnboundParam duplicateCell missingDefaultKey"
).split(), (
    "INCOMPLETE_TUPLE UNBOUND_DIMENSION DUPLICATE_DIMENSION KEY_NOT_DECLARED NON_FINITE NON_FINITE KEY_NOT_DECLARED "
    "CELL_NOT_FOUND FORM_MISSING STALE_GENERATION WRONG_OWNER NULL_KEY_VALUE MORPH_NOT_FOUND "
    "FORM_MISSING NULL_FORM_GUID BAD_TOPOLOGY BAD_TOPOLOGY BAD_TOPOLOGY NON_FINITE NON_FINITE "
    "DUPLICATE_MORPH_IDENTITY DUPLICATE_KEY INVALID_RANGE INVALID_RANGE INVALID_RANGE DUPLICATE_DIMENSION "
    "DUPLICATE_DIMENSION INCOMPLETE_TUPLE KEY_NOT_DECLARED UNBOUND_DIMENSION DUPLICATE_CELL MISSING_DEFAULT_KEY"
).split(), strict=True))
REJECTION_REASONS = {"rej." + k: v for k, v in REJECTION_REASONS.items()}
SCOPE = dict(sessionId="synthetic-session-t006", documentId="synthetic-doc-t006",
             modelId="synthetic-model-t006", generation=1, ownerId="synthetic-owner-t006")
HEX = re.compile(r"[0-9a-f]{64}")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def same(actual, expected, message):
    # JSON booleans must not compare equal to integer counts.
    require(json.dumps(actual, sort_keys=True, ensure_ascii=False) ==
            json.dumps(expected, sort_keys=True, ensure_ascii=False), message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def regular_bytes(path):
    for part in (path, *path.parents):
        require(not part.is_symlink(), f"symlink evidence path: {part}")
    require(path.is_file(), f"missing evidence file: {path}")
    require(path.stat().st_size <= 4_000_000, f"oversized evidence file: {path}")
    return path.read_bytes()


def verify_manifest(run, name, directory, digest):
    data = regular_bytes(run / name)
    require(hashlib.sha256(data).hexdigest() == digest, f"{name} digest mismatch")
    names = []
    for line in data.decode("ascii").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_./$-]+)", line)
        require(match is not None, f"bad manifest line in {name}")
        sha, rel = match.groups()
        require(not rel.startswith("/") and all(p not in ("", ".", "..") for p in rel.split("/")), "unsafe manifest path")
        names.append(rel)
        require(hashlib.sha256(regular_bytes(run / directory / rel)).hexdigest() == sha, f"manifest content mismatch: {rel}")
    require(names and names == sorted(set(names)), f"empty/duplicate/unsorted manifest {name}")
    disk = []
    for p in (run / directory).rglob("*"):
        require(not p.is_symlink(), "symlink in evidence inventory")
        if p.is_file():
            disk.append(p.relative_to(run / directory).as_posix())
    same(sorted(disk), names, f"{name} inventory mismatch")
    return len(names)


def verify_build(d, path):
    run = path.parent.parent
    props = {}
    for line in regular_bytes(run / "build.properties").decode("ascii").splitlines():
        k, v = line.split("=", 1)
        require(k not in props, "duplicate build property")
        props[k] = v
    keys = {"head", "gitStatusSha256", "sourceSha256", "sourceFiles", "classesSha256",
            "compilerVersion", "worktreeDirtyFingerprint", "runDirectoryId"}
    require(set(props) == keys, "build properties keys mismatch")
    require(props["runDirectoryId"] == run.name, "build run directory mismatch")
    for key in ("gitStatusSha256", "sourceSha256", "classesSha256", "worktreeDirtyFingerprint"):
        require(HEX.fullmatch(props[key]), f"invalid {key}")
    require(re.fullmatch(r"[0-9a-f]{40}|unobserved", props["head"]), "invalid captured HEAD")
    require(regular_bytes(run / "git-head.txt").decode().strip() == props["head"], "captured HEAD mismatch")
    status = regular_bytes(run / "git-status.txt")
    require(hashlib.sha256(status).hexdigest() == props["gitStatusSha256"], "captured status mismatch")
    dirty = hashlib.sha256((props["gitStatusSha256"] + "\n" + props["sourceSha256"] + "\n").encode()).hexdigest()
    require(dirty == props["worktreeDirtyFingerprint"], "dirty content identity mismatch")
    count = verify_manifest(run, "sources.sha256", "inputs", props["sourceSha256"])
    verify_manifest(run, "classes.sha256", "classes", props["classesSha256"])
    require(str(count) == props["sourceFiles"], "source count mismatch")
    build = d["build"]
    require(type(build) is dict, "build must be object")
    for key in ("head", "gitStatusSha256", "sourceSha256", "classesSha256", "compilerVersion", "worktreeDirtyFingerprint"):
        same(build[key], props[key], f"report build.{key} differs from captured input")
    same(build["sourceFiles"], count, "report sourceFiles mismatch")
    same(build["executionId"], run.name + "/" + path.parent.name, "executionId mismatch")
    require(type(build["javaVersion"]) is str and re.fullmatch(r"17\.[0-9A-Za-z.+_-]+", build["javaVersion"]), "invalid Java version")
    require(re.fullmatch(r"17\.[0-9A-Za-z.+_-]+", props["compilerVersion"]), "invalid compiler version")
    argv = build["argv"]
    require(type(argv) is list and len(argv) == 2 and argv[0] == "--out" and type(argv[1]) is str, "invalid clean-run argv")
    # Full run bundles may be relocated for readonly review; original argv is retained.
    out = Path(argv[1])
    require(out.is_absolute() and out.parts[-2:] == (run.name, path.parent.name)
            and not any(x in (".", "..") for x in argv[1].split("/")[1:]), "argv/output identity mismatch")
    require(set(build) == {"head", "worktreeDirtyFingerprint", "javaVersion", "argv",
            "classesSha256", "sourceSha256", "sourceFiles", "compilerVersion", "gitStatusSha256", "executionId"}, "build keys mismatch")


def payload_sha256(d):
    data = bytearray()
    def fields(*values):
        for value in values:
            encoded = None if value is None else str(value).encode("utf-8")
            data.extend(struct.pack(">i", -1 if encoded is None else len(encoded)))
            if encoded is not None:
                data.extend(encoded)
    off, native = d["offlineSelfcheck"], d["native"]
    for group in (off["checks"], off["rejectionChecks"], native["checks"], native["blocked"]):
        for c in group:
            fields("CHECK", c["id"], c["expected"], c["actual"], c["status"],
                   c["evidenceKind"], c["requiredEvidenceKind"],
                   "true" if c["evidenceProvided"] else "false", c["reason"],
                   c["identitySource"], c.get("operationStatus"))
    for key in sorted(d["domainDigests"]):
        fields("DIGEST", key, d["domainDigests"][key])
    for sample in d["materialSamples"]:
        fields("SAMPLE", sample)
    scope = d["scope"]
    fields("SCOPE", scope["sessionId"], scope["documentId"], scope["modelId"], scope["generation"], scope["ownerId"])
    fields("MATERIAL", d["materialSha256"])
    return hashlib.sha256(data).hexdigest()


def run_id(d):
    b = d["build"]
    data = b["executionId"] + "\n" + d["payloadSha256"] + "\n" + b["sourceSha256"] + "\n" + b["classesSha256"] + "\n"
    return "t006-offline-" + hashlib.sha256(data.encode()).hexdigest()


def verify(d, path):
    require(type(d) is dict, "root must be object")
    require(set(d) == {"schemaVersion", "runId", "payloadSha256", "hostExecuted", "hostIdentity", "build", "scope",
            "materialSha256", "offlineSelfcheck", "native", "domainDigests", "materialSamples", "exitCodeRule"}, "root fields mismatch (clean reports only)")
    same(d["schemaVersion"], "t006-offline-r3", "wrong schema")
    same(d["hostExecuted"], False, "hostExecuted must be false")
    same(d["hostIdentity"], dict(observed=False, cubismVersion=None, jarSha256=None,
         reason="offline entrypoint; host not executed"), "unobserved host identity mismatch")
    same(d["scope"], SCOPE, "fixture scope mismatch")
    verify_build(d, path)
    expected = expected_checks()
    require(set(expected) == REQUIRED_CHECKS and set(REJECTION_REASONS) == REQUIRED_REJECTIONS, "internal oracle IDs mismatch")
    seen = set()
    off, native = d["offlineSelfcheck"], d["native"]
    require(type(off) is dict and set(off) == {"checks", "rejectionChecks", "summary"}, "offline section malformed")
    require(type(native) is dict and set(native) == {"checks", "blocked", "summary"}, "native section malformed")
    for group, required, status, observed, rejection in (
            (off["checks"], REQUIRED_CHECKS, "PASS", True, False),
            (off["rejectionChecks"], REQUIRED_REJECTIONS, "PASS", True, True),
            (native["checks"], REQUIRED_NATIVE, "NOT_RUN", False, False),
            (native["blocked"], REQUIRED_SEAM, "BLOCKED", False, False)):
        require(type(group) is list, "check group must be array")
        group_ids = set()
        for c in group:
            require(type(c) is dict, "check must be object")
            keys = {"id", "expected", "actual", "status", "evidenceKind", "requiredEvidenceKind", "evidenceProvided", "reason", "identitySource"}
            require(set(c) == keys | ({"operationStatus"} if rejection else set()), "check fields mismatch")
            for key in ("id", "expected", "actual", "status", "reason", "identitySource"):
                require(type(c[key]) is str and c[key], f"invalid check {key}")
            key = c["id"]
            require(key in required and key not in seen, f"unexpected/duplicate ID {key}")
            seen.add(key); group_ids.add(key)
            same(c["status"], status, f"{key}: status mismatch")
            same(c["evidenceKind"], "OFFLINE_FIXTURE" if observed else None, f"{key}: evidenceKind mismatch")
            same(c["requiredEvidenceKind"], "OFFLINE_FIXTURE" if observed else "EXACT_HOST", f"{key}: requiredEvidenceKind mismatch")
            same(c["evidenceProvided"], observed, f"{key}: evidenceProvided mismatch")
            same(c["identitySource"], "synthetic-fixture", f"{key}: identity source mismatch")
            if rejection:
                reason = REJECTION_REASONS[key]
                same(c["expected"], "operation rejected with reason=" + reason, f"{key}: wrong required rejection")
                same(c["actual"], "rejected with reason=" + reason, f"{key}: wrong observed rejection")
                same(c["operationStatus"], "REJECTED", f"{key}: operation not rejected")
            elif observed:
                same([c["expected"], c["actual"]], list(expected[key]), f"{key}: fixed semantic oracle mismatch")
                if key.startswith("loc.cell.") and key != "loc.cell.distinct":
                    writable = "BLOCKED_ALIAS" if key == "loc.cell.1_1" else "BLOCKED_HOST_UNVERIFIED"
                    same(c["reason"], "cell located; writable=" + writable, f"{key}: writable semantics mismatch")
            else:
                same(c["actual"], status, f"{key}: unobserved actual mismatch")
                same(c["expected"], "host-evaluated" if status == "NOT_RUN" else "sdk read seam available", f"{key}: expected mismatch")
        same(sorted(group_ids), sorted(required), "required check IDs missing")
    same(off["summary"], {"pass": len(REQUIRED_CHECKS) + len(REQUIRED_REJECTIONS), "fail": 0, "selfcheckStatus": "PASS"}, "offline summary mismatch")
    same(native["summary"], {"pass": 0, "fail": 0, "blocked": len(REQUIRED_SEAM), "notRun": len(REQUIRED_NATIVE), "executed": False, "verdict": "NOT_EVALUATED"}, "native summary mismatch")
    same(d["domainDigests"], expected_digests(), "domain digest fixed oracle mismatch")
    same(d["materialSamples"], expected_samples(), "material sample oracle mismatch")
    blob = regular_bytes(path.parent / "material.png")
    w, h, px = decode_png(blob)
    require(all(px[i] == 255 for i in range(3, len(px), 4)), "material not opaque")
    for x, y, want, label in SAMPLES:
        require(argb_at(px, w, x, y) == want, f"PNG sample mismatch: {label}")
    same(d["materialSha256"], hashlib.sha256(blob).hexdigest(), "material SHA mismatch")
    same(d["payloadSha256"], payload_sha256(d), "payload SHA mismatch")
    same(d["runId"], run_id(d), "runId mismatch")
    same(d["exitCodeRule"], "0 = all offline checks as expected; 2 = at least one FAIL; 3 = usage/io error", "exit rule mismatch")


def main(path):
    try:
        p = Path(path).absolute()
        d = json.loads(regular_bytes(p).decode("utf-8"), object_pairs_hook=unique_object,
                       parse_constant=lambda s: (_ for _ in ()).throw(ValueError("invalid JSON constant " + s)))
        verify(d, p)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError, struct.error, zlib.error) as e:
        print("verify_report: FAIL:", e, file=sys.stderr)
        return 1
    print("verify_report: PASS (clean offline evidence only; native NOT_RUN/BLOCKED)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: verify_report.py <report.json>", file=sys.stderr)
        sys.exit(1)
    sys.exit(main(sys.argv[1]))
