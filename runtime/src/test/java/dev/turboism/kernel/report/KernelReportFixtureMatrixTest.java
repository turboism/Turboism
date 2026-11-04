package dev.turboism.kernel.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KernelReportFixtureMatrixTest {

    private static final String ROOT = "fixtures/schema/preview-report-v1";
    private static final Map<KernelReportType, String> VALID = Map.of(
            KernelReportType.PREVIEW_RUNTIME, "preview-runtime-report.json",
            KernelReportType.PLUGIN_LOAD, "plugin-load-report.json",
            KernelReportType.CAPABILITY, "capability-report.json",
            KernelReportType.I18N, "i18n-report.json");

    @Test
    void acceptsEveryPersistentValidVariantAndTheCompleteSet() throws Exception {
        final EnumMap<KernelReportType, byte[]> reports = new EnumMap<>(KernelReportType.class);
        for (Map.Entry<KernelReportType, String> fixture : VALID.entrySet()) {
            final byte[] bytes = resource("valid/" + fixture.getValue());
            assertEquals(fixture.getKey(), KernelReportValidator.validate(bytes).reportType(), fixture.getValue());
            reports.put(fixture.getKey(), bytes);
        }
        assertEquals(4, KernelReportValidator.validateSet(reports).size());
    }

    @Test
    void rejectsEveryPersistentInvalidFixtureWithStableCode() throws Exception {
        assertInvalid("unknown-envelope-field.json", "UNKNOWN_FIELD");
        assertInvalid("absolute-artifact-path.json", "BAD_PATH");
        assertInvalid("bad-registration-total.json", "BAD_REGISTRATION_COUNTS");
        assertInvalid("discriminator-payload-mismatch.json", "REPORT_TYPE_MISMATCH");
    }

    @Test
    void rejectsPersistentMixedRuntimeSet() throws Exception {
        final EnumMap<KernelReportType, byte[]> reports = new EnumMap<>(KernelReportType.class);
        for (Map.Entry<KernelReportType, String> fixture : VALID.entrySet()) {
            reports.put(fixture.getKey(), resource("valid/" + fixture.getValue()));
        }
        reports.put(KernelReportType.PLUGIN_LOAD, resource("mixed-runtime/plugin-load-report.json"));
        assertEquals(
                "MIXED_RUNTIME_ID",
                org.junit.jupiter.api.Assertions.assertThrows(
                                KernelReportValidationException.class, () -> KernelReportValidator.validateSet(reports))
                        .code());
    }

    private static void assertInvalid(final String name, final String expectedCode) throws Exception {
        assertEquals(
                expectedCode,
                org.junit.jupiter.api.Assertions.assertThrows(
                                KernelReportValidationException.class,
                                () -> KernelReportValidator.validate(resource("invalid/" + name)))
                        .code(),
                name);
    }

    private static byte[] resource(final String relative) throws IOException {
        final String name = ROOT + "/" + relative;
        try (InputStream input =
                KernelReportFixtureMatrixTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing preview report fixture " + name);
            }
            return input.readAllBytes();
        }
    }
}
