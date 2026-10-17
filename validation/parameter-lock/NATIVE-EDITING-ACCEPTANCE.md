# Native parameter editing restriction

The lock now disables the actual Swing components of each native parameter value editor and drag surface, rather than only rejecting model writes. A transparent input shield covers only disabled editors, leaving row names and leading lock actions clickable. The custom native drag rendering is desaturated from its actual paint output; native geometry and icons remain intact. Unlocking, removing the action, scope changes, disconnect and plugin shutdown restore previous enabled states and remove the shield. Originally disabled controls stay disabled.

For combined two-axis rows, the drag surface is shared: either locked axis blocks the shared drag operation, while the unlocked axis retains its independent numeric input. Group actions still apply through each descendant parameter's own state.

## Static and offline evidence

Five new exact selectors read the single/double row's `getSliderFloat`, double row's `getSliderFloatSec`, and each row's private `paintComp`. No native field is written. Existing verified `CWidget.getJComponent` resolves each Swing component. All 5 selectors passed classfile verification on all 20 reviewed artifacts (100 checks); production Java verification passed all 20 complete editor-model records (`/tmp/parameter-editing-gates.log`). Records, mapping packs, source template and version propagation rules were updated together.

`ParameterEditingTrackerTest` verifies disabled numeric/drag components, grayscale paint, pointer hit interception outside the lock label, independent numeric fields on combined rows, preservation of pre-disabled native state, idempotence and restoration. The full plugin tests include propagation of the explicit SDK `editingDisabled` flag. Runtime appearance and HostSession regression checks passed.

## Host evidence

The 5.3.02 matrix job `eb259a97-21bb-41c4-8cc6-4e93fa905496` passed native disabled/shielded editing, restored enabled/unshielded editing, and all 2/2 group-descendant lock/unlock checks. Final grayscale-background/DPI polish was then validated by fresh-process restore job `691299c6-1443-4336-8adb-0d72af8b7a32`: disabled editors were restored before the first UI click, then row unlock restored write access. Both finished with normal exit, verified host identity, unchanged fixture and safe cleanup.

The probe now requires actual native input and drag components to become disabled and marked, and the native pointer hit test to resolve to the input shield while locked. Unlock must restore enabled components and remove the shield. Persist-read additionally checks disabled editors before the first UI click. Physical Robot drag/keystroke coverage is not claimed by these hit-test assertions.


Final runtime SHA-256: `f2af27767cf5d98823cdc7685e6fde87084653df36837a5b0ad81911f0c8a9a4`; plugin: `8b9ad3e6ed5a73d83dc7ce0a458c8424cc680256445beae6ca2e447f21adc161`; probe: `83ae9b330beab4ef20448ce95c4526829fb166edc67d13e6d3f4109380ed5ee4`. Each job's actual staged hashes and result checks are preserved in [native-editing-host-results.json](native-editing-host-results.json).

The first attempt `79ae4117-803b-4f97-8464-299a103b7bbb` failed the disabled-editor assertion because the separately built development plugin JAR had not been refreshed (old `f871b655…` plugin). The stale artifact was replaced and the failure was not counted as acceptance. Cleanup was safe.

Final offline results: SDK 440 tests (0 failures/errors, 1 skip), plugin 42 tests (0 failures/errors), latest focused appearance suite 51 tests (0 failures/errors); the preceding broader appearance + HostSession run also passed. Module/plugin boundaries, permission audit for 30 plugins, SDK annotation coverage, formatting, localized README checks and compatibility generation check passed.

The final-artifact 5.2.03 matrix job `ce994cee-b71f-4ed5-ac21-d181f8fab986` also passed disabled/shielded controls, unlocked/enabled controls, and all group checks (2/2 descendants). Normal exit, verified host identity, unchanged fixture and safe cleanup were confirmed.

Manual session `af258ea8-42b7-47f7-8a6b-fa13003a0f7d` (`queue-304db44fc4ab444497ebf0de6a2d2c8f`) was opened on the physical desktop with the production plugin only; it was subsequently closed through the managed queue for the alignment update. Screenshot `/tmp/parameter-lock-disabled-ui-window.png` confirms the prelocked first parameter has a grayscale drag surface and a disabled gray numeric field, while unlocked rows retain colored controls and ring placeholders. That screenshot records the earlier layout; the replacement manual session is recorded below.

## Separate leading lock column

Native Parts/Deformer uses a dedicated checkbox renderer (`com.live2d.ui.treeTable.h`) and fixed table columns, not an icon painted over the name renderer. On 5.3.02, `CTreeTable` sets column 1 minimum/preferred/maximum widths to 20. Bytecode inspection is recorded in `/tmp/native-parts-lock-column-bytecode.txt` and `/tmp/native-parts-treetable-bytecode.txt`.

