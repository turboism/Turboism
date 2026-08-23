package dev.turboism.preview.report;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewReportValidatorTest {

    private static final String RUNTIME_ID = "runtime-0123456789";

    @Test
    void validatesAllFourClosedVariantsAndCoherentRuntimeSet() {
        final Map<PreviewReportType, byte[]> reports = new EnumMap<>(PreviewReportType.class);
        reports.put(PreviewReportType.PREVIEW_RUNTIME, bytes(previewRuntime()));
        reports.put(PreviewReportType.PLUGIN_LOAD, bytes(pluginLoad()));
        reports.put(PreviewReportType.CAPABILITY, bytes(capability()));
        reports.put(PreviewReportType.I18N, bytes(i18n()));

        final Map<PreviewReportType, PreviewReportValidator.ValidatedReport> validated =
            PreviewReportValidator.validateSet(reports);

        assertEquals(4, validated.size());
        assertEquals(RUNTIME_ID, validated.get(PreviewReportType.I18N).runtimeId());
        assertEquals(
            PreviewReportType.PLUGIN_LOAD,
            validated.get(PreviewReportType.PLUGIN_LOAD).reportType()
        );
    }

    @Test
    void rejectsRecursiveUnknownFieldAndDuplicateKey() {
        final String unknown = pluginLoad().replace(
            "\"badNeighbor\":false,",
            "\"badNeighbor\":false,\"unknownNested\":true,"
        );
        assertCode("UNKNOWN_FIELD", unknown);

        final String duplicate = previewRuntime().replace(
            "\"format\":\"turboism.preview.report\",",
            "\"format\":\"turboism.preview.report\",\"format\":\"turboism.preview.report\","
        );
        assertCode("MALFORMED_JSON", duplicate);
    }

    @Test
    void rejectsBomFloatVersionBadTimestampAndTruncationMismatch() {
        final byte[] normal = bytes(previewRuntime());
        final byte[] bom = new byte[normal.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(normal, 0, bom, 3, normal.length);
        assertEquals(
            "UTF8_BOM",
            assertThrows(
                PreviewReportValidationException.class,
                () -> PreviewReportValidator.validate(bom)
            ).code()
        );

        assertCode("BAD_SCHEMA_VERSION", previewRuntime().replace(
            "\"schemaVersion\":1",
            "\"schemaVersion\":1.0"
        ));
        assertCode("BAD_TIMESTAMP", previewRuntime().replace(
            "2026-07-15T00:00:00Z",
            "2026-07-15T08:00:00+08:00"
        ));
        assertCode("BAD_TRUNCATION", previewRuntime().replace(
            "\"truncated\":false,\"droppedEntries\":0,\"reason\":\"NONE\"",
            "\"truncated\":false,\"droppedEntries\":1,\"reason\":\"ENTRY_LIMIT\""
        ));
    }

    @Test
    void rejectsPathCountDigestAndMixedRuntimeViolations() {
        assertCode("BAD_PATH", pluginLoad().replace(
            "plugins/project-inspector.jar",
            "C:/Users/private/project-inspector.jar"
        ));
        assertCode("BAD_REGISTRATION_COUNTS", capability().replace(
            "\"total\":1",
            "\"total\":2"
        ));
        assertCode("BAD_DIGEST", capability().replace(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        ));

        final Map<PreviewReportType, byte[]> reports = new EnumMap<>(PreviewReportType.class);
        reports.put(PreviewReportType.PREVIEW_RUNTIME, bytes(previewRuntime()));
        reports.put(PreviewReportType.PLUGIN_LOAD, bytes(pluginLoad().replace(
            RUNTIME_ID,
            "runtime-other"
        )));
        reports.put(PreviewReportType.CAPABILITY, bytes(capability()));
        reports.put(PreviewReportType.I18N, bytes(i18n()));
        assertEquals(
            "MIXED_RUNTIME_ID",
            assertThrows(
                PreviewReportValidationException.class,
                () -> PreviewReportValidator.validateSet(reports)
            ).code()
        );
    }

    @Test
    void acceptsStartupLocaleSourceAndKeepsSourceSetClosed() {
        // createResolved (PreviewPluginServicesFactory path) emits localeSource=STARTUP.
        final String startup = i18n().replace(
            "\"localeSource\":\"JVM_DISPLAY_DEFAULT\"",
            "\"localeSource\":\"STARTUP\""
        );
        final Map<PreviewReportType, byte[]> reports = new EnumMap<>(PreviewReportType.class);
        reports.put(PreviewReportType.PREVIEW_RUNTIME, bytes(previewRuntime()));
        reports.put(PreviewReportType.PLUGIN_LOAD, bytes(pluginLoad()));
        reports.put(PreviewReportType.CAPABILITY, bytes(capability()));
        reports.put(PreviewReportType.I18N, bytes(startup));
        assertEquals(4, PreviewReportValidator.validateSet(reports).size());

        assertCode("BAD_I18N_ENTRY", startup.replace(
            "\"localeSource\":\"STARTUP\"",
            "\"localeSource\":\"UNKNOWN_SOURCE\""
        ));
    }

    @Test
    void rejectsWrongDiscriminatorPayloadAndDomainAlgebra() {
        assertCode("REPORT_TYPE_MISMATCH", pluginLoad().replace(
            "\"reportType\":\"PLUGIN_LOAD\"",
            "\"reportType\":\"CAPABILITY\""
        ));
        assertCode("BAD_SHUTDOWN_COUNTS", previewRuntime().replace(
            "\"attempted\":0,\"succeeded\":0,\"failed\":0,\"timedOut\":0",
            "\"attempted\":1,\"succeeded\":0,\"failed\":0,\"timedOut\":0"
        ));
        assertCode("BAD_I18N_SUPPRESSION", i18n().replace(
            "\"missingWarningsEmitted\":0",
            "\"missingWarningsEmitted\":-1"
        ));
    }

    private static void assertCode(final String code, final String json) {
        assertEquals(
            code,
            assertThrows(
                PreviewReportValidationException.class,
                () -> PreviewReportValidator.validate(bytes(json))
            ).code()
        );
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String previewRuntime() {
        return envelope(
            "PREVIEW_RUNTIME",
            """
            {"host":{"product":"Live2D Cubism","version":"UNKNOWN","identityState":"UNKNOWN"},
             "adapterState":"UNAVAILABLE","runtimeState":"RUNNING",
             "taskFailures":[],"storageFailures":[],"configFailures":[],"eventFailures":[],
             "shutdownCounts":{"attempted":0,"succeeded":0,"failed":0,"timedOut":0},
             "cleanupCounts":{"taskHandlesCanceled":0,"taskCompletionsSettled":0,
               "pluginContinuationsDrained":0,"userFileHandlesRevoked":0,
               "configSchemasUnregistered":0,"temporaryFilesDeleted":0,"scopesClosed":0,
               "classloadersClosed":0,"failures":0}}
            """
        );
    }

    private static String pluginLoad() {
        return envelope(
            "PLUGIN_LOAD",
            """
            {"plugins":[{"pluginId":"dev.turboism.plugin.project-inspector",
              "artifactRelativePath":"plugins/project-inspector.jar",
              "artifactSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "discoveryState":"DISCOVERED","dependencyState":"RESOLVED",
              "lifecycleState":"ENABLED","badNeighbor":false,
              "disableState":"NOT_STARTED","shutdownState":"NOT_STARTED",
              "unloadState":"NOT_STARTED","scopeCleanupState":"NOT_STARTED",
              "classloaderCleanupState":"NOT_STARTED",
              "registrationsBeforeCleanup":{"actions":0,"events":0,"menus":0,"toolbars":0,
                "contextMenus":0,"overlays":0,"dialogs":0,"panels":0,"status":0,"tasks":0,
                "configSchemas":0,"userFileHandles":0,"total":0},
              "registrationsAfterCleanup":{"actions":0,"events":0,"menus":0,"toolbars":0,
                "contextMenus":0,"overlays":0,"dialogs":0,"panels":0,"status":0,"tasks":0,
                "configSchemas":0,"userFileHandles":0,"total":0},"failures":[]}]}
            """
        );
    }

    private static String capability() {
        return envelope(
            "CAPABILITY",
            """
            {"capabilities":[{"pluginId":"dev.turboism.plugin.project-inspector",
              "capabilityId":"turboism.cubism.project.read","operationId":"cubism.project.read",
              "permissionId":"turboism.cubism.project.read","capabilityAvailability":"UNAVAILABLE",
              "permissionAvailability":"GRANTED","registrationState":"NONE",
              "registrationCounts":{"actions":0,"events":0,"menus":0,"toolbars":0,
                "contextMenus":0,"overlays":0,"dialogs":0,"panels":0,"status":0,"tasks":1,
                "configSchemas":0,"userFileHandles":0,"total":1},
              "evidence":[{"kind":"STATIC_VERIFIED","state":"UNAVAILABLE",
                "summary":"Exact-version static record is present but runtime host is unavailable.",
                "relativeRecordPath":"state/verification/cubism-5.3.02-project-workspace.json",
                "digestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],
              "failures":[]}]}
            """
        );
    }

    private static String i18n() {
        return envelope(
            "I18N",
            """
            {"plugins":[{"pluginId":"dev.turboism.plugin.project-inspector",
              "localeSource":"JVM_DISPLAY_DEFAULT","requestedLocale":"en-US",
              "normalizedLocale":"en-US","fallbackChain":["en","base","marker"],
              "catalogs":[{"locale":"base","state":"AVAILABLE","keyCount":5}],
              "missingKeys":[],"malformedPatterns":[],
              "suppression":{"missingWarningsEmitted":0,"missingWarningsSuppressed":0,
                "malformedWarningsEmitted":0,"malformedWarningsSuppressed":0}}]}
            """
        );
    }

    private static String envelope(final String type, final String payload) {
        return "{" +
            "\"format\":\"turboism.preview.report\"," +
            "\"schemaVersion\":1," +
            "\"reportType\":\"" + type + "\"," +
            "\"runtimeId\":\"" + RUNTIME_ID + "\"," +
            "\"createdAt\":\"2026-07-15T00:00:00Z\"," +
            "\"truncation\":{\"truncated\":false,\"droppedEntries\":0,\"reason\":\"NONE\"}," +
            "\"payload\":" + payload.replaceAll("\\s+", "") +
            "}";
    }
}
