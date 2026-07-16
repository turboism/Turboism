package dev.turboism.preview.report;

public enum PreviewReportType {
    PREVIEW_RUNTIME("preview-runtime-report.json"),
    PLUGIN_LOAD("plugin-load-report.json"),
    CAPABILITY("capability-report.json"),
    I18N("i18n-report.json");

    private final String fileName;

    PreviewReportType(final String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
