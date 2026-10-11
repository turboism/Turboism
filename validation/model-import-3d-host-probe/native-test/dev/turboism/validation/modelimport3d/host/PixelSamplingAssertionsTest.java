package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.CubismRenderedPixels;

/** T008 offline fixture: oracle logic on synthetic frames; never host evidence. */
final class PixelSamplingAssertionsTest {

    private static int checks;

    static void run() throws Exception {
        // Material is deterministic, correctly sized and quadrant-marked.
        var png = PixelSamplingProbe.materialPng();
        var png2 = PixelSamplingProbe.materialPng();
        check(java.util.Arrays.equals(png, png2), "material not deterministic");
        check(png.length > 100, "material implausibly small");
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        check(image.getWidth() == 256 && image.getHeight() == 128, "material dimensions");
        check(image.getRGB(40, 30) == 0xFF101010, "marker shaft pixel");
        check(image.getRGB(16, 16) == 0xFFE23B3B, "TL quadrant pixel");
        check(image.getRGB(200, 100) == 0xFFF0C832, "BR quadrant pixel");

        // Frozen geometry: 4 canvas-document verts, full-coverage UVs, 6 indices.
        var g = PixelSamplingProbe.geometry();
        check(g.positions().size() == 4 && g.uvs().size() == 4
            && g.triangleIndices().equals(java.util.List.of(0, 1, 2, 0, 2, 3)), "geometry");
        check(g.positions().equals(java.util.List.of(
            new dev.turboism.sdk.cubism.model.Point2(256, 384),
            new dev.turboism.sdk.cubism.model.Point2(768, 384),
            new dev.turboism.sdk.cubism.model.Point2(768, 640),
            new dev.turboism.sdk.cubism.model.Point2(256, 640))),
            "frozen canvas rect");

        // A frame built exactly per the frozen mapping passes all 15 patches.
        var bound = render(0);
        check(PixelSamplingProbe.patchFailures(bound, 0).isEmpty(), "positive patches p0");
        check(PixelSamplingProbe.outsideAlphaZero(bound, 0), "outside alpha p0");
        check(PixelSamplingProbe.patchFailures(render(80), 80).isEmpty(), "shifted patches +80");
        check(PixelSamplingProbe.outsideAlphaZero(render(80), 80), "outside alpha +80");
        check(PixelSamplingProbe.patchFailures(render(-80), -80).isEmpty(), "shifted patches -80");

        // Mutations must be rejected.
        check(!PixelSamplingProbe.patchFailures(bound, 80).isEmpty(), "stale at +80 accepted");
        check(!PixelSamplingProbe.patchFailures(render(80), 0).isEmpty(), "shifted at 0 accepted");
        var mirrored = mirrored();
        check(!PixelSamplingProbe.patchFailures(mirrored, 0).isEmpty(), "mirrored-U accepted");
        var wrongDim = new CubismRenderedPixels(512, 512, new int[512 * 512]);
        check(PixelSamplingProbe.patchFailures(wrongDim, 0).contains("output-dimensions"),
            "dimensions not rejected");
        check(PixelSamplingProbe.allTransparent(render(0, true)), "blank frame");
        check(!PixelSamplingProbe.allTransparent(bound), "bound frame wrongly transparent");
        System.out.println("T008 OFFLINE_FIXTURE checks=" + checks + " PASS; hostExecuted=false");
    }

    /** Synthetic frame per the frozen mapping; not the expected-value source. */
    private static CubismRenderedPixels render(int shift, boolean blank) {
        var argb = new int[1024 * 1024];
        if (!blank) {
            int[][] quads = {
                {0, 0, 128, 64, 0xFFE23B3B}, {128, 0, 256, 64, 0xFF3BB25A},
                {0, 64, 128, 128, 0xFF3B5CE2}, {128, 64, 256, 128, 0xFFF0C832}};
            for (int[] q : quads) {
                for (int y = q[1]; y < q[3]; y++) for (int x = q[0]; x < q[2]; x++) {
                    plot(argb, x, y, shift, q[4]);
                }
            }
            for (int y = 24; y <= 36; y++) for (int x = 28; x <= 64; x++) {
                plot(argb, x, y, shift, 0xFF101010);
            }
            for (int y = 12; y <= 48; y++) {
                int x1 = 88 - (int) Math.round(24.0 * Math.abs(y - 30) / 18.0);
                for (int x = 64; x <= x1; x++) plot(argb, x, y, shift, 0xFF101010);
            }
        }
        return new CubismRenderedPixels(1024, 1024, argb);
    }

    private static CubismRenderedPixels render(int shift) { return render(shift, false); }

    private static void plot(int[] argb, int sx, int sy, int shift, int color) {
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            int x = 256 + 2 * sx + dx + shift, y = 384 + 2 * sy + dy;
            argb[y * 1024 + x] = color;
        }
    }

    /** U-mirrored variant: quadrants swapped left-right. */
    private static CubismRenderedPixels mirrored() {
        var argb = new int[1024 * 1024];
        int[][] quads = {
            {0, 0, 128, 64, 0xFF3BB25A}, {128, 0, 256, 64, 0xFFE23B3B},
            {0, 64, 128, 128, 0xFFF0C832}, {128, 64, 256, 128, 0xFF3B5CE2}};
        for (int[] q : quads) {
            for (int y = q[1]; y < q[3]; y++) for (int x = q[0]; x < q[2]; x++) {
                plot(argb, x, y, 0, q[4]);
            }
        }
        for (int y = 24; y <= 36; y++) for (int x = 28; x <= 64; x++) {
            plot(argb, x, y, 0, 0xFF101010);
        }
        return new CubismRenderedPixels(1024, 1024, argb);
    }

    private static void check(boolean value, String message) {
        NativeAssertions.require(value, message);
        checks++;
    }
}
