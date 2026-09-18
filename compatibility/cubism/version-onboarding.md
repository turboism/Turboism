# Onboarding a New Cubism Editor Version

Maintainer checklist for admitting one exact Cubism Editor release (say `X.Y.Z`,
compact form `XYZZ`) to the runtime. The model in `index.md` applies throughout:
`DRAFT` artifacts under `mapping-packs/draft/` and `profiles/draft/` never
authorize runtime binding; runtime admission requires a matching `VERIFIED_STATIC`
record under `verification/`, a reviewed host-artifact identity, a pinned record
digest, the exact version string, the declared capability set, and selector
aliases. Unknown or mismatched inputs fail closed.

Adding a version is therefore a *reviewed* change spread across contracts,
runtime trust roots, packaging, tests, and the host-validation catalog — not a
data append. Expect every gate below to fail closed until all of them agree.

Every command in this manual was verified against this worktree
(`./gradlew tasks --all`, `<tool> --help`, or file inspection on 2026-09-17).
Replace `X.Y.Z`/`XYZZ` consistently; do not reuse a retired `mNN` token in new
asset names (`checkCodeQuality` rejects it).

## 0. Prerequisites

- The exact `Live2D_Cubism.jar` for `X.Y.Z` and, when the Core surface changed
  or is onboarded for the first time, the matching `Live2DCubismCore.jar`.
  Both stay outside the repo (ignored `cubism-ref/` or an external evidence
  store); only sanitized public contracts are committed.
- JDK `javap` on PATH (used by `cubism_core_api.py extract`; `--javap` overrides).
- A locally runnable Gradle build (`./gradlew tasks --all` should list every
  task referenced below).
- For host validation: `.env` seeded from `.env.example` with the golden
  prefix, Proton runner, host root, and a `TURBOISM_HOST_VALIDATION_FIXTURE_XYZZ`
  entry (plus optional `..._SHA256`), per
  `scripts/preview/README-host-validation-scheduling.md`.

## 1. Register the exact artifact identity

Measure the Editor artifact, then restate it in the single production trust
root:

```bash
sha256sum Live2D_Cubism.jar   # digest
stat -c %s Live2D_Cubism.jar  # byte size
```

- `runtime/.../mapping/verification/ReviewedHostArtifacts.java`: add
  `CUBISM_X_Y_Z_VERSION`, a `HostArtifactDigest` constant, and arms in
  `cubismVersionOf`, `all()`, and `admitsFullRuntime`. Keep `admitsFullRuntime`
  closed until the runtime/UI/SDK surfaces are genuinely enabled — artifact
  review and full-runtime admission are separate decisions.
- `runtime/src/test/.../ReviewedHostArtifactsTest.java`: the one test allowed
  to restate the digest literals.
- `scripts/test/check_code_quality.py`: add the digest to `REVIEWED_DIGESTS`
  (the `digests` rule then rejects any further copies outside the declaration
  sites listed in `DIGEST_DECLARATION_SITES`).
- `runtime/.../mapping/verification/CubismEditorReleaseDetector.java`: pin the
  host-declared build integer in `pinnedBuild` for `X.Y.Z` (measured from the
  `com/live2d/cubism/h` declaration class; builds are *not* formula-derived —
  5.2.03 is `502030002`, not `502030001`).
- Cross-check with the shipped CLI: `./gradlew verifyStaticHostSelectors`
  exercises records, and `ReviewedHostArtifactCli` resolves an artifact to its
  admitted version (run via `runtime` classpath or a test).

Gate: `./gradlew checkCodeQuality` (covers digests, naming, assets rules) and
the focused runtime tests under `runtime/src/test/java/dev/turboism/mapping/verification/`.

## 2. Core API observed inventory (`core-api/observed/`)

Only needed when the new Editor ships a different `Live2DCubismCore.jar`.

