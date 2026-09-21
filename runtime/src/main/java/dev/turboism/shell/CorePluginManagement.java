package dev.turboism.shell;

import java.util.List;
import java.util.Optional;

/** Runtime-supplied private management seam for the framework shell only. */
public interface CorePluginManagement extends AutoCloseable {
    String CORE_PLUGIN_ID = "turboism.core";

    /**
     * @return the catalog of plugins known to the runtime — live plugins, installed archives,
     *         and entries with pending operations — sorted with the core plugin first; desired
     *         state reflects configuration and never means a pending change already applied
     */
    List<PluginInfo> plugins();

    /**
     * @param pluginId the plugin to describe
     * @return the plugin's details, or empty when the id is unknown; this default exposes only
     *         the plugin-list metadata, so extended fields are empty — implementations may
     *         return richer detail from the installed archive
     */
    default Optional<PluginDetails> details(final String pluginId) {
        return plugins().stream()
            .filter(plugin -> plugin.id().equals(pluginId))
            .findFirst()
            .map(PluginDetails::summary);
    }
    /**
     * Runs the interactive package-pick-and-stage flow on the calling thread.
     *
     * @return the accepted/rejected outcome; acceptance means the install is journalled for the
     *         next launch, not that the plugin is already installed
     */
    OperationResult install();

    /**
     * Starts the install flow and delivers its outcome to {@code completion}.
     *
     * <p>This default runs {@link #install()} synchronously on the calling thread;
     * implementations may dispatch the pick and staging asynchronously.</p>
     *
     * @param completion receives exactly one outcome
     */
    default void requestInstall(final java.util.function.Consumer<OperationResult> completion) {
        completion.accept(install());
    }

    /**
     * Stages the removal of an installed plugin.
     *
     * @param pluginId the plugin to remove
     * @return the accepted/rejected outcome; acceptance means the uninstall is journalled for
     *         the next launch — the plugin stays live for this session
     */
    OperationResult uninstall(String pluginId);

    /**
     * Records the desired enabled state of an installed plugin.
     *
     * @param pluginId the plugin to enable or disable
     * @param enabled the desired state
     * @return the accepted/rejected outcome; acceptance means the change applies from the next
     *         launch, not immediately
     */
    OperationResult setEnabled(String pluginId, boolean enabled);
    @Override default void close() { }

    /**
     * One plugin catalog row.
     *
     * @param id unique plugin id, not blank
     * @param name display name, not blank
     * @param version plugin version, not blank
     * @param description display description, empty when absent
     * @param effectiveState lifecycle state in this session, {@code "DISCOVERED"} when unset
     * @param desiredState the configured state for the next launch; defaults to
     *     {@code effectiveState}
     * @param core whether this row is the built-in framework component
     * @param pendingOperation the journalled {@code "INSTALL"}/{@code "UNINSTALL"} awaiting the
     *     next launch
     * @param category presentation category, {@code "other"} when unset
     * @param tags descriptor tags, defensively copied
     * @param authors descriptor authors, defensively copied
     */
    record PluginInfo(
        String id, String name, String version, String description,
        String effectiveState, String desiredState, boolean core,
        Optional<String> pendingOperation,
        String category,
        List<String> tags,
        List<Author> authors
    ) {
        public PluginInfo {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            description = description == null ? "" : description;
            effectiveState = effectiveState == null ? "DISCOVERED" : effectiveState;
            desiredState = desiredState == null ? effectiveState : desiredState;
            pendingOperation = pendingOperation == null ? Optional.empty() : pendingOperation;
            category = category == null || category.isBlank() ? "other" : category;
            tags = tags == null ? List.of() : List.copyOf(tags);
            authors = authors == null ? List.of() : List.copyOf(authors);
        }

        public PluginInfo(
            final String id,
            final String name,
            final String version,
            final String description,
            final String effectiveState,
            final String desiredState,
            final boolean core,
            final Optional<String> pendingOperation,
            final String category,
            final List<String> tags
        ) {
            this(
                id, name, version, description, effectiveState, desiredState, core,
                pendingOperation, category, tags, List.of()
            );
        }
    }

