package dev.turboism.preview;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RecentPreviewDiagnosticsTest {

    @AfterEach
    void resetSink() {
        RecentPreviewDiagnostics.uninstall();
    }

    @Test
    void emitForwardsSanitizedRuntimeDiagnostics() {
        final AtomicReference<String> received = new AtomicReference<>();
        RecentPreviewDiagnostics.install(received::set);

        RecentPreviewDiagnostics.emit("adapter-diag:preview-rebind:IllegalStateException");

        assertEquals("adapter-diag:preview-rebind:IllegalStateException", received.get());
    }

    @Test
    void sinkFailureCannotBreakHostLifecycle() {
        RecentPreviewDiagnostics.install(message -> {
            throw new IllegalStateException("logging unavailable");
        });

        assertDoesNotThrow(() -> RecentPreviewDiagnostics.emit("popup-diag:popup-show"));
    }
}
