package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

/**
 * Persistent dependency-driven worker pool for warp-deform subtree DAG and ArtMesh updates.
 *
 * <p>Executes ready deformer subtrees and downstream ArtMesh batches across persistent daemon
 * workers using a single dispatch barrier per frame instead of per-depth synchronization.
 */
final class SubtreeParallelExecutor implements AutoCloseable {

    static final int MIN_PARALLEL_DEFORMERS = 4;
    static final int MIN_PARALLEL_MESHES = 16;

    private static final int MAX_WORKERS = 8;
    private static final int MIN_WORKERS = 1;
    private static final int SLOT_RING_SIZE = 4;
    private static final int SLOT_RING_MASK = SLOT_RING_SIZE - 1;
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;
    private static final int SPIN_ITERS = 64;
    private static final int MIN_MESH_CHUNK = 1;
    private static final int MAX_MESH_CHUNK = 8;
    private static final int MESH_CHUNK_DIVISOR = 4;
    private static final long PARK_TIMEOUT_NANOS = 250_000L;
    private static final String WORKER_THREAD_PREFIX = "turboism-deform-worker-";

    private final int workerCount;
    private final Thread[] workers;
    private volatile ThreadLocal<Boolean> meshThreadMarker;
    private final AtomicBoolean[] workerIdle;
    private final AtomicBoolean[] workerDagParked;
    private final DispatchSlot[] slots;
    private final ReentrantLock dispatchLock = new ReentrantLock();
    private final AtomicLong publishedEpoch = new AtomicLong(0L);
    private volatile DispatchSlot currentSlot;
    private volatile boolean shutdown;

    SubtreeParallelExecutor() {
        this(RuntimeThreads.contextClassLoader(SubtreeParallelExecutor.class));
    }

