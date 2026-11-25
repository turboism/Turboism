package dev.turboism.adapter.cubism.temporarydeform;

import static dev.turboism.mapping.selector.ModelingSelectionSelectorContract.*;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.SelectorAdmission;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformTool;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EdtDispatch;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-session coordinator owning registered custom Temporary Deform Tools, menu/Tool Details
 * synchronization, and native temporary handler activation on the EDT.
 */
public final class TemporaryDeformCoordinator implements AutoCloseable, NativeTemporaryDeformBridge.Handler {

    private static final String LOG_COMPONENT = "temporary-deform";
    private static final String MANAGER_FIELD_HANDLER = "b";
    private static final String MANAGER_ENTER_SNAPSHOT = "m";
    private static final String MANAGER_ENTER_FINISH = "n";
    private static final String MANAGER_UNBIND = "i";
    private static final String MANAGER_ACTION_PACK_HELPER = "b";
    private static final String ACTION_CONTEXT_CLASS = "com.live2d.cubism.view.context.actionManager.aG";
    private static final String ACTION_BASE_CLASS = "com.live2d.cubism.view.context.actionManager.N";
    private static final String COMPLETE_PACK_CLASS = "com.live2d.cubism.pack.CECompletePack";
    private static final String QUALIFIED_ID_SEPARATOR = ":";

    private static final Comparator<ToolEntry> TOOL_ORDER =
            Comparator.comparingInt((ToolEntry entry) -> entry.tool().order()).thenComparing(ToolEntry::qualifiedId);

    private final Map<ToolKey, ToolEntry> tools = new LinkedHashMap<>();
    private final NativeTemporaryDeformMenuAdapter menuAdapter;
    private final NativeTemporaryDeformToolDetailAdapter toolDetailAdapter;

    private VerifiedMemberResolver resolver;
    private TemporaryDeformHostProfile profile;
    private ClassLoader hostLoader;
    private long hostGeneration;
    private boolean lifecycleReady;
    private boolean closed;
    private ActiveSession active;

    public TemporaryDeformCoordinator() {
        this(new NativeTemporaryDeformMenuAdapter(), new NativeTemporaryDeformToolDetailAdapter());
    }

    TemporaryDeformCoordinator(
            final NativeTemporaryDeformMenuAdapter menuAdapter,
            final NativeTemporaryDeformToolDetailAdapter toolDetailAdapter) {
        this.menuAdapter = Objects.requireNonNull(menuAdapter, "menuAdapter");
        this.toolDetailAdapter = Objects.requireNonNull(toolDetailAdapter, "toolDetailAdapter");
    }

    /** Binds the verified host session resolver and generation on the EDT. */
    public void connect(final VerifiedMemberResolver nextResolver, final long nextHostGeneration) {
        EdtDispatch.call("temporary deform connect", () -> {
            disconnectOnEdt();
            if (closed) {
                return null;
            }
            this.hostGeneration = nextHostGeneration;
            if (nextResolver != null && SelectorAdmission.authorizesModelingSelection(nextResolver)) {
                final Optional<TemporaryDeformHostProfile> candidate =
                        TemporaryDeformHostProfile.forVersion(nextResolver.admittedCubismVersion());
                if (candidate.isPresent()) {
                    this.resolver = nextResolver;
                    this.profile = candidate.get();
                    this.hostLoader = Objects.requireNonNull(nextResolver.hostClassLoader(), "hostClassLoader");
                }
            }
            reconcileSurfacesOnEdt();
            return null;
        });
    }

    /** Marks the ASM lifecycle hook ready state for the active host generation. */
    public void lifecycleReady(final long targetHostGeneration, final boolean ready) {
        EdtDispatch.runEventually("temporary deform lifecycleReady", () -> {
            if (closed || this.hostGeneration != targetHostGeneration) {
                return;
            }
            this.lifecycleReady = ready;
            if (!ready) {
                deactivateOnEdt(true);
            }
            reconcileSurfacesOnEdt();
        });
    }

    /** Reports whether temporary deform tool integration is available on the active host. */
    public boolean isAvailable() {
        return EdtDispatch.call(
                "temporary deform availability",
                () -> !closed && lifecycleReady && resolver != null && profile != null);
    }

    /** Detaches host surfaces and active sessions while keeping registered plugin tools alive. */
    public void disconnect() {
        EdtDispatch.runEventually("temporary deform disconnect", this::disconnectOnEdt);
    }

