package dev.turboism.core.descriptor;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.List;
import java.util.Optional;

/**
 * Runtime's immutable realization of a plugin manifest, produced by
 * {@link PluginDescriptorParser} after schema validation.
 *
 * <p>Every list component is defensively copied, so a descriptor handed to plugin code cannot be
 * mutated by its holder. {@code category} and {@code tags} are the only components that tolerate a
 * null argument, normalized to empty; a null anywhere else fails the copy.</p>
 *
 * @param id dotted plugin identifier, unique across the installation
 * @param name human-readable plugin name
 * @param version plugin's own version string
 * @param description short summary shown in plugin listings
 * @param entrypoints unmodifiable copy of the plugin entrypoint class names
 * @param turboismApi Turboism API version the plugin declares it was built against
 * @param authors unmodifiable copy of the declared authors
 * @param license license identifier declared by the plugin
 * @param website project URL, empty when the manifest declared none
 * @param resources unmodifiable copy of the resource paths bundled with the plugin
 * @param i18n message-bundle declaration
 * @param dependencies unmodifiable copy of the plugins this one depends on
 * @param permissions unmodifiable copy of the permissions the plugin requests
 * @param capabilities unmodifiable copy of the host capabilities the plugin requires
 * @param environment host requirements such as whether Cubism itself must be present
 * @param category directory category, empty when unclassified
 * @param tags unmodifiable copy of the free-form tags, empty when the manifest declared none
 */
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

    /**
     * One declared author of the plugin.
     *
     * @param name author or organisation name
     * @param email contact address, empty when the manifest declared none
     */
    public record CoreAuthor(String name, Optional<String> email) implements Author {
    }

    /**
     * The plugin's message-bundle declaration.
     *
     * @param baseName resource-bundle base name resolved against the plugin's own classloader
     * @param locales unmodifiable copy of the locale tags the plugin ships translations for
     */
    public record CoreI18n(String baseName, List<String> locales) implements I18n {
        public CoreI18n {
            locales = List.copyOf(locales);
        }
    }

    /**
     * A dependency on another plugin.
     *
     * @param id identifier of the depended-on plugin
     * @param type kind of dependency, which decides whether a missing target is fatal
     * @param version version requirement expressed against the target
     * @param ordering load-order constraint relative to the target
     * @param reason operator-facing justification, empty when the manifest gave none
     */
    public record CoreDependencyRef(
        String id,
        String type,
        String version,
        String ordering,
        Optional<String> reason
    ) implements DependencyRef {
    }

    /**
     * A permission the plugin requests at install time. Declaring it here is a request, not a
     * grant; the runtime's permission checker decides at call time.
     *
     * @param id permission identifier
     * @param scope narrowing scope the permission is requested for
     * @param reason operator-facing justification shown when reviewing the request, empty when the
     *     manifest gave none
     */
    public record CorePermissionRef(
        String id,
        String scope,
        Optional<String> reason
    ) implements PermissionRef {
    }

    /**
     * Host requirements the plugin declares.
     *
     * @param requiresCubism true when the plugin cannot load outside a real Cubism Editor host
     * @param ui the UI surface the plugin needs from the host
     */
    public record CoreEnvironment(boolean requiresCubism, String ui) implements Environment {
    }
}
