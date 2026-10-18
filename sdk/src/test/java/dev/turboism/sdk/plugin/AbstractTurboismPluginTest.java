package dev.turboism.sdk.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AbstractTurboismPluginTest {

    private static final class TestPlugin extends AbstractTurboismPlugin {}

    private static PluginContext dummyContext() {
        return (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, args) -> null);
    }

    @Test
    void contextBeforeInitThrowsIllegalStateException() {
        final TestPlugin plugin = new TestPlugin();
        final IllegalStateException exception = assertThrows(IllegalStateException.class, plugin::context);
        assertTrue(exception.getMessage().contains("not initialized"));
    }

    @Test
    void disposalScopeBeforeInitThrowsIllegalStateException() {
        final TestPlugin plugin = new TestPlugin();
        final IllegalStateException exception = assertThrows(IllegalStateException.class, plugin::disposalScope);
        assertTrue(exception.getMessage().contains("not initialized"));
    }

    @Test
    void enableBeforeInitThrowsIllegalStateException() {
        final TestPlugin plugin = new TestPlugin();
        assertThrows(IllegalStateException.class, plugin::enable);
    }

    @Test
    void initSetsContextAndPreparesScope() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        final PluginContext context = dummyContext();
        plugin.init(context);

        assertSame(context, plugin.context());
        assertNotNull(plugin.disposalScope());
        assertSame(plugin.disposalScope(), plugin.disposableScope());
    }

    @Test
    void initWithNullThrowsNullPointerException() {
        final TestPlugin plugin = new TestPlugin();
        assertThrows(NullPointerException.class, () -> plugin.init(null));
    }

    @Test
    void initTwiceThrowsIllegalStateException() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        final PluginContext context = dummyContext();
        plugin.init(context);

        final IllegalStateException exception = assertThrows(IllegalStateException.class, () -> plugin.init(context));
        assertTrue(exception.getMessage().contains("already initialized"));
    }

    @Test
    void contextAndScopeAfterShutdownThrowIllegalStateException() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        plugin.init(dummyContext());
        plugin.shutdown();

        final IllegalStateException ctxEx = assertThrows(IllegalStateException.class, plugin::context);
        assertTrue(ctxEx.getMessage().contains("shut down"));

        final IllegalStateException scopeEx = assertThrows(IllegalStateException.class, plugin::disposalScope);
        assertTrue(scopeEx.getMessage().contains("shut down"));
    }

    @Test
    void initAfterShutdownThrowsIllegalStateException() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        plugin.init(dummyContext());
        plugin.shutdown();

        final IllegalStateException ex = assertThrows(IllegalStateException.class, () -> plugin.init(dummyContext()));
        assertTrue(ex.getMessage().contains("shut down"));
    }

    @Test
    void disableClosesRegisteredResourcesInReverseOrder() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        plugin.init(dummyContext());

        final List<String> closed = new ArrayList<>();
        plugin.disposalScope().register(() -> closed.add("first"));
        plugin.disposalScope().register(() -> closed.add("second"));
        plugin.disposalScope().register(() -> closed.add("third"));

        plugin.disable();

        assertEquals(List.of("third", "second", "first"), closed);
    }

    @Test
    void disablePreparesFreshScopeForReEnable() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        plugin.init(dummyContext());

        final List<String> closedCycle1 = new ArrayList<>();
        plugin.disposalScope().register(() -> closedCycle1.add("cycle1"));
        plugin.disable();
        assertEquals(List.of("cycle1"), closedCycle1);

        // Re-enable cycle: register into fresh scope
        final List<String> closedCycle2 = new ArrayList<>();
        plugin.disposalScope().register(() -> closedCycle2.add("cycle2-a"));
        plugin.disposalScope().register(() -> closedCycle2.add("cycle2-b"));
        plugin.disable();
        assertEquals(List.of("cycle2-b", "cycle2-a"), closedCycle2);
    }

    @Test
    void shutdownClosesRemainingResourcesInReverseOrder() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        plugin.init(dummyContext());

        final List<String> closed = new ArrayList<>();
        plugin.disposalScope().register(() -> closed.add("res1"));
        plugin.disposalScope().register(() -> closed.add("res2"));

        plugin.shutdown();

        assertEquals(List.of("res2", "res1"), closed);
    }

    @Test
    void exceptionsDuringCloseDoNotPreventClosingRemaining() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        plugin.init(dummyContext());

        final List<String> closed = new ArrayList<>();
        plugin.disposalScope().register(() -> closed.add("first"));
        plugin.disposalScope().register(() -> {
            closed.add("faulty");
            throw new RuntimeException("boom");
        });
        plugin.disposalScope().register(() -> closed.add("third"));

        final Exception thrown = assertThrows(Exception.class, plugin::disable);
        assertEquals("boom", thrown.getMessage());
        assertEquals(List.of("third", "faulty", "first"), closed);
    }

    @Test
    void concurrentAccessDuringEnableDisableLifecycle() throws Exception {
        final TestPlugin plugin = new TestPlugin();
        final PluginContext context = dummyContext();
        plugin.init(context);

        final int threads = 8;
        final int iterations = 100;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threads);
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger closedCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        assertSame(context, plugin.context());
                        if (threadIndex % 2 == 0) {
                            try {
                                plugin.disposalScope().register(closedCount::incrementAndGet);
                            } catch (IllegalStateException ignored) {
                                // scope may be transiently closing during disable
                            }
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        for (int i = 0; i < 10; i++) {
            plugin.disable();
            Thread.sleep(5);
        }
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(failures.isEmpty(), () -> "failures: " + failures);

        plugin.shutdown();
    }
}
