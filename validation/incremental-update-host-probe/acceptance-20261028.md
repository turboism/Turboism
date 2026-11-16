# Warp Incremental Update Undo Host Acceptance (2026-10-28)

Acceptance ran in the official Cubism Editor with the build and run commands from the README in this directory. Both mirror hooks were disabled, the mirror plugin was not loaded, and full-frame skipping was off. With incremental update disabled and then enabled, the run dragged a visible Warp control point through the official mouse-event wrapper of the modelling view, then performed a native undo and redo.

| Version | Final result | Child ArtMeshes actually deformed | Coordinate records per stage | Mesh-reuse increment when enabled | New Bridge failures |
| --- | --- | --- | --- | --- | --- |
| 5.0.00 | PASS | 2 | 82 | 192 | 0 |
| 5.1.03 | PASS | 2 | 82 | 192 | 0 |
| 5.2.03 | PASS | 2 | 82 | 195 | 0 |
| 5.3.03 | PASS | 3 | 82 | 195 | 0 |
| 5.3.04 | PASS | 3 | 82 | 207 | 0 |

Each stage compared the raw float bit patterns of the positions of every Warp and ArtMesh source form and computed form. In every final case the undo state matched the initial state bit for bit and the redo state matched the dragged state bit for bit. The coordinates of the matching stage and the pixel digest of the native GLJPanel paint output also matched for both incremental update settings. The document timestamp stayed unchanged before and after undo on every version. Between stages the run requested only a repaint and called neither a model rebuild nor `updateParamInstance`; with incremental update off the mesh-reuse increment was zero.

Every final task exited normally, the isolation cleanup was safe, and the hashes of the original project and of the official JAR and BAT files of the golden installation were unchanged. The product agent under test had SHA-256 `e3fefeec7115338b7b2498429a6275df6fd06b24e1a6889c096a79254d78894d` and carried the reinitInstance geometry invalidation and the cross-thread native fallback fix. The 65 direct host method calls of the acceptance probe were separately checked against the inheritance chain and descriptors of 20 real JARs.

One early 5.0.00 redo frame showed a 467-pixel thin-line difference while the coordinate assertions passed. In the strict OFF / ON / OFF comparison that followed, all three coordinate sets and the four-stage pixel digests matched, and the difference did not reproduce. The root cause of that single occurrence is still unknown.

The original queue output, coordinates, screenshots, and difference record stay on the acceptance machine and never enter Git. This conclusion covers only the five listed versions and the target Warp and child mesh scenario of one test project. FREE mode may limit the advanced parameter capabilities of other objects in the project. The structural check of 20 real JARs is not host acceptance of all 20 versions, and it does not mean every editing operation or a future version is compatible.
