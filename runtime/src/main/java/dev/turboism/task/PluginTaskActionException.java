package dev.turboism.task;

import java.util.Objects;

/**
 * Unchecked failure raised when a plugin task action throws; the original failure is always
 * retained as the cause after the run outcome was recorded.
 */
final class PluginTaskActionException extends RuntimeException {

    PluginTaskActionException(final String message, final Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
    }
}
