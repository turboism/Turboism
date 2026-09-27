package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistorySemanticSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryParameterCoordinate;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Decodes only exact list snapshots backed by a reviewed domain descriptor. */
final class ListUndoDecoder implements NativeHistoryDecoder {

    /** One exact form family whose snapshots the decoder is allowed to compare. */
    enum FormFamily {
        ART_MESH(
            "cubism.editor-history.semantic.art-mesh-form.class",
            "cubism.editor-history.semantic.art-mesh-form.source",
            EditorHistorySemanticSelectorContract.ART_MESH_FORM_REQUIRED_ALIASES,
            "ART_MESH"
        ),
        WARP_DEFORMER(
            "cubism.editor-history.semantic.warp-form.class",
            "cubism.editor-history.semantic.deformer-form.source",
            EditorHistorySemanticSelectorContract.WARP_FORM_REQUIRED_ALIASES,
            "WARP_DEFORMER"
        ),
        ROTATION_DEFORMER(
            "cubism.editor-history.semantic.rotation-form.class",
            "cubism.editor-history.semantic.deformer-form.source",
            EditorHistorySemanticSelectorContract.ROTATION_FORM_REQUIRED_ALIASES,
            "ROTATION_DEFORMER"
        );

        private final String classAlias;
        private final String sourceAlias;
        private final java.util.Set<String> requiredAliases;
        private final String targetType;

        FormFamily(
            final String classAlias,
            final String sourceAlias,
            final java.util.Set<String> requiredAliases,
            final String targetType
        ) {
            this.classAlias = classAlias;
            this.sourceAlias = sourceAlias;
            this.requiredAliases = requiredAliases;
            this.targetType = targetType;
        }

        boolean isInstance(final VerifiedMemberResolver resolver, final Object value) {
            return value != null && resolver.isExactInstance(classAlias, value);
        }

        /** {@return the family this value is an exact instance of, or {@code null}} */
        static FormFamily of(final VerifiedMemberResolver resolver, final Object value) {
            for (final FormFamily family : values()) {
                if (NativeHistoryDecoderRegistry.authorized(resolver, family.requiredAliases)
                    && family.isInstance(resolver, value)) {
                    return family;
                }
            }
            return null;
        }
    }

    @Override
    public NativeHistoryDecodeResult decode(
        final Object entry,
        final String label,
        final NativeHistoryDecodeContext context,
        final int depth,
        final NativeHistoryDecoderRegistry registry
    ) {
        final VerifiedMemberResolver resolver = context.resolver();
        final Object undoValue = resolver.invoke("cubism.editor-history.semantic.list.undo", entry);
        final Object redoValue = resolver.invoke("cubism.editor-history.semantic.list.redo", entry);
        if (!(undoValue instanceof List<?> undoForms) || redoValue == null) {
            return NativeHistoryDecodeResult.unsupported("history.detail.post-state-unavailable");
        }
        if (!(redoValue instanceof List<?> redoForms)) {
            return NativeHistoryDecodeResult.unsupported("history.detail.value-unsupported");
        }
        if (undoForms.size() > NativeHistoryDecodeContext.MAX_NODES
            || redoForms.size() > NativeHistoryDecodeContext.MAX_NODES) {
            return NativeHistoryDecodeResult.unsupported("history.detail.node-or-depth-limit");
        }
        for (final FormFamily family : FormFamily.values()) {
            if (NativeHistoryDecoderRegistry.authorized(resolver, family.requiredAliases)
                && allForms(resolver, undoForms, family)
                && allForms(resolver, redoForms, family)) {
                return decodeForms(
                    resolver, context.boundedLabel(label), undoForms, redoForms, family);
            }
        }
        return NativeHistoryDecodeResult.unsupported("history.semantic-operation-unmapped");
    }

