package dev.turboism.adapter.cubism.optimization.modelinstances;

import java.util.List;

/**
 * Reviewed host members the model-instance coalescer reads and rewrites.
 *
 * <p>The coalescer decides whether two publications describe the same committed edit, so it reads
 * the document, the edit mode and the undo group through their own accessors. Every member below is
 * proven present before the weave is armed, and the names live here instead of in the installer so
 * the composition layer stays free of host-package literals.</p>
 */
public final class ModelInstanceCoalesceDependencies {

    /** Modeling document the source publishes instances of. */
    public static final String DOCUMENT_OWNER = "com/live2d/cubism/doc/modeling/CModelingDocument";

    /** Undo group the edit mode commits into. */
    public static final String UNDO_GROUP_OWNER = "com/live2d/undo/GroupUndo";

    /** Source accessor returning the modeling document. */
    public static final String GET_DOCUMENT = "getDocument";

    /** {@code getDocument() CModelingDocument}. */
    public static final String GET_DOCUMENT_DESCRIPTOR = "()Lcom/live2d/cubism/doc/modeling/CModelingDocument;";

    /** Document accessor returning the current edit mode. */
    public static final String GET_CURRENT_EDIT_MODE = "getCurrentEditMode";

    /** {@code getCurrentEditMode() CModelingEditMode_Base}. */
    public static final String GET_CURRENT_EDIT_MODE_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/modeling/CModelingEditMode_Base;";

    /** Edit-mode predicate reporting whether an edit is in progress. */
    public static final String IS_EDITING = "isEditing";

    /** {@code isEditing() boolean}. */
    public static final String IS_EDITING_DESCRIPTOR = "()Z";

    /** Edit-mode accessor returning the current undo group. */
    public static final String GET_CURRENT_UNDO = "getCurrentUndo";

    /** {@code getCurrentUndo() GroupUndo}. */
    public static final String GET_CURRENT_UNDO_DESCRIPTOR = "()Lcom/live2d/undo/GroupUndo;";

    /** Edit-mode accessor returning its document. */
    public static final String GET_DOC = "getDoc";

    /** {@code getDoc() IDocument}. */
    public static final String GET_DOC_DESCRIPTOR = "()Lcom/live2d/cubism/doc/IDocument;";

    /** Undo-group accessor returning the committed edit count. */
    public static final String GET_EDIT_COUNT = "getEditCount";

    /** {@code getEditCount() int}. */
    public static final String GET_EDIT_COUNT_DESCRIPTOR = "()I";

    private ModelInstanceCoalesceDependencies() {}

    /**
     * The reviewed members of one host class, in resolution order.
     *
     * @param owner internal name of the declaring class
     * @return the reviewed members of that class; empty when the coalescer reads none of them
     */
    public static List<Member> forOwner(final String owner) {
        if (ModelInstanceCoalesceTarget.SOURCE_OWNER.equals(owner)) {
            return List.of(new Member(GET_DOCUMENT, GET_DOCUMENT_DESCRIPTOR));
        }
        if (DOCUMENT_OWNER.equals(owner)) {
            return List.of(new Member(GET_CURRENT_EDIT_MODE, GET_CURRENT_EDIT_MODE_DESCRIPTOR));
        }
        if (ModelInstanceCoalesceTarget.EDIT_MODE_OWNER.equals(owner)) {
            return List.of(
                    new Member(IS_EDITING, IS_EDITING_DESCRIPTOR),
                    new Member(GET_CURRENT_UNDO, GET_CURRENT_UNDO_DESCRIPTOR),
                    new Member(GET_DOC, GET_DOC_DESCRIPTOR));
        }
        if (UNDO_GROUP_OWNER.equals(owner)) {
            return List.of(new Member(GET_EDIT_COUNT, GET_EDIT_COUNT_DESCRIPTOR));
        }
        return List.of();
    }

    /**
     * One reviewed host member of the coalescer.
     *
     * @param name the exact member name
     * @param descriptor the exact JVM descriptor
     */
    public record Member(String name, String descriptor) {}
}
