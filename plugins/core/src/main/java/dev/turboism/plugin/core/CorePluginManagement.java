package dev.turboism.plugin.core;

import java.util.List;
import java.util.Optional;

/** Runtime-supplied private management seam for the built-in core plugin only. */
public interface CorePluginManagement extends AutoCloseable {
    String CORE_PLUGIN_ID = "turboism.core";

    List<PluginInfo> plugins();
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
