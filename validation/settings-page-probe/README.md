# Settings-page validation

This plugin is test-only. It operates the real Turboism menu and settings dialog,
changes only the isolated task's preferences, restores their effective entry values,
and exits the Editor. Never install it in a user's everyday plugin directory.
The shared exact-host queue, official launcher, isolated prefix/home and immutable
fixture checks described in the host-validation runbook remain mandatory.

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
