package dev.turboism.adapter.cubism.optimization.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        int error, queries;
        GL(Context context) { this.context = context; }
        public Context getContext() { return context; }
        public int glGetError() { queries++; return error; }
    }
    /** Stand-in for {@code com.live2d.util.log.a}: singleton field plus static sink. */
    public static final class Logger {
        public static final Logger a = new Logger();
        public final java.util.List<String> messages = new java.util.ArrayList<>();
        public static void b(Logger self, Object message, boolean silent, String tag,
                             int level, Object attachment) {
            self.messages.add((String) message);
        }
    }
    /** Stand-in for the host {@code com.jogamp.opengl.GLException} shape. */
    public static final class GLException extends RuntimeException {
        public GLException(String message) { super(message); }
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
    private UniformLocationHookBridge deferredBridge() throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandles.Lookup own = MethodHandles.lookup();
        var deferred = new UniformLocationHookBridge.DeferredAccessors(
            lookup.findVirtual(GL.class, "glGetError", MethodType.methodType(int.class))
                .asType(MethodType.methodType(int.class, Object.class)),
            Logger.a,
            lookup.findStatic(Logger.class, "b", MethodType.methodType(void.class, Logger.class,
                    Object.class, boolean.class, String.class, int.class, Object.class))
                .asType(MethodType.methodType(void.class, Object.class, Object.class,
                    boolean.class, String.class, int.class, Object.class)),
            lookup.findConstructor(GLException.class, MethodType.methodType(void.class, String.class)));
        return new UniformLocationHookBridge(
            own.findVirtual(Frame.class, "getGL", MethodType.methodType(GL.class)).asType(MethodType.methodType(Object.class, Object.class)),
            own.findVirtual(GL.class, "getContext", MethodType.methodType(Context.class)).asType(MethodType.methodType(Object.class, Object.class)),
            own.findStatic(getClass(), "currentContext", MethodType.methodType(Object.class)),
            own.findVirtual(Context.class, "isShared", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class)),
            own.findVirtual(Context.class, "isCreated", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class)),
            deferred);
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
    @Test void cachesOnlyEnabledOwnedUnsharedFrame() throws Throwable {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "false");
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
    @Test void absentPreferenceDefaultsOnAndExplicitFalseRemainsNative() throws Exception {
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.install();
            long scope = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 8); bridge.error(gl, 0);
            assertEquals(8, bridge.lookup(gl, 7, "x"));
            bridge.end(scope);
            System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "false");
            scope = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 9); bridge.error(gl, 0);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"));
            bridge.end(scope);
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
    /**
     * Deferred mode: an in-frame checkpoint returns GL_NO_ERROR without a
     * native query, the emitted zero flows through the paired error callback
     * and confirms pending locations within the frame exactly like the
     * elision emission, and the frame boundary performs the single real query.
     */
    @Test void deferredCheckpointDefersAndFrameEndConfirmsPending() throws Throwable {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = deferredBridge()) {
            bridge.install();
            long scope = bridge.begin(new Frame(gl));
            bridge.record(gl, 7, "x", 8);
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"),
                "pending before the deferred checkpoint");
            assertEquals(0, bridge.deferQuery(gl, "shader/A.a", true));
            bridge.error(gl, 0);   // the checkpoint's emitted zero, as in the composed body
            assertEquals(8, bridge.lookup(gl, 7, "x"),
                "the deferred checkpoint's zero confirms pending within the frame");
            assertEquals(0, gl.queries, "no native glGetError inside the frame");
            bridge.end(scope);
            assertEquals(1, gl.queries, "the frame boundary performs one real query");
            assertNull(bridge.consumeReport());
            assertEquals(1L, bridge.statistics().get("deferredFrames"));
            assertEquals(1L, bridge.statistics().get("deferredChecks"));
        }
    }
    /**
     * A nonzero frame-end result invalidates the pending entries (never
     * confirmed), logs through the host logger shape and arms the host
     * GLException when the first deferred checkpoint was throwing (z=true).
     */
    @Test void deferredErrorInvalidatesPendingLogsAndArmsThrow() throws Throwable {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Logger.a.messages.clear();
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = deferredBridge()) {
            bridge.install();
            long scope = bridge.begin(new Frame(gl));
            assertEquals(0, bridge.deferQuery(gl, "shader/A.a", true));
            bridge.error(gl, 0);
            bridge.record(gl, 7, "x", 8);
            gl.error = 1280;
            bridge.end(scope);
            assertEquals(1, gl.queries);
            assertEquals(1, Logger.a.messages.size());
            assertTrue(Logger.a.messages.get(0).contains("GL_INVALID_ENUM"));
            Throwable armed = (Throwable) bridge.consumeReport();
            assertInstanceOf(GLException.class, armed);
            assertTrue(armed.getMessage().contains("shader/A.a: glError GL_INVALID_ENUM"));
            assertNull(bridge.consumeReport(), "the armed report is consumed once");
            scope = bridge.begin(new Frame(gl));
            assertEquals(Integer.MIN_VALUE, bridge.lookup(gl, 7, "x"),
                "an erroneous frame never confirms its pending locations");
            bridge.end(scope);
            assertEquals(1L, bridge.statistics().get("deferredErrors"));
            assertEquals(1L, bridge.statistics().get("deferredThrows"));
            assertEquals(1L, bridge.statistics().get("glErrors"));
        }
    }
    /** A first deferred checkpoint with z=false keeps host semantics: log only. */
    @Test void deferredNonThrowingCheckpointLogsWithoutArming() throws Throwable {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Logger.a.messages.clear();
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = deferredBridge()) {
            bridge.install();
            long scope = bridge.begin(new Frame(gl));
            assertEquals(0, bridge.deferQuery(gl, "texture", false));
            bridge.error(gl, 0);
            gl.error = 1285;
            bridge.end(scope);
            assertEquals(1, Logger.a.messages.size());
            assertTrue(Logger.a.messages.get(0).contains("GL_OUT_OF_MEMORY"));
            assertNull(bridge.consumeReport(), "z=false reports by log only");
            assertEquals(0L, bridge.statistics().get("deferredThrows"));
        }
    }
    /** Unmapped codes arm IllegalStateException without logging — the host default branch. */
    @Test void deferredUnknownErrorArmsIllegalStateWithoutLogging() throws Throwable {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Logger.a.messages.clear();
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = deferredBridge()) {
            bridge.install();
            long scope = bridge.begin(new Frame(gl));
            assertEquals(0, bridge.deferQuery(gl, "shader/A.a", false));
            bridge.error(gl, 0);
            gl.error = 1283;
            bridge.end(scope);
            assertTrue(Logger.a.messages.isEmpty(), "unknown codes throw before logging");
            Throwable armed = (Throwable) bridge.consumeReport();
            assertInstanceOf(IllegalStateException.class, armed);
            assertEquals("Not impl : 1283", armed.getMessage());
        }
    }
    /**
     * Outside an owned frame — and for a foreign GL — the checkpoint falls back
     * to the real query, preserving the site's exact upstream semantics.
     */
    @Test void outOfFrameAndForeignGlDeferQueryRunsRealQuery() throws Throwable {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Context context = new Context(); GL gl = new GL(context); current = context;
        GL foreign = new GL(context); foreign.error = 1281;
        try (UniformLocationHookBridge bridge = deferredBridge()) {
            bridge.install();
            gl.error = 1282;
            assertEquals(1282, bridge.deferQuery(gl, "outside", true),
                "no frame: the real glGetError runs");
            assertEquals(1, gl.queries);
            long scope = bridge.begin(new Frame(gl));
            assertEquals(1281, bridge.deferQuery(foreign, "foreign", true),
                "a non-frame GL keeps the real query inside a frame");
            assertEquals(1, foreign.queries);
            assertEquals(1, gl.queries);
            bridge.end(scope);
            assertEquals(1, gl.queries, "no deferred checkpoint ran — no frame-end query");
            assertTrue(bridge.statistics().get("deferredFallbacks") >= 2L);
        }
    }
    /** Without resolved deferred accessors the checkpoint throws for the emitted fallback. */
    @Test void deferQueryWithoutAccessorsThrowsForFallback() throws Throwable {
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = bridge()) {
            bridge.install();
            assertThrows(IllegalStateException.class,
                () -> { bridge.deferQuery(gl, "ctx", true); });
            long scope = bridge.begin(new Frame(gl));
            assertThrows(IllegalStateException.class,
                () -> { bridge.deferQuery(gl, "ctx", true); });
            bridge.end(scope);
            assertEquals(0, gl.queries, "no accessor means nothing may fabricate a result");
        }
    }
    /** An armed report left by an aborted frame is disarmed by the next frame entry. */
    @Test void abortedFrameReportIsDisarmedAtNextBegin() throws Throwable {
        System.setProperty(UniformLocationHookBridge.ENABLE_PROPERTY, "true");
        Context context = new Context(); GL gl = new GL(context); current = context;
        try (UniformLocationHookBridge bridge = deferredBridge()) {
            bridge.install();
            long scope = bridge.begin(new Frame(gl));
            bridge.deferQuery(gl, "shader/A.a", true);
            bridge.error(gl, 0);
            gl.error = 1286;
            bridge.end(scope);          // arms a throw the RETURN consult never consumed
            long next = bridge.begin(new Frame(gl));
            assertNull(bridge.consumeReport(),
                "the previous frame's armed report must not surface in the next frame");
            gl.error = 0;
            bridge.end(next);
        }
    }
}
