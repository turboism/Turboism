# Model Image Scale Lock Acceptance Plan

The runtime admits the canvas contract on exact Cubism 5.3.02, 5.3.03, and 5.3.04.
Static inspection checked all nine added selectors against each reviewed JAR.

## Verified on Cubism 5.3.02

- The probe locked two images at 80% in the native atlas editor on a 100-image model copy.
- MaxRects-BSSF and Dalsoo both repacked the page. Two images kept 80%, and 98 images used 100%.
- The rendered canvas showed a gray overlay and the native lock icon after the menu action.
- Cubism's native packing choice displayed a cancellation message. All 100 transforms stayed equal.
- Unlocking one image preserved the lock on the other image. The fixture copy kept its original hash.

Queue job `4bd40694-e651-418a-aa41-54cf8ed12dce` recorded 11 passing assertions, normal exit, and safe cleanup.

Queue job `544fe4f9-e34d-4daf-a346-55524658e29c` reopened the same model in a fresh Cubism process with the persisted lock file.
The remaining locked image returned to 80% during layout. The other images used 100%.
Cubism's native atlas renderer produced identical pixel hashes before and after unlock.
The job recorded six passing assertions and preserved the original fixture hash.

## Behavior

- Use Lock Scale or Unlock Scale on the selected model images in the atlas canvas context menu.
- Lock the current absolute scale. Keep unlocked images at 100% during layout on a page with locks.
- Keep scale independent of position and rotation. A scale lock does not pin the image to its previous position.
- Apply lock edits to the open document immediately. Write them to Turboism storage after a successful model save.
- Restore the saved locks by model GUID and image GUID when the model reopens.
- Paint a gray overlay and a native lock icon in the editor only. Do not include either in exported atlas pixels.
- Refuse layout with a visible message when the selected algorithm cannot preserve the locks. Do not switch algorithms automatically.

## Integration boundaries

Resolve stable identities before converting images to invocation-local IDs. Do not persist `native-item-N`, page indices, or display names.

Use the same lock snapshot for planning and validation. Validate absolute image scale before native writeback, not after the atlas changes.

Use the runtime-owned layout dispatch boundary for enforcement. It rejects partial placement and blocks native fallback when locks apply.

Rectangle planners receive bounds at the required scales. Polygon planners receive the scale through the existing per-item policy.

Use `CubismIcon.LOCK` through the existing UI resource resolver. The parameter lock plugin already uses this reference. See [native icon provenance](../../compatibility/cubism/native-lock-icon-provenance.md) for the verified theme and DPI variants.

Cubism 5.3.02 `impl.v.a(MouseEvent)` builds a context menu for the texture page list. It queries the page-list index and does not target the selected model images. Do not use that method for the canvas command.

## Acceptance

1. Adjust two skirts to 80%, select both, and lock their scales from the canvas context menu.
2. Confirm the selected images show the gray overlay and native lock. Confirm unlocked images do not.
3. Lay out the page. Check that both skirts remain at 80% and unadjusted images use 100%.
4. Repeat with a different supported algorithm. Confirm the same scales and a valid, non-overlapping layout.
5. Choose an unsupported algorithm. Check for a visible message and confirm the page remains unchanged.
6. Save and reopen the model. Verify the locks return, and unlock one skirt without changing the other.
7. Close the model and discard changes. Reopen it and verify that both saved locks return.
8. Export the texture. Verify that neither the overlay nor the icon appears in the output.
