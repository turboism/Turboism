# SDK v18 texture-atlas-append review

This revision freezes the SDK after introducing the native append-only texture
atlas capability (`ModelTextures.appendTextureAtlasPages`, `supportsAppendTextureAtlasPages`),
along with its input parameter models (`AtlasPageAppendPlan` and `ModelImagePlacement`).
v17 becomes a historical exact audit while v18 is the live gate.

## Immutable reference

- Source: `ff08fc44da131f8b6e3043f5c86fb133b89bccb3`; `sdk/` main sources are
  frozen at this commit.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Canonical dump: 8260 lines, SHA-256 `a39b4fee26681b60c5871bbf3f96ba4ffd659e4f761a72b359e618a1c7d59f56`.
- The baseline was captured with `scripts/test/sdk_api_baseline_cli.py capture`,
  then added as a new v18 baseline. All v2–v17 baseline files and their anchors remain unchanged.

## v17 → v18 delta

The frozen v17 baseline has 8222 canonical lines. v18 has 8260 canonical lines (+38 lines):

| Surface | Effect |
| --- | --- |
| `cubism.model.AtlasPageAppendPlan` | Added record specifying appended page dimensions, count, names, and sprite placements. |
| `cubism.model.ModelImagePlacement` | Added record specifying per-image placement offsets and dimensions. |
| `cubism.model.ModelTextures#appendTextureAtlasPages` | Added method to append texture atlas pages under an undoable transaction. |
| `cubism.model.ModelTextures#supportsAppendTextureAtlasPages` | Added capability probe method for append support. |

### Removed surfaces

None.
