package dev.turboism.sdk.config;

import dev.turboism.sdk.plugin.Registration;

import java.util.Optional;

public interface PluginConfigRegistry {

    Registration readScope(String relativePath);

    Registration writeScope(String relativePath);

    Optional<String> readString(String relativePath, String key);
    void writeString(String relativePath, String key, String value) throws PluginConfigException;

    record ConfigScope(String relativePath, String permissionId) {}
}
