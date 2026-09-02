package dev.turboism.runtime.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeDiagnosticsTest {

    @AfterEach
    void clearSink() {
        RuntimeDiagnostics.clear();
    }

    @Test
    void routesFrameworkDiagnosticsOnlyToTheInstalledSink() {
        final List<String> records = new ArrayList<>();
        RuntimeDiagnostics.install((level, component, message, failure) ->
            records.add(level + ":" + component + ":" + message)
        );

        RuntimeDiagnostics.debug("lifecycle", "Installed verified lifecycle hooks");
        RuntimeDiagnostics.warn("physics-editor", "Physics editor unavailable");

        assertEquals(List.of(
            "DEBUG:lifecycle:Installed verified lifecycle hooks",
            "WARN:physics-editor:Physics editor unavailable"
        ), records);

        RuntimeDiagnostics.clear();
        RuntimeDiagnostics.info("bootstrap", "not routed");
        assertEquals(2, records.size());
    }
}
