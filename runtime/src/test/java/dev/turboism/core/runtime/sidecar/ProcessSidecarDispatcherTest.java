package dev.turboism.core.runtime.sidecar;

import dev.turboism.core.runtime.PluginTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessSidecarDispatcherTest {

    @Test
    void disabledDispatcherFailsFastWithoutLaunchingProcess() {
        // Given
        ProcessSidecarDispatcher dispatcher = new ProcessSidecarDispatcher(new SidecarDispatcherConfiguration(
            false,
            "/path/to/java-that-must-not-launch",
            List.of("runtime.jar"),
            "dev.turboism.sidecar.Main",
            1_000L
        ));
        AtomicInteger callbacks = new AtomicInteger();

        // When
        CompletionException exception = assertThrows(CompletionException.class, () -> {
            dispatcher.dispatch(task(), callbacks::incrementAndGet)
                .toCompletableFuture()
                .join();
        });

        // Then
        assertEquals(0, callbacks.get());
        assertEquals("SIDECAR_DISABLED", ((SidecarDispatchException) exception.getCause()).diagnosticCode());
    }

    @Test
    void failingExecutableReturnsErrorAndDoesNotRunCallback() {
        // Given
        ProcessSidecarDispatcher dispatcher = new ProcessSidecarDispatcher(new SidecarDispatcherConfiguration(
            true,
            "/path/to/java-that-does-not-exist",
            List.of("runtime.jar"),
            "dev.turboism.sidecar.Main",
            1_000L
        ));
        AtomicInteger callbacks = new AtomicInteger();

        // When
        SidecarResult result = dispatcher.dispatch(task(), callbacks::incrementAndGet)
            .toCompletableFuture()
            .join();

        // Then
        assertEquals(SidecarResult.Kind.ERROR, result.kind());
        assertEquals("SIDECAR_LAUNCH_FAILED", result.errorCode());
        assertEquals(0, callbacks.get());
    }

    @Test
    void callbackRunsOnlyOnSuccessfulResult() {
        // Given
        ProcessSidecarDispatcher dispatcher = new ProcessSidecarDispatcher(
            new SidecarDispatcherConfiguration(true, "/unused/java", List.of("runtime.jar"), "sidecar.Main", 1_000L),
            command -> new ProcessSidecarDispatcher.LaunchResult(0, "{\"ok\":true}", "")
        );
        AtomicInteger callbacks = new AtomicInteger();

        // When
        SidecarResult result = dispatcher.dispatch(task(), callbacks::incrementAndGet)
            .toCompletableFuture()
            .orTimeout(1, TimeUnit.SECONDS)
            .join();

        // Then
        assertEquals(SidecarResult.Kind.SUCCESS, result.kind());
        assertEquals("{\"ok\":true}", result.payload());
        assertEquals(1, callbacks.get());
    }

    private static PluginTask task() {
        return new PluginTask(
            SidecarWorkAction.EXECUTE.name(),
            "dev.turboism.plugin.demo",
            "{\"message\":\"hello\"}",
            "sidecar"
        );
    }
}
