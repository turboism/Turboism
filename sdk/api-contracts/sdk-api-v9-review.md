# SDK v9 main/Atlas integration contract review

The user explicitly approved Atlas queue integration and SDK contract coordination after the merged main SDK failed the frozen v8 exact gate. This is a contract revision, not a release or a host-validation PASS. No SDK production source is changed by this revision.

## Immutable reference

- Source: `7f8b005e953c04254e95c91925e8c93128121288`, merging main `951b6b97f855830684a39f8365064da05e6e5ffd` with the Atlas branch.
- Reconstructed from an isolated Git archive using `reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 / Java 17.
- Reference JAR: 868406 bytes, SHA-256 `d6b2cb5d39e0022371e50042b568707d4aec706f55edac0b81f0c291ed8708ae`.
- Canonical dump: 6540 lines, SHA-256 `7cd983307bd53a558faa71f0ffa54118d905b7c4c34f49a04a1965e723330e9a`.
- Baseline first captured outside the baseline directory for review, then added as a **new** v9 baseline. All v2–v8 baseline files and their anchors remain unchanged.
- v8 becomes a historical exact audit. v9 is the live exact gate; both remain required by `checkRelease`. No verification task generates or replaces a baseline.

## v8 → v9 delta and compatibility limits

The exact comparison reported 2 changed records and 127 additions (net +125). SDK source changes are confined to history DTOs and RuntimeSettings; texture-atlas SDK source is unchanged.

| Surface | Reviewed effect |
| --- | --- |
| `HistoryEntry` | Adds `detail`, a seven-argument canonical constructor and accessor. Existing three-, four- and six-argument constructors remain. The six-argument constructor's parameter metadata changes when becoming an explicit overload. |
| `HistoryEntry.detailLevel()` | Now projects semantic detail rather than directly reading the legacy action. Compatibility overloads derive detail from the action or label-only fallback. |
| New history DTOs | `HistoryChange`, `HistoryEditContext`, `HistoryEntryDetail`, `HistoryGroup`, `HistoryOrigin`, `HistoryParameterCoordinate`, `HistoryTarget`, and their nested enums expose bounded immutable semantic projections. They do not grant host write authority. |
| History validation | Full detail requires sufficient trusted context/values; partial/label-only requires degradation information. Targets, coordinates, strings and grouping are validated and bounded. Unattributed origin cannot claim a producer. |
| History compatibility limits | Old constructors now derive validated semantic detail. Long/control-character legacy labels or action values can be rejected by the new detail constraints. This is not a guarantee that every formerly accepted value retains identical behavior. |
| `RuntimeSettings` | Adds `useTextIcon`, accessor and nine-argument canonical constructor. Old overloads remain and default text icons to false. The eight-argument overload's parameter metadata changes. |
| Record consumers | Extended component lists change record reflection, generated equality/hash/toString and serialization shape. Binary constructor retention is not exact reflection or serialization compatibility. |
| Atlas | v8 current-page absolute scale/rotation and retained v7 complete-atlas constructors are unchanged. No cross-page scope expansion. |

## Gates

```sh
./gradlew checkSdkV8ExactApiCompatibility checkSdkV9ExactApiCompatibility \
  checkSdkV8Linkage checkTextureAtlasSdkV7Linkage checkSdkApiBaselineTool :sdk:test
```

`checkSdkV8Linkage` compiles history/settings/current-page client bytecode against the frozen v8 JAR and runs it with only the current SDK. It checks retained entry points and representative values, not all history semantics. The existing v7 Atlas linkage gate remains intact.

A v8 JAR tested against the v9 exact baseline must be rejected. Mutation selftests must still reject identity tampering and unauthorized API drift. Exact API passing does not replace behavioral tests, full release checks, queue admission or real-host evidence.

Executed PASS: v8 historical exact (6413 records), v9 live exact (6538 records), both linkage gates, verifier mutation selftests, and 260 SDK tests (zero failures/errors/skips). The v8-input/v9-baseline negative comparison was rejected. Evidence: `build/atlas-main-integration/v9-gates.log` and `v9-negative.log`. Full `checkRelease` and real-host validation have not been run for this integration.
