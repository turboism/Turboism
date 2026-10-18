package dev.turboism.test.plugin;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.hostread.AsyncHostReadService;
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
import dev.turboism.sdk.script.ScriptService;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.test.action.RecordingActionRegistry;
import dev.turboism.test.config.InMemoryConfigRegistry;
import dev.turboism.test.event.RecordingEventBus;
import dev.turboism.test.menu.RecordingMenuRegistry;
import dev.turboism.test.storage.InMemoryPluginStorage;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import dev.turboism.test.ui.RecordingContextMenuRegistry;
import dev.turboism.test.ui.RecordingUiHost;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Highly configurable, builder-style fake {@link PluginContext} for plugin tests.
 */
public class FakePluginContext implements PluginContext {

    private final PluginDescriptor descriptor;
    private final PluginLogger logger;
    private final PluginPaths paths;
    private final PluginLocalization localization;
    private final PluginTaskScheduler tasks;
    private final AsyncHostReadService hostReads;
    private final PluginStorage storage;
    private final ScriptService scripts;
    private final UserFileAccessService userFiles;
    private final CubismFacade cubism;
    private final SelectionQueryService selectionQuery;
    private final ModelHierarchyQueryService modelHierarchyQuery;
    private final CubismReadCapabilityService cubismRead;
    private final ModelObjectService modelObjects;
    private final List<PluginPermission> permissions;
    private final EventBus eventBus;
    private final ActionRegistry actions;
    private final MenuRegistry menus;
    private final PluginConfigRegistry config;
    private final UiScheduler uiScheduler;
    private final DiagnosticReport diagnostics;
    private final DisposableScope disposableScope;
    private final PluginServiceDirectory services;
    private final RecordingUiHost uiHost;
    private final RecordingContextMenuRegistry contextMenu;

