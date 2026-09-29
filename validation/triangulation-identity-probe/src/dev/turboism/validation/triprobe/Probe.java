package dev.turboism.validation.triprobe;

import java.io.BufferedWriter;
import java.io.IOException;
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
 * non-blocking offer. All file IO happens on a single daemon writer; no retry, no traversal of the
 * observed collection, no element access, no reflective field access, no business calls.
 */
public final class Probe {
    private static final int FIELD_CAP = 512;
    private static final int SNAPSHOT_CAP = 4000;

    private static final AtomicBoolean USE_SITE_CLAIMED = new AtomicBoolean();
    private static final AtomicInteger WRITES_ATTEMPTED = new AtomicInteger();
    private static final AtomicInteger WRITES_FAILED = new AtomicInteger();

    private static final BlockingQueue<String[]> QUEUE = new ArrayBlockingQueue<>(32);
    private static final AtomicBoolean WRITER_STARTED = new AtomicBoolean();
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
     * Woven callsite body. The weave wraps this invocation in a {@code catch (Throwable)}, so any
     * linkage, initialization, or call failure must propagate and is absorbed at the callsite.
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

    /** Definition-observation line; the caller bounds event count, this sink only bounds queue. */
    static void defineObserved(String line) {
        offer(new String[] {"define", line});
    }

    private static String snapshot(Object owner, Object set) {
        StringBuilder sb = new StringBuilder(SNAPSHOT_CAP);
        ProbeConfig cfg = CONFIG;
        field(sb, "phase", cfg.phase);
        field(sb, "runId", cfg.runId);
        field(sb, "thread", Thread.currentThread().getName());
        field(sb, "epochMs", Long.toString(System.currentTimeMillis()));
        field(sb, "setClass", set == null ? "null" : set.getClass().getName());
        field(sb, "setLoader", loaderName(set));
        field(sb, "setCodeSource", codeSource(set == null ? null : set.getClass()));
        field(sb, "ownerClass", owner == null ? "null" : owner.getClass().getName());
        field(sb, "ownerLoader", loaderName(owner));
        field(sb, "ownerCodeSource", codeSource(owner == null ? null : owner.getClass()));
        field(sb, "java.version", System.getProperty("java.version"));
        field(sb, "java.vm.version", System.getProperty("java.vm.version"));
        field(sb, "java.home", System.getProperty("java.home"));
        if (sb.length() > SNAPSHOT_CAP) {
            sb.setLength(SNAPSHOT_CAP);
        }
        return sb.toString();
    }

    private static void field(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(key).append('=');
        if (value == null || value.isEmpty()) {
            sb.append("unset");
        } else if (value.length() > FIELD_CAP) {
            sb.append(value, 0, FIELD_CAP);
        } else {
            sb.append(value.replace(' ', '_'));
        }
    }

    private static String loaderName(Object o) {
        if (o == null) return "null-arg";
        ClassLoader cl = o.getClass().getClassLoader();
        return cl == null ? "bootstrap" : cl.getClass().getName();
    }

    private static String codeSource(Class<?> cls) {
        if (cls == null) return "null-arg";
        ProtectionDomain pd = cls.getProtectionDomain();
        CodeSource cs = pd == null ? null : pd.getCodeSource();
        URL loc = cs == null ? null : cs.getLocation();
        return loc == null ? "no-codesource" : loc.toString();
    }

    private static void offer(String[] tagged) {
        QUEUE.offer(tagged);
        if (WRITER_STARTED.compareAndSet(false, true)) {
            Thread writer = new Thread(Probe::writerLoop, "tri-identity-writer");
            writer.setDaemon(true);
            writer.start();
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
        try {
            Files.createDirectories(target.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
                w.newLine();
            }
        } catch (Throwable t) {
            // Single attempt only: the write is retried never, and the failure is in-process state.
            WRITES_FAILED.incrementAndGet();
        }
    }

    /** Self-check visibility: {@code claimed/defineSeq/overflow/writesAttempted/writesFailed}. */
    public static String status() {
        return "useSiteClaimed=" + USE_SITE_CLAIMED.get()
            + " writesAttempted=" + WRITES_ATTEMPTED.get()
            + " writesFailed=" + WRITES_FAILED.get();
    }
}
