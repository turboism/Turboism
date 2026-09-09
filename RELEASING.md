# Releasing Turboism

## Product release: build first, tag only on promotion

Do **not** push an official version tag to start a build. Choose the next version once in `gradle/common-java.gradle.kts` and prepare its dated `CHANGELOG.md` section, then merge the intended source to `main` through normal review.

### 1. Build a candidate (no publication)

```bash
SOURCE_SHA=$(git rev-parse main)
gh workflow run release.yml --ref main -f expected_source_sha="$SOURCE_SHA"
```

CI refuses a different SHA if main advanced before dispatch. It runs the complete release gate, builds the installer/archives, verifies checksums and retains the candidate artifact. Its token is read-only and has no production secrets. After verification, a disposable **local-only** annotated tag lets the existing candidate serializer record the intended tag; it is never pushed. Candidate completion does not publish automatically.

The candidate identity is **source SHA + workflow run ID + run attempt**. If a candidate fails, fix the source and retry with the **same intended product version**. Do not increment patch versions, create public RC tags or add separate changelog release headings for failed attempts. Keep the pending version's notes accurate across fixes.

### 2. Review and explicitly promote the successful candidate

Find the candidate run and its successful attempt in Actions. Review the eight verified assets and source identity, then dispatch:

```bash
gh workflow run release-github-only.yml --ref main \
  -f candidate_run_id=<completed-actions-run-id> \
  -f candidate_run_attempt=<successful-attempt-number> \
  -f source_sha="$SOURCE_SHA" \
  -f confirmation="publish-github-only:$SOURCE_SHA"
```

The protected `production-release` environment and shared publication concurrency apply. Trusted publisher code is checked out separately from candidate source. Before creating any remote ref, CI revalidates the successful run, repository/workflow/ref/SHA/attempt, source version/changelog, all eight artifact bytes and existing remote identities. It then creates the annotated `vMAJOR.MINOR.PATCH` tag at the **built SHA**, creates/resumes the draft and publishes the same files **without rebuilding**. Main advancing cannot retarget the release.

This path publishes **GitHub only**. It does not update Updates service pointers or independently publish bundled plugins. New manual product candidates are deliberately excluded from automatic coordinated publication.

### 3. Retry publication without changing version or bytes

Use the same promotion command and original candidate attempt after interrupted tag creation/upload. Identical tag/assets resume or become a no-op; conflicting tag targets, lightweight tags, different bytes and incomplete already-published Releases fail closed. Never delete/move a tag or use `--clobber` to force progress. Candidate artifacts expire after seven days: promote/resume while the exact successful attempt is retained; expired evidence is not permission to bypass validation.

The no-version-consumption guarantee covers **candidate validation failures**. Once publication creates an official tag, that version is bound; a later publication failure must resume that identity rather than allocate another patch version automatically.

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

## Product build identity and the new release API

All numbered product candidates use the reusable `allocate-build.yml` workflow.
It atomically reserves a number in the independent `build-ledger` branch using
fast-forward-only Git ref updates. Stable, beta and future nightly workflows must
reuse this allocator; do not use each workflow's `run_number` as a global counter.
A reservation binds `version`, `channel`, source SHA, run ID and run attempt.
Repeated allocation of the same identity is idempotent; a rebuilt attempt gets a
new number. Failed builds may leave gaps without consuming a new product version.
Re-run **all candidate jobs**, including the allocator, when rebuilding a failure.
Publishing/retrying/mirroring already built files does not allocate a new number.

CI stamps numbered product JARs with `Turboism-Build-Number` and
`Turboism-Source-Revision`, and retains `build-identity.json` inside the Actions
candidate bundle (not as a ninth public Release asset). Protected promotion checks
the receipt against the ledger and packaged JARs and publishes an identical
machine-readable comment in the Release notes. History without a receipt retains
an unknown build number; do not backfill it from dates or legacy numbering.

The new `api.turboism.dev` service synchronizes published GitHub Releases through
OIDC-authenticated notification plus a 15-minute scheduled reconciliation. It does
not invoke the legacy coordinated Updates publisher or require manual JSON edits.
Publishing a beta/nightly build still requires an explicitly reviewed product
candidate; this change does not start automatic nightly software publication.
