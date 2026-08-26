package dev.turboism.plugin.core;

import java.util.List;
import java.util.Optional;

/** Runtime-supplied private management seam for the built-in core plugin only. */
public interface CorePluginManagement extends AutoCloseable {
    String CORE_PLUGIN_ID = "turboism.core";

    List<PluginInfo> plugins();
    default Optional<PluginDetails> details(final String pluginId) {
        return plugins().stream()
            .filter(plugin -> plugin.id().equals(pluginId))
            .findFirst()
            .map(PluginDetails::summary);
    }
    OperationResult install();
    default void requestInstall(final java.util.function.Consumer<OperationResult> completion) {
        completion.accept(install());
    }
    OperationResult uninstall(String pluginId);
    OperationResult setEnabled(String pluginId, boolean enabled);
    @Override default void close() { }

    record PluginInfo(
        String id, String name, String version, String description,
        String effectiveState, String desiredState, boolean core,
        Optional<String> pendingOperation,
        String category,
        List<String> tags
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
        }
    }

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

    record Author(String name, Optional<String> email) {
        public Author {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            email = email == null ? Optional.empty() : email;
        }
    }

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

    record Permission(String id, String scope, Optional<String> reason) {
        public Permission {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            scope = scope == null ? "" : scope;
            reason = reason == null ? Optional.empty() : reason;
        }
    }

    record EventExport(String id, String contractVersion, String eventType, String abiSha256) {
        public EventExport {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            contractVersion = contractVersion == null ? "" : contractVersion;
            eventType = eventType == null ? "" : eventType;
            abiSha256 = abiSha256 == null ? "" : abiSha256;
        }
    }

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
