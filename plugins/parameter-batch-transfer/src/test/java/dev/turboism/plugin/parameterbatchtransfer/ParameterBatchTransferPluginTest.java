package dev.turboism.plugin.parameterbatchtransfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.parameterbatchtransfer.service.ParameterBatchTransferService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParameterBatchTransferPluginTest {

    @Test
    void enableRegistersOneActionAndThreeContextMenuEntries() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();

        assertEquals(List.of(ParameterBatchTransferPlugin.ACTION_ID), fixture.actions.ids());
        assertEquals(3, fixture.contextMenus.contributions.size());

        final ContextMenuRegistry.ContextMenuContribution deformer =
                fixture.contextMenus.byId(ParameterBatchTransferPlugin.CONTEXT_MENU_DEFORMER_ID);
        final ContextMenuRegistry.ContextMenuContribution part =
                fixture.contextMenus.byId(ParameterBatchTransferPlugin.CONTEXT_MENU_PART_ID);

        final ContextMenuRegistry.ContextMenuContribution workspace =
                fixture.contextMenus.byId(ParameterBatchTransferPlugin.CONTEXT_MENU_WORKSPACE_ID);

        assertEquals(ContextMenuRegistry.Location.WORKSPACE_OBJECT, workspace.location());
        assertEquals(ContextMenuRegistry.Location.DEFORMER_TAB, deformer.location());
        assertEquals(ContextMenuRegistry.Location.PART_TAB, part.location());
        for (final ContextMenuRegistry.ContextMenuContribution contribution : fixture.contextMenus.contributions) {
            assertEquals(ParameterBatchTransferPlugin.ACTION_ID, contribution.actionId());
            assertEquals(
                    Set.of(
                            ContextMenuRegistry.ObjectKind.ART_MESH,
                            ContextMenuRegistry.ObjectKind.WARP_DEFORMER,
                            ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER),
                    contribution.objectKinds());
            assertSame(ParameterBatchTransferPlugin.SINGLE_SELECTION, contribution.visibleWhen());
        }
        assertEquals("menu.batchTransfer", deformer.label());
    }

    @Test
    void singleSelectionPredicateAcceptsExactlyOneItem() {
        final ContextMenuSelection single = new ContextMenuSelection(
                1L,
                "document-a",
                ContextMenuRegistry.Location.DEFORMER_TAB,
                List.of(new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1")));
        final ContextMenuSelection multiple = new ContextMenuSelection(
                1L,
                "document-a",
                ContextMenuRegistry.Location.DEFORMER_TAB,
                List.of(
                        new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                        new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2")));

        assertTrue(ParameterBatchTransferPlugin.SINGLE_SELECTION.test(single));
        assertFalse(ParameterBatchTransferPlugin.SINGLE_SELECTION.test(multiple));
    }

    @Test
    void actionWithoutSelectionNotifiesNoSelection() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();

        fixture.actions
                .byId(ParameterBatchTransferPlugin.ACTION_ID)
                .handler()
                .accept(new ActionRegistry.ActionContext() {});

        assertEquals(
                List.of(new StatusNotification(
                        "parameter.batchTransfer.status.noSelection", "INFO", "status.noSelection")),
                fixture.uiHost.notifications);
        assertFalse(fixture.cubism.accessed);
    }

    @Test
    void actionWithMultiSelectionNotifiesNoSelection() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();

        fixture.actions
                .byId(ParameterBatchTransferPlugin.ACTION_ID)
                .handler()
                .accept(new ActionRegistry.ActionContext() {
                    @Override
                    public Optional<ContextMenuSelection> contextMenuSelection() {
                        return Optional.of(new ContextMenuSelection(
                                1L,
                                "document-a",
                                ContextMenuRegistry.Location.PART_TAB,
                                List.of(
                                        new ContextMenuSelection.Item(
                                                ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"),
                                        new ContextMenuSelection.Item(
                                                ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-2"))));
                    }
                });

        assertEquals(1, fixture.uiHost.notifications.size());
        assertEquals(
                "parameter.batchTransfer.status.noSelection",
                fixture.uiHost.notifications.get(0).id());
    }

    @Test
    void actionWithSingleUnboundObjectNotifiesNoBoundParameters() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.plugin.enable();

        fixture.actions
                .byId(ParameterBatchTransferPlugin.ACTION_ID)
                .handler()
                .accept(new ActionRegistry.ActionContext() {
                    @Override
                    public Optional<ContextMenuSelection> contextMenuSelection() {
                        return Optional.of(new ContextMenuSelection(
                                1L,
                                "document-a",
                                ContextMenuRegistry.Location.DEFORMER_TAB,
                                List.of(new ContextMenuSelection.Item(
                                        ContextMenuRegistry.ObjectKind.ART_MESH, "mesh-1"))));
                    }
                });

        assertEquals(
                List.of(new StatusNotification(
                        "parameter.batchTransfer.status.noBoundParameters", "INFO", "status.noBoundParameters")),
                fixture.uiHost.notifications);
        assertTrue(fixture.cubism.accessed);
    }

    @Test
    void enableFailureClosesTheDisposableScope() {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context);
        fixture.contextMenus.failOnContribute = true;

        assertThrows(IllegalStateException.class, fixture.plugin::enable);

        // A closed disposable scope rejects further registrations.
        assertThrows(IllegalStateException.class, () -> fixture.scope.register(() -> {}));
    }

    private static final class Fixture {
        final RecordingActions actions = new RecordingActions();
        final RecordingContextMenus contextMenus = new RecordingContextMenus();
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingCubism cubism = new RecordingCubism();
        final DisposableScope scope = new DisposableScope();
        final PluginContext context = new RecordingContext(actions, contextMenus, uiHost, cubism, scope);
        final ParameterBatchTransferPlugin plugin =
                new ParameterBatchTransferPlugin(new ParameterBatchTransferService());
    }

    private static final class RecordingContext extends FakePluginContext {
        private final RecordingActions actions;
        private final RecordingContextMenus contextMenus;
        private final RecordingUiHost uiHost;
        private final RecordingCubism cubism;
        private final DisposableScope scope;

        RecordingContext(
                final RecordingActions actions,
                final RecordingContextMenus contextMenus,
                final RecordingUiHost uiHost,
                final RecordingCubism cubism,
                final DisposableScope scope) {
            super(FakePluginContext.builder());
            this.actions = actions;
            this.contextMenus = contextMenus;
            this.uiHost = uiHost;
            this.cubism = cubism;
            this.scope = scope;
        }

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public CubismFacade cubism() {
            return cubism;
        }

        @Override
        public RecordingActions actions() {
            return actions;
        }

        public RecordingContextMenus contextMenus() {
            return contextMenus;
        }

        @Override
        public PluginLocalization localization() {
            return passThroughLocalization();
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return dev.turboism.sdk.plugin.PluginServices.builder()
                    .supply(dev.turboism.sdk.ui.context.ContextMenuRegistry.class, () -> this.contextMenus())
                    .supply(dev.turboism.sdk.ui.UiHostCapabilityService.class, () -> this.uiHost())
                    .fallback(dev.turboism.sdk.plugin.PluginServices.of(this))
                    .build();
        }
    }

    private static final class RecordingActions implements ActionRegistry {
        final List<ActionRegistry.Action> actions = new ArrayList<>();

        @Override
        public Registration register(final String id, final ActionRegistry.Action action) {
            actions.add(action);
            return () -> {};
        }

        List<String> ids() {
            return actions.stream().map(ActionRegistry.Action::id).toList();
        }

        ActionRegistry.Action byId(final String id) {
            return actions.stream()
                    .filter(action -> action.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static final class RecordingContextMenus implements ContextMenuRegistry {
        final List<ContextMenuRegistry.ContextMenuContribution> contributions = new ArrayList<>();
        boolean failOnContribute;

        @Override
        public Registration contribute(final ContextMenuRegistry.ContextMenuContribution contribution) {
            if (failOnContribute) {
                throw new IllegalStateException("contribute failed");
            }
            contributions.add(contribution);
            return () -> {};
        }

        ContextMenuRegistry.ContextMenuContribution byId(final String id) {
            return contributions.stream()
                    .filter(value -> value.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static final class RecordingCubism implements CubismFacade {
        boolean accessed;
        final CubismModel model = new EmptyModel();

        @Override
        public CubismRuntimeSnapshot runtime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            throw new UnsupportedOperationException();
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
            accessed = true;
            return () -> model;
        }
    }

    private static final class EmptyModel implements CubismModel {
        @Override
        public ModelId id() {
            return new ModelId("model-1");
        }

        @Override
        public Parameters parameters() {
            return new Parameters() {
                @Override
                public List<Parameter> all() {
                    return List.of();
                }

                @Override
                public Parameter find(final dev.turboism.sdk.cubism.id.ParameterId id) {
                    throw new java.util.NoSuchElementException(id.value());
                }
            };
        }

        @Override
        public Parts parts() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Drawables drawables() {
            return new Drawables() {
                @Override
                public List<dev.turboism.sdk.cubism.model.Drawable> all() {
                    return List.of();
                }

                @Override
                public dev.turboism.sdk.cubism.model.Drawable find(final ArtMeshId id) {
                    throw new java.util.NoSuchElementException(id.value());
                }
            };
        }

        @Override
        public Deformers deformers() {
            return new Deformers() {
                @Override
                public List<dev.turboism.sdk.cubism.model.Deformer> all() {
                    return List.of();
                }

                @Override
                public dev.turboism.sdk.cubism.model.Deformer find(final dev.turboism.sdk.cubism.id.DeformerId id) {
                    throw new java.util.NoSuchElementException(id.value());
                }
            };
        }

        @Override
        public Glues glues() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update() {}

        @Override
        public ParameterBindingBatchOperations parameterBindingBatch() {
            throw new UnsupportedOperationException();
        }
    }
}
