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
- **5.3.04 after routing fix (before the new Core profile):** native Core reports `6.0.513`; the bound reviewed
  profile expected `6.0.257`, so that run correctly rejected admission. The next
  review is recorded below; this historical failed run remains a failure.
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

## New Core review, 2026-10-01

Cubism 5.3.04 now has a separate exact Core profile. Its 43,708-byte JAR is
pinned to `5458a64ebe33b38350de1437399343b0d502f2353a34a97da76d56d118c0d186`.
The native tuple expectation is exactly `6.0.513`, observed in the retained
5.3.04 run above; the 5.3.02 expectation remains exactly `6.0.257`.
The observed paired JNI DLL is 137,984 bytes, SHA-256
`71d5757025cd31c61be0d52ad79d4cd0de0465e1df6c4508c4fde70207613985`.
The DLL hash is recorded evidence; resolver admission pins the JAR, classloader,
selector contract and runtime tuple.

All 82 selectors passed the production Java `StaticVerificationCli` against
the exact 5.3.04 Core JAR. The observed public classes and member descriptors/access
flags match 5.3.02. The new profile includes parameter-repeat capability, a
separate record trust root, generated selector contract, compatibility candidate,
bootstrap resource and DRAFT mapping/profile registration. Wrong-profile artifacts
and classes loaded from the old Core are rejected by regression tests.

Validation: 99 Core/runtime resolver tests passed without skips; the Core inventory,
member and selector gates, generated-source consistency, Python lint and
`devCheck` passed. Mapping integration passed after adding the independent
new-record digest pin. Optional exact-host integration remains subject to its
existing external-fixture skips.

The [20-version Core scan](2026-10-01-core-selector-exploration.json) records exact
artifact facts and each missing selector. It verifies classfile shape only:

- 5.0.00–5.1.03 match 62/80 of the 5.2 contract. Deformers, glues and related
  drawable/model getters are absent; the full structural-read profile cannot
  simply be copied to these versions.
- 5.2.00–5.2.02 match 80/80, but each has distinct Core bytes and still requires
  its native tuple and host behavior to be observed.
- 5.3.00–5.3.03 share the exact reviewed Core JAR and match 82/82.
- 5.3.04 also matches 82/82, with distinct bytes and the new tuple.

Queued host probes and authoritative results are tracked separately from these
static findings. Core acquisition examines stable Editor document/model identity
and public getters for a Core model bridge. A `NO_PUBLIC_BRIDGE` result would
not demonstrate structural reads or owned-MOC execution.

## Host follow-up

The [2026-10-01 results](2026-10-01-host-results.json) preserve separate outcomes:

- 5.3.04 Core acquisition first run: new Core and Editor hashes match, adapter
  READY and runtime RUNNING; model acquisition timed out while startup was blocked
  by the native license-selection dialog. Final FAIL, safe cleanup.
- 5.3.01 Workspace: runtime ACTIVE and custom Workspace created, but the project
  readiness check timed out. Native Start/confirmation dialogs were observed;
  this is not a Core selector failure or a completed behavior pass.
- 5.3.04 Workspace: final supervisor **PASS**, identity verified, unchanged fixture,
  normal exit and safe cleanup. This run needed manual confirmation of its exact
  task-owned initial-layout dialog. The harness recognized “default layout”
  but the native English prompt said “initial layout”; the matcher now handles
  both while still requiring the unique task workspace name. A FREE-mode
  parameter-limit information dialog was also observed. The validation-only
  Workspace provider and this fixture do not establish production composition,
  unrestricted model editing, PSD export or texture replacement.

The Core acquisition probe now offers the explicit task-local JVM property
`turboism.coreAcquisition.startFree=true`. It uses Swing public UI components
on the EDT to select only the exact native FREE startup button and confirm only
the FREE welcome message. It never chooses purchase, activation or trial options.
The self-check rejects unrelated dialogs and trial/activation labels. This helper
does not relax the stable-model, exact-hash or final lifecycle assertions.

The final 5.3.04 Core acquisition repeat (`t17-r3`) received supervisor **PASS**
without manual GUI assistance: exact Editor/Core hashes and defining classloader
match, three consecutive EDT snapshots have stable document/source/pack/view/model
identity, the fixture is unchanged, and exit/cleanup are normal/safe. Both FREE
startup actions appear in the probe log. Its bridge outcome is **NO_PUBLIC_BRIDGE**:
the tested Editor objects expose no public getter returning a native Core model.
Consequently native Core structural reads and owned-MOC execution remain unproved
by this workload. This is a successful identity/acquisition reconnaissance result,
not a blanket pass for every Core API.

The follow-up Workspace probe regression passed all 21 tests. All jobs created
for this round are terminal; the superseded `t17-r2` never launched.
