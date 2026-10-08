package dev.turboism.validation.triweave;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded isolated sink: one daemon writer, per-file byte caps, non-blocking offer on the
 * host path. Mirrors the T029-IDENTITY probe's contract — callbacks never create threads,
 * never retry writes, and field escaping+capping happens while appending.
 *
 * <p>{@link #markInvalid} is deliberately synchronous: a leg-invalid marker is the hard-fail
 * evidence and must be durable even if the daemon writer is still draining or the JVM exits.
 */
public final class Sink {
    /** Default per-field output cap in characters, including the key. */
    private static final int FIELD_CAP = 256;
    /** Cap for the ordered edge index sequence field. */
    private static final int FIELD_CAP_EDGES = 4096;
    /** Hard per-line cap; per-field bounds make this a backstop. */
    private static final int LINE_CAP = 4800;
    private static final int FILE_CAP_BYTES = 64 * 1024;
    private static final int QUEUE_CAP = 32;
    private static final String TRUNC = "~truncated";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final AtomicInteger WRITES_ATTEMPTED = new AtomicInteger();
    private static final AtomicInteger WRITES_FAILED = new AtomicInteger();
    private static final AtomicInteger QUEUE_DROPPED = new AtomicInteger();
    private static final AtomicInteger BYTES_WRITTEN_DEF = new AtomicInteger();
    private static final AtomicInteger BYTES_WRITTEN_DUMP = new AtomicInteger();
    private static final AtomicInteger INVALID_MARKED = new AtomicInteger();
    private static final AtomicBoolean WRITER_STARTED = new AtomicBoolean();
    private static final AtomicBoolean WRITER_FAILED = new AtomicBoolean();

    private static final BlockingQueue<String[]> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAP);
    private static volatile WeaveAbConfig CONFIG;

    /** Capture-time parameters exposed to the capture callsite (same schema both modes). */
    public static final class Params {
        public final int captureN;
        public final String mode;
        public final String runId;
        public final String phase;
        public final String helperInternal;

        Params(WeaveAbConfig cfg) {
            this.captureN = cfg.captureN;
            this.mode = cfg.mode;
            this.runId = cfg.runId;
            this.phase = cfg.phase;
            this.helperInternal = cfg.candidateHelperInternal == null
                ? "" : cfg.candidateHelperInternal;
        }
    }

    private static volatile Params PARAMS;

    private Sink() {}

    /** Called from premain only; resolves the class early so linkage cost is not on the hot path. */
    public static void warm() {}

    static void installConfig(WeaveAbConfig config) {
        CONFIG = config;
        PARAMS = new Params(config);
    }

    /** Null before premain installs a validated config — capture no-ops then. */
    public static Params params() {
        return PARAMS;
    }

    static WeaveAbConfig config() {
        return CONFIG;
    }

    static boolean startWriter() {
        if (!WRITER_STARTED.compareAndSet(false, true)) return true;
        try {
            Thread writer = new Thread(Sink::writerLoop, "tri-weave-writer");
            writer.setDaemon(true);
            writer.start();
            return true;
        } catch (Throwable t) {
            WRITER_FAILED.set(true);
            return false;
        }
    }

    /** Definition-observation line; the transformer bounds event count, this sink bounds queue. */
    static void def(String line) {
        offer(new String[] {"def", line});
    }

    /** Bounded capture record line; written to the dump sink. */
    public static void dumpLine(String line) {
        offer(new String[] {"dump", line});
    }

    /**
     * Leg-invalidity marker: synchronous append to the status file, never queued. Emitted when a
     * transform-level gate rejects the pinned-correct target bytes — the leg must be treated as
     * INVALID, never silently downgraded to baseline.
     */
    static void markInvalid(String reason) {
        INVALID_MARKED.incrementAndGet();
        WeaveAbConfig cfg = CONFIG;
        if (cfg == null || cfg.outputDir.isEmpty()) return;
        StringBuilder sb = new StringBuilder(256);
        field(sb, "legStatus", "INVALID");
        field(sb, "reason", reason);
        field(sb, "mode", cfg.mode);
        field(sb, "runId", cfg.runId);
        try {
            Files.createDirectories(Path.of(cfg.outputDir));
            Files.write(cfg.statusFile(), (sb + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            WRITES_FAILED.incrementAndGet();
        }
    }

    /**
     * Appends {@code key=escaped(value)} at the default field cap. Escape + cap happen per
     * character while appending — the input is never fully scanned or allocated first.
     */
    public static void field(StringBuilder sb, String key, String value) {
        field(sb, key, value, FIELD_CAP);
    }

    /** Same contract at a caller-selected cap (the edges field uses FIELD_CAP_EDGES). */
    public static void field(StringBuilder sb, String key, String value, int cap) {
        if (sb.length() > 0) sb.append(' ');
        int room = cap - key.length() - 1;
        if (room <= TRUNC.length()) {
            sb.append(key).append('=').append(TRUNC, 0, Math.max(0, room));
            return;
        }
        sb.append(key).append('=');
        if (value == null || value.isEmpty()) {
            sb.append("unset");
            return;
        }
        int budget = room;
        int i = 0;
        int len = value.length();
        boolean truncated = false;
        while (i < len) {
            char c = value.charAt(i);
            int cost = escCost(c);
            if (cost > budget - TRUNC.length()) {
                truncated = true;
                break;
            }
            appendEsc(sb, c);
            budget -= cost;
            i++;
        }
        if (truncated) {
            sb.append(TRUNC);
        }
    }

    public static int edgesFieldCap() { return FIELD_CAP_EDGES; }

    private static int escCost(char c) {
        if (c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == ' ') return 2;
        if (c < 0x20 || c == 0x7f) return 4;
        return 1;
    }

    private static void appendEsc(StringBuilder sb, char c) {
        switch (c) {
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            case ' ' -> sb.append("\\s");
            default -> {
                if (c < 0x20 || c == 0x7f) {
                    sb.append("\\x").append(HEX[c >> 4]).append(HEX[c & 0xf]);
                } else {
                    sb.append(c);
                }
            }
        }
    }

    private static void offer(String[] tagged) {
        if (tagged[1].length() > LINE_CAP) {
            tagged[1] = tagged[1].substring(0, LINE_CAP - TRUNC.length()) + TRUNC;
        }
        if (!QUEUE.offer(tagged)) {
            QUEUE_DROPPED.incrementAndGet();
        }
    }

    private static void writerLoop() {
        while (true) {
            String[] tagged;
            try {
                tagged = QUEUE.take();
            } catch (InterruptedException e) {
                return; // daemon exits on JVM teardown
            }
            writeOnce(tagged[0], tagged[1]);
        }
    }

    private static void writeOnce(String tag, String line) {
        WRITES_ATTEMPTED.incrementAndGet();
        WeaveAbConfig cfg = CONFIG;
        if (cfg == null) { WRITES_FAILED.incrementAndGet(); return; }
        Path target = "def".equals(tag) ? cfg.definitionLog() : cfg.dumpLog();
        AtomicInteger bytes = "def".equals(tag) ? BYTES_WRITTEN_DEF : BYTES_WRITTEN_DUMP;
        byte[] utf = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.get() + utf.length > FILE_CAP_BYTES) {
            if (bytes.get() < FILE_CAP_BYTES) {
                writeBytes(target, "bytesCapReached=true\n".getBytes(StandardCharsets.UTF_8));
                bytes.set(FILE_CAP_BYTES);
            }
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
                w.newLine();
            }
            bytes.addAndGet(utf.length);
        } catch (Throwable t) {
            WRITES_FAILED.incrementAndGet();
        }
    }

    private static void writeBytes(Path target, byte[] payload) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, payload, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            WRITES_FAILED.incrementAndGet();
        }
    }

    /** Self-check visibility; also the declared evidence-absence semantics. */
    public static String status() {
        return "writesAttempted=" + WRITES_ATTEMPTED.get()
            + " writesFailed=" + WRITES_FAILED.get()
            + " queueDropped=" + QUEUE_DROPPED.get()
            + " invalidMarked=" + INVALID_MARKED.get()
            + " writerFailed=" + WRITER_FAILED.get();
    }
}
