package dev.turboism.plugin.mcp;

import dev.turboism.sdk.json.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;

/** Explicit bounded raster evidence, not semantic or artistic quality inference. */
final class McpRasterValidation {
    static final int MAX_SAMPLES = 4_194_304;

    private record Check(
            String id,
            String kind,
            int frame,
            Integer reference,
            McpRenderDomain.Crop region,
            BigDecimal minimum,
            BigDecimal maximum,
            int[] low,
            int[] high,
            int tolerance) {}

    static Map<String, Object> analyze(
            Map<String, Object> args, Map<String, Object> scan, List<Map<String, Object>> images) {
        fields(args, "operationId", "checks");
        if (!Objects.equals(args.get("operationId"), scan.get("operationId"))) throw fail("INVALID_ARGUMENT");
        if (!(args.get("checks") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 32)
            throw fail("INVALID_ARGUMENT");
        if (!(scan.get("frames") instanceof List<?> frames) || frames.size() > 9) throw fail("INVALID_RETAINED_RESULT");
        var byIndex = new HashMap<Integer, Map<String, Object>>();
        for (var value : frames) {
            var frame = map(value);
            if (byIndex.putIfAbsent(integer(frame.get("index"), 0, 8), frame) != null)
                throw fail("INVALID_RETAINED_RESULT");
        }
        var ids = new HashSet<String>();
        var checks = new ArrayList<Check>();
        var used = new TreeSet<Integer>();
        long samples = 0;
        for (var value : raw) {
            var check = map(value);
            String kind = text(check.get("kind"), 32);
            boolean difference = kind.equals("frame_difference");
            if (!difference && !kind.equals("color_fraction")) throw fail("INVALID_ARGUMENT");
            fields(
                    check,
                    difference
                            ? new String[] {
                                "checkId",
                                "kind",
                                "frameIndex",
                                "region",
                                "minimumFraction",
                                "maximumFraction",
                                "referenceFrameIndex",
                                "tolerance"
                            }
                            : new String[] {
                                "checkId",
                                "kind",
                                "frameIndex",
                                "region",
                                "minimumFraction",
                                "maximumFraction",
                                "minimumRgba",
                                "maximumRgba"
                            });
            String id = text(check.get("checkId"), 64);
            if (!ids.add(id)) throw fail("INVALID_ARGUMENT");
            int frameIndex = integer(check.get("frameIndex"), 0, 8);
            var frame = byIndex.get(frameIndex);
            if (frame == null) throw fail("FRAME_NOT_FOUND");
            int width = integer(frame.get("width"), 1, 1024), height = integer(frame.get("height"), 1, 1024);
            McpRenderDomain.Crop region;
            try {
                region = McpRenderDomain.crop(check.get("region"), width, height);
            } catch (RuntimeException e) {
                throw fail("INVALID_ARGUMENT");
            }
            samples += (long) region.width() * region.height();
            if (samples > MAX_SAMPLES) throw fail("BUDGET_EXCEEDED");
            var min = fraction(check.get("minimumFraction"));
            var max = fraction(check.get("maximumFraction"));
            if (min.compareTo(max) > 0) throw fail("INVALID_ARGUMENT");
            Integer reference = null;
            int[] low = null, high = null;
            int tolerance = 0;
            if (difference) {
                reference = integer(check.get("referenceFrameIndex"), 0, 8);
                var ref = byIndex.get(reference);
                if (ref == null) throw fail("FRAME_NOT_FOUND");
                for (String key : List.of("width", "height", "renderWidth", "renderHeight", "crop", "pixelCoordinates"))
                    if (!Objects.equals(frame.get(key), ref.get(key))) throw fail("COORDINATE_MISMATCH");
                tolerance = integer(check.get("tolerance"), 0, 255);
                used.add(reference);
            } else {
                low = rgba(check.get("minimumRgba"));
                high = rgba(check.get("maximumRgba"));
                for (int i = 0; i < 4; i++) if (low[i] > high[i]) throw fail("INVALID_ARGUMENT");
            }
            used.add(frameIndex);
            checks.add(new Check(id, kind, frameIndex, reference, region, min, max, low, high, tolerance));
        }
        long pixels = 0, bytes = 0;
        // All budgets and available-image identities are checked before any PNG decoding.
        for (int index : used) {
            var frame = byIndex.get(index);
            pixels += (long) integer(frame.get("width"), 1, 1024) * integer(frame.get("height"), 1, 1024);
            bytes += integer(frame.get("byteLength"), 1, McpRenderDomain.MAX_PNG_BYTES);
            int imageIndex = integer(frame.get("imageContentIndex"), 1, 9) - 1;
            if (imageIndex >= images.size()
                    || !"image/png".equals(images.get(imageIndex).get("mimeType")))
                throw fail("INVALID_RETAINED_RESULT");
        }
        if (pixels > McpRenderDomain.MAX_PIXELS || bytes > McpRenderDomain.MAX_PNG_BYTES) throw fail("BUDGET_EXCEEDED");
        var rasters = new HashMap<Integer, BufferedImage>();
        try {
            for (int index : used) {
                var frame = byIndex.get(index);
                rasters.put(index, decode(frame, images.get(integer(frame.get("imageContentIndex"), 1, 9) - 1)));
            }
            var results = new ArrayList<Map<String, Object>>();
            for (var check : checks) {
                var image = rasters.get(check.frame());
                var reference = check.reference() == null ? null : rasters.get(check.reference());
                long count = 0;
                for (int y = check.region().y();
                        y < check.region().y() + check.region().height();
                        y++)
                    for (int x = check.region().x();
                            x < check.region().x() + check.region().width();
                            x++) {
                        int pixel = image.getRGB(x, y);
                        if (reference == null
                                ? matches(pixel, check.low(), check.high())
                                : differs(pixel, reference.getRGB(x, y), check.tolerance())) count++;
                    }
                long total = (long) check.region().width() * check.region().height();
                // Compare exact counts to caller thresholds; rounding the returned fraction cannot alter pass/fail.
                var totalDecimal = BigDecimal.valueOf(total);
                boolean passed = BigDecimal.valueOf(count)
                                        .compareTo(check.minimum().multiply(totalDecimal))
                                >= 0
                        && BigDecimal.valueOf(count).compareTo(check.maximum().multiply(totalDecimal)) <= 0;
                var row = new LinkedHashMap<String, Object>();
                row.put("checkId", check.id());
                row.put("kind", check.kind());
                row.put("frameIndex", check.frame());
                row.put("referenceFrameIndex", check.reference());
                row.put("region", check.region().metadata());
                row.put("minimumFraction", check.minimum());
                row.put("maximumFraction", check.maximum());
                row.put(
                        "minimumRgba",
                        check.low() == null
                                ? null
                                : Arrays.stream(check.low()).boxed().toList());
                row.put(
                        "maximumRgba",
                        check.high() == null
                                ? null
                                : Arrays.stream(check.high()).boxed().toList());
                row.put("tolerance", check.reference() == null ? null : check.tolerance());
                row.put("matchingPixels", count);
                row.put("totalPixels", total);
                row.put("fraction", (double) count / total);
                row.put("passed", passed);
                results.add(Collections.unmodifiableMap(row));
            }
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", true);
            output.put("source", "RETAINED_POSE_GRID");
            output.put("operationId", scan.get("operationId"));
            output.put("scanOutcome", scan.get("outcome"));
            output.put("parametersRestored", scan.get("parametersRestored"));
            output.put("allRequestedPosesCaptured", frames.size() == integer(scan.get("requestedPoses"), 1, 9));
            output.put("analysisComplete", true);
            output.put("checksPassed", results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed"))));
            output.put("sampleCount", samples);
            output.put("checks", List.copyOf(results));
            output.put(
                    "frames",
                    used.stream()
                            .map(index -> {
                                var frame = new LinkedHashMap<>(byIndex.get(index));
                                frame.put("imageContentIndex", null);
                                return frame;
                            })
                            .toList());
            output.put(
                    "limitations",
                    List.of(
                            "EXPLICIT_PIXEL_THRESHOLDS_ONLY",
                            "NO_SEMANTIC_OR_ARTISTIC_ACCEPTANCE",
                            "RETAINED_IMAGES_NOT_CURRENT_MODEL_FRESHNESS",
                            "NO_REGISTRATION_OR_RESAMPLING"));
            if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length > McpPoseGridDomain.MAX_METADATA_BYTES)
                throw fail("BUDGET_EXCEEDED");
            return output;
        } finally {
            rasters.values().forEach(BufferedImage::flush);
        }
    }

    private static boolean matches(int pixel, int[] low, int[] high) {
        for (int i = 0; i < 4; i++) {
            int shift = i == 3 ? 24 : (2 - i) * 8;
            int value = pixel >>> shift & 255;
            if (value < low[i] || value > high[i]) return false;
        }
        return true;
    }

    private static boolean differs(int a, int b, int tolerance) {
        int aa = a >>> 24, ba = b >>> 24;
        if (Math.abs(aa - ba) > tolerance) return true;
        // Integer products preserve exact comparison of premultiplied byte differences.
        for (int shift = 16; shift >= 0; shift -= 8)
            if (Math.abs(((a >>> shift) & 255) * aa - ((b >>> shift) & 255) * ba) > tolerance * 255) return true;
        return false;
    }

    private static BufferedImage decode(Map<String, Object> frame, Map<String, Object> image) {
        try {
            if (!"image".equals(image.get("type"))
                    || !(image.get("data") instanceof String data)
                    || data.length() > 4 * ((McpRenderDomain.MAX_PNG_BYTES + 2) / 3))
                throw fail("INVALID_RETAINED_RESULT");
            byte[] png = Base64.getDecoder().decode(data);
            if (png.length != integer(frame.get("byteLength"), 1, McpRenderDomain.MAX_PNG_BYTES)
                    || !HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(png))
                            .equals(frame.get("sha256"))) throw fail("INVALID_RETAINED_RESULT");
            // Memory-backed stream; do not create ImageIO disk-cache files.
            try (var stream = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
                var readers = ImageIO.getImageReadersByFormatName("png");
                if (!readers.hasNext()) throw fail("UNAVAILABLE");
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    if (reader.getWidth(0) != integer(frame.get("width"), 1, 1024)
                            || reader.getHeight(0) != integer(frame.get("height"), 1, 1024))
                        throw fail("INVALID_RETAINED_RESULT");
                    return reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
        } catch (Failure e) {
            throw e;
        } catch (Exception e) {
            throw fail("INVALID_RETAINED_RESULT");
        }
    }

    private static int[] rgba(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() != 4) throw fail("INVALID_ARGUMENT");
        return values.stream().mapToInt(v -> integer(v, 0, 255)).toArray();
    }

