package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.PaletteEntry;
import dev.turboism.sdk.ui.appearance.PaletteEntryState;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;
import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelStyleApplierTest {

    private static final ContextMenuSelection SELECTION = selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER, "p1");

    void deformerTabDeformerTextOverridesPartPaletteEntry() {
        final FakeModel model = FakeModel.with("warp1");
        final FakeDeformer deformer = model.deformer("warp1");

        apply(model, selection(Location.DEFORMER_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("red"));

        assertEquals(List.of("text:#E53935"), deformer.partEntry.events());
        assertEquals(List.of(), deformer.deformerEntry.events());
        assertTrue(deformer.nativeLabelColors.isEmpty());
    }

    void deformerTabDeformerBackgroundSyncsNativePresetWithoutOverridingTextBackground() {
        final FakeModel model = FakeModel.with("warp1");
        final FakeDeformer deformer = model.deformer("warp1");
        final List<String> saved = new ArrayList<>();

        apply(model, selection(Location.DEFORMER_TAB, ObjectKind.ROTATION_DEFORMER, "warp1"),
            LabelStylePersistence.PROPERTY_BACKGROUND, LabelStyleApplier.ColorChoice.preset("red"),
            (palette, objectId, property, hex) -> saved.add(hex.orElse("clear")));

        assertEquals(List.of(new NativeLabelColor.Preset(dev.turboism.sdk.ui.appearance.PresetColor.RED)),
            deformer.nativeLabelColors);
        assertEquals(List.of(), saved);
        assertEquals(List.of(), deformer.partEntry.events());
        assertEquals(List.of(), deformer.deformerEntry.events());
    }

    void deformerTabDeformerBackgroundNoneUsesNativeDefault() {
        final FakeModel model = FakeModel.with("warp1");
        final List<String> saved = new ArrayList<>();

        apply(model, selection(Location.DEFORMER_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
            LabelStylePersistence.PROPERTY_BACKGROUND, LabelStyleApplier.ColorChoice.none(),
            (palette, objectId, property, hex) -> saved.add(hex.orElse("clear")));

        assertEquals(List.of(new NativeLabelColor.Default()),
            model.deformer("warp1").nativeLabelColors);
        assertEquals(List.of(), saved);
    }

    void deformerTabDeformerBackgroundCustomSyncsNativeCustomWithoutOverride() {
        final FakeModel model = FakeModel.with("warp1");
        final UiColor custom = LabelStylePresets.parseHex("#123456").orElseThrow();

        apply(model, selection(Location.DEFORMER_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
            LabelStylePersistence.PROPERTY_BACKGROUND, LabelStyleApplier.ColorChoice.custom(custom));

        assertEquals(List.of(new NativeLabelColor.Custom(custom)),
            model.deformer("warp1").nativeLabelColors);
        assertEquals(List.of(), model.deformer("warp1").partEntry.events());
    }

    @Test
    void deformerTabArtMeshTextOverridesPartPaletteEntryAndBackgroundSyncsNative() {
        final FakeModel model = FakeModel.withArtMesh("mesh1");
        final FakeDrawable drawable = model.drawable("mesh1");
        final List<String> saved = new ArrayList<>();

        apply(model, selection(Location.DEFORMER_TAB, ObjectKind.ART_MESH, "mesh1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("blue"));
        apply(model, selection(Location.DEFORMER_TAB, ObjectKind.ART_MESH, "mesh1"),
            LabelStylePersistence.PROPERTY_BACKGROUND, LabelStyleApplier.ColorChoice.preset("green"),
            (palette, objectId, property, hex) -> saved.add(hex.orElse("clear")));

        assertEquals(List.of("text:#2196F3"), drawable.partEntry.textEvents());
        assertEquals(List.of(), drawable.partEntry.backgroundEvents());
        assertEquals(List.of(), drawable.deformerEntry.events());
        assertEquals(List.of(), saved);
        assertEquals(List.of(new NativeLabelColor.Preset(dev.turboism.sdk.ui.appearance.PresetColor.GREEN)),
            drawable.nativeLabelColors);
    }

    @Test
    void partTabPartAndFolderOverridePartPaletteEntry() {
        final FakeModel model = FakeModel.withPart("part1");
        final FakeModel folderModel = FakeModel.withPart("folder1");

        apply(model, selection(Location.PART_TAB, ObjectKind.PART, "part1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("orange"));
        apply(folderModel, selection(Location.PART_TAB, ObjectKind.PART_FOLDER, "folder1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("yellow"));

        assertEquals(List.of("text:#FF9800"), model.part("part1").entry.events());
        assertEquals(List.of("text:#FDD835"), folderModel.part("folder1").entry.events());
    }

    @Test
    void partTabDeformerAndArtMeshOverridePartPaletteEntry() {
        final FakeModel model = FakeModel.with("warp1");
        model.addArtMesh("mesh1");

        apply(model, selection(Location.PART_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("purple"));
        apply(model, selection(Location.PART_TAB, ObjectKind.ART_MESH, "mesh1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("gray"));

        assertEquals(List.of("text:#9C27B0"), model.deformer("warp1").partEntry.events());
        assertEquals(List.of("text:#9E9E9E"), model.drawable("mesh1").partEntry.events());
    }

    @Test
    void parameterTabParameterTextAndBackgroundOverrideParameterPaletteEntry() {
        final FakeModel model = FakeModel.withParameter("p1");

        apply(model, selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER, "p1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("red"));
        apply(model, selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER, "p1"),
            LabelStylePersistence.PROPERTY_BACKGROUND, LabelStyleApplier.ColorChoice.preset("blue"));

        assertEquals(List.of("text:#E53935"), model.parameter("p1").entry.textEvents());
        assertEquals(List.of("background:#2196F3"), model.parameter("p1").entry.backgroundEvents());
    }

    @Test
    void parameterTabFolderTextOverridesParameterGroupPaletteEntry() {
        final FakeModel model = FakeModel.withGroupFolder("folder1");

        apply(model, selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER_FOLDER, "folder1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("green"));

        assertEquals(List.of("text:#4CAF50"), model.group("folder1").entry.events());
    }

    @Test
    void noneClosesActiveRegistrationAndClearsPersistence() {
        final FakeModel model = FakeModel.withParameter("p1");
        final List<String> saved = new ArrayList<>();
        final LabelStyleApplier applier = new LabelStyleApplier();

        applier.apply(model, SELECTION, LabelStylePersistence.PROPERTY_TEXT,
            LabelStyleApplier.ColorChoice.preset("red"), sink(saved));
        assertEquals(1, applier.activeRegistrations().size());

        applier.apply(model, SELECTION, LabelStylePersistence.PROPERTY_TEXT,
            LabelStyleApplier.ColorChoice.none(), sink(saved));

        assertEquals(List.of("#E53935", "clear"), saved);
        assertTrue(applier.activeRegistrations().isEmpty());
        assertEquals(List.of("text:#E53935", "text:closed"), model.parameter("p1").entry.events());
    }

    @Test
    void reapplyingSamePropertyReplacesRegistration() {
        final FakeModel model = FakeModel.withParameter("p1");
        final LabelStyleApplier applier = new LabelStyleApplier();

        applier.apply(model, SELECTION, LabelStylePersistence.PROPERTY_TEXT,
            LabelStyleApplier.ColorChoice.preset("red"), LabelStyleApplier.NOOP_SINK);
        applier.apply(model, SELECTION, LabelStylePersistence.PROPERTY_TEXT,
            LabelStyleApplier.ColorChoice.preset("blue"), LabelStyleApplier.NOOP_SINK);

        assertEquals(List.of("text:#E53935", "text:closed", "text:#2196F3"),
            model.parameter("p1").entry.events());
        assertEquals(1, applier.activeRegistrations().size());
    }

    @Test
    void applySavesCanonicalHexForEverySelectedObject() {
        final FakeModel model = FakeModel.withParameter("p1");
        model.addParameter("p2");
        final List<String> saved = new ArrayList<>();

        apply(model, new ContextMenuSelection(1L, "doc", Location.PARAMETER_TAB, List.of(
            new ContextMenuSelection.Item(ObjectKind.PARAMETER, "p1"),
            new ContextMenuSelection.Item(ObjectKind.PARAMETER, "p2")
        )), LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("red"),
            (palette, objectId, property, hex) -> saved.add(objectId + "=" + hex.orElse("clear")));

        assertEquals(List.of("p1=#E53935", "p2=#E53935"), saved);
    }

    @Test
    void missingObjectIsIgnored() {
        final FakeModel model = FakeModel.withParameter("p1");
        final List<String> saved = new ArrayList<>();

        apply(model, selection(Location.PARAMETER_TAB, ObjectKind.PARAMETER, "missing"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("red"), sink(saved));

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
        assertEquals(List.of(new NativeLabelColor.Custom(
            LabelStylePresets.parseHex("#E53935").orElseThrow())),
            model.drawable("mesh1").nativeLabelColors);
        assertEquals(List.of("text:#E53935"), model.group("folder1").entry.events());
        assertEquals(List.of("background:#E53935"), model.parameter("p1").entry.backgroundEvents());
    }

    @Test
    void replayUnknownObjectIsNoOp() {
        final FakeModel model = FakeModel.withParameter("p1");
        final LabelStyleApplier applier = new LabelStyleApplier();

        applier.replay(model, Location.PARAMETER_TAB, "missing", "text",
            LabelStylePresets.parseHex("#E53935").orElseThrow(), LabelStyleApplier.NOOP_SINK);

        assertTrue(model.parameter("p1").entry.events().isEmpty());
    }

    @Test
    void clearAllClosesEveryTrackedRegistration() {
        final FakeModel model = FakeModel.with("warp1");
        model.addPart("part1");
        final LabelStyleApplier applier = new LabelStyleApplier();

        applier.apply(model, selection(Location.DEFORMER_TAB, ObjectKind.WARP_DEFORMER, "warp1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("red"), LabelStyleApplier.NOOP_SINK);
        applier.apply(model, selection(Location.PART_TAB, ObjectKind.PART, "part1"),
            LabelStylePersistence.PROPERTY_TEXT, LabelStyleApplier.ColorChoice.preset("blue"), LabelStyleApplier.NOOP_SINK);
        applier.clearAll();

        assertEquals(List.of("text:#E53935", "text:closed"), model.deformer("warp1").partEntry.events());
        assertEquals(List.of("text:#2196F3", "text:closed"), model.part("part1").entry.events());
        assertTrue(applier.activeRegistrations().isEmpty());
    }

    private static void apply(
        final FakeModel model,
        final ContextMenuSelection selection,
        final String property,
        final LabelStyleApplier.ColorChoice choice
    ) {
        apply(model, selection, property, choice, LabelStyleApplier.NOOP_SINK);
    }

    private static void apply(
        final FakeModel model,
        final ContextMenuSelection selection,
        final String property,
        final LabelStyleApplier.ColorChoice choice,
        final LabelStyleApplier.ColorSink sink
    ) {
        new LabelStyleApplier().apply(model, selection, property, choice, sink);
    }

    private static LabelStyleApplier.ColorSink sink(final List<String> saved) {
        return (palette, objectId, property, hex) -> saved.add(hex.orElse("clear"));
    }

    private static ContextMenuSelection selection(
        final Location location,
        final ObjectKind kind,
        final String id
    ) {
        return new ContextMenuSelection(1L, "document-1", location,
            List.of(new ContextMenuSelection.Item(kind, id)));
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(); }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(); }
        };
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeModel implements CubismModel {
        private final List<FakeParameter> parameters = new ArrayList<>();
        private final List<FakeParameterGroup> groups = new ArrayList<>();
        private final List<FakePart> parts = new ArrayList<>();
        private final List<FakeDeformer> deformers = new ArrayList<>();
        private final List<FakeDrawable> drawables = new ArrayList<>();

        static FakeModel with(final String deformerId) {
            final FakeModel model = new FakeModel();
            model.addDeformer(deformerId);
            return model;
        }

        static FakeModel withParameter(final String id) {
            final FakeModel model = new FakeModel();
            model.addParameter(id);
            return model;
        }

        static FakeModel withPart(final String id) {
            final FakeModel model = new FakeModel();
            model.addPart(id);
            return model;
        }

        static FakeModel withGroupFolder(final String id) {
            final FakeModel model = new FakeModel();
            model.addGroupFolder(id);
            return model;
        }

        static FakeModel withArtMesh(final String id) {
            final FakeModel model = new FakeModel();
            model.addArtMesh(id);
            return model;
        }

        void addParameter(final String id) {
            parameters.add(new FakeParameter(id));
        }

        void addGroupFolder(final String id) {
            groups.add(new FakeParameterGroup(id));
        }

        void addPart(final String id) {
            parts.add(new FakePart(id));
        }

        void addDeformer(final String id) {
            deformers.add(new FakeDeformer(id));
        }

        void addArtMesh(final String id) {
            drawables.add(new FakeDrawable(id));
        }

        FakeParameter parameter(final String id) {
            return find(parameters, id);
        }

        FakeParameterGroup group(final String id) {
            return find(groups, id);
        }

        FakePart part(final String id) {
            return find(parts, id);
        }

        FakeDeformer deformer(final String id) {
            return find(deformers, id);
        }

        FakeDrawable drawable(final String id) {
            return find(drawables, id);
        }

        private static <T> T find(final List<T> values, final String id) {
            return values.stream()
                .filter(value -> String.valueOf(value).contains(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(id));
        }

        @Override public ModelId id() { return new ModelId("model-1"); }
        @Override public Parameters parameters() {
            return new Parameters() {
                @Override public List<Parameter> all() { return List.copyOf(parameters); }
                @Override public Parameter find(final ParameterId id) {
                    return parameters.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public ParameterGroups parameterGroups() {
            return new ParameterGroups() {
                @Override public List<ParameterGroup> all() { return List.copyOf(groups); }
                @Override public ParameterGroup root() { return groups.isEmpty() ? null : groups.get(0); }
                @Override public ParameterGroup find(ParameterGroupId id) {
                    return groups.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public Parts parts() {
            return new Parts() {
                @Override public List<Part> all() { return List.copyOf(parts); }
                @Override public Part find(PartId id) {
                    return parts.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public Deformers deformers() {
            return new Deformers() {
                @Override public List<Deformer> all() { return List.copyOf(deformers); }
                @Override public Deformer find(DeformerId id) {
                    return deformers.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public Drawables drawables() {
            return new Drawables() {
                @Override public List<Drawable> all() { return List.copyOf(drawables); }
                @Override public Drawable find(ArtMeshId id) {
                    return drawables.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public Glues glues() { throw new UnsupportedOperationException("not used"); }
        @Override public void update() { throw new UnsupportedOperationException("not used"); }
    }

    private static final class FakeParameter implements Parameter {
        final FakePaletteEntry entry = new FakePaletteEntry();
        private final String id;
        FakeParameter(final String id) { this.id = id; }
        @Override public ParameterId id() { return new ParameterId(id); }
        @Override public ParameterAppearance ui() {
            return () -> Optional.of(entry);
        }
        @Override public float getValue() { return 0.0F; }
        @Override public float getMinimumValue() { return -1.0F; }
        @Override public float getMaximumValue() { return 1.0F; }
        @Override public float getDefaultValue() { return 0.0F; }
        @Override public void setValue(final float value) { }
        @Override public String toString() { return "parameter:" + id; }
    }

    private static final class FakeParameterGroup implements ParameterGroup {
        final FakePaletteEntry entry = new FakePaletteEntry();
        private final String id;
        FakeParameterGroup(final String id) { this.id = id; }
        @Override public ParameterGroupId id() { return new ParameterGroupId(id); }
        @Override public ParameterGroupAppearance ui() {
            return new ParameterGroupAppearance() {
                @Override public Optional<PaletteEntry> parameterPaletteEntry() { return Optional.of(entry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
                @Override public void setNativeLabelColor(final NativeLabelColor color) { }
            };
        }
        @Override public Optional<String> name() { return Optional.of(id); }
        @Override public Optional<ParameterGroupId> parentId() { return Optional.empty(); }
        @Override public List<ParameterGroupId> childGroupIds() { return List.of(); }
        @Override public List<ParameterId> parameterIds() { return List.of(); }
        @Override public String toString() { return "group:" + id; }
    }

    private static final class FakePart implements Part {
        final FakePaletteEntry entry = new FakePaletteEntry();
        private final String id;
        FakePart(final String id) { this.id = id; }
        @Override public PartId id() { return new PartId(id); }
        @Override public PartAppearance ui() {
            return new PartAppearance() {
                @Override public Optional<PaletteEntry> partPaletteEntry() { return Optional.of(entry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
                @Override public void setNativeLabelColor(final NativeLabelColor color) { }
            };
        }
        @Override public void setName(final String name) { }
        @Override public float getOpacity() { return 1.0F; }
        @Override public int parentIndex() { return -1; }
        @Override public void setOpacity(final float opacity) { }
        @Override public String toString() { return "part:" + id; }
    }

    private static final class FakeDeformer implements Deformer {
        final FakePaletteEntry partEntry = new FakePaletteEntry();
        final FakePaletteEntry deformerEntry = new FakePaletteEntry();
        final List<NativeLabelColor> nativeLabelColors = new ArrayList<>();
        private final String id;
        FakeDeformer(final String id) { this.id = id; }
        @Override public DeformerId id() { return new DeformerId(id); }
        @Override public DeformerAppearance ui() {
            return new DeformerAppearance() {
                @Override public Optional<PaletteEntry> partPaletteEntry() { return Optional.of(partEntry); }
                @Override public Optional<PaletteEntry> deformerPaletteEntry() { return Optional.of(deformerEntry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
                @Override public void setNativeLabelColor(final NativeLabelColor color) { nativeLabelColors.add(color); }
            };
        }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return emptyInts(); }
        @Override public String toString() { return "deformer:" + id; }
    }

    private static final class FakeDrawable implements Drawable {
        final FakePaletteEntry partEntry = new FakePaletteEntry();
        final FakePaletteEntry deformerEntry = new FakePaletteEntry();
        final List<dev.turboism.sdk.ui.appearance.NativeLabelColor> nativeLabelColors = new ArrayList<>();
        private final String id;
        FakeDrawable(final String id) { this.id = id; }
        @Override public ArtMeshId id() { return new ArtMeshId(id); }
        @Override public DrawableAppearance ui() {
            return new DrawableAppearance() {
                @Override public Optional<PaletteEntry> partPaletteEntry() { return Optional.of(partEntry); }
                @Override public Optional<PaletteEntry> deformerPaletteEntry() { return Optional.of(deformerEntry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
                @Override public void setNativeLabelColor(dev.turboism.sdk.ui.appearance.NativeLabelColor color) { nativeLabelColors.add(color); }
            };
        }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public float getOpacity() { return 1.0F; }
        @Override public IntSequence masks() { return emptyInts(); }
        @Override public FloatSequence vertexPositions() { return emptyFloats(); }
        @Override public FloatSequence vertexUvs() { return emptyFloats(); }
        @Override public IntSequence indices() { return emptyInts(); }
        @Override public Color multiplyColor() { return new Color(1.0F, 1.0F, 1.0F, 1.0F); }
        @Override public Color screenColor() { return new Color(0.0F, 0.0F, 0.0F, 0.0F); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return emptyInts(); }
        @Override public String toString() { return "drawable:" + id; }
    }

    private static final class FakePaletteEntry implements PaletteEntry {
        private final List<String> events = new ArrayList<>();
        private final List<Registration> open = new ArrayList<>();

        List<String> events() { return List.copyOf(events); }

        List<String> textEvents() {
            return events.stream().filter(e -> e.startsWith("text:")).toList();
        }

        List<String> backgroundEvents() {
            return events.stream().filter(e -> e.startsWith("background:")).toList();
        }

        @Override public Registration overrideTextColor(final UiColor color) {
            return override("text", color);
        }

        @Override public Registration overrideBackgroundColor(final UiColor color) {
            return override("background", color);
        }

        private Registration override(final String property, final UiColor color) {
            events.add(property + ":" + LabelStylePresets.toHex(color));
            final Registration registration = () -> {
                open.remove(this);
                events.add(property + ":closed");
            };
            open.add(registration);
            return registration;
        }

        @Override public Registration overrideFontSize(final float points) { throw new UnsupportedOperationException("not used"); }
        @Override public Registration overrideBold(final boolean bold) { throw new UnsupportedOperationException("not used"); }
        @Override public Registration overrideItalic(final boolean italic) { throw new UnsupportedOperationException("not used"); }
        @Override public PaletteEntryState resolved() { return PaletteEntryState.empty(); }
        @Override public Optional<PaletteEntryState> actual() { return Optional.empty(); }
    }
}
