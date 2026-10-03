# SDK v14 parameter read-plane review

This revision freezes the SDK after the parameter query read plane was retired
on top of the v13 anchor. `ParameterQueryService`, `ParameterSummary` and
`ParameterBounds` duplicated the unified object API that already ships on the
exact surface, so the snapshot listing is deleted and its two remaining
projections — palette visibility and editability — move onto
`ParameterAppearance`. The deletion also removes `PluginService.PARAMETER_QUERY`
and, unlike the v13 enum-constant renumber, shifts real JVM `ordinal()` values.
The plugin remains Preview; incubating surfaces remain excluded from the exact
gate under the existing policy.

## Immutable reference

- Source: `34a68362b7d1b523094e6788f9346339c584f116`, the branch tip carrying the parameter read-plane removal; `sdk/` is unchanged since that removal merged in `4b3c348d6`.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Reference JAR: 1142942 bytes, SHA-256 `16f6c103ad7ea689e8c1056b4c7455036f1de5bb8d389e127ff01fba1be779fe`.
- Canonical dump: 7730 lines, SHA-256 `ccc7ed3b61e7a93f4c318f91f0170279b98431059c0b1536d1674cac22f732e8`.
- The baseline was captured outside `sdk/api-contracts/baselines`, then added as a new v14 baseline. All v2–v13 baseline files and their anchors remain unchanged.
- v13 becomes a historical exact audit. v14 compares the live JAR's complete non-incubating canonical API with the independently reconstructed reference. Both are required by `checkRelease`; no check generates or overwrites a baseline.
- Raw JAR metadata can vary with the build environment. The existing verifier binds the reference's canonical hash/line count and compares every API record, including signatures, annotations, enum ordinals and parameter metadata.

## v13 → v14 delta

The frozen v13 baseline is bound to
`77b9d6cff4aa7fa6afd6cefbea0f6a8bdffde501` and has 7775 canonical lines.
Comparing its reconstructed JAR with v14 reports **85 removed/changed records
and 40 added/changed records**: 7773 → 7728 API records, plus two header lines
in each dump. The delta comprises 38 changed identities (the renumbered
`PluginService` constants, each consuming one removed and one added record),
47 true removals and 2 new records.

This is another breaking revision, allowed before the first stable (1.x)
baseline exists. Every removal and the ordinal shift are deliberate and carry
the migration below; plugin authors must recompile against v14.

### Existing records

| Surface | Exact delta and migration |
| --- | --- |
| `PluginService` | 38 enum-constant field records change `enum-ordinal` after `PARAMETER_QUERY` is deleted. This time the change is not a dump-recording artifact: the constant is gone from the declaration, so real JVM `ordinal()` values shift −1 for everything declared after it — the 38 non-incubating constants (`SELECTION_QUERY` 6→5 … `EXPORT_SETTINGS` 43→42 in dump ordinals) plus the incubating `MESH_TOOLS`, `MODELING_TOOLS` and `MCP_CONNECTIONS`, which are declared after `PARAMETER_QUERY` but never enter the dump. Name-based lookup (`valueOf`, `services().find`/`require`, enum switches) is unaffected; **any plugin or data file that persisted `PluginService.ordinal()` values must rewrite them**: ordinals 0–5 keep their meaning, ordinal 6 was `PARAMETER_QUERY` and must be dropped or remapped, ordinals 7–47 become 6–46. |

No other surviving class, method or field changes its name, erased JVM
descriptor, signature, annotations or parameter metadata.

### New surfaces

| Surface | Effect |
| --- | --- |
| `ParameterAppearance.visible()`, `ParameterAppearance.editable()` | Two default methods returning `Optional<Boolean>` project the parameter palette's `visible`/`editable` flags — the only `ParameterSummary` state the object API did not already carry. Editor-attached models report the snapshot values; owned/Core models and the `unavailable()` projection report empty. Read them through `parameter.ui()`; see the migration below. |

### Removed surfaces

