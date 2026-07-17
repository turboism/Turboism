package dev.turboism.config;

import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.runtime.PluginTask;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class RuntimePluginConfigRegistry implements PluginConfigRegistry {

    private static final String READ_TASK_TYPE = "config.read";
    private static final String WRITE_TASK_TYPE = "config.write";
    private static final String DEFAULT_CAPABILITY = "none";
    private static final long CONFIG_WAIT_TIMEOUT_MILLIS = 1_000L;

    private final PermissionChecker permissionChecker;
    private final RuntimeScheduler scheduler;
    private final Path pluginDataDir;
    private final Consumer<StartupReport.DiagnosticProblem> diagnosticSink;
    private final String pluginId;
    private final Set<String> readScopes = ConcurrentHashMap.newKeySet();
    private final Set<String> writeScopes = ConcurrentHashMap.newKeySet();

    public RuntimePluginConfigRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final Path pluginDataDir,
        final String pluginId
    ) {
        this(permissionChecker, scheduler, pluginDataDir, pluginId, ignored -> {
        });
    }

    public RuntimePluginConfigRegistry(
        final PermissionChecker permissionChecker,
        final RuntimeScheduler scheduler,
        final Path pluginDataDir,
        final String pluginId,
        final Consumer<StartupReport.DiagnosticProblem> diagnosticSink
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginDataDir = Objects.requireNonNull(pluginDataDir, "pluginDataDir").toAbsolutePath().normalize();
        this.pluginId = requireText(pluginId, "pluginId");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    @Override
    public Registration readScope(final String relativePath) {
        permissionChecker.check(PermissionIds.TURBOISM_CONFIG_PLUGIN_READ, "config.readScope");
        final String scope = scopeKey(relativePath);
        readScopes.add(scope);
        return () -> readScopes.remove(scope);
    }

    @Override
    public Registration writeScope(final String relativePath) {
        permissionChecker.check(PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE, "config.writeScope");
        final String scope = scopeKey(relativePath);
        writeScopes.add(scope);
        return () -> writeScopes.remove(scope);
    }

    @Override
    public Optional<String> readString(final String relativePath, final String key) {
        permissionChecker.check(PermissionIds.TURBOISM_CONFIG_PLUGIN_READ, "config.readString");
        final String scope = scopeKey(relativePath);
        requireScope(readScopes, scope, "read");
        final String propertyKey = requireText(key, "key");
        CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        if (!scheduler.dispatch(
            task(READ_TASK_TYPE, scope),
            () -> result.complete(readProperty(scope, propertyKey))
        )) {
            emit("CONFIG_READ_REJECTED", "Plugin config read was rejected", scope);
            return Optional.empty();
        }
        try {
            return result.get(CONFIG_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emit("CONFIG_READ_INTERRUPTED", exception.getMessage(), scope);
            return Optional.empty();
        } catch (ExecutionException exception) {
            emit("CONFIG_READ_FAILED", exception.getMessage(), scope);
            return Optional.empty();
        } catch (TimeoutException exception) {
            emit("CONFIG_READ_TIMED_OUT", "Timed out waiting for plugin config read", scope);
            return Optional.empty();
        }
    }

    @Override
    public void writeString(final String relativePath, final String key, final String value) throws PluginConfigException {
        permissionChecker.check(PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE, "config.writeString");
        final String scope = scopeKey(relativePath);
        requireScope(writeScopes, scope, "write");
        final String propertyKey = requireText(key, "key");
        final String propertyValue = Objects.requireNonNull(value, "value");
        CompletableFuture<PluginConfigException> result = new CompletableFuture<>();
        if (!scheduler.dispatch(
            task(WRITE_TASK_TYPE, scope),
            () -> result.complete(writeProperty(scope, propertyKey, propertyValue))
        )) {
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
        final CompletableFuture<PluginConfigException> result
    ) throws PluginConfigException {
        try {
            return result.get(CONFIG_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emit("CONFIG_WRITE_INTERRUPTED", exception.getMessage(), scope);
            throw new PluginConfigException("Interrupted while waiting for plugin config write " + scope, exception);
        } catch (ExecutionException exception) {
            emit("CONFIG_WRITE_FAILED", exception.getMessage(), scope);
            throw new PluginConfigException("Failed while waiting for plugin config write " + scope, exception);
        } catch (TimeoutException exception) {
            emit("CONFIG_WRITE_TIMED_OUT", "Timed out waiting for plugin config write", scope);
            throw new PluginConfigException("Timed out waiting for plugin config write " + scope, exception);
        }
    }

    private Optional<String> readProperty(final String scope, final String key) {
        Path path = pluginDataDir.resolve(scope);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return Optional.ofNullable(properties.getProperty(key));
        } catch (IOException exception) {
            emit("CONFIG_READ_FAILED", exception.getMessage(), scope);
            return Optional.empty();
        }
    }

    private PluginConfigException writeProperty(final String scope, final String key, final String value) {
        Path path = pluginDataDir.resolve(scope);
        Properties properties = new Properties();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
            properties.setProperty(key, value);
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Turboism plugin config");
            }
            return null;
        } catch (IOException exception) {
            emit("CONFIG_WRITE_FAILED", exception.getMessage(), scope);
            return new PluginConfigException("Failed to write plugin config scope " + scope, exception);
        }
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

    private PluginTask task(final String taskType, final String scope) {
        return new PluginTask(taskType, pluginId, scope, DEFAULT_CAPABILITY);
    }

    private void emit(final String code, final String message, final String scope) {
        diagnosticSink.accept(new StartupReport.DiagnosticProblem(
            code,
            message == null ? "" : message,
            "config://" + scope,
            StartupReport.Severity.WARNING
        ));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
