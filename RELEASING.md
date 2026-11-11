# Releasing Turboism

## One product identity, three channels

The product uses one global `buildNumber` sequence and one source-bound build
workflow, `release.yml`, for `stable`, `beta`, and `nightly`. Version, channel,
number, source SHA and run attempt are fixed **before** packaging. Publishing and
R2 synchronization never rebuild, relabel, or allocate another number.

| Channel | Version | Trigger | Publication |
| --- | --- | --- | --- |
| stable | committed `X.Y.Z` | explicit candidate request | protected, explicit promotion |
| beta | committed base plus `-beta.N`, `-rc.N` or `-alpha.N` | explicit candidate request with version | protected, explicit prerelease promotion |
| nightly | `X.Y.Z-0.nightly.BUILD` | daily **04:20 Asia/Shanghai**, or manual request | automatic after every release gate passes |

The UTC schedule is `20 20 * * *`; GitHub scheduling is best effort, not an exact
start-time guarantee. Nightly compares its fixed main SHA with the **last fully
published, verified Nightly**. Unchanged source is skipped before number
allocation. Drafts and failed runs never advance the success baseline. An older
queued source cannot replace a newer published source. First use builds if no
Nightly exists. A released stable base moves the Nightly base to at least its next
patch, without changing the committed product version or consuming stable versions.

### Local development and identity inspection

A local build is not a fourth update channel: it has a selected target channel,
but defaults to `buildKind=local` and no official number. It never contacts the
update service to guess its own identity, and never increments the ledger.

```bash
# Print resolved settings without packaging, publication, or number allocation
./gradlew -q printBuildInfo
./gradlew -q printBuildInfo -PturboismChannel=beta -PturboismVersion=0.43.10-beta.1
./gradlew -q printBuildInfo -PturboismChannel=nightly

# Build a local development agent (replace ./gradlew with .\gradlew.bat on Windows)
./gradlew :bootstrap:jar -PturboismChannel=beta -PturboismVersion=0.43.10-beta.1

# The Python wrapper also supports metadata inspection
python3 scripts/release/product.py inspect --channel nightly
python3 scripts/release/product.py info --jar /path/to/turboism-agent.jar
```

Normal local builds carry a `-SNAPSHOT` suffix. With no explicit preview version,
Beta uses `<base>-beta.local-SNAPSHOT` and Nightly uses
`<base>-0.nightly.local-SNAPSHOT`. `-PturboismRelease=true` removes SNAPSHOT for
packaging checks; it does **not** authorize publication or claim an official build.
Do not manually set `TURBOISM_BUILD_NUMBER`. Public numbers come from the CI ledger.

CI supplies `TURBOISM_BUILD_VERSION`, `TURBOISM_BUILD_CHANNEL`,
`TURBOISM_BUILD_NUMBER`, and `TURBOISM_SOURCE_REVISION` from one allocation.
Conflicting Gradle overrides, mismatched source, dirty numbered builds, and a
Nightly suffix different from its number are rejected. All channels use the same
runtime metadata resource; stale channel information invalidates Gradle inputs.
The frozen SDK and independent plugin versions are not product build counters.

### Request a source-bound candidate from a local terminal

Choose the next stable **base** in `gradle/common-java.gradle.kts`, prepare the
base's dated `docs/en/CHANGELOG.md` section and merge the intended source to main. Beta
release notes use that reviewed base section; Beta's precise prerelease version
is selected in the workflow input, not by rewriting the stable base for every try.
Do **not** push a public version tag to trigger a build.
Reviewed Simplified Chinese, Japanese and Korean notes for that base live in
`distribution/release-notes/<version>.json`, bound to the SHA-256 of the trimmed English section.
`englishSha256` is a staleness check, not a security control: a stale value fails promotion instead of reusing old text, and a base
without that file is published with its English notes only. Nightly notes are
generated from a frozen published baseline; they take no reviewed file.

`distribution/release-notes/<version>.json` is the single source for localized changelog
text: `scripts/release/render_localized_changelogs.py` rewrites every
JSON-covered section of `docs/zh/CHANGELOG.md`/`docs/ja/CHANGELOG.md`/`docs/ko/CHANGELOG.md`
verbatim, keeps pre-JSON history and the `Unreleased` sections as committed,
and `checkLocalizedChangelogs` fails on drift. Never hand-edit a
JSON-covered section; sections predating the JSON scheme remain authored in
the localized files themselves.

```bash
# Default is a dry run; --submit actually dispatches GitHub Actions.
python3 scripts/release/product.py candidate --channel stable --submit
python3 scripts/release/product.py candidate --channel beta --version 0.43.10-beta.1 --submit
python3 scripts/release/product.py candidate --channel nightly --submit
```

