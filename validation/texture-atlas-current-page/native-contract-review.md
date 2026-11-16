# Texture Atlas Native Contract Independent Review (Read-Only)

Conclusion: this is implementable as **one receiver input → current-page placement plus native overflow**. The scale is the absolute ratio from model-image local coordinates to texture pixels, not a delta on the old atlas scale. The rotation and write-back formulas are in §3. It must never call the whole-model image enumeration or plan another page.

## Evidence Basis

- Review date: 2026-09-07; current worktree `${ATLAS_WORKTREE}`. The main agent was implementing in parallel, so the current implementation and test line numbers below are frozen at **`84beadfd404509036e17a076a7c022e150f2c6ad`** and its uncommitted work is not evaluated.
- **A** = `runtime/src/main/java/dev/turboism/adapter/cubism/textureatlas/VerifiedTextureAtlasNativeInvocationAdapter.java` at that baseline.
- **T** = `runtime/src/test/java/dev/turboism/adapter/cubism/textureatlas/TextureAtlasNativeInvocationCoordinatorTest.java` at that baseline.
- **L** = `${LEGACY_WORKTREE}/plugins/turboism.texture-atlas/src/CubismTextureAtlasLayoutTool.java`; its HEAD is confirmed at `3c2f0fbbbaafa689afa32334941950790e0624dd`.
- **D** = `${LEGACY_RESEARCH_ROOT}/research/decompiled/com/live2d/cubism/doc/modeling/ui/atlasEditor/`. `D/a/b.kt` and `D/a/a.kt` are readable; `D/a/c.kt:1–10`, `D/a/e.kt:1–11`, and `D/impl/v.kt:1–10` are really failed decompilation records and cannot serve as algorithm source.
- Because of that gap the official JAR was checked independently with offline `javap -c -p -l`; no class from it was run. The JAR has no relevant LineNumberTable, so **BC below is a bytecode offset inside a method and is never presented as a source line number**.
- Native abbreviations: `C` = `com.live2d.cubism.doc.modeling.ui.atlasEditor.a.c`; `B` = `b` in the same package; `C.run` = `C.a(c$c)`; `C.rect` = `C.b(c$b,b)`; `C.place` = `C.a(c$b,b,jp.live2d.type_editor.i,boolean,int,int)`; `V.run` = `com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.v$a.run()`.

## 1. Input, Rect, and the Existing Scale

**Read only `receiver.c.b()` and `DATA_ITEMS`; the size comes from the same `receiver.c.c()` and `d()`.** Native `V.run` BC 1–89 enumerates only the container children of this one `impl.S`, wraps each in `new B(editLayer)`, and then calls `C.run`. It is not `CTextureManager.getAllModelImages()` and it walks no texture page. Compare L:391–413 and A:76–103.

| Mode | Exact source geometry | Scale meaning and evidence |
|---|---|---|
| `settings.c()==false` (mesh range) | `r=item.h()`: the union AABB of the local mesh shapes, falling back to the full image rectangle when there are no shapes | **Excludes the existing LayerRef atlas translation, rotation, and scale, and excludes DATA_CURRENT_SCALE.** D/a/b.kt:78–110; `B.h` BC 0–359. |
| `settings.c()==true` (full model image) | `r=item.e()=(0,0,item.f(),item.g())`; `f` and `g` are the original CModelImage width and height | D/a/b.kt:64–75; `B.e` BC 0–31. The mode changes only the geometry range of each input and **never the input set**. |

Tracing further: `TAE__EditLayer_ModelImage.setupEditLayer()` BC 313–397 and 622–712 turn editable mesh coordinates into material-local shapes through `materialLocalToBasicCoordTransform.createInverse()`; the current atlas LayerRef transform is never applied there.

`item.c()` is the wrapper `layoutScale` and **is not an old ratio extracted from the current texture transform**: the constructor fixes `d=1.0`, the getter only returns d, and there is no setter (D/a/b.kt:36–53; `B.<init>` BC 49–54). Native `C.rect` BC 93–128 does multiply the original width and height by that q, but the factory above always produces q=1. So `item.h()` cannot be said to have already multiplied q, and the old DATA_SCALE must not be multiplied in either. The q factor at L:436 and L:526–527 is 1 for an ordinary input and is no proven "double scaling bug".

## 2. requestedScale: Automatic and Fixed

