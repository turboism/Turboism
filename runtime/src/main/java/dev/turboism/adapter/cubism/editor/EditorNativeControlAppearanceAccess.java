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
    private final Supplier<Object> currentSource;

    EditorNativeControlAppearanceAccess(
        final VerifiedMemberResolver resolver,
        final Supplier<Object> currentSource
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentSource = Objects.requireNonNull(currentSource, "currentSource");
    }

    @Override
    public NativeControlAppearance snapshot(final ControlAppearanceTarget target) {
        requireReadAuthorization();
        final Object labelColor = labelColor(Objects.requireNonNull(target, "target"));
        return new NativeControlAppearance(background(labelColor), effectiveColor(labelColor));
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
        final Object labelColor = labelColor(target);
        final NativeBackgroundValue requested = nativeValue(background);
        if (exactMatch(requested, readBackground(labelColor))) {
            return;
        }
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object undo = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, ACTION_NAME
        );
        boolean completed = false;
        try {
            addUndo(undo, labelColor);
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    refresh(app, target);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            apply(labelColor, requested);
            refresh(app, target);
            if (!exactMatch(requested, readBackground(labelColor))) {
                throw new IllegalStateException(
                    "Cubism native control background write was not applied exactly."
                );
            }
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
        labelColor(target);
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

    private Object labelColor(final ControlAppearanceTarget target) {
        if (target instanceof ControlAppearanceTarget.ParameterLabel) {
            throw new UnsupportedOperationException(
                "ParameterLabel is overlay-only; native label-background authoring is unsupported."
            );
        }
        final Object source = currentSource.get();
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

    private Color effectiveColor(final Object labelColor) {
        return readColor(resolver.invoke("cubism.editor-model.label-color.color", labelColor));
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

    private void refresh(final Object app, final ControlAppearanceTarget target) {
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
    }

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
