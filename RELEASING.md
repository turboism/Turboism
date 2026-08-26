# Releasing Turboism

Turboism releases are planned by one public command and published by protected CI. The planner distinguishes four outcomes:

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
python3 scripts/release/verify-release.py --version <version> --dist build/windows-installer/dist
```

Release notes are always extracted from the exact version section of `CHANGELOG.md`.

## Credentials and repository hygiene

Core release behavior belongs in tracked scripts, tests, and workflows. Do not place release decisions or credentials in `AGENTS.md`, `.claude/`, local specifications, or operator prompts. Optional user-level automation may only invoke this CLI.

The framework workflow never receives the Plugin Directory signing key or Cloudflare credentials. The signing key remains in the Plugin Directory signing environment; Cloudflare credentials remain in the Updates service environment.
