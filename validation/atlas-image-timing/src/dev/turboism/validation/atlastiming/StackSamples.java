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
import java.util.concurrent.CountDownLatch;

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
 *   <li>Queue ownership transfer and counter mutation happen under one short lock, so every
 *       counter snapshot is a consistent point-in-time view: a dequeued-but-unwritten batch
 *       is {@code inWrite}, never negative {@code pending}. No lock is held while walking a
 *       stack or doing file IO, and producers never wait for queue space.</li>
 *   <li>A single daemon writer drains to {@code timing-stacks.txt} and keeps a single-writer
 *       {@code timing-stacks.status} fresh through its own temp file + atomic rename
 *       (independent of the timing probe's {@code .tmp} window). A batch whose write threw is
 *       {@code unconfirmedWrites} — the file may hold a partial append; {@code seq} lets a
 *       reviewer reconcile lines instead of trusting counts alone.</li>
 *   <li>Every loss path has a counter and flips {@code incomplete}; a writer that dies keeps
 *       its last status and an in-memory terminal reason. A native JVM exit can drop tail
 *       samples and even the final status — evidence without a drained queue is incomplete
 *       by construction. A missing file never proves a path did not run.</li>
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
    private static final int MAX_STATUS_FAILURES = 8;
    private static final long WRITER_IDLE_MILLIS = 250L;
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
            final Engine created = new Engine(MAX_SAMPLES, QUEUE_CAPACITY, output,
                null, null, null, null);
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
     * caps), never raised. The writer gate holds the writer closed and the sink/collector
     * hooks let a test force deterministic interleavings without timing races.
     */
    static synchronized Engine enableForTest(final int maxSamples, final int queueCapacity,
            final Path outputDir, final FrameCollector collector,
            final CountDownLatch writerGate, final RecordSink sink,
            final StatusListener listener) throws IOException {
        final Engine created = new Engine(maxSamples, queueCapacity, outputDir, collector,
            writerGate, sink, listener);
        created.start();
        engine = created;
        state = "enabled:test";
        return created;
    }

    private static Path outputDir() {
        final String value = System.getProperty("turboism.validation.atlasTiming.output");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    /**
     * Length is capped on the raw input first so a huge name never produces a huge escaped
     * copy, then separators/control characters are neutralised. Truncation is made visible
     * by a trailing {@code ..} and counted per record.
     */
    private static String escape(final String value, final int maxLength) {
        if (value == null) {
            return "?";
        }
        final boolean over = value.length() > maxLength;
        final String bounded = over ? value.substring(0, maxLength) : value;
        final StringBuilder out = new StringBuilder(bounded.length() + (over ? 2 : 0));
        for (int i = 0; i < bounded.length(); i++) {
            final char c = bounded.charAt(i);
            switch (c) {
                case '"':  out.append('\''); break;
                case ';':  out.append(',');  break;
                case '\\': out.append('/');  break;
                case '\n': case '\r': out.append(' '); break;
                default:
                    out.append(c < 0x20 ? ' ' : c);
            }
        }
        if (over) {
            out.append("..");
        }
        return out.toString();
    }

    /** Swappable collector so tests can inject failures without touching the host path. */
    interface FrameCollector {
        List<String> collect();
    }

    /** Swappable sink so tests can hold or fail the write side deterministically. */
    interface RecordSink {
        void write(List<String> batch) throws IOException;
    }

    /** Test handshake: invoked once per status write attempt with its committed result. */
    interface StatusListener {
        void statusResult(boolean ok);
    }

    static final class Engine {
        private final int maxSamples;
        private final int queueCapacity;
        private final Path outputDir;
        private final Path recordsFile;
        private final Path statusFile;
        private final Path statusTmp;
        private final FrameCollector collector;
        private final RecordSink sink;
        private final CountDownLatch writerGate;
        private final ArrayDeque<String> queue;
        private final Object lock = new Object();
        private final Thread writer;

        // Every counter below is mutated only while holding {@code lock}.
        private long attempted;
        private long reserved;
        private long sampleError;
        private long queued;
        private long droppedQueue;
        private long droppedBudget;
        private long dequeued;
        private long written;
        private long unconfirmedWrites;
        private long droppedStopped;
        private long droppedInFlight;
        private long truncated;
        private long namesTruncated;
        private long statusErrors;
        private long statusWriteFailures;
        private boolean incomplete;
        private boolean statusDisabled;
        private volatile String terminal;
        private long dataVersion;
        private long lastAttemptVersion = -1L;
        private final StatusListener listener;

        private Engine(final int requestedSamples, final int requestedQueue,
                final Path outputDir, final FrameCollector collector,
                final CountDownLatch writerGate, final RecordSink sink,
                final StatusListener listener) {
            this.outputDir = outputDir;
            this.maxSamples = Math.max(1, Math.min(requestedSamples, MAX_SAMPLES));
            this.queueCapacity = Math.max(1, Math.min(requestedQueue, QUEUE_CAPACITY));
            this.queue = new ArrayDeque<>();
            this.recordsFile = outputDir.resolve(RECORDS_FILE);
            this.statusFile = outputDir.resolve(STATUS_FILE);
            this.statusTmp = outputDir.resolve(STATUS_TMP);
            this.writerGate = writerGate;
            this.listener = listener;
            if (collector != null) {
                this.collector = collector;
            } else {
                final StackWalker walker = StackWalker.getInstance();
                this.collector = () -> walker.walk(stream -> stream
                        .limit(WALK_LIMIT)
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                        .collect(java.util.stream.Collectors.toList()));
            }
            this.sink = sink != null ? sink : batch -> Files.write(recordsFile, batch,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.writer = new Thread(this::runWriter, "turboism-stack-samples");
            this.writer.setDaemon(true);
        }

        private void start() throws IOException {
            Files.createDirectories(outputDir);
            writer.start();
        }

        /** Consistent point-in-time counter snapshot for the package's own assertions. */
        java.util.Map<String, Long> snapshot() {
            final java.util.Map<String, Long> snap = new java.util.LinkedHashMap<>();
            synchronized (lock) {
                snap.put("attempted", attempted);
                snap.put("reserved", reserved);
                snap.put("sampleError", sampleError);
                snap.put("queued", queued);
                snap.put("droppedQueue", droppedQueue);
                snap.put("droppedBudget", droppedBudget);
                snap.put("dequeued", dequeued);
                snap.put("written", written);
                snap.put("unconfirmedWrites", unconfirmedWrites);
                snap.put("droppedStopped", droppedStopped);
                snap.put("droppedInFlight", droppedInFlight);
                snap.put("truncated", truncated);
                snap.put("namesTruncated", namesTruncated);
                snap.put("pending", queued - dequeued);
                snap.put("inFlight", reserved - sampleError - droppedQueue
                    - droppedInFlight - queued);
                snap.put("inWrite", dequeued - written - unconfirmedWrites);
                snap.put("incomplete", incomplete ? 1L : 0L);
                snap.put("statusErrors", statusErrors);
                snap.put("statusWriteFailures", statusWriteFailures);
                snap.put("statusDisabled", statusDisabled ? 1L : 0L);
                snap.put("terminated", terminal != null ? 1L : 0L);
            }
            return snap;
        }

        String terminal() {
            return terminal;
        }

        /**
         * Reserves budget first, then collects, then offers without waiting. Any internal
         * failure is counted and swallowed; nothing reaches the instrumented caller.
         */
        private void sample(final int metricId) {
            try {
                final long seq;
                synchronized (lock) {
                    attempted++;
                    dataVersion++;
                    if (terminal != null) {
                        // Dead writer: never reserve, never walk a stack.
                        droppedStopped++;
                        incomplete = true;
                        return;
                    }
                    if (reserved >= maxSamples) {
                        droppedBudget++;
                        incomplete = true;
                        return;
                    }
                    seq = ++reserved;
                }
                // Collection is deliberately outside the lock: walking a stack is the slow
                // part and must not serialize producers or the writer's dequeue.
                final String record;
                try {
                    record = collect(metricId, seq);
                } catch (Throwable failure) {
                    synchronized (lock) {
                        sampleError++;
                        incomplete = true;
                        dataVersion++;
                    }
                    return;
                }
                synchronized (lock) {
                    if (terminal != null) {
                        // Writer died while this sample was collecting: bounded drop,
                        // explicitly accounted instead of queued to a dead consumer.
                        droppedInFlight++;
                        incomplete = true;
                    } else if (queue.size() < queueCapacity) {
                        final boolean wasEmpty = queue.isEmpty();
                        queue.addLast(record);
                        queued++;
                        if (wasEmpty) {
                            lock.notify();
                        }
                    } else {
                        droppedQueue++;
                        incomplete = true;
                    }
                    dataVersion++;
                }
            } catch (Throwable failure) {
                // Absolute last resort: a sample failure must never reach the host.
                try {
                    synchronized (lock) {
                        sampleError++;
                        incomplete = true;
                        dataVersion++;
                    }
                } catch (Throwable ignored) {
                    // Even the counter update gave up; stay silent.
                }
            }
        }

        private String collect(final int metricId, final long seq) {
            final List<String> frames = collector.collect();
            final boolean isTruncated = frames.size() > MAX_DEPTH;
            final List<String> kept = isTruncated ? frames.subList(0, MAX_DEPTH) : frames;
            final String threadName = Thread.currentThread().getName();
            int clippedNames = threadName != null && threadName.length() > MAX_THREAD_NAME
                ? 1 : 0;
            final String metric = metricId >= 0
                && metricId < AtlasTimingTargets.METRIC_NAMES.length
                    ? AtlasTimingTargets.METRIC_NAMES[metricId] : "metric" + metricId;
            final StringBuilder line = new StringBuilder();
            line.append("stack seq=").append(seq)
                .append(" metric=").append(metric)
                .append(" tid=").append(Thread.currentThread().getId())
                .append(" thread=\"").append(escape(threadName, MAX_THREAD_NAME)).append('"')
                .append(" depth=").append(kept.size())
                .append(" truncated=").append(isTruncated ? 1 : 0)
                .append(" frames=\"");
            for (int i = 0; i < kept.size(); i++) {
                final String raw = kept.get(i);
                if (raw != null && raw.length() > MAX_FRAME_NAME) {
                    clippedNames++;
                }
                if (i > 0) {
                    line.append(';');
                }
                line.append(escape(raw, MAX_FRAME_NAME));
            }
            line.append('"').append(" truncNames=").append(clippedNames);
            if (isTruncated || clippedNames > 0) {
                synchronized (lock) {
                    if (isTruncated) {
                        truncated++;
                    }
                    namesTruncated += clippedNames;
                    dataVersion++;
                }
            }
            return line.toString();
        }

        private void runWriter() {
            final List<String> batch = new ArrayList<>();
            String terminalReason = "exited";
            try {
                if (writerGate != null) {
                    writerGate.await();
                }
                while (true) {
                    synchronized (lock) {
                        String head = queue.pollFirst();
                        if (head != null) {
                            dequeued++;
                            dataVersion++;
                            batch.add(head);
                            String next;
                            while ((next = queue.pollFirst()) != null) {
                                dequeued++;
                                dataVersion++;
                                batch.add(next);
                            }
                        }
                    }
                    if (batch.isEmpty()) {
                        writeStatusIfChanged();
                        synchronized (lock) {
                            if (queue.isEmpty()) {
                                try {
                                    lock.wait(WRITER_IDLE_MILLIS);
                                } catch (InterruptedException interrupted) {
                                    terminalReason = "interrupted";
                                    return;
                                }
                            }
                        }
                        continue;
                    }
                    try {
                        sink.write(batch);
                        synchronized (lock) {
                            written += batch.size();
                            dataVersion++;
                        }
                    } catch (IOException | RuntimeException failure) {
                        // A failed write may have partially appended; callers must reconcile
                        // by seq, and this run is incomplete evidence.
                        synchronized (lock) {
                            unconfirmedWrites += batch.size();
                            incomplete = true;
                            dataVersion++;
                        }
                    }
                    batch.clear();
                    writeStatusIfChanged();
                }
            } catch (Throwable failure) {
                terminalReason = "failed:" + failure.getClass().getSimpleName();
            } finally {
                synchronized (lock) {
                    terminal = terminalReason;
                    incomplete = true;
                    dataVersion++;
                }
                writeStatusTerminal(terminalReason);
            }
        }

        /** Terminal status is best-effort: a dying writer cannot guarantee any disk state. */
        private void writeStatusTerminal(final String reason) {
            writeStatus("TERMINATED:" + reason, true);
        }

        private void writeStatusIfChanged() {
            writeStatus("RUNNING", false);
        }

        /**
         * Builds a consistent snapshot under {@code lock}, then performs the file IO outside
         * it: the lock protects queue ownership and counters only, never IO or stack walks.
         */
        private void writeStatus(final String runState, final boolean force) {
            // Retry only when record-flow data actually changed: the status' own error
            // counters never bump dataVersion, so a failed attempt is not retried on idle
            // wakes — failures map one-to-one to real sampling changes.
            synchronized (lock) {
                if (statusDisabled || (!force && dataVersion == lastAttemptVersion)) {
                    return;
                }
                lastAttemptVersion = dataVersion;
            }
            final String status;
            synchronized (lock) {
                status = "state=" + runState
                    + "\nattempted=" + attempted
                    + "\nreserved=" + reserved
                    + "\nsampleError=" + sampleError
                    + "\nqueued=" + queued
                    + "\ndroppedQueue=" + droppedQueue
                    + "\ndroppedBudget=" + droppedBudget
                    + "\ndequeued=" + dequeued
                    + "\nwritten=" + written
                    + "\nunconfirmedWrites=" + unconfirmedWrites
                    + "\ndroppedStopped=" + droppedStopped
                    + "\ndroppedInFlight=" + droppedInFlight
                    + "\ntruncated=" + truncated
                    + "\nnamesTruncated=" + namesTruncated
                    + "\npending=" + (queued - dequeued)
                    + "\ninFlight=" + (reserved - sampleError - droppedQueue
                        - droppedInFlight - queued)
                    + "\ninWrite=" + (dequeued - written - unconfirmedWrites)
                    + "\nqueueDepth=" + queue.size()
                    + "\nincomplete=" + (incomplete ? 1 : 0)
                    + "\nstatusErrors=" + statusErrors
                    + "\nstatusWriteFailures=" + statusWriteFailures
                    + "\nstatusDisabled=" + (statusDisabled ? 1 : 0)
                    + '\n';
            }
            boolean ok = false;
            try {
                Files.createDirectories(statusFile.getParent());
                Files.write(statusTmp, status.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(statusTmp, statusFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                    Files.move(statusTmp, statusFile, StandardCopyOption.REPLACE_EXISTING);
                }
                ok = true;
            } catch (IOException | RuntimeException failure) {
                ok = false;
            }
            synchronized (lock) {
                if (ok) {
                    statusWriteFailures = 0;
                } else {
                    // Cumulative statusErrors never resets; the consecutive counter does.
                    // The first failure already marks the run incomplete. Reaching the cap
                    // only stops further status writes — records keep draining.
                    statusWriteFailures++;
                    statusErrors++;
                    incomplete = true;
                    if (statusWriteFailures >= MAX_STATUS_FAILURES) {
                        statusDisabled = true;
                    }
                }
            }
            final StatusListener l = listener;
            if (l != null) {
                try {
                    l.statusResult(ok);
                } catch (Throwable ignored) {
                    // Listener feedback must never reach the writer loop.
                }
            }
        }
    }
}
