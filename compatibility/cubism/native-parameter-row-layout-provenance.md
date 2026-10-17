# Native parameter folder layout capture

Existing parameter rows may predate runtime hook installation. Constructor retransformation does not replay existing instances, so appearance refresh also captures the current native row hierarchy on the EDT.

The exact Editor-model records and matching mapping packs for all 20 reviewed versions contain these additive selectors. Each was checked against that record's pinned JAR identity with both the Python structural verifier and production Java `StaticVerificationCli`.

| Alias suffix | Native contract |
|---|---|
| `parameter-operation.row-layout` | Exact per-version parameter operation owner, `b(): RowLayoutVBox` |
| `parameter-row-container.children` | `com/live2d/ui/container/CContainer.getChildren(): java.util.List` |
| `parameter-group-row.layout` | `com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget.getRowLayout(): RowLayoutVBox` |

The existing `parameter-operation.rows` / `f(): CArrayList` selector remains unchanged and supplies parameter widgets. Layout traversal adds native folder widgets and recursively follows their row layouts. Only the verified exact folder owner is followed; row and layout identity sets prevent duplicates and cycles. The bridge still validates exact row owner, host loader, source ID and label selectors before binding.

For Cubism 5.3.02 (`Live2D_Cubism.jar` SHA-256 `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`), bytecode of `com.live2d.cubism.view.palette.parameter.s.b(RowLayoutVBox): int` independently demonstrates the same traversal: call `RowLayoutVBox.getChildren()`, test for `ParameterGroupRowWidget`, call `getRowLayout()`, and recurse. `CContainer.getChildren()` resolves the corresponding native widgets from Swing child components using the host's own `com.live2d.ui.k.a(Component)` mapping.

The superficially suitable `g(): List<I>` accessor is explicitly **not** used: it reads the selected-row field `i`, which is maintained by `s.a(I, boolean, boolean)`. Signature verification alone does not establish collection semantics. Its briefly proposed `palette-rows` alias was removed before acceptance.

Validation logs: `/tmp/parameter-row-layout-static.log` (20 versions, 3 new selectors each), `/tmp/parameter-row-layout-build.log` (production Java verification of all 20 complete Editor-model records, control-appearance and HostSession regressions, preview build). Static coverage does not claim live-host coverage of all 20 versions; actual host runs are recorded separately in `validation/parameter-lock/ROW-UI-ACCEPTANCE.md`.
