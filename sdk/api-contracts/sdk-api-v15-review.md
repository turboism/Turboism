# SDK v15 permission-ids single source review

This revision freezes the SDK after `PermissionIds` became the single source of
truth for the permission identifier set on top of the v14 anchor. The second
review round found three drifted hard-coded copies of the permission set
(`PermissionIds`, `PermissionValidator`, `ScriptRegistry`); the runtime copies
now reference `PermissionIds.KNOWN_IDS`, which required exporting the set — and
the six missing constants — on the SDK surface. The plugin remains Preview;
incubating surfaces remain excluded from the exact gate under the existing
policy.

## Immutable reference

- Source: `0003e82bf52daf65939ba0a27f0aa1563d22dc71`, the branch tip carrying the permission-id consolidation; `sdk/` is unchanged since it.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Canonical dump: 7737 lines, SHA-256 `ce721d69d4db94586f8c0e38d3c6f92cd4ad8b4f5b54fcfb99aef7163b924681`.
- The baseline was captured outside `sdk/api-contracts/baselines`, then added as a new v15 baseline. All v2–v14 baseline files and their anchors remain unchanged.
- v14 becomes a historical exact audit. v15 compares the live JAR's complete non-incubating canonical API with the independently reconstructed reference. Both are required by `checkRelease`; no check generates or overwrites a baseline.
- Raw JAR metadata can vary with the build environment. The existing verifier binds the reference's canonical hash/line count and compares every API record, including signatures, annotations, enum ordinals and parameter metadata.

## v14 → v15 delta

The frozen v14 baseline is bound to
`34a68362b7d1b523094e6788f9346339c584f116` and has 7730 canonical lines.
v15 reports **7 added records and 0 removed/changed records**: 7730 → 7737
canonical lines. This is a pure addition — a non-breaking revision.

### New surfaces

| Surface | Effect |
| --- | --- |
| `PermissionIds.TURBOISM_CUBISM_MESH_READ`, `.TURBOISM_CUBISM_PARAMETER_READ`, `.TURBOISM_CUBISM_PROJECT_READ`, `.TURBOISM_UI_MENU`, `.TURBOISM_UI_TOOLBAR`, `.TURBOISM_UI_PALETTE` | Six constant fields completing the permission identifier set already enforced by the runtime validator (`turboism.cubism.mesh.read`, `turboism.cubism.parameter.read`, `turboism.cubism.project.read`, `turboism.ui.menu`, `turboism.ui.toolbar`, `turboism.ui.palette`). |
| `PermissionIds.KNOWN_IDS` | `java.util.Set<String>` exporting the complete identifier set so runtime validators reference one source instead of duplicating literal lists. |

### Removed surfaces

None. No existing class, method or field changes its name, erased JVM
descriptor, signature, annotations or parameter metadata; plugin authors do not
need to recompile.
