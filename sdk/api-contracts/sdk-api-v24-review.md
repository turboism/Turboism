# SDK v24 Cubism version annotations and selection-table dialog review

This baseline records the SDK extensions and `@CubismEditor` version
annotations authorized for 0.68.0. All v2–v23 anchors and baseline files remain
unchanged; v24 becomes the live release gate.

## Immutable reference

- Source: `f6da7f8a60a2e9e3b6aabcbc1f58f87ffb44264c`.
- Reconstructed from an isolated Git archive using the historical SDK Gradle
  builder, Gradle 8.10.2 and Java 17.
- Canonical dump: 8559 lines, SHA-256
  `57acc53c3b38add2ae8f0ffaba4d24ac57fe600fb871b26a1b220a6d1741dc02`.
- Captured to an external review path before adding the reviewed baseline.

## v23 → v24 delta and compatibility

The canonical dump grows from 8419 to 8559 lines. No existing public type or
callable JVM descriptor is removed:

- Added `@CubismEditor` version annotations across public hook, mesh, model,
  file-chooser and PSD interfaces/records (`ModelFileHooks`, `ParameterHooks`,
  `ArtMeshMirrorService`, `ArtMeshKeyformValues`, `ModelInstance`,
  `OrdinaryKeyformWriteSupport`, `ParameterGroupChild`, `PsdEditFile`, and
  related methods) to close the annotation coverage baseline.
- Added `SelectionTableDialogRequest`, `SelectionTableDialogRequest.Row` and
  `SelectionTableDialogResultListener` under `dev.turboism.sdk.ui` for typed
  table-selection dialogs.
- Added `ArtMeshMirrorService.Unavailable` and
  `OrdinaryKeyformWriteSupport.Check.ABSENT` sentinel values.

## Ongoing checks

`checkSdkApiCurrent` checks the reviewed current snapshot. `checkRelease`
reconstructs this source and compares its canonical API exactly with the live
SDK. Historical anchors continue to audit their own immutable references.
