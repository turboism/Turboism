package dev.turboism.adapter.cubism.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceLatencyHistogramTest {
    @Test
    void fixedBucketsReturnConservativePercentileBoundsAndReset() {
        final PerformanceLatencyHistogram histogram = new PerformanceLatencyHistogram();
        assertEquals(0, histogram.snapshot().samples());
        for (int i = 0; i < 95; i++) histogram.record(10);
        for (int i = 0; i < 4; i++) histogram.record(100);
        histogram.record(1_000);
        final var snapshot = histogram.snapshot();
        assertEquals(100, snapshot.samples());
        assertEquals(15, snapshot.p50UpperBoundNanos());
        assertEquals(15, snapshot.p95UpperBoundNanos());
        assertEquals(127, snapshot.p99UpperBoundNanos());
        histogram.reset();
        histogram.record(0);
        histogram.record(Long.MAX_VALUE);
        assertEquals(2, histogram.snapshot().samples());
        assertEquals(0, histogram.snapshot().p50UpperBoundNanos());
        assertEquals(Long.MAX_VALUE, histogram.snapshot().p99UpperBoundNanos());
    }

    @Test
    void nextCaptureCannotResetAStillRunningPreviousSample() {
        final PerformanceProbeRecorder recorder = new PerformanceProbeRecorder();
        assertTrue(recorder.startCapture());
        final long token = recorder.enter(PerformanceProbeMetric.RENDER_SCENE);
        recorder.stopCapture();
        assertFalse(recorder.startCapture());
        recorder.exit(PerformanceProbeMetric.RENDER_SCENE, token);
        assertTrue(recorder.awaitQuiescence(100));
        assertEquals(1, recorder.snapshot().metrics().get(PerformanceProbeMetric.RENDER_SCENE).latency().samples());
        assertTrue(recorder.startCapture());
        assertEquals(0, recorder.snapshot().metrics().get(PerformanceProbeMetric.RENDER_SCENE).latency().samples());
        recorder.stopCapture();
    }
}