The wrapper rejects local uncommitted files or HEAD different from remote main.
It does not push local changes. A manual Nightly request follows the same
changed-only check as the schedule and publishes only after a successful build.
Stable/Beta requests produce candidate artifacts, not public releases.

Equivalent direct GitHub CLI (Bash/WSL; use PowerShell variable syntax on Windows):

```bash
git fetch origin
SOURCE_SHA=$(git rev-parse origin/main)
gh workflow run release.yml --repo turboism/Turboism --ref main \
  -f channel=beta -f version=0.43.10-beta.1 -f expected_source_sha="$SOURCE_SHA"
```

If main changes before dispatch, the SHA check fails rather than building a
moving target. Global allocation happens only after preflight. The candidate
identity is source SHA + workflow run ID + attempt; read its `build-identity.json`
for the actual number. Do not read the latest API number or the current counter
as if it belonged to this candidate. Re-run **all build jobs including the
allocator** when rebuilding; publication-only retries retain the original number.

### Explicitly publish Stable or Beta without rebuilding

Review the successful candidate's eight files, receipt and source identity.
Use the **original** source SHA and successful run attempt, even if main advanced.

```bash
python3 scripts/release/product.py promote \
  --run-id <candidate-run-id> --attempt <successful-attempt> \
  --source <original-source-sha> --submit
```

The wrapper dispatches `release-github-only.yml` into the existing
`production-release` environment. Trusted publisher code separately checks the
exact source, successful run, channel, receipt, all file bytes and remote tag
identity before any remote mutation. For Stable and Beta candidates whose
`prepare-market-release.py` pass staged changed first-party plugins (via
content-addressed auto-bump against the live signed v2 catalog at
`https://plugin.turboism.dev/api/v2/catalog.json`; Nightly never publishes to
the catalog), promotion publishes plugins **first**:

1. Dispatches `publish-plugin-directory-v2.yml` in
   `turboism/turboism-plugin-directory` using `PLUGIN_DIRECTORY_DISPATCH_TOKEN`
   with `source_run_id`, `source_sha`, `artifact_name`, and `request_id`,
   locates the workflow run by its correlated `run-name`, and watches it to
   completion.
2. Polls `scripts/release/verify-plugin-publication.py` with backoff (up to 5
   minutes) until the signed public catalog exposes the new plugin versions and
   every GitHub Release JAR matches its planned SHA-256 and descriptor hash,
   then probes `https://turboism.dev/plugins` with `curl -L`.
3. Only after plugin publication and verification succeed does it publish the
   framework GitHub Release without rebuilding. If plugin publication fails,
   framework promotion stops before any public tag or Release is created.

Stable remains a final release; Beta is always a prerelease and never GitHub's
stable latest. The publisher has no channel override: a Beta candidate cannot
become Stable by unchecking the GitHub prerelease box. A different embedded
version requires a new candidate and a newly allocated build number. The legacy
`release-publisher.yml` workflow, `turboism/turboism-updates`, and
`UPDATES_RELEASE_DISPATCH_TOKEN` are retired and superseded by
`release-github-only.yml` and `notify-release-api.yml`.

Retries resume the same immutable draft/tag/assets; never delete/move tags or use
`--clobber`. Conflicting bytes, a changed receipt or an incomplete already-public
release fail closed. Candidate artifacts expire after seven days; expiry is not
permission to skip verification. Failures before publication may reuse the same
intended product version; once a public tag exists it is bound to its candidate.

### Runtime and update client contract

`META-INF/turboism/framework-version.properties` embeds `version`, `channel`,
`buildNumber`, `sourceRevision`, `buildKind`, `dirty`, and `displayVersion`.
`dev.turboism.core.FrameworkBuildInfo.current()` exposes the runtime identity;
`buildNumber()` is an `OptionalLong`, not a fabricated zero. Startup and About
read this same generated resource. Older version-only resources remain readable
with unknown number; missing/corrupt identity is reported as unknown.

The installed artifact's `channel` is distinct from a user's future
`updateChannel` preference. Changing update preferences does not mutate installed
identity. Compare the installed identity against the selected feed at
`https://api.turboism.dev/v1/releases/{stable,beta,nightly}.json`. A later build of
an older maintenance series must not silently downgrade a newer development line.
No updater/UI preference migration or automatic replacement of running Cubism files
is performed by this build-tooling change.

GitHub's published release triggers the existing OIDC notification and verified
R2 mirror. The completed product workflow also triggers notification because a
Release created with `GITHUB_TOKEN` does not itself start another ordinary workflow.
The 15-minute reconciliation remains the fallback. The new pipeline does not read
or update the legacy Updates service.

## Version and digest model

One rule governs every value below. An **independent root** changes only through
an explicit committed edit (or CI ledger allocation for `buildNumber`). A
**derived** value changes only when its root changes. No build step invents a
root. `./gradlew -q printBuildInfo` prints the resolved identity of the current
checkout.

### First-party plugins are independent of the framework version

