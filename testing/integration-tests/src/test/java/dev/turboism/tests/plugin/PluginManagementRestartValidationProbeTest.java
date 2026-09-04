package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagementRestartValidationProbeTest {

    @TempDir
    Path temporary;

    @Test
    void recognizesEnabledTargetInLoadReport() throws Exception {
        final Path report = temporary.resolve("plugin-load-report.json");
        Files.writeString(report, "{\"payload\":{\"plugins\":["
            + "{\"pluginId\":\"dev.turboism.validation.plugin-management-chooser\","
            + "\"discoveryState\":\"DISCOVERED\",\"dependencyState\":\"RESOLVED\","
            + "\"lifecycleState\":\"ENABLED\"},"
            + "{\"pluginId\":\"other\"}]}}");

        assertTrue(PluginManagementRestartValidationProbe.loadReportShowsEnabled(
            report, "dev.turboism.validation.plugin-management-chooser"));
    }

    @Test
    void rejectsNonEnabledTarget() throws Exception {
        final Path report = temporary.resolve("plugin-load-report.json");
        Files.writeString(report, "{\"pluginId\":\"dev.turboism.validation.plugin-management-chooser\","
            + "\"discoveryState\":\"DISCOVERED\",\"dependencyState\":\"RESOLVED\","
            + "\"lifecycleState\":\"FAILED\"}");

        assertFalse(PluginManagementRestartValidationProbe.loadReportShowsEnabled(
            report, "dev.turboism.validation.plugin-management-chooser"));
    }
}
