# Onboarding a New Cubism Editor Version

This is the Phase 3 workflow for one exact `X.Y.Z` release (compact `XYZZ`).
Reviewed identity, complete `VERIFIED_STATIC` records and exact-host selector
verification remain separate requirements. DRAFT packs/profiles never authorize
runtime binding; unknown or mismatched inputs still fail closed. See [index.md](index.md)
for admission and the structurally verified `COMPATIBLE` path.

The design target is **three hand-edit sites plus commands**: the release table,
affected family sources, and host-validation exceptions. This is not yet a
three-file onboarding procedure: multiple families may need edits, ranges and
validation task versions still need manual extension, and the remaining work
listed below has not been automated. There is no `onboard_cubism_version.py` yet.

## 1. Review the release row, then generate the Java table

Keep licensed Editor/Core JARs and private fixtures outside Git. Measure the exact
Editor JAR with `sha256sum` and `stat -c %s`, then append a row to
`compatibility/cubism/reviewed-releases.json`: exact version, observed build,
artifact name/size/SHA-256, observed Core tuple, `fullRuntime` and installed
`hostLabel`. Build numbers are observed, never inferred from the version.

```bash
python3 scripts/generate_reviewed_releases.py
```

Review the generated `ReviewedHostArtifacts.java`, `ReviewedReleaseTable.java`
and `.env.example` changes; do not hand-edit them. Java trust-root consumers,
Python readers and host identity catalogs derive from the JSON. `fullRuntime`
retains its existing meaning; approving the row does not enable every UI slice.
Adding a row automatically widens unrestricted/ranged `@CubismEditor` coverage
through `REVIEWED_VERSIONS`, so audit the affected SDK surface and slice coverage.

## 2. Author family sources, then materialize complete records

Review or extend `compatibility/cubism/verification-src/<family>.json` for each
supported family. Add the release's evidence fields under `releases`; explicitly
extend applicable selector, capability, artifact and layout ranges and preserve
original `mappingId` values. Neither range extension nor evidence review is
automatic, even for an otherwise unchanged patch. New selector observations
still require the exact licensed artifact and human review.

```bash
python3 scripts/materialize_verification_records.py
# Or: env -u WAYLAND_DISPLAY xvfb-run -a ./gradlew materializeVerificationRecords
```

Complete records now live in **`build/generated/verification/`**, are not committed,
and are copied unchanged into `META-INF/turboism/verification/` in the agent.
Gradle source generation, packaging and gates depend on the materializer. Run it
first for standalone Python tools or IDE tests that read repository files.
Do not hand-edit generated records or restore the old packaging filename list.

Verify each materialized family against its exact Editor/Core artifact:

```bash
env -u WAYLAND_DISPLAY xvfb-run -a ./gradlew verifyStaticHostSelectors \
  -PturboismStaticVerificationRecord=build/generated/verification/cubism-X.Y.Z-FAMILY.json \
  -PturboismHostArtifact=/path/to/Live2D_Cubism.jar
```

Use the matching Core JAR for Core records. Exit 0 requires matching artifact
identity and all selectors to verify. Historical `evidencePath` and pack
`inventoryRef` values retain the old `compatibility/cubism/verification/` spelling
for byte/provenance compatibility; the metadata reader maps record references
to the materialized directory. They are not instructions to commit those files.
For export/import and rollback commands, see [verification-src/README.md](verification-src/README.md).

## 3. Complete the still-manual onboarding work

Phase 3 removes repeated release facts and committed record copies. It does not
yet remove these review/update sites:

- **Manifest/selector generation:** extend applicable `records`/coverage/pack
  declarations in `scripts/verification-sources/families/*.json` and version
  blocks in `scripts/verification-sources/templates/`. Template looping was
  deferred. `:runtime:generateVerificationSources` renders the Java; edit inputs,
  not generated classes. Review remaining version-specific resolver/installer
  sets and their registry/monotonicity exceptions without silently widening them.
- **Draft catalogs and tests:** create/review `mapping-packs/draft/` packs and the
  `profiles/draft/cubism-X.Y.Z.json` catalog. Sync record-derived pack metadata
  with `python3 scripts/sync_draft_pack_metadata.py`, after materializing. Update
  repository expectations, identity/routing tests, alias-consumption contracts,
  probe close routes, `index.md` coverage and command evidence where applicable.
- **Hook pins:** run `python3 scripts/generate_class_pins.py update --jar
  /path/to/Live2D_Cubism.jar --version X.Y.Z`; review the diff and investigate
  missing classes. New class keys and support decisions still require review.
- **Core evidence/policies:** produce the per-version observed inventory with
  `scripts/cubism_core_api.py extract --jar /path/to/Live2DCubismCore.jar
  --cubism-version X.Y.Z --output compatibility/cubism/core-api/observed/cubism-core-X.Y.Z.json`.
  Wire applicable `runtime/build.gradle.kts` inventory/pack inputs; review policy
  rosters and `scripts/test/test_cubism_core_*.py` facts. An OBSERVED inventory
  alone does not authorize runtime binding.
- **Host-validation catalog:** manually extend relevant `versions` lists in
  `scripts/preview/host-validation-tasks.json`. Capability-based default expansion
  and exception-only editing are still a target, not implemented. Add any needed
  Gradle `validate*HostXYZZ` tasks, probe support or exceptional install-path
  handling. Configure private fixture paths/hashes in ignored `.env`; fixture
  placeholders and admitted host identities are generated from the release row.

Compatibility docs/sidecars can be refreshed with
`python3 scripts/generate_compatibility_matrix.py`; review the output. Existing
developer matrix tools write scratch records into the materialized directory;
fold reviewed changes into family sources before rebuilding (see the source
README). They do not replace exact-artifact review.

## 4. Validate and package

```bash
env -u WAYLAND_DISPLAY xvfb-run -a ./gradlew devCheck checkIntegration checkCompletedCommit
env -u WAYLAND_DISPLAY xvfb-run -a ./gradlew previewBundle checkPreviewBundleLayout
```

`checkMaterializedRecords` checks table/family expansion byte for byte;
`checkVerificationRecordIndex` checks the generated filename/byte set and manifest
pins; `checkVersionSetCompleteness` checks generated data against the table.
Packaging copies the materializer task output automatically, with unchanged
resource names. `checkPreviewBundleLayout` invokes `verifyPreviewAgentJar`.

For real-host validation, use the queue described in
[scheduling](../../scripts/preview/README-host-validation-scheduling.md):
`host_validation.py list`, `plan <task>:XYZZ`, then `run <task>:XYZZ` with the
worker running. A PASS requires exact-host identity, fixture hashes, structured
terminal evidence and task-owned cleanup; offline gates do not imply a host PASS.
Run `checkRelease` and `checkReleaseTooling` when preparing a release.
