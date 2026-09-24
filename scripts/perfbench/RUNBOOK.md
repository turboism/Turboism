# PerfBench — synthetic-host A/B microbenchmark for the snapshot-read path

Not part of the Gradle build. `src/` carries stub classes under the reviewed Cubism package
names so `documentKind`/`isProjectContent` classification resolves, plus `bench.*` owners for the
verified selectors. The same `PerfBench` is compiled against a chosen revision's built classes
to produce A/B numbers.

## Run

```sh
# build the revision under test first: ./gradlew :runtime:compileJava :runtime:compileTestJava
WT=<worktree>; SLUG=<build/worktree slug under $WT/build/worktree/>
MAIN=$WT/build/worktree/$SLUG/runtime/classes/java/main
TEST=$WT/build/worktree/$SLUG/runtime/classes/java/test
SDK=$WT/build/worktree/$SLUG/sdk/classes/java/main
javac -d /tmp/pb-classes -cp "$MAIN:$TEST:$SDK" $(find scripts/perfbench/src -name '*.java')
java -cp "/tmp/pb-classes:$MAIN:$TEST:$SDK" \
    dev.turboism.adapter.cubism.PerfBench [modelDocs] [animDocs] [extraContents] [iters] [warmup]
# e.g. dev.turboism.adapter.cubism.PerfBench 60 30 40 2000 500
```

Reports ns/op and allocated B/op for `activeProject`, `observe()+versionOf`, and the legacy
four-call scope-capture sequence. Evidence: ledger entry I45.
