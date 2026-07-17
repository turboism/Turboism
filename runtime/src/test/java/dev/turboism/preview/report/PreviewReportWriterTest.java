package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

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
            "../private/plugin.jar",
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
    void typeMismatchFailsWithoutCreatingPlaceholder() throws Exception {
        final Path state = temporary.resolve("state");
        final PreviewReportWriter writer = new PreviewReportWriter(state, ignored -> { });
        final ObjectNode wrong = PreviewReportDocuments.emptyReport(
            PreviewReportType.I18N,
            "runtime-writer-test",
            Instant.parse("2026-07-15T00:00:00Z")
        );
        assertFalse(writer.write(PreviewReportType.CAPABILITY, wrong));
        assertFalse(Files.exists(state.resolve(PreviewReportType.CAPABILITY.fileName())));
    }

    @Test
    void sanitizesSensitiveTextAcrossAllFourVariantsBeforeStrictValidation() throws Exception {
        final Path state = temporary.resolve("state");
        final PreviewReportWriter writer = new PreviewReportWriter(state, ignored -> { });
        for (PreviewReportType type : PreviewReportType.values()) {
            final ObjectNode report = reportWithSensitiveText(type);
            assertTrue(writer.write(type, report), type.name());
            final byte[] written = Files.readAllBytes(state.resolve(type.fileName()));
            PreviewReportValidator.validate(written);
            final JsonNode document = PreviewReportValidator.validate(written).document();
            final String json = new String(written, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("redacted", document.path("runtimeId").asText(), type.name());
            assertFalse(document.has("relativeRecordPath"), type.name());
            assertFalse(json.contains("C:/Users/alice/private.txt"), type.name());
            assertFalse(json.contains("fileserver"), type.name());
            assertFalse(json.contains("/home/alice/private.txt"), type.name());
            assertFalse(json.contains("~/private.txt"), type.name());
            assertFalse(json.contains("https://private.example/path"), type.name());
            assertFalse(json.contains("Bearer-secret"), type.name());
            assertFalse(json.contains("token-super-secret"), type.name());
            assertFalse(json.contains("grant-private-id"), type.name());
            assertFalse(json.contains("handle-private-id"), type.name());
            assertFalse(json.contains("com.private.HostException"), type.name());
            assertFalse(json.contains("private detail"), type.name());
            assertTrue(json.contains("<redacted-"), type.name());
        }
    }

    @Test
    void deterministicallyTruncatesEveryOversizedVariantAndStrictlyValidatesOutput()
        throws Exception {
        for (PreviewReportType type : PreviewReportType.values()) {
            final ObjectNode first = oversized(type);
            final ObjectNode second = oversized(type);
            final Path firstState = temporary.resolve("first-" + type.name());
            final Path secondState = temporary.resolve("second-" + type.name());
            assertTrue(new PreviewReportWriter(firstState, ignored -> { }).write(type, first));
            assertTrue(new PreviewReportWriter(secondState, ignored -> { }).write(type, second));
            final byte[] firstBytes = Files.readAllBytes(firstState.resolve(type.fileName()));
            final byte[] secondBytes = Files.readAllBytes(secondState.resolve(type.fileName()));
            assertArrayEquals(firstBytes, secondBytes, type.name());
            assertTrue(firstBytes.length <= PreviewReportValidator.MAX_REPORT_BYTES, type.name());
            final JsonNode validated = PreviewReportValidator.validate(firstBytes).document();
            assertTrue(validated.path("truncation").path("truncated").booleanValue(), type.name());
            assertTrue(validated.path("truncation").path("droppedEntries").longValue() > 0, type.name());
            assertNotEquals("NONE", validated.path("truncation").path("reason").textValue());
        }
    }

    @Test
    void sanitizesLargeOrdinaryTextBatchWithinBoundedTime() {
        assertTimeout(Duration.ofSeconds(2), () -> {
            final PreviewReportSanitizer sanitizer = new PreviewReportSanitizer();
            for (int index = 0; index < 1000; index++) {
                final ObjectNode report = baseReport(PreviewReportType.PREVIEW_RUNTIME);
                ((ObjectNode) report.path("payload").path("host"))
                    .put("product", "x".repeat(10_000));
                sanitizer.sanitize(report);
            }
        });
    }

    @Test
    void runtimeFailureArraysRemainInTheirOriginalCategoriesWhenTruncated() throws Exception {
        final ObjectNode report = baseReport(PreviewReportType.PREVIEW_RUNTIME);
        final ObjectNode payload = (ObjectNode) report.path("payload");
        for (int index = 0; index < 700; index++) {
            addFailure((ArrayNode) payload.path("taskFailures"), "task-" + index + "-" + "x".repeat(900));
            addFailure((ArrayNode) payload.path("storageFailures"), "storage-" + index + "-" + "x".repeat(900));
            addFailure((ArrayNode) payload.path("configFailures"), "config-" + index + "-" + "x".repeat(900));
        }
        final Path state = temporary.resolve("categories");
        assertTrue(new PreviewReportWriter(state, ignored -> { }).write(
            PreviewReportType.PREVIEW_RUNTIME, report
        ));
        final JsonNode payloadWritten = PreviewReportValidator.validate(Files.readAllBytes(
            state.resolve(PreviewReportType.PREVIEW_RUNTIME.fileName())
        )).document().path("payload");
        for (JsonNode failure : payloadWritten.path("taskFailures")) {
            assertTrue(failure.path("message").asText().startsWith("task-"));
        }
        for (JsonNode failure : payloadWritten.path("storageFailures")) {
            assertTrue(failure.path("message").asText().startsWith("storage-"));
        }
        for (JsonNode failure : payloadWritten.path("configFailures")) {
            assertTrue(failure.path("message").asText().startsWith("config-"));
        }
    }

    @Test
    void minimumSummaryStillOverLimitPreservesPreviousValidFile() throws Exception {
        final Path state = temporary.resolve("state");
        final PreviewReportWriter writer = new PreviewReportWriter(state, ignored -> { });
        final ObjectNode valid = PreviewReportDocuments.emptyReport(
            PreviewReportType.PREVIEW_RUNTIME,
            "runtime-writer-test",
            Instant.parse("2026-07-15T00:00:00Z")
        );
        assertTrue(writer.write(PreviewReportType.PREVIEW_RUNTIME, valid));
        final Path target = state.resolve(PreviewReportType.PREVIEW_RUNTIME.fileName());
        final byte[] before = Files.readAllBytes(target);
        final ObjectNode impossible = valid.deepCopy();
        impossible.put("runtimeId", "x".repeat(PreviewReportValidator.MAX_REPORT_BYTES));

        assertFalse(writer.write(PreviewReportType.PREVIEW_RUNTIME, impossible));

        assertArrayEquals(before, Files.readAllBytes(target));
    }

    private static ObjectNode reportWithSensitiveText(final PreviewReportType type) {
        final ObjectNode report = baseReport(type);
        final String sensitive = "C:/Users/alice/private.txt "
            + "\\\\fileserver\\private\\item /home/alice/private.txt ~/private.txt "
            + "https://private.example/path Authorization: bearer-secret "
            + "token-super-secret grant-private-id handle-private-id "
            + "com.private.HostException: private detail";
        report.put("runtimeId", "token-private-runtime");
        report.put("relativeRecordPath", "/home/alice/private-record.json");
        switch (type) {
            case PREVIEW_RUNTIME -> ((ObjectNode) report.path("payload").path("host"))
                .put("product", sensitive);
            case PLUGIN_LOAD -> addPlugin(report, sensitive);
            case CAPABILITY -> addCapability(report, sensitive);
            case I18N -> addI18n(report, sensitive);
        }
        return report;
    }

    private static ObjectNode oversized(final PreviewReportType type) {
        final ObjectNode report = baseReport(type);
        final String padding = "x".repeat(900);
        for (int index = 0; index < 2000; index++) {
            final String value = padding + index;
            switch (type) {
                case PREVIEW_RUNTIME -> addFailure(
                    (ArrayNode) report.path("payload").path("taskFailures"), value
                );
                case PLUGIN_LOAD -> addPlugin(report, value);
                case CAPABILITY -> addCapability(report, value);
                case I18N -> addI18n(report, value);
            }
        }
        return report;
    }

    private static ObjectNode baseReport(final PreviewReportType type) {
        return PreviewReportDocuments.emptyReport(
            type,
            "runtime-writer-test",
            Instant.parse("2026-07-15T00:00:00Z")
        );
    }

    private static void addFailure(final ArrayNode failures, final String message) {
        failures.add(PreviewReportDocuments.failure(
            "PRIVATE_FAILURE", "ERROR", "runtime", null, null, null, message, null, 1
        ));
    }

    private static void addPlugin(final ObjectNode report, final String message) {
        final ObjectNode plugin = PreviewReportDocuments.pluginLoadEntry(
            "dev.example.plugin", null, null, "DISCOVERED", "RESOLVED", "ENABLED", false
        );
        addFailure((ArrayNode) plugin.path("failures"), message);
        ((ArrayNode) report.path("payload").path("plugins")).add(plugin);
    }

    private static void addCapability(final ObjectNode report, final String summary) {
        final ObjectNode capability = PreviewReportDocuments.capabilityEntry(
            "dev.example.plugin", "cubism.project.read", "cubism.project.read", null,
            "UNKNOWN", "NOT_DECLARED", "NONE"
        );
        ((ArrayNode) capability.path("evidence")).add(PreviewReportDocuments.evidence(
            "DECLARED", "UNKNOWN", summary, null, null
        ));
        ((ArrayNode) report.path("payload").path("capabilities")).add(capability);
    }

    private static void addI18n(final ObjectNode report, final String marker) {
        final ObjectNode plugin = PreviewReportDocuments.i18nPluginEntry(
            "dev.example.plugin", "JVM_DISPLAY_DEFAULT", "en-US", "en-US",
            List.of("en", "base", "marker")
        );
        final ObjectNode missing = PreviewReportDocuments.JSON.createObjectNode();
        missing.put("key", "missing.key");
        missing.put("locale", "en-US");
        missing.put("marker", marker);
        missing.put("count", 1);
        ((ArrayNode) plugin.path("missingKeys")).add(missing);
        ((ArrayNode) report.path("payload").path("plugins")).add(plugin);
    }
}
