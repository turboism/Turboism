package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageWriteResult;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

final class PaletteLabelStyleTestFixtures {

    static final class RecordingPluginContext implements PluginContext {
        final DisposableScope disposableScope = new DisposableScope();
        final RecordingActionRegistry actions = new RecordingActionRegistry();
        final RecordingContextMenuRegistry contextMenu = new RecordingContextMenuRegistry();
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingPluginStorage storage = new RecordingPluginStorage();
        final FixedCubismFacade cubism = new FixedCubismFacade();
        final TestPluginLogger logger = new TestPluginLogger();

        @Override
        public PluginDescriptor descriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginLocalization localization() {
            return new PluginLocalization() {
                @Override
                public Locale locale() {
                    return Locale.ROOT;
                }

                @Override
                public String text(final String key) {
                    return key;
                }

                @Override
                public String format(final String key, final Object... arguments) {
                    return key;
                }

                @Override
                public boolean contains(final String key) {
                    return true;
                }
            };
        }

        @Override
        public FixedCubismFacade cubism() {
            return cubism;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RecordingActionRegistry actions() {
            return actions;
        }

        @Override
        public MenuRegistry menus() {
            throw new UnsupportedOperationException();
        }

        public RecordingContextMenuRegistry contextMenu() {
            return contextMenu;
        }

        @Override
        public UiScheduler uiScheduler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DiagnosticReport diagnostics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }

        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public RecordingPluginStorage storage() {
            return storage;
        }

