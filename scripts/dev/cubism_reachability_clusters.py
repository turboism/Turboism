#!/usr/bin/env python3
"""Cluster reviewed Cubism versions by per-family reachable bytecode fingerprints.

For each mapping-pack family under ``compatibility/cubism/mapping-packs/draft/``
this tool computes, inside each version's Live2D jars, the transitive
*method-level* closure starting from the family's selector members
(``x.verification.ownerInternalName`` + ``runtime`` member name + ``descriptor``):

- edges are member references actually used by a method's bytecode
  (invoke*/get*/put*/new/casts/ldc-class, exception-table catches, and
  invokedynamic bootstrap method-handle targets so lambda bodies are reached);
- virtual/interface calls expand to every same ``name+desc`` implementation in
  the owner hierarchy (ancestors, descendants, interfaces) — an
  over-approximation of real dispatch: wrong merges are impossible, at worst a
  cluster splits;
- fingerprint = sha256 over the closure's sorted
  ``(owner, name, desc, code-sha256)`` entries plus reached classes' hierarchy
  facts and accessed fields' constant values.

A shared fingerprint means every method body in the family-reachable set is
byte-identical: under the stable 5.x obfuscation map the family's observable
behavior for the touched paths is identical. Roots missing in a version are
reported separately — those selectors fail structurally anyway.

Blind spots (kept honest): ``Class.forName`` with computed names, service
loading, license data and native methods; treat clusters as
run-one-representative evidence with periodic full-matrix sweeps as the net.

Usage: cubism_reachability_clusters.py [--family NAME] [--cache FILE]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pickle
import re
import struct
import subprocess
import sys
import zipfile
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACK_DIR = ROOT / "compatibility" / "cubism" / "mapping-packs" / "draft"
LIVE2D_PREFIX = "com/live2d/"
L2D = LIVE2D_PREFIX.encode()

# Fixed instruction lengths after the opcode byte; 0 = no operands.
OPLEN = [0] * 256
for _op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC):
    OPLEN[_op] = 1
for _op in (0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F,
            0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
            0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7):
    OPLEN[_op] = 2
for _op in (0xB9, 0xBA, 0xC5, 0xC8, 0xC9):
    OPLEN[_op] = 4

REF_OPS = {0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA,
           0xBB, 0xBD, 0xC0, 0xC1, 0xC5}
LDC_OPS = {0x12: 1, 0x13: 2, 0x14: 2}


class BadClass(Exception):
    pass


def u2(b, i):
    return struct.unpack_from(">H", b, i)[0]


def u4(b, i):
    return struct.unpack_from(">I", b, i)[0]


def cp_utf(cp, idx):
    e = cp[idx] if 0 < idx < len(cp) else None
    return e[1].decode("utf-8", "replace") if e and e[0] == "utf8" else None


def cp_cname(cp, idx):
    e = cp[idx] if 0 < idx < len(cp) else None
    return cp_utf(cp, e[1]) if e and e[0] == "class" else None


def cp_nat(cp, idx):
    e = cp[idx] if 0 < idx < len(cp) else None
    return (cp_utf(cp, e[1]), cp_utf(cp, e[2])) if e and e[0] == "nat" else (None, None)


def cp_member(cp, idx):
    e = cp[idx] if 0 < idx < len(cp) else None
    if e and e[0] == "member":
        n, d = cp_nat(cp, e[2])
        return cp_cname(cp, e[1]), n, d
    return None


def cp_mh_target(cp, idx):
    e = cp[idx] if 0 < idx < len(cp) else None
    return cp_member(cp, e[1]) if e and e[0] == "mh" else None


def parse_class(data: bytes):
    """Compact per-class record; constant pool is discarded afterwards."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise BadClass()
    cp_count = u2(data, 8)
    cp = [None] * cp_count
    i = 10
    idx = 1
    while idx < cp_count:
        tag = data[i]
        i += 1
        if tag == 1:
            ln = u2(data, i)
            cp[idx] = ("utf8", data[i + 2 : i + 2 + ln])
            i += 2 + ln
        elif tag == 7:
            cp[idx] = ("class", u2(data, i))
            i += 2
        elif tag == 8:
            cp[idx] = ("str", u2(data, i))
            i += 2
        elif tag in (9, 10, 11):
            cp[idx] = ("member", u2(data, i), u2(data, i + 2))
            i += 4
        elif tag == 12:
            cp[idx] = ("nat", u2(data, i), u2(data, i + 2))
            i += 4
        elif tag == 15:
            cp[idx] = ("mh", u2(data, i + 1))
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
            raise BadClass()
        idx += 1

    i += 4  # access_flags + this_class
    sup = cp_cname(cp, u2(data, i))
    i += 2
    ifc = u2(data, i)
    i += 2
    ifs = tuple(filter(None, (cp_cname(cp, u2(data, i + 2 * k)) for k in range(ifc))))
    i += 2 * ifc

    fields = {}
    fc = u2(data, i)
    i += 2
    for _ in range(fc):
        i += 2  # access_flags
        fn, fd = cp_utf(cp, u2(data, i)), cp_utf(cp, u2(data, i + 2))
        i += 4
        ac = u2(data, i)
        i += 2
        const = None
        for _ in range(ac):
            an = cp_utf(cp, u2(data, i))
            i += 2
            ln = u4(data, i)
            i += 4
            if an == "ConstantValue":
                const = data[i : i + ln]
            i += ln
        if fn is not None:
            fields[(fn, fd)] = const

    methods = {}
    extra_refs: set[str] = set()
    mc = u2(data, i)
    i += 2
    for _ in range(mc):
        i += 2  # access_flags
        mn, md = cp_utf(cp, u2(data, i)), cp_utf(cp, u2(data, i + 2))
        i += 4
        ac = u2(data, i)
        i += 2
        code = None
        catches: list[str] = []
        for _ in range(ac):
            an = cp_utf(cp, u2(data, i))
            i += 2
            ln = u4(data, i)
            i += 4
            if an == "Code":
                cl = u4(data, i + 4)
                code = data[i + 8 : i + 8 + cl]
                j = i + 8 + cl
                ec = u2(data, j)
                j += 2
                for _ in range(ec):
                    j += 6
                    cn = cp_cname(cp, u2(data, j))
                    j += 2
                    if cn and cn.startswith(LIVE2D_PREFIX):
                        catches.append(cn)
            i += ln
        if mn is not None:
            edges = method_edges(cp, code) if code is not None else ((), (), ())
            methods[(mn, md)] = (hashlib.sha256(code).digest() if code else None, *edges)
            extra_refs.update(catches)

    boot_targets: set[tuple] = set()
    ac = u2(data, i)
    i += 2
    for _ in range(ac):
        an = cp_utf(cp, u2(data, i))
        i += 2
        ln = u4(data, i)
        i += 4
        if an == "BootstrapMethods":
            nb = u2(data, i)
            j = i + 2
            for _ in range(nb):
                bm = cp_mh_target(cp, u2(data, j))
                j += 2
                if bm and bm[0] and bm[0].startswith(LIVE2D_PREFIX):
                    boot_targets.add(bm)
                na = u2(data, j)
                j += 2
                for _ in range(na):
                    arg = cp_mh_target(cp, u2(data, j))
                    j += 2
                    if arg and arg[0] and arg[0].startswith(LIVE2D_PREFIX):
                        boot_targets.add(arg)
        i += ln

    return {
        "super": sup,
        "interfaces": ifs,
        "methods": methods,
        "fields": fields,
        "extra_refs": extra_refs,   # catch-clause classes
        "boot_targets": boot_targets,
    }


