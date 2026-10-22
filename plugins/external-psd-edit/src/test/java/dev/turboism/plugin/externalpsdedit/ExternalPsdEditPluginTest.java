package dev.turboism.plugin.externalpsdedit;

import static dev.turboism.plugin.externalpsdedit.ExternalPsdEditTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalPsdEditPluginTest {

    private static final String BINDING = "session-test:model-1:1";
    private static final String OTHER_BINDING = "session-test:model-2:1";
    private static final RawImageId RAW_A = new RawImageId("raw-a");
    private static final RawImageId RAW_B = new RawImageId("raw-b");
    private static final RawImageId RAW_C = new RawImageId("raw-c");
    private static final ModelImageId IMAGE_A = new ModelImageId("image-a");
    private static final ModelImageId IMAGE_B = new ModelImageId("image-b");

    @Test
    void enableRegistersActionAndArtMeshContextMenuContributions() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
                List.of(ExternalPsdEditPlugin.OPEN_ACTION_ID),
                context.actions().actions().stream()
                        .map(ActionRegistry.Action::id)
                        .toList());
        assertEquals(
                Set.of(
                        "external-psd-edit.open.part",
                        "external-psd-edit.open.deformer",
                        "external-psd-edit.open.workspace"),
                Set.copyOf(context.contextMenu().contributions().stream()
                        .map(ContextMenuRegistry.ContextMenuContribution::id)
                        .toList()));
        for (final ContextMenuRegistry.ContextMenuContribution contribution :
                context.contextMenu().contributions()) {
            assertEquals(Set.of(ContextMenuRegistry.ObjectKind.ART_MESH), contribution.objectKinds());
            assertEquals(ExternalPsdEditPlugin.OPEN_ACTION_ID, contribution.actionId());
        }
    }

    @Test
    void settingsPersistCustomEditorAndUseItForTheExportedDocument() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final var control = (dev.turboism.sdk.ui.settings.SettingsControl.FilePath)
                context.uiHost().settings.get(0).control();
        assertEquals("", control.binding().read());
        context.uiHost().setChosenFile("C:/Program Files/PaintTool SAI/sai.exe");
        assertEquals(
                Optional.of("C:/Program Files/PaintTool SAI/sai.exe"),
                control.browse().get());
        assertEquals("", control.binding().read(), "browsing does not save until Apply");
        control.binding().write("C:/Program Files/PaintTool SAI/sai.exe");
        assertEquals("C:/Program Files/PaintTool SAI/sai.exe", new ExternalPsdEditSettings(context).applicationPath());
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertEquals("C:/Program Files/PaintTool SAI/sai.exe", file.applicationPath);
        assertTrue(file.subscribedBeforeOpen);
        control.binding().write("   ");
        assertEquals("", new ExternalPsdEditSettings(context).applicationPath());
        context.disposableScope().close();
        assertTrue(context.uiHost().settings.isEmpty());
    }

    @Test
    void actionResolvesArtMeshToCurrentRawImageExportsSubscribesThenOpens() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A), artMesh("mesh-2", IMAGE_B))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertNotNull(file);
        assertTrue(file.subscribedBeforeOpen, "saves must be subscribed before opening");
        assertEquals(
                List.of(
                        "external-psd-edit.status.preparing",
                        "external-psd-edit.status.exporting",
                        "external-psd-edit.status.editing"),
                context.uiHost().notifications().stream().map(n -> n.id()).toList());
        assertEquals(1, file.openCalls.get());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.editing")));
    }

    @Test
    void modelPreparationFailureLogsItsStageAndOriginalCauseWithoutExporting() {
        for (String stage : List.of("active-model", "model-textures", "texture-relations", "model-id")) {
            final TestPluginLogger logger = new TestPluginLogger();
            final RecordingPluginContext context = new RecordingPluginContext(logger);
            final RuntimeException failure = new IllegalStateException(
                    "rejected at " + stage, new UnsupportedOperationException("host contract rejected"));
            context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
            switch (stage) {
                case "active-model" -> context.cubism().activeFailure = failure;
                case "model-textures" -> context.cubism().texturesFailure = failure;
                case "texture-relations" ->
                    context.cubism().textures().onNextRelationsRead(() -> {
                        throw failure;
                    });
                case "model-id" -> context.cubism().identityFailure = failure;
                default -> throw new AssertionError(stage);
            }
            final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
            plugin.init(context);
            plugin.enable();
            try {
                context.actions()
                        .execute(
                                ExternalPsdEditPlugin.OPEN_ACTION_ID,
                                selection(
                                        BINDING,
                                        ContextMenuRegistry.Location.PART_TAB,
                                        item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
                assertTrue(
                        context.uiHost().notifications().stream()
                                .anyMatch(n -> n.id().equals("external-psd-edit.error.model-unavailable")),
                        stage);
                assertTrue(context.cubism().textures().calls().isEmpty(), stage);
                assertEquals(0, plugin.liveSessions(), stage);
                assertEquals(List.of(failure), logger.failures, stage);
                assertTrue(
                        logger.messages().stream()
                                .anyMatch(message ->
                                        message.contains("External PSD model preparation failed; stage=" + stage)),
                        stage);
            } finally {
                plugin.disable();
            }
        }
    }

    @Test
    void atlasCurrentInputResolvesItsRetainedModelImageRegardlessOfInputOrder() {
        final TextureInputBinding image = TextureInputBinding.modelImage(IMAGE_A);
        final TextureInputBinding atlas = TextureInputBinding.atlas(new TextureAtlasId("atlas-1"));
        for (List<TextureInputBinding> inputs : List.of(List.of(image, atlas), List.of(atlas, image))) {
            final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
            context.cubism()
                    .relations(withMeshInputs(
                            relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))), inputs, inputs.indexOf(atlas)));
            final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
            plugin.init(context);
            plugin.enable();
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    context.actions()
                            .execute(
                                    ExternalPsdEditPlugin.OPEN_ACTION_ID,
                                    selection(
                                            BINDING,
                                            ContextMenuRegistry.Location.DEFORMER_TAB,
                                            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
                }
                assertEquals(
                        List.of("export:raw-a"), context.cubism().textures().calls());
                assertEquals(1, plugin.liveSessions());
                assertEquals(
                        2,
                        context.cubism()
                                .textures()
                                .issued()
                                .get(RAW_A)
                                .openCalls
                                .get());
                assertTrue(context.cubism().textures().issued().get(RAW_A).subscribedBeforeOpen);
                context.cubism().textures().issued().get(RAW_A).saveListener.accept(new TestRevision("atlas-save"));
                assertEquals(
                        List.of("export:raw-a", "replace:raw-a:atlas-save"),
                        context.cubism().textures().calls());
                assertTrue(context.uiHost().notifications().stream()
                        .anyMatch(n -> n.id().equals("external-psd-edit.status.applied")));
            } finally {
                plugin.disable();
            }
        }
    }

    @Test
    void atlasModelImageInputsSharingOneRawResolveToOneSession() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism()
                .relations(withMeshInputs(
                        twoImageRelations(BINDING, 1L, 1L, RAW_A, RAW_A),
                        List.of(
                                TextureInputBinding.modelImage(IMAGE_B),
                                TextureInputBinding.atlas(new TextureAtlasId("atlas-1")),
                                TextureInputBinding.modelImage(IMAGE_A)),
                        1));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            context.actions()
                    .execute(
                            ExternalPsdEditPlugin.OPEN_ACTION_ID,
                            selection(
                                    BINDING,
                                    ContextMenuRegistry.Location.DEFORMER_TAB,
                                    item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
            assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
            assertEquals(1, plugin.liveSessions());
            assertNull(context.uiHost().lastConfirmRequest());
        } finally {
            plugin.disable();
        }
    }

    @Test
    void atlasSourcesRejectAmbiguityMissingAndUnknownInputsWithoutExporting() {
        final TextureInputBinding atlas = TextureInputBinding.atlas(new TextureAtlasId("atlas-1"));
        final TextureInputBinding image = TextureInputBinding.modelImage(IMAGE_A);
        final TextureRelationsSnapshot base = twoImageRelations(BINDING, 1L, 1L, RAW_A, RAW_B);
        for (List<TextureInputBinding> inputs : List.of(
                List.of(atlas),
                List.of(atlas, image, TextureInputBinding.modelImage(IMAGE_B)),
                List.of(atlas, TextureInputBinding.modelImage(IMAGE_B), image),
                List.of(atlas, image, TextureInputBinding.unknown()),
                List.of(atlas, image, TextureInputBinding.modelImage(new ModelImageId("missing"))),
                List.of(atlas, TextureInputBinding.modelImage(IMAGE_A, TextureInputBinding.ResolutionState.UNKNOWN)))) {
            final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
            context.cubism().relations(withMeshInputs(base, inputs, 0));
            final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
            plugin.init(context);
            plugin.enable();
            try {
                context.actions()
                        .execute(
                                ExternalPsdEditPlugin.OPEN_ACTION_ID,
                                selection(
                                        BINDING,
                                        ContextMenuRegistry.Location.DEFORMER_TAB,
                                        item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
                assertTrue(context.cubism().textures().calls().isEmpty(), inputs.toString());
                assertEquals(0, plugin.liveSessions(), inputs.toString());
                assertTrue(
                        context.uiHost().notifications().stream()
                                .anyMatch(n -> n.id().equals("external-psd-edit.error.unresolved")),
                        inputs.toString());
            } finally {
                plugin.disable();
            }
        }
    }

    @Test
    void atlasSourcesNeverGuessFromCandidatesOrIncompleteResourceRecords() {
        final TextureRelationsSnapshot base = relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A)));
        final ModelImageRelation image = base.modelImages().get(0);
        final ModelImageRelation noCurrent = new ModelImageRelation(
                IMAGE_A,
                entry(IMAGE_A),
                List.of(RAW_A, RAW_B),
                Optional.empty(),
                Map.of(),
                List.of(new ArtMeshId("mesh-1")));
        final List<TextureRelationsSnapshot> invalidGraphs = List.of(
                new TextureRelationsSnapshot(
                        base.availability(),
                        BINDING,
                        1L,
                        1L,
                        base.rawImages(),
                        List.of(noCurrent),
                        base.groups(),
                        base.artMeshInputs()),
                new TextureRelationsSnapshot(
                        base.availability(),
                        BINDING,
                        1L,
                        1L,
                        base.rawImages(),
                        List.of(image, image),
                        base.groups(),
                        base.artMeshInputs()),
                new TextureRelationsSnapshot(
                        base.availability(),
                        BINDING,
                        1L,
                        1L,
                        List.of(),
                        base.modelImages(),
                        base.groups(),
                        base.artMeshInputs()));
        for (TextureRelationsSnapshot graph : invalidGraphs) {
            final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
            context.cubism()
                    .relations(withMeshInputs(
                            graph,
                            List.of(
                                    TextureInputBinding.modelImage(IMAGE_A),
                                    TextureInputBinding.atlas(new TextureAtlasId("atlas-1"))),
                            1));
            final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
            plugin.init(context);
            plugin.enable();
            try {
                context.actions()
                        .execute(
                                ExternalPsdEditPlugin.OPEN_ACTION_ID,
                                selection(
                                        BINDING,
                                        ContextMenuRegistry.Location.DEFORMER_TAB,
                                        item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
                assertTrue(context.cubism().textures().calls().isEmpty());
                assertEquals(0, plugin.liveSessions());
                assertTrue(context.uiHost().notifications().stream()
                        .anyMatch(n -> n.id().equals("external-psd-edit.error.unresolved")));
            } finally {
                plugin.disable();
            }
        }
    }

    @Test
    void currentModelImageStillWinsOverOtherInputsAndAnAtlas() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism()
                .relations(withMeshInputs(
                        twoImageRelations(BINDING, 1L, 1L, RAW_A, RAW_B),
                        List.of(
                                TextureInputBinding.modelImage(IMAGE_A),
                                TextureInputBinding.atlas(new TextureAtlasId("atlas-1")),
                                TextureInputBinding.modelImage(IMAGE_B)),
                        2));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            context.actions()
                    .execute(
                            ExternalPsdEditPlugin.OPEN_ACTION_ID,
                            selection(
                                    BINDING,
                                    ContextMenuRegistry.Location.DEFORMER_TAB,
                                    item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
            assertEquals(List.of("export:raw-b"), context.cubism().textures().calls());
        } finally {
            plugin.disable();
        }
    }

    @Test
    void sharedModelImageDedupesToOneSessionPerRawImage() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A), artMesh("mesh-2", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")));

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertNull(context.uiHost().lastConfirmRequest(), "a single new file must not require confirmation");
    }

    @Test
    void repeatedInvocationReusesLiveSession() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertEquals(2, file.openCalls.get(), "a repeated action must reopen the live file");
        assertEquals(1, file.observeCalls.get(), "a repeated action must not resubscribe");
    }

    @Test
    void reopeningExistingFileDoesNotOverwriteExternalEdits() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        file.externalContent = "edited outside Cubism";

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals("edited outside Cubism", file.externalContent);
        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertEquals(2, file.openCalls.get());
        assertEquals(1, file.observeCalls.get());
    }

    @Test
    void openingSessionDoesNotDuplicateWorkAndKeepsEarlySave() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        final CompletableFuture<PsdFileOperationResult> openCompletion = new CompletableFuture<>();
        context.cubism().textures().openCompletion = openCompletion;
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        file.saveListener.accept(new TestRevision("during-open"));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:during-open"),
                context.cubism().textures().calls(),
                "a save observed while the initial open is pending must not be dropped");
        assertEquals(1, file.openCalls.get(), "an in-flight open must not be launched twice");
        assertEquals(1, file.observeCalls.get(), "an in-flight session must not resubscribe");

        openCompletion.complete(new PsdFileOperationResult(PsdFileOperationResult.Status.OPENED, "test"));
    }

    @Test
    void partialFailureDuringOpeningRemainsPausedAfterOpenCompletes() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        final CompletableFuture<PsdFileOperationResult> openCompletion = new CompletableFuture<>();
        context.cubism().textures().openCompletion = openCompletion;
        context.cubism().textures().replaceStatus = PsdReplaceResult.Status.PARTIAL_FAILURE;
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);

        file.saveListener.accept(new TestRevision("during-open"));
        openCompletion.complete(new PsdFileOperationResult(PsdFileOperationResult.Status.OPENED, "test"));
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        file.saveListener.accept(new TestRevision("after-open"));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:during-open"),
                context.cubism().textures().calls(),
                "a partial failure during opening must pause later automatic imports");
        assertEquals(
                2, file.openCalls.get(), "a paused session still reopens its existing file without exporting again");
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.paused-partial")));
        assertTrue(
                context.uiHost().notifications().stream()
                        .noneMatch(n -> n.id().equals("external-psd-edit.status.editing")),
                "a late open completion must not report a paused session as actively syncing");
    }

    @Test
    void exportingSessionDoesNotDuplicateExportBeforeAFileExists() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        final CompletableFuture<PsdExportResult> exportCompletion = new CompletableFuture<>();
        context.cubism().textures().exportCompletion = exportCompletion;
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertTrue(context.cubism().textures().issued().isEmpty(), "an exporting session has no file to reopen yet");

        final FakePsdEditFile file = new FakePsdEditFile();
        exportCompletion.complete(new PsdExportResult(
                PsdExportResult.Status.EXPORTED,
                "test",
                RAW_A,
                Optional.of(file),
                Optional.of(new TestRevision("baseline"))));

        assertEquals(1, file.observeCalls.get());
        assertEquals(1, file.openCalls.get());
    }

    enum InFlightOp {
        EXPORT,
        OPEN
    }

    @ParameterizedTest(name = "refresh does not pause {0} session before completion")
    @EnumSource(InFlightOp.class)
    void refreshDoesNotPauseInFlightSessionBeforeCompletion(InFlightOp op) {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        final CompletableFuture<PsdExportResult> exportCompletion = new CompletableFuture<>();
        final CompletableFuture<PsdFileOperationResult> openCompletion = new CompletableFuture<>();
        if (op == InFlightOp.EXPORT) {
            context.cubism().textures().exportCompletion = exportCompletion;
        } else {
            context.cubism().textures().openCompletion = openCompletion;
        }
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        context.cubism().relations(TextureRelationsSnapshot.unavailable());
        context.taskScheduler.runRefreshTick();

        assertTrue(context.uiHost().notifications().stream()
                .noneMatch(n -> n.id().equals("external-psd-edit.status.paused-reason")));

        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        if (op == InFlightOp.EXPORT) {
            final FakePsdEditFile file = new FakePsdEditFile();
            exportCompletion.complete(new PsdExportResult(
                    PsdExportResult.Status.EXPORTED,
                    "test",
                    RAW_A,
                    Optional.of(file),
                    Optional.of(new TestRevision("baseline"))));
        } else {
            openCompletion.complete(new PsdFileOperationResult(PsdFileOperationResult.Status.OPENED, "test"));
        }

        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.editing")));
    }

    @ParameterizedTest(name = "synchronous {0} failure cleans session and allows retry")
    @ValueSource(strings = {"export", "open"})
    void synchronousOperationFailureCleansSessionAndAllowsRetry(String op) {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        if ("export".equals(op)) {
            context.cubism().textures().exportFailure = new IllegalStateException("synchronous export failure");
        } else {
            context.cubism().textures().openFailure = new IllegalStateException("synchronous open failure");
        }
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(0, plugin.liveSessions(), "a synchronous failure must remove the session");
        assertTrue(context.taskScheduler.refreshClosed(), "startup cleanup must close the refresh task as well");
        if ("export".equals(op)) {
            assertTrue(context.uiHost().notifications().stream()
                    .anyMatch(n -> n.id().equals("external-psd-edit.error.session-failed")));
        } else {
            final FakePsdEditFile failedFile =
                    context.cubism().textures().issued().get(RAW_A);
            assertTrue(failedFile.stopped.get());
            assertTrue(failedFile.subscriptionClosed.get());
        }

        if ("export".equals(op)) {
            context.cubism().textures().exportFailure = null;
        } else {
            context.cubism().textures().openFailure = null;
        }
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(
                List.of("export:raw-a", "export:raw-a"),
                context.cubism().textures().calls());
        assertEquals(1, plugin.liveSessions(), "the same target must be retryable after cleanup");
        final FakePsdEditFile recoveredFile =
                context.cubism().textures().issued().get(RAW_A);
        assertEquals(1, recoveredFile.openCalls.get());
        assertFalse(recoveredFile.stopped.get());
    }

    @Test
    void subscriptionFailureDoesNotDeadlockReopenOrStop() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        final CompletableFuture<PsdExportResult> exportCompletion = new CompletableFuture<>();
        context.cubism().textures().exportCompletion = exportCompletion;
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        final FakePsdEditFile file = new FakePsdEditFile();
        file.observeEntered = new CountDownLatch(1);
        file.observeRelease = new CountDownLatch(1);
        file.observeFailure = new IllegalStateException("observe failed");
        final AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        final Thread exportThread = new Thread(
                () -> {
                    try {
                        exportCompletion.complete(new PsdExportResult(
                                PsdExportResult.Status.EXPORTED,
                                "test",
                                RAW_A,
                                Optional.of(file),
                                Optional.of(new TestRevision("baseline"))));
                    } catch (Throwable failure) {
                        threadFailure.set(failure);
                    }
                },
                "external-psd-test-export");
        exportThread.start();
        assertTrue(
                file.observeEntered.await(1, TimeUnit.SECONDS),
                "observeSaves must be entered before the lock-order check");

        final Thread reopenThread = new Thread(
                () -> {
                    try {
                        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
                    } catch (Throwable failure) {
                        threadFailure.set(failure);
                    }
                },
                "external-psd-test-reopen");
        reopenThread.start();
        final Thread tickThread = new Thread(
                () -> {
                    try {
                        context.taskScheduler.runRefreshTick();
                    } catch (Throwable failure) {
                        threadFailure.set(failure);
                    }
                },
                "external-psd-test-refresh");
        tickThread.start();
        final Thread stopThread = new Thread(
                () -> {
                    try {
                        plugin.disable();
                    } catch (Throwable failure) {
                        threadFailure.set(failure);
                    }
                },
                "external-psd-test-stop");
        stopThread.start();

        try {
            reopenThread.join(1000);
            assertFalse(reopenThread.isAlive(), "reopen must not wait behind an external subscription call");
            tickThread.join(1000);
            assertFalse(tickThread.isAlive(), "refresh tick must not wait behind an external subscription call");
            stopThread.join(1000);
            assertFalse(stopThread.isAlive(), "stop must remain bounded while subscription fails");
        } finally {
            file.observeRelease.countDown();
            exportThread.join(1000);
            reopenThread.join(1000);
            tickThread.join(1000);
            stopThread.join(1000);
        }

        assertNull(threadFailure.get());
        assertFalse(exportThread.isAlive());
        assertFalse(reopenThread.isAlive());
        assertFalse(tickThread.isAlive());
        assertFalse(stopThread.isAlive());
        assertEquals(0, plugin.liveSessions());
        assertTrue(file.stopped.get());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.error.session-failed")
                        && n.message().startsWith("external-psd-edit.error.subscribe-failed")));
    }

    @Test
    void multipleNewSessionsRequireOneConfirmation() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A), artMesh("mesh-2", IMAGE_B))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.uiHost().confirmResult = false;
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")));
        assertTrue(context.cubism().textures().calls().isEmpty(), "declined confirmation must not export");
        assertNotNull(context.uiHost().lastConfirmRequest());

        context.uiHost().confirmResult = true;
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")));
        assertEquals(
                List.of("export:raw-a", "export:raw-b"),
                context.cubism().textures().calls());
    }

    @Test
    void mixedExistingAndFreshSessionsCanBeCancelledAsOneBatch() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A), artMesh("mesh-2", IMAGE_B))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        final ContextMenuSelection existingSelection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, existingSelection);
        final FakePsdEditFile existing = context.cubism().textures().issued().get(RAW_A);

        context.uiHost().confirmResult = false;
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")));

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertEquals(1, existing.openCalls.get(), "cancel must not reopen an existing file");
        assertNotNull(context.uiHost().lastConfirmRequest());

        context.uiHost().confirmResult = true;
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")));

        final FakePsdEditFile fresh = context.cubism().textures().issued().get(RAW_B);
        assertEquals(
                List.of("export:raw-a", "export:raw-b"),
                context.cubism().textures().calls());
        assertEquals(2, existing.openCalls.get());
        assertEquals(1, existing.observeCalls.get());
        assertEquals(1, fresh.openCalls.get());
        assertEquals(1, fresh.observeCalls.get());
    }

    @Test
    void unavailableMultipleConfirmationFailsClosedWithoutExport() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A), artMesh("mesh-2", IMAGE_B))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.uiHost().confirmFailure = new UnsupportedOperationException("dialog unavailable");

        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")));

        assertTrue(
                context.cubism().textures().calls().isEmpty(),
                "an unavailable confirmation must not grant consent to export");
        assertEquals(0, plugin.liveSessions());
        assertTrue(
                context.uiHost().notifications().stream()
                        .anyMatch(n -> n.id().equals("external-psd-edit.error.confirmation-unavailable")
                                && n.severity().equals("ERROR")),
                "confirmation failure must be visible");
        assertTrue(
                context.uiHost().notifications().stream()
                        .noneMatch(n -> n.message().contains("multiple PSD files")),
                "confirmation failure must not be reported as an open failure");
    }

    @Test
    void differentBindingNeverReopensTheOldRawImageHandle() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile oldFile = context.cubism().textures().issued().get(RAW_A);

        context.cubism().relations(relations(OTHER_BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                OTHER_BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        final FakePsdEditFile newFile = context.cubism().textures().issued().get(RAW_A);
        assertEquals(1, oldFile.openCalls.get(), "the old binding must never be reopened");
        assertTrue(oldFile.stopped.get(), "the old binding must be stopped before replacement");
        assertTrue(oldFile.subscriptionClosed.get());
        assertNotSame(oldFile, newFile);
        assertEquals(
                List.of("export:raw-a", "export:raw-a"),
                context.cubism().textures().calls());
        assertEquals(1, newFile.openCalls.get());
        assertEquals(1, newFile.observeCalls.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actionFailureCases")
    void actionFailsVisiblyWhenTargetCannotBeResolved(
            String caseName,
            TextureRelationsSnapshot relations,
            ContextMenuSelection selection,
            String expectedNotificationId) {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations);
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        if (selection != null) {
            context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        } else {
            context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID);
        }

        assertTrue(context.cubism().textures().calls().isEmpty());
        assertTrue(context.uiHost().notifications().stream().anyMatch(n -> n.id().equals(expectedNotificationId)));
    }

    private static Stream<Arguments> actionFailureCases() {
        return Stream.of(
                Arguments.of(
                        "refuses when menu binding is stale",
                        relations("binding-now", List.of(artMesh("mesh-1", IMAGE_A))),
                        selection(
                                "binding-then",
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")),
                        "external-psd-edit.error.document-changed"),
                Arguments.of(
                        "fails visibly when nothing resolves",
                        relations(BINDING, List.of()),
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "missing-mesh")),
                        "external-psd-edit.error.unresolved"),
                Arguments.of(
                        "no context menu selection fails visibly",
                        relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))),
                        null,
                        "external-psd-edit.error.no-selection"));
    }

    @Test
    void saveOnActiveSessionAppliesNativeReplace() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final PsdFileRevision revision = new TestRevision("rev-1");
        file.saveListener.accept(revision);

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:rev-1"),
                context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.applied")));
    }

    @Test
    void rejectedSaveSubmissionDoesNotRunNativeOrLeaveInFlightStuck() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        context.taskScheduler.rejectNextSubmit();

        file.saveListener.accept(new TestRevision("rejected"));
        file.saveListener.accept(new TestRevision("must-not-run"));

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.paused-reason")));
    }

    @Test
    void acceptedTaskCanceledBeforeNativePausesWithoutFallbackDispatch() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        context.taskScheduler.autoRunSubmissions(false);
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);

        file.saveListener.accept(new TestRevision("canceled"));
        context.taskScheduler
                .lastSubmitHandle()
                .complete(outcome(context.taskScheduler.lastSubmitHandle().id(), TaskOutcomeStatus.CANCELED));
        file.saveListener.accept(new TestRevision("after-cancel"));

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.paused-reason")));
    }

    @Test
    void acceptedTaskFailureBeforeNativeProcessesAQueuedFreshSave() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        context.taskScheduler.autoRunSubmissions(false);
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);

        file.saveListener.accept(new TestRevision("task-failed"));
        final RecordingTaskHandle failedTask = context.taskScheduler.lastSubmitHandle();
        file.saveListener.accept(new TestRevision("fresh-pending"));
        context.taskScheduler.autoRunSubmissions(true);
        failedTask.complete(failedOutcome(failedTask.id(), "before native"));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:fresh-pending"),
                context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.error.replace-failed")));
    }

    @Test
    void lateTaskFailureAfterNativeDispatchCannotCompeteWithNativeCompletion() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        context.taskScheduler.deferAutoRunCompletion();
        final CompletableFuture<PsdReplaceResult> nativeCompletion = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(nativeCompletion);
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);

        file.saveListener.accept(new TestRevision("native-wins"));
        final RecordingTaskHandle task = context.taskScheduler.lastSubmitHandle();
        task.complete(failedOutcome(task.id(), "late task terminal"));
        nativeCompletion.complete(applied(RAW_A, RAW_A, "native-wins"));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:native-wins"),
                context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.applied")));
        assertTrue(context.uiHost().notifications().stream()
                .noneMatch(n -> n.id().equals("external-psd-edit.status.paused-partial")));
    }

    @Test
    void refreshTracksPassiveRawMigrationAndMenuReusesExistingSession() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        context.cubism().relations(singleRelation(BINDING, 7L, 2L, RAW_B));
        context.taskScheduler.runRefreshTick();
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertEquals(2, file.openCalls.get(), "menu reopen must follow refreshed current raw");
    }

    @Test
    void refreshLateTickCannotOverwriteRawAfterNativeTransition() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final CompletableFuture<PsdReplaceResult> nativeCompletion = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(nativeCompletion);
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        file.saveListener.accept(new TestRevision("native-transition"));
        final TextureRelationsSnapshot relationA = singleRelation(BINDING, 7L, 2L, RAW_A);
        context.cubism().relations(relationA);
        context.cubism().textures().onNextRelationsRead(() -> {
            context.cubism().relations(singleRelation(BINDING, 7L, 3L, RAW_B));
            nativeCompletion.complete(applied(RAW_A, RAW_B, "native-transition"));
        });

        context.taskScheduler.runRefreshTick();
        context.cubism().relations(TextureRelationsSnapshot.unavailable());
        context.taskScheduler.runRefreshTick();

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:native-transition"),
                context.cubism().textures().calls());
        final List<StatusNotification> paused = context.uiHost().notifications().stream()
                .filter(n -> n.id().equals("external-psd-edit.status.paused-reason"))
                .toList();
        assertEquals(1, paused.size(), "the follow-up diagnostic must observe one current raw");
        assertTrue(
                paused.get(0).message().contains("raw-b"),
                "the stale refresh must not overwrite the completed raw-B observation");
        assertFalse(paused.get(0).message().contains("raw-a"), "the follow-up diagnostic must not report stale raw-A");
    }

    @Test
    void externalRefreshCancellationIsReportedAndDoesNotPretendToStayEnabled() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        context.taskScheduler.cancelRefreshExternally();

        assertEquals(1, context.taskScheduler.scheduleCount());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.error.refresh-unavailable")
                        && n.severity().equals("ERROR")));
    }

    @Test
    void rejectedRefreshSubmissionIsVisibleAndDoesNotRunDirectReads() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        context.taskScheduler.rejectNextSchedule();
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final int readsAfterOpen = context.cubism().textures().relationReads();

        assertEquals(1, context.taskScheduler.scheduleCount());
        assertEquals(readsAfterOpen, context.cubism().textures().relationReads());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.error.refresh-unavailable")));
    }

    @Test
    void disablingSessionClosesRefreshAndIgnoresLateTick() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final int readsBeforeDisable = context.cubism().textures().relationReads();

        plugin.disable();
        context.taskScheduler.runRefreshTick();

        assertTrue(context.taskScheduler.refreshClosed());
        assertEquals(readsBeforeDisable, context.cubism().textures().relationReads());
        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
    }

    @Test
    void saveFollowsModelImageAcrossRawMigrationAndMenuReusesOneSession() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final CompletableFuture<PsdReplaceResult> first = new CompletableFuture<>();
        final CompletableFuture<PsdReplaceResult> second = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(first);
        context.cubism().textures().replaceCompletions.add(second);

        file.saveListener.accept(new TestRevision("save-a"));
        context.cubism().relations(singleRelation(BINDING, 7L, 2L, RAW_B));
        first.complete(applied(RAW_A, RAW_B, "save-a"));

        file.saveListener.accept(new TestRevision("save-b"));
        context.cubism().relations(singleRelation(BINDING, 7L, 3L, RAW_C));
        second.complete(applied(RAW_B, RAW_C, "save-b"));

        // The captured model-image identity, rather than the old raw id, finds this session.
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:save-a", "replace:raw-b:save-b"),
                context.cubism().textures().calls());
        assertEquals(2, file.openCalls.get(), "a migrated session is reopened, not re-exported");
        assertEquals(1, file.observeCalls.get());
        assertTrue(context.uiHost().notifications().stream()
                        .filter(n -> n.id().equals("external-psd-edit.status.applied"))
                        .count()
                >= 2);
    }

    @Test
    void savesWhileReplacingKeepOnlyLatestPendingRevision() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final CompletableFuture<PsdReplaceResult> first = new CompletableFuture<>();
        final CompletableFuture<PsdReplaceResult> second = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(first);
        context.cubism().textures().replaceCompletions.add(second);

        file.saveListener.accept(new TestRevision("save-a"));
        file.saveListener.accept(new TestRevision("save-b"));
        file.saveListener.accept(new TestRevision("save-c"));
        assertEquals(
                List.of("export:raw-a", "replace:raw-a:save-a"),
                context.cubism().textures().calls());

        context.cubism().relations(singleRelation(BINDING, 7L, 2L, RAW_B));
        first.complete(applied(RAW_A, RAW_B, "save-a"));
        assertEquals(
                List.of("export:raw-a", "replace:raw-a:save-a", "replace:raw-b:save-c"),
                context.cubism().textures().calls());

        context.cubism().relations(singleRelation(BINDING, 7L, 3L, RAW_C));
        second.complete(applied(RAW_B, RAW_C, "save-c"));
    }

    @Test
    void undoAndRedoRelationsDriveTheNextSaveWithoutReplayingConsumedRevision() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        file.saveListener.accept(new TestRevision("save-a"));
        context.cubism().relations(singleRelation(BINDING, 7L, 3L, RAW_A));
        file.saveListener.accept(new TestRevision("undo-a"));
        context.cubism().relations(singleRelation(BINDING, 7L, 5L, RAW_B));
        file.saveListener.accept(new TestRevision("redo-b"));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:save-a", "replace:raw-a:undo-a", "replace:raw-b:redo-b"),
                context.cubism().textures().calls());
    }

    @Test
    void afterMismatchPausesAndDoesNotDispatchPendingRevision() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final CompletableFuture<PsdReplaceResult> first = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(first);
        file.saveListener.accept(new TestRevision("save-a"));
        file.saveListener.accept(new TestRevision("save-b"));
        context.cubism().relations(singleRelation(BINDING, 7L, 2L, RAW_B));
        first.complete(applied(RAW_A, RAW_A, "save-a"));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:save-a"),
                context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.paused-partial")));
    }

    @Test
    void consumedRevisionMismatchIsNotReportedAsApplied() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final CompletableFuture<PsdReplaceResult> completion = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(completion);
        file.saveListener.accept(new TestRevision("save-a"));
        completion.complete(new PsdReplaceResult(
                PsdReplaceResult.Status.APPLIED,
                "applied with the wrong consumed revision",
                RAW_A,
                Optional.of(RAW_A),
                Optional.of(new TestRevision("other")),
                Optional.empty()));

        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.paused-partial")));
        assertTrue(context.uiHost().notifications().stream()
                .noneMatch(n -> n.id().equals("external-psd-edit.status.applied")));
    }

    @Test
    void partialFailureDropsQueuedRevisionAndPauses() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final CompletableFuture<PsdReplaceResult> completion = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(completion);
        file.saveListener.accept(new TestRevision("save-a"));
        file.saveListener.accept(new TestRevision("save-b"));
        completion.complete(new PsdReplaceResult(
                PsdReplaceResult.Status.PARTIAL_FAILURE,
                "native outcome uncertain",
                RAW_A,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:save-a"),
                context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.paused-partial")));
    }

    @Test
    void failedReplacementDropsPendingButAFreshSaveCanTryAgain() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final CompletableFuture<PsdReplaceResult> failed = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(failed);
        file.saveListener.accept(new TestRevision("save-a"));
        file.saveListener.accept(new TestRevision("save-b"));
        failed.complete(new PsdReplaceResult(
                PsdReplaceResult.Status.FAILED, "failed", RAW_A, Optional.empty(), Optional.empty(), Optional.empty()));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:save-a", "replace:raw-a:save-b"),
                context.cubism().textures().calls());
    }

    @ParameterizedTest(name = "duplicate {0} fails closed before replacement")
    @ValueSource(strings = {"model-image", "raw-index"})
    void duplicateGraphStructureFailsClosedBeforeAReplacement(String type) {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        if ("model-image".equals(type)) {
            context.cubism().relations(duplicateModelImageRelations(BINDING, 7L, 2L, RAW_A));
            file.saveListener.accept(new TestRevision("ambiguous"));
            assertFalse(context.uiHost().notifications().isEmpty());
        } else {
            context.cubism().relations(duplicateRawRelations(BINDING, 7L, 2L, RAW_A));
            file.saveListener.accept(new TestRevision("raw-collision"));
            assertTrue(file.stopped.get());
        }

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
    }

    @Test
    void lateNativeCompletionAfterDisableCannotPublishOrResubmit() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final CompletableFuture<PsdReplaceResult> completion = new CompletableFuture<>();
        context.cubism().textures().replaceCompletions.add(completion);
        file.saveListener.accept(new TestRevision("late"));

        plugin.disable();
        completion.complete(applied(RAW_A, RAW_A, "late"));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:late"),
                context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .noneMatch(n -> n.id().equals("external-psd-edit.status.applied")));
        assertTrue(file.stopped.get());
    }

    @Test
    void generationChangeStopsOldSessionBeforeOpeningNewAnchor() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        final FakePsdEditFile oldFile = context.cubism().textures().issued().get(RAW_A);

        context.cubism().relations(singleRelation(BINDING, 8L, 2L, RAW_B));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(
                List.of("export:raw-a", "export:raw-b"),
                context.cubism().textures().calls());
        assertTrue(oldFile.stopped.get());
        assertEquals(1, plugin.liveSessions());
    }

    @Test
    void addingAnotherModelImageOnTheSameRawStillReusesTheExistingSession() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(singleRelation(BINDING, 7L, 1L, RAW_A));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);

        context.cubism().relations(twoImageRelations(BINDING, 7L, 2L, RAW_A, RAW_A));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertEquals(2, file.openCalls.get());
        assertEquals(1, plugin.liveSessions());
    }

    @Test
    void twoExistingAnchorsThatFreshlyConvergeOnOneRawRejectMenuWithoutGuessing() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(twoImageRelations(BINDING, 7L, 1L, RAW_A, RAW_B));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")));
        context.cubism().relations(twoImageRelations(BINDING, 7L, 2L, RAW_A, RAW_A));

        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        assertEquals(
                List.of("export:raw-a", "export:raw-b"),
                context.cubism().textures().calls());
        assertNotNull(context.cubism().textures().issued().get(RAW_A));
        assertNotNull(context.cubism().textures().issued().get(RAW_B));
    }

    @Test
    void partialFailurePausesAutomaticImports() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        context.cubism().textures().replaceStatus = PsdReplaceResult.Status.PARTIAL_FAILURE;
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        file.saveListener.accept(new TestRevision("rev-1"));
        file.saveListener.accept(new TestRevision("rev-2"));

        assertEquals(
                List.of("export:raw-a", "replace:raw-a:rev-1"),
                context.cubism().textures().calls(),
                "a paused session must not attempt further replacements");
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.status.paused-partial")));
    }

    @Test
    void saveAfterBindingChangeInvalidatesSessionWithoutReplacing() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        context.cubism().relations(relations("other-binding", List.of(artMesh("mesh-1", IMAGE_A))));
        context.cubism().textures().issued().get(RAW_A).saveListener.accept(new TestRevision("rev-1"));

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.warn.session-invalidated")));
    }

    @Test
    void disableStopsSessionsAndClosesSaveSubscription() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        plugin.disable();

        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertTrue(file.stopped.get(), "disable must stop the runtime file handle");
        assertTrue(file.subscriptionClosed.get(), "disable must close the save subscription");
        file.saveListener.accept(new TestRevision("rev-2"));
        assertEquals(
                List.of("export:raw-a"), context.cubism().textures().calls(), "a stopped session must not replace");
    }

    @Test
    void disableThenEnableRecoversASessionForTheSameBinding() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
                BINDING,
                ContextMenuRegistry.Location.PART_TAB,
                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"));
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        plugin.disable();
        assertEquals(0, plugin.liveSessions());
        final FakePsdEditFile firstFile = context.cubism().textures().issued().get(RAW_A);
        assertTrue(firstFile.stopped.get());
        assertTrue(firstFile.subscriptionClosed.get());

        // Re-enabling with the same live binding must admit a fresh export and session.
        plugin.enable();
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(
                List.of("export:raw-a", "export:raw-a"),
                context.cubism().textures().calls(),
                "recovery must export a new temporary PSD rather than replaying the stopped one");
        assertEquals(1, plugin.liveSessions());
        assertEquals(1, firstFile.openCalls.get(), "the stopped file must not reopen");
        final FakePsdEditFile recovered = context.cubism().textures().issued().get(RAW_A);
        assertNotSame(firstFile, recovered);
        assertTrue(recovered.subscribedBeforeOpen);
        assertEquals(1, recovered.openCalls.get());
    }

    @Test
    void modelCloseInvalidatesSessionsWhoseBindingChanged() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        context.cubism().relations(relations("binding-after-close", List.of()));
        plugin.onModelClosed(null);

        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertTrue(file.stopped.get());
        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.warn.session-invalidated")));
    }

    @ParameterizedTest(name = "operation failure on {0}")
    @ValueSource(strings = {"export", "open"})
    void operationFailureFailsVisiblyAndReleasesSession(String operation) {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        if ("export".equals(operation)) {
            context.cubism().textures().exportStatus = PsdExportResult.Status.UNAVAILABLE;
        } else {
            context.cubism().textures().openStatus = PsdFileOperationResult.Status.UNAVAILABLE;
        }
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions()
                .execute(
                        ExternalPsdEditPlugin.OPEN_ACTION_ID,
                        selection(
                                BINDING,
                                ContextMenuRegistry.Location.PART_TAB,
                                item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));

        assertTrue(context.uiHost().notifications().stream()
                .anyMatch(n -> n.id().equals("external-psd-edit.error.session-failed")));
        if ("export".equals(operation)) {
            assertTrue(context.uiHost().notifications().stream()
                    .anyMatch(n -> n.id().equals("external-psd-edit.error.session-failed")
                            && n.severity().equals("ERROR")));
            assertEquals(0, plugin.liveSessions());
        } else {
            final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
            assertTrue(file.stopped.get(), "a failed open must still stop the file handle");
        }
    }
}