A first-party plugin behaves exactly like a third-party plugin. Its `version` is
independent of `turboismFrameworkVersion`. Raising the framework version never
bumps an unchanged first-party plugin. A plugin bumps its own SemVer only when its
own packaged code, resources or descriptor contract changes. Compatibility between
framework and plugin is expressed only by `turboismApi` and the descriptor
`schemaVersion`.

Staging enforces this. `resolve_next_plugin_version` keeps the committed version
when the built bytes already match the newest catalog release. Otherwise it
advances one patch above the highest cataloged version. The build never rewrites
`plugin.json`; only the staged copy carries the resolved version.

### Version values and digests

Kind codes: **root** = independent version root; **derived** = derived or bounded
version value; **digest (a)** = security-relevant identity or integrity anchor;
**digest (b)** = change detection only; **digest (c)** = removable or derivable.

| Value / Digest | Kind | Owner (declared in) | Consumer | Bump / Update rule |
| --- | --- | --- | --- | --- |
| `turboismFrameworkVersion` | root | `gradle/common-java.gradle.kts` | Gradle build, `FrameworkBuildInfo`, update pipeline | One committed `X.Y.Z` edit per Stable base. Never a build counter. |
| `buildNumber` | root | `build-ledger` branch (`allocate-build.yml`) | `FrameworkBuildInfo`, `UpdateDiscoveryParser`, installer | Monotonic global counter allocated once per CI source/run/attempt. Never hand-set. |
| Plugin `version` | root | `plugins/*/src/main/resources/META-INF/turboism/plugin.json` | `PluginDescriptorParser`, `PluginMarketService`, `RuntimePluginAutoUpdater` | Plugin's own SemVer (independent of framework version). Bump only when that plugin's packaged code, resources or descriptor contract changes. |
| Descriptor `schemaVersion` | root | same `plugin.json` | `PluginDescriptorParser` (`DescriptorSchemaTier`) | Monotonic. Increment only when the admitted descriptor field set changes. |
| `turboismReadmeSchema` | root | `plugins/*/README.md` front matter | `checkOfficialPluginReadmes` | Monotonic. Increment only when the README front-matter field set changes. |
| Format `schemaVersion` | root | each JSON contract (`turboism.mapping.pack`, `turboism.plugin.pending`, `turboism.runtime.config`, signed catalog, update manifest, incremental candidate, release notes) | format-specific reader/validator | One version per format, owned by its reader. Increment only with a reader that still accepts the previous version. |
| Managed runtime pins | root | `packaging/managed-graal.json`, `packaging/windows-installer/script-engine.json` | `ManagedGraalRuntimeService`, Windows installer scripts | Bump version, URL and archive digest together. |
| SDK API baseline | root | `compatibility/sdk-api-since.json` (`baseline`) | `scripts/sdk_api_since.py`, `checkPluginApiFloors` | Fixed at `0.67.0`. Advance only with a documented re-baseline. |
| Runtime `version` | derived | `gradle/common-java.gradle.kts` + `TURBOISM_BUILD_VERSION` | `framework-version.properties`, JAR manifests, `FrameworkBuildInfo` | Derived from `turboismFrameworkVersion` (`-SNAPSHOT` locally unless `-PturboismRelease=true`). Never hand-edited. |
| `apiVersion` | derived | `turboismFrameworkVersion.substringBefore('-')` | `KernelPluginDiscovery` | Derived automatically; prerelease suffixes never lower it. |
| Plugin `turboismApi` | derived | `plugin.json` (bounded `[floor, 1.0.0)`) | `KernelPluginDiscovery`, `PluginMarketService`, `checkPluginApiFloors` | Lower bound is the oldest SDK version providing every linked member; raise only when linking newer SDK APIs, then sync via `python3 scripts/sync_plugin_api.py --write`. |
| README `version`, `turboismApi` | derived | `plugins/*/README*.md` front matter | `checkOfficialPluginReadmes`, `checkPluginApiRanges`, market publisher | Exact copies of `plugin.json`. Never hand-edited independently. |
| `minimumUpdaterVersion` | derived | signed framework update manifest | `FrameworkUpdateApplier`, `UpdateManifest` | Advance only when installed clients below the floor cannot apply the target update format. |
| `turboismSdkVersion` | derived | plugin-template repository | `sync-from-turboism.yml`, template consumers | Written automatically from the published release tag. |
| `catalogVersion` | derived | signed plugin catalog | `PluginCatalogVerifier`, `PluginCatalogStateStore` | Strictly increasing monotonic counter emitted per catalog publication. |
| `catalogSha256` | digest (a) | signed catalog signature envelope | `PluginCatalogVerifier` (constant-time compare before JSON parse) | Written by the Plugin Directory signer only. |
| Catalog artifact `sha256`, `descriptorSha256` | digest (a) | signed catalog `releases[].artifact` | `PluginCatalogVerifier`, `PluginCatalogClient`, `PluginMarketService`, `RuntimePluginAutoUpdater` | Written by the publisher. Never re-derived locally. |
| `packageHash` | digest (a) | `META-INF/turboism/package.json` | `PluginManifestReader` (canonical self-hash) | Written by `scripts/release/package-plugin.py`. Same-origin: detects corruption, not tampering. |
| `sha256` per update entry | digest (a) | signed framework update manifest | `UpdateManifest`, `FrameworkUpdateApplier`, `FrameworkInstallationReceiptLoader` | Written by `build-framework-update.py`. Every object and receipt byte is rehashed. |
| `manifestSha256` | digest (a) | update signature envelope | `framework_update.sign`, `FrameworkInstallationReceiptLoader` | Written at signing time. |
| `objectsSha256` | digest (a) | incremental candidate descriptor | `distribution/incremental.mjs` closure check | Derived from the signed manifest object closure. |
| Receipt index `manifestSha256` | digest (a) | `state/installed-receipt.json` | `FrameworkInstallationReceiptLoader` | Derived from the authenticated manifest bytes. |
| `agentSha256` | digest (a) | framework update journal | `FrameworkUpdateApplier` | Binds the journal to the agent that wrote it. |
| `distributionSha256Sum` | digest (a) | `gradle/wrapper/gradle-wrapper.properties` | Gradle wrapper | Hand-pinned. Bump with the wrapper version. |
| Dependency `sha256` (796 entries) | digest (a) | `gradle/verification-metadata.xml` | Gradle dependency verification | Regenerated by the documented verification task only. |
| Managed archive `sha256` | digest (a) | `packaging/managed-graal.json`, `packaging/windows-installer/script-engine.json` | `ManagedGraalRuntimeService`, `install-managed-graal.ps1`, `install-script-engine.ps1` | Bump with the pin. |
| Reviewed host artifact `sha256` (20 releases) | digest (a) | `compatibility/cubism/reviewed-releases.json` | `ReviewedHostArtifacts`, `ReviewedHostContract`, `CubismHostCompatibilityResolver` | One declaration per reviewed release. `scripts/generate_reviewed_releases.py` regenerates the Java table. |
| Reviewed class pins (1816 digests) | digest (a) | `runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/*.json` | `ClassPinTable`, every `Verified*Installer` | Binds each hook to exact host class bytes before arming. Regenerate from a reviewed host. |
| `reviewedSha256`, `admittedSha256` | digest (a) | transformer admission sets | `AtlasCacheReuseTransformer`, `AtlasTileBboxTransformer` | Same reviewed-class identity, read from the pin tables. |
| `verifierSha256` | digest (a) | installer discovery snapshot | `cubism-launch-common.ps1` | Equals the digest of the installed `turboism-agent.jar`. |
| Release asset `.sha256` sidecars | digest (a) | GitHub Release assets | `verify-release.py`, `mirror.mjs`, plugin-template `setup.sh` | Same-origin transfer integrity. Generated at packaging time. |
| `englishSha256` (9 files) | digest (b) | `distribution/release-notes/<version>.json` | `release_notes.reviewed_locales`, `render_localized_changelogs.py` | Recompute when the trimmed English changelog section changes. A stale value fails promotion. |
| Golden snapshot `sha256` (22 cases) | digest (b) | `testing/fixtures/snapshots/manifest.json` | `scripts/check_golden_snapshots.py` | Regenerate only with a reviewed format change. |
| Frozen draft-pack `sha256` (77 rows) | digest (b) | `compatibility/cubism/mapping-packs/draft/frozen.json` | `scripts/materialize_draft_contracts.py` (`check_frozen`) | Pins shipped `5.0.x` and `5.1.x` retained evidence rows. Refresh via `--write-freeze` only for a reviewed compatibility change. |
| `surfaceSha256` | digest (b) | `compatibility/sdk-api-since.json` | `scripts/sdk_api_since.py --check` | Regenerate via `python3 scripts/sdk_api_since.py --write` after a reviewed SDK surface change. |
| `notesSha256` | digest (b) | prerelease candidate JSON | `prerelease.verify_candidate` | Derived from the generated notes. |
| `planId` | digest (b) | release plan document | `contracts.py`, `executor.py` | Derived from the canonical plan. Detects resume drift, not tampering. |
| `recordSha256` | digest (b) | verification records and manifest constants | `SliceContract`, `EditorUiProviderAdmission`, `CubismHostCompatibilityResolver` | Provenance metadata. Runtime admission checks schema and identity, not the self-hash. |
| `fixtureSha256`, probe `agentSha256` | digest (b) | performance probe report and rollback manifest | `PerformanceProbeReportWriter`, `PerformanceProbeRollbackWriter` | Binds a report to the run that produced it. Rejects stale evidence. |
| `sourceSha256` | digest (b) | script `manifest.json` | `ScriptRegistry` | Binds a user script to its reviewed source bytes. |
| `digestSha256` | digest (b) | kernel report record entry | `KernelReportValidationSupport` | Provenance only. |
| `contentSha256` | digest (c) | signed catalog `releases[].artifact` | `prepare-market-release.jar_matches_catalog_release` | No producer in this repository writes it. Removable. |
| `rawSha256` | digest (c) | plugin staging journal `operations[]` | `PendingPluginOperations` | Always equals `jarSha256`. Derivable. |
| `exactArtifactSha256` (24 expanded packs) | digest (c) | `compatibility/cubism/mapping-packs/draft/{baseline,overrides}/*.json` | `MappingPackValidator` allowlist only | Duplicates `metadata.artifactSha256` in 22 expanded packs; two carry it alone. Removable. |
| `metadata.verificationRecordSha256` (100 expanded packs) | digest (c) | retained pack `metadata` (`${verificationRecordSha256}`) | `MappingPackDraftImportTest` only | No runtime reader. Removable. |
| `contentId` | digest (c) | signed update manifest entry | `UpdateManifestEntry` | Always `sha256:` + `sha256`. Derivable. |
| `abiSha256` (2 plugins) | digest (c) | `plugin.json` `eventExports[]`, `eventImports[]` | `PublicEventAbi`, `PublicEventRouteCatalog` | Fully derived from the SDK payload type, yet no generator writes it today. Generate it; never hand-copy it. |
| Native icon pins (120 PNG digests) | digest (c) | `CubismNativeIconCatalog` | `CubismNativeIconResolver.decodePng` | Derivable from the reviewed artifact that already gates the JAR. Generate them; never hand-copy them. |
| `pluginDescriptorSha256` | digest (c) | `META-INF/turboism/package.json` | `LocalPluginPackageInspector` | Same value as catalog `descriptorSha256` in a different container. Unify the name. |

