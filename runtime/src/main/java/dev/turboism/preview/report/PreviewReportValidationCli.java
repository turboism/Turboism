package dev.turboism.preview.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Command-line gate for one complete preview report state directory. */
public final class PreviewReportValidationCli {

    private PreviewReportValidationCli() {
    }

    public static void main(final String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                "Usage: PreviewReportValidationCli <preview-state-directory>"
            );
        }
        final Path state = Path.of(arguments[0]).toAbsolutePath().normalize();
        final EnumMap<PreviewReportType, byte[]> reports =
            new EnumMap<>(PreviewReportType.class);
        try {
            for (PreviewReportType type : PreviewReportType.values()) {
                final Path report = state.resolve(type.fileName());
                if (!Files.isRegularFile(report)) {
                    throw new IllegalStateException(
                        "Missing preview report: " + type.fileName()
                    );
                }
                reports.put(type, Files.readAllBytes(report));
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Preview report set could not be read safely.",
                exception
            );
        }
        final Map<PreviewReportType, PreviewReportValidator.ValidatedReport> validated =
            PreviewReportValidator.validateSet(reports);
        final String runtimeId = validated.get(PreviewReportType.PREVIEW_RUNTIME).runtimeId();
        System.out.println(
            "preview report contract: PASS runtimeId=" + runtimeId + " reports="
                + validated.size()
        );
    }
}
