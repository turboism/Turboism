package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.turboism.sdk.cubism.edit.EditAlphaBlend;
import dev.turboism.sdk.cubism.edit.EditArtMeshData;
import dev.turboism.sdk.cubism.edit.EditColorBlend;
import dev.turboism.sdk.cubism.edit.EditDeformerAttachMode;
import dev.turboism.sdk.cubism.edit.EditGlueData;
import dev.turboism.sdk.cubism.edit.EditLabelColor;
import dev.turboism.sdk.cubism.edit.EditLabelColorType;
import dev.turboism.sdk.cubism.edit.EditObjectData;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditObjectSnapshot;
import dev.turboism.sdk.cubism.edit.EditParameterGroupNode;
import dev.turboism.sdk.cubism.edit.EditParameterNode;
import dev.turboism.sdk.cubism.edit.EditParameterStructureEntry;
import dev.turboism.sdk.cubism.edit.EditPartData;
import dev.turboism.sdk.cubism.edit.EditRectangle;
import dev.turboism.sdk.cubism.edit.EditRotationDeformerData;
import dev.turboism.sdk.cubism.edit.EditWarpDeformerData;
import dev.turboism.sdk.cubism.edit.ParameterKeyOps;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.Point2;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Serializers between the Phase-046 engine records and the official 1.1.0 wire shapes.
 *
 * <p>Response fragments are built as Jackson trees and emitted as raw {@code Data} JSON, the
 * same concatenation recipe the host responder uses. Wire names follow the official tables:
 * object types {@code "Part"/"ArtMesh"/"WarpDeformer"/"RotationDeformer"/"ArtPath"/"Glue"},
 * label colors {@code "Undefined".."Custom"}, blends {@code "Normal"/"AddGlow"/…/"Add_5.2"},
 * alpha blends {@code "Over".."Disjoint"}, attach modes {@code "AsParent"/"AsChild"}.</p>
 */
final class EditApiWire {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private EditApiWire() {
    }

    /** {@code {}} — the empty body used by the log/progress acknowledgements. */
    static ObjectNode empty() {
        return object();
    }

    /** {@code { "Result" : bool }} — the standard success/failure body. */
    static ObjectNode result(final boolean value) {
        return object().put("Result", value);
    }

    /** {@code { "Accepted" : bool }} — the {@code NotifyUndoCancel} acknowledgement. */
    static ObjectNode accepted(final boolean value) {
        return object().put("Accepted", value);
    }

    /** {@code { "Ids" : [..] }} — object-id list bodies. */
    static ObjectNode ids(final List<ModelObjectId> ids) {
        final ArrayNode array = JSON.arrayNode();
        for (final ModelObjectId id : ids) {
            array.add(id.value());
        }
        return object().set("Ids", array);
    }

    /** {@code { "Parameters" : [{ "Id", "KeyValues" : [..] }] }} — GetParameterKeys body. */
    static ObjectNode parameterKeys(final List<ParameterKeyOps.ParameterKeyValues> rows) {
        final ArrayNode parameters = JSON.arrayNode();
        for (final ParameterKeyOps.ParameterKeyValues row : rows) {
            final ArrayNode keys = JSON.arrayNode();
            row.keyValues().forEach(keys::add);
            parameters.add(object().put("Id", row.parameter().value()).set("KeyValues", keys));
        }
        return object().set("Parameters", parameters);
    }

    /** {@code { "ParameterStructure" : {Name, Id, Entries} }} — parameter-tree body. */
    static ObjectNode parameterStructure(final EditParameterGroupNode root) {
        final ObjectNode structure = object()
            .put("Name", root.name())
            .put("Id", root.id().value());
        final ArrayNode entries = JSON.arrayNode();
        for (final EditParameterStructureEntry child : root.children()) {
            entries.add(parameterEntry(child));
        }
        structure.set("Entries", entries);
        return object().set("ParameterStructure", structure);
    }

