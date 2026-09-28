package dev.turboism.validation.atlastiming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Independent, bounded, entry-side stack sampler for metric ids {@code UPDATE_TEXTURE} and
 * {@code SETUP_CACHE_IMAGE} only (T029-STACK).
 *
 * <p>Design contract:</p>
 * <ul>
 *   <li>Budget is reserved <em>before</em> any stack walk; {@code seq} is the unique
 *       monotonically increasing reservation id. Reservations past the hard cap are refused
 *       and counted, never collected.</li>
 *   <li>Collection uses JDK 17 {@link StackWalker} with a depth limit and keeps only
 *       {@code getClassName() + "." + getMethodName()} strings — no {@code Class} or host
 *       object references are retained and no host getter is called.</li>
 *   <li>A single daemon writer drains a bounded queue with a non-blocking {@code offer} on
 *       the producer side; it owns both the records file and a status file written through
 *       its own temp-then-atomic-replace (independent of the timing probe's
 *       {@code .tmp} window).</li>
 *   <li>Every loss path has its own counter so a missing sample is distinguishable from a
 *       path that never ran. A native JVM exit can truncate tail samples and even the final
 *       status write — evidence without a drained queue is incomplete by construction.</li>
 *   <li>Disabled state creates no thread, no walker and no file; enable/startup failures are
 *       swallowed into an in-memory state string and never propagate into the host.</li>
 *   <li>Records are raw frames only: no caller classification, no causal or scheduling
 *       inference. A marker frame appearing in a stack only proves the sampled entry ran
 *       underneath it.</li>
 * </ul>
 */
final class StackSamples {
    static final int MAX_SAMPLES = 512;
    static final int QUEUE_CAPACITY = 512;
    static final int MAX_DEPTH = 96;
    private static final int WALK_LIMIT = MAX_DEPTH + 1;
    private static final int MAX_THREAD_NAME = 64;
    private static final int MAX_FRAME_NAME = 160;
    private static final long STATUS_IDLE_MILLIS = 250L;
    static final String RECORDS_FILE = "timing-stacks.txt";
    static final String STATUS_FILE = "timing-stacks.status";
    static final String STATUS_TMP = "timing-stacks.status.tmp";

    private static volatile Engine engine;
    private static volatile String state = "disabled:not-enabled";

    private StackSamples() {
    }

    /** Called from inside instrumented entries; independent failure domain from pairing. */
    static void maybeSample(final int metricId) {
        if (metricId != AtlasTimingTargets.UPDATE_TEXTURE
                && metricId != AtlasTimingTargets.SETUP_CACHE_IMAGE) {
            return;
        }
        final Engine current = engine;
        if (current == null) {
            return;
        }
        current.sample(metricId);
    }

    /** Enables production-budget sampling; every failure collapses to a disabled state. */
    static synchronized void enable() {
        if (engine != null || state.startsWith("enabled") || state.startsWith("failed")) {
            return;
        }
        try {
            final Path output = outputDir();
            if (output == null) {
                state = "failed:no-output-dir";
                return;
            }
            final Engine created = new Engine(MAX_SAMPLES, QUEUE_CAPACITY, output, null, null);
            created.start();
            engine = created;
            state = "enabled";
        } catch (Throwable failure) {
            // An observation aid must never reach the host premain with an exception.
            state = "failed:" + failure.getClass().getSimpleName();
        }
    }

    /** Records a disabled reason in memory only; no thread, walker or file is created. */
    static synchronized void disabled(final String reason) {
        if (engine == null) {
            state = "disabled:" + (reason == null ? "unspecified" : reason);
        }
    }

    static String state() {
        return state;
    }

    /**
     * Package-private test seam: budgets may only be lowered (clamped to the production
     * caps), never raised. The writer gate lets a test hold the writer closed so queue-full
     * and backlog accounting are deterministic rather than timing races.
     */
    static synchronized Engine enableForTest(final int maxSamples, final int queueCapacity,
            final Path outputDir, final FrameCollector collector,
            final CountDownLatch writerGate) throws IOException {
        final Engine created =
            new Engine(maxSamples, queueCapacity, outputDir, collector, writerGate);
        created.start();
        engine = created;
        state = "enabled:test";
        return created;
    }

