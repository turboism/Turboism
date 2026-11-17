# Geometry500 Real-Host Layout

Input: `test-assets/texture-atlas-layout/cmo3/geometry/atlas_mapping_geometry_500.cmo3`

SHA-256: `99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b`

Host Cubism 5.3.03; plugin Dalsoo Polygon; 8192²; 3px spacing; mesh mode; automatic scale; rotation allowed.

- Job: `ebe50be7-bed3-49f2-87b4-372cad22ec26`
- Run: `queue-f3bcfc7ccb8f461aac2b2e2281df364c`
- Prepared: `39d953d47094f838697e2fa599fbc545e94440985e2308ff645fc167c04bd93b`
- Algorithm time: 12465.3775 ms; UI time: 12853.8949 ms. Both are single-run timings with the probe attached.
- The output holds 500 items at scale 1, with overlaps=0, overflow=[], and finite/inside/layerMatch=true.

`layout-before.png` and `layout-after.png` are direct niri screenshots of the "edit texture set" window for this task. `comparison.png` only crops the shared canvas region of both images and joins them side by side; it performs no re-layout, redraw, or texture generation.

The result shows a ramp on the left and a large empty area on the right. Passing the geometry check does not mean the layout reached the compact shape a user expects. The corpus holds textures with complex appearance, but the extracted outline this run used was 500 rings and 2000 vertices, so it does not represent the complex outline load of `heavy.cmo3`. `heavy.cmo3` did not run in this round.

Two earlier capture interfaces produced a black screen; switching to niri produced visible screenshots. The repeated run corrects the screenshot and is not a speed comparison.

The memory file records the whole host heap. One completed run cannot rule out a long-term host leak. See the parent `KERNEL-ALLOCATION.md` for the kernel allocation optimization and its separate regression evidence.

The screenshots came from a temporary validation probe pinned by the prepared snapshot above. The screenshot wait logic was removed from the source after capture so routine validation gains no extra wait.

The committed queue record replaces the local user directory with `<USER_HOME>`; identifiers and hashes keep their original values.
