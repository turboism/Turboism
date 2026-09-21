package dev.turboism.preview;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.GuardedServiceFixture;
import dev.turboism.sdk.plugin.TurboismPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinCorePluginTest {

    private static final PluginLifecyclePolicy POLICY = new PluginLifecyclePolicy(
        1,
        4,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofMillis(100),
        Duration.ofMillis(20)
    );

    @TempDir
    Path temporary;

    @Test
    void jarSourceUrl_stripsJarPrefixAndEntrySuffix() throws Exception {
        assertEquals(
            "file:/Z:/home/developer/turboism-agent.jar",
            BuiltinCorePlugin.jarSourceUrl(new URL(
                "jar:file:/Z:/home/developer/turboism-agent.jar!/META-INF/turboism/core-plugin.json"
            )).toExternalForm()
        );
    }

    @Test
    void jarSourceUrl_passesThroughNonJarUrls() throws Exception {
        final URL fileUrl = new URL("file:/opt/turboism/classes/");
        assertEquals(fileUrl, BuiltinCorePlugin.jarSourceUrl(fileUrl));
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
            @Override public GuardedServiceFixture child() { return this; }
            @Override public void close() { }
        };
        final PluginGenerationGuard guard = new PluginGenerationGuard("turboism.core");
        final GuardedServiceFixture guarded = guard.wrapForTesting(
            service,
            GuardedServiceFixture.class
        );
        final Thread caller = new Thread(() -> guarded.mutate("x"));
        caller.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        final BuiltinCorePlugin.CoreLoad state = new BuiltinCorePlugin.CoreLoad();
        final RecordingPlugin plugin = new RecordingPlugin();
        state.plugin = plugin;
        state.enabled = true;
        state.guard = guard;
        state.scope = new DisposableScope();
        state.scope.register(() -> { });
        state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/core.log"))) {
            assertFalse(
                BuiltinCorePlugin.cleanupCore(state, log, POLICY, false),
                "cleanup must defer while a pre-fence SDK call is held"
            );
            assertEquals(0, plugin.disables.get(), "disable must not run before drain");
            assertEquals(0, plugin.shutdowns.get(), "shutdown must not run before drain");
            assertFalse(state.scopeAttempted, "scope disposal must not run before drain");

            release.countDown();
            caller.join(5_000);

            assertTrue(BuiltinCorePlugin.cleanupCore(state, log, POLICY, false));
            assertEquals(1, plugin.disables.get());
            assertEquals(1, plugin.shutdowns.get());
            assertTrue(state.scopeClosed);
            assertTrue(state.resourcesClosed);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedScopeCloseKeepsOutcomeAndIsNeverRetriedIntoSuccess() throws Exception {
        final BuiltinCorePlugin.CoreLoad state = new BuiltinCorePlugin.CoreLoad();
        final RecordingPlugin plugin = new RecordingPlugin();
        state.plugin = plugin;
        state.enabled = false;
        final AtomicInteger closerCalls = new AtomicInteger();
        state.scope = new DisposableScope();
        state.scope.register(() -> {
            closerCalls.incrementAndGet();
            throw new IllegalStateException("scope closer failed");
        });
        state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/core2.log"))) {
            assertFalse(BuiltinCorePlugin.cleanupCore(state, log, POLICY, false));
            assertEquals(1, plugin.shutdowns.get(), "rollback teardown still ran once");
            assertTrue(state.scopeAttempted);
            assertFalse(state.scopeClosed);
            assertFalse(
                state.resourcesAttempted,
                "the classloader must not be attempted while scope disposal is unproven"
            );

            // A re-drive must not re-invoke the failed closer — DisposableScope.close() would
            // return empty success and erase the recorded failure.
            assertFalse(BuiltinCorePlugin.cleanupCore(state, log, POLICY, false));
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
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/core3.log"))) {
            final RetainedPluginGenerations retention =
                new RetainedPluginGenerations(lane, POLICY, log);
            final BuiltinCorePlugin.CoreLoad state = new BuiltinCorePlugin.CoreLoad();
            final RecordingPlugin plugin = new RecordingPlugin();
            state.plugin = plugin;
            final AtomicInteger closerCalls = new AtomicInteger();
            state.scope = new DisposableScope();
            state.scope.register(() -> {
                closerCalls.incrementAndGet();
                throw new IllegalStateException("scope closer failed");
            });
            state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

            // The FAILED branch shape: the lane task exited exceptionally, so workerDone is
            // already complete, and the in-worker rollback left disposal unproven.
            final PluginLifecycleLane.Invocation<Object> invocation =
                lane.submit("turboism.core", "load", () -> {
                    throw new IllegalStateException("core load failed");
                });
            invocation.workerDone.get(5, TimeUnit.SECONDS);
            state.cleanupComplete =
                BuiltinCorePlugin.cleanupCore(state, log, POLICY, false);
            assertFalse(state.cleanupComplete);

            BuiltinCorePlugin.retainIfIncomplete(state, log, POLICY, retention, invocation);
            assertEquals(
                1,
                retention.retainedCount(),
                "incomplete core cleanup must retain the generation on FAILED too"
            );

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
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/core4.log"))) {
            final RetainedPluginGenerations retention =
                new RetainedPluginGenerations(lane, POLICY, log);
            final BuiltinCorePlugin.CoreLoad state = new BuiltinCorePlugin.CoreLoad();
            state.plugin = new RecordingPlugin();
            state.scope = new DisposableScope();
            state.resources = new URLClassLoader(new URL[0], getClass().getClassLoader());

            final PluginLifecycleLane.Invocation<Object> invocation =
                lane.submit("turboism.core", "load", () -> {
                    throw new IllegalStateException("core load failed");
                });
            invocation.workerDone.get(5, TimeUnit.SECONDS);
            state.cleanupComplete =
                BuiltinCorePlugin.cleanupCore(state, log, POLICY, false);
            assertTrue(state.cleanupComplete);

            BuiltinCorePlugin.retainIfIncomplete(state, log, POLICY, retention, invocation);
            assertEquals(
                0,
                retention.retainedCount(),
                "a fully cleaned generation has nothing left to retain"
            );
        } finally {
            lane.shutdown();
        }
    }

    private static final class RecordingPlugin implements TurboismPlugin {
        final AtomicInteger disables = new AtomicInteger();
        final AtomicInteger shutdowns = new AtomicInteger();

        @Override
        public void disable() {
            disables.incrementAndGet();
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
        }
    }
}
