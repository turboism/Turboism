package dev.turboism.adapter.cubism.edit;

import dev.turboism.mapping.verification.selector.EditorEditParameterStructureSelectorContract;
import dev.turboism.sdk.cubism.edit.EditLabelColor;
import dev.turboism.sdk.cubism.edit.EditParameterGroupNode;
import dev.turboism.sdk.cubism.edit.EditParameterNode;
import dev.turboism.sdk.cubism.edit.EditParameterStructureEntry;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.ParameterStructureOps;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parameter-structure family routed through the session's verified member surface
 * (spec 046, T3): {@code GetParameterStructure}, {@code AddParameter},
 * {@code AddParameterGroup}, {@code EditParameter}, {@code EditParameterGroup},
 * {@code DeleteParameter}, {@code DeleteParameterGroup}, {@code MoveParameter}, and
 * {@code MoveParameterGroup}.
 *
 * <p>Write orchestrations mirror the verified paths in {@code EditorParameterStructureAccess}
 * and the parameter-definition write in {@code EditorBackedCubismModelAccess}: creates register
 * the handler-returned undo payload on the session edit token, renames capture a simple-undo,
 * and every write finishes with the refresh envelope.</p>
 *
 * <p>{@code EditParameterGroup.newId} rides on the {@code parameter-group.set-id} row, which is
 * declared but absent from every verification record — requests carrying it fail closed.
 * {@code MoveParameterGroup} stays fail-closed as well: {@code CParameterGroup.remove} requires
 * a {@code CParameterGroupEntryGuid} and no verified member reads one from a group child.</p>
 */
final class SessionParameterStructureOps implements ParameterStructureOps {

    private final EditSessionOps ops;

