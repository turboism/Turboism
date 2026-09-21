package dev.turboism.sdk.plugin;

import java.util.List;
import java.util.Optional;

/**
 * Public contract of a plugin descriptor read from
 * {@code META-INF/turboism/plugin.json}.
 */
public interface PluginDescriptor {

    /** Returns the plugin's stable identifier. */
    String id();

    /** Returns the plugin's display name. */
    String name();

    /** Returns the plugin's declared version string. */
    String version();

    /** Returns the plugin's declared description. */
    String description();

    /** Ordered plugin entrypoint classes. */
    List<String> entrypoints();

    /** Returns the Turboism API version range the plugin was built against. */
    String turboismApi();

    /** Returns the declared authors in declaration order. */
    List<Author> authors();

    /** Returns the declared license identifier or text. */
    String license();

    /** Returns the declared project website, when present. */
    Optional<String> website();

    /** Plugin-owned resource roots, relative to the JAR root. */
    List<String> resources();

    /** Returns the plugin's declared localization catalog layout. */
    I18n i18n();

    /** Returns the plugin's declared dependencies in declaration order. */
    List<DependencyRef> dependencies();

    /** Returns the permissions the plugin declares it needs. */
    List<PermissionRef> permissions();

    /** Returns the runtime capabilities the plugin declares it uses. */
    List<String> capabilities();

    /** Returns the host environment the plugin declares it requires. */
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

    /** One public event contract this plugin provides. */
    interface EventExport {
        /** Returns the event's stable identifier within the providing plugin. */
        String id();

        /** Returns the declared contract version governing the payload ABI. */
        String contractVersion();

        /** Returns the event payload's fully qualified type name. */
        String eventType();

        /** Returns the payload type's declared ABI digest. */
        String abiSha256();
    }

    /** One public event contract this plugin consumes. */
    interface EventImport {
        /** Returns the providing plugin's identifier. */
        String providerId();

        /** Returns the event identifier declared by the provider. */
        String eventId();

        /** Returns the contract version this plugin was built against. */
        String contractVersion();

        /** Returns the expected event payload type name. */
        String eventType();

        /** Returns the expected payload type's ABI digest. */
        String abiSha256();

        /** Returns whether an unsatisfied import fails route admission instead of being skipped. */
        boolean required();
    }

    /** One declared plugin author. */
    interface Author {
        /** Returns the author's display name. */
        String name();

        /** Returns the author's contact email, when declared. */
        Optional<String> email();
    }

    /** Declared localization catalog layout. */
    interface I18n {
        /** Resource base without locale suffix or extension. */
        String baseName();

        /** Declared catalogs such as base, en, ja, or zh_Hans. */
        List<String> locales();
    }

    /** One declared plugin dependency. */
    interface DependencyRef {
        /** Returns the dependency's plugin identifier. */
        String id();

        /** Returns the dependency kind declared in plugin meta. */
        String type();

        /** Returns the declared acceptable version range. */
        String version();

        /** Returns the declared load-ordering constraint relative to the dependency. */
        String ordering();

        /** Returns the declared justification for the dependency, when present. */
        Optional<String> reason();
    }

    /** One permission the plugin declares it needs. */
    interface PermissionRef {
        /** Returns the permission identifier. */
        String id();

        /** Returns the scope the permission is requested for. */
        String scope();

        /** Returns the declared justification for the permission, when present. */
        Optional<String> reason();
    }

    /** Declared host environment requirements. */
    interface Environment {
        /** Returns whether the plugin requires a Cubism host to run. */
        boolean requiresCubism();

        /** Returns the declared UI mode the plugin uses. */
        String ui();
    }
}
