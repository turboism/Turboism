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

The **promoted build** was then checked on 5.1.03, 5.2.03 and 5.3.04. Shadow
comparisons again covered 11/11 preparations / 939,524,096 bytes with zero
mismatches per host. The clean off/on block measured wall medians −3.5% / −3.1% /
−7.7% and observed allocation −13.7% / −32.5% / −32.4% respectively. Two to three
samples per arm and one fixture still make this a screening signal, not a
release-wide guarantee. See [MULTIVERSION.md](MULTIVERSION.md).

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
| Exact editor JAR corpus staged on the validation machine | All 20 reviewed stable versions | **Passed** offline exact-byte artifact test: 23/23 executed, 0 skipped, 0 failures. Each JAR matched `ReviewedHostArtifacts`, its factory matched the pin, JOGL matched the gate, the adjacent overload stayed unchanged and tampering was rejected. See [ARTIFACTS.md](ARTIFACTS.md) |
| Complete local installed hosts | 5.1.03, 5.2.03, 5.3.04 (all bundled JVM 17.0.3.1 and the reviewed JOGL) | **Promoted product build shadow-tested on all three**: 11/11 preparations, 939,524,096 bytes, zero mismatches per host. Off/on loading comparisons completed: the clean block showed 3.1–7.7% shorter wall medians and 13.7–32.5% lower observed allocation. Two to three samples per arm; a screening signal, not a guarantee. See [MULTIVERSION.md](MULTIVERSION.md) |
| Remaining reviewed versions (13 of 20) | 5.0.00, 5.0.02–5.0.04, 5.0.06, 5.1.01–5.1.03, 5.2.00–5.2.02, 5.3.01, 5.3.03 | **Runtime shadow test passed** on the official `hiyori_free_t08` fixture: 1 preparation / 16,777,216 bytes, zero mismatches, safe cleanup and normal exit on every version. No per-version loading benchmark was run. See [MULTIVERSION.md](MULTIVERSION.md) |

**Do not claim all 20 runtime versions are tested.** The offline artifact test
proves exact bytecode admission for every reviewed stable JAR; it does not
substitute for per-version shadow equivalence or loading evidence.

Offline result recorded on 2026-11-07: the expanded parameterized test covered
all 20 stable versions and all three shader families. The historical three
explicit property entry points remain supported, and missing staged versions
skip with an explicit assumption. Reproduction details and limits are in
[ARTIFACTS.md](ARTIFACTS.md).

## Integration verification status

This is a work-in-progress branch, not release acceptance.

- [x] Integrated 8192-square area bound with the existing direct-array conversion and unchanged default-off setting.
- [x] Focused converter/transformer tests passed, including scalar size/overflow checks and full-byte 8192×4097 ARGB/ABGR comparisons with unchanged source pixels.
- [x] Native probe and Windows/Proton wrappers now accept/derive the factory owner from the frozen product pin rather than always assuming shader.A.
- [x] Python harness tests (8), Ruff and shell syntax passed after factory-owner selection changes.
- [x] Rebuilt the product/texture-validation bundle; `checkTextureUploadValidationBundle` and focused runtime/Spotless checks passed. The standalone native-probe factory-selection smoke test also passed on JDK 17.
- [x] Wire/run the new standalone native-probe factory-selection test in the standard validation gate (`checkNativeTextureFactorySelection` is part of `checkTextureUploadValidationBundle`).
- [x] Expand `TextureUploadPreparationArtifactTest` beyond its existing three explicit versions; add an offline all-reviewed-version pin/owner contract test. 23/23 executed, 0 skipped, 0 failures on the staged 20-version corpus.
- [x] Stage exact JAR dependencies and verify each available artifact and all three shader families; distinguish this from unavailable full-version binaries. See [ARTIFACTS.md](ARTIFACTS.md).
- [x] Rebuild the preview product/validation bundle and shadow-test that exact build on the available hosts, using isolated homes. 5.1.03/5.2.03/5.3.04 each compared 11/11 preparations and 939,524,096 bytes with zero mismatches.
- [x] Complete-host loading comparisons on 5.1.03 / 5.2.03 / 5.3.04. Clean block: wall medians −3.5% / −3.1% / −7.7% and observed allocation −13.7% / −32.5% / −32.4% for 5.1.03 / 5.2.03 / 5.3.04. Two to three samples per arm; a screening signal, not a guarantee. Details, diagnostic cohort and failures in [MULTIVERSION.md](MULTIVERSION.md).
- [x] Runtime coverage for all 20 reviewed stable versions: the 13 previously untested versions each loaded the official hiyori fixture with byte-identical upload data and verified restoration.
- [x] Interactive rendering under continuous repaint: no regression detected. Native Windows 5.3.04 with an in-JVM window-resize driver recorded 1,023 → 1,044 median frames (+2.1%) and 18.066 → 18.130 fpsMax (+0.4%) across `off, on, on, off`. Two runs per arm; this closes the Java2D-acceleration risk, not a speedup claim. See [MULTIVERSION.md](MULTIVERSION.md).
- [ ] Broader memory/input checks for 256 MiB targets — unmeasured.
- [x] Add automated regression coverage for the historical builder's `--converter-source` admission guard and validate source/control equivalence for a frozen reproduction. The builder now checks the control agent's converter long constants against the frozen source; eight offline tests pass.
- [ ] Run proportionate final gates after the intended base is integrated; `checkCompletedCommit` has not been run for this promotion.

Historical raw evidence is local-only; sanitized tables and hashes are tracked in
`benchmark/observations/` and `experiments/observations/`. Another machine needs its
own authorized fixtures and matching official artifacts; the earlier private JAR,
model, profile, AppData and raw-log directories are not part of this branch.

## Validation-machine note

On the takeover machine, 5.1.03, 5.2.03 and 5.3.04 were installed with the same
JVM 17.0.3.1 and reviewed JOGL, and the 728 MiB fixture matched the recorded
SHA-256. The offline 20-version artifact gate passed. The quiet-host check now
ignores already-exited Toolhelp process objects; seven such ghost entries from an
older Cubism 5.3.04 session were present but consumed no live process. The trial
welcome dialog and the newer-saved-file warning are handled by explicit test-only
runner flags; the warning was accepted only on disposable fixture copies. No live
host or user process was terminated. Raw evidence remains local; sanitized numbers
are in `observations/multiversion/`.
