package dev.turboism.adapter.cubism.modeling;

import static dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.*;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.modeling.ModelingBrush;
import dev.turboism.sdk.cubism.modeling.ModelingTool;
import dev.turboism.sdk.cubism.modeling.ModelingToolContext;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EdtDispatch;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.Timer;

/** Host-session owner of ordinary tools. All transitions and native operations serialize on the EDT. */
public final class ModelingToolCoordinator implements AutoCloseable {
    private final Map<Key, Entry> tools = new HashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private NativeModelingToolSessionResolver sessions;
    private Active active;
    private Timer lifetime;
    private java.util.List<Object> viewListeners;
    private Object viewCallback;
    private boolean eligible;
    private long generation;
    private long activationGeneration;
    private boolean lifecycleReady;
    private boolean closed;

    /** Binds the host generation and a reversible native view-change subscription on the EDT. */
    public void connect(VerifiedMemberResolver resolver, long hostGeneration) {
        edt(() -> {
            disconnectOnEdt();
            if (closed) return;
            generation = hostGeneration;
            if (ModelingSelectionSelectorContract.authorizes(resolver)) {
                sessions = new NativeModelingToolSessionResolver(resolver);
                final Object app = resolver.invokeStatic(APP);
                final Object callbacks = resolver.invoke(VIEW_LISTENERS, app);
                if (!(callbacks instanceof java.util.List<?> list)) {
                    sessions = null;
                    throw new IllegalStateException("native view-change notifications are unavailable");
                }
                @SuppressWarnings("unchecked")
                final java.util.List<Object> typed = (java.util.List<Object>) list;
                viewListeners = typed;
                viewCallback = resolver.createBiFunctionalListElementProxy(VIEW_LISTENERS, (previous, next) -> {
                    if (active != null && active.identity.view() != next) deactivateOnEdt();
                    changed();
                    // Native view notifications ignore the erased callback result.
                    return null;
                });
                viewListeners.add(viewCallback);
            }
            startLifetime();
            changed();
        });
    }

    /** Hook installation is an additional runtime prerequisite, separate from static admission. */
    public void lifecycleReady(long hostGeneration, boolean ready) {
        edt(() -> {
            if (generation != hostGeneration || closed) return;
            lifecycleReady = ready;
            if (!ready) deactivateOnEdt();
            changed();
        });
    }

    /** Reports static admission plus successful lifecycle-hook installation. */
    public boolean isAvailable() {
        return EdtDispatch.call(
                "modeling tool availability",
                () -> !closed
                        && lifecycleReady
                        && sessions != null
                        && ModelingSelectionSelectorContract.authorizes(sessions.resolver()));
    }

    /** Reports whether ordinary mode contains a selected editable ArtMesh or Warp Deformer. */
    public boolean isEligible() {
        return EdtDispatch.call("modeling tool eligibility", () -> {
            if (!isAvailable()) return false;
            return sessions.resolve()
                    .map(id -> {
                        final var resolver = sessions.resolver();
                        for (Object source :
                                NativeModelingPointProjector.list(resolver.invoke(SELECTED_OBJECTS, id.selector()))) {
                            if (!Boolean.TRUE.equals(resolver.invoke(SOURCE_EDITABLE, source))) continue;
                            final Object object =
                                    resolver.invoke(MODEL_OBJECT, id.model(), resolver.invoke(SOURCE_ID, source));
                            if ((resolver.isInstance(MESH_CLASS, object) || resolver.isInstance(WARP_CLASS, object))
                                    && Boolean.TRUE.equals(resolver.invoke(VIEW_EDITABLE, id.view(), object)))
                                return true;
                        }
                        return false;
                    })
                    .orElse(false);
        });
    }