        FakeModel model() {
            return cubism.model;
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

    static final class FixedCubismFacade implements CubismFacade {
        final FakeModel model = new FakeModel();
        private Optional<ProjectSnapshot> project =
                Optional.of(new ProjectSnapshot("project-1", "Project 1", Optional.empty(), List.of(), List.of()));
        boolean modelPresent = true;

        void noProject() {
            project = Optional.empty();
        }

        void noModel() {
            modelPresent = false;
        }

        @Override
        public CubismRuntimeSnapshot runtime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return project;
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public CubismModelAccess model() {
            return () -> {
                if (!modelPresent) {
                    throw new IllegalStateException("no model active");
                }
                return model;
            };
        }
    }

    static final class RecordingActionRegistry implements ActionRegistry {
        final List<ActionRegistry.Action> actions = new ArrayList<>();

        List<String> ids() {
            return actions.stream().map(ActionRegistry.Action::id).toList();
        }

        @Override
        public Registration register(final String id, final ActionRegistry.Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }

        void execute(final String id) {
            execute(id, new ActionRegistry.ActionContext() {});
        }

        void execute(final String id, final ContextMenuSelection selection) {
            execute(id, new ActionRegistry.ActionContext() {
                @Override
                public Optional<ContextMenuSelection> contextMenuSelection() {
                    return Optional.of(selection);
                }
            });
        }

        void execute(final String id, final ActionRegistry.ActionContext actionContext) {
            actions.stream()
                    .filter(action -> action.id().equals(id))
                    .findFirst()
                    .orElseThrow()
                    .handler()
                    .accept(actionContext);
        }
    }

    static final class RecordingContextMenuRegistry implements ContextMenuRegistry {
        final List<ContextMenuContribution> contributions = new ArrayList<>();

        List<ContextMenuContribution> contributions() {
            return List.copyOf(contributions);
        }

        @Override
        public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    /** In-memory PluginStorage recording every read/write operation for async-aware assertions. */
    static final class RecordingPluginStorage implements PluginStorage {
        final Map<String, String> files = new HashMap<>();
        final List<CompletableFuture<Void>> operations = new ArrayList<>();

        synchronized void seed(final String relativePath, final String content) {
            files.put(relativePath, content);
        }

        synchronized String content(final String relativePath) {
            return files.get(relativePath);
        }

        /** Waits until at least {@code count} storage operations have been recorded. */
        void awaitOperations(final int count) {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                synchronized (this) {
                    if (operations.size() >= count) {
                        return;
                    }
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while awaiting storage operations");
                }
            }
            synchronized (this) {
                throw new AssertionError("storage operations did not reach " + count + "; saw " + operations.size());
            }
        }

        void record() {
            final CompletableFuture<Void> operation = new CompletableFuture<>();
            operation.complete(null);
            operations.add(operation);
        }

        @Override
        public synchronized CompletionStage<StorageReadResult<String>> readUtf8(
                final StoragePath path, final int maxBytes) {
            record();
            return CompletableFuture.completedFuture(new StorageReadResult<>(
                    Optional.ofNullable(files.get(path.relativePath())), Optional.empty(), false));
        }

        @Override
        public synchronized CompletionStage<StorageWriteResult> writeUtf8Atomic(
                final StoragePath path, final String content) {
            record();
            files.put(path.relativePath(), content);
            return CompletableFuture.completedFuture(new StorageWriteResult(true, Optional.empty()));
        }

        @Override
        public CompletionStage<StorageReadResult<byte[]>> readBytes(StoragePath path, int maxBytes) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public CompletionStage<StorageWriteResult> writeBytesAtomic(StoragePath path, byte[] content) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public CompletionStage<StorageListResult> list(StoragePath directory, int maxEntries) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public CompletionStage<StorageMutationResult> copy(
                StoragePath source, StoragePath target, boolean replaceExisting) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public CompletionStage<StorageMutationResult> moveAtomic(
                StoragePath source, StoragePath target, boolean replaceExisting) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public CompletionStage<StorageMutationResult> delete(StoragePath path, boolean recursive) {
            throw new UnsupportedOperationException("not used");
        }
    }

    static final class RecordingUiHost implements UiHostCapabilityService {
        final List<ColorPickerCall> colorPickers = new ArrayList<>();
        final List<dev.turboism.sdk.ui.ColorPickerResultListener> colorPickerListeners = new ArrayList<>();

        List<ColorPickerCall> colorPickers() {
            return List.copyOf(colorPickers);
        }

        void acceptColorPicker(final int index, final String hex) {
            colorPickerListeners.get(index).onResult(true, hex);
        }

        void cancelColorPicker(final int index) {
            colorPickerListeners.get(index).onResult(false, null);
        }

        @Override
        public void openColorPicker(
                final String id,
                final String title,
                final String initialColorHex,
                final dev.turboism.sdk.ui.ColorPickerResultListener listener) {
            colorPickers.add(new ColorPickerCall(id, title, initialColorHex));
            colorPickerListeners.add(listener);
        }

        record ColorPickerCall(String id, String title, String initial) {}

        @Override
        public Registration contributeOverlay(dev.turboism.sdk.ui.OverlayContribution contribution) {
            throw unsupported();
        }

        @Override
        public Registration contributeBoundingBoxOverlayButton(
                dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution) {
            throw unsupported();
        }

        @Override
        public dev.turboism.sdk.ui.context.ContextSourceSnapshot contextSource() {
            throw unsupported();
        }

        @Override
        public dev.turboism.sdk.ui.ViewportSnapshot viewport() {
            throw unsupported();
        }

        @Override
        public Registration openDialog(dev.turboism.sdk.ui.DialogRequest request) {
            throw unsupported();
        }

        @Override
        public boolean confirmDialog(dev.turboism.sdk.ui.DialogRequest request) {
            throw unsupported();
        }

        @Override
        public Registration contributeEmbeddedPanel(dev.turboism.sdk.ui.EmbeddedPanelContribution contribution) {
            throw unsupported();
        }

        @Override
        public Optional<String> requestFile(dev.turboism.sdk.ui.FileChooserRequest request) {
            throw unsupported();
        }

        @Override
        public Registration notifyStatus(dev.turboism.sdk.ui.StatusNotification notification) {
            throw unsupported();
        }

        @Override
        public Registration contributeContextMenu(ContextMenuContribution contribution) {
            throw unsupported();
        }

        @Override
        public Registration contributeMainToolbar(
                dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.MainToolbarContribution contribution) {
            throw unsupported();
        }

        @Override
        public Registration contributePaletteToolbar(
                dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw unsupported();
        }

        static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    static final class TestPluginLogger implements PluginLogger {
        @Override
        public void debug(String message) {}

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void error(String message) {}

        @Override
        public void error(String message, Throwable throwable) {}
    }

    // ---------------------------------------------------------- model fakes

    static final class FakeModel implements CubismModel {
        final List<FakeParameter> parameters = new ArrayList<>();
        final List<FakeParameterGroup> groups = new ArrayList<>();
        final List<FakePart> parts = new ArrayList<>();
        final List<FakeDeformer> deformers = new ArrayList<>();
        final List<FakeDrawable> drawables = new ArrayList<>();

        FakeModel() {
            this(true);
        }

        FakeModel(final boolean prePopulate) {
            if (prePopulate) {
                parameters.add(new FakeParameter("p1"));
                groups.add(new FakeParameterGroup("folder1"));
                parts.add(new FakePart("part1"));
                deformers.add(new FakeDeformer("warp1"));
                drawables.add(new FakeDrawable("mesh1"));
            }
        }

        static FakeModel empty() {
            return new FakeModel(false);
        }

        static FakeModel with(final String deformerId) {
            final FakeModel model = empty();
            model.addDeformer(deformerId);
            return model;
        }

        static FakeModel withParameter(final String id) {
            final FakeModel model = empty();
            model.addParameter(id);
            return model;
        }

        static FakeModel withPart(final String id) {
            final FakeModel model = empty();
            model.addPart(id);
            return model;
        }

        static FakeModel withGroupFolder(final String id) {
            final FakeModel model = empty();
            model.addGroupFolder(id);
            return model;
        }

        static FakeModel withArtMesh(final String id) {
            final FakeModel model = empty();
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
            return find(parameters, "parameter:" + id);
        }

        FakeParameterGroup group(final String id) {
            return find(groups, "group:" + id);
        }

        FakePart part(final String id) {
            return find(parts, "part:" + id);
        }

        FakeDeformer deformer(final String id) {
            return find(deformers, "deformer:" + id);
        }

        FakeDrawable drawable(final String id) {
            return find(drawables, "drawable:" + id);
        }

        private static <T> T find(final List<T> values, final String marker) {
            return values.stream()
                    .filter(value -> String.valueOf(value).contains(marker))
                    .findFirst()
                    .orElseThrow(() -> new java.util.NoSuchElementException(marker));
        }

        @Override
        public ModelId id() {
            return new ModelId("model-1");
        }

        @Override
        public Parameters parameters() {
            return new Parameters() {
                @Override
                public List<Parameter> all() {
                    return List.copyOf(parameters);
                }

                @Override
                public Parameter find(final ParameterId id) {
                    return parameters.stream()
                            .filter(p -> p.id().equals(id))
                            .findFirst()
                            .orElse(null);
                }
            };
        }

        @Override
        public ParameterGroups parameterGroups() {
            return new ParameterGroups() {
                @Override
                public List<ParameterGroup> all() {
                    return List.copyOf(groups);
                }

                @Override
                public ParameterGroup root() {
                    return groups.isEmpty() ? null : groups.get(0);
                }

                @Override
                public ParameterGroup find(final ParameterGroupId id) {
                    return groups.stream()
                            .filter(g -> g.id().equals(id))
                            .findFirst()
                            .orElse(null);
                }
            };
        }

        @Override
        public Parts parts() {
            return new Parts() {
                @Override
                public List<Part> all() {
                    return List.copyOf(parts);
                }

                @Override
                public Part find(final PartId id) {
                    return parts.stream()
                            .filter(p -> p.id().equals(id))
                            .findFirst()
                            .orElse(null);
                }
            };
        }

        @Override
        public Deformers deformers() {
            return new Deformers() {
                @Override
                public List<Deformer> all() {
                    return List.copyOf(deformers);
                }

                @Override
                public Deformer find(final DeformerId id) {
                    return deformers.stream()
                            .filter(d -> d.id().equals(id))
                            .findFirst()
                            .orElse(null);
                }
            };
        }

        @Override
        public Drawables drawables() {
            return new Drawables() {
                @Override
                public List<Drawable> all() {
                    return List.copyOf(drawables);
                }

                @Override
                public Drawable find(final ArtMeshId id) {
                    return drawables.stream()
                            .filter(d -> d.id().equals(id))
                            .findFirst()
                            .orElse(null);
                }
            };
        }

        @Override
        public Glues glues() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update() {
            throw new UnsupportedOperationException("not used");
        }
    }

    static final class FakeParameter implements Parameter {
        final FakePaletteEntry entry = new FakePaletteEntry();
        final String id;

        FakeParameter(final String id) {
            this.id = id;
        }

        @Override
        public ParameterId id() {
            return new ParameterId(id);
        }

        @Override
        public dev.turboism.sdk.ui.appearance.model.ParameterAppearance ui() {
            return () -> Optional.of(entry);
        }

        @Override
        public float getValue() {
            return 0.0F;
        }

        @Override
        public float getMinimumValue() {
            return -1.0F;
        }

        @Override
        public float getMaximumValue() {
            return 1.0F;
        }

        @Override
        public float getDefaultValue() {
            return 0.0F;
        }

        @Override
        public void setValue(final float value) {}

        @Override
        public String toString() {
            return "parameter:" + id;
        }
    }

    static final class FakeParameterGroup implements ParameterGroup {
        final FakePaletteEntry entry = new FakePaletteEntry();
        final String id;

        FakeParameterGroup(final String id) {
            this.id = id;
        }

        @Override
        public ParameterGroupId id() {
            return new ParameterGroupId(id);
        }

        @Override
        public dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance ui() {
            return new dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance() {
                @Override
                public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> parameterPaletteEntry() {
                    return Optional.of(entry);
                }

                @Override
                public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() {
                    return Optional.empty();
                }

                @Override
                public void setNativeLabelColor(dev.turboism.sdk.ui.appearance.NativeLabelColor color) {}
            };
        }

        @Override
        public Optional<String> name() {
            return Optional.of(id);
        }

        @Override
        public Optional<ParameterGroupId> parentId() {
            return Optional.empty();
        }

        @Override
        public List<ParameterGroupId> childGroupIds() {
            return List.of();
        }

        @Override
        public List<ParameterId> parameterIds() {
            return List.of();
        }

        @Override
        public String toString() {
            return "group:" + id;
        }
    }

    static final class FakePart implements Part {
        final FakePaletteEntry entry = new FakePaletteEntry();
        final String id;

        FakePart(final String id) {
            this.id = id;
        }

        @Override
        public PartId id() {
            return new PartId(id);
        }

        @Override
        public dev.turboism.sdk.ui.appearance.model.PartAppearance ui() {
            return new dev.turboism.sdk.ui.appearance.model.PartAppearance() {
                @Override
                public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> partPaletteEntry() {
                    return Optional.of(entry);
                }

                @Override
                public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() {
                    return Optional.empty();
                }

                @Override
                public void setNativeLabelColor(dev.turboism.sdk.ui.appearance.NativeLabelColor color) {}
            };
        }

        @Override
        public void setName(final String name) {}

        @Override
        public float getOpacity() {
            return 1.0F;
        }

        @Override
        public int parentIndex() {
            return -1;
        }

        @Override
        public void setOpacity(final float opacity) {}

        @Override
        public String toString() {
            return "part:" + id;
        }
    }

    static final class FakeDeformer implements Deformer {
        final FakePaletteEntry partEntry = new FakePaletteEntry();
        final FakePaletteEntry deformerEntry = new FakePaletteEntry();
        final List<dev.turboism.sdk.ui.appearance.NativeLabelColor> nativeLabelColors = new ArrayList<>();
        final String id;

        FakeDeformer(final String id) {
            this.id = id;
        }

        @Override
        public DeformerId id() {
            return new DeformerId(id);
        }

        @Override
        public dev.turboism.sdk.ui.appearance.model.DeformerAppearance ui() {
            return new dev.turboism.sdk.ui.appearance.model.DeformerAppearance() {
                @Override
                public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> partPaletteEntry() {
                    return Optional.of(partEntry);
                }

                @Override
                public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> deformerPaletteEntry() {
                    return Optional.of(deformerEntry);
                }

                @Override
                public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() {
                    return Optional.empty();
                }

                @Override
                public void setNativeLabelColor(dev.turboism.sdk.ui.appearance.NativeLabelColor color) {
                    nativeLabelColors.add(color);
                }
            };
        }

        @Override
        public int parentDeformerIndex() {
            return -1;
        }

        @Override
        public IntSequence parameters() {
            return emptyInts();
        }

        @Override
        public String toString() {
            return "deformer:" + id;
        }
    }

    static final class FakeDrawable implements Drawable {
        final FakePaletteEntry partEntry = new FakePaletteEntry();
        final FakePaletteEntry deformerEntry = new FakePaletteEntry();
        final List<dev.turboism.sdk.ui.appearance.NativeLabelColor> nativeLabelColors = new ArrayList<>();
        final String id;

        FakeDrawable(final String id) {
            this.id = id;
        }

        @Override
        public ArtMeshId id() {
            return new ArtMeshId(id);
        }

        @Override
        public dev.turboism.sdk.ui.appearance.model.DrawableAppearance ui() {
            return new dev.turboism.sdk.ui.appearance.model.DrawableAppearance() {
                @Override
                public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> partPaletteEntry() {
                    return Optional.of(partEntry);
                }

                @Override
                public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> deformerPaletteEntry() {
                    return Optional.of(deformerEntry);
                }

                @Override
                public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() {
                    return Optional.empty();
                }

                @Override
                public void setNativeLabelColor(dev.turboism.sdk.ui.appearance.NativeLabelColor color) {
                    nativeLabelColors.add(color);
                }
            };
        }

        @Override
        public byte constantFlag() {
            return 0;
        }

        @Override
        public byte dynamicFlag() {
            return 0;
        }

        @Override
        public BlendMode blendMode() {
            return BlendMode.NORMAL;
        }

        @Override
        public int textureIndex() {
            return 0;
        }

        @Override
        public int drawOrder() {
            return 0;
        }

        @Override
        public int renderOrder() {
            return 0;
        }

        @Override
        public float getOpacity() {
            return 1.0F;
        }

        @Override
        public IntSequence masks() {
            return emptyInts();
        }

        @Override
        public FloatSequence vertexPositions() {
            return emptyFloats();
        }

        @Override
        public FloatSequence vertexUvs() {
            return emptyFloats();
        }

        @Override
        public IntSequence indices() {
            return emptyInts();
        }

        @Override
        public Color multiplyColor() {
            return new Color(1.0F, 1.0F, 1.0F, 1.0F);
        }

        @Override
        public Color screenColor() {
            return new Color(0.0F, 0.0F, 0.0F, 0.0F);
        }

        @Override
        public int parentPartIndex() {
            return -1;
        }

        @Override
        public int parentDeformerIndex() {
            return -1;
        }

        @Override
        public IntSequence parameters() {
            return emptyInts();
        }

        @Override
        public String toString() {
            return "drawable:" + id;
        }
    }

    static final class FakePaletteEntry implements dev.turboism.sdk.ui.appearance.PaletteEntry {
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        List<String> events() {
            return List.copyOf(events);
        }

        List<String> textEvents() {
            return events.stream().filter(e -> e.startsWith("text:")).toList();
        }

        List<String> backgroundEvents() {
            return events.stream().filter(e -> e.startsWith("background:")).toList();
        }

        void awaitEvents(final int minCount) {
            final long deadline =
                    System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (events.size() < minCount && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }

        void awaitTextEvents(final int minCount) {
            final long deadline =
                    System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (textEvents().size() < minCount && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }

        @Override
        public Registration overrideTextColor(dev.turboism.sdk.ui.appearance.UiColor color) {
            return override("text", color);
        }

        @Override
        public Registration overrideBackgroundColor(dev.turboism.sdk.ui.appearance.UiColor color) {
            return override("background", color);
        }

        private Registration override(final String property, final dev.turboism.sdk.ui.appearance.UiColor color) {
            events.add(property + ":" + LabelStylePresets.toHex(color));
            return () -> events.add(property + ":closed");
        }

        @Override
        public Registration overrideFontSize(final float points) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Registration overrideBold(final boolean bold) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Registration overrideItalic(final boolean italic) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public dev.turboism.sdk.ui.appearance.PaletteEntryState resolved() {
            return dev.turboism.sdk.ui.appearance.PaletteEntryState.empty();
        }

        @Override
        public Optional<dev.turboism.sdk.ui.appearance.PaletteEntryState> actual() {
            return Optional.empty();
        }
    }

    static IntSequence emptyInts() {
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

    static FloatSequence emptyFloats() {
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
}
