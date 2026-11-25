package dev.turboism.adapter.cubism.optimization.meshsetup;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the skipped-frame {@code GMesh2.setup} elision.
 *
 * <p>The woven host bytecode consults JDK functional-interface values stored in
 * {@link System#getProperties()}, so no Turboism class is referenced by the
 * rewritten mesh class. A setup may be suppressed only when all of the
 * following hold: the current frame's native model update was skipped, the
 * mesh was set up at least once in the current GL context generation, and
 * every persistent buffer wrapper that already owns a GL name reports a clean
 * dirty flag ({@code k() == false}). The dirty flag is precisely the wrapper's
 * own "content not yet uploaded" marker: the host marks it with {@code l()}
 * before geometry refreshes, and a render bind clears it only after the upload.
 * A wrapper with GL name 0 has never been uploaded by this shader (an unused
 * attribute, an empty array, or a setup that has not reached its first render
 * bind yet); its CPU payload was already filled by the completed native setup,
 * so it does not block the elision.</p>
 *
 * <p>Context identity is not inferred from the mesh. A GL context-generation
 * change invalidates every tracked instance, so the first setup pass in the
 * new context always runs natively; a thread change clears the table for the
 * same reason. Any extraction or callback failure clears the table and falls
 * back to the native setup.</p>
 */
public final class SkippedFrameMeshSetupElisionBridge implements AutoCloseable {

    /** Slot for the {@code BiPredicate<Object,Object>} (mesh, frame) setup consult. */
    public static final String PREDICATE_PROPERTY = "turboism.mesh-setup-elision.predicate";
    /** Slot for the {@code Consumer<Object>} after a normal native setup return. */
    public static final String COMPLETE_PROPERTY = "turboism.mesh-setup-elision.complete";
    /** Slot for the {@code Consumer<Object>} after a setup exception. */
    public static final String FAILURE_PROPERTY = "turboism.mesh-setup-elision.failure";
    /** Slot for the payload-free statistics supplier. */
    public static final String STATS_PROPERTY = "turboism.mesh-setup-elision.stats";

    /** {@code getContext()} and {@code static getCurrent()} both answer {@code GLContext}. */
    private static final String GL_CONTEXT_DESCRIPTOR = "()Lcom/jogamp/opengl/GLContext;";

    private static final long EMPTY_GENERATION = 0L;
    private static final long INITIAL_GENERATION = 1L;
    private static final int INITIAL_TABLE_CAPACITY = 64;

    private static final int STAT_CALLS = 0;
    private static final int STAT_SKIPPED_CALLS = 1;
    private static final int STAT_ELIDED = 2;
    private static final int STAT_PASSED = 3;
    private static final int STAT_PASS_NOT_SKIPPED = 4;
    private static final int STAT_PASS_CONTEXT = 5;
    private static final int STAT_PASS_THREAD = 6;
    private static final int STAT_PASS_NO_BASELINE = 7;
    private static final int STAT_PASS_DIRTY = 8;
    private static final int STAT_COMPLETIONS = 9;
    private static final int STAT_FAILURES = 10;
    private static final int STAT_OBSERVER_FAILURES = 11;
    private static final int STAT_CONTEXT_CHANGES = 12;
    private static final int STAT_THREAD_CHANGES = 13;
    private static final int STAT_COUNT = 14;

    private final MethodHandle[] bufferGetters;
    private final MethodHandle nameHandle, dirtyHandle;
    private final MethodHandle frameGl, glContext, currentContext, contextCreated, contextHandle;

    private final WeakGenerationTable generations = new WeakGenerationTable(INITIAL_TABLE_CAPACITY);
    private final Object stateLock = new Object();
    private volatile Object observedGl, observedContext;
    private volatile long observedHandle;
    private volatile long generation = INITIAL_GENERATION;
    private volatile Thread observedThread;

    private final AtomicBoolean active = new AtomicBoolean();
    private final BiPredicate<Object, Object> predicate = this::test;
    private final Consumer<Object> complete = this::complete;
    private final Consumer<Object> failed = this::failed;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private volatile AtomicBoolean skippedFrameSlot;
    private Properties installedProperties;

    private final AtomicLongArray stats = new AtomicLongArray(STAT_COUNT);

    /**
     * Resolves every reviewed member on the admitted host loader.
     *
     * @throws ReflectiveOperationException when a reviewed member is absent
     */
    public SkippedFrameMeshSetupElisionBridge(final ClassLoader loader) throws ReflectiveOperationException {
        final Class<?> mesh = HostHandles.load(loader, SkippedFrameMeshSetupElisionTarget.OWNER);
        bufferGetters = new MethodHandle[SkippedFrameMeshSetupElisionTarget.BUFFER_GETTERS.size()];
        for (int i = 0; i < bufferGetters.length; i++) {
            // The reviewed buffer wrappers differ per attribute, so the getter is discovered by name and
            // opened on its discovered descriptor.
            bufferGetters[i] = HostHandles.virtual(
                    mesh.getMethod(SkippedFrameMeshSetupElisionTarget.BUFFER_GETTERS.get(i)),
                    MethodType.methodType(Object.class, Object.class));
        }

        final Class<?> base = HostHandles.load(loader, SkippedFrameMeshSetupElisionTarget.BASE_OWNER);
        nameHandle = HostHandles.virtual(
                base,
                SkippedFrameMeshSetupElisionTarget.NAME_METHOD,
                SkippedFrameMeshSetupElisionTarget.NAME_DESCRIPTOR,
                MethodType.methodType(IntBuffer.class, Object.class));
        dirtyHandle = HostHandles.virtual(
                base,
                SkippedFrameMeshSetupElisionTarget.DIRTY_METHOD,
                SkippedFrameMeshSetupElisionTarget.DIRTY_DESCRIPTOR,
                MethodType.methodType(boolean.class, Object.class));

        final Class<?> frame = HostHandles.load(loader, SkippedFrameMeshSetupElisionTarget.FRAME_OWNER);
        frameGl = HostHandles.virtual(
                frame,
                SkippedFrameMeshSetupElisionTarget.FRAME_METHOD,
                SkippedFrameMeshSetupElisionTarget.FRAME_DESCRIPTOR,
                MethodType.methodType(Object.class, Object.class));

        final Class<?> gl = HostHandles.load(loader, "com/jogamp/opengl/GL");
        final Class<?> context = HostHandles.load(loader, "com/jogamp/opengl/GLContext");
        glContext = HostHandles.virtual(
                gl, "getContext", GL_CONTEXT_DESCRIPTOR, MethodType.methodType(Object.class, Object.class));
        currentContext = HostHandles.staticCall(
                context, "getCurrent", GL_CONTEXT_DESCRIPTOR, MethodType.methodType(Object.class));
        contextCreated =
                HostHandles.virtual(context, "isCreated", "()Z", MethodType.methodType(boolean.class, Object.class));
        contextHandle =
                HostHandles.virtual(context, "getHandle", "()J", MethodType.methodType(long.class, Object.class));
    }

    /** Occupies the predicate/complete/failure/stats slots; refuses another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("mesh setup elision already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(PREDICATE_PROPERTY)
                    || properties.containsKey(COMPLETE_PROPERTY)
                    || properties.containsKey(FAILURE_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("mesh setup elision slots occupied");
            }
            try {
                properties.put(PREDICATE_PROPERTY, predicate);
                properties.put(COMPLETE_PROPERTY, complete);
                properties.put(FAILURE_PROPERTY, failed);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(COMPLETE_PROPERTY, complete);
                properties.remove(FAILURE_PROPERTY, failed);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
    }

    private boolean test(final Object mesh, final Object frame) {
        if (!active.get()) return false;
        stats.getAndIncrement(STAT_CALLS);
        try {
            if (mesh == null || frame == null) {
                observerFailed();
                stats.getAndIncrement(STAT_PASSED);
                return false;
            }
            if (!frameSkipped()) {
                stats.getAndIncrement(STAT_PASS_NOT_SKIPPED);
                stats.getAndIncrement(STAT_PASSED);
                return false;
            }
            stats.getAndIncrement(STAT_SKIPPED_CALLS);
            final Thread current = Thread.currentThread();
            if (observedThread != current && !claimOrSwitchThread(current)) {
                stats.getAndIncrement(STAT_PASS_THREAD);
                stats.getAndIncrement(STAT_PASSED);
                return false;
            }
            if (!validContext(frame)) {
                stats.getAndIncrement(STAT_PASS_CONTEXT);
                stats.getAndIncrement(STAT_PASSED);
                return false;
            }
            final long currentGeneration = generation;
            if (generations.get(mesh) != currentGeneration) {
                stats.getAndIncrement(STAT_PASS_NO_BASELINE);
                stats.getAndIncrement(STAT_PASSED);
                return false;
            }
            for (final MethodHandle getter : bufferGetters) {
                final Object wrapper = (Object) getter.invokeExact(mesh);
                if (wrapper == null) {
                    observerFailed();
                    stats.getAndIncrement(STAT_PASSED);
                    return false;
                }
                if ((boolean) dirtyHandle.invokeExact(wrapper)) {
                    final IntBuffer names = (IntBuffer) nameHandle.invokeExact(wrapper);
                    if (names == null || names.capacity() < 1) {
                        observerFailed();
                        stats.getAndIncrement(STAT_PASSED);
                        return false;
                    }
                    if (names.get(0) > 0) {
                        stats.getAndIncrement(STAT_PASS_DIRTY);
                        stats.getAndIncrement(STAT_PASSED);
                        return false;
                    }
                }
            }
            stats.getAndIncrement(STAT_ELIDED);
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailed();
            stats.getAndIncrement(STAT_PASSED);
            return false;
        }
    }

    private boolean claimOrSwitchThread(final Thread current) {
        synchronized (stateLock) {
            final Thread owner = observedThread;
            if (owner == null) {
                observedThread = current;
                return true;
            }
            if (owner == current) {
                return true;
            }
            observedThread = current;
            stats.getAndIncrement(STAT_THREAD_CHANGES);
            observerFailedLocked();
            return false;
        }
    }

    /** Records one successful native setup for the current context generation. */
    private void complete(final Object mesh) {
        if (!active.get() || mesh == null) return;
        try {
            final Thread owner = observedThread;
            if (owner != null && owner != Thread.currentThread()) return;
            final long currentGeneration = generation;
            generations.put(mesh, currentGeneration);
            stats.getAndIncrement(STAT_COMPLETIONS);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailed();
        }
    }

    /** Removes an instance whose pointer/value refresh failed; the next setup runs natively. */
    private void failed(final Object mesh) {
        if (!active.get()) return;
        stats.getAndIncrement(STAT_FAILURES);
        if (mesh != null) generations.remove(mesh);
    }

    private void observerFailed() {
        synchronized (stateLock) {
            observerFailedLocked();
        }
    }

    private void observerFailedLocked() {
        stats.getAndIncrement(STAT_OBSERVER_FAILURES);
        advanceGenerationLocked();
    }

    private void advanceGenerationLocked() {
        final long next = generation + 1L;
        generation = next == EMPTY_GENERATION ? INITIAL_GENERATION : next;
    }

    /**
     * Requires the current thread's live, non-shared GL context. A new context
     * generation invalidates every tracked mesh so the first setup pass in that
     * context cannot reuse stale buffer names.
     */
    private boolean validContext(final Object frame) throws Throwable {
        final Object gl = (Object) frameGl.invokeExact(frame);
        if (gl == null) return false;
        final Object context = (Object) glContext.invokeExact(gl);
        if (context == null || context != (Object) currentContext.invokeExact()) return false;
        if (!(boolean) contextCreated.invokeExact(context)) {
            return false;
        }
        final long handle = (long) contextHandle.invokeExact(context);
        if (handle == 0L) return false;
        if (observedGl != gl || observedContext != context || observedHandle != handle) {
            recordContextChange(gl, context, handle);
        }
        return true;
    }

    private void recordContextChange(final Object gl, final Object context, final long handle) {
        synchronized (stateLock) {
            if (observedGl == gl && observedContext == context && observedHandle == handle) {
                return;
            }
            final boolean hadPrevious = observedGl != null;
            observedGl = gl;
            observedContext = context;
            observedHandle = handle;
            if (hadPrevious) {
                stats.getAndIncrement(STAT_CONTEXT_CHANGES);
                advanceGenerationLocked();
            }
        }
    }

    private boolean frameSkipped() {
        try {
            final AtomicBoolean flag = skippedFrameSlot;
            if (flag != null) return flag.get();
            final Object slot = System.getProperties().get(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
            if (!(slot instanceof AtomicBoolean published)) return false;
            skippedFrameSlot = published;
            return published.get();
        } catch (Throwable denied) {
            FatalErrors.rethrowIfFatal(denied);
            return false;
        }
    }

    /** Work counts and live state only; no interaction benefit is inferred from them. */
    public Map<String, Long> snapshot() {
        final Map<String, Long> result = new java.util.LinkedHashMap<>();
        result.put("calls", stats.get(STAT_CALLS));
        result.put("skippedCalls", stats.get(STAT_SKIPPED_CALLS));
        result.put("elided", stats.get(STAT_ELIDED));
        result.put("passed", stats.get(STAT_PASSED));
        result.put("passNotSkipped", stats.get(STAT_PASS_NOT_SKIPPED));
        result.put("passContext", stats.get(STAT_PASS_CONTEXT));
        result.put("passThread", stats.get(STAT_PASS_THREAD));
        result.put("passNoBaseline", stats.get(STAT_PASS_NO_BASELINE));
        result.put("passDirty", stats.get(STAT_PASS_DIRTY));
        result.put("completions", stats.get(STAT_COMPLETIONS));
        result.put("failures", stats.get(STAT_FAILURES));
        result.put("observerFailures", stats.get(STAT_OBSERVER_FAILURES));
        result.put("contextChanges", stats.get(STAT_CONTEXT_CHANGES));
        result.put("threadChanges", stats.get(STAT_THREAD_CHANGES));
        result.put("tracked", (long) generations.countLive(generation));
        result.put("active", active.get() ? 1L : 0L);
        return Map.copyOf(result);
    }

    /** Clears owned slots; outstanding consults fall back to the native setup. */
    @Override
    public synchronized void close() {
        active.set(false);
        skippedFrameSlot = null;
        synchronized (stateLock) {
            observedThread = null;
            observedGl = null;
            observedContext = null;
            observedHandle = 0L;
            advanceGenerationLocked();
        }
        generations.clear();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null)
            synchronized (properties) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(COMPLETE_PROPERTY, complete);
                properties.remove(FAILURE_PROPERTY, failed);
                properties.remove(STATS_PROPERTY, statistics);
            }
    }

    /** Open-addressed identity weak-key table mapping live meshes to primitive generation stamps. */
    private static final class WeakGenerationTable {

        private static final int LOAD_DENOMINATOR = 2;
        private static final int GROW_THRESHOLD_DENOMINATOR = 4;
        private static final int IDENTITY_HASH_SHIFT = 16;
        private static final int GOLDEN_RATIO_MIX = 0x9E3779B9;
        private static final WeakKey DEAD_KEY = new WeakKey(null, 0, EMPTY_GENERATION);
        private static final WeakKey MOVED_KEY = new WeakKey(null, 0, EMPTY_GENERATION);

        private final int initialCapacity;
        private final Object writeLock = new Object();
        private volatile BucketTable currentTable;

        WeakGenerationTable(final int initialCapacity) {
            this.initialCapacity = initialCapacity;
            this.currentTable = new BucketTable(initialCapacity);
        }

        long get(final Object mesh) {
            final int hash = smearIdentityHash(mesh);
            BucketTable table = this.currentTable;
            for (; ; ) {
                final AtomicReferenceArray<WeakKey> slots = table.slots;
                final int mask = table.mask;
                boolean moved = false;
                for (int idx = hash & mask, step = 0; step <= mask; idx = (idx + 1) & mask, step++) {
                    final WeakKey key = slots.get(idx);
                    if (key == null) {
                        return EMPTY_GENERATION;
                    }
                    if (key == MOVED_KEY) {
                        table = advanceTable(table);
                        moved = true;
                        break;
                    }
                    if (key != DEAD_KEY && key.identityHash == hash && key.get() == mesh) {
                        return key.generation;
                    }
                }
                if (!moved) {
                    return EMPTY_GENERATION;
                }
            }
        }

        void put(final Object mesh, final long gen) {
            final int hash = smearIdentityHash(mesh);
            BucketTable table = this.currentTable;
            for (; ; ) {
                final AtomicReferenceArray<WeakKey> slots = table.slots;
                final int mask = table.mask;
                boolean moved = false;
                for (int idx = hash & mask, step = 0; step <= mask; idx = (idx + 1) & mask, step++) {
                    final WeakKey key = slots.get(idx);
                    if (key == null) {
                        break;
                    }
                    if (key == MOVED_KEY) {
                        table = advanceTable(table);
                        moved = true;
                        break;
                    }
                    if (key != DEAD_KEY && key.identityHash == hash && key.get() == mesh) {
                        key.generation = gen;
                        return;
                    }
                }
                if (!moved) {
                    break;
                }
            }
            insertOrUpdateLocked(mesh, hash, gen);
        }

        void remove(final Object mesh) {
            final int hash = smearIdentityHash(mesh);
            synchronized (writeLock) {
                final BucketTable table = this.currentTable;
                final AtomicReferenceArray<WeakKey> slots = table.slots;
                final int mask = table.mask;
                for (int idx = hash & mask, step = 0; step <= mask; idx = (idx + 1) & mask, step++) {
                    final WeakKey key = slots.get(idx);
                    if (key == null) {
                        return;
                    }
                    if (key != DEAD_KEY && key != MOVED_KEY && key.identityHash == hash && key.get() == mesh) {
                        key.generation = EMPTY_GENERATION;
                        slots.set(idx, DEAD_KEY);
                        return;
                    }
                }
            }
        }

        void clear() {
            synchronized (writeLock) {
                final BucketTable oldTable = this.currentTable;
                final BucketTable emptyTable = new BucketTable(initialCapacity);
                oldTable.next = emptyTable;
                for (int i = 0; i <= oldTable.mask; i++) {
                    oldTable.slots.set(i, MOVED_KEY);
                }
                this.currentTable = emptyTable;
            }
        }

        int countLive(final long expectedGeneration) {
            final BucketTable table = this.currentTable;
            final AtomicReferenceArray<WeakKey> slots = table.slots;
            int live = 0;
            for (int i = 0; i <= table.mask; i++) {
                final WeakKey key = slots.get(i);
                if (key != null
                        && key != DEAD_KEY
                        && key != MOVED_KEY
                        && key.generation == expectedGeneration
                        && key.get() != null) {
                    live++;
                }
            }
            return live;
        }

        private void insertOrUpdateLocked(final Object mesh, final int hash, final long gen) {
            synchronized (writeLock) {
                BucketTable table = this.currentTable;
                for (; ; ) {
                    final AtomicReferenceArray<WeakKey> slots = table.slots;
                    final int mask = table.mask;
                    int firstReusable = -1;
                    boolean exhausted = true;
                    for (int idx = hash & mask, step = 0; step <= mask; idx = (idx + 1) & mask, step++) {
                        final WeakKey key = slots.get(idx);
                        if (key == null) {
                            if (firstReusable >= 0) {
                                slots.set(firstReusable, new WeakKey(mesh, hash, gen));
                                return;
                            }
                            if ((table.used + 1) * LOAD_DENOMINATOR > mask + 1) {
                                exhausted = false;
                                break;
                            }
                            slots.set(idx, new WeakKey(mesh, hash, gen));
                            table.used++;
                            return;
                        }
                        if (key == DEAD_KEY || key.get() == null) {
                            if (firstReusable < 0) {
                                firstReusable = idx;
                            }
                            continue;
                        }
                        if (key.identityHash == hash && key.get() == mesh) {
                            key.generation = gen;
                            return;
                        }
                    }
                    if (exhausted && firstReusable >= 0) {
                        slots.set(firstReusable, new WeakKey(mesh, hash, gen));
                        return;
                    }
                    table = rehashLocked(table);
                }
            }
        }

        private BucketTable rehashLocked(final BucketTable oldTable) {
            final int capacity = oldTable.mask + 1;
            int live = 0;
            for (int i = 0; i <= oldTable.mask; i++) {
                final WeakKey key = oldTable.slots.get(i);
                if (key != null && key != DEAD_KEY && key != MOVED_KEY && key.get() != null) {
                    live++;
                }
            }
            final int nextCapacity = live * GROW_THRESHOLD_DENOMINATOR >= capacity ? capacity << 1 : capacity;
            final BucketTable nextTable = new BucketTable(Math.max(initialCapacity, nextCapacity));
            oldTable.next = nextTable;
            for (int i = 0; i <= oldTable.mask; i++) {
                final WeakKey key = oldTable.slots.getAndSet(i, MOVED_KEY);
                if (key != null && key != DEAD_KEY && key != MOVED_KEY && key.get() != null) {
                    insertMigrated(nextTable, key);
                }
            }
            this.currentTable = nextTable;
            return nextTable;
        }

        private static void insertMigrated(final BucketTable target, final WeakKey key) {
            final int mask = target.mask;
            for (int idx = key.identityHash & mask; ; idx = (idx + 1) & mask) {
                if (target.slots.get(idx) == null) {
                    target.slots.set(idx, key);
                    target.used++;
                    return;
                }
            }
        }

        private BucketTable advanceTable(final BucketTable table) {
            final BucketTable next = table.next;
            return next != null ? next : this.currentTable;
        }

        private static int smearIdentityHash(final Object mesh) {
            final int raw = System.identityHashCode(mesh) * GOLDEN_RATIO_MIX;
            return raw ^ (raw >>> IDENTITY_HASH_SHIFT);
        }

        private static final class BucketTable {

            final int mask;
            final AtomicReferenceArray<WeakKey> slots;
            int used;
            volatile BucketTable next;

            BucketTable(final int capacity) {
                this.mask = capacity - 1;
                this.slots = new AtomicReferenceArray<>(capacity);
            }
        }

        private static final class WeakKey extends WeakReference<Object> {

            final int identityHash;
            volatile long generation;

            WeakKey(final Object referent, final int identityHash, final long generation) {
                super(referent);
                this.identityHash = identityHash;
                this.generation = generation;
            }
        }
    }
}
