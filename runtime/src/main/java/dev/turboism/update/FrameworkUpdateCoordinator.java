package dev.turboism.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Persisted-state-free coordinator that owns the truthful lifecycle of one framework update in this process:
 *
 * <pre>
 * IDLE → DOWNLOADING → STAGED → APPLY_PENDING (restart scheduled) → [process exits; helper applies]
 *            ↘ CANCELLED / FAILED
 * </pre>
 *
 * <p>It never replaces installed files and never terminates Cubism. "Downloaded" is not "installed": the state only
 * reaches {@link State#STAGED} after every byte was verified, and {@link State#APPLY_PENDING} only after the helper
 * process has actually been started. Everything that can block runs on the injected executor, never on the caller
 * (which in production is the Cubism UI thread).
 *
 * <p>The helper is started <em>detached with the exact pid to wait for</em>. The helper itself refuses to touch a
 * file while that pid is alive, so a user who keeps working simply keeps the old version until they quit Cubism.
 * Unsaved work is therefore never at risk: nothing here closes anything.
 */
public final class FrameworkUpdateCoordinator implements AutoCloseable {
    public enum State {
        IDLE,
        DOWNLOADING,
        STAGED,
        APPLY_PENDING,
        CANCELLED,
        FAILED
    }

    /** Immutable view for UI code. */
    public record Status(
            State state,
            String version,
            long bytesToDownload,
            long bytesVerified,
            long bytesTotal,
            Optional<String> message) {
        public Status {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(message, "message");
        }
    }

    /** Starts the helper. Abstracted so tests and platforms can substitute launching. */
    @FunctionalInterface
    interface HelperLauncher {
        /** @return the launched process; it must be detached from this JVM's lifetime. */
        Process launch(List<String> command) throws IOException;
    }

    private final Path root;
    private final Path stagesRoot;
    private final Path workDir;
    private final Path helperClasspath;
    private final Path javaExecutable;
    private final ExecutorService io;
    private final HelperLauncher launcher;
    private final Supplier<Long> ownPid;
    private final CopyOnWriteArrayList<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private Status status = new Status(State.IDLE, "", 0, 0, 0, Optional.empty());
    private CompletableFuture<?> inFlight;
    /** The downloader's own future: cancelling THIS stops the transfer; {@link #inFlight} then completes normally. */
    private CompletableFuture<?> transfer;
    private FrameworkUpdateDownloader.Downloaded staged;
    private Path stageDirectory;
    private boolean closed;

    FrameworkUpdateCoordinator(
            final Path root,
            final Path stagesRoot,
            final Path workDir,
            final Path helperClasspath,
            final Path javaExecutable,
            final ExecutorService io,
            final HelperLauncher launcher,
            final Supplier<Long> ownPid) {
        this.root = root.toAbsolutePath().normalize();
        this.stagesRoot = stagesRoot.toAbsolutePath().normalize();
        this.workDir = workDir.toAbsolutePath().normalize();
        this.helperClasspath = helperClasspath.toAbsolutePath().normalize();
        this.javaExecutable = javaExecutable.toAbsolutePath().normalize();
        this.io = Objects.requireNonNull(io, "io");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.ownPid = Objects.requireNonNull(ownPid, "ownPid");
        if (this.stagesRoot.startsWith(this.root) || this.root.startsWith(this.stagesRoot)) {
            throw new IllegalArgumentException("Stage area must be separate from the installation");
        }
    }

    /** Default process launcher: detached on Windows via the JVM's inherited-less ProcessBuilder. */
    static HelperLauncher defaultLauncher() {
        return command -> new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start();
    }

    public Status status() {
        synchronized (lock) {
            return status;
        }
    }

    public Runnable subscribe(final Consumer<Status> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        listener.accept(status());
        return () -> listeners.remove(listener);
    }

    /**
     * Begin downloading in the background. Joins an in-flight download for the same call rather than starting a
     * second one. Returns immediately.
     */
    CompletableFuture<Status> download(
            final FrameworkUpdateDownloader downloader,
            final UpdateDiscovery.Candidate candidate,
            final UpdateVerificationContext context) {
        synchronized (lock) {
            if (closed) return CompletableFuture.completedFuture(status);
            if (status.state() == State.DOWNLOADING && inFlight != null) {
                @SuppressWarnings("unchecked")
                final var joined = (CompletableFuture<Status>) inFlight;
                return joined;
            }
            if (status.state() == State.APPLY_PENDING) return CompletableFuture.completedFuture(status);
            final String version = candidate.incremental().orElseThrow().version();
            publish(new Status(State.DOWNLOADING, version, 0, 0, 0, Optional.empty()));
            final var download = downloader.stage(candidate, context, root, progress -> {
                synchronized (lock) {
                    if (status.state() == State.DOWNLOADING) {
                        publish(new Status(
                                State.DOWNLOADING,
                                version,
                                0,
                                progress.taskVerified(),
                                progress.taskTotal(),
                                Optional.empty()));
                    }
                }
            });
            transfer = download;
            inFlight = download.handleAsync(
                    (downloaded, failure) -> {
                        synchronized (lock) {
                            if (failure != null) {
                                final boolean cancelled = failure instanceof java.util.concurrent.CancellationException
                                        || failure.getCause() instanceof java.util.concurrent.CancellationException;
                                publish(new Status(
                                        cancelled ? State.CANCELLED : State.FAILED,
                                        version,
                                        0,
                                        0,
                                        0,
                                        Optional.of(cancelled ? "Download cancelled" : describe(failure))));
                            } else {
                                staged = downloaded;
                                publish(new Status(
                                        State.STAGED,
                                        version,
                                        downloaded.plan().bytesToDownload(),
                                        downloaded.plan().bytesToDownload(),
                                        downloaded.plan().bytesToDownload(),
                                        Optional.empty()));
                            }
                            return status;
                        }
                    },
                    io);
            @SuppressWarnings("unchecked")
            final var result = (CompletableFuture<Status>) inFlight;
            return result;
        }
    }

    /** Cancel an in-flight download. The installation is never touched. Idempotent. */
    public void cancel() {
        final CompletableFuture<?> toCancel;
        synchronized (lock) {
            // Cancel the transfer, never the future handed to callers: they must observe the CANCELLED status
            // as a normal result rather than an exception.
            toCancel = status.state() == State.DOWNLOADING ? transfer : null;
        }
        if (toCancel != null) toCancel.cancel(true);
    }

    /**
     * Schedule the staged update to be applied once <em>this</em> process exits. Does not exit anything: the helper
     * is launched now, waits for our exact pid, and installs only after we are gone.
     *
     * @return the new status; {@link State#FAILED} (with a message) if staging or launching failed
     */
    CompletableFuture<Status> applyOnExit() {
        synchronized (lock) {
            if (closed || status.state() != State.STAGED || staged == null) {
                return CompletableFuture.completedFuture(status);
            }
        }
        return CompletableFuture.supplyAsync(this::scheduleHelper, io);
    }

    private Status scheduleHelper() {
        final FrameworkUpdateDownloader.Downloaded downloaded;
        final String version;
        synchronized (lock) {
            if (status.state() != State.STAGED || staged == null) return status;
            downloaded = staged;
            version = status.version();
        }
        try {
            final Path directory = stagesRoot.resolve("stage-" + System.nanoTime());
            FrameworkUpdateStage.materialize(downloaded, directory);
            final List<String> command = new ArrayList<>();
            command.add(javaExecutable.toString());
            command.add("-cp");
            command.add(helperClasspath.toString());
            command.add(FrameworkUpdateHelper.class.getName());
            command.add("--root");
            command.add(root.toString());
            command.add("--stage");
            command.add(directory.toString());
            command.add("--work");
            command.add(workDir.toString());
            command.add("--wait-pid");
            command.add(Long.toString(ownPid.get()));
            command.add("--wait-seconds");
            command.add("3600");
            final Process helper = launcher.launch(command);
            if (helper == null || !helper.isAlive()) {
                // A helper that died immediately (bad classpath, missing java) must not be reported as scheduled.
                throw new IOException("Update helper did not start");
            }
            synchronized (lock) {
                stageDirectory = directory;
                return publish(new Status(
                        State.APPLY_PENDING, version, status.bytesToDownload(), status.bytesVerified(), status.bytesTotal(), Optional.empty()));
            }
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            synchronized (lock) {
                return publish(new Status(State.FAILED, version, 0, 0, 0, Optional.of(describe(failure))));
            }
        }
    }

    /** Back to IDLE after a terminal state, so a later check can start fresh. Never valid while applying. */
    public Status reset() {
        synchronized (lock) {
            if (status.state() == State.DOWNLOADING || status.state() == State.APPLY_PENDING) return status;
            staged = null;
            return publish(new Status(State.IDLE, "", 0, 0, 0, Optional.empty()));
        }
    }

    Optional<Path> stageDirectory() {
        synchronized (lock) {
            return Optional.ofNullable(stageDirectory);
        }
    }

    private Status publish(final Status next) {
        status = next;
        for (final var listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException ignored) {
                // A faulty listener must never break the update state machine.
            }
        }
        return next;
    }

    private static String describe(final Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
                        || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** True if an interrupted earlier update left a journal that the helper must repair before anything else. */
    boolean needsRecovery() {
        return Files.exists(workDir.resolve(FrameworkUpdateApplier.JOURNAL), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
        cancel();
    }
}
