# Parameter and parameter-group row locks

This revision replaces the toolbar toggle with native lock icon slots on each parameter and parameter-group label. Context menu actions remain available.

- A parameter row acts on its own ID, independently of selection.
- A group row recursively includes parameters in nested groups, with cycle protection.
- Group states: no descendants locked = UNLOCKED; some = MIXED; all = LOCKED. Clicking MIXED locks all; clicking LOCKED unlocks all.
- Runtime resolves the existing `CubismIcon.LOCK` resources through the verified host connection. No copied icon assets are bundled.
- Unlocked rows continuously display the native `None_16x16` ring placeholder, matching Parts/Deformer rows; mixed groups show the disabled lock continuously; locked rows show the normal lock. Existing native folder icons are preserved.
- Row actions have plugin, content, model and host generation scope. Removal restores native decoration; callbacks check permission and scope before invocation. Copies retaining a model GUID still have separate live document scopes.

## Offline evidence (2026-10-17)

The production plugin suite passed: 42 tests, 0 failures/errors/skips (including the existing persistence, disable/re-enable and shutdown regressions). Plugin formatting and README checks passed. The SDK test suite passed: 440 tests, 0 failures/errors, 1 skipped. The final focused Runtime suite passed: 40 tests, 0 failures/errors/skips (row interaction/rendering, appearance registration lifetime, provider restore, native icon resolution). Additional native selection bridge and transformer regressions passed in the earlier focused gate.

`checkModuleBoundaries`, `checkPluginBoundaries` and `checkPluginPermissionAudit` passed for the current 30-plugin roster. Metadata validation and the PSD clip-mask descriptor regression passed. SDK annotation checks and all 30 localized plugin README contracts passed. The row probe descriptor passed the production `PluginMetaValidationCli` schema validator. Windows config merge/payload/uninstall simulation passed after regenerating the 26-plugin NSIS roster from the existing assembly generator and current descriptor resources.

The Runtime tests use Swing components and fake icons. They prove ownership, restoration, hit testing and state rendering, but do not establish real Cubism label layout or actual pointer interaction.

## Real-host evidence

The final layout-capture revision passed managed host validation on 5.2.03 and 5.3.02. Every job below finished with `terminalState=succeeded`, `normalExit=true`, `identityVerified=true`, `fixtureUnchanged=true`, and `cleanup=safe`.

| Version | Mode | Job | Result |
|---|---|---|---|
| 5.2.03 | row + group matrix | `b94f7555-8c07-4c0c-b163-4533a77d852e` | PASS, all 2/2 group descendants |
| 5.3.02 | row + group matrix | `356d82ae-fd96-450b-bcc1-67023bd29c85` | PASS, all 2/2 group descendants |
| 5.3.02 | fresh-process persistence read | `d8e762f7-ee5b-4ded-9e02-4efd2857b063` | PASS, restored before first click, unlocked via row |

Both matrices verified the native 16px CachedIcon source, real row MouseEvent clicks, SDK and native-controller writes before/during/after locking, and real group MIXED → all locked → all unlocked. The existing visible fixture group has one member; the probe temporarily moved `ParamAngleX` into it using the public SDK, tested both descendants, then restored membership in `finally` (`groupFixture.restored=true`). The model was never saved. The persistence seed came from an earlier production row-click write job, preloaded by the runner before startup, with no probe-time copying.

Exact tested artifacts: runtime `f5242ef058d42a9b9d279a20f638e11fca8c793798feb8feb04dd3e714082b84`, plugin `f871b6558b5e9c334c2398705c9ba01cc8622db6c688749e0fc4cc9876618e1d`, final matrix probe `ce6dc5b066b71497f2de28674ebc31ea48238b87f8edf5f64fb375109a0049f8`. Structured results and per-job staged hashes: [row-host-results.json](row-host-results.json).

**Coverage limits:** The tested groups were flat. Live deep nested groups, physical slider dragging, text keystrokes, Undo/Redo, and multi-model switching remain untested; deep recursion has offline regression coverage.