    private static ObjectNode parameterEntry(final EditParameterStructureEntry entry) {
        if (entry instanceof EditParameterNode parameter) {
            return object()
                .put("EntryType", "Parameter")
                .put("Name", parameter.name())
                .put("Id", parameter.id().value())
                .put("Min", parameter.min())
                .put("Default", parameter.defaultValue())
                .put("Max", parameter.max())
                .put("IsRepeat", parameter.repeat())
                .put("IsBlendShape", parameter.blendShape());
        }
        final EditParameterGroupNode group = (EditParameterGroupNode) entry;
        final ObjectNode node = object()
            .put("EntryType", "ParameterGroup")
            .put("Name", group.name())
            .put("Id", group.id().value());
        putLabelColor(node, group.labelColor());
        final ArrayNode children = JSON.arrayNode();
        for (final EditParameterStructureEntry child : group.children()) {
            children.add(parameterEntry(child));
        }
        node.set("Entries", children);
        return node;
    }

    /** {@code { "PartStructure" : {Name, Id, Type, Children} }} — parts-palette tree body. */
    static ObjectNode partStructure(final EditObjectNode root) {
        return object().set("PartStructure", objectNode(root));
    }

    /** {@code { "DeformerStructure" : {..} }} — deformer-tree body. */
    static ObjectNode deformerStructure(final EditObjectNode root) {
        return object().set("DeformerStructure", objectNode(root));
    }

    private static ObjectNode objectNode(final EditObjectNode node) {
        final ObjectNode json = object()
            .put("Name", node.name())
            .put("Id", node.id().value())
            .put("Type", objectKind(node.kind()));
        final ArrayNode children = JSON.arrayNode();
        for (final EditObjectNode child : node.children()) {
            children.add(objectNode(child));
        }
        return json.set("Children", children);
    }

    /**
     * {@code { "Result" : bool, "Type" : kind, "Data" : {..} }} — the GetObject body.
     * {@code Result} is {@code false} when the object is absent.
     */
    static ObjectNode objectSnapshot(final EditObjectSnapshot snapshot) {
        final ObjectNode body = result(true)
            .put("Type", objectKind(snapshot.data().kind()));
        return body.set("Data", objectData(snapshot.object().value(), snapshot.data()));
    }

    private static ObjectNode objectData(final String id, final EditObjectData data) {
        final ObjectNode json = object()
            .put("Name", name(data))
            .put("Id", id);
        if (data instanceof EditPartData part) {
            part.parentId().ifPresent(parent -> json.put("ParentId", parent.value()));
            json.put("IsGrouped", part.grouped())
                .put("IsGuidImage", part.guidImage())
                .put("IsOffscreen", part.offscreen());
            putIdList(json, "ClippingIds", part.clippingIds());
            json.put("IsReverseMask", part.reverseMask())
                .put("DrawOrder", part.drawOrder())
                .put("Opacity", part.opacity());
            part.multiplyColor().ifPresent(v -> json.put("MultiplyColor", v));
            part.screenColor().ifPresent(v -> json.put("ScreenColor", v));
            json.put("ColorBlend", colorBlend(part.colorBlend()))
                .put("AlphaBlend", alphaBlend(part.alphaBlend()));
            putLabelColor(json, part.labelColor());
        } else if (data instanceof EditArtMeshData mesh) {
            mesh.parentId().ifPresent(parent -> json.put("ParentId", parent.value()));
            mesh.parentDeformerId().ifPresent(parent -> json.put("ParentDeformerId", parent.value()));
            putIdList(json, "ClippingIds", mesh.clippingIds());
            json.put("IsReverseMask", mesh.reverseMask())
                .put("DrawOrder", mesh.drawOrder())
                .put("Opacity", mesh.opacity());
            mesh.multiplyColor().ifPresent(v -> json.put("MultiplyColor", v));
            mesh.screenColor().ifPresent(v -> json.put("ScreenColor", v));
            json.put("ColorBlend", colorBlend(mesh.colorBlend()))
                .put("AlphaBlend", alphaBlend(mesh.alphaBlend()))
                .put("IsCulling", mesh.culling())
                .put("Vertices", mesh.vertexCount());
            putLabelColor(json, mesh.labelColor());
        } else if (data instanceof EditRotationDeformerData rotation) {
            rotation.parentId().ifPresent(parent -> json.put("ParentId", parent.value()));
            rotation.parentDeformerId()
                .ifPresent(parent -> json.put("ParentDeformerId", parent.value()));
            json.put("Angle", rotation.angle())
                .put("BaseAngle", rotation.baseAngle())
                .put("Scale", rotation.scale())
                .put("Opacity", rotation.opacity());
            rotation.multiplyColor().ifPresent(v -> json.put("MultiplyColor", v));
            rotation.screenColor().ifPresent(v -> json.put("ScreenColor", v));
            json.set("Position", point(rotation.position()));
            putLabelColor(json, rotation.labelColor());
        } else if (data instanceof EditWarpDeformerData warp) {
            warp.parentId().ifPresent(parent -> json.put("ParentId", parent.value()));
            warp.parentDeformerId()
                .ifPresent(parent -> json.put("ParentDeformerId", parent.value()));
            json.put("Opacity", warp.opacity());
            warp.multiplyColor().ifPresent(v -> json.put("MultiplyColor", v));
            warp.screenColor().ifPresent(v -> json.put("ScreenColor", v));
            json.put("WarpDivH", warp.warpDivH())
                .put("WarpDivV", warp.warpDivV());
            warp.bezierDivH().ifPresent(v -> json.put("BezierDivH", v));
            warp.bezierDivV().ifPresent(v -> json.put("BezierDivV", v));
            json.set("Rectangle", rectangle(warp.rectangle()));
            putLabelColor(json, warp.labelColor());
        } else if (data instanceof EditGlueData glue) {
            glue.parentId().ifPresent(parent -> json.put("ParentId", parent.value()));
            json.put("Intensity", glue.intensity());
            putLabelColor(json, glue.labelColor());
        }
        return json;
    }