### Concrete removals and their compatibility effect

Each item below is a separate change. Apply them in this order; the first three
need no wire-format migration.

1. **Delete the `contentSha256` reader branch.** No producer in this repository
   writes the field, so no published catalog entry carries it. Removing
   `prepare-market-release.jar_matches_catalog_release`'s branch changes no
   published entry; the byte-identity and `artifact_loader` fallbacks stay.
2. **Drop pack-level `exactArtifactSha256`.** Packs are build inputs, never
   published artifacts, so no installed client reads them. Remove the field from
   the retained baseline/override declarations for those 24 expanded packs, from
   both validator allowlists, and move the `MappingPackDraftImportTest` assertion
   to `metadata.artifactSha256`. Two packs carry the field without metadata; give
   them the metadata value first.
3. **Drop `metadata.verificationRecordSha256`.** Only an integration test reads
   it. Remove the field, the generator write, and the test assertion together.
4. **Retire `abiSha256`, or generate it.** This is a descriptor schema change.
   Add schema v6 without the field, keep v4 and v5 read support in
   `DescriptorSchemaTier`, and keep emitting `publishedEvents[].abiSha256` in the
   catalog for every v4 descriptor. Installed clients reject an unknown
   `schemaVersion`, so the writer must not emit v6 until the oldest supported
   client reads it. Generating the value from the SDK at build time is the safer
   first step: it keeps the wire format and removes the hand-copied digest.
