package dev.turboism.test.plugin;

import dev.turboism.sdk.plugin.PluginDescriptor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Synthetic {@link PluginDescriptor} implementation for test contexts.
 */
public final class FakePluginDescriptor implements PluginDescriptor {

    private final String id;
    private final String name;
    private final String version;
    private final String description;

    public FakePluginDescriptor(final String id, final String name, final String version, final String description) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.version = Objects.requireNonNull(version, "version");
        this.description = Objects.requireNonNull(description, "description");
    }

    public static FakePluginDescriptor testDescriptor(final String id) {
        return new FakePluginDescriptor(id, id, "1.0.0", "Test plugin " + id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<String> entrypoints() {
        return List.of();
    }

    @Override
    public String turboismApi() {
        return ">=1.0.0";
    }

    @Override
    public List<Author> authors() {
        return List.of();
    }

    @Override
    public String license() {
        return "Apache-2.0";
    }

    @Override
    public Optional<String> website() {
        return Optional.empty();
    }

    @Override
    public List<String> resources() {
        return List.of();
    }

    @Override
    public I18n i18n() {
        return new I18n() {
            @Override
            public String baseName() {
                return "messages";
            }

            @Override
            public List<String> locales() {
                return List.of("en");
            }
        };
    }

    @Override
    public List<DependencyRef> dependencies() {
        return List.of();
    }

    @Override
    public List<PermissionRef> permissions() {
        return List.of();
    }

    @Override
    public List<String> capabilities() {
        return List.of();
    }

    @Override
    public Environment environment() {
        return new Environment() {
            @Override
            public boolean requiresCubism() {
                return false;
            }

            @Override
            public String ui() {
                return "swing";
            }
        };
    }
}