    static NativeHistoryDecodeResult decodeForms(
        final VerifiedMemberResolver resolver,
        final String label,
        final List<?> undoForms,
        final List<?> redoForms,
        final FormFamily family
    ) {
        final Map<String, Object> beforeByGuid = formsByGuid(resolver, undoForms);
        final Map<String, Object> afterByGuid = formsByGuid(resolver, redoForms);
        if (beforeByGuid.size() != undoForms.size()
            || afterByGuid.size() != redoForms.size()
            || !beforeByGuid.keySet().equals(afterByGuid.keySet())) {
            return NativeHistoryDecodeResult.unsupported("history.form-scope-unresolved");
        }

        HistoryTarget target = null;
        final List<HistoryChange> changes = new ArrayList<>();
        String degradation = "";
        for (Map.Entry<String, Object> item : beforeByGuid.entrySet()) {
            final String formId = item.getKey();
            final Object before = item.getValue();
            final Object after = afterByGuid.get(formId);
            final Object beforeSource = resolver.invoke(family.sourceAlias, before);
            final Object afterSource = resolver.invoke(family.sourceAlias, after);
            if (beforeSource == null || afterSource == null) {
                degradation = first(degradation, "history.target-unresolved");
                continue;
            }
            final HistoryTarget beforeTarget = formTarget(resolver, beforeSource, family);
            final HistoryTarget afterTarget = formTarget(resolver, afterSource, family);
            if (!beforeTarget.equals(afterTarget)) {
                return NativeHistoryDecodeResult.unsupported("history.target-unresolved");
            }
            if (target == null) target = afterTarget;
            else if (!target.equals(afterTarget)) {
                return NativeHistoryDecodeResult.unsupported("history.target-unresolved");
            }
            final ContextProjection beforeContext = editContext(resolver, beforeSource, before, formId);
            final ContextProjection afterContext = editContext(resolver, afterSource, after, formId);
            degradation = first(degradation, beforeContext.degradationCode());
            degradation = first(degradation, afterContext.degradationCode());
            final HistoryEditContext projectedContext;
            if (beforeContext.context().equals(afterContext.context())) {
                projectedContext = afterContext.context();
            } else {
                projectedContext = ContextProjection.degraded("history.form-scope-unresolved").context();
                degradation = first(degradation, "history.form-scope-unresolved");
            }
            degradation = first(degradation,
                compareFormValues(resolver, before, after, projectedContext, changes, family));
        }
        if (target == null) {
            return NativeHistoryDecodeResult.unsupported("history.target-unresolved");
        }
        if (changes.isEmpty() && degradation.isEmpty()) {
            return NativeHistoryDecodeResult.unsupported("history.semantic-operation-unmapped");
        }
        if (target.id().isEmpty()) degradation = first(degradation, "history.target-unresolved");
        final HistoryAction.DetailLevel level = degradation.isEmpty()
            ? HistoryAction.DetailLevel.FULL
            : HistoryAction.DetailLevel.PARTIAL;
        return NativeHistoryDecodeResult.decoded(new HistoryEntryDetail(
            label,
            level,
            HistoryOrigin.hostUnattributed(),
            List.of(target),
            List.copyOf(changes),
            Optional.empty(),
            degradation.isEmpty() ? Optional.empty() : Optional.of(degradation)
        ));
    }

