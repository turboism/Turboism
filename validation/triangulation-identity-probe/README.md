# triangulation-identity-probe (T029-IDENTITY, offline slice)

Default-off auxiliary agent for the 020/T029 static-vs-runtime contradiction: job 9b55f078's
JFR shows `HashMap$KeySet`/`HashIterator(bci75)` frames under `TriangleList.iterator(bci4)` while
the official 5.3.03 class assigns `new LinkedHashSet`. This slice provides one-shot *use-site*
identity evidence — it does not optimize anything and does not interpret results.

## What the agent does when enabled (`turboism.validation.triIdentity.enabled=true`)

Admission (premain order): config validation → helper warm → writer pre-start → already-loaded
gate → transformer registration. `outputDir` must be an explicit absolute path, `runId` must match
`[A-Za-z0-9._-]{1,32}`, `expectClassSha256` 64 hex, `expectLoader` non-empty, and
`expectCodeSource` a non-empty URL-ish string. Any missing/invalid value → one bounded
`admission=reject reason=…` stderr line and **nothing is installed** — no transformer, no helper,
no writer thread, no files. Premain never throws.

1. **Definition observation** — on each definition event for
   `com/live2d/graphics3d/editableMesh/triangulation/TriangleList` records (bounded to 4 events +
   one overflow marker): `classfileBuffer` sha256, loader class name, module, ProtectionDomain
   CodeSource, observer seq. A sha mismatch is recorded and rejects weaving — it is an observation,
   *not* attribution of any prior transformer.
2. **Gates** — loader class name (`expectLoader`), class sha (`expectClassSha256`, default the
   official `87835641…`), **exact** CodeSource match (`expectCodeSource`; normalization:
   `toExternalForm` → collapse redundant leading slashes after `scheme:` → strip trailing `/`;
   case-sensitive equality only, no prefix/wildcard — authority-bearing URLs fold the authority
   into the path, a documented limitation), and a structural shape check
   (private final `b: LinkedHashSet`; `<init>` allocates `new LinkedHashSet`; `iterator()`
   dispatches `getfield b → invokevirtual LinkedHashSet.iterator`).
3. **Use-site weave** — prepends `Probe.record(this, this.b)` to `iterator()` inside a
   `catch (Throwable)` handler that pops and re-enters the original body. The try block wraps the
   helper invocation itself, so helper linkage/initialization/call failures are absorbed at the
   callsite; the original return reference and exceptions pass through unchanged.
4. **Recording** — record() claims a process-wide one-shot slot (first successful claim wins),
   builds one bounded snapshot string (set/owner actual class + per-run loader token
   `class@identityHashCode` — distinguishes same-named loader instances within THIS run only, not
   a stable ID + modules + CodeSources + JRE + thread + phase tag). Every emitted line is escaped
   (CR/LF/TAB/control/space) and capped (field 256 chars + `~truncated`, line 2000 + `~truncated`,
   per-file 64KiB + `bytesCapReached` marker). Non-blocking offer into a 32-slot queue; a full
   queue increments `queueDropped` (declared evidence-absence). One daemon writer **pre-started at
   premain**; each line written once, never retried; write failure is in-process status only and
   does not count as a successful sample file.

Explicit non-actions: no collection traversal, no element reads, no private `map` reflection,
no field writes, no host-business calls, no production optimization, no hot-path file IO, no
unbounded output.

## Interpretation limits (frozen, required reading)

- sha difference ≠ responsibility attribution; sha match ≠ proof of final loaded bytes.
- One construction/use sample does not prove later objects keep the same type.
- A `LinkedHashSet` sample does not by itself prove the JFR frames were wrong.
- The bundled-JRE constructor chain (`LinkedHashSet→HashSet(IFZ)→map=new LinkedHashMap`) is the
  stock path only — it is not a guarantee under reflection/clone/redefinition.
- `HashIterator.<init>` bci75 is the first-non-empty-bucket table scan; sample counts there are
  neither iteration counts nor time.

## Layout

```
src/      agent classes (IdentityProbeAgent, DefinitionObserver, TriangleListShape,
          IteratorWeave, Probe, ProbeConfig, OfficialShapeProbe)
fixture/  own same-named fixture classes (loaded only via FixtureLoader, child-first)
fixture-badshape/  shape-rejection fixture
selfcheck/  scenario driver (fresh JVM per scenario)
tools/    build-time jar relocation (copied from meshhash, retargeted)
build.sh  offline build → agent jar + no-helper jar (prints sha256s)
run.sh    build + all scenarios under -Xverify:all
```

## Reproduce

```
bash validation/triangulation-identity-probe/run.sh
```

Expected tail: `TRI_IDENTITY_RUN PASS scenarios=N hostExecuted=false officialClassLoaded=false`.

Scenarios: happy (sentinel return + actual set class/loader-token/module recorded), off (zero
side effects incl. no writer thread), refusedConfig×3 (missing props / bad runId / relative
outputDir → premain refuses, JVM+entrypoint continue, no files), wrongSource/wrongSha/wrongLoader/
badShape (per-class gate rejects), missingHelper/failInit (premain refuses on helper warm
failure), throwOnRecord (weave-level call failure absorbed), directLinkFail/directInitFail
(fixture-local stub Probe → NoSuchMethodError/ExceptionInInitializerError absorbed by the
weave catch), passthrough (exception class+message), concurrency (8 threads → exactly 1 sample),
writeFailure (unwritable dir → bounded, no retry, no host-path throw), observerBudget
(6 definitions → ≤4 recorded + overflow), maliciousFields (hostile phase value escaped +
`~truncated`), officialShape (read-only shape verify against the official jar).

## Dependencies

- JDK 17 toolchain (javac/jar/jimage), bash, sha256sum.
- ASM 9.7.1 `asm-9.7.1.jar` sha256 `8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281`
  (compile dep, shaded into the agent under `dev/turboism/validation/triprobe/shaded/asm97`).
- Gradle distribution ASM 9.7 trio (asm/asm-commons/asm-tree, build-time relocation only).
- No network, no official-class loading/execution, no Gradle module, no production/wrapper/Runner
  changes.

## Known gaps (not covered offline)

- Real host loader/CodeSource/PD strings differ from the fixture; admission props must be set by
  the reviewer-approved wrapper layer, not hard-coded here.
- The probe observes one object at one callsite; per the frozen limits it cannot speak to other
  instances, later states, or prior runs.
- The optional `b.map` type check (reflective read) is explicitly not implemented in this slice.