    SessionParameterStructureOps(final EditSessionOps ops) {
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    @Override
    public EditParameterGroupNode parameterStructure() throws EditSessionException {
        return ops.dispatch("GetParameterStructure", access -> {
            ops.require(
                access,
                EditorEditParameterStructureSelectorContract
                    .GET_PARAMETER_STRUCTURE_CAPABILITY_ID,
                EditorEditParameterStructureSelectorContract
                    .GET_PARAMETER_STRUCTURE_REQUIRED_ALIASES,
                "GetParameterStructure");
            return groupNode(access, ops.rootGroup(access));
        });
    }

    @Override
    public boolean addParameter(final AddParameter request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("AddParameter", access -> {
            ops.require(
                access,
                EditorEditParameterStructureSelectorContract.ADD_PARAMETER_CAPABILITY_ID,
                EditorEditParameterStructureSelectorContract.ADD_PARAMETER_REQUIRED_ALIASES,
                "AddParameter");
            String idValue = request.id().map(ParameterId::value).orElse(null);
            if (idValue == null) {
                idValue = request.name().orElse(null);
            }
            if (idValue == null) {
                throw new EditUnavailableException(
                    "cubism.edit.op-unverified",
                    "AddParameter without Id or Name is not verified on this Cubism host");
            }
            final Object hostId =
                access.construct("cubism.editor-model.parameter-id.create", idValue);
            final Object type = access.readStaticField(request.blendShape()
                ? "cubism.editor-model.parameter-source.type-morph-target"
                : "cubism.editor-model.parameter-source.type-normal");
            final Object hostSource = access.construct(
                "cubism.editor-model.parameter-source.create",
                hostId,
                request.name().orElse(idValue),
                Float.valueOf(request.min().orElse(0.0).floatValue()),
                Float.valueOf(request.max().orElse(1.0).floatValue()),
                Float.valueOf(request.defaultValue().orElse(0.0).floatValue()),
                "",
                null,
                type);
            final Object parent = request.group()
                .map(group -> ops.requireParameterGroup(access, group))
                .orElseGet(() -> ops.rootGroup(access));
            final Object undo = access.invoke(
                "cubism.editor-model.parameter-group-handler.add-parameter-child",
                groupHandler(access, parent),
                hostSource,
                Integer.valueOf(ops.groupChildren(access, parent).size()));
            ops.addUndo(access, undo, "Turboism: Add Parameter");
            ops.finishWrite(access, true, true);
            return true;
        });
    }

    @Override
    public boolean addParameterGroup(final AddParameterGroup request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("AddParameterGroup", access -> {
            ops.require(
                access,
                EditorEditParameterStructureSelectorContract.ADD_PARAMETER_GROUP_CAPABILITY_ID,
                EditorEditParameterStructureSelectorContract
                    .ADD_PARAMETER_GROUP_REQUIRED_ALIASES,
                "AddParameterGroup");
            final String name = request.name()
                .orElseGet(() -> request.id().map(ParameterGroupId::value).orElse("Folder"));
            final Object hostGroupId = access.invokeStatic(
                "cubism.editor-model.model-handler.create-free-id-default",
                ops.modelHandler(access),
                access.construct("cubism.editor-model.parameter-group-id.create", name),
                null,
                Integer.valueOf(2),
                null);
            final Object guid =
                access.construct("cubism.editor-model.parameter-group-guid.create");
            final Object hostGroup = access.construct(
                "cubism.editor-model.parameter-group.create", name, guid, hostGroupId);
            access.invoke(
                "cubism.editor-model.parameter-group.set-folder-opened",
                hostGroup,
                Boolean.FALSE);
            final Object root = ops.rootGroup(access);
            final Object undo = access.invoke(
                "cubism.editor-model.parameter-group-handler.add-group-child",
                groupHandler(access, root),
                hostGroup,
                Integer.valueOf(ops.groupChildren(access, root).size()));
            ops.addUndo(access, undo, "Turboism: Add Parameter Group");
            ops.finishWrite(access, true, true);
            return true;
        });
    }

    @Override
    public boolean editParameter(final EditParameter request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("EditParameter", access -> {
            ops.require(
                access,
                EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_CAPABILITY_ID,
                EditorEditParameterStructureSelectorContract.EDIT_PARAMETER_REQUIRED_ALIASES,
                "EditParameter");
            updateDefinition(access, ops.requireParameterSource(access, request.id()), request);
            ops.finishWrite(access, true, true);
            return true;
        });
    }

    @Override
    public boolean editParameterGroup(final EditParameterGroup request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("EditParameterGroup", access -> {
            ops.require(
                access,
                EditorEditParameterStructureSelectorContract
                    .EDIT_PARAMETER_GROUP_CAPABILITY_ID,
                EditorEditParameterStructureSelectorContract
                    .EDIT_PARAMETER_GROUP_REQUIRED_ALIASES,
                "EditParameterGroup");
            final Object group = ops.requireParameterGroup(access, request.id());
            if (request.newId().isPresent()) {
                ops.require(
                    access,
                    EditorEditParameterStructureSelectorContract
                        .EDIT_PARAMETER_GROUP_CAPABILITY_ID,
                    EditorEditParameterStructureSelectorContract
                        .EDIT_PARAMETER_GROUP_NEW_ID_ALIASES,
                    "EditParameterGroup(newId)");
            }
            if (request.name().isPresent()) {
                final String name = request.name().get();
                final Object current =
                    access.invoke("cubism.editor-model.parameter-group.name", group);
                if (!(current instanceof String existing) || !existing.equals(name)) {
                    final Object undo = access.construct(
                        "cubism.editor-model.simple-undo.create",
                        "Turboism: Rename Parameter Folder",
                        group,
                        null);
                    access.invoke("cubism.editor-model.parameter-group.set-name", group, name);
                    ops.addUndo(access, undo, "Turboism: Rename Parameter Folder");
                }
            }
            if (request.labelColor().isPresent()) {
                final EditLabelColor labelColor = request.labelColor().get();
                ops.writeLabelColor(
                    access,
                    access.invoke("cubism.editor-model.parameter-group.label-color", group),
                    labelColor);
            }
            ops.finishWrite(access, true, true);
            return true;
        });
    }

    @Override
    public boolean deleteParameter(final DeleteParameter request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("DeleteParameter", access -> {
            ops.require(
                access,
                EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_CAPABILITY_ID,
                EditorEditParameterStructureSelectorContract.DELETE_PARAMETER_REQUIRED_ALIASES,
                "DeleteParameter");
            final Object source = ops.requireParameterSource(access, request.id());
            final Object guid =
                access.invoke("cubism.editor-model.parameter-source.guid", source);
            final Object parameterSet =
                access.invoke("cubism.editor-model.model.parameter-set", access.model());
            final Object undo = access.invoke(
                "cubism.editor-model.model-handler.remove-parameter",
                ops.modelHandler(access),
                guid,
                parameterSet,
                Boolean.TRUE);
            ops.addUndo(access, undo, "Turboism: Delete Parameter");
            ops.finishWrite(access, true, true);
            return true;
        });
    }

    @Override
    public boolean deleteParameterGroup(final DeleteParameterGroup request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("DeleteParameterGroup", access -> {
            ops.require(
                access,
                EditorEditParameterStructureSelectorContract
                    .DELETE_PARAMETER_GROUP_CAPABILITY_ID,
                EditorEditParameterStructureSelectorContract
                    .DELETE_PARAMETER_GROUP_REQUIRED_ALIASES,
                "DeleteParameterGroup");
            final Object group = ops.requireParameterGroup(access, request.id());
            final Object parent =
                access.invoke("cubism.editor-model.parameter-group.parent", group);
            if (parent == null) {
                throw new IllegalArgumentException(
                    "The root parameter folder cannot be deleted.");
            }
            final Object parameterSet =
                access.invoke("cubism.editor-model.model.parameter-set", access.model());
            final Object undo = access.invoke(
                "cubism.editor-model.parameter-group-handler.remove-descendant",
                groupHandler(access, parent),
                group,
                parameterSet,
                Boolean.TRUE,
                Boolean.TRUE);
            ops.addUndo(access, undo, "Turboism: Delete Parameter Group");
            ops.finishWrite(access, true, true);
            return true;
        });
    }

    @Override
    public boolean moveParameter(final MoveParameter request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("MoveParameter", access -> {
            ops.require(
                access,
                EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_CAPABILITY_ID,
                EditorEditParameterStructureSelectorContract.MOVE_PARAMETER_REQUIRED_ALIASES,
                "MoveParameter");
            final Object current = ops.requireParameterSource(access, request.id());
            final Object target = ops.requireParameterGroup(access, request.group());
            final int index = request.insertIndex()
                .orElseGet(() -> ops.groupChildren(access, target).size());
            final Object undo = access.invoke(
                "cubism.editor-model.model-handler.move-parameter",
                ops.modelHandler(access),
                target,
                current,
                Integer.valueOf(index));
            ops.addUndo(access, undo, "Turboism: Move Parameter");
            ops.finishWrite(access, true, true);
            return true;
        });
    }

    @Override
    public boolean moveParameterGroup(final MoveParameterGroup request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        // CParameterGroup.remove requires a CParameterGroupEntryGuid and no verified member
        // reads one from a group child; the operation stays fail-closed until the record
        // admits a guid reader.
        throw new EditUnavailableException(
            "cubism.edit.op-unverified",
            "MoveParameterGroup is unavailable: no verified member reads a parameter-group "
                + "entry guid required by parameter-group.remove");
    }

    private void updateDefinition(
        final EditSessionOpsAccess access,
        final Object source,
        final EditParameter request
    ) {
        final Object app = ops.app(access);
        final Object mainFrame =
            access.invoke("cubism.editor-model.app-controller.main-frame", app);
        final Object palette = access.invoke(
            "cubism.editor-model.main-frame.parameter-palette", mainFrame);
        final Object paletteView =
            access.invoke("cubism.editor-model.parameter-palette.view", palette);
        final Object operation = access.invoke(
            "cubism.editor-model.parameter-palette-view.operation", paletteView);
        final Object propertyEditor = access.invokeStatic(
            "cubism.editor-model.parameter-operation.property-editor", operation);
        final Object validator = access.invokeStatic(
            "cubism.editor-model.parameter-operation.validator", operation);

        final String currentId = ops.parameterSourceId(access, source);
        final String nextId = request.newId().map(ParameterId::value).orElse(currentId);
        if (!nextId.equals(currentId)
            && !ops.flag(access.invoke(
                    "cubism.editor-model.parameter-validator.valid-id", validator, nextId),
                "Editor parameter ID validation is unavailable.")) {
            throw new IllegalArgumentException(
                "Editor rejected the parameter ID: " + nextId);
        }

        final float minimum = request.min()
            .orElseGet(() -> ops.number(
                access.invoke("cubism.editor-model.parameter-source.minimum", source),
                "Editor parameter minimum"))
            .floatValue();
        final float maximum = request.max()
            .orElseGet(() -> ops.number(
                access.invoke("cubism.editor-model.parameter-source.maximum", source),
                "Editor parameter maximum"))
            .floatValue();
        final float defaultValue = request.defaultValue()
            .orElseGet(() -> ops.number(
                access.invoke("cubism.editor-model.parameter-source.default", source),
                "Editor parameter default"))
            .floatValue();
        final String name = request.name()
            .orElseGet(() -> ops.text(
                access.invoke("cubism.editor-model.parameter-source.name", source),
                "Editor parameter name"));
        final boolean repeat = request.repeat()
            .orElseGet(() -> ops.flag(
                access.invoke("cubism.editor-model.parameter-source.repeat", source),
                "Editor parameter repeat flag is invalid."));
        final boolean morphTarget = ops.flag(
            access.invoke("cubism.editor-model.parameter-source.morph-target", source),
            "Editor parameter type is invalid.");

        if (request.min().isPresent() || request.max().isPresent()) {
            final Object parameterGuid =
                access.invoke("cubism.editor-model.parameter-source.guid", source);
            if (ops.flag(access.invoke(
                    "cubism.editor-model.parameter-validator.keys-outside-range",
                    validator,
                    parameterGuid,
                    Float.valueOf(minimum),
                    Float.valueOf(maximum)),
                    "Editor parameter range validation is unavailable.")) {
                throw new IllegalStateException(
                    "The requested range would exclude existing parameter keys.");
            }
        }
        if (request.repeat().isPresent()
            && !ops.flag(access.invoke(
                    "cubism.editor-model.parameter-validator.allow-repeat",
                    validator,
                    source,
                    Boolean.valueOf(repeat)),
                    "Editor parameter repeat validation is unavailable.")) {
            throw new IllegalStateException(
                "The Editor rejected the requested repeat setting.");
        }
        if (request.defaultValue().isPresent()
            && ops.flag(access.invoke(
                    "cubism.editor-model.parameter-validator.default-change-affects-morph-target",
                    validator,
                    source,
                    Float.valueOf(defaultValue)),
                    "Editor morph-target default validation is unavailable.")) {
            throw new IllegalStateException(
                "Changing this default would affect existing morph-target keyforms.");
        }

        final Object refreshCallback = access.construct(
            "cubism.editor-model.parameter-refresh-callback.create", operation);
        final Object updated = access.invoke(
            "cubism.editor-model.parameter-property-editor.update-definition",
            propertyEditor,
            source,
            name,
            Float.valueOf(minimum),
            Float.valueOf(maximum),
            Float.valueOf(defaultValue),
            nextId,
            Boolean.valueOf(repeat),
            Boolean.FALSE,
            Boolean.valueOf(morphTarget),
            refreshCallback);
        if (!ops.flag(updated, "Editor parameter definition update is unavailable.")) {
            throw new IllegalStateException(
                "The Editor rejected the parameter definition update.");
        }
        access.invoke(
            "cubism.editor-model.parameter-property-editor.rebuild-keep-value",
            propertyEditor);
        access.invoke(
            "cubism.editor-model.parameter-operation.refresh", operation, Boolean.TRUE);
    }

    private Object groupHandler(final EditSessionOpsAccess access, final Object group) {
        final Object handler =
            access.invoke("cubism.editor-model.parameter-group.handler", group);
        if (!access.isInstance("cubism.editor-model.parameter-group-handler.class", handler)) {
            throw ops.unavailable("Editor parameter group handler is unavailable.");
        }
        return handler;
    }

    private EditParameterGroupNode groupNode(
        final EditSessionOpsAccess access,
        final Object group
    ) {
        final ArrayList<EditParameterStructureEntry> children = new ArrayList<>();
        for (final Object child : ops.groupChildren(access, group)) {
            if (access.isInstance("cubism.editor-model.parameter-group.class", child)) {
                children.add(groupNode(access, child));
            } else if (access.isInstance("cubism.editor-model.parameter-source.class", child)) {
                children.add(parameterNode(access, child));
            }
        }
        return new EditParameterGroupNode(
            ops.groupId(access, group),
            ops.text(
                access.invoke("cubism.editor-model.parameter-group.name", group),
                "Editor parameter group name"),
            ops.readLabelColor(
                access,
                access.invoke("cubism.editor-model.parameter-group.label-color", group)),
            children);
    }

    private EditParameterNode parameterNode(
        final EditSessionOpsAccess access,
        final Object source
    ) {
        return new EditParameterNode(
            new ParameterId(ops.parameterSourceId(access, source)),
            ops.text(
                access.invoke("cubism.editor-model.parameter-source.name", source),
                "Editor parameter name"),
            ops.number(
                access.invoke("cubism.editor-model.parameter-source.minimum", source),
                "Editor parameter minimum"),
            ops.number(
                access.invoke("cubism.editor-model.parameter-source.default", source),
                "Editor parameter default"),
            ops.number(
                access.invoke("cubism.editor-model.parameter-source.maximum", source),
                "Editor parameter maximum"),
            ops.flag(
                access.invoke("cubism.editor-model.parameter-source.repeat", source),
                "Editor parameter repeat flag is invalid."),
            ops.flag(
                access.invoke("cubism.editor-model.parameter-source.morph-target", source),
                "Editor parameter type is invalid."));
    }
}