    private static String name(final EditObjectData data) {
        if (data instanceof EditPartData part) {
            return part.name();
        }
        if (data instanceof EditArtMeshData mesh) {
            return mesh.name();
        }
        if (data instanceof EditRotationDeformerData rotation) {
            return rotation.name();
        }
        if (data instanceof EditWarpDeformerData warp) {
            return warp.name();
        }
        return ((EditGlueData) data).name();
    }

    private static void putIdList(
        final ObjectNode json, final String field, final List<ModelObjectId> ids
    ) {
        final ArrayNode array = JSON.arrayNode();
        for (final ModelObjectId id : ids) {
            array.add(id.value());
        }
        json.set(field, array);
    }

    private static void putLabelColor(final ObjectNode json, final EditLabelColor color) {
        json.put("LabelColorType", labelColorType(color.type()));
        color.customColor().ifPresent(v -> json.put("LabelCustomColor", v));
    }

    private static ObjectNode point(final Point2 point) {
        return object().put("X", point.x()).put("Y", point.y());
    }

    private static ObjectNode rectangle(final EditRectangle rectangle) {
        final ObjectNode json = object();
        json.set("TopLeft", point(rectangle.topLeft()));
        json.set("TopRight", point(rectangle.topRight()));
        json.set("BottomLeft", point(rectangle.bottomLeft()));
        json.set("BottomRight", point(rectangle.bottomRight()));
        return json;
    }

    private static ObjectNode object() {
        return JSON.objectNode();
    }

    // ------------------------------------------------------------------
    // enum wire names
    // ------------------------------------------------------------------

    static String objectKind(final EditObjectKind kind) {
        return switch (kind) {
            case PART -> "Part";
            case ART_MESH -> "ArtMesh";
            case WARP_DEFORMER -> "WarpDeformer";
            case ROTATION_DEFORMER -> "RotationDeformer";
            case ART_PATH -> "ArtPath";
            case GLUE -> "Glue";
        };
    }

    private static final Map<EditLabelColorType, String> LABEL_COLOR_TYPES = Map.of(
        EditLabelColorType.UNDEFINED, "Undefined",
        EditLabelColorType.RED, "Red",
        EditLabelColorType.ORANGE, "Orange",
        EditLabelColorType.YELLOW, "Yellow",
        EditLabelColorType.GREEN, "Green",
        EditLabelColorType.BLUE, "Blue",
        EditLabelColorType.PURPLE, "Purple",
        EditLabelColorType.GRAY, "Gray",
        EditLabelColorType.CUSTOM, "Custom");

