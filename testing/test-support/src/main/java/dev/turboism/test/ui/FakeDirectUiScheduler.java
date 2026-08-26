package dev.turboism.test.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.time.Duration;
import java.util.Objects;

public final class FakeDirectUiScheduler implements UiScheduler {

    @Override
    public Registration runOnUiThread(Runnable work) {
        Objects.requireNonNull(work, "work").run();
        return () -> { };
    }

    @Override
    public Registration runOnUiThreadLater(Runnable work, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(work, "work").run();
        return () -> { };
    }
}