    static boolean allForms(
        final VerifiedMemberResolver resolver,
        final List<?> forms,
        final FormFamily family
    ) {
        if (forms.isEmpty()) return false;
        for (Object form : forms) {
            if (!family.isInstance(resolver, form)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> formsByGuid(
        final VerifiedMemberResolver resolver,
        final List<?> forms
    ) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (Object form : forms) {
            final String guid = formGuid(resolver, form);
            if (guid.isBlank() || result.put(guid, form) != null) return Map.of();
        }
        return result;
    }

    private static String formGuid(final VerifiedMemberResolver resolver, final Object form) {
        final Object guid = resolver.invoke("cubism.editor-history.semantic.form.guid", form);
        if (guid == null) return "";
        final Object value = resolver.invoke("cubism.editor-model.form-guid.value", guid);
        return semanticString(value);
    }

    private static HistoryTarget formTarget(
        final VerifiedMemberResolver resolver,
        final Object source,
        final FormFamily family
    ) {
        final Object id = resolver.invoke("cubism.editor-model.parameter-controllable-source.id", source);
        final Object idValue = id == null ? null : resolver.invoke("cubism.editor-model.id.value", id);
        final Object nameValue = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.local-name",
            source
        );
        final String targetId = semanticString(idValue);
        final String targetName = semanticString(nameValue);
        return new HistoryTarget(
            family.targetType,
            targetId.isEmpty() ? Optional.empty() : Optional.of(targetId),
            targetName.isEmpty() ? Optional.empty() : Optional.of(targetName)
        );
    }

    private static ContextProjection editContext(
        final VerifiedMemberResolver resolver,
        final Object source,
        final Object form,
        final String formId
    ) {
        final Object grid = resolver.invoke("cubism.editor-model.parameter-controllable.keyform-grid", source);
        if (grid == null) {
            return ContextProjection.degraded("history.form-scope-unresolved");
        }
        final Object bindingValue = resolver.invoke("cubism.editor-model.keyform-grid.bindings", grid);
        if (!(bindingValue instanceof List<?> bindings)) {
            return ContextProjection.degraded("history.parameter-bindings-unresolved");
        }
        if (bindings.isEmpty()) {
            return new ContextProjection(
                new HistoryEditContext(
                    HistoryEditContext.Kind.DEFAULT_FORM,
                    Optional.of(formId),
                    List.of()
                ),
                ""
            );
        }
        final Object guid = resolver.invoke("cubism.editor-history.semantic.form.guid", form);
        final Object rowValue = resolver.invoke(
            "cubism.editor-history.semantic.keyform-grid.forms-for-guid",
            grid,
            guid
        );
        if (!(rowValue instanceof List<?> rows) || rows.size() != 1
            || !resolver.isExactInstance("cubism.editor-history.semantic.keyform-on-grid.class", rows.get(0))) {
            return ContextProjection.degraded("history.form-scope-unresolved");
        }
        final Object accessKey = resolver.invoke(
            "cubism.editor-history.semantic.keyform-on-grid.access-key",
            rows.get(0)
        );
        if (!resolver.isExactInstance("cubism.editor-history.semantic.keyform-access-key.class", accessKey)) {
            return ContextProjection.degraded("history.keyform-coordinates-incomplete");
        }
        final Object coordinateValue = resolver.invoke(
            "cubism.editor-history.semantic.keyform-access-key.coordinates",
            accessKey
        );
        if (!(coordinateValue instanceof List<?> coordinates) || coordinates.size() != bindings.size()) {
            return ContextProjection.degraded("history.keyform-coordinates-incomplete");
        }
        final List<HistoryParameterCoordinate> projected = new ArrayList<>(coordinates.size());
        for (int index = 0; index < coordinates.size(); index++) {
            final Object coordinate = coordinates.get(index);
            if (!resolver.isExactInstance("cubism.editor-history.semantic.key-on-parameter.class", coordinate)) {
                return ContextProjection.degraded("history.keyform-coordinates-incomplete");
            }
            final Object binding = resolver.invoke(
                "cubism.editor-history.semantic.key-on-parameter.binding",
                coordinate
            );
            if (binding != bindings.get(index)
                || !resolver.isExactInstance("cubism.editor-model.keyform-binding.class", binding)) {
                return ContextProjection.degraded("history.keyform-coordinates-incomplete");
            }
            final Object parameter = resolver.invoke(
                "cubism.editor-history.semantic.keyform-binding.parameter",
                binding
            );
            final Object parameterId = parameter == null
                ? null
                : resolver.invoke("cubism.editor-model.parameter-source.id", parameter);
            final Object parameterIdValue = parameterId == null
                ? null
                : resolver.invoke("cubism.editor-model.id.value", parameterId);
            final Object parameterName = parameter == null
                ? null
                : resolver.invoke("cubism.editor-model.parameter-source.name", parameter);
            final Object coordinateNumber = resolver.invoke(
                "cubism.editor-history.semantic.key-on-parameter.value",
                coordinate
            );
            final String id = semanticString(parameterIdValue);
            if (id.isEmpty()
                || !(coordinateNumber instanceof Number number)
                || !Float.isFinite(number.floatValue())) {
                return ContextProjection.degraded("history.keyform-coordinates-incomplete");
            }
            final String name = semanticString(parameterName);
            projected.add(new HistoryParameterCoordinate(
                new HistoryTarget(
                    "PARAMETER",
                    Optional.of(id),
                    name.isEmpty() ? Optional.empty() : Optional.of(name)
                ),
                Float.toString(number.floatValue())
            ));
        }
        return new ContextProjection(
            new HistoryEditContext(
                HistoryEditContext.Kind.KEYFORM,
                Optional.of(formId),
                List.copyOf(projected)
            ),
            ""
        );
    }

    static ArtMeshPropertyCapture captureProperty(
        final VerifiedMemberResolver resolver, final Object form, final String property
    ) {
        if (!NativeHistoryDecoderRegistry.authorized(resolver,
            EditorHistorySemanticSelectorContract.ART_MESH_FORM_REQUIRED_ALIASES)
            || !resolver.isExactInstance("cubism.editor-history.semantic.art-mesh-form.class", form)) {
            throw new IllegalStateException("Artmesh capture selectors unavailable");
        }
        final String guid = formGuid(resolver, form);
        if (guid.isEmpty()) throw new IllegalStateException("form identity unavailable");
        final Object source = resolver.invoke("cubism.editor-history.semantic.art-mesh-form.source", form);
        final HistoryTarget target = formTarget(resolver, source, FormFamily.ART_MESH);
        if (target.id().isEmpty()) throw new IllegalStateException("target identity unavailable");
        final ContextProjection context = editContext(resolver, source, form, guid);
        final String value;
        switch (property) {
            case "opacity" -> {
                final Object raw = resolver.invoke("cubism.editor-model.drawable-form.opacity", form);
                if (!(raw instanceof Number number) || !validChannel(number.floatValue())) {
                    throw new IllegalStateException("invalid opacity");
                }
                value = Float.toString(number.floatValue());
            }
            case "drawOrder" -> {
                final Object raw = resolver.invoke("cubism.editor-model.drawable-form.draw-order", form);
                if (!(raw instanceof Integer number)) throw new IllegalStateException("invalid draw order");
                value = Integer.toString(number);
            }
            case "multiplyColor", "screenColor" -> {
                if (!NativeHistoryDecoderRegistry.authorized(resolver,
                    java.util.Set.of("cubism.editor-model.float-color.alpha"))) {
                    throw new IllegalStateException("color alpha unavailable");
                }
                final Object raw = resolver.invoke(property.equals("multiplyColor")
                    ? "cubism.editor-model.drawable-form.multiply-color"
                    : "cubism.editor-model.drawable-form.screen-color", form);
                final String rgb = color(resolver, raw);
                final Object alpha = resolver.invoke("cubism.editor-model.float-color.alpha", raw);
                if (rgb.isEmpty() || !(alpha instanceof Number number) || !validChannel(number.floatValue())) {
                    throw new IllegalStateException("invalid color");
                }
                value = number.floatValue() == 1.0F ? rgb
                    : rgb + String.format(java.util.Locale.ROOT, "%02x", Math.round(number.floatValue() * 255.0F));
            }
            default -> throw new IllegalArgumentException("unmapped Artmesh property");
        }
        return new ArtMeshPropertyCapture(target, context.context(), property, value, context.degradationCode());
    }


    private static String compareFormValues(
        final VerifiedMemberResolver resolver,
        final Object before,
        final Object after,
        final HistoryEditContext context,
        final List<HistoryChange> changes,
        final FormFamily family
    ) {
        return switch (family) {
            case ART_MESH -> {
                String degradation = addNumberChange(resolver, before, after, context, changes,
                    "opacity", "cubism.editor-model.drawable-form.opacity");
                degradation = first(degradation, addIntegerChange(resolver, before, after, context,
                    changes, "drawOrder", "cubism.editor-model.drawable-form.draw-order"));
                degradation = first(degradation, addColorChange(resolver, before, after, context,
                    changes, "multiplyColor", "cubism.editor-model.drawable-form.multiply-color"));
                degradation = first(degradation, addColorChange(resolver, before, after, context,
                    changes, "screenColor", "cubism.editor-model.drawable-form.screen-color"));
                degradation = first(degradation, addPositionsChange(resolver, before, after, context,
                    changes, "cubism.editor-model.art-mesh-form.positions", "vertexPositions"));
                yield degradation;
            }
            case WARP_DEFORMER -> {
                String degradation = addNumberChange(resolver, before, after, context, changes,
                    "opacity", "cubism.editor-model.deformer-form.opacity");
                degradation = first(degradation, addColorChange(resolver, before, after, context,
                    changes, "multiplyColor", "cubism.editor-model.deformer-form.multiply-color"));
                degradation = first(degradation, addColorChange(resolver, before, after, context,
                    changes, "screenColor", "cubism.editor-model.deformer-form.screen-color"));
                degradation = first(degradation, addPositionsChange(resolver, before, after, context,
                    changes, "cubism.editor-model.warp-form.positions", "controlPointPositions"));
                yield degradation;
            }
            case ROTATION_DEFORMER -> compareRotationForm(resolver, before, after, context, changes);
        };
    }

    /**
     * Compares one rotation-deformer form pair scalar by scalar.
     *
     * <p>The origin pair is reported as one {@code origin} change: a rotation deformer's anchor is
     * a single point, so reporting the axes separately would invent two facts out of one edit.</p>
     */
    private static String compareRotationForm(
        final VerifiedMemberResolver resolver,
        final Object before,
        final Object after,
        final HistoryEditContext context,
        final List<HistoryChange> changes
    ) {
        final int base = changes.size();
        String degradation = addNumberChange(resolver, before, after, context, changes, "opacity",
            "cubism.editor-model.deformer-form.opacity");
        degradation = first(degradation, addColorChange(resolver, before, after, context, changes,
            "multiplyColor", "cubism.editor-model.deformer-form.multiply-color"));
        degradation = first(degradation, addColorChange(resolver, before, after, context, changes,
            "screenColor", "cubism.editor-model.deformer-form.screen-color"));
        degradation = first(degradation, addNumberChange(resolver, before, after, context, changes,
            "angle", "cubism.editor-model.rotation-form.angle"));
        degradation = first(degradation, addOriginChange(resolver, before, after, context, changes));
        degradation = first(degradation, addNumberChange(resolver, before, after, context, changes,
            "scale", "cubism.editor-model.rotation-form.scale"));
        degradation = first(degradation, addBooleanChange(resolver, before, after, context, changes,
            "reflectX", "cubism.editor-model.rotation-form.reflect-x"));
        degradation = first(degradation, addBooleanChange(resolver, before, after, context, changes,
            "reflectY", "cubism.editor-model.rotation-form.reflect-y"));
        // A rotation deformer's only movable point is its origin: a form pair whose sole change is
        // a moved origin is a proven whole-object translation, not a property edit.
        if (degradation.isEmpty() && changes.size() - base == 1
            && changes.get(changes.size() - 1).property().filter("origin"::equals).isPresent()) {
            final HistoryChange origin = changes.remove(changes.size() - 1);
            changes.add(new HistoryChange(
                HistoryChange.Operation.MOVE,
                origin.targetIndex(),
                Optional.of("translation"),
                origin.before(),
                origin.after(),
                context
            ));
        }
        return degradation;
    }

    private static String addOriginChange(
        final VerifiedMemberResolver resolver,
        final Object before,
        final Object after,
        final HistoryEditContext context,
        final List<HistoryChange> changes
    ) {
        final Object beforeX = resolver.invoke("cubism.editor-model.rotation-form.origin-x", before);
        final Object beforeY = resolver.invoke("cubism.editor-model.rotation-form.origin-y", before);
        final Object afterX = resolver.invoke("cubism.editor-model.rotation-form.origin-x", after);
        final Object afterY = resolver.invoke("cubism.editor-model.rotation-form.origin-y", after);
        if (!(beforeX instanceof Number bx) || !(beforeY instanceof Number by)
            || !(afterX instanceof Number ax) || !(afterY instanceof Number ay)
            || !Float.isFinite(bx.floatValue()) || !Float.isFinite(by.floatValue())
            || !Float.isFinite(ax.floatValue()) || !Float.isFinite(ay.floatValue())) {
            return "history.value-codec-unavailable";
        }
        if (Float.compare(bx.floatValue(), ax.floatValue()) == 0
            && Float.compare(by.floatValue(), ay.floatValue()) == 0) {
            return "";
        }
        if (changes.size() >= NativeHistoryDecodeContext.MAX_NODES) {
            return "history.detail.node-or-depth-limit";
        }
        changes.add(change(
            "origin",
            point(bx.floatValue(), by.floatValue()),
            point(ax.floatValue(), ay.floatValue()),
            context
        ));
        return "";
    }

    private static String addBooleanChange(
        final VerifiedMemberResolver resolver,
        final Object before,
        final Object after,
        final HistoryEditContext context,
        final List<HistoryChange> changes,
        final String property,
        final String alias
    ) {
        final Object beforeValue = resolver.invoke(alias, before);
        final Object afterValue = resolver.invoke(alias, after);
        if (!(beforeValue instanceof Boolean left) || !(afterValue instanceof Boolean right)) {
            return "history.value-codec-unavailable";
        }
        if (left.booleanValue() == right.booleanValue()) return "";
        if (changes.size() >= NativeHistoryDecodeContext.MAX_NODES) {
            return "history.detail.node-or-depth-limit";
        }
        changes.add(change(property, Boolean.toString(left), Boolean.toString(right), context));
        return "";
    }

    /**
     * Compares the flattened point arrays of two snapshots of one form.
     *
     * <p>When every point pair moved by exactly the same delta the snapshots prove a pure
     * translation, and the change carries that signed delta as a {@code MOVE}. Any uneven or
     * partial change is a deformation the decoder does not interpret: it stays a bounded-count
     * edit — a point total per side and the number of changed points — and the catalog forbids
     * unbounded coordinate arrays either way.</p>
     */
    private static String addPositionsChange(
        final VerifiedMemberResolver resolver,
        final Object before,
        final Object after,
        final HistoryEditContext context,
        final List<HistoryChange> changes,
        final String alias,
        final String property
    ) {
        final Object beforeValue = resolver.invoke(alias, before);
        final Object afterValue = resolver.invoke(alias, after);
        if (!(beforeValue instanceof float[] left) || !(afterValue instanceof float[] right)
            || left.length % 2 != 0 || right.length % 2 != 0) {
            return "history.value-codec-unavailable";
        }
        if (!finiteCoordinates(left) || !finiteCoordinates(right)
            || !finiteDifferences(left, right)) {
            return "history.value-codec-unavailable";
        }
        final int changed = changedPoints(left, right);
        if (changed == 0) return "";
        if (changes.size() >= NativeHistoryDecodeContext.MAX_NODES) {
            return "history.detail.node-or-depth-limit";
        }
        // A pure translation is provable only when every point pair moved by exactly the same
        // delta. A partial selection, a deformation, or a parent transform all change points
        // unevenly and stay a bounded-count edit instead.
        final float[] delta = uniformDelta(left, right);
        if (delta != null) {
            changes.add(new HistoryChange(
                HistoryChange.Operation.MOVE,
                Optional.of(0),
                Optional.of("translation"),
                Optional.empty(),
                Optional.of(point(delta[0], delta[1])),
                context
            ));
            return "";
        }
        changes.add(change(
            property,
            "points=" + left.length / 2,
            "points=" + right.length / 2 + ";changed=" + changed,
            context
        ));
        return "";
    }

    /**
     * {@return the shared (dx, dy) every point pair moved by, or {@code null} when the move is
     * not a pure translation}
     */
    private static float[] uniformDelta(final float[] before, final float[] after) {
        if (before.length != after.length || before.length < 2) return null;
        if (!finiteCoordinates(before) || !finiteCoordinates(after)) return null;
        final float dx = after[0] - before[0];
        final float dy = after[1] - before[1];
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || (dx == 0.0F && dy == 0.0F)) {
            return null;
        }
        for (int index = 2; index + 1 < before.length; index += 2) {
            final float currentDx = after[index] - before[index];
            final float currentDy = after[index + 1] - before[index + 1];
            if (!Float.isFinite(currentDx) || !Float.isFinite(currentDy)
                || Float.compare(currentDx, dx) != 0
                || Float.compare(currentDy, dy) != 0) {
                return null;
            }
        }
        return new float[] {dx, dy};
    }

