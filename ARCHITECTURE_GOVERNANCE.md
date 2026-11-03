# Architecture governance: boundaries, AST transforms and golden snapshots

## Scope and dependency direction

This foundation **does not split runtime yet**. Existing `checkModuleBoundaries`,
`checkPluginBoundaries`, `checkPluginPermissionAudit` and SDK ABI gates remain
authoritative. Do not replace type-aware or compiled-class checks with AST search.

The next proposed split is the pure persistence codec/migration kernel, followed
by pure compatibility policy, but only after an import/dependency-cycle inventory.
No module should reference the composition layer or Cubism host to implement
persistence. Keep the runtime/host adapter responsible for actual host IO.

## Solo-Maintainer Governance Principles

This repository is maintained by one person. Governance therefore optimises for
*decisions that stay reviewable by their author tomorrow*, not for distributed
ownership ceremony. Three consequences:

1. **The Cubism version surface is the primary target.** Cubism Editor 5.2+ is
the supported host line, and **5.2.03 and 5.3.02+** are the Tier-1 deep
adaptation targets: they are where exact-version adapter work, host validation
runs and SDK parity claims must be proven. The older 5.0.x/5.1.x adapters stay as
shipped compatibility, not as places to invest new capability. Any claim about a
version outside the listed set fails closed rather than degrading silently.
2. **Third-party plugin ecosystem mechanisms are deferred.** Extension points,
published plugin ABI guarantees, marketplace submission rules and sandboxing for
external plugin authors stay unbuilt until an actual external plugin consumer
exists. Speculating about a plugin ecosystem with no consumer produces API
surface that cannot be validated, and API surface is the most expensive thing
here to retract. The first-party plugin set under `plugins/` is the only
supported consumer today.
3. **Every verification gate must justify itself.** A new gate cites a concrete
user-harm mode — what breaks for a user if the gate is absent — or a specific
past incident with a date. "It would be good practice" is not a citation, and a
gate that cannot name its harm gets removed rather than maintained. All aggregate
gates are wired in exactly one file, `gradle/verification.gradle.kts`; a new
task registered anywhere else is either a leaf helper or a wiring mistake, and
`scripts/test/test_ci_coverage.py` asserts the centralized aggregate declarations stay complete.

Review routing follows from the same reasoning: `.github/CODEOWNERS` is a single
default rule because a per-path table of the same owner carries no information.
The paths that need care are protected by the gates above, not by a routing list.

## ast-grep contract

CLI pinned to `@ast-grep/cli@0.45.3` (required by `devCheck`).

```bash
npm install --global @ast-grep/cli@0.45.3
python3 scripts/check_ast_grep.py
# Review a scoped rewrite without applying it:
ast-grep scan --rule tooling/ast-grep/codemods/java17-empty-list.yml plugins/mcp
# Apply an explicitly reviewed rewrite interactively:
ast-grep scan --rule tooling/ast-grep/codemods/java17-empty-list.yml plugins/mcp --interactive
```

The AST JVM-termination rule exempts only
`plugins/mcp/src/main/java/dev/turboism/plugin/mcp/TurboismMcpBridge.java`:
it is a separately launched stdio bridge process whose fatal exit does not
terminate Cubism. This is not a general exception for the MCP in-host plugin.

The codemod directory is intentionally **excluded** from lint rule discovery.
No agent may call `--update-all` on a whole repository without a scoped file
list, a diff review, and subsequent compilation/tests. For nonlocal Java symbol
renames and type-sensitive rewrites, use a type-aware refactoring tool.

## Golden snapshot contract

`tests/fixtures/snapshots/manifest.json` is the immutable fixture inventory.
`checkGoldenSnapshotManifest` ensures each file is listed once with a stable SHA-256,
and `checkGoldenSnapshotManifestSelfTest` proves the checker rejects a changed hash,
an unlisted, missing or symlinked fixture, an escaped path and a bogus provenance
instead of passing silently.
`persistenceGoldenSnapshotsTest` replays the complete manifest using the real
RuntimeConfigRepository and RuntimeTypedPluginConfigRegistry. These are
independent offline tests, with no Cubism installation requirement.

Current fixtures only cover **synthetic v1 format examples**, not user archives
or all historical release versions. Release-derived samples must be captured,
sanitized, and provenance-reviewed separately, never invented.

Every new persisted schema version should add a fixture and expected semantic
assertions before production changes merge. Never change previous bytes to
make a failing migration pass. Tests must distinguish stored values from
fallback defaults and verify both repeated reads and byte-preservation on failure.

The next phase must cover actual release-generated documents, plugin-specific
storage, installation journals and signed update receipts (without changing signed
bytes). Do not claim atomic replacement alone is a transactional backup.

