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
 * <p>Host-path contract: {@link #record} builds one bounded snapshot string and performs one
 * non-blocking offer. The single daemon writer is started at premain by {@link #startWriter} —
 * callbacks never create threads. Every field is escaped and length-capped per character while
 * appending (bounded scan, bounded output, truncation marked); a per-file byte cap applies on the
 * writer side. No collection traversal, no element access, no reflective field access, no field
 * writes, no business calls, no retained strong references.
 */
public final class Probe {
    /** Per-field output cap in characters, including the key and any truncation marker. */
    private static final int FIELD_CAP = 256;
    /** Hard per-line cap; per-field bounds make this a backstop, not the primary limiter. */
    private static final int LINE_CAP = 2000;
    private static final int FILE_CAP_BYTES = 64 * 1024;
    private static final int QUEUE_CAP = 32;
    private static final String TRUNC = "~truncated";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

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
     * Bounded per-run loader identity hint: class name plus {@link System#identityHashCode} of
     * the loader instance. identityHashCode can collide — this is an association hint within
     * THIS run only, not a unique instance ID and not stable across runs.
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
        return bound(sb);
    }

    /**
     * Appends {@code key=escaped(value)}. Escape + cap happen per character while appending —
     * the input is scanned only until the field's output budget is exhausted, the full input is
     * never allocated or walked. Field separators outside this method stay literal spaces.
     */
    static void field(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append(' ');
        int room = FIELD_CAP - key.length() - 1;
        if (room <= TRUNC.length()) {
            sb.append(key).append('=').append(TRUNC, 0, Math.max(0, room));
            return;
        }
        sb.append(key).append('=');
        if (value == null || value.isEmpty()) {
            sb.append("unset");
            return;
        }
        // room counts the remaining output budget for this field; escape output counts against
        // the same budget so a multi-char escape can never overflow.
        // Reserve room for the truncation marker so the marker is inside the field cap.
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

    /** Standalone bounded field for callers building observation lines field-by-field. */
    static String boundedField(String key, String value) {
        StringBuilder sb = new StringBuilder(FIELD_CAP + 16);
        field(sb, key, value);
        return sb.toString();
    }

    private static int escCost(char c) {
        if (c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == ' ') return 2;
        if (c < 0x20 || c == 0x7f) return 4;
        return 1;
    }

    /**
     * Escapes: {@code \}→{@code \\} so a literal two-character backslash-n stays distinguishable
     * from a real newline; {@code \n}→{@code \n} (escape), {@code \r}→{@code \r},
     * {@code \t}→{@code \t}, space→{@code \s} (inside values only — field separators in the
     * surrounding line remain real spaces), other control chars→{@code \xNN}.
     */
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

    /** Hard line bound; callers are already per-field bounded so this is a backstop. */
    private static String bound(StringBuilder sb) {
        if (sb.length() <= LINE_CAP) return sb.toString();
        return sb.substring(0, LINE_CAP - TRUNC.length()) + TRUNC;
    }

    private static String codeSource(Class<?> cls) {
        if (cls == null) return "null-arg";
        ProtectionDomain pd = cls.getProtectionDomain();
        CodeSource cs = pd == null ? null : pd.getCodeSource();
        URL loc = cs == null ? null : cs.getLocation();
        return loc == null ? "no-codesource" : loc.toString();
    }

    private static void offer(String[] tagged) {
        if (tagged[1].length() > LINE_CAP) {
            tagged[1] = tagged[1].substring(0, LINE_CAP - TRUNC.length()) + TRUNC;
        }
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
