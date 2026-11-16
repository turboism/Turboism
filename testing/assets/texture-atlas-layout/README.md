# Texture Atlas Layout Test Assets

## Purpose

Validate texture atlas current-page auto-layout: native/new/legacy performance comparison, fixed and automatic scaling, rotation, region parallelism, overflow, undo/redo, and save-then-reopen.

## License and Distribution

- **License**: **Creative Commons Zero v1.0 Universal (CC0 1.0)**. The user confirmed this authorization explicitly, and it supersedes the earlier "current test only, no redistribution" note in historical documents.
- **Binary distribution**: the 17 binary model and project files in this directory (9 CMO3 and 8 PSD, 123,517,686 bytes total) are **never committed to Git**. They publish as versioned GitHub Release assets and are managed by the `testing/assets/corpus.json` manifest at the repository root.
- **How to obtain them**:
  ```sh
  # Fetch and map into the local testing/assets/texture-atlas-layout/ directory
  python3 scripts/test_corpus.py fetch texture-atlas-layout

  # Verify local file integrity and hashes
  python3 scripts/test_corpus.py verify texture-atlas-layout
  ```
- **Model reproduction and build instructions**: see [BUILDING.md](BUILDING.md).

## File Categories

- `psd/`: PSD copies used to import and build the test models (fetched locally with `fetch`, then symlinked or copied; Git-ignored).
- `cmo3/`: `.cmo3` model copies that open directly in Cubism Editor (fetched locally with `fetch`, then symlinked or copied; Git-ignored).

## Test Conventions

1. Files in this directory are input samples. Before any write operation such as layout or save, create a separate run copy and never overwrite the input sample.
2. Record sample provenance and license, Cubism version, initial current page, page size, scale, rotation, and margin settings.
3. Start every algorithm comparison round from the same input copy. Never feed the previous round's layout result into the next round.
4. Git ignore rules exclude the whole `psd/` and `cmo3/` content of this directory. Never commit a binary model file.

Host acceptance protocol: [`HOST-CHECKLIST.md`](../../../validation/texture-atlas-current-page/HOST-CHECKLIST.md).

## Sample Registry

The full corpus holds the Circle and Geometry model and PSD sets: 17 files, 123,517,686 bytes (9 CMO3, 8 PSD). Provenance and newly added files are in [atlas-manifest.md](atlas-manifest.md).

**The two sets distribute differently (user-provided note): every Circle model contains same-size circles, while Geometry holds geometric images of differing size and shape.** Circle leans on a uniform-size baseline; Geometry exercises inputs with differing sizes and aspect ratios. Report the two separately and never extrapolate the Circle speedup to Geometry.

| Case | PSD | CMO3 | License | Focus |
|---|---|---|---|---|
| circle-100 | `psd/circle/atlas_mapping_100.psd` | `cmo3/circle/atlas_mapping_100.cmo3` | CC0-1.0 | Small-scale baseline |
| circle-500 | `psd/circle/atlas_mapping_500.psd` | `cmo3/circle/atlas_mapping_500.cmo3` | CC0-1.0 | Medium-scale comparison |
| circle-1000 | `psd/circle/atlas_mapping_1000.psd` | `cmo3/circle/atlas_mapping_1000.cmo3` | CC0-1.0 | Large-scale performance |
| circle-2500 | `psd/circle/atlas_mapping_2500.psd` | `cmo3/circle/atlas_mapping_2500.cmo3` | CC0-1.0 | Heavy-load stress test |
| geometry-100 | `psd/geometry/atlas_mapping_geometry_100.psd` | `cmo3/geometry/atlas_mapping_geometry_100.cmo3` | CC0-1.0 | Irregular geometry small-scale baseline |
| geometry-500 | `psd/geometry/atlas_mapping_geometry_500.psd` | `cmo3/geometry/atlas_mapping_geometry_500.cmo3` | CC0-1.0 | Irregular geometry medium-scale comparison |
| geometry-1000 | `psd/geometry/atlas_mapping_geometry_1000.psd` | `cmo3/geometry/atlas_mapping_geometry_1000.cmo3` | CC0-1.0 | Irregular geometry large-scale performance |
| geometry-2500 | `psd/geometry/atlas_mapping_geometry_2500.psd` | `cmo3/geometry/atlas_mapping_geometry_2500.cmo3` | CC0-1.0 | Irregular geometry heavy-load stress test |
| geometry-500x2 | *(based on geometry-500)* | `cmo3/geometry/atlas_mapping_geometry_500x2.cmo3` | CC0-1.0 | Two-page multi-texture layout project |

All four Circle PSD files are 4096×4096, 8-bit RGB (4 channels). Reading the PSD structure directly confirmed layer records of 100, 500, 1000, and 2500. **The PSD layer count is not the input count of one native current-page invocation**; measure that on the host. The Circle input asset manifest is in [circle-manifest.md](circle-manifest.md).

All four Geometry PSD files are 2048×2048, 8-bit RGB (4 channels) and hold irregular geometric shapes. See [geometry-manifest.md](geometry-manifest.md).
