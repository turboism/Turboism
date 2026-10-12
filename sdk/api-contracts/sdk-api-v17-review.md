# SDK v17 widened-availability review

This revision freezes the SDK after the Cubism multi-version adaptation campaign
widened the `@CubismEditor` availability declarations of already-reviewed SDK
types and members. No member is added, removed or changes its JVM shape; only the
runtime-visible availability annotation of 20 records changes, so plugins built
against v16 link unchanged against v17. v16 becomes a historical exact audit
while v17 is the live gate.

## Immutable reference

- Source: `2b049ce16863ac93584eddcbfbf7f7cfce140951`; `sdk/` main sources are
  unchanged since it.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Canonical dump: 8222 lines, SHA-256 `4e4730ffd1ac0c9d40cf4352572267e50616af84e88e65e065ac713ddc4a8121`.
- The baseline was captured outside `sdk/api-contracts/baselines` with
  `scripts/test/sdk_api_baseline_cli.py capture`, then added as a new v17
  baseline. All v2–v16 baseline files and their anchors remain unchanged.
- Raw JAR metadata can vary with the build environment. The existing verifier
  binds the reference's canonical hash/line count and compares every API record,
  including signatures, annotations, enum ordinals and parameter metadata.

## v16 → v17 delta

The frozen v16 baseline is bound to `576892d6e08cd6ff80a0a2cc344c5242acbb1853`
and has 8222 canonical lines. v17 has the same 8222 canonical lines; 20 records
are re-recorded with a wider `@CubismEditor` declaration (for example
`from = "5.2.00"` becomes `from = "5.0.00"`), backed by VERIFIED_STATIC
verification records for every newly covered Editor version and by
`compatibility/cubism/version-boundaries.json` for the versions that remain
excluded.

| Surface | Effect |
| --- | --- |
| `cubism.mesh.MeshEditParticipation`, `MeshEditService`, `MeshEditUiService`, `MeshMirrorAxisService`, `MeshMirrorCounterparts`, `MeshMirrorMoveParticipation`, `MeshMirrorToolEligibility` | Mesh-edit mirror/session services are declared for every reviewed version (5.0.00–5.3.04). |
| `cubism.model.ArtMeshTextureInput`, `AuthorizedImageContent`, `CubismRenderedPixels`, `ModelImageBinding`, `ParameterInterpolation`, and the matching `CubismModel` / `ModelTextures` methods | Model texture binding, rendered-pixel and interpolation reads follow their widened capability admission. |
| `cubism.transaction.AuthoringTransactionService`, `CubismFacade` accessor | Authoring transactions are declared for every reviewed version. |
| `cubism.recentfile.RecentFileService`, `cubism.recentpreview.RecentPreviewContributionService`, `cubism.screenshot.ScreenshotCaptureService` | Project/recent-file surfaces are declared for every reviewed version. |
| `cubism.warp.WarpAltMirrorParticipation` | Alt-symmetry warp mirroring is declared for every reviewed version. |

### Removed surfaces

None. No class, method or field loses its name, erased JVM descriptor,
signature, parameter metadata or incubating status.