**Subsequent visual correction:** The user requested a continuously visible ring for unlocked rows. The runtime now resolves the native `None_16x16` family (20 variants verified across all 20 reviewed JARs). The matrix jobs above precede this visual correction; their hashes must not be presented as evidence for the new ring rendering. Updated rendering/contract checks and a fresh manual window are tracked below.

Previous toolbar runs remain separately in [HOST-ACCEPTANCE.md](HOST-ACCEPTANCE.md).

## Diagnostic history (superseded revisions)

### Initial host attempt

5.3.02 job `11c1385f-4ca3-4b89-ac70-20d197f89c4c` (`queue-5af44032a641402d832ff2be062b0b1f`) failed before any interaction checks: the probe inspected the first parameter label after 38 ms and found no row-action marker. This attempt does not establish row-action behavior. The probe must await the asynchronous production decoration before verifying or clicking it. Queue cleanup was safe; post-containment hashes showed the original fixture unchanged.

### Readiness retry

5.3.02 job `fd01b871-b0b9-4e90-b1fb-e258bf16fb56` (`queue-32fd1ac84c7a4b6ca8e572d459c01966`) also failed after a 20-second readiness wait. The label was showing, the appearance bridge/provider/coordinator/native resolver were present, and 30 overrides were registered, but the coordinator contained zero parameter-control bindings. This rules out a short probe readiness race as the complete explanation. No interaction checks passed. Cleanup was safe; post-containment original fixture SHA-256 remained `57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4`. This failure was resolved by the existing-row replay described below.

### Existing-row capture diagnosis and fix

Read-only diagnostic job `531544cf-ca18-4c32-bc9c-d4ceb698517d` (`queue-bd6c83909abd42f596e2f6182a52a543`) confirmed that the exact row owner, host classloader, native source, parameter ID and Swing label selectors all succeeded, despite an empty binding catalog. Constructor hooks do not replay widgets created before hook installation; the previous existing-row replay was reachable only through parameter filtering.

The appearance provider now replays the current verified Editor parameter operation rows before reapplying overrides. `HostSession` owns the verified row source and revokes it on disconnect; coordinator invalidation/close also drops it. This uses the existing exact Editor-model selectors and does not invoke callbacks from the validation probe. A new regression creates rows before bridge installation, registers a lock without any filter contribution, verifies the native-label marker, and verifies restoration and catalog cleanup. The final focused Runtime run passed 69 tests (all control-appearance tests plus `HostSessionTest`), with zero failures/errors. Log: `/tmp/parameter-row-replay-final.log`.

Runtime preview SHA-256: `f6d8e2036bf0d9b5f98d231234abfb8bc8f9aa6bd80e4814542ba495ac8140c1`; production plugin SHA-256: `f871b6558b5e9c334c2398705c9ba01cc8622db6c688749e0fc4cc9876618e1d`; read-only diagnostic probe SHA-256: `16888dbe2b6de8eb96eade20c8b8bf9764794ea440c4441b1a6b34b3a4ff98ff`.

### Parameter-row and persistence passes (first replay revision)

The following managed jobs completed with `terminalState=succeeded`, `normalExit=true`, `identityVerified=true`, `fixtureUnchanged=true`, and `cleanup=safe`:

| Version | Mode | Job |
|---|---|---|
| 5.3.02 | matrix | `d2c37947-1cd7-4f53-8277-d6e00638451b` |
| 5.2.03 | matrix | `4c1171c9-7074-4ecc-b051-5253f70b3d32` |
| 5.3.02 | persist-write | `905e9f95-3de9-4c52-bc7c-2a59a4c85b5a` |
| 5.3.02 | persist-read | `27963bf9-0ddf-4ca0-858d-6b359759270c` |

Matrix checks proved the actual native 16px icon source, parameter-row MouseEvent lock/unlock, SDK writes and native-controller writes before/while/after locking. Write mode captured the production JSON generated by a row click. Read mode used that JSON preseeded by the runner before a fresh host launched and verified automatic LOCKED state/write blocking before its first UI click, then unlocked through the row UI.

