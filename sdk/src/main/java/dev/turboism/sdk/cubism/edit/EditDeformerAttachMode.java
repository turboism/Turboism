package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/**
 * How a newly created deformer attaches to its target objects, matching the official
 * {@code DeformerAttachMode} enumeration of the deformer creation requests.
 */
@Incubating
@CubismEditor(from = "5.2.03", to = "5.3.99")
public enum EditDeformerAttachMode {
    /** The new deformer becomes the parent of the target objects. */
    AS_PARENT,
    /** The new deformer is inserted as a child of the target objects. */
    AS_CHILD
}
