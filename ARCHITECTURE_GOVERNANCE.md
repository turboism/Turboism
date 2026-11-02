# Architecture governance: boundaries, AST transforms and golden snapshots

## Scope and dependency direction

This foundation **does not split runtime yet**. Existing `checkModuleBoundaries`,
`checkPluginBoundaries`, `checkPluginPermissionAudit` and SDK ABI gates remain
authoritative. Do not replace type-aware or compiled-class checks with AST search.

The next proposed split is the pure persistence codec/migration kernel, followed
by pure compatibility policy, but only after an import/dependency-cycle inventory.
No module should reference the composition layer or Cubism host to implement
persistence. Keep the runtime/host adapter responsible for actual host IO.

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
bytes). Add fault injection for crashes, disk errors, symlink attacks and rollback
safety. Do not claim atomic replacement alone is a transactional backup.

## Delivery and merge policy

`devCheck` runs `checkArchitectureRules`, `checkGoldenSnapshotManifest` and the
focused JUnit golden test; `checkCompletedCommit` and `checkRelease` run
the static checks as well, and the normal runtime test suite includes the golden
JUnit class. Both static gates depend on their own fail-closed selftests, and
`checkGateWiringSelfTest` asserts they stay aggregated and documented, so a gate
cannot be dropped by deleting one `dependsOn` line.

This document is the initial governance baseline. Track intended module splits
and follow-up evidence in issues rather than moving hundreds of classes blindly.
