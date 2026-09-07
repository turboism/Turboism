package dev.turboism.adapter.cubism.optimization.serialization;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FloatArrayParseBridgeTest {
    @Test void closeCannotBeFollowedByInFlightCallbackRepopulatingKeys() throws Exception {
        Properties original = System.getProperties();
        CountDownLatch admitted = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        Properties controlled = new Properties() {
            @Override public String getProperty(String key) {
                String value = super.getProperty(key);
                if (FloatArrayParseBridge.ENABLE_PROPERTY.equals(key) && pauseOnce.compareAndSet(true, false)) {
                    admitted.countDown();
                    try {
                        if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback not resumed");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
                return value;
            }
        };
        controlled.putAll(original);
        controlled.setProperty(FloatArrayParseBridge.ENABLE_PROPERTY, "true");
        FloatArrayParseBridge bridge = new FloatArrayParseBridge();
        Thread parser = null, closer = null;
        try {
            System.setProperties(controlled);
            bridge.install();
            @SuppressWarnings("unchecked")
            BiFunction<Object, Object, Object> callback = (BiFunction<Object, Object, Object>)
                controlled.get(FloatArrayParseBridge.CALLBACK_PROPERTY);
            FutureTask<Object> parse = new FutureTask<>(() -> callback.apply(2, List.of("1.25", "-0.0")));
            parser = new Thread(parse, "float-parse-race");
            parser.start();
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            FutureTask<Void> close = new FutureTask<>(() -> { bridge.close(); return null; });
            closer = new Thread(close, "float-close-race");
            closer.start();
            // Old close completes here. A quiescing close blocks on the active callback.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!close.isDone() && closer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(close.isDone() || closer.getState() == Thread.State.BLOCKED);
            resume.countDown();
            parse.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertEquals(0L, bridge.snapshot().get("entries"));
            assertEquals(0L, bridge.snapshot().get("characters"));
            assertEquals(0L, bridge.snapshot().get("active"));
            assertFalse(controlled.containsKey(FloatArrayParseBridge.CALLBACK_PROPERTY));
            assertFalse(controlled.containsKey(FloatArrayParseBridge.STATS_PROPERTY));
            assertNull(callback.apply(1, List.of("5.5")), "retained callback must stay inert");
        } finally {
            resume.countDown();
            if (parser != null) parser.join(5000);
            if (closer != null) closer.join(5000);
            bridge.close();
            System.setProperties(original);
        }
    }
}