    /**
     * Full plugin metadata for a details view; every collection is defensively copied and
     * optional metadata that is null falls back to an empty value. The {@code plugin}
     * component is required and a null value is rejected.
     */
    record PluginDetails(
        PluginInfo plugin,
        String turboismApi,
        List<Author> authors,
        String license,
        Optional<String> website,
        List<Dependency> dependencies,
        List<Permission> permissions,
        List<String> capabilities,
        boolean requiresCubism,
        String ui,
        List<String> entrypoints,
        List<String> resources,
        String i18nBaseName,
        List<String> locales,
        List<EventExport> eventExports,
        List<EventImport> eventImports,
        Optional<String> readme
    ) {
        public PluginDetails {
            if (plugin == null) throw new IllegalArgumentException("plugin must not be null");
            turboismApi = textOr(turboismApi, "");
            authors = authors == null ? List.of() : List.copyOf(authors);
            license = textOr(license, "");
            website = website == null ? Optional.empty() : website;
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            ui = textOr(ui, "none");
            entrypoints = entrypoints == null ? List.of() : List.copyOf(entrypoints);
            resources = resources == null ? List.of() : List.copyOf(resources);
            i18nBaseName = textOr(i18nBaseName, "");
            locales = locales == null ? List.of() : List.copyOf(locales);
            eventExports = eventExports == null ? List.of() : List.copyOf(eventExports);
            eventImports = eventImports == null ? List.of() : List.copyOf(eventImports);
            readme = readme == null ? Optional.empty() : readme;
        }

        /**
         * Creates details containing only the already available plugin-list metadata.
         *
         * @param plugin the plugin-list row to expose
         * @return immutable summary details with extended metadata left empty
         */
        public static PluginDetails summary(final PluginInfo plugin) {
            return new PluginDetails(
                plugin, "", List.of(), "", Optional.empty(), List.of(), List.of(), List.of(),
                false, "none", List.of(), List.of(), "", List.of(), List.of(), List.of(), Optional.empty()
            );
        }

        private static String textOr(final String value, final String fallback) {
            return value == null ? fallback : value;
        }
    }

    /** One plugin author: display {@code name} and optional contact {@code email}. */
    record Author(String name, Optional<String> email) {
        public Author {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            email = email == null ? Optional.empty() : email;
        }
    }

    /** One declared plugin dependency: target {@code id}, {@code type}, {@code version} range, {@code ordering} hint, and optional {@code reason}. */
    record Dependency(
        String id, String type, String version, String ordering, Optional<String> reason
    ) {
        public Dependency {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            type = type == null ? "" : type;
            version = version == null ? "" : version;
            ordering = ordering == null ? "" : ordering;
            reason = reason == null ? Optional.empty() : reason;
        }
    }

    /** One declared permission requirement: permission {@code id}, {@code scope}, and optional {@code reason}. */
    record Permission(String id, String scope, Optional<String> reason) {
        public Permission {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            scope = scope == null ? "" : scope;
            reason = reason == null ? Optional.empty() : reason;
        }
    }

    /** One event contract the plugin publishes: {@code id}, {@code contractVersion}, {@code eventType}, and payload {@code abiSha256} fingerprint. */
    record EventExport(String id, String contractVersion, String eventType, String abiSha256) {
        public EventExport {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            contractVersion = contractVersion == null ? "" : contractVersion;
            eventType = eventType == null ? "" : eventType;
            abiSha256 = abiSha256 == null ? "" : abiSha256;
        }
    }

    /**
     * One event contract the plugin consumes: {@code providerId}/{@code eventId} identify the
     * export; {@code required} marks subscriptions the plugin cannot run without.
     */
    record EventImport(
        String providerId,
        String eventId,
        String contractVersion,
        String eventType,
        String abiSha256,
        boolean required
    ) {
        public EventImport {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("providerId must not be blank");
            }
            if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId must not be blank");
            contractVersion = contractVersion == null ? "" : contractVersion;
            eventType = eventType == null ? "" : eventType;
            abiSha256 = abiSha256 == null ? "" : abiSha256;
        }
    }

    /**
     * Outcome of one management operation: {@code accepted} plus a stable machine-readable
     * {@code code} and a human-readable {@code message}; neither may be blank.
     */
    record OperationResult(boolean accepted, String code, String message) {
        public OperationResult {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
            if (message == null || message.isBlank()) throw new IllegalArgumentException("message must not be blank");
        }

        /**
         * @param code stable machine-readable outcome code; must not be blank
         * @param message human-readable detail; must not be blank
         * @return an accepted result — the runtime has taken the request on, which is not itself a
         *     promise that the operation has already finished
         */
        public static OperationResult accepted(String code, String message) {
            return new OperationResult(true, code, message);
        }

        /**
         * @param code stable machine-readable rejection code; must not be blank
         * @param message human-readable reason; must not be blank
         * @return a rejected result — nothing was done
         */
        public static OperationResult rejected(String code, String message) {
            return new OperationResult(false, code, message);
        }

        /**
         * @param message human-readable reason; must not be blank
         * @return a rejected result under the generic {@code PLUGIN_OPERATION_REJECTED} code, for
         *     refusals that need no more specific classification
         */
        public static OperationResult rejected(String message) {
            return rejected("PLUGIN_OPERATION_REJECTED", message);
        }
    }
}
