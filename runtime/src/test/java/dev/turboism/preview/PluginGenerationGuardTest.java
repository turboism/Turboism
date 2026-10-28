package dev.turboism.preview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.GuardedServiceFixture;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.sdk.plugin.PluginServiceUnavailableException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PluginGenerationGuardTest {

    @Test
    void baseDeformerCollectionsPreserveClosedSubtypesAndKeepTheirGuards() {
        var guard = new PluginGenerationGuard("p");
        var warp = (dev.turboism.sdk.cubism.model.WarpDeformer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.cubism.model.WarpDeformer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "id" -> new dev.turboism.sdk.cubism.id.DeformerId("warp");
                    case "grid" ->
                        new dev.turboism.sdk.cubism.model.WarpGrid(
                                1,
                                1,
                                false,
                                java.util.List.of(
                                        new dev.turboism.sdk.cubism.model.Point2(0, 0),
                                                new dev.turboism.sdk.cubism.model.Point2(1, 0),
                                        new dev.turboism.sdk.cubism.model.Point2(0, 1),
                                                new dev.turboism.sdk.cubism.model.Point2(1, 1)));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var rotation = (dev.turboism.sdk.cubism.model.RotationDeformer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.cubism.model.RotationDeformer.class},
                (proxy, method, arguments) -> new dev.turboism.sdk.cubism.id.DeformerId("rotation"));
        var source = (dev.turboism.sdk.cubism.model.Deformers) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.cubism.model.Deformers.class},
                (proxy, method, arguments) ->
                        method.getName().equals("all") ? java.util.List.of(warp, rotation) : warp);
        var wrapped = guard.wrapForTesting(source, dev.turboism.sdk.cubism.model.Deformers.class);
        var values = wrapped.all();
        assertTrue(values.get(0) instanceof dev.turboism.sdk.cubism.model.WarpDeformer);
        assertTrue(values.get(1) instanceof dev.turboism.sdk.cubism.model.RotationDeformer);
        assertSame(values.get(0), wrapped.find(new dev.turboism.sdk.cubism.id.DeformerId("warp")));
        assertEquals(
                1,
                ((dev.turboism.sdk.cubism.model.WarpDeformer) values.get(0))
                        .grid()
                        .rows());
        assertEquals("rotation", values.get(1).id().value());
        guard.fence();
        assertThrows(
                IllegalStateException.class, () -> ((dev.turboism.sdk.cubism.model.WarpDeformer) values.get(0)).grid());
        assertThrows(IllegalStateException.class, values.get(1)::id);
    }

    @Test
    void fencedContextDeniesNewAdmissionButKeepsDiagnostics() {
        final AtomicReference<String> logSink = new AtomicReference<>();
        final PluginContext delegate = stubContext(new DisposableScope(), new SinkLogger(logSink));
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
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);
        final GuardedServiceFixture child = guarded.child();

        assertEquals("m:a", guarded.mutate("a"));
        assertEquals("m:c", child.mutate("c"));

        guard.fence();

        assertThrows(IllegalStateException.class, () -> guarded.mutate("b"));
        assertThrows(
                IllegalStateException.class,
                () -> child.mutate("d"),
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
        final GuardedServiceFixture guarded = guard.wrapForTesting(registration, GuardedServiceFixture.class);

        final DisposableScope scope = new DisposableScope();
        scope.register(guarded::close);
        guard.fence();
        scope.seal();
        scope.close();

        assertEquals(1, registration.closes.get(), "scope teardown must reach the guarded registration's close()");
    }

    @Test
    void terminalBypassIsExactVerbAndZeroArgumentOnly() {
        // The documented surface: a zero-arg terminal verb reaches the delegate after
        // fencing; the same verb with an argument and ordinary work stay fenced.
        final FixtureService service = new FixtureService();
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);
        guard.fence();

        guarded.close();
        assertEquals(1, service.closes.get());
        assertThrows(IllegalStateException.class, () -> guarded.close("reason"));
        assertThrows(IllegalStateException.class, () -> guarded.mutate("x"));
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
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

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
        final GuardedServiceFixture first = guard.wrapForTesting(service, GuardedServiceFixture.class);
        final GuardedServiceFixture second = guard.wrapForTesting(service, GuardedServiceFixture.class);
        assertSame(first, second);
        assertTrue(Proxy.isProxyClass(first.getClass()));
    }

    @Test
    void serviceDirectoryRequireAndFindReturnGuardedProxiesAndEnforceFence() {
        final FixtureService service = new FixtureService();
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);

        final GuardedServiceFixture viaRequire = guardedDirectory.require(GuardedServiceFixture.class);
        final GuardedServiceFixture viaFind =
                guardedDirectory.find(GuardedServiceFixture.class).orElseThrow();

        assertNotSame(service, viaRequire);
        assertNotSame(service, viaFind);
        assertSame(viaRequire, viaFind);
        assertTrue(Proxy.isProxyClass(viaRequire.getClass()));

        assertEquals("m:req", viaRequire.mutate("req"));
        assertEquals("m:find", viaFind.mutate("find"));

        guard.fence();

        assertThrows(IllegalStateException.class, () -> viaRequire.mutate("post"));
        assertThrows(IllegalStateException.class, () -> viaFind.mutate("post"));
    }

    @Test
    void inFlightCallsOnDirectoryServiceKeepDrainedFalse() throws Exception {
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
                return "done:" + value;
            }
        };
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);
        final GuardedServiceFixture guarded = guardedDirectory.require(GuardedServiceFixture.class);

        final AtomicReference<String> outcome = new AtomicReference<>();
        final Thread caller = new Thread(() -> outcome.set(guarded.mutate("inFlight")));
        caller.start();

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            guard.fence();
            assertFalse(guard.drained(), "running call on directory-discovered service must keep guard non-drained");
            assertEquals(1, guard.inFlightCalls());
        } finally {
            release.countDown();
            caller.join(5_000);
        }
        assertFalse(caller.isAlive());
        assertTrue(guard.drained());
        assertEquals(0, guard.inFlightCalls());
        assertEquals("done:inFlight", outcome.get());
    }

    @Test
    void terminalCloseOnDirectoryServiceSucceedsAfterFence() {
        final FixtureService service = new FixtureService();
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);
        final GuardedServiceFixture guarded = guardedDirectory.require(GuardedServiceFixture.class);

        guard.fence();

        guarded.close();
        assertEquals(1, service.closes.get());
        assertTrue(guard.drained());
        assertEquals(0, guard.inFlightCalls());

        assertThrows(IllegalStateException.class, () -> guarded.mutate("denied"));
        assertThrows(IllegalStateException.class, () -> guarded.close("with-arg"));
    }

    @Test
    void nestedSdkHandlesFromDirectoryServiceAreGuarded() {
        final FixtureService service = new FixtureService();
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);
        final GuardedServiceFixture parent = guardedDirectory.require(GuardedServiceFixture.class);
        final GuardedServiceFixture child = parent.child();

        assertEquals("m:child", child.mutate("child"));
        guard.fence();

        assertThrows(IllegalStateException.class, () -> child.mutate("denied"));
        child.close();
        assertEquals(1, service.closes.get());
    }

    @Test
    void serviceDirectoryThrowsStructuredUnavailableExceptionWhenMissing() {
        final FixtureService service = new FixtureService();
        final PluginServiceDirectory directory = createDirectory(service);
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final PluginServiceDirectory guardedDirectory = guard.wrapForTesting(directory, PluginServiceDirectory.class);

        assertTrue(guardedDirectory.find(PluginPaths.class).isEmpty());
        assertThrows(PluginServiceUnavailableException.class, () -> guardedDirectory.require(PluginPaths.class));
    }

    @Test
    void primitiveArrayInputAndOutputPreserveDefensiveCopy() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final ArrayFixtureService service = new ArrayFixtureService();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final byte[] input = new byte[] {1, 2, 3};
        final byte[] output = guarded.transferBytes(input);

        assertNotSame(input, output);
        assertNotSame(input, service.receivedBytes);
        assertArrayEquals(new byte[] {1, 2, 3}, output);

        input[0] = 99;
        assertEquals(1, output[0]);
        assertEquals(1, service.receivedBytes[0]);

        output[1] = 88;
        assertEquals(2, service.receivedBytes[1]);
    }

    @Test
    void multiDimensionalPrimitiveArrayPreservesDeepDefensiveCopyAndHandlesNulls() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final ArrayFixtureService service = new ArrayFixtureService();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final byte[][] matrix = new byte[][] {{1, 2}, null, {3, 4, 5}};
        final byte[][] output = guarded.transferMatrix(matrix);

        assertNotSame(matrix, output);
        assertNotSame(matrix, service.receivedMatrix);
        assertNotSame(matrix[0], output[0]);
        assertNull(output[1]);
        assertNotSame(matrix[2], output[2]);

        assertArrayEquals(new byte[] {1, 2}, output[0]);
        assertArrayEquals(new byte[] {3, 4, 5}, output[2]);

        matrix[0][0] = 77;
        assertEquals(1, output[0][0]);
        assertEquals(1, service.receivedMatrix[0][0]);
    }

    @Test
    void objectHandleArrayInputUnwrapsAndOutputProxiesElements() {
        final PluginGenerationGuard guard = new PluginGenerationGuard("p");
        final ArrayFixtureService service = new ArrayFixtureService();
        final GuardedServiceFixture guarded = guard.wrapForTesting(service, GuardedServiceFixture.class);

        final FixtureService child1 = new FixtureService();
        final GuardedServiceFixture guardedChild1 = guard.wrapForTesting(child1, GuardedServiceFixture.class);
        final GuardedServiceFixture[] array = new GuardedServiceFixture[] {guardedChild1, null};

        final GuardedServiceFixture[] output = guarded.transferArray(array);

        assertNotSame(array, output);
        assertSame(child1, service.receivedArray[0], "proxy should be unwrapped to delegate before reaching service");
        assertNull(service.receivedArray[1]);

        assertNotNull(output[0]);
        assertNull(output[1]);
        assertTrue(Proxy.isProxyClass(output[0].getClass()));
        assertEquals("m:nested", output[0].mutate("nested"));

        guard.fence();
        assertThrows(IllegalStateException.class, () -> output[0].mutate("post"));
        output[0].close();
        assertEquals(1, child1.closes.get());
    }

    private static PluginServiceDirectory createDirectory(final GuardedServiceFixture service) {
        return new PluginServiceDirectory() {
            @Override
            public Set<PluginService> installed() {
                return Set.of();
            }

            @Override
            public <T> Optional<T> find(final Class<T> serviceType) {
                if (serviceType == GuardedServiceFixture.class) {
                    return Optional.of(serviceType.cast(service));
                }
                return Optional.empty();
            }
        };
    }

    private static class ArrayFixtureService implements GuardedServiceFixture {
        byte[] receivedBytes;
        byte[][] receivedMatrix;
        GuardedServiceFixture[] receivedArray;

        @Override
        public String mutate(final String value) {
            return value;
        }

        @Override
        public GuardedServiceFixture child() {
            return this;
        }

        @Override
        public void close() {}

        @Override
        public byte[] transferBytes(final byte[] input) {
            this.receivedBytes = input;
            return input;
        }

        @Override
        public byte[][] transferMatrix(final byte[][] input) {
            this.receivedMatrix = input;
            return input;
        }

        @Override
        public GuardedServiceFixture[] transferArray(final GuardedServiceFixture[] input) {
            this.receivedArray = input;
            return input;
        }
    }

    private static PluginContext stubContext(final DisposableScope scope, final PluginLogger logger) {
        return (PluginContext) Proxy.newProxyInstance(
                PluginGenerationGuardTest.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "descriptor" -> null;
                    case "logger" -> logger;
                    case "disposableScope" -> scope;
                    case "paths" ->
                        (dev.turboism.sdk.plugin.PluginPaths) Proxy.newProxyInstance(
                                PluginGenerationGuardTest.class.getClassLoader(),
                                new Class<?>[] {dev.turboism.sdk.plugin.PluginPaths.class},
                                (p, m, a) -> switch (m.getName()) {
                                    case "dataDir" -> Path.of("data");
                                    case "stateDir" -> Path.of("state");
                                    case "toString" -> "paths";
                                    default -> null;
                                });
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static class FixtureService implements GuardedServiceFixture {
        final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();

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

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {
            sink.set(message);
        }

        @Override
        public void warn(final String message) {}

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }
}
