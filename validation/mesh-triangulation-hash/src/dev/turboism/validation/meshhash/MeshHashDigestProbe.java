package dev.turboism.validation.meshhash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded evidence sink called from inside the patched host method.
 *
 * <p>Everything here is deliberately static and public: the host class lives in a different package
 * and loader, so it can only reach a {@code public static} entry point. {@link #capture(Object)}
 * must never throw into the host — every failure is swallowed and surfaced through the evidence
 * file instead.</p>
 */
public final class MeshHashDigestProbe {
    private static final String OUTPUT_PROPERTY = "turboism.validation.meshHash.output";
    private static final long MAX_WRITES = 5000L;
    private static final Object LOCK = new Object();
    private static final AtomicLong WRITES = new AtomicLong();
    private static volatile String blocked = "NONE";
    private static volatile String tripleState = "NOT_SEEN";
    private static volatile String tripleDetail = "NONE";
    private static volatile String meshHook = "NOT_SEEN";
    private static volatile String mode = "UNKNOWN";
    private static long captures;
    private static String digest = "NONE";
    private static int lastIndicesLength = -1;
    private static int points = -1;
    private static int edges = -1;

    private MeshHashDigestProbe() {
    }

    /** Entry point injected before each return of the host's mesh update method. */
    public static void capture(final Object mesh) {
        try {
            final int[] indices = MeshReflection.indices(mesh);
            synchronized (LOCK) {
                digest = MeshReflection.fold(digest, indices, captures);
                captures++;
                lastIndicesLength = indices == null ? -1 : indices.length;
                points = MeshReflection.points(mesh);
                edges = MeshReflection.edges(mesh);
            }
            flush();
        } catch (Throwable ignored) {
            // A probe must never change host behaviour.
        }
    }

    static void setMode(final String value) {
        mode = value;
    }

    static void setTriple(final String state, final String detail) {
        tripleState = state;
        tripleDetail = detail;
        flush();
    }

    static void setMeshHook(final String state, final String detail) {
        meshHook = state;
        if (detail != null && !"ok".equals(detail)) {
            tripleDetail = tripleDetail + "|mesh:" + detail;
        }
        flush();
    }

    static void markBlocked(final String reason) {
        blocked = reason == null ? "unknown" : reason;
        flush();
    }

    static void flush() {
        if (WRITES.get() >= MAX_WRITES) return;
        synchronized (LOCK) {
            if (WRITES.get() >= MAX_WRITES) return;
            WRITES.incrementAndGet();
            final Path output = outputPath();
            if (output == null) return;
            final String body = "mode=" + mode
                + "\ncaptures=" + captures
                + "\ndigest=" + digest
                + "\nlastIndicesLength=" + lastIndicesLength
                + "\npoints=" + points
                + "\nedges=" + edges
                + "\ntripleState=" + tripleState
                + "\ntripleDetail=" + tripleDetail
                + "\nmeshHook=" + meshHook
                + "\nblocked=" + blocked
                + "\n";
            try {
                final Path parent = output.getParent();
                if (parent != null) Files.createDirectories(parent);
                final Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
                Files.write(temporary, body.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failure) {
                blocked = "evidence write failed: " + failure.getClass().getSimpleName();
            }
        }
    }

    private static Path outputPath() {
        final String value = System.getProperty(OUTPUT_PROPERTY);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