```bash
python3 scripts/cubism_core_api.py extract \
  --jar /path/to/Live2DCubismCore.jar \
  --cubism-version X.Y.Z \
  --output compatibility/cubism/core-api/observed/cubism-core-X.Y.Z.json
python3 scripts/cubism_core_api.py validate compatibility/cubism/core-api/observed/*.json
python3 scripts/cubism_core_api.py render \
  --inventory compatibility/cubism/core-api/observed/cubism-core-X.Y.Z.json \
  --output build/reports/cubism-core-X.Y.Z-api.md   # review artifact, not committed
```

`extract` runs `javap -public -s -constants` and records only public
declarations, JVM descriptors, and public constants. The result is `OBSERVED`
evidence (`authorizesRuntime: false`); it never authorizes binding by itself.

Then wire the new inventory into:

- `runtime/build.gradle.kts`: add `--inventory` inputs to
  `generateCorePublicApiCatalog` (and `--pack`/`--inventory` inputs to
  `generateCorePublicApiSelectorContract` when a Core mapping pack exists).
- `scripts/test/test_cubism_core_api_inventory.py`: extend the expected facts
  (class count, callable/field counts, artifact size+SHA-256, version-specific
  member presence/absence). This file is the fail-closed gate
  `checkCubismCoreApiInventory`.

## 3. Core member and selector policies (`core-api/policy/`)

Both policies bind ordered hand-reviewed rules to SHA-256 rosters of the full
classified surface. When the inventory set changes:

```bash
python3 scripts/cubism_core_policy.py bootstrap \
  --inventory compatibility/cubism/core-api/observed/cubism-core-5.2.03.json \
  --inventory compatibility/cubism/core-api/observed/cubism-core-5.3.02.json \
  --inventory compatibility/cubism/core-api/observed/cubism-core-X.Y.Z.json
# review/edit compatibility/cubism/core-api/policy/cubism-core-member-policy.json
python3 scripts/cubism_core_policy.py validate \
  --policy compatibility/cubism/core-api/policy/cubism-core-member-policy.json \
  --inventory <same inventory list>
python3 scripts/cubism_core_policy.py render-java \
  --policy compatibility/cubism/core-api/policy/cubism-core-member-policy.json \
  --output /tmp/GeneratedCorePublicApiCatalog.java --check \
  --inventory <same inventory list>   # --check: report drift, don't write
```

Same shape for the selector policy against the Core model-read packs:

```bash
python3 scripts/cubism_core_selector_policy.py validate \
  --policy compatibility/cubism/core-api/policy/cubism-core-selector-policy.json \
  --pack compatibility/cubism/mapping-packs/draft/cubism-5.2.03-core-model-read.json \
  --pack compatibility/cubism/mapping-packs/draft/cubism-5.3.02-core-model-read.json \
  --pack compatibility/cubism/mapping-packs/draft/cubism-X.Y.Z-core-model-read.json
python3 scripts/cubism_core_selector_policy.py bootstrap ...  # re-derive roster when rules change
```

Offline gates: `./gradlew checkCubismCoreApiInventory
checkCubismCoreMemberPolicy checkCubismCoreSelectorPolicy` — all three pin
counts and roster digests; update `scripts/test/test_cubism_core_*.py`
expectations when the surface legitimately grows.

## 4. VERIFIED_STATIC records (`verification/`)

Records are sanitized public contracts (selectors by
kind/owner/member/descriptor/access flags), produced by host-side observation
tooling kept outside this repository and committed by review (see
`manifest-provenance-report.md`). There is no in-repo generator; author each
`cubism-X.Y.Z-<family>.json` to satisfy
`dev.turboism.mapping.verification.StaticVerificationRecordValidator`:

