# SDK v8 release review

Prepared on 2026-09-11 for Turboism 0.43.9: re-anchor the live exact release gate to the current
`dev.turboism.sdk` publishing surface. This review is the anchor change; the release owner confirms
it at release time exactly as the v7 review was confirmed.

## Why a new anchor was due

v7 was recorded as "the live exact release gate". The live gate had already been violated before
this change: the captured-semantic-timeline API landed on the release line in
`a2d7559f4 feat(history): add captured semantic timeline and stable navigation` (2026-09-07), one
day after the v7 anchor commit (`46ea5cb30`, 2026-09-06), and 232 canonical records had accumulated
against the v7 baseline since then. This change adds four more records, so the live gate is
re-anchored rather than left silently red.

The four records this change is responsible for are the appended `CubismOperation` constants
`SET_HIERARCHY_PARENT`, `DETACH_HIERARCHY_PARENT`, `MOVE_DRAWABLE` and `SET_DRAWABLE_COLOR` that
give native editor edits a typed identity (spec 012 HR-05G). They are appended after
`OPEN_CONTEXT_MENU`, so **no existing constant's ordinal moves**; the v7 review's standing rule that
consumers must not persist enum ordinals still applies.

## Immutable identity

- Source: `ab89a3c431228f6d2367f23b93e17f5f3b59f335`.
- Reference reconstruction: Gradle 8.10.2, Temurin 17.0.20.1+1,
  `scripts/test/reconstruct_sdk_gradle_jar.py`, worktree ID `history-reference`.
- JAR: 883969 bytes; SHA-256 `943a9f6b74810c692573224f69f19e06397671ac553f881e41c7c128ba1679b7`.
- Canonical dump (`dev.turboism.sdk`): 6633 lines including headers; SHA-256
  `8d523bf3bcc939465fa506e7fa4b90d2d4fd481d38b7c601e85e0aa845f83ff1`.
- The live `:sdk:jar` built from the same commit is byte-identical to this reference JAR.

## Reviewed v7 → v8 difference

Against the v7 canonical records: **236 added**, **3 changed**.

| Changed v7 record | Disposition |
| --- | --- |
| `HistoryEntry` canonical constructor | Parameter list changed; identity fields added. Consumers must not persist enum ordinals and must tolerate additional identity components. |
| `RuntimeSettings` constructor | Parameter list changed with the added settings; use the accessor pair. |
| `PanelView.Toggle` constructor | Parameter list changed with the added inline-label/resource identity. |

Additions by record kind and area:

| Area | class | method | field | record-component |
| --- | --- | --- | --- | --- |
| `cubism.history` (captured semantic timeline DTOs) | 14 | 92 | 16 | 35 |
| `ui` + `ui.resource` (inline label, icon references, resource service) | 8 | 45 | 11 | 5 |
| `runtime` (`RuntimeSettings` accessors) | 0 | 3 | 0 | 1 |
| `plugin` (`PluginContext` accessor) | 0 | 1 | 0 | 0 |
| `cubism.event` (the four added `CubismOperation` constants) | 0 | 0 | 4 | 0 |

The `cubism.history`, `ui`, `runtime` and `plugin` additions are the already-released captured
timeline and UI surface that the violated v7 gate never captured. No addition removes or renames
existing API, and no addition changes an existing constant ordinal.

## Enforcement

`checkSdkV8ExactApiCompatibility` verifies the live SDK against the pinned v8 reference and this
baseline using **verify-exact** (`baseline=6631 current=6631 additions=0`); it does not capture or
overwrite a baseline during verification. v7 is demoted to a historical audit
(`checkSdkV7ExactApiCompatibility` now audits the pinned v7 artifact against its own binding, as v2–v6
already do), and `checkRelease` runs the historical audits followed by the live v8 gate.

Reproduce by reconstructing the pinned commit with
`./gradlew prepareSdkV8ExactReference` and dumping it with
`sdk_api_baseline_cli.py dump --package-prefix dev.turboism.sdk`.

## Anchor constraint

The anchor is pinned to the commit that introduced the four operation identities. That commit must
stay reachable and must not be rebased away; if the release line re-creates the SDK change as a
different commit, re-pin `sdkV8ExactCommit`, rebuild the reference with
`prepareSdkV8ExactReference` and capture the baseline again with the same procedure. Because the
binding is the SDK tree content, any later SDK API change needs another anchor.

This review promotes no Cubism host capability and claims no new real-host evidence.
