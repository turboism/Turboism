package dev.turboism.plugin.geometricmeshlayout;

import dev.turboism.sdk.cubism.mesh.MeshCanvasSession;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;
import java.util.Objects;

/**
 * Geometric Mesh Layout tool: the plugin half of the point-and-edge authoring capability.
 *
 * <p>The tool owns the parameter state, the configuration dialog and the commit policy. The runtime
 * owns the canvas: it provides the {@link MeshCanvasSession} that follows the anchor, paints the
 * preview and commits the layout as one Undo entry. Anchor placement, Escape handling and the
 * overlay lifetime therefore never appear in this class.</p>
 */
final class GeometricMeshLayoutTool implements MeshTool {
    static final String TOOL_ID = "geometric-mesh-layout";
    private static final int TOOL_ORDER = 120;
    private static final Duration TEARDOWN_TIMEOUT = Duration.ofSeconds(2);

    private final Object monitor = new Object();
    /** Configuration survives tool re-activation, so the operator keeps the shape they last used. */
    private volatile GeometricMeshConfig sharedConfig = GeometricMeshConfig.defaults();

    private final PluginContext context;
    private final PluginLogger logger;
    private final PluginLocalization localization;
    private Activation active;
    private boolean closed;

    GeometricMeshLayoutTool(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.localization = context.localization();
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String label() {
        return localization.text("mesh.geometric-layout.label", "Geometric Mesh Layout");
    }

    @Override
    public String iconResourcePath() {
        return "icons/geometric-mesh-layout.png";
    }

    @Override
    public String activeIconResourcePath() {
        return "icons/geometric-mesh-layout-active.png";
    }

    @Override
    public String rollOverIconResourcePath() {
        return "icons/geometric-mesh-layout-rollover.png";
    }

    @Override
    public String selectedIconResourcePath() {
        return "icons/geometric-mesh-layout-selected.png";
    }

    @Override
    public String disabledIconResourcePath() {
        return "icons/geometric-mesh-layout-disabled.png";
    }

    @Override
    public String disabledSelectedIconResourcePath() {
        return "icons/geometric-mesh-layout-disabled-selected.png";
    }

    @Override
    public int order() {
        return TOOL_ORDER;
    }

    @Override
    public void activate(final MeshToolContext toolContext) {
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("geometric mesh layout tool is closed");
        }
        final MeshCanvasSession session = Objects.requireNonNull(
                Objects.requireNonNull(toolContext, "toolContext").canvasSession(), "canvasSession");
        final Activation activation = new Activation(session, sharedConfig);
        try {
            activation.schedule();
        } catch (RuntimeException | Error failure) {
            activation.close();
            throw failure;
        }
        final Activation previous;
        synchronized (monitor) {
            if (closed) {
                activation.close();
                throw new IllegalStateException("geometric mesh layout tool is closed");
            }
            previous = active;
            active = activation;
        }
        if (previous != null) previous.close();
    }

    @Override
    public void deactivate() {
        closeActive();
    }

    /** Ends the current activation without retiring the tool; the plugin calls this on disable. */
    void deactivateActivation() {
        closeActive();
    }

    /** Releases the dialog and the canvas session; the plugin calls this on shutdown. */
    void close() {
        synchronized (monitor) {
            if (closed) return;
            closed = true;
        }
        closeActive();
    }

    /** Test seam: the dialog of the current activation, or {@code null} when none is installed. */
    GeometricMeshLayoutDialog dialogForTests() {
        final Activation current;
        synchronized (monitor) {
            current = active;
        }
        return current == null ? null : current.dialog;
    }

    private void closeActive() {
        final Activation current;
        synchronized (monitor) {
            current = active;
            active = null;
        }
        if (current != null) current.close();
    }

