package dev.turboism.test.concurrent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Timer driven by the test thread. Worker threads may schedule or cancel timers;
 * callbacks run only when the test calls {@link #advanceBy} or {@link #runDueTasks}.
 * Supports one-shot and fixed-delay callbacks; fixed-rate scheduling is deliberately unsupported.
 */
public final class ManualScheduledExecutor extends AbstractExecutorService implements ScheduledExecutorService {
    private final PriorityQueue<Timer<?>> timers = new PriorityQueue<>();
    private long nowNanos;
    private long sequence;
    private boolean shutdown;

    /** Advances monotonically, running due callbacks in deadline order, including newly queued work. */
    public void advanceBy(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        final long target;
        synchronized (this) {
            target = Math.addExact(nowNanos, duration.toNanos());
        }
        while (true) {
            final Timer<?> timer;
            synchronized (this) {
                timer = timers.peek();
                if (timer == null || timer.deadline > target) {
                    nowNanos = target;
                    return;
                }
                timers.remove();
                nowNanos = timer.deadline;
            }
            timer.run();
        }
    }

    /** Runs callbacks whose delay has elapsed without advancing time. */
    public void runDueTasks() {
        advanceBy(Duration.ZERO);
    }

    /** Number of live callbacks, useful for synchronizing with a worker that arms a timer. */
    public synchronized int pendingTaskCount() {
        return (int) timers.stream().filter(timer -> !timer.isDone()).count();
    }

    @Override
    public synchronized <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (shutdown) {
            throw new RejectedExecutionException("timer is shut down");
        }
        Timer<V> timer =
                new Timer<>(callable, Math.addExact(nowNanos, Math.max(0, unit.toNanos(delay))), sequence++, 0);
        timers.add(timer);
        return timer;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(java.util.concurrent.Executors.callable(command, null), delay, unit);
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initial, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("use one-shot timers");
    }

    @Override
    public synchronized ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command, long initial, long delay, TimeUnit unit) {
        if (delay <= 0) {
            throw new IllegalArgumentException("delay must be positive");
        }
        if (shutdown) {
            throw new RejectedExecutionException("timer is shut down");
        }
        Timer<?> timer = new Timer<>(
                java.util.concurrent.Executors.callable(command, null),
                Math.addExact(nowNanos, Math.max(0, unit.toNanos(initial))),
                sequence++,
                unit.toNanos(delay));
        timers.add(timer);
        return timer;
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> pending = new ArrayList<>();
        for (Timer<?> timer : timers) {
            if (!timer.isDone()) {
                pending.add(timer);
                timer.cancel(false);
            }
        }
        timers.clear();
        return pending;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && pendingTaskCount() == 0;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }

    private final class Timer<V> extends FutureTask<V> implements ScheduledFuture<V> {
        private long deadline;
        private final long interval;
        private final long order;

        Timer(Callable<V> callable, long deadline, long order, long interval) {
            super(callable);
            this.deadline = deadline;
            this.order = order;
            this.interval = interval;
        }

        @Override
        public void run() {
            if (interval == 0) {
                super.run();
            } else if (super.runAndReset()) {
                synchronized (ManualScheduledExecutor.this) {
                    if (shutdown || isCancelled()) {
                        cancel(false);
                    } else {
                        deadline = Math.addExact(nowNanos, interval);
                        timers.add(this);
                    }
                }
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            synchronized (ManualScheduledExecutor.this) {
                return unit.convert(deadline - nowNanos, TimeUnit.NANOSECONDS);
            }
        }

        @Override
        public int compareTo(Delayed other) {
            Timer<?> timer = (Timer<?>) other;
            int byDeadline = Long.compare(deadline, timer.deadline);
            return byDeadline == 0 ? Long.compare(order, timer.order) : byDeadline;
        }
    }
}