- `format: turboism.static.verification.record`, `schemaVersion: 1`,
  `status: VERIFIED_STATIC`, `evidenceType: JAR_METADATA`, `evidencePath` equal
  to the record's own repository-relative path, `artifact.name` a bare file name
  (`Live2D_Cubism.jar`) with the reviewed `size`/`sha256`, one exact
  `MAJOR.MINOR.PATCH` `cubismVersion`, non-empty unique `capabilityIds`, and at
  least one selector whose own `status` is `VERIFIED_STATIC`.

Verify each record against the exact artifact offline before committing:

```bash
./gradlew verifyStaticHostSelectors \
  -PturboismStaticVerificationRecord=compatibility/cubism/verification/cubism-X.Y.Z-<family>.json \
  -PturboismHostArtifact=/path/to/Live2D_Cubism.jar
```

This runs `dev.turboism.mapping.verification.StaticVerificationCli`
(exit 0 only when artifact identity and every selector verify).

## 5. DRAFT mapping packs and the profile

Author `mapping-packs/draft/cubism-X.Y.Z-<family>.json` (`format:
turboism.mapping.pack`, `status: DRAFT`, `source` describing provenance, one
`entries[]` per selector with semantic `name`/`runtime`/`descriptor`/`profile`
and `x.verification` evidence). For the family bound to a verification record,
set `metadata.inventoryRef` to the record path and `metadata.artifactSha256` to
the reviewed artifact digest; keep `metadata.selectorCount`, `capabilityCount`,
`capabilityIds`, and `verificationRecordSha256` (SHA-256 of the record file
bytes) exactly in sync — this metadata has drifted before (see the provenance
report) and only part of it is currently enforced.

Then author `profiles/draft/cubism-X.Y.Z.json` (`format: turboism.profile`,
`status: DRAFT`, `verifiedBy: "none"`, `verifiedAt: null`) listing the pack IDs.

The review pipeline covers *one* surgical change class — replacing the
`runtime` field of an existing `kind: "class"` entry:

```bash
scripts/dev/mapping-review.sh generate \
  --artifact /path/to/Live2D_Cubism.jar \
  --pack compatibility/cubism/mapping-packs/draft/cubism-X.Y.Z-<family>.json \
  --semantic-name <pack semanticName> --expected-old-runtime <internal/name> \
  --caller-owner <internal/name> --caller-name <m> --caller-descriptor <desc> \
  --target-method-name <m> --target-method-descriptor <desc> \
  [--invocation ANY|STATIC|INSTANCE] [--output build/worktree/<id>/mapping-review]
# review the generated candidate/diff, then mark review.json decision APPROVED
scripts/dev/mapping-review.sh apply \
  --candidate <stem>.candidate.json --review <stem>.review.json \
  --artifact /path/to/Live2D_Cubism.jar            # dry run
scripts/dev/mapping-review.sh apply ... --write    # atomic pack replacement
```

The wrapper calls `./gradlew mappingReview` with a Base64 args file
(`-PturboismMappingReviewArgsFile`); `-PturboismMappingReviewArgs` is rejected.
Apply re-validates everything, requires `decision: APPROVED`, matching candidate
and pack digests, the same artifact file name, and refuses any `semanticName`
referenced by a VERIFIED_STATIC record (`PACK_REFERENCED_BY_VERIFIED_STATIC`).
The generated `diff.json` is presentation-only. Schema checks live in
`dev.turboism.mapping.draft` (`MappingUpdateCandidateValidator`,
`MappingUpdateDiffValidator`, `MappingReviewValidator`,
`DraftMappingPackValidator`, `JarScanPolicy` bounds, `ForbiddenSelectorTerms`
compliance gate); the wrapper transport itself is gated by
`checkMappingReviewWrapperArgs`.

Gates: `:testing:integration-tests:test` runs `MappingPackDraftImportTest`
(schema, `DRAFT`-only, unique semantic names, no raw/private runtime fragments,
high/medium confidence only, provenance metadata) and
`ProfileDraftImportTest` (schema, unique profileId, `DRAFT`/`none`/`null`).

