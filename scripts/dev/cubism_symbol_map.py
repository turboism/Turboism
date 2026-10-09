#!/usr/bin/env python3
"""Cross-version obfuscated symbol mapping for Cubism Editor jars (spec-less dev tool).

Live2D re-obfuscates ``Live2D*.jar`` between releases, so an obfuscated owner,
method or field name that verifies on one version may not exist on the next
(e.g. the startup-suppression splash factory ``CECubismEditorApp.e()`` is
``c()`` on 5.0.00-5.0.02). This tool builds a class/member correspondence
between any two versions from signals that survive re-obfuscation:

- non-obfuscated name anchors (stable readable internal/member names),
- per-class string-constant anchors (rare and pairwise-unique literals),
- member shapes: descriptors whose ``Lcom/live2d/...;`` tokens are rewritten
  through the partial class map (unmapped tokens degrade to wildcards),
- exact method code hashes and operand-masked opcode signatures,
- hierarchy (super/interfaces, InnerClasses outer) and call-graph neighbours
  mapped through the current correspondence.

A pair is only "resolved" when the best candidate is unique: score above
``RESOLVE_MIN`` and ahead of the runner-up by ``RESOLVE_MARGIN``. Anything
below that stays unmapped rather than guessing.

Public API for the record-extrapolation hook:

    resolve(selector: dict, from_version: str, to_version: str) -> dict | None

``selector`` is a verification-record selector (kind class/method/constructor/
field/inherits). The result is the same selector with ownerInternalName,
memberName and descriptor rewritten into ``to_version`` symbols, ``status``
removed. ``None`` means "no unique high-confidence counterpart".

CLI:
    cubism_symbol_map.py --from 5.2.03 --to 5.0.02 \
        --class com/live2d/cubism/CECubismEditorApp --method 'e()'
    cubism_symbol_map.py --from 5.3.03 --to 5.3.04 \
        --emit build/symbol-maps/5.3.03_to_5.3.04.json

Parsed jar models cache to /tmp keyed by jar sha-256; pair maps cache to
/tmp per ordered version pair. ``--emit`` writes the committed-artifact JSON.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pickle
import re
import sys
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cubism_reachability_clusters as rc  # noqa: E402  (cp/bytecode primitives)

ROOT = Path(__file__).resolve().parents[2]
LIVE2D_PREFIX = "com/live2d/"
MODEL_CACHE_DIR = Path("/tmp/cubism-symbol-models")
MAP_CACHE_DIR = Path("/tmp/cubism-symbol-maps")

# Resolution gates: a candidate pair is resolved only when the top score
# clears RESOLVE_MIN and beats the runner-up by RESOLVE_MARGIN.
RESOLVE_MIN = 0.72
RESOLVE_MARGIN = 0.12
MEMBER_RESOLVE_MIN = 0.70
MEMBER_RESOLVE_MARGIN = 0.10
MAX_ROUNDS = 8
# Strings shared by more classes than this carry no anchor weight.
STRING_DF_CAP = 32

W_NAME = 0.18
W_STRING = 0.27
W_MEMBER = 0.30
W_HIER = 0.10
W_CALL = 0.15
UNIQUE_STRING_BONUS = 0.20

# Obfuscated leaves are short lowercase tokens or single uppercase letters
# (ProGuard emits both: "bf", "ab", but also "X", "V", "Q"); real class names
# are longer or mixed-case ("CECubismEditorApp", "ACUndoable", "Map").
_OBF_SIMPLE = re.compile(r"^(?:[a-z]{1,3}|[A-Z])\d*$")
_OBF_MEMBER = re.compile(r"^[a-z]{1,2}\d*$")
_TYPE_TOKEN = re.compile(r"L([^;]+);")


def _simple_name(internal: str) -> str:
    return internal.rsplit("/", 1)[-1]


def _leaf_name(internal: str) -> str:
    return _simple_name(internal).rsplit("$", 1)[-1]


def is_obfuscated_class(internal: str) -> bool:
    """Heuristic: every package/leaf segment is a short lowercase token."""
    if "[" in internal:
        return False
    leaf = _leaf_name(internal)
    if leaf in ("<init>", "<clinit>"):
        return False
    return bool(_OBF_SIMPLE.match(leaf))


def is_obfuscated_member(name: str) -> bool:
    return bool(_OBF_MEMBER.match(name))


# ---------------------------------------------------------------------------
# Jar model
# ---------------------------------------------------------------------------


@dataclass
class MethodInfo:
    name: str
    desc: str
    access: int
    code_sha: bytes | None
    opsig: bytes
    invokes: tuple
    fieldrefs: tuple
    classrefs: tuple


@dataclass
class FieldInfo:
    name: str
    desc: str
    access: int
    const: bytes | None


@dataclass
class ClassInfo:
    name: str
    access: int
    super_name: str | None
    interfaces: tuple
    outer: str | None
    methods: dict = field(default_factory=dict)   # (name, desc) -> MethodInfo
    fields: dict = field(default_factory=dict)    # (name, desc) -> FieldInfo
    strings: frozenset = frozenset()
    # Index-time precomputations (filled by _index_model).
    shapes: frozenset = frozenset()      # wildcard member-shape signature
    tok_members: tuple = ()              # (kind, acc, desc) with live2d tokens
    anc: frozenset = frozenset()         # super + outer + interfaces
    nei: tuple = ()                      # referenced owner names (multiset)
    str_w: float = 0.0                   # sum of 1/df over strings
    shape_hash: bytes = b""


@dataclass
class JarModel:
    version: str
    classes: dict = field(default_factory=dict)   # name -> ClassInfo
    string_df: Counter = field(default_factory=Counter)
    shape_buckets: dict = field(default_factory=dict)  # shape-hash -> [name]
    callers: dict = field(default_factory=dict)   # referenced -> {referrers}
    subclasses: dict = field(default_factory=dict)  # ancestor -> {descendants}


def _scan_code(cp, code: bytes):
    """One pass over bytecode: opcode signature plus invoke/field/class edges."""
    ops = bytearray()
    inv, fld, cls = [], [], []
    i, n = 0, len(code)
    while i < n:
        op = code[i]
        ops.append(op)
        i += 1
        if op == 0xAA:  # tableswitch
            i += (4 - (i % 4)) % 4
            if i + 12 > n:
                break
            lo, hi = rc.u4(code, i + 4), rc.u4(code, i + 8)
            i += 12 + 4 * max(0, hi - lo + 1)
            continue
        if op == 0xAB:  # lookupswitch
            i += (4 - (i % 4)) % 4
            if i + 8 > n:
                break
            i += 8 + 8 * rc.u4(code, i + 4)
            continue
        if op == 0xC4:  # wide
            i += 6 if code[i] == 0x84 else 2
            continue
        ldc_len = rc.LDC_OPS.get(op)
        if ldc_len:
            idx = code[i] if ldc_len == 1 else rc.u2(code, i)
            cn = rc.cp_cname(cp, idx)
            if cn and cn.startswith(LIVE2D_PREFIX):
                cls.append(cn)
            i += ldc_len
            continue
        if op in rc.REF_OPS:
            idx = rc.u2(code, i)
            m = rc.cp_member(cp, idx)
            if m:
                owner, name, desc = m
                if owner and owner.startswith(LIVE2D_PREFIX):
                    if op in (0xB2, 0xB3, 0xB4, 0xB5):
                        fld.append((owner, name, desc))
                    elif op in (0xB6, 0xB7, 0xB8, 0xB9, 0xBA):
                        inv.append((owner, name, desc, op))
                    else:
                        cls.append(owner)
            elif op in (0xBB, 0xBD, 0xC0, 0xC1, 0xC5):
                cn = rc.cp_cname(cp, idx)
                if cn and cn.startswith(LIVE2D_PREFIX):
                    cls.append(cn)
            i += rc.OPLEN[op]
            continue
        i += rc.OPLEN[op]
    return bytes(ops), tuple(inv), tuple(fld), tuple(cls)


def parse_class(data: bytes, fallback_name: str) -> ClassInfo:
    """Richer per-class record than cubism_reachability_clusters.parse_class:
    keeps access flags, string constants, InnerClasses outer and opcode sigs."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise rc.BadClass()
    cp_count = rc.u2(data, 8)
    cp = [None] * cp_count
    str_idx: list[int] = []
    i = 10
    idx = 1
    while idx < cp_count:
        tag = data[i]
        i += 1
        if tag == 1:
            ln = rc.u2(data, i)
            cp[idx] = ("utf8", data[i + 2 : i + 2 + ln])
            i += 2 + ln
        elif tag == 7:
            cp[idx] = ("class", rc.u2(data, i))
            i += 2
        elif tag == 8:
            cp[idx] = ("str", rc.u2(data, i))
            str_idx.append(idx)
            i += 2
        elif tag in (9, 10, 11):
            cp[idx] = ("member", rc.u2(data, i), rc.u2(data, i + 2))
            i += 4
        elif tag == 12:
            cp[idx] = ("nat", rc.u2(data, i), rc.u2(data, i + 2))
            i += 4
        elif tag == 15:
            cp[idx] = ("mh", rc.u2(data, i + 1))
            i += 3
        elif tag == 16:
            i += 2
        elif tag in (3, 4, 17, 18):
            i += 4
        elif tag in (19, 20):
            i += 2
        elif tag in (5, 6):
            i += 8
            idx += 1
        else:
            raise rc.BadClass()
        idx += 1

    strings = frozenset(
        s for e in (cp[k] for k in str_idx)
        if e and (s := rc.cp_utf(cp, e[1])) is not None and len(s) >= 3
    )

    access = rc.u2(data, i)
    this_name = rc.cp_cname(cp, rc.u2(data, i + 2)) or fallback_name
    sup = rc.cp_cname(cp, rc.u2(data, i + 4))
    i += 6
    ifc = rc.u2(data, i)
    i += 2
    ifs = tuple(
        filter(None, (rc.cp_cname(cp, rc.u2(data, i + 2 * k)) for k in range(ifc)))
    )
    i += 2 * ifc

    fields: dict = {}
    fc = rc.u2(data, i)
    i += 2
    for _ in range(fc):
        faccess = rc.u2(data, i)
        fn, fd = rc.cp_utf(cp, rc.u2(data, i + 2)), rc.cp_utf(cp, rc.u2(data, i + 4))
        i += 6
        ac = rc.u2(data, i)
        i += 2
        const = None
        for _ in range(ac):
            an = rc.cp_utf(cp, rc.u2(data, i))
            i += 2
            ln = rc.u4(data, i)
            i += 4
            if an == "ConstantValue":
                ce = cp[rc.u2(data, i)]
                if ce and ce[0] == "utf8":
                    const = ce[1]
                elif ce and ce[0] == "str":
                    const = cp[ce[1]][1] if cp[ce[1]] else None
                else:
                    const = data[i : i + ln]
            i += ln
        if fn is not None:
            fields[(fn, fd)] = FieldInfo(fn, fd or "", faccess, const)

    methods: dict = {}
    mc = rc.u2(data, i)
    i += 2
    for _ in range(mc):
        maccess = rc.u2(data, i)
        mn, md = rc.cp_utf(cp, rc.u2(data, i + 2)), rc.cp_utf(cp, rc.u2(data, i + 4))
        i += 6
        ac = rc.u2(data, i)
        i += 2
        code = None
        for _ in range(ac):
            an = rc.cp_utf(cp, rc.u2(data, i))
            i += 2
            ln = rc.u4(data, i)
            i += 4
            if an == "Code":
                cl = rc.u4(data, i + 4)
                code = data[i + 8 : i + 8 + cl]
            i += ln
        if mn is not None:
            if code is not None:
                opsig, inv, fld, cls = _scan_code(cp, code)
                code_sha = hashlib.sha256(code).digest()
            else:
                opsig, inv, fld, cls = b"", (), (), ()
                code_sha = None
            methods[(mn, md)] = MethodInfo(
                mn, md or "", maccess, code_sha, opsig, inv, fld, cls
            )

    outer = None
    ac = rc.u2(data, i)
    i += 2
    for _ in range(ac):
        an = rc.cp_utf(cp, rc.u2(data, i))
        i += 2
        ln = rc.u4(data, i)
        i += 4
        if an == "InnerClasses" and ln >= 2:
            nic = rc.u2(data, i)
            for k in range(nic):
                base = i + 2 + k * 8
                inner = rc.cp_cname(cp, rc.u2(data, base))
                if inner == this_name:
                    outer = rc.cp_cname(cp, rc.u2(data, base + 2))
        i += ln

    return ClassInfo(this_name, access, sup, ifs, outer, methods, fields, strings)


