package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HookRegistryTest {

    private static HookContributor contributor(
        final String id,
        final boolean processExit
    ) {
        return new HookContributor() {
            @Override public String id() {
                return id;
            }

            @Override public Phase phase() {
                return Phase.RUNTIME_STARTED;
            }

            @Override public boolean closesOnProcessExit() {
                return processExit;
            }

            @Override public boolean admitted(final HookEnvironment environment) {
                return true;
            }

            @Override public AutoCloseable install(final HookEnvironment environment) {
                return () -> {
                };
            }
        };
    }

    @Test
    void closeAllClosesHandlesInReverseInstallOrder() {
        final HookRegistry registry = new HookRegistry();
        final List<String> closed = new ArrayList<>();
        registry.enroll(contributor("HOOK_A", false), () -> closed.add("A"));
        registry.enroll(contributor("HOOK_B", false), () -> closed.add("B"));
        registry.enroll(contributor("HOOK_C", true), () -> closed.add("C"));
        registry.closeAll(message -> {
        }, message -> {
        });
        assertEquals(List.of("C", "B", "A"), closed);
        assertFalse(registry.contains("HOOK_A"));
    }

    @Test
    void processExitPathClosesOnlyFlaggedHandles() {
        final HookRegistry registry = new HookRegistry();
        final List<String> closed = new ArrayList<>();
        registry.enroll(contributor("HOOK_A", false), () -> closed.add("A"));
        registry.enroll(contributor("HOOK_B", true), () -> closed.add("B"));
        registry.closeOnProcessExit(message -> {
        }, message -> {
        });
        assertEquals(List.of("B"), closed);
        assertTrue(registry.contains("HOOK_A"));
        assertFalse(registry.contains("HOOK_B"));
    }

    @Test
    void flaggedHandlesReportCleanupCompleteWithThePhaseTag() {
        final HookRegistry registry = new HookRegistry();
        final List<String> info = new ArrayList<>();
        registry.enroll(contributor("TURBOISM_PARAMETER_HOOK", true), () -> {
        });
        registry.enroll(contributor("HOOK_PLAIN", false), () -> {
        });
        registry.closeOnProcessExit(message -> {
        }, info::add);
        assertEquals(
            List.of("TURBOISM_PARAMETER_HOOK cleanup=COMPLETE phase=process-exit"),
            info
        );
        info.clear();
        registry.closeAll(message -> {
        }, info::add);
        assertTrue(info.isEmpty());
    }

    @Test
    void aFailingCloseDoesNotStopRemainingHandles() {
        final HookRegistry registry = new HookRegistry();
        final AtomicBoolean secondClosed = new AtomicBoolean(false);
        final List<String> warnings = new ArrayList<>();
        registry.enroll(contributor("HOOK_A", false), () -> secondClosed.set(true));
        registry.enroll(contributor("HOOK_B", false), () -> {
            throw new IllegalStateException("boom");
        });
        registry.closeAll(warnings::add, message -> {
        });
        assertTrue(secondClosed.get());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("HOOK_B"));
    }

    @Test
    void enrollmentRacingTheProcessExitPassNeverCorruptsOrDoubleCloses()
        throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            final HookRegistry registry = new HookRegistry();
            final List<AtomicInteger> closeCounts = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                final AtomicInteger closes = new AtomicInteger();
                closeCounts.add(closes);
                registry.enroll(
                    contributor("HOOK_SEED_" + index, true),
                    closes::incrementAndGet
                );
            }
            final CountDownLatch ready = new CountDownLatch(1);
            final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            final Thread closer = new Thread(() -> {
                try {
                    ready.await(5, TimeUnit.SECONDS);
                    registry.closeOnProcessExit(message -> {
                    }, message -> {
                    });
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            closer.start();
            ready.countDown();
            for (int index = 0; index < 6; index++) {
                final AtomicInteger closes = new AtomicInteger();
                closeCounts.add(closes);
                try {
                    registry.enroll(
                        contributor("HOOK_LATE_" + index, true),
                        closes::incrementAndGet
                    );
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
            closer.join(TimeUnit.SECONDS.toMillis(5));
            assertNull(failures.peek(), "round " + round + " threw");
            for (final AtomicInteger closes : closeCounts) {
                assertTrue(closes.get() <= 1, "handle closed " + closes.get() + " times");
            }
        }
    }
}
