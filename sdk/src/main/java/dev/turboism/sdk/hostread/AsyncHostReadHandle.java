package dev.turboism.sdk.hostread;

import java.util.concurrent.CompletionStage;

public interface AsyncHostReadHandle extends AutoCloseable {

    AsyncHostReadIntent intent();

    AsyncHostReadStatus status();

    boolean cancel();

    CompletionStage<AsyncHostReadResult> completion();

    @Override
    void close();
}