def build_model(version: str, ref: Path) -> JarModel:
    """Parse every Live2D-shipped jar of one version into a JarModel."""
    model = JarModel(version)
    for jar in sorted((ref / version).glob("Live2D*.jar")):
        with zipfile.ZipFile(jar) as zf:
            for info in zf.infolist():
                if not info.filename.endswith(".class"):
                    continue
                name = info.filename[: -len(".class")]
                if not name.startswith(LIVE2D_PREFIX) or name in model.classes:
                    continue
                try:
                    model.classes[name] = parse_class(
                        zf.read(info.filename), name
                    )
                except Exception:
                    pass
    _index_model(model)
    return model


def _shape_desc(desc: str) -> str:
    """Descriptor with every Live2D type token wildcarded (pre-map shape)."""
    return _TYPE_TOKEN.sub(
        lambda m: "L?;" if m.group(1).startswith(LIVE2D_PREFIX) else m.group(0),
        desc or "",
    )


def _index_model(model: JarModel) -> None:
    for info in model.classes.values():
        shapes = set()
        toks = []
        for (n, d), m in info.methods.items():
            if _TYPE_TOKEN.search(d or "") and any(
                t.startswith(LIVE2D_PREFIX) for t in _desc_tokens(d)
            ):
                toks.append(("m", m.access & 0x1107, d))
            else:
                shapes.add(("m", m.access & 0x1107, d))
            if not is_obfuscated_member(n):
                shapes.add(("mn", n))
        for (n, d), f in info.fields.items():
            if any(t.startswith(LIVE2D_PREFIX) for t in _desc_tokens(d)):
                toks.append(("f", f.access & 0x1107, d))
            else:
                shapes.add(("f", f.access & 0x1107, d))
            if not is_obfuscated_member(n):
                shapes.add(("fn", n))
            if f.const:
                shapes.add(("fc", _shape_desc(d), f.const[:64]))
        info.shapes = frozenset(shapes)
        info.tok_members = tuple(toks)
        info.anc = frozenset(
            x for x in (info.super_name, info.outer, *info.interfaces) if x
        )
        nei = Counter()
        for m in info.methods.values():
            for o, _n, _d, _op in m.invokes:
                nei[o] += 1
            for o, _n, _d in m.fieldrefs:
                nei[o] += 1
            for o in m.classrefs:
                nei[o] += 1
        info.nei = tuple(nei.items())
        for s in info.strings:
            model.string_df[s] = min(model.string_df[s] + 1, STRING_DF_CAP + 1)
        for anc in info.anc:
            model.subclasses.setdefault(anc, set()).add(info.name)
        for o in nei:
            model.callers.setdefault(o, set()).add(info.name)
    # Second pass once string_df is final: weights, shape buckets.
    for name, info in model.classes.items():
        info.str_w = sum(1.0 / model.string_df[s] for s in info.strings)
        info.shape_hash = hashlib.sha256(
            b"\0".join(sorted(repr(x).encode() for x in info.shapes))
            + b"\xff"
            + b"\0".join(sorted(repr(x).encode() for x in info.tok_members))
        ).digest()
        model.shape_buckets.setdefault(info.shape_hash, []).append(name)


