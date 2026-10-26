package dev.turboism.plugin.parametersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.parametersync.domain.SyncRuleSet;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.Parameters;
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
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.sdk.plugin.PluginServices;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.test.storage.InMemoryPluginStorage;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParameterSyncPluginTest {

    private ParameterSyncPlugin plugin;
    private TestPluginContext context;
    private TestModel model;
    private TestParameter paramA;
    private TestParameter paramB;
    private TestParameter paramC;

    @BeforeEach
    void setUp() {
        plugin = new ParameterSyncPlugin();
        context = new TestPluginContext();
        model = new TestModel("model-guid-1");
        paramA = new TestParameter("ParamA", 0.0f, 1.0f, 0.0f);
        paramB = new TestParameter("ParamB", 0.0f, 1.0f, 0.0f);
        paramC = new TestParameter("ParamC", -10.0f, 10.0f, 0.0f);
        model.addParameter(paramA);
        model.addParameter(paramB);
        model.addParameter(paramC);
        context.setActiveModel(model);
        context.setActiveParameterId("ParamA");

        plugin.init(context);
        plugin.enable();
    }

    @Test
    void enableRegistersActionsAndContextMenus() {
        assertTrue(context.actionRegistry.actions.containsKey(ParameterSyncPlugin.ACTION_OPEN_SYNC));
        assertTrue(context.actionRegistry.actions.containsKey(ParameterSyncPlugin.ACTION_TOGGLE_GLOBAL));

        final List<String> menuIds = context.contextMenuRegistry.contributions.stream()
                .map(ContextMenuRegistry.ContextMenuContribution::id)
                .toList();
        assertTrue(menuIds.contains(ParameterSyncPlugin.CONTEXT_MENU_SYNC_ID));
        assertFalse(menuIds.contains("parameter-sync.menu.toggle"));
    }

    @Test
    void disableUnregistersAll() {
        plugin.disable();
        assertTrue(context.actionRegistry.actions.isEmpty());
    }

    @Test
    void onParameterValueChangedSyncsCheckedParameters() {
        final SyncRuleSet ruleSet = plugin.getRuleSet("model-guid-1");
        ruleSet.setSynchronized("ParamA", true);
        ruleSet.setSynchronized("ParamB", true);
        ruleSet.setSynchronized("ParamC", true);

        // Move ParamA to 0.75 (75%)
        plugin.onParameterValueChanged(paramA, 0.0f, 0.75f);

        assertEquals(0.75f, paramB.getValue(), 1e-4f);
        // ParamC 75% in [-10, 10] is 5.0
        assertEquals(5.0f, paramC.getValue(), 1e-4f);
    }

    @Test
    void syncSuppressesSecondaryCascades() {
        final SyncRuleSet ruleSet = plugin.getRuleSet("model-guid-1");
        ruleSet.setSynchronized("ParamA", true);
        ruleSet.setSynchronized("ParamB", true);

        // User drags ParamA to 0.85
        paramA.setValue(0.85f);
        plugin.onParameterValueChanged(paramA, 0.0f, 0.85f);
        assertEquals(0.85f, paramB.getValue(), 1e-4f);

        // Host emits secondary callback for ParamB
        plugin.onParameterValueChanged(paramB, 0.0f, 0.85f);

        // ParamA was NOT bounced or corrupted by the secondary callback
        assertEquals(0.85f, paramA.getValue(), 1e-4f);
    }

    @Test
    void toggleGlobalActionChangesGlobalState() {
        final SyncRuleSet ruleSet = plugin.getRuleSet("model-guid-1");
        assertTrue(ruleSet.isGlobalEnabled());

        final ActionRegistry.Action toggleAction =
                context.actionRegistry.actions.get(ParameterSyncPlugin.ACTION_TOGGLE_GLOBAL);
        assertNotNull(toggleAction);

        toggleAction.handler().accept(new FakeActionContext(null));

        assertFalse(ruleSet.isGlobalEnabled());
    }

    @Test
    void saveAndLoadRulesCrossSession() throws Exception {
        final SyncRuleSet ruleSet = plugin.getRuleSet("model-guid-1");
        ruleSet.setSynchronized("ParamA", true);
        ruleSet.setSynchronized("ParamB", true);

        final Boolean saved = plugin.saveRules("model-guid-1").get();
        assertTrue(saved);

        final ParameterSyncPlugin freshPlugin = new ParameterSyncPlugin();
        freshPlugin.init(context);
        freshPlugin.enable();

        final SyncRuleSet loaded = freshPlugin.ensureLoaded("model-guid-1").get();
        assertEquals(2, loaded.synchronizedCount());
        assertTrue(loaded.isSynchronized("ParamA"));
        assertTrue(loaded.isSynchronized("ParamB"));
    }

    // ── Test Support Stubs ──

    private static final class TestPluginContext implements PluginContext {
        final FakeActionRegistry actionRegistry = new FakeActionRegistry();
        final FakeContextMenuRegistry contextMenuRegistry = new FakeContextMenuRegistry();
        final RecordingUiHost uiHost = new RecordingUiHost();
        final InMemoryPluginStorage storage = new InMemoryPluginStorage();
        final DisposableScope disposableScope = new DisposableScope();
        final NoopLogger logger = new NoopLogger();
        private CubismModel activeModel;
        private String activeParamId = "ParamA";

        final UiScheduler uiScheduler = new UiScheduler() {
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

        final PluginLocalization localization = new PluginLocalization() {
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

        void setActiveModel(final CubismModel model) {
            this.activeModel = model;
        }

        void setActiveParameterId(final String id) {
            this.activeParamId = id;
        }

        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public PluginLocalization localization() {
            return localization;
        }

        @Override
        public PluginStorage storage() {
            return storage;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return null;
        }

        @Override
        public ActionRegistry actions() {
            return actionRegistry;
        }

        @Override
        public MenuRegistry menus() {
            return null;
        }

        @Override
        public UiScheduler uiScheduler() {
            return uiScheduler;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }

        @Override
        public CubismFacade cubism() {
            return new CubismFacade() {
                @Override
                public CubismRuntimeSnapshot runtime() {
                    return new CubismRuntimeSnapshot(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            new SelectionSnapshot(
                                    activeParamId != null ? List.of(activeParamId) : List.of(),
                                    Optional.ofNullable(activeParamId),
                                    Optional.empty(),
                                    Optional.empty()),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of());
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
                    return new CubismModelAccess() {
                        @Override
                        public CubismModel active() {
                            if (activeModel == null) {
                                throw new IllegalStateException("no active model");
                            }
                            return activeModel;
                        }
                    };
                }
            };
        }

        @Override
        public PluginServiceDirectory services() {
            return PluginServices.builder()
                    .supply(ContextMenuRegistry.class, () -> contextMenuRegistry)
                    .supply(UiHostCapabilityService.class, () -> uiHost)
                    .build();
        }
    }

    private static final class FakeActionRegistry implements ActionRegistry {
        final Map<String, Action> actions = new HashMap<>();

        @Override
        public Registration register(final String id, final Action action) {
            actions.put(id, action);
            return () -> actions.remove(id);
        }
    }

    private static final class FakeContextMenuRegistry implements ContextMenuRegistry {
        final List<ContextMenuContribution> contributions = new ArrayList<>();

        @Override
        public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class FakeActionContext implements ActionRegistry.ActionContext {
        private final ContextMenuSelection selection;

        FakeActionContext(final ContextMenuSelection selection) {
            this.selection = selection;
        }

        @Override
        public Optional<ContextMenuSelection> contextMenuSelection() {
            return Optional.ofNullable(selection);
        }
    }

    private static final class TestModel implements CubismModel {
        private final String guid;
        private final Map<String, Parameter> params = new LinkedHashMap<>();

        TestModel(final String guid) {
            this.guid = guid;
        }

        void addParameter(final Parameter p) {
            params.put(p.id().value(), p);
        }

        @Override
        public ModelId id() {
            return new ModelId(guid);
        }

        @Override
        public String name() {
            return "TestModel";
        }

        @Override
        public Parameters parameters() {
            return new Parameters() {
                @Override
                public List<Parameter> all() {
                    return new ArrayList<>(params.values());
                }

                @Override
                public Parameter find(final ParameterId id) {
                    final Parameter p = params.get(id.value());
                    if (p == null) throw new NoSuchElementException(id.value());
                    return p;
                }

                @Override
                public Optional<Parameter> findById(final ParameterId id) {
                    return Optional.ofNullable(params.get(id.value()));
                }

                @Override
                public Optional<Parameter> findById(final String id) {
                    return Optional.ofNullable(params.get(id));
                }
            };
        }

        @Override
        public Parts parts() {
            return null;
        }

        @Override
        public Drawables drawables() {
            return null;
        }

        @Override
        public Deformers deformers() {
            return null;
        }

        @Override
        public Glues glues() {
            return null;
        }

        @Override
        public void update() {}
    }

    private static final class TestParameter implements Parameter {
        private final String id;
        private final float min;
        private final float max;
        private float value;

        TestParameter(final String id, final float min, final float max, final float initial) {
            this.id = id;
            this.min = min;
            this.max = max;
            this.value = initial;
        }

        @Override
        public ParameterId id() {
            return new ParameterId(id);
        }

        @Override
        public float getValue() {
            return value;
        }

        @Override
        public void setValue(final float value) {
            this.value = value;
        }

        @Override
        public float getMinimumValue() {
            return min;
        }

        @Override
        public float getMaximumValue() {
            return max;
        }

        @Override
        public float getDefaultValue() {
            return min;
        }
    }

    private static final class NoopLogger implements PluginLogger {
        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {}

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }
}
