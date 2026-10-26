package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class McpRenderDomainTest {
    @Test
    void capturesNativePixelsWithMetadataAndTypedImageThroughCatalog() throws Exception {
        var fixture = new Fixture();
        var state = fixture.state();
        assertEquals(0, fixture.renderCalls, "state discovery must not invoke rendering");
        var request = Map.<String, Object>of("expectedState", state, "width", 2, "height", 3);
        // Exercise actual JSON transport number types, not only in-process map equality.
        var wireRequest = Json.parseObject(Json.stringify(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var envelope = fixture.tools.call(McpRenderDomain.CAPTURE, new LinkedHashMap<>(wireRequest));
        var metadata = output(envelope);
        assertEquals(true, metadata.get("ok"), metadata.toString());
        assertEquals(state, metadata.get("state"));
        assertEquals("cubism.native.renderPixels", metadata.get("renderer"));
        assertEquals(2, metadata.get("renderWidth"));
        assertEquals(3, metadata.get("renderHeight"));
        assertEquals(Map.of("x", 0, "y", 0, "width", 2, "height", 3), metadata.get("crop"));
        assertNull(metadata.get("modelToPixelTransform"), "Do not invent an SDK coordinate transform");
        var content = (List<?>) envelope.get("content");
        assertEquals(2, content.size());
        var image = (Map<?, ?>) content.get(1);
        assertEquals("image", image.get("type"));
        var bytes = Base64.getDecoder().decode((String) image.get("data"));
        var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertEquals(2, decoded.getWidth());
        assertEquals(3, decoded.getHeight());
        assertEquals(0xffff0000, decoded.getRGB(0, 0));
        assertEquals(bytes.length, metadata.get("byteLength"));
        assertEquals(1, fixture.renderCalls);
        assertEquals(false, fixture.tools.registration(McpRenderDomain.CAPTURE).transactionEligible());
        assertEquals(
                McpOperationEffect.READ,
                fixture.tools.registration(McpRenderDomain.CAPTURE).effect());
        assertEquals(
                McpExecutionAffinity.UI_THREAD,
                fixture.tools.registration(McpRenderDomain.CAPTURE).affinity());
    }

    @Test
    void distinguishesWorkspaceIdentityFromNativeGuidButRejectsConflictingWorkspaceModels() {
        var fixture = new Fixture();
        var state = fixture.state();
        assertEquals("model-1", state.get("modelId"));
        assertEquals("native-source-guid", fixture.model.id().value());
        fixture.documentModelMismatch = true;
        assertError(fixture.tools.call(McpRenderDomain.STATE, Map.of()), "STALE_STATE");
        assertEquals(0, fixture.renderCalls);
    }

    @Test
    void rejectsStalePoseEvenWithoutHistoryChangeAndDiscardsMidRenderChanges() {
        var fixture = new Fixture();
        var state = fixture.state();
        fixture.value = 0.5F;
        assertError(fixture.capture(state), "STALE_STATE");
        assertEquals(0, fixture.renderCalls);
        state = fixture.state();
        fixture.duringRender = () -> fixture.documentId = "other-document";
        assertError(fixture.capture(state), "STALE_STATE");
        assertEquals(1, fixture.renderCalls);
    }

    @Test
    void refusesWrongDocumentGenerationRevisionAndModel() {
        for (String key : List.of("documentId", "modelId", "historyGeneration", "historyRevision", "poseHash")) {
            var fixture = new Fixture();
            var state = new LinkedHashMap<>(fixture.state());
            state.put(key, state.get(key) instanceof Number ? 99 : "other");
            assertError(fixture.capture(state), "STALE_STATE");
            assertEquals(0, fixture.renderCalls);
        }
    }

    @Test
    void failsClosedForNoDocumentPermissionsProviderAndMissingHistory() {
        var fixture = new Fixture();
        var state = fixture.state();
        fixture.present = false;
        assertError(fixture.capture(state), "NO_ACTIVE_MODEL");
        fixture.present = true;
        fixture.historyAvailable = false;
        assertError(fixture.capture(state), "UNAVAILABLE");
        fixture.historyAvailable = true;
        fixture.renderFailure = new UnsupportedOperationException("unverified provider secret");
        assertError(fixture.capture(state), "UNAVAILABLE");
        fixture.renderFailure = new SecurityException("token=must-not-leak");
        var denied = fixture.capture(state);
        assertError(denied, "PERMISSION_DENIED");
        assertFalse(denied.toString().contains("must-not-leak"));
    }

    @Test
    void validatesDimensionsSchemaAndRasterSizeWithoutFallback() {
        var fixture = new Fixture();
        var state = fixture.state();
        for (Object width : List.of(0, 1025, 1.5, Double.NaN)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.tools.call(
                            McpRenderDomain.CAPTURE, Map.of("expectedState", state, "width", width, "height", 1)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.tools.call(
                        McpRenderDomain.CAPTURE,
                        Map.of("expectedState", state, "width", 1, "height", 1, "path", "/tmp/no")));
        assertEquals(0, fixture.renderCalls);
        fixture.wrongDimensions = true;
        assertError(fixture.capture(state), "RENDER_FAILED");
    }

    @Test
    void boundsPngBytesAndReturnsNoImageOnOverBudget() {
        var fixture = new Fixture();
        var state = fixture.state();
        fixture.noise = true;
        var result = fixture.tools.call(
                McpRenderDomain.CAPTURE, Map.of("expectedState", state, "width", 1024, "height", 1024));
        assertError(result, "BUDGET_EXCEEDED");
    }

    @Test
    void parameterMetadataHasAByteBudgetBeforeAnyGpuCall() {
        var fixture = new Fixture();
        var parameters = new java.util.ArrayList<Parameter>();
        for (int i = 0; i < 40; i++) {
            String id = "p".repeat(4000) + i;
            parameters.add(proxy(Parameter.class, (method, args) -> switch (method) {
                case "id" -> new ParameterId(id);
                case "getValue" -> .1F;
                default -> throw new UnsupportedOperationException(method);
            }));
        }
        fixture.extraParameters = parameters;
        assertError(fixture.tools.call(McpRenderDomain.STATE, Map.of()), "BUDGET_EXCEEDED");
        assertEquals(0, fixture.renderCalls);
    }

    @Test
    void pixelCropPreservesExactNativeSamplesAndSourceCoordinates() throws Exception {
        var f = new Fixture();
        f.noise = true;
        var crop = Map.of("x", 1, "y", 1, "width", 2, "height", 2);
        var response = f.tools.call(
                McpRenderDomain.CAPTURE, Map.of("expectedState", f.state(), "width", 4, "height", 3, "crop", crop));
        var r = output(response);
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(4, r.get("renderWidth"));
        assertEquals(3, r.get("renderHeight"));
        assertEquals(2, r.get("width"));
        assertEquals(2, r.get("height"));
        assertEquals(crop, r.get("crop"));
        assertNull(r.get("modelToPixelTransform"));
        var image = (Map<?, ?>) ((List<?>) response.get("content")).get(1);
        var png = Base64.getDecoder().decode((String) image.get("data"));
        var decoded = ImageIO.read(new ByteArrayInputStream(png));
        int[] nativePixels = new int[12];
        var rng = new Random(42);
        for (int i = 0; i < 12; i++) nativePixels[i] = rng.nextInt();
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++) assertEquals(nativePixels[(y + 1) * 4 + x + 1], decoded.getRGB(x, y));
        assertEquals(2, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
        assertEquals(png.length, r.get("byteLength"));
        assertEquals(1, f.renderCalls);
        assertEquals(
                java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(png)),
                r.get("sha256"));
    }

    @Test
    void cropBoundsAreCheckedBeforeNativeRendering() {
        var f = new Fixture();
        var state = f.state();
        for (var crop : List.of(
                Map.of("x", 2, "y", 0, "width", 1, "height", 1), Map.of("x", 0, "y", 2, "width", 1, "height", 2)))
            assertError(
                    f.tools.call(
                            McpRenderDomain.CAPTURE,
                            Map.of("expectedState", state, "width", 2, "height", 3, "crop", crop)),
                    "INVALID_ARGUMENT");
        for (var crop : List.of(
                Map.of("x", -1, "y", 0, "width", 1, "height", 1),
                Map.of("x", .5, "y", 0, "width", 1, "height", 1),
                Map.of("x", 0, "y", 0, "width", 0, "height", 1),
                Map.of("x", 0, "y", 0, "width", 1, "height", 1, "path", "unsafe")))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.tools.call(
                            McpRenderDomain.CAPTURE,
                            Map.of("expectedState", state, "width", 2, "height", 3, "crop", crop)));
        assertEquals(0, f.renderCalls);
    }

    @Test
    void croppedCaptureDiscardsStaleStateAndDoesNotBypassNativeRasterLimits() {
        var f = new Fixture();
        var state = f.state();
        var crop = Map.of("x", 0, "y", 0, "width", 1, "height", 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> f.tools.call(
                        McpRenderDomain.CAPTURE,
                        Map.of("expectedState", state, "width", 1025, "height", 1, "crop", crop)));
        assertEquals(0, f.renderCalls);
        f.duringRender = () -> f.value = .8f;
        assertError(
                f.tools.call(
                        McpRenderDomain.CAPTURE, Map.of("expectedState", state, "width", 2, "height", 3, "crop", crop)),
                "STALE_STATE");
        assertEquals(1, f.renderCalls);
    }

    private static void assertError(Map<String, Object> envelope, String code) {
        var result = output(envelope);
        assertEquals(false, result.get("ok"), result.toString());
        assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"), result.toString());
        assertEquals(1, ((List<?>) envelope.get("content")).size(), "Failure must not return an image");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("structuredContent");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> call) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (instance, method, args) -> call.apply(method.getName(), args));
    }

    private static final class Fixture {
        boolean present = true, historyAvailable = true, wrongDimensions, noise, documentModelMismatch;
        String documentId = "document-1";
        float value = 0.1F;
        int renderCalls;
        Runnable duringRender = () -> {};
        RuntimeException renderFailure;
        final ModelSnapshot snapshot =
                new ModelSnapshot("model-1", "Model", List.of(), List.of(), List.of(), List.of());
        final Parameter parameter = proxy(Parameter.class, (method, args) -> switch (method) {
            case "id" -> new ParameterId("ParamAngleX");
            case "getValue" -> value;
            default -> throw new UnsupportedOperationException(method);
        });
        List<Parameter> extraParameters = List.of();
        final Parameters parameters = proxy(Parameters.class, (method, args) -> switch (method) {
            case "all" -> extraParameters.isEmpty() ? List.of(parameter) : extraParameters;
            default -> throw new UnsupportedOperationException(method);
        });
        final CubismModel model = proxy(CubismModel.class, (method, args) -> switch (method) {
            case "id" -> new ModelId("native-source-guid");
            case "parameters" -> parameters;
            case "renderPixels" -> {
                renderCalls++;
                if (renderFailure != null) throw renderFailure;
                duringRender.run();
                int width = wrongDimensions ? 1 : (int) args[0], height = (int) args[1];
                int[] pixels = new int[width * height];
                Arrays.fill(pixels, 0xffff0000);
                if (noise) {
                    var random = new Random(42);
                    for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
                }
                yield new CubismRenderedPixels(width, height, pixels);
            }
            default -> throw new UnsupportedOperationException(method);
        });
        final CubismHistory history = proxy(CubismHistory.class, (method, args) -> switch (method) {
            case "snapshot" ->
                historyAvailable
                        ? new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1, 2, 0, List.of(), false, false)
                        : HistorySnapshot.unavailable();
            default -> throw new UnsupportedOperationException(method);
        });
        final CubismFacade facade = proxy(CubismFacade.class, (method, args) -> switch (method) {
            case "activeModelDocument" ->
                present
                        ? Optional.of(new DocumentSnapshot(
                                documentId,
                                "Model",
                                "model.cmo3",
                                Optional.empty(),
                                Optional.of(
                                        documentModelMismatch
                                                ? new ModelSnapshot(
                                                        "other-model",
                                                        "Other",
                                                        List.of(),
                                                        List.of(),
                                                        List.of(),
                                                        List.of())
                                                : snapshot)))
                        : Optional.empty();
            case "activeModel" -> present ? Optional.of(snapshot) : Optional.empty();
            case "model" -> (CubismModelAccess) () -> model;
            case "history" -> history;
            default -> throw new UnsupportedOperationException(method);
        });
        final McpToolCatalog tools = new McpRenderDomain(facade, new McpExecutionBridge(new UiScheduler() {
                    @Override
                    public Registration runOnUiThread(Runnable work) {
                        work.run();
                        return () -> {};
                    }

                    @Override
                    public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                        throw new UnsupportedOperationException();
                    }
                }))
                .tools();

        Map<String, Object> state() {
            return output(tools.call(McpRenderDomain.STATE, Map.of())).get("state") instanceof Map<?, ?> raw
                    ? cast(raw)
                    : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> cast(Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }

        Map<String, Object> capture(Map<String, Object> state) {
            return tools.call(McpRenderDomain.CAPTURE, Map.of("expectedState", state, "width", 2, "height", 3));
        }
    }
}
