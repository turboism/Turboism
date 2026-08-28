package dev.turboism.plugin.turboismwithfx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Native process transport for a verified managed or explicitly selected fx executable. */
final class FxProcessTransport implements FxAcpTransport {

    private final Process process;

    private FxProcessTransport(final Process process) {
        this.process = Objects.requireNonNull(process, "process");
    }

    /**
     * Starts {@code fx acp} directly without a shell. Stock fx is refused unless the caller has
     * explicitly selected compatibility mode because its native tools cannot currently be disabled
     * through a supported CLI or ACP option.
     */
    static FxProcessTransport start(final FxLaunchConfiguration configuration) throws IOException {
        final FxLaunchConfiguration launch = Objects.requireNonNull(configuration, "configuration");
        if (!launch.permitsStockFx()) {
            throw new IllegalStateException(
                "MCP-only mode is unavailable with stock fx because native tools cannot be disabled"
            );
        }
        verifyManagedRuntimeForLaunch(launch);
        final ProcessBuilder builder = new ProcessBuilder(command(launch));
        builder.directory(launch.workingDirectory().toFile());
        builder.redirectErrorStream(false);
        builder.environment().put("NO_COLOR", "1");
        if (validationJavaBridge(launch)) {
            builder.environment().keySet().removeIf(name ->
                "JAVA_TOOL_OPTIONS".equalsIgnoreCase(name)
                    || "_JAVA_OPTIONS".equalsIgnoreCase(name)
                    || "JDK_JAVA_OPTIONS".equalsIgnoreCase(name)
            );
        }
        return new FxProcessTransport(builder.start());
    }

    private static void verifyManagedRuntimeForLaunch(
        final FxLaunchConfiguration launch
    ) throws IOException {
        final FxLaunchConfiguration.ManagedRuntimeIdentity identity = launch.managedRuntime();
        if (identity == null) return;
        final Path executable = Path.of(launch.executable()).toAbsolutePath().normalize();
        Path parent = executable.getParent();
        while (parent != null) {
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("managed fx runtime ancestor is a symbolic link");
            }
            parent = parent.getParent();
        }
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(executable)
            || Files.size(executable) != identity.size()
            || !identity.sha256().equals(FxRuntimeResolver.sha256(executable))) {
            throw new IOException("managed fx runtime changed before launch");
        }
    }

    private static List<String> command(final FxLaunchConfiguration launch) {
        if (!validationJavaBridge(launch)) return launch.command();
        final String classPath = System.getProperty(
            "turboism.fx.validation.bridgeClassPath",
            ""
        );
        final String configuration = System.getProperty(
            "turboism.fx.validation.bridgeConfig",
            ""
        );
        if (classPath.isBlank() || configuration.isBlank()) {
            throw new IllegalStateException(
                "fx validation bridge properties are unavailable"
            );
        }
        return List.of(
            launch.executable(),
            "-Dturboism.fx.validation.bridgeConfig=" + configuration,
            "-cp",
            classPath,
            "acp",
            "acp"
        );
    }

    private static boolean validationJavaBridge(
        final FxLaunchConfiguration launch
    ) {
        return Boolean.getBoolean("turboism.fx.validation.bridge")
            && launch.executable().toLowerCase(Locale.ROOT).endsWith("java.exe");
    }

    @Override
    public InputStream stdout() {
        return process.getInputStream();
    }

    @Override
    public InputStream stderr() {
        return process.getErrorStream();
    }

    @Override
    public OutputStream stdin() {
        return process.getOutputStream();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Terminates descendants before the parent and repeatedly re-enumerates the tree while the
     * parent is alive, closing the window in which a child can fork after the first snapshot. The
     * shutdown is bounded, escalates every retained survivor, and preserves interrupt status.
     */
    @Override
    public void terminate(final Duration grace) {
        final long millis = Math.max(0L, Objects.requireNonNull(grace, "grace").toMillis());
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        final java.util.Map<Long, ProcessHandle> retained = new java.util.LinkedHashMap<>();
        retainDescendants(retained);
        boolean interrupted = false;
        do {
            retainDescendants(retained);
            retained.values().stream()
                .filter(ProcessHandle::isAlive)
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroy);
            process.destroy();
            if (!anyAlive(retained) || millis == 0L) break;
            try {
                process.waitFor(10L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                interrupted = true;
                break;
            }
        } while (System.nanoTime() < deadline);

        retainDescendants(retained);
        final java.util.List<ProcessHandle> survivors = retained.values().stream()
            .filter(ProcessHandle::isAlive)
            .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
            .toList();
        survivors.forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        awaitExit(survivors, Math.max(100L, Math.min(1000L, millis)));
        try {
            process.waitFor(Math.max(100L, Math.min(1000L, millis)), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void retainDescendants(final java.util.Map<Long, ProcessHandle> retained) {
        retainDescendants(process.toHandle(), retained);
        for (ProcessHandle handle : java.util.List.copyOf(retained.values())) {
            retainDescendants(handle, retained);
        }
    }

    private static void retainDescendants(
        final ProcessHandle root,
        final java.util.Map<Long, ProcessHandle> retained
    ) {
        root.descendants().forEach(handle -> retained.putIfAbsent(handle.pid(), handle));
    }

    private boolean anyAlive(final java.util.Map<Long, ProcessHandle> retained) {
        return process.isAlive() || retained.values().stream().anyMatch(ProcessHandle::isAlive);
    }

    private static void awaitExit(
        final java.util.List<ProcessHandle> handles,
        final long millis
    ) {
        if (handles.isEmpty()) return;
        final java.util.concurrent.CompletableFuture<?>[] exits = handles.stream()
            .map(ProcessHandle::onExit)
            .toArray(java.util.concurrent.CompletableFuture[]::new);
        try {
            java.util.concurrent.CompletableFuture.allOf(exits)
                .get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException
            | java.util.concurrent.TimeoutException ignored) {
            // Every survivor has already received a forcible termination request.
        }
    }

    @Override
    public void close() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Process teardown remains the authoritative cleanup path.
        }
        terminate(Duration.ofSeconds(2));
    }
}
