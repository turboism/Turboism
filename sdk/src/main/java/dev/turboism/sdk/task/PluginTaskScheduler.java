package dev.turboism.sdk.task;

public interface PluginTaskScheduler {

    TaskSubmission submit(PluginTaskRequest request);

    TaskSubmission scheduleWithFixedDelay(FixedDelayTaskRequest request);
}
