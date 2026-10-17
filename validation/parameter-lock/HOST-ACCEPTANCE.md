# Parameter lock exact-host acceptance

> Historical toolbar evidence. Current native parameter/group row acceptance, its artifact hashes, successful jobs and limitations are recorded in [ROW-UI-ACCEPTANCE.md](ROW-UI-ACCEPTANCE.md). The successful toolbar runs below remain distinct evidence.

Date: 2026-10-17. User authorized real-host validation. Production distribution
status remains development-only; this document does not record a release.

## Procedure

Build `previewBundle`, `:plugins:parameter-lock:jar`,
`validation/shared/build-startup-assistance.sh` and
`validation/parameter-lock-host-probe/build.sh`. Run through
`scripts/preview/run-parameter-lock-host-validation.sh`; that wrapper delegates to
the managed exact-host queue. The probe is validation tooling, not a release plugin.

The production plugin and probe are separate JARs. Each run uses the reviewed
official BAT, a task-owned CoW prefix and home, and a hash-verified model copy.
The original fixture and official installation must remain unchanged.

`matrix` checks real production UI contributions and parameter writes.
`persist-write` leaves a locked parameter and its production JSON on disk.
`persist-read` starts a new Editor process and isolated home seeded only with
that persisted JSON, then verifies restoration after model loading.
Any state transfer must use the runner's snapshotted `--home-file`/`--home-dir`
inputs; do not alter a running task home.

PASS requires both probe `status=PASS` and the queue's authoritative lifecycle
acceptance (host/artifact identities, fixture hashes, normal exit, containment
cleanup). A successful enqueue/build or process exit alone is insufficient.

## Results

The 5.2.03 and 5.3.02 matrices passed with authoritative queue lifecycle acceptance. The 5.3.02 persistence write and independent-session restoration also passed. Machine-readable evidence: [host-results.json](host-results.json).

| Job | Host | Result |
|---|---|---|
| `93bc3249-e3c1-47cb-9cb2-269048829824` | 5.3.04 | Preflight failed: official installation absent; Editor did not launch. |
| `f17885f3-c3e1-48a0-9803-a0007ae45dbb` | 5.3.02 | Production plugin loaded and model GUID read. Probe rejected for missing schema-v3 category; job cancelled through queue. |
| `50a2e760-31eb-49f1-921a-3892dc71cf16` | 5.3.02 | Native 16px toolbar icon and unlocked SDK write passed; synthetic row click did not produce a selected parameter. |
| `68e7648f-6530-479a-a864-fdf31729bded` | 5.3.02 | Native row found and `setSelectParameter()` invoked, but SDK selection remained empty. Selection diagnostics required before lock/unlock acceptance. |
| `4ac335f7-44ea-4a5a-95b4-2fc9e8143202` | 5.3.02 | Live selection reader authorized; probe had picked a hidden row (`labelShowing=false`). Source identity diagnostic was inconclusive because the SDK handle had not been fully unwrapped; correct that before interpreting identity differences. |
| `adcbe224-0d3f-4247-bdde-63449c63e012` | 5.3.02 | Fixture loaded (16 drawables, 28 parameters); `SHOW_PARAMETER_PALETTE` executed, then production toolbar not found. No lock assertions ran. Probe must inspect visibility before issuing this command. |
| `ddcf3a0d-7cca-447c-9df0-863d9b3d0672` | 5.3.02 | Toolbar and native 16px icon found without issuing show; unlocked SDK write passed. Cached row was detached by selection time; bridge installed, but private-record reflection diagnostics failed and SDK source unwrapping returned null. Re-resolve visible rows after writes and require source identity before retry. |
| `8240e754-bf1e-499e-9d00-bda5dcb49d4c` | 5.3.02 | Host did not reach readiness. Launcher reported X connection broken; no plugin assertions ran. Display was queryable again after task failure; retry through queue. |
| `7ba2ab47-4880-4d55-b64a-a3d73da73df1` | 5.3.02 | Toolbar/icon passed; 27 current rows visible. SDK parameter is `jdk.proxy1.$Proxy79`, so field-only source unwrapping failed before selection. Add exact runtime invocation-handler delegate unwrapping; no lock assertions ran. |
| `15d79bb8-f0d1-445a-8b9e-b7ff84b97b19` | 5.3.02 | **PASS matrix**: native source identity/selection, 16px toolbar icon, lock/unlock through production toolbar, SDK writes and exact native controller writes accepted before lock, blocked after lock, restored after unlock. Queue: normalExit/identityVerified/fixtureUnchanged=true, cleanup=safe. |
| `2b928a3f-ec22-44cc-bb1d-2ab0720bdd4a` | 5.3.02 | **PASS persist-write**: toolbar lock active, production JSON saved (144 bytes, `ParamAngleX`). Queue lifecycle fully accepted. |
| `e4df8946-cfce-44aa-8d28-e7fda325de8a` | 5.2.03 | **PASS matrix**: same eight checks as 5.3.02, using final dynamic-row probe. Queue lifecycle fully accepted. |
| `2dec1cd7-400a-48b8-a151-1e923ca63f8d` | 5.3.02 | **PASS persist-read**: new process and isolated home seeded before launch from write-session JSON; model loading restored rejection without a lock click; toolbar unlock restored writable state. Queue lifecycle fully accepted. |

