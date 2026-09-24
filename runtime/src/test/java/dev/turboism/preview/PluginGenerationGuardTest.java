package dev.turboism.preview;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.GuardedServiceFixture;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginGenerationGuardTest {

    @Test
    void fencedContextDeniesNewAdmissionButKeepsDiagnostics() {
        final AtomicReference<String> logSink = new AtomicReference<>();
        final PluginContext delegate = stubContext(
            new DisposableScope(),
            new SinkLogger(logSink)
        );
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginContext guarded = guard.wrap(delegate);

        assertNotNull(guarded.paths());
        guard.fence();

        assertThrows(IllegalStateException.class, guarded::paths);
        assertThrows(IllegalStateException.class, guarded::disposableScope);
        assertThrows(IllegalStateException.class, guarded::storage);
        // Diagnostics remain available while fenced so cleanup can still log and describe.
        assertSame(delegate.descriptor(), guarded.descriptor());
        assertNotNull(guarded.logger());
        guarded.logger().info("cleanup still logs");
        assertEquals("cleanup still logs", logSink.get());
    }

    @Test
    void fencePropagatesToHandlesAcquiredBeforeFence() {
        final FixtureService service = new FixtureService();
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final GuardedServiceFixture guarded = guard.wrapForTesting(
            service,
            GuardedServiceFixture.class
        );
        final GuardedServiceFixture child = guarded.child();

        assertEquals("m:a", guarded.mutate("a"));
        assertEquals("m:c", child.mutate("c"));

        guard.fence();

        assertThrows(IllegalStateException.class, () -> guarded.mutate("b"));
        assertThrows(IllegalStateException.class, () -> child.mutate("d"),
            "handles acquired before fencing must deny new admission too");
        // Terminal release still works so teardown can detach handles.
        guarded.close();
        child.close();
        assertEquals(2, service.closes.get());
    }

    @Test
    void terminalCloseInsideScopeRunsAfterFence() throws Exception {
        // The real sequence: acquire a guarded registration, hand it to the plugin scope, fence
        // the generation, then close the scope — the proxy's close() must reach the delegate so
        // resources actually detach (atlas-style blocking closers included).
        final FixtureService registration = new FixtureService();
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final GuardedServiceFixture guarded = guard.wrapForTesting(
            registration,
            GuardedServiceFixture.class
        );

        final DisposableScope scope = new DisposableScope();
        scope.register(guarded::close);
        guard.fence();
        scope.seal();
        scope.close();

        assertEquals(
            1,
            registration.closes.get(),
            "scope teardown must reach the guarded registration's close()"
        );
    }

    @Test
    void inFlightAdmittedCallsKeepDrainedFalse() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final FixtureService service = new FixtureService() {
            @Override
            public String mutate(final String value) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }
        };
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final GuardedServiceFixture guarded = guard.wrapForTesting(
            service,
            GuardedServiceFixture.class
        );

        final AtomicReference<String> outcome = new AtomicReference<>();
        final Thread caller = new Thread(() -> outcome.set(guarded.mutate("x")));
        caller.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        guard.fence();
        assertFalse(guard.drained(), "an admitted call still executing must block disposal");
        release.countDown();
        caller.join(5_000);
        assertTrue(guard.drained());
        assertEquals("done", outcome.get());
    }

    @Test
    void repeatedAccessReturnsSameWrappedHandle() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final FixtureService service = new FixtureService();
        final GuardedServiceFixture first = guard.wrapForTesting(
            service,
            GuardedServiceFixture.class
        );
        final GuardedServiceFixture second = guard.wrapForTesting(
            service,
            GuardedServiceFixture.class
        );
        assertSame(first, second);
        assertTrue(Proxy.isProxyClass(first.getClass()));
    }

    private static PluginContext stubContext(
        final DisposableScope scope,
        final PluginLogger logger
    ) {
        return (PluginContext) Proxy.newProxyInstance(
            PluginGenerationGuardTest.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "descriptor" -> null;
                case "logger" -> logger;
                case "disposableScope" -> scope;
                case "paths" -> (dev.turboism.sdk.plugin.PluginPaths) Proxy.newProxyInstance(
                    PluginGenerationGuardTest.class.getClassLoader(),
                    new Class<?>[] {dev.turboism.sdk.plugin.PluginPaths.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "dataDir" -> Path.of("data");
                        case "stateDir" -> Path.of("state");
                        case "toString" -> "paths";
                        default -> null;
                    }
                );
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static class FixtureService implements GuardedServiceFixture {
        final java.util.concurrent.atomic.AtomicInteger closes =
            new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public String mutate(final String value) {
            return "m:" + value;
        }

        @Override
        public GuardedServiceFixture child() {
            return this;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static final class SinkLogger implements PluginLogger {
        private final AtomicReference<String> sink;

        private SinkLogger(final AtomicReference<String> sink) {
            this.sink = sink;
        }

        @Override public void debug(final String message) { }
        @Override public void info(final String message) { sink.set(message); }
        @Override public void warn(final String message) { }
        @Override public void error(final String message) { }
        @Override public void error(final String message, final Throwable throwable) { }
    }
}
