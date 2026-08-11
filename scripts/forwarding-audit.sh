#!/usr/bin/env bash
# Re-runnable forwarding-completeness audit for the SDK wrapper layers
# (w13-20260807-forwarding-audit, lane A).
#
# Compares every dev.turboism.sdk.cubism.model interface method against the
# methods declared by the two wrapper layers:
#   PC layer      runtime/src/main/java/dev/turboism/adapter/cubism/CubismFacadeImpl.java
#   Session layer runtime/src/main/java/dev/turboism/adapter/host/DynamicCubismModelAccess.java
#
# Exit code 0 = no missing methods (except the SDK self-derived defaults that
# are intentionally not forwarded: FloatSequence/IntSequence.isEmpty() and the
# non-throwing ui() appearance defaults on the Warp/Rotation wrappers).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

WORKTREE_ID="$(bash scripts/dev/worktree-id.sh 2>/dev/null || basename "$ROOT")"
SDK_CLASSES="build/worktree/$WORKTREE_ID/sdk/classes/java/main"

if [ ! -d "$SDK_CLASSES" ]; then
  echo "SDK classes missing; compiling :sdk:compileJava ..." >&2
  ./gradlew :sdk:compileJava --no-daemon --console=plain -q
fi

PYTHON_BIN="${PYTHON_BIN:-python3}"
"$PYTHON_BIN" - "$SDK_CLASSES" <<'PYEOF'
import json
import os
import re
import subprocess
import sys

sdk_classes = sys.argv[1]
pc_file = "runtime/src/main/java/dev/turboism/adapter/cubism/CubismFacadeImpl.java"
session_file = "runtime/src/main/java/dev/turboism/adapter/host/DynamicCubismModelAccess.java"
model_pkg = "dev/turboism/sdk/cubism/model"

def javap_methods(cls):
    out = subprocess.run(
        ["javap", "-p", "-classpath", sdk_classes, cls],
        capture_output=True, text=True,
    ).stdout
    methods = {}
    for line in out.splitlines():
        m = re.match(
            r'\s*(?:public\s+)?(default\s+)?(?:abstract\s+)?(?:static\s+)?'
            r'[\w.<>\[\],\s]+ (\w+)\((.*)\);',
            line,
        )
        if not m or m.group(2) in ("<init>",) or "static" in line:
            continue
        params = re.sub(r'\s+', '', m.group(3))
        methods[f"{m.group(2)}({params})"] = bool(m.group(1))
    return methods

ifaces = {}
model_dir = os.path.join(sdk_classes, *model_pkg.split("."))
for f in sorted(os.listdir(model_dir)):
    if not f.endswith(".class"):
        continue
    cls = f[:-6]
    out = subprocess.run(
        ["javap", "-p", "-classpath", sdk_classes, f"{model_pkg}.{cls}"],
        capture_output=True, text=True,
    ).stdout
    if "interface " not in out:
        continue
    methods = javap_methods(f"{model_pkg}.{cls}")
    if methods:
        ifaces[cls] = methods

def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'//[^\n]*', '', src)

def class_bodies(src):
    classes = []
    for m in re.finditer(r'class\s+(\w+)|new\s+([\w.$]+)\s*\(\)\s*\{', src):
        pos = m.start()
        open_pos = src.find('{', pos)
        if open_pos < 0:
            continue
        if m.group(1):
            prefix = src[max(0, pos - 200):pos]
            if re.search(r'interface\s+' + re.escape(m.group(1)), prefix):
                continue
            name = m.group(1)
        else:
            name = 'anon:' + m.group(2).split('.')[-1]
        depth = 0
        close_pos = None
        for j in range(open_pos, len(src)):
            if src[j] == '{':
                depth += 1
            elif src[j] == '}':
                depth -= 1
                if depth == 0:
                    close_pos = j
                    break
        if close_pos:
            classes.append((name, open_pos, close_pos))
    return classes

def methods_in_body(src, start, end):
    body = src[start:end]
    depth = 0
    out = []
    for ch in body:
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
        if depth <= 1:
            out.append(ch)
    methods = {}
    pat = re.compile(r'@Override\s+(?:public\s+)?[\w.<>\[\],\s]+\s+(\w+)\s*\(([^)]*)\)')
    for m in pat.finditer(''.join(out)):
        name = m.group(1)
        params = m.group(2).strip()
        methods[f'{name}({params})'] = True
    return methods

def wrapper_methods(path):
    src = strip_comments(open(path).read())
    result = {}
    extends = {}
    for m in re.finditer(r'class\s+(\w+)[^{]*?\bextends\s+(\w+)', src):
        extends[m.group(1)] = m.group(2)
    for name, start, end in class_bodies(src):
        result[name] = methods_in_body(src, start, end)
    # merge superclass methods so subclass-only declarations are not reported missing
    for name in result:
        merged = dict(result[name])
        parent = extends.get(name)
        while parent and parent in result:
            merged.update(result[parent])
            parent = extends.get(parent)
        result[name] = merged
    return result

