package dev.turboism.sdk.cubism.edit;

/**
 * The six object types the editor's parts palette can report, matching the official
 * {@code ModelObjectType} enumeration.
 *
 * <p>Wider than {@link dev.turboism.sdk.cubism.model.ModelObjectKind}: structure queries can
 * report {@link #ART_PATH} and {@link #GLUE} entries even though some operations do not accept
 * them as targets.
 */
public enum EditObjectKind {
    PART,
    ART_MESH,
    WARP_DEFORMER,
    ROTATION_DEFORMER,
    ART_PATH,
    GLUE
}
