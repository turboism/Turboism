package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
/**
 * The typed {@code Data} payload of an object read ({@code GetObject} result).
 *
 * <p>Sealed to the five object types the editor can actually return. {@link
 * EditObjectKind#ART_PATH} has no data payload here: the official API does not support reading
 * ArtPath data, so requests for ArtPath objects fail closed instead of producing a partially
 * populated record.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public sealed interface EditObjectData
        permits EditArtMeshData, EditPartData, EditWarpDeformerData, EditRotationDeformerData, EditGlueData {

    /** Returns the object type this payload describes. */
    EditObjectKind kind();
}
