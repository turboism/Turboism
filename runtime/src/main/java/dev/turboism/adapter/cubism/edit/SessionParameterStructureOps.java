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
 * <p>Write orchestrations keep every mutation inside the session's own edit bracket: creates
 * register the handler-returned undo payload on the session edit token, renames and
 * {@code EditParameter} definition writes capture a {@code SimpleUndo} snapshot before
 * mutating through the verified {@code CParameterSource} setters, and every write finishes
 * with the refresh envelope.</p>
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
            if (request.newId().isPresent()) {
                ops.require(
                    access,
                    EditorEditParameterStructureSelectorContract
                        .EDIT_PARAMETER_CAPABILITY_ID,
                    EditorEditParameterStructureSelectorContract
                        .EDIT_PARAMETER_NEW_ID_ALIASES,
                    "EditParameter(newId)");
            }
            updateParameter(access, ops.requireParameterSource(access, request.id()), request);
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

    /**
     * Applies one parameter-definition edit inside the session's own edit bracket: validate
     * against the host's parameter validator, snapshot the source into a {@code SimpleUndo}
     * before mutating, apply the verified {@code CParameterSource} setters, and register the
     * undoable on the session edit token. This replaces the former property-editor path
     * ({@code update-definition}), which committed its own history entry outside the session
     * {@code GroupUndo}.
     */
    private void updateParameter(
        final EditSessionOpsAccess access,
        final Object source,
        final EditParameter request
    ) {
        if (request.newId().isEmpty()
            && request.name().isEmpty()
            && request.min().isEmpty()
            && request.defaultValue().isEmpty()
            && request.max().isEmpty()
            && request.repeat().isEmpty()) {
            return;
        }
        final Object validator = parameterValidator(access);

        if (request.newId().isPresent()) {
            final String nextId = request.newId().get().value();
            if (!nextId.equals(ops.parameterSourceId(access, source))
                && !ops.flag(access.invoke(
                        "cubism.editor-model.parameter-validator.valid-id", validator, nextId),
                    "Editor parameter ID validation is unavailable.")) {
                throw new IllegalArgumentException(
                    "Editor rejected the parameter ID: " + nextId);
            }
        }
        if (request.min().isPresent() || request.max().isPresent()) {
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
                    request.repeat().get()),
                    "Editor parameter repeat validation is unavailable.")) {
            throw new IllegalStateException(
                "The Editor rejected the requested repeat setting.");
        }
        if (request.defaultValue().isPresent()
            && ops.flag(access.invoke(
                    "cubism.editor-model.parameter-validator.default-change-affects-morph-target",
                    validator,
                    source,
                    Float.valueOf(request.defaultValue().get().floatValue())),
                    "Editor morph-target default validation is unavailable.")) {
            throw new IllegalStateException(
                "Changing this default would affect existing morph-target keyforms.");
        }

        final Object undo = access.construct(
            "cubism.editor-model.simple-undo.create",
            "Turboism: Edit Parameter",
            source,
            null);
        request.name().ifPresent(name ->
            access.invoke("cubism.editor-model.parameter-source.set-name", source, name));
        request.min().ifPresent(minimum ->
            access.invoke(
                "cubism.editor-model.parameter-source.set-minimum",
                source,
                Float.valueOf(minimum.floatValue())));
        request.max().ifPresent(maximum ->
            access.invoke(
                "cubism.editor-model.parameter-source.set-maximum",
                source,
                Float.valueOf(maximum.floatValue())));
        request.defaultValue().ifPresent(defaultValue ->
            access.invoke(
                "cubism.editor-model.parameter-source.set-default",
                source,
                Float.valueOf(defaultValue.floatValue())));
        request.repeat().ifPresent(repeat ->
            access.invoke(
                "cubism.editor-model.parameter-source.set-repeat",
                source,
                repeat));
        request.newId()
            .map(ParameterId::value)
            .filter(nextId -> !nextId.equals(ops.parameterSourceId(access, source)))
            .ifPresent(nextId -> access.invoke(
                "cubism.editor-model.parameter-source.set-id",
                source,
                access.construct("cubism.editor-model.parameter-id.create", nextId)));
        ops.addUndo(access, undo, "Turboism: Edit Parameter");
    }

    /** The host parameter validator reached through the parameter palette's operation. */
    private Object parameterValidator(final EditSessionOpsAccess access) {
        final Object mainFrame = access.invoke(
            "cubism.editor-model.app-controller.main-frame", ops.app(access));
        final Object palette = access.invoke(
            "cubism.editor-model.main-frame.parameter-palette", mainFrame);
        final Object paletteView =
            access.invoke("cubism.editor-model.parameter-palette.view", palette);
        final Object operation = access.invoke(
            "cubism.editor-model.parameter-palette-view.operation", paletteView);
        return access.invokeStatic(
            "cubism.editor-model.parameter-operation.validator", operation);
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
