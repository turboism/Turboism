package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.NativeControlAppearanceAuthoring;
import dev.turboism.mapping.verification.EditorNativeControlAppearanceReadSelectorContract;
import dev.turboism.mapping.verification.EditorNativeControlAppearanceWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.NativeControlAppearance;
import dev.turboism.sdk.ui.appearance.NativeControlBackground;
import dev.turboism.sdk.ui.appearance.PresetColor;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Exact, generation-bound Editor-native control label-background authoring.
 *
 * <p>Resolves the active model source and the target fresh per operation and rejects missing,
 * stale, or ambiguous identities before mutation. Default/preset writes go through the exact
 * native {@code setLabelType} so a latent custom color is preserved; custom writes use
 * {@code setColor(CUSTOM, CColor)}.</p>
 */
final class EditorNativeControlAppearanceAccess implements NativeControlAppearanceAuthoring {

    private static final String ACTION_NAME = "Turboism: Set Native Control Background";

    private final VerifiedMemberResolver resolver;
    private final Supplier<EditorBackedCubismModelAccess.Binding> currentBinding;

    EditorNativeControlAppearanceAccess(
        final VerifiedMemberResolver resolver,
        final Supplier<EditorBackedCubismModelAccess.Binding> currentBinding
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentBinding = Objects.requireNonNull(currentBinding, "currentBinding");
    }

    @Override
    public NativeControlAppearance snapshot(final ControlAppearanceTarget target) {
        requireReadAuthorization();
        Objects.requireNonNull(target, "target");
        final EditorBackedCubismModelAccess.Binding binding = currentBinding.get();
        final Object labelColor = labelColor(binding, target);
        final NativeControlBackground semantic = background(labelColor);
        final NativeControlAppearance result = new NativeControlAppearance(
            semantic, effectiveColor(labelColor, semantic)
        );
        requireCurrent(binding);
        requireSameLabelColor(binding, target, labelColor);
        return result;
    }

