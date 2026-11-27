package dev.turboism.plugin.acp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Read-only discovery, version reporting, and protocol checks for installed harnesses. */
final class AgentAvailability {
    record Detection(Path executable, boolean available, String version, String reason) {}

    private record Check(long checkedAt, boolean available, String version, String reason) {}

    /** Detection reported for a profile whose executable candidates are all unusable. */
    static final Detection NOT_INSTALLED = new Detection(null, false, "", "executable-not-found");

    private static final Map<Path, Check> CACHE = new ConcurrentHashMap<>();
    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("\\b\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?\\b");
    /**
     * Probe results stay reusable across controller actions. The cache lifetime exceeds one
     * controller lifetime so reopening the window cannot re-spawn two processes per installed
     * CLI; only an explicit user Refresh drops the entries.
     */
    private static final long CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    /** Every harness CLI answers a version or help query in seconds; a silent one is not usable. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    private static final long PROBE_TERMINATE_GRACE_MILLIS = 100L;
    private static final int PROBE_OUTPUT_LIMIT_BYTES = 128 * 1024;
    private static final int PROBE_READ_BUFFER_BYTES = 8 * 1024;
    /** Bounded fan-out: each worker blocks on external CLI processes, not on host CPU. */
    private static final int DISCOVERY_WORKERS = 4;

    private static final int PROBE_STREAM_WORKERS = 2;
    private static final int PROBE_STREAM_QUEUE_CAPACITY = 2;

    private static final String DISCOVERY_THREAD_PREFIX = "turboism-acp-discovery-probe-";
    private static final String PROBE_STREAM_THREAD_PREFIX = "turboism-acp-probe-stream-";

    private AgentAvailability() {}

    static void invalidate() {
        CACHE.clear();
    }

    static boolean available(final AgentProfile profile, final Path executable) {
        return checked(profile, executable).available();
    }

    static String version(final Path executable) {
        final Check check = CACHE.get(executable);
        return check == null ? "" : check.version();
    }

    private static Check checked(final AgentProfile profile, final Path executable) {
        final Check cached = CACHE.get(executable);
        if (cached != null && System.nanoTime() - cached.checkedAt() < CACHE_TTL_NANOS) return cached;
        final Check result = check(profile, executable);
        CACHE.put(executable, result);
        return result;
    }

    /**
     * Probes the given profiles concurrently and returns detections in the requested order.
     *
     * <p>The ordered result keeps the caller's rendering stable regardless of which probe finishes
     * first. A single failed probe reports an unavailable detection instead of aborting the sweep,
     * because one broken CLI must not hide the harnesses that do work.</p>
     */
    static Map<String, Detection> discoverAll(final List<AgentProfile> profiles, final Consumer<String> diagnostics) {
        final List<AgentProfile> requested = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (requested.isEmpty()) return new LinkedHashMap<>();
        final Map<String, Detection> findings = new ConcurrentHashMap<>();
        final ExecutorService probes =
                Executors.newFixedThreadPool(Math.min(DISCOVERY_WORKERS, requested.size()), daemonProbeThreads());
        try {
            final List<Future<?>> pending = new ArrayList<>(requested.size());
            for (AgentProfile profile : requested) {
                pending.add(probes.submit(() -> {
                    findings.put(profile.id(), probeIsolated(profile, diagnostics));
                }));
            }
            awaitProbes(pending, diagnostics);
        } finally {
            probes.shutdownNow();
        }
        final Map<String, Detection> ordered = new LinkedHashMap<>();
        for (AgentProfile profile : requested) {
            ordered.putIfAbsent(profile.id(), findings.getOrDefault(profile.id(), NOT_INSTALLED));
        }
        return ordered;
    }

    private static ThreadFactory daemonProbeThreads() {
        return daemonThreads(DISCOVERY_THREAD_PREFIX);
    }