def method_edges(cp, code: bytes):
    """Scan bytecode; return (invoke, field, class) edge tuples."""
    inv: list[tuple] = []
    fld: list[tuple] = []
    cls: list[str] = []
    i = 0
    n = len(code)
    while i < n:
        op = code[i]
        i += 1
        if op == 0xAA:  # tableswitch
            i += (4 - (i % 4)) % 4
            if i + 12 > n:
                break
            lo, hi = u4(code, i + 4), u4(code, i + 8)
            i += 12 + 4 * max(0, hi - lo + 1)
            continue
        if op == 0xAB:  # lookupswitch
            i += (4 - (i % 4)) % 4
            if i + 8 > n:
                break
            i += 8 + 8 * u4(code, i + 4)
            continue
        if op == 0xC4:  # wide
            i += 6 if code[i] == 0x84 else 2
            continue
        l = LDC_OPS.get(op)
        if l:
            idx = code[i] if l == 1 else u2(code, i)
            cn = cp_cname(cp, idx)
            if cn and cn.startswith(LIVE2D_PREFIX):
                cls.append(cn)
            i += l
            continue
        if op in REF_OPS:
            idx = u2(code, i)
            m = cp_member(cp, idx)
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
                cn = cp_cname(cp, idx)
                if cn and cn.startswith(LIVE2D_PREFIX):
                    cls.append(cn)
            i += OPLEN[op]
            continue
        i += OPLEN[op]
    return tuple(inv), tuple(fld), tuple(cls)