# ---------------------------------------------------------------------------
# Loading and caching
# ---------------------------------------------------------------------------


def cubism_ref_dir() -> Path:
    return rc.cubism_ref_dir()


def _model_cache_key(version: str, ref: Path) -> str:
    h = hashlib.sha256(version.encode())
    for jar in sorted((ref / version).glob("Live2D*.jar")):
        h.update(jar.name.encode())
        h.update(hashlib.sha256(jar.read_bytes()).digest())
    return h.hexdigest()[:24]


_MODELS: dict[str, JarModel] = {}


def load_model(version: str, ref: Path | None = None) -> JarModel:
    if version in _MODELS:
        return _MODELS[version]
    ref = ref or cubism_ref_dir()
    MODEL_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache = MODEL_CACHE_DIR / f"{version}-{_model_cache_key(version, ref)}.pkl"
    model = None
    if cache.exists():
        try:
            model = pickle.loads(cache.read_bytes())
        except Exception:
            model = None
    if model is None:
        model = build_model(version, ref)
        try:
            cache.write_bytes(pickle.dumps(model, protocol=4))
        except OSError:
            pass
    _MODELS[version] = model
    return model


# ---------------------------------------------------------------------------
# Descriptor rewriting through a partial class map
# ---------------------------------------------------------------------------


