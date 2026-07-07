package dev.turboism.core.descriptor;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record CorePluginDescriptor(
    String id,
    String name,
    String version,
    String description,
    Map<String, String> entrypoints,
    String turboismApi,
    List<Author> authors,
    String license,
    Optional<String> homepage,
    List<DependencyRef> dependencies,
    List<PermissionRef> permissions,
    List<String> capabilities,
    Environment environment
) implements PluginDescriptor {

    public record CoreAuthor(String name, Optional<String> email) implements Author {
    }

    public record CoreDependencyRef(String id, String type, String version, String ordering, Optional<String> reason) implements DependencyRef {
    }

    public record CorePermissionRef(String id, String scope, Optional<String> reason) implements PermissionRef {
    }

    public record CoreEnvironment(boolean requiresCubism, String ui) implements Environment {
    }
}
