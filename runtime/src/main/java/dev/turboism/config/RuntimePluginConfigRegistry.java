package dev.turboism.config;

import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.failure.RuntimeFailure;
import dev.turboism.failure.RuntimeFailureDomain;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Runtime implementation of the plugin-facing config registry, backed by Java properties files
 * under one plugin's own data directory.
 *
 * <p>Every operation is gated twice: the caller must hold the corresponding
 * {@code turboism.config.plugin.*} permission, and must have opened a read or write scope for the
 * path first, so a permission alone does not grant access to arbitrary files. Actual file I/O is
 * never done on the caller's thread — it runs on one bounded registry-owned lane and is awaited for
 * at most one second, so a wedged filesystem degrades to an empty read or a
 * {@link PluginConfigException} rather than blocking the host or requiring a sidecar process.</p>
 *
 * <p>Reads fail soft: rejection, interruption, failure and timeout all return an empty value after
 * emitting a diagnostic. Writes fail loud, throwing {@code PluginConfigException} in the same
 * cases. Diagnostics deliberately report the location as {@code config://<redacted>} so host paths
 * never reach a report. Instances are thread-safe; the open scope sets are concurrent.</p>
 */
public final class RuntimePluginConfigRegistry implements PluginConfigRegistry, AutoCloseable {

    private static final String DIAGNOSTIC_LOCATION = "config://<redacted>";
    private static final long CONFIG_WAIT_TIMEOUT_MILLIS = 1_000L;
    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final PermissionChecker permissionChecker;
    private final ExecutorService io;
    private final Runnable beforePublication;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock lifecycleLock = new ReentrantLock(true);
    private final Path pluginDataDir;
    private final Consumer<StartupReport.DiagnosticProblem> diagnosticSink;
    private final RuntimeFailureSink failureSink;
    private final String pluginId;
    private final Set<String> readScopes = ConcurrentHashMap.newKeySet();
    private final Set<String> writeScopes = ConcurrentHashMap.newKeySet();

    public RuntimePluginConfigRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final Path pluginDataDir,
        final String pluginId
    ) {
        this(
            permissionChecker,
            scheduler,
            pluginDataDir,
            pluginId,
            ignored -> { },
            RuntimeFailureSink.noop()
        );
    }

    public RuntimePluginConfigRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final Path pluginDataDir,
        final String pluginId,
        final Consumer<StartupReport.DiagnosticProblem> diagnosticSink
    ) {
        this(
            permissionChecker,
            scheduler,
            pluginDataDir,
            pluginId,
            diagnosticSink,
            RuntimeFailureSink.noop()
        );
    }

    public RuntimePluginConfigRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final Path pluginDataDir,
        final String pluginId,
        final Consumer<StartupReport.DiagnosticProblem> diagnosticSink,
        final RuntimeFailureSink failureSink
    ) {
        this(
            permissionChecker,
            scheduler,
            pluginDataDir,
            pluginId,
            diagnosticSink,
            failureSink,
            null,
            () -> { }
        );
    }

    RuntimePluginConfigRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final Path pluginDataDir,
        final String pluginId,
        final Consumer<StartupReport.DiagnosticProblem> diagnosticSink,
        final RuntimeFailureSink failureSink,
        final ExecutorService io
    ) {
        this(
            permissionChecker,
            scheduler,
            pluginDataDir,
            pluginId,
            diagnosticSink,
            failureSink,
            io,
            () -> { }
        );
    }

    RuntimePluginConfigRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final Path pluginDataDir,
        final String pluginId,
        final Consumer<StartupReport.DiagnosticProblem> diagnosticSink,
        final RuntimeFailureSink failureSink,
        final ExecutorService io,
        final Runnable beforePublication
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        Objects.requireNonNull(scheduler, "scheduler");
        this.pluginDataDir = Objects.requireNonNull(pluginDataDir, "pluginDataDir")
            .toAbsolutePath()
            .normalize();
        this.pluginId = requireText(pluginId, "pluginId");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.failureSink = RuntimeFailureSink.require(failureSink);
        this.io = io == null ? newIoExecutor(this.pluginId) : io;
        this.beforePublication = Objects.requireNonNull(
            beforePublication,
            "beforePublication"
        );
    }

    private static ThreadPoolExecutor newIoExecutor(final String pluginId) {
        return new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(64),
            runnable -> {
                final Thread thread = new Thread(
                    runnable,
                    "turboism-legacy-config-" + pluginId
                );
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public Registration readScope(final String relativePath) {
        permissionChecker.check(PermissionIds.TURBOISM_CONFIG_PLUGIN_READ, "config.readScope");
        requireOpen();
        final String scope = scopeKey(relativePath);
        readScopes.add(scope);
        return () -> readScopes.remove(scope);
    }

    @Override
    public Registration writeScope(final String relativePath) {
        permissionChecker.check(PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE, "config.writeScope");
        requireOpen();
        final String scope = scopeKey(relativePath);
        writeScopes.add(scope);
        return () -> writeScopes.remove(scope);
    }

    @Override
    public Optional<String> readString(final String relativePath, final String key) {
        permissionChecker.check(PermissionIds.TURBOISM_CONFIG_PLUGIN_READ, "config.readString");
        requireOpen();
        final String scope = scopeKey(relativePath);
        requireScope(readScopes, scope, "read");
        final String propertyKey = requireText(key, "key");
        final Future<Optional<String>> result;
        try {
            result = io.submit(() -> readProperty(scope, propertyKey));
        } catch (RejectedExecutionException exception) {
            emit("CONFIG_READ_REJECTED", "Plugin config read was rejected", scope);
            return Optional.empty();
        }
        try {
            return result.get(CONFIG_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            emit("CONFIG_READ_INTERRUPTED", exception.getMessage(), scope);
            return Optional.empty();
        } catch (CancellationException | ExecutionException exception) {
            emit("CONFIG_READ_FAILED", exception.getMessage(), scope);
            return Optional.empty();
        } catch (TimeoutException exception) {
            result.cancel(true);
            emit("CONFIG_READ_TIMED_OUT", "Timed out waiting for plugin config read", scope);
            return Optional.empty();
        }
    }

    @Override
    public void writeString(final String relativePath, final String key, final String value) throws PluginConfigException {
        permissionChecker.check(PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE, "config.writeString");
        requireOpen();
        final String scope = scopeKey(relativePath);
        requireScope(writeScopes, scope, "write");
        final String propertyKey = requireText(key, "key");
        final String propertyValue = Objects.requireNonNull(value, "value");
        final Future<PluginConfigException> result;
        try {
            result = io.submit(() -> writeProperty(scope, propertyKey, propertyValue));
        } catch (RejectedExecutionException exception) {
            emit("CONFIG_WRITE_REJECTED", "Plugin config write was rejected", scope);
            throw new PluginConfigException("Plugin config write was rejected for " + scope);
        }
        PluginConfigException failure = awaitWrite(scope, result);
        if (failure != null) {
            throw failure;
        }
    }

    private PluginConfigException awaitWrite(
        final String scope,
        final Future<PluginConfigException> result
    ) throws PluginConfigException {
        try {
            return result.get(CONFIG_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            emit("CONFIG_WRITE_INTERRUPTED", exception.getMessage(), scope);
            throw new PluginConfigException("Interrupted while waiting for plugin config write " + scope, exception);
        } catch (CancellationException | ExecutionException exception) {
            emit("CONFIG_WRITE_FAILED", exception.getMessage(), scope);
            throw new PluginConfigException("Failed while waiting for plugin config write " + scope, exception);
        } catch (TimeoutException exception) {
            result.cancel(true);
            emit("CONFIG_WRITE_TIMED_OUT", "Timed out waiting for plugin config write", scope);
            throw new PluginConfigException("Timed out waiting for plugin config write " + scope, exception);
        }
    }

    private Optional<String> readProperty(final String scope, final String key) {
        if (closed.get() || !readScopes.contains(scope)) return Optional.empty();
        try {
            final Path path = confinedPath(scope);
            if (!Files.exists(path)) return Optional.empty();
            final Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
                return Optional.ofNullable(properties.getProperty(key));
            }
        } catch (IOException exception) {
            emit("CONFIG_READ_FAILED", exception.getMessage(), scope);
            return Optional.empty();
        }
    }

    private PluginConfigException writeProperty(final String scope, final String key, final String value) {
        if (writeRevoked(scope)) {
            return revokedWrite(scope);
        }
        final Properties properties = new Properties();
        Path temporary = null;
        try {
            final Path path = confinedPath(scope);
            final Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            final Path confined = confinedPath(scope);
            if (writeRevoked(scope)) {
                return revokedWrite(scope);
            }
            if (Files.exists(confined)) {
                try (Reader reader = Files.newBufferedReader(confined, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
            properties.setProperty(key, value);
            temporary = Files.createTempFile(
                Objects.requireNonNull(confined.getParent(), "config parent"),
                ".turboism-config-",
                ".tmp"
            );
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Turboism plugin config");
            }
            beforeConfigPublication();
            lifecycleLock.lockInterruptibly();
            try {
                if (writeRevoked(scope)) {
                    return revokedWrite(scope);
                }
                replaceConfig(temporary, confined);
                temporary = null;
                return null;
            } finally {
                lifecycleLock.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return revokedWrite(scope);
        } catch (IOException exception) {
            emit("CONFIG_WRITE_FAILED", exception.getMessage(), scope);
            return new PluginConfigException("Failed to write plugin config scope " + scope, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort: the unpublished temporary file remains confined to plugin data.
                }
            }
        }
    }

    private boolean writeRevoked(final String scope) {
        return closed.get() || !writeScopes.contains(scope) || Thread.currentThread().isInterrupted();
    }

    private PluginConfigException revokedWrite(final String scope) {
        return new PluginConfigException("Plugin config write scope is no longer active for " + scope);
    }

    private void beforeConfigPublication() {
        beforePublication.run();
    }

    private void replaceConfig(final Path temporary, final Path target) throws IOException {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path confinedPath(final String scope) throws IOException {
        Path current = pluginDataDir;
        for (Path segment : Path.of(scope)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Plugin config path contains a symbolic link");
            }
        }
        return current;
    }

    private String scopeKey(final String relativePath) {
        final String value = requireText(relativePath, "relativePath");
        final Path relative = Path.of(value).normalize();
        if (relative.isAbsolute() || startsWithParent(relative)) {
            throw new IllegalArgumentException("Config path must be relative and stay within the plugin data directory");
        }
        final Path resolved = pluginDataDir.resolve(relative).normalize();
        if (!resolved.startsWith(pluginDataDir)) {
            throw new IllegalArgumentException("Config path must stay within the plugin data directory");
        }
        return pluginDataDir.relativize(resolved).toString().replace('\\', '/');
    }

    private static boolean startsWithParent(final Path path) {
        return path.getNameCount() > 0 && "..".equals(path.getName(0).toString());
    }

    private static void requireScope(final Set<String> scopes, final String scope, final String operation) {
        if (!scopes.contains(scope)) {
            throw new IllegalStateException("Config " + operation + " scope is not registered for " + scope);
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("Plugin config registry is closed");
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (!closed.compareAndSet(false, true)) return;
        } finally {
            lifecycleLock.unlock();
        }
        final List<Runnable> discarded = io.shutdownNow();
        discarded.forEach(this::cancelDiscarded);
        try {
            if (!io.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Legacy config I/O did not quiesce before scope close");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for legacy config I/O quiescence",
                exception
            );
        } finally {
            readScopes.clear();
            writeScopes.clear();
        }
    }

    private void cancelDiscarded(final Runnable discarded) {
        if (discarded instanceof Future<?> future) future.cancel(false);
    }

    private void emit(final String code, final String ignoredMessage, final String ignoredScope) {
        diagnosticSink.accept(new StartupReport.DiagnosticProblem(
            code,
            stableDiagnosticMessage(code),
            DIAGNOSTIC_LOCATION,
            StartupReport.Severity.WARNING
        ));
        failureSink.record(RuntimeFailureDomain.CONFIG, new RuntimeFailure(
            code,
            "ERROR",
            "legacy-config",
            pluginId,
            legacyOperation(code),
            null,
            stableFailureMessage(code),
            null,
            1
        ));
    }

    private static String legacyOperation(final String code) {
        return code.startsWith("CONFIG_READ_")
            ? "config.readString"
            : "config.writeString";
    }

    private static String stableDiagnosticMessage(final String code) {
        return code.startsWith("CONFIG_READ_")
            ? "Plugin config read failed safely."
            : "Plugin config write failed safely.";
    }

    private static String stableFailureMessage(final String code) {
        return code.startsWith("CONFIG_READ_")
            ? "Plugin config read failed safely."
            : "Plugin config write failed safely.";
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
