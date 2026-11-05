package dev.turboism.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.ShellHandle;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.GuardedServiceFixture;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.test.concurrent.Await;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreShellRuntimeTest {

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
    void anchorSource_stripsJarPrefixAndEntrySuffix() throws Exception {
        assertEquals(
                "file:/Z:/home/developer/turboism-agent.jar",
                CoreShellRuntime.anchorSource(
                                new URL(
                                        "jar:file:/Z:/home/developer/turboism-agent.jar!/META-INF/turboism/i18n/messages.properties"))
                        .toExternalForm());
    }

    @Test
    void anchorSource_locatesExplodedResourceDirectory() throws Exception {
        assertEquals(
                "file:/opt/turboism/resources/",
                CoreShellRuntime.anchorSource(
                                new URL("file:/opt/turboism/resources/META-INF/turboism/i18n/messages.properties"))
                        .toExternalForm());
    }

    @Test
    void anchorSource_passesThroughNonJarUrls() throws Exception {
        final URL fileUrl = new URL("file:/opt/turboism/classes/");
        assertEquals(fileUrl, CoreShellRuntime.anchorSource(fileUrl));
    }

    @Test
    void productionShellDeadlineOutlastsPluginLoads() {
        final PluginLifecyclePolicy production = PluginLifecyclePolicy.production();
        assertTrue(
                production.shellLoadTimeout().compareTo(production.loadTimeout()) > 0,
                "the shell builds host UI synchronously and needs a wider deadline than a plugin load");
    }

    @Test
    void shellEdtWaitBudget_defaultsWhenPropertyAbsent() {
        final String previous = System.getProperty(CoreShellRuntime.SHELL_EDT_WAIT_PROPERTY);
        System.clearProperty(CoreShellRuntime.SHELL_EDT_WAIT_PROPERTY);
        try {
            assertEquals(CoreShellRuntime.DEFAULT_SHELL_EDT_WAIT, CoreShellRuntime.shellEdtWaitBudget());
        } finally {
            restoreProperty(CoreShellRuntime.SHELL_EDT_WAIT_PROPERTY, previous);
        }
    }

    @Test
    void shellEdtWaitBudget_parsesOverrideAndDisablesOnNonPositive() {
        final String previous = System.getProperty(CoreShellRuntime.SHELL_EDT_WAIT_PROPERTY);
        try {
            System.setProperty(CoreShellRuntime.SHELL_EDT_WAIT_PROPERTY, "90");
            assertEquals(Duration.ofSeconds(90), CoreShellRuntime.shellEdtWaitBudget());
            System.setProperty(CoreShellRuntime.SHELL_EDT_WAIT_PROPERTY, "0");
            assertEquals(Duration.ZERO, CoreShellRuntime.shellEdtWaitBudget());
            System.setProperty(CoreShellRuntime.SHELL_EDT_WAIT_PROPERTY, "bogus");
            assertEquals(CoreShellRuntime.DEFAULT_SHELL_EDT_WAIT, CoreShellRuntime.shellEdtWaitBudget());
        } finally {
            restoreProperty(CoreShellRuntime.SHELL_EDT_WAIT_PROPERTY, previous);
        }
    }

    private static void restoreProperty(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void cleanupDefersPluginTeardownUntilAdmittedSdkCallDrains() throws Exception {
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
        final PluginGenerationGuard guard = new PluginGenerationGuard("turboism.core");
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);
        final Thread caller = new Thread(() -> guarded.mutate("x"));
        caller.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        final CoreShellRuntime.ShellLoad state = new CoreShellRuntime.ShellLoad();
        final RecordingShell shell = new RecordingShell();
        state.shell = shell;
        state.guard = guard;
        state.scope = new DisposableScope();
        state.scope.register(() -> {});
        state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

        try (KernelLog log = new KernelLog(temporary.resolve("logs/core.log"))) {
            assertFalse(
                    CoreShellRuntime.cleanupShell(state, null, log, POLICY, false, () -> true),
                    "cleanup must defer while a pre-fence SDK call is held");
            assertEquals(0, shell.closes.get(), "shell close must not run before drain");
            assertFalse(state.scopeAttempted, "scope disposal must not run before drain");

            release.countDown();
            caller.join(5_000);

            assertTrue(CoreShellRuntime.cleanupShell(state, null, log, POLICY, false, () -> true));
            assertEquals(1, shell.closes.get());
            assertTrue(state.scopeClosed);
            assertTrue(state.resourcesClosed);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedScopeCloseKeepsOutcomeAndIsNeverRetriedIntoSuccess() throws Exception {
        final CoreShellRuntime.ShellLoad state = new CoreShellRuntime.ShellLoad();
        final RecordingShell shell = new RecordingShell();
        state.shell = shell;
        final AtomicInteger closerCalls = new AtomicInteger();
        state.scope = new DisposableScope();
        state.scope.register(() -> {
            closerCalls.incrementAndGet();
            throw new IllegalStateException("scope closer failed");
        });
        state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

        try (KernelLog log = new KernelLog(temporary.resolve("logs/core2.log"))) {
            assertFalse(CoreShellRuntime.cleanupShell(state, null, log, POLICY, false, () -> true));
            assertEquals(1, shell.closes.get(), "rollback teardown still ran once");
            assertTrue(state.scopeAttempted);
            assertFalse(state.scopeClosed);
            assertFalse(
                    state.resourcesAttempted, "the classloader must not be attempted while scope disposal is unproven");

            // A re-drive must not re-invoke the failed closer — DisposableScope.close() would
            // return empty success and erase the recorded failure.
            assertFalse(CoreShellRuntime.cleanupShell(state, null, log, POLICY, false, () -> true));
            assertEquals(1, closerCalls.get(), "failed one-shot cleanup must not be retried");
            assertFalse(state.scopeClosed);
            assertFalse(state.resourcesClosed);
        }
    }

    /**
     * A FAILED lane outcome whose rollback left cleanup incomplete must retain the generation —
     * the same retention the timeout branch already had — instead of dropping the scope and
     * classloader references. The retention watcher re-drives {@code cleanupCore}; the sticky
     * one-shot flags keep the failed scope outcome and never re-run its closer.
     */
    @Test
    void failedLoadRetainsGenerationWhileCleanupIsIncomplete() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        try (KernelLog log = new KernelLog(temporary.resolve("logs/core3.log"))) {
            final RetainedPluginGenerations retention = new RetainedPluginGenerations(lane, POLICY, log);
            final CoreShellRuntime.ShellLoad state = new CoreShellRuntime.ShellLoad();
            final RecordingShell shell = new RecordingShell();
            state.shell = shell;
            final AtomicInteger closerCalls = new AtomicInteger();
            state.scope = new DisposableScope();
            state.scope.register(() -> {
                closerCalls.incrementAndGet();
                throw new IllegalStateException("scope closer failed");
            });
            state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

            // The FAILED branch shape: the lane task exited exceptionally, so workerDone is
            // already complete, and the in-worker rollback left disposal unproven.
            final PluginLifecycleLane.Invocation<Object> invocation = lane.submit("turboism.core", "load", () -> {
                throw new IllegalStateException("core load failed");
            });
            invocation.workerDone.get(5, TimeUnit.SECONDS);
            state.cleanupComplete = CoreShellRuntime.cleanupShell(state, null, log, POLICY, false, () -> true);
            assertFalse(state.cleanupComplete);

            CoreShellRuntime.retainIfIncomplete(state, null, log, POLICY, retention, invocation);
            assertEquals(
                    1, retention.retainedCount(), "incomplete shell cleanup must retain the generation on FAILED too");

            // Re-drives keep the generation retained and never re-run the failed closer.
            Thread.sleep(POLICY.retentionRetryInterval().toMillis() * 5);
            assertEquals(1, retention.retainedCount());
            assertEquals(1, closerCalls.get(), "sticky scope failure must not be retried");
            assertFalse(state.resourcesAttempted);
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void failedLoadWithCompleteCleanupRetainsNothing() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        try (KernelLog log = new KernelLog(temporary.resolve("logs/core4.log"))) {
            final RetainedPluginGenerations retention = new RetainedPluginGenerations(lane, POLICY, log);
            final CoreShellRuntime.ShellLoad state = new CoreShellRuntime.ShellLoad();
            state.shell = new RecordingShell();
            state.scope = new DisposableScope();
            state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

            final PluginLifecycleLane.Invocation<Object> invocation = lane.submit("turboism.core", "load", () -> {
                throw new IllegalStateException("core load failed");
            });
            invocation.workerDone.get(5, TimeUnit.SECONDS);
            state.cleanupComplete = CoreShellRuntime.cleanupShell(state, null, log, POLICY, false, () -> true);
            assertTrue(state.cleanupComplete);

            CoreShellRuntime.retainIfIncomplete(state, null, log, POLICY, retention, invocation);
            assertEquals(0, retention.retainedCount(), "a fully cleaned generation has nothing left to retain");
        } finally {
            lane.shutdown();
        }
    }

    /**
     * A shell close body that outlives {@code closeTimeout} must not block the caller: the lane
     * worker is interrupted (advisory only), the timed-out generation is retained, and nothing it
     * still owns — scope, resource loader — is released while its worker is inside
     * {@code shell.close()}. The re-drive finishes the one-shot stages once the worker exits.
     */
    @Test
    void shellCloseOutlivingItsDeadlineRetainsTheGeneration() throws Exception {
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
        try (KernelLog log = new KernelLog(temporary.resolve("logs/core-close.log"))) {
            final RetainedPluginGenerations retention = new RetainedPluginGenerations(lane, policy, log);
            final CoreShellRuntime.ShellLoad state = new CoreShellRuntime.ShellLoad();
            final BlockingShell shell = new BlockingShell(release);
            state.shell = shell;
            state.scope = new DisposableScope();
            state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

            // The admission shape of CoreShellRuntime.close(): a lane task under closeTimeout.
            final PluginLifecycleLane.Invocation<Boolean> invocation = lane.submit(
                    ShellManifest.ID,
                    "close",
                    () -> CoreShellRuntime.cleanupShell(state, null, log, policy, true, () -> true));
            final PluginLifecycleLane.AwaitResult<Boolean> result = lane.await(invocation, policy.closeTimeout(), null);

            assertEquals(PluginLifecycleLane.Outcome.TIMED_OUT, result.outcome, "close must time out, not block");
            assertEquals(1, shell.closes.get(), "the close body ran exactly once");
            assertFalse(state.scopeAttempted, "scope disposal must wait for the close body");
            assertFalse(state.resourcesAttempted, "the resource loader must survive an unfinished close");

            CoreShellRuntime.retainIfIncomplete(state, null, log, policy, retention, invocation);
            assertEquals(1, retention.retainedCount(), "a timed-out close retains its generation");

            release.countDown();
            Await.condition(
                    "retained shell generation must drain once its worker exits",
                    () -> retention.retainedCount() == 0,
                    Duration.ofSeconds(10));
            assertEquals(1, shell.closes.get(), "the retained re-drive never re-enters shell.close()");
            assertTrue(state.scopeClosed);
            assertTrue(state.resourcesClosed);
        } finally {
            release.countDown();
            lane.shutdown();
        }
    }

    /**
     * The shell resource loader is the last thing released: a scope whose closer failed stays
     * unproven, so the loader is neither attempted nor closed, and every retained re-drive must
     * leave it usable — a later pass may still resolve classes through it.
     */
    @Test
    void retainedShellGenerationDoesNotReleaseItsLoader() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final Path roots = Files.createDirectories(temporary.resolve("loader-roots"));
        final Path catalog = roots.resolve("META-INF/turboism/i18n/messages.properties");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, "shell=Shell");
        try (KernelLog log = new KernelLog(temporary.resolve("logs/core-loader.log"))) {
            final RetainedPluginGenerations retention = new RetainedPluginGenerations(lane, POLICY, log);
            final CoreShellRuntime.ShellLoad state = new CoreShellRuntime.ShellLoad();
            state.shell = new RecordingShell();
            state.scope = new DisposableScope();
            state.scope.register(() -> {
                throw new IllegalStateException("scope closer failed");
            });
            final URLClassLoader resources =
                    new URLClassLoader(new URL[] {roots.toUri().toURL()}, null);
            state.resources = resources;

            state.cleanupComplete = CoreShellRuntime.cleanupShell(state, null, log, POLICY, false, () -> true);
            assertFalse(state.cleanupComplete);
            assertTrue(state.scopeAttempted);
            assertFalse(state.scopeClosed);
            CoreShellRuntime.retainIfIncomplete(state, null, log, POLICY, retention, null);

            for (int pass = 0; pass < 3; pass++) {
                assertFalse(
                        CoreShellRuntime.cleanupShell(state, null, log, POLICY, false, () -> true),
                        "an unproven scope keeps the generation incomplete");
            }
            assertFalse(state.resourcesAttempted, "the loader must not be attempted while the scope is unproven");
            assertFalse(state.resourcesClosed);
            assertNotNull(
                    resources.getResource("META-INF/turboism/i18n/messages.properties"),
                    "a retained shell generation keeps its resource loader usable for later re-drives");
            assertEquals(1, retention.retainedCount());
        } finally {
            lane.shutdown();
        }
    }

    /**
     * The shell owns no subscriber-derived plugin executor surface: it never claims or releases a
     * plugin executor set, so its per-generation state carries no claim/release bookkeeping at all.
     * The shell adapter that replaces this state pins the same fact against
     * {@code AdmittedGeneration.Content}.
     */
    @Test
    void shellGenerationStateCarriesNoExecutorClaim() {
        final List<String> unexpected = new ArrayList<>();
        for (Field field : CoreShellRuntime.ShellLoad.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && field.getName().toLowerCase().contains("executor")) {
                unexpected.add(field.getName());
            }
        }
        assertEquals(List.of(), unexpected, "the shell never claims a plugin executor set");
    }

    private static final class RecordingShell implements ShellHandle {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void start(final PluginContext context) {}

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    /** A close body that ignores the lane's advisory interrupt, like a stuck host call. */
    private static final class BlockingShell implements ShellHandle {
        private final CountDownLatch release;
        final AtomicInteger closes = new AtomicInteger();

        private BlockingShell(final CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void start(final PluginContext context) {}

        @Override
        public void close() {
            closes.incrementAndGet();
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException ignored) {
                    Thread.interrupted();
                }
            }
        }
    }
}