## 6. Runtime trust roots and manifests

For each capability family admitted on `X.Y.Z`:

- `runtime/.../mapping/verification/*VerificationManifest.java`: add
  `RECORD_X_Y_Z` (`ReviewedSliceRecord` = artifact + `verificationId` +
  `recordSha256` of the record file bytes + `cubismVersion` + `profileId`) and
  the version's capability/alias sets, mirroring existing records. Record-byte
  pins must move with any later reviewed selector addition.
- `runtime/.../mapping/verification/selector/*.java`: add or extend the
  `REQUIRED_ALIASES` contracts the manifest references.
- `CorePublicApiTrustRoots.java`: add the Core `verificationId` arm only if a
  `cubism-X.Y.Z.core-model-read` record is admitted.
- `CubismEditorAvailabilityPolicy.REVIEWED_VERSIONS`: add `X.Y.Z` when the
  version is admitted — this *widens* every unrestricted or ranged
  `@CubismEditor` SDK declaration to the new version automatically; audit the
  annotated surface (`sdk/src/main/java`) before flipping, and review
  `sdk/src/test/.../CubismEditorAvailabilityContractTest` expectations.
- `Verified*ResolverFactory` classes and `TurboismAgent` wiring may carry
  version-scoped validation modes/tokens (e.g. `EXACT_5303_*`); mirror the
  existing pattern rather than widening silently.

## 7. Packaging

- `bootstrap/build.gradle.kts` `processResources`: add every new
  `verification/cubism-X.Y.Z-*.json` filename — this explicit list is what lands
  in `META-INF/turboism/verification/` inside the agent JAR; a missing entry
  means a fail-closed host start.
- `gradle/distribution-preview.gradle.kts` `verifyPreviewAgentJar` pins one
  record entry as a packaging smoke check; extend if the probe set changes.
- `./gradlew previewBundle` rebuilds the relocatable preview directory used by
  host-validation packaging tasks.

## 8. Test pins and static gates

- `testing/.../mapping/StaticVerificationRecordRepositoryTest.java`: add a
  `SliceExpectation` per record — verificationId, slice, version, profile,
  capabilities, artifact size+SHA-256, **record SHA-256**, selector count,
  required/used alias sets, pack path, profile path, slice kind.
- `MappingPackDraftImportTest`/`ProfileDraftImportTest`: adjust per-version
  pins (entry counts, `capabilityCount`, metadata provenance).
- `runtime/src/test/.../CubismEditorIdentityRoutingTest.java`,
  `ReviewedHostArtifactsTest.java`, `CubismEditorReleaseDetectorTest.java`:
  extend to the new identity.
- `scripts/test/check_editor_model_aliases.py`: extend `BASE_RECORDS` /
  `ADDITIVE_RECORD`/`ADDITIVE_ALIASES` when Editor-model admission changes —
  admitted-but-unconsumed aliases fail (`UNUSED_ALIAS_MAXIMUM = 0`).
- `compatibility/cubism/index.md`: update the version list.
- `editor-commands/top-menu-parameterized-static-contract.tsv`: add
  `vX_Y_ZZ_*` columns or record why coverage stays asymmetric; the SDK test
  `EditorParameterizedStaticEvidenceTest` pins row semantics.

## 9. Host validation enablement

- `scripts/preview/host-validation-tasks.json`: add `XYZZ` to the `versions`
  list of each task family that should run on the new version.
- `scripts/preview/host_validation.py`: extend the admitted version set
  (`{"5203","5302","5303"}` today).
- `scripts/preview/run-cubism-host-validation.sh` (and family wrappers
  `run-*-host-validation.sh`): add the `XYZZ` `case` arm — install path under
  the golden prefix (`cubism_win`/`cubism_rel`) and `reviewed_jar_sha256`.
