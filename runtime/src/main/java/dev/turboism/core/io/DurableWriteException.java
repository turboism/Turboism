package dev.turboism.core.io;

import java.io.IOException;
import java.util.Objects;

/**
 * Failure to publish bytes through {@link DurableFile}, carrying a closed reason set so every
 * caller keeps its existing diagnostic code instead of pattern-matching a raw JDK exception.
 *
 * <p>It is an {@link IOException} because every caller already handles that: the migration of a
 * call site maps {@link Failure} onto the code it reports today and nothing else changes.
 */
public class DurableWriteException extends IOException {

    private static final long serialVersionUID = 1L;

    private final Failure failure;

    /**
     * @param failure which publication step refused to complete
     * @param message detail that never contains file content
     * @throws NullPointerException if either argument is {@code null}
     */
    public DurableWriteException(final Failure failure, final String message) {
        super(Objects.requireNonNull(message, "message"));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    /**
     * @param failure which publication step refused to complete
     * @param message detail that never contains file content
     * @param cause the platform failure that made the step fail, kept for diagnosis
     * @throws NullPointerException if {@code failure}, {@code message} or {@code cause} is
     *     {@code null}
     */
    public DurableWriteException(final Failure failure, final String message, final Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    /** @return which publication step refused to complete; never {@code null} */
    public Failure failure() {
        return failure;
    }

    /** The closed set of reasons a publication can be refused, one per caller action. */
    public enum Failure {
        /** The file system refused {@code ATOMIC_MOVE} and the caller required atomicity. */
        ATOMIC_REPLACE_UNAVAILABLE,
        /** The file system exposes neither POSIX permissions nor an ACL, so owner-only could not be applied. */
        OWNER_ONLY_UNAVAILABLE,
        /** A create-once target already exists; it was not replaced. */
        ALREADY_EXISTS,
        /** Any other filesystem problem: staging, writing, forcing, renaming or forcing the directory. */
        IO_FAILURE
    }
}