    private static BigDecimal fraction(Object raw) {
        if (!(raw instanceof Number n)) throw fail("INVALID_ARGUMENT");
        try {
            var value = new BigDecimal(n.toString());
            if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) throw fail("INVALID_ARGUMENT");
            return value;
        } catch (NumberFormatException e) {
            throw fail("INVALID_ARGUMENT");
        }
    }

    private static int integer(Object raw, int min, int max) {
        if (!(raw instanceof Number n)) throw fail("INVALID_ARGUMENT");
        try {
            int value = new BigDecimal(n.toString()).intValueExact();
            if (value < min || value > max) throw fail("INVALID_ARGUMENT");
            return value;
        } catch (NumberFormatException | ArithmeticException e) {
            throw fail("INVALID_ARGUMENT");
        }
    }

    private static String text(Object raw, int max) {
        if (!(raw instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw fail("INVALID_ARGUMENT");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw fail("INVALID_ARGUMENT");
        return (Map<String, Object>) m;
    }

    private static void fields(Map<String, Object> args, String... keys) {
        if (!args.keySet().equals(Set.of(keys))) throw fail("INVALID_ARGUMENT");
    }

    static Failure fail(String code) {
        return new Failure(code);
    }

    static final class Failure extends RuntimeException {
        final String code;

        Failure(String code) {
            super(code);
            this.code = code;
        }
    }
}