5. **Retire `rawSha256`.** The journal is local state. A new writer that omits the
   field makes older clients reject the file, because `OPERATION_FIELDS` matches
   the exact field set. Bump the journal `schemaVersion` to 2. Let the reader
   accept v1 with `rawSha256` and v2 without it. Drop the writer field only after
   one full release cycle.
6. **Derive `contentId`.** It is a pure function of `sha256`. The signed update
   manifest is a wire format, so keep the field for readers and stop treating it
   as an independent value.
7. **Rename `pluginDescriptorSha256` to `descriptorSha256`.** The package manifest
   is a separate container, so the rename needs a dual-read window: accept both
   names, write the new one.

No removal touches the class (a) anchors. These stay as they are:

- the Ed25519-signed plugin catalog;
- the pinned Gradle verification metadata;
- the wrapper and archive pins;
- the reviewed host tables;
- the R2 write verification.

Each one anchors identity outside the released bytes.

### Measured at this revision

| Measure | Count |
| --- | --- |
| First-party plugins | 39 |
| Distinct plugin `version` values | 4 (`0.1.0` ×4, `0.68.0` ×25, `0.68.1` ×9, `0.68.2` ×1) |
| Distinct `turboismApi` values | 6 |
| Descriptor `schemaVersion` in use | 3 (37 plugins) and 4 (2 plugins); the runtime admits 2 through 5 |
| Distinct `(format, schemaVersion)` pairs | 44 (including retained baseline, override and freeze contracts) |
| Reviewed host artifact digests | 20 |
| Reviewed class-pin digests | 1816 |
| Gradle dependency digests | 796 |
| Golden snapshot digests | 22 |
| Release-notes `englishSha256` | 9 |
| Retained mapping-pack evidence | 20 baselines + 20 override tables + `frozen.json` (77 frozen rows), expanding to 166 retained packs (24 pack-level digests, 104 metadata digests, 22 identical pairs) |

