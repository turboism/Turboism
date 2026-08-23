package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.PaletteEntry;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies label text/background colors to palette objects selected in context menus.
 *
 * <p>Overrides flow through {@link PaletteEntry} and are tracked per
 * {@code (palette, objectId, property)} so {@code none} can close the active
 * registration. Deformer label backgrounds on the Deformer tab use the native
 * {@code setNativeLabelColor} mechanism (host-persisted, untracked).</p>
 */
public final class LabelStyleApplier {

    /** One user color choice: a preset key ({@code none} included) or a custom color. */
    public record ColorChoice(String key, Optional<UiColor> color) {

        public ColorChoice {
            Objects.requireNonNull(key, "key");
            color = Objects.requireNonNull(color, "color");
        }

        /**
         * @return the choice meaning "no override"; applying it closes any active
         *     registration for the property rather than setting a color
         */
        public static ColorChoice none() {
            return new ColorChoice(LabelStylePresets.NONE_KEY, Optional.empty());
        }

        /**
         * @param key preset identifier from {@link LabelStylePresets}
         * @return a choice carrying the preset's color, or no color when the key is
         *     unknown, which applies as a clear
         */
        public static ColorChoice preset(final String key) {
            return new ColorChoice(key, LabelStylePresets.colorFor(key));
        }

        /**
         * @param color color the user picked directly
         * @return a choice keyed as custom, carrying that exact color
         */
        public static ColorChoice custom(final UiColor color) {
            return new ColorChoice(LabelStylePresets.CUSTOM_KEY, Optional.of(color));
        }
    }

    /** Persistence callback: empty hex clears the entry. */
    public interface ColorSink {
        void save(Location palette, String objectId, String property, Optional<String> hex);
    }

    public static final ColorSink NOOP_SINK = (palette, objectId, property, hex) -> { };

    private final Map<String, Registration> active = new HashMap<>();

    /** Active override registrations keyed by {@code <PALETTE>:<objectId>:<property>}. */
    public Map<String, Registration> activeRegistrations() {
        return Map.copyOf(active);
    }