pc = wrapper_methods(pc_file)
session = wrapper_methods(session_file)

def split_params(p):
    parts, depth, cur = [], 0, ''
    for ch in p:
        if ch in '<([':
            depth += 1
        elif ch in '>)]':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += ch
    if cur:
        parts.append(cur)
    return parts

def norm_param(t):
    t = t.strip()
    if not t:
        return ''
    toks = [x for x in re.split(r'\s+', t) if x]
    toks = [x for x in toks if x != 'final' and not x.startswith('@')]
    if not toks:
        return ''
    if len(toks) > 1 and not t.endswith('...'):
        type_str = ' '.join(toks[:-1])
    else:
        type_str = t
    return re.sub(r'<.*', '', type_str).strip().split('.')[-1]

def norm_sig(sig):
    name, params = sig.split('(', 1)
    return name + '(' + ','.join(norm_param(x) for x in split_params(params.rstrip(')'))) + ')'

def norm_pool(pool):
    return {cls: set(norm_sig(s) for s in sigs) for cls, sigs in pool.items()}

pc_n = norm_pool(pc)
session_n = norm_pool(session)

mapping = {
    'PC': {
        'PermissionCheckedModel': 'CubismModel', 'anon:Parameters': 'Parameters',
        'anon:ParameterGroups': 'ParameterGroups', 'anon:ParameterDefinitions': 'ParameterDefinitions',
        'anon:Canvas': 'Canvas', 'anon:ModelTextures': 'ModelTextures',
        'anon:ParameterBindingOperations': 'ParameterBindingOperations',
        'anon:ParameterBindingBatchOperations': 'ParameterBindingBatchOperations',
        'anon:Parts': 'Parts', 'anon:Drawables': 'Drawables', 'anon:Deformers': 'Deformers',
        'anon:RotationDeformers': 'RotationDeformers', 'anon:Glues': 'Glues',
        'anon:WarpDeformers': 'WarpDeformers',
        'PermissionCheckedDrawable': 'Drawable', 'PermissionCheckedDeformer': 'Deformer',
        'PermissionCheckedGlue': 'Glue', 'PermissionCheckedParameterGroup': 'ParameterGroup',
        'PermissionCheckedParameter': 'Parameter', 'PermissionCheckedPart': 'Part',
        'PermissionCheckedWarpDeformer': 'Deformer',
        'PermissionCheckedRotationDeformer': 'Deformer',
    },
    'SESSION': {
        'SessionModel': 'CubismModel', 'SessionModelTextures': 'ModelTextures',
        'SessionCanvas': 'Canvas', 'SessionParameterDefinitions': 'ParameterDefinitions',
        'SessionParameters': 'Parameters', 'SessionParameterGroups': 'ParameterGroups',
        'SessionParameterGroup': 'ParameterGroup', 'SessionParameter': 'Parameter',
        'SessionParts': 'Parts', 'SessionPart': 'Part', 'SessionDrawables': 'Drawables',
        'SessionDrawable': 'Drawable', 'SessionFloatSequence': 'FloatSequence',
        'SessionIntSequence': 'IntSequence', 'SessionDeformers': 'Deformers',
        'SessionDeformer': 'Deformer', 'SessionWarpDeformers': 'WarpDeformers',
        'SessionWarpDeformer': 'WarpDeformer', 'SessionRotationDeformers': 'RotationDeformers',
        'SessionRotationDeformer': 'RotationDeformer', 'SessionGlues': 'Glues',
        'SessionGlue': 'Glue',
        'SessionWarpDeformer': 'Deformer',
        'SessionRotationDeformer': 'Deformer',
    },
}

missing = 0
for layer, layer_map in mapping.items():
    pool = pc_n if layer == 'PC' else session_n
    for wrapper, iface in sorted(layer_map.items()):
        declared = pool.get(wrapper, set())
        for sig, is_default in sorted(ifaces[iface].items()):
            if norm_sig(sig) not in declared:
                # Intentional non-forwardings:
                #  - SDK self-derived defaults (size() == 0) compose through size().
                #  - ui() defaults return *Appearance.unavailable() (non-throwing) and the
                #    Warp/Rotation wrappers cannot compute the real appearance (no modelId).
                if sig.startswith('isEmpty()') or sig.startswith('ui()'):
                    continue
                missing += 1
                print(f"MISSING {layer} {wrapper} implements {iface}: "
                      f"{'default' if is_default else 'abstract'} {sig}")

if missing:
    print(f"\n{missing} missing forwarding(s) found.")
    sys.exit(1)
print("OK: no missing SDK method forwarding in either wrapper layer.")
PYEOF
