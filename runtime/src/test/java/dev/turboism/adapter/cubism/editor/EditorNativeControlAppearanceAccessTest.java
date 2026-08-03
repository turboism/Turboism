package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorNativeControlAppearanceReadSelectorContract;
import dev.turboism.mapping.verification.EditorNativeControlAppearanceWriteSelectorContract;
import dev.turboism.mapping.verification.EditorParameterGroupsReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.NativeControlAppearance;
import dev.turboism.sdk.ui.appearance.NativeControlBackground;
import dev.turboism.sdk.ui.appearance.PresetColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact-selector-gated Editor-native control label-background authoring. */
class EditorNativeControlAppearanceAccessTest {

    @AfterEach
    void clearHost() {
        Host.currentDocument = null;
        Host.completePack = null;
        Host.mainFrame = null;
    }

    @Test
    void parameterFolderReadsAndWritesTheCParameterGroupLabelColor() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"));

        NativeControlAppearance before = access.snapshot(folder);
        assertEquals(new NativeControlBackground.Preset(PresetColor.BLUE), before.background());
        assertEquals(Optional.of(new Color(0.25F, 0.5F, 0.75F, 1.0F)), before.effectiveBackground());

        access.setNativeBackground(
            folder,
            new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F))
        );

        assertEquals(new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F)),
            access.snapshot(folder).background());
        assertEquals(LabelColorType.CUSTOM, fixture.face.labelColor.type);
        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.editMode.committedEdits);
        assertEquals(1, fixture.document.dirtyUpdates);
        assertEquals(1, fixture.operation.refreshes);
        assertEquals(1, fixture.completePack.parameterRefreshes);
        assertEquals(1, fixture.completePack.canvasRepaints);
        assertEquals(0, fixture.completePack.partRefreshes);
        assertEquals(0, fixture.completePack.deformerRefreshes);

        fixture.editMode.undo();
        assertEquals(new NativeControlBackground.Preset(PresetColor.BLUE),
            access.snapshot(folder).background());
        fixture.editMode.redo();
        assertEquals(new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F)),
            access.snapshot(folder).background());

        access.setNativeBackground(folder,
            new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F)));
        assertEquals(1, fixture.editMode.beginCalls, "unchanged custom color must not create history");
    }

    @Test
    void partLabelAndPartFolderAliasTheSameCPartSourceLabelBackground() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.PartLabel label =
            new ControlAppearanceTarget.PartLabel(new PartId("PartA"));
        ControlAppearanceTarget.PartFolder folder =
            new ControlAppearanceTarget.PartFolder(new PartId("PartA"));

        assertEquals(new NativeControlBackground.Preset(PresetColor.RED),
            access.snapshot(label).background());
        access.setNativeBackground(
            folder,
            new NativeControlBackground.Preset(PresetColor.GREEN)
        );
        assertEquals(new NativeControlBackground.Preset(PresetColor.GREEN),
            access.snapshot(label).background());
        assertEquals(new NativeControlBackground.Preset(PresetColor.GREEN),
            access.snapshot(folder).background());
        assertEquals(LabelColorType.GREEN, fixture.partA.labelColor.type);
        assertEquals(0, fixture.completePack.parameterRefreshes);
        assertEquals(1, fixture.completePack.partRefreshes);
        assertEquals(1, fixture.completePack.canvasRepaints);

        fixture.editMode.undo();
        assertEquals(new NativeControlBackground.Preset(PresetColor.RED),
            access.snapshot(label).background());
        fixture.editMode.redo();
        assertEquals(new NativeControlBackground.Preset(PresetColor.GREEN),
            access.snapshot(folder).background());
    }

    @Test
    void deformerLabelAndDeformerControlRowAliasTheSameACDeformerSourceLabelBackground() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.DeformerLabel label =
            new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpA"));
        ControlAppearanceTarget.DeformerControlRow row =
            new ControlAppearanceTarget.DeformerControlRow(new DeformerId("WarpA"));

        assertEquals(new NativeControlBackground.Default(), access.snapshot(label).background());
        access.setNativeBackground(
            row,
            new NativeControlBackground.Preset(PresetColor.ORANGE)
        );
        assertEquals(new NativeControlBackground.Preset(PresetColor.ORANGE),
            access.snapshot(label).background());
        assertEquals(LabelColorType.ORANGE, fixture.warpA.labelColor.type);
        assertEquals(0, fixture.completePack.parameterRefreshes);
        assertEquals(0, fixture.completePack.partRefreshes);
        assertEquals(1, fixture.completePack.deformerRefreshes);
        assertEquals(1, fixture.completePack.canvasRepaints);
        assertEquals(1, fixture.document.dirtyUpdates);

        access.setNativeBackground(row, new NativeControlBackground.Default());
        assertEquals(LabelColorType.UNDEFINED, fixture.warpA.labelColor.type);
        assertEquals(new NativeControlBackground.Default(), access.snapshot(row).background());
        fixture.editMode.undo();
        assertEquals(new NativeControlBackground.Preset(PresetColor.ORANGE),
            access.snapshot(row).background());
    }

    @Test
    void customReadsTheCustomizedColorWhileEffectiveColorComesFromGetColor() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        fixture.face.labelColor.type = LabelColorType.CUSTOM;
        fixture.face.labelColor.custom = new HostColor(0.9F, 0.8F, 0.7F, 0.6F);
        fixture.face.labelColor.color = new HostColor(0.1F, 0.2F, 0.3F, 0.4F);

        NativeControlAppearance appearance = access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"))
        );
        assertEquals(
            new NativeControlBackground.Custom(new Color(0.9F, 0.8F, 0.7F, 0.6F)),
            appearance.background()
        );
        assertEquals(Optional.of(new Color(0.1F, 0.2F, 0.3F, 0.4F)), appearance.effectiveBackground());
    }

    @Test
    void defaultAndPresetWritesAreExactNoOpsWithoutCreatingHistory() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.type = LabelColorType.UNDEFINED;
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"));

        access.setNativeBackground(folder, new NativeControlBackground.Default());
        assertEquals(0, fixture.editMode.beginCalls, "Default on UNDEFINED must be an exact no-op");
        assertEquals(0, fixture.document.dirtyUpdates);

        fixture.face.labelColor.type = LabelColorType.BLUE;
        access.setNativeBackground(folder, new NativeControlBackground.Preset(PresetColor.BLUE));
        assertEquals(0, fixture.editMode.beginCalls, "Preset on the same preset must be an exact no-op");
        assertEquals(0, fixture.document.dirtyUpdates);

        access.setNativeBackground(folder, new NativeControlBackground.Preset(PresetColor.GREEN));
        assertEquals(1, fixture.editMode.beginCalls);
        assertEquals(1, fixture.document.dirtyUpdates);
        assertEquals(LabelColorType.GREEN, fixture.face.labelColor.type);

        access.setNativeBackground(folder, new NativeControlBackground.Preset(PresetColor.GREEN));
        assertEquals(1, fixture.editMode.beginCalls, "Preset on the same preset after a mode change must no-op");
    }

    @Test
    void customWriteIsAnExactNoOpWhenTypeAndRgbaAlreadyMatch() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.type = LabelColorType.CUSTOM;
        fixture.face.labelColor.custom = new HostColor(0.1F, 0.2F, 0.3F, 0.4F);
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"));

        access.setNativeBackground(
            folder,
            new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F))
        );
        assertEquals(0, fixture.editMode.beginCalls, "identical custom RGBA must be an exact no-op");
        assertEquals(0, fixture.document.dirtyUpdates);

        access.setNativeBackground(
            folder,
            new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.5F))
        );
        assertEquals(1, fixture.editMode.beginCalls, "different alpha must write");
    }

    @Test
    void modeChangesPreserveTheLatentCustomizedColor() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.custom = new HostColor(0.7F, 0.6F, 0.5F, 0.4F);
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"));

        access.setNativeBackground(folder, new NativeControlBackground.Preset(PresetColor.RED));
        assertEquals(LabelColorType.RED, fixture.face.labelColor.type);
        assertEquals(
            new HostColor(0.7F, 0.6F, 0.5F, 0.4F),
            fixture.face.labelColor.custom,
            "setLabelType must preserve the latent custom color"
        );

        access.setNativeBackground(folder, new NativeControlBackground.Default());
        assertEquals(LabelColorType.UNDEFINED, fixture.face.labelColor.type);
        assertEquals(
            new HostColor(0.7F, 0.6F, 0.5F, 0.4F),
            fixture.face.labelColor.custom,
            "Default restore must preserve the latent custom color"
        );
        assertEquals(2, fixture.editMode.beginCalls);
        assertEquals(2, fixture.document.dirtyUpdates);
    }

    @Test
    void postCheckRejectsAWronglyAppliedWriteFailClosed() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.rejectWrites = true;
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"));

        assertThrows(IllegalStateException.class, () -> access.setNativeBackground(
            folder, new NativeControlBackground.Preset(PresetColor.RED)
        ));
        assertEquals(0, fixture.document.dirtyUpdates, "failed write must not mark the document dirty");
        assertEquals(1, fixture.editMode.cancelledEnds, "the transaction must be cancelled");
        assertEquals(0, fixture.editMode.committedEdits, "no Undo history may be committed");
        assertEquals(
            LabelColorType.BLUE,
            fixture.face.labelColor.type,
            "the cancelled transaction must restore the original label color"
        );
    }

    @Test
    void snapshotFailsClosedWhenLabelColorIsReplacedDuringRead() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        final LabelColor original = fixture.face.labelColor;
        original.onRead = () -> fixture.face.labelColor = new LabelColor();
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );

        assertThrows(IllegalStateException.class, () -> access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"))
        ));
        assertEquals(LabelColorType.BLUE, original.type, "read must not mutate anything");
    }

    @Test
    void noOpWriteFailsClosedWhenLabelColorIsReplacedDuringRead() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.type = LabelColorType.BLUE;
        Host.install(fixture);
        final LabelColor original = fixture.face.labelColor;
        original.onRead = () -> fixture.face.labelColor = new LabelColor();
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"));

        assertThrows(IllegalStateException.class, () -> access.setNativeBackground(
            folder, new NativeControlBackground.Preset(PresetColor.BLUE)
        ));
        assertEquals(0, fixture.editMode.beginCalls, "a no-op must not open a transaction");
        assertEquals(0, fixture.face.labelColor.setterCalls);
        assertEquals(0, fixture.document.dirtyUpdates);
    }

    @Test
    void documentReplacementBetweenTargetResolutionAndMutationFailsClosedAndRestores() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        Fixture replaced = new Fixture("model-b");
        fixture.editMode.onBegin = () -> Host.install(replaced);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"));

        assertThrows(IllegalStateException.class, () -> access.setNativeBackground(
            folder, new NativeControlBackground.Preset(PresetColor.RED)
        ));

        assertEquals(
            0,
            fixture.face.labelColor.setterCalls,
            "the stale target must never reach the native setter"
        );
        assertEquals(
            LabelColorType.BLUE,
            fixture.face.labelColor.type,
            "the old label color must remain untouched"
        );
        assertEquals(0, fixture.editMode.committedEdits, "no wrong history may be committed");
        assertEquals(1, fixture.editMode.cancelledEnds);
        assertEquals(0, fixture.document.dirtyUpdates);
        assertEquals(
            0,
            replaced.document.dirtyUpdates,
            "the replacement document must not be marked dirty"
        );
        assertEquals(
            0,
            replaced.completePack.canvasRepaints,
            "the replacement complete pack must not be refreshed"
        );
        assertEquals(
            0,
            replaced.operation.refreshes,
            "the replacement parameter operation must not be refreshed"
        );
    }

    @Test
    void sameIdLabelColorReplacementAfterMutationFailsClosedAndRestores() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        final LabelColor original = fixture.face.labelColor;
        original.onMutated = () -> fixture.face.labelColor = new LabelColor();
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        ControlAppearanceTarget.ParameterFolder folder =
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"));

        assertThrows(IllegalStateException.class, () -> access.setNativeBackground(
            folder, new NativeControlBackground.Preset(PresetColor.RED)
        ));

        assertEquals(
            LabelColorType.BLUE,
            original.type,
            "the cancelled transaction must restore the mutated old label color"
        );
        assertEquals(0, fixture.editMode.committedEdits, "no wrong history may be committed");
        assertEquals(1, fixture.editMode.cancelledEnds);
        assertEquals(0, fixture.document.dirtyUpdates);
        assertEquals(
            0,
            fixture.completePack.canvasRepaints,
            "the UI refresh must not run against the replaced target"
        );
    }

    @Test
    void readOnlyCapabilityAllowsSnapshotButDeniesWrite() {
        Host.install(new Fixture("model-a"));
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolverReadOnly(), "session-a"
        );
        ControlAppearanceTarget.PartLabel target =
            new ControlAppearanceTarget.PartLabel(new PartId("PartA"));
        assertEquals(
            new NativeControlBackground.Preset(PresetColor.RED),
            access.snapshot(target).background()
        );
        assertThrows(UnsupportedOperationException.class, () -> access.setNativeBackground(
            target, new NativeControlBackground.Default()
        ));
    }

    @Test
    void writeOnlyCapabilityAllowsWriteButDeniesSnapshot() {
        Host.install(new Fixture("model-a"));
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolverWriteOnly(), "session-a"
        );
        ControlAppearanceTarget.PartLabel target =
            new ControlAppearanceTarget.PartLabel(new PartId("PartA"));
        assertThrows(UnsupportedOperationException.class, () -> access.snapshot(target));
        access.setNativeBackground(target, new NativeControlBackground.Preset(PresetColor.GREEN));
        assertEquals(LabelColorType.GREEN, Host.currentDocument.modelSource().parts().get(0).labelColor.type);
    }

    @Test
    void undefinedLabelWithNullHostColorSnapshotsDefaultWithUnavailableEffective() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.type = LabelColorType.UNDEFINED;
        fixture.face.labelColor.color = null;
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );

        NativeControlAppearance appearance = access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"))
        );

        assertEquals(new NativeControlBackground.Default(), appearance.background());
        assertEquals(Optional.empty(), appearance.effectiveBackground(),
            "UNDEFINED must report the effective background as unavailable, never a fabricated color");
    }

    @Test
    void presetLabelWithNullHostColorFailsClosed() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.type = LabelColorType.RED;
        fixture.face.labelColor.color = null;
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );

        assertThrows(IllegalStateException.class, () -> access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"))
        ));
    }

    @Test
    void customLabelWithNullHostColorFailsClosed() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.type = LabelColorType.CUSTOM;
        fixture.face.labelColor.custom = new HostColor(0.1F, 0.2F, 0.3F, 0.4F);
        fixture.face.labelColor.color = null;
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );

        assertThrows(IllegalStateException.class, () -> access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"))
        ));
    }

    @Test
    void nonNullWrongTypeHostColorStillFailsClosed() {
        Fixture fixture = new Fixture("model-a");
        fixture.face.labelColor.type = LabelColorType.UNDEFINED;
        fixture.face.labelColor.wrongTypeColor = new Id("not-a-color");
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );

        assertThrows(IllegalStateException.class, () -> access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"))
        ));
    }

    @Test
    void missingDuplicateAndStaleTargetsFailClosedBeforeMutation() {
        Fixture fixture = new Fixture("model-a");
        Host.install(fixture);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        assertThrows(NoSuchElementException.class, () -> access.snapshot(
            new ControlAppearanceTarget.PartLabel(new PartId("PartMissing"))
        ));
        assertThrows(NoSuchElementException.class, () -> access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupMissing"))
        ));
        assertThrows(NoSuchElementException.class, () -> access.snapshot(
            new ControlAppearanceTarget.DeformerLabel(new DeformerId("WarpMissing"))
        ));

        fixture.partA.id = new Id("PartB");
        assertThrows(NoSuchElementException.class, () -> access.snapshot(
            new ControlAppearanceTarget.PartLabel(new PartId("PartA"))
        ));

        Fixture replaced = new Fixture("model-a");
        replaced.rootGroup.children.add(0, new ParameterGroup("GroupDup", replaced.rootGroup));
        replaced.rootGroup.children.add(1, new ParameterGroup("GroupDup", replaced.rootGroup));
        Host.install(replaced);
        assertThrows(IllegalStateException.class, () -> access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"))
        ));

        Host.currentDocument = null;
        assertThrows(IllegalStateException.class, () -> access.snapshot(
            new ControlAppearanceTarget.ParameterFolder(new ParameterGroupId("GroupFace"))
        ));
    }

    @Test
    void parameterLabelIsOverlayOnlyAndFailsClosed() {
        Host.install(new Fixture("model-a"));
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(true), "session-a"
        );
        assertThrows(UnsupportedOperationException.class, () -> access.snapshot(
            new ControlAppearanceTarget.ParameterLabel(
                new dev.turboism.sdk.cubism.id.ParameterId("ParamA")
            )
        ));
        assertThrows(UnsupportedOperationException.class, () -> access.setNativeBackground(
            new ControlAppearanceTarget.ParameterLabel(
                new dev.turboism.sdk.cubism.id.ParameterId("ParamA")
            ),
            new NativeControlBackground.Default()
        ));
        assertEquals(0, Host.INSTANCE.beginCalls());
    }

    @Test
    void readsAndWritesFailClosedWithoutTheirSeparateVerifiedCapability() {
        Host.install(new Fixture("model-a"));
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(false), "session-a"
        );
        ControlAppearanceTarget.PartLabel target =
            new ControlAppearanceTarget.PartLabel(new PartId("PartA"));
        assertThrows(UnsupportedOperationException.class, () -> access.snapshot(target));
        assertThrows(UnsupportedOperationException.class, () -> access.setNativeBackground(
            target, new NativeControlBackground.Default()
        ));
        assertEquals(0, Host.INSTANCE.beginCalls());
        assertEquals(0, Host.INSTANCE.currentDocument().dirtyUpdates);
    }

    private static VerifiedMemberResolver resolverReadOnly() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            java.util.Set.of(
                "cubism.editor-model.read",
                "cubism.editor-model.write",
                EditorNativeControlAppearanceReadSelectorContract.CAPABILITY_ID
            ),
            selectors(),
            Host.class.getClassLoader()
        );
    }

    private static VerifiedMemberResolver resolverWriteOnly() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            java.util.Set.of(
                "cubism.editor-model.read",
                "cubism.editor-model.write",
                EditorNativeControlAppearanceWriteSelectorContract.CAPABILITY_ID
            ),
            selectors(),
            Host.class.getClassLoader()
        );
    }

    private static VerifiedMemberResolver resolver(final boolean authorized) {
        final String host = internal(Host.class);
        final String document = internal(Document.class);
        final String source = internal(ModelSource.class);
        final String model = internal(Model.class);
        final String id = internal(Id.class);
        final String group = internal(ParameterGroup.class);
        final String partSource = internal(PartSource.class);
        final String partId = internal(CPartId.class);
        final String deformerSource = internal(DeformerSource.class);
        final String controllableSource = internal(ParameterControllableSource.class);
        final String labelColor = internal(LabelColor.class);
        final String labelColorType = internal(LabelColorType.class);
        final String color = internal(HostColor.class);
        final String completePack = internal(CompletePack.class);
        final String mainFrame = internal(MainFrame.class);
        final String palette = internal(ParameterPalette.class);
        final String paletteView = internal(ParameterPaletteView.class);
        final String operation = internal(ParameterOperation.class);
        final String editMode = internal(EditMode.class);
        final String undo = internal(Undo.class);
        final String copyable = internal(Copyable.class);
        final String undoListener = internal(UndoListener.class);
        final String simpleUndo = internal(SimpleUndo.class);
        final java.util.Set<String> capabilities = java.util.Set.of(
            "cubism.editor-model.read",
            "cubism.editor-model.write",
            EditorParameterGroupsReadSelectorContract.CAPABILITY_ID,
            EditorNativeControlAppearanceReadSelectorContract.CAPABILITY_ID,
            EditorNativeControlAppearanceWriteSelectorContract.CAPABILITY_ID
        );
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            authorized ? capabilities : java.util.Set.of("cubism.editor-model.read", "cubism.editor-model.write"),
            selectors(),
            Host.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        String host = internal(Host.class);
        String document = internal(Document.class);
        String source = internal(ModelSource.class);
        String model = internal(Model.class);
        String id = internal(Id.class);
        String group = internal(ParameterGroup.class);
        String partSource = internal(PartSource.class);
        String partId = internal(CPartId.class);
        String deformerSource = internal(DeformerSource.class);
        String controllableSource = internal(ParameterControllableSource.class);
        String labelColor = internal(LabelColor.class);
        String labelColorType = internal(LabelColorType.class);
        String color = internal(HostColor.class);
        String completePack = internal(CompletePack.class);
        String mainFrame = internal(MainFrame.class);
        String palette = internal(ParameterPalette.class);
        String paletteView = internal(ParameterPaletteView.class);
        String operation = internal(ParameterOperation.class);
        String editMode = internal(EditMode.class);
        String undo = internal(Undo.class);
        String copyable = internal(Copyable.class);
        String undoListener = internal(UndoListener.class);
        String simpleUndo = internal(SimpleUndo.class);
        return List.of(
            StaticSelector.classSelector("cubism.editor-model.app-controller.class", host),
            StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", host, "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
            method("cubism.editor-model.app-controller.complete-pack", Host.class, "completePack", desc(CompletePack.class)),
            method("cubism.editor-model.app-controller.main-frame", Host.class, "mainFrame", desc(MainFrame.class)),
            StaticSelector.classSelector("cubism.editor-model.modeling-document.class", document),
            method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
            method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
            method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
            method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
            method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
            method("cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)),
            method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"),
            StaticSelector.classSelector("cubism.editor-model.model-source.class", source),
            method("cubism.editor-model.model-source.root-parameter-group", ModelSource.class, "rootParameterGroup", desc(ParameterGroup.class)),
            method("cubism.editor-model.model-source.parts", ModelSource.class, "parts", "()Ljava/util/List;"),
            method("cubism.editor-model.model-source.all-deformers", ModelSource.class, "allDeformers", "()Ljava/util/List;"),
            StaticSelector.classSelector("cubism.editor-model.model.class", model),
            StaticSelector.classSelector("cubism.editor-model.parameter-group.class", group),
            method("cubism.editor-model.parameter-group.id", ParameterGroup.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-group.children", ParameterGroup.class, "children", "()Ljava/util/List;"),
            method("cubism.editor-model.parameter-group.label-color", ParameterGroup.class, "labelColor", desc(LabelColor.class)),
            StaticSelector.classSelector("cubism.editor-model.part-source.class", partSource),
            method("cubism.editor-model.part-source.id", PartSource.class, "id", desc(CPartId.class)),
            StaticSelector.classSelector("cubism.editor-model.part-id.class", partId),
            method("cubism.editor-model.part-id.value", CPartId.class, "value", "()Ljava/lang/String;"),
            StaticSelector.classSelector("cubism.editor-model.deformer-source.class", deformerSource),
            StaticSelector.classSelector("cubism.editor-model.parameter-controllable-source.class", controllableSource),
            method("cubism.editor-model.parameter-controllable-source.id", DeformerSource.class, "id", desc(Id.class)),
            method("cubism.editor-model.parameter-controllable-source.label-color", ParameterControllableSource.class, "labelColor", desc(LabelColor.class)),
            StaticSelector.classSelector("cubism.editor-model.label-color.class", labelColor),
            method("cubism.editor-model.label-color.label-type", LabelColor.class, "getLabelType", desc(LabelColorType.class)),
            method("cubism.editor-model.label-color.customized-color", LabelColor.class, "getCustomizedColor", desc(HostColor.class)),
            method("cubism.editor-model.label-color.color", LabelColor.class, "getColor", "()Ljava/lang/Object;"),
            method("cubism.editor-model.label-color.set-color", LabelColor.class, "setColor", "(L" + labelColorType + ";L" + color + ";)V"),
            method("cubism.editor-model.label-color.set-label-type", LabelColor.class, "setLabelType", "(L" + labelColorType + ";)V"),
            StaticSelector.classSelector("cubism.editor-model.label-color-type.class", labelColorType),
            StaticSelector.field("cubism.editor-model.label-color-type.undefined", labelColorType, "UNDEFINED", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.field("cubism.editor-model.label-color-type.custom", labelColorType, "CUSTOM", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.field("cubism.editor-model.label-color-type.red", labelColorType, "RED", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.field("cubism.editor-model.label-color-type.orange", labelColorType, "ORANGE", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.field("cubism.editor-model.label-color-type.yellow", labelColorType, "YELLOW", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.field("cubism.editor-model.label-color-type.green", labelColorType, "GREEN", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.field("cubism.editor-model.label-color-type.blue", labelColorType, "BLUE", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.field("cubism.editor-model.label-color-type.purple", labelColorType, "PURPLE", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.field("cubism.editor-model.label-color-type.gray", labelColorType, "GRAY", "L" + labelColorType + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            StaticSelector.classSelector("cubism.editor-model.color.class", color),
            StaticSelector.constructor("cubism.editor-model.color.create", color, "(FFFF)V", StaticSelector.ACCESS_PUBLIC),
            method("cubism.editor-model.color.red", HostColor.class, "red", "()F"),
            method("cubism.editor-model.color.green", HostColor.class, "green", "()F"),
            method("cubism.editor-model.color.blue", HostColor.class, "blue", "()F"),
            method("cubism.editor-model.color.alpha", HostColor.class, "alpha", "()F"),
            StaticSelector.classSelector("cubism.editor-model.complete-pack.class", completePack),
            method("cubism.editor-model.complete-pack.update-parameter", CompletePack.class, "updateParameter", "(Z)V"),
            method("cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updatePartPalette", "(Z)V"),
            method("cubism.editor-model.complete-pack.update-deformer-palette", CompletePack.class, "updateDeformerPalette", "(Z)V"),
            method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaintCanvas", "(Z)V"),
            StaticSelector.classSelector("cubism.editor-model.main-frame.class", mainFrame),
            method("cubism.editor-model.main-frame.parameter-palette", MainFrame.class, "parameterPalette", desc(ParameterPalette.class)),
            StaticSelector.classSelector("cubism.editor-model.parameter-palette.class", palette),
            method("cubism.editor-model.parameter-palette.view", ParameterPalette.class, "view", desc(ParameterPaletteView.class)),
            StaticSelector.classSelector("cubism.editor-model.parameter-palette-view.class", paletteView),
            method("cubism.editor-model.parameter-palette-view.operation", ParameterPaletteView.class, "operation", desc(ParameterOperation.class)),
            StaticSelector.classSelector("cubism.editor-model.parameter-operation.class", operation),
            method("cubism.editor-model.parameter-operation.refresh", ParameterOperation.class, "refresh", "(Z)V"),
            StaticSelector.classSelector("cubism.editor-model.edit-mode.class", editMode),
            method("cubism.editor-model.edit-mode.begin", EditMode.class, "begin", "(Ljava/lang/String;)L" + undo + ";"),
            method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)Z"),
            StaticSelector.classSelector("cubism.editor-model.undo.class", undo),
            method("cubism.editor-model.undo.add", Undo.class, "add", "(Ljava/lang/Object;Z)Z"),
            method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(L" + undoListener + ";)Z"),
            StaticSelector.classSelector("cubism.editor-model.undo-listener.class", undoListener),
            StaticSelector.constructor("cubism.editor-model.simple-undo.create", simpleUndo, "(Ljava/lang/String;L" + copyable + ";Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC)
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            internal(owner),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    public interface Copyable {
        Object snapshot();

        void restore(Object snapshot);
    }

    public interface UndoListener {
        void executed(Object event);
    }

    public record Id(String value) {
        public String value() { return value; }
    }

    public static final class Model {
    }

    public enum LabelColorType {
        UNDEFINED,
        CUSTOM,
        RED,
        ORANGE,
        YELLOW,
        GREEN,
        BLUE,
        PURPLE,
        GRAY
    }

    public record HostColor(float red, float green, float blue, float alpha) {
        public HostColor {
            if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)
                || !Float.isFinite(alpha)) {
                throw new IllegalArgumentException("invalid color");
            }
        }
    }

    public static final class LabelColor implements Copyable {
        LabelColorType type = LabelColorType.UNDEFINED;
        HostColor color = new HostColor(0.25F, 0.5F, 0.75F, 1.0F);
        HostColor custom = new HostColor(0.25F, 0.5F, 0.75F, 1.0F);

        public LabelColorType getLabelType() {
            if (onRead != null) {
                onRead.run();
            }
            return type;
        }

        public HostColor getCustomizedColor() { return custom; }

        Object wrongTypeColor;
        public Object getColor() { return wrongTypeColor != null ? wrongTypeColor : color; }

        boolean rejectWrites;
        int setterCalls;
        Runnable onRead;
        Runnable onMutated;

        public void setLabelType(final LabelColorType nextType) {
            setterCalls++;
            if (rejectWrites) {
                return;
            }
            type = nextType;
            if (onMutated != null) {
                onMutated.run();
            }
        }

        public void setColor(final LabelColorType nextType, final HostColor nextColor) {
            setterCalls++;
            if (rejectWrites) {
                return;
            }
            type = nextType;
            if (nextType == LabelColorType.CUSTOM) {
                custom = nextColor;
            }
            color = nextColor;
            if (onMutated != null) {
                onMutated.run();
            }
        }

        @Override public Object snapshot() { return new State(type, custom, color); }

        @Override public void restore(final Object snapshot) {
            State state = (State) snapshot;
            type = state.type;
            custom = state.custom;
            color = state.color;
        }

        private record State(LabelColorType type, HostColor custom, HostColor color) {
        }
    }

    public static final class ParameterGroup {
        final Id id;
        final ParameterGroup parent;
        LabelColor labelColor = new LabelColor();
        final List<Object> children = new ArrayList<>();
        ParameterGroup(final String id, final ParameterGroup parent) {
            this.id = new Id(id);
            this.parent = parent;
        }
        public Id id() { return id; }
        public List<Object> children() { return children; }
        public LabelColor labelColor() { return labelColor; }
    }

    public static final class CPartId {
        final String value;
        CPartId(final String value) { this.value = value; }
        public String value() { return value; }
    }

    public abstract static class ParameterControllableSource {
        final LabelColor labelColor = new LabelColor();
        public LabelColor labelColor() { return labelColor; }
    }

    public static final class PartSource extends ParameterControllableSource {
        Id id;
        PartSource(final String id) { this.id = new Id(id); }
        public CPartId id() { return new CPartId(id.value); }
    }

    public static final class DeformerSource extends ParameterControllableSource {
        final Id id;
        DeformerSource(final String id) { this.id = new Id(id); }
        public Id id() { return id; }
    }

    public static final class ModelSource {
        final Id guid;
        final Model model = new Model();
        final ParameterGroup root;
        final List<PartSource> parts = new ArrayList<>();
        final List<DeformerSource> deformers = new ArrayList<>();
        ModelSource(final String id) {
            guid = new Id(id);
            root = new ParameterGroup("GroupRoot", null);
        }
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public ParameterGroup rootParameterGroup() { return root; }
        public List<PartSource> parts() { return parts; }
        public List<DeformerSource> allDeformers() { return deformers; }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode;
        int dirtyUpdates;
        Document(final ModelSource source, final EditMode editMode) {
            this.source = source;
            this.editMode = editMode;
        }
        public ModelSource modelSource() { return source; }
        public EditMode editMode() { return editMode; }
        public void markDirty() { dirtyUpdates++; }
    }

    public static final class CompletePack {
        int parameterRefreshes;
        int partRefreshes;
        int deformerRefreshes;
        int canvasRepaints;
        public void updateParameter(final boolean immediate) { parameterRefreshes++; }
        public void updatePartPalette(final boolean immediate) { partRefreshes++; }
        public void updateDeformerPalette(final boolean immediate) { deformerRefreshes++; }
        public void repaintCanvas(final boolean immediate) { canvasRepaints++; }
    }

    public static final class ParameterOperation {
        int refreshes;
        public void refresh(final boolean immediate) { refreshes++; }
    }

    public static final class ParameterPaletteView {
        final ParameterOperation operation;
        ParameterPaletteView(final ParameterOperation operation) { this.operation = operation; }
        public ParameterOperation operation() { return operation; }
    }

    public static final class ParameterPalette {
        final ParameterPaletteView view;
        ParameterPalette(final ParameterPaletteView view) { this.view = view; }
        public ParameterPaletteView view() { return view; }
    }

    public static final class MainFrame {
        final ParameterPalette palette;
        MainFrame(final ParameterPalette palette) { this.palette = palette; }
        public ParameterPalette parameterPalette() { return palette; }
    }

    public static final class SimpleUndo {
        final Copyable target;
        final Object before;
        Object after;
        public SimpleUndo(final String name, final Copyable target, final Object context) {
            this.target = target;
            this.before = target.snapshot();
        }
        void undo() {
            after = target.snapshot();
            target.restore(before);
        }
        void restoreBefore() { target.restore(before); }
        void redo() { target.restore(after); }
    }

    public static final class Undo {
        final List<SimpleUndo> edits = new ArrayList<>();
        final List<UndoListener> listeners = new ArrayList<>();
        public boolean add(final Object raw, final boolean force) {
            edits.add((SimpleUndo) raw);
            return force;
        }
        public boolean addListener(final UndoListener listener) {
            listeners.add(listener);
            return true;
        }
        void undo() {
            for (int index = edits.size() - 1; index >= 0; index--) edits.get(index).undo();
            listeners.forEach(listener -> listener.executed(null));
        }
        void restoreAll() {
            for (int index = edits.size() - 1; index >= 0; index--) edits.get(index).restoreBefore();
        }
        void redo() {
            edits.forEach(SimpleUndo::redo);
            listeners.forEach(listener -> listener.executed(null));
        }
    }

    public static final class EditMode {
        int beginCalls;
        int committedEdits;
        int cancelledEnds;
        Runnable onBegin;
        Undo active;
        Undo committed;
        public Undo begin(final String action) {
            beginCalls++;
            active = new Undo();
            if (onBegin != null) {
                onBegin.run();
            }
            return active;
        }
        public boolean end(final boolean cancelled, final Object callback) {
            if (!cancelled) {
                committedEdits++;
                committed = active;
            } else {
                cancelledEnds++;
                if (active != null) {
                    active.restoreAll();
                }
            }
            active = null;
            return !cancelled;
        }
        void undo() { committed.undo(); }
        void redo() { committed.redo(); }
    }

    public static final class Host {
        static final Host INSTANCE = new Host();
        static Document currentDocument;
        static CompletePack completePack;
        static MainFrame mainFrame;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return currentDocument; }
        public CompletePack completePack() { return completePack; }
        public MainFrame mainFrame() { return mainFrame; }
        int beginCalls() { return currentDocument == null ? 0 : currentDocument.editMode.beginCalls; }
        static void install(final Fixture fixture) {
            currentDocument = fixture.document;
            completePack = fixture.completePack;
            mainFrame = fixture.mainFrame;
        }
    }

    static final class Fixture {
        final ModelSource source;
        final ParameterGroup rootGroup;
        final ParameterGroup face;
        final PartSource partA;
        final DeformerSource warpA;
        final EditMode editMode = new EditMode();
        final Document document;
        final CompletePack completePack = new CompletePack();
        final ParameterOperation operation = new ParameterOperation();
        final MainFrame mainFrame = new MainFrame(
            new ParameterPalette(new ParameterPaletteView(operation))
        );
        Fixture(final String id) {
            source = new ModelSource(id);
            rootGroup = source.root;
            face = new ParameterGroup("GroupFace", rootGroup);
            face.labelColor.type = LabelColorType.BLUE;
            rootGroup.children.add(face);
            partA = new PartSource("PartA");
            partA.labelColor.type = LabelColorType.RED;
            source.parts.add(partA);
            warpA = new DeformerSource("WarpA");
            source.deformers.add(warpA);
            document = new Document(source, editMode);
        }
    }
}