## Legacy coordinated and independent plugin publication

Existing successful product **push-tag** candidate runs can still be resumed through the protected publishers (the explicit attempt is optional for legacy GitHub-only calls). Plugin-only orchestration remains unchanged. The coordinated route below requires its external service credentials/contracts; it does not accept new manually dispatched product candidates. Do not manually tag a new product to use this legacy route.

The legacy orchestrator is planned by one public command and published by protected CI. The planner distinguishes four outcomes:

- `none`: published state already matches the candidate;
- `framework`: only the framework release changes;
- `plugins`: only one or more independent Plugin Directory entries change;
- `combined`: plugins are published and verified before the framework release.

The framework bundle roster and Plugin Directory roster are independent contracts:

- `packaging/release-plugins.txt` controls plugins bundled in the Full installer/archive.
- `packaging/market-plugins.txt` controls which first-party plugins are published to the Plugin Directory (generating `packaging/market-plugins.json`).
- `packaging/market-plugins.json` is the Git-reviewed publication manifest with derived compatibility and channel metadata.

Each manifest entry carries a `publication` decision. `approved` lets the plugin enter the next
publication batch; `withheld` keeps it in the roster while blocking publication. Regeneration
preserves existing decisions and defaults every newly rostered plugin to `withheld`, so adding a
plugin to `market-plugins.txt` never publishes it. An approved plugin whose `required` dependency
is withheld fails staging unless the signed catalog already serves a compatible release.

Staging publishes a channel promotion. When a plugin's built bytes already match its newest
catalog release but that release carries a weaker channel, as when a plugin first shipped during
a Beta round and never changed, the next Stable staging bumps the patch version and publishes it
on `stable`. Catalog versions must be unique and strictly ascending, so a promotion cannot reuse
the carried version. The reverse direction never happens implicitly, because it would withdraw a
release from Stable installations.

A plugin bundled in Full is not thereby published to the Plugin Directory, and a plugin published to the Plugin Directory does not need to be bundled in the Full installer.

## One-command dry run

Build the normal framework and market payloads first, then run:

```bash
python3 scripts/release/turboism-release.py release \
  --require-tag \
  --dist build/windows-installer/dist \
  --market-dir build/market-release \
  --plugin-directory-repo /path/to/turboism-plugin-directory
```

The command writes canonical JSON under `build/release-orchestrator/` and prints the detected `intent`. It does not mutate remote state by default. Repository paths are runtime arguments and are never written into the release documents.

Individual phases are also available:

```bash
python3 scripts/release/turboism-release.py build --require-tag --dist <dist> --market-dir <market-dir>
python3 scripts/release/turboism-release.py plan --candidate <candidate.json> --plugin-directory-repo <directory-repo>
python3 scripts/release/turboism-release.py verify --plan <plan.json>
```

For reproducible tests, `plan` accepts JSON observations instead of contacting services:

```bash
python3 scripts/release/turboism-release.py plan \
  --candidate <candidate.json> \
  --github-observation <github.json> \
  --updates-observation <updates.json> \
  --catalog-observation <catalog.json>
```

A missing or unreadable canonical service is an error when its state is required; it is never interpreted as “unchanged”.

## Production boundary

Local production mode does not upload files directly. It dispatches the protected publisher workflow for the exact source revision and plan. The one-command path accepts the same completed candidate run identity:

```bash
python3 scripts/release/turboism-release.py release \
  --require-tag \
  --dist <dist> \
  --market-dir <market-dir> \
  --plugin-directory-repo <directory-repo> \
  --candidate-run-id <completed-actions-run-id> \
  --production \
  --confirm publish:<40-character-source-sha>
```

A previously generated plan can be dispatched or resumed without rebuilding:

```bash
python3 scripts/release/turboism-release.py publish \
  --plan <plan.json> \
  --candidate-run-id <completed-actions-run-id> \
  --production \
  --confirm publish:<40-character-source-sha>
```

Generic `--yes` confirmation is intentionally unsupported. The run ID must identify a completed allowlisted candidate workflow whose exact source SHA and immutable Actions artifact match the plan. The protected workflow must re-fetch all remote observations before each mutation.

## Immutable release rules