def _desc_tokens(desc: str) -> list[str]:
    return _TYPE_TOKEN.findall(desc or "")


def rewrite_desc_source(desc: str, class_map: dict) -> str:
    """Rewrite source-side descriptor tokens: mapped -> target name, else '?'."""
    def sub(m):
        t = m.group(1)
        if t.startswith(LIVE2D_PREFIX):
            return "L" + class_map.get(t, "?") + ";"
        return m.group(0)

    return _TYPE_TOKEN.sub(sub, desc or "")


def rewrite_desc_target(desc: str, mapped_targets: set) -> str:
    """Rewrite target-side descriptor tokens: reachable targets stay literal,
    other Live2D tokens degrade to '?' (source may map there later)."""
    def sub(m):
        t = m.group(1)
        if t.startswith(LIVE2D_PREFIX) and t not in mapped_targets:
            return "L?;"
        return m.group(0)

    return _TYPE_TOKEN.sub(sub, desc or "")


def _jaccard(a, b) -> float:
    if not a and not b:
        return 0.0
    if isinstance(a, frozenset) or isinstance(a, set):
        inter = len(a & b)
        return inter / (len(a | b)) if (a or b) else 0.0
    ca, cb = Counter(a), Counter(b)
    inter = sum((ca & cb).values())
    union = sum((ca | cb).values())
    return inter / union if union else 0.0


# ---------------------------------------------------------------------------
# Pairwise matching
# ---------------------------------------------------------------------------


