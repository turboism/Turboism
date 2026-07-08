package dev.turboism.sdk.plugin;

public interface CancellationToken {

    boolean isCancellationRequested();

    void checkCanceled() throws TaskCanceledException;
}
