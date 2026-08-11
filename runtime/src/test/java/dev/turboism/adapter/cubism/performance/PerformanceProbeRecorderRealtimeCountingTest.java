package dev.turboism.adapter.cubism.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceProbeRecorderRealtimeCountingTest {

    @Test
    void renderSceneCallsAccumulateWhileCaptureIsOff() {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        assertEquals(0L, recorder.renderSceneCalls());
        recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
        recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
        assertEquals(2L, recorder.renderSceneCalls());
        // Non-RENDER_SCENE metrics must not touch the real-time counter.
        recorder.enter(PerformanceProbeMetric.MODELING_PRE_RENDER_UPDATE);
        assertEquals(2L, recorder.renderSceneCalls());
    }

    @Test
    void captureModeAndRealtimeCountingShareTheRecorder() {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        assertTrue(recorder.startCapture());
        final long started = recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
        assertTrue(started > 0L);
        recorder.exit(PerformanceProbeMetric.RENDER_SCENE, started);
        assertEquals(1L, recorder.renderSceneCalls());
        final PerformanceProbeRecorder.Snapshot capture = recorder.snapshot();
        assertEquals(1L, capture.metrics().get(PerformanceProbeMetric.RENDER_SCENE).calls());
        recorder.stopCapture();
        // Counting continues after capture stops; capture reset does not wipe it.
        recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
        assertEquals(2L, recorder.renderSceneCalls());
        assertTrue(recorder.startCapture());
        recorder.stopCapture();
        assertEquals(2L, recorder.renderSceneCalls());
        assertTrue(recorder.awaitQuiescence(1_000L));
        assertEquals(0L, recorder.snapshot().failures());
    }

    @Test
    void startCaptureResetsOnlyTheCaptureMetrics() {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
        assertTrue(recorder.startCapture());
        final long started = recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
        recorder.exit(PerformanceProbeMetric.RENDER_SCENE, started);
        final PerformanceProbeRecorder.Snapshot capture = recorder.snapshot();
        // The capture window restarted the call counter...
        assertEquals(1L, capture.metrics().get(PerformanceProbeMetric.RENDER_SCENE).calls());
        // ...but the real-time counter is cumulative across capture windows.
        assertEquals(2L, recorder.renderSceneCalls());
    }
}
