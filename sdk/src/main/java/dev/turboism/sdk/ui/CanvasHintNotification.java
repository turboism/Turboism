package dev.turboism.sdk.ui;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable request for a native Cubism hint over the drawing area.
 *
 * <p>The native host owns the lower-right placement and visual theme. The
 * notification id is a stable replacement key: showing another hint with the same
 * id replaces the current one, and closing a registration only dismisses the keyed
 * hint that is still current.</p>
 *
 * <p>A notification that carries {@link #onClick()} is routed through the native
 * clickable entry point, so the user can act on the hint by clicking it. Without a
 * click action the hint stays a passive message.</p>
 *
 * @param id stable key used to replace or dismiss one hint
 * @param message message text shown by the native host
 * @param durationSeconds timeout in seconds, or {@link #UNTIL_DISMISSED}
 * @param onClick action run when the user clicks the hint, empty for a passive hint
 */
public record CanvasHintNotification(
    String id,
    String message,
    float durationSeconds,
    Optional<Runnable> onClick
) {
    /** The timeout used by the native Cubism screen-color warning. */
    public static final float DEFAULT_DURATION_SECONDS = 5.0f;

    /** Keeps the hint visible until its returned registration is closed. */
    public static final float UNTIL_DISMISSED = Float.POSITIVE_INFINITY;

    /** Creates a hint using the native warning timeout. */
    public CanvasHintNotification(final String id, final String message) {
        this(id, message, DEFAULT_DURATION_SECONDS);
    }

    /** Creates a passive hint that the user cannot click. */
    public CanvasHintNotification(
        final String id,
        final String message,
        final float durationSeconds
    ) {
        this(id, message, durationSeconds, Optional.empty());
    }

    public CanvasHintNotification {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be null or blank");
        }
        if (Float.isNaN(durationSeconds) || durationSeconds <= 0.0f) {
            throw new IllegalArgumentException(
                    "durationSeconds must be positive or CanvasHintNotification.UNTIL_DISMISSED");
        }
        onClick = Objects.requireNonNull(onClick, "onClick");
    }

    /**
     * Returns a copy of this notification whose click action is {@code action},
     * leaving id, message, and duration untouched.
     *
     * @param action the click action replacing any action this notification already carries
     * @return a new notification sharing every other component with this one
     * @throws NullPointerException when {@code action} is {@code null}
     */
    public CanvasHintNotification withOnClick(final Runnable action) {
        return new CanvasHintNotification(
            id,
            message,
            durationSeconds,
            Optional.of(Objects.requireNonNull(action, "action"))
        );
    }
}
