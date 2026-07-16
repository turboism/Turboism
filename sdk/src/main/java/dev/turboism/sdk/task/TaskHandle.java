package dev.turboism.sdk.task;

import java.util.concurrent.CompletionStage;

public interface TaskHandle extends AutoCloseable {

    TaskId id();

    TaskProgress progress();

    boolean cancel();

    CompletionStage<TaskOutcome> completion();

    @Override
    void close();
}
