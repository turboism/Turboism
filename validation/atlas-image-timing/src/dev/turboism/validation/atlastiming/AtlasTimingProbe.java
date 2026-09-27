package dev.turboism.validation.atlastiming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded evidence sink called from inside instrumented host methods.
 *
 * <p>{@link #enter(int)} pushes {@code (metricId, nanoTime, epochMillis)} onto a per-thread stack;
 * {@link #exit(int)} pops the matching entry and records the call's wall duration. Interleaving of
 * different instrumented methods on one thread unwinds in LIFO order, which matches how nested
 * calls actually return. A method that leaves through a thrown exception has no woven exit call, so
 * the next exit may find a stale entry on top; exit scans down for its own metric and counts every
 * discarded entry so imbalance stays visible instead of corrupting later pairs.</p>
 *
 * <p>Both entry points must never throw into the host: every failure is swallowed and surfaced
 * through the evidence file. Records go to a bounded queue drained by a daemon flusher, because the
 * host JVM exits through a native path where Java shutdown hooks do not run.</p>
 */
public final class AtlasTimingProbe {
    private static final String OUTPUT_PROPERTY = "turboism.validation.atlasTiming.output";
    private static final long MAX_RECORDS = 200_000L;
    private static final long FLUSH_INTERVAL_MILLIS = 250L;
    private static final Object LOCK = new Object();
    private static final AtomicLong WRITES = new AtomicLong();
    private static final long MAX_WRITES = 60_000L;
    private static final ArrayDeque<String> PENDING = new ArrayDeque<>();
    private static final ThreadLocal<ArrayDeque<long[]>> STACK =
        ThreadLocal.withInitial(ArrayDeque::new);

    private static volatile String blocked = "NONE";
    private static volatile long records;
    private static volatile long dropped;
    private static volatile long unpaired;
    private static volatile long[] counts = new long[AtlasTimingTargets.METRIC_NAMES.length];
    private static volatile long[] totalsNanos = new long[AtlasTimingTargets.METRIC_NAMES.length];
    private static volatile long[] maxNanos = new long[AtlasTimingTargets.METRIC_NAMES.length];
    private static volatile String targetStates = "NONE";
    private static volatile String classSha = "NONE";
    private static volatile boolean flusherStarted;

    private AtlasTimingProbe() {
    }

    /** Woven at the top of each instrumented method. Stack-neutral: pushes onto a thread stack. */
    public static void enter(final int metricId) {
        try {
            final ArrayDeque<long[]> stack = STACK.get();
            if (stack.size() < 512) {
                stack.push(new long[]{metricId, System.nanoTime(), System.currentTimeMillis()});
            }
        } catch (Throwable ignored) {
            // A probe must never change host behaviour.
        }
    }

    /** Woven before each RETURN of each instrumented method. */
    public static void exit(final int metricId) {
        try {
            final ArrayDeque<long[]> stack = STACK.get();
            long[] entry = null;
            int discarded = 0;
            while (!stack.isEmpty()) {
                final long[] top = stack.pop();
                if (top[0] == metricId) {
                    entry = top;
                    break;
                }
                discarded++;
            }
            if (discarded > 0) {
                synchronized (LOCK) {
                    unpaired += discarded;
                }
            }
            if (entry == null) {
                synchronized (LOCK) {
                    unpaired++;
                }
                return;
            }
            final long duration = System.nanoTime() - entry[1];
            record(metricId, Thread.currentThread().getName(), entry[2], duration);
        } catch (Throwable ignored) {
            // A probe must never change host behaviour.
        }
    }

    private static void record(final int metricId, final String thread,
                               final long startEpochMillis, final long durationNanos) {
        synchronized (LOCK) {
            if (records < MAX_RECORDS) {
                final String name = metricId >= 0
                    && metricId < AtlasTimingTargets.METRIC_NAMES.length
                    ? AtlasTimingTargets.METRIC_NAMES[metricId] : "metric" + metricId;
                PENDING.addLast("call " + name + " thread=\"" + sanitize(thread)
                    + "\" startEpochMs=" + startEpochMillis + " nanos=" + durationNanos);
                if (metricId >= 0 && metricId < counts.length) {
                    counts[metricId]++;
                    totalsNanos[metricId] += durationNanos;
                    if (durationNanos > maxNanos[metricId]) maxNanos[metricId] = durationNanos;
                }
                records++;
            } else {
                dropped++;
            }
            if (records == 1) startFlusher();
        }
        if (records % 64 == 0) flush();
    }

    private static String sanitize(final String thread) {
        return thread == null ? "?" : thread.replace('"', '\'').replace('\n', ' ');
    }

    private static void startFlusher() {
        synchronized (LOCK) {
            if (flusherStarted) return;
            flusherStarted = true;
        }
        final Thread flusher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                try {
                    flush();
                } catch (Throwable ignored) {
                    // A probe must never change host behaviour.
                }
            }
        }, "turboism-atlas-timing-flush");
        flusher.setDaemon(true);
        flusher.start();
    }

    static void setTargetStates(final String states) {
        targetStates = states;
        flush();
    }

    static void setClassSha(final String sha) {
        classSha = sha;
        flush();
    }

    static void markBlocked(final String reason) {
        blocked = reason == null ? "unknown" : reason;
        flush();
    }

    /** Appends pending per-call lines and rewrites the small summary file. */
    static void flush() {
        if (WRITES.get() >= MAX_WRITES) return;
        final List<String> lines = new ArrayList<>();
        final String summary;
        synchronized (LOCK) {
            while (!PENDING.isEmpty()) lines.add(PENDING.pollFirst());
            final StringBuilder body = new StringBuilder();
            body.append("records=").append(records)
                .append("\ndropped=").append(dropped)
                .append("\nunpaired=").append(unpaired)
                .append("\ntargets=").append(targetStates)
                .append("\nclassSha=").append(classSha)
                .append("\nblocked=").append(blocked);
            for (int i = 0; i < AtlasTimingTargets.METRIC_NAMES.length; i++) {
                body.append("\nmetric.").append(AtlasTimingTargets.METRIC_NAMES[i])
                    .append(".count=").append(counts[i])
                    .append("\nmetric.").append(AtlasTimingTargets.METRIC_NAMES[i])
                    .append(".totalMillis=").append(totalsNanos[i] / 1_000_000L)
                    .append("\nmetric.").append(AtlasTimingTargets.METRIC_NAMES[i])
                    .append(".maxMillis=").append(maxNanos[i] / 1_000_000L);
            }
            summary = body.append('\n').toString();
        }
        final Path output = outputDir();
        if (output == null) return;
        WRITES.incrementAndGet();
        try {
            Files.createDirectories(output);
            if (!lines.isEmpty()) {
                Files.write(output.resolve("timing-calls.txt"), lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            final Path summaryFile = output.resolve("timing-summary.properties");
            final Path temporary = summaryFile.resolveSibling("timing-summary.properties.tmp");
            Files.write(temporary, summary.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, summaryFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, summaryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            synchronized (LOCK) {
                blocked = "evidence write failed: " + failure.getClass().getSimpleName();
            }
        }
    }

    private static Path outputDir() {
        final String value = System.getProperty(OUTPUT_PROPERTY);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
