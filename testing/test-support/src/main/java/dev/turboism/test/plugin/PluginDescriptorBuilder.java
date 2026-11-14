package dev.turboism.test.plugin;

import dev.turboism.sdk.plugin.PluginDescriptor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fluent builder for {@link PluginDescriptor} test doubles.
 *
 * <p>Every unset property keeps the shared test default, so a fixture states only the values its
 * assertions depend on. The defaults match the descriptor the runtime accepts for a minimal plugin.
 */
public final class PluginDescriptorBuilder {

    /** Version reported when a fixture does not declare one. */
    public static final String DEFAULT_VERSION = "1.0.0";

    /** Description reported when a fixture does not declare one. */
    public static final String DEFAULT_DESCRIPTION = "test";

    /** API range reported when a fixture does not declare one. */
    public static final String DEFAULT_TURBOISM_API = "[0.67.0,1.0.0)";

    /** License reported when a fixture does not declare one. */
    public static final String DEFAULT_LICENSE = "UNLICENSED";

    /** Localization base name reported when a fixture does not declare one. */
    public static final String DEFAULT_I18N_BASE_NAME = "messages";

    /** UI mode reported when a fixture does not declare one. */
    public static final String DEFAULT_UI = "none";

    private final String id;
    private String name;
    private String version = DEFAULT_VERSION;
    private String description = DEFAULT_DESCRIPTION;
    private List<String> entrypoints = List.of();
    private String turboismApi = DEFAULT_TURBOISM_API;
    private List<PluginDescriptor.Author> authors = List.of();
    private String license = DEFAULT_LICENSE;
    private Optional<String> website = Optional.empty();
    private List<String> resources = List.of();
    private String i18nBaseName = DEFAULT_I18N_BASE_NAME;
    private List<String> i18nLocales = List.of();
    private List<PluginDescriptor.DependencyRef> dependencies = List.of();
    private List<PluginDescriptor.PermissionRef> permissions = List.of();
    private List<String> capabilities = List.of();
    private boolean requiresCubism;
    private String ui = DEFAULT_UI;
    private Optional<String> category = Optional.empty();

    private PluginDescriptorBuilder(final String id) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = id;
    }

    /** Starts a builder whose display name defaults to {@code id}. */
    public static PluginDescriptorBuilder forId(final String id) {
        return new PluginDescriptorBuilder(id);
    }

    public PluginDescriptorBuilder name(final String name) {
        this.name = Objects.requireNonNull(name, "name");
        return this;
    }

    public PluginDescriptorBuilder version(final String version) {
        this.version = Objects.requireNonNull(version, "version");
        return this;
    }

    public PluginDescriptorBuilder description(final String description) {
        this.description = Objects.requireNonNull(description, "description");
        return this;
    }

    public PluginDescriptorBuilder entrypoints(final String... entrypoints) {
        this.entrypoints = List.of(entrypoints);
        return this;
    }

    public PluginDescriptorBuilder entrypoints(final List<String> entrypoints) {
        this.entrypoints = List.copyOf(entrypoints);
        return this;
    }

    public PluginDescriptorBuilder turboismApi(final String turboismApi) {
        this.turboismApi = Objects.requireNonNull(turboismApi, "turboismApi");
        return this;
    }

    public PluginDescriptorBuilder authors(final List<PluginDescriptor.Author> authors) {
        this.authors = List.copyOf(authors);
        return this;
    }

    public PluginDescriptorBuilder license(final String license) {
        this.license = Objects.requireNonNull(license, "license");
        return this;
    }

    public PluginDescriptorBuilder website(final Optional<String> website) {
        this.website = Objects.requireNonNull(website, "website");
        return this;
    }

    public PluginDescriptorBuilder website(final String website) {
        this.website = Optional.of(Objects.requireNonNull(website, "website"));
        return this;
    }

    public PluginDescriptorBuilder resources(final String... resources) {
        this.resources = List.of(resources);
        return this;
    }

    public PluginDescriptorBuilder i18n(final String baseName, final List<String> locales) {
        this.i18nBaseName = Objects.requireNonNull(baseName, "baseName");
        this.i18nLocales = List.copyOf(locales);
        return this;
    }

    public PluginDescriptorBuilder i18n(final String baseName, final String... locales) {
        return i18n(baseName, List.of(locales));
    }

    public PluginDescriptorBuilder dependencies(final List<PluginDescriptor.DependencyRef> dependencies) {
        this.dependencies = List.copyOf(dependencies);
        return this;
    }

    public PluginDescriptorBuilder permissions(final List<PluginDescriptor.PermissionRef> permissions) {
        this.permissions = List.copyOf(permissions);
        return this;
    }

    public PluginDescriptorBuilder permissions(final PluginDescriptor.PermissionRef... permissions) {
        return permissions(List.of(permissions));
    }

    public PluginDescriptorBuilder capabilities(final String... capabilities) {
        this.capabilities = List.of(capabilities);
        return this;
    }

    public PluginDescriptorBuilder requiresCubism(final boolean requiresCubism) {
        this.requiresCubism = requiresCubism;
        return this;
    }

    public PluginDescriptorBuilder ui(final String ui) {
        this.ui = Objects.requireNonNull(ui, "ui");
        return this;
    }

    public PluginDescriptorBuilder category(final String category) {
        this.category = Optional.of(Objects.requireNonNull(category, "category"));
        return this;
    }

    /** Returns an immutable descriptor snapshot of the current builder state. */
    public PluginDescriptor build() {
        return new BuiltPluginDescriptor(this);
    }

    private record BuiltI18n(String baseName, List<String> locales) implements PluginDescriptor.I18n {}

    private record BuiltEnvironment(boolean requiresCubism, String ui) implements PluginDescriptor.Environment {}

    private record BuiltPluginDescriptor(
            String id,
            String name,
            String version,
            String description,
            List<String> entrypoints,
            String turboismApi,
            List<Author> authors,
            String license,
            Optional<String> website,
            List<String> resources,
            I18n i18n,
            List<DependencyRef> dependencies,
            List<PermissionRef> permissions,
            List<String> capabilities,
            Environment environment,
            Optional<String> category)
            implements PluginDescriptor {

        private BuiltPluginDescriptor(final PluginDescriptorBuilder builder) {
            this(
                    builder.id,
                    builder.name,
                    builder.version,
                    builder.description,
                    builder.entrypoints,
                    builder.turboismApi,
                    builder.authors,
                    builder.license,
                    builder.website,
                    builder.resources,
                    new BuiltI18n(builder.i18nBaseName, builder.i18nLocales),
                    builder.dependencies,
                    builder.permissions,
                    builder.capabilities,
                    new BuiltEnvironment(builder.requiresCubism, builder.ui),
                    builder.category);
        }
    }
}