- `gradle/verification.gradle.kts`: register `validate*HostXYZZ` tasks for the
  families that need exact-host evidence (existing registrations for
  5203/5302/5303 are the template), plus `package*HostValidation` if a new
  bundle is required.
- `.env.example`: add `TURBOISM_HOST_VALIDATION_FIXTURE_XYZZ` (and the
  `_SHA256` placeholder) if the version uses its own fixture.

Queue usage (all verified; the worker serializes one Cubism session across all
worktrees and versions):

```bash
python3 scripts/preview/host_validation.py list                 # task catalog
python3 scripts/preview/host_validation.py plan <task>:XYZZ     # dry plan
python3 scripts/preview/host_validation.py prepare <task>:XYZZ --run-label <label>
python3 scripts/preview/host_validation.py submit --prepared <id> --request-id <key> --json
python3 scripts/preview/host_validation.py serve                # separate terminal: the worker
python3 scripts/preview/host_validation.py status <job> --json
python3 scripts/preview/host_validation.py wait <job>
```

`run TASK...` is a convenience prepare+submit+wait client. PASS requires
structured terminal evidence plus exact host identity, fixture hashes, normal
exit, and task-owned cleanup — see `README-host-validation-scheduling.md` for
recovery/quarantine semantics. A PASS is a per-feature exact-host gate; it is
never implied by the scheduler's own tests.

### Timing knobs for large fixtures

Opening a large fixture project under Proton can take several minutes, so the
validation harness exposes timing overrides. Defaults keep small-fixture runs
unchanged:

- `FPS_WINDOW_WAIT_SECONDS` (environment, `fps-resize-driver.sh`): seconds to
  wait for the visible project-titled window of the exact JVM before giving up
  (default 600). Iterations are one-second sleeps, so the name is the unit.
- `-Dturboism.validation.fps.hostReadySeconds=<s>` (FpsHostValidationPlugin):
  host-READY settle timeout in seconds (default 180).
- `-Dturboism.validation.fps.settleSeconds=<s>` and
  `-Dturboism.validation.fps.sustained=<true|false>` (FpsHostValidationPlugin):
  pre-sample settle window and sustained-jank sampling mode.
- `-Dturboism.validation.resource.zoomIterations=<n>` (NativeResourceHostAgent):
  zoom stress-loop length (default 60); the actual value is recorded into the
  result properties as `zoom.iterations`.

Malformed whole-second overrides fall back to their defaults with a warning
instead of aborting plugin loading; set them via the task entry in
`host-validation-tasks.json` (`-D...` JVM flags) or the environment for shell
drivers.

## 10. Verification ladder and release checks

Per `ARCHITECTURE.md` §11, run only what the change justifies:

```bash
./gradlew devCheck              # compile + permanent boundaries (always)
./gradlew checkIntegration      # packaged + cross-module gates incl. the
                                # cubism-core policy checks and editor-alias admission
./gradlew checkCompletedCommit  # full repository gate for a coherent change
./gradlew checkRelease          # + supply-chain, SDK historical baselines,
                                # installer, release-tooling audits
```

Release-side verification (CI is the authority; these are the local handles):

- `python3 scripts/release/verify-release.py --version <v> --channel <stable|beta|nightly> --dist <dist> --release-plugins packaging/release-plugins.txt` — fail-closed assembled-release check used by `release.yml`, `release-publisher.yml`, `unified-channel-checks.yml`.
- `python3 scripts/release/product.py inspect|info|candidate|promote` — identity inspection and source-bound candidate dispatch (dry run unless `--submit`).
- `python3 scripts/release/turboism-release.py build|plan|verify|publish|resume|release` — orchestrated release driver.
- `./gradlew checkReleaseTooling` — offline gate over the release scripts.

## Appendix A — touch-point checklist

