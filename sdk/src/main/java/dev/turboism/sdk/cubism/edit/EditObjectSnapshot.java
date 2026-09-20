package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
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
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditObjectSnapshot(ModelObjectId object, EditObjectData data) {

    public EditObjectSnapshot {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(data, "data");
    }
}