    protected FakePluginContext(final Builder builder) {
        this.descriptor =
                builder.descriptor != null ? builder.descriptor : FakePluginDescriptor.testDescriptor("test-plugin");
        this.logger = builder.logger != null ? builder.logger : new RecordingPluginLogger();
        this.paths = builder.paths;
        this.localization = builder.localization != null ? builder.localization : defaultLocalization();
        this.tasks = builder.tasks != null ? builder.tasks : PluginTaskScheduler.unavailable();
        this.hostReads = builder.hostReads != null ? builder.hostReads : AsyncHostReadService.unavailable();
        this.storage = builder.storage != null ? builder.storage : new InMemoryPluginStorage();
        this.scripts = builder.scripts != null ? builder.scripts : ScriptService.unavailable();
        this.userFiles = builder.userFiles != null ? builder.userFiles : UserFileAccessService.unavailable();
        this.cubism = builder.cubism;
        this.selectionQuery =
                builder.selectionQuery != null ? builder.selectionQuery : SelectionQueryService.unavailable();
        this.modelHierarchyQuery = builder.modelHierarchyQuery != null
                ? builder.modelHierarchyQuery
                : ModelHierarchyQueryService.unavailable();
        this.cubismRead = builder.cubismRead != null ? builder.cubismRead : CubismReadCapabilityService.unavailable();
        this.modelObjects = builder.modelObjects != null ? builder.modelObjects : ModelObjectService.unavailable();
        this.permissions = builder.permissions != null ? List.copyOf(builder.permissions) : List.of();
        this.eventBus = builder.eventBus != null ? builder.eventBus : new RecordingEventBus();
        this.actions = builder.actions != null ? builder.actions : new RecordingActionRegistry();
        this.menus = builder.menus != null ? builder.menus : new RecordingMenuRegistry();
        this.config = builder.config != null ? builder.config : new InMemoryConfigRegistry();
        this.uiScheduler = builder.uiScheduler != null ? builder.uiScheduler : new FakeDirectUiScheduler();
        this.diagnostics = builder.diagnostics != null ? builder.diagnostics : defaultDiagnostics();
        this.disposableScope = builder.disposableScope != null ? builder.disposableScope : new DisposableScope();
        this.uiHost = builder.uiHost;
        this.contextMenu = builder.contextMenu;

        final PluginServices.Builder serviceBuilder = PluginServices.builder();
        if (this.uiHost != null) {
            serviceBuilder.install(UiHostCapabilityService.class, this.uiHost);
        }
        if (this.contextMenu != null) {
            serviceBuilder.install(ContextMenuRegistry.class, this.contextMenu);
        }
        builder.customServices.forEach((type, supplier) -> installCustomService(serviceBuilder, type, supplier));
        serviceBuilder.fallback(PluginServices.of(this));
        this.services = serviceBuilder.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> void installCustomService(
            final PluginServices.Builder serviceBuilder, final Class<?> type, final Supplier<?> supplier) {
        serviceBuilder.supply((Class<T>) type, (Supplier<T>) supplier);
    }

    public FakePluginContext() {
        this(builder());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static FakePluginContext create() {
        return new FakePluginContext();
    }

    private static PluginLocalization defaultLocalization() {
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
                if (arguments == null || arguments.length == 0) {
                    return key;
                }
                return key + " " + java.util.Arrays.toString(arguments);
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };
    }

    private static DiagnosticReport defaultDiagnostics() {
        return new DiagnosticReport() {
            @Override
            public Instant createdAt() {
                return Instant.now();
            }

            @Override
            public List<Problem> problems() {
                return List.of();
            }
        };
    }

    @Override
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public PluginLogger logger() {
        return logger;
    }

    @Override
    public PluginPaths paths() {
        return paths;
    }

    @Override
    public PluginLocalization localization() {
        return localization;
    }

    @Override
    public PluginTaskScheduler tasks() {
        return tasks;
    }

    @Override
    public AsyncHostReadService hostReads() {
        return hostReads;
    }

    @Override
    public PluginStorage storage() {
        return storage;
    }

    @Override
    public ScriptService scripts() {
        return scripts;
    }

    @Override
    public UserFileAccessService userFiles() {
        return userFiles;
    }

    @Override
    public CubismFacade cubism() {
        return cubism;
    }

    @Override
    public SelectionQueryService selectionQuery() {
        return selectionQuery;
    }

    @Override
    public ModelHierarchyQueryService modelHierarchyQuery() {
        return modelHierarchyQuery;
    }

    @Override
    public CubismReadCapabilityService cubismRead() {
        return cubismRead;
    }

    @Override
    public ModelObjectService modelObjects() {
        return modelObjects;
    }

    @Override
    public List<PluginPermission> permissions() {
        return permissions;
    }

    @Override
    public PluginServiceDirectory services() {
        return services;
    }

    @Override
    public EventBus eventBus() {
        return eventBus;
    }

    @Override
    public ActionRegistry actions() {
        return actions;
    }

    @Override
    public MenuRegistry menus() {
        return menus;
    }

    @Override
    public PluginConfigRegistry config() {
        return config;
    }

    @Override
    public UiScheduler uiScheduler() {
        return uiScheduler;
    }

    @Override
    public DiagnosticReport diagnostics() {
        return diagnostics;
    }

    @Override
    public DisposableScope disposableScope() {
        return disposableScope;
    }

    public RecordingUiHost uiHost() {
        return uiHost;
    }

    public RecordingContextMenuRegistry contextMenu() {
        return contextMenu;
    }

    public RecordingActionRegistry actionRegistry() {
        return actions instanceof RecordingActionRegistry recording ? recording : null;
    }

    public InMemoryPluginStorage storageFake() {
        return storage instanceof InMemoryPluginStorage inMemory ? inMemory : null;
    }

    public InMemoryConfigRegistry configRegistry() {
        return config instanceof InMemoryConfigRegistry inMemory ? inMemory : null;
    }

    public RecordingEventBus eventBusFake() {
        return eventBus instanceof RecordingEventBus recording ? recording : null;
    }

    public RecordingPluginLogger loggerFake() {
        return logger instanceof RecordingPluginLogger recording ? recording : null;
    }

    public <T> Optional<T> findService(final Class<T> serviceType) {
        return services.find(serviceType);
    }

    public <T> T requireService(final Class<T> serviceType) {
        return services.require(serviceType);
    }

    public static class Builder {
        private PluginDescriptor descriptor;
        private PluginLogger logger;
        private PluginPaths paths;
        private PluginLocalization localization;
        private PluginTaskScheduler tasks;
        private AsyncHostReadService hostReads;
        private PluginStorage storage;
        private ScriptService scripts;
        private UserFileAccessService userFiles;
        private CubismFacade cubism;
        private SelectionQueryService selectionQuery;
        private ModelHierarchyQueryService modelHierarchyQuery;
        private CubismReadCapabilityService cubismRead;
        private ModelObjectService modelObjects;
        private List<PluginPermission> permissions;
        private EventBus eventBus;
        private ActionRegistry actions;
        private MenuRegistry menus;
        private PluginConfigRegistry config;
        private UiScheduler uiScheduler;
        private DiagnosticReport diagnostics;
        private DisposableScope disposableScope;
        private RecordingUiHost uiHost;
        private RecordingContextMenuRegistry contextMenu;
        private final Map<Class<?>, Supplier<?>> customServices = new LinkedHashMap<>();

        protected Builder() {}

        public Builder descriptor(final PluginDescriptor descriptor) {
            this.descriptor = descriptor;
            return this;
        }

        public Builder logger(final PluginLogger logger) {
            this.logger = logger;
            return this;
        }

        public Builder paths(final PluginPaths paths) {
            this.paths = paths;
            return this;
        }

        public Builder localization(final PluginLocalization localization) {
            this.localization = localization;
            return this;
        }

        public Builder tasks(final PluginTaskScheduler tasks) {
            this.tasks = tasks;
            return this;
        }

        public Builder hostReads(final AsyncHostReadService hostReads) {
            this.hostReads = hostReads;
            return this;
        }

        public Builder storage(final PluginStorage storage) {
            this.storage = storage;
            return this;
        }

        public Builder scripts(final ScriptService scripts) {
            this.scripts = scripts;
            return this;
        }

        public Builder userFiles(final UserFileAccessService userFiles) {
            this.userFiles = userFiles;
            return this;
        }

        public Builder cubism(final CubismFacade cubism) {
            this.cubism = cubism;
            return this;
        }

        public Builder selectionQuery(final SelectionQueryService selectionQuery) {
            this.selectionQuery = selectionQuery;
            return this;
        }

        public Builder modelHierarchyQuery(final ModelHierarchyQueryService modelHierarchyQuery) {
            this.modelHierarchyQuery = modelHierarchyQuery;
            return this;
        }

        public Builder cubismRead(final CubismReadCapabilityService cubismRead) {
            this.cubismRead = cubismRead;
            return this;
        }

        public Builder modelObjects(final ModelObjectService modelObjects) {
            this.modelObjects = modelObjects;
            return this;
        }

        public Builder permissions(final List<PluginPermission> permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder eventBus(final EventBus eventBus) {
            this.eventBus = eventBus;
            return this;
        }

        public Builder actions(final ActionRegistry actions) {
            this.actions = actions;
            return this;
        }

        public Builder menus(final MenuRegistry menus) {
            this.menus = menus;
            return this;
        }

        public Builder config(final PluginConfigRegistry config) {
            this.config = config;
            return this;
        }

        public Builder uiScheduler(final UiScheduler uiScheduler) {
            this.uiScheduler = uiScheduler;
            return this;
        }

        public Builder diagnostics(final DiagnosticReport diagnostics) {
            this.diagnostics = diagnostics;
            return this;
        }

        public Builder disposableScope(final DisposableScope disposableScope) {
            this.disposableScope = disposableScope;
            return this;
        }

        public Builder uiHost(final RecordingUiHost uiHost) {
            this.uiHost = uiHost;
            return this;
        }

        public Builder contextMenu(final RecordingContextMenuRegistry contextMenu) {
            this.contextMenu = contextMenu;
            return this;
        }

        public <T> Builder service(final Class<T> serviceType, final T instance) {
            Objects.requireNonNull(serviceType, "serviceType");
            if (instance == null) {
                customServices.remove(serviceType);
            } else {
                customServices.put(serviceType, () -> instance);
            }
            return this;
        }

        public <T> Builder supplyService(final Class<T> serviceType, final Supplier<? extends T> supplier) {
            Objects.requireNonNull(serviceType, "serviceType");
            Objects.requireNonNull(supplier, "supplier");
            customServices.put(serviceType, supplier);
            return this;
        }

        public FakePluginContext build() {
            return new FakePluginContext(this);
        }
    }
}
