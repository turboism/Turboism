# SDK v16 merged-surface review

This revision freezes the SDK after merging the origin/main second-round review
hardening (PR #27/#28/#29/#31 — permission ids as the single source, incubating
`BoundedInput`, runtime/gradle/packaging gates) onto the locally developed
`@CubismEditor`-annotated SDK surface (art-mesh/render/moc/interpolation and the
version-availability contract). The v15 anchor remains the recorded freeze of the
remote permission-ids revision; with v16 registered it becomes a historical exact
audit while v16 is the live gate.

## Immutable reference

- Source: `576892d6e08cd6ff80a0a2cc344c5242acbb1853`, the merge tip carrying the
  merged `sdk/` sources; `sdk/` main sources are unchanged since it.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- The baseline was captured outside `sdk/api-contracts/baselines`, then added as
  a new v16 baseline. All v2–v15 baseline files and their anchors remain unchanged.
- v15 becomes a historical exact audit. v16 compares the live JAR's complete
  non-incubating canonical API with the independently reconstructed reference.
  Both are required by `checkRelease`; no check generates or overwrites a baseline.
- Raw JAR metadata can vary with the build environment. The existing verifier
  binds the reference's canonical hash/line count and compares every API record,
  including signatures, annotations, enum ordinals and parameter metadata.

## v15 → v16 delta

The frozen v15 baseline is bound to `0003e82bf52daf65939ba0a27f0aa1563d22dc71`
and has 7737 canonical lines. v16 reports **931 added records and 446
removed/changed records**: 7737 → 8222 canonical lines. No member disappears:
all 446 removed/changed records are the same members re-recorded because the
merged surface carries the runtime-visible `Ldev/turboism/sdk/CubismEditor;`
declaration on 488 existing member names, and the merged tree adds the remote
permission-id members on top. 116 member names are brand new; incubating
surfaces (for example `BoundedInput`) remain excluded from the exact gate under
the existing `@Incubating` policy.

### New surfaces

| Surface | Effect |
| --- | --- |
| `PermissionIds.TURBOISM_CUBISM_MESH_READ`, `.TURBOISM_CUBISM_PARAMETER_READ`, `.TURBOISM_CUBISM_PROJECT_READ`, `.TURBOISM_UI_MENU`, `.TURBOISM_UI_TOOLBAR`, `.TURBOISM_UI_PALETTE`, `.KNOWN_IDS` | The remote v15 permission-ids single source, merged onto this surface. |
| `@CubismEditor` runtime-visible declarations across 488 existing member names | The local version-availability contract (exact/range/exclude host-version declarations) is now part of the frozen API record of the annotated members. |
| 116 new member names under `dev.turboism.sdk.cubism.*` | The local art-mesh/render/moc/interpolation surface (for example keyform batch command ids, `bindModelImage`, `createMorphKeyform`, budget constants) enters the exact contract as non-incubating reviewed API. |

### Removed surfaces

None. No existing class, method or field loses its name, erased JVM descriptor,
signature, parameter metadata or incubating status; the 446 removed/changed
records are re-records of surviving members under the adopted annotations.
