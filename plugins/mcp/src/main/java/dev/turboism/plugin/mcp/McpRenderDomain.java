package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import dev.turboism.sdk.json.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Native current-pose renders, bound to the discovered document, history and live parameter values. */
final class McpRenderDomain {
    static final String STATE = "turboism.render.state";
    static final String CAPTURE = "turboism.render.capture";
    static final int MAX_DIMENSION = 1024;
    static final int MAX_PIXELS = 1_048_576;
    static final int MAX_PNG_BYTES = 3 * 1024 * 1024;
    private static final int MAX_PARAMETERS = 2048;
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;

    McpRenderDomain(final CubismFacade cubism, final McpExecutionBridge execution) {
        this.cubism = Objects.requireNonNull(cubism, "cubism");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        var support = McpVersionSupport.exact(
                "cubism.editor-model.render.read",
                versions,
                List.of(
                        McpVersionSupport.OperationSupport.available(
                                "state",
                                McpOperationEffect.READ,
                                false,
                                McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                versions,
                                "Document/history/live-pose discovery; does not probe the GPU renderer."),
                        McpVersionSupport.OperationSupport.available(
                                "capture",
                                McpOperationEffect.READ,
                                false,
                                McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                versions,
                                "SDK native renderPixels; exact Runtime authorization and GPU readiness checked on invocation. No fallback.")));
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        definition(STATE, "Read current render state", object(Map.of()), stateSchema()),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        support,
                        execution,
                        this::state),
                McpRegisteredTool.typed(
                        definition(CAPTURE, "Render the current model pose", captureInputSchema(), captureSchema()),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        support,
                        execution,
                        this::capture)));
    }

    private Map<String, Object> state(final Map<String, Object> arguments) {
        try {
            if (!arguments.isEmpty()) throw new IllegalArgumentException("state accepts no arguments");
            var current = context();
            return envelope(
                    Map.of(
                            "ok",
                            true,
                            "state",
                            current.state(),
                            "parameters",
                            current.parameters(),
                            "maxDimension",
                            MAX_DIMENSION,
                            "maxPixels",
                            MAX_PIXELS,
                            "maxPngBytes",
                            MAX_PNG_BYTES),
                    null);
        } catch (RuntimeException failure) {
            return failure(failure);
        }
    }

    Map<String, Object> capture(final Map<String, Object> arguments) {
        try {
            var required = java.util.Set.of("expectedState", "width", "height");
            var allowed = new java.util.HashSet<>(required);
            allowed.add("crop");
            if (!arguments.keySet().containsAll(required) || !allowed.containsAll(arguments.keySet()))
                throw new IllegalArgumentException(
                        "capture requires expectedState, width, height and optional pixel crop only");
            int width = dimension(arguments.get("width"));
            int height = dimension(arguments.get("height"));
            if ((long) width * height > MAX_PIXELS) throw new IllegalArgumentException("render pixel budget exceeded");
            var crop = arguments.containsKey("crop")
                    ? crop(arguments.get("crop"), width, height)
                    : new Crop(0, 0, width, height);
            var before = context();
            requireExpected(arguments.get("expectedState"), before.state());
            CubismRenderedPixels pixels = before.model().renderPixels(width, height);
            if (pixels.width() != width || pixels.height() != height)
                throw new IllegalStateException("Native renderer returned incorrect dimensions");
            var after = context();
            if (!before.state().equals(after.state()))
                throw new StaleState("Document, history or parameter pose changed during capture; image discarded");
            var raster = new BufferedImage(crop.width(), crop.height(), BufferedImage.TYPE_INT_ARGB);
            raster.setRGB(0, 0, crop.width(), crop.height(), pixels.argb(), crop.y() * width + crop.x(), width);
            var encoded = new ByteArrayOutputStream();
            try {
                if (!ImageIO.write(raster, "png", encoded)) throw new IOException("PNG encoder unavailable");
            } finally {
                raster.flush();
            }
            byte[] png = encoded.toByteArray();
            if (png.length > MAX_PNG_BYTES)
                throw new BudgetExceeded("PNG byte budget exceeded; request smaller dimensions");
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", true);
            output.put("captureId", UUID.randomUUID().toString());
            output.put("capturedAt", Instant.now().toString());
            output.put("state", after.state());
            output.put("parameters", after.parameters());
            output.put("width", crop.width());
            output.put("height", crop.height());
            output.put("renderWidth", width);
            output.put("renderHeight", height);
            output.put("crop", crop.metadata());
            output.put("mimeType", "image/png");
            output.put("byteLength", png.length);
            output.put("sha256", hash(png));
            output.put("renderer", "cubism.native.renderPixels");
            output.put("pixelCoordinates", "origin-top-left,x-right,y-down");
            // The SDK does not expose its model-to-raster matrix. Never invent a conversion.
            output.put("modelToPixelTransform", null);
            return envelope(output, Base64.getEncoder().encodeToString(png));
        } catch (IOException failure) {
            return error("ENCODING_FAILED", "Native raster could not be encoded as PNG");
        } catch (RuntimeException failure) {
            return failure(failure);
        }
    }

    Context context() {
        var document = cubism.activeModelDocument().orElseThrow(() -> new NoModel("No active model document"));
        var snapshot = cubism.activeModel().orElseThrow(() -> new NoModel("No active model"));
        var model = cubism.model().active();
        // Workspace snapshots use session-scoped opaque model IDs; typed Editor models use
        // the native source GUID. Comparing those namespaces rejects every real host model.
        // Reading id() still validates the typed handle against the active native binding.
        if (model.id().value().isBlank()
                || document.model().isEmpty()
                || !document.model().get().modelId().equals(snapshot.modelId()))
            throw new StaleState("Active document/model identities disagree");
        var history = cubism.history().snapshot();
        if (history.availability() != HistorySnapshot.Availability.AVAILABLE)
            throw new UnsupportedOperationException("History identity is unavailable; capture cannot be state-bound");
        var parameters = new ArrayList<Map<String, Object>>();
        var all = model.parameters().all();
        if (all.size() > MAX_PARAMETERS) throw new BudgetExceeded("Parameter metadata budget exceeded");
        var ids = new java.util.HashSet<String>();
        for (int index = 0; index < all.size(); index++) {
            var parameter = all.get(index);
            if (!ids.add(parameter.id().value())) throw new AmbiguousParameters();
            float value = parameter.getValue();
            if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite live parameter value");
            // Ordinal is metadata only, never a native source GUID or write target identity.
            parameters.add(Map.of("ordinal", index, "id", parameter.id().value(), "value", value));
        }
        if (Json.stringify(parameters).getBytes(StandardCharsets.UTF_8).length > 128 * 1024)
            throw new BudgetExceeded("Parameter metadata byte budget exceeded");
        var binding = Map.<String, Object>of(
                "documentId",
                document.documentId(),
                "modelId",
                snapshot.modelId(),
                "historyGeneration",
                history.generation(),
                "historyRevision",
                history.revision(),
                "poseHash",
                hash(Json.stringify(parameters).getBytes(StandardCharsets.UTF_8)));
        return new Context(model, binding, List.copyOf(parameters));
    }

    static void requireExpected(final Object value, final Map<String, Object> actual) {
        if (!(value instanceof Map<?, ?> expected) || !expected.keySet().equals(actual.keySet()))
            throw new IllegalArgumentException("expectedState must contain the complete discovered render state");
        for (String key : actual.keySet()) {
            Object current = actual.get(key), previous = expected.get(key);
            boolean same = current instanceof Number number && previous instanceof Number prior
                    ? new java.math.BigDecimal(number.toString()).compareTo(new java.math.BigDecimal(prior.toString()))
                            == 0
                    : Objects.equals(current, previous);
            if (!same) throw new StaleState("Render state changed; read turboism.render.state before retrying");
        }
    }

    static int dimension(final Object value) {
        return boundedInteger(value, 1, MAX_DIMENSION);
    }

    private static int boundedInteger(final Object value, int minimum, int maximum) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("render dimensions must be integers");
        int result;
        try {
            result = new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new IllegalArgumentException("render dimensions must be finite integers", failure);
        }
        if (result < minimum || result > maximum)
            throw new IllegalArgumentException("render dimension budget exceeded");
        return result;
    }

    record Crop(int x, int y, int width, int height) {
        Map<String, Object> metadata() {
            return Map.of("x", x, "y", y, "width", width, "height", height);
        }
    }

    static Crop crop(Object raw, int renderWidth, int renderHeight) {
        if (!(raw instanceof Map<?, ?> fields)
                || !fields.keySet().equals(java.util.Set.of("x", "y", "width", "height")))
            throw new IllegalArgumentException("Invalid crop fields");
        int x = boundedInteger(fields.get("x"), 0, MAX_DIMENSION - 1),
                y = boundedInteger(fields.get("y"), 0, MAX_DIMENSION - 1);
        int width = dimension(fields.get("width")), height = dimension(fields.get("height"));
        if (x + width > renderWidth || y + height > renderHeight)
            throw new IllegalArgumentException("Crop exceeds native raster");
        return new Crop(x, y, width, height);
    }

    static Map<String, Object> cropSchema() {
        var coordinate = Map.<String, Object>of("type", "integer", "minimum", 0, "maximum", MAX_DIMENSION - 1);
        return object(Map.of("x", coordinate, "y", coordinate, "width", dimension(), "height", dimension()));
    }

    private static Map<String, Object> captureInputSchema() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("expectedState", bindingSchema());
        fields.put("width", dimension());
        fields.put("height", dimension());
        var required = List.copyOf(fields.keySet());
        fields.put("crop", cropSchema());
        var input = new LinkedHashMap<>(object(fields));
        input.put("required", required);
        return input;
    }

    private static String hash(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Map<String, Object> failure(final RuntimeException failure) {
        String code = failure instanceof NoModel
                ? "NO_ACTIVE_MODEL"
                : failure instanceof AmbiguousParameters
                        ? "AMBIGUOUS_PARAMETER_IDS"
                        : failure instanceof StaleState
                                ? "STALE_STATE"
                                : failure instanceof BudgetExceeded
                                        ? "BUDGET_EXCEEDED"
                                        : failure instanceof SecurityException
                                                        || failure
                                                                instanceof
                                                                dev.turboism.sdk.permission.CubismPermissionException
                                                ? "PERMISSION_DENIED"
                                                : failure instanceof UnsupportedOperationException
                                                        ? "UNAVAILABLE"
                                                        : failure instanceof IllegalArgumentException
                                                                ? "INVALID_ARGUMENT"
                                                                : "RENDER_FAILED";
        // Host exceptions may contain local paths or credentials; do not copy arbitrary exception messages.
        return error(
                code,
                switch (code) {
                    case "NO_ACTIVE_MODEL" -> "No active model document";
                    case "AMBIGUOUS_PARAMETER_IDS" ->
                        "Duplicate display IDs prevent reliable native parameter pose binding; GUID addressing is required";
                    case "STALE_STATE" -> "Document, history or pose changed; rediscover state";
                    case "BUDGET_EXCEEDED" -> "Render data budget exceeded; reduce the requested data";
                    case "PERMISSION_DENIED" -> "Model read permission is required";
                    case "UNAVAILABLE" -> "Native renderer or state reader is not admitted by the current host";
                    case "INVALID_ARGUMENT" -> "Invalid render request";
                    default -> "Native render failed; no image or cached fallback returned";
                });
    }

    private static Map<String, Object> error(final String code, final String message) {
        return envelope(Map.of("ok", false, "error", Map.of("code", code, "message", message)), null);
    }

    private static Map<String, Object> envelope(final Map<String, Object> output, final String png) {
        var safe = McpJsonSupport.encodable(output);
        var content = new ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", Json.stringify(safe)));
        if (png != null) content.add(Map.of("type", "image", "mimeType", "image/png", "data", png));
        return Map.of(
                "content",
                List.copyOf(content),
                "structuredContent",
                safe,
                "isError",
                !Boolean.TRUE.equals(output.get("ok")));
    }

    private static Map<String, Object> definition(
            String name, String title, Map<String, Object> input, Map<String, Object> success) {
        return Map.of(
                "name",
                name,
                "title",
                title,
                "description",
                title
                        + ". Read-only; never moves parameters or selection. No screenshot/cache fallback. Discover render.state before capture. Optional capture crop={x,y,width,height} selects a bounded top-left-origin pixel rectangle from the native width/height raster without resizing or improving resolution. Output width/height describe the returned PNG; renderWidth/renderHeight and crop preserve its source mapping (source pixel = output pixel + crop origin). Native pixel/memory budget still applies to the full raster, and modelToPixelTransform remains unknown.",
                "inputSchema",
                input,
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(
                                success,
                                object(Map.of(
                                        "ok",
                                        Map.of("const", false),
                                        "error",
                                        object(Map.of("code", string(), "message", string())))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    static Map<String, Object> bindingSchema() {
        return object(Map.of(
                "documentId",
                string(),
                "modelId",
                string(),
                "historyGeneration",
                integer(),
                "historyRevision",
                integer(),
                "poseHash",
                string()));
    }

    private static Map<String, Object> parametersSchema() {
        return Map.of(
                "type",
                "array",
                "maxItems",
                MAX_PARAMETERS,
                "items",
                object(Map.of("ordinal", integer(), "id", string(), "value", Map.of("type", "number"))));
    }

    private static Map<String, Object> stateSchema() {
        return object(Map.of(
                "ok",
                Map.of("const", true),
                "state",
                bindingSchema(),
                "parameters",
                parametersSchema(),
                "maxDimension",
                integer(),
                "maxPixels",
                integer(),
                "maxPngBytes",
                integer()));
    }

    private static Map<String, Object> captureSchema() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("ok", Map.of("const", true));
        fields.put("captureId", string());
        fields.put("capturedAt", string());
        fields.put("state", bindingSchema());
        fields.put("parameters", parametersSchema());
        fields.put("width", integer());
        fields.put("height", integer());
        fields.put("renderWidth", integer());
        fields.put("renderHeight", integer());
        fields.put("crop", cropSchema());
        fields.put("byteLength", integer());
        fields.put("mimeType", Map.of("const", "image/png"));
        fields.put("sha256", string());
        fields.put("renderer", Map.of("const", "cubism.native.renderPixels"));
        fields.put("pixelCoordinates", string());
        fields.put("modelToPixelTransform", Map.of("type", "null"));
        return object(fields);
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

    private static Map<String, Object> string() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer", "minimum", 0);
    }

    private static Map<String, Object> dimension() {
        return Map.of("type", "integer", "minimum", 1, "maximum", MAX_DIMENSION);
    }

    record Context(CubismModel model, Map<String, Object> state, List<Map<String, Object>> parameters) {}

    private static final class AmbiguousParameters extends UnsupportedOperationException {}

    private static final class NoModel extends IllegalStateException {
        NoModel(String message) {
            super(message);
        }
    }

    private static final class StaleState extends IllegalStateException {
        StaleState(String message) {
            super(message);
        }
    }

    private static final class BudgetExceeded extends IllegalStateException {
        BudgetExceeded(String message) {
            super(message);
        }
    }
}
