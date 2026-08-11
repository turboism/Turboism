package dev.turboism.performance;

import dev.turboism.sdk.performance.PerformanceSnapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceSnapshotTest {

    @Test
    void carriesAllRecordedFields() {
        final PerformanceSnapshot snapshot = PerformanceSnapshot.of(
            1234L, 12.5, 1024L, 512L, 60.0, 1800L
        );
        assertEquals(1234L, snapshot.timestampEpochMs());
        assertEquals(12.5, snapshot.cpuPercent());
        assertEquals(1024L, snapshot.jvmHeapBytes());
        assertEquals(512L, snapshot.jvmNonHeapBytes());
        assertEquals(60.0, snapshot.fps());
        assertEquals(1800L, snapshot.renderedFrames());
        assertEquals(0L, snapshot.diskReadBytes());
        assertEquals(0L, snapshot.diskWriteBytes());
    }

    @Test
    void carriesCumulativeGcCounters() {
        final PerformanceSnapshot snapshot = new PerformanceSnapshot(
            1234L, 12.5, 1024L, 512L, 60.0, 1800L, 0L, 0L, 42L, 317L
        );
        assertEquals(42L, snapshot.gcCollections());
        assertEquals(317L, snapshot.gcPauseMillis());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
            () -> PerformanceSnapshot.of(-1L, 0.0, 0L, 0L, 0.0, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> PerformanceSnapshot.of(0L, -0.1, 0L, 0L, 0.0, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> PerformanceSnapshot.of(0L, 100.1, 0L, 0L, 0.0, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> PerformanceSnapshot.of(0L, Double.NaN, 0L, 0L, 0.0, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> PerformanceSnapshot.of(0L, 0.0, -1L, 0L, 0.0, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> PerformanceSnapshot.of(0L, 0.0, 0L, -1L, 0.0, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> PerformanceSnapshot.of(0L, 0.0, 0L, 0L, -1.0, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> PerformanceSnapshot.of(0L, 0.0, 0L, 0L, 0.0, -1L));
        assertThrows(IllegalArgumentException.class,
            () -> new PerformanceSnapshot(0L, 0.0, 0L, 0L, 0.0, 0L, 0L, 0L, -1L, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> new PerformanceSnapshot(0L, 0.0, 0L, 0L, 0.0, 0L, 0L, 0L, 0L, -1L));
    }

    @Test
    void unavailableSnapshotIsZeroedButValid() {
        final PerformanceSnapshot snapshot = PerformanceSnapshot.unavailable(42L);
        assertEquals(42L, snapshot.timestampEpochMs());
        assertEquals(0.0, snapshot.cpuPercent());
        assertEquals(0.0, snapshot.fps());
        assertTrue(snapshot.jvmHeapBytes() == 0L && snapshot.diskWriteBytes() == 0L);
    }
}
