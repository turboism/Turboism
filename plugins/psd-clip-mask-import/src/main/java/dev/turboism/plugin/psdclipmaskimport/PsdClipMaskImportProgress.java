package dev.turboism.plugin.psdclipmaskimport;

/** Progress surface for one PSD clip-mask import. */
interface PsdClipMaskImportProgress extends AutoCloseable {

    void show();

    void preparing();

    void awaitingConfirmation();

    void applying();

    void focus();

    boolean cancellationRequested();

    @Override
    void close();

    static PsdClipMaskImportProgress noop() {
        return NoopProgress.INSTANCE;
    }

    enum NoopProgress implements PsdClipMaskImportProgress {
        INSTANCE;

        @Override public void show() { }
        @Override public void preparing() { }
        @Override public void awaitingConfirmation() { }
        @Override public void applying() { }
        @Override public void focus() { }
        @Override public boolean cancellationRequested() { return false; }
        @Override public void close() { }
    }
}
