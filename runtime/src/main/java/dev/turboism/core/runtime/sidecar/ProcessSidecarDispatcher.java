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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs plugin work in a separate JVM process, so a task that hangs or crashes
 * cannot take the Cubism host down with it.
 *
 * <p>Each dispatch serializes a {@link SidecarEnvelope} to JSON, has it screened
 * by {@link SidecarEnvelopeValidator}, then launches the configured java binary
 * and feeds the envelope to its stdin. The process is forcibly destroyed once the
 * configured timeout elapses. Launches are serialized onto a single daemon
 * dispatch thread, so calls never run on the host thread and never overlap.</p>
 *
 * <p>Failures are reported as {@link SidecarResult} error or timeout values rather
 * than thrown; the only exceptional completion is a {@link SidecarDispatchException}
 * when the dispatcher is configured as disabled.</p>
 */
public final class ProcessSidecarDispatcher implements SidecarDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Executor DEFAULT_DISPATCH_EXECUTOR = Executors.newSingleThreadExecutor(
        new SidecarDispatchThreadFactory()
    );

    private final SidecarDispatcherConfiguration configuration;
    private final ProcessLauncher launcher;
    private final Executor dispatchExecutor;

    public ProcessSidecarDispatcher(final SidecarDispatcherConfiguration configuration) {
        this(configuration, new ProcessBuilderLauncher(), DEFAULT_DISPATCH_EXECUTOR);
    }

    ProcessSidecarDispatcher(
        final SidecarDispatcherConfiguration configuration,
        final ProcessLauncher launcher
    ) {
        this(configuration, launcher, DEFAULT_DISPATCH_EXECUTOR);
    }

    ProcessSidecarDispatcher(
        final SidecarDispatcherConfiguration configuration,
        final ProcessLauncher launcher,
        final Executor dispatchExecutor
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.dispatchExecutor = Objects.requireNonNull(dispatchExecutor, "dispatchExecutor");
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

        return CompletableFuture.supplyAsync(() -> run(command, callback), dispatchExecutor);
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

    /**
     * What one sidecar process run produced.
     *
     * @param exitCode the process exit status, or {@code -1} when it was destroyed
     *                 after the timeout
     * @param stdout   whatever the process wrote to standard output; {@code null} is
     *                 normalized to the empty string
     * @param stderr   whatever the process wrote to standard error; {@code null} is
     *                 normalized to the empty string
     * @param timedOut {@code true} when the process outlived the configured timeout
     *                 and was forcibly destroyed
     */
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

    private static final class SidecarDispatchThreadFactory implements ThreadFactory {

        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(final Runnable runnable) {
            Thread thread = new Thread(runnable, "turboism-sidecar-dispatch-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
