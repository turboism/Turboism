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
    private final Optional<String> category;
    private final List<String> tags;
    private final List<EventExport> eventExports;
    private final List<EventImport> eventImports;

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
        category = source.category();
        tags = List.copyOf(source.tags());
        eventExports = source.eventExports().stream()
            .map(value -> new EventExport(
                value.id(), value.contractVersion(), value.eventType(), value.abiSha256()
            ))
            .toList();
        eventImports = source.eventImports().stream()
            .map(value -> new EventImport(
                value.providerId(), value.eventId(), value.contractVersion(), value.eventType(),
                value.abiSha256(), value.required()
            ))
            .toList();
    }

    /** @return the plugin's declared identity */
    public String id() { return id; }

    /** @return the display name */
    public String name() { return name; }

    /** @return the declared plugin version */
    public String version() { return version; }

    /** @return the human-readable description */
    public String description() { return description; }

    /** @return entrypoint class names in manifest declaration order */
    public List<String> entrypoints() { return entrypoints; }

    /** @return the SDK version range this plugin declares compatibility with */
    public String turboismApi() { return turboismApi; }

    /** @return the declared authors */
    public List<Author> authors() { return authors; }

    /** @return the declared license */
    public String license() { return license; }

    /** @return the project website, when declared */
    public Optional<String> website() { return website; }

    /** @return declared bundled resource paths */
    public List<String> resources() { return resources; }

    /** @return the localization bundle declaration */
    public I18n i18n() { return i18n; }

    /** @return declared plugin dependencies */
    public List<Dependency> dependencies() { return dependencies; }

    /** @return declared permissions with the reason each was requested */
    public List<Permission> permissions() { return permissions; }

    /** @return declared capability identities */
    public List<String> capabilities() { return capabilities; }

    /** @return declared runtime environment requirements */
    public Environment environment() { return environment; }

    /** @return the directory category, when declared */
    public Optional<String> category() { return category; }

    /** @return declared directory tags */
    public List<String> tags() { return tags; }

    /** @return provider-owned public event contracts */
    public List<EventExport> eventExports() { return eventExports; }

    /** @return dependency-owned public event contracts consumed by the plugin */
    public List<EventImport> eventImports() { return eventImports; }

    /**
     * One declared plugin author.
     *
     * @param name the author's name
     * @param email contact address, when declared
     */
    public record Author(String name, Optional<String> email) {
    }

    /**
     * The plugin's localization bundle declaration.
     *
     * @param baseName resource-bundle base name
     * @param locales locales the plugin ships, copied defensively
     */
    public record I18n(String baseName, List<String> locales) {
        /** Copies the locale list so the snapshot stays deeply immutable. */
        public I18n {
            locales = List.copyOf(locales);
        }
    }

    /**
     * One declared dependency on another plugin.
     *
     * @param id the dependency's plugin id
     * @param type whether the dependency is required or optional
     * @param version the accepted version range
     * @param ordering load-order constraint relative to the dependency
     * @param reason why the dependency is needed, when declared
     */
    public record Dependency(
        String id,
        String type,
        String version,
        String ordering,
        Optional<String> reason
    ) {
    }

    /**
     * One declared permission request.
     *
     * @param id the permission identity
     * @param scope the scope the permission is requested at
     * @param reason why the plugin needs it, when declared
     */
    public record Permission(String id, String scope, Optional<String> reason) {
    }

    /** Public event exported by the provider plugin. */
    public record EventExport(
        String id,
        String contractVersion,
        String eventType,
        String abiSha256
    ) {
    }

    /** Public event imported from a declared dependency. */
    public record EventImport(
        String providerId,
        String eventId,
        String contractVersion,
        String eventType,
        String abiSha256,
        boolean required
    ) {
    }

    /**
     * Declared runtime environment requirements.
     *
     * @param requiresCubism whether the plugin needs an admitted Cubism host
     * @param ui the UI mode the plugin declares
     */
    public record Environment(boolean requiresCubism, String ui) {
    }
}