    private static boolean finiteCoordinates(final float[] values) {
        for (final float value : values) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private static boolean finiteDifferences(final float[] before, final float[] after) {
        final int shared = Math.min(before.length, after.length);
        for (int index = 0; index < shared; index++) {
            if (!Float.isFinite(after[index] - before[index])) return false;
        }
        return true;
    }

    private static String point(final float x, final float y) {
        return "(" + Float.toString(x) + "," + Float.toString(y) + ")";
    }

    private static int changedPoints(final float[] before, final float[] after) {
        final int shared = Math.min(before.length, after.length);
        int changed = Math.abs(before.length - after.length) / 2;
        for (int index = 0; index + 1 < shared; index += 2) {
            if (Float.compare(before[index], after[index]) != 0
                || Float.compare(before[index + 1], after[index + 1]) != 0) {
                changed++;
            }
        }
        return changed;
    }

    private static String addNumberChange(
        final VerifiedMemberResolver resolver,
        final Object before,
        final Object after,
        final HistoryEditContext context,
        final List<HistoryChange> changes,
        final String property,
        final String alias
    ) {
        final Object beforeValue = resolver.invoke(alias, before);
        final Object afterValue = resolver.invoke(alias, after);
        if (!(beforeValue instanceof Number left) || !(afterValue instanceof Number right)
            || !Float.isFinite(left.floatValue()) || !Float.isFinite(right.floatValue())) {
            return "history.value-codec-unavailable";
        }
        if (Float.compare(left.floatValue(), right.floatValue()) == 0) return "";
        if (changes.size() >= NativeHistoryDecodeContext.MAX_NODES) {
            return "history.detail.node-or-depth-limit";
        }
        changes.add(change(property, Float.toString(left.floatValue()), Float.toString(right.floatValue()), context));
        return "";
    }

    private static String addIntegerChange(
        final VerifiedMemberResolver resolver,
        final Object before,
        final Object after,
        final HistoryEditContext context,
        final List<HistoryChange> changes,
        final String property,
        final String alias
    ) {
        final Object beforeValue = resolver.invoke(alias, before);
        final Object afterValue = resolver.invoke(alias, after);
        if (!(beforeValue instanceof Number left) || !(afterValue instanceof Number right)) {
            return "history.value-codec-unavailable";
        }
        if (left.intValue() == right.intValue()) return "";
        if (changes.size() >= NativeHistoryDecodeContext.MAX_NODES) {
            return "history.detail.node-or-depth-limit";
        }
        changes.add(change(property, Integer.toString(left.intValue()), Integer.toString(right.intValue()), context));
        return "";
    }

    private static String addColorChange(
        final VerifiedMemberResolver resolver,
        final Object before,
        final Object after,
        final HistoryEditContext context,
        final List<HistoryChange> changes,
        final String property,
        final String alias
    ) {
        final String beforeValue = color(resolver, resolver.invoke(alias, before));
        final String afterValue = color(resolver, resolver.invoke(alias, after));
        if (beforeValue.isEmpty() || afterValue.isEmpty()) return "history.value-codec-unavailable";
        if (beforeValue.equals(afterValue)) return "";
        if (changes.size() >= NativeHistoryDecodeContext.MAX_NODES) {
            return "history.detail.node-or-depth-limit";
        }
        changes.add(change(property, beforeValue, afterValue, context));
        return "";
    }

    private static String color(final VerifiedMemberResolver resolver, final Object color) {
        if (color == null) return "";
        final Object red = resolver.invoke("cubism.editor-model.float-color.red", color);
        final Object green = resolver.invoke("cubism.editor-model.float-color.green", color);
        final Object blue = resolver.invoke("cubism.editor-model.float-color.blue", color);
        if (!(red instanceof Number r) || !(green instanceof Number g) || !(blue instanceof Number b)) {
            return "";
        }
        final float redValue = r.floatValue();
        final float greenValue = g.floatValue();
        final float blueValue = b.floatValue();
        if (!validChannel(redValue) || !validChannel(greenValue) || !validChannel(blueValue)) return "";
        return SemanticHistoryValueCodec.rgb(
            Math.round(redValue * 255.0F),
            Math.round(greenValue * 255.0F),
            Math.round(blueValue * 255.0F)
        );
    }

    private static boolean validChannel(final float value) {
        return Float.isFinite(value) && value >= 0.0F && value <= 1.0F;
    }

    private static HistoryChange change(
        final String property,
        final String before,
        final String after,
        final HistoryEditContext context
    ) {
        return new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.of(property),
            Optional.of(before),
            Optional.of(after),
            context
        );
    }

    private static String semanticString(final Object value) {
        if (!(value instanceof String text)) return "";
        final String normalized = text.strip();
        if (normalized.isEmpty() || normalized.codePoints().anyMatch(codePoint ->
            Character.isISOControl(codePoint) && codePoint != '\t'
        )) {
            return "";
        }
        return normalized.codePoints().limit(NativeHistoryDecodeContext.MAX_STRING_LENGTH)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }

    private static String first(final String existing, final String candidate) {
        return existing.isEmpty() ? candidate : existing;
    }

    private record ContextProjection(
        HistoryEditContext context,
        String degradationCode
    ) {
        static ContextProjection degraded(final String code) {
            return new ContextProjection(new HistoryEditContext(
                HistoryEditContext.Kind.UNKNOWN,
                Optional.empty(),
                List.of()
            ), code);
        }
    }
}
