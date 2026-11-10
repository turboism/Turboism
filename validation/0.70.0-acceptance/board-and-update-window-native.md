# 0.70.0 board and update-window observations

## Scope

The main agent used isolated Windows Cubism 5.3.04 sessions.
The runs preserved the official installation and used only a disposable model created through Cubism.
These observations do not establish final release readiness.

## Board resize and partial refresh

The old runtime placed the board through a 750 ms visibility timer.
The fixed runtime binds its geometry to the native viewport parent's layout pass.
It retains one installed surface and reconciles changed content without replacing unchanged header controls.

| Check | Old runtime | Fixed runtime |
| --- | --- | --- |
| Bounds match at the first observation after six native resizes | 0/6 | 6/6 |
| Header identity after a layout-button click | Replaced | Retained |
| Layout-button identity | Replaced | Retained |
| Overlay identity | Replaced | Retained |
| Details, six-column, and three-column cycle | Full replacement | Only card content changes |

The same native resize driver produced both samples.
It observed each resize after approximately 120 ms.
This measures first-query alignment, not a rendering-frame-rate improvement.
The regression test separately requires alignment before the native layout call returns to the event queue.

The fixed session preserved the overlay, header, and button identities across all three layout transitions.
The lower region changed layout and content while the header remained attached.

### Bound artifacts

- Baseline source: `9994a3aa7460485d452e8065e442a95703fddad9`.
- Board implementation: `6b62544fd`.
- Fixed native sample agent: `ca243ff6dd3ea19fc553ed19d5cc752eec48161f76c867f582bdd097de397084`.
- Resize driver: `5c0fee1e541bba99835438bf92d9f5486b8a13b03be4e032049fa86f66d2a829`.

The fixed sample used an unnumbered local dirty build before the implementation commit.
Its source binding hashes the workspace patch and every changed source file.

### Theme coverage

The focused regression verifies background and link-color changes after a Swing theme refresh.
Native theme switching remains pending.
The old theme manager deadlocked while waiting for storage on its own completion worker.
That independent defect has its own repair and native acceptance task.
Do not treat the current light-theme screenshot as proof of dark-theme or custom-theme behavior.

## Centered empty update list

The main agent reviewed and integrated child implementation `882d660b0` as `8f702ac43`.
The empty-list label now occupies a centered placeholder card inside the list region.
The populated table uses a separate card.

Native evidence covers three update-window sizes:

| Physical window size | Horizontal center error | Vertical center error |
| --- | ---: | ---: |
| 1500 x 1000 | 0 logical px | 0.5 logical px |
| 1200 x 800 | 0 logical px | 0.5 logical px |
| 1750 x 1100 | 0.5 logical px | 0.5 logical px |

The observer measured the label against its actual list-region parent.
It did not substitute the whole dialog or the bottom button strip for that region.
The visible text at measurement time was `没有可用更新。`.

A later localization change removed the terminal full stop from `updates.list.empty` in all six
catalogs, so the label now reads `没有可用更新` and its siblings keep the same label style.
The placeholder card, its parent region and its layout are unchanged by that edit.
A fresh native centering sample for the new wording remains outstanding.

The native session used source `8064d69df` with agent:
`a34653cda07fd9e3f64180c926dca89fab574d778ef41fc670fe231c8fbd813b`.
The build remained local and unnumbered, not a signed release candidate.
The session exited normally with code 0.
The Windows Job Object reported zero active processes, safe cleanup, and unchanged official host files.

## Focused verification

Four added board assertions failed on the old behavior.
All 29 renderer cases passed after the fix.
The related embedded-panel suite and admission preflight also passed.

The main agent independently reran the integrated renderer and update-window suites:

```sh
./gradlew --offline --console=plain :runtime:test \
  --tests '*FrameworkUpdatesWindowTest' \
  --tests '*SwingPanelViewRendererTest' \
  :runtime:spotlessJavaCheck \
  -PturboismVersion=0.70.0 -PturboismRelease=true
```

Both suites and formatting passed.
This command does not replace the final completed-tree gate.

## Retained evidence

The ignored `build/acceptance/070/` directory preserves:

- `board-red.log`: the four failing board regressions.
- `board-related-verification.log`: renderer and embedded-panel verification.
- `reviewed-ui-focused.log`: independent integrated UI verification.
- `host-5304-r4/resize-result.json`: the old geometry sample.
- `host-5304-r4/layout-identity-result.json`: old component replacement.
- `host-5304-r5/resize-result.json`: corrected geometry.
- `host-5304-r5/layout-identity-result.json`: retained header and button identities.
- `host-5304-r8/update-center-result.json`: actual list-region centering measurements.

Each native session retains screenshots, component snapshots, source binding, and kernel cleanup evidence.
