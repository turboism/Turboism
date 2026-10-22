# Onboarding a New Cubism Editor Version

Reviewed identity, complete `VERIFIED_STATIC` records and exact-artifact selector
verification remain separate requirements. DRAFT packs/profiles never authorize
runtime binding; unknown or mismatched inputs fail closed. See [index.md](index.md)
for admission and the structurally verified `COMPATIBLE` path.

## 1. Review the release row and generate the tables

Keep licensed Editor/Core JARs and private fixtures outside Git. Measure the exact
Editor JAR with `sha256sum` and `stat -c %s`, then append a row to
`compatibility/cubism/reviewed-releases.json`: exact version, observed build,
artifact name/size/SHA-256, observed Core tuple, `fullRuntime` and installed
`hostLabel`. Build numbers are observed, never inferred from the version.

```bash
python3 scripts/generate_reviewed_releases.py
```

Review the generated `ReviewedHostArtifacts.java`, `ReviewedReleaseTable.java`,
internal `ReviewedVersions.java` and `.env.example`; do not hand-edit them.
`fullRuntime` retains its existing meaning; approving a row does not enable every
UI slice. `ReviewedVersions` is package-private in the SDK command and MCP packages; the
runtime copy lives in `dev.turboism.internal`. One generator template owns all
three copies because SDK/plugin loader boundaries forbid sharing runtime helpers.
The SDK helper is excluded from Javadoc and public API baselines by visibility.
Public SDK signatures must not expose it.

### What automatically widens, and what verifies it

`since(first)` selects all reviewed releases at or after its boundary; `line("5.3")`
selects the reviewed members of that minor line. `between(first,last)` stays bounded.
Adding a canonical release therefore widens applicable selections, including SDK
command enums. Unrestricted/ranged `@CubismEditor` coverage also retains the
existing `REVIEWED_VERSIONS` widening semantics. A selection is a support policy,
not evidence that a host fact has been verified.

R1's `r1_family_range_coverage` checks every range in a verification family's
`files` templates and `coverageSources` against that family's materialized
`VERIFIED_STATIC` records. It uses the existing metadata in
`scripts/verification-sources/families/*.json`, including across minor lines.
R2 additionally rejects missing higher patches and lost capabilities/aliases
within a minor line, subject to the reviewed monotonicity exceptions. Neither
check proves selector correctness: run `verifyStaticHostSelectors` below against
the exact artifact. Runtime providers still require artifact identity, resolver
admission and successful binding; see `VerifiedMemberResolver` and each provider's
`Verified*Installer`/manifest contract.

There is **no automated proof of native command-id meaning** for newly widened
`EditorCommand`, `EditorFileCommand` or `EditorParameterizedCommand` entries, nor
of every file-scoped profile/layout choice. Family record coverage alone cannot
supply this proof. `VerifiedEditorCommandAdapter` discovers the exact native menu
id and returns `UNAVAILABLE` when it is missing; that does not detect an id reused
for different behavior. Typed operations in `VerifiedTypedEditorCommandOperations`
rely on their resolver selectors and their own state checks.

Before accepting a new row, the maintainer must inspect all affected
`ReviewedVersions.since`/`line` calls (search production Java and verification
source templates), verify command ids and typed command behavior against the new
Editor, and review file-scoped selector/class/layout facts against that artifact.
Record the observations in the affected family evidence or command review. If a
site has not been verified, keep it bounded with `between` or defer the release;
do not treat a passing static gate or runtime discovery as semantic verification.

## 2. Author family evidence and materialize records and drafts

Review or extend `compatibility/cubism/verification-src/<family>.json` for each
supported family. Add evidence under `releases`; explicitly extend applicable
selector, capability, artifact and layout ranges while preserving `mappingId`s.
New selector observations require the exact licensed artifact and human review.
Extend the corresponding records/coverage declarations in
`scripts/verification-sources/families/*.json` and template blocks as needed;
`:runtime:generateVerificationSources` renders Java from these inputs.

```bash
python3 scripts/materialize_verification_records.py
env -u WAYLAND_DISPLAY xvfb-run -a ./gradlew materializeDraftContracts
```

