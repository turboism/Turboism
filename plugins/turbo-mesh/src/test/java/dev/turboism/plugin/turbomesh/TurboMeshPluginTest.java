package dev.turboism.plugin.turbomesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.turbomesh.algorithm.AdaptiveAlgorithm;
import dev.turboism.plugin.turbomesh.algorithm.RegionAlgorithm;
import dev.turboism.plugin.turbomesh.algorithm.TurboMeshAlgorithms;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.TurboismEvent;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Plugin-shell contract: action + menu registration and the apply flow end to end. */
class TurboMeshPluginTest {

    @Test
    void enableRegistersActionAndMenuContributions() {
        final RecordingContext context = new RecordingContext();
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();

        assertEquals(
                List.of(TurboMeshPlugin.GENERATE_ACTION_ID, TurboMeshPlugin.APPLY_ACTION_ID),
                context.actions.registered.stream()
                        .map(ActionRegistry.Action::id)
                        .toList());
        assertEquals(6, context.contextMenu.contributions.size());
        for (final ContextMenuRegistry.ContextMenuContribution c : context.contextMenu.contributions) {
            assertTrue(c.actionId().equals(TurboMeshPlugin.GENERATE_ACTION_ID)
                    || c.actionId().equals(TurboMeshPlugin.APPLY_ACTION_ID));
            assertTrue(c.objectKinds().contains(ContextMenuRegistry.ObjectKind.ART_MESH));
        }
    }

    @Test
    void generateWithSelectionIncrementallyRefinesGeometryImmediately() {
        final RecordingContext context = new RecordingContext();
        final StubDrawable drawable = new StubDrawable("mesh-1");
        context.modelDrawables.add(drawable);
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions.execute(TurboMeshPlugin.GENERATE_ACTION_ID, selection("mesh-1"));

        assertNotNull(drawable.applied, "replaceGeometry must be invoked");
        assertTrue(drawable.applied.uvs().size() > 4, "must add refinement points");
        assertEquals(0, context.uiHost.chooseCalls, "incremental generate must never pop up dialogs");
        assertEquals(0, context.uiHost.confirmCalls, "incremental generate must never pop up dialogs");
        assertTrue(context.uiHost.notifications.stream()
                .anyMatch(n -> "INFO".equals(n.severity()) && n.message().contains("turbo-mesh.status.refined")));
    }

    @Test
    void applyWithoutSelectionWarns() {
        final RecordingContext context = new RecordingContext();
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();

        context.actions.execute(TurboMeshPlugin.APPLY_ACTION_ID, new ActionRegistry.ActionContext() {});

        assertEquals(1, context.uiHost.notifications.size());
        assertEquals("WARNING", context.uiHost.notifications.get(0).severity());
    }