    /** Registers exact plugin-generation logic; disposal revokes only this registration's lease. */
    public Registration register(
            String pluginId, long pluginGeneration, ModelingTool tool, PermissionChecker permissions) {
        final Key key = new Key(pluginId, pluginGeneration, tool.id());
        final Entry entry = new Entry(tool, permissions);
        edt(() -> {
            if (closed) throw new IllegalStateException("modeling tool coordinator is closed");
            if (tools.putIfAbsent(key, entry) != null)
                throw new IllegalStateException("modeling tool is already registered");
            startLifetime();
        });
        final AtomicBoolean disposed = new AtomicBoolean();
        return () -> {
            if (!disposed.compareAndSet(false, true)) return;
            edtEventually(() -> {
                if (tools.remove(key, entry) && active != null && active.key.equals(key)) deactivateOnEdt();
                if (tools.isEmpty() && lifetime != null) {
                    lifetime.stop();
                    lifetime = null;
                }
                changed();
            });
        };
    }

    /** Activates the authorized exact registration, or stops it when already active. */
    public void toggle(String pluginId, long pluginGeneration, String toolId) {
        edt(() -> {
            final Key key = new Key(pluginId, pluginGeneration, toolId);
            if (active != null && active.key.equals(key)) {
                deactivateOnEdt();
                return;
            }
            if (!isAvailable())
                throw new UnsupportedOperationException("ordinary modeling tool lifecycle is unavailable");
            final Entry entry = Objects.requireNonNull(tools.get(key), "modeling tool registration is stale");
            entry.permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "modelingTools.activate");
            entry.permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "modelingTools.activate");
            final var identity = sessions.resolve()
                    .orElseThrow(() -> new IllegalStateException("ordinary modeling mode is unavailable"));
            if (NativeModelingPointProjector.capture(sessions.resolver(), identity)
                    .candidates()
                    .isEmpty()) {
                throw new IllegalStateException(
                        "select an editable ArtMesh or Warp Deformer before activating the brush");
            }
            // An obsolete widget or denied request must not revoke another registration's lease.
            deactivateOnEdt();
            // Put native canvas navigation into the ordinary arrow mode before intercepting primary
            // brush input. Its lifecycle callback runs while no custom activation is published.
            sessions.resolver()
                    .invoke(
                            SETUP_TOOL,
                            identity.app(),
                            null,
                            sessions.resolver().readStaticField(ARROW_TOOL),
                            false);
            if (!sessions.isCurrent(identity))
                throw new IllegalStateException("modeling target changed during activation");
            final Active candidate = new Active(key, entry, identity, ++activationGeneration);
            active = candidate;
            try {
                entry.tool.activate(candidate);
                if (active != candidate) {
                    candidate.close();
                    return;
                }
                startLifetime();
                changed();
            } catch (RuntimeException | Error failure) {
                if (active == candidate) {
                    try {
                        deactivateOnEdt();
                    } catch (RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                } else candidate.close();
                changed();
                throw failure;
            }
        });
    }

    /** Checks selection state for an exact registration identity. */
    public boolean isActive(String pluginId, long pluginGeneration, String toolId) {
        return EdtDispatch.call(
                "modeling tool state",
                () -> active != null && active.key.equals(new Key(pluginId, pluginGeneration, toolId)));
    }

    /** Stops the matching activation without touching another plugin generation. */
    public void deactivate(String pluginId, long pluginGeneration, String toolId) {
        edtEventually(() -> {
            if (active != null && active.key.equals(new Key(pluginId, pluginGeneration, toolId))) deactivateOnEdt();
        });
    }

    /** Receives normal native tool requests, including re-selection of the existing tool. */
    public void nativeToolActivated(Object app) {
        edtEventually(() -> {
            if (active != null && active.identity.app() == app) deactivateOnEdt();
        });
    }

    /** Revokes the activation only when its document moves to a different edit-mode identity. */
    public void modeChanged(Object document, Object mode) {
        edtEventually(() -> {
            if (active != null && active.identity.document() == document && active.identity.mode() != mode)
                deactivateOnEdt();
            changed();
        });
    }

    /** Subscribes reversible toolbar-state synchronization to serialized coordinator changes. */
    public Registration onStateChanged(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    /** Removes native subscriptions, lifetime polling and the current activation. */
    public void disconnect() {
        edtEventually(this::disconnectOnEdt);
    }

    private void disconnectOnEdt() {
        deactivateOnEdt();
        if (lifetime != null) {
            lifetime.stop();
            lifetime = null;
        }
        if (viewListeners != null && viewCallback != null) viewListeners.removeIf(value -> value == viewCallback);
        viewListeners = null;
        viewCallback = null;
        lifecycleReady = false;
        sessions = null;
        changed();
    }

    private void startLifetime() {
        if (sessions == null || tools.isEmpty()) return;
        if (lifetime != null) lifetime.stop();
        lifetime = new Timer(120, event -> {
            try {
                if (active != null && !current(active)) deactivateOnEdt();
                final boolean next = isEligible();
                if (eligible != next) {
                    eligible = next;
                    changed();
                }
            } catch (Throwable failure) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
                deactivateOnEdt();
            }
        });
        lifetime.start();
    }

    private boolean current(Active candidate) {
        return !closed
                && lifecycleReady
                && active == candidate
                && !candidate.closed
                && sessions != null
                && sessions.isCurrent(candidate.identity);
    }

    private void deactivateOnEdt() {
        final Active stale = active;
        active = null;
        if (stale != null) {
            try {
                stale.close();
            } finally {
                try {
                    stale.entry.tool.deactivate();
                } catch (Throwable failure) {
                    dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
                }
                try {
                    restoreNativeButtons();
                } catch (Throwable failure) {
                    dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
                    dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                            "modeling-tool",
                            "native highlight restore failed: "
                                    + failure.getClass().getSimpleName());
                } finally {
                    changed();
                }
            }
        }
    }

    private void changed() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable failure) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            }
        }
    }

    /** Restores host-owned toolbar highlights after an ordinary tool ends. */
    public void restoreNativeButtons() {
        edtEventually(() -> {
            if (sessions == null || active != null) return;
            final var resolver = sessions.resolver();
            final Object app = resolver.invokeStatic(APP);
            resolver.invoke(UPDATE_TOOL_BUTTONS, resolver.invoke(MAIN_FRAME, app), resolver.invoke(TOOL_GROUP, app));
        });
    }

    @Override
    public void close() {
        edtEventually(() -> {
            if (closed) return;
            closed = true;
            disconnectOnEdt();
            tools.clear();
            listeners.clear();
        });
    }

    private static void edt(Runnable action) {
        EdtDispatch.call("modeling tool transition", () -> {
            action.run();
            return null;
        });
    }

    /**
     * Idempotent revocation/teardown work: on acceptance timeout the task stays queued and still
     * runs exactly once when the EDT drains, so a stopped tool never keeps its activation,
     * subscriptions, or native highlight cleanup from running.
     */
    private static void edtEventually(Runnable action) {
        EdtDispatch.runEventually("modeling tool EDT cleanup", action);
    }

    private record Key(String pluginId, long pluginGeneration, String toolId) {}

    private record Entry(ModelingTool tool, PermissionChecker permissions) {}

    private final class Active implements ModelingToolContext, AutoCloseable {
        private final Key key;
        private final Entry entry;
        private final NativeModelingToolSessionResolver.Identity identity;
        private final long activation;
        private RuntimeModelingBrush brush;
        private boolean closed;

        private Active(Key key, Entry entry, NativeModelingToolSessionResolver.Identity identity, long activation) {
            this.key = key;
            this.entry = entry;
            this.identity = identity;
            this.activation = activation;
        }

        @Override
        public ModelingBrush selectionBrush() {
            return EdtDispatch.call("modeling brush creation", () -> {
                if (!current(this) || activation != activationGeneration)
                    throw new IllegalStateException("modeling activation is stale");
                if (brush == null) {
                    final RuntimeModelingBrush candidate = new RuntimeModelingBrush(
                            sessions,
                            identity,
                            () -> current(this),
                            () -> {
                                if (active == this) deactivateOnEdt();
                            },
                            entry.tool,
                            entry.permissions);
                    try {
                        candidate.install();
                        brush = candidate;
                    } catch (RuntimeException | Error failure) {
                        candidate.close();
                        throw failure;
                    }
                }
                return brush;
            });
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (brush != null) brush.close();
        }
    }
}