- A framework version has exactly eight GitHub Release assets: the standard Windows installer (`TurboismInstaller-<version>.exe`), portable package (`turboism-<version>.zip`), and their SHA-256 sidecars, plus the SDK and event-processor JARs and their sidecars. Both standard packages contain all bundled plugins and the complete, pinned GraalJS offline engine closure. A compatible GraalVM runtime is installed separately through the configurator. The public download API accepts only this standard EXE/ZIP layout. The standalone SDK JAR is byte-identical to the SDK JAR in the staged payload.
- The SDK and event-processor pairs are GitHub-only developer artifacts: they never enter the Updates `release.json` media contract, the distribution API's two public downloads, or the R2 mirror. The candidate binds them under `framework.developerArtifacts` separately from the four product files and checksums.
- Framework self-update documents and payload objects are not GitHub Release assets and do not change this count or the EXE/ZIP whitelist; they are admitted into R2 through the authenticated path in `distribution/INCREMENTAL.md` ("Production service"), before the release is published.
- The reviewed Updates `release.json` is transferred as a digest-bound Actions artifact and is never a ninth GitHub Release asset.
- Existing assets and versioned R2 objects are reused only after exact name, size, and SHA-256 equality.
- Trust model: GitHub Release `.sha256` sidecars, the plugin-template `setup.sh` sidecar check and a plugin's `packageHash` are same-origin integrity checks (they catch corruption, not tampering). Independent anchors are the Ed25519-signed plugin catalog, pinned Gradle verification metadata / wrapper `distributionSha256Sum` / the ManagedGraal archive pin, and R2 write verification.
- Same-version different bytes fail with a version-not-bumped error. Assets are never overwritten.
- Partial publication is resumable by uploading only absent immutable objects.
- Plugin versions are descriptor-owned and independent of the framework version. The build never rewrites
  `plugin.json`; installer payloads and market jars carry the version committed in source. Staging reuses a
  cataloged release when the jar content is unchanged and otherwise publishes the next patch above the highest
  cataloged version (or the committed version when that is higher). Bump the committed version and add a
  matching README changelog entry when a plugin changes.
- Plugin publication uses immutable GitHub Releases and a verified Ed25519-signed catalog v2.
- Combined releases publish and publicly verify plugins first, then framework GitHub assets, then R2 immutable files. Mutable channel pointers are updated last.
- v0.42.0 is an immutable baseline and must not be edited or backfilled with fabricated historical counts.

## Resume state

The orchestrator writes three contracts outside source history:

- `turboism.release-candidate`: verified local identities;
- `turboism.release-plan`: immutable decision and ordered steps; its canonical hash is `planId`, a consistency check that detects a resumed state drifting from the plan, not a tamper defense;
- `turboism.release-state`: mutable attempts and verified remote step results.

Resume refuses a state whose `planId` or step set differs from the plan. When a protected publisher run already uploaded `turboism-release-plan-<planId>`, a manual resume recovers and validates that exact Actions artifact instead of deriving a different plan from partially published remote state.

## Required release gates

Before publication CI must run:

```bash
python3 scripts/check_remote_hygiene.py --all
./gradlew --no-daemon checkRelease -PinstallerVersion=<version> -PturboismRelease=true --console=plain
bash packaging/windows-installer/assemble-release.sh <version>
python3 scripts/release/verify-release.py \
  --version <version> \
  --dist build/windows-installer/dist \
  --release-plugins packaging/release-plugins.txt \
  --windows-stage build/windows-installer/staging
```

Release notes are always extracted from the exact version section of `docs/en/CHANGELOG.md`.

## Credentials and repository hygiene

Core release behavior belongs in tracked scripts, tests, and workflows. Do not place release decisions or credentials in `AGENTS.md`, `.claude/`, local specifications, or operator prompts. Optional user-level automation may only invoke this CLI.

The candidate build never receives signing keys or Cloudflare credentials. Its separate framework signing job uses
the dedicated key in `framework-release-signing`; the protected `release-github-only.yml` publisher uses R2
credentials from `production-release`. Both environments are limited to main. The Plugin Directory signing key
remains in the Plugin Directory's own signing environment and is not framework signing authority.

## Global build ledger

The reusable `allocate-build.yml` workflow atomically reserves a number in the
independent `build-ledger` branch using fast-forward-only Git updates. Never merge
this branch into product source or use a per-workflow `run_number` as the global
counter. Repeated allocation of the same source/version/run/attempt is idempotent;
a rebuild attempt is new and failures may leave harmless gaps.

Numbered product JARs and the Java installer carry `Turboism-Version`,
`Turboism-Channel`, `Turboism-Build-Number`, and `Turboism-Source-Revision`.
`build-identity.json` stays in the Actions candidate artifact, not a ninth
public Release asset. Protected promotion checks it against the ledger, JAR manifests,
and the embedded runtime resource, and publishes the matching machine-readable
receipt in Release notes. Historical releases keep `buildNumber: null`.

## Standalone plugin template repository sync

