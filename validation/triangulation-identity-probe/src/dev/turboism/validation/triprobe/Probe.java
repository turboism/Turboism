package dev.turboism.validation.triprobe;

import java.io.BufferedWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot use-site recorder plus bounded definition-observation sink.
 *
 * <p>Host-path contract: {@link #record} builds one bounded sanitized snapshot string and performs
 * one non-blocking offer. The single daemon writer is started at premain by {@link #startWriter} —
 * callbacks never create threads. Every queued line is escaped and length-capped before writing;
 * per-file output is byte-capped. No collection traversal, no element access, no reflective field
 * access, no field writes, no business calls, no retained strong references.
 */
public final class Probe {
    private static final int FIELD_CAP = 256;
    private static final int LINE_CAP = 2000;
    private static final int FILE_CAP_BYTES = 64 * 1024;
    private static final int QUEUE_CAP = 32;

    private static final AtomicBoolean USE_SITE_CLAIMED = new AtomicBoolean();
    private static final AtomicInteger WRITES_ATTEMPTED = new AtomicInteger();
    private static final AtomicInteger WRITES_FAILED = new AtomicInteger();
    private static final AtomicInteger QUEUE_DROPPED = new AtomicInteger();
    private static final AtomicInteger BYTES_WRITTEN_DEF = new AtomicInteger();
    private static final AtomicInteger BYTES_WRITTEN_USE = new AtomicInteger();
    private static final AtomicBoolean WRITER_STARTED = new AtomicBoolean();
    private static final AtomicBoolean WRITER_FAILED = new AtomicBoolean();

    private static final BlockingQueue<String[]> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAP);
    private static volatile ProbeConfig CONFIG = new ProbeConfig();

    static {
        if ("true".equals(System.getProperty(ProbeConfig.FAIL_INIT))) {
            throw new IllegalStateException("triIdentity failInit test knob");
        }
    }

    private Probe() {}

    /** Called from premain only; resolves the class early so linkage cost is not on the hot path. */
    public static void warm() {}

    /** Overridden only by the agent premain so the self-check can point the sink at a dir. */
    static void installConfig(ProbeConfig config) {
        CONFIG = config;
    }

    /**
     * Pre-start the single daemon writer at premain. Returns false if the thread cannot start —
     * the caller must then refuse installation entirely.
     */
    static boolean startWriter() {
        if (!WRITER_STARTED.compareAndSet(false, true)) return true;
        try {
            Thread writer = new Thread(Probe::writerLoop, "tri-identity-writer");
            writer.setDaemon(true);
            writer.start();
            return true;
        } catch (Throwable t) {
            WRITER_FAILED.set(true);
            return false;
        }
    }

    /**
     * Woven callsite body. The weave wraps this invocation in a {@code catch (Throwable)}, so any
     * linkage, initialization, or call failure propagates and is absorbed at the callsite.
     */
    public static void record(Object owner, Object set) {
        if (Boolean.getBoolean(ProbeConfig.THROW_ON_RECORD)) {
            throw new IllegalStateException("triIdentity throwOnRecord test knob");
        }
        if (!USE_SITE_CLAIMED.compareAndSet(false, true)) {
            return;
        }
        offer(new String[] {"use-site", snapshot(owner, set)});
    }

    /** Definition-observation line; the transformer bounds event count, this sink bounds queue. */
    static void defineObserved(String line) {
        offer(new String[] {"define", line});
    }

    /**
     * Bounded per-run loader identity: class name plus {@link System#identityHashCode} of the
     * loader instance. The hash token distinguishes same-named loader instances within THIS run
     * only — it is not stable or comparable across runs.
     */
    static String loaderToken(Object o) {
        if (o == null) return "null-arg";
        ClassLoader cl = o.getClass().getClassLoader();
        if (cl == null) return "bootstrap";
        return cl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cl));
    }

    private static String moduleName(Class<?> cls) {
        if (cls == null) return "null-arg";
        Module m = cls.getModule();
        return m == null || !m.isNamed() ? "unnamed" : m.getName();
    }

    private static String snapshot(Object owner, Object set) {
        StringBuilder sb = new StringBuilder(LINE_CAP);
        ProbeConfig cfg = CONFIG;
        field(sb, "phase", cfg.phase);
        field(sb, "runId", cfg.runId);
        field(sb, "thread", Thread.currentThread().getName());
        field(sb, "epochMs", Long.toString(System.currentTimeMillis()));
        Class<?> setCls = set == null ? null : set.getClass();
        Class<?> ownerCls = owner == null ? null : owner.getClass();
        field(sb, "setClass", setCls == null ? "null" : setCls.getName());
        field(sb, "setLoader", loaderToken(set));
        field(sb, "setModule", moduleName(setCls));
        field(sb, "setCodeSource", codeSource(setCls));
        field(sb, "ownerClass", ownerCls == null ? "null" : ownerCls.getName());
        field(sb, "ownerLoader", loaderToken(owner));
        field(sb, "ownerModule", moduleName(ownerCls));
        field(sb, "ownerCodeSource", codeSource(ownerCls));
        field(sb, "java.version", System.getProperty("java.version"));
        field(sb, "java.vm.version", System.getProperty("java.vm.version"));
        field(sb, "java.home", System.getProperty("java.home"));
        return sb.toString();
    }

    /** Escape first, cap second, truncation marked. Values never contain raw control chars. */
    private static void field(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(key).append('=');
        String v = value == null || value.isEmpty() ? "unset" : escape(value);
        if (v.length() > FIELD_CAP) {
            sb.append(v, 0, FIELD_CAP).append("~truncated");
        } else {
            sb.append(v);
        }
    }

    /** Replaces CR/LF/TAB and other control characters with escaped forms; also replaces spaces. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c == ' ') out.append('_');
            else if (c < 0x20 || c == 0x7f) out.append(String.format("\\x%02x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    /** Line-level bound applied to everything the writer can receive. */
    private static String boundLine(String line) {
        String e = escape(line);
        return e.length() > LINE_CAP ? e.substring(0, LINE_CAP) + "~truncated" : e;
    }

    private static String codeSource(Class<?> cls) {
        if (cls == null) return "null-arg";
        ProtectionDomain pd = cls.getProtectionDomain();
        CodeSource cs = pd == null ? null : pd.getCodeSource();
        URL loc = cs == null ? null : cs.getLocation();
        return loc == null ? "no-codesource" : loc.toString();
    }

    private static void offer(String[] tagged) {
        tagged[1] = boundLine(tagged[1]);
        if (!QUEUE.offer(tagged)) {
            // Definite evidence-absence semantics: the drop is counted, never retried.
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
        Path target = "define".equals(tag) ? CONFIG.definitionLog() : CONFIG.useSiteLog();
        AtomicInteger bytes = "define".equals(tag) ? BYTES_WRITTEN_DEF : BYTES_WRITTEN_USE;
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
            // Single attempt only; failure is in-process state, never retried.
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
        return "useSiteClaimed=" + USE_SITE_CLAIMED.get()
            + " writesAttempted=" + WRITES_ATTEMPTED.get()
            + " writesFailed=" + WRITES_FAILED.get()
            + " queueDropped=" + QUEUE_DROPPED.get()
            + " writerFailed=" + WRITER_FAILED.get();
    }
}
