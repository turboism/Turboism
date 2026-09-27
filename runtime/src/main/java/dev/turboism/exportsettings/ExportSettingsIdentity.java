package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.id.ModelId;

import java.util.Objects;

/**
 * Immutable Turboism-owned document/model identity captured for one native dialog flow.
 *
 * <p>Both fields are required: the export settings callback contract delivers the
 * canonical document identity and model identity together. When the host session cannot
 * resolve either one, the dialog flow treats identity as unavailable and the selected
 * path fails closed.</p>
 */
public record ExportSettingsIdentity(String documentId, ModelId modelId) {

    public ExportSettingsIdentity {
        Objects.requireNonNull(documentId, "documentId");
        if (documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        modelId = Objects.requireNonNull(modelId, "modelId");
    }
}
