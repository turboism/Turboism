# Settings-page validation

This plugin is test-only. It operates the real Turboism menu and settings dialog,
changes only the isolated task's preferences, restores their effective entry values,
and exits the Editor. Never install it in a user's everyday plugin directory.
The shared exact-host queue, official launcher, isolated prefix/home and immutable
fixture checks described in the host-validation runbook remain mandatory.

## Triangulation edge-index mode (T029-TLPROD)

Pass `-Dturboism.validation.settingsEdgeIndex=true` through the unified queue/Runner.
It is mutually exclusive with `settingsPerformance`; without either property the
original mesh-hash scenario is unchanged. This mode uses the actual Performance tab,
identifies `triangulation-edge-index` by contribution ID, verifies its exact shipped
i18n label and visible checkbox, then checks missing-key default-on or the exact
persisted Boolean. Apply, an unsaved opposite change followed by Cancel, reopening,
OK restoration and reopening again must preserve the corresponding values.

The probe copies—not edits—the config files produced by those actual UI saves into
`state/dev.turboism.validation.settingspage/edge-index-ui-{off,on}.json`, and reports
each SHA-256 in `settings-result.txt`. Only snapshots from a standard-gate PASS and
`status=PASS` result are admissible as UI evidence. The production capture wrapper's
`--tri-tlindex-home-config` accepts the leg-matching explicit Boolean and freezes its
SHA during prepare; that option alone does not establish that a file came from UI.
Subsequent off/on host starts must use these verified snapshots and observe pristine/
production-patched TriangleList bytes. A same-process save is not hot unweaving and
cannot substitute for restart-dependent activation/deactivation evidence.

2026-09-30 continuation: the extended probe compiles and the contribution/persistence
regressions pass offline. Global queue seq2004 is orphaned after a disk-I/O failure,
worker is offline and recovery is `safe=false`; the user chose offline convergence.
No new real settings run or restart acceptance is claimed; no queue row was forced,
no supervisor verdict was fabricated and no official host/model was modified.

2026-09-30 real-host follow-up: seq2013 ran after queue recovery and failed before
opening settings (`model and native renderer did not become ready`); its bound
supervisor recorded safe cleanup. The readiness call reads the active model, but
the probe descriptor had no `turboism.cubism.model.read` permission. The descriptor
now declares that read-only application permission, and readiness timeout reports
the last exception instead of hiding it. Production agent `4c326728…` is unchanged.
Replacement probe `5d79212f…` was built and 185 prepared input file hashes verified;
retry seq2025/job `355fecc7-a031-4562-93f2-64cdc375c500`, prepared `3433984a…`, is queued.
It has not executed: preceding unrelated seq2021 is quarantined after supervisor
error `invalid canonical task identity`. No UI PASS or restart evidence is claimed.

Subsequent recovery resolved that blocker. Seq2025 completed with `status=PASS`,
identity/fixture checks, normal exit and supervisor cleanup all passing. It observed
an active model and 13 completed native frames, default-on and the exact Chinese
label, Apply-to-off, unsaved-change Cancel, reopening off, OK restoration and
reopening on. The two actual UI configuration SHA-256 values are
`8945ee224b724641a5b468cf087be0287cb3908bca908c7abdc0c582bec9b846` (off) and
`190ea2fcf912c290a4db40ccd512becf4d3f032098f9cbeca339c2f0903cce02` (on).
The final production A/B inputs bind those exact bytes; restart and resource
acceptance are recorded separately from this settings UI result.

## Performance settings mode

Pass `-Dturboism.validation.settingsPerformance=true` through the common Runner.
Without this property the original mesh-triangulation preference scenario remains.
Build with `./gradlew :sdk:jar` followed by `bash validation/settings-page-probe/build.sh`.
The result file is `state/dev.turboism.validation.settingspage/settings-result.txt`.

Performance mode requires an active model and at least one completed native render
before opening settings. It then checks the actual uniform-location checkbox by
its contribution ID, not a language-specific label. The test shrinks the dialog to
620 x 340 and checks vertical overflow, mouse-wheel scrolling, last-checkbox
reachability, fixed OK/Cancel/Apply controls and real keyboard focus reveal.
Apply, reopening, and restoring with OK must preserve the effective preference.
Absent uniform preference means enabled; explicit false must not be lost.
Experimental incremental updates must remain unselected in the fresh task config.