- `requestedScale > 0`: **fixed absolute scale s=requestedScale**. Native sets the buffer width and height limit to `atlasW/s, atlasH/s` (C.run BC 81–140) and still uses requestedScale directly at the end (708–727); an item that does not fit enters overflow and is never silently shrunk to place everything.
- `requestedScale <= 0`: automatic. Native first lays out into a growable buffer and then matches the page width and height to the occupied range, capped at 1 (C.run BC 604–772); the mathematical intent is `s=min(1,atlasW/usedW,atlasH/usedH)`, and the real branch converts the float width and height ratios. **0 is neither the final scale nor the same as fixed 1.** The automatic search may replace the heuristic but must not search the whole texture atlas.
- The UI fixed value is the percentage divided by 100 and the automatic value writes 0: native `a.f.c(f,ActionEvent)` BC 66–107; a readable UI clue is D/a/g.kt:16–22.
- The old DATA_CURRENT_SCALE serves only as a rollback snapshot; the new s is never multiplied with it. Validate finiteness and a positive effective output scale first and never treat NaN or Infinity as an automatic sentinel.
- The native automatic algorithm is not the legacy binary search. Whether to pursue "the largest feasible s under the chosen single-page packer" and the precision and iteration count are new-implementation strategy, not a guarantee obtained from native.

## 3. Directly Implementable Transform and Scale Formula

Let `r=(rx,ry,rw,rh)` be the **unrounded source rectangle** of §1, `p=(px,py)` the **top-left corner of the content AABB in the final texture pixels, padding already excluded**, and s the final uniform scale of §2. Use column vectors; Java AffineTransform concatenate multiplies on the right.

```text
no rotation: M = T(px,py) · S(s) · T(-rx,-ry)
rotation:    M = T(px,py) · S(s) · T(rh,0) · R(+π/2) · T(-rx,-ry)

equivalent native and legacy decomposition:
M = S(s) · T(px/s,py/s) · [T(rh,0) · R(+π/2)] · T(-rx,-ry)
```

The matching `new AffineTransform(m00,m10,m01,m11,m02,m12)` is directly constructible:

```java
// no rotation
new AffineTransform(s, 0, 0, s, px - s*rx, py - s*ry);
// rotated +90° (screen y-down: clockwise)
new AffineTransform(0, s, -s, 0, px + s*(ry + rh), py - s*rx);
```

- Point transform: without rotation `(px+s*(u-rx), py+s*(v-ry))`; with rotation `(px+s*(rh-(v-ry)), py+s*(u-rx))`. The rotated AABB is `(px,py,s*rh,s*rw)` and the unrotated one is `(px,py,s*rw,s*rh)`.
- **It is not a rotation about the image centre, and the final pixel p is not multiplied by s again.** Normalize to the source top-left, rotate, compensate with the original source height, then scale and translate. The compensation uses the raw rh, never the rotated height, `ceil(rh)`, the packed height, or a height with margin.
- Native construction evidence: C.place BC 0–24 selects the source rect; 35–70 adds the buffer margin and position; 73–102 does `translate(rh,0); rotate(π/2)`; 105–135 does `translate(-rx,-ry)`. C.run BC 848–875 then applies `S(s).concatenate(pos)` and stores the item. L:1174–1187 and 1195–1246 follow the same order.
- CAffine really extends Java AffineTransform; `CAffine.concatenate(CAffine)` BC 6–14 calls the parent concatenate, and `CAffine$a.a(double,double)` BC 0–18 calls `AffineTransform.getScaleInstance`. There is no extra hidden inversion.
- **Replace** the local-to-texture transform of the item and the LayerRef entirely; never concatenate the old atlas M. The old transform may be non-identity, but native overwrites it anyway.
- Align with the SDK contract the main agent reported last: take `s=plan.scale()` and set the placement width and height to `ceil(inputWidth*s), ceil(inputHeight*s)`, swapped after rotation. If the input already uses `inputWidth=ceil(rw)`, that integer bounding box may be slightly larger than the exact geometric AABB of M, which is conservative packing; **the raw source rh must still be kept separately for the rotation compensation**. Never use the placement height as the pivot, and never derive a non-uniform s from the integer placement width and the source width. With padding m per side, p is the pack outer box origin plus m. The native margin and 8 px buffer heuristic does not promise pixel identity with this padding strategy.
- Example: r=(10,20,80,40), p=(7,11), s=.5 gives the rotation matrix `(0,.5,-.5,0,37,6)`, whose four-corner AABB is exactly `(7,11,20,40)`.

## 4. DATA_SCALE and Write-Back: Three Outputs Must Stay in Sync

`DATA_SCALE / atlasData.a(double)` **only assigns field b** (D/a/a.kt:28–30; native BC 0–5) and scales no item, layer, or container. The new s must agree with the matrices actually written:

