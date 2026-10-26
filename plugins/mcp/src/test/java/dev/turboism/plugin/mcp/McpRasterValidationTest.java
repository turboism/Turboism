package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class McpRasterValidationTest {
    @Test
    void colorBoundsIncludeAlphaAndReportExactCountsThresholdsAndHistoricalProvenance() throws Exception {
        var f = new Fixture(2, 2, new int[] {0xffffffff, 0x00ffffff, 0xff000000, 0xff808080});
        var check = color("white", 0, 2, 2);
        var r = f.run(List.of(check));
        assertEquals(true, r.get("ok"));
        assertEquals(true, r.get("checksPassed"));
        var row = row(r, 0);
        assertEquals(1L, row.get("matchingPixels"));
        assertEquals(4L, row.get("totalPixels"));
        assertEquals(.25, row.get("fraction"));
        assertEquals(check.get("minimumRgba"), row.get("minimumRgba"));
        assertEquals("RETAINED_POSE_GRID", r.get("source"));
        var source = (Map<?, ?>) ((List<?>) r.get("frames")).get(0);
        assertEquals("capture-0", source.get("captureId"));
        assertEquals("pose-0", source.get("label"));
        assertNull(source.get("imageContentIndex"));
        assertFalse(r.toString().contains("base64"));
        check.put("minimumFraction", .5);
        r = f.run(List.of(check));
        assertEquals(true, r.get("ok"));
        assertEquals(false, r.get("checksPassed"));
        check.put("region", Map.of("x", 1, "y", 0, "width", 1, "height", 2));
        check.put("minimumRgba", List.of(0, 0, 0, 0));
        check.put("maximumRgba", List.of(255, 255, 255, 0));
        r = f.run(List.of(check));
        assertEquals(1L, row(r, 0).get("matchingPixels"));
        assertEquals(.5, row(r, 0).get("fraction"));
    }

    @Test
    void frameDifferenceUsesPremultipliedChannelsStrictToleranceAndNoHiddenTransparentRgb() throws Exception {
        var f = new Fixture(
                3, 1, new int[] {0x00ff0000, 0xffffffff, 0x80ff0000}, new int[] {0x000000ff, 0xfffefefe, 0x80000000});
        var c = difference(0, 1, 3, 1, 127);
        assertEquals(1L, row(f.run(List.of(c)), 0).get("matchingPixels"));
        c.put("tolerance", 128);
        assertEquals(0L, row(f.run(List.of(c)), 0).get("matchingPixels"));
        c.put("tolerance", 0);
        assertEquals(2L, row(f.run(List.of(c)), 0).get("matchingPixels"));
        c.put("region", Map.of("x", 0, "y", 0, "width", 1, "height", 1));
        assertEquals(0L, row(f.run(List.of(c)), 0).get("matchingPixels"));
        c.put("referenceFrameIndex", 0);
        assertEquals(0L, row(f.run(List.of(c)), 0).get("matchingPixels"));
        var alpha = new Fixture(1, 1, new int[] {0x40000000}, new int[] {0x80000000});
        assertEquals(1L, row(alpha.run(List.of(difference(0, 1, 1, 1, 63))), 0).get("matchingPixels"));
        assertEquals(0L, row(alpha.run(List.of(difference(0, 1, 1, 1, 64))), 0).get("matchingPixels"));
    }

    @Test
    void fractionComparisonsAreExactAndPartialScanIsNeverReportedAsFullCaptureOrRestored() throws Exception {
        var f = new Fixture(1, 1, new int[] {0xffffffff});
        f.scan.put("requestedPoses", 2);
        f.scan.put("outcome", "FAILED");
        f.scan.put("parametersRestored", false);
        var c = color("check", 0, 1, 1);
        c.put("minimumFraction", new BigDecimal("1.000000000000000000001"));
        error("INVALID_ARGUMENT", () -> f.run(List.of(c)));
        c.put("minimumFraction", 1);
        c.put("maximumFraction", 1);
        var r = f.run(List.of(c));
        assertEquals(true, r.get("checksPassed"));
        assertEquals(false, r.get("allRequestedPosesCaptured"));
        assertEquals(false, r.get("parametersRestored"));
        assertEquals("FAILED", r.get("scanOutcome"));
        c.put("minimumFraction", new BigDecimal("-1e-999"));
        error("INVALID_ARGUMENT", () -> f.run(List.of(c)));
        c.put("minimumFraction", 0);
        c.put("maximumFraction", new BigDecimal("0.99999999999999999999"));
        assertEquals(false, f.run(List.of(c)).get("checksPassed"));
    }

    @Test
    void missingFramesCoordinateMismatchAndMalformedInputsFailClosed() throws Exception {
        var f = new Fixture(2, 2, new int[4], new int[4]);
        var c = color("a", 8, 2, 2);
        error("FRAME_NOT_FOUND", () -> f.run(List.of(c)));
        c.put("frameIndex", 0);
        c.put("unexpected", true);
        error("INVALID_ARGUMENT", () -> f.run(List.of(c)));
        c.remove("unexpected");
        error("INVALID_ARGUMENT", () -> f.run(List.of(c, c)));
        c.put("region", Map.of("x", 1, "y", 1, "width", 2, "height", 2));
        error("INVALID_ARGUMENT", () -> f.run(List.of(c)));
        c.put("region", Map.of("x", 0, "y", 0, "width", 2, "height", 2));
        c.put("minimumRgba", List.of(256, 0, 0, 0));
        error("INVALID_ARGUMENT", () -> f.run(List.of(c)));
        f.frames.get(1).put("crop", Map.of("x", 1, "y", 0, "width", 2, "height", 2));
        error("COORDINATE_MISMATCH", () -> f.run(List.of(difference(0, 1, 2, 2, 0))));
    }

    @Test
    void sampleAndDecodeBudgetsRejectBeforeTryingToDecode() throws Exception {
        var f = new Fixture(1, 1, new int[1], new int[1]);
        for (var frame : f.frames) {
            frame.put("width", 1024);
            frame.put("height", 1024);
        }
        var checks = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5; i++) checks.add(color("c" + i, 0, 1024, 1024));
        f.images.clear(); // No decoder can run: the sample budget is checked first.
        error("BUDGET_EXCEEDED", () -> f.run(checks));
        f.images.add(Map.of("mimeType", "image/png"));
        f.images.add(Map.of("mimeType", "image/png"));
        error("BUDGET_EXCEEDED", () -> f.run(List.of(color("a", 0, 1, 1), color("b", 1, 1, 1))));
        error("INVALID_ARGUMENT", () -> f.run(Collections.nCopies(33, color("a", 0, 1, 1))));
    }

    @Test
    void corruptionHashByteLengthAndActualPngDimensionsCannotBecomeEvidence() throws Exception {
        for (String field : List.of("sha256", "byteLength", "width")) {
            var f = new Fixture(2, 2, new int[4]);
            f.frames.get(0).put(field, field.equals("sha256") ? "0".repeat(64) : field.equals("width") ? 3 : 1);
            error("INVALID_RETAINED_RESULT", () -> f.run(List.of(color("a", 0, 1, 1))));
        }
        var f = new Fixture(1, 1, new int[1]);
        f.images.set(0, Map.of("type", "image", "mimeType", "image/png", "data", "broken"));
        error("INVALID_RETAINED_RESULT", () -> f.run(List.of(color("a", 0, 1, 1))));
    }

    static Map<String, Object> color(String id, int frame, int width, int height) {
        return new LinkedHashMap<>(Map.of(
                "checkId",
                id,
                "kind",
                "color_fraction",
                "frameIndex",
                frame,
                "region",
                Map.of("x", 0, "y", 0, "width", width, "height", height),
                "minimumRgba",
                List.of(255, 255, 255, 255),
                "maximumRgba",
                List.of(255, 255, 255, 255),
                "minimumFraction",
                0,
                "maximumFraction",
                1));
    }

    static Map<String, Object> difference(int frame, int reference, int width, int height, int tolerance) {
        return new LinkedHashMap<>(Map.of(
                "checkId",
                "difference",
                "kind",
                "frame_difference",
                "frameIndex",
                frame,
                "referenceFrameIndex",
                reference,
                "tolerance",
                tolerance,
                "region",
                Map.of("x", 0, "y", 0, "width", width, "height", height),
                "minimumFraction",
                0,
                "maximumFraction",
                1));
    }

    private static Map<?, ?> row(Map<String, Object> result, int i) {
        return (Map<?, ?>) ((List<?>) result.get("checks")).get(i);
    }

    private static void error(String code, Runnable work) {
        assertEquals(code, assertThrows(McpRasterValidation.Failure.class, work::run).code);
    }

    private static final class Fixture {
        final List<Map<String, Object>> frames = new ArrayList<>(), images = new ArrayList<>();
        final Map<String, Object> scan = new LinkedHashMap<>();

        Fixture(int width, int height, int[]... arrays) throws Exception {
            int index = 0;
            for (var argb : arrays) {
                var raster = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                raster.setRGB(0, 0, width, height, argb, 0, width);
                var out = new ByteArrayOutputStream();
                ImageIO.write(raster, "png", out);
                raster.flush();
                byte[] png = out.toByteArray();
                var frame = new LinkedHashMap<String, Object>();
                frame.put("index", index);
                frame.put("label", "pose-" + index);
                frame.put("captureId", "capture-" + index);
                frame.put("imageContentIndex", ++index);
                frame.put(
                        "sha256",
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(png)));
                frame.put("byteLength", png.length);
                frame.put("width", width);
                frame.put("height", height);
                frame.put("renderWidth", width);
                frame.put("renderHeight", height);
                frame.put("crop", Map.of("x", 0, "y", 0, "width", width, "height", height));
                frame.put("pixelCoordinates", "TOP_LEFT");
                frames.add(frame);
                images.add(Map.of(
                        "type",
                        "image",
                        "mimeType",
                        "image/png",
                        "data",
                        Base64.getEncoder().encodeToString(png)));
            }
            scan.putAll(Map.of(
                    "operationId",
                    "scan",
                    "outcome",
                    "COMPLETED",
                    "parametersRestored",
                    true,
                    "requestedPoses",
                    arrays.length,
                    "frames",
                    frames));
        }

        Map<String, Object> run(List<Map<String, Object>> checks) {
            return McpRasterValidation.analyze(Map.of("operationId", "scan", "checks", checks), scan, images);
        }
    }
}