Records live in `build/generated/verification/`; DRAFT packs and profiles live in
`build/generated/draft/mapping-packs/` and `build/generated/draft/profiles/`.
These outputs are not committed. Author draft alias/template/range declarations
in `compatibility/cubism/draft-src/`; use `editor-model-read.json` for editor-model
selectors. `scripts/materialize_draft_contracts.py` owns draft profile/pack
assertions. Do not restore committed draft copies. See the family source READMEs
for export/import and rollback commands.

Verify each materialized family against its exact Editor/Core artifact:

```bash
env -u WAYLAND_DISPLAY xvfb-run -a ./gradlew verifyStaticHostSelectors \
  -PturboismStaticVerificationRecord=build/generated/verification/cubism-X.Y.Z-FAMILY.json \
  -PturboismHostArtifact=/path/to/Live2D_Cubism.jar
```

Use the matching Core JAR for Core records. Exit 0 requires matching artifact
identity and all selectors to verify. Historical `evidencePath`/`inventoryRef`
spellings can still reference `compatibility/cubism/verification/`; the metadata
reader maps them to materialized records. They do not name files to commit.

## 3. Review pins, policy and validation coverage

- Update hook pins with `python3 scripts/generate_class_pins.py update --jar
  /path/to/Live2D_Cubism.jar --version X.Y.Z`; investigate missing classes.
- Review Core inventory, selector contracts and policy following
  [core-api](core-api/). An OBSERVED inventory does not authorize binding.
- Update identity/routing tests, alias-consumption contracts, probe close routes,
  `index.md` coverage and command evidence where applicable.
- Refresh host task versions with `python3 scripts/check_reviewed_releases.py
  --update-host-tasks`. R4 derives them from reviewed releases, verified capability
  records, class pins and task/plugin requirements. Review the result and any
  explicit task exceptions; do not extend lists mechanically. Add required Gradle
  host tasks or install-path handling. Private fixture paths/hashes belong in
  ignored `.env`.
- Refresh committed compatibility sidecars with
  `python3 scripts/generate_compatibility_matrix.py` and review the diff.

## 4. Check the result

```bash
env -u WAYLAND_DISPLAY xvfb-run -a ./gradlew devCheck checkCompletedCommit
env -u WAYLAND_DISPLAY xvfb-run -a ./gradlew previewBundle checkPreviewBundleLayout
```

`checkReviewedReleases` and its SelfTest replace the retired checkers:

| Rule | Contract / former checker |
| --- | --- |
| R1 | Canonical/generated integrity and remaining completeness checks; materialized record index, IDs and manifest pins; family range coverage. Replaces `check_version_set_completeness.py` and relevant `verification_record_index.py` rules. |
| R2 | Patch monotonicity, capability/alias closure and evidence exceptions. Replaces `check_version_monotonicity.py`; keeps `version-monotonicity-exceptions.json` semantics. |
| R3 | Production bare version collection ban, plus evidence-backed residual literals. Replaces `check_version_set_registry.py`; no registry remains. |
| R4 | Derived host task coverage and shell wrapper policy. Replaces `check_host_validation_versions.py`. |

Generated array/pack repetition checks are unnecessary: generators check their
outputs against authoritative sources. Draft assertions belong to the draft
materializer. `checkMaterializedRecords` checks record bytes;
`check_sdk_cubism_annotations.py` remains independent.

For a necessary literal exception, edit
`scripts/reviewed-release-literal-exceptions.json`: give the production `path`,
`kind` and concrete `evidence` explaining the host fact. File exceptions permit
residual fact tables, never bare version Sets/Lists. A collection exception also
requires exact `versions` and `count`. Stale, duplicate, evidenceless or drifting
entries fail R3. Prefer a bounded range when it expresses the actual policy.

Run `checkRelease` before release: its live SDK exact baseline rejects public API
additions/removals; the development gates do not freeze the SDK surface. Baselines
are reviewed artifacts and must not be regenerated to conceal unintended API.

Real-host validation uses the [queue](../../scripts/preview/README-host-validation-scheduling.md):
`host_validation.py list`, `plan <task>:XYZZ`, then `run <task>:XYZZ` with a worker.
A PASS requires exact-host identity, fixture hashes, terminal evidence and cleanup;
offline gates do not imply a host PASS.
