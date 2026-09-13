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

    @Override
    public NativeHistoryDecodeResult decode(
        final Object entry,
        final String label,
        final NativeHistoryDecodeContext context,
        final int depth,
        final NativeHistoryDecoderRegistry registry
    ) {
        final VerifiedMemberResolver resolver = context.resolver();
        if (!NativeHistoryDecoderRegistry.authorized(
            resolver,
            EditorHistorySemanticSelectorContract.ART_MESH_FORM_REQUIRED_ALIASES
        )) {
            return NativeHistoryDecodeResult.unsupported("history.semantic-operation-unmapped");
        }
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
        if (!allArtMeshForms(resolver, undoForms) || !allArtMeshForms(resolver, redoForms)) {
            return NativeHistoryDecodeResult.unsupported("history.semantic-operation-unmapped");
        }
        return decodeArtMeshForms(resolver, context.boundedLabel(label), undoForms, redoForms);
    }

    static NativeHistoryDecodeResult decodeArtMeshForms(
        final VerifiedMemberResolver resolver,
        final String label,
        final List<?> undoForms,
        final List<?> redoForms
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
            final Object beforeSource = resolver.invoke("cubism.editor-history.semantic.art-mesh-form.source", before);
            final Object afterSource = resolver.invoke("cubism.editor-history.semantic.art-mesh-form.source", after);
            if (beforeSource == null || afterSource == null) {
                degradation = first(degradation, "history.target-unresolved");
                continue;
            }
            final HistoryTarget beforeTarget = artMeshTarget(resolver, beforeSource);
            final HistoryTarget afterTarget = artMeshTarget(resolver, afterSource);
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
            degradation = first(degradation, compareFormValues(resolver, before, after, projectedContext, changes));
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

    static boolean allArtMeshForms(
        final VerifiedMemberResolver resolver,
        final List<?> forms
    ) {
        if (forms.isEmpty()) return false;
        for (Object form : forms) {
            if (!resolver.isExactInstance("cubism.editor-history.semantic.art-mesh-form.class", form)) {
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

    private static HistoryTarget artMeshTarget(
        final VerifiedMemberResolver resolver,
        final Object source
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
            "ART_MESH",
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
        final HistoryTarget target = artMeshTarget(resolver, source);
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
        final List<HistoryChange> changes
    ) {
        String degradation = addNumberChange(resolver, before, after, context, changes, "opacity",
            "cubism.editor-model.drawable-form.opacity");
        degradation = first(degradation, addIntegerChange(resolver, before, after, context, changes, "drawOrder",
            "cubism.editor-model.drawable-form.draw-order"));
        degradation = first(degradation, addColorChange(resolver, before, after, context, changes, "multiplyColor",
            "cubism.editor-model.drawable-form.multiply-color"));
        degradation = first(degradation, addColorChange(resolver, before, after, context, changes, "screenColor",
            "cubism.editor-model.drawable-form.screen-color"));
        return degradation;
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
