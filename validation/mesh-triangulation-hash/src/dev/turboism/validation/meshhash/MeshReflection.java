package dev.turboism.validation.meshhash;

import java.lang.reflect.Method;

/**
 * Reflective readers for the host mesh. The probe must not compile against host classes, so every
 * access goes through reflection by exact name and is failure-tolerant: a missing or renamed method
 * yields {@code -1}/{@code null} rather than an exception into the host.
 */
final class MeshReflection {
    private static final String INDICES_GETTER = "getCached_indices$core";
    private static final String POINTS_GETTER = "getPoints";
    private static final String EDGES_GETTER = "getEdges";

    private MeshReflection() {
    }

    static int[] indices(final Object mesh) {
        final Object value = invoke(mesh, INDICES_GETTER);
        return value instanceof int[] array ? array : null;
    }

    static int points(final Object mesh) {
        return sizeOf(mesh, POINTS_GETTER);
    }

    static int edges(final Object mesh) {
        return sizeOf(mesh, EDGES_GETTER);
    }

    /** Folds one capture into a running digest, so native and patched runs are comparable. */
    static String fold(final String previous, final int[] indices, final long captures) {
        try {
            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update((previous == null ? "NONE" : previous).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) (captures & 0xFF));
            if (indices == null) {
                digest.update((byte) 0x00);
            } else {
                digest.update((byte) 0x01);
                final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(indices.length * 4);
                for (final int value : indices) buffer.putInt(value);
                digest.update(buffer.array());
            }
            final byte[] hash = digest.digest();
            final StringBuilder text = new StringBuilder(hash.length * 2);
            for (final byte value : hash) {
                text.append(Character.forDigit((value >> 4) & 0xF, 16));
                text.append(Character.forDigit(value & 0xF, 16));
            }
            return text.toString();
        } catch (Exception failure) {
            return "NONE";
        }
    }

    private static int sizeOf(final Object mesh, final String method) {
        final Object value = invoke(mesh, method);
        return value instanceof java.util.List<?> list ? list.size() : -1;
    }

    private static Object invoke(final Object mesh, final String method) {
        if (mesh == null) return null;
        try {
            final Method found = mesh.getClass().getMethod(method);
            found.setAccessible(true);
            return found.invoke(mesh);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
