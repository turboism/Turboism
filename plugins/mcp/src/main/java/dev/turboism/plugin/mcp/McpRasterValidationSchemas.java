package dev.turboism.plugin.mcp;

import java.util.*;

final class McpRasterValidationSchemas {
    static final String TOOL = "turboism.validation.raster";

    static Map<String, Object> definition() {
        var common = Map.<String, Object>of(
                "checkId",
                text(64),
                "frameIndex",
                integer(0, 8),
                "region",
                McpRenderDomain.cropSchema(),
                "minimumFraction",
                fraction(),
                "maximumFraction",
                fraction());
        var color = new LinkedHashMap<>(common);
        color.put("kind", Map.of("const", "color_fraction"));
        var rgba = Map.of("type", "array", "minItems", 4, "maxItems", 4, "items", integer(0, 255));
        color.put("minimumRgba", rgba);
        color.put("maximumRgba", rgba);
        var difference = new LinkedHashMap<>(common);
        difference.put("kind", Map.of("const", "frame_difference"));
        difference.put("referenceFrameIndex", integer(0, 8));
        difference.put("tolerance", integer(0, 255));
        var checkResult = new LinkedHashMap<>(common);
        checkResult.put("kind", Map.of("enum", List.of("color_fraction", "frame_difference")));
        checkResult.put("referenceFrameIndex", Map.of("type", List.of("integer", "null"), "minimum", 0, "maximum", 8));
        checkResult.put("minimumRgba", Map.of("oneOf", List.of(rgba, Map.of("type", "null"))));
        checkResult.put("maximumRgba", Map.of("oneOf", List.of(rgba, Map.of("type", "null"))));
        checkResult.put("tolerance", Map.of("type", List.of("integer", "null"), "minimum", 0, "maximum", 255));
        checkResult.put("matchingPixels", integer(0, McpRenderDomain.MAX_PIXELS));
        checkResult.put("totalPixels", integer(1, McpRenderDomain.MAX_PIXELS));
        checkResult.put("fraction", fraction());
        checkResult.put("passed", Map.of("type", "boolean"));
        var report = new LinkedHashMap<String, Object>();
        report.put("ok", Map.of("const", true));
        report.put("source", Map.of("const", "RETAINED_POSE_GRID"));
        report.put("operationId", text(128));
        report.put("scanOutcome", text(64));
        report.put("parametersRestored", Map.of("type", "boolean"));
        report.put("allRequestedPosesCaptured", Map.of("type", "boolean"));
        report.put("analysisComplete", Map.of("const", true));
        report.put("checksPassed", Map.of("type", "boolean"));
        report.put("sampleCount", integer(1, McpRasterValidation.MAX_SAMPLES));
        report.put("checks", array(object(checkResult), 1, 32));
        report.put("frames", array(McpPoseGridSchemas.frame(), 1, 9));
        report.put("limitations", array(text(128), 1, 8));
        return Map.of(
                "name",
                TOOL,
                "title",
                "Check retained pose image regions",
                "description",
                "Read-only pixel checks on terminal, unreleased pose-grid PNGs in this MCP session. No host access or new capture. Each unique checkId selects a frameIndex and region in the returned cropped PNG's top-left pixel coordinates (not model/full-raster coordinates). color_fraction counts pixels within inclusive straight-sRGB RGBA byte bounds; include alpha explicitly to exclude transparent/background colors. frame_difference counts pixels whose maximum premultiplied-sRGB RGB/alpha byte difference exceeds tolerance, comparing identical raster/crop coordinates with no alignment/resampling. Each check requires inclusive minimumFraction/maximumFraction. At most 32 checks/4194304 ROI samples/1048576 decoded pixels/3MiB PNG/240KiB report. PNG size/hash and frame coordinates are verified. Reports checked-frame capture/pose/state/crop provenance with null imageContentIndex; no images/base64. checksPassed refers only to these explicit thresholds, not art/semantic/model quality. Partial terminal scans may be inspected; scanOutcome/restoration/allRequestedPosesCaptured remain explicit. Retained images are historical observations, not current model freshness. Released/running/missing frames fail closed.",
                "inputSchema",
                object(Map.of(
                        "operationId",
                        text(128),
                        "checks",
                        array(Map.of("oneOf", List.of(object(color), object(difference))), 1, 32))),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(object(report), object(Map.of("ok", Map.of("const", false), "error", text(128))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> fraction() {
        return Map.of("type", "number", "minimum", 0, "maximum", 1);
    }

    private static Map<String, Object> array(Object item, int min, int max) {
        return Map.of("type", "array", "minItems", min, "maxItems", max, "items", item);
    }

    private static Map<String, Object> object(Map<String, Object> fields) {
        return Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                List.copyOf(fields.keySet()),
                "additionalProperties",
                false);
    }
}
