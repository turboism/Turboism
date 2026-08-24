package dev.turboism.preview;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Process-private sink for sanitized Recent Preview host diagnostics. */
public final class RecentPreviewDiagnostics {

    private static final AtomicReference<Consumer<String>> SINK =
        new AtomicReference<>(ignored -> { });

    private RecentPreviewDiagnostics() {
    }

    static void install(final Consumer<String> sink) {
        SINK.set(Objects.requireNonNull(sink, "sink"));
    }

    static void uninstall() {
        SINK.set(ignored -> { });
    }

    /** Emits one runtime-authored, path-free Recent Preview diagnostic to the active preview log. */
    public static void emit(final String message) {
        try {
            SINK.get().accept(Objects.requireNonNull(message, "message"));
        } catch (RuntimeException ignored) {
            // Diagnostics must never alter host admission, popup cleanup, or capture behavior.
        }
    }
}
