package dev.turboism.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.GuardedServiceFixture;
import dev.turboism.test.concurrent.Await;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The admitted-generation module's own test surface: the stage order and its one-shot semantics,
 * the deferral gates, the retention of a generation whose cleanup could not be proven, and the
 * admission shapes the module resolves without an adapter.
 *
 * <p>The teardown tests drive a stub {@link AdmittedGeneration.Content} through
 * {@code adopt(...).close(...)} — the shell's shape — so they assert the module's contract rather
 * than one kind's bodies. {@code ShellGenerationContentTest} covers the shell adapter itself.</p>
 */
class AdmittedGenerationTest {

    private static final PluginLifecyclePolicy POLICY = new PluginLifecyclePolicy(
            1,
            4,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(100),
            Duration.ofMillis(20));

    @TempDir
    Path temporary;

    @Test
    void guardExistsBeforeTheWorkerBodyRuns() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        try (KernelLog log = new KernelLog(temporary.resolve("logs/guard.log"))) {
            final StubContent content = new StubContent();
            final AdmittedGeneration.Submission<String> submission =
                    AdmittedGeneration.submit(content, admission(lane, log), Duration.ofSeconds(5));

            final AdmittedGeneration.Outcome<String> outcome = submission.await();

            assertEquals(PluginLifecycleLane.Outcome.SUCCEEDED, outcome.kind());
            assertNotNull(content.guardSeenInStart, "the admission guard must exist before the worker runs");
            assertSame(content.guard, content.guardSeenInStart);
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void rejectedAdmissionNeverRunsTheBodyAndStillFencesTheGuard() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        try (KernelLog log = new KernelLog(temporary.resolve("logs/rejected.log"))) {
            final StubContent content = new StubContent();
            lane.stopAdmission();

            final AdmittedGeneration.Outcome<String> outcome = AdmittedGeneration.submit(
                            content, admission(lane, log), Duration.ofSeconds(5))
                    .await();

            assertEquals(PluginLifecycleLane.Outcome.REJECTED, outcome.kind());
            assertEquals(0, content.startCalls, "a rejected generation constructs nothing");
            assertTrue(
                    content.guard.isFenced(), "the pre-created guard is fenced so nothing can admit work through it");
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void teardownDefersUntilAdmittedSdkCallsDrain() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GuardedServiceFixture service = new GuardedServiceFixture() {
            @Override
            public String mutate(final String value) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return value;
            }

            @Override
            public GuardedServiceFixture child() {
                return this;
            }

            @Override
            public void close() {}
        };
        final PluginGenerationGuard guard = new PluginGenerationGuard("test.generation");
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);
        final Thread caller = new Thread(() -> guarded.mutate("x"));
        caller.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        try (KernelLog log = new KernelLog(temporary.resolve("logs/drain.log"))) {
            final StubContent content = new StubContent();
            content.guard = guard;
            final AdmittedGeneration.Handle handle = AdmittedGeneration.adopt(content, admission(lane, log));

            AdmittedGeneration.CloseAttempt attempt = handle.close(() -> true);
            assertEquals(
                    AdmittedGeneration.Gate.SDK_DRAIN, attempt.report().gate(), "a pre-fence SDK call holds the body");
            assertFalse(attempt.report().complete());
            assertEquals(0, content.bodyAttempts, "the body must not run before admitted calls drain");
            assertEquals(0, content.scopeAttempts, "scope disposal must not run before drain");

            release.countDown();
            caller.join(5_000);

            attempt = handle.close(() -> true);
            assertTrue(attempt.report().complete());
            assertEquals(1, content.bodyAttempts);
            assertEquals(1, content.scopeAttempts);
            assertEquals(1, content.resourcesAttempts);
        } finally {
            release.countDown();
            lane.shutdown();
        }
    }

    @Test
    void failedStageOutcomeIsStickyAndNeverRetriedIntoSuccess() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final AtomicInteger closerCalls = new AtomicInteger();
        try (KernelLog log = new KernelLog(temporary.resolve("logs/sticky.log"))) {
            final StubContent content = new StubContent();
            content.scopeCloser = () -> {
                closerCalls.incrementAndGet();
                throw new IllegalStateException("scope closer failed");
            };
            content.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());
            final AdmittedGeneration.Handle handle = AdmittedGeneration.adopt(content, admission(lane, log));

            AdmittedGeneration.CloseAttempt attempt = handle.close(() -> true);
            assertFalse(attempt.report().complete());
            assertFalse(attempt.report().disposalProven());
            assertEquals(1, content.bodyAttempts, "teardown still ran once");
            assertEquals(0, content.resourcesCloses, "the loader must not be released while the scope is unproven");

            // A re-drive must not re-invoke the failed closer — DisposableScope.close() would
            // return empty success and erase the recorded failure.
            attempt = handle.close(() -> true);
            assertFalse(attempt.report().complete());
            assertEquals(1, closerCalls.get(), "failed one-shot cleanup must not be retried");
            assertEquals(1, content.bodyAttempts);
            assertEquals(0, content.resourcesCloses);
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void barrierHoldsTheTeardownBodyUntilOtherGenerationsAreInert() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        try (KernelLog log = new KernelLog(temporary.resolve("logs/barrier.log"))) {
            final StubContent content = new StubContent();
            final AdmittedGeneration.Handle handle = AdmittedGeneration.adopt(content, admission(lane, log));

            AdmittedGeneration.CloseAttempt attempt = handle.close(() -> false);
            assertEquals(
                    AdmittedGeneration.Gate.OTHER_GENERATIONS, attempt.report().gate());
            assertEquals(0, content.bodyAttempts, "the body waits behind the caller's drain barrier");
            assertFalse(attempt.report().complete());

            attempt = handle.close(() -> true);
            assertTrue(attempt.report().complete());
            assertEquals(1, content.bodyAttempts);
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void failedLoadRetainsGenerationWhileCleanupIsIncomplete() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final AtomicInteger closerCalls = new AtomicInteger();
        try (KernelLog log = new KernelLog(temporary.resolve("logs/failed-retain.log"))) {
            final RetainedPluginGenerations retention = new RetainedPluginGenerations(lane, POLICY, log);
            final StubContent content = new StubContent();
            content.failStart = true;
            content.scopeCloser = () -> {
                closerCalls.incrementAndGet();
                throw new IllegalStateException("scope closer failed");
            };
            content.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

            final AdmittedGeneration.Outcome<String> outcome = AdmittedGeneration.submit(
                            content, admission(lane, retention, log), Duration.ofSeconds(5))
                    .await();

            assertEquals(PluginLifecycleLane.Outcome.FAILED, outcome.kind());
            assertEquals(1, content.bodyAttempts, "the failed load tears down what the worker built");
            assertEquals(1, retention.retainedCount(), "incomplete cleanup must retain the generation on FAILED too");

            // Re-drives keep the generation retained and never re-run the failed closer.
            Thread.sleep(POLICY.retentionRetryInterval().toMillis() * 5);
            assertEquals(1, retention.retainedCount());
            assertEquals(1, closerCalls.get(), "sticky stage failure must not be retried");
            assertEquals(0, content.resourcesCloses);
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void failedLoadWithCompleteCleanupRetainsNothing() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        try (KernelLog log = new KernelLog(temporary.resolve("logs/failed-clean.log"))) {
            final RetainedPluginGenerations retention = new RetainedPluginGenerations(lane, POLICY, log);
            final StubContent content = new StubContent();
            content.failStart = true;
            content.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

            final AdmittedGeneration.Outcome<String> outcome = AdmittedGeneration.submit(
                            content, admission(lane, retention, log), Duration.ofSeconds(5))
                    .await();

            assertEquals(PluginLifecycleLane.Outcome.FAILED, outcome.kind());
            assertEquals(1, content.bodyAttempts);
            assertEquals(1, content.scopeAttempts);
            assertEquals(1, content.resourcesCloses);
            assertEquals(0, retention.retainedCount(), "a fully cleaned generation has nothing left to retain");
        } finally {
            lane.shutdown();
        }
    }

    /**
     * A close body that outlives {@code closeTimeout} must not block the caller: the lane worker is
     * interrupted (advisory only), the timed-out generation is retained, and nothing it still
     * owns — scope, resource loader — is released while its worker is inside the body. The re-drive
     * finishes the one-shot stages once the worker exits.
     */
    @Test
    void closeOutlivingItsDeadlineRetainsTheGeneration() throws Exception {
        final PluginLifecyclePolicy policy = new PluginLifecyclePolicy(
                1,
                4,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofMillis(150),
                Duration.ofMillis(100),
                Duration.ofMillis(20));
        final PluginLifecycleLane lane = new PluginLifecycleLane(policy);
        final CountDownLatch release = new CountDownLatch(1);
        try (KernelLog log = new KernelLog(temporary.resolve("logs/close-timeout.log"))) {
            final RetainedPluginGenerations retention = new RetainedPluginGenerations(lane, policy, log);
            final StubContent content = new StubContent();
            content.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());
            content.closeBody = () -> {
                while (true) {
                    try {
                        release.await();
                        return;
                    } catch (InterruptedException ignored) {
                        Thread.interrupted();
                    }
                }
            };
            final AdmittedGeneration.Handle handle = AdmittedGeneration.adopt(content, admission(lane, retention, log));

            final AdmittedGeneration.CloseAttempt attempt = handle.close(() -> true);

            assertEquals(PluginLifecycleLane.Outcome.TIMED_OUT, attempt.outcome(), "close must time out, not block");
            assertEquals(1, content.bodyAttempts, "the close body ran exactly once");
            assertEquals(0, content.scopeAttempts, "scope disposal must wait for the close body");
            assertEquals(0, content.resourcesAttempts, "the resource loader must survive an unfinished close");

            handle.retain(attempt.workerDone(), AdmittedGeneration.Cause.CLOSE, () -> true, null);
            assertEquals(1, retention.retainedCount(), "a timed-out close retains its generation");

            release.countDown();
            Await.condition(
                    "retained generation must drain once its worker exits",
                    () -> retention.retainedCount() == 0,
                    Duration.ofSeconds(10));
            assertEquals(1, content.bodyAttempts, "the retained re-drive never re-enters the close body");
            assertEquals(1, content.scopeAttempts);
            assertEquals(1, content.resourcesCloses);
        } finally {
            release.countDown();
            lane.shutdown();
        }
    }

    /**
     * The resource loader is the last thing released: a scope whose closer failed stays unproven, so
     * the loader is never closed and every retained re-drive must leave it usable — a later pass may
     * still resolve classes through it.
     */
    @Test
    void retainedGenerationDoesNotReleaseItsLoader() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final Path roots = Files.createDirectories(temporary.resolve("loader-roots"));
        final Path catalog = roots.resolve("META-INF/turboism/i18n/messages.properties");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, "shell=Shell");
        try (KernelLog log = new KernelLog(temporary.resolve("logs/loader.log"))) {
            final RetainedPluginGenerations retention = new RetainedPluginGenerations(lane, POLICY, log);
            final StubContent content = new StubContent();
            content.scopeCloser = () -> {
                throw new IllegalStateException("scope closer failed");
            };
            final URLClassLoader resources =
                    new URLClassLoader(new URL[] {roots.toUri().toURL()}, null);
            content.resources = resources;
            final AdmittedGeneration.Handle handle = AdmittedGeneration.adopt(content, admission(lane, retention, log));

            final AdmittedGeneration.CloseAttempt attempt = handle.close(() -> true);
            assertFalse(attempt.report().complete());
            assertFalse(attempt.report().disposalProven());
            handle.retain(attempt.workerDone(), AdmittedGeneration.Cause.CLOSE, () -> true, null);

            for (int pass = 0; pass < 3; pass++) {
                assertFalse(
                        handle.teardown(AdmittedGeneration.Cause.CLOSE, false, () -> true)
                                .complete(),
                        "an unproven scope keeps the generation incomplete");
            }
            assertEquals(0, content.resourcesCloses, "the loader must not be released while the scope is unproven");
            assertNotNull(
                    resources.getResource("META-INF/turboism/i18n/messages.properties"),
                    "a retained generation keeps its resource loader usable for later re-drives");
            assertEquals(1, retention.retainedCount());
        } finally {
            lane.shutdown();
        }
    }

    private static AdmittedGeneration.Admission admission(final PluginLifecycleLane lane, final KernelLog log) {
        return new AdmittedGeneration.Admission(POLICY, lane, new RetainedPluginGenerations(lane, POLICY, log), log);
    }

    private static AdmittedGeneration.Admission admission(
            final PluginLifecycleLane lane, final RetainedPluginGenerations retention, final KernelLog log) {
        return new AdmittedGeneration.Admission(POLICY, lane, retention, log);
    }

    /** A kind-neutral content: the stage bodies are counters and the handles are configurable. */
    private static final class StubContent implements AdmittedGeneration.Content<String> {

        private String id = "test.generation";
        private PluginGenerationGuard guard = new PluginGenerationGuard("test.generation");
        private URLClassLoader resources;
        private AutoCloseable scopeCloser = () -> {};
        private Runnable closeBody = () -> {};
        private boolean failStart;
        private volatile PluginGenerationGuard guardSeenInStart;
        private int startCalls;
        private int hookAttempts;
        private int bodyAttempts;
        private int scopeAttempts;
        private int scopeCloses;
        private int resourcesAttempts;
        private int resourcesCloses;

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean claimsExecutors() {
            return false;
        }

        @Override
        public void bind(final AdmittedGeneration.GenerationState shared) {
            shared.guard = guard;
            final DisposableScope scope = new DisposableScope();
            scope.register(scopeCloser);
            shared.scope = scope;
        }

        @Override
        public String start(final AdmittedGeneration.GenerationState shared, final PluginLifecycleLease lease)
                throws Exception {
            startCalls++;
            guardSeenInStart = shared.guard;
            if (failStart) {
                throw new IllegalStateException("stub generation failed to start");
            }
            return lease.commit(() -> id);
        }

        @Override
        public void claimExecutors(
                final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {}

        @Override
        public void unregisterHooks(final AdmittedGeneration.GenerationState shared) {
            hookAttempts++;
        }

        @Override
        public boolean quiesceBackup(final AdmittedGeneration.GenerationState shared) {
            return true;
        }

        @Override
        public void closeBody(final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {
            bodyAttempts++;
            closeBody.run();
        }

        @Override
        public AdmittedGeneration.StageOutcome closeScope(final AdmittedGeneration.GenerationState shared) {
            if (shared.scope == null) {
                return AdmittedGeneration.StageOutcome.succeeded();
            }
            scopeAttempts++;
            try {
                shared.scope.close();
                scopeCloses++;
                return AdmittedGeneration.StageOutcome.succeeded();
            } catch (Throwable failure) {
                return AdmittedGeneration.StageOutcome.failed();
            }
        }

        @Override
        public AdmittedGeneration.StageOutcome closeResources(final AdmittedGeneration.GenerationState shared) {
            if (!shared.scopeClosed) {
                // Never attempted-but-closed: the loader outlives an unproven scope.
                resourcesAttempts++;
                return AdmittedGeneration.StageOutcome.failed();
            }
            resourcesAttempts++;
            if (resources == null) {
                return AdmittedGeneration.StageOutcome.succeeded();
            }
            try {
                resources.close();
                resourcesCloses++;
                return AdmittedGeneration.StageOutcome.succeeded();
            } catch (Throwable failure) {
                return AdmittedGeneration.StageOutcome.failed();
            }
        }

        @Override
        public void finishDisposal(final AdmittedGeneration.GenerationState shared) {}

        @Override
        public void releaseExecutors(
                final AdmittedGeneration.GenerationState shared, final AdmittedGeneration.Cause cause) {}

        @Override
        public String incompleteMessage() {
            return "stub generation cleanup is incomplete";
        }

        @Override
        public String retainedMessage() {
            return "stub generation retained";
        }
    }
}
