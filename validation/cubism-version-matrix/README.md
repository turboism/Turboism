# Cubism version-matrix exact-host observations

The [2026-09-30 results](2026-09-30-host-results.json) bind each observation to a
shared-queue job, immutable prepared digest, run ID and retained evidence directory.
The workload is the disposable Workspace validation harness, including its
validation-only provider. It does not establish production Workspace composition,
texture-relation semantics, PSD export fidelity, replacement Undo/Redo or persistence.

## Findings

- **5.2.03:** final supervisor PASS, verified identity, unchanged fixture, normal
  exit and safe task containment cleanup.
- **5.3.04:** the first attempt exposed an incomplete finalizer host inventory.
  Its matching kernel cleanup proof allowed formal recovery as failed/UNKNOWN;
  the preliminary lifecycle must not be interpreted as acceptance. The finalizer
  now pins the same 20 Editor artifacts as the reviewed records. Subsequent
  failed attempts finalize normally without quarantining the shared queue.
- **Core profile routing:** the provider and structural tracer used the declared
  Editor version instead of the resolver's admitted Core contract version.
  The fix uses `admittedCubismVersion()` consistently with the connector's existing
  tuple expectation. A regression admits the synthetic matching tuple through a
  different declared Editor generation and rejects a mismatching tuple.
- **5.3.04 after routing fix:** native Core reports `6.0.513`; the bound reviewed
  profile expects `6.0.257`. Admission remains rejected. The observed tuple and
  exact Core JAR/JNI hashes are evidence for future review, not new trust roots.
- **5.0.00:** runtime reached ACTIVE but the host exited before the Workspace
  terminal result. Native UI evidence contains the warning that the fixture was
  saved in 5.2.03 and a subsequent update dialog. These are observations, not a
  proven cause of exit. A fixture appropriate to 5.0 and explicit dialog evidence
  are needed before treating this as a behavior regression or a pass.
- **5.3.00 initial run:** reached native Workspace setup, then Runner refused the
  workload because Mesa threading was enabled without an active deferred GL error
  check. A repeat explicitly disables that optional optimization using supported
  task-local Linux/JVM options. That repeat reached host ACTIVE but returned FAIL:
  the project did not become ready within 180 seconds. Supervisor cleanup is safe.
  Neither run establishes optimization or Workspace behavior readiness.

## Verification of the fixes

- Core/connector regressions: 103 tests, no failures or skips.
- Evidence tests: 25 tests, including the full existing lifecycle suite on 5.3.04
  and a strict identity-table comparison against all reviewed Editor records.
  Before the inventory fix this regression failed with one mismatch and twelve
  rejected expanded-version layouts.
- Isolated retention tests: 41 pass (require local Unix socket permission).
- `devCheck`, Python lint and whitespace checks pass.

No original model, official installation or golden prefix was edited. Failed-run
prefixes and evidence are retained under the queue's normal retention rules.

The read-only wait client twice exhausted its bounded SQLite lock retries. This
was a client infrastructure failure, not cancellation or a host verdict. Results
above were checked against each exact job's authoritative supervisor lifecycle;
no duplicate job was submitted merely because a waiter failed.
