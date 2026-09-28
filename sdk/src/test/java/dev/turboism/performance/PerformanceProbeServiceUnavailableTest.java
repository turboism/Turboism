package dev.turboism.performance;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.performance.PerformanceProbeService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PerformanceProbeServiceUnavailableTest {

    @Test
    void unavailableSnapshotFailsClosedWithoutHostExceptions() {
        final PerformanceProbeService service = PerformanceProbeService.unavailable();
        final UnsupportedOperationException failure =
                assertThrows(UnsupportedOperationException.class, service::snapshot);
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("not available"));
    }

    @Test
    void unavailableSampleFailsClosedWithoutHostExceptions() {
        final PerformanceProbeService service = PerformanceProbeService.unavailable();
        assertThrows(UnsupportedOperationException.class, () -> service.sample(Duration.ofSeconds(1), snapshot -> {}));
        // No host code is ever reached; the consumer is only validated for null.
        final AtomicInteger calls = new AtomicInteger();
        assertThrows(
                UnsupportedOperationException.class,
                () -> service.sample(Duration.ofSeconds(1), snapshot -> calls.incrementAndGet()));
        org.junit.jupiter.api.Assertions.assertEquals(0, calls.get());
    }
}