def build_graph(version: str, ref: Path):
    """Parse every Live2D-shipped jar (Editor, Core, MotionSync, PFUtils)."""
    classes = {}
    for jar in sorted((ref / version).glob("Live2D*.jar")):
        with zipfile.ZipFile(jar) as zf:
            for info in zf.infolist():
                if not info.filename.endswith(".class"):
                    continue
                name = info.filename[: -len(".class")]
                if not name.startswith(LIVE2D_PREFIX) or name in classes:
                    continue
                try:
                    classes[name] = parse_class(zf.read(info.filename))
                except Exception:
                    pass
    subs = {}
    for name, c in classes.items():
        for anc in ([c["super"]] if c["super"] else []) + list(c["interfaces"]):
            subs.setdefault(anc, set()).add(name)
    return {"classes": classes, "subs": subs}


def family_roots():
    """family -> {'methods': {(owner,name,desc)}, 'fields': {(owner,name,desc)}}"""
    fams: dict[str, dict[str, set]] = {}
    for p in sorted(PACK_DIR.glob("*.json")):
        d = json.loads(p.read_text())
        m = re.match(r"cubism-\d+\.\d+\.\d+-(.+)\.json$", p.name)
        fam = m.group(1) if m else p.stem
        slot = fams.setdefault(fam, {"methods": set(), "fields": set()})
        for e in d.get("entries", []):
            xv = e.get("x.verification") or {}
            owner = xv.get("ownerInternalName")
            if not owner or not owner.startswith(LIVE2D_PREFIX):
                continue
            key = (owner, e.get("runtime") or "", e.get("descriptor") or "")
            if e.get("kind") == "field":
                slot["fields"].add(key)
            elif e.get("kind") == "method":
                slot["methods"].add(key)
    return fams


def hierarchy_members(graph, owner, name, desc):
    cache = graph.setdefault("_hm", {})
    key = (owner, name, desc)
    hit = cache.get(key)
    if hit is not None:
        return hit
    classes = graph["classes"]
    subs = graph["subs"]
    out = []
    seen = set()
    q = deque([owner])
    while q:
        c = q.popleft()
        if c in seen:
            continue
        seen.add(c)
        info = classes.get(c)
        if not info:
            continue
        if (name, desc) in info["methods"]:
            out.append((c, name, desc))
        for anc in ([info["super"]] if info["super"] else []) + list(info["interfaces"]):
            if anc and anc.startswith(LIVE2D_PREFIX):
                q.append(anc)
        q.extend(subs.get(c, ()))
    out = tuple(out)
    cache[key] = out
    return out


def closure(graph, roots):
    classes = graph["classes"]
    m_done: set[tuple] = set()
    m_q: deque = deque()
    f_seen: set[tuple] = set()
    c_seen: set[str] = set()

    def touch_class(c):
        if c in c_seen:
            return
        c_seen.add(c)
        info = classes.get(c)
        if info:
            for n, d in info["methods"]:
                if n in ("<clinit>", "<init>"):
                    m_q.append((c, n, d))

    for owner, name, desc in roots["methods"]:
        if owner in classes:
            c_seen.add(owner)
            if (name, desc) in classes[owner]["methods"]:
                m_q.append((owner, name, desc))
    for owner, name, desc in roots["fields"]:
        if owner in classes and (name, desc) in classes[owner]["fields"]:
            f_seen.add((owner, name, desc))
        touch_class(owner)

    while m_q:
        node = m_q.popleft()
        if node in m_done:
            continue
        owner, name, desc = node
        info = classes.get(owner)
        if not info:
            continue
        m_done.add(node)
        for cn in info["extra_refs"]:
            touch_class(cn)
        for bo, bn, bd in info["boot_targets"]:
            if bo in classes and (bn, bd) in classes[bo]["methods"]:
                c_seen.add(bo)
                m_q.append((bo, bn, bd))
        rec = info["methods"].get((name, desc))
        if not rec:
            continue
        _, inv, fld, cls = rec
        for o, n, d, op in inv:
            touch_class(o)
            if op in (0xB6, 0xB9, 0xBA):
                m_q.extend(hierarchy_members(graph, o, n, d))
            elif o in classes and (n, d) in classes[o]["methods"]:
                c_seen.add(o)
                m_q.append((o, n, d))
        for o, n, d in fld:
            f_seen.add((o, n, d))
            touch_class(o)
        for o in cls:
            touch_class(o)
    return m_done, f_seen, c_seen


