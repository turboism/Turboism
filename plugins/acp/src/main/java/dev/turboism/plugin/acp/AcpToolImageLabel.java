package dev.turboism.plugin.acp;

import dev.turboism.sdk.json.Json;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Bounded tool-reported render provenance for a thumbnail; never infers current pose or freshness. */
final class AcpToolImageLabel {
    static final int MAX_METADATA_CHARS = 256 * 1024;

    private AcpToolImageLabel() {}

    static String label(String toolId, List<?> contents, int imageIndex) {
        return label(toolId, contents, imageIndex, java.util.function.UnaryOperator.identity());
    }

    static String label(
            String toolId, List<?> contents, int imageIndex, java.util.function.UnaryOperator<String> redact) {
        String fallback = "Tool image · " + scalar(toolId, 64, redact);
        if (contents.isEmpty() || contents.size() > 64 || imageIndex < 0 || imageIndex >= contents.size())
            return fallback;
        var first = block(contents.get(0));
        if (!"text".equals(first.get("type"))
                || !(first.get("text") instanceof String json)
                || json.length() > MAX_METADATA_CHARS) return fallback;
        try {
            var metadata = Json.parseObject(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<?, ?> capture = metadata;
            String pose = "";
            if (metadata.get("frames") instanceof List<?> frames) {
                if (frames.size() > 9) return fallback;
                var used = new java.util.HashSet<Integer>();
                Map<?, ?> matched = null;
                for (var raw : frames) {
                    if (!(raw instanceof Map<?, ?> frame)) return fallback;
                    Object index = frame.get("imageContentIndex");
                    if (index == null) continue;
                    int value = integer(index);
                    if (value < 1
                            || value >= contents.size()
                            || !used.add(value)
                            || !isImage(block(contents.get(value)))) return fallback;
                    if (value == imageIndex) matched = frame;
                }
                if (matched == null) return fallback;
                capture = matched;
                pose = scalar(capture.get("label"), 64, redact);
            } else {
                if (!Boolean.TRUE.equals(metadata.get("ok")) || imageIndex != 1) return fallback;
                int images = 0;
                for (var content : contents) if (isImage(block(content))) images++;
                if (images != 1) return fallback;
            }
            if (!(capture.get("state") instanceof Map<?, ?> state)) return fallback;
            String id = scalar(capture.get("captureId"), 64, redact),
                    document = scalar(state.get("documentId"), 64, redact),
                    model = scalar(state.get("modelId"), 64, redact);
            long revision = revision(state.get("historyRevision"));
            if (id.isEmpty() || document.isEmpty() || model.isEmpty() || revision < 0) return fallback;
            String result = fallback + " · reported: " + (pose.isEmpty() ? "" : "pose=" + pose + " · ") + "capture="
                    + id + " · doc=" + document + " · model=" + model + " · revision=" + revision;
            int width = integer(capture.get("width")), height = integer(capture.get("height"));
            if (width > 0 && width <= 1024 && height > 0 && height <= 1024) result += " · " + width + "×" + height;
            if (capture.get("crop") instanceof Map<?, ?> crop) {
                int x = integer(crop.get("x")),
                        y = integer(crop.get("y")),
                        w = integer(crop.get("width")),
                        h = integer(crop.get("height")),
                        rw = integer(capture.get("renderWidth")),
                        rh = integer(capture.get("renderHeight"));
                if (x >= 0
                        && y >= 0
                        && w == width
                        && h == height
                        && w > 0
                        && h > 0
                        && rw > 0
                        && rh > 0
                        && rw <= 1024
                        && rh <= 1024
                        && (long) x + w <= rw
                        && (long) y + h <= rh) result += " · crop=(" + x + "," + y + ") of " + rw + "×" + rh;
            }
            return result;
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    private static Map<?, ?> block(Object value) {
        if (value instanceof Map<?, ?> wrapper && wrapper.get("content") instanceof Map<?, ?> content) return content;
        return Map.of();
    }

    private static boolean isImage(Map<?, ?> block) {
        if ("image".equals(block.get("type"))) return true;
        return "resource".equals(block.get("type"))
                && block.get("resource") instanceof Map<?, ?> resource
                && resource.get("mimeType") instanceof String mime
                && mime.startsWith("image/")
                && resource.get("blob") instanceof String;
    }

    private static String scalar(Object raw, int max, java.util.function.UnaryOperator<String> redact) {
        if (!(raw instanceof String value)) return "";
        value = redact.apply(value);
        var result = new StringBuilder();
        for (int i = 0; i < value.length() && i < max; i++) {
            char c = value.charAt(i);
            result.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (value.length() > max) result.append('…');
        return result.toString();
    }

    private static int integer(Object raw) {
        if (!(raw instanceof Number value)) return -1;
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            return -1;
        }
    }

    private static long revision(Object raw) {
        if (!(raw instanceof Number value)) return -1;
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            return -1;
        }
    }
}
