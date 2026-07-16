package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewReportWriterTest {

    @TempDir
    Path temporary;

    @Test
    void atomicallyWritesAndValidatesTheCompleteRuntimeSet() throws Exception {
        final Path state = temporary.resolve("state");
        final List<PreviewReportWriter.Diagnostic> diagnostics = new ArrayList<>();
        final PreviewReportWriter writer = new PreviewReportWriter(state, diagnostics::add);
        final Map<PreviewReportType, ObjectNode> documents =
            PreviewReportDocuments.emptyReportSet(
                "runtime-writer-test",
                Instant.parse("2026-07-15T00:00:00Z")
            );

        final Map<PreviewReportType, Boolean> results = writer.writeAll(documents);

        assertEquals(Map.of(
            PreviewReportType.PREVIEW_RUNTIME, true,
            PreviewReportType.PLUGIN_LOAD, true,
            PreviewReportType.CAPABILITY, true,
            PreviewReportType.I18N, true
        ), results);
        final Map<PreviewReportType, byte[]> written = new EnumMap<>(PreviewReportType.class);
        for (PreviewReportType type : PreviewReportType.values()) {
            final Path target = state.resolve(type.fileName());
            assertTrue(Files.isRegularFile(target));
            written.put(type, Files.readAllBytes(target));
        }
        assertEquals(4, PreviewReportValidator.validateSet(written).size());
        assertTrue(diagnostics.isEmpty());
        try (var files = Files.list(state)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp")));
        }
    }

    @Test
    void invalidReplacementPreservesPreviousValidReportAndEmitsSanitizedDiagnostic()
        throws Exception {
        final Path state = temporary.resolve("state");
        final List<PreviewReportWriter.Diagnostic> diagnostics = new ArrayList<>();
        final PreviewReportWriter writer = new PreviewReportWriter(state, diagnostics::add);
        final ObjectNode valid = PreviewReportDocuments.emptyReport(
            PreviewReportType.PLUGIN_LOAD,
            "runtime-writer-test",
            Instant.parse("2026-07-15T00:00:00Z")
        );
        assertTrue(writer.write(PreviewReportType.PLUGIN_LOAD, valid));
        final Path target = state.resolve(PreviewReportType.PLUGIN_LOAD.fileName());
        final byte[] before = Files.readAllBytes(target);

        final ObjectNode invalid = valid.deepCopy();
        final ObjectNode plugin = PreviewReportDocuments.pluginLoadEntry(
            "dev.example.plugin",
            "C:/Users/private/plugin.jar",
            null,
            "DISCOVERED",
            "RESOLVED",
            "ENABLED",
            false
        );
        ((com.fasterxml.jackson.databind.node.ArrayNode) invalid.path("payload").path("plugins"))
            .add(plugin);

        assertFalse(writer.write(PreviewReportType.PLUGIN_LOAD, invalid));
        assertArrayEquals(before, Files.readAllBytes(target));
        assertEquals(1, diagnostics.size());
        assertEquals("PREVIEW_REPORT_WRITE_REJECTED", diagnostics.get(0).code());
        assertFalse(diagnostics.get(0).message().contains("C:/Users/private"));
    }

    @Test
    void typeMismatchAndOversizedOutputFailWithoutCreatingPlaceholder() throws Exception {
        final Path state = temporary.resolve("state");
        final PreviewReportWriter writer = new PreviewReportWriter(state, ignored -> { });
        final ObjectNode wrong = PreviewReportDocuments.emptyReport(
            PreviewReportType.I18N,
            "runtime-writer-test",
            Instant.parse("2026-07-15T00:00:00Z")
        );
        assertFalse(writer.write(PreviewReportType.CAPABILITY, wrong));
        assertFalse(Files.exists(state.resolve(PreviewReportType.CAPABILITY.fileName())));

        final ObjectNode oversized = PreviewReportDocuments.emptyReport(
            PreviewReportType.PREVIEW_RUNTIME,
            "runtime-writer-test",
            Instant.parse("2026-07-15T00:00:00Z")
        );
        ((ObjectNode) oversized.path("payload").path("host"))
            .put("product", "x".repeat(PreviewReportValidator.MAX_REPORT_BYTES));
        assertFalse(writer.write(PreviewReportType.PREVIEW_RUNTIME, oversized));
        assertFalse(Files.exists(state.resolve(PreviewReportType.PREVIEW_RUNTIME.fileName())));
    }
}
