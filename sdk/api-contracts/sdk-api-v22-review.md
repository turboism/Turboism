# SDK v22 file-path settings and texture-atlas review

This baseline records the API changes accepted by the maintainer for 0.67.2.
It introduces no additional SDK changes. v21 becomes a historical exact audit;
v22 is the live gate. All v2–v21 anchors and baseline files remain unchanged.

## Immutable reference

- Source: `428f15ff8078659771ae90e84226e053105e3017`.
- Reconstructed from an isolated Git archive using
  `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17.
- Canonical dump: 8406 lines (8404 API records), SHA-256
  `867fe4c57b76eb23d8559f0e12d67b9a346037a395645764655b7109132c8b7c`.
- Captured to an external review path with `sdk_api_baseline_cli.py capture`,
  then copied into `baselines/sdk-api-v22-exact.json`.
- Exact comparison against the live 0.67.2 SDK passed with no differences.

## v21 → v22 delta and compatibility

The v21 dump contains 8364 lines. v22 contains 8406: 42 new API records
and two replaced metadata records (44 added lines, two removed lines).
No existing public type or callable JVM descriptor is removed.

- `TextureAtlasPageSize` and the per-page `TextureAtlasLayoutPlan` constructor,
  accessors and record component add 13 API records. Existing constructor
  descriptors remain. The layout record grows from six to seven components,
  affecting record reflection, equality, hashing and string representation.
  The retained six-argument constructor no longer carries parameter-name
  metadata; reflection consumers must adapt. Introduced by `365383c18`.
- `TextureAtlasLayoutPlanner.supportsCompleteAtlas()` and
  `TextureAtlasPackingException` with its `Reason` enum add 11 records.
  The planner method has a default implementation, retaining existing
  implementations. The exception is deliberately public and extensible:
  official packing plugins consume and subclass it. Introduced by `bb2d83ffb`.
- `PsdEditFile.openInApplication(String)` adds one default method. Blank paths
  delegate to the default application; unsupported custom applications fail
  safely. Introduced by `33deee692`.
- `SettingsControl.FilePath` adds 17 records and changes the sealed interface's
  permitted subtypes. Exhaustive switches must handle the new variant when
  recompiling; this source compatibility effect is documented in the release
  changelogs. Introduced by `33deee692`.

The maintainer accepted these additive and source/reflection compatibility
changes before release. They are not hidden retroactively with `@Incubating`.
The four official plugins using these additions declare an API floor of
`[0.67.2,1.0.0)`: external-psd-edit, atlas-folder-layout,
atlas-maxrects-bssf and atlas-bitmask-nesting.

## Ongoing checks

`checkSdkApiCurrent` compares the live API to the reviewed `current.txt` on
completed commits. `checkRelease` additionally reconstructs the immutable v22
reference and checks the live API exactly against this baseline. Older
baselines continue to audit their historical references independently.