Parameter actions now reserve a separate leading border slot (16px native action plus the existing spacing) and keep the original native text alignment. The name layout excludes the slot, so long/clipped names cannot overlap the lock. Native folder icons remain inside the name area. The existing action-slot preferred size is preserved; removing the action restores the native border, icon, tooltip and gap. This avoids native internal writes and additional host selectors.

Six focused regressions pass, including exact name bounds/preferred sizes against the previous compound action-slot layout for LEFT/CENTER/RIGHT native alignment, long-name clipping in an 80px column, actual border painting, click ownership, rebinding and restoration (`/tmp/parameter-lock-column-layout-test.log`). Runtime test and preview build passed (`/tmp/parameter-lock-column-build.log`).

Superseded attempts: LEFT alignment (`1c6f46a1-de86-4f53-93aa-c73a05001c05`) moved names; zero-width unreserved painting (`30dac026-0dc7-4562-b700-b2c325203ec1`) passed behavior checks but overlapped names in the manual screenshot. Neither is visual acceptance. Their manual sessions were closed through the managed queue before replacement.


Locked names now use the active theme's `Label.disabledForeground` (gray fallback) without disabling the lock label itself. Unlock/removal restores the original foreground; regression checks preserve native name alignment and lock clickability. The host probe checks both locked-name theme color and unlocked-name restoration alongside disabled editors. Build and six tracker tests passed (`/tmp/parameter-lock-gray-name-build.log`).


The separate-column matrix `3dc8142a-ca17-4b05-acab-ab7724c81710` (`queue-7a9e304e23204829a0cf90f5453c889e`) passed all row, disabled-editor and group checks, with normal exit, verified identity, unchanged fixture and safe cleanup.

The first gray-name matrix `dced5914-f5cd-4340-abec-4294dbe7e09c` failed the unlocked native-controller write and subsequent lock click while an external window-maximization helper was also active. It is not counted as acceptance; a fresh run without concurrent resizing is required. The manual screenshot `/tmp/parameter-lock-gray-name-window.png` from `77aabe8f-232b-45ff-8d7f-a45621bea5cc` (`queue-71a83ab53cec4e75969d85e1b91283a7`) confirms gray locked text, a distinct left lock slot, normal unlocked names and no overlap at normal panel width. That manual job was closed through the managed queue for the follow-up run.


Final gray-name matrix `2162a682-c9e2-4bef-8ad8-f9c5ef86f4db` (`queue-cf917f44613049f1a99092c3a1d75c6a`) passed every row/controller/editing/group check without concurrent resizing. Both `editing.true.nameColorMatches=true` and `editing.false.nameColorMatches=true` prove locked theme color and unlocked original-color restoration. Normal exit, verified identity, unchanged fixture and safe cleanup were confirmed. Exact staged artifacts and results are preserved in [leading-column-host-results.json](leading-column-host-results.json).


Final manual job `735956c5-59b9-469c-93c3-3f158f884e7f` (`queue-abb2c627d657429584e7cc39a4d380f1`, window 3064) is left open for visual inspection. Screenshot `/tmp/parameter-lock-gray-name-final-window.png` confirms gray locked names, normal unlocked names, and the lock column separated from text. Manual interaction may change in-memory values/lock states; no completed terminal outcome or unchanged-copy claim is made for this open session.


## Final localization and multiversion gate

All plugin UI keys pass the official completeness gate for English, Simplified Chinese, Traditional Chinese, Japanese and Korean; Traditional Chinese group terms were aligned. All three READMEs document the independent lock column and gray/restored name color. The final SDK/plugin/runtime tests and all 20 complete editor-model records passed (`/tmp/parameter-lock-final-gates.log`). Record digest/count pins in the repository integration test were updated for the eight reviewed row/layout/editor selectors added to each record.

Final 5.2.03 matrix `a10c43d9-9981-405b-a4d9-b645f0b481b4`, persist-write `c647f7d9-1a97-41e0-bf81-c0aa2f5773a3`, and fresh persist-read `ccfcad69-768a-4d3c-b33b-1a4510d2076e` all passed, with normal exit, verified identity, unchanged fixture and safe cleanup. Fresh-process restoration includes gray locked names and disabled editors before the first UI click. The preloaded JSON came from the completed write job and was staged by the runner before launch. Exact artifacts/results are in `leading-column-host-results.json`. These real-host results cover representative 5.2.03/5.3.02; the other reviewed versions have static mapping/icon evidence, not a claim of full real-host coverage.

The repository compatibility-record integration test passed after updating digest and selector-count pins (`/tmp/parameter-lock-final-integration.log`). Generated compatibility artifacts, localized README contracts and Windows installer config/payload checks also passed.
