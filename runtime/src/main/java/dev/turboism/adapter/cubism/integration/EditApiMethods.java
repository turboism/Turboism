package dev.turboism.adapter.cubism.integration;

import java.util.Set;

/**
 * The editing-API method-name recognition set.
 *
 * <p>Exactly the 36 routes documented for the 5.4 editing protocol in
 * {@code host-evidence/edit-api-54alpha2/api-internal-routes.json}, including
 * {@code GetIsEditApproval}. Anything else — every native 1.0.x method, typos, unknown names —
 * is not recognized here and must fall through to the host dispatcher untouched.</p>
 */
public final class EditApiMethods {

    public static final Set<String> ALL = Set.of(
        "AddParameter",
        "AddParameterGroup",
        "AddParameterKey",
        "AddPart",
        "AddRotationDeformer",
        "AddSelectedObjects",
        "AddWarpDeformer",
        "ClearSelectedObjects",
        "DeleteObject",
        "DeleteParameter",
        "DeleteParameterGroup",
        "DeleteParameterKey",
        "EditArtMesh",
        "EditBegin",
        "EditEnd",
        "EditGlue",
        "EditParameter",
        "EditParameterGroup",
        "EditPart",
        "EditRotationDeformer",
        "EditSendLog",
        "EditSendProgress",
        "EditWarpDeformer",
        "GetDeformerStructure",
        "GetIsEditApproval",
        "GetObject",
        "GetObjectsByParameterKeys",
        "GetParameterKeys",
        "GetParameterStructure",
        "GetPartStructure",
        "GetSelectedObjects",
        "MoveObjectOnPartsPalette",
        "MoveParameter",
        "MoveParameterGroup",
        "MoveParameterKey",
        "NotifyUndoCancel"
    );

    private EditApiMethods() {
    }

    /** {@return whether {@code method} is one of the 36 editing-API method names} */
    public static boolean isEditApiMethod(final String method) {
        return method != null && ALL.contains(method);
    }
}