These matrix runs explicitly reported group recursion and MIXED state as NOT_TESTED. Investigation found the existing `parameter-operation.rows` selector reads `f(): CArrayList<B>`, which contains parameter rows but excludes folder widgets. An intermediate proposal treated `g(): List<I>` as the full row list; this interpretation was subsequently disproved and the alias removed (see below). All 20 exact reviewed JARs structurally verified the new method, and their matching records/mapping packs were updated together. These early jobs do not establish group acceptance. Job summaries are in `row-host-results.json`; previous runtime hashes remain associated with their actual jobs.

The production Java `StaticVerificationCli` subsequently verified all selectors in all 20 updated Editor-model records, including 20/20 `parameter-operation.palette-rows` successes (`/tmp/parameter-row-all-static.log`, Gradle `BUILD SUCCESSFUL`). Alias admission consistency passed for 796 consumed aliases. The complete-list preview SHA-256 is `7305a0544d49ee690ed00cbfa495fd9ad7287b86486e74b0fd1dbb7e574001cd`; control-appearance plus HostSession regressions remain 69/69 passing. Compatibility documents were regenerated with the repository generator.

### Rejected selected-row accessor and corrected hierarchy capture

Job `46d6e024-c479-4596-b6c7-43a85ff41d7c` (`queue-0c7bdb262ec6401f845dfa11192f5481`) failed with no bindings on the proposed `g()` revision. Bytecode analysis of the native selection controller proved that `g()` exposes the selected-row list, not all palette rows. The preceding complete-list interpretation was incorrect: structural verification proved a method signature, not its semantics. That proposed alias has been removed from records, mapping packs, authoring templates and generation rules.

The corrected source preserves `f()` for all parameter rows and traverses the actual native layout: operation `b(): RowLayoutVBox`, `CContainer.getChildren(): List`, then each exact `ParameterGroupRowWidget.getRowLayout(): RowLayoutVBox` recursively. The native controller itself uses this hierarchy to visit folder rows. The three new exact selectors structurally verified against all 20 reviewed JARs before the records were written. An identity set prevents repeated rows/layout cycles. Real-host acceptance of this corrected revision passed in the final matrices listed above.

## Native ring correction checks

SDK icon contract and focused runtime native resource/row rendering tests passed (`/tmp/parameter-lock-ring-build.log`, `BUILD SUCCESSFUL`). Rendering regression asserts unlocked pixels are painted both before hover and during hover, separately from mixed/locked rendering. Preview runtime SHA-256: `41c6c395233ce34cdc26e70b3828149b56af67bbe3f4f25e7043fe93ec2673d4`. Native resource provenance: [native-lock-icon-provenance.md](../../compatibility/cubism/native-lock-icon-provenance.md).

The ring-correction 5.3.02 matrix job `9f10dcd0-ba85-484c-8051-373860f2342c` (`queue-7465328d7bf245b6bae6b9273e3a7b45`) passed with normal exit, verified host identity, unchanged fixture, and safe cleanup. All row checks and group MIXED/all-lock/all-unlock checks passed; group lock and unlock verified 2/2 descendants and restored fixture membership. The probe checks interactions and icon identity; the unlocked ring appearance is additionally checked by the rendering regression and manual-window screenshot.

Manual session `535e5de7-ca1e-4b4f-8295-b5d514e619c2` (`queue-a55fd023940e42249c4b0a39658cedcd`) was opened on the physical desktop with the production plugin only. Window screenshot `/tmp/parameter-lock-row-ring-window.png` confirms a normal lock on prelocked `ParamAngleX` and continuously visible native rings on other parameter rows and the folder row. The window remains open for user inspection; this interactive session has no terminal acceptance outcome yet.

The subsequent editing restriction revision is tracked separately in [NATIVE-EDITING-ACCEPTANCE.md](NATIVE-EDITING-ACCEPTANCE.md); earlier row/icon hashes do not prove disabled native editors.
