# ArtPath and Incremental Update: Feasibility Analysis (2026-11-02)

## Conclusion

ArtPath (shape path) is first a **modelling aid** and only second a savable drawable:

- `ToolGroup_ArtPath.command_createArtMeshForHitTest` creates an ArtMesh,
  `addControlPoint` accepts `CArtMesh`/`CArtMeshSource` directly, and
  `genControllerExtensionByArtPath` generates a controller extension from a path.
  The i18n bundle also carries entries such as "Auto Generation of ArtPath" and
  "Align ArtPath's control point positions with selected ArtMesh". A path drawn
  with the tool is normally converted into ArtMesh vertices and controller curves.
- When the ArtPath object itself is retained, it is `CArtPath extends ACDrawable`
  with a `GMeshRenderer`, brush and colour, key shapes, and membership in
  `getAllArtPaths()`. The update pass interpolates it and runs the point-to-mesh
  skinning of `postAffectedForm`.

Therefore:

1. ArtMesh vertices produced by the tool are ordinary mesh geometry that the
   existing incremental reuse already covers, with no extra switch.
2. Only a **retained `CArtPath` drawable** is affected by the ArtPath gate of
   `unsafe()`. The local desktop sample reports `containArtPath=false` and
   `getAllArtPaths()=0`, so it kept only the generated mesh and retained no path
   object.

For a retained path object, incremental reuse (reusing `deformedForm` and
skipping per-object interpolation) holds in principle as well; but no local
project contains a `CArtPath`, so no host comparison is possible, and `unsafe()`
still rejects ArtPath as a whole.

## Host Pipeline Evidence (5.0.00 / 5.2.03 / 5.3.04)

The geometry update runs two ArtPath passes:

1. `c(CModel, CParameterSet, List<CArtPath>, ...)` only interpolates: it skips when
   `e && keyformGrid.c()` is true, exactly as for ArtMesh and Deformer, and
   performs no transform.
2. `a(CModel, List<CArtPath>, az)` handles each path:

   - `CArtPath.setPostAffectedForm(null)` clears the output of the previous frame;
   - when `CArtPathSource.getExtensions()` holds a
     `CArtPathPointToArtMeshExtension`, it first calls
     `preparePostAffectedForm$cubism()` and then uses `PointInTriangle` to take
     points inside a triangle from the ArtMesh `getCalculatedForm()` and write
     them into the **path point** `CSplineCurvePoint.getPoint()` (`setX`/`setY`).

`CArtPath.getCalculatedForm()` falls back through `postAffectedForm`,
`affectedForm`, `deformedForm`, `localAnimatedForm`, and `interpolatedForm`, and
rendering uses that result. The whole ArtPath pass only reads ArtMesh and writes
no mesh vertex, so reusing the mesh `deformedForm` cannot make the path result
stale, exactly as with glue.

## Remaining Problems

1. **Probe coverage**: `IncrementalUpdateBridge.digest()` currently records
   `getDeformedForm()` and `getAffectedForm()`, while the real ArtPath output is
   `getPostAffectedForm()`. That getter must enter the digest before the gate
   opens, or the alternating full/narrow probe stays blind to paths. The getter
   exists on `CArtPath` in all 20 reviewed versions, so it can become a new
   verified dependency.
2. **Extension allowlist**: an ArtPath source may carry other `ACExtension`
   instances (controller, autoYure, and similar). The current update pass handles
   only `CArtPathPointToArtMeshExtension`; an unreviewed extension type must keep
   the model on the native path.
3. **Host sample**: diagnostics on the 3 local models (including a 763 MB project)
   all report `artPaths=0`. Without an ArtPath project there is no OFF/ON
   coordinate and pixel comparison, and therefore no host acceptance evidence of
   the kind this repository requires.

## Smallest Change When the Gate Opens

```text
unsafe(model):
  every path satisfies:
    - getAllArtPaths() is a List;
    - each path source holds only CArtPathPointToArtMeshExtension in extensions, or none;
    - the new switch turboism.optimization.incrementalUpdateArtPaths (default false).
digest(drawable):
  CArtPath -> putForm(getPostAffectedForm())
acceptance:
  On a project with ArtPath, drag Warp and run Undo/Redo;
  getCalculatedForm() (postAffectedForm for paths) matches bit for bit and pixel for pixel
  between OFF and ON, ON reuse > 0, failures = 0; then run the probe alternating
  full/narrow with no mismatch.
```

Until these conditions hold, an ArtPath model keeps the full native fallback and
the existing glue switch is unaffected.