    /** Registers one plugin tool and synchronizes native menus and Tool Details on the EDT. */
    public Registration register(
            final String pluginId,
            final long pluginGeneration,
            final TemporaryDeformTool tool,
            final PermissionChecker permissions) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(permissions, "permissions");
        final ToolKey key = new ToolKey(pluginId, pluginGeneration, tool.id());
        final ToolEntry entry = new ToolEntry(key, qualifyId(key), tool, permissions);
        EdtDispatch.call("temporary deform register", () -> {
            if (closed) {
                throw new IllegalStateException("temporary deform coordinator is closed");
            }
            if (tools.putIfAbsent(key, entry) != null) {
                throw new IllegalStateException("temporary deform tool is already registered: " + key.toolId());
            }
            reconcileSurfacesOnEdt();
            return null;
        });
        final AtomicBoolean disposed = new AtomicBoolean();
        return () -> {
            if (!disposed.compareAndSet(false, true)) {
                return;
            }
            EdtDispatch.runEventually("temporary deform unregister", () -> {
                if (tools.remove(key, entry)) {
                    if (active != null && active.key().equals(key)) {
                        deactivateOnEdt(true);
                    }
                    reconcileSurfacesOnEdt();
                }
            });
        };
    }

    /** Activates one tool owned by the specified plugin generation on the current modeling view. */
    public boolean activate(final String pluginId, final long pluginGeneration, final String toolId) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(toolId, "toolId");
        final ToolKey key = new ToolKey(pluginId, pluginGeneration, toolId);
        return EdtDispatch.call("temporary deform activate", () -> {
            final ToolEntry entry = tools.get(key);
            if (entry == null) {
                throw new IllegalStateException("temporary deform tool is not registered: " + toolId);
            }
            return activateEntryOnEdt(entry);
        });
    }

    /** Returns the plugin-local active tool id for one plugin generation, if active. */
    public Optional<String> activeToolId(final String pluginId, final long pluginGeneration) {
        Objects.requireNonNull(pluginId, "pluginId");
        return EdtDispatch.call("temporary deform activeToolId", () -> {
            if (active != null
                    && active.key().pluginId().equals(pluginId)
                    && active.key().pluginGeneration() == pluginGeneration) {
                return Optional.of(active.key().toolId());
            }
            return Optional.empty();
        });
    }

    /** Deactivates the active custom temporary deform tool if it belongs to the specified generation. */
    public void deactivate(final String pluginId, final long pluginGeneration) {
        Objects.requireNonNull(pluginId, "pluginId");
        EdtDispatch.runEventually("temporary deform deactivate", () -> {
            if (active != null
                    && active.key().pluginId().equals(pluginId)
                    && active.key().pluginGeneration() == pluginGeneration) {
                deactivateOnEdt(true);
            }
        });
    }

    /** Deactivates any currently active custom temporary deform tool regardless of owner. */
    public void unbindActive() {
        EdtDispatch.runEventually("temporary deform unbind", () -> deactivateOnEdt(true));
    }

    @Override
    public void onHostUnbind(final Object manager, final Object actionContext) {
        if (manager == null) {
            return;
        }
        EdtDispatch.runEventually("temporary deform host unbind", () -> {
            if (active != null && active.manager() == manager) {
                deactivateOnEdt(false);
            }
        });
    }

    @Override
    public void onHostSwitchToNative(final Object manager, final Object handlerType) {
        if (manager == null) {
            return;
        }
        EdtDispatch.runEventually("temporary deform host switch", () -> {
            if (active != null && active.manager() == manager) {
                deactivateOnEdt(false);
            }
        });
    }

    @Override
    public void onToolDetailInit(final Object toolDetailPanel, final Object completePack) {
        if (toolDetailPanel == null) {
            return;
        }
        EdtDispatch.runEventually("temporary deform detail init", () -> {
            if (closed) {
                return;
            }
            toolDetailAdapter.ensurePanelHooked(
                    toolDetailPanel, this::activateByQualifiedIdOnEdt, () -> deactivateOnEdt(false));
            reconcileToolDetailPanelOnEdt(toolDetailPanel);
        });
    }

    @Override
    public void onToolDetailRefresh(final Object toolDetailPanel, final Object modelingView) {
        if (toolDetailPanel == null) {
            return;
        }
        EdtDispatch.runEventually("temporary deform detail refresh", () -> {
            if (closed) {
                return;
            }
            reconcileToolDetailPanelOnEdt(toolDetailPanel);
        });
    }

    @Override
    public void close() {
        EdtDispatch.runEventually("temporary deform coordinator close", () -> {
            if (closed) {
                return;
            }
            closed = true;
            disconnectOnEdt();
            tools.clear();
        });
    }

    private void disconnectOnEdt() {
        lifecycleReady = false;
        deactivateOnEdt(true);
        menuAdapter.clear();
        toolDetailAdapter.clearAll();
        resolver = null;
        profile = null;
        hostLoader = null;
    }

    private void activateByQualifiedIdOnEdt(final String qualifiedId) {
        for (final ToolEntry entry : tools.values()) {
            if (entry.qualifiedId().equals(qualifiedId)) {
                try {
                    activateEntryOnEdt(entry);
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    RuntimeDiagnostics.warn(
                            LOG_COMPONENT,
                            "Custom tool activation failed safely: "
                                    + failure.getClass().getSimpleName());
                }
                return;
            }
        }
    }

    private boolean activateEntryOnEdt(final ToolEntry entry) {
        if (closed || !lifecycleReady || resolver == null || profile == null || hostLoader == null) {
            return false;
        }
        entry.permissions().check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "temporaryDeformTools.activate");
        entry.permissions().check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "temporaryDeformTools.activate");

        if (active != null && active.key().equals(entry.key())) {
            reconcileToolDetailsOnEdt();
            return true;
        }
        try {
            final HostModelingTarget target = resolveCurrentModelingTarget();
            if (target == null) {
                return false;
            }
            final Class<?> actionCtxClass = Class.forName(ACTION_CONTEXT_CLASS, false, hostLoader);
            final Class<?> managerClass = target.manager().getClass();
            final Method enterSnapshot = managerClass.getDeclaredMethod(MANAGER_ENTER_SNAPSHOT, actionCtxClass);
            enterSnapshot.setAccessible(true);
            final boolean prepared =
                    Boolean.TRUE.equals(enterSnapshot.invoke(target.manager(), target.actionContext()));
            if (!prepared) {
                return false;
            }
            final NativeTemporaryDeformHandlerAdapter handlerAdapter = new NativeTemporaryDeformHandlerAdapter(
                    profile,
                    hostLoader,
                    entry.tool(),
                    this::reconcileToolDetailsOnEdt,
                    () -> repaintCanvas(target.modelingView(), target.actionContext()),
                    () -> deactivateOnEdt(true));
            final Field handlerField = managerClass.getDeclaredField(MANAGER_FIELD_HANDLER);
            handlerField.setAccessible(true);
            handlerField.set(target.manager(), handlerAdapter.hostProxy());

            active = new ActiveSession(
                    entry.key(), entry.qualifiedId(), target.manager(), target.modelingView(), handlerAdapter);

            final Method enterFinish = managerClass.getDeclaredMethod(MANAGER_ENTER_FINISH, actionCtxClass);
            enterFinish.setAccessible(true);
            enterFinish.invoke(target.manager(), target.actionContext());

            final Method lockPalettes = managerClass.getDeclaredMethod(profile.lockMethodName());
            lockPalettes.setAccessible(true);
            lockPalettes.invoke(target.manager());

            reconcileToolDetailsOnEdt();
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            deactivateOnEdt(false);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Temporary deform activation failed safely: "
                            + failure.getClass().getSimpleName());
            return false;
        }
    }

    private void deactivateOnEdt(final boolean invokeNativeUnbind) {
        final ActiveSession current = active;
        active = null;
        if (current != null) {
            if (invokeNativeUnbind && resolver != null && hostLoader != null) {
                try {
                    final Object completePack = current.modelingView()
                            .getClass()
                            .getMethod("getCompletePack")
                            .invoke(current.modelingView());
                    final Object actionCtx = createActionContext(current.manager(), completePack);
                    if (actionCtx != null) {
                        current.manager()
                                .getClass()
                                .getMethod(MANAGER_UNBIND, actionCtx.getClass())
                                .invoke(current.manager(), actionCtx);
                    }
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    RuntimeDiagnostics.warn(
                            LOG_COMPONENT,
                            "Native temporary handler unbind failed safely: "
                                    + failure.getClass().getSimpleName());
                }
            }
            current.handlerAdapter().deactivate();
        }
        reconcileToolDetailsOnEdt();
    }

    private HostModelingTarget resolveCurrentModelingTarget() throws ReflectiveOperationException {
        final Object app = resolver.invokeStatic(APP);
        final Object currentView = resolver.invoke(CURRENT_VIEW, app);
        if (!resolver.isInstance(VIEW_CLASS, currentView)) {
            return null;
        }
        final Object pack = resolver.invoke(PACK, currentView);
        final Object manager =
                currentView.getClass().getMethod("getTemporaryHandlerManager").invoke(currentView);
        if (manager == null || pack == null) {
            return null;
        }
        final Object actionContext = createActionContext(manager, pack);
        if (actionContext == null) {
            return null;
        }
        return new HostModelingTarget(currentView, manager, actionContext);
    }

    private Object createActionContext(final Object manager, final Object completePack)
            throws ReflectiveOperationException {
        if (manager == null || completePack == null || hostLoader == null) {
            return null;
        }
        final Class<?> packClass = Class.forName(COMPLETE_PACK_CLASS, false, hostLoader);
        final Method factory = manager.getClass().getDeclaredMethod(MANAGER_ACTION_PACK_HELPER, packClass);
        factory.setAccessible(true);
        return factory.invoke(null, completePack);
    }

    private void repaintCanvas(final Object modelingView, final Object actionContext) {
        try {
            final Class<?> baseClass = Class.forName(ACTION_BASE_CLASS, false, hostLoader);
            final Method repaint = baseClass.getMethod("a", baseClass, boolean.class, int.class, Object.class);
            repaint.invoke(null, actionContext, false, 1, null);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            try {
                final Object pack =
                        modelingView.getClass().getMethod("getCompletePack").invoke(modelingView);
                pack.getClass().getMethod("updateProject").invoke(pack);
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
            }
        }
    }

    private void reconcileSurfacesOnEdt() {
        reconcileMenuOnEdt();
        reconcileToolDetailsOnEdt();
    }

    private void reconcileMenuOnEdt() {
        final List<NativeTemporaryDeformMenuAdapter.MenuEntry> entries = tools.values().stream()
                .sorted(TOOL_ORDER)
                .map(entry -> new NativeTemporaryDeformMenuAdapter.MenuEntry(
                        entry.qualifiedId(),
                        entry.tool().label(),
                        entry.tool().order(),
                        () -> activateByQualifiedIdOnEdt(entry.qualifiedId())))
                .toList();
        menuAdapter.reconcile(entries);
    }

    private void reconcileToolDetailsOnEdt() {
        final List<NativeTemporaryDeformToolDetailAdapter.ToolDetailEntry> entries = toolDetailEntries();
        final ToolEntry activeEntry = active != null ? tools.get(active.key()) : null;
        toolDetailAdapter.reconcileAll(
                entries,
                activeEntry != null ? activeEntry.qualifiedId() : null,
                activeEntry != null ? activeEntry.tool().options() : List.of(),
                this::handleOptionChangedOnEdt,
                this::activateByQualifiedIdOnEdt,
                () -> deactivateOnEdt(false));
    }

    private void reconcileToolDetailPanelOnEdt(final Object panel) {
        final ToolEntry activeEntry = active != null ? tools.get(active.key()) : null;
        toolDetailAdapter.reconcilePanel(
                panel,
                toolDetailEntries(),
                activeEntry != null ? activeEntry.qualifiedId() : null,
                activeEntry != null ? activeEntry.tool().options() : List.of(),
                this::handleOptionChangedOnEdt,
                this::activateByQualifiedIdOnEdt,
                () -> deactivateOnEdt(false));
    }

    private void handleOptionChangedOnEdt(final String optionId, final String value) {
        if (active != null) {
            active.handlerAdapter().notifyOptionChanged(optionId, value);
            repaintCanvas(active.modelingView(), createQuietActionContext(active));
        }
    }

    private Object createQuietActionContext(final ActiveSession session) {
        try {
            final Object pack = session.modelingView()
                    .getClass()
                    .getMethod("getCompletePack")
                    .invoke(session.modelingView());
            return createActionContext(session.manager(), pack);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private List<NativeTemporaryDeformToolDetailAdapter.ToolDetailEntry> toolDetailEntries() {
        return tools.values().stream()
                .sorted(TOOL_ORDER)
                .map(entry -> new NativeTemporaryDeformToolDetailAdapter.ToolDetailEntry(
                        entry.qualifiedId(), entry.tool().label(), entry.tool().order()))
                .toList();
    }

    private static String qualifyId(final ToolKey key) {
        return key.pluginId() + QUALIFIED_ID_SEPARATOR + key.pluginGeneration() + QUALIFIED_ID_SEPARATOR + key.toolId();
    }

    private record ToolKey(String pluginId, long pluginGeneration, String toolId) {}

    private record ToolEntry(
            ToolKey key, String qualifiedId, TemporaryDeformTool tool, PermissionChecker permissions) {}

    private record ActiveSession(
            ToolKey key,
            String qualifiedId,
            Object manager,
            Object modelingView,
            NativeTemporaryDeformHandlerAdapter handlerAdapter) {}

    private record HostModelingTarget(Object modelingView, Object manager, Object actionContext) {}
}
