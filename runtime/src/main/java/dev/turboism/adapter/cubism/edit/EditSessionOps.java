package dev.turboism.adapter.cubism.edit;

import dev.turboism.sdk.cubism.edit.EditLabelColor;
import dev.turboism.sdk.cubism.edit.EditLabelColorType;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.PartId;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared orchestration engine for the admitted edit session's operation families (spec 046, T3).
 *
 * <p>Every operation runs on the host UI thread through {@link EditorEditSessionHost#dispatch},
 * opens a binding-scoped {@link EditSessionOpsAccess}, and passes the per-operation capability
 * gate — {@code authorizesFeature} over the operation's declared capability row and member set —
 * before any host member is touched. An unadmitted row or member fails closed with {@link
 * EditUnavailableException}; no operation ever falls back to unverified members.</p>
 *
 * <p>Writes run inside the session's own edit bracket: they register their undo payloads on the
 * session's edit token and finish with the verified refresh envelope (instance update, palette
 * refresh, dirty marking, canvas repaint) instead of opening nested edit modes.</p>
 */
final class EditSessionOps {

    private final RuntimeEditSession session;

    EditSessionOps(final RuntimeEditSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** Work one operation executes on the host thread with its bound member surface. */
    @FunctionalInterface
    interface OpsWork<T> {
        T run(EditSessionOpsAccess access) throws EditSessionException;
    }

    /**
     * Dispatches one operation to the host UI thread and supplies it a fresh binding-scoped
     * member surface — opening the accessor re-validates the session binding's staleness, so a
     * stale session can never reach a member.
     */
    <T> T dispatch(final String label, final OpsWork<T> work) throws EditSessionException {
        return session.dispatchToHost(label, () ->
            work.run(session.opsAccess()));
    }

    /**
     * The per-operation capability gate: the operation's declared capability row and every
     * member in {@code aliases} must be admitted by the bound verification record. Anything less
     * fails closed — the operation is reported unavailable with the operation label and the
     * exact host version in the diagnostic.
     */
    void require(
        final EditSessionOpsAccess access,
        final String capabilityId,
        final Set<String> aliases,
        final String label
    ) throws EditSessionException {
        if (!access.authorizesFeature(capabilityId, aliases)) {
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                label + " is not verified on this Cubism host (" + capabilityId + ")"
            );
        }
    }

    /** The session edit token writes register their undo payloads on. */
    Object editToken() {
        return session.editToken();
    }

    // ------------------------------------------------------------------
    // navigation
    // ------------------------------------------------------------------

    Object app(final EditSessionOpsAccess access) {
        return access.invokeStatic("cubism.editor-model.app-controller.instance");
    }

    Object updateManager(final EditSessionOpsAccess access) {
        return access.invoke(
            "cubism.editor-model.app-controller.update-manager", app(access));
    }

    Object completePack(final EditSessionOpsAccess access) {
        return access.invoke(
            "cubism.editor-model.app-controller.complete-pack", app(access));
    }

    Object modelHandler(final EditSessionOpsAccess access) {
        return access.invoke(
            "cubism.editor-model.model-source.handler", access.modelSource());
    }

    Object rootGroup(final EditSessionOpsAccess access) {
        final Object root = access.invoke(
            "cubism.editor-model.model-source.root-parameter-group", access.modelSource());
        if (!access.isInstance("cubism.editor-model.parameter-group.class", root)) {
            throw unavailable("Editor root parameter group is unavailable.");
        }
        return root;
    }

    // ------------------------------------------------------------------
    // primitive conversions
    // ------------------------------------------------------------------

