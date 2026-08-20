package dev.turboism.preview.report;

/**
 * The closed set of documents a preview run emits, each bound to a fixed file name inside the
 * preview state directory.
 *
 * <p>A complete run writes every constant: validation of a report set treats a missing file as a
 * failure rather than as an empty report.
 */
public enum PreviewReportType {
    PREVIEW_RUNTIME("preview-runtime-report.json"),
    PLUGIN_LOAD("plugin-load-report.json"),
    CAPABILITY("capability-report.json"),
    I18N("i18n-report.json");

    private final String fileName;

    PreviewReportType(final String fileName) {
        this.fileName = fileName;
    }

    /**
     * @return the fixed file name this report is written to and read back from, relative to the
     *     preview state directory; part of the on-disk contract and not configurable
     */
    public String fileName() {
        return fileName;
    }
}
