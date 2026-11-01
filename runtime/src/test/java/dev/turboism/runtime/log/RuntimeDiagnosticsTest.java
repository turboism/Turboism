package dev.turboism.runtime.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeDiagnosticsTest {

    @AfterEach
    void clearSink() {
        RuntimeDiagnostics.clear();
    }

    @Test
    void routesFrameworkDiagnosticsOnlyToTheInstalledSink() {
        final List<String> records = new ArrayList<>();
        RuntimeDiagnostics.install(
                (level, component, message, failure) -> records.add(level + ":" + component + ":" + message));

        RuntimeDiagnostics.debug("lifecycle", "Installed verified lifecycle hooks");
        RuntimeDiagnostics.warn("physics-editor", "Physics editor unavailable");

        assertEquals(
                List.of(
                        "DEBUG:lifecycle:Installed verified lifecycle hooks",
                        "WARN:physics-editor:Physics editor unavailable"),
                records);

        RuntimeDiagnostics.clear();
        RuntimeDiagnostics.info("bootstrap", "not routed");
        assertEquals(2, records.size());
    }

    @Test
    void buffersPreInstallDiagnosticsAndReplaysThemInOrder() {
        RuntimeDiagnostics.debug("bootstrap", "Startup suppression: STARTUP_SUPPRESSION_INSTALLED_5303");
        RuntimeDiagnostics.warn("bootstrap", "premain warning");

        final List<String> records = new ArrayList<>();
        RuntimeDiagnostics.install(
                (level, component, message, failure) -> records.add(level + ":" + component + ":" + message));

        assertEquals(
                List.of(
                        "DEBUG:bootstrap:Startup suppression: STARTUP_SUPPRESSION_INSTALLED_5303",
                        "WARN:bootstrap:premain warning"),
                records);

        RuntimeDiagnostics.info("bootstrap", "after install");
        assertEquals(3, records.size());
    }

    @Test
    void clearDropsBufferedDiagnosticsWithoutReplayingThem() {
        RuntimeDiagnostics.debug("bootstrap", "buffered before clear");

        RuntimeDiagnostics.clear();
        final List<String> records = new ArrayList<>();
        RuntimeDiagnostics.install(
                (level, component, message, failure) -> records.add(level + ":" + component + ":" + message));

        assertEquals(List.of(), records);
    }

    @Test
    void optionalDiagnosticsFollowTheLiveSinkAndStopOnClear() {
        RuntimeDiagnostics.clear();
        assertFalse(RuntimeDiagnostics.isEnabled(RuntimeDiagnostics.Level.TRACE));
        final AtomicBoolean enabled = new AtomicBoolean();
        RuntimeDiagnostics.install(new RuntimeDiagnostics.Sink() {
            @Override
            public boolean isEnabled(final RuntimeDiagnostics.Level level) {
                return level != RuntimeDiagnostics.Level.TRACE || enabled.get();
            }

            @Override
            public void write(RuntimeDiagnostics.Level level, String component, String message, Throwable failure) {}
        });
        assertTrue(RuntimeDiagnostics.isEnabled(RuntimeDiagnostics.Level.INFO));
        assertFalse(RuntimeDiagnostics.isEnabled(RuntimeDiagnostics.Level.TRACE));
        enabled.set(true);
        assertTrue(RuntimeDiagnostics.isEnabled(RuntimeDiagnostics.Level.TRACE));
        RuntimeDiagnostics.clear();
        assertFalse(RuntimeDiagnostics.isEnabled(RuntimeDiagnostics.Level.TRACE));
    }

    @Test
    void failingEnablementCheckCannotEscapeIntoTheHost() {
        RuntimeDiagnostics.install(new RuntimeDiagnostics.Sink() {
            @Override
            public boolean isEnabled(final RuntimeDiagnostics.Level level) {
                throw new IllegalStateException("unavailable sink");
            }

            @Override
            public void write(RuntimeDiagnostics.Level level, String component, String message, Throwable failure) {}
        });
        assertFalse(RuntimeDiagnostics.isEnabled(RuntimeDiagnostics.Level.TRACE));
    }
}
