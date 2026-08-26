package dev.turboism.test.config;

import dev.turboism.sdk.config.PluginConfigException;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory key-value config store backed by a sandboxed relative path.
 */
public final class FakeConfigStore {

    private final Path baseDataDir;
    private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();

    public FakeConfigStore(Path baseDataDir) {
        this.baseDataDir = Objects.requireNonNull(baseDataDir, "baseDataDir");
    }

    public String readString(String relativePath, String key) throws PluginConfigException {
        validateRelativePath(relativePath);
        Objects.requireNonNull(key, "key");
        Map<String, String> section = data.get(normalizePath(relativePath));
        return section == null ? null : section.get(key);
    }

    public void writeString(String relativePath, String key, String value) throws PluginConfigException {
        validateRelativePath(relativePath);
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        data.computeIfAbsent(normalizePath(relativePath), k -> new ConcurrentHashMap<>()).put(key, value);
    }

    private void validateRelativePath(String relativePath) throws PluginConfigException {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank()) {
            throw new PluginConfigException("relativePath must not be blank");
        }
        if (relativePath.contains("..")) {
            throw new PluginConfigException("relativePath must not contain '..': " + relativePath);
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new PluginConfigException("relativePath must be relative: " + relativePath);
        }
    }

    private String normalizePath(String relativePath) {
        return relativePath.replace('\\', '/');
    }
}