    private static final Map<EditColorBlend, String> COLOR_BLENDS = Map.ofEntries(
        Map.entry(EditColorBlend.NORMAL, "Normal"),
        Map.entry(EditColorBlend.ADD, "Add"),
        Map.entry(EditColorBlend.ADD_GLOW, "AddGlow"),
        Map.entry(EditColorBlend.DARKEN, "Darken"),
        Map.entry(EditColorBlend.MULTIPLY, "Multiply"),
        Map.entry(EditColorBlend.COLOR_BURN, "ColorBurn"),
        Map.entry(EditColorBlend.LINEAR_BURN, "LinearBurn"),
        Map.entry(EditColorBlend.LIGHTEN, "Lighten"),
        Map.entry(EditColorBlend.SCREEN, "Screen"),
        Map.entry(EditColorBlend.COLOR_DODGE, "ColorDodge"),
        Map.entry(EditColorBlend.OVERLAY, "Overlay"),
        Map.entry(EditColorBlend.SOFT_LIGHT, "SoftLight"),
        Map.entry(EditColorBlend.HARD_LIGHT, "HardLight"),
        Map.entry(EditColorBlend.LINEAR_LIGHT, "LinearLight"),
        Map.entry(EditColorBlend.HUE, "Hue"),
        Map.entry(EditColorBlend.COLOR, "Color"),
        Map.entry(EditColorBlend.ADD_5_2, "Add_5.2"),
        Map.entry(EditColorBlend.MULTIPLY_5_2, "Multiply_5.2"));

    private static final Map<EditAlphaBlend, String> ALPHA_BLENDS = Map.of(
        EditAlphaBlend.OVER, "Over",
        EditAlphaBlend.ATOP, "Atop",
        EditAlphaBlend.OUT, "Out",
        EditAlphaBlend.CONJOINT, "Conjoint",
        EditAlphaBlend.DISJOINT, "Disjoint");

    static String labelColorType(final EditLabelColorType type) {
        return LABEL_COLOR_TYPES.get(type);
    }

    static String colorBlend(final EditColorBlend blend) {
        return COLOR_BLENDS.get(blend);
    }

    static String alphaBlend(final EditAlphaBlend blend) {
        return ALPHA_BLENDS.get(blend);
    }

    /**
     * Parses an enum wire name case-insensitively against {@code names}.
     *
     * @return the enum constant, or empty for absent/unknown names
     */
    static <E extends Enum<E>> Optional<E> parseWireName(
        final Map<E, String> names,
        final Class<E> type,
        final String wire
    ) {
        if (wire == null) {
            return Optional.empty();
        }
        for (final E constant : type.getEnumConstants()) {
            final String name = names.get(constant);
            if (name != null && name.equalsIgnoreCase(wire)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    static Map<EditLabelColorType, String> labelColorTypeNames() {
        return LABEL_COLOR_TYPES;
    }

    static Map<EditColorBlend, String> colorBlendNames() {
        return COLOR_BLENDS;
    }

    static Map<EditAlphaBlend, String> alphaBlendNames() {
        return ALPHA_BLENDS;
    }

    static Optional<EditDeformerAttachMode> parseAttachMode(final String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        final String normalized = wire.replace("_", "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "asparent" -> Optional.of(EditDeformerAttachMode.AS_PARENT);
            case "aschild" -> Optional.of(EditDeformerAttachMode.AS_CHILD);
            default -> Optional.empty();
        };
    }

    /** Parses a {@code LabelColorType}/{@code LabelCustomColor} pair. */
    static Optional<EditLabelColor> parseLabelColor(
        final EditApiPayload payload
    ) throws EditApiFailure {
        final Optional<String> typeText = payload.optionalString("LabelColorType");
        final Optional<String> custom = payload.optionalString("LabelCustomColor");
        if (typeText.isEmpty()) {
            if (custom.isPresent()) {
                return Optional.of(EditLabelColor.custom(custom.orElseThrow()));
            }
            return Optional.empty();
        }
        final Optional<EditLabelColorType> type = parseWireName(
            LABEL_COLOR_TYPES, EditLabelColorType.class, typeText.orElseThrow());
        if (type.isEmpty()) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        try {
            return Optional.of(type.orElseThrow() == EditLabelColorType.CUSTOM
                ? EditLabelColor.custom(custom.orElse(""))
                : EditLabelColor.of(type.orElseThrow()));
        } catch (IllegalArgumentException malformed) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
    }

    /** Parses one enum-valued optional field; unknown names fail {@code InvalidData}. */
    static <E extends Enum<E>> Optional<E> optionalEnum(
        final EditApiPayload payload,
        final String field,
        final Map<E, String> names,
        final Class<E> type
    ) throws EditApiFailure {
        final Optional<String> text = payload.optionalString(field);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        final Optional<E> parsed = parseWireName(names, type, text.orElseThrow());
        if (parsed.isEmpty()) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        return parsed;
    }
}
