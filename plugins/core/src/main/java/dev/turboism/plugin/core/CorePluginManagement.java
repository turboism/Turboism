package dev.turboism.plugin.core;

import java.util.List;
import java.util.Optional;

/** Runtime-supplied private management seam for the built-in core plugin only. */
public interface CorePluginManagement {
    String CORE_PLUGIN_ID = "turboism.core";

    List<PluginInfo> plugins();
    OperationResult install();
    OperationResult uninstall(String pluginId);
    OperationResult setEnabled(String pluginId, boolean enabled);

    record PluginInfo(
        String id, String name, String version, String description,
        String effectiveState, String desiredState, boolean core,
        Optional<String> pendingOperation
    ) {
        public PluginInfo {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            description = description == null ? "" : description;
            effectiveState = effectiveState == null ? "DISCOVERED" : effectiveState;
            desiredState = desiredState == null ? effectiveState : desiredState;
            pendingOperation = pendingOperation == null ? Optional.empty() : pendingOperation;
        }
    }

    record OperationResult(boolean accepted, String code, String message) {
        public OperationResult {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
            if (message == null || message.isBlank()) throw new IllegalArgumentException("message must not be blank");
        }

        public static OperationResult accepted(String code, String message) {
            return new OperationResult(true, code, message);
        }

        public static OperationResult rejected(String code, String message) {
            return new OperationResult(false, code, message);
        }

        public static OperationResult rejected(String message) {
            return rejected("PLUGIN_OPERATION_REJECTED", message);
        }
    }
}
