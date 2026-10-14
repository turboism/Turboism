package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AtlasPageAppendPlan;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.ModelImagePlacement;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EditorTextureAccessAppendAtlasTest {

    private static final String VERSION = "5.3.04";
    private static final String IDENTITY = "session-a:model-1:1";
    private String originalKillSwitch;

    @BeforeEach
    void setUp() {
        originalKillSwitch = System.getProperty(HostUndoMutationScope.ENABLED_PROPERTY);
        System.clearProperty(HostUndoMutationScope.ENABLED_PROPERTY);
        Undo.rejectListener = false;
        Undo.throwOnForceRedo = false;
        Undo.undoCount = 0;
        GroupUndo.rejectUndo = false;
    }

    @AfterEach
    void tearDown() {
        Undo.rejectListener = false;
        Undo.throwOnForceRedo = false;
        Undo.undoCount = 0;
        GroupUndo.rejectUndo = false;
        if (originalKillSwitch != null) {
            System.setProperty(HostUndoMutationScope.ENABLED_PROPERTY, originalKillSwitch);
        } else {
            System.clearProperty(HostUndoMutationScope.ENABLED_PROPERTY);
        }
    }

    @Test
    void preservesOldPagesAndAppendsNewPageWithExactPlacementsAndIds() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures("session-a:model-1:1", fixture.source, fixture.model);

        assertTrue(textures.supportsAppendTextureAtlasPages());
        assertEquals(1, textures.textureAtlases().size());
        final AtlasTexture oldPage = textures.textureAtlases().get(0);
        assertEquals(new TextureAtlasId("atlas-1"), oldPage.id());
        assertEquals(2048, oldPage.width());
        assertEquals(1024, oldPage.height());

        // Add model image 2 (256x256)
        final HostModelImage image2 = new HostModelImage("image-2", "Image B", 256, 256);
        fixture.manager.allModelImages.add(image2);

        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                1024,
                1024,
                1,
                List.of("Appended Page 2"),
                List.of(new ModelImagePlacement(new ModelImageId("image-2"), 0, 10, 20, 256, 256)));

        final List<TextureAtlasId> createdIds = textures.appendTextureAtlasPages(plan);
        assertEquals(1, createdIds.size());
        final TextureAtlasId newId = createdIds.get(0);

        final List<AtlasTexture> atlases = textures.textureAtlases();
        assertEquals(2, atlases.size());
        // Verify page 0 is identical
        assertEquals(oldPage.id(), atlases.get(0).id());
        assertEquals(oldPage.name(), atlases.get(0).name());
        assertEquals(oldPage.width(), atlases.get(0).width());
        assertEquals(oldPage.height(), atlases.get(0).height());

        // Verify page 1 is appended
        assertEquals(newId, atlases.get(1).id());
        assertEquals("Appended Page 2", atlases.get(1).name());
        assertEquals(1024, atlases.get(1).width());
        assertEquals(1024, atlases.get(1).height());

        // Verify undo reverts to 1 page
        fixture.editMode.undo();
        assertEquals(1, textures.textureAtlases().size());
        assertEquals(oldPage.id(), textures.textureAtlases().get(0).id());

        // Verify redo restores 2 pages
        fixture.editMode.redo();
        assertEquals(2, textures.textureAtlases().size());
        assertEquals(newId, textures.textureAtlases().get(1).id());
    }

    @Test
    void appendsFromZeroAtlas() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        fixture.manager.textureAtlases.clear(); // zero atlas state
        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures("session-a:model-1:1", fixture.source, fixture.model);

        assertTrue(textures.supportsAppendTextureAtlasPages());
        assertTrue(textures.textureAtlases().isEmpty());

        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                512,
                512,
                2,
                List.of("Page One", "Page Two"),
                List.of(new ModelImagePlacement(new ModelImageId("image-1"), 0, 0, 0, 256, 128)));

        final List<TextureAtlasId> createdIds = textures.appendTextureAtlasPages(plan);
        assertEquals(2, createdIds.size());

        final List<AtlasTexture> atlases = textures.textureAtlases();
        assertEquals(2, atlases.size());
        assertEquals(createdIds.get(0), atlases.get(0).id());
        assertEquals("Page One", atlases.get(0).name());
        assertEquals(512, atlases.get(0).width());
        assertEquals(createdIds.get(1), atlases.get(1).id());
        assertEquals("Page Two", atlases.get(1).name());
        assertEquals(512, atlases.get(1).height());

        // Undo restores empty
        fixture.editMode.undo();
        assertTrue(textures.textureAtlases().isEmpty());

        // Redo restores 2 pages
        fixture.editMode.redo();
        assertEquals(2, textures.textureAtlases().size());
    }

    @Test
    void relinkBindsArtMeshesToTextureAtlasRegionAndRestoresOnUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures("session-a:model-1:1", fixture.source, fixture.model);

        // Add ArtMesh 2 bound to image-2 (MODEL_IMAGE)
        final HostModelImage image2 = new HostModelImage("image-2", "Image B", 128, 128);
        fixture.manager.allModelImages.add(image2);
        final HostArtMesh mesh2 = new HostArtMesh("mesh-2", image2);
        fixture.source.artMeshes.add(mesh2);

        final Optional<ArtMeshTextureInput> inputBefore = textures.textureInput(new ArtMeshId("mesh-2"));
        assertTrue(inputBefore.isPresent());
        assertEquals(ArtMeshTextureInput.Kind.MODEL_IMAGE, inputBefore.get().kind());
        assertEquals(Optional.of(new ModelImageId("image-2")), inputBefore.get().modelImageId());

        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                1024, 1024, 1, List.of(new ModelImagePlacement(new ModelImageId("image-2"), 0, 50, 50, 128, 128)));

        final List<TextureAtlasId> createdIds = textures.appendTextureAtlasPages(plan);
        assertEquals(1, createdIds.size());

        // Verify mesh-2 is now bound to TEXTURE_ATLAS_REGION on the new page
        final Optional<ArtMeshTextureInput> inputAfter = textures.textureInput(new ArtMeshId("mesh-2"));
        assertTrue(inputAfter.isPresent());
        assertEquals(
                ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION, inputAfter.get().kind());
        assertEquals(Optional.of(createdIds.get(0)), inputAfter.get().textureAtlasId());

        // Execute Undo -> mesh-2 restores to MODEL_IMAGE
        fixture.editMode.undo();
        final Optional<ArtMeshTextureInput> inputUndone = textures.textureInput(new ArtMeshId("mesh-2"));
        assertTrue(inputUndone.isPresent());
        assertEquals(ArtMeshTextureInput.Kind.MODEL_IMAGE, inputUndone.get().kind());
        assertEquals(Optional.of(new ModelImageId("image-2")), inputUndone.get().modelImageId());

        // Execute Redo -> mesh-2 returns to TEXTURE_ATLAS_REGION
        fixture.editMode.redo();
        final Optional<ArtMeshTextureInput> inputRedone = textures.textureInput(new ArtMeshId("mesh-2"));
        assertTrue(inputRedone.isPresent());
        assertEquals(
                ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION, inputRedone.get().kind());
        assertEquals(Optional.of(createdIds.get(0)), inputRedone.get().textureAtlasId());
    }

    @Test
    void joinsAmbientAuthoringTransactionAndRollsBackOnTransactionFailure() {
        EditorHostThread.dispatch("joinsAmbientAuthoringTransactionAndRollsBackOnTransactionFailure", () -> {
            final Fixture fixture = new Fixture();
            Host.document = fixture.document;

            final TestTxHost txHost = new TestTxHost(fixture.document, fixture.source, fixture.model);
            final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(txHost);

            final EditorTextureAccess access = createAccess(VERSION, true, coordinator);
            final ModelTextures textures = access.textures(IDENTITY, fixture.source, fixture.model);

            final HostModelImage image2 = new HostModelImage("image-2", "Image B", 128, 128);
            fixture.manager.allModelImages.add(image2);
            final HostArtMesh mesh2 = new HostArtMesh("mesh-2", image2);
            fixture.source.artMeshes.add(mesh2);

            final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                    1024, 1024, 1, List.of(new ModelImagePlacement(new ModelImageId("image-2"), 0, 10, 10, 128, 128)));

            final var binding = new EditorAuthoringTransactionCoordinator.Binding(
                    "test-plugin", "doc-1", 1L, "model-1", 1L, Thread.currentThread());

            // Run transaction that performs append and then throws
            final AuthoringTransactionResult<Void> result =
                    coordinator.execute(binding, AuthoringTransactionOptions.of("Failing Import"), () -> {
                        final List<TextureAtlasId> newPages = textures.appendTextureAtlasPages(plan);
                        assertEquals(1, newPages.size());
                        assertEquals(2, textures.textureAtlases().size());
                        throw new RuntimeException("Simulated import failure");
                    });

            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());

            // Verify complete rollback: atlases reverted to 1
            assertEquals(1, textures.textureAtlases().size());
            assertEquals(
                    new TextureAtlasId("atlas-1"),
                    textures.textureAtlases().get(0).id());

            // Verify mesh-2 input restored to MODEL_IMAGE
            final Optional<ArtMeshTextureInput> input = textures.textureInput(new ArtMeshId("mesh-2"));
            assertTrue(input.isPresent());
            assertEquals(ArtMeshTextureInput.Kind.MODEL_IMAGE, input.get().kind());
            return null;
        });
    }

    @Test
    void standaloneAppendRevertsNativeStateWhenUndoListenerIsRejected() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures(IDENTITY, fixture.source, fixture.model);

        final HostModelImage image2 = new HostModelImage("image-2", "Image B", 128, 128);
        fixture.manager.allModelImages.add(image2);
        final HostArtMesh mesh2 = new HostArtMesh("mesh-2", image2);
        fixture.source.artMeshes.add(mesh2);

        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                1024, 1024, 1, List.of(new ModelImagePlacement(new ModelImageId("image-2"), 0, 10, 10, 128, 128)));

        Undo.rejectListener = true;
        try {
            final IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> textures.appendTextureAtlasPages(plan));
            assertTrue(ex.getMessage().contains("rejected the texture-atlas Undo/Redo refresh listener"));

            // Verify rollback: atlas pages restored to before-state (1 page)
            assertEquals(1, textures.textureAtlases().size());
            assertEquals(
                    new TextureAtlasId("atlas-1"),
                    textures.textureAtlases().get(0).id());

            // Verify mesh-2 remains MODEL_IMAGE
            final Optional<ArtMeshTextureInput> input = textures.textureInput(new ArtMeshId("mesh-2"));
            assertTrue(input.isPresent());
            assertEquals(ArtMeshTextureInput.Kind.MODEL_IMAGE, input.get().kind());
            assertTrue(fixture.editMode.lastAborted, "Edit mode must be ended with abort=true");
        } finally {
            Undo.rejectListener = false;
        }
    }

    @Test
    void standaloneAppendRevertsNativeStateWhenRegisterAppliedUndoFails() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures(IDENTITY, fixture.source, fixture.model);

        final HostModelImage image2 = new HostModelImage("image-2", "Image B", 128, 128);
        fixture.manager.allModelImages.add(image2);
        final HostArtMesh mesh2 = new HostArtMesh("mesh-2", image2);
        fixture.source.artMeshes.add(mesh2);

        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                1024, 1024, 1, List.of(new ModelImagePlacement(new ModelImageId("image-2"), 0, 10, 10, 128, 128)));

        GroupUndo.rejectUndo = true;
        try {
            final IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> textures.appendTextureAtlasPages(plan));
            assertTrue(ex.getMessage().contains("rejected the texture Undo entry"));

            assertEquals(1, Undo.undoCount, "Exactly 1 native undo must be invoked, no double-undo");
            // Verify rollback: atlas pages restored to before-state (1 page) without double-undo corruption
            assertEquals(1, textures.textureAtlases().size());
            assertEquals(
                    new TextureAtlasId("atlas-1"),
                    textures.textureAtlases().get(0).id());

            // Verify mesh-2 remains MODEL_IMAGE
            final Optional<ArtMeshTextureInput> input = textures.textureInput(new ArtMeshId("mesh-2"));
            assertTrue(input.isPresent());
            assertEquals(ArtMeshTextureInput.Kind.MODEL_IMAGE, input.get().kind());
            assertTrue(fixture.editMode.lastAborted, "Edit mode must be ended with abort=true");
        } finally {
            GroupUndo.rejectUndo = false;
        }
    }

    @Test
    void standaloneAppendRevertsWhenForceRedoThrowsMidWay() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures(IDENTITY, fixture.source, fixture.model);

        final HostModelImage image2 = new HostModelImage("image-2", "Image B", 128, 128);
        fixture.manager.allModelImages.add(image2);
        final HostArtMesh mesh2 = new HostArtMesh("mesh-2", image2);
        fixture.source.artMeshes.add(mesh2);

        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                1024, 1024, 1, List.of(new ModelImagePlacement(new ModelImageId("image-2"), 0, 10, 10, 128, 128)));

        Undo.throwOnForceRedo = true;
        try {
            final RuntimeException ex =
                    assertThrows(RuntimeException.class, () -> textures.appendTextureAtlasPages(plan));
            assertNotNull(ex);

            assertEquals(1, Undo.undoCount, "Native undo must be invoked to revert partial forceRedo");
            assertEquals(1, textures.textureAtlases().size(), "Manager atlases restored to old page");
            assertEquals(
                    new TextureAtlasId("atlas-1"),
                    textures.textureAtlases().get(0).id());

            final Optional<ArtMeshTextureInput> input = textures.textureInput(new ArtMeshId("mesh-2"));
            assertTrue(input.isPresent());
            assertEquals(ArtMeshTextureInput.Kind.MODEL_IMAGE, input.get().kind());
            assertTrue(fixture.editMode.lastAborted, "Edit mode must be ended with abort=true");
        } finally {
            Undo.throwOnForceRedo = false;
        }
    }

    @Test
    void relinkHandlesRollbackWithDeletedMeshesWithoutThrowing() {
        EditorHostThread.dispatch("relinkHandlesRollbackWithDeletedMeshesWithoutThrowing", () -> {
            final Fixture fixture = new Fixture();
            Host.document = fixture.document;

            final TestTxHost txHost = new TestTxHost(fixture.document, fixture.source, fixture.model);
            final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(txHost);

            final EditorTextureAccess access = createAccess(VERSION, true, coordinator);
            final ModelTextures textures = access.textures(IDENTITY, fixture.source, fixture.model);

            final HostModelImage image2 = new HostModelImage("image-2", "Image B", 128, 128);
            fixture.manager.allModelImages.add(image2);
            final HostArtMesh mesh2 = new HostArtMesh("mesh-created-in-transaction", image2);
            fixture.source.artMeshes.add(mesh2);

            final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                    1024, 1024, 1, List.of(new ModelImagePlacement(new ModelImageId("image-2"), 0, 10, 10, 128, 128)));

            final var binding = new EditorAuthoringTransactionCoordinator.Binding(
                    "test-plugin", "doc-1", 1L, "model-1", 1L, Thread.currentThread());

            // Transaction appends atlas, then deletes mesh2 (as an outer transaction rollback would delete newly added
            // meshes), then throws
            final AuthoringTransactionResult<Void> result =
                    coordinator.execute(binding, AuthoringTransactionOptions.of("Rollback with Deleted Mesh"), () -> {
                        textures.appendTextureAtlasPages(plan);
                        // Simulate root transaction undo removing the newly imported mesh
                        fixture.source.artMeshes.remove(mesh2);
                        throw new RuntimeException("Rollback after mesh deletion");
                    });

            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(1, textures.textureAtlases().size());
            return null;
        });
    }

    @Test
    void rejectsWhenTotalPageCountExceeds32AcrossOldAndNew() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        // Populate manager with 30 existing atlas pages
        fixture.manager.textureAtlases.clear();
        for (int i = 0; i < 30; i++) {
            fixture.manager.textureAtlases.add(new HostTextureAtlas("atlas-" + i, "Atlas " + i, 512, 512));
        }

        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures("session-a:model-1:1", fixture.source, fixture.model);

        // Appending 3 pages would make total 33 > 32
        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                512, 512, 3, List.of(new ModelImagePlacement(new ModelImageId("image-1"), 0, 0, 0, 256, 128)));

        final IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> textures.appendTextureAtlasPages(plan));
        assertTrue(ex.getMessage().contains("exceeds maximum allowed"));
        assertEquals(30, textures.textureAtlases().size(), "No mutation on pre-validation rejection");
    }

    @Test
    void rejectsPlacementIfModelImageIsAlreadyOnExistingAtlas() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures("session-a:model-1:1", fixture.source, fixture.model);

        // image-1 is already in atlas-1 (see Fixture setup)
        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                1024, 1024, 1, List.of(new ModelImagePlacement(new ModelImageId("image-1"), 0, 0, 0, 256, 128)));

        final IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> textures.appendTextureAtlasPages(plan));
        assertTrue(ex.getMessage().contains("already placed"));
        assertEquals(1, textures.textureAtlases().size(), "Model must not be mutated");
    }

    @Test
    void rejectsPlacementIfPlacementDimensionsDoNotMatchModelImage() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final EditorTextureAccess access = createAccess(VERSION, true, null);
        final ModelTextures textures = access.textures("session-a:model-1:1", fixture.source, fixture.model);

        final HostModelImage image2 = new HostModelImage("image-2", "Image B", 256, 256);
        fixture.manager.allModelImages.add(image2);

        // Plan specifies 128x128 but image is 256x256
        final AtlasPageAppendPlan plan = new AtlasPageAppendPlan(
                1024, 1024, 1, List.of(new ModelImagePlacement(new ModelImageId("image-2"), 0, 0, 0, 128, 128)));

        final IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> textures.appendTextureAtlasPages(plan));
        assertTrue(ex.getMessage().contains("must match ModelImage dimensions"));
        assertEquals(1, textures.textureAtlases().size(), "Model must not be mutated");
    }

    @Test
    void planValidationRejectsOverlapEmptyAndExcess() {
        // Empty placements
        assertThrows(IllegalArgumentException.class, () -> new AtlasPageAppendPlan(1024, 1024, 1, List.of()));

        // Page count exceeds 32
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtlasPageAppendPlan(
                        1024, 1024, 33, List.of(new ModelImagePlacement(new ModelImageId("img-1"), 0, 0, 0, 10, 10))));

        // Mismatched pageNames count
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtlasPageAppendPlan(
                        1024,
                        1024,
                        2,
                        List.of("Only One Name"),
                        List.of(new ModelImagePlacement(new ModelImageId("img-1"), 0, 0, 0, 10, 10))));

        // Blank page name
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtlasPageAppendPlan(
                        1024,
                        1024,
                        1,
                        List.of("   "),
                        List.of(new ModelImagePlacement(new ModelImageId("img-1"), 0, 0, 0, 10, 10))));

        // Overlapping placements on same page
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtlasPageAppendPlan(
                        1024,
                        1024,
                        1,
                        List.of(
                                new ModelImagePlacement(new ModelImageId("img-1"), 0, 10, 10, 100, 100),
                                new ModelImagePlacement(new ModelImageId("img-2"), 0, 50, 50, 100, 100))));

        // Exceeds page boundary with long arithmetic
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtlasPageAppendPlan(
                        1024,
                        1024,
                        1,
                        List.of(new ModelImagePlacement(new ModelImageId("img-1"), 0, 1000, 10, 100, 100))));
    }

    @Test
    void supportsAppendReportsFalseWhenCapabilityMissingOrKillSwitchSet() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;

        // 1. Without layout capability
        final EditorTextureAccess noCapability = createAccess(VERSION, false, null);
        final ModelTextures texturesNoCap = noCapability.textures("session-a:model-1:1", fixture.source, fixture.model);
        assertFalse(texturesNoCap.supportsAppendTextureAtlasPages());
        assertThrows(
                UnsupportedOperationException.class,
                () -> texturesNoCap.appendTextureAtlasPages(new AtlasPageAppendPlan(
                        512,
                        512,
                        1,
                        List.of(new ModelImagePlacement(new ModelImageId("image-1"), 0, 0, 0, 256, 128)))));

        // 2. With kill switch set to "false"
        System.setProperty(HostUndoMutationScope.ENABLED_PROPERTY, "false");
        final EditorTextureAccess withCap = createAccess(VERSION, true, null);
        final ModelTextures texturesKillSwitch = withCap.textures("session-a:model-1:1", fixture.source, fixture.model);
        assertFalse(texturesKillSwitch.supportsAppendTextureAtlasPages());
    }

    private static EditorTextureAccess createAccess(
            final String version,
            final boolean includeCapability,
            final EditorAuthoringTransactionCoordinator coordinator) {
        return new EditorTextureAccess(
                resolver(version, includeCapability), (identity, model) -> {}, coordinator, () -> null);
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean includeCapability) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                "()L" + internal(Host.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                desc(Document.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.complete-pack",
                Host.class,
                "completePack",
                desc(CompletePack.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.model-source",
                Document.class,
                "modelSource",
                desc(ModelSource.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.current-instance",
                ModelSource.class,
                "currentInstance",
                desc(Model.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method(
                "cubism.editor-model.edit-mode.begin",
                EditMode.class,
                "begin",
                "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method(
                "cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(method("cubism.editor-model.texture-undo.undo", Undo.class, "undo", "()V"));
        selectors.add(method(
                "cubism.texture-atlas.undo.force-redo", Undo.class, "forceRedo", "()L" + internal(Undo.class) + ";"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
        selectors.add(
                method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.model-source.texture-manager",
                ModelSource.class,
                "textureManager",
                desc(TextureManager.class)));
        selectors.add(method(
                "cubism.editor-model.texture-manager.raw-images",
                TextureManager.class,
                "rawImages",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.texture-manager.model-image-groups",
                TextureManager.class,
                "modelImageGroups",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.texture-manager.all-model-images",
                TextureManager.class,
                "allModelImages",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.texture-manager.texture-atlases",
                TextureManager.class,
                "textureAtlases",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.texture-manager.handler",
                TextureManager.class,
                "handler",
                desc(HostTextureManagerHandler.class)));
        selectors.add(method(
                "cubism.editor-model.layered-image-wrapper.image",
                HostLayeredImageWrapper.class,
                "image",
                desc(HostLayeredImage.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.layered-image.class", internal(HostLayeredImage.class)));
        selectors.add(method("cubism.editor-model.layered-image.guid", HostLayeredImage.class, "guid", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.layered-image.name", HostLayeredImage.class, "name", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.layered-image.width", HostLayeredImage.class, "width", "()I"));
        selectors.add(method("cubism.editor-model.layered-image.height", HostLayeredImage.class, "height", "()I"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.model-image-group.class", internal(HostModelImageGroup.class)));
        selectors.add(method(
                "cubism.editor-model.model-image-group.group-name",
                HostModelImageGroup.class,
                "groupName",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.model-image-group.memo",
                HostModelImageGroup.class,
                "memo",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.model-image-group.model-images",
                HostModelImageGroup.class,
                "modelImages",
                "()Ljava/util/List;"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.model-image.class", internal(HostModelImage.class)));
        selectors.add(method("cubism.editor-model.model-image.guid", HostModelImage.class, "guid", desc(Id.class)));
        selectors.add(
                method("cubism.editor-model.model-image.name", HostModelImage.class, "name", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.model-image.width", HostModelImage.class, "width", "()I"));
        selectors.add(method("cubism.editor-model.model-image.height", HostModelImage.class, "height", "()I"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.texture-atlas.class", internal(HostTextureAtlas.class)));
        selectors.add(method("cubism.editor-model.texture-atlas.guid", HostTextureAtlas.class, "guid", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.texture-atlas.name", HostTextureAtlas.class, "name", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.texture-atlas.width", HostTextureAtlas.class, "width", "()I"));
        selectors.add(method("cubism.editor-model.texture-atlas.height", HostTextureAtlas.class, "height", "()I"));
        selectors.add(method(
                "cubism.editor-model.texture-atlas.atlas-version", HostTextureAtlas.class, "atlasVersion", "()I"));
        selectors.add(method(
                "cubism.editor-model.texture-atlas.model-images",
                HostTextureAtlas.class,
                "modelImages",
                "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));

        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.model-image-group.create",
                internal(HostModelImageGroup.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.texture-handler.add-model-image-group",
                HostTextureManagerHandler.class,
                "addGroup",
                "(" + type(HostModelImageGroup.class) + "I)" + type(Undo.class)));
        selectors.add(method(
                "cubism.editor-model.texture-handler.remove-model-image",
                HostTextureManagerHandler.class,
                "removeImage",
                "(" + type(Id.class) + ")" + type(Undo.class)));
        selectors.add(method(
                "cubism.editor-model.texture-handler.add-texture-atlas",
                HostTextureManagerHandler.class,
                "addAtlas",
                "(" + type(HostTextureAtlas.class) + "I)" + type(Undo.class)));
        selectors.add(method(
                "cubism.editor-model.texture-handler.remove-texture-atlas",
                HostTextureManagerHandler.class,
                "removeAtlas",
                "(" + type(HostTextureAtlas.class) + ")" + type(Undo.class)));

        // Texture atlas creation
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.texture-atlas.create",
                internal(HostTextureAtlas.class),
                "(L" + internal(ModelSource.class) + ";Ljava/lang/String;II)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(
                method("cubism.texture-atlas.atlas.entries", HostTextureAtlas.class, "entries", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector(
                "cubism.texture-atlas.entry.class", internal(HostTextureAtlasEntry.class)));
        selectors.add(StaticSelector.classSelector("cubism.texture-atlas.affine.class", internal(Affine.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.texture-atlas.affine.create", internal(Affine.class), "()V", StaticSelector.ACCESS_PUBLIC));
        selectors.add(method("cubism.texture-atlas.affine.translate", Affine.class, "translate", "(FF)V"));
        selectors.add(StaticSelector.constructor(
                "cubism.texture-atlas.entry.create",
                internal(HostTextureAtlasEntry.class),
                "(" + type(HostTextureAtlas.class) + type(Id.class) + type(Affine.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.texture-atlas.entry.image", HostTextureAtlasEntry.class, "image", desc(HostModelImage.class)));
        selectors.add(method("cubism.texture-atlas.image.guid", HostModelImage.class, "guid", desc(Id.class)));

        selectors.add(StaticSelector.constructor(
                "cubism.texture-atlas.undo.create",
                internal(Undo.class),
                "(Ljava/lang/String;L" + internal(ModelSource.class) + ";Ljava/util/List;Ljava/util/List;)V",
                StaticSelector.ACCESS_PUBLIC));

        // Relink and verify
        selectors.add(StaticSelector.field(
                "cubism.texture-atlas.texture-input-relink.helper-instance",
                internal(RelinkHelper.class),
                "INSTANCE",
                type(RelinkHelper.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.texture-atlas.texture-input-relink.rebuild",
                RelinkHelper.class,
                "rebuild",
                "(L" + internal(ModelSource.class) + ";)V"));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.model-source.verify",
                internal(ModelSource.class),
                "verify",
                "(L" + internal(ModelSource.class) + ";ZLjava/lang/Object;ILjava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.texture-atlas.texture-manager.change-input-to-atlas",
                TextureManager.class,
                "changeInputToAtlas",
                "()V"));
        selectors.add(method(
                "cubism.texture-atlas.texture-manager.reinit-input", TextureManager.class, "reinitInput", "()V"));

        // ArtMesh queries and texture inputs
        selectors.add(method(
                "cubism.editor-model.model-source.all-art-meshes",
                ModelSource.class,
                "allArtMeshes",
                "()Ljava/util/List;"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.art-mesh-source.class", internal(HostArtMesh.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.id", HostArtMesh.class, "id", desc(Id.class)));
        selectors.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.texture-input-extension",
                HostArtMesh.class,
                "textureInputExtension",
                desc(HostTextureInputExtension.class)));
        selectors.add(method(
                "cubism.editor-model.texture-input-extension.current-input",
                HostTextureInputExtension.class,
                "currentInput",
                "()Ljava/lang/Object;"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.texture-input-model-image.class", internal(HostTextureInputModelImage.class)));
        selectors.add(method(
                "cubism.editor-model.texture-input-model-image.model-image",
                HostTextureInputModelImage.class,
                "modelImage",
                desc(HostModelImage.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.texture-input-texture-atlas-region.class",
                internal(HostTextureInputAtlasRegion.class)));
        selectors.add(method(
                "cubism.editor-model.texture-atlas-guid",
                HostTextureInputAtlasRegion.class,
                "textureAtlasGuid",
                desc(Id.class)));

        // Ambient authoring transaction support
        selectors.add(method("cubism.editor-model.undo.group-undo", GroupUndo.class, "undo", "()V"));

        final Set<String> capabilities = new HashSet<>();
        capabilities.add(EditorTextureSelectorContract.READ_CAPABILITY_ID);
        capabilities.add(EditorTextureSelectorContract.WRITE_CAPABILITY_ID);
        capabilities.add(EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID);

        if (includeCapability) {
            capabilities.add(VerifiedTextureAtlasSelectorContract.CAPABILITY_ID);
        }

        return TestVerifiedResolvers.create(
                version,
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors,
                Host.class.getClassLoader());
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static String desc(final Class<?> type) {
        return "()" + type(type);
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    // --- Mock Host Hierarchy ---

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }

        public CompletePack completePack() {
            return document == null ? null : document.pack;
        }
    }

    public static final class Document {
        final ModelSource source = new ModelSource();
        final EditMode editMode = new EditMode();
        final CompletePack pack = new CompletePack();
        boolean dirty;

        public ModelSource modelSource() {
            return source;
        }

        public EditMode editMode() {
            return editMode;
        }

        public void markDirty() {
            dirty = true;
        }
    }

    public static final class CompletePack {
        boolean repainted;

        public void updateParts(final boolean value) {}

        public void repaint(final boolean value) {
            repainted = true;
        }
    }

    public static final class ModelSource {
        final TextureManager manager = new TextureManager();
        final Model model = new Model();
        final List<HostArtMesh> artMeshes = new ArrayList<>();
        final Id guid = new Id("model-source-1");
        boolean instancesUpdated;

        public TextureManager textureManager() {
            return manager;
        }

        public Model currentInstance() {
            return model;
        }

        public Id guid() {
            return guid;
        }

        public List<HostArtMesh> allArtMeshes() {
            return artMeshes;
        }

        public void updateInstances() {
            instancesUpdated = true;
        }

        public static void verify(
                final ModelSource source, final boolean a, final Object b, final int c, final Object d) {}
    }

    public static final class Model {}

    public static final class TextureManager {
        final List<HostModelImage> allModelImages = new ArrayList<>();
        final List<HostModelImageGroup> modelImageGroups = new ArrayList<>();
        final List<HostTextureAtlas> textureAtlases = new ArrayList<>();
        final HostTextureManagerHandler handler = new HostTextureManagerHandler(this);
        boolean atlasMode;

        TextureManager() {
            final HostModelImage image1 = new HostModelImage("image-1", "Image A", 256, 128);
            allModelImages.add(image1);
            final HostTextureAtlas atlas1 = new HostTextureAtlas("atlas-1", "Atlas A", 2048, 1024);
            atlas1.entries.add(new HostTextureAtlasEntry(atlas1, image1.guid, new Affine()));
            textureAtlases.add(atlas1);
        }

        public List<HostModelImage> allModelImages() {
            return allModelImages;
        }

        public List<HostTextureAtlas> textureAtlases() {
            return textureAtlases;
        }

        public HostTextureManagerHandler handler() {
            return handler;
        }

        public void changeInputToAtlas() {
            atlasMode = true;
        }

        public void reinitInput() {
            atlasMode = false;
        }
    }

    public static final class HostLayeredImageWrapper {
        final HostLayeredImage image;

        public HostLayeredImageWrapper(final HostLayeredImage image) {
            this.image = image;
        }

        public HostLayeredImage image() {
            return image;
        }
    }

    public static final class HostLayeredImage {
        final Id guid;
        final String name;
        final int width;
        final int height;

        public HostLayeredImage(final String guid, final String name, final int width, final int height) {
            this.guid = new Id(guid);
            this.name = name;
            this.width = width;
            this.height = height;
        }

        public Id guid() {
            return guid;
        }

        public String name() {
            return name;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    public static final class HostModelImageGroup {
        final String groupName;
        final String memo;
        final List<HostModelImage> modelImages;

        public HostModelImageGroup(final String groupName, final String memo, final List<HostModelImage> modelImages) {
            this.groupName = groupName;
            this.memo = memo;
            this.modelImages = modelImages;
        }

        public String groupName() {
            return groupName;
        }

        public String memo() {
            return memo;
        }

        public List<HostModelImage> modelImages() {
            return modelImages;
        }
    }

    public static final class HostTextureAtlas {
        final Id guid;
        final String name;
        final int width;
        final int height;
        final List<HostTextureAtlasEntry> entries = new ArrayList<>();

        public HostTextureAtlas(final ModelSource source, final String name, final int width, final int height) {
            this("atlas-" + (source.manager.textureAtlases.size() + 1), name, width, height);
        }

        public HostTextureAtlas(final String guid, final String name, final int width, final int height) {
            this.guid = new Id(guid);
            this.name = name;
            this.width = width;
            this.height = height;
        }

        public Id guid() {
            return guid;
        }

        public String name() {
            return name;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int atlasVersion() {
            return 3;
        }

        public List<HostModelImage> modelImages() {
            return List.of();
        }

        public List<HostTextureAtlasEntry> entries() {
            return entries;
        }
    }

    public static final class Affine extends AffineTransform {
        public Affine() {}

        public Affine(final AffineTransform value) {
            super(value);
        }

        public void translate(final float x, final float y) {
            super.translate(x, y);
        }
    }

    public static final class HostTextureAtlasEntry {
        final HostTextureAtlas atlas;
        final Id imageGuid;
        final Affine affine;

        public HostTextureAtlasEntry(final HostTextureAtlas atlas, final Id imageGuid, final Affine affine) {
            this.atlas = atlas;
            this.imageGuid = imageGuid;
            this.affine = affine;
        }

        public HostModelImage image() {
            for (final HostModelImage img : Host.document.source.manager.allModelImages) {
                if (img.guid.value.equals(imageGuid.value)) return img;
            }
            return null;
        }
    }

    public static final class HostModelImage {
        final Id guid;
        final String name;
        final int width;
        final int height;

        public HostModelImage(final String guid, final String name, final int width, final int height) {
            this.guid = new Id(guid);
            this.name = name;
            this.width = width;
            this.height = height;
        }

        public Id guid() {
            return guid;
        }

        public String name() {
            return name;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    public static final class HostArtMesh {
        final Id id;
        final HostTextureInputExtension extension;
        final HostModelImage originalImage;

        public HostArtMesh(final String id, final HostModelImage boundImage) {
            this.id = new Id(id);
            this.originalImage = boundImage;
            this.extension = new HostTextureInputExtension(new HostTextureInputModelImage(boundImage));
        }

        public Id id() {
            return id;
        }

        public HostTextureInputExtension textureInputExtension() {
            return extension;
        }
    }

    public static final class HostTextureInputExtension {
        Object currentInput;

        public HostTextureInputExtension(final Object initialInput) {
            this.currentInput = initialInput;
        }

        public Object currentInput() {
            return currentInput;
        }
    }

    public static final class HostTextureInputModelImage {
        final HostModelImage image;

        public HostTextureInputModelImage(final HostModelImage image) {
            this.image = image;
        }

        public HostModelImage modelImage() {
            return image;
        }
    }

    public static final class HostTextureInputAtlasRegion {
        final Id atlasGuid;

        public HostTextureInputAtlasRegion(final Id atlasGuid) {
            this.atlasGuid = atlasGuid;
        }

        public Id textureAtlasGuid() {
            return atlasGuid;
        }
    }

    public static final class RelinkHelper {
        public static final RelinkHelper INSTANCE = new RelinkHelper();

        public void rebuild(final ModelSource source) {
            for (final HostArtMesh mesh : source.artMeshes) {
                final String imgId = mesh.originalImage.guid.value;
                boolean placedOnAtlas = false;
                for (final HostTextureAtlas atlas : source.manager.textureAtlases) {
                    for (final HostTextureAtlasEntry entry : atlas.entries) {
                        if (entry.imageGuid.value.equals(imgId)) {
                            mesh.extension.currentInput = new HostTextureInputAtlasRegion(atlas.guid);
                            placedOnAtlas = true;
                            break;
                        }
                    }
                    if (placedOnAtlas) break;
                }
                if (!placedOnAtlas) {
                    mesh.extension.currentInput = new HostTextureInputModelImage(mesh.originalImage);
                }
            }
        }
    }

    public static final class HostTextureManagerHandler {
        final TextureManager manager;

        public HostTextureManagerHandler(final TextureManager manager) {
            this.manager = manager;
        }

        public Undo addGroup(final HostModelImageGroup group, final int index) {
            manager.modelImageGroups.add(index, group);
            return new Undo("add group", Host.document.source, List.of(), List.of());
        }

        public Undo removeImage(final Id imageId) {
            return new Undo("remove image", Host.document.source, List.of(), List.of());
        }

        public Undo addAtlas(final HostTextureAtlas atlas, final int index) {
            manager.textureAtlases.add(index, atlas);
            return new Undo("add atlas", Host.document.source, List.of(), List.of());
        }

        public Undo removeAtlas(final HostTextureAtlas atlas) {
            manager.textureAtlases.remove(atlas);
            return new Undo("remove atlas", Host.document.source, List.of(), List.of());
        }
    }

    public static final class EditMode {
        final List<GroupUndo> edits = new ArrayList<>();
        boolean active;
        boolean lastAborted;

        public GroupUndo begin(final String label) {
            active = true;
            final GroupUndo undo = new GroupUndo();
            edits.add(undo);
            return undo;
        }

        public void end(final boolean abort, final Object unused) {
            active = false;
            lastAborted = abort;
        }

        public void undo() {
            for (int i = edits.size() - 1; i >= 0; i--) {
                edits.get(i).undo();
            }
        }

        public void redo() {
            for (final GroupUndo edit : edits) {
                edit.redo();
            }
        }
    }

    public static final class GroupUndo {
        final List<Undo> undos = new ArrayList<>();
        static boolean rejectUndo;

        public boolean add(final Undo undo, final boolean value) {
            if (rejectUndo) {
                return false;
            }
            undos.add(undo);
            return true;
        }

        public void undo() {
            for (int i = undos.size() - 1; i >= 0; i--) {
                undos.get(i).undo();
            }
        }

        public void redo() {
            for (final Undo undo : undos) {
                undo.forceRedo();
            }
        }
    }

    public static final class Undo {
        final ModelSource source;
        final List<HostTextureAtlas> oldAtlases;
        final List<HostTextureAtlas> newAtlases;
        final List<Listener> listeners = new ArrayList<>();
        static boolean rejectListener;
        static boolean throwOnForceRedo;
        static int undoCount;

        @SuppressWarnings("unchecked")
        public Undo(final String label, final ModelSource source, final List<?> oldAtlases, final List<?> newAtlases) {
            this.source = source;
            this.oldAtlases = new ArrayList<>((List<HostTextureAtlas>) oldAtlases);
            this.newAtlases = new ArrayList<>((List<HostTextureAtlas>) newAtlases);
        }

        public boolean addListener(final Listener listener) {
            if (rejectListener) {
                return false;
            }
            listeners.add(listener);
            return true;
        }

        public Undo forceRedo() {
            source.manager.textureAtlases.clear();
            source.manager.textureAtlases.addAll(newAtlases);
            if (throwOnForceRedo) {
                throw new RuntimeException("Force-redo failure mid-way");
            }
            for (final Listener listener : listeners) {
                listener.action(Boolean.TRUE);
            }
            return this;
        }

        public void undo() {
            undoCount++;
            source.manager.textureAtlases.clear();
            source.manager.textureAtlases.addAll(oldAtlases);
            for (final Listener listener : listeners) {
                listener.action(Boolean.FALSE);
            }
        }
    }

    public interface Listener {
        Object action(Object arg);
    }

    public static final class TestTxHost implements EditorAuthoringTransactionCoordinator.Host {
        final Document document;
        final ModelSource source;
        final Model model;
        GroupUndo currentEdit;

        TestTxHost(final Document document, final ModelSource source, final Model model) {
            this.document = document;
            this.source = source;
            this.model = model;
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding binding) {
            return true;
        }

        @Override
        public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE, 1, 1L, 0, List.of(), false, false, "doc-1", "model-1");
        }

        @Override
        public Optional<String> committedHistoryEntryId(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final HistorySnapshot before,
                final HistorySnapshot after,
                final String transactionId,
                final String label) {
            return Optional.of("entry-1");
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }

        @Override
        public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding binding, final String label) {
            currentEdit = document.editMode.begin(label);
            return currentEdit;
        }

        @Override
        public void endEdit(
                final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit, final boolean abort) {
            document.editMode.end(abort, null);
        }

        @Override
        public void undoEditGroup(final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit) {
            if (edit instanceof GroupUndo group) {
                group.undo();
            }
        }

        @Override
        public void refresh(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final Set<dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement> requirements) {
            source.updateInstances();
            if (document.pack != null) {
                document.pack.repaint(true);
            }
        }
    }

    public static final class Id {
        final String value;

        public Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private static final class Fixture {
        final Document document = new Document();
        final ModelSource source = document.source;
        final Model model = source.model;
        final TextureManager manager = source.manager;
        final EditMode editMode = document.editMode;
    }
}
