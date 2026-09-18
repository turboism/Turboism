# Manifest Provenance Audit

Read-only audit of the artifact families that bind Turboism to exact Cubism
Editor/Core versions: which are generated, which are hand-maintained, and where
the two sides can drift. Scope: `compatibility/cubism/` (96 tracked files) and
`runtime/src/main/java/dev/turboism/mapping/verification/` (102 Java sources,
44 of them under `selector/`). Evidence is cited per family; every command in
the appendix was run on this worktree.

## Data flow

```text
licensed host artifacts (outside the repo; ignored cubism-ref/ boundary)
  |  (host-side observation tooling — not tracked here)
  v
compatibility/cubism/verification/*.json        VERIFIED_STATIC records (38)
compatibility/cubism/mapping-packs/draft/*.json DRAFT selector catalogues (49)
compatibility/cubism/profiles/draft/*.json      DRAFT profiles (3)
compatibility/cubism/core-api/observed/*.json   OBSERVED Core inventories (2)
compatibility/cubism/core-api/policy/*.json     member/selector policies (2)
compatibility/cubism/editor-commands/*.tsv      static command contract (1)
  |  (checked-in sources of truth)
  v
runtime/.../verification/*VerificationManifest.java   per-family trust roots (21)
runtime/.../verification/selector/*.java              REQUIRED_ALIASES contracts (44)
runtime/.../verification/ReviewedHostArtifacts.java   artifact size+SHA-256
runtime/.../verification/CubismEditorReleaseDetector  pinned declared builds
runtime/.../verification/CorePublicApiTrustRoots      Core record identities
  |  (two generated exceptions, emitted into runtime/build/, never checked in)
  v
runtime/build/generated/.../GeneratedCorePublicApiCatalog.java   <- cubism_core_policy.py render-java
runtime/build/generated/.../selector/CorePublicApiSelectorContract.java <- cubism_core_selector_policy.py render-java
  |
  v
bootstrap fat JAR: META-INF/turboism/verification/*.json  (explicit filename list
in bootstrap/build.gradle.kts) -> extracted at agent start -> verified at runtime
by PinnedVerifiedResolverWorkflow (record SHA-256 + artifact size/SHA-256 +
selector re-verification + class-source attestation)
```

## Verdict summary

| Family | Count | Verdict | Evidence |
| --- | --- | --- | --- |
| `verification/*.json` | 38 | Hand-maintained (sanitized public contracts) | No in-repo producer; `evidenceType: JAR_METADATA`, `verifiedBy: turboism-*-static-verifier`; imported by `44b446d3b`/`a09ddc0a0` |
| `mapping-packs/draft/*.json` | 49 | Hand-maintained; surgical tool updates only | `mappingReview` supports only `UPDATE_CLASS_RUNTIME` on `kind=class` entries and refuses VERIFIED_STATIC-referenced selectors (`PACK_REFERENCED_BY_VERIFIED_STATIC`) |
| `profiles/draft/*.json` | 3 | Hand-maintained | No generator; `ProfileValidator` + `ProfileDraftImportTest` only validate |
| `core-api/observed/*.json` | 2 | Tool-produced, checked in | `scripts/cubism_core_api.py extract --jar <Live2DCubismCore.jar>` runs `javap -public -s -constants`; counts/digest pinned by `checkCubismCoreApiInventory` |
| `core-api/policy/*.json` | 2 | Mixed: hand-authored rules + machine roster digests | `cubism_core_policy.py` / `cubism_core_selector_policy.py` `bootstrap`/`validate`/`render-java`; roster SHA-256s pinned in policy files and gate tests |
| `editor-commands/*.tsv` | 1 | Hand-maintained | Introduced by `e4588879e`; consumed by `sdk` `EditorParameterizedStaticEvidenceTest`; carries only `v5_2_03_*`/`v5_3_02_*` columns |
| `runtime .../verification/*VerificationManifest.java` | 21 | Hand-maintained | No generator exists; each embeds per-version `ReviewedSliceRecord` (verificationId, record SHA-256, cubismVersion, profileId) plus capability/alias sets |
| `runtime .../verification/selector/*.java` | 44 | Hand-maintained | Plain constant classes; the only generated sibling (`CorePublicApiSelectorContract`) is emitted into `runtime/build/`, not this source set |
| `ReviewedHostArtifacts`, `CubismEditorReleaseDetector`, `CorePublicApiTrustRoots`, `CubismEditorAvailabilityPolicy`, `bootstrap/build.gradle.kts` record list | — | Hand-maintained | Version set is restated in each; see drift register |
| `mapping/draft/*` (pipeline classes) | 24 | Hand-maintained tooling | `MappingReviewCli`/`MappingReviewService`/`MappingUpdate*Validator`/`MappingReviewValidator` — the pipeline is a reviewer tool, not a manifest producer |

