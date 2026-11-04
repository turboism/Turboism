package dev.turboism.kernel.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Command-line gate for one complete preview report state directory. */
public final class KernelReportValidationCli {

    private KernelReportValidationCli() {}

    /**
     * Validates a complete preview report set and prints a one-line PASS summary naming the
     * runtime id and the number of reports checked.
     *
     * <p>Fails loudly rather than exiting with a status code: every problem — wrong argument
     * count, a missing report file, an unreadable file, or a contract violation — is thrown. All
     * four reports must exist as regular files before any validation runs, so a partially written
     * state directory is rejected as a set rather than half-accepted.
     *
     * @param arguments exactly one element: the preview state directory, resolved to an absolute
     *     normalized path
     * @throws IllegalArgumentException if the argument count is not exactly one
     * @throws IllegalStateException if a report is missing or cannot be read
     * @throws KernelReportValidationException if a report violates the report contract
     */
    public static void main(final String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: KernelReportValidationCli <preview-state-directory>");
        }
        final Path state = Path.of(arguments[0]).toAbsolutePath().normalize();
        final EnumMap<KernelReportType, byte[]> reports = new EnumMap<>(KernelReportType.class);
        try {
            for (KernelReportType type : KernelReportType.values()) {
                final Path report = state.resolve(type.fileName());
                if (!Files.isRegularFile(report)) {
                    throw new IllegalStateException("Missing preview report: " + type.fileName());
                }
                reports.put(type, Files.readAllBytes(report));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Preview report set could not be read safely.", exception);
        }
        final Map<KernelReportType, KernelReportValidator.ValidatedReport> validated =
                KernelReportValidator.validateSet(reports);
        final String runtimeId =
                validated.get(KernelReportType.PREVIEW_RUNTIME).runtimeId();
        System.out.println("preview report contract: PASS runtimeId=" + runtimeId + " reports=" + validated.size());
    }
}