@dataclass
class PairMap:
    from_version: str
    to_version: str
    classes: dict = field(default_factory=dict)     # src -> dst
    class_conf: dict = field(default_factory=dict)  # src -> float
    members: dict = field(default_factory=dict)     # (src_owner,name,desc) -> (dst_owner,dst_name,dst_desc)
    member_conf: dict = field(default_factory=dict)
    unresolved: set = field(default_factory=set)    # src classes with no unique match


def _string_score(a: ClassInfo, b: ClassInfo, modelA: JarModel, modelB: JarModel):
    """Weighted Jaccard over class string constants; also reports whether the
    pair shares at least one string unique to both jars (decisive anchor)."""
    if not a.strings or not b.strings:
        return 0.0, False
    small, large, small_model = (
        (a, b, modelA) if len(a.strings) <= len(b.strings) else (b, a, modelB)
    )
    shared_unique = False
    num = 0.0
    for s in small.strings:
        if s in large.strings:
            w = 1.0 / small_model.string_df.get(s, STRING_DF_CAP + 1)
            num += w
            if (
                modelA.string_df.get(s, 0) == 1
                and modelB.string_df.get(s, 0) == 1
                and len(s) >= 6
            ):
                shared_unique = True
    den = a.str_w + b.str_w - num
    return (num / den if den > 0 else 0.0), shared_unique


def _tok_overlap(a: ClassInfo, b: ClassInfo, class_map, mapped_targets):
    """Jaccard over descriptor-bearing members whose Live2D tokens are rewritten
    through the current map (source side) or kept when already a mapped target."""
    if not a.tok_members or not b.tok_members:
        return 0.0
    sa = {
        (k, acc, rewrite_desc_source(d, class_map)) for k, acc, d in a.tok_members
    }
    sb = {
        (k, acc, rewrite_desc_target(d, mapped_targets))
        for k, acc, d in b.tok_members
    }
    return _jaccard(sa, sb)


def _score_pair(a: ClassInfo, b: ClassInfo, modelA, modelB, class_map, mapped_targets):
    member = _jaccard(a.shapes, b.shapes)
    # Cheap prefilter: unrelated obfuscated classes score ~0 on every signal;
    # skip the string/map-aware work for them.
    name = 0.0
    if a.name == b.name:
        name = 1.0 if not is_obfuscated_class(a.name) else 0.4
    elif _simple_name(a.name) == _simple_name(b.name) and not is_obfuscated_class(a.name):
        name = 0.5
    if member < 0.05 and name == 0.0 and not (a.strings & b.strings):
        return 0.0, False

    string, shared_unique = _string_score(a, b, modelA, modelB)
    member = max(member, 0.85 * member + 0.15 * _tok_overlap(a, b, class_map, mapped_targets))

    a_anc = {class_map.get(x, x) for x in a.anc}
    hier = _jaccard(a_anc, b.anc) if (a_anc or b.anc) else 0.0

    a_nei = Counter(
        class_map[o] for o, c in a.nei if o in class_map for _ in range(c)
    )
    b_nei = Counter(
        o for o, c in b.nei if o in mapped_targets for _ in range(c)
    )
    call = _jaccard(a_nei, b_nei) if (a_nei or b_nei) else 0.0

    score = (
        W_NAME * name
        + W_STRING * string
        + W_MEMBER * member
        + W_HIER * hier
        + W_CALL * call
        + (UNIQUE_STRING_BONUS if shared_unique else 0.0)
    )
    return min(score, 1.0), shared_unique


