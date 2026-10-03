"""Build an independent memory-accounting diagnostic overlay; no performance acceptance."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import zipfile


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('guard', HERE / 'verify-native-mesh-edge-loop.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
CANDIDATE = 'eb6875699d3fdb92e3340a0a0af106fcf30f2985d490389f81fe3a0823c209f8'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def instrument(text):
    # The only edits are primitive accounting and one JFR event after scope release.
    # No host list access, query return, admission condition or native patch changes.
    replacements = {
        '        boolean closed;': '        boolean closed;\n        final NativeMeshMemoryScopeEvent observation = new NativeMeshMemoryScopeEvent();',
        '        boolean transferred = false;':
        '        boolean transferred = false;\n        long allocationStart = NativeMeshMemoryScopeEvent.allocatedBytes();',
        '            try {\n                discard();\n            } finally {\n                LazyTriangulationEdgeBridge.leave(definition);\n            }':
        '            observation.end();\n            boolean discarded = table == null;\n            try {\n                discard();\n            } finally {\n                LazyTriangulationEdgeBridge.leave(definition);\n            }\n            observation.finishAllocation();\n            observation.discarded = discarded;\n            observation.released = true;\n            observation.commit();',
        '            CURRENT.set(scope);':
        '            scope.observation.capture(table, size, allocationStart);\n            scope.observation.begin();\n            CURRENT.set(scope);',
        '            return scope.table.find(first, second);':
        '            int result = scope.table.find(first, second);\n            scope.observation.tableCalls++;\n            if (result >= 0) scope.observation.hits++;\n            else if (result == NativeMeshEdgeTable.ABSENT) scope.observation.absent++;\n            else scope.observation.unknown++;\n            return result;',
        '            scope.size++;': '            scope.size++;\n            scope.observation.appended++;',
    }
    for before, after in replacements.items():
        if text.count(before) != 1:
            raise ValueError('diagnostic seam changed: ' + before)
        text = text.replace(before, after)
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if sha(args.candidate) != CANDIDATE:
        raise ValueError('frozen production candidate identity')
    if guard.quiet_jobs():
        raise RuntimeError('performance work queued/running')
    source = Path('runtime/src/main/java/dev/turboism/adapter/cubism/mesh/NativeMeshEdgeLookup.java')
    reviewed = Path('build/t085-native-shared-integration-r1/sole-premain-r2/compile-sources/NativeMeshEdgeLookup.java')
    if source.read_bytes() != reviewed.read_bytes():
        raise ValueError('helper source differs from reviewed T085 compile input')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    compiled_source = out / source.name
    compiled_source.write_text(instrument(source.read_text()))
    classes = out / 'classes'; classes.mkdir()
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    event = HERE / 'NativeMeshMemoryScopeEvent.java'
    guard.guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', str(args.candidate.resolve()),
                   '-d', str(classes), str(compiled_source), str(event)], out / 'compile.log', env)
    prefix = 'dev/turboism/adapter/cubism/mesh/'
    overrides = {str(f.relative_to(classes)): f.read_bytes() for f in classes.rglob('*.class')}
    allowed = {prefix + n + '.class' for n in ('NativeMeshEdgeLookup', 'NativeMeshEdgeLookup$1',
               'NativeMeshEdgeLookup$Access', 'NativeMeshEdgeLookup$Scope', 'NativeMeshMemoryScopeEvent')}
    if set(overrides) != allowed:
        raise ValueError('unexpected diagnostic class family')
    target = out / 'turboism-agent.jar'; unchanged = 0
    with zipfile.ZipFile(args.candidate) as original, zipfile.ZipFile(target, 'w') as patched:
        for item in original.infolist():
            if item.filename in overrides:
                patched.writestr(item, overrides.pop(item.filename))
            else:
                patched.writestr(item, original.read(item.filename)); unchanged += 1
        for name, data in overrides.items():
            patched.writestr(name, data)
    with zipfile.ZipFile(args.candidate) as original, zipfile.ZipFile(target) as patched:
        changed = sorted(n for n in original.namelist() if original.read(n) != patched.read(n))
        added = sorted(set(patched.namelist()) - set(original.namelist()))
        if not set(changed + added) <= allowed:
            raise ValueError('unrelated candidate byte differences')
    report = {'status': 'BUILT_DIAGNOSTIC_ONLY', 'nativeMeshAdmissionIntegrated': True,
              'candidateSha256': sha(target), 'productionCandidateSha256': CANDIDATE,
              'changedEntries': changed, 'addedEntries': added, 'unrelatedEntriesIdentical': unchanged,
              'performanceAcceptance': 'NOT_APPLICABLE_DIAGNOSTIC',
              'pins': {str(f): sha(f) for f in (args.candidate, source, reviewed, event, compiled_source, Path(__file__))}}
    (out / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'], report['candidateSha256'], flush=True)


if __name__ == '__main__':
    main()