    /**
     * One tool activation: the canvas session plus the dialog that configures it.
     *
     * <p>The dialog is created on the UI thread by {@link #schedule()}; every later member runs from
     * its listeners on that same thread.</p>
     */
    private final class Activation {
        private final MeshCanvasSession session;
        private GeometricMeshConfig config;
        private GeometricMeshLayoutDialog dialog;
        private Registration anchorObservation;
        private Registration scheduled;
        private boolean closed;
        /** Mirrors the previous point-and-edge brush: a commit disarms the preview until the next anchor. */
        private boolean previewArmed = true;

        Activation(final MeshCanvasSession session, final GeometricMeshConfig config) {
            this.session = session;
            this.config = config;
        }

        void schedule() {
            scheduled = context.uiScheduler().runOnUiThread(this::install);
        }

        private void install() {
            if (closed) return;
            try {
                final GeometricMeshLayoutDialog candidate =
                        new GeometricMeshLayoutDialog(config, session.anchor(), listener(), localization);
                dialog = candidate;
                anchorObservation = session.observeAnchorMoved(this::anchorMoved);
                refreshPreview();
                candidate.show();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                logger.error("failed to open the geometric mesh layout dialog", failure);
                abandon();
            }
        }

        private GeometricMeshLayoutDialog.Listener listener() {
            return new GeometricMeshLayoutDialog.Listener() {
                @Override
                public void onConfigChanged(final GeometricMeshConfig updated) {
                    configChanged(updated);
                }

                @Override
                public void onConfirm(final GeometricMeshConfig updated) {
                    confirm(updated);
                }

                @Override
                public void onCancel() {
                    cancel();
                }
            };
        }

        private void anchorMoved(final Point2 anchor) {
            if (closed) return;
            previewArmed = true;
            if (dialog != null) {
                dialog.updateAnchor(anchor);
                dialog.show();
            }
            refreshPreview();
        }

        private void configChanged(final GeometricMeshConfig updated) {
            config = updated;
            sharedConfig = updated;
            if (closed) return;
            refreshPreview();
        }

        private void refreshPreview() {
            if (closed || !previewArmed) return;
            try {
                final MeshPointLayout layout = config.toLayoutPlan(session.anchor());
                session.showPreview(layout.points(), layout.edgePairs());
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                logger.error("failed to refresh the geometric mesh layout preview", failure);
            }
        }

        private void confirm(final GeometricMeshConfig updated) {
            config = updated;
            sharedConfig = updated;
            if (closed) return;
            try {
                final MeshPointLayout layout = config.toLayoutPlan(session.anchor());
                session.commit(layout.points(), layout.edgePairs());
                previewArmed = false;
                session.clearPreview();
                if (dialog != null) dialog.hide();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                logger.error("failed to commit the geometric mesh layout", failure);
            }
        }

        private void cancel() {
            if (closed) return;
            try {
                session.clearPreview();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                logger.warn("failed to clear the geometric mesh layout preview: " + failure);
            }
            if (dialog != null) dialog.hide();
            session.deactivate();
        }

        private void abandon() {
            try {
                session.deactivate();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                logger.warn("failed to deactivate the geometric mesh layout tool: " + failure);
            }
            close();
        }

        void close() {
            if (closed) return;
            closed = true;
            final Registration pendingSchedule = scheduled;
            scheduled = null;
            if (pendingSchedule != null) closeQuietly(pendingSchedule);
            final Registration observation = anchorObservation;
            anchorObservation = null;
            if (observation != null) closeQuietly(observation);
            disposeDialog();
            closeQuietly(session);
        }

        private void disposeDialog() {
            final GeometricMeshLayoutDialog current = dialog;
            dialog = null;
            if (current == null) return;
            try {
                context.uiScheduler().runUiTeardown(current::dispose, TEARDOWN_TIMEOUT);
            } catch (RuntimeException failure) {
                logger.warn("failed to schedule the geometric mesh layout dialog teardown: " + failure.getMessage());
            }
        }
    }

    private void closeQuietly(final AutoCloseable handle) {
        try {
            handle.close();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            logger.warn("failed to release a geometric mesh layout handle: " + failure);
        }
    }
}
