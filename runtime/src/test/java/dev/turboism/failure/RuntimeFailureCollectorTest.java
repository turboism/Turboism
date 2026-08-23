package dev.turboism.failure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFailureCollectorTest {

    @Test
    void aggregatesExactKeysWithSaturatingCountsAndImmutableSortedSnapshots() {
        final RuntimeFailureCollector collector = new RuntimeFailureCollector();
        final RuntimeFailure zeta = failure("ZETA", "plugin.zeta", "task.zeta");
        final RuntimeFailure alpha = failure("ALPHA", "plugin.alpha", "task.alpha");

        collector.record(RuntimeFailureDomain.TASK, zeta);
        collector.record(RuntimeFailureDomain.TASK, alpha);
        collector.record(RuntimeFailureDomain.TASK, zeta);

        final RuntimeFailureSnapshot snapshot = collector.snapshot();
        assertEquals(List.of(
            alpha.withCount(1),
            zeta.withCount(2)
        ), snapshot.taskFailures());
        assertTrue(snapshot.storageFailures().isEmpty());
        assertTrue(snapshot.configFailures().isEmpty());
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.taskFailures().add(alpha)
        );
    }

    @Test
    void firstOverflowMergesTheEvictedCountAcrossEveryDomain() {
        for (RuntimeFailureDomain domain : RuntimeFailureDomain.values()) {
            final RuntimeFailureCollector collector = new RuntimeFailureCollector();
            fillToLimit(collector, domain, 2);
            collector.record(domain, failure("NEW_FAILURE", pluginId(domain), "operation"));

            final List<RuntimeFailure> failures = failures(collector.snapshot(), domain);
            final RuntimeFailure overflow = overflow(failures);
            assertEquals(RuntimeFailureCollector.ENTRY_LIMIT, failures.size(), domain.name());
            assertEquals(3, overflow.count(), domain.name());
            assertEquals(
                258,
                failures.stream().mapToLong(RuntimeFailure::count).sum(),
                domain.name()
            );
            assertTrue(failures.stream().noneMatch(value -> value.code().equals("ZZZ_EVICTED")));
        }
    }

    @Test
    void overflowEvictionKeepsSnapshotsInStableKeyOrder() {
        final RuntimeFailureCollector collector = new RuntimeFailureCollector();
        fillToLimit(collector, RuntimeFailureDomain.STORAGE, 2);
        collector.record(
            RuntimeFailureDomain.STORAGE,
            failure("NEW_FAILURE", pluginId(RuntimeFailureDomain.STORAGE), "operation")
        );

        final List<RuntimeFailure> failures = collector.snapshot().storageFailures();
        final List<RuntimeFailure> expected = new ArrayList<>(failures);
        expected.sort(RuntimeFailure.KEY_ORDER);

        assertEquals(expected, failures);
        assertEquals("ENTRY_000", failures.get(0).code());
        assertEquals("FAILURE_COLLECTOR_ENTRY_LIMIT", failures.get(253).code());
        assertEquals("OTHER_AGGREGATE", failures.get(254).code());
    }

    @Test
    void firstOverflowSaturatesTheEvictedAndIncomingCounts() {
        final RuntimeFailureCollector collector = new RuntimeFailureCollector();
        fillToLimit(collector, RuntimeFailureDomain.TASK, Long.MAX_VALUE - 2);
        collector.record(
            RuntimeFailureDomain.TASK,
            failure("NEW_FAILURE", pluginId(RuntimeFailureDomain.TASK), "operation").withCount(10)
        );

        assertEquals(
            Long.MAX_VALUE,
            overflow(failures(collector.snapshot(), RuntimeFailureDomain.TASK)).count()
        );
    }

    @Test
    void concurrentRecordingAndSnapshotsRemainLinearized() throws Exception {
        final RuntimeFailureCollector collector = new RuntimeFailureCollector();
        final RuntimeFailure failure = failure("FAILED", "plugin.concurrent", "task.concurrent");
        final int workers = 8;
        final int iterations = 1_000;
        final CountDownLatch ready = new CountDownLatch(workers);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            final Thread thread = new Thread(() -> {
                ready.countDown();
                await(start);
                for (int index = 0; index < iterations; index++) {
                    collector.record(RuntimeFailureDomain.TASK, failure);
                    collector.snapshot();
                }
            });
            thread.start();
            threads.add(thread);
        }
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }

        assertEquals(
            (long) workers * iterations,
            collector.snapshot().taskFailures().get(0).count()
        );
    }

    private static void fillToLimit(
        final RuntimeFailureCollector collector,
        final RuntimeFailureDomain domain,
        final long evictedCount
    ) {
        for (int index = 0; index < RuntimeFailureCollector.ENTRY_LIMIT - 2; index++) {
            collector.record(domain, failure(
                "ENTRY_%03d".formatted(index),
                pluginId(domain),
                "operation"
            ));
        }
        collector.record(domain, failure("OTHER_AGGREGATE", pluginId(domain), "operation").withCount(2));
        collector.record(domain, failure("ZZZ_EVICTED", pluginId(domain), "operation")
            .withCount(evictedCount));
    }

    private static RuntimeFailure overflow(final List<RuntimeFailure> failures) {
        return failures.stream()
            .filter(value -> value.code().equals("FAILURE_COLLECTOR_ENTRY_LIMIT"))
            .findFirst()
            .orElseThrow();
    }

    private static String pluginId(final RuntimeFailureDomain domain) {
        return "plugin." + domain.name().toLowerCase();
    }

    private static List<RuntimeFailure> failures(
        final RuntimeFailureSnapshot snapshot,
        final RuntimeFailureDomain domain
    ) {
        return switch (domain) {
            case TASK -> snapshot.taskFailures();
            case STORAGE -> snapshot.storageFailures();
            case CONFIG -> snapshot.configFailures();
            case EVENT -> snapshot.eventFailures();
        };
    }

    private static RuntimeFailure failure(
        final String code,
        final String pluginId,
        final String operationId
    ) {
        return new RuntimeFailure(
            code,
            "ERROR",
            "execution",
            pluginId,
            operationId,
            null,
            "Runtime operation failed safely.",
            null,
            1
        );
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
