package dev.turboism.mapping.verification;

import java.util.Objects;

/** Sanitized runtime failure while invoking a verified host selector. */
public final class VerifiedAccessException extends RuntimeException {

    private final String alias;
    private final FailureKind failureKind;
    private final HostFailureCategory hostFailureCategory;

    public VerifiedAccessException(
            final String alias, final FailureKind failureKind, final String message, final Throwable cause) {
        this(alias, failureKind, message, cause, HostFailureCategory.UNKNOWN);
    }

    private VerifiedAccessException(
            final String alias,
            final FailureKind failureKind,
            final String message,
            final Throwable cause,
            final HostFailureCategory hostFailureCategory) {
        super(requireText(message, "message"), cause);
        this.alias = requireText(alias, "alias");
        this.failureKind = Objects.requireNonNull(failureKind, "failureKind");
        this.hostFailureCategory = Objects.requireNonNull(hostFailureCategory, "hostFailureCategory");
    }

    /**
     * @return the verified selector alias that failed, which names the host
     *     member without exposing it
     */
    public String alias() {
        return alias;
    }

    /**
     * @return whether the call site itself could not be used
     *     ({@code RESOLUTION}) or the host method ran and threw
     *     ({@code INVOCATION}); the host throwable itself is never carried
     */
    public FailureKind failureKind() {
        return failureKind;
    }

    /** Fixed diagnostic category only; no host throwable, class name or message is retained. */
    public HostFailureCategory hostFailureCategory() {
        return hostFailureCategory;
    }

    static VerifiedAccessException invocationFailure(
            final String alias, final String message, final Throwable failure) {
        return new VerifiedAccessException(alias, FailureKind.INVOCATION, message, null, categorize(failure));
    }

    private static HostFailureCategory categorize(final Throwable failure) {
        // Some native libraries wrap their cause. Bound traversal and do not retain that graph.
        final java.util.IdentityHashMap<Throwable, Boolean> seen = new java.util.IdentityHashMap<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (seen.put(current, Boolean.TRUE) != null) return HostFailureCategory.UNKNOWN;
            final Throwable next;
            try {
                next = current.getCause();
            } catch (RuntimeException | LinkageError inaccessible) {
                return HostFailureCategory.UNKNOWN;
            }
            if (next != null) {
                current = next;
                continue;
            }
            if (current instanceof OutOfMemoryError) return HostFailureCategory.OUT_OF_MEMORY;
            if (current instanceof java.io.IOException) return HostFailureCategory.IO;
            if (current instanceof NullPointerException) return HostFailureCategory.NULL_POINTER;
            if (current instanceof IllegalArgumentException) return HostFailureCategory.ILLEGAL_ARGUMENT;
            if (current instanceof IllegalStateException) return HostFailureCategory.ILLEGAL_STATE;
            if (current instanceof IndexOutOfBoundsException) return HostFailureCategory.INDEX_OUT_OF_BOUNDS;
            if (current instanceof SecurityException) return HostFailureCategory.SECURITY;
            if (current instanceof LinkageError) return HostFailureCategory.LINKAGE;
            return HostFailureCategory.UNKNOWN;
        }
        return HostFailureCategory.UNKNOWN;
    }

    /** Sanitized native failure category, without host paths or exception messages. */
    public enum HostFailureCategory {
        UNKNOWN,
        OUT_OF_MEMORY,
        IO,
        NULL_POINTER,
        ILLEGAL_ARGUMENT,
        ILLEGAL_STATE,
        INDEX_OUT_OF_BOUNDS,
        SECURITY,
        LINKAGE
    }

    /** Which stage of a verified selector call failed. */
    public enum FailureKind {
        /** The call site itself could not be resolved or used. */
        RESOLUTION,
        /** The host member ran and threw. */
        INVOCATION
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
