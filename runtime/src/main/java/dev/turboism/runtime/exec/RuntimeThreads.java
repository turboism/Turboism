package dev.turboism.runtime.exec;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The single construction point for runtime-owned threads and bounded work lanes.
 *
 * <p>Every lane the runtime creates is daemon, carries a {@code turboism} thread name, and
 * rejects saturated work instead of blocking the caller. A live lane therefore can neither pin
 * the JVM at shutdown nor hide an unattributed thread in a host stack dump.
 *
 * <p>Call sites keep ownership of their pool sizes, queue bounds, thread-name suffix, context
 * class loader and shutdown order; this class owns only the parts that must stay identical
 * across the runtime. Lane bounds are passed through unchanged because a lane's queue depth is
 * part of its backpressure contract, not a formatting detail.
 *
 * <p>Deliberately free of {@code dev.turboism} imports: it sits at the bottom of the
 * {@code :runtime} layering, so any package may use it without creating a dependency cycle.
 */
public final class RuntimeThreads {

    /** Prefix every runtime-owned thread name starts with, so host dumps stay attributable. */
    public static final String THREAD_NAME_PREFIX = "turboism";

    private static final String NAME_SEPARATOR = "-";
    private static final long NO_IDLE_KEEP_ALIVE_MILLIS = 0L;
    private static final int SINGLE_WORKER = 1;
    private static final int FIRST_THREAD_NUMBER = 1;
    private static final Pattern UNSAFE_NAME_CHARACTER = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final String SAFE_NAME_REPLACEMENT = "_";

    /**
     * Shared rejection policy: a saturated lane refuses work instead of running it on the caller
     * thread, so a wedged lane can never stall the host or the event dispatch thread.
     */
    private static final RejectedExecutionHandler REJECT_WHEN_SATURATED = new ThreadPoolExecutor.AbortPolicy();

    private RuntimeThreads() {}

    /**
     * Resolves the loader a runtime-owned thread must publish as its context class loader.
     *
     * <p>The production agent is bootstrap-loaded through {@code Boot-Class-Path}, so an agent
     * class reports a {@code null} defining loader. Publishing that value would seed the shared
     * event dispatch thread with a null loader and hide host classes from it, so the system
     * loader — Cubism's application loader — stands in. A non-null loader is returned unchanged,
     * because a plugin or host class must keep resolving through the loader that defined it.
     *
     * @param owner the class whose loader the thread would otherwise inherit
     * @return the owner's defining loader, or the system loader when the owner is bootstrap-loaded
     * @throws NullPointerException if {@code owner} is {@code null}
     */
    public static ClassLoader contextClassLoader(final Class<?> owner) {
        return resolveContextClassLoader(Objects.requireNonNull(owner, "owner").getClassLoader());
    }

    /**
     * Applies the null-defining-loader rule to an already resolved loader.
     *
     * @param definingLoader the owner's defining loader, possibly {@code null}
     * @return the defining loader, or the system loader when it is {@code null}
     */
    static ClassLoader resolveContextClassLoader(final ClassLoader definingLoader) {
        return definingLoader != null ? definingLoader : ClassLoader.getSystemClassLoader();
    }

    /**
     * Replaces every character a host thread dump cannot carry with an underscore.
     *
     * <p>Plugin ids reach runtime thread names. A raw id can contain whitespace or punctuation
     * that makes a stack dump unreadable or splits a log line, so the id is sanitized once, at the
     * point where it becomes part of a name.
     *
     * @param value the raw name fragment
     * @return the fragment restricted to letters, digits, underscore, dot and hyphen
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String sanitizeNameFragment(final String value) {
        return UNSAFE_NAME_CHARACTER
                .matcher(Objects.requireNonNull(value, "value"))
                .replaceAll(SAFE_NAME_REPLACEMENT);
    }

    /**
     * Creates daemon threads named {@code <prefix>-<sequence>}.
     *
     * @param prefix the lane identity, already including any plugin or component suffix
     * @return a factory whose threads never block JVM shutdown
     * @throws NullPointerException if {@code prefix} is {@code null}
     */
    public static ThreadFactory daemonThreads(final String prefix) {
        return daemonThreads(prefix, () -> {});
    }

