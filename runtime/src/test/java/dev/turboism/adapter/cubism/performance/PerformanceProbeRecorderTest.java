package dev.turboism.adapter.cubism.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceProbeRecorderTest {

    @Test
    void captureIsSingleFlightAndSamplingIsBounded() {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        assertTrue(recorder.startCapture());
        assertFalse(recorder.startCapture());

        for (int i = 0; i < 64; i++) {
            final long started = recorder.enter(PerformanceProbeMetric.SCENE_TRAVERSAL);
            recorder.exit(PerformanceProbeMetric.SCENE_TRAVERSAL, started);
        }

        final PerformanceProbeRecorder.MetricSnapshot snapshot = recorder.snapshot()
            .metrics().get(PerformanceProbeMetric.SCENE_TRAVERSAL);
        assertEquals(64L, snapshot.calls());
        assertEquals(4L, snapshot.sampled());

        recorder.stopCapture();
        assertEquals(0L, recorder.enter(PerformanceProbeMetric.RENDER_SCENE));
    }

    @Test
    void awaitQuiescenceWaitsForLateExitsAndTimesOutBounded() {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        assertTrue(recorder.startCapture());
        final long started = recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
        // One in-flight entry: quiescence must time out instead of waiting forever.
        assertFalse(recorder.awaitQuiescence(50L));
        recorder.exit(PerformanceProbeMetric.RENDER_SCENE, started);
        assertTrue(recorder.awaitQuiescence(1_000L));
        recorder.stopCapture();
    }
}