def build_pair_map(modelA: JarModel, modelB: JarModel) -> PairMap:
    pm = PairMap(modelA.version, modelB.version)
    class_map: dict[str, str] = {}

    def commit(src, dst, conf):
        class_map[src] = dst
        pm.class_conf[src] = conf

    # Seed 1: identical internal names that are not obfuscated tokens. Obf
    # same-name classes are NOT seeded — renames reshuffle short tokens —
    # they flow through normal candidate scoring where the name still helps.
    for name, a in modelA.classes.items():
        b = modelB.classes.get(name)
        if b is None or is_obfuscated_class(name):
            continue
        member = _jaccard(a.shapes, b.shapes)
        if member >= 0.20:
            commit(name, name, min(1.0, 0.80 + member * 0.2))

    # Seed 2: pairwise-unique string anchors.
    by_stringA = defaultdict(list)
    by_stringB = defaultdict(list)
    for n, c in modelA.classes.items():
        for s in c.strings:
            if modelA.string_df[s] == 1:
                by_stringA[s].append(n)
    for n, c in modelB.classes.items():
        for s in c.strings:
            if modelB.string_df[s] == 1:
                by_stringB[s].append(n)
    anchor_pairs = Counter()
    for s, a_names in by_stringA.items():
        if len(a_names) != 1 or len(s) < 6:
            continue
        b_names = by_stringB.get(s)
        if b_names and len(b_names) == 1:
            anchor_pairs[(a_names[0], b_names[0])] += 1
    for (a_name, b_name), cnt in anchor_pairs.items():
        if a_name in class_map:
            continue
        score, _u = _score_pair(
            modelA.classes[a_name], modelB.classes[b_name],
            modelA, modelB, class_map, set(class_map.values()),
        )
        if cnt >= 2 or score >= RESOLVE_MIN:
            commit(a_name, b_name, min(1.0, score + 0.1 * cnt))

    # Seed 3: classes whose full member signature is identical AND the bucket
    # is unique on both sides — a pure rename with untouched members.
    for sha, a_names in modelA.shape_buckets.items():
        if len(a_names) != 1:
            continue
        b_names = modelB.shape_buckets.get(sha)
        if not b_names or len(b_names) != 1:
            continue
        a_name, b_name = a_names[0], b_names[0]
        if a_name in class_map or b_name in class_map.values():
            continue
        a = modelA.classes[a_name]
        n_members = len(a.shapes) + len(a.tok_members)
        if n_members >= 4:
            # Identical non-trivial signature, unique on both sides: a pure
            # rename with untouched members resolves directly.
            commit(a_name, b_name, 0.85)
            continue
        score, _u = _score_pair(
            a, modelB.classes[b_name],
            modelA, modelB, class_map, set(class_map.values()),
        )
        # Trivial signatures still need corroborating signals.
        if score >= RESOLVE_MIN:
            commit(a_name, b_name, score)

    # Iterative rounds: candidate generation + mutual-best acceptance.
    for _round in range(MAX_ROUNDS):
        unresolved_A = [n for n in modelA.classes if n not in class_map]
        if not unresolved_A:
            break
        mapped_targets = set(class_map.values())
        unresolved_B = [n for n in modelB.classes if n not in mapped_targets]
        unresolved_B_set = set(unresolved_B)

        # Reverse index over B: rare strings, exact shape buckets, names.
        str_indexB = defaultdict(list)
        for n in unresolved_B:
            for s in modelB.classes[n].strings:
                if modelB.string_df[s] <= 8:
                    str_indexB[s].append(n)
        shape_indexB = defaultdict(list)
        for n in unresolved_B:
            shape_indexB[modelB.classes[n].shape_hash].append(n)
        name_indexB = defaultdict(list)
        for n in unresolved_B:
            name_indexB[n].append(n)
            name_indexB[_simple_name(n)].append(n)

        best_for_a: dict[str, tuple] = {}
        for a_name in unresolved_A:
            a = modelA.classes[a_name]
            cand: set[str] = set()
            for s in a.strings:
                if modelA.string_df.get(s, 99) <= 8:
                    cand.update(str_indexB.get(s, ()))
            cand.update(shape_indexB.get(a.shape_hash, ()))
            cand.update(name_indexB.get(a_name, ()))
            cand.update(name_indexB.get(_simple_name(a_name), ()))
            # Neighbour-propagation: mapped neighbours point at candidates.
            for o, _c in a.nei:
                if o in class_map:
                    cand.update(modelB.callers.get(class_map[o], ()))
            for r in modelA.callers.get(a_name, ()):
                if r in class_map:
                    cand.update(modelB.callers.get(class_map[r], ()))
                    tgt = modelB.classes.get(class_map[r])
                    if tgt:
                        for m in tgt.methods.values():
                            cand.update(o for o, _n, _d, _op in m.invokes)
                            cand.update(m.classrefs)
            for anc in a.anc:
                if anc in class_map:
                    cand.update(modelB.subclasses.get(class_map[anc], ()))
            for sub in modelA.subclasses.get(a_name, ()):
                if sub in class_map:
                    tgt = modelB.classes.get(class_map[sub])
                    if tgt:
                        cand.update(tgt.anc)
            cand &= unresolved_B_set
            if not cand:
                continue
            scored = sorted(
                (
                    (
                        _score_pair(
                            a, modelB.classes[b_name],
                            modelA, modelB, class_map, mapped_targets,
                        )[0],
                        b_name,
                    )
                    for b_name in cand
                ),
                reverse=True,
            )
            top = scored[0]
            second = scored[1][0] if len(scored) > 1 else 0.0
            if top[0] >= RESOLVE_MIN and top[0] - second >= RESOLVE_MARGIN:
                prev = best_for_a.get(a_name)
                if prev is None or top[0] > prev[0]:
                    best_for_a[a_name] = top

        # Mutual-best acceptance keeps merges one-to-one.
        best_for_b: dict[str, tuple] = {}
        for a_name, (score, b_name) in best_for_a.items():
            prev = best_for_b.get(b_name)
            if prev is None or score > prev[0]:
                best_for_b[b_name] = (score, a_name)
        gained = 0
        for b_name, (score, a_name) in best_for_b.items():
            if best_for_a.get(a_name, (None, None))[1] == b_name:
                commit(a_name, b_name, score)
                gained += 1
        if not gained:
            break

    pm.classes = dict(class_map)
    pm.unresolved = set(modelA.classes) - set(class_map)
    _match_members(pm, modelA, modelB)
    return pm


