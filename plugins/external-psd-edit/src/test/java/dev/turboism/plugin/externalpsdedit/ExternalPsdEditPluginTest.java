package dev.turboism.plugin.externalpsdedit;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawImageDetails;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalPsdEditPluginTest {

    private static final String BINDING = "session-test:model-1:1";
    private static final RawImageId RAW_A = new RawImageId("raw-a");
    private static final RawImageId RAW_B = new RawImageId("raw-b");
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
            context.actions().actions().stream().map(ActionRegistry.Action::id).toList()
        );
        assertEquals(
            Set.of(
                "external-psd-edit.open.part",
                "external-psd-edit.open.deformer",
                "external-psd-edit.open.workspace"
            ),
            Set.copyOf(context.contextMenu().contributions().stream()
                .map(ContextMenuRegistry.ContextMenuContribution::id)
                .toList())
        );
        for (final ContextMenuRegistry.ContextMenuContribution contribution
            : context.contextMenu().contributions()) {
            assertEquals(
                Set.of(ContextMenuRegistry.ObjectKind.ART_MESH),
                contribution.objectKinds()
            );
            assertEquals(ExternalPsdEditPlugin.OPEN_ACTION_ID, contribution.actionId());
        }
    }

    @Test
    void actionResolvesArtMeshToCurrentRawImageExportsSubscribesThenOpens() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(
            artMesh("mesh-1", IMAGE_A),
            artMesh("mesh-2", IMAGE_B)
        )));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertNotNull(file);
        assertTrue(file.subscribedBeforeOpen, "saves must be subscribed before opening");
        assertEquals(1, file.openCalls.get());
        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.status.editing")));
    }

    @Test
    void sharedModelImageDedupesToOneSessionPerRawImage() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(
            artMesh("mesh-1", IMAGE_A),
            artMesh("mesh-2", IMAGE_A)
        )));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")
        ));

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertNull(context.uiHost().lastConfirmRequest(),
            "a single new file must not require confirmation");
    }

    @Test
    void repeatedInvocationReusesLiveSession() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        final ContextMenuSelection selection = selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        );
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection);

        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls());
        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.status.already-open")));
    }

    @Test
    void multipleNewSessionsRequireOneConfirmation() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(
            artMesh("mesh-1", IMAGE_A),
            artMesh("mesh-2", IMAGE_B)
        )));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.uiHost().confirmResult = false;
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")
        ));
        assertTrue(context.cubism().textures().calls().isEmpty(),
            "declined confirmation must not export");
        assertNotNull(context.uiHost().lastConfirmRequest());

        context.uiHost().confirmResult = true;
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")
        ));
        assertEquals(
            List.of("export:raw-a", "export:raw-b"),
            context.cubism().textures().calls()
        );
    }

    @Test
    void actionRefusesWhenMenuBindingIsStale() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations("binding-now", List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            "binding-then", ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        assertTrue(context.cubism().textures().calls().isEmpty());
        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.error.document-changed")));
    }

    @Test
    void actionFailsVisiblyWhenNothingResolves() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of()));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "missing-mesh")
        ));

        assertTrue(context.cubism().textures().calls().isEmpty());
        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.error.unresolved")));
    }

    @Test
    void saveOnActiveSessionAppliesNativeReplace() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        final PsdFileRevision revision = new TestRevision("rev-1");
        file.saveListener.accept(revision);

        assertEquals(
            List.of("export:raw-a", "replace:raw-a:rev-1"),
            context.cubism().textures().calls()
        );
        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.status.applied")));
    }

    @Test
    void partialFailurePausesAutomaticImports() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        context.cubism().textures().replaceStatus = PsdReplaceResult.Status.PARTIAL_FAILURE;
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        file.saveListener.accept(new TestRevision("rev-1"));
        file.saveListener.accept(new TestRevision("rev-2"));

        assertEquals(
            List.of("export:raw-a", "replace:raw-a:rev-1"),
            context.cubism().textures().calls(),
            "a paused session must not attempt further replacements"
        );
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
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        context.cubism().relations(relations("other-binding", List.of(artMesh("mesh-1", IMAGE_A))));
        context.cubism().textures().issued().get(RAW_A)
            .saveListener.accept(new TestRevision("rev-1"));

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
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        plugin.disable();

        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertTrue(file.stopped.get(), "disable must stop the runtime file handle");
        assertTrue(file.subscriptionClosed.get(), "disable must close the save subscription");
        file.saveListener.accept(new TestRevision("rev-2"));
        assertEquals(List.of("export:raw-a"), context.cubism().textures().calls(),
            "a stopped session must not replace");
    }

    @Test
    void disableThenEnableRecoversASessionForTheSameBinding() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();
        final ContextMenuSelection selection = selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        );
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
            "recovery must export a new temporary PSD rather than replaying the stopped one"
        );
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
        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        context.cubism().relations(relations("binding-after-close", List.of()));
        plugin.onModelClosed(null);

        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertTrue(file.stopped.get());
        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.warn.session-invalidated")));
    }

    @Test
    void exportFailureFailsVisiblyAndLeavesNoSession() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        context.cubism().textures().exportStatus = PsdExportResult.Status.UNAVAILABLE;
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.error.session-failed")
                && n.severity().equals("ERROR")));
        assertEquals(0, plugin.liveSessions());
    }

    @Test
    void openFailureFailsVisiblyAndReleasesSession() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        context.cubism().textures().openStatus = PsdFileOperationResult.Status.UNAVAILABLE;
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID, selection(
            BINDING, ContextMenuRegistry.Location.PART_TAB,
            item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")
        ));

        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.error.session-failed")));
        final FakePsdEditFile file = context.cubism().textures().issued().get(RAW_A);
        assertTrue(file.stopped.get(), "a failed open must still stop the file handle");
    }

    @Test
    void noContextMenuSelectionFailsVisibly() {
        final RecordingPluginContext context = new RecordingPluginContext(new TestPluginLogger());
        context.cubism().relations(relations(BINDING, List.of(artMesh("mesh-1", IMAGE_A))));
        final ExternalPsdEditPlugin plugin = new ExternalPsdEditPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute(ExternalPsdEditPlugin.OPEN_ACTION_ID);

        assertTrue(context.uiHost().notifications().stream()
            .anyMatch(n -> n.id().equals("external-psd-edit.error.no-selection")));
        assertTrue(context.cubism().textures().calls().isEmpty());
    }

    private static ContextMenuSelection.Item item(
        final ContextMenuRegistry.ObjectKind kind,
        final String id
    ) {
        return new ContextMenuSelection.Item(kind, id);
    }

    private static ContextMenuSelection selection(
        final String binding,
        final ContextMenuRegistry.Location location,
        final ContextMenuSelection.Item... items
    ) {
        return new ContextMenuSelection(1L, binding, location, List.of(items));
    }

    private static ArtMeshTextureInputs artMesh(final String id, final ModelImageId image) {
        return new ArtMeshTextureInputs(
            new ArtMeshId(id),
            List.of(new TextureInputBinding(
                TextureInputBinding.Kind.MODEL_IMAGE,
                Optional.of(image),
                Optional.empty(),
                TextureInputBinding.ResolutionState.RESOLVED
            )),
            OptionalInt.of(0)
        );
    }

    private static TextureRelationsSnapshot relations(
        final String binding,
        final List<ArtMeshTextureInputs> artMeshes
    ) {
        final List<ModelImageRelation> images = new ArrayList<>();
        final List<RawImageDetails> raws = new ArrayList<>();
        for (final ArtMeshTextureInputs inputs : artMeshes) {
            final ModelImageId imageId = inputs.inputs().get(0).modelImageId().orElseThrow();
            if (images.stream().noneMatch(r -> r.id().equals(imageId))) {
                final RawImageId raw = imageId.equals(IMAGE_B) ? RAW_B : RAW_A;
                images.add(new ModelImageRelation(
                    imageId,
                    entry(imageId),
                    List.of(raw),
                    Optional.of(raw),
                    java.util.Map.of(),
                    List.of(inputs.id())
                ));
            }
            if (raws.stream().noneMatch(r -> r.id().equals(imageToRaw(imageId)))) {
                raws.add(new RawImageDetails(
                    rawTexture(imageToRaw(imageId)),
                    RawImageDetails.SourceKind.PSD,
                    List.of(),
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                ));
            }
        }
        return new TextureRelationsSnapshot(
            TextureRelationsSnapshot.Availability.AVAILABLE,
            binding,
            1L,
            1L,
            raws,
            images,
            List.of(),
            artMeshes
        );
    }

    private static RawImageId imageToRaw(final ModelImageId id) {
        return id.equals(IMAGE_B) ? RAW_B : RAW_A;
    }

    private static RawTexture rawTexture(final RawImageId id) {
        return new RawTexture() {
            @Override public RawImageId id() { return id; }
            @Override public String name() { return id.value(); }
            @Override public int width() { return 64; }
            @Override public int height() { return 64; }
        };
    }

    private static ModelImageEntry entry(final ModelImageId id) {
        return new ModelImageEntry() {
            @Override public ModelImageId id() { return id; }
            @Override public String name() { return id.value(); }
            @Override public int width() { return 64; }
            @Override public int height() { return 64; }
        };
    }

    private record TestRevision(String value) implements PsdFileRevision {
    }

    private static final class FakePsdEditFile implements PsdEditFile {
        private final java.util.concurrent.atomic.AtomicBoolean stopped =
            new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean subscriptionClosed =
            new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger openCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean subscribedBeforeOpen;
        private volatile boolean observed;
        private volatile Consumer<PsdFileRevision> saveListener = ignored -> { };
        private volatile PsdFileOperationResult.Status openStatus =
            PsdFileOperationResult.Status.OPENED;

        @Override
        public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
            subscribedBeforeOpen = observed && !subscriptionClosed.get();
            openCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                new PsdFileOperationResult(openStatus, "test"));
        }

        @Override
        public Registration observeSaves(final Consumer<PsdFileRevision> listener) {
            saveListener = listener;
            observed = true;
            return () -> subscriptionClosed.set(true);
        }

        @Override
        public CompletionStage<PsdFileOperationResult> stop() {
            stopped.set(true);
            return CompletableFuture.completedFuture(
                new PsdFileOperationResult(PsdFileOperationResult.Status.STOPPED, "test"));
        }
    }

    private static final class RecordingModelTextures implements ModelTextures {
        private final java.util.Map<RawImageId, FakePsdEditFile> issued =
            new java.util.LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private volatile TextureRelationsSnapshot relations = TextureRelationsSnapshot.unavailable();
        private volatile PsdExportResult.Status exportStatus = PsdExportResult.Status.EXPORTED;
        private volatile PsdReplaceResult.Status replaceStatus = PsdReplaceResult.Status.APPLIED;
        private volatile PsdFileOperationResult.Status openStatus =
            PsdFileOperationResult.Status.OPENED;

        List<String> calls() { return List.copyOf(calls); }
        java.util.Map<RawImageId, FakePsdEditFile> issued() { return issued; }

        @Override public List<RawTexture> rawImages() { return List.of(); }
        @Override public List<ModelImageGroup> modelImageGroups() { return List.of(); }
        @Override public List<AtlasTexture> textureAtlases() { return List.of(); }
        @Override public TextureRelationsSnapshot relations() { return relations; }

        @Override
        public CompletionStage<PsdExportResult> exportRawImagePsd(final RawImageId source) {
            calls.add("export:" + source.value());
            if (exportStatus != PsdExportResult.Status.EXPORTED) {
                return CompletableFuture.completedFuture(new PsdExportResult(
                    exportStatus, "test", source, Optional.empty(), Optional.empty()));
            }
            final FakePsdEditFile file = new FakePsdEditFile();
            file.openStatus = openStatus;
            issued.put(source, file);
            return CompletableFuture.completedFuture(new PsdExportResult(
                PsdExportResult.Status.EXPORTED, "test", source,
                Optional.of(file), Optional.of(new TestRevision("baseline"))));
        }

        @Override
        public CompletionStage<PsdReplaceResult> replaceRawImagePsd(
            final RawImageId target,
            final PsdEditFile file,
            final PsdFileRevision revision
        ) {
            calls.add("replace:" + target.value() + ":"
                + ((TestRevision) revision).value());
            if (replaceStatus != PsdReplaceResult.Status.APPLIED) {
                return CompletableFuture.completedFuture(new PsdReplaceResult(
                    replaceStatus, "test", target,
                    Optional.empty(), Optional.empty(), Optional.empty()));
            }
            return CompletableFuture.completedFuture(new PsdReplaceResult(
                PsdReplaceResult.Status.APPLIED, "test", target,
                Optional.of(target), Optional.of(revision), Optional.empty()));
        }

        @Override public void addModelImageGroup(final String name) { throw unsupported(); }
        @Override public void removeModelImage(final ModelImageId id) { throw unsupported(); }
        @Override public TextureAtlasId addTextureAtlas(
            final String name, final int widthPixels, final int heightPixels
        ) { throw unsupported(); }
        @Override public void removeTextureAtlas(final TextureAtlasId id) { throw unsupported(); }
        @Override public void removeRawImage(final RawImageId id) { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class FixedCubismFacade implements CubismFacade {
        private final RecordingModelTextures textures = new RecordingModelTextures();

        RecordingModelTextures textures() { return textures; }
        void relations(final TextureRelationsSnapshot snapshot) { textures.relations = snapshot; }

        @Override public CubismRuntimeSnapshot runtime() { throw unsupported(); }
        @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return true; }
        @Override public dev.turboism.sdk.cubism.model.CubismModelAccess model() {
            return () -> new CubismModel() {
                @Override public ModelId id() { return new ModelId("model-1"); }
                @Override public ModelTextures textures() { return textures; }
                @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                    throw unsupported();
                }
                @Override public dev.turboism.sdk.cubism.model.Parts parts() {
                    throw unsupported();
                }
                @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
                    throw unsupported();
                }
                @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
                    throw unsupported();
                }
                @Override public dev.turboism.sdk.cubism.model.Glues glues() {
                    throw unsupported();
                }
                @Override public void update() { }
            };
        }
        @Override public TransactionManager transactionManager() { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final DisposableScope disposableScope = new DisposableScope();
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingContextMenuRegistry contextMenu = new RecordingContextMenuRegistry();
        private final RecordingUiHost uiHost = new RecordingUiHost();
        private final PluginLogger logger;
        private final FixedCubismFacade cubism = new FixedCubismFacade();

        RecordingPluginContext(final PluginLogger logger) {
            this.logger = logger;
        }

        @Override public PluginDescriptor descriptor() { throw new UnsupportedOperationException(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public FixedCubismFacade cubism() { return cubism; }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public RecordingActionRegistry actions() { return actions; }
        @Override public MenuRegistry menus() {
            return contribution -> () -> { };
        }
        @Override public RecordingContextMenuRegistry contextMenu() { return contextMenu; }
        @Override public UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { return disposableScope; }
        @Override public RecordingUiHost uiHost() { return uiHost; }
        @Override public PluginTaskScheduler tasks() {
            return new PluginTaskScheduler() {
                @Override
                public TaskSubmission submit(final PluginTaskRequest request) {
                    try {
                        request.action().run(new dev.turboism.sdk.plugin.CancellationToken() {
                            @Override public boolean isCancellationRequested() { return false; }
                            @Override public void checkCanceled() { }
                        });
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return new TaskSubmission(
                        dev.turboism.sdk.task.TaskSubmissionStatus.ACCEPTED,
                        new dev.turboism.sdk.task.TaskHandle() {
                            @Override public dev.turboism.sdk.task.TaskId id() {
                                return request.id();
                            }
                            @Override public dev.turboism.sdk.task.TaskProgress progress() {
                                return new dev.turboism.sdk.task.TaskProgress(
                                    1, Optional.empty());
                            }
                            @Override public boolean cancel() { return true; }
                            @Override public CompletionStage<dev.turboism.sdk.task.TaskOutcome>
                                completion() {
                                return CompletableFuture.completedFuture(null);
                            }
                            @Override public void close() { }
                        },
                        Optional.empty()
                    );
                }
                @Override
                public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
                    throw new UnsupportedOperationException("not used");
                }
            };
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();
        List<Action> actions() { return actions; }
        @Override public Registration register(final String id, final Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }
        void execute(final String id) {
            execute(id, new ActionContext() { });
        }
        void execute(final String id, final ContextMenuSelection selection) {
            execute(id, new ActionContext() {
                @Override public Optional<ContextMenuSelection> contextMenuSelection() {
                    return Optional.of(selection);
                }
            });
        }
        private void execute(final String id, final ActionContext context) {
            actions.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow()
                .handler().accept(context);
        }
    }

    private static final class RecordingContextMenuRegistry implements ContextMenuRegistry {
        private final List<ContextMenuContribution> contributions = new ArrayList<>();
        List<ContextMenuContribution> contributions() { return List.copyOf(contributions); }
        @Override public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<StatusNotification> notifications = new ArrayList<>();
        private boolean confirmResult = true;
        private DialogRequest lastConfirmRequest;
        List<StatusNotification> notifications() { return notifications; }
        DialogRequest lastConfirmRequest() { return lastConfirmRequest; }
        @Override public Registration contributeOverlay(OverlayContribution contribution) { throw unsupported(); }
        @Override public Registration contributeBoundingBoxOverlayButton(dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution) { throw unsupported(); }
        @Override public ContextSourceSnapshot contextSource() { throw unsupported(); }
        @Override public ViewportSnapshot viewport() { throw unsupported(); }
        @Override public Registration openDialog(DialogRequest request) { throw unsupported(); }
        @Override public boolean confirmDialog(final DialogRequest request) {
            lastConfirmRequest = request;
            return confirmResult;
        }
        @Override public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) { throw unsupported(); }
        @Override public Optional<String> requestFile(FileChooserRequest request) { return Optional.empty(); }
        @Override public Registration notifyStatus(final StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }
        @Override public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) { throw unsupported(); }
        @Override public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) { throw unsupported(); }
        @Override public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class TestPluginLogger implements PluginLogger {
        private final List<String> messages = new ArrayList<>();
        @Override public void debug(final String message) { messages.add("DEBUG: " + message); }
        @Override public void info(final String message) { messages.add("INFO: " + message); }
        @Override public void warn(final String message) { messages.add("WARN: " + message); }
        @Override public void error(final String message) { messages.add("ERROR: " + message); }
        @Override public void error(final String message, final Throwable throwable) {
            messages.add("ERROR: " + message + ": " + throwable.getMessage());
        }
        List<String> messages() { return List.copyOf(messages); }
    }
}
