# All-reviewed-version texture factory artifact check

Offline exact-byte verification for the texture-upload preparation promotion.
This document covers **bytecode admission only**; it does not claim that the
same versions were launched, loaded a model, or produced a performance change.

## Result

The validation machine staged the exact official `Live2D_Cubism.jar` for every
reviewed stable version and ran `TextureUploadPreparationArtifactTest` against
the full lib directory of each installation.

| Family | Factory owner | Versions |
| --- | --- | --- |
| w | `com/live2d/graphics3d/shader/w` | 5.0.00, 5.0.01, 5.0.02, 5.0.03, 5.0.04, 5.0.05, 5.0.06, 5.1.00, 5.1.01, 5.1.02, 5.1.03 |
| y | `com/live2d/graphics3d/shader/y` | 5.2.00, 5.2.01, 5.2.02, 5.2.03 |
| A | `com/live2d/graphics3d/shader/A` | 5.3.00, 5.3.01, 5.3.02, 5.3.03, 5.3.04 |

Recorded result: **23 tests executed, 23 passed, 0 skipped, 0 failures** (three
historical explicit entry points plus 20 parameterized reviewed versions).
For every version the test checked:

- the whole Editor JAR equals `ReviewedHostArtifacts` (size and SHA-256);
- the factory class equals its `texture-upload-preparation.json` pin;
- the bundled `jogl/jogl-all.jar`, when present, equals the reviewed JOGL gate;
- the exact `newTexture` call site transforms and the neighboring overload stays
  byte-shape identical;
- a mutated factory body is rejected;
- the source JAR is unchanged after the test.

The companion `TextureUploadPreparationPinTableTest` adds five offline checks:
the pin table covers exactly the reviewed stable versions, each block names one
factory owner, the owner matches the reviewed generation, family pins stay
stable, and every reviewed artifact has a pin. The native-probe owner selection
test is also part of `checkTextureUploadValidationBundle`.

## Reproduce

Stage a reference root whose children are `<version>/Live2D_Cubism.jar` plus the
dependencies used by that installation (a directory junction/copy of the official
`app/lib` is sufficient), then run:

```sh
./gradlew :runtime:legacyCubismEvidenceTest \
  --tests '*TextureUploadPreparationArtifactTest' \
  -Dturboism.test.cubismRefDir=/path/to/refs \
  --no-daemon --max-workers=2
```

Without a reference root the class keeps its historical three exact-JAR property
entry points and skips the remaining versions. A staged but missing version is
also skipped with an explicit assumption; inspect the XML result counts rather
than treating a green build as full coverage.

## Limits

- This is offline class-file verification. It does not install the hook, create
  an OpenGL context, decode PNG data, or measure loading/memory.
- The exact JAR corpus was present on one authorized validation machine. Do not
  claim other machines have the same corpus without checking the artifact hashes.
- Runtime evidence is tracked separately in [PROMOTION.md](PROMOTION.md) and
  [MULTIVERSION.md](MULTIVERSION.md). All 20 reviewed stable versions have now
  been launched and shadow-verified; the offline test here remains the exact-byte
  admission proof, not a runtime substitute.