    @Test
    void applyWithSelectionReplacesGeometry() {
        final RecordingContext context = new RecordingContext();
        final StubDrawable drawable = new StubDrawable("mesh-1");
        context.modelDrawables.add(drawable);
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();

        final ActionRegistry.ActionContext actionContext = new ActionRegistry.ActionContext() {
            @Override
            public Optional<ContextMenuSelection> contextMenuSelection() {
                return Optional.of(new ContextMenuSelection(
                        1L,
                        "doc-1",
                        ContextMenuRegistry.Location.PART_TAB,
                        List.of(new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"))));
            }
        };
        context.actions.execute(TurboMeshPlugin.APPLY_ACTION_ID, actionContext);

        assertNotNull(drawable.applied, "replaceGeometry must be invoked");
        assertEquals(drawable.applied.positions().size(), drawable.applied.uvs().size());
        assertEquals(0, drawable.applied.triangleIndices().size() % 3);
        assertTrue(context.uiHost.notifications.stream().anyMatch(n -> "INFO".equals(n.severity())));
    }

    @Test
    void modeDialogCancellationWritesNothing() {
        final RecordingContext context = new RecordingContext();
        context.uiHost.chooseResult = Optional.empty();
        final StubDrawable drawable = new StubDrawable("mesh-1");
        context.modelDrawables.add(drawable);
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions.execute(TurboMeshPlugin.APPLY_ACTION_ID, selection("mesh-1"));

        assertNull(drawable.applied, "cancelling the mode dialog must not write back");
        assertEquals(0, context.uiHost.confirmCalls);
        assertTrue(
                context.uiHost.notifications.stream().anyMatch(n -> n.severity().equals("INFO")));
    }

    @Test
    void confirmDialogCancellationWritesNothing() {
        final RecordingContext context = new RecordingContext();
        context.uiHost.confirmResult = false;
        final StubDrawable drawable = new StubDrawable("mesh-1");
        context.modelDrawables.add(drawable);
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions.execute(TurboMeshPlugin.APPLY_ACTION_ID, selection("mesh-1"));

        assertNull(drawable.applied, "cancelling the confirm dialog must not write back");
        assertEquals(1, context.uiHost.confirmCalls);
        assertEquals(2, context.uiHost.chooseCalls);
        assertTrue(
                context.uiHost.notifications.stream().anyMatch(n -> n.severity().equals("INFO")));
    }

    @Test
    void keyformMeshIsBlockedWithoutDialogService() {
        final RecordingContext context = new RecordingContext();
        context.uiHost.dialogAvailable = false;
        final StubDrawable drawable = new KeyformStubDrawable("mesh-key");
        context.modelDrawables.add(drawable);
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();
        context.actions.execute(TurboMeshPlugin.APPLY_ACTION_ID, selection("mesh-key"));

        assertNull(drawable.applied, "keyform mesh must fail closed without a preview surface");
        assertTrue(context.uiHost.notifications.stream().anyMatch(n -> "WARNING".equals(n.severity())));
    }

    @Test
    void confirmNeverBypassesDialogsForAutomation() {
        final RecordingContext context = new RecordingContext();
        final StubDrawable drawable = new StubDrawable("mesh-1");
        context.modelDrawables.add(drawable);
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();
        System.setProperty("turboMesh.confirm", "never");
        try {
            context.actions.execute(TurboMeshPlugin.APPLY_ACTION_ID, selection("mesh-1"));
        } finally {
            System.clearProperty("turboMesh.confirm");
        }
        assertEquals(0, context.uiHost.chooseCalls, "automation mode must not open the mode dialog");
        assertEquals(0, context.uiHost.confirmCalls, "automation mode must not open the confirm dialog");
        assertNotNull(drawable.applied);
    }

    @Test
    void enableRegistersBothAlgorithmsAndReleasesThemOnDisable() {
        final RecordingContext context = new RecordingContext();
        context.autoMeshAlgorithms = new RecordingAlgorithmRegistry();
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();

        final RecordingAlgorithmRegistry registry = (RecordingAlgorithmRegistry) context.autoMeshAlgorithms;
        assertEquals(List.of(RegionAlgorithm.ID, AdaptiveAlgorithm.ID), registry.registeredIds());
        assertEquals(6, context.contextMenu.contributions.size(), "context menus keep working");

        plugin.disable();
        assertEquals(List.of(), registry.registeredIds(), "disable releases the algorithms");

        plugin.shutdown();
        assertEquals(List.of(), registry.registeredIds(), "shutdown stays idempotent");
    }

    @Test
    void enableWithoutAutoMeshRegistryKeepsTheContextMenuActions() {
        final RecordingContext context = new RecordingContext();
        final TurboMeshPlugin plugin = new TurboMeshPlugin();
        plugin.init(context);
        plugin.enable();

        assertEquals(
                List.of(TurboMeshPlugin.GENERATE_ACTION_ID, TurboMeshPlugin.APPLY_ACTION_ID),
                context.actions.registered.stream()
                        .map(ActionRegistry.Action::id)
                        .toList());
        assertEquals(1, context.warnings.size(), "the missing registry is reported once");
        assertEquals(TurboMeshAlgorithms.UNAVAILABLE_MESSAGE, context.warnings.get(0));
    }

    private static ActionRegistry.ActionContext selection(final String drawableId) {
        return new ActionRegistry.ActionContext() {
            @Override
            public Optional<ContextMenuSelection> contextMenuSelection() {
                return Optional.of(new ContextMenuSelection(
                        1L,
                        "doc-1",
                        ContextMenuRegistry.Location.PART_TAB,
                        List.of(new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, drawableId))));
            }
        };
    }

    // ---------- test doubles ----------

    /** Stub whose ArtMesh reports one parameter keyform binding (re-topology risk marker). */
    private static final class KeyformStubDrawable extends StubDrawable {
        KeyformStubDrawable(final String id) {
            super(id);
        }

        @Override
        public List<ParameterBinding> getParameterBindings() {
            return List.of(new ParameterBinding(
                    ParameterBindingTarget.artMesh(id()),
                    new ParameterId("ParamAngleX"),
                    ParameterBindingFamily.KEYFORM_GRID,
                    List.of()));
        }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed by this test");
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

    private static dev.turboism.sdk.cubism.model.FloatSequence emptyFloats() {
        return new dev.turboism.sdk.cubism.model.FloatSequence() {
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

    private static class StubDrawable implements Drawable {
        private final ArtMeshId id;
        private ArtMeshGeometry applied;
        private final ArtMeshGeometry square;

        StubDrawable(final String id) {
            this.id = new ArtMeshId(id);
            final List<Point2> corners =
                    List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
            this.square = new ArtMeshGeometry(corners, corners, List.of(0, 1, 2, 0, 2, 3));
        }

        @Override
        public ArtMeshId id() {
            return id;
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
            return 1.0f;
        }

        @Override
        public IntSequence masks() {
            return emptyInts();
        }

        @Override
        public IntSequence parameters() {
            return emptyInts();
        }

        @Override
        public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
            return emptyFloats();
        }

        @Override
        public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
            return emptyFloats();
        }

        @Override
        public IntSequence indices() {
            return emptyInts();
        }

        @Override
        public dev.turboism.sdk.cubism.model.Color multiplyColor() {
            return new dev.turboism.sdk.cubism.model.Color(0f, 0f, 0f, 0f);
        }

        @Override
        public dev.turboism.sdk.cubism.model.Color screenColor() {
            return new dev.turboism.sdk.cubism.model.Color(0f, 0f, 0f, 0f);
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
        public ArtMeshGeometry geometry() {
            return applied != null ? applied : square;
        }

        @Override
        public void replaceGeometry(final ArtMeshGeometry geometry) {
            this.applied = geometry;
        }
    }

    private static final class RecordingContext implements PluginContext {
        final DisposableScope disposableScope = new DisposableScope();
        final RecordingActions actions = new RecordingActions();
        final RecordingContextMenu contextMenu = new RecordingContextMenu();
        final RecordingUiHost uiHost = new RecordingUiHost();
        final List<Drawable> modelDrawables = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithmRegistry autoMeshAlgorithms;
        final PluginLogger logger = new PluginLogger() {
            @Override
            public void debug(final String message) {}

            @Override
            public void info(final String message) {}

            @Override
            public void warn(final String message) {
                warnings.add(message);
            }

            @Override
            public void error(final String message) {}

            @Override
            public void error(final String message, final Throwable throwable) {}
        };
        final PluginLocalization localization = FakePluginContext.passThroughLocalization(Locale.ENGLISH);
        final CubismFacade cubism = new CubismFacade() {
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
            public CubismModelAccess model() {
                return () -> new CubismModel() {
                    @Override
                    public ModelId id() {
                        return new ModelId("model-1");
                    }

                    @Override
                    public Parameters parameters() {
                        throw unsupported();
                    }

                    @Override
                    public Parts parts() {
                        throw unsupported();
                    }

                    @Override
                    public Drawables drawables() {
                        return new Drawables() {
                            @Override
                            public List<Drawable> all() {
                                return List.copyOf(modelDrawables);
                            }

                            @Override
                            public Drawable find(final ArtMeshId id) {
                                for (final Drawable d : modelDrawables) {
                                    if (d.id().equals(id)) {
                                        return d;
                                    }
                                }
                                throw new java.util.NoSuchElementException(id.value());
                            }
                        };
                    }

                    @Override
                    public Deformers deformers() {
                        throw unsupported();
                    }

                    @Override
                    public Glues glues() {
                        throw unsupported();
                    }

                    @Override
                    public void update() {}
                };
            }
        };

        @Override
        public PluginDescriptor descriptor() {
            throw unsupported();
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            throw unsupported();
        }

        @Override
        public CubismFacade cubism() {
            return cubism;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public PluginLocalization localization() {
            return localization;
        }

        @Override
        public EventBus eventBus() {
            return new EventBus() {
                @Override
                public <T extends TurboismEvent> Registration subscribe(
                        final Class<T> type, final Consumer<T> listener) {
                    return () -> {};
                }

                @Override
                public <T extends TurboismEvent> void publish(final T event) {}
            };
        }

        @Override
        public RecordingActions actions() {
            return actions;
        }

        @Override
        public MenuRegistry menus() {
            throw unsupported();
        }

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return new dev.turboism.sdk.plugin.PluginServiceDirectory() {
                @Override
                public java.util.Set<dev.turboism.sdk.plugin.PluginService> installed() {
                    return java.util.Set.of();
                }

                @Override
                public <T> Optional<T> find(final Class<T> serviceType) {
                    if (serviceType == ContextMenuRegistry.class) {
                        return Optional.of(serviceType.cast(contextMenu));
                    }
                    if (serviceType == UiHostCapabilityService.class) {
                        return Optional.of(serviceType.cast(uiHost));
                    }
                    if (serviceType == dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithmRegistry.class
                            && autoMeshAlgorithms != null) {
                        return Optional.of(serviceType.cast(autoMeshAlgorithms));
                    }
                    return Optional.empty();
                }
            };
        }

        @Override
        public UiScheduler uiScheduler() {
            return new UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    work.run();
                    return () -> {};
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final java.time.Duration delay) {
                    work.run();
                    return () -> {};
                }
            };
        }

        @Override
        public DiagnosticReport diagnostics() {
            throw unsupported();
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }
    }

    /** Registry double that tracks live registrations by algorithm id. */
    private static final class RecordingAlgorithmRegistry
            implements dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithmRegistry {
        private final List<dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm> algorithms = new ArrayList<>();

        List<String> registeredIds() {
            return algorithms.stream()
                    .map(dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm::id)
                    .toList();
        }

        @Override
        public Registration register(final dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm algorithm) {
            algorithms.add(algorithm);
            return () -> algorithms.remove(algorithm);
        }

        @Override
        public List<dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm> algorithms() {
            return List.copyOf(algorithms);
        }

        @Override
        public Optional<dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm> find(final String algorithmId) {
            return algorithms.stream()
                    .filter(algorithm -> algorithm.id().equals(algorithmId))
                    .findFirst();
        }

        @Override
        public void select(final String algorithmId) {}

        @Override
        public Optional<String> selectedAlgorithmId() {
            return Optional.empty();
        }

        @Override
        public void close() {
            algorithms.clear();
        }
    }

    private static final class RecordingActions implements ActionRegistry {
        final List<Action> registered = new ArrayList<>();

        @Override
        public Registration register(final String id, final Action action) {
            registered.add(action);
            return () -> registered.remove(action);
        }

        void execute(final String id, final ActionContext context) {
            for (final Action action : List.copyOf(registered)) {
                if (action.id().equals(id)) {
                    action.handler().accept(context);
                    return;
                }
            }
            throw new IllegalStateException("no action: " + id);
        }
    }

    private static final class RecordingContextMenu implements ContextMenuRegistry {
        final List<ContextMenuContribution> contributions = new ArrayList<>();

        @Override
        public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        final List<StatusNotification> notifications = new ArrayList<>();
        Optional<String> chooseResult = Optional.of("AUTO_BLEND");
        boolean confirmResult = true;
        boolean dialogAvailable = true;
        int chooseCalls;
        int confirmCalls;

        @Override
        public Optional<String> choose(final dev.turboism.sdk.ui.ChoiceDialogRequest request) {
            if (!dialogAvailable) {
                throw new UnsupportedOperationException("dialog surface unavailable");
            }
            chooseCalls++;
            if ("turbo-mesh.confirm".equals(request.id())) {
                confirmCalls++;
                return confirmResult ? Optional.of("APPLY") : Optional.empty();
            }
            return chooseResult;
        }

        @Override
        public Registration notifyStatus(final StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }

        @Override
        public Registration contributeOverlay(final dev.turboism.sdk.ui.OverlayContribution c) {
            throw unsupported();
        }

        @Override
        public Registration contributeBoundingBoxOverlayButton(final dev.turboism.sdk.ui.BoundingBoxOverlayButton c) {
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
        public Registration openDialog(final dev.turboism.sdk.ui.DialogRequest r) {
            throw unsupported();
        }

        @Override
        public boolean confirmDialog(final dev.turboism.sdk.ui.DialogRequest r) {
            confirmCalls++;
            return confirmResult;
        }

        @Override
        public Registration contributeEmbeddedPanel(final dev.turboism.sdk.ui.EmbeddedPanelContribution c) {
            throw unsupported();
        }

        @Override
        public Optional<String> requestFile(final dev.turboism.sdk.ui.FileChooserRequest r) {
            throw unsupported();
        }

        @Override
        public Registration contributeCollapsibleSection(final dev.turboism.sdk.ui.CollapsibleSectionContribution c) {
            throw unsupported();
        }

        @Override
        public Registration contributeContextMenu(final ContextMenuRegistry.ContextMenuContribution c) {
            throw unsupported();
        }

        @Override
        public Registration contributeMainToolbar(
                final dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.MainToolbarContribution c) {
            throw unsupported();
        }

        @Override
        public Registration contributePaletteToolbar(
                final dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.PaletteToolbarContribution c) {
            throw unsupported();
        }
    }
}