A menu being visible does not imply that the host's initial document or OS window
focus is ready. Failures in those prerequisites remain failed validation runs;
scroll movement by itself never certifies keyboard focus or persistence.
The probe preserves failure causes and visible-window focus diagnostics.

## UI behavior

The runtime uses a separate scroll viewport per settings tab. Form row heights are
not compressed to fit a short dialog. Ordinary forms fill available width; when a
translation cannot fit its minimum width, a horizontal scrollbar preserves access.
Wheel/page increments follow font metrics. Focus reveal is local to each form;
there is no process-global focus listener. Dialog actions are outside the viewport.
Tab ordering, control validators and Apply/Cancel semantics remain unchanged.

Only existing, reviewed optimization defaults are retained: uniform-location
caching is enabled by default on admitted hosts, while incremental Slice B and
matrix scratch remain opt-in. This UI change does not claim a new rendering gain
or widen exact-version admission. Existing user opt-outs are preserved.

## Verification record for this continuation

- Three new layout/default regressions failed against the original unscrollable
  forms, then passed after the runtime change. Existing tab ordering and path/JVM
  save tests still pass. Layout cases use 12, 18 and 24 point fonts, narrow/short
  viewports, wheel movement, bottom-row visibility and focus-listener behavior.
- `devCheck` and the settings probe build passed. Preview bundle construction passed.
  After the final changes, focused renderer, shell registration, uniform checkbox,
  settings persistence, uniform bridge, incremental opt-in and preference-schema
  tests passed together. The display-only case remains an opt-in skip in that run.
- The opt-in Xvfb test creates an actual settings dialog. Its first run failed
  because control focus was requested before window activation; activation waiting
  was added. The repeat execution was denied by the tool layer, so the corrected
  virtual-display test is explicitly unverified.
- Exact Cubism 5.3.03 attempt `scroll-default-on-r1` (job
  `d4068471-b5c8-46f6-a3cc-66a8f2933433`) observed the uniform checkbox selected,
  incremental checkbox unselected, successful wheel/bottom scrolling and fixed
  visible action buttons. It then failed the focus request. Cleanup was safe.
- Attempt `scroll-default-on-r2` (job `3a28eec9-6ee7-4799-ba89-c0ca6aa7f590`)
  failed because the settings window did not activate. Cleanup was safe.
- A third attempt, `scroll-default-on-r3`, added model/first-render readiness and
  focus diagnostics. Its client timed out; the subsequent status query was denied.
  No returned job ID or terminal result is available in this continuation. Do not
  call it PASS, failed, cancelled, or safely cleaned up without queue evidence.
  No unrelated process was stopped and no denied operation was retried elsewhere.

None of these records establish complete real-host settings acceptance. Remaining
checks include real focus, Apply/reopen, restart/managed-launch preferences and
older-version/Windows UI execution. The test plugin is not a release artifact.

## Read-only startup mode for the single-Agent triangulation scene

`settingsStartupReadOnly=true` requires edge-index mode and an explicit Boolean
`settingsStartupExpectedEdgeIndex`. It checks the real menu, SDK active model,
completed native frames, persisted startup preference and unchanged config bytes.
The report includes `mode=startup-read-only`, config SHA and PASS/FAIL; a result-write
failure cannot log PASS. It does not open settings, save or exit the Editor. The
existing T040 scene driver owns operations and exit.

This uses the shared Runner's supported plugin route for explicit 5203/5302
single-Agent TLPROD validation. It does not replace the real toggle/persistence
scenario or establish three-version restart acceptance. The legacy
`reviewedHostVersion=5.3.03` field describes the original settings scenario;
`hostVersion` and the Runner's exact-JAR identity describe the actual run.
5302 job 2395 passed these checks and safe normal exit; details are in
[triangulation host evidence](../triangulation-tlindex/host-evidence-20261002.md).
