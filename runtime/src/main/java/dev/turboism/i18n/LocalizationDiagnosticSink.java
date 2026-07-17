package dev.turboism.i18n;

@FunctionalInterface
public interface LocalizationDiagnosticSink {
    void record(LocalizationDiagnostic diagnostic);
}
