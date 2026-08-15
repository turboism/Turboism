package dev.turboism.core.descriptor;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.List;
import java.util.Optional;

public record CorePluginDescriptor(
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
    Optional<String> category,
    List<String> tags
) implements PluginDescriptor {

    public CorePluginDescriptor {
        entrypoints = List.copyOf(entrypoints);
        authors = List.copyOf(authors);
        resources = List.copyOf(resources);
        dependencies = List.copyOf(dependencies);
        permissions = List.copyOf(permissions);
        capabilities = List.copyOf(capabilities);
        category = category == null ? Optional.empty() : category;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public record CoreAuthor(String name, Optional<String> email) implements Author {
    }

    public record CoreI18n(String baseName, List<String> locales) implements I18n {
        public CoreI18n {
            locales = List.copyOf(locales);
        }
    }

    public record CoreDependencyRef(
        String id,
        String type,
        String version,
        String ordering,
        Optional<String> reason
    ) implements DependencyRef {
    }

    public record CorePermissionRef(
        String id,
        String scope,
        Optional<String> reason
    ) implements PermissionRef {
    }

    public record CoreEnvironment(boolean requiresCubism, String ui) implements Environment {
    }
}
