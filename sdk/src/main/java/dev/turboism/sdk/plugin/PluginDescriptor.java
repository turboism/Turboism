package dev.turboism.sdk.plugin;

import java.util.List;
import java.util.Optional;

/**
 * Public contract of a plugin descriptor read from
 * {@code META-INF/turboism/plugin.json}.
 */
public interface PluginDescriptor {

    String id();

    String name();

    String version();

    String description();

    /** Ordered plugin entrypoint classes. */
    List<String> entrypoints();

    String turboismApi();

    List<Author> authors();

    String license();

    Optional<String> website();

    /** Plugin-owned resource roots, relative to the JAR root. */
    List<String> resources();

    I18n i18n();

    List<DependencyRef> dependencies();

    List<PermissionRef> permissions();

    List<String> capabilities();

    Environment environment();

    interface Author {
        String name();

        Optional<String> email();
    }

    interface I18n {
        /** Resource base without locale suffix or extension. */
        String baseName();

        /** Declared catalogs such as base, en, ja, or zh_Hans. */
        List<String> locales();
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