    /**
     * Creates daemon threads named {@code <prefix>-<sequence>} that run {@code beforeTask} on the
     * worker thread ahead of every task.
     *
     * <p>The preamble exists for lanes that must publish their own worker identity, such as the
     * shared host-read lane, without wrapping each submitted task at the call site.
     *
     * @param prefix the lane identity
     * @param beforeTask the per-task preamble; must be cheap and must not throw
     * @return a factory whose threads never block JVM shutdown
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ThreadFactory daemonThreads(final String prefix, final Runnable beforeTask) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(beforeTask, "beforeTask");
        final AtomicInteger threadNumber = new AtomicInteger(FIRST_THREAD_NUMBER);
        return runnable -> {
            final Thread thread = new Thread(
                    () -> {
                        beforeTask.run();
                        runnable.run();
                    },
                    prefix + NAME_SEPARATOR + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Creates daemon threads named {@code <prefix>-<sequence>} that resolve plugin code through
     * {@code contextClassLoader}.
     *
     * @param prefix the lane identity
     * @param contextClassLoader the loader plugin code must resolve against
     * @return a factory whose threads never block JVM shutdown
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ThreadFactory daemonThreads(final String prefix, final ClassLoader contextClassLoader) {
        Objects.requireNonNull(contextClassLoader, "contextClassLoader");
        final ThreadFactory named = daemonThreads(prefix);
        return runnable -> {
            final Thread thread = named.newThread(runnable);
            thread.setContextClassLoader(contextClassLoader);
            return thread;
        };
    }

    /**
     * Creates daemon threads that all carry exactly {@code name}.
     *
     * <p>Reserved for single-worker lanes: a fixed name is only unambiguous while at most one
     * thread of that lane can exist.
     *
     * @param name the exact thread name
     * @return a factory whose threads never block JVM shutdown
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static ThreadFactory daemonThreadsWithFixedName(final String name) {
        Objects.requireNonNull(name, "name");
        return runnable -> {
            final Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Creates daemon threads that all carry exactly {@code name} and resolve plugin code through
     * {@code contextClassLoader}.
     *
     * @param name the exact thread name
     * @param contextClassLoader the loader plugin code must resolve against
     * @return a factory whose threads never block JVM shutdown
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ThreadFactory daemonThreadsWithFixedName(final String name, final ClassLoader contextClassLoader) {
        Objects.requireNonNull(contextClassLoader, "contextClassLoader");
        final ThreadFactory named = daemonThreadsWithFixedName(name);
        return runnable -> {
            final Thread thread = named.newThread(runnable);
            thread.setContextClassLoader(contextClassLoader);
            return thread;
        };
    }

    /**
     * Creates a single-worker lane with an unbounded queue.
     *
     * <p>This reproduces the JDK single-thread executor contract: work is never rejected while
     * the lane is open, so the lane's owner must bound its own admission. Use a bounded
     * {@link #serialLane(int, ThreadFactory)} when the lane must push back instead.
     *
     * @param threadFactory the worker thread factory
     * @return a lane the caller owns and must shut down
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public static ThreadPoolExecutor serialLane(final ThreadFactory threadFactory) {
        return serialLane(new LinkedBlockingQueue<>(), threadFactory);
    }

    /**
     * Creates a single-worker lane with a bounded queue that rejects work once it is full.
     *
     * @param queueCapacity the maximum number of queued tasks; must be positive
     * @param threadFactory the worker thread factory
     * @return a lane the caller owns and must shut down
     * @throws IllegalArgumentException if {@code queueCapacity} is not positive
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public static ThreadPoolExecutor serialLane(final int queueCapacity, final ThreadFactory threadFactory) {
        return serialLane(linkedQueue(queueCapacity), threadFactory);
    }

    /**
     * Creates a single-worker lane over a caller-owned queue.
     *
     * <p>The caller keeps the queue so it can report queue depth or drain it during shutdown.
     *
     * @param queue the queue backing the lane
     * @param threadFactory the worker thread factory
     * @return a lane the caller owns and must shut down
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ThreadPoolExecutor serialLane(
            final BlockingQueue<Runnable> queue, final ThreadFactory threadFactory) {
        return fixedLane(SINGLE_WORKER, queue, threadFactory);
    }

    /**
     * Creates a fixed-size lane with a bounded array queue that rejects work once it is full.
     *
     * @param workers the exact worker count; must be positive
     * @param queueCapacity the maximum number of queued tasks; must be positive
     * @param threadFactory the worker thread factory
     * @return a lane the caller owns and must shut down
     * @throws IllegalArgumentException if either bound is not positive
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public static ThreadPoolExecutor fixedLane(
            final int workers, final int queueCapacity, final ThreadFactory threadFactory) {
        return fixedLane(workers, arrayQueue(queueCapacity), threadFactory);
    }

    /**
     * Creates a fixed-size lane over a caller-owned queue.
     *
     * @param workers the exact worker count; must be positive
     * @param queue the queue backing the lane
     * @param threadFactory the worker thread factory
     * @return a lane the caller owns and must shut down
     * @throws IllegalArgumentException if {@code workers} is not positive
     * @throws NullPointerException if either reference argument is {@code null}
     */
    public static ThreadPoolExecutor fixedLane(
            final int workers, final BlockingQueue<Runnable> queue, final ThreadFactory threadFactory) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be positive: " + workers);
        }
        return new ThreadPoolExecutor(
                workers,
                workers,
                NO_IDLE_KEEP_ALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                Objects.requireNonNull(queue, "queue"),
                Objects.requireNonNull(threadFactory, "threadFactory"),
                REJECT_WHEN_SATURATED);
    }

    /**
     * Creates a scheduled lane that keeps cancelled tasks queued until their delay elapses.
     *
     * @param coreThreads the exact timer thread count; must be positive
     * @param threadFactory the timer thread factory
     * @return a lane the caller owns and must shut down
     * @throws IllegalArgumentException if {@code coreThreads} is not positive
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public static ScheduledThreadPoolExecutor scheduledLane(final int coreThreads, final ThreadFactory threadFactory) {
        return scheduledLane(coreThreads, threadFactory, false);
    }

    /**
     * Creates a scheduled lane, optionally evicting cancelled tasks immediately.
     *
     * <p>Immediate eviction is the contract for debounce and deadline timers: a cancelled timer
     * must not hold its slot until the original delay elapses.
     *
     * @param coreThreads the exact timer thread count; must be positive
     * @param threadFactory the timer thread factory
     * @param removeCanceledTasks whether a cancelled task leaves the queue at once
     * @return a lane the caller owns and must shut down
     * @throws IllegalArgumentException if {@code coreThreads} is not positive
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public static ScheduledThreadPoolExecutor scheduledLane(
            final int coreThreads, final ThreadFactory threadFactory, final boolean removeCanceledTasks) {
        if (coreThreads < 1) {
            throw new IllegalArgumentException("coreThreads must be positive: " + coreThreads);
        }
        final ScheduledThreadPoolExecutor lane =
                new ScheduledThreadPoolExecutor(coreThreads, Objects.requireNonNull(threadFactory, "threadFactory"));
        lane.setRemoveOnCancelPolicy(removeCanceledTasks);
        return lane;
    }

    /**
     * Creates a single-thread timer that evicts cancelled tasks immediately.
     *
     * @param threadFactory the timer thread factory
     * @return a lane the caller owns and must shut down
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public static ScheduledThreadPoolExecutor serialTimer(final ThreadFactory threadFactory) {
        return scheduledLane(SINGLE_WORKER, threadFactory, true);
    }

    /**
     * Creates a bounded array queue.
     *
     * @param capacity the maximum number of queued tasks; must be positive
     * @return a queue that rejects an element once full
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public static BlockingQueue<Runnable> arrayQueue(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        return new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Creates a bounded linked queue.
     *
     * @param capacity the maximum number of queued tasks; must be positive
     * @return a queue that rejects an element once full
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public static BlockingQueue<Runnable> linkedQueue(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        return new LinkedBlockingQueue<>(capacity);
    }
}