def fingerprint(graph, m_done, f_seen, c_seen):
    classes = graph["classes"]
    h = hashlib.sha256()
    for o, n, d in sorted(m_done):
        rec = classes[o]["methods"].get((n, d))
        h.update(b"m\0")
        h.update(f"{o}\0{n}\0{d}\0".encode())
        h.update(rec[0] or b"\x00" * 32)
    for o, n, d in sorted(f_seen):
        h.update(b"f\0")
        h.update(f"{o}\0{n}\0{d}\0".encode())
        info = classes.get(o)
        const = info["fields"].get((n, d)) if info else None
        h.update(hashlib.sha256(const or b"").digest())
    for c in sorted(c_seen):
        info = classes.get(c)
        if not info:
            continue
        h.update(b"c\0")
        h.update(c.encode())
        h.update((info["super"] or "").encode())
        for it in sorted(info["interfaces"]):
            h.update(it.encode())
    return h.hexdigest()


def cubism_ref_dir() -> Path:
    configured = __import__("os").environ.get("TURBOISM_CUBISM_REF")
    if configured:
        return Path(configured).expanduser().resolve()
    primary = subprocess.run(
        ["git", "-C", str(ROOT), "rev-parse", "--path-format=absolute", "--git-common-dir"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    return Path(primary).parent / "cubism-ref"


def load_graphs(ref: Path, cache: Path):
    if cache.exists():
        return pickle.loads(cache.read_bytes())
    graphs = {}
    for d in sorted(ref.iterdir()):
        if d.is_dir() and list(d.glob("Live2D*.jar")):
            graphs[d.name] = build_graph(d.name, ref)
            print(f"  parsed {d.name}: {len(graphs[d.name]['classes'])} classes", file=sys.stderr)
    cache.write_bytes(pickle.dumps(graphs, protocol=4))
    return graphs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--family")
    ap.add_argument("--cache", default="/tmp/cubism-jar-graphs-m.pkl")
    args = ap.parse_args()

    ref = cubism_ref_dir()
    print(f"cubism-ref: {ref}", file=sys.stderr)
    graphs = load_graphs(ref, Path(args.cache))
    versions = sorted(graphs)
    print(f"versions: {len(versions)}", file=sys.stderr)

    fams = family_roots()
    if args.family:
        fams = {args.family: fams[args.family]}

    for fam, roots in sorted(fams.items()):
        nroots = len(roots["methods"]) + len(roots["fields"])
        if not nroots:
            continue
        clusters: dict[str, list[str]] = {}
        missing: dict[str, list[str]] = {}
        sizes = {}
        for v in versions:
            g = graphs[v]
            miss = [
                f"{o}.{n}{d}"
                for o, n, d in roots["methods"]
                if o not in g["classes"] or (n, d) not in g["classes"][o]["methods"]
            ]
            miss += [
                f"{o}.{n}{d}"
                for o, n, d in roots["fields"]
                if o not in g["classes"] or (n, d) not in g["classes"][o]["fields"]
            ]
            if miss:
                missing[v] = miss
            m_done, f_seen, c_seen = closure(g, roots)
            fp = fingerprint(g, m_done, f_seen, c_seen)
            clusters.setdefault(fp, []).append(v)
            sizes[v] = len(m_done)
        print(f"\n=== {fam} (roots={nroots}) -> {len(clusters)} clusters")
        for vs in sorted(clusters.values(), key=lambda x: x[0]):
            print(f"  rep={vs[0]} methods={sizes[vs[0]]}  members: {', '.join(vs)}")
        for v, miss in sorted(missing.items()):
            print(f"  {v}: {len(miss)} missing roots, e.g. {miss[:3]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