| Surface | Migration |
| --- | --- |
| `ParameterQueryService` and its `Unavailable` sentinel (`findById`, `listAll`, `exists`, `isAvailable`, `unavailable`, `INSTANCE`) | The snapshot listing is retired in favor of the unified object API: `context.parameterQuery().listAll()` → `context.cubism().model().active().parameters().all()`; `findById(id)` → `parameters().findById(id)`; `exists(id)` → `parameters().findById(id).isPresent()`. The service was gated by `turboism.cubism.parameter.read`; the object API requires `turboism.cubism.model.read`, so manifests must declare the model permission instead. `parameterQuery().isAvailable()` probes move to `cubism().model().isAvailable()`. |
| `ParameterSummary` (`id`/`name`/`currentValue`/`bounds`/`visible`/`editable` components, `minValue`/`maxValue`/`defaultValue` helpers, record constructor) | `ParameterSummary.currentValue()` → `Parameter.getValue()`; `bounds().minValue()`/`minValue()` → `Parameter.getMinimumValue()`; `bounds().maxValue()`/`maxValue()` → `Parameter.getMaximumValue()`; `bounds().defaultValue()`/`defaultValue()` → `Parameter.getDefaultValue()`; `name()` → `Parameter.name()`; `visible()`/`editable()` → `parameter.ui().visible()`/`editable()`. Mind the type changes: the `Parameter` value accessors return `float` (the summary carried `double`), `Parameter.name()` returns `Optional<String>` and the palette flags are `Optional<Boolean>` — empty when the backend cannot report a palette state. |
| `ParameterBounds` (`minValue`/`maxValue`/`defaultValue` components, record constructor) | Folded into the `Parameter` accessors above; there is no standalone bounds carrier on the object API. |
| `PluginContext.parameterQuery()` | Removed with the service it returned; use `context.cubism().model().active().parameters()` as above. `services().find(ParameterQueryService.class)` call sites must be deleted — the service type no longer exists. |
| `PluginService.PARAMETER_QUERY` | Removed with the service; declared-service manifests and `PluginService` lookups naming it must be dropped. See the ordinal migration note in Existing records. |

### @Incubating scope changes

None. The incubating set is unchanged — but note the incubating
`PluginService` constants declared after `PARAMETER_QUERY`
(`MESH_TOOLS`, `MODELING_TOOLS`, `MCP_CONNECTIONS`) still take the real JVM
ordinal shift even though they leave no dump records.

### Documentation-only changes

34 service interfaces reword their `isAvailable()` contract to "backend
unavailable, including the sentinel" and `CubismModel`, `Parameter`, the
`cubism.model` package-info and `PluginServices` drop `ParameterSummary`
references. Javadoc and private switch labels are not canonical records, so
none of this enters the dump.

## Reproduction and gates

Reconstruct the pinned SDK to a caller-selected review path:

```sh
python3 scripts/test/reconstruct_sdk_gradle_jar.py \
  --root . --commit 34a68362b7d1b523094e6788f9346339c584f116 \
  --gradle <gradle-8.10.2-executable> --output <review-reference.jar> \
  --reuse-gradle-user-home <gradle-cache>
python3 scripts/test/sdk_api_baseline_cli.py capture \
  --input <review-reference.jar> --package-prefix dev.turboism.sdk \
  --role exact --commit 34a68362b7d1b523094e6788f9346339c584f116 \
  --output <review-baseline.json>
```

Run the historical audit, live freeze, mutation selftests and retained linkage:

```sh
./gradlew checkSdkV13ExactApiCompatibility checkSdkV14ExactApiCompatibility \
  checkSdkApiBaselineTool checkSdkV8Linkage checkTextureAtlasSdkV7Linkage
./gradlew checkRelease -PinstallerVersion=0.44.0 -PturboismRelease=true
```

A v13 reference used as the live input against the v14 baseline must fail; using
the wrong expected commit must also fail. The existing baseline-tool selftests
remain responsible for API mutation and identity-tampering rejection. API gates
do not replace behavior tests, package checks or exact-host validation.
