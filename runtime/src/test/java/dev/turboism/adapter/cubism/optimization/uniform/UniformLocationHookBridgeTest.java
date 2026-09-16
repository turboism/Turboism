package dev.turboism.adapter.cubism.optimization.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UniformLocationHookBridgeTest {
    public static final class Context {
        boolean shared;
        boolean created = true;
        int sharedReads;
        public boolean isShared() { sharedReads++; return shared; }
        public boolean isCreated() { return created; }
    }
    public static final class GL {
        final Context context;
        GL(Context context) { this.context = context; }
        public Context getContext() { return context; }
    }
    public static final class Frame {
        final GL gl;
        Frame(GL gl) { this.gl = gl; }
        public GL getGL() { return gl; }
    }
    private static Object current;
    public static Object currentContext() { return current; }
    @AfterEach void cleanup() {
        System.clearProperty(UniformLocationHookBridge.ENABLE_PROPERTY);
        System.clearProperty(UniformLocationHookBridge.SHADOW_PROPERTY);
        for (String key : UniformLocationHookBridge.slots()) System.getProperties().remove(key);
        current = null;
    }
    private UniformLocationHookBridge bridge() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        return new UniformLocationHookBridge(
            lookup.findVirtual(Frame.class, "getGL", MethodType.methodType(GL.class)).asType(MethodType.methodType(Object.class, Object.class)),
            lookup.findVirtual(GL.class, "getContext", MethodType.methodType(Context.class)).asType(MethodType.methodType(Object.class, Object.class)),
            lookup.findStatic(getClass(), "currentContext", MethodType.methodType(Object.class)),
            lookup.findVirtual(Context.class, "isShared", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class)),
            lookup.findVirtual(Context.class, "isCreated", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class)));
    }
    @Test void installsTypedSlotsAndPreservesOtherOwnersOnClose() throws Throwable {
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.install();
            for (String key : UniformLocationHookBridge.slots()) assertTrue(System.getProperties().containsKey(key));
            Object replacement = new Object();
            System.getProperties().put(UniformLocationCallSiteTransformer.RECORD_PROPERTY, replacement);
            bridge.close();
            assertSame(replacement, System.getProperties().get(UniformLocationCallSiteTransformer.RECORD_PROPERTY));
            assertFalse(System.getProperties().containsKey(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY));
        }
    }
    @Test void refusesCollisionWithoutPartialPublication() throws Exception {
        Object occupied = new Object();
        System.getProperties().put(UniformLocationHookBridge.ERROR_PROPERTY, occupied);
        try (UniformLocationHookBridge bridge = bridge()) {
            assertThrows(IllegalStateException.class, bridge::install);
            assertFalse(System.getProperties().containsKey(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY));
            assertSame(occupied, System.getProperties().get(UniformLocationHookBridge.ERROR_PROPERTY));
        }
    }
    @Test void cachesOnlyExplicitEnabledOwnedUnsharedFrame() throws Throwable {
        Context context = new Context(); GL gl = new GL(context); Frame frame = new Frame(gl); current = context;
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.install();
            long scope = bridge.begin(frame);
            bridge.record(gl, 7, "color", 21); bridge.error(gl, 0);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "color"));
            bridge.end(scope);
            System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
            scope = bridge.begin(frame);
            bridge.record(gl, 7, "color", 21);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "color"));
            bridge.error(gl, 0);
            MethodHandle lookup = (MethodHandle) System.getProperties().get(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY);
            assertEquals(21, (int) lookup.invokeExact((Object) gl, 7, "color"));
            bridge.end(scope);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "color"));
        }
    }
    @Test void completeMutationCoverageAvoidsRepeatedAllocatingShareLookups() throws Exception {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Context context = new Context(); context.shared = true;
        GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.confirmMutationCoverage(); bridge.install();
            long scope = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
            for (int i = 0; i < 100; i++) {
                assertEquals(8, bridge.lookup(gl, 7, "x"));
                bridge.error(gl, 0);
            }
            assertEquals(1, context.sharedReads, "share state is diagnostic at entry; complete mutation coverage already admits either state");
            context.created = false;
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"), "live context validity must still be checked");
            bridge.end(scope);
        }
    }
    @Test void rejectsSharedUncreatedAndNoncurrentContexts() throws Exception {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Context context = new Context(); GL gl = new GL(context); Frame frame = new Frame(gl);
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.install();
            for (int mode = 0; mode < 3; mode++) {
                context.shared = mode == 0; context.created = mode != 1; current = mode == 2 ? new Context() : context;
                long scope = bridge.begin(frame);
                bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
                assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"));
                bridge.end(scope);
            }
            assertEquals(3L, bridge.statistics().get("rejectedFrames"));
        }
    }
    @Test void mutationAndWrongContextErrorCannotLeaveStaleCache() throws Exception {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.install();
            long scope = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
            bridge.invalidate();
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x")); bridge.end(scope);
            scope = bridge.begin(new Frame(gl)); bridge.record(gl, 7, "x", 8);
            bridge.error(new GL(new Context()), 0); bridge.error(gl, 0);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x")); bridge.end(scope);
        }
    }
    @Test void shadowRetainsNativeQueryAndRetiresOnMismatch() throws Exception {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        System.setProperty(UniformLocationHookBridge.SHADOW_PROPERTY, "true");
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.install(); long scope = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"));
            bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
            assertEquals(1L, bridge.statistics().get("shadowQueries"));
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"));
            bridge.record(gl, 7, "x", 99);
            assertEquals(1L, bridge.statistics().get("shadowMismatches"));
            assertEquals(0L, bridge.statistics().get("active"));
            bridge.end(scope);
            assertEquals(0L, bridge.begin(new Frame(gl)));
        }
    }
    @Test void sharedReuseRequiresCoverageAndWaitsForEveryMutation() throws Exception {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Context context = new Context(); context.shared = true;
        GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.confirmMutationCoverage(); bridge.install();
            long frame = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
            assertEquals(8, bridge.lookup(gl, 7, "x"));
            long first = bridge.beginMutation(), second = bridge.beginMutation();
            assertTrue(first != 0L && second != 0L && first != second);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x")); bridge.end(frame);
            bridge.endMutation(first);
            frame = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 99); bridge.error(gl, 0);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"));
            bridge.endMutation(second);
            bridge.record(gl, 7, "x", 99); bridge.error(gl, 0);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"), "retired frame cannot revive after writers finish");
            bridge.end(frame);
            frame = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 99); bridge.error(gl, 0);
            assertEquals(99, bridge.lookup(gl, 7, "x")); bridge.end(frame);
            assertEquals(0L, bridge.statistics().get("mutationsInFlight"));
        }
    }
    @Test void concurrentWriterBlocksNewFrameAndCannotPublishLateNativeResult() throws Exception {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Context context = new Context(); context.shared = true;
        GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.confirmMutationCoverage(); bridge.install();
            long frame = bridge.begin(new Frame(gl));
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
            Thread writer = new Thread(() -> {
                long mutation = bridge.beginMutation(); started.countDown();
                try { finish.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { bridge.endMutation(mutation); }
            });
            writer.start();
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
                assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x")); bridge.end(frame);
                frame = bridge.begin(new Frame(gl));
                bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
                assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x")); bridge.end(frame);
            } finally { finish.countDown(); writer.join(5000); }
            assertFalse(writer.isAlive());
            assertEquals(0L, bridge.statistics().get("mutationsInFlight"));
            frame = bridge.begin(new Frame(gl)); bridge.record(gl, 7, "x", 11); bridge.error(gl, 0);
            assertEquals(11, bridge.lookup(gl, 7, "x")); bridge.end(frame);
        }
    }
    @Test void duplicateMutationExitRetiresReuseRatherThanUnderflowing() throws Exception {
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.confirmMutationCoverage(); bridge.install();
            long mutation = bridge.beginMutation(); bridge.endMutation(mutation); bridge.endMutation(mutation);
            assertEquals(0L, bridge.statistics().get("active"));
            assertEquals(0L, bridge.statistics().get("mutationsInFlight"));
        }
    }
    @Test void contextAccessorFailureDisablesReuseAndReturnsMiss() throws Exception {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.install();
            bridge.begin(new Object());
            assertEquals(Integer.MIN_VALUE, bridge.lookup(new Object(), 7, "x"));
            assertTrue(bridge.statistics().get("failures") > 0L);
        }
    }
}