Generation coverage is asymmetric by design today: `runtime:generateCorePublicApiCatalog`
and `runtime:generateCorePublicApiSelectorContract` cover only the Cubism **Core**
side (`com.live2d.sdk.cubism.core` public surface). Nothing generates the Editor-side
manifests, the 44 selector contracts, the verification records, the draft packs, or
the bootstrap packaging list.

Rough split across the audited surface: 2 tool-produced files (`core-api/observed`),
2 mixed files (`core-api/policy`), 2 build-time generated Java sources — everything
else, including all 38 verification records, 49 packs, 21 manifests, 44 selector
contracts, and every version pin, is hand-maintained (~97% of files by count).

## Drift register (ranked)

1. **Record-byte pins are restated in at least three places.** Each
   `*VerificationManifest` pins `recordSha256`; `StaticVerificationRecordRepositoryTest`
   re-pins the same digests plus selector counts; `CubismEditorIdentityRoutingTest`
   pins the editor-model record; draft-pack `metadata.verificationRecordSha256` may
   restate it again. A selector addition to a record therefore needs four
   coordinated edits. This already broke once: `112a917aa` added the guarded
   `texture_atlas.native_item_scale` selector to all three editor-model records
   without updating packs or pins; `8ad4e5a69` and `648425ed2` repaired it.
   *Live evidence on this branch:* `mapping-packs/draft/cubism-5.3.03-editor-model-read.json`
   still carries `selectorCount: 620` and `verificationRecordSha256: 7e03714a…`
   while the record has 621 selectors and hashes to `4e92ece4…`; the follow-up
   resync commit (`45cc8c27f`, also adds a negative tamper matrix) is not an
   ancestor of this branch.
2. **The agent packaging list is a manual filename roster.**
   `bootstrap/build.gradle.kts` `processResources` enumerates every record file;
   forgetting a new record leaves the agent JAR without it and runtime admission
   fails closed only at host start.
3. **`CubismEditorAvailabilityPolicy.REVIEWED_VERSIONS` widens silently.**
   Adding a version auto-extends every unannotated or ranged `@CubismEditor`
   declaration to it; nothing forces a per-API review.
4. **`CubismEditorReleaseDetector.pinnedBuild` and `CorePublicApiTrustRoots` are
   closed switches.** A new version fails closed until both are edited — safe but
   easy to miss outside a checklist.
5. **Enforcement regexes are stale for 5.3.03.** `check_code_quality.py`
   `VERSION_SUFFIXED_TYPE` rejects only `52|53|5203|5302` endings, yet
   `VerifiedCubism5303TextureAtlasSelectorContract`/`...LayoutProvider` were
   merged (`924630474`) after the rule (`8b1b0cce4`) — the rule never learned `5303`.
6. **Editor-side alias accounting is asymmetric.** `checkEditorModelAliases`
   derives production-vs-record admission only for the `cubism.editor-model`
   family; other families rely solely on `StaticVerificationRecordRepositoryTest`
   pins (selector counts + bidirectional pack/record identity).
