package dev.turboism.core.runtime.sidecar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.runtime.PluginTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class ProcessSidecarDispatcher implements SidecarDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SidecarDispatcherConfiguration configuration;
    private final ProcessLauncher launcher;

    public ProcessSidecarDispatcher(final SidecarDispatcherConfiguration configuration) {
        this(configuration, new ProcessBuilderLauncher());
    }

    ProcessSidecarDispatcher(
        final SidecarDispatcherConfiguration configuration,
        final ProcessLauncher launcher
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override
    public CompletionStage<SidecarResult> dispatch(final PluginTask task, final Runnable callback) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(callback, "callback");
        if (!configuration.enabled()) {
            return CompletableFuture.failedFuture(new SidecarDispatchException(
                "SIDECAR_DISABLED",
                "Sidecar dispatcher is disabled"
            ));
        }

        final SidecarEnvelope envelope = createEnvelope(task);
        final SidecarEnvelopeValidator.ValidationResult validation = new SidecarEnvelopeValidator().validate(envelope);
        if (!validation.valid()) {
            return CompletableFuture.completedFuture(SidecarResult.error(
                validation.problemCode(),
                validation.problemMessage()
            ));
        }

        final SidecarCommand command;
        try {
            command = new SidecarCommand(commandLine(), serialize(envelope), configuration.timeoutMillis());
        } catch (final JsonProcessingException exception) {
            return CompletableFuture.completedFuture(SidecarResult.error(
                "SIDECAR_ENVELOPE_SERIALIZATION_FAILED",
                exception.getOriginalMessage()
            ));
        }

        return CompletableFuture.supplyAsync(() -> run(command, callback));
    }

    private SidecarResult run(final SidecarCommand command, final Runnable callback) {
        final LaunchResult launched;
        try {
            launched = launcher.launch(command);
        } catch (final IOException exception) {
            return SidecarResult.error("SIDECAR_LAUNCH_FAILED", exception.getMessage());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SidecarResult.error("SIDECAR_INTERRUPTED", exception.getMessage());
        }

        if (launched.timedOut()) {
            return SidecarResult.timeout(launched.stderr());
        }
        if (launched.exitCode() != 0) {
            return SidecarResult.error("SIDECAR_EXIT_FAILED", launched.stderr());
        }

        callback.run();
        return SidecarResult.success(launched.stdout());
    }

    private List<String> commandLine() {
        final List<String> command = new ArrayList<>();
        command.add(configuration.javaBinary());
        if (!configuration.classpath().isEmpty()) {
            command.add("-cp");
            command.add(String.join(File.pathSeparator, configuration.classpath()));
        }
        command.add(configuration.mainClass());
        return List.copyOf(command);
    }

    private static SidecarEnvelope createEnvelope(final PluginTask task) {
        return new SidecarEnvelope(
            task.pluginId(),
            UUID.randomUUID().toString(),
            task.taskType(),
            task.payloadDescription(),
            task.declaredCapability(),
            Instant.now().toString()
        );
    }

    private static String serialize(final SidecarEnvelope envelope) throws JsonProcessingException {
        return MAPPER.writeValueAsString(envelope);
    }

    @FunctionalInterface
    interface ProcessLauncher {

        LaunchResult launch(SidecarCommand command) throws IOException, InterruptedException;
    }

    record SidecarCommand(List<String> commandLine, String envelopeJson, long timeoutMillis) {

        SidecarCommand {
            commandLine = List.copyOf(commandLine);
        }
    }

    public record LaunchResult(int exitCode, String stdout, String stderr, boolean timedOut) {

        public LaunchResult(final int exitCode, final String stdout, final String stderr) {
            this(exitCode, stdout, stderr, false);
        }

        public LaunchResult {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }

    private static final class ProcessBuilderLauncher implements ProcessLauncher {

        @Override
        public LaunchResult launch(final SidecarCommand command) throws IOException, InterruptedException {
            final Process process = new ProcessBuilder(command.commandLine()).start();
            process.getOutputStream().write(command.envelopeJson().getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            final boolean completed = process.waitFor(command.timeoutMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new LaunchResult(-1, read(process.getInputStream()), read(process.getErrorStream()), true);
            }
            return new LaunchResult(process.exitValue(), read(process.getInputStream()), read(process.getErrorStream()));
        }

        private static String read(final java.io.InputStream stream) throws IOException {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            stream.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