    /** Applies a choice to every item of the selection. */
    public void apply(
        final CubismModel model,
        final ContextMenuSelection selection,
        final String property,
        final ColorChoice choice,
        final ColorSink sink
    ) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(selection, "selection");
        for (final ContextMenuSelection.Item item : selection.items()) {
            applyOne(model, selection.location(), item.kind(), item.id(), property, choice, sink);
        }
    }

    /** Replays one persisted entry, resolving the object kind by model membership. */
    public void replay(
        final CubismModel model,
        final Location palette,
        final String objectId,
        final String property,
        final UiColor color,
        final ColorSink sink
    ) {
        final ObjectKind kind = resolveKind(model, palette, objectId);
        if (kind != null) {
            applyOne(model, palette, kind, objectId, property, ColorChoice.custom(color), sink);
        }
    }

    /** Closes every tracked override registration (model/project switch). */
    public void clearAll() {
        for (final Registration registration : active.values()) {
            closeQuietly(registration);
        }
        active.clear();
    }

    private void applyOne(
        final CubismModel model,
        final Location location,
        final ObjectKind kind,
        final String objectId,
        final String property,
        final ColorChoice choice,
        final ColorSink sink
    ) {
        switch (location) {
            case DEFORMER_TAB -> {
                switch (kind) {
                    case WARP_DEFORMER, ROTATION_DEFORMER -> {
                        final Optional<Deformer> deformer = findById(model.deformers().all(), objectId);
                        if (deformer.isPresent()) {
                            if (LabelStylePersistence.PROPERTY_TEXT.equals(property)) {
                                // The Deformer tab's tree column renders Palette.DEFORMER_PART
                                // (partPaletteEntry); Palette.DEFORMER only colors control cells.
                                override(deformer.orElseThrow().ui().partPaletteEntry(),
                                    location, objectId, property, choice, sink);
                            } else {
                                // Label color: native label color only (shows as the left color
                                // indicator in the Parts palette, host-persisted). It must not
                                // paint the row's text background in the Deformer tab.
                                setNativeLabelColor(deformer.orElseThrow(), choice);
                            }
                        }
                    }
                    case ART_MESH -> {
                        final Optional<Drawable> drawable = findById(model.drawables().all(), objectId);
                        if (drawable.isPresent()) {
                            if (LabelStylePersistence.PROPERTY_TEXT.equals(property)) {
                                // The Deformer tab's tree column renders Palette.DEFORMER_PART
                                // (partPaletteEntry), shared with the Parts tab for the same object.
                                override(drawable.orElseThrow().ui().partPaletteEntry(),
                                    location, objectId, property, choice, sink);
                            } else {
                                // Label color: native label color only (leftmost control column,
                                // same semantics as the Parts tab's native label-color menu).
                                setNativeLabelColor(drawable.orElseThrow(), choice);
                            }
                        }
                    }
                    default -> { }
                }
            }
            case PART_TAB -> {
                final Optional<PaletteEntry> entry;
                switch (kind) {
                    case PART, PART_FOLDER -> entry = findById(model.parts().all(), objectId)
                        .map(Part::ui).flatMap(PartAppearance::partPaletteEntry);
                    case WARP_DEFORMER, ROTATION_DEFORMER -> entry = findById(model.deformers().all(), objectId)
                        .map(Deformer::ui).flatMap(DeformerAppearance::partPaletteEntry);
                    case ART_MESH -> entry = findById(model.drawables().all(), objectId)
                        .map(Drawable::ui).flatMap(DrawableAppearance::partPaletteEntry);
                    default -> entry = Optional.empty();
                }
                override(entry, location, objectId, property, choice, sink);
            }
            case PARAMETER_TAB -> {
                final Optional<PaletteEntry> entry;
                switch (kind) {
                    case PARAMETER -> entry = findById(model.parameters().all(), objectId)
                        .map(Parameter::ui).flatMap(ParameterAppearance::parameterPaletteEntry);
                    case PARAMETER_FOLDER -> entry = findById(model.parameterGroups().all(), objectId)
                        .map(ParameterGroup::ui).flatMap(ParameterGroupAppearance::parameterPaletteEntry);
                    default -> entry = Optional.empty();
                }
                override(entry, location, objectId, property, choice, sink);
            }
            default -> { }
        }
    }

    private void override(
        final Optional<PaletteEntry> entry,
        final Location palette,
        final String objectId,
        final String property,
        final ColorChoice choice,
        final ColorSink sink
    ) {
        if (entry.isEmpty()) {
            return;
        }
        final String key = LabelStylePersistence.key(palette, objectId, property);
        closeActive(key);
        if (choice.color().isEmpty()) {
            sink.save(palette, objectId, property, Optional.empty());
            return;
        }
        final UiColor color = choice.color().orElseThrow();
        final Registration registration = LabelStylePersistence.PROPERTY_TEXT.equals(property)
            ? entry.orElseThrow().overrideTextColor(color)
            : entry.orElseThrow().overrideBackgroundColor(color);
        active.put(key, registration);
        sink.save(palette, objectId, property, Optional.of(LabelStylePresets.toHex(color)));
    }

    private void setNativeLabelColor(final Deformer deformer, final ColorChoice choice) {
        deformer.ui().setNativeLabelColor(nativeLabelColor(choice));
    }

    private void setNativeLabelColor(final Drawable drawable, final ColorChoice choice) {
        drawable.ui().setNativeLabelColor(nativeLabelColor(choice));
    }

    private static NativeLabelColor nativeLabelColor(final ColorChoice choice) {
        if (LabelStylePresets.CUSTOM_KEY.equals(choice.key())) {
            return new NativeLabelColor.Custom(choice.color().orElseThrow());
        }
        if (choice.color().isPresent()) {
            return new NativeLabelColor.Preset(
                LabelStylePresets.nativePresetFor(choice.key()).orElseThrow());
        }
        return new NativeLabelColor.Default();
    }

    private void closeActive(final String key) {
        final Registration previous = active.remove(key);
        if (previous != null) {
            closeQuietly(previous);
        }
    }

    private static void closeQuietly(final Registration registration) {
        try {
            registration.close();
        } catch (Exception ignored) {
        }
    }

    private static ObjectKind resolveKind(final CubismModel model, final Location palette, final String objectId) {
        return switch (palette) {
            case DEFORMER_TAB -> containsId(model.deformers().all(), objectId) ? ObjectKind.WARP_DEFORMER
                : containsId(model.drawables().all(), objectId) ? ObjectKind.ART_MESH
                : null;
            case PART_TAB -> containsId(model.parts().all(), objectId) ? ObjectKind.PART
                : containsId(model.deformers().all(), objectId) ? ObjectKind.WARP_DEFORMER
                : containsId(model.drawables().all(), objectId) ? ObjectKind.ART_MESH
                : null;
            case PARAMETER_TAB -> containsId(model.parameterGroups().all(), objectId) ? ObjectKind.PARAMETER_FOLDER
                : containsId(model.parameters().all(), objectId) ? ObjectKind.PARAMETER
                : null;
            default -> null;
        };
    }

    private static <T> Optional<T> findById(final List<T> objects, final String id) {
        return objects.stream()
            .filter(object -> idOf(object).equals(id))
            .findFirst();
    }

    private static String idOf(final Object object) {
        if (object instanceof Parameter parameter) {
            return parameter.id().value();
        }
        if (object instanceof ParameterGroup group) {
            return group.id().value();
        }
        if (object instanceof Part part) {
            return part.id().value();
        }
        if (object instanceof Deformer deformer) {
            return deformer.id().value();
        }
        if (object instanceof Drawable drawable) {
            return drawable.id().value();
        }
        throw new IllegalArgumentException("unknown model object: " + object.getClass().getName());
    }

    private static boolean containsId(final List<?> objects, final String id) {
        return objects.stream().anyMatch(object -> idOf(object).equals(id));
    }
}
