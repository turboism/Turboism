# SDK v19 model-import-3d and humanoid-viewer extensions review

This revision freezes the SDK after introducing the model-import-3d native character
authoring and humanoid-viewer capabilities, including:
- `WarpMorphSnapshot` and `WarpDeformer.morphSnapshot()`
- `Drawables.SourceGeometryRequest` and batch mesh source authoring APIs
- `Parts.createPatchParts` and batch deformer authoring APIs
- Extended `CubismOperation` enum constants
- `UserFileAccessService.readBytesBounded` for user file access up to 128 MiB
v18 becomes a historical exact audit while v19 is the live gate.

## Immutable reference

- Source: `4d4a7d0fa65629278fe1f1b594e214e93b82ec31`; `sdk/` main sources are
  frozen at this commit.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Canonical dump: 8327 lines, SHA-256 `6ef69648ff3e5efdab6b8c6549e3248f60bff2e273c57ecd04617fd397a64b87`.
- The baseline was captured with `scripts/test/sdk_api_baseline_cli.py capture`,
  then added as a new v19 baseline. All v2–v18 baseline files and their anchors remain unchanged.

## v18 → v19 delta

The frozen v18 baseline has 8260 canonical lines. v19 has 8327 canonical lines (+67 lines).

### Removed surfaces

None.
