package dev.turboism.core.descriptor;

import dev.turboism.sdk.plugin.PluginDescriptor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The 21 components of one descriptor as they are read, and the only way to obtain a {@link
 * CorePluginDescriptor}.
 *
 * <p>Each component has its own setter, so a reader cannot land a value in the wrong slot the way a
 * positional constructor of 21 arguments can. Components a tier does not admit keep the value the
 * record's own defaults give them: empty strings, empty lists, no category, no host requirements.</p>
 */
final class DescriptorBuilder {

    private String id = "";
    private String name = "";
    private String version = "";
    private String description = "";
    private List<String> entrypoints = List.of();
    private String turboismApi = "";
    private List<PluginDescriptor.Author> authors = List.of();
    private String license = "";
    private Optional<String> website = Optional.empty();
    private List<String> resources = List.of();
    private PluginDescriptor.I18n i18n;
    private List<PluginDescriptor.DependencyRef> dependencies = List.of();
    private List<PluginDescriptor.PermissionRef> permissions = List.of();
    private List<String> capabilities = List.of();
    private PluginDescriptor.Environment environment;
    private Optional<String> category = Optional.empty();
    private List<String> tags = List.of();
    private List<PluginDescriptor.EventExport> eventExports = List.of();
    private List<PluginDescriptor.EventImport> eventImports = List.of();
    private List<PluginDescriptor.EventContract> eventContracts = List.of();
    private boolean hotReload;

    /** @param id declared plugin identity */
    void id(final String id) {
        this.id = id;
    }

    /** @param name declared display name */
    void name(final String name) {
        this.name = name;
    }

    /** @param version declared plugin version */
    void version(final String version) {
        this.version = version;
    }

    /** @param description declared summary */
    void description(final String description) {
        this.description = description;
    }

    /** @param entrypoints declared entrypoint class names */
    void entrypoints(final List<String> entrypoints) {
        this.entrypoints = entrypoints;
    }

    /** @param turboismApi declared API range */
    void turboismApi(final String turboismApi) {
        this.turboismApi = turboismApi;
    }

    /** @param authors declared authors in declaration order */
    void authors(final List<PluginDescriptor.Author> authors) {
        this.authors = authors;
    }

    /** @param license declared license identifier */
    void license(final String license) {
        this.license = license;
    }

    /** @param website declared project URL */
    void website(final Optional<String> website) {
        this.website = website;
    }

    /** @param resources declared resource roots */
    void resources(final List<String> resources) {
        this.resources = resources;
    }

    /** @param i18n declared message-bundle layout */
    void i18n(final PluginDescriptor.I18n i18n) {
        this.i18n = i18n;
    }

    /** @param dependencies declared dependencies in declaration order */
    void dependencies(final List<PluginDescriptor.DependencyRef> dependencies) {
        this.dependencies = dependencies;
    }

    /** @param permissions declared permission requests */
    void permissions(final List<PluginDescriptor.PermissionRef> permissions) {
        this.permissions = permissions;
    }

    /** @param capabilities declared host capabilities */
    void capabilities(final List<String> capabilities) {
        this.capabilities = capabilities;
    }

    /** @param environment declared host requirements */
    void environment(final PluginDescriptor.Environment environment) {
        this.environment = environment;
    }

    /** @param category declared classification category */
    void category(final Optional<String> category) {
        this.category = category;
    }

    /** @param tags declared classification tags */
    void tags(final List<String> tags) {
        this.tags = tags;
    }

    /** @param eventExports declared public event exports */
    void eventExports(final List<PluginDescriptor.EventExport> eventExports) {
        this.eventExports = eventExports;
    }

    /** @param eventImports declared public event imports */
    void eventImports(final List<PluginDescriptor.EventImport> eventImports) {
        this.eventImports = eventImports;
    }

    /** @param eventContracts declared public event contract artifacts */
    void eventContracts(final List<PluginDescriptor.EventContract> eventContracts) {
        this.eventContracts = eventContracts;
    }

    /** @param hotReload whether the plugin declares hot reload capability */
    void hotReload(final boolean hotReload) {
        this.hotReload = hotReload;
    }

    /**
     * @return the immutable descriptor; every list is copied by the record itself
     * @throws IllegalStateException if a required component was never read, which the tier's
     *     presence rules make unreachable
     */
    CorePluginDescriptor build() {
        return new CorePluginDescriptor(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(version, "version"),
                Objects.requireNonNull(description, "description"),
                Objects.requireNonNull(entrypoints, "entrypoints"),
                Objects.requireNonNull(turboismApi, "turboismApi"),
                Objects.requireNonNull(authors, "authors"),
                Objects.requireNonNull(license, "license"),
                Objects.requireNonNull(website, "website"),
                Objects.requireNonNull(resources, "resources"),
                Objects.requireNonNull(i18n, "i18n"),
                Objects.requireNonNull(dependencies, "dependencies"),
                Objects.requireNonNull(permissions, "permissions"),
                Objects.requireNonNull(capabilities, "capabilities"),
                Objects.requireNonNull(environment, "environment"),
                Objects.requireNonNull(category, "category"),
                Objects.requireNonNull(tags, "tags"),
                Objects.requireNonNull(eventExports, "eventExports"),
                Objects.requireNonNull(eventImports, "eventImports"),
                Objects.requireNonNull(eventContracts, "eventContracts"),
                hotReload);
    }
}
