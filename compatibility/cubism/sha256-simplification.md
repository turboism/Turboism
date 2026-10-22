# SHA-256 check simplification

The reviewed facts in `reviewed-releases.json` are the single source for host
identities. `ReviewedHostArtifacts.java` is generated from that table, and
shell/Python tools read the JSON directly. Adding a reviewed host digest changes
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

`checkCompatibilityMatrix` inputs track the canonical release table and the Java tokens the generator reads,
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

- Final full gate, after integration and baseline fixes:
  `env -u JAVA_TOOL_OPTIONS -u DISPLAY -u WAYLAND_DISPLAY ./gradlew --no-daemon checkCompletedCommit --continue`:
  BUILD SUCCESSFUL in 10m 10s; 391 tasks (53 executed, 338 up-to-date).
  Display variables were unset instead of injecting `JAVA_TOOL_OPTIONS`, which
  adds output to subprocess protocols. The final run passed all repository gates.

- `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew devCheck spotlessCheck`:
  BUILD SUCCESSFUL. Runtime admission: 3,895 tests, 56 skipped; integration
  admission: 23 tests, one skipped; no failures. Headless mode avoids this
  desktop's blocking `Robot.createScreenCapture` / Screencast portal call.
- Focused resolver/repository tests: 48 tests, no failures. Probe/evidence Python
  suites: 9 + 26 tests; queue: 121 tests; memory identity/process cleanup: 12 + 11
  tests; all passed. Queue multiprocessing requires normal host permissions.
- Final integration includes the current `main` runtime diagnostic changes.
  Inherited atlas folder metadata, localized resources and README contracts were
  reconciled with the compatibility generator and official plugin requirements.
- Real-host smoke `status-bar:5302`, label `sha256-smoke-fixed`, passed through
  the supported host-validation queue using the main checkout's environment.
  Job: `a0465c91-5217-4f5a-bfc5-0350910c8823`;
  run: `queue-8ca4cca3177b486e8a9897f0b7f726a7`.
  Result: `PASS`, zero failures, verified host identity, unchanged fixture and
  model identity, normal exit, safe cleanup, no retained prefix.
  Evidence: `$VALIDATION_ROOT/status-bar/5302-sha256-smoke-fixed/queue-8ca4cca3177b486e8a9897f0b7f726a7`.
  The initial smoke exposed a probe-only shutdown lookup using the isolated
  plugin loader; the probe now explicitly uses the system loader for bootstrap.
  The corrected probe passed on the rerun. The task's idle worker was stopped.

The three delegated branches and the previously uncommitted phase-one follow-up
are preserved in the integration branch `ci/simplify-sha256-gates`.

The three dedicated workspaces were successfully archived on 2026-10-21:
`wks_a3261bfdd8a0565c` (digest-single-source),
`wks_02a18a39a7105ca4` (hash-trust-clarify), and
`wks_c386e683950f430a` (draft-pack-hash-hoist).
Paseo managed their worktree cleanup; their changes remain in the integration
branch history.
