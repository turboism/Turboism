package dev.turboism.core.runtime;

import java.util.Objects;

/**
 * Unchecked wrapper for a {@link Throwable} that is neither an {@link Error} nor an
 * {@link Exception}, used when such a throwable must cross a boundary that cannot declare it.
 */
public final class UncheckedThrowableException extends RuntimeException {

    public UncheckedThrowableException(final Throwable cause) {
        super(Objects.requireNonNull(cause, "cause"));
    }
}
