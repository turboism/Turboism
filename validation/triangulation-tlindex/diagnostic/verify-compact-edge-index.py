#!/usr/bin/env python3
"""Replay the actual index regression suite against owned baseline/compact helper copies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError('unknown helper stencil: ' + old[:80])
    return source.replace(old, new)


RUNNER = '''import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
public final class IndexSuiteRunner {
  private IndexSuiteRunner() { }
  public static void main(String[] args) {
    var request = LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(
      "dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndexTest")).build();
    var listener = new SummaryGeneratingListener();
    var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(request);
    var summary = listener.getSummary();
    summary.printTo(new java.io.PrintWriter(System.out, true));
    summary.printFailuresTo(new java.io.PrintWriter(System.out, true));
    if(summary.getTestsFoundCount()!=33 || summary.getTestsSucceededCount()!=33
        || summary.getTestsFailedCount()!=0 || summary.getTestsSkippedCount()!=0)
      throw new AssertionError("Complete real index suite did not pass");
    System.out.println("COMPACT_INDEX_SUITE_FINISHED tests=33");
  }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path.cwd()
    source = root / 'runtime/src/main/java/dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex.java'
    tests = root / 'runtime/src/test/java/dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndexTest.java'
    prototype = Path(__file__).with_name('CompactEdgeBucketSelfCheck.java')
    if sha(source) != '2fe0f008cd71ff373e0ae0a4453707fd7b4aa6aa114df13d3ca06b426d3d52ec':
        raise ValueError('unreviewed helper source')
    if sha(tests) != 'a6bf1f3cb926ab46e61a9b68bf29fb649162b1bfe14711787e3451bb63ace512':
        raise ValueError('unreviewed index suite')
    if sha(args.agent) != '17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295':
        raise ValueError('unreviewed baseline dependency artifact')
    text = prototype.read_text()
    bucket = text.split('    static final class Bucket {', 1)[1].split('    private static final class Hostile', 1)[0]
    bucket = 'package dev.turboism.adapter.cubism.mesh;\nimport java.util.ArrayList;\nfinal class CompactEdgeBucket {' + bucket
    candidate = source.read_text().replace('HashMap<EdgeKey, ArrayList<Object>>', 'HashMap<EdgeKey, CompactEdgeBucket>')
    candidate = candidate.replace('ArrayList<Object> bucket', 'CompactEdgeBucket bucket')
    candidate = replace_once(candidate, 'bucket == null ? new ArrayList<>(4) : new ArrayList<>(bucket)',
                             'bucket == null ? new ArrayList<>(4) : bucket.snapshot()')
    candidate = replace_once(candidate, 'bucket = new ArrayList<>(2);', 'bucket = new CompactEdgeBucket();')
    old = '''            for (int i = 0; i < bucket.size(); i++) {
                if (bucket.get(i) == victim) {
                    bucket.remove(i);
                    // Keep the probe bound until empty-bucket deletion: no second
                    // hash mix or monitor entry, and no reader can rebind it midway.
                    if (bucket.isEmpty()) t.byKey.remove(t.lookup);
                    return true;
                }
            }
            return false;'''
    new = '''            if (!bucket.removeIdentity(victim)) return false;
            if (bucket.size() == 0) t.byKey.remove(t.lookup);
            return true;'''
    candidate = replace_once(candidate, old, new)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
    jars = sorted([*cache.glob('org.junit.platform/*/1.10.3/*/*.jar'),
                   *cache.glob('org.junit.jupiter/*/5.10.3/*/*.jar'),
                   *cache.glob('org.opentest4j/opentest4j/*/*/*.jar'),
                   *cache.glob('org.apiguardian/apiguardian-api/*/*/*.jar')])
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    records = {}
    for leg, helper in (('baseline', source.read_text()), ('candidate', candidate)):
        work = out / leg
        src = work / 'sources'
        src.mkdir(parents=True)
        classes = work / 'classes'
        classes.mkdir()
        (src / source.name).write_text(helper)
        shutil.copy2(tests, src / tests.name)
        (src / 'IndexSuiteRunner.java').write_text(RUNNER)
        if leg == 'candidate':
            (src / 'CompactEdgeBucket.java').write_text(bucket)
        cp = os.pathsep.join(map(str, [args.agent.resolve(), *jars]))
        compile_cmd = ['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', cp,
                       '-d', str(classes), *[str(p) for p in src.glob('*.java') if p.name != tests.name]]
        run = subprocess.run(compile_cmd, env=env, capture_output=True, text=True, timeout=60)
        (work / 'compile.txt').write_text(run.stdout + run.stderr)
        if run.returncode:
            raise ValueError(run.stdout + run.stderr)
        # Preserve the existing test source exactly; its known serial/unchecked warnings
        # are outside the production-source lint gate.
        test_compile = ['javac', '--release', '17', '-Xlint:all,-serial,-unchecked', '-Werror', '-cp',
                        str(classes) + os.pathsep + cp, '-d', str(classes), str(src / tests.name)]
        run = subprocess.run(test_compile, env=env, capture_output=True, text=True, timeout=60)
        (work / 'test-compile.txt').write_text(run.stdout + run.stderr)
        if run.returncode:
            raise ValueError(run.stdout + run.stderr)
        argv = ['java', '-Xverify:all', '-Xmx512m', '-cp', str(classes) + os.pathsep + cp, 'IndexSuiteRunner']
        run = subprocess.run(argv, env=env, capture_output=True, text=True, timeout=180)
        (work / 'suite.txt').write_text(run.stdout + run.stderr)
        print(leg, run.stdout + run.stderr)
        if run.returncode or 'COMPACT_INDEX_SUITE_FINISHED tests=33' not in run.stdout:
            raise ValueError('real index suite failed ' + leg)
        records[leg] = {'testsPassed': 33, 'compile': compile_cmd, 'testCompile': test_compile, 'argv': argv}
    pins = {str(p): sha(p) for p in [source, tests, prototype, Path(__file__).resolve(), args.agent, *jars,
                                    *out.rglob('*.java'), *out.rglob('*.class'), *out.rglob('*.txt')]}
    report = {'status': 'PASS_OWNED_FULL_INDEX_COPIES', 'legs': records, 'pins': pins,
              'limitations': ['No production edit, native Editor performance or all-version host claim.',
                              'The existing real index suite supplies synthetic triangles; no manufactured host admission.',
                              'Separate bucket identity differential and local allocation probe are required; no timing claim.']}
    (out / 'review.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
