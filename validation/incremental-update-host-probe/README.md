# incremental-update-host-probe

Host-behaviour probe for the incremental update path.

Run `./build.sh` to compile. A real Cubism Editor host is required.

## Glue Affecters in Incremental Update (2026-11-02)

`unsafe(model)` still rejects ArtPath and any affecter that is not a `CGlue`; only an explicit `-Dturboism.optimization.incrementalUpdateAffecters=true` allows reuse for a model that holds glue alone. Every host update clears `affectedForm` and recomputes the glue result from the current `deformedForm`, so a reused state stays valid. The probe digest covers both `getDeformedForm()` and `getAffectedForm()`. The 5.0.00/5.1.03/5.2.03/5.3.03/5.3.04 host matrix and its limits are in [acceptance-20261102-affecters.md](acceptance-20261102-affecters.md).

## Undo Rebuild and Version Compatibility (2026-10-28)

After dragging a green Warp handle, an undo restores the form in place through `reinitInstance` without changing the document timestamp and without calling the interpolation entry point. Watching interpolation alone therefore cannot prove that the computed coordinates of a child ArtMesh stay reusable. Incremental update now watches the public `ACParameterControllableSource.reinitInstance` entry point, clears the completed and pending baselines, the parent and child caches, and the differential probe state, and restores native interpolation. The next update computes fully, and the following stable frame resumes incremental reuse. An in-frame rebuild never commits the old baseline; disabling the optimization removes the added callback and restores the rewritten class.

Covered versions: all 20, 5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, and 5.3.00–5.3.04. Each version pins the SHA-256 of the public source classes, of the Warp, Rotation, and ArtMesh source classes, and of their inheritance chain; an unknown method or a bytecode mismatch refuses installation. The installer requires all five classes and seven methods to inject successfully, and uninstall covers the added source classes.

Automated regression:

```sh
./gradlew :runtime:test --tests '*IncrementalUpdate*' \
  :bootstrap:test --tests '*IncrementalUpdate*'
TURBOISM_CUBISM_REF=/path/to/refs ./gradlew :runtime:legacyCubismEvidenceTest \
  --tests '*IncrementalUpdateArtifactTest*'
```

The reference directory is `<refs>/<version>/Live2D_Cubism.jar`, and the same directory and its subdirectories supply the dependency JARs of that version. A version directory may be a symlink. A version without licensed host material is skipped separately and never counted as verified.

All 20 real JAR checks passed this round, covering class fingerprints, the source-rebuild inheritance call chain, dependencies and method signatures, and the real bytecode transformation of the five classes. The offline tests cover rebuild invalidation without a timestamp or interpolation callback, in-frame and cross-thread notification with resumed reuse afterwards, and continued native rebuild when the notification is missing, mistyped, or throws. These results do not mean that all 20 versions passed editor interaction regression.

Host interaction acceptance: disable both mirrors, enable incremental update, drag a green Warp handle, then undo and redo, and check that Warp, source coordinates, child ArtMeshes, and multi-level child deformers all restore consistently; a stable frame must show reuse again. Repeat the same operations with incremental update disabled and compare. The in-editor result is in the [2026-10-28 host report](acceptance-20261028.md) and concludes only for the versions and scenarios that report lists.

Offline gate results: headless `devCheck` passed; runtime admission ran 3981 cases with zero failures and 8 skipped. All 20 real-version regressions passed with no skip. `spotlessJavaCheck` passed for runtime and bootstrap. The first graphical-mode `devCheck` stopped because an existing screenshot test blocked on system screen capture and is not counted as a pass.

## Cross-Thread Native Fallback (2026-10-28)

A callback that replaces dirty marking and ArtMesh deformation can trigger the native fallback of the injector only by throwing; a normal return or a `null` return counts as handled. A cross-thread call now clears the geometry baseline and the probe state, restores native interpolation, and then runs the native sequence through the existing exception branch. An interrupted update must not commit the old baseline, and a complete update resumes reuse.

The regression test combines the real Bridge callbacks with the transformed call sites of the 20 version descriptors, asserts that native dirty marking, transform creation, and ArtMesh deformation each run once, and verifies in-frame invalidation with later recovery. That test failed before the fix. This verifies call-site behaviour and does not add editor GUI interaction acceptance.

## In-Editor Warp Drag Acceptance

A task-specific Java agent drags a visible Warp control point through the official mouse-event wrapper of the current modelling view, calls the editor undo and redo commands, and compares every Warp and ArtMesh source form plus the computed coordinates bit for bit. Between stages it requests only `repaintCanvas` and calls neither a model rebuild nor `updateParamInstance`. Both mirror hooks are disabled and the mirror plugin is not loaded. The run first performs the native comparison with incremental update off, then enables it, compares the coordinates and canvas of four stages, and requires real mesh reuse with a non-increasing Bridge failure count.

```sh
./gradlew previewBundle
bash validation/incremental-update-host-probe/build-undo-agent.sh /path/to/editor/app/lib
bash validation/incremental-update-host-probe/run-undo-host-validation.sh 5304 \
  --golden-prefix /path/to/proton-prefix
```

The build carrier needs a licensed host installation supplied explicitly; it serves acceptance only and never enters the product distribution.

The local installation and project paths come from the existing `.env` or `TURBOISM_ENV_FILE`. The runner starts the official BAT through the global queue and copies the test project and the Proton prefix; the probe accepts only a task directory that matches the queue run ID and exits that task's editor after it writes its result. It uses only the native FREE startup of the editor or an existing licence state. The runner verifies the launcher, the original project, and the golden prefix, and the test never saves a modification into the original project.

The canvas is located exactly as `com.jogamp.opengl.awt.GLJPanel` in the component tree of the modelling view, so an older internal component or a texture preview is never mistaken for the modelling canvas. Coordinate keys use stable UUIDs, so a debug number that changes after an undo rebuild cannot cause a false report. Visual evidence comes from the native paint entry; a blank or single-colour capture never counts as a pass.

Final host result on 2026-10-28: the target scenario passed on 5.0.00, 5.1.03, 5.2.03, 5.3.03, and 5.3.04. See the [host report](acceptance-20261028.md) for the detailed task evidence and the re-measurement boundary of the single 5.0.00 pixel difference.

The undo tool of the combined acceptance waits for the framework runtime to finish startup and then requires the modelling canvas layout to stay stable for three consecutive seconds. Every group start and every canvas capture must share that boundary; a layout change rejects the comparison outright, and the bit-level coordinate and pixel equality assertions are never relaxed. This prevents an early OFF/ON pair from using different canvases while the framework toolbar is still being installed.

After undo, after redo, and after the final undo, the run records `meshSkipped` and the failure delta of the stable frame, and separately requires ON reuse to be positive, OFF reuse to be zero, and new Bridge failures to be zero. The total reuse of the whole group cannot replace these per-stage assertions.
