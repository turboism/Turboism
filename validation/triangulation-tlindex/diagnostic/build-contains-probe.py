"""Build a separate diagnostic jar; never edit production source or launch a host."""
import hashlib
import json
import pathlib
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'runtime/src/main/java/dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex.java'
SOURCE_SHA = 'bcbcc330f72b430d73dd91ec15967da42b69600b0e1e6184f7374b18d46fa841'
AGENT_SHA = '77ce425567f8a4fb1ef1c5055fe5015a33c1cf1dcefbc90dcfff9c0bbeebd17c'
PREFIX = 'dev/turboism/adapter/cubism/mesh/'
METHOD = '''    public static boolean contains(final LinkedHashSet s, final Object tri) {
        int reason;
        try {
            final St t = st(s);
            if (t.dead) reason = 1;
            else if (t.dirty) reason = 2;
            else if (t.sz != s.size()) reason = 3;
            else if (t.keys.size() != s.size()) reason = 4;
            else if (!t.keys.containsKey(tri)) reason = 5;
            else {
                ContainsDiagnostic.record(0, 0);
                ContainsDiagnostic.record(0, 1);
                return true;
            }
        } catch (Throwable bookkeeping) {
            FatalErrors.rethrowIfFatal(bookkeeping);
            reason = 6;
        }
        ContainsDiagnostic.record(reason, 0);
        final boolean result = s.contains(tri);
        ContainsDiagnostic.record(reason, result ? 1 : 2);
        return result;
    }

'''
COUNTER = '''package dev.turboism.adapter.cubism.mesh;
import java.util.concurrent.atomic.AtomicLongArray;
final class ContainsDiagnostic {
    static final String[] NAMES = {"identityHit", "dead", "dirty", "setSize",
        "keySize", "identityMiss", "bookkeepingFailure"};
    static final AtomicLongArray COUNTS = new AtomicLongArray(21);
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StringBuilder out = new StringBuilder("[TL-CONTAINS-DIAGNOSTIC-v1] {");
            for (int i = 0; i < NAMES.length; i++) {
                if (i > 0) out.append(',');
                out.append('"').append(NAMES[i]).append("\\\":[");
                for (int j = 0; j < 3; j++) {
                    if (j > 0) out.append(',');
                    out.append(COUNTS.get(i * 3 + j));
                }
                out.append(']');
            }
            System.err.println(out.append('}'));
        }, "tl-contains-diagnostic"));
    }
    static void record(int reason, int outcome) {
        COUNTS.incrementAndGet(reason * 3 + outcome);
    }
}
'''


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    agent, output = (pathlib.Path(x).resolve() for x in sys.argv[1:])
    assert sha(SOURCE) == SOURCE_SHA, 'source changed; review diagnostic insertion'
    assert sha(agent) == AGENT_SHA, 'not the frozen contains candidate'
    output.mkdir()  # refuse reuse or overwrite
    source = SOURCE.read_text()
    start = source.index('    public static boolean contains(')
    end = source.index('    /** Replaces {@code LinkedHashSet.clear}. */', start)
    generated = output / 'TriangulationEdgeIndex.java'
    generated.write_text(source[:start] + METHOD + source[end:])
    counter = output / 'ContainsDiagnostic.java'
    counter.write_text(COUNTER)
    classes = output / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none',
                    '-cp', str(agent), '-d', str(classes), str(generated), str(counter)], check=True)
    test_classes = output / 'test-classes'
    test_classes.mkdir()
    test = pathlib.Path(__file__).with_name('ContainsDiagnosticSelfCheck.java')
    cp = str(classes) + ':' + str(agent)
    subprocess.run(['javac', '--release', '17', '-proc:none', '-cp', cp,
                    '-d', str(test_classes), str(test)], check=True)
    check = subprocess.run(['java', '-cp', str(test_classes) + ':' + cp,
                            'dev.turboism.adapter.cubism.mesh.ContainsDiagnosticSelfCheck'],
                           capture_output=True, text=True)
    (output / 'selfcheck.log').write_text(check.stdout + check.stderr)
    check.check_returncode()
    marker = '[TL-CONTAINS-DIAGNOSTIC-v1] '
    lines = [line[len(marker):] for line in check.stderr.splitlines() if line.startswith(marker)]
    assert len(lines) == 1
    expected = {'identityHit': [1, 1, 0], 'dead': [1, 1, 0], 'dirty': [2, 1, 0],
                'setSize': [1, 0, 1], 'keySize': [1, 1, 0], 'identityMiss': [2, 1, 1],
                'bookkeepingFailure': [1, 0, 0]}
    assert json.loads(lines[0]) == expected, 'shutdown counters must match all branch tests'
    replacement = {p.relative_to(classes).as_posix(): p.read_bytes()
                   for p in classes.rglob('*.class')}
    assert set(replacement) == {PREFIX + n + '.class' for n in
                               ('TriangulationEdgeIndex', 'TriangulationEdgeIndex$St',
                                'TriangulationEdgeIndex$SetKey', 'ContainsDiagnostic')}
    # Preserve the frozen inner classes byte-for-byte, including compilation metadata.
    for name in ('TriangulationEdgeIndex$St', 'TriangulationEdgeIndex$SetKey'):
        del replacement[PREFIX + name + '.class']
    jar = output / 'turboism-contains-diagnostic.jar'
    with zipfile.ZipFile(agent) as src, zipfile.ZipFile(jar, 'x') as dst:
        for item in src.infolist():
            dst.writestr(item, replacement.pop(item.filename, src.read(item)))
        for name, data in replacement.items():
            dst.writestr(name, data)
    with zipfile.ZipFile(agent) as before, zipfile.ZipFile(jar) as after:
        changed = [n for n in before.namelist() if before.read(n) != after.read(n)]
        assert changed == [PREFIX + 'TriangulationEdgeIndex.class']
    packaged = subprocess.run(['java', '-cp', str(test_classes) + ':' + str(jar),
                              'dev.turboism.adapter.cubism.mesh.ContainsDiagnosticSelfCheck'],
                             capture_output=True, text=True)
    (output / 'packaged-selfcheck.log').write_text(packaged.stdout + packaged.stderr)
    packaged.check_returncode()
    lines = [line[len(marker):] for line in packaged.stderr.splitlines() if line.startswith(marker)]
    assert len(lines) == 1 and json.loads(lines[0]) == expected
    (output / 'manifest.json').write_text(json.dumps({
        'kind': 'DIAGNOSTIC_ONLY_NOT_PERFORMANCE_ACCEPTANCE',
        'sourceSha256': SOURCE_SHA, 'baseAgentSha256': AGENT_SHA,
        'diagnosticJarSha256': sha(jar), 'changedEntries': changed,
        'addedEntry': PREFIX + 'ContainsDiagnostic.class',
        'counterColumns': ['attempts', 'returnedTrue', 'returnedFalse'],
        'limitations': 'Counters perturb timing. No per-call logging or host-object retention. '
                      'Attempts minus completed returns include native throws or unfinished calls. '
                      'Only normal shutdown emits the aggregate; missing output is not zero.'
    }, indent=2) + '\n')
    print(jar)


if __name__ == '__main__':
    main()
