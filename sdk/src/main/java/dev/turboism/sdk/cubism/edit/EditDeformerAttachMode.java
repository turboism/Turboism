package dev.turboism.sdk.cubism.edit;

/**
 * How a newly created deformer attaches to its target objects, matching the official
 * {@code DeformerAttachMode} enumeration of the deformer creation requests.
 */
public enum EditDeformerAttachMode {
    /** The new deformer becomes the parent of the target objects. */
    AS_PARENT,
    /** The new deformer is inserted as a child of the target objects. */
    AS_CHILD
}
