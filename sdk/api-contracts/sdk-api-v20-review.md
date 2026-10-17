# SDK v20 parameter-lock native row and group locking review

This revision freezes the SDK after introducing the parameter-lock native row
and group locking capabilities, including:
- `PaletteRowAction` record with state, icon, tooltip, and click callback
- `PaletteRowAction.State` enum (UNLOCKED, LOCKED, MIXED)
- `PaletteEntry.overrideLeadingAction(PaletteRowAction)`
- `CubismIcon.LOCK` and `CubismIcon.LOCK_PLACEHOLDER`
- `PaletteToolbarRegistry.PaletteToolbarContribution` with additional constructor parameters
v19 becomes a historical exact audit while v20 is the live gate.

## Immutable reference

- Source: `6f22203fa879560ec6a04408f3a7a7fc1e3c9bb9`; `sdk/` main sources are
  frozen at this commit.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Canonical dump: 8355 lines, SHA-256 `01f5894b485aea946e64d3e3ebdf2d3723703bd343b42c63838dcfa448508ab4`.
- The baseline was captured with `scripts/test/sdk_api_baseline_cli.py capture`,
  then added as a new v20 baseline. All v2–v19 baseline files and their anchors remain unchanged.

## v19 → v20 delta

The frozen v19 baseline has 8327 canonical lines. v20 has 8355 canonical lines (+28 lines).

### Removed surfaces

None.