    private static Path outputDir() {
        final String value = System.getProperty("turboism.validation.atlasTiming.output");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String escape(final String value, final int maxLength) {
        if (value == null) {
            return "?";
        }
        String cleaned = value.replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength);
        }
        return cleaned;
    }

    /** Swappable collector so tests can inject failures without touching the host path. */
    interface FrameCollector {
        List<String> collect();
    }

    static final class Engine {
        private final int maxSamples;
        private final Path outputDir;
        private final Path recordsFile;
        private final Path statusFile;
        private final Path statusTmp;
        private final FrameCollector collector;
        private final CountDownLatch writerGate;
        private final BlockingQueue<String> queue;
        private final Thread writer;

        private final AtomicLong attempted = new AtomicLong();
        private final AtomicLong reserved = new AtomicLong();
        private final AtomicLong sampleError = new AtomicLong();
        private final AtomicLong queued = new AtomicLong();
        private final AtomicLong droppedQueue = new AtomicLong();
        private final AtomicLong droppedBudget = new AtomicLong();
        private final AtomicLong dequeued = new AtomicLong();
        private final AtomicLong written = new AtomicLong();
        private final AtomicLong ioLost = new AtomicLong();
        private final AtomicLong truncated = new AtomicLong();
        private final AtomicLong statusError = new AtomicLong();
        private volatile String lastStatus;

        private Engine(final int requestedSamples, final int requestedQueue,
                final Path outputDir, final FrameCollector collector,
                final CountDownLatch writerGate) {
            this.outputDir = outputDir;
            this.maxSamples = Math.max(1, Math.min(requestedSamples, MAX_SAMPLES));
            final int capacity = Math.max(1, Math.min(requestedQueue, QUEUE_CAPACITY));
            this.queue = new LinkedBlockingQueue<>(capacity);
            this.recordsFile = outputDir.resolve(RECORDS_FILE);
            this.statusFile = outputDir.resolve(STATUS_FILE);
            this.statusTmp = outputDir.resolve(STATUS_TMP);
            this.writerGate = writerGate;
            if (collector != null) {
                this.collector = collector;
            } else {
                final StackWalker walker = StackWalker.getInstance();
                this.collector = () -> walker.walk(stream -> stream
                        .limit(WALK_LIMIT)
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                        .collect(java.util.stream.Collectors.toList()));
            }
            this.writer = new Thread(this::runWriter, "turboism-stack-samples");
            this.writer.setDaemon(true);
        }

        private void start() throws IOException {
            Files.createDirectories(outputDir);
            writer.start();
        }

        /** Point-in-time counter snapshot for the package's own assertions. */
        java.util.Map<String, Long> snapshot() {
            final java.util.Map<String, Long> snap = new java.util.LinkedHashMap<>();
            final long queuedNow = queued.get();
            final long dequeuedNow = dequeued.get();
            snap.put("attempted", attempted.get());
            snap.put("reserved", reserved.get());
            snap.put("sampleError", sampleError.get());
            snap.put("queued", queuedNow);
            snap.put("droppedQueue", droppedQueue.get());
            snap.put("droppedBudget", droppedBudget.get());
            snap.put("dequeued", dequeuedNow);
            snap.put("written", written.get());
            snap.put("ioLost", ioLost.get());
            snap.put("truncated", truncated.get());
            snap.put("pending", queuedNow - dequeuedNow);
            snap.put("inFlight", reserved.get() - sampleError.get()
                - droppedQueue.get() - queuedNow);
            snap.put("inWrite", dequeuedNow - written.get() - ioLost.get());
            return snap;
        }

        /**
         * Reserves budget first, then collects, then offers without waiting. Any internal
         * failure is counted and swallowed; nothing reaches the instrumented caller.
         */
        private void sample(final int metricId) {
            try {
                attempted.incrementAndGet();
                final long seq = reserve();
                if (seq < 0L) {
                    droppedBudget.incrementAndGet();
                    return;
                }
                final String record;
                try {
                    record = collect(metricId, seq);
                } catch (Throwable failure) {
                    sampleError.incrementAndGet();
                    return;
                }
                if (queue.offer(record)) {
                    queued.incrementAndGet();
                } else {
                    droppedQueue.incrementAndGet();
                }
            } catch (Throwable failure) {
                sampleError.incrementAndGet();
            }
        }

        private long reserve() {
            while (true) {
                final long current = reserved.get();
                if (current >= maxSamples) {
                    return -1L;
                }
                if (reserved.compareAndSet(current, current + 1L)) {
                    return current + 1L;
                }
            }
        }

        private String collect(final int metricId, final long seq) {
            final List<String> frames = collector.collect();
            final boolean isTruncated = frames.size() > MAX_DEPTH;
            final List<String> kept = isTruncated ? frames.subList(0, MAX_DEPTH) : frames;
            if (isTruncated) {
                truncated.incrementAndGet();
            }
            final String metric = metricId >= 0
                && metricId < AtlasTimingTargets.METRIC_NAMES.length
                    ? AtlasTimingTargets.METRIC_NAMES[metricId] : "metric" + metricId;
            final StringBuilder line = new StringBuilder();
            line.append("stack seq=").append(seq)
                .append(" metric=").append(metric)
                .append(" tid=").append(Thread.currentThread().getId())
                .append(" thread=\"").append(escape(Thread.currentThread().getName(),
                    MAX_THREAD_NAME)).append('"')
                .append(" depth=").append(kept.size())
                .append(" truncated=").append(isTruncated ? 1 : 0)
                .append(" frames=\"");
            for (int i = 0; i < kept.size(); i++) {
                if (i > 0) {
                    line.append(';');
                }
                line.append(escape(kept.get(i), MAX_FRAME_NAME));
            }
            return line.append('"').toString();
        }

        private void runWriter() {
            try {
                if (writerGate != null) {
                    writerGate.await();
                }
            } catch (InterruptedException interrupted) {
                return;
            }
            final List<String> batch = new ArrayList<>();
            while (true) {
                final String head;
                try {
                    head = queue.poll(STATUS_IDLE_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (head != null) {
                    batch.add(head);
                    final int drained = queue.drainTo(batch);
                    dequeued.addAndGet(1L + drained);
                    try {
                        Files.write(recordsFile, batch, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        written.addAndGet(batch.size());
                    } catch (IOException failure) {
                        ioLost.addAndGet(batch.size());
                    }
                    batch.clear();
                }
                writeStatusIfChanged();
            }
        }

        /** Single-writer status: temp file plus atomic replace, independent of the probe's tmp. */
        private void writeStatusIfChanged() {
            final long queuedNow = queued.get();
            final long dequeuedNow = dequeued.get();
            final String status = "state=RUNNING"
                + "\nattempted=" + attempted.get()
                + "\nreserved=" + reserved.get()
                + "\nsampleError=" + sampleError.get()
                + "\nqueued=" + queuedNow
                + "\ndroppedQueue=" + droppedQueue.get()
                + "\ndroppedBudget=" + droppedBudget.get()
                + "\ndequeued=" + dequeuedNow
                + "\nwritten=" + written.get()
                + "\nioLost=" + ioLost.get()
                + "\ntruncated=" + truncated.get()
                + "\npending=" + (queuedNow - dequeuedNow)
                + "\ninFlight=" + (reserved.get() - sampleError.get()
                    - droppedQueue.get() - queuedNow)
                + "\ninWrite=" + (dequeuedNow - written.get() - ioLost.get())
                + "\nqueueDepth=" + queue.size()
                + '\n';
            if (status.equals(lastStatus)) {
                return;
            }
            try {
                Files.createDirectories(statusFile.getParent());
                Files.write(statusTmp, status.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(statusTmp, statusFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                    Files.move(statusTmp, statusFile, StandardCopyOption.REPLACE_EXISTING);
                }
                lastStatus = status;
            } catch (IOException failure) {
                // The status file cannot report its own write failure; keep it in memory.
                statusError.incrementAndGet();
            }
        }
    }
}