## Recovery point contract

A destructive migration may not replace the only copy of a user's stored bytes.
`TypedConfigDocumentStore` therefore captures a **recovery point** immediately
before the first read-time or write-time migration replaces a typed config
document; `RuntimeTypedPluginConfigRegistry` calls it on both paths, and no other
code path needs it.

- **Location and shape**: `<config root>/.recovery-points/<document path>.recovery/`
  holding `<schemaVersion>-r<revision>-<sha256>.bak`. The file *is* the document
  bytes, byte for byte, in the existing `turboism-typed-config-v1` envelope; the
  name records the state it holds and the SHA-256 of its content. No second
  format is introduced and no document format changed.
- **Atomic replace**: a point is written through the same owner-only temporary
  file, fsync and atomic rename as a document, so it is either absent or
  byte-identical — never partial. A crash before the rename leaves the old
  document, a crash after it leaves the new one; both are complete images.
- **Bounded retention**: at most `RECOVERY_POINT_RETENTION` (3) points per
  document, oldest document state pruned first, never the point just captured.
  Pruning runs only after a durable capture and is best effort: an extra retained
  copy cannot lose bytes, so it must not turn a capture into a failure.
- **Fail closed**: a capture that fails — unusable recovery area, unreadable
  document, or a concurrent writer that changed the bytes since they were read —
  aborts the migration with `PERSISTENCE_FAILED` and leaves the document
  untouched. A migration never proceeds on the strength of an older point.
- **Not a new single point of failure**: `read` never consults the recovery area,
  so a damaged or unwritable recovery area cannot take readable documents
  offline; it fails only the migrations that need it, and reports them. A damaged
  point with a matching name is rewritten on retry, never trusted.
- **Recovery**: restoring a point is a plain atomic write of those bytes back to
  the document path, after which the production reader returns the pre-migration
  schema version, revision and values. Recovery is deliberately not automatic:
  an automatic rollback would need a policy decision that this contract does not
  make.

Fault-injection evidence lives in `TypedConfigRecoveryPointTest` (IO errors,
interruption before and after the replacement rename, concurrent writers, symlink
and path-replacement attacks, damaged points, retention) plus the migration
regressions in `RuntimeTypedPluginConfigRegistryTest` and the immutable-fixture
replay in `PersistenceGoldenSnapshotsTest`. Every case asserts that the
pre-migration bytes stay recoverable. All of it runs offline, without a Cubism
host or an installed Editor.

Still open (requires evidence or an explicit release decision, tracked in `#69`):
release-derived fixtures with provenance, the legacy global `config.json` path
(it has no read-time migration yet), plugin install/enable journals, launcher
state and signed update receipts.

## Delivery and merge policy

`devCheck` runs `checkArchitectureRules`, `checkGoldenSnapshotManifest` and the
focused JUnit golden test; `checkCompletedCommit` and `checkRelease` run
the static checks as well, and the normal runtime test suite includes the golden
JUnit class. `scripts/test/test_ci_coverage.py` asserts they stay aggregated in
`gradle/verification.gradle.kts`, so a gate cannot be dropped silently.

`.github/CODEOWNERS` is a single default rule for the sole maintainer. Ownership
of persistence formats, migrations, API baselines, verification wiring and the
governance tooling is recorded here and enforced by gates; see
"Solo-Maintainer Governance Principles".

Main is now enforced by the repository ruleset `protect-main` (id 26441981,
`refs/heads/main`, active, no bypass actors):

- merges must arrive through a pull request (`required_approving_review_count: 0`,
  stale reviews dismissed on push, all merge methods allowed);
- `devCheck` and `scan reachable history` must pass — both run on every pull
  request, so a branch that carries a forbidden path or a failing gate cannot be
  merged;
- branch deletion and force push (non-fast-forward) are rejected.

There are deliberately **no bypass actors**. Every account with write access is
an administrator here, so a standing admin bypass would make the protection
nominal. The emergency exception is therefore an explicit, audited admin action:
edit or temporarily disable `protect-main` in the repository settings, land the
one change, and re-enable it. Treat that as an incident, not a workflow — main
stayed red for two hours on 2026-11-02 precisely because a direct push skipped
the gates.

Enabling "Require review from Code Owners" in the same ruleset is still pending a
second reviewer with write access; turning it on today would make the sole
owner's own pull requests unmergeable.

Verified: a direct push to `main` is rejected with `GH013 ... Changes must be
made through a pull request ... 2 of 2 required status checks are expected`.
Not yet verified: the required-check behaviour for a non-administrator
contributor (there is only one collaborator).

This document is the initial governance baseline. Track intended module splits
and follow-up evidence in issues rather than moving hundreds of classes blindly.