1. For every placed input call `ITEM_TRANSFORM(item, CAffine(M))`, where B.a(CAffine) writes only f (D/a/b.kt:60–62).
2. Synchronously call `LayerRef.setTransformToParent(editorAffine(M))` on the LayerRef matching that item.editLayer, instead of writing only the item data. Native C.run BC 878–1027; L:1245–1261. A missing matching layer must be refused or rolled back and never reported as APPLIED.
3. `DATA_SCALE(data,s)` and replace the receiver overflow content in place. An unplaced item receives no new transform; no other page and no other object is written.

Take special care: the LayerRef setter **stores the inverse of M and fires fireTransformChangedEvent** (BC 0–17); the getter `getTransformToParent` inverts again and returns M (BC 0–7). `jp.live2d.type_editor.a.b()` BC 0–13 calls createInverse. The write must therefore go through the setter and never touch the internal transform field of the LayerRef, and a read-back comparison needs a tolerance because a double inversion is not bit-identical.

Before updating a LayerRef, native checks invertibility and `abs(det(M)) > 9.99999993922529e-9` (C.run BC 988–1027); here det=s². A very small s must not be declared visually successful just because the item write succeeded. Perform the geometry, membership, matching, and invertibility checks in one pass before writing and roll back item f, the LayerRef, data.b, and the original overflow content together on failure. A:228–246 already has that snapshot restore shape, but the baseline success read-back at A:270–275 checks only the item and the overflow and **never the layer or the data scale**.

## 5. Overflow, Return Value, and the Single-Page Boundary

- `receiver.i` holds the **native item instances** of this input that were not placed, not a textureId or a cross-page placement. The native branch for an item that does not fit calls `i.add(item)` directly (C.a(c$b,b) BC 545–553 and 732–740), and the write-back loop skips overflow (C.run BC 836–845).
- After C.run returns, the native caller reads `C.a()` (which returns i directly), runs the removal command of the current layer for each entry, and then refreshes the model image list and the current view: V.run BC 93–238. `jp.noids.design.layer.b.s.redo()` BC 40–63 confirms the final `ContainerLayer.removeChild`. **This is "handing back the unplaced list", not this hook creating the next texture page.** The adapter must not delete a child early and must not plan the next page for the overflow itself.
- C.run returning true means the algorithm completed normally and **does not mean every input was placed** (BC 1092–1116); a non-empty input may even be entirely overflow. The false path covers no input or a cancellation. The current transformer callback returns IRETURN 1 directly for handled=true (`TextureAtlasAutoLayoutTransformer.java:181–186`), and the host V.run even discards that boolean (BC 104) and still consumes i.
- A single-page plan must therefore express a subset of the current-page placements; a missing input goes into overflow, the original input order may be preserved, and only pageIndex=0 is allowed. Planning pages 1, 2, … to build a "complete plan" and then treating them as overflow is forbidden. The A baseline `maxPages=32` (181), its requirement of all ids (188–190), and its treatment of a non-zero page as overflow (196–207) are an adapter strategy to be replaced, not the native contract.
- L:1124–1130 clears the old i and 1166–1170 adds the unplaced items, which is the right intent; a failure rollback must restore the original i so a partial write-back cannot pollute the next native run.

## 6. Legacy Points Not to Copy Blindly

1. **The fixed scale is silently lowered (a clear defect)**: L:466–469 fixes hi=lo=requestedScale; when not everything fits, the serial path at L:556–558 re-packs at `scaleLo*0.8`. This violates the fixed-scale plus overflow contract.
2. **A successful candidate is destroyed by a later trial (a clear aliasing defect)**: L:535–546 mutates a shared ItemInfo and stores a placed list holding the same objects; a later failure at L:549–552 clears the position, rotation, and placed flag of every object; the snapshot taken at L:576 and 721–734 then holds the destroyed state rather than the coordinates of bestScale. This can lose an already found successful layout. Save an immutable or deep-copied result and never keep a shared mutable list.
3. **A partial write-back failure still reports success (a clear defect)**: L:1154–1160 swallows a single-item Throwable and continues to write the global scale, and executeLayout at L:614 still returns true; it may also skip the visual write-back at L:1258 because of a degenerate matrix. The tryInvoke of the adjacent `TextureAtlasBridge.java:139–143` returns an optional or null and is no proof that the setter succeeded. A new implementation must not copy this "data succeeded, canvas untouched" boundary.
4. **q≠1 has no reliable contract (out of scope)**: L:526–527 multiplies q during packing and L:1234–1246 multiplies only s on output; native likewise reads q during packing and never multiplies q in the transform. A normal native factory has q=1, so this is no double-scaling problem on the normal flow; if someone reflectively changes wrapper.d, no arbitrary q support may be derived from legacy, and q must never be treated as the old atlas scale.