def _match_members(pm: PairMap, modelA: JarModel, modelB: JarModel) -> None:
    mapped_targets = set(pm.classes.values())
    for a_name, b_name in pm.classes.items():
        a, b = modelA.classes[a_name], modelB.classes[b_name]

        def resolve_pool(pool_a, pool_b, kind):
            for (n, d), mi in pool_a.items():
                src_rd = rewrite_desc_source(d, pm.classes)
                scored = []
                n_desc_eq = 0
                for (tn, td), tmi in pool_b.items():
                    tgt_rd = rewrite_desc_target(td, mapped_targets)
                    desc_eq = src_rd == tgt_rd
                    n_desc_eq += desc_eq
                    code_eq = (
                        kind == "m"
                        and mi.code_sha is not None
                        and mi.code_sha == tmi.code_sha
                    )
                    ops_eq = bool(
                        kind == "m" and mi.opsig and mi.opsig == tmi.opsig
                    )
                    name_eq = n == tn
                    name_pts = 1.0 if (name_eq and not is_obfuscated_member(n)) else (
                        0.4 if name_eq else 0.0
                    )
                    const_eq = (
                        kind == "f"
                        and mi.const is not None
                        and mi.const == tmi.const
                    )
                    s = (
                        0.45 * desc_eq
                        + 0.25 * (code_eq or const_eq)
                        + 0.15 * ops_eq
                        + 0.10 * name_pts
                        + 0.05 * (mi.access == tmi.access)
                    )
                    if s > 0:
                        scored.append((s, desc_eq, tn, td))
                if not scored:
                    continue
                scored.sort(reverse=True)
                top_s, top_desc_eq, tn, td = scored[0]
                if top_desc_eq and n_desc_eq == 1:
                    # The rewritten descriptor matches exactly one member on
                    # the target side — overloads would have matched too, so
                    # uniqueness itself is strong evidence for a rename.
                    top_s += 0.25
                second = scored[1][0] if len(scored) > 1 else 0.0
                if top_s >= MEMBER_RESOLVE_MIN and top_s - second >= MEMBER_RESOLVE_MARGIN:
                    key = (a_name, n, d)
                    pm.members[key] = (b_name, tn, td)
                    pm.member_conf[key] = min(top_s, 1.0)

        resolve_pool(a.methods, b.methods, "m")
        resolve_pool(a.fields, b.fields, "f")


# ---------------------------------------------------------------------------
# resolve() — public API for record extrapolation
# ---------------------------------------------------------------------------

_PAIRMAPS: dict[tuple[str, str], PairMap] = {}


def pair_map(from_version: str, to_version: str) -> PairMap:
    key = (from_version, to_version)
    if key in _PAIRMAPS:
        return _PAIRMAPS[key]
    MAP_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    ref = cubism_ref_dir()
    cache = MAP_CACHE_DIR / f"{from_version}_to_{to_version}-{_model_cache_key(from_version, ref)[:12]}-{_model_cache_key(to_version, ref)[:12]}.pkl"
    pm = None
    if cache.exists():
        try:
            pm = pickle.loads(cache.read_bytes())
        except Exception:
            pm = None
    if pm is None:
        pm = build_pair_map(load_model(from_version, ref), load_model(to_version, ref))
        try:
            cache.write_bytes(pickle.dumps(pm, protocol=4))
        except OSError:
            pass
    _PAIRMAPS[key] = pm
    return pm


def _available_versions(ref: Path | None = None) -> list[str]:
    ref = ref or cubism_ref_dir()
    return sorted(
        (d.name for d in ref.iterdir() if d.is_dir() and list(d.glob("Live2D*.jar"))),
        key=lambda v: tuple(int(x) for x in v.split(".")),
    )


