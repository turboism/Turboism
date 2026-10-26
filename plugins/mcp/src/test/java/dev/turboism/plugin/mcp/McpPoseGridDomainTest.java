package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class McpPoseGridDomainTest {
    @Test
    void retainedRasterChecksPreserveProvenanceAndNeverReadOrWriteHost() {
        var f = new Fixture();
        var request = f.request("inspect", f.grid());
        request.put("crop", Map.of("x", 1, "y", 1, "width", 1, "height", 1));
        var scan = f.call(McpPoseGridDomain.SCAN, request);
        int writes = f.writes, renders = f.renders, transactions = f.transactions;
        f.forbidHost = true;
        var check = McpRasterValidationTest.difference(0, 1, 1, 1, 0);
        check.put("minimumFraction", 1);
        var args = Map.<String, Object>of("operationId", "inspect", "checks", List.of(check));
        var response = f.call(McpRasterValidationSchemas.TOOL, args);
        var r = output(response);
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(true, r.get("checksPassed"));
        assertEquals(true, r.get("allRequestedPosesCaptured"));
        assertEquals(true, r.get("parametersRestored"));
        assertEquals(1, ((List<?>) response.get("content")).size());
        assertEquals(2, ((List<?>) r.get("frames")).size());
        var original = (Map<?, ?>) ((List<?>) output(scan).get("frames")).get(0);
        var observed = (Map<?, ?>) ((List<?>) r.get("frames")).get(0);
        assertEquals(original.get("captureId"), observed.get("captureId"));
        assertEquals(original.get("state"), observed.get("state"));
        assertEquals(Map.of("x", 1, "y", 1, "width", 1, "height", 1), observed.get("crop"));
        var measurement = (Map<?, ?>) ((List<?>) r.get("checks")).get(0);
        assertEquals(Map.of("x", 0, "y", 0, "width", 1, "height", 1), measurement.get("region"));
        assertEquals(1L, measurement.get("totalPixels"));
        assertEquals(original.get("parameters"), observed.get("parameters"));
        assertNull(observed.get("imageContentIndex"));
        assertEquals(writes, f.writes);
        assertEquals(renders, f.renders);
        assertEquals(transactions, f.transactions);
        assertEquals(
                McpOperationEffect.READ,
                f.tools.registration(McpRasterValidationSchemas.TOOL).effect());
        assertEquals(
                McpExecutionAffinity.DIRECT,
                f.tools.registration(McpRasterValidationSchemas.TOOL).affinity());
        assertFalse(f.tools.registration(McpRasterValidationSchemas.TOOL).transactionEligible());
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession(
                        "other",
                        () -> output(f.call(McpRasterValidationSchemas.TOOL, args))
                                .get("error")));
        f.call(McpPoseGridDomain.RELEASE, Map.of("operationId", "inspect"));
        assertEquals(
                "IMAGES_RELEASED",
                output(f.call(McpRasterValidationSchemas.TOOL, args)).get("error"));
    }

    @Test
    void rasterChecksRejectRunningJobsAndReportPartialRetainedObservations() {
        var f = new Fixture();
        var args = Map.<String, Object>of(
                "operationId", "partial", "checks", List.of(McpRasterValidationTest.color("a", 0, 2, 2)));
        assertEquals(
                "NOT_FOUND",
                output(f.call(McpRasterValidationSchemas.TOOL, args)).get("error"));
        f.duringRender = () -> assertEquals(
                "STILL_RUNNING",
                output(f.call(McpRasterValidationSchemas.TOOL, args)).get("error"));
        f.failRender = 2;
        f.call(McpPoseGridDomain.SCAN, f.request("partial", f.grid()));
        f.forbidHost = true;
        var r = output(f.call(McpRasterValidationSchemas.TOOL, args));
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals("FAILED", r.get("scanOutcome"));
        assertEquals(false, r.get("allRequestedPosesCaptured"));
        assertEquals(true, r.get("parametersRestored"));
        var missing = Map.<String, Object>of(
                "operationId", "partial", "checks", List.of(McpRasterValidationTest.color("a", 1, 2, 2)));
        assertEquals(
                "FRAME_NOT_FOUND",
                output(f.call(McpRasterValidationSchemas.TOOL, missing)).get("error"));
        var unknown = new LinkedHashMap<>(args);
        unknown.put("path", "/arbitrary/image.png");
        assertThrows(IllegalArgumentException.class, () -> f.call(McpRasterValidationSchemas.TOOL, unknown));
    }

    @Test
    void ninePosesReturnNineRealPngsWithExactAttachmentMappingAndRestoreBaseline() throws Exception {
        var f = new Fixture();
        var request = f.request("nine", f.grid());
        var response = f.call(McpPoseGridDomain.SCAN, request);
        var output = output(response);
        assertEquals("COMPLETED", output.get("outcome"), output.toString());
        assertEquals(true, output.get("parametersRestored"));
        assertEquals(1, f.transactions);
        assertEquals(9, f.renders);
        assertArrayEquals(new float[] {.2F, .4F}, f.values);
        assertNotNull(output.get("receipt"));
        assertEquals(
                "COMMITTED", output.get("transactionOutcome"), "A restored scan can still create one native Undo unit");
        var frames = (List<?>) output.get("frames");
        var contents = (List<?>) response.get("content");
        assertEquals(9, frames.size());
        assertEquals(10, contents.size());
        var hashes = new HashSet<Object>();
        for (int i = 0; i < frames.size(); i++) {
            var frame = (Map<?, ?>) frames.get(i);
            assertEquals(i, ((Number) frame.get("index")).intValue());
            assertEquals(i + 1, ((Number) frame.get("imageContentIndex")).intValue());
            hashes.add(frame.get("sha256"));
            var image = (Map<?, ?>) contents.get(i + 1);
            assertEquals("image", image.get("type"));
            var png = Base64.getDecoder().decode((String) image.get("data"));
            var raster = ImageIO.read(new ByteArrayInputStream(png));
            assertNotNull(raster);
            var actual = (List<?>) frame.get("parameters");
            float x = 0, y = 0;
            for (Object value : actual) {
                var p = (Map<?, ?>) value;
                if (p.get("id").equals("X")) x = ((Number) p.get("value")).floatValue();
                else y = ((Number) p.get("value")).floatValue();
            }
            assertEquals(color(x, y), raster.getRGB(0, 0));
            assertNotNull(((Map<?, ?>) frame.get("state")).get("poseHash"));
        }
        assertEquals(9, hashes.size());
        assertEquals(response, f.call(McpPoseGridDomain.RESULT, Map.of("operationId", "nine")));
        assertEquals(response, f.call(McpPoseGridDomain.SCAN, request));
        assertEquals(9, f.renders);
    }

    @Test
    void partialPoseDefinitionsAreRelativeToOriginalBaselineNotPreviousPose() {
        var f = new Fixture();
        var poses = List.of(pose("x-only", Map.of("X", 1F)), pose("y-only", Map.of("Y", -1F)));
        var result = output(f.call(McpPoseGridDomain.SCAN, f.request("partial", poses)));
        assertEquals("COMPLETED", result.get("outcome"));
        assertEquals(List.of(List.of(1F, .4F), List.of(.2F, -1F)), f.renderedValues);
        assertArrayEquals(new float[] {.2F, .4F}, f.values);
    }

    @Test
    void renderFailureReturnsPartialEvidenceAndRestoresParameters() {
        var f = new Fixture();
        f.failRender = 2;
        var response = f.call(McpPoseGridDomain.SCAN, f.request("failed", f.grid()));
        var output = output(response);
        assertEquals("FAILED", output.get("outcome"), output.toString());
        assertEquals(true, output.get("parametersRestored"));
        assertEquals("ROLLED_BACK", output.get("transactionOutcome"));
        assertEquals("RENDER_FAILED", output.get("failureCode"));
        assertEquals(1, ((List<?>) output.get("frames")).size());
        assertEquals(2, ((List<?>) response.get("content")).size());
        assertEquals(9, ((List<?>) output.get("poses")).size());
        assertArrayEquals(new float[] {.2F, .4F}, f.values);
        assertFalse(output.toString().contains("private-render-secret"));
    }

    @Test
    void explicitCancelAcknowledgesPendingRequestAndRestoresAfterCurrentRendererReturns() {
        var f = new Fixture();
        f.duringRender = () -> {
            var cancel = output(f.call(McpPoseGridDomain.CANCEL, Map.of("operationId", "cancel")));
            assertEquals(true, cancel.get("cancelRequested"));
            assertEquals(false, cancel.get("terminal"));
            assertEquals("RENDERING", cancel.get("phase"));
            assertEquals("RUNNING", cancel.get("outcome"));
        };
        var result = output(f.call(McpPoseGridDomain.SCAN, f.request("cancel", f.grid())));
        assertEquals("CANCELLED", result.get("outcome"));
        assertEquals(true, result.get("parametersRestored"));
        assertEquals(1, f.renders);
        assertArrayEquals(new float[] {.2F, .4F}, f.values);
    }

    @Test
    void requestCancellationCrossesUiQueueButCannotSkipRestoration() {
        var f = new Fixture();
        var registry = new McpRequestRegistry();
        try (var scope = registry.enter("session", 1)) {
            var request = f.request("request-cancel", f.grid());
            f.duringRender = () -> registry.cancel("session", 1);
            var result = output(f.call(McpPoseGridDomain.SCAN, request));
            assertEquals("CANCELLED", result.get("outcome"));
            assertEquals(true, result.get("parametersRestored"));
        }
        assertEquals(
                "CANCELLED",
                McpRequestRegistry.inSession(
                                "session",
                                () -> output(f.call(McpPoseGridDomain.RESULT, Map.of("operationId", "request-cancel"))))
                        .get("outcome"));
        assertArrayEquals(new float[] {.2F, .4F}, f.values);
    }

    @Test
    void cooperativeDeadlineDoesNotInterruptNativeCallOrParameterRestoration() {
        var f = new Fixture();
        f.duringRender = () -> f.clock.set(31_000_000_000L);
        var result = output(f.call(McpPoseGridDomain.SCAN, f.request("deadline", f.grid())));
        assertEquals("TIME_BUDGET_EXCEEDED", result.get("outcome"));
        assertEquals(true, result.get("parametersRestored"));
        assertEquals(1, f.renders);
        assertArrayEquals(new float[] {.2F, .4F}, f.values);
    }

    @Test
    void rendererSideEffectsAreRejectedAndFullBaselineIsChecked() {
        var f = new Fixture();
        f.duringRender = () -> f.values[1] = -.8F;
        var result =
                output(f.call(McpPoseGridDomain.SCAN, f.request("side-effect", List.of(pose("x", Map.of("X", 1F))))));
        assertEquals("FAILED", result.get("outcome"));
        assertEquals(true, result.get("parametersRestored"));
        assertArrayEquals(new float[] {.2F, .4F}, f.values);
        assertEquals(0, ((List<?>) result.get("frames")).size());
    }

    @Test
    void switchedDocumentIsNeverWrittenDuringRestoreAndUnverifiedRecoveryIsExplicit() {
        var f = new Fixture();
        f.duringRender = () -> f.document = "other-document";
        var result = output(f.call(McpPoseGridDomain.SCAN, f.request("switch", f.grid())));
        assertEquals("OUTCOME_UNKNOWN", result.get("outcome"));
        assertEquals(false, result.get("parametersRestored"));
        assertEquals(0, f.wrongDocumentWrites);
        assertEquals(1, f.renders);
    }

    @Test
    void restorationSetterFailureUsesSdkRollbackAndRecoveryFailureRemainsUnknown() {
        for (boolean recover : List.of(true, false)) {
            var f = new Fixture();
            f.failWrite = 3;
            f.recover = recover;
            var result = output(f.call(
                    McpPoseGridDomain.SCAN, f.request("restore", List.of(pose("xy", Map.of("X", 1F, "Y", -1F))))));
            assertEquals(recover ? "FAILED" : "OUTCOME_UNKNOWN", result.get("outcome"), result.toString());
            assertEquals(recover, result.get("parametersRestored"));
            assertEquals("RESTORE_FAILED", result.get("failureCode"));
            if (recover) assertArrayEquals(new float[] {.2F, .4F}, f.values);
        }
    }

    @Test
    void unknownResponseAndConflictingReplayCannotStartAnotherScan() {
        var f = new Fixture();
        var request = f.request("lost", f.grid());
        f.loseResult = true;
        var response = f.call(McpPoseGridDomain.SCAN, request);
        assertEquals("OUTCOME_UNKNOWN", output(response).get("outcome"));
        assertEquals(response, f.call(McpPoseGridDomain.SCAN, request));
        assertEquals(9, f.renders);
        var changed = new HashMap<>(request);
        changed.put("width", 3);
        assertEquals("OPERATION_ID_CONFLICT", error(f.call(McpPoseGridDomain.SCAN, changed)));
        assertEquals(
                "OUTCOME_UNKNOWN",
                output(f.call(McpPoseGridDomain.RESULT, Map.of("operationId", "missing")))
                        .get("outcome"));
    }

    @Test
    void rejectsStaleDuplicateOutOfRangeAndPixelBudgetBeforeAnyMutation() {
        var stale = new Fixture();
        var request = stale.request("stale", stale.grid());
        stale.values[0] = .3F;
        assertEquals(
                "NOT_APPLIED",
                output(stale.call(McpPoseGridDomain.SCAN, request)).get("outcome"));
        assertEquals(0, stale.transactions);
        var duplicates = new Fixture();
        duplicates.duplicateIds = true;
        var stateResponse = duplicates.call(McpRenderDomain.STATE, Map.of());
        assertEquals("AMBIGUOUS_PARAMETER_IDS", error(stateResponse));
        assertEquals(0, duplicates.writes);
        var range = new Fixture();
        assertEquals(
                "INVALID_ARGUMENT",
                error(range.call(
                        McpPoseGridDomain.SCAN, range.request("range", List.of(pose("bad", Map.of("X", 2F)))))));
        var pixels = new Fixture();
        var tooBig = pixels.request("pixels", pixels.grid());
        tooBig.put("width", 1024);
        tooBig.put("height", 1024);
        assertEquals("INVALID_ARGUMENT", error(pixels.call(McpPoseGridDomain.SCAN, tooBig)));
        assertEquals(0, pixels.transactions);
        assertEquals(0, range.writes);
    }

    @Test
    void unavailableTransactionNeverWritesAndProgressQueriesDoNotReadModel() {
        var f = new Fixture();
        var request = f.request("no-backend", f.grid());
        f.unavailable = true;
        var result = output(f.call(McpPoseGridDomain.SCAN, request));
        assertEquals("NOT_APPLIED", result.get("outcome"));
        assertEquals(0, f.writes);
        assertEquals(0, f.renders);
        f.readFailure = true;
        assertEquals(
                "NOT_APPLIED",
                output(f.call(McpPoseGridDomain.RESULT, Map.of("operationId", "no-backend")))
                        .get("outcome"));
        assertEquals(
                true,
                output(f.call(McpPoseGridDomain.STATUS, Map.of("operationId", "no-backend")))
                        .get("terminal"));
    }

    @Test
    void sessionIsolationAndRetentionLimitDoNotEvictOldResultsOrReplay() {
        var f = new Fixture();
        var first =
                McpRequestRegistry.inSession("a", () -> f.call(McpPoseGridDomain.SCAN, f.request("same", f.grid())));
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession(
                        "b", () -> error(f.call(McpPoseGridDomain.RESULT, Map.of("operationId", "same")))));
        McpRequestRegistry.inSession("b", () -> f.call(McpPoseGridDomain.SCAN, f.request("same", f.grid())));
        assertEquals("RESULT_STORE_FULL", error(f.call(McpPoseGridDomain.SCAN, f.request("third", f.grid()))));
        assertEquals(
                first,
                McpRequestRegistry.inSession(
                        "a", () -> f.call(McpPoseGridDomain.RESULT, Map.of("operationId", "same"))));
        assertEquals(18, f.renders);
    }

    @Test
    void totalPngBudgetDiscardsOnlyOverBudgetFrameAndStillRestores() {
        var f = new Fixture();
        f.noise = true;
        var request = f.request("noise", f.grid());
        request.put("width", 320);
        request.put("height", 320);
        var response = f.call(McpPoseGridDomain.SCAN, request);
        var result = output(response);
        assertEquals("FAILED", result.get("outcome"));
        assertEquals("IMAGE_BUDGET_EXCEEDED", result.get("failureCode"));
        assertEquals(true, result.get("parametersRestored"));
        assertTrue(((List<?>) result.get("frames")).size() < 9);
        int bytes = 0;
        for (Object block : ((List<?>) response.get("content")).subList(1, ((List<?>) response.get("content")).size()))
            bytes += Base64.getDecoder().decode((String) ((Map<?, ?>) block).get("data")).length;
        assertTrue(bytes <= 3 * 1024 * 1024);
        assertArrayEquals(new float[] {.2F, .4F}, f.values);
    }

    @Test
    void releasingPixelsPreservesHistoricalMetadataAndIdentityButAllowsNextScan() {
        var f = new Fixture();
        var first = f.request("first", f.grid());
        f.call(McpPoseGridDomain.SCAN, first);
        f.call(McpPoseGridDomain.SCAN, f.request("second", f.grid()));
        assertEquals("RESULT_STORE_FULL", error(f.call(McpPoseGridDomain.SCAN, f.request("blocked", f.grid()))));
        assertEquals(
                true,
                output(f.call(McpPoseGridDomain.RELEASE, Map.of("operationId", "first")))
                        .get("imagesReleased"));
        var released = f.call(McpPoseGridDomain.RESULT, Map.of("operationId", "first"));
        assertEquals(1, ((List<?>) released.get("content")).size());
        assertEquals(true, output(released).get("imagesReleased"));
        assertTrue(((List<?>) output(released).get("frames"))
                .stream().allMatch(v -> ((Map<?, ?>) v).get("imageContentIndex") == null));
        assertEquals(released, f.call(McpPoseGridDomain.SCAN, first));
        assertEquals(18, f.renders);
        assertEquals(
                "COMPLETED",
                output(f.call(McpPoseGridDomain.SCAN, f.request("third", f.grid())))
                        .get("outcome"));
        assertEquals(27, f.renders);
    }

    @Test
    void runningScanCannotReleaseImagesOrClaimRestorationComplete() {
        var f = new Fixture();
        f.duringRender = () -> assertEquals(
                "STILL_RUNNING", error(f.call(McpPoseGridDomain.RELEASE, Map.of("operationId", "running"))));
        assertEquals(
                "COMPLETED",
                output(f.call(McpPoseGridDomain.SCAN, f.request("running", f.grid())))
                        .get("outcome"));
    }

    @Test
    void unchangedPoseDoesNotCallSettersOrProduceAnUndoEntry() {
        var f = new Fixture();
        var result = output(f.call(
                McpPoseGridDomain.SCAN,
                f.request("same-values", List.of(pose("baseline", Map.of("X", .2F, "Y", .4F))))));
        assertEquals("COMPLETED", result.get("outcome"));
        assertEquals("NO_CHANGE", result.get("transactionOutcome"));
        assertEquals(true, result.get("parametersRestored"));
        assertEquals(0, f.writes);
        assertEquals(1, f.renders);
        assertNull(((Map<?, ?>) result.get("receipt")).get("historyEntryId"));
    }

    private static Map<String, Object> pose(String label, Map<String, Float> values) {
        return Map.of(
                "label",
                label,
                "values",
                values.entrySet().stream()
                        .map(e -> Map.<String, Object>of("parameterId", e.getKey(), "value", e.getValue()))
                        .toList());
    }

    private static int color(float x, float y) {
        return 0xff000000 | ((int) ((x + 1) * 100) << 16) | ((int) ((y + 1) * 100));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> value) {
        assertInstanceOf(Map.class, value.get("structuredContent"), value.toString());
        return (Map<String, Object>) value.get("structuredContent");
    }

    private static String error(Map<String, Object> response) {
        return (String) ((Map<?, ?>) output(response).get("error")).get("code");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> body) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (o, m, a) -> body.apply(m.getName(), a));
    }

    @Test
    void croppedPoseFramesKeepMappingAndRestoreBaseline() throws Exception {
        var f = new Fixture();
        var request = f.request("crop-grid", f.grid());
        var crop = Map.of("x", 1, "y", 0, "width", 1, "height", 2);
        request.put("crop", crop);
        var response = f.call(McpPoseGridDomain.SCAN, request);
        var r = output(response);
        assertEquals("COMPLETED", r.get("outcome"), r.toString());
        assertEquals(true, r.get("parametersRestored"));
        assertArrayEquals(new float[] {.2f, .4f}, f.values);
        for (var item : (List<?>) r.get("frames")) {
            var frame = (Map<?, ?>) item;
            assertEquals(crop, frame.get("crop"));
            assertEquals(2, frame.get("renderWidth"));
            assertEquals(2, frame.get("renderHeight"));
            assertEquals(1, frame.get("width"));
            assertEquals(2, frame.get("height"));
            assertNull(frame.get("modelToPixelTransform"));
            var image = (Map<?, ?>)
                    ((List<?>) response.get("content")).get(((Number) frame.get("imageContentIndex")).intValue());
            var png = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String) image.get("data"))));
            assertEquals(1, png.getWidth());
            assertEquals(2, png.getHeight());
        }
        assertEquals(response, f.call(McpPoseGridDomain.RESULT, Map.of("operationId", "crop-grid")));
        assertEquals(response, f.call(McpPoseGridDomain.SCAN, request));
        assertEquals(9, f.renders);
        var conflict = new LinkedHashMap<>(request);
        conflict.put("crop", Map.of("x", 0, "y", 0, "width", 1, "height", 2));
        assertEquals("OPERATION_ID_CONFLICT", error(f.call(McpPoseGridDomain.SCAN, conflict)));
        assertEquals(9, f.renders);
    }

    @Test
    void invalidCropAndFullNativePixelBudgetRejectBeforeAnyPoseWrites() {
        var f = new Fixture();
        var request = f.request("bad-crop", f.grid());
        request.put("crop", Map.of("x", 2, "y", 0, "width", 1, "height", 1));
        assertEquals("INVALID_ARGUMENT", error(f.call(McpPoseGridDomain.SCAN, request)));
        assertEquals(0, f.writes);
        assertEquals(0, f.transactions);
        assertEquals(0, f.renders);
        request = f.request("full-budget", f.grid());
        request.put("width", 1024);
        request.put("height", 1024);
        request.put("crop", Map.of("x", 0, "y", 0, "width", 1, "height", 1));
        assertEquals("INVALID_ARGUMENT", error(f.call(McpPoseGridDomain.SCAN, request)));
        assertEquals(0, f.writes);
        assertEquals(0, f.transactions);
        assertEquals(0, f.renders);
    }

    @Test
    void partialCroppedEvidenceSurvivesFailureAndRestoration() {
        var f = new Fixture();
        f.failRender = 2;
        var request = f.request("crop-failure", f.grid());
        var crop = Map.of("x", 1, "y", 1, "width", 1, "height", 1);
        request.put("crop", crop);
        var r = output(f.call(McpPoseGridDomain.SCAN, request));
        assertEquals("FAILED", r.get("outcome"));
        assertEquals(true, r.get("parametersRestored"));
        assertArrayEquals(new float[] {.2f, .4f}, f.values);
        var frames = (List<?>) r.get("frames");
        assertEquals(1, frames.size());
        assertEquals(crop, ((Map<?, ?>) frames.get(0)).get("crop"));
        assertEquals("ROLLED_BACK", r.get("transactionOutcome"));
    }

    private static final class Fixture {
        float[] values = {.2F, .4F};
        String document = "document-1";
        int renders, writes, transactions, wrongDocumentWrites, failRender, failWrite;
        long revision = 2;
        boolean duplicateIds, readFailure, unavailable, loseResult, noise, forbidHost, recover = true;
        Runnable duringRender = () -> {};
        final AtomicLong clock = new AtomicLong();
        final Parameter x = parameter(0), y = parameter(1);
        final Parameters parameters = proxy(Parameters.class, (m, a) -> {
            if (m.equals("all")) return List.of(x, y);
            throw new UnsupportedOperationException(m);
        });
        final ModelSnapshot snapshot =
                new ModelSnapshot("model-1", "Model", List.of(), List.of(), List.of(), List.of());
        final List<List<Float>> renderedValues = new ArrayList<>();
        final CubismModel model = proxy(CubismModel.class, (m, a) -> switch (m) {
            case "id" -> new ModelId("model-1");
            case "parameters" -> parameters;
            case "renderPixels" -> {
                renders++;
                if (renders == failRender) throw new IllegalStateException("private-render-secret");
                renderedValues.add(List.of(values[0], values[1]));
                int width = (int) a[0], height = (int) a[1];
                int[] pixels = new int[width * height];
                Arrays.fill(pixels, color(values[0], values[1]));
                if (noise) {
                    var random = new Random(renders);
                    for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
                }
                duringRender.run();
                yield new CubismRenderedPixels(width, height, pixels);
            }
            default -> throw new UnsupportedOperationException(m);
        });
        final AuthoringTransactionService transactionService = new AuthoringTransactionService() {
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                transactions++;
                if (unavailable) return AuthoringTransactionResult.unavailable("simulated.unavailable");
                var before = history();
                var original = values.clone();
                int oldWrites = writes;
                T value;
                try {
                    value = work.run();
                } catch (Exception failure) {
                    var receipt = new AuthoringTransactionReceipt(
                            "tx-" + transactions, options.label(), before, history(), Optional.empty());
                    if (!recover || !document.equals("document-1"))
                        return AuthoringTransactionResult.recoveryFailed(receipt, "simulated.recovery-failed");
                    values = original;
                    return AuthoringTransactionResult.rolledBack(receipt, "simulated.rollback");
                }
                boolean changed = writes > oldWrites;
                if (changed) revision++;
                var receipt = new AuthoringTransactionReceipt(
                        "tx-" + transactions,
                        options.label(),
                        before,
                        history(),
                        changed ? Optional.of("entry-" + transactions) : Optional.empty());
                if (loseResult) throw new IllegalStateException("lost transaction result");
                return changed
                        ? AuthoringTransactionResult.committed(value, receipt)
                        : AuthoringTransactionResult.noChange(value, receipt);
            }
        };
        final CubismFacade facade = proxy(CubismFacade.class, (m, a) -> {
            if (forbidHost) throw new AssertionError("Retained raster check accessed host: " + m);
            return switch (m) {
                case "activeModelDocument" ->
                    Optional.of(new DocumentSnapshot(
                            document, "Model", "model.cmo3", Optional.empty(), Optional.of(snapshot)));
                case "activeModel" -> Optional.of(snapshot);
                case "model" -> (CubismModelAccess) () -> model;
                case "history" -> proxy(CubismHistory.class, (n, b) -> history());
                case "authoringTransactions" -> transactionService;
                default -> throw new UnsupportedOperationException(m);
            };
        });
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable r) {
                r.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable r, Duration d) {
                throw new UnsupportedOperationException();
            }
        });
        final McpRenderDomain render = new McpRenderDomain(facade, execution);
        final McpToolCatalog tools = McpToolCatalog.combine(
                render.tools(), new McpPoseGridDomain(facade, execution, render, clock::get).tools());

        private Parameter parameter(int index) {
            return proxy(Parameter.class, (m, a) -> switch (m) {
                case "id" -> new ParameterId(index == 0 || duplicateIds ? "X" : "Y");
                case "getValue" -> {
                    if (readFailure) throw new SecurityException("private read failure");
                    yield values[index];
                }
                case "getMinimumValue" -> -1F;
                case "getMaximumValue" -> 1F;
                case "getDefaultValue" -> 0F;
                case "setValue" -> {
                    if (!document.equals("document-1")) {
                        wrongDocumentWrites++;
                        throw new IllegalStateException("Wrong document");
                    }
                    writes++;
                    if (writes == failWrite) throw new IllegalStateException("Injected setter failure");
                    values[index] = ((Number) a[0]).floatValue();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
        }

        private HistorySnapshot history() {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    1,
                    revision,
                    0,
                    List.of(),
                    false,
                    false,
                    "document-binding",
                    "manager-binding");
        }

        List<Map<String, Object>> grid() {
            var result = new ArrayList<Map<String, Object>>();
            for (float x : List.of(-1F, 0F, 1F))
                for (float y : List.of(-1F, 0F, 1F)) result.add(pose("pose-" + result.size(), Map.of("X", x, "Y", y)));
            return result;
        }

        Map<String, Object> request(String id, List<Map<String, Object>> poses) {
            var state = output(call(McpRenderDomain.STATE, Map.of())).get("state");
            return new LinkedHashMap<>(Json.parseObject(Json.stringify(Map.of(
                    "operationId",
                    id,
                    "expectedState",
                    state,
                    "width",
                    2,
                    "height",
                    2,
                    "poses",
                    poses,
                    "timeBudgetMs",
                    30000,
                    "restorePolicy",
                    "always"))));
        }

        Map<String, Object> call(String name, Map<String, Object> args) {
            return tools.call(name, args);
        }
    }
}
