package dev.turboism.distribution;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.List;
import java.util.Optional;

/** Distribution-owned deep immutable descriptor evidence. */
public final class PluginDescriptorSnapshot {
    private final String id;
    private final String name;
    private final String version;
    private final String description;
    private final String turboismApi;
    private final String license;
    private final List<String> entrypoints;
    private final List<Author> authors;
    private final Optional<String> website;
    private final List<String> resources;
    private final I18n i18n;
    private final List<Dependency> dependencies;
    private final List<Permission> permissions;
    private final List<String> capabilities;
    private final Environment environment;

    static PluginDescriptorSnapshot copyOf(final PluginDescriptor source) {
        return new PluginDescriptorSnapshot(source);
    }

    private PluginDescriptorSnapshot(final PluginDescriptor source) {
        id = source.id();
        name = source.name();
        version = source.version();
        description = source.description();
        turboismApi = source.turboismApi();
        license = source.license();
        entrypoints = List.copyOf(source.entrypoints());
        authors = source.authors().stream()
            .map(value -> new Author(value.name(), value.email()))
            .toList();
        website = source.website();
        resources = List.copyOf(source.resources());
        i18n = new I18n(source.i18n().baseName(), source.i18n().locales());
        dependencies = source.dependencies().stream()
            .map(value -> new Dependency(
                value.id(), value.type(), value.version(), value.ordering(), value.reason()
            ))
            .toList();
        permissions = source.permissions().stream()
            .map(value -> new Permission(value.id(), value.scope(), value.reason()))
            .toList();
        capabilities = List.copyOf(source.capabilities());
        environment = new Environment(
            source.environment().requiresCubism(),
            source.environment().ui()
        );
    }

    public String id() { return id; }
    public String name() { return name; }
    public String version() { return version; }
    public String description() { return description; }
    public List<String> entrypoints() { return entrypoints; }
    public String turboismApi() { return turboismApi; }
    public List<Author> authors() { return authors; }
    public String license() { return license; }
    public Optional<String> website() { return website; }
    public List<String> resources() { return resources; }
    public I18n i18n() { return i18n; }
    public List<Dependency> dependencies() { return dependencies; }
    public List<Permission> permissions() { return permissions; }
    public List<String> capabilities() { return capabilities; }
    public Environment environment() { return environment; }

    public record Author(String name, Optional<String> email) {
    }

    public record I18n(String baseName, List<String> locales) {
        public I18n {
            locales = List.copyOf(locales);
        }
    }

    public record Dependency(
        String id,
        String type,
        String version,
        String ordering,
        Optional<String> reason
    ) {
    }

    public record Permission(String id, String scope, Optional<String> reason) {
    }

    public record Environment(boolean requiresCubism, String ui) {
    }
}