7. **Host-validation catalogs lag by hand.** `host-validation-tasks.json`
   versions, `host_validation.py`'s `{5203,5302,5303}` set, `run-*-validation.sh`
   `case` arms, `.env.example` fixture variables, and Gradle `validate*Host*`
   task registrations all restate the version set (see `8edeac163`, which fixed
   exactly such a lag for 5303).
8. **Documentation lists the version set in prose.** `index.md` names the three
   admitted versions; editor-commands TSV has no 5.3.03 columns; both are
   hand-synced.

## If generation is wanted: task specs (draft, not implemented)

1. `generateVerificationManifest` — render each `*VerificationManifest.java` and
   `selector/*Contract.java` from `verification/*.json` plus a small per-family
   authoring file (adapter slice, capability grouping). Inputs: records + family
   policy; output: `runtime/build/generated/...`; `--check` mode diffs against
   checked-in sources during migration. Mirrors `generateCorePublicApiSelectorContract`.
2. `syncDraftPackMetadata` — recompute `metadata.selectorCount`,
   `capabilityCount`, `capabilityIds`, `verificationRecordSha256` of each pack
   from its `inventoryRef` record; `--check` fails on mismatch. Would have
   prevented the stale 5.3.03 metadata noted above.
3. `generateVerificationIndex` — emit the bootstrap `processResources` record
   list (or a `META-INF/turboism/verification/index.json` consumed by the agent)
   from the directory listing cross-checked against manifests' `verificationId`s.
4. `checkVersionSetCompleteness` — fail when the admitted-version sets differ
   across `ReviewedHostArtifacts`, `CubismEditorReleaseDetector.pinnedBuild`,
   `CubismEditorAvailabilityPolicy.REVIEWED_VERSIONS`, `bootstrap/build.gradle.kts`,
   `host-validation-tasks.json`, `profiles/draft/`, and `index.md`.
5. Extend `check_code_quality.py` `VERSION_SUFFIXED_TYPE` to derive its token set
   from admitted versions rather than the fixed `52|53|5203|5302` alternation.

## Verification appendix

All commands below were executed on this worktree and exist:

- `./gradlew tasks --all` lists `runtime:generateCorePublicApiCatalog`,
  `runtime:generateCorePublicApiSelectorContract`, `mappingReview`,
  `verifyStaticHostSelectors`, `checkCubismCoreApiInventory`,
  `checkCubismCoreMemberPolicy`, `checkCubismCoreSelectorPolicy`,
  `checkEditorModelAliases`, `checkCodeQuality`, `devCheck`, `checkIntegration`,
  `checkCompletedCommit`, `checkRelease`, `runtime:legacyCubismEvidenceTest`.
- `python3 scripts/cubism_core_api.py --help` → `extract|validate|render`;
  `python3 scripts/cubism_core_policy.py --help` → `bootstrap|validate|render-java|render`;
  `python3 scripts/cubism_core_selector_policy.py --help` → `bootstrap|validate|render-java`.
- `python3 scripts/preview/host_validation.py --help` →
  `list|plan|prepare|run|submit|status|wait|cancel|events|serve|recover`.
- `sha256sum compatibility/cubism/verification/cubism-5.3.03-editor-model.json`
  → `4e92ece4…` matches `EditorModelVerificationManifest.RECORD_5_3_03` and the
  repository-test pin, not the pack's stale `metadata.verificationRecordSha256`.
- `git log -S`, `--diff-filter=A`, `merge-base --is-ancestor` were used for the
  provenance and drift-incident claims above (commits `44b446d3b`, `a09ddc0a0`,
  `b733aad9e`, `924630474`, `112a917aa`, `8ad4e5a69`, `648425ed2`, `45cc8c27f`,
  `8edeac163`, `8b1b0cce4`, `e4588879e`, `32dac8601`).
