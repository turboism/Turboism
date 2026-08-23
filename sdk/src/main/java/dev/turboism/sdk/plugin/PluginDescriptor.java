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

    /**
     * Declared primary classification category, or empty for descriptors
     * without one (schema v2 and legacy descriptors).
     *
     * <p>Descriptive metadata only: it grants no permission, capability, or
     * execution-policy effect. Values are canonical lowercase kebab-case IDs;
     * display localization is owned by consumers.</p>
     *
     * @return the declared category token when the descriptor provides one
     */
    default Optional<String> category() {
        return Optional.empty();
    }

    /**
     * Declared canonical classification tags in declaration order.
     *
     * <p>Descriptive metadata only: it grants no permission, capability, or
     * execution-policy effect. The returned list is immutable; descriptors
     * without tags (schema v2 and legacy descriptors) expose an empty list.</p>
     *
     * @return immutable declared tags, never {@code null}
     */
    default List<String> tags() {
        return List.of();
    }

    /**
     * Public event contracts this plugin provides to declared dependents.
     *
     * <p>The provider plugin id is implicit in this descriptor. Event ids are
     * stable within that provider and contract versions govern payload ABI.</p>
     *
     * @return immutable exports in declaration order
     */
    default List<EventExport> eventExports() {
        return List.of();
    }

    /**
     * Public event contracts this plugin consumes from declared dependencies.
     *
     * @return immutable imports in declaration order
     */
    default List<EventImport> eventImports() {
        return List.of();
    }

    interface EventExport {
        String id();

        String contractVersion();

        String eventType();

        String abiSha256();
    }

    interface EventImport {
        String providerId();

        String eventId();

        String contractVersion();

        String eventType();

        String abiSha256();

        boolean required();
    }

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