| `18f5a99f-c64b-43f1-a311-06a36eb34837` | 5.3.02 | **PASS final matrix regression**: all eight checks passed after moving remaining read-only Swing diagnostics to EDT. Queue lifecycle fully accepted. |

The successful runs use fixture SHA-256
`57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4`,
model GUID `41f72ac0-bd7f-4431-8011-be1ee7131e8a`, and parameter `ParamAngleX`.
Queue evidence is under the current account's
`.local/state/turboism/host-validation/jobs/<job-id>/`.

Diagnosis found that native parameter selection belongs to a separate controller
list, not the update manager's global object GUID selection. The runtime now
captures the boolean from the already-reviewed `updateSelection(Z)V` row hook
and matches selected sources against the active model by object identity.
Focused regression: 16 tests, no failures/errors/skips. That connection is now confirmed live by the 5.3.02 matrix above; the unit tests remain separate regression evidence.

## Scope

Archive identity/resource review covers 20 declared versions, separately from
live-host checks. Representative live-host results must name exact versions.
Undo/Redo, native slider/text editing, reopen, and multiple-model switching
are accepted only when explicitly asserted by the corresponding host probe;
offline tests or SDK-only writes do not prove those user interactions.

## Successful matrix artifact identity

Run `queue-a7b2f77b77d64389b716f0f037282193` used probe SHA-256
`b32cb703e0fface57dc39db8634c3d89f53b3c39193d73f1a705cf1fe7cc7fe1`.
The probe unwraps runtime-owned dynamic proxies before comparing native parameter
source references; identity matched exactly and the bridge exported one selected source.
Native controller submission invokes `s.a(CParameterSource,float)` directly. It
does not constitute physical slider dragging, text keystroke, or control-display synchronization acceptance.

Acceptance probe SHA-256 (5.2.03 matrix and persistence):
`8505dfea625ed3ef80051ff01edb71bbd6796a175b0059aa0e8d13520bacbbda`.
Production plugin SHA-256:
`dd11a6a6182a6195f7fed38153b864d40cb3d50fadd6a0c0d3ec112563139b15`.
Preview agent SHA-256:
`103e57a6a441fe3fc953b236f2d35f4151dd8f5eb81fe8969455ddc95e582760`.

Final probe diagnostic-thread correction SHA-256:
`f432fd024c77b4099177d2e719f85815c66457bc550a42f73731487f331b88c5`.
The final matrix rerun above confirms the correction without changing production artifacts.
