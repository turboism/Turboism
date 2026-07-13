package dev.turboism.distribution;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Distribution-owned deep immutable descriptor evidence. */
public final class PluginDescriptorSnapshot {
    private final String id, name, version, description, turboismApi, license;
    private final Map<String, String> entrypoints;
    private final List<Author> authors;
    private final Optional<String> homepage;
    private final List<Dependency> dependencies;
    private final List<Permission> permissions;
    private final List<String> capabilities;
    private final Environment environment;

    static PluginDescriptorSnapshot copyOf(PluginDescriptor source) {
        return new PluginDescriptorSnapshot(source);
    }

    private PluginDescriptorSnapshot(PluginDescriptor source) {
        id = source.id(); name = source.name(); version = source.version(); description = source.description();
        turboismApi = source.turboismApi(); license = source.license();
        entrypoints = Map.copyOf(source.entrypoints());
        authors = source.authors().stream().map(value -> new Author(value.name(), value.email())).toList();
        homepage = source.homepage();
        dependencies = source.dependencies().stream().map(value -> new Dependency(value.id(), value.type(),
            value.version(), value.ordering(), value.reason())).toList();
        permissions = source.permissions().stream().map(value ->
            new Permission(value.id(), value.scope(), value.reason())).toList();
        capabilities = List.copyOf(source.capabilities());
        environment = new Environment(source.environment().requiresCubism(), source.environment().ui());
    }

    public String id() { return id; }
    public String name() { return name; }
    public String version() { return version; }
    public String description() { return description; }
    public Map<String, String> entrypoints() { return entrypoints; }
    public String turboismApi() { return turboismApi; }
    public List<Author> authors() { return authors; }
    public String license() { return license; }
    public Optional<String> homepage() { return homepage; }
    public List<Dependency> dependencies() { return dependencies; }
    public List<Permission> permissions() { return permissions; }
    public List<String> capabilities() { return capabilities; }
    public Environment environment() { return environment; }

    public record Author(String name, Optional<String> email) {}
    public record Dependency(String id, String type, String version, String ordering, Optional<String> reason) {}
    public record Permission(String id, String scope, Optional<String> reason) {}
    public record Environment(boolean requiresCubism, String ui) {}
}