    String text(final Object value, final String what) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw unavailable(what + " is invalid.");
        }
        return text;
    }

    String optionalText(final Object value, final String what) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw unavailable(what + " is invalid.");
        }
        return text;
    }

    double number(final Object value, final String what) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw unavailable(what + " is invalid.");
        }
        return number.doubleValue();
    }

    int integer(final Object value, final String what) {
        if (!(value instanceof Number number)) {
            throw unavailable(what + " is invalid.");
        }
        return number.intValue();
    }

    boolean flag(final Object value, final String what) {
        if (!(value instanceof Boolean flag)) {
            throw unavailable(what + " is invalid.");
        }
        return flag;
    }

    List<Object> list(final Object value, final String what) {
        if (!(value instanceof List<?> raw)) {
            throw unavailable(what + " is unavailable.");
        }
        final ArrayList<Object> copy = new ArrayList<>(raw.size());
        for (final Object element : raw) {
            copy.add(element);
        }
        return copy;
    }

    float[] floatArray(final Object value, final String what) {
        if (value instanceof float[] array) {
            return array;
        }
        if (value instanceof List<?> raw) {
            final float[] copy = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                copy[i] = (float) number(raw.get(i), what);
            }
            return copy;
        }
        throw unavailable(what + " is unavailable.");
    }

    int[] intArray(final Object value, final String what) {
        if (value instanceof int[] array) {
            return array;
        }
        if (value instanceof List<?> raw) {
            final int[] copy = new int[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                copy[i] = integer(raw.get(i), what);
            }
            return copy;
        }
        throw unavailable(what + " is unavailable.");
    }

    // ------------------------------------------------------------------
    // identity
    // ------------------------------------------------------------------

    String idValue(final EditSessionOpsAccess access, final Object hostId) {
        return text(access.invoke("cubism.editor-model.id.value", hostId), "Editor object id");
    }

    String partIdValue(final EditSessionOpsAccess access, final Object hostId) {
        return text(
            access.invoke("cubism.editor-model.part-id.value", hostId), "Editor part id");
    }

    String guidValue(final EditSessionOpsAccess access, final Object hostGuid) {
        return text(
            access.invoke("cubism.editor-model.guid.value", hostGuid), "Editor object guid");
    }

    /** The string id of any controllable object source (art mesh, deformer, glue, part). */
    String objectId(final EditSessionOpsAccess access, final Object source) {
        return idValue(
            access,
            access.invoke("cubism.editor-model.parameter-controllable-source.id", source));
    }

    /** The guid string of any controllable object source. */
    String sourceGuid(final EditSessionOpsAccess access, final Object source) {
        return guidValue(
            access,
            access.invoke("cubism.editor-model.parameter-controllable-source.guid", source));
    }

    String localName(final EditSessionOpsAccess access, final Object source) {
        return optionalText(
            access.invoke(
                "cubism.editor-model.parameter-controllable-source.local-name", source),
            "Editor object name");
    }

    // ------------------------------------------------------------------
    // object lookup
    // ------------------------------------------------------------------

    List<Object> allObjectSources(final EditSessionOpsAccess access) {
        return list(
            access.invoke("cubism.editor-model.model-source.all-objects", access.modelSource()),
            "Editor object enumeration");
    }

    List<Object> allDeformerSources(final EditSessionOpsAccess access) {
        return list(
            access.invoke("cubism.editor-model.model-source.all-deformers", access.modelSource()),
            "Editor deformer enumeration");
    }

    List<Object> allArtMeshSources(final EditSessionOpsAccess access) {
        return list(
            access.invoke("cubism.editor-model.model-source.all-art-meshes", access.modelSource()),
            "Editor art mesh enumeration");
    }

    List<Object> allGlueSources(final EditSessionOpsAccess access) {
        return list(
            access.invoke("cubism.editor-model.model-source.all-glues", access.modelSource()),
            "Editor glue enumeration");
    }

    List<Object> partSources(final EditSessionOpsAccess access) {
        return list(
            access.invoke("cubism.editor-model.model-source.parts", access.modelSource()),
            "Editor part enumeration");
    }

    List<Object> parameters(final EditSessionOpsAccess access) {
        final Object parameterSet = access.invoke(
            "cubism.editor-model.model.parameter-set", access.model());
        return list(
            access.invoke("cubism.editor-model.parameter-set.parameters", parameterSet),
            "Editor parameter enumeration");
    }

    /** The palette type of one object source; unknown kinds report {@link EditObjectKind#ART_PATH}. */
    EditObjectKind kindOf(final EditSessionOpsAccess access, final Object source) {
        if (access.isInstance("cubism.editor-model.part-source.class", source)) {
            return EditObjectKind.PART;
        }
        if (access.isInstance("cubism.editor-model.art-mesh-source.class", source)) {
            return EditObjectKind.ART_MESH;
        }
        if (access.isInstance("cubism.editor-model.warp-source.class", source)) {
            return EditObjectKind.WARP_DEFORMER;
        }
        if (access.isInstance("cubism.editor-model.rotation-source.class", source)) {
            return EditObjectKind.ROTATION_DEFORMER;
        }
        if (access.isInstance("cubism.editor-model.glue-source.class", source)) {
            return EditObjectKind.GLUE;
        }
        return EditObjectKind.ART_PATH;
    }

    /**
     * Resolves a {@link ModelObjectReference} to its object source inside the bound model,
     * verifying the palette kind matches the reference kind. Art paths and glue objects are not
     * addressable through {@link ModelObjectKind}; callers targeting them resolve by their
     * typed ids instead.
     */
    Object requireObjectSource(
        final EditSessionOpsAccess access,
        final ModelObjectReference reference
    ) {
        final Object found = findObjectSource(access, reference.id());
        if (found == null) {
            throw new NoSuchElementException(
                "Cubism object is absent: " + reference.id());
        }
        final EditObjectKind kind = kindOf(access, found);
        final EditObjectKind expected = switch (reference.kind()) {
            case PART -> EditObjectKind.PART;
            case ART_MESH -> EditObjectKind.ART_MESH;
            case WARP_DEFORMER -> EditObjectKind.WARP_DEFORMER;
            case ROTATION_DEFORMER -> EditObjectKind.ROTATION_DEFORMER;
        };
        if (kind != expected) {
            throw new IllegalArgumentException(
                "Cubism object " + reference.id() + " is a " + kind + ", not a " + expected);
        }
        return found;
    }

    Object requireObjectSourceById(
        final EditSessionOpsAccess access,
        final ModelObjectId id
    ) {
        final Object found = findObjectSource(access, id.value());
        if (found == null) {
            throw new NoSuchElementException("Cubism object is absent: " + id.value());
        }
        return found;
    }

    private Object findObjectSource(final EditSessionOpsAccess access, final String id) {
        for (final Object source : allObjectSources(access)) {
            if (objectId(access, source).equals(id)) {
                return source;
            }
        }
        return null;
    }

    Object requirePartSource(final EditSessionOpsAccess access, final PartId id) {
        for (final Object source : partSources(access)) {
            final String value = partIdValue(
                access, access.invoke("cubism.editor-model.part-source.id", source));
            if (value.equals(id.value())) {
                return source;
            }
        }
        throw new NoSuchElementException("Cubism part is absent: " + id.value());
    }

    Object requireDeformerSource(final EditSessionOpsAccess access, final DeformerId id) {
        for (final Object source : allDeformerSources(access)) {
            if (objectId(access, source).equals(id.value())) {
                return source;
            }
        }
        throw new NoSuchElementException("Cubism deformer is absent: " + id.value());
    }

    Object requireArtMeshSource(final EditSessionOpsAccess access, final ArtMeshId id) {
        for (final Object source : allArtMeshSources(access)) {
            if (objectId(access, source).equals(id.value())) {
                return source;
            }
        }
        throw new NoSuchElementException("Cubism art mesh is absent: " + id.value());
    }

    Object requireGlueSource(final EditSessionOpsAccess access, final GlueId id) {
        for (final Object source : allGlueSources(access)) {
            if (objectId(access, source).equals(id.value())) {
                return source;
            }
        }
        throw new NoSuchElementException("Cubism glue object is absent: " + id.value());
    }

    /** Resolves a parameter id to its {@code CParameterSource} via the model parameter set. */
    Object requireParameterSource(final EditSessionOpsAccess access, final ParameterId id) {
        for (final Object parameter : parameters(access)) {
            final Object source = access.invoke(
                "cubism.editor-model.parameter.source", parameter);
            if (source != null
                && idValue(
                        access,
                        access.invoke("cubism.editor-model.parameter-source.id", source))
                    .equals(id.value())) {
                return source;
            }
        }
        throw new NoSuchElementException("Cubism parameter is absent: " + id.value());
    }

    Object requireParameterGroup(
        final EditSessionOpsAccess access,
        final ParameterGroupId id
    ) {
        final Object found = findGroup(access, rootGroup(access), id);
        if (found == null) {
            throw new NoSuchElementException("Cubism parameter group is absent: " + id.value());
        }
        return found;
    }

    private Object findGroup(
        final EditSessionOpsAccess access,
        final Object group,
        final ParameterGroupId id
    ) {
        if (groupId(access, group).equals(id)) {
            return group;
        }
        for (final Object child : groupChildren(access, group)) {
            if (access.isInstance("cubism.editor-model.parameter-group.class", child)) {
                final Object found = findGroup(access, child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    List<Object> groupChildren(final EditSessionOpsAccess access, final Object group) {
        return list(
            access.invoke("cubism.editor-model.parameter-group.children", group),
            "Editor parameter group children");
    }

    ParameterGroupId groupId(final EditSessionOpsAccess access, final Object group) {
        return new ParameterGroupId(idValue(
            access, access.invoke("cubism.editor-model.parameter-group.id", group)));
    }

    String parameterSourceId(final EditSessionOpsAccess access, final Object parameterSource) {
        return idValue(
            access,
            access.invoke("cubism.editor-model.parameter-source.id", parameterSource));
    }

    String parameterSourceGuid(final EditSessionOpsAccess access, final Object parameterSource) {
        return guidValue(
            access,
            access.invoke("cubism.editor-model.parameter-source.guid", parameterSource));
    }

    // ------------------------------------------------------------------
    // keyform binding
    // ------------------------------------------------------------------

    Object keyformGrid(final EditSessionOpsAccess access, final Object objectSource) {
        final Object grid = access.invoke(
            "cubism.editor-model.parameter-controllable.keyform-grid", objectSource);
        if (grid == null) {
            throw unavailable("Editor keyform grid is unavailable.");
        }
        return grid;
    }

    /** The verified controllable handler of one object source (undo/reparent entry point). */
    Object controllableHandler(final EditSessionOpsAccess access, final Object source) {
        final Object handler = access.invoke(
            "cubism.editor-model.parameter-controllable-source.handler", source);
        if (!access.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
            throw unavailable("Editor object handler is unavailable.");
        }
        return handler;
    }

    /** Finds the keyform binding of one parameter guid on a grid; {@code null} when unbound. */
    Object findBinding(
        final EditSessionOpsAccess access,
        final Object grid,
        final Object parameterGuid
    ) {
        return access.invoke("cubism.editor-model.keyform-grid.find-binding", grid, parameterGuid);
    }

    Object parameterGuid(
        final EditSessionOpsAccess access,
        final Object parameterSource
    ) {
        return access.invoke("cubism.editor-model.parameter-source.guid", parameterSource);
    }

    List<Float> bindingKeys(final EditSessionOpsAccess access, final Object binding) {
        final ArrayList<Float> keys = new ArrayList<>();
        for (final Object raw : list(
            access.invoke("cubism.editor-model.keyform-binding.keys", binding),
            "Editor keyform binding keys")) {
            keys.add((float) number(raw, "Editor keyform key"));
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // label color
    // ------------------------------------------------------------------

    EditLabelColor readLabelColor(final EditSessionOpsAccess access, final Object labelColor) {
        if (labelColor == null
            || !access.isInstance("cubism.editor-model.label-color.class", labelColor)) {
            return new EditLabelColor(EditLabelColorType.UNDEFINED, java.util.Optional.empty());
        }
        final Object type = access.invoke("cubism.editor-model.label-color.label-type", labelColor);
        for (final EditLabelColorType candidate : EditLabelColorType.values()) {
            if (candidate == EditLabelColorType.CUSTOM) {
                continue;
            }
            if (type == access.readStaticField(labelTypeAlias(candidate))) {
                return new EditLabelColor(candidate, java.util.Optional.empty());
            }
        }
        if (type == access.readStaticField("cubism.editor-model.label-color-type.custom")) {
            final Object custom = access.invoke(
                "cubism.editor-model.label-color.customized-color", labelColor);
            final Object color = access.invoke("cubism.editor-model.label-color.color", custom);
            return EditLabelColor.custom(hexColor(access, color));
        }
        throw unavailable("Editor label-color type is unsupported.");
    }

    void writeLabelColor(
        final EditSessionOpsAccess access,
        final Object labelColor,
        final EditLabelColor requested
    ) {
        if (requested.type() == EditLabelColorType.CUSTOM) {
            final float[] rgba = parseHexColor(requested.customColor().orElseThrow());
            final Object hostColor = access.construct(
                "cubism.editor-model.color.create",
                rgba[0], rgba[1], rgba[2], rgba[3]);
            access.invoke(
                "cubism.editor-model.label-color.set-color",
                labelColor,
                access.readStaticField("cubism.editor-model.label-color-type.custom"),
                hostColor);
            return;
        }
        access.invoke(
            "cubism.editor-model.label-color.set-label-type",
            labelColor,
            access.readStaticField(labelTypeAlias(requested.type())));
    }

    private String labelTypeAlias(final EditLabelColorType type) {
        return "cubism.editor-model.label-color-type." + type.name().toLowerCase(java.util.Locale.ROOT);
    }

    private String hexColor(final EditSessionOpsAccess access, final Object color) {
        final int red = colorComponent(access, color, "red");
        final int green = colorComponent(access, color, "green");
        final int blue = colorComponent(access, color, "blue");
        final int alpha = colorComponent(access, color, "alpha");
        return alpha == 255
            ? String.format("#%02X%02X%02X", red, green, blue)
            : String.format("#%02X%02X%02X%02X", red, green, blue, alpha);
    }

    private int colorComponent(
        final EditSessionOpsAccess access,
        final Object color,
        final String component
    ) {
        final double value = number(
            access.invoke("cubism.editor-model.color." + component, color),
            "Editor color component");
        return Math.max(0, Math.min(255, (int) Math.round(value * 255.0)));
    }

    private float[] parseHexColor(final String hex) {
        final String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!digits.matches("[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            throw new IllegalArgumentException("customColor must be #RRGGBB or #RRGGBBAA");
        }
        return new float[] {
            Integer.parseInt(digits.substring(0, 2), 16) / 255.0f,
            Integer.parseInt(digits.substring(2, 4), 16) / 255.0f,
            Integer.parseInt(digits.substring(4, 6), 16) / 255.0f,
            digits.length() == 8
                ? Integer.parseInt(digits.substring(6, 8), 16) / 255.0f
                : 1.0f
        };
    }

    // ------------------------------------------------------------------
    // write envelope — inside the session's own edit bracket
    // ------------------------------------------------------------------

    /**
     * Captures the controllable object's pre-mutation state onto the session edit token, the way
     * the official per-operation undo capture works inside an open session edit.
     */
    void captureUndoForAllEdit(
        final EditSessionOpsAccess access,
        final Object objectSource,
        final String label
    ) {
        final Object handler = access.invoke(
            "cubism.editor-model.parameter-controllable-source.handler", objectSource);
        final Object undo = access.invoke(
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
            handler,
            label);
        final Object accepted = access.invoke(
            "cubism.editor-model.undo.add", session.editToken(), undo, Boolean.TRUE);
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException("Cubism rejected the " + label + " Undo entry.");
        }
    }

    /**
     * Registers an undo payload produced by a create/delete mutation on the session edit token.
     */
    void addUndo(final EditSessionOpsAccess access, final Object undo, final String label) {
        final Object accepted = access.invoke(
            "cubism.editor-model.undo.add", session.editToken(), undo, Boolean.TRUE);
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException("Cubism rejected the " + label + " Undo entry.");
        }
    }

    /**
     * The verified post-write refresh: model instances update, palette refresh for the affected
     * surfaces, dirty marking, canvas repaint. Runs inside the session's own edit bracket — the
     * session already owns the outer edit mode, so no nested begin/end is opened here.
     */
    void finishWrite(
        final EditSessionOpsAccess access,
        final boolean parameterPalette,
        final boolean objectPalettes
    ) {
        access.invoke(
            "cubism.editor-model.model-source.update-instances", access.modelSource());
        final Object pack = completePack(access);
        if (parameterPalette) {
            access.invoke(
                "cubism.editor-model.complete-pack.update-parameter", pack, Boolean.TRUE);
        }
        if (objectPalettes) {
            access.invoke(
                "cubism.editor-model.complete-pack.update-part-palette", pack, Boolean.TRUE);
            access.invoke(
                "cubism.editor-model.complete-pack.update-deformer-palette", pack, Boolean.TRUE);
        }
        access.invoke("cubism.editor-model.modeling-document.mark-dirty", access.document());
        access.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas", pack, Boolean.TRUE);
    }

    // ------------------------------------------------------------------
    // selection guid <-> object id translation (T5)
    // ------------------------------------------------------------------

    /**
     * Reads the host selection guid list and translates each guid to its object id inside the
     * bound model. Guids that no longer resolve are skipped — the host can retain guids of
     * deleted objects in its selection list.
     */
    List<ModelObjectId> selectedObjectIds(final EditSessionOpsAccess access) {
        final Object raw = access.invoke(
            "cubism.editor-model.update-manager.selection-guid-list",
            updateManager(access));
        final ArrayList<ModelObjectId> ids = new ArrayList<>();
        for (final Object guid : list(raw, "Editor selection guid list")) {
            final String value = guidValue(access, guid);
            for (final Object source : allObjectSources(access)) {
                if (sourceGuid(access, source).equals(value)) {
                    ids.add(new ModelObjectId(objectId(access, source)));
                    break;
                }
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Resolves object ids to their host guids and writes the selection through {@code
     * setSelection}.
     *
     * <p>OPEN (T7 host verification): the meaning of {@code setSelection}'s two boolean flags is
     * not host-validated. The call uses {@code (false, true)}, the flag pair the existing
     * delete-orchestration path passes; direct host validation is deferred to T7 — 待宿主验证.
     */
    void writeSelection(
        final EditSessionOpsAccess access,
        final List<Object> targetSources
    ) {
        final ArrayList<Object> guids = new ArrayList<>(targetSources.size());
        for (final Object source : targetSources) {
            guids.add(access.invoke(
                "cubism.editor-model.parameter-controllable-source.guid", source));
        }
        access.invoke(
            "cubism.editor-model.update-manager.set-selection",
            updateManager(access),
            access.document(),
            List.copyOf(guids),
            Boolean.FALSE,
            Boolean.TRUE);
    }

    // ------------------------------------------------------------------

    IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }
}
