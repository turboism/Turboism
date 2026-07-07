package dev.turboism.sdk.plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public contract of a plugin descriptor as read from {@code META-INF/turboism/plugin.json}.
 */
public interface PluginDescriptor {

    String id();

    String name();

    String version();

    String description();

    Map<String, String> entrypoints();

    String turboismApi();

    List<Author> authors();

    String license();

    Optional<String> homepage();

    List<DependencyRef> dependencies();

    List<PermissionRef> permissions();

    List<String> capabilities();

    Environment environment();

    interface Author {
        String name();

        Optional<String> email();
    }

    interface DependencyRef {
        String id();

        String type();

        String version();

        String ordering();

        Optional<String> reason();
    }

    interface PermissionRef {
        String id();

        String scope();

        Optional<String> reason();
    }

    interface Environment {
        boolean requiresCubism();

        String ui();
    }
}
