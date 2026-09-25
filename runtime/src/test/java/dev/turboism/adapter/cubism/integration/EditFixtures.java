package dev.turboism.adapter.cubism.integration;

import dev.turboism.sdk.cubism.edit.EditAlphaBlend;
import dev.turboism.sdk.cubism.edit.EditColorBlend;
import dev.turboism.sdk.cubism.edit.EditLabelColor;
import dev.turboism.sdk.cubism.edit.EditLabelColorType;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditObjectSnapshot;
import dev.turboism.sdk.cubism.edit.EditParameterGroupNode;
import dev.turboism.sdk.cubism.edit.EditParameterNode;
import dev.turboism.sdk.cubism.edit.EditPartData;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Optional;

/** Canned engine values for the protocol-matrix tests. */
final class EditFixtures {

    static final DocumentId DOCUMENT = new DocumentId("doc-1");
    static final String MODEL_UID = "model-uid-1";

    static final ModelObjectId PART = new ModelObjectId("part-1");
    static final ModelObjectId MESH = new ModelObjectId("mesh-1");
    static final ModelObjectId WARP = new ModelObjectId("warp-1");
    static final ModelObjectId ROTATION = new ModelObjectId("rot-1");
    static final ModelObjectId GLUE = new ModelObjectId("glue-1");

    private EditFixtures() {
    }

    /** Root parameter group with one parameter child. */
    static EditParameterGroupNode parameterTree() {
        return new EditParameterGroupNode(
            new ParameterGroupId("pg-root"), "Root",
            EditLabelColor.of(EditLabelColorType.UNDEFINED),
            List.of(new EditParameterNode(
                new ParameterId("ParamAngle"), "Angle", -30.0, 0.0, 30.0, false, false)));
    }

    /** Parts-palette root containing a part with one mesh child. */
    static EditObjectNode objectTree() {
        return new EditObjectNode("PartsRoot", new ModelObjectId("parts-root"),
            EditObjectKind.PART, List.of(
                new EditObjectNode("PartA", PART, EditObjectKind.PART, List.of(
                    new EditObjectNode("MeshA", MESH, EditObjectKind.ART_MESH, List.of()))),
                new EditObjectNode("GlueA", GLUE, EditObjectKind.GLUE, List.of())));
    }

    /** Deformer root containing one warp and one rotation deformer. */
    static EditObjectNode deformerTree() {
        return new EditObjectNode("DeformerRoot", new ModelObjectId("def-root"),
            EditObjectKind.WARP_DEFORMER, List.of(
                new EditObjectNode("WarpA", WARP, EditObjectKind.WARP_DEFORMER, List.of()),
                new EditObjectNode("RotA", ROTATION, EditObjectKind.ROTATION_DEFORMER,
                    List.of())));
    }

    /** A part snapshot for {@code GetObject}. */
    static EditObjectSnapshot snapshot() {
        return new EditObjectSnapshot(PART, new EditPartData(
            "PartA", Optional.empty(), false, false, false, List.of(), false, 500, 1.0,
            Optional.empty(), Optional.empty(), EditColorBlend.NORMAL, EditAlphaBlend.OVER,
            EditLabelColor.of(EditLabelColorType.UNDEFINED)));
    }
}