    @Override
    public void setNativeBackground(
        final ControlAppearanceTarget target,
        final NativeControlBackground background
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(background, "background");
        if (target instanceof ControlAppearanceTarget.ParameterLabel) {
            throw new UnsupportedOperationException(
                "ParameterLabel is overlay-only; native label-background authoring is unsupported."
            );
        }
        requireWriteAuthorization();
        final EditorBackedCubismModelAccess.Binding binding = currentBinding.get();
        final Object labelColor = labelColor(binding, target);
        final NativeBackgroundValue requested = nativeValue(background);
        if (exactMatch(requested, readBackground(labelColor))) {
            requireCurrent(binding);
            requireSameLabelColor(binding, target, labelColor);
            return;
        }
        requireCurrent(binding);
        requireSameLabelColor(binding, target, labelColor);
        final String sourceId = sourceId(target);
        final long transaction = EditorObjectValidationTrace.begin(
            TRACE_KIND, TRACE_ACTION, sourceId, binding.document(), binding.source()
        );
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = binding.document();
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object undo = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, ACTION_NAME
        );
        trace(transaction, "edit-begin", binding, sourceId, "action=" + ACTION_NAME);
        boolean completed = false;
        try {
            addUndo(undo, labelColor);
            trace(transaction, "undo-admitted", binding, sourceId, "undoAccepted=true");
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    refresh(app, target, transaction, binding, sourceId);
                    trace(
                        transaction,
                        "undo-redo-listener",
                        binding,
                        sourceId,
                        "refresh=" + refreshFamily(target)
                    );
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            requireCurrent(binding);
            requireSameLabelColor(binding, target, labelColor);
            apply(labelColor, requested);
            trace(
                transaction,
                "mutation",
                binding,
                sourceId,
                "requested=" + backgroundText(background)
            );
            requireCurrent(binding);
            requireSameLabelColor(binding, target, labelColor);
            if (!exactMatch(requested, readBackground(labelColor))) {
                throw new IllegalStateException(
                    "Cubism native control background write was not applied exactly."
                );
            }
            refresh(app, target, transaction, binding, sourceId);
            requireCurrent(binding);
            requireSameLabelColor(binding, target, labelColor);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            trace(transaction, "dirty", binding, sourceId, "documentMarkedDirty=true");
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
            trace(
                transaction,
                "edit-end",
                binding,
                sourceId,
                "cancelled=" + !completed
            );
        }
        requireCurrent(binding);
        requireSameLabelColor(binding, target, labelColor);
    }

    private void apply(final Object labelColor, final NativeBackgroundValue requested) {
        if (requested.type() == customType()) {
            final Object hostColor = resolver.construct(
                "cubism.editor-model.color.create",
                Float.valueOf(requested.customColor().red()),
                Float.valueOf(requested.customColor().green()),
                Float.valueOf(requested.customColor().blue()),
                Float.valueOf(requested.customColor().alpha())
            );
            resolver.invoke(
                "cubism.editor-model.label-color.set-color",
                labelColor,
                requested.type(),
                hostColor
            );
        } else {
            resolver.invoke(
                "cubism.editor-model.label-color.set-label-type",
                labelColor,
                requested.type()
            );
        }
    }

    private Object labelColor(
        final EditorBackedCubismModelAccess.Binding binding,
        final ControlAppearanceTarget target
    ) {
        if (target instanceof ControlAppearanceTarget.ParameterLabel) {
            throw new UnsupportedOperationException(
                "ParameterLabel is overlay-only; native label-background authoring is unsupported."
            );
        }
        final Object source = binding.source();
        final Object value;
        if (target instanceof ControlAppearanceTarget.ParameterFolder folder) {
            value = groupLabelColor(source, folder.id().value());
        } else if (target instanceof ControlAppearanceTarget.PartLabel label) {
            value = sourceLabelColor(partSource(source, label.id().value()));
        } else if (target instanceof ControlAppearanceTarget.PartFolder folder) {
            value = sourceLabelColor(partSource(source, folder.id().value()));
        } else if (target instanceof ControlAppearanceTarget.DeformerLabel label) {
            value = sourceLabelColor(deformerSource(source, label.id().value()));
        } else if (target instanceof ControlAppearanceTarget.DeformerControlRow row) {
            value = sourceLabelColor(deformerSource(source, row.id().value()));
        } else {
            throw new IllegalArgumentException(
                "unsupported control appearance target: " + target.getClass().getName()
            );
        }
        if (!resolver.isInstance("cubism.editor-model.label-color.class", value)) {
            throw unavailable("Editor native control label color is unavailable.");
        }
        return value;
    }

    private Object groupLabelColor(final Object source, final String id) {
        final Object root = resolver.invoke(
            "cubism.editor-model.model-source.root-parameter-group", source
        );
        if (!resolver.isInstance("cubism.editor-model.parameter-group.class", root)) {
            throw unavailable("Editor root parameter group is unavailable.");
        }
        final java.util.ArrayDeque<Object> pending = new java.util.ArrayDeque<>();
        final java.util.Set<Object> identities = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>()
        );
        final java.util.Set<String> ids = new java.util.HashSet<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            final Object group = pending.removeFirst();
            if (!identities.add(group)) {
                throw unavailable("Editor parameter group hierarchy contains a cycle.");
            }
            final String groupId = text(resolver.invoke(
                "cubism.editor-model.id.value",
                resolver.invoke("cubism.editor-model.parameter-group.id", group)
            ));
            if (!ids.add(groupId)) {
                throw unavailable("Editor parameter group identifiers are not unique.");
            }
            if (groupId.equals(id)) {
                return resolver.invoke("cubism.editor-model.parameter-group.label-color", group);
            }
            final Object raw = resolver.invoke("cubism.editor-model.parameter-group.children", group);
            if (!(raw instanceof List<?> children)) {
                throw unavailable("Editor parameter group children are unavailable.");
            }
            for (Object child : children) {
                if (resolver.isInstance("cubism.editor-model.parameter-group.class", child)) {
                    pending.addLast(child);
                }
            }
        }
        throw new NoSuchElementException("Cubism parameter group is absent: " + id);
    }

    private Object partSource(final Object source, final String id) {
        final Object raw = resolver.invoke("cubism.editor-model.model-source.parts", source);
        if (!(raw instanceof List<?> sources)) {
            throw unavailable("Editor Part source collection is unavailable.");
        }
        return findSource("cubism.editor-model.part-source.class", "cubism.editor-model.part-source.id",
            "cubism.editor-model.part-id.value", "Cubism Part", id, sources);
    }

    private Object deformerSource(final Object source, final String id) {
        final Object raw = resolver.invoke(
            "cubism.editor-model.model-source.all-deformers", source
        );
        if (!(raw instanceof List<?> sources)) {
            throw unavailable("Editor Deformer source collection is unavailable.");
        }
        return findSource("cubism.editor-model.deformer-source.class",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.id.value", "Cubism Deformer", id, sources);
    }

    private Object findSource(
        final String sourceClassAlias,
        final String idMethodAlias,
        final String idValueAlias,
        final String label,
        final String id,
        final List<?> sources
    ) {
        Object match = null;
        for (Object source : sources) {
            if (!resolver.isInstance(sourceClassAlias, source)) {
                throw unavailable("Editor " + label + " source collection contains an invalid value.");
            }
            final String candidate = text(resolver.invoke(
                idValueAlias, resolver.invoke(idMethodAlias, source)
            ));
            if (match != null && candidate.equals(id)) {
                throw unavailable("Editor " + label + " source identifiers are not unique.");
            }
            if (candidate.equals(id)) {
                match = source;
            }
        }
        if (match == null) {
            throw new NoSuchElementException(label + " is absent: " + id);
        }
        return match;
    }

    /**
     * Fail closed unless the active document/source/model references and identity still match
     * the consistent binding this operation started from.
     */
    private void requireCurrent(final EditorBackedCubismModelAccess.Binding expected) {
        final EditorBackedCubismModelAccess.Binding current = currentBinding.get();
        if (!current.identity().equals(expected.identity())
            || current.document() != expected.document()
            || current.source() != expected.source()
            || current.model() != expected.model()) {
            throw new IllegalStateException(
                "Cubism model reference is stale for the active Editor model generation."
            );
        }
    }

    /** Fail closed unless the target still resolves by ID to the exact same label-color object. */
    private Object requireSameLabelColor(
        final EditorBackedCubismModelAccess.Binding binding,
        final ControlAppearanceTarget target,
        final Object expected
    ) {
        final Object resolved = labelColor(binding, target);
        if (resolved != expected) {
            throw new IllegalStateException(
                "Cubism control label color reference is stale for the active Editor model generation."
            );
        }
        return resolved;
    }

    private Object sourceLabelColor(final Object source) {
        final Object value = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.label-color", source
        );
        if (!resolver.isInstance("cubism.editor-model.label-color.class", value)) {
            throw unavailable("Editor native control label color is unavailable.");
        }
        return value;
    }

    /**
     * Exact semantic value comparison: same native type is an exact no-op; for CUSTOM the
     * canonical RGBA must also match. Default/preset writes never compare against a
     * placeholder color.
     */
    private boolean exactMatch(final NativeBackgroundValue requested, final NativeBackgroundValue current) {
        if (requested.type() != current.type()) {
            return false;
        }
        return requested.type() != customType()
            || requested.customColor().equals(current.customColor());
    }

    private NativeBackgroundValue readBackground(final Object labelColor) {
        final Object type = resolver.invoke("cubism.editor-model.label-color.label-type", labelColor);
        if (type == resolver.readStaticField("cubism.editor-model.label-color-type.undefined")) {
            return new NativeBackgroundValue(type, null);
        }
        if (type == customType()) {
            return new NativeBackgroundValue(type, customizedColor(labelColor));
        }
        for (PresetColor preset : PresetColor.values()) {
            if (type == resolver.readStaticField(presetAlias(preset))) {
                return new NativeBackgroundValue(type, null);
            }
        }
        throw unavailable("Editor native control label color type is unsupported.");
    }

    private NativeControlBackground background(final Object labelColor) {
        final Object type = resolver.invoke("cubism.editor-model.label-color.label-type", labelColor);
        if (type == resolver.readStaticField("cubism.editor-model.label-color-type.undefined")) {
            return new NativeControlBackground.Default();
        }
        if (type == customType()) {
            return new NativeControlBackground.Custom(customizedColor(labelColor));
        }
        for (PresetColor preset : PresetColor.values()) {
            if (type == resolver.readStaticField(presetAlias(preset))) {
                return new NativeControlBackground.Preset(preset);
            }
        }
        throw unavailable("Editor native control label color type is unsupported.");
    }

    private NativeBackgroundValue nativeValue(final NativeControlBackground background) {
        if (background instanceof NativeControlBackground.Default) {
            return new NativeBackgroundValue(
                resolver.readStaticField("cubism.editor-model.label-color-type.undefined"),
                null
            );
        }
        if (background instanceof NativeControlBackground.Preset preset) {
            return new NativeBackgroundValue(
                resolver.readStaticField(presetAlias(preset.color())),
                null
            );
        }
        if (background instanceof NativeControlBackground.Custom custom) {
            return new NativeBackgroundValue(customType(), custom.color());
        }
        throw new IllegalArgumentException(
            "unsupported native control background: " + background.getClass().getName()
        );
    }

    private Object customType() {
        return resolver.readStaticField("cubism.editor-model.label-color-type.custom");
    }

    private static String presetAlias(final PresetColor preset) {
        return "cubism.editor-model.label-color-type." + preset.name().toLowerCase(java.util.Locale.ROOT);
    }

    private Optional<Color> effectiveColor(
        final Object labelColor,
        final NativeControlBackground semantic
    ) {
        final Object color = resolver.invoke("cubism.editor-model.label-color.color", labelColor);
        if (color == null) {
            if (semantic instanceof NativeControlBackground.Default) {
                return Optional.empty();
            }
            throw unavailable(
                "Editor native control effective color is unavailable for semantic "
                    + semantic.getClass().getSimpleName()
            );
        }
        return Optional.of(readColor(color));
    }

    private Color customizedColor(final Object labelColor) {
        return readColor(
            resolver.invoke("cubism.editor-model.label-color.customized-color", labelColor)
        );
    }

    private Color readColor(final Object color) {
        if (!resolver.isInstance("cubism.editor-model.color.class", color)) {
            throw unavailable("Editor native control effective color is unavailable.");
        }
        return new Color(
            unit(resolver.invoke("cubism.editor-model.color.red", color), "red"),
            unit(resolver.invoke("cubism.editor-model.color.green", color), "green"),
            unit(resolver.invoke("cubism.editor-model.color.blue", color), "blue"),
            unit(resolver.invoke("cubism.editor-model.color.alpha", color), "alpha")
        );
    }

    private static float unit(final Object value, final String name) {
        if (!(value instanceof Number number)
            || !Float.isFinite(number.floatValue())
            || number.floatValue() < 0.0F
            || number.floatValue() > 1.0F) {
            throw unavailable("Editor native control color component is invalid: " + name);
        }
        return number.floatValue();
    }

    private void addUndo(final Object edit, final Object labelColor) {
        final Object colorUndo = resolver.construct(
            "cubism.editor-model.simple-undo.create",
            ACTION_NAME,
            labelColor,
            null
        );
        final Object accepted = resolver.invoke(
            "cubism.editor-model.undo.add",
            edit,
            colorUndo,
            Boolean.TRUE
        );
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException("Cubism rejected the native-control background Undo entry.");
        }
    }

    private void refresh(
        final Object app,
        final ControlAppearanceTarget target,
        final long transaction,
        final EditorBackedCubismModelAccess.Binding binding,
        final String sourceId
    ) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        if (target instanceof ControlAppearanceTarget.ParameterFolder) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-parameter",
                completePack,
                Boolean.TRUE
            );
            final Object mainFrame = resolver.invoke(
                "cubism.editor-model.app-controller.main-frame", app
            );
            final Object palette = resolver.invoke(
                "cubism.editor-model.main-frame.parameter-palette", mainFrame
            );
            final Object paletteView = resolver.invoke(
                "cubism.editor-model.parameter-palette.view", palette
            );
            final Object operation = resolver.invoke(
                "cubism.editor-model.parameter-palette-view.operation", paletteView
            );
            resolver.invoke(
                "cubism.editor-model.parameter-operation.refresh",
                operation,
                Boolean.TRUE
            );
        } else if (target instanceof ControlAppearanceTarget.PartLabel
            || target instanceof ControlAppearanceTarget.PartFolder) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-part-palette",
                completePack,
                Boolean.TRUE
            );
        } else if (target instanceof ControlAppearanceTarget.DeformerLabel
            || target instanceof ControlAppearanceTarget.DeformerControlRow) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-deformer-palette",
                completePack,
                Boolean.TRUE
            );
        } else {
            throw new IllegalArgumentException(
                "unsupported control appearance target: " + target.getClass().getName()
            );
        }
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas",
            completePack,
            Boolean.TRUE
        );
        trace(
            transaction,
            "refresh",
            binding,
            sourceId,
            "family=" + refreshFamily(target) + " palette=" + refreshPalette(target)
                + " canvas=repaintCanvas"
        );
    }

    private void trace(
        final long transaction,
        final String phase,
        final EditorBackedCubismModelAccess.Binding binding,
        final String sourceId,
        final String detail
    ) {
        EditorObjectValidationTrace.event(
            transaction,
            phase,
            TRACE_KIND,
            TRACE_ACTION,
            sourceId,
            binding.document(),
            binding.source(),
            detail
        );
    }

    private static String sourceId(final ControlAppearanceTarget target) {
        if (target instanceof ControlAppearanceTarget.ParameterFolder value) {
            return "ParameterFolder:" + value.id().value();
        }
        if (target instanceof ControlAppearanceTarget.PartFolder value) {
            return "PartFolder:" + value.id().value();
        }
        if (target instanceof ControlAppearanceTarget.PartLabel value) {
            return "PartLabel:" + value.id().value();
        }
        if (target instanceof ControlAppearanceTarget.DeformerControlRow value) {
            return "DeformerControlRow:" + value.id().value();
        }
        if (target instanceof ControlAppearanceTarget.DeformerLabel value) {
            return "DeformerLabel:" + value.id().value();
        }
        return target.getClass().getSimpleName();
    }

    private static String refreshFamily(final ControlAppearanceTarget target) {
        if (target instanceof ControlAppearanceTarget.ParameterFolder) {
            return "parameterFolder";
        }
        if (target instanceof ControlAppearanceTarget.PartLabel
            || target instanceof ControlAppearanceTarget.PartFolder) {
            return "part";
        }
        if (target instanceof ControlAppearanceTarget.DeformerLabel
            || target instanceof ControlAppearanceTarget.DeformerControlRow) {
            return "deformer";
        }
        return "unknown";
    }

    private static String refreshPalette(final ControlAppearanceTarget target) {
        if (target instanceof ControlAppearanceTarget.ParameterFolder) {
            return "parameterOperation";
        }
        if (target instanceof ControlAppearanceTarget.PartLabel
            || target instanceof ControlAppearanceTarget.PartFolder) {
            return "partPalette";
        }
        if (target instanceof ControlAppearanceTarget.DeformerLabel
            || target instanceof ControlAppearanceTarget.DeformerControlRow) {
            return "deformerPalette";
        }
        return "unknown";
    }

    private static String backgroundText(final NativeControlBackground background) {
        if (background instanceof NativeControlBackground.Default) {
            return "default";
        }
        if (background instanceof NativeControlBackground.Preset preset) {
            return "preset(" + preset.color().name() + ")";
        }
        if (background instanceof NativeControlBackground.Custom custom) {
            return "custom(rgba(" + custom.color().red() + "," + custom.color().green() + ","
                + custom.color().blue() + "," + custom.color().alpha() + "))";
        }
        return background.getClass().getSimpleName();
    }

    private static final String TRACE_KIND = "native-control-background";
    private static final String TRACE_ACTION = "set-native-background";

    private void requireReadAuthorization() {
        if (!resolver.authorizesFeature(
            EditorNativeControlAppearanceReadSelectorContract.ADAPTER_SLICE_ID,
            EditorNativeControlAppearanceReadSelectorContract.CAPABILITY_ID,
            EditorNativeControlAppearanceReadSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Native control appearance reading is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireWriteAuthorization() {
        if (!resolver.authorizesFeature(
            EditorNativeControlAppearanceWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorNativeControlAppearanceWriteSelectorContract.CAPABILITY_ID,
            EditorNativeControlAppearanceWriteSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Native control appearance writing is unavailable without exact verified host evidence."
            );
        }
    }

    private static String text(final Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw unavailable("Verified Editor identity is unavailable.");
        }
        return text;
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    /** Native type identity plus the canonical RGBA that is only meaningful for CUSTOM. */
    private record NativeBackgroundValue(Object type, Color customColor) {
    }
}
