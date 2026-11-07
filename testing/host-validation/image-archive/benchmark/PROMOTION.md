# Texture-upload preparation promotion: direct conversion + large-image range

Status of the promoted production implementation as of 2026-11-07. Earlier,
pre-promotion evidence remains in [README.md](README.md),
[NATIVE-WINDOWS.md](NATIVE-WINDOWS.md), [HOTSPOTS.md](HOTSPOTS.md) and the
[private experiment ledger](../experiments/RESULTS.md); those documents describe
their own pinned builds and are **not** re-labeled as current-product tests.

## What was promoted

Two source changes to `runtime/.../optimization/image/CanonicalRgbaImage.java` are integrated
together under the existing opt-in, default-off setting
`-Dturboism.optimization.textureUploadPreparation=true`:

1. **Direct ARGB/ABGR raster conversion** (previously measured only as a
   benchmark candidate): standard JDK `TYPE_INT_ARGB` / `TYPE_4BYTE_ABGR`
   rasters are read directly from their arrays; all other layouts keep the
   `getRGB` fallback.
2. **Expanded admission range**: maximum area raised from 33,554,432 to
   **67,108,864 pixels** (8192×8192), one fresh RGBA target bounded to
   **256 MiB**. Scalar checks live in the package-private `supportedSize()`;
   `prepare()` calls it. The previous area bound was 33,554,432 pixels.

Unchanged: shader call site and pin verification, JVM 17 gating, pinned JOGL
artifact gating, premultiplication table, destination allocation/ownership,
GL operations, source-image immutability, no caching, and the opt-in/default-off
policy. Safe mode, disabled hooks, invalid config and non-matching artifacts
still refuse installation.

## Why the large-image range was promoted

The earlier direct-only build had **no whole-load benefit** on its own
(document-ready medians 43.112 → 43.122 s). The large-image candidate did show
positive screening signals in two separate bracketed blocks on native Windows
5.3.04 (one model, RTX 4060):

| Cohort | Control ready (s) | Candidate ready (s) | Observed allocation (GB) |
| --- | ---: | ---: | --- |
| Screen | 40.774 | 39.005 (−4.3%) | 21.058 → 16.789 (≈ −20%) |
| Confirmation | 42.877 | 41.264 (−3.8%) | 21.095 → 16.751 (≈ −20%) |

**Limits of that evidence:** only two samples per arm per block, one model, one
version (5.3.04), one GPU, no statistical testing; baseline spread was roughly
39–44 s. These are screening point estimates, **not a stable or general
performance promise**. The prior promotion-independent validation compared all
**11 texture preparations / 939,524,096 bytes** with zero differences
(`large-shadow`), including two real 8192² inputs. Full numbers:
[experiment results](../experiments/RESULTS.md) and [hotspot attribution](HOTSPOTS.md).

## Known limitations carried into production

- **Memory observations were not uniformly favorable.** Direct-conversion
  loading runs showed adverse/variable peak-commit and idle working-set medians;
  large-image peak-working-set magnitude was unstable across blocks (−29% vs
  −3%). Observed allocation reduction is a sampled lower bound, **not** retained
  RAM, VRAM or a guaranteed peak-memory saving.
- **Java2D acceleration tracking.** `DataBuffer.getData()` on the source can
  affect Java2D source-acceleration bookkeeping. Pixels are unchanged, but
  subsequent interactive rendering FPS/deformation cost remains unmeasured.
- **256 MiB target arrays.** Admitting 8192² inputs allocates a 256 MiB RGBA
  buffer per eligible call. This is a bound, not a claim that peak memory grows
  by exactly 256 MiB relative to the native path (which also allocates
  conversion storage).
- **No dirty rectangles / partial upload / coalescing.** Upload regions are
  unchanged; this targets CPU conversion and temporary allocation only.

## Version coverage — honest status

The production installer derives the texture-factory owner per version from the
class pin table (`texture-upload-preparation.json`), covering **20 reviewed
stable versions** across three shader families:

| Family | Pin owner | Reviewed versions |
| --- | --- | --- |
| w | `com/live2d/graphics3d/shader/w` | 5.0.00–5.0.06, 5.1.00–5.1.03 (11) |
| y | `com/live2d/graphics3d/shader/y` | 5.2.00–5.2.03 (4) |
| A | `com/live2d/graphics3d/shader/A` | 5.3.00–5.3.04 (5) |

Pin coverage is **not** runtime-test coverage. Current evidence status:

| Evidence tier | Versions | Status |
| --- | --- | --- |
| Exact editor JAR corpus available | 5.0.01, 5.0.05, 5.1.00, 5.2.03, 5.3.00, 5.3.02, 5.3.04 (7 releases) | Newly integrated multi-version artifact tests **pending**; corpus directories may lack bundled dependencies |
| Complete local installed hosts | 5.1 (exact patch pending confirmation), 5.2.03, 5.3.04 | Historical direct-only and private large-image builds were tested on 5.3.04. The newly integrated product build and the other hosts still need real-host validation |
| Remaining reviewed versions (13 of 20) | 5.0.00, 5.0.02–5.0.04, 5.0.06, 5.1.01–5.1.03, 5.2.00–5.2.02, 5.3.01, 5.3.03 | **No new artifact/runtime test**; declared in the reviewed pin table, not newly verified on these versions |

**Do not claim all 20 runtime versions are tested.** The pin table admits an
install after verified class-shape/hash checks; it does not substitute for
per-version shadow equivalence or loading evidence.

## Integration verification status

This is a work-in-progress branch, not release acceptance.

- [x] Integrated 8192-square area bound with the existing direct-array conversion and unchanged default-off setting.
- [x] Focused converter/transformer tests passed, including scalar size/overflow checks and full-byte 8192×4097 ARGB/ABGR comparisons with unchanged source pixels.
- [x] Native probe and Windows/Proton wrappers now accept/derive the factory owner from the frozen product pin rather than always assuming shader.A.
- [x] Python harness tests (8), Ruff and shell syntax passed after factory-owner selection changes.
- [x] Rebuilt the product/texture-validation bundle; `checkTextureUploadValidationBundle` and focused runtime/Spotless checks passed. The standalone native-probe factory-selection smoke test also passed on JDK 17.
- [ ] Wire/run the new standalone native-probe factory-selection test in the standard validation gate.
- [ ] Expand `TextureUploadPreparationArtifactTest` beyond its existing three explicit versions; add an offline all-reviewed-version pin/owner contract test. No such expanded test has been implemented yet.
- [ ] Stage exact JAR dependencies, verify each available artifact and all three shader families; distinguish this from unavailable full-version binaries.
- [ ] Rebuild the preview product/validation bundle and shadow-test that exact build on the available hosts, using version-compatible fixtures and isolated homes.
- [ ] Complete-host loading comparisons on 5.1 / 5.2.03 — pending.
- [ ] Interactive rendering (FPS/deformation) and broader memory/input checks for 256 MiB targets — unmeasured.
- [ ] Add automated regression coverage for the historical builder's `--converter-source` admission guard and validate source/control equivalence for a frozen reproduction.
- [ ] Run proportionate final gates after the intended base is integrated; `checkCompletedCommit` has not been run for this promotion.

Historical raw evidence is local-only; sanitized tables and hashes are tracked in
`benchmark/observations/` and `experiments/observations/`. Another machine needs its
own authorized fixtures and matching official artifacts; the earlier private JAR,
model, profile, AppData and raw-log directories are not part of this branch.
