package dev.turboism.adapter.cubism.performance;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceProbeReportWriterTest {

    private static final String ARTIFACT_SHA = ReviewedHostArtifacts.CUBISM_5_3_02.sha256();
    private static final String AGENT_SHA = "a".repeat(64);
    private static final String FIXTURE_SHA = "b".repeat(64);
    private static final Set<String> METRIC_NAMES = Set.of(
        "renderScene",
        "modelingPreRenderUpdate",
        "renderSystem",
        "sceneTraversal",
        "rendererDispatch",
        "updateModelInstances",
        "reinitModelInstanceExe"
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void reportBindsExactIdentitiesScenarioFailuresAndStableMetricNames() throws Exception {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        assertTrue(recorder.startCapture());
        for (int i = 0; i < 10; i++) {
            final long started = recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
            recorder.exit(PerformanceProbeMetric.RENDER_SCENE, started);
        }
        recorder.fail();
        recorder.stopCapture();
        final PerformanceProbeRecorder.Snapshot snapshot = recorder.snapshot();

        final long startedEpochMillis = 1_700_000_000_000L;
        final long endedEpochMillis = startedEpochMillis + 30_000L;
        final Path output = temporary.resolve("logs/performance-probe.json");
        new PerformanceProbeReportWriter().write(
            output,
            ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
            ARTIFACT_SHA,
            AGENT_SHA,
            FIXTURE_SHA,
            "edit",
            startedEpochMillis,
            endedEpochMillis,
            snapshot
        );

        final JsonNode root = JSON.readTree(Files.readAllBytes(output));
        assertEquals("turboism.cubism.performance-probe", root.path("format").asText());
        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("5.3.02", root.path("cubismVersion").asText());
        assertEquals(ARTIFACT_SHA, root.path("artifactSha256").asText());
        assertEquals(AGENT_SHA, root.path("agentSha256").asText());
        assertEquals(FIXTURE_SHA, root.path("fixtureSha256").asText());
        assertEquals("edit", root.path("scenario").asText());

        final JsonNode capture = root.path("capture");
        assertEquals(startedEpochMillis, capture.path("startEpochMs").asLong());
        assertEquals(endedEpochMillis, capture.path("endEpochMs").asLong());
        assertEquals(0, capture.path("dropped").asInt());
        assertEquals(1L, capture.path("failures").asLong());

        final JsonNode metrics = root.path("metrics");
        assertEquals(METRIC_NAMES.size(), metrics.size());
        for (String name : METRIC_NAMES) {
            final JsonNode metric = metrics.path(name);
            assertFalse(metric.isMissingNode(), "missing metric " + name);
            assertTrue(metric.isObject());
            assertEquals(4, metric.size(), "unexpected fields for " + name);
            assertTrue(metric.path("calls").isIntegralNumber());
            assertTrue(metric.path("sampled").isIntegralNumber());
            assertTrue(metric.path("totalNanos").isIntegralNumber());
            assertTrue(metric.path("maxNanos").isIntegralNumber());
        }
        assertEquals(10L, metrics.path("renderScene").path("calls").asLong());
        assertEquals(0L, metrics.path("updateModelInstances").path("calls").asLong());
        assertEquals(0L, metrics.path("reinitModelInstanceExe").path("calls").asLong());

        // A report that carries failures or omits metrics must remain visibly
        // rejectable: failures is explicit, and no metric is silently dropped.
        assertTrue(root.path("capture").path("failures").asLong() > 0);
        assertTrue(root.path("capture").path("endEpochMs").asLong()
            > root.path("capture").path("startEpochMs").asLong());
        assertFalse(root.path("writtenAt").asText().isBlank());
    }

    @Test
    void reportCarriesTheExact5303ProfileIdentity() throws Exception {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        final Path output = temporary.resolve("logs/performance-probe-5303.json");

        new PerformanceProbeReportWriter().write(
            output,
            ReviewedHostArtifacts.CUBISM_5_3_03_VERSION,
            ReviewedHostArtifacts.CUBISM_5_3_03.sha256(),
            AGENT_SHA,
            FIXTURE_SHA,
            "camera",
            1_700_000_000_000L,
            1_700_000_030_000L,
            recorder.snapshot()
        );

        final JsonNode root = JSON.readTree(Files.readAllBytes(output));
        assertEquals("5.3.03", root.path("cubismVersion").asText());
        assertEquals(
            ReviewedHostArtifacts.CUBISM_5_3_03.sha256(),
            root.path("artifactSha256").asText()
        );
    }
}
