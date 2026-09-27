# SDK v11 inline-label and history-relation contract review

This revision registers the semantic-history and typed inline-icon surface as the
reviewed exact contract following the `worktree/semantic-history-timeline` merge.
It adds API and changes a small, explicit set of existing records. This is a
contract revision, not a release or a host-validation PASS.

## Immutable reference

- Source: `984c40b231cfa94f12bd6a59c97159db3a9342dd`, the last commit to change `sdk/` (the merge commit that lands the semantic-history timeline feature branch).
- Reconstructed from an isolated Git archive using `reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 / Java 17.
- Reference JAR: 916161 bytes, SHA-256 `74d26af1aa23109a19f8e3ce6dc5e9dbcd3af6daf5b7e203ba414dabce3c63e5`.
- Canonical dump: 6883 lines, SHA-256 `3b2d204cc562b688f04f80196c655ff8850763174457b88e71b6ba4f892dfd4c`.
- The anchor is pinned to the SDK-change commit and must stay reachable and unrebased. The release owner confirms it at release time.
- Baseline first captured outside the baseline directory for review, then added as a **new** v11 baseline. All v2–v10 baseline files and their anchors remain unchanged.
- v10 becomes a historical exact audit. v11 is the live exact gate; both remain required by `checkRelease`. No verification task generates or replaces a baseline.

## v10 → v11 delta and compatibility limits

The exact comparison against the v10 anchor reported **5 changed records and 306
added records** (6580 → 6881). No class, method or field that survives is renamed;
the five changes are enumerated below rather than summarized away.

### Changed records (v10 entries that no longer match byte-for-byte)

| Surface | Change |
| --- | --- |
| `HistoryChange$Operation` | `UNKNOWN` moves from enum-ordinal 3 to 4 (`SET` 0, `ADD` 1, `REMOVE` 2, `MOVE` 3, `UNKNOWN` 4). Compiled `ordinal()`/`switch`-table users must recompile; `name()`-based dispatch is unaffected. |
| `HistoryChange` constructor | The canonical constructor gains explicit `targetIndex`/`property`/`before`/`after` parameter-name metadata (descriptor unchanged); only the recorded parameter-name table changed. |
| `CubismOperationEvent` constructor | Parameter-name metadata recorded for `sequence`, `operation`, `origin`, `subjectId` (descriptor unchanged). |
| `PanelView$Toggle` canonical constructor | The `(String, String, boolean, boolean, String)` compact constructor became a plain public constructor delegating to the six-argument `(String, String, boolean, boolean, String, UiInlineLabel)` form; the descriptor and flags are unchanged. |
| `SettingsControl` sealed permit list | `SettingsControl$Note` is added as a fourth permitted subtype. |

### Added records (major surfaces)

| Surface | Reviewed effect |
| --- | --- |
| `UiInlineLabel`, `UiInlineLabel$IconRun`, `UiInlineLabel$TextRun` | Typed inline labels whose values may interleave icon runs with literal text; missing icons fall back to the run's embedded text. |
| `UiIconRef`, `UiIconAvailability`, `CubismIcon` (`sdk.ui.resource`) | Declarative native icon references and availability reporting. |
| `PanelView$Toggle` icon overloads | `toggle(id, UiInlineLabel, selected, actionId)` and `toggle(id, UiInlineLabel, selected, grayed, actionId)` construct icon-capable rows. |
| `PluginContext.uiResources()` | New default accessor; the default fails closed to `UiResourceService.unavailable()` until a verified provider is installed. |
| `PluginService` | 41 added capability/service lookup fields backing the icon and history services. |
| `HistoryRelationChange`, `HistoryRelationChange$Endpoint` | Typed relation records (`DEFORMER_PARENT`, part membership) with before/after endpoints for hierarchy edits. |
| `HistoryChange`, `HistoryEntryDetail` relation plumbing | Relation changes attach to history entries alongside property changes. |
| `AnimationScene`, `AnimationTrack`, `AnimationAttribute`, `AnimationKeyframe`, `AnimationBezierHandle`, `AnimationCurveType`, `AnimationTrackKind`, `AnimationAttributeKind` | Animation editing surface used by the timeline validation probes. |
| `Motion3Report`, `Motion3Issue` | Structured motion3 parse reports. |
| `SettingsControl$Note` | New note control variant (see sealed permit change above). |

### Compatibility notes

- The enum re-ordering of `HistoryChange$Operation.UNKNOWN` is a binary-compatibility
  break for plugins that persist `ordinal()` values or switch over the enum compiled
  against v10 or earlier. Recompile against v11; `name()` comparisons are unaffected.
- `SettingsControl` consumers that exhaust the sealed hierarchy must handle the
  `Note` subtype.
- Everything else is additive; v10 entry points are retained with identical
  descriptors, so plugins that do not touch the surfaces above are unaffected.

## Gates

```sh
./gradlew checkSdkV10ExactApiCompatibility checkSdkV11ExactApiCompatibility \
  checkSdkV8Linkage checkTextureAtlasSdkV7Linkage checkSdkApiBaselineTool :sdk:test
```

`checkSdkV10ExactApiCompatibility` becomes a historical self-audit: it verifies
the reconstructed v10 reference JAR against its own baseline, exactly like the
v2–v9 audits, and no longer compares the live SDK. A v10 JAR tested against the
v11 exact baseline must be rejected as a removal. Mutation selftests
must still reject identity tampering and unauthorized API drift. Exact API
passing does not replace behavioral tests, full release checks or real-host
evidence.
