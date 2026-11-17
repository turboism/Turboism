# Texture Atlas Test Model Build and Reproduction Notes (BUILDING.md)

This document records the known specification facts, generation logic, and reproduction notes for the `texture-atlas-layout` corpus (Circle and Geometry series, 9 CMO3 and 8 PSD files).

---

## 1. Design Goals and Background

The corpus measures the efficiency, space utilization, and stability of the Live2D Cubism Editor texture atlas current-page layout (Texture Atlas Auto-Layout) algorithm:

- **Circle series**: same-size circular geometry. Establishes a performance baseline under uniform size and regular boundaries, which removes shape and size variation from layout complexity.
- **Geometry series**: geometry of differing size, shape, and aspect ratio. Exercises varying sizes, rotation fitting, and fragment space utilization in realistic complex projects.
- **500x2 variant**: a two-page, high-density configuration that exercises multi-texture-page layout and boundary behaviour.

Every binary asset uses the **CC0 1.0** open license and is fetched with `scripts/test_corpus.py fetch texture-atlas-layout`.

---

## 2. Known Facts and Technical Specifications

### 2.1 File Header and Format Traits

- **CMO3 models**: the file header magic is `CAFF` (Live2D Cubism model project format).
- **PSD files**: the file header magic is `8BPS`, version 1 (standard Photoshop format), RGB colour mode, 8-bit depth, 4 channels.

### 2.2 Circle Series Specifications

| Model/PSD | PSD canvas | Colour channels | PSD layer records | CMO3 file size |
|---|---|---|---:|---:|
| `circle_100` | 4096×4096 | 8-bit RGB (4 channels) | 100 | 850,522 bytes |
| `circle_500` | 4096×4096 | 8-bit RGB (4 channels) | 500 | 3,538,094 bytes |
| `circle_1000` | 4096×4096 | 8-bit RGB (4 channels) | 1,000 | 7,731,906 bytes |
| `circle_2500` | 4096×4096 | 8-bit RGB (4 channels) | 2,500 | 18,691,242 bytes |

- **Shape traits**: every layer holds one solid circle of identical size.
- **Input count and texture pages**: the PSD layer count is not the input count that a native Cubism Editor current-page layout invocation receives. On a real host, mesh generation state and the atlas current-page selection change the actual layout input (host measurements are in `validation/texture-atlas-current-page/HOST-AB.md`).

### 2.3 Geometry Series Specifications

| Model/PSD | PSD canvas | Colour channels | PSD layer records | CMO3 file size |
|---|---|---|---:|---:|
| `geometry_100` | 2048×2048 | 8-bit RGB (4 channels) | 100 | 694,856 bytes |
| `geometry_500` | 2048×2048 | 8-bit RGB (4 channels) | 500 | 3,564,178 bytes |
| `geometry_1000` | 2048×2048 | 8-bit RGB (4 channels) | 1,000 | 6,701,324 bytes |
| `geometry_2500` | 2048×2048 | 8-bit RGB (4 channels) | 2,500 | 24,581,494 bytes |
| `geometry_500x2` | *(based on 500)* | - | - | 6,978,687 bytes |

- **Shape traits**: layer content holds geometric shapes of differing size, shape, and aspect ratio.
- **`geometry_500x2` variant**: a single project with two texture pages, or a doubled layout model image set, used to compare across texture pages and under extreme load.

---

## 3. Unknown Steps Awaiting the Author

To stay rigorous and truthful, the generation parameters that no existing document records are marked **awaiting the author**. Never guess them.

1. **PSD generation method**:
   - Whether the original PSD layers came from an automation script (Photoshop ExtendScript, Python/Pillow, ImageMagick, or similar) or from a manual design-tool export: **awaiting the author**.
   - The layout algorithm, random seed, colour fill rules, and layer naming rules for the circles and geometric shapes: **awaiting the author**.
2. **Cubism Editor import and project generation**:
   - The mesh generation options used when importing the PSD into Cubism Editor (fine, standard, or simple mesh density; polygon subdivision parameters): **awaiting the author**.
   - The initial settings when the texture atlas editor dialog opened (initial atlas size, margin, fixed scale, and similar): **awaiting the author**.
   - The exact steps and page assignment state that created the multiple texture pages in the `geometry_500x2` variant: **awaiting the author**.

---

## 4. Appendix: Reference Notes for Generating a Similar PSD Structure

When the original environment is unavailable and a structurally similar test PSD is needed (multiple layers, independent transparent shapes), use a standard PSD structure writer.

> **Important**: a self-generated PSD always differs from the original in compression format, metadata, layer record byte stream, and random distribution, so its SHA-256 hash **cannot match** the official baseline. Baseline tests and automation gates must use the official CC0 archive through `scripts/test_corpus.py fetch`.

### Deterministic Structure Skeleton (Reference Only)

```python
# Conceptual example: build a multi-layer image of one shape family with a standard graphics library.
# Note: this skeleton only shows the idea for a similar test PSD.
def create_circle_test_spec(count: int, canvas_size: int = 4096):
    """
    Known specification:
    - Canvas: canvas_size x canvas_size, RGBA, 8-bit
    - Layers: count (100 / 500 / 1000 / 2500)
    - Layer content: one equal-radius circle per layer, spread across the canvas
    """
    return {
        "canvas_width": canvas_size,
        "canvas_height": canvas_size,
        "layer_count": count,
        "shape_type": "uniform_circle",
    }
```