    SubtreeParallelExecutor(final ClassLoader hostLoader) {
        final int cpus = Runtime.getRuntime().availableProcessors();
        final int targetWorkers = cpus > 1 ? Math.min(MAX_WORKERS, cpus - 1) : MIN_WORKERS;
        int actualWorkers = Math.max(MIN_WORKERS, targetWorkers);
        Thread[] threads = null;
        AtomicBoolean[] idleFlags = null;
        AtomicBoolean[] dagParkedFlags = null;
        try {
            final ClassLoader fallbackLoader = RuntimeThreads.contextClassLoader(SubtreeParallelExecutor.class);
            final ClassLoader resolvedLoader = hostLoader != null ? hostLoader : fallbackLoader;
            final ClassLoader effectiveLoader =
                    resolvedLoader != null ? resolvedLoader : SubtreeParallelExecutor.class.getClassLoader();
            final ThreadFactory factory = RuntimeThreads.daemonThreads(WORKER_THREAD_PREFIX, effectiveLoader);
            threads = new Thread[actualWorkers];
            idleFlags = new AtomicBoolean[actualWorkers];
            dagParkedFlags = new AtomicBoolean[actualWorkers];
            for (int i = 0; i < actualWorkers; i++) {
                idleFlags[i] = new AtomicBoolean(false);
                dagParkedFlags[i] = new AtomicBoolean(false);
                final int workerIndex = i;
                threads[i] = factory.newThread(() -> workerLoop(workerIndex));
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            actualWorkers = 0;
            threads = null;
            idleFlags = null;
            dagParkedFlags = null;
        }
        final DispatchSlot[] ring = new DispatchSlot[SLOT_RING_SIZE];
        for (int i = 0; i < SLOT_RING_SIZE; i++) {
            ring[i] = new DispatchSlot();
        }
        this.slots = ring;
        this.workerIdle = idleFlags;
        this.workerDagParked = dagParkedFlags;
        this.workers = threads;
        this.workerCount = actualWorkers;
        if (threads != null) {
            int started = 0;
            try {
                for (int i = 0; i < actualWorkers; i++) {
                    threads[i].start();
                    started++;
                }
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                shutdown = true;
                for (int i = 0; i < started; i++) {
                    LockSupport.unpark(threads[i]);
                }
            }
        }
    }

    boolean isParallelCapable() {
        return workers != null && !shutdown && workerCount > 0;
    }

    /** Registers a thread-local flag set on worker threads only while running the ArtMesh transform phase. */
    void setMeshThreadMarker(final ThreadLocal<Boolean> marker) {
        this.meshThreadMarker = marker;
    }

    /** Executes {@code count} indexed tasks on EDT directly or across persistent worker threads. */
    void executeBatch(final int count, final int minParallelThreshold, final IntConsumer task) {
        if (count <= 0) {
            return;
        }
        if (count < minParallelThreshold || !isParallelCapable()) {
            final ThreadLocal<Boolean> marker = meshThreadMarker;
            final boolean alreadyMarked = marker != null && marker.get() != null;
            if (marker != null && !alreadyMarked) {
                marker.set(Boolean.TRUE);
            }
            try {
                for (int i = 0; i < count; i++) {
                    task.accept(i);
                }
            } finally {
                if (marker != null && !alreadyMarked) {
                    marker.remove();
                }
            }
            return;
        }
        dispatchLock.lock();
        try {
            final long epoch = publishedEpoch.get() + 1L;
            final DispatchSlot slot = acquireCleanSlot(epoch);
            slot.meshThreadMarker = meshThreadMarker;
            slot.resetForBatch(Thread.currentThread(), count, task);
            currentSlot = slot;
            publishedEpoch.set(epoch);
            wakeIdleWorkers(Math.min(workerCount, count - 1));
            runMeshPhase(slot);
            awaitCompletion(slot);
            rethrowFirstFailure(slot);
        } finally {
            dispatchLock.unlock();
        }
    }

    /**
     * Executes active deformer DAG nodes in topological parent-child order followed immediately by
     * all dirty ArtMeshes under a single join barrier.
     */
    void executeDagAndMeshes(
            final int[] dirtyDefIndices,
            final boolean[] dirtyDefFlags,
            final int[][] deformerChildren,
            final int[] deformerParentIdx,
            final IntConsumer deformerTask,
            final int dirtyMeshCount,
            final IntConsumer meshTask) {
        final int dirtyDefCount = dirtyDefIndices.length;
        if (dirtyDefCount == 0 && dirtyMeshCount <= 0) {
            return;
        }
        if (!isParallelCapable() || (dirtyDefCount < MIN_PARALLEL_DEFORMERS && dirtyMeshCount < MIN_PARALLEL_MESHES)) {
            runSequentialDag(dirtyDefIndices, deformerTask);
            for (int i = 0; i < dirtyMeshCount; i++) {
                meshTask.accept(i);
            }
            return;
        }
        if (dirtyDefCount < MIN_PARALLEL_DEFORMERS) {
            runSequentialDag(dirtyDefIndices, deformerTask);
            executeBatch(dirtyMeshCount, MIN_PARALLEL_MESHES, meshTask);
            return;
        }
        dispatchLock.lock();
        try {
            final long epoch = publishedEpoch.get() + 1L;
            final DispatchSlot slot = acquireCleanSlot(epoch);
            slot.meshThreadMarker = meshThreadMarker;
            slot.resetForDagAndMeshes(
                    Thread.currentThread(),
                    dirtyDefCount,
                    dirtyDefFlags,
                    deformerChildren,
                    deformerTask,
                    dirtyMeshCount,
                    meshTask);
            int localRoot = -1;
            int seededRoots = 0;
            for (final int defIdx : dirtyDefIndices) {
                final int pIdx = deformerParentIdx[defIdx];
                if (pIdx < 0 || !dirtyDefFlags[pIdx]) {
                    if (localRoot < 0) {
                        localRoot = defIdx;
                    } else {
                        slot.queue.offer(defIdx);
                        seededRoots++;
                    }
                }
            }
            currentSlot = slot;
            publishedEpoch.set(epoch);
            if (seededRoots > 0) {
                wakeIdleWorkers(Math.min(workerCount, seededRoots));
            }
            runDagAndMeshFrame(-1, localRoot, slot);
            awaitCompletion(slot);
            rethrowFirstFailure(slot);
        } finally {
            dispatchLock.unlock();
        }
    }

    @Override
    public void close() {
        shutdown = true;
        if (workers != null) {
            for (final Thread worker : workers) {
                if (worker != null) {
                    LockSupport.unpark(worker);
                    worker.interrupt();
                }
            }
        }
    }

    private static void runSequentialDag(final int[] dirtyDefIndices, final IntConsumer deformerTask) {
        for (final int defIdx : dirtyDefIndices) {
            deformerTask.accept(defIdx);
        }
    }

    private DispatchSlot acquireCleanSlot(final long nextEpoch) {
        final DispatchSlot slot = slots[(int) (nextEpoch & SLOT_RING_MASK)];
        while (slot.activeVisitors.get() > 0) {
            Thread.onSpinWait();
        }
        return slot;
    }

    private void workerLoop(final int workerIndex) {
        long seenEpoch = 0L;
        while (!shutdown) {
            final long latestEpoch = publishedEpoch.get();
            if (latestEpoch != seenEpoch) {
                seenEpoch = latestEpoch;
                final DispatchSlot slot = currentSlot;
                if (slot != null && !slot.isFinished()) {
                    slot.activeVisitors.incrementAndGet();
                    try {
                        if (!slot.isFinished()) {
                            runDagAndMeshFrame(workerIndex, -1, slot);
                        }
                    } finally {
                        slot.activeVisitors.decrementAndGet();
                    }
                }
                continue;
            }
            workerIdle[workerIndex].set(true);
            if (publishedEpoch.get() != seenEpoch || shutdown) {
                workerIdle[workerIndex].set(false);
                continue;
            }
            LockSupport.park(this);
            workerIdle[workerIndex].set(false);
            if (Thread.interrupted() && shutdown) {
                return;
            }
        }
    }

    private void runDagAndMeshFrame(final int workerIndex, final int initialDef, final DispatchSlot slot) {
        if (slot.deformerTask != null) {
            runDagPhase(workerIndex, initialDef, slot);
        }
        if (slot.meshCount > 0 && slot.meshTask != null) {
            runMeshPhase(slot);
        }
    }

    private void runDagPhase(final int workerIndex, int currentDef, final DispatchSlot slot) {
        final IntConsumer task = slot.deformerTask;
        final boolean[] dirtyFlags = slot.dirtyDefFlags;
        final int[][] children = slot.deformerChildren;
        final IntRingQueue queue = slot.queue;
        while (slot.remainingDeformers.get() > 0L) {
            if (currentDef < 0) {
                currentDef = queue.poll();
            }
            if (currentDef < 0) {
                if (workerIndex >= 0) {
                    parkInDagUntilReady(workerIndex, slot);
                } else {
                    Thread.onSpinWait();
                }
                continue;
            }
            int nextLocal = -1;
            try {
                task.accept(currentDef);
                final int[] kids = children[currentDef];
                int pushed = 0;
                for (final int childIdx : kids) {
                    if (dirtyFlags[childIdx]) {
                        if (nextLocal < 0) {
                            nextLocal = childIdx;
                        } else {
                            queue.offer(childIdx);
                            pushed++;
                        }
                    }
                }
                if (pushed > 0) {
                    wakeDagOrIdleWorkers(pushed);
                }
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                slot.firstFailure.compareAndSet(null, failure);
            } finally {
                final long remaining = slot.remainingDeformers.decrementAndGet();
                if (remaining == 0L) {
                    wakeAllForMeshOrCompletion(slot);
                }
            }
            currentDef = nextLocal;
        }
    }

    private void parkInDagUntilReady(final int workerIndex, final DispatchSlot slot) {
        for (int spin = 0; spin < SPIN_ITERS; spin++) {
            if (!slot.queue.isEmpty() || slot.remainingDeformers.get() <= 0L || shutdown) {
                return;
            }
            Thread.onSpinWait();
        }
        workerDagParked[workerIndex].set(true);
        if (!slot.queue.isEmpty() || slot.remainingDeformers.get() <= 0L || shutdown) {
            workerDagParked[workerIndex].set(false);
            return;
        }
        LockSupport.parkNanos(this, PARK_TIMEOUT_NANOS);
        workerDagParked[workerIndex].set(false);
    }

    private void runMeshPhase(final DispatchSlot slot) {
        final int count = slot.meshCount;
        final IntConsumer task = slot.meshTask;
        if (count <= 0 || task == null) {
            return;
        }
        final ThreadLocal<Boolean> marker = slot.meshThreadMarker;
        final boolean alreadyMarked = marker != null && marker.get() != null;
        if (marker != null && !alreadyMarked) {
            marker.set(Boolean.TRUE);
        }
        try {
            final int divisor = (workerCount + 1) * MESH_CHUNK_DIVISOR;
            while (true) {
                final int cur = (int) slot.meshCursor.get();
                if (cur >= count) {
                    return;
                }
                final int chunk = Math.max(MIN_MESH_CHUNK, Math.min(MAX_MESH_CHUNK, (count - cur) / divisor));
                final int start = (int) slot.meshCursor.getAndAdd(chunk);
                if (start >= count) {
                    return;
                }
                final int end = Math.min(count, start + chunk);
                int executed = 0;
                try {
                    for (int i = start; i < end; i++) {
                        executed++;
                        task.accept(i);
                    }
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    slot.firstFailure.compareAndSet(null, failure);
                } finally {
                    final int unread = (end - start) - executed;
                    final long left = slot.remainingMeshes.addAndGet(-(executed + unread));
                    if (left <= 0L) {
                        LockSupport.unpark(slot.edtThread);
                    }
                }
            }
        } finally {
            if (marker != null && !alreadyMarked) {
                marker.remove();
            }
        }
    }

    private void awaitCompletion(final DispatchSlot slot) {
        int spin = 0;
        while (!slot.isFinished()) {
            if (spin < SPIN_ITERS) {
                Thread.onSpinWait();
                spin++;
            } else {
                LockSupport.parkNanos(this, PARK_TIMEOUT_NANOS);
            }
        }
    }

    private static void rethrowFirstFailure(final DispatchSlot slot) {
        final Throwable failure = slot.firstFailure.get();
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("subtree parallel task failed", failure);
    }

    private void wakeIdleWorkers(int maxToWake) {
        if (workers == null || maxToWake <= 0) {
            return;
        }
        for (int i = 0; i < workerCount && maxToWake > 0; i++) {
            if (workerIdle[i].get()) {
                LockSupport.unpark(workers[i]);
                maxToWake--;
            }
        }
    }

    private void wakeDagOrIdleWorkers(int countNeeded) {
        if (workers == null || countNeeded <= 0) {
            return;
        }
        for (int i = 0; i < workerCount && countNeeded > 0; i++) {
            if (workerDagParked[i].get() || workerIdle[i].get()) {
                LockSupport.unpark(workers[i]);
                countNeeded--;
            }
        }
    }

    private void wakeAllForMeshOrCompletion(final DispatchSlot slot) {
        if (workers != null) {
            for (int i = 0; i < workerCount; i++) {
                if (workerDagParked[i].get() || (slot.meshCount > 0 && workerIdle[i].get())) {
                    LockSupport.unpark(workers[i]);
                }
            }
        }
        if (slot.meshCount <= 0) {
            LockSupport.unpark(slot.edtThread);
        }
    }

    private static final class DispatchSlot {
        final AtomicLong activeVisitors = new AtomicLong(0L);
        final AtomicLong remainingDeformers = new AtomicLong(0L);
        final AtomicLong remainingMeshes = new AtomicLong(0L);
        final AtomicLong meshCursor = new AtomicLong(0L);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        final IntRingQueue queue = new IntRingQueue(DEFAULT_QUEUE_CAPACITY);
        volatile Thread edtThread;
        volatile ThreadLocal<Boolean> meshThreadMarker;
        volatile boolean[] dirtyDefFlags;
        volatile int[][] deformerChildren;
        volatile IntConsumer deformerTask;
        volatile int meshCount;
        volatile IntConsumer meshTask;

        void resetForBatch(final Thread edt, final int count, final IntConsumer task) {
            edtThread = edt;
            dirtyDefFlags = null;
            deformerChildren = null;
            deformerTask = null;
            meshCount = count;
            meshTask = task;
            remainingDeformers.set(0L);
            meshCursor.set(0L);
            remainingMeshes.set(count);
            firstFailure.set(null);
        }

        void resetForDagAndMeshes(
                final Thread edt,
                final int dirtyDefCount,
                final boolean[] defFlags,
                final int[][] children,
                final IntConsumer defTask,
                final int meshes,
                final IntConsumer mTask) {
            edtThread = edt;
            dirtyDefFlags = defFlags;
            deformerChildren = children;
            deformerTask = defTask;
            meshCount = meshes;
            meshTask = mTask;
            queue.resetForCapacity(dirtyDefCount);
            remainingDeformers.set(dirtyDefCount);
            meshCursor.set(0L);
            remainingMeshes.set(meshes);
            firstFailure.set(null);
        }

        boolean isFinished() {
            return remainingDeformers.get() <= 0L && remainingMeshes.get() <= 0L;
        }
    }

    private static final class IntRingQueue {
        private static final VarHandle LONG_ARRAY_VH = MethodHandles.arrayElementVarHandle(long[].class);

        private long[] sequence;
        private int[] buffer;
        private int mask;
        private final AtomicLong head = new AtomicLong(0L);
        private final AtomicLong tail = new AtomicLong(0L);

        IntRingQueue(final int initialCapacity) {
            allocate(initialCapacity);
        }

        private void allocate(final int minCap) {
            int cap = Integer.highestOneBit(Math.max(DEFAULT_QUEUE_CAPACITY, minCap));
            if (cap < minCap) {
                cap <<= 1;
            }
            this.buffer = new int[cap];
            this.sequence = new long[cap];
            this.mask = cap - 1;
            for (int i = 0; i < cap; i++) {
                INT_SEQ_SET(sequence, i, i);
            }
        }

        void resetForCapacity(final int needed) {
            head.set(0L);
            tail.set(0L);
            if (buffer == null || needed > buffer.length) {
                allocate(needed);
                return;
            }
            for (int i = 0; i < sequence.length; i++) {
                INT_SEQ_SET(sequence, i, i);
            }
        }

        boolean isEmpty() {
            return tail.get() <= head.get();
        }

        void offer(final int value) {
            while (true) {
                final long pos = tail.get();
                final int idx = (int) pos & mask;
                final long seq = (long) LONG_ARRAY_VH.getAcquire(sequence, idx);
                final long diff = seq - pos;
                if (diff == 0L) {
                    if (tail.compareAndSet(pos, pos + 1L)) {
                        buffer[idx] = value;
                        LONG_ARRAY_VH.setRelease(sequence, idx, pos + 1L);
                        return;
                    }
                } else if (diff < 0L) {
                    throw new IllegalStateException("DAG ready ring buffer full");
                } else {
                    Thread.onSpinWait();
                }
            }
        }

        int poll() {
            while (true) {
                final long pos = head.get();
                final int idx = (int) pos & mask;
                final long seq = (long) LONG_ARRAY_VH.getAcquire(sequence, idx);
                final long diff = seq - (pos + 1L);
                if (diff == 0L) {
                    if (head.compareAndSet(pos, pos + 1L)) {
                        final int value = buffer[idx];
                        LONG_ARRAY_VH.setRelease(sequence, idx, pos + mask + 1L);
                        return value;
                    }
                } else if (diff < 0L) {
                    return -1;
                } else {
                    Thread.onSpinWait();
                }
            }
        }

        private static void INT_SEQ_SET(final long[] seq, final int idx, final long val) {
            LONG_ARRAY_VH.setRelease(seq, idx, val);
        }
    }
}