## 7. Mapping and Verification Availability, and Explicit Gaps

- The three `compatibility/cubism/verification/cubism-{5.2.03,5.3.02,5.3.03}-editor-model.json` files each hold 32 native selectors and **no selector for `native.item.scale`**. Defining the string at A:37 does not mean the resolver can use it, and `NATIVE_INVOCATION_ALIASES` in `VerifiedTextureAtlasSelectorContract.java` does not require it either. The normal q=1 path needs no unknown old ratio.
- **Model image type trap**: in 5.3.03 JSON:6043–6063 `ITEM_MODEL_RECT` returns a CRect while `ITEM_RECT` returns a GRectF, and every RECT_* getter owner at JSON:6164–6206 is a GRectF. A CRect must never be passed to a GRectF getter. Full-image mode is proven to have a fixed source origin of 0, so the existing ITEM_WIDTH and ITEM_HEIGHT suffice; going through `e().toGRect()` would need a separately approved mapping, which these 32 entries do not provide.
- The baseline T:26–81 covers temporary state, never calling the persistent provider, and failure rollback; T:84–151 covers connection switching, Throwable, and nesting. **It does not cover real scaling, rotation, or model image mode**: T:259–263 hardcodes false, false, and 1; T:280 keeps the source origin at 0; T:283 disguises `e()` and `h()` as the same Rect, so a real CRect and GRectF mismatch cannot be caught; and the setter at T:304 does not simulate the native inversion or event.
- Focused acceptance still to add (handed to the main agent; this task changes no test): a negative or non-zero source origin; fractional rh rotation compensation; an old layer scale other than 1 while the new fixed s is absolute; fixed .5, 1, and above 1 with overflow; automatic s at or below 1; different geometry per mode; partial and complete overflow; other pages untouched; recovery of every output after a mid-write-back failure; and the layer inversion read-back tolerance plus the det threshold.
- **Executed this round**: targeted parsing of the three JSON files; the official JAR hashes and the named class comparison; offline disassembly of the key methods; and **48 pure mathematical assertions passing** (non-zero, negative, and fractional origins; .125, .5, 1, and 1.25; rotated and unrotated; verifying concatenation against the explicit matrix, the four-corner AABB, and det=s²). This is no native dynamic execution or project test.
- **Not executed this round**: Gradle or JUnit, to avoid generating or changing the repository build and cache; and no Cubism session was started, connected, probed, or modified. The existing T can serve as the focused extension entry, and a static or fake result can never claim exact-host readiness.
- The named `${PROJECT_ROOT}/.docs/oracle/texture-atlas-r9-c22f8ff/review.md:10,34–37,54` reviews the old persistent manager and undo truth-value fix, not this native-invocation single-page contract, and it explicitly does not equal real-host readiness. Interaction, undo and redo, cancel recovery, and save and reopen remain **host acceptance not done**, which this task is not authorized to run.

### Exact JAR Identity and Review Method

All read only from `${GOLDEN_PREFIX}/pfx/drive_c/Program Files/Live2D Cubism <directory version>/app/lib/Live2D_Cubism.jar`:

| Profile (directory) | SHA-256 computed this round |
|---|---|
| 5.2.03 (5.2) | `bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd` |
| 5.3.02 (5.3) | `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21` |
| 5.3.03 (5.3.03) | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |

The class bytes of a/a, a/b, a/c, a/d, a/e, a/c$b, a/f, impl/v$a, CAffine, LayerRef, and the editor affine are identical across the three versions; the a/c SHA is `dc567866e1bd2c9fe6da8c80d155769d425fd793da2d140e6fa3eb5116d6f7b7`. The CAffine companion class differs as a whole, but the normalized disassembly of the scale factory method above matches; the only 5.2 to 5.3.03 difference found in EditLayer.setupEditLayer is the getGlIndices default-argument helper class `util/i/a` becoming `util/j/a`, and the inverse transform construction path is the same. This is not generalized to future versions that were not checked.

Example read-only review commands (they start no Cubism):

```sh
javap -c -p -l -classpath "$JAR" 'com.live2d.cubism.doc.modeling.ui.atlasEditor.a.c'
javap -c -p -l -classpath "$JAR" 'com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.v$a'
```

**Delivery boundary: this `/tmp` report is the only addition; no production implementation, SDK, test, or host file was modified.**
