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