def _resolve_direct(selector: dict, from_version: str, to_version: str) -> dict | None:
    pm = pair_map(from_version, to_version)
    owner = selector.get("ownerInternalName")
    if not owner:
        return None
    dst_owner = pm.classes.get(owner)
    if dst_owner is None:
        return None
    kind = selector.get("kind")
    out = {k: v for k, v in selector.items() if k != "status"}
    out["ownerInternalName"] = dst_owner
    if kind == "class":
        return out
    member = selector.get("memberName")
    if member is None:
        return None
    if kind == "inherits":
        # memberName is the ancestor's internal name.
        dst = pm.classes.get(member)
        if dst is None:
            return None
        out["memberName"] = dst
        return out
    if kind not in ("method", "field", "constructor"):
        return None
    hit = pm.members.get((owner, member, selector.get("descriptor") or ""))
    if hit is None:
        return None
    dst_o, dst_n, dst_d = hit
    out["ownerInternalName"] = dst_o
    out["memberName"] = dst_n
    out["descriptor"] = dst_d
    return out


def resolve(selector: dict, from_version: str, to_version: str) -> dict | None:
    """Map a verification-record selector into ``to_version`` symbols.

    Adjacent versions use the direct pair map. For non-adjacent pairs the
    adjacent-version maps are composed hop by hop first — each hop is the
    cheapest inference and tolerates gradual drift — and a direct pairwise
    inference is the fallback when a hop has no unique counterpart (e.g. the
    member exists only at the endpoints). Returns the candidate selector or
    ``None``.
    """
    if from_version == to_version:
        return {k: v for k, v in selector.items() if k != "status"}
    versions = _available_versions()
    try:
        i0, i1 = versions.index(from_version), versions.index(to_version)
    except ValueError:
        return _resolve_direct(selector, from_version, to_version)
    if abs(i1 - i0) > 1:
        step = 1 if i1 > i0 else -1
        path = [versions[i] for i in range(i0, i1 + step, step)]
        current = selector
        for hop_a, hop_b in zip(path, path[1:]):
            current = _resolve_direct(current, hop_a, hop_b)
            if current is None:
                break
        else:
            return current
    return _resolve_direct(selector, from_version, to_version)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _selector_from_args(args) -> dict:
    sel = {"kind": "class", "ownerInternalName": args.klass}
    if args.method is not None:
        m = re.fullmatch(r"([^\(]+)\((.*)", args.method)
        if not m:
            raise SystemExit(f"--method must look like 'name(descriptor)': {args.method}")
        sel["kind"] = "constructor" if m.group(1) == "<init>" else "method"
        sel["memberName"] = m.group(1)
        sel["descriptor"] = "(" + m.group(2)
    if args.field is not None:
        if ":" not in args.field:
            raise SystemExit(f"--field must look like 'name:descriptor': {args.field}")
        sel["kind"] = "field"
        sel["memberName"], sel["descriptor"] = args.field.split(":", 1)
    return sel


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--from", dest="from_v", required=True)
    ap.add_argument("--to", dest="to_v", required=True)
    ap.add_argument("--class", dest="klass", help="internal name to map")
    ap.add_argument("--method", dest="method", help="'name(desc)' to map with --class")
    ap.add_argument("--field", dest="field", help="'name:desc' to map with --class")
    ap.add_argument("--emit", metavar="PATH", help="write the full pair map as JSON")
    ap.add_argument("--stats", action="store_true", help="print map coverage")
    args = ap.parse_args()

    if args.emit:
        pm = pair_map(args.from_v, args.to_v)
        out = {
            "from": pm.from_version,
            "to": pm.to_version,
            "classes": {
                a: {"target": b, "confidence": round(pm.class_conf.get(a, 0.0), 4)}
                for a, b in sorted(pm.classes.items())
            },
            "members": {
                f"{o}::{n}{d}": {
                    "target": f"{to}::{tn}{td}",
                    "confidence": round(pm.member_conf.get((o, n, d), 0.0), 4),
                }
                for (o, n, d), (to, tn, td) in sorted(pm.members.items())
            },
            "unresolvedClasses": sorted(pm.unresolved),
        }
        path = Path(args.emit)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(out, indent=1))
        print(
            f"emitted {path}: {len(pm.classes)} classes, {len(pm.members)} members, "
            f"{len(pm.unresolved)} unresolved"
        )
        return 0

    if args.stats:
        pm = pair_map(args.from_v, args.to_v)
        total = len(pm.classes) + len(pm.unresolved)
        print(
            f"{pm.from_version} -> {pm.to_version}: {len(pm.classes)}/{total} classes "
            f"resolved, {len(pm.members)} members"
        )
        return 0

    if not args.klass:
        ap.error("one of --class/--emit/--stats is required")
    result = resolve(_selector_from_args(args), args.from_v, args.to_v)
    print(json.dumps(result, indent=1) if result else "null")
    return 0 if result else 1


if __name__ == "__main__":
    raise SystemExit(main())
