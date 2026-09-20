package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.Objects;

/**
 * The result of reading one palette object ({@code GetObject}): its identity plus the typed
 * property payload.
 *
 * @param object the editor-assigned object id that was read
 * @param data the typed property payload; its {@link EditObjectData#kind()} is the official
 *     {@code Type} of the result
 */
public record EditObjectSnapshot(ModelObjectId object, EditObjectData data) {

    public EditObjectSnapshot {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(data, "data");
    }
}