| # | Touch point | Type |
| --- | --- | --- |
| 1 | `ReviewedHostArtifacts.java` (+ test, `check_code_quality.py` digests) | artifact identity |
| 2 | `CubismEditorReleaseDetector.pinnedBuild` (+ detector/routing tests) | declared release identity |
| 3 | `core-api/observed/cubism-core-X.Y.Z.json` + `runtime/build.gradle.kts` inputs + `test_cubism_core_api_inventory.py` facts | tool-produced inventory |
| 4 | `core-api/policy/*.json` rosters + `test_cubism_core_{member,selector}_policy.py` | mixed policy |
| 5 | `verification/cubism-X.Y.Z-*.json` (one per family) | VERIFIED_STATIC records |
| 6 | `mapping-packs/draft/cubism-X.Y.Z-*.json` + `profiles/draft/cubism-X.Y.Z.json` | DRAFT catalogues |
| 7 | `*VerificationManifest.java` records + `selector/*Contract.java` + `CorePublicApiTrustRoots` | runtime trust roots |
| 8 | `CubismEditorAvailabilityPolicy.REVIEWED_VERSIONS` + `@CubismEditor` surface audit | SDK availability |
| 9 | `bootstrap/build.gradle.kts` record list (+ `previewBundle` smoke check) | packaging |
| 10 | `StaticVerificationRecordRepositoryTest`, `MappingPackDraftImportTest`, `ProfileDraftImportTest`, `check_editor_model_aliases.py` | test pins |
| 11 | `host-validation-tasks.json`, `host_validation.py` versions, `run-*-host-validation.sh` arms, `.env.example`, `validate*Host*` tasks | host validation |
| 12 | `index.md`, `editor-commands/*.tsv` | public docs/evidence |

## Appendix B — verified command reference

- `./gradlew devCheck | checkIntegration | checkCompletedCommit | checkRelease | checkReleaseTooling`
- `./gradlew checkCodeQuality | checkEditorModelAliases | checkCubismCoreApiInventory | checkCubismCoreMemberPolicy | checkCubismCoreSelectorPolicy | checkPackageLayout | checkModuleBoundaries | checkMappingReviewWrapperArgs`
- `./gradlew verifyStaticHostSelectors -PturboismStaticVerificationRecord=<record> -PturboismHostArtifact=<jar>`
- `./gradlew mappingReview -PturboismMappingReviewArgsFile=<file>` (prefer `scripts/dev/mapping-review.sh`)
- `./gradlew runtime:generateCorePublicApiCatalog | runtime:generateCorePublicApiSelectorContract` (run by `compileJava`; outputs live under `runtime/build/generated/`)
- `./gradlew generateSdkApiReport | generateSdkApiBaseline -PturboismSdkBaselineOutput=<outside sdk/api-contracts/baselines> -PturboismSdkBaselineRole=<pre-phase|exact> -PturboismSdkBaselineCommit=<40-hex> | checkSdkApiBaselineTool | checkSdkApiReferenceBuilder | checkSdkV*ExactApiCompatibility | checkSdkV8Linkage | checkTextureAtlasSdkV7Linkage`
- `./gradlew previewBundle | printBuildInfo | validatePluginMeta | runtime:legacyCubismEvidenceTest`
- `python3 scripts/cubism_core_api.py extract|validate|render` (see `--help`)
- `python3 scripts/cubism_core_policy.py bootstrap|validate|render-java|render`
- `python3 scripts/cubism_core_selector_policy.py bootstrap|validate|render-java`
- `python3 scripts/preview/host_validation.py list|plan|prepare|run|submit|status|wait|cancel|events|serve|recover`
- `python3 scripts/release/verify-release.py --version ... --dist ... --release-plugins ...`
- `python3 scripts/release/product.py inspect|info|candidate|promote`
- `python3 scripts/release/turboism-release.py build|plan|verify|publish|resume|release`
- `python3 scripts/test/check_editor_model_aliases.py <repo-root> [--report]`
- `python3 scripts/test/check_code_quality.py <repo-root> [--rules javadoc,digests,naming,assets]`