`templates/plugin-template/` in this repository is the single source of truth for
the public GitHub Template repository `turboism/turboism-plugin-template`. No
cross-repository secret or push step runs from `turboism/Turboism`: instead,
`templates/plugin-template/.github/workflows/sync-from-turboism.yml` runs inside
the template repository (on a daily schedule and via `workflow_dispatch`):

1. Resolves the latest published `stable` and `beta` release tags from
   `https://api.turboism.dev/v1/releases/<channel>.json` (falling back to the
   GitHub Releases API).
2. Fetches `templates/plugin-template/` at that release tag from the public
   `turboism/Turboism` repository.
3. Replaces the template repository working tree (preserving `.git`), updates
   `turboismSdkVersion` in `gradle.properties`, commits if changed, and tags
   `sdk-<version>` using the template repository's own `GITHUB_TOKEN`.

### Framework update signing and publication

When `FRAMEWORK_UPDATE_SIGNING_ENABLED=true`, Stable/Beta candidates bundle the reviewed public keyring and client
policy before the agent is assembled. The protected signing job signs the exact numbered payload, packages the
initial installation receipt in both the ZIP and EXE, and rechecks the final bytes. Nightly keeps the existing
full-package path. The minimum updater version is the first compatible enabled release and must remain supported
by earlier installed clients as subsequent targets advance.

Promotion with `framework_publish=initialize` registers the first candidate of a channel; later releases use
`admit`. The publisher uploads immutable objects, obtains a real GitHub OIDC identity, and verifies public
reachability before completing the GitHub release. `framework_publish=none` publishes only the full packages.
The setup and package contract are documented in
[BOOTSTRAP_RECEIPT.md](packaging/windows-installer/BOOTSTRAP_RECEIPT.md).

`writeFrameworkPayloadInventory` is an explicit, unsigned build-inventory task for
numbered, clean CI builds. It depends on `stageInstallerPayload` and writes
`build/windows-installer/generated/framework-payload-inventory.json` from the
explicit Windows package roots and the resolved Graal build output. It does not
sign, upload, or change the ordinary release asset contract. Its inventory is a
trusted build input, **not** a receipt proving ownership of a user's installation.

The offline producer accepts the existing release candidate and allocated build
identity receipt from that same protected build, its standard Windows ZIP, and
that inventory:

```sh
python3 scripts/release/build-framework-update.py \
  --candidate CANDIDATE_JSON --receipt BUILD_IDENTITY_JSON \
  --inventory FRAMEWORK_PAYLOAD_INVENTORY_JSON --archive STANDARD_ZIP \
  --version VERSION --channel CHANNEL --build-number BUILD_NUMBER \
  --source-revision SOURCE_SHA --minimum-updater UPDATER_VERSION \
  --key-id KEY_ID --trusted-keys PUBLIC_KEYRING_JSON \
  --private-key PRIVATE_PEM_FILE --output LOCAL_OUTPUT_DIRECTORY
```

The local public keyring maps approved key IDs to base64 X.509 DER Ed25519 public
keys. The supplied private PEM must match the selected public key and be private
to its owner on POSIX. Missing, unknown, encrypted, or mismatched signing material
fails closed; no key material is emitted in the output or error diagnostics.
Tests generate disposable keys and require the existing OpenSSL command-line
provider; this introduces no Python cryptography dependency.

The output is a versioned manifest, its detached v1 signature envelope, an object
inventory, a v1 incremental-candidate descriptor, and `objects/<sha256>/payload`. Existing exact objects are rechecked and
reused without overwriting. These are local paths, not promises of public download
URLs. The producer checks candidate archive hashes, build/source identities,
embedded Agent/runtime metadata, and the complete inventory before signing.
Configuration, installed state and independent plugins are explicitly excluded;
unknown files or unsafe archive aliases fail closed. The supported layout is the
standard Windows ZIP; it does not describe installed private GraalVM downloads or
historical NSIS path differences. No installer receipt persistence, downloader,
apply helper or production admission is supplied by this command.

Focused verification:

```sh
./gradlew checkFrameworkUpdateTooling
./gradlew :runtime:test --tests 'dev.turboism.update.*'
```

Ordinary `checkCompletedCommit` reaches the offline tool tests and the Java test
that consumes real producer output. `checkReleaseTooling` also retains the
existing release tests. Signing and publication remain separate operations.

The offline [incremental admission contract](distribution/INCREMENTAL.md) consumes
the descriptor, authenticates the exact manifest, recomputes its object closure,
and verifies actual bytes before immutable registration and a final channel CAS.
Its storage interface is exercised with an in-memory adapter by
`node --test distribution/test/*.test.mjs`. The production Worker uses R2 with a Durable Object registration and
channel authority, and serves only admitted resources. Historical full-package releases remain valid without an
incremental descriptor; adding signing configuration does not retroactively make them self-update capable.
