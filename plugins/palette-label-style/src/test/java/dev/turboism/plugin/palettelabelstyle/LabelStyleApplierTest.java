package dev.turboism.plugin.palettelabelstyle;

import static dev.turboism.plugin.palettelabelstyle.PaletteLabelStyleTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LabelStyleApplierTest {

    private static final ContextMenuSelection SELECTION = selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER, "p1");

    void deformerTabDeformerTextOverridesPartPaletteEntry() {
        final FakeModel model = FakeModel.with("warp1");
        final FakeDeformer deformer = model.deformer("warp1");

        apply(
                model,
                selection(Location.DEFORMER_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("red"));

        assertEquals(List.of("text:#E53935"), deformer.partEntry.events());
        assertEquals(List.of(), deformer.deformerEntry.events());
        assertTrue(deformer.nativeLabelColors.isEmpty());
    }

    void deformerTabDeformerBackgroundSyncsNativePresetWithoutOverridingTextBackground() {
        final FakeModel model = FakeModel.with("warp1");
        final FakeDeformer deformer = model.deformer("warp1");
        final List<String> saved = new ArrayList<>();

        apply(
                model,
                selection(Location.DEFORMER_TAB, ObjectKind.ROTATION_DEFORMER, "warp1"),
                LabelStylePersistence.PROPERTY_BACKGROUND,
                LabelStyleApplier.ColorChoice.preset("red"),
                (palette, objectId, property, hex) -> saved.add(hex.orElse("clear")));

        assertEquals(
                List.of(new NativeLabelColor.Preset(dev.turboism.sdk.ui.appearance.PresetColor.RED)),
                deformer.nativeLabelColors);
        assertEquals(List.of(), saved);
        assertEquals(List.of(), deformer.partEntry.events());
        assertEquals(List.of(), deformer.deformerEntry.events());
    }

    void deformerTabDeformerBackgroundNoneUsesNativeDefault() {
        final FakeModel model = FakeModel.with("warp1");
        final List<String> saved = new ArrayList<>();

        apply(
                model,
                selection(Location.DEFORMER_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
                LabelStylePersistence.PROPERTY_BACKGROUND,
                LabelStyleApplier.ColorChoice.none(),
                (palette, objectId, property, hex) -> saved.add(hex.orElse("clear")));

        assertEquals(List.of(new NativeLabelColor.Default()), model.deformer("warp1").nativeLabelColors);
        assertEquals(List.of(), saved);
    }

    void deformerTabDeformerBackgroundCustomSyncsNativeCustomWithoutOverride() {
        final FakeModel model = FakeModel.with("warp1");
        final UiColor custom = LabelStylePresets.parseHex("#123456").orElseThrow();

        apply(
                model,
                selection(Location.DEFORMER_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
                LabelStylePersistence.PROPERTY_BACKGROUND,
                LabelStyleApplier.ColorChoice.custom(custom));

        assertEquals(List.of(new NativeLabelColor.Custom(custom)), model.deformer("warp1").nativeLabelColors);
        assertEquals(List.of(), model.deformer("warp1").partEntry.events());
    }

    @Test
    void deformerTabArtMeshTextOverridesPartPaletteEntryAndBackgroundSyncsNative() {
        final FakeModel model = FakeModel.withArtMesh("mesh1");
        final FakeDrawable drawable = model.drawable("mesh1");
        final List<String> saved = new ArrayList<>();

        apply(
                model,
                selection(Location.DEFORMER_TAB, ObjectKind.ART_MESH, "mesh1"),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("blue"));
        apply(
                model,
                selection(Location.DEFORMER_TAB, ObjectKind.ART_MESH, "mesh1"),
                LabelStylePersistence.PROPERTY_BACKGROUND,
                LabelStyleApplier.ColorChoice.preset("green"),
                (palette, objectId, property, hex) -> saved.add(hex.orElse("clear")));

        assertEquals(List.of("text:#2196F3"), drawable.partEntry.textEvents());
        assertEquals(List.of(), drawable.partEntry.backgroundEvents());
        assertEquals(List.of(), drawable.deformerEntry.events());
        assertEquals(List.of(), saved);
        assertEquals(
                List.of(new NativeLabelColor.Preset(dev.turboism.sdk.ui.appearance.PresetColor.GREEN)),
                drawable.nativeLabelColors);
    }

    @ParameterizedTest(name = "part tab {0} overrides part palette entry")
    @CsvSource({
        "PART, part1, orange, text:#FF9800",
        "PART_FOLDER, folder1, yellow, text:#FDD835",
        "WARP_DEFORMER, warp1, purple, text:#9C27B0",
        "ART_MESH, mesh1, gray, text:#9E9E9E"
    })
    void partTabObjectsOverridePartPaletteEntry(ObjectKind kind, String id, String preset, String expectedEvent) {
        final FakeModel model = new FakeModel();
        switch (kind) {
            case PART, PART_FOLDER -> model.addPart(id);
            case WARP_DEFORMER -> model.addDeformer(id);
            case ART_MESH -> model.addArtMesh(id);
            default -> throw new IllegalArgumentException();
        }

        apply(
                model,
                selection(Location.PART_TAB, kind, id),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset(preset));

        final List<String> events =
                switch (kind) {
                    case PART, PART_FOLDER -> model.part(id).entry.events();
                    case WARP_DEFORMER -> model.deformer(id).partEntry.events();
                    case ART_MESH -> model.drawable(id).partEntry.events();
                    default -> throw new IllegalArgumentException();
                };
        assertEquals(List.of(expectedEvent), events);
    }

    @Test
    void parameterTabParameterTextAndBackgroundOverrideParameterPaletteEntry() {
        final FakeModel model = FakeModel.withParameter("p1");

        apply(
                model,
                selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER, "p1"),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("red"));
        apply(
                model,
                selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER, "p1"),
                LabelStylePersistence.PROPERTY_BACKGROUND,
                LabelStyleApplier.ColorChoice.preset("blue"));

        assertEquals(List.of("text:#E53935"), model.parameter("p1").entry.textEvents());
        assertEquals(List.of("background:#2196F3"), model.parameter("p1").entry.backgroundEvents());
    }

    @Test
    void parameterTabFolderTextOverridesParameterGroupPaletteEntry() {
        final FakeModel model = FakeModel.withGroupFolder("folder1");

        apply(
                model,
                selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER_FOLDER, "folder1"),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("green"));

        assertEquals(List.of("text:#4CAF50"), model.group("folder1").entry.events());
    }

    @Test
    void noneClosesActiveRegistrationAndClearsPersistence() {
        final FakeModel model = FakeModel.withParameter("p1");
        final List<String> saved = new ArrayList<>();
        final LabelStyleApplier applier = new LabelStyleApplier();

        applier.apply(
                model,
                SELECTION,
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("red"),
                sink(saved));
        assertEquals(1, applier.activeRegistrations().size());

        applier.apply(
                model,
                SELECTION,
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.none(),
                sink(saved));

        assertEquals(List.of("#E53935", "clear"), saved);
        assertTrue(applier.activeRegistrations().isEmpty());
        assertEquals(
                List.of("text:#E53935", "text:closed"),
                model.parameter("p1").entry.events());
    }

    @Test
    void reapplyingSamePropertyReplacesRegistration() {
        final FakeModel model = FakeModel.withParameter("p1");
        final LabelStyleApplier applier = new LabelStyleApplier();

        applier.apply(
                model,
                SELECTION,
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("red"),
                LabelStyleApplier.NOOP_SINK);
        applier.apply(
                model,
                SELECTION,
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("blue"),
                LabelStyleApplier.NOOP_SINK);

        assertEquals(
                List.of("text:#E53935", "text:closed", "text:#2196F3"),
                model.parameter("p1").entry.events());
        assertEquals(1, applier.activeRegistrations().size());
    }

    @Test
    void applySavesCanonicalHexForEverySelectedObject() {
        final FakeModel model = FakeModel.withParameter("p1");
        model.addParameter("p2");
        final List<String> saved = new ArrayList<>();

        apply(
                model,
                new ContextMenuSelection(
                        1L,
                        "doc",
                        Location.PARAMETER_TAB,
                        List.of(
                                new ContextMenuSelection.Item(ObjectKind.PARAMETER, "p1"),
                                new ContextMenuSelection.Item(ObjectKind.PARAMETER, "p2"))),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("red"),
                (palette, objectId, property, hex) -> saved.add(objectId + "=" + hex.orElse("clear")));

        assertEquals(List.of("p1=#E53935", "p2=#E53935"), saved);
    }

    @Test
    void missingObjectIsIgnored() {
        final FakeModel model = FakeModel.withParameter("p1");
        final List<String> saved = new ArrayList<>();

        apply(
                model,
                selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER, "missing"),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("red"),
                sink(saved));

        assertTrue(saved.isEmpty());
        assertTrue(model.parameter("p1").entry.events().isEmpty());
    }

    @Test
    void replayResolvesKindByModelMembershipForEveryPalette() {
        final FakeModel model = FakeModel.with("warp1");
        model.addArtMesh("mesh1");
        model.addPart("part1");
        model.addGroupFolder("folder1");
        model.addParameter("p1");
        final LabelStyleApplier applier = new LabelStyleApplier();
        final UiColor color = LabelStylePresets.parseHex("#E53935").orElseThrow();

        applier.replay(model, Location.DEFORMER_TAB, "warp1", "text", color, LabelStyleApplier.NOOP_SINK);
        applier.replay(model, Location.DEFORMER_TAB, "mesh1", "background", color, LabelStyleApplier.NOOP_SINK);
        applier.replay(model, Location.PART_TAB, "part1", "text", color, LabelStyleApplier.NOOP_SINK);
        applier.replay(model, Location.PART_TAB, "mesh1", "text", color, LabelStyleApplier.NOOP_SINK);
        applier.replay(model, Location.PARAMETER_TAB, "folder1", "text", color, LabelStyleApplier.NOOP_SINK);
        applier.replay(model, Location.PARAMETER_TAB, "p1", "background", color, LabelStyleApplier.NOOP_SINK);

        assertEquals(List.of("text:#E53935"), model.deformer("warp1").partEntry.events());
        assertEquals(List.of("text:#E53935"), model.part("part1").entry.events());
        // DEFORMER_TAB background is native-only; PART_TAB text uses the DEFORMER_PART slot.
        assertEquals(List.of("text:#E53935"), model.drawable("mesh1").partEntry.events());
        // Replay applies the persisted hex as a custom native label color.
        assertEquals(
                List.of(new NativeLabelColor.Custom(
                        LabelStylePresets.parseHex("#E53935").orElseThrow())),
                model.drawable("mesh1").nativeLabelColors);
        assertEquals(List.of("text:#E53935"), model.group("folder1").entry.events());
        assertEquals(List.of("background:#E53935"), model.parameter("p1").entry.backgroundEvents());
    }

    @Test
    void replayUnknownObjectIsNoOp() {
        final FakeModel model = FakeModel.withParameter("p1");
        final LabelStyleApplier applier = new LabelStyleApplier();

        applier.replay(
                model,
                Location.PARAMETER_TAB,
                "missing",
                "text",
                LabelStylePresets.parseHex("#E53935").orElseThrow(),
                LabelStyleApplier.NOOP_SINK);

        assertTrue(model.parameter("p1").entry.events().isEmpty());
    }

    @Test
    void clearAllClosesEveryTrackedRegistration() {
        final FakeModel model = FakeModel.with("warp1");
        model.addPart("part1");
        final LabelStyleApplier applier = new LabelStyleApplier();

        applier.apply(
                model,
                selection(Location.DEFORMER_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("red"),
                LabelStyleApplier.NOOP_SINK);
        applier.apply(
                model,
                selection(Location.PART_TAB, ObjectKind.PART, "part1"),
                LabelStylePersistence.PROPERTY_TEXT,
                LabelStyleApplier.ColorChoice.preset("blue"),
                LabelStyleApplier.NOOP_SINK);
        applier.clearAll();

        assertEquals(
                List.of("text:#E53935", "text:closed"),
                model.deformer("warp1").partEntry.events());
        assertEquals(
                List.of("text:#2196F3", "text:closed"),
                model.part("part1").entry.events());
        assertTrue(applier.activeRegistrations().isEmpty());
    }

    private static void apply(
            final FakeModel model,
            final ContextMenuSelection selection,
            final String property,
            final LabelStyleApplier.ColorChoice choice) {
        apply(model, selection, property, choice, LabelStyleApplier.NOOP_SINK);
    }

    private static void apply(
            final FakeModel model,
            final ContextMenuSelection selection,
            final String property,
            final LabelStyleApplier.ColorChoice choice,
            final LabelStyleApplier.ColorSink sink) {
        new LabelStyleApplier().apply(model, selection, property, choice, sink);
    }

    private static LabelStyleApplier.ColorSink sink(final List<String> saved) {
        return (palette, objectId, property, hex) -> saved.add(hex.orElse("clear"));
    }

    private static ContextMenuSelection selection(final Location location, final ObjectKind kind, final String id) {
        return new ContextMenuSelection(1L, "document-1", location, List.of(new ContextMenuSelection.Item(kind, id)));
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public int get(final int index) {
                throw new IndexOutOfBoundsException();
            }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public float get(final int index) {
                throw new IndexOutOfBoundsException();
            }
        };
    }

    // ------------------------------------------------------------------ fakes

}
