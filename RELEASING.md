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
base's dated `CHANGELOG.md` section and merge the intended source to main. Beta
release notes use that reviewed base section; Beta's precise prerelease version
is selected in the workflow input, not by rewriting the stable base for every try.
Do **not** push a public version tag to trigger a build.

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
identity. It publishes the same checked files without rebuilding. Stable remains
a final release; Beta is always a prerelease and never GitHub's stable latest.
The publisher has no channel override: a Beta candidate cannot become Stable by
unchecking the GitHub prerelease box. A different embedded version requires a
new candidate and a newly allocated build number.

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

## Legacy coordinated and independent plugin publication

Existing successful product **push-tag** candidate runs can still be resumed through the protected publishers (the explicit attempt is optional for legacy GitHub-only calls). Plugin-only orchestration remains unchanged. The coordinated route below requires its external service credentials/contracts; it does not accept new manually dispatched product candidates. Do not manually tag a new product to use this legacy route.

The legacy orchestrator is planned by one public command and published by protected CI. The planner distinguishes four outcomes:

- `none`: published state already matches the candidate;
- `framework`: only the framework release changes;
- `plugins`: only one or more independent Plugin Directory entries change;
- `combined`: plugins are published and verified before the framework release.

The framework bundle roster and Plugin Directory roster are independent contracts:

- `packaging/release-plugins.txt` controls plugins bundled in the Full installer/archive.
- `packaging/market-plugins.json` controls which first-party plugins are eligible for independent store publication.

A plugin bundled in Full is not thereby published to the Plugin Directory.

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

- A framework version has exactly eight GitHub Release assets: four files and four portable SHA-256 sidecars.
- The reviewed Updates `release.json` is transferred as a digest-bound Actions artifact and is never a ninth GitHub Release asset.
- Existing assets and versioned R2 objects are reused only after exact name, size, and SHA-256 equality.
- Same-version different bytes fail with a version-not-bumped error. Assets are never overwritten.
- Partial publication is resumable by uploading only absent immutable objects.
- Plugin versions are descriptor-owned and independent of the framework version.
- Plugin publication uses immutable GitHub Releases and a verified Ed25519-signed catalog v2.
- Combined releases publish and publicly verify plugins first, then framework GitHub assets, then R2 immutable files. Mutable channel pointers are updated last.
- v0.42.0 is an immutable baseline and must not be edited or backfilled with fabricated historical counts.

## Resume state

The orchestrator writes three contracts outside source history:

- `turboism.release-candidate`: verified local identities;
- `turboism.release-plan`: immutable decision and ordered steps; its canonical hash is `planId`;
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

Release notes are always extracted from the exact version section of `CHANGELOG.md`.

## Credentials and repository hygiene

Core release behavior belongs in tracked scripts, tests, and workflows. Do not place release decisions or credentials in `AGENTS.md`, `.claude/`, local specifications, or operator prompts. Optional user-level automation may only invoke this CLI.

The framework workflow never receives the Plugin Directory signing key or Cloudflare credentials. The signing key remains in the Plugin Directory signing environment; Cloudflare credentials remain in the Updates service environment.

## Global build ledger

The reusable `allocate-build.yml` workflow atomically reserves a number in the
independent `build-ledger` branch using fast-forward-only Git updates. Never merge
this branch into product source or use a per-workflow `run_number` as the global
counter. Repeated allocation of the same source/version/run/attempt is idempotent;
a rebuild attempt is new and failures may leave harmless gaps.

Numbered product JARs and the Java installer carry `Turboism-Version`,
`Turboism-Channel`, `Turboism-Build-Number`, and `Turboism-Source-Revision`.
`build-identity.json` stays in the Actions candidate artifact, not a ninth public
Release asset. Protected promotion checks it against the ledger, JAR manifests,
and the embedded runtime resource, and publishes the matching machine-readable
receipt in Release notes. Historical releases keep `buildNumber: null`.
