package dev.turboism.plugin.externalpsdedit;

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
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
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
import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskFailure;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskRejectionReason;
import dev.turboism.sdk.task.TaskRunOutcome;
import dev.turboism.sdk.task.TaskRunOutcomeStatus;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.task.TaskSubmissionStatus;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.test.action.RecordingActionRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.plugin.RecordingPluginLogger;
import dev.turboism.test.ui.RecordingContextMenuRegistry;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class ExternalPsdEditTestFixtures {

    static final String BINDING = "session-test:model-1:1";
    static final String OTHER_BINDING = "session-test:model-2:1";
    static final RawImageId RAW_A = new RawImageId("raw-a");
    static final RawImageId RAW_B = new RawImageId("raw-b");
    static final RawImageId RAW_C = new RawImageId("raw-c");
    static final ModelImageId IMAGE_A = new ModelImageId("image-a");
    static final ModelImageId IMAGE_B = new ModelImageId("image-b");

    static ContextMenuSelection.Item item(final ContextMenuRegistry.ObjectKind kind, final String id) {
        return new ContextMenuSelection.Item(kind, id);
    }

    static ContextMenuSelection selection(
            final String binding,
            final ContextMenuRegistry.Location location,
            final ContextMenuSelection.Item... items) {
        return new ContextMenuSelection(1L, binding, location, List.of(items));
    }

    static ArtMeshTextureInputs artMesh(final String id, final ModelImageId image) {
        return new ArtMeshTextureInputs(
                new ArtMeshId(id),
                List.of(new TextureInputBinding(
                        TextureInputBinding.Kind.MODEL_IMAGE,
                        Optional.of(image),
                        Optional.empty(),
                        TextureInputBinding.ResolutionState.RESOLVED)),
                OptionalInt.of(0));
    }

    static TextureRelationsSnapshot relations(final String binding, final List<ArtMeshTextureInputs> artMeshes) {
        return relations(binding, 1L, 1L, artMeshes, Map.of());
    }

    static TextureRelationsSnapshot withMeshInputs(
            final TextureRelationsSnapshot base, final List<TextureInputBinding> inputs, final int currentIndex) {
        return new TextureRelationsSnapshot(
                base.availability(),
                base.binding(),
                base.generation(),
                base.revision(),
                base.rawImages(),
                base.modelImages(),
                base.groups(),
                List.of(new ArtMeshTextureInputs(new ArtMeshId("mesh-1"), inputs, OptionalInt.of(currentIndex))));
    }

    static TextureRelationsSnapshot singleRelation(
            final String binding, final long generation, final long revision, final RawImageId raw) {
        return relations(binding, generation, revision, List.of(artMesh("mesh-1", IMAGE_A)), Map.of(IMAGE_A, raw));
    }

    static TextureRelationsSnapshot twoImageRelations(
            final String binding,
            final long generation,
            final long revision,
            final RawImageId firstRaw,
            final RawImageId secondRaw) {
        return relations(
                binding,
                generation,
                revision,
                List.of(artMesh("mesh-1", IMAGE_A), artMesh("mesh-2", IMAGE_B)),
                Map.of(IMAGE_A, firstRaw, IMAGE_B, secondRaw));
    }

    static TextureRelationsSnapshot relations(
            final String binding,
            final long generation,
            final long revision,
            final List<ArtMeshTextureInputs> artMeshes,
            final Map<ModelImageId, RawImageId> rawOverrides) {
        final List<ModelImageRelation> images = new ArrayList<>();
        final List<RawImageDetails> raws = new ArrayList<>();
        for (final ArtMeshTextureInputs inputs : artMeshes) {
            final ModelImageId imageId = inputs.inputs().get(0).modelImageId().orElseThrow();
            final RawImageId raw = rawOverrides.getOrDefault(imageId, imageToRaw(imageId));
            if (images.stream().noneMatch(r -> r.id().equals(imageId))) {
                images.add(new ModelImageRelation(
                        imageId,
                        entry(imageId),
                        List.of(raw),
                        Optional.of(raw),
                        java.util.Map.of(),
                        List.of(inputs.id())));
            }
            if (raws.stream().noneMatch(r -> r.id().equals(raw))) {
                raws.add(new RawImageDetails(
                        rawTexture(raw),
                        RawImageDetails.SourceKind.PSD,
                        List.of(),
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
            }
        }
        return new TextureRelationsSnapshot(
                TextureRelationsSnapshot.Availability.AVAILABLE,
                binding,
                generation,
                revision,
                raws,
                images,
                List.of(),
                artMeshes);
    }

    static TextureRelationsSnapshot duplicateModelImageRelations(
            final String binding, final long generation, final long revision, final RawImageId raw) {
        final TextureRelationsSnapshot base = singleRelation(binding, generation, revision, raw);
        final ModelImageRelation image = base.modelImages().get(0);
        return new TextureRelationsSnapshot(
                TextureRelationsSnapshot.Availability.AVAILABLE,
                binding,
                generation,
                revision,
                base.rawImages(),
                List.of(image, image),
                base.groups(),
                base.artMeshInputs());
    }

    static TextureRelationsSnapshot duplicateRawRelations(
            final String binding, final long generation, final long revision, final RawImageId raw) {
        final TextureRelationsSnapshot base = singleRelation(binding, generation, revision, raw);
        return new TextureRelationsSnapshot(
                TextureRelationsSnapshot.Availability.AVAILABLE,
                binding,
                generation,
                revision,
                List.of(base.rawImages().get(0), base.rawImages().get(0)),
                base.modelImages(),
                base.groups(),
                base.artMeshInputs());
    }

    static PsdReplaceResult applied(final RawImageId before, final RawImageId after, final String revision) {
        return new PsdReplaceResult(
                PsdReplaceResult.Status.APPLIED,
                "applied",
                before,
                Optional.of(after),
                Optional.of(new TestRevision(revision)),
                Optional.empty());
    }

    static RawImageId imageToRaw(final ModelImageId id) {
        return id.equals(IMAGE_B) ? RAW_B : RAW_A;
    }

    static RawTexture rawTexture(final RawImageId id) {
        return new RawTexture() {
            @Override
            public RawImageId id() {
                return id;
            }

            @Override
            public String name() {
                return id.value();
            }

            @Override
            public int width() {
                return 64;
            }

            @Override
            public int height() {
                return 64;
            }
        };
    }

    static ModelImageEntry entry(final ModelImageId id) {
        return new ModelImageEntry() {
            @Override
            public ModelImageId id() {
                return id;
            }

            @Override
            public String name() {
                return id.value();
            }

            @Override
            public int width() {
                return 64;
            }

            @Override
            public int height() {
                return 64;
            }
        };
    }

    record TestRevision(String value) implements PsdFileRevision {}

    static final class FakePsdEditFile implements PsdEditFile {
        final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean subscriptionClosed =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger openCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger observeCalls = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean subscribedBeforeOpen;
        volatile boolean observed;
        volatile String externalContent = "baseline";
        volatile Consumer<PsdFileRevision> saveListener = ignored -> {};
        volatile CompletionStage<PsdFileOperationResult> openCompletion;
        volatile RuntimeException openFailure;
        volatile RuntimeException observeFailure;
        volatile CountDownLatch observeEntered;
        volatile CountDownLatch observeRelease;
        volatile PsdFileOperationResult.Status openStatus = PsdFileOperationResult.Status.OPENED;

        volatile String applicationPath = "";

        @Override
        public CompletionStage<PsdFileOperationResult> openInApplication(final String application) {
            applicationPath = application;
            return openInDefaultApplication();
        }

        @Override
        public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
            subscribedBeforeOpen = observed && !subscriptionClosed.get();
            openCalls.incrementAndGet();
            if (openFailure != null) {
                throw openFailure;
            }
            if (openCompletion != null) {
                return openCompletion;
            }
            return CompletableFuture.completedFuture(new PsdFileOperationResult(openStatus, "test"));
        }

        @Override
        public Registration observeSaves(final Consumer<PsdFileRevision> listener) {
            observeCalls.incrementAndGet();
            final CountDownLatch entered = observeEntered;
            if (entered != null) {
                entered.countDown();
            }
            final CountDownLatch release = observeRelease;
            if (release != null) {
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("observe release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
            }
            if (observeFailure != null) {
                throw observeFailure;
            }
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

    static final class RecordingModelTextures implements ModelTextures {
        final java.util.Map<RawImageId, FakePsdEditFile> issued = new java.util.LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final Deque<CompletableFuture<PsdReplaceResult>> replaceCompletions = new ArrayDeque<>();
        volatile TextureRelationsSnapshot relations = TextureRelationsSnapshot.unavailable();
        final AtomicInteger relationReads = new AtomicInteger();
        volatile Runnable relationReadHook;
        volatile PsdExportResult.Status exportStatus = PsdExportResult.Status.EXPORTED;
        volatile RuntimeException exportFailure;
        volatile PsdReplaceResult.Status replaceStatus = PsdReplaceResult.Status.APPLIED;
        volatile CompletableFuture<PsdExportResult> exportCompletion;
        volatile CompletableFuture<PsdFileOperationResult> openCompletion;
        volatile RuntimeException openFailure;
        volatile PsdFileOperationResult.Status openStatus = PsdFileOperationResult.Status.OPENED;

        List<String> calls() {
            return List.copyOf(calls);
        }

        java.util.Map<RawImageId, FakePsdEditFile> issued() {
            return issued;
        }

        int relationReads() {
            return relationReads.get();
        }

        void onNextRelationsRead(final Runnable hook) {
            relationReadHook = hook;
        }

        @Override
        public List<RawTexture> rawImages() {
            return List.of();
        }

        @Override
        public List<ModelImageGroup> modelImageGroups() {
            return List.of();
        }

        @Override
        public List<AtlasTexture> textureAtlases() {
            return List.of();
        }

        @Override
        public TextureRelationsSnapshot relations() {
            throw new AssertionError("External editing must not query the full layer graph");
        }

        @Override
        public dev.turboism.sdk.cubism.model.TextureSourcesSnapshot sources(
                final dev.turboism.sdk.cubism.model.TextureSourceQuery query) {
            relationReads.incrementAndGet();
            final TextureRelationsSnapshot sampled = relations;
            final Runnable hook = relationReadHook;
            relationReadHook = null;
            if (hook != null) {
                hook.run();
            }
            if (!sampled.isAvailable()) {
                return dev.turboism.sdk.cubism.model.TextureSourcesSnapshot.unavailable();
            }
            final var meshes = sampled.artMeshInputs().stream()
                    .filter(mesh -> query.artMeshes().contains(mesh.id()))
                    .toList();
            final var imageIds = new java.util.HashSet<>(query.modelImages());
            for (final var mesh : meshes) {
                if (mesh.currentInputIndex().isEmpty()) {
                    continue;
                }
                final var current = mesh.inputs().get(mesh.currentInputIndex().getAsInt());
                if (current.kind() == TextureInputBinding.Kind.ATLAS) {
                    mesh.inputs().forEach(input -> input.modelImageId().ifPresent(imageIds::add));
                } else {
                    current.modelImageId().ifPresent(imageIds::add);
                }
            }
            final var images = sampled.modelImages().stream()
                    .filter(image -> imageIds.contains(image.id()))
                    .map(image -> new dev.turboism.sdk.cubism.model.TextureSourcesSnapshot.ModelImageSource(
                            image.id(), image.currentRawImageId()))
                    .toList();
            final var rawIds = new java.util.HashSet<RawImageId>();
            images.forEach(image -> image.currentRawImageId().ifPresent(rawIds::add));
            return new dev.turboism.sdk.cubism.model.TextureSourcesSnapshot(
                    dev.turboism.sdk.cubism.model.TextureSourcesSnapshot.Availability.AVAILABLE,
                    sampled.binding(),
                    sampled.generation(),
                    sampled.revision(),
                    sampled.rawImages().stream()
                            .filter(raw -> rawIds.contains(raw.id()))
                            .map(RawImageDetails::rawImage)
                            .toList(),
                    images,
                    meshes);
        }

        @Override
        public CompletionStage<PsdExportResult> exportRawImagePsd(final RawImageId source) {
            calls.add("export:" + source.value());
            if (exportFailure != null) {
                throw exportFailure;
            }
            if (exportCompletion != null) {
                return exportCompletion;
            }
            if (exportStatus != PsdExportResult.Status.EXPORTED) {
                return CompletableFuture.completedFuture(
                        new PsdExportResult(exportStatus, "test", source, Optional.empty(), Optional.empty()));
            }
            final FakePsdEditFile file = new FakePsdEditFile();
            file.openStatus = openStatus;
            file.openCompletion = openCompletion;
            file.openFailure = openFailure;
            issued.put(source, file);
            return CompletableFuture.completedFuture(new PsdExportResult(
                    PsdExportResult.Status.EXPORTED,
                    "test",
                    source,
                    Optional.of(file),
                    Optional.of(new TestRevision("baseline"))));
        }

        @Override
        public CompletionStage<PsdReplaceResult> replaceRawImagePsd(
                final RawImageId target, final PsdEditFile file, final PsdFileRevision revision) {
            calls.add("replace:" + target.value() + ":" + ((TestRevision) revision).value());
            final CompletableFuture<PsdReplaceResult> completion = replaceCompletions.pollFirst();
            if (completion != null) {
                return completion;
            }
            if (replaceStatus != PsdReplaceResult.Status.APPLIED) {
                return CompletableFuture.completedFuture(new PsdReplaceResult(
                        replaceStatus, "test", target, Optional.empty(), Optional.empty(), Optional.empty()));
            }
            return CompletableFuture.completedFuture(new PsdReplaceResult(
                    PsdReplaceResult.Status.APPLIED,
                    "test",
                    target,
                    Optional.of(target),
                    Optional.of(revision),
                    Optional.empty()));
        }

        @Override
        public void addModelImageGroup(final String name) {
            throw unsupported();
        }

        @Override
        public void removeModelImage(final ModelImageId id) {
            throw unsupported();
        }

        @Override
        public TextureAtlasId addTextureAtlas(final String name, final int widthPixels, final int heightPixels) {
            throw unsupported();
        }

        @Override
        public void removeTextureAtlas(final TextureAtlasId id) {
            throw unsupported();
        }

        @Override
        public void removeRawImage(final RawImageId id) {
            throw unsupported();
        }

        static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    static final class FixedCubismFacade implements CubismFacade {
        final RecordingModelTextures textures = new RecordingModelTextures();
        RuntimeException activeFailure;
        RuntimeException texturesFailure;
        RuntimeException identityFailure;

        RecordingModelTextures textures() {
            return textures;
        }

        void relations(final TextureRelationsSnapshot snapshot) {
            textures.relations = snapshot;
        }

        @Override
        public CubismRuntimeSnapshot runtime() {
            throw unsupported();
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            return Optional.empty();
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public dev.turboism.sdk.cubism.model.CubismModelAccess model() {
            if (activeFailure != null) {
                throw activeFailure;
            }
            return () -> new CubismModel() {
                @Override
                public ModelId id() {
                    if (identityFailure != null) {
                        throw identityFailure;
                    }
                    return new ModelId("model-1");
                }

                @Override
                public ModelTextures textures() {
                    if (texturesFailure != null) {
                        throw texturesFailure;
                    }
                    return textures;
                }

                @Override
                public dev.turboism.sdk.cubism.model.Parameters parameters() {
                    throw unsupported();
                }

                @Override
                public dev.turboism.sdk.cubism.model.Parts parts() {
                    throw unsupported();
                }

                @Override
                public dev.turboism.sdk.cubism.model.Drawables drawables() {
                    throw unsupported();
                }

                @Override
                public dev.turboism.sdk.cubism.model.Deformers deformers() {
                    throw unsupported();
                }

                @Override
                public dev.turboism.sdk.cubism.model.Glues glues() {
                    throw unsupported();
                }

                @Override
                public void update() {}
            };
        }

        static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    static final class SettingsUiHost extends RecordingUiHost {
        final List<dev.turboism.sdk.ui.settings.SettingsContribution> settings = new ArrayList<>();

        @Override
        public Registration contributeSettings(final dev.turboism.sdk.ui.settings.SettingsContribution contribution) {
            settings.add(contribution);
            return () -> settings.remove(contribution);
        }
    }

    static final class RecordingPluginContext extends FakePluginContext {
        final DisposableScope disposableScope = new DisposableScope();
        final RecordingActionRegistry actions = new RecordingActionRegistry();
        final RecordingContextMenuRegistry contextMenu = new RecordingContextMenuRegistry();
        final SettingsUiHost uiHost = new SettingsUiHost();
        final PluginLogger logger;
        final FixedCubismFacade cubism = new FixedCubismFacade();
        final RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();

        RecordingPluginContext(final PluginLogger logger) {
            super(FakePluginContext.builder().localization(passThroughLocalization()));
            this.logger = logger;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public FixedCubismFacade cubism() {
            return cubism;
        }

        @Override
        public RecordingActionRegistry actions() {
            return actions;
        }

        @Override
        public RecordingContextMenuRegistry contextMenu() {
            return contextMenu;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }

        @Override
        public SettingsUiHost uiHost() {
            return uiHost;
        }

        @Override
        public RecordingTaskScheduler tasks() {
            return taskScheduler;
        }

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return dev.turboism.sdk.plugin.PluginServices.builder()
                    .supply(dev.turboism.sdk.ui.context.ContextMenuRegistry.class, () -> this.contextMenu())
                    .supply(dev.turboism.sdk.ui.UiHostCapabilityService.class, () -> this.uiHost())
                    .fallback(dev.turboism.sdk.plugin.PluginServices.of(this))
                    .build();
        }
    }

    static final class RecordingTaskScheduler implements PluginTaskScheduler {
        final List<PluginTaskRequest> submitted = new ArrayList<>();
        final List<RecordingTaskHandle> submitHandles = new ArrayList<>();
        private FixedDelayTaskRequest refreshRequest;
        RecordingTaskHandle refreshHandle;
        boolean autoRunSubmissions = true;
        boolean completeAutoRun = true;
        boolean rejectNextSubmit;
        boolean rejectNextSchedule;
        int scheduleCalls;

        void autoRunSubmissions(final boolean value) {
            autoRunSubmissions = value;
        }

        void deferAutoRunCompletion() {
            completeAutoRun = false;
        }

        void rejectNextSubmit() {
            rejectNextSubmit = true;
        }

        void rejectNextSchedule() {
            rejectNextSchedule = true;
        }

        int submitCount() {
            return submitted.size();
        }

        int scheduleCount() {
            return scheduleCalls;
        }

        RecordingTaskHandle lastSubmitHandle() {
            return submitHandles.get(submitHandles.size() - 1);
        }

        boolean refreshClosed() {
            return refreshHandle != null && refreshHandle.closed();
        }

        void runRefreshTick() {
            if (refreshRequest == null) {
                throw new AssertionError("refresh was not scheduled");
            }
            try {
                refreshRequest.action().run(neverCanceled());
            } catch (Exception failure) {
                throw new AssertionError("refresh action failed", failure);
            }
        }

        void cancelRefreshExternally() {
            if (refreshHandle == null) {
                throw new AssertionError("refresh was not scheduled");
            }
            refreshHandle.complete(outcome(refreshHandle.id(), TaskOutcomeStatus.CANCELED));
        }

        @Override
        public TaskSubmission submit(final PluginTaskRequest request) {
            submitted.add(request);
            final RecordingTaskHandle handle = new RecordingTaskHandle(request.id());
            submitHandles.add(handle);
            if (rejectNextSubmit) {
                rejectNextSubmit = false;
                handle.complete(rejectedOutcome(request.id()));
                return new TaskSubmission(
                        TaskSubmissionStatus.REJECTED, handle, Optional.of(TaskRejectionReason.BACKPRESSURE));
            }
            if (autoRunSubmissions) {
                try {
                    request.action().run(neverCanceled());
                    if (completeAutoRun) {
                        handle.complete(outcome(request.id(), TaskOutcomeStatus.SUCCEEDED));
                    }
                } catch (Exception failure) {
                    handle.complete(failedOutcome(request.id(), failure));
                }
            }
            return new TaskSubmission(TaskSubmissionStatus.ACCEPTED, handle, Optional.empty());
        }

        @Override
        public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
            scheduleCalls++;
            if (rejectNextSchedule) {
                rejectNextSchedule = false;
                final RecordingTaskHandle rejected = new RecordingTaskHandle(request.id());
                rejected.complete(rejectedOutcome(request.id()));
                return new TaskSubmission(
                        TaskSubmissionStatus.REJECTED, rejected, Optional.of(TaskRejectionReason.BACKPRESSURE));
            }
            refreshRequest = request;
            refreshHandle = new RecordingTaskHandle(request.id());
            return new TaskSubmission(TaskSubmissionStatus.ACCEPTED, refreshHandle, Optional.empty());
        }
    }

    static final class RecordingTaskHandle implements TaskHandle {
        final dev.turboism.sdk.task.TaskId id;
        final CompletableFuture<TaskOutcome> completion = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean();

        private RecordingTaskHandle(final dev.turboism.sdk.task.TaskId id) {
            this.id = id;
        }

        boolean closed() {
            return closed.get();
        }

        void complete(final TaskOutcome value) {
            completion.complete(value);
        }

        @Override
        public dev.turboism.sdk.task.TaskId id() {
            return id;
        }

        @Override
        public TaskProgress progress() {
            return new TaskProgress(0, Optional.empty());
        }

        @Override
        public boolean cancel() {
            return completion.complete(outcome(id, TaskOutcomeStatus.CANCELED));
        }

        @Override
        public CompletionStage<TaskOutcome> completion() {
            return completion;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    static CancellationToken neverCanceled() {
        return new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

            @Override
            public void checkCanceled() {}
        };
    }

    static TaskOutcome outcome(final dev.turboism.sdk.task.TaskId id, final TaskOutcomeStatus status) {
        if (status == TaskOutcomeStatus.SUCCEEDED) {
            final TaskRunOutcome run = new TaskRunOutcome(1, TaskRunOutcomeStatus.SUCCEEDED, Optional.empty());
            return new TaskOutcome(id, status, 1, Optional.of(run), Optional.empty());
        }
        if (status == TaskOutcomeStatus.CANCELED) {
            return new TaskOutcome(id, status, 0, Optional.empty(), Optional.empty());
        }
        return failedOutcome(id, new IllegalStateException(status.name()));
    }

    static TaskOutcome failedOutcome(final dev.turboism.sdk.task.TaskId id, final String message) {
        return failedOutcome(id, new IllegalStateException(message));
    }

    static TaskOutcome failedOutcome(final dev.turboism.sdk.task.TaskId id, final Throwable failure) {
        final TaskFailure detail =
                new TaskFailure("TEST_FAILURE", failure.getClass().getSimpleName());
        final TaskRunOutcome run = new TaskRunOutcome(1, TaskRunOutcomeStatus.FAILED, Optional.of(detail));
        return new TaskOutcome(id, TaskOutcomeStatus.FAILED, 1, Optional.of(run), Optional.of(detail));
    }

    static TaskOutcome rejectedOutcome(final dev.turboism.sdk.task.TaskId id) {
        final TaskFailure detail = new TaskFailure("TEST_REJECTED", "rejected");
        return new TaskOutcome(id, TaskOutcomeStatus.REJECTED, 0, Optional.empty(), Optional.of(detail));
    }

    static final class TestPluginLogger extends RecordingPluginLogger {}
}