    private static ThreadFactory daemonThreads(final String prefix) {
        final AtomicInteger threadNumber = new AtomicInteger(1);
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void awaitProbes(final List<Future<?>> pending, final Consumer<String> diagnostics) {
        for (Future<?> probe : pending) {
            try {
                probe.get();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                pending.forEach(task -> task.cancel(true));
                return;
            } catch (ExecutionException failure) {
                diagnostics.accept("harness detection task failed: " + failure.getCause());
            }
        }
    }

    /** Confines a profile failure to its own detection so the remaining probes still publish. */
    private static Detection probeIsolated(final AgentProfile profile, final Consumer<String> diagnostics) {
        try {
            return discover(profile, diagnostics);
        } catch (RuntimeException failure) {
            return new Detection(
                    null, false, "", "probe-failed-" + failure.getClass().getSimpleName());
        }
    }

    static java.util.Optional<Path> locate(final AgentProfile profile) {
        return locate(profile, ignored -> {});
    }

    static java.util.Optional<Path> locate(
            final AgentProfile profile, final java.util.function.Consumer<String> diagnostics) {
        final Detection found = discover(profile, diagnostics);
        return found.available() ? java.util.Optional.of(found.executable()) : java.util.Optional.empty();
    }

    static Detection discover(final AgentProfile profile, final java.util.function.Consumer<String> diagnostics) {
        final List<Path> candidates = new ArrayList<>();
        for (String name : profile.executableCandidates()) candidates.addAll(AgentLocator.findExecutables(name));
        return discover(profile, candidates, diagnostics);
    }

    static java.util.Optional<Path> locate(
            final AgentProfile profile,
            final List<Path> candidates,
            final java.util.function.Consumer<String> diagnostics) {
        final Detection found = discover(profile, candidates, diagnostics);
        return found.available() ? java.util.Optional.of(found.executable()) : java.util.Optional.empty();
    }

    static Detection discover(
            final AgentProfile profile,
            final List<Path> candidates,
            final java.util.function.Consumer<String> diagnostics) {
        Detection firstFailure = null;
        for (Path candidate : candidates) {
            final Check check = checked(profile, candidate);
            diagnostics.accept(profile.id() + " path=" + candidate + " version="
                    + (check.version().isEmpty() ? "unknown" : check.version()) + " status=" + check.reason());
            final Detection detection = new Detection(candidate, check.available(), check.version(), check.reason());
            if (check.available()) return detection;
            if (firstFailure == null) firstFailure = detection;
        }
        if (candidates.isEmpty()) diagnostics.accept(profile.id() + " status=executable-not-found");
        return firstFailure == null ? NOT_INSTALLED : firstFailure;
    }

    private static Check result(final boolean available, final String version, final String reason) {
        return new Check(System.nanoTime(), available, version, reason);
    }

    private static Check check(final AgentProfile profile, final Path executable) {
        String version = "";
        try {
            final var match = VERSION.matcher(probe(List.of(executable.toString(), "--version")));
            if (match.find()) version = match.group();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return result(false, version, "interrupted");
        } catch (Exception ignored) {
            // Some ACP adapters only provide initialize, not --version.
        }
        final AgentProtocol protocol = AgentProtocol.forExecutable(profile, executable);
        if (protocol == AgentProtocol.ACP) {
            try (AcpClient ignored = AcpClient.start(
                    new AgentLaunchSpec(command(profile, executable), Path.of(System.getProperty("user.home"))),
                    new AcpListener() {})) {
                return result(true, version, "available");
            } catch (IOException | AcpException | RuntimeException failure) {
                return result(
                        false,
                        version,
                        "initialize-failed-" + failure.getClass().getSimpleName());
            }
        }
        final var arguments = new ArrayList<String>();
        arguments.add(executable.toString());
        if (protocol == AgentProtocol.CODEX) arguments.add("app-server");
        arguments.add("--help");
        try {
            final String help = probe(arguments);
            final boolean supported =
                    switch (protocol) {
                        case CODEX -> help.contains("app-server");
                        case CLAUDE -> help.contains("stream-json") && help.contains("--input-format");
                        case PI -> help.contains("rpc") && help.contains("--extension");
                        default -> false;
                    };
            return result(supported, version, supported ? "available" : "native-mode-not-advertised");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return result(false, version, "interrupted");
        } catch (Exception failure) {
            return result(false, version, "probe-failed-" + failure.getClass().getSimpleName());
        }
    }

    /** Help/version output may be written to either stream by native CLIs and Windows shims. */
    private static String probe(final List<String> command) throws Exception {
        final ExecutorService streamReaders = new ThreadPoolExecutor(
                PROBE_STREAM_WORKERS,
                PROBE_STREAM_WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PROBE_STREAM_QUEUE_CAPACITY),
                daemonThreads(PROBE_STREAM_THREAD_PREFIX));
        try (AcpProcessTransport process =
                AcpProcessTransport.start(new AgentLaunchSpec(command, Path.of(System.getProperty("user.home"))))) {
            final var stdout = read(process.stdout(), streamReaders);
            final var stderr = read(process.stderr(), streamReaders);
            try {
                final long deadline = System.nanoTime() + PROBE_TIMEOUT.toNanos();
                java.util.concurrent.CompletableFuture.allOf(stdout, stderr)
                        .get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (process.awaitExit(deadline - System.nanoTime()) != 0) {
                    throw new IOException("Harness probe exited unsuccessfully");
                }
                return stdout.join() + "\n" + stderr.join();
            } finally {
                process.terminate(Duration.ofMillis(PROBE_TERMINATE_GRACE_MILLIS));
            }
        } finally {
            streamReaders.shutdownNow();
        }
    }

    private static java.util.concurrent.CompletableFuture<String> read(
            final java.io.InputStream input, final Executor executor) {
        return java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return readProbeOutput(input);
                    } catch (IOException failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                },
                executor);
    }

    /** Windows JRE 17 FileInputStream.readNBytes tries to seek on non-seekable process pipes. */
    static String readProbeOutput(final java.io.InputStream input) throws IOException {
        final var captured = new java.io.ByteArrayOutputStream();
        final byte[] buffer = new byte[PROBE_READ_BUFFER_BYTES];
        int count;
        while ((count = input.read(buffer)) != -1) {
            final int keep = Math.min(count, PROBE_OUTPUT_LIMIT_BYTES - captured.size());
            if (keep > 0) captured.write(buffer, 0, keep);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static List<String> command(final AgentProfile profile, final Path executable) {
        final var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(profile.argumentsFor(executable));
        return command;
    }
}
