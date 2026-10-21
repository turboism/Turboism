# SHA-256 check simplification

The host artifact declarations in `ReviewedHostArtifacts.java` are the single
source for shell/Python host identities. Adding a reviewed host digest changes
one declaration instead of restating the same value in five or more consumers.
This does not make the entire version-onboarding procedure a one-file change.

The circular digest quality ratchet, `verification-record-pins.json` and
`updateVerificationPins` are retired. Repository record tests check schema,
identity, capability/alias relationships and mapping/profile consistency. Runtime
record admission no longer compares record bytes with their generated self-hash;
it checks manifest fields and host selector structure instead. A compatibility
contract carries the digest actually observed during probing to reject changes
between probing and resolver construction.

Generated record digests still exist as provenance metadata for reports and
existing evidence interfaces. Removing those metadata fields is a separate format
migration; they no longer supply the removed runtime self-hash admission gate.

All 290 DRAFT packs were compared with base `9bfd3c58a`. The 283 changed packs
contain exactly the original data except for the approved changes: per-entry
`x.review` removal, per-file `exactArtifactSha256`, and removal of the two access
flag copies in `x.verification`. Access flags remain in verification selectors.
The packs shrink from 33,213,386 to 24,999,956 bytes: 8,213,430 bytes removed.
The generator, validators and import tests use the reduced representation.

`checkCompatibilityMatrix` inputs now track the Java tokens the generator reads,
plus previously omitted metadata dependencies. Unused update asset digests are
removed; security/release documentation distinguishes transfer integrity from
independent trust anchors.

The earlier audit overstated local packaging gates: the inspected `package-*.sh`
scripts emit checksum inventories rather than compare locally built JARs against
independent expected hashes. These inventories remain records. Performance probe
agent and fixture digests bind a report/rollback manifest to the actual run;
those checks remain to reject stale or mismatched evidence. Dependency downloads,
reviewed host/class bytes and signed plugin metadata retain their checks.

Validation results and any environment/baseline failures are recorded in the
implementation handoff rather than hidden by disabling repository gates.

## Acceptance evidence (2026-10-21)

- `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew devCheck spotlessCheck`:
  BUILD SUCCESSFUL. Runtime admission: 3,895 tests, 56 skipped; integration
  admission: 23 tests, one skipped; no failures. Headless mode avoids this
  desktop's blocking `Robot.createScreenCapture` / Screencast portal call.
- Focused resolver/repository tests: 48 tests, no failures. Probe/evidence Python
  suites: 9 + 26 tests; queue: 121 tests; memory identity/process cleanup: 12 + 11
  tests; all passed. Queue multiprocessing requires normal host permissions.
- `checkCompletedCommit` was attempted. It fails in the inherited atlas folder
  plugin's `OfficialPluginCatalogCompletenessTest`: unsorted baseline keys and
  copied English values in Korean resources. The aggregate has not passed and
  this branch has not been promoted to `main`.
- Real-host smoke was not run: this workspace has no `.env`, and host root,
  golden prefix, Proton runner and 5302 fixture settings are unset.

The three delegated branches and the previously uncommitted phase-one follow-up
are preserved in the integration branch `ci/simplify-sha256-gates`.

Workspace cleanup remains unconfirmed: Paseo MCP archive timed out after 300s,
and CLI archive attempts timed out after 45s. The dedicated workspaces are
`wks_a3261bfdd8a0565c` (digest-single-source), `wks_02a18a39a7105ca4`
(hash-trust-clarify), and `wks_c386e683950f430a` (draft-pack-hash-hoist).
Their changes are preserved above; retry workspace archival when the daemon
responds. Do not archive just their agents or manually discard the worktrees.
