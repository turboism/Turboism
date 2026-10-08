package dev.turboism.ui.mesh;

import dev.turboism.adapter.cubism.mesh.MeshToolCoordinator;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EdtDispatch;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import java.awt.event.ActionListener;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.ImageIcon;

/** Exact-version mesh-toolbar operations restricted to verified aliases. */
public final class VerifiedMeshToolbarHostOperations implements MeshToolbarHostOperations {

    private static final String MESH_TOOL_MODE_INSTANCE = "cubism.ui-main-toolbar.mesh-tool-mode.instance";
    private static final String MESH_TOOL_MODE_TOOL_PANEL = "cubism.ui-main-toolbar.mesh-tool-mode.tool-panel";
    private static final String MESH_TOOL_PANEL_ARROW_BUTTON = "cubism.ui-main-toolbar.mesh-tool-panel.arrow-button";
    private static final String ABSTRACT_BUTTON_SET_SELECTED = "cubism.ui-main-toolbar.abstract-button.set-selected";
    private static final String ABSTRACT_BUTTON_CLASS = "cubism.ui-main-toolbar.abstract-button.class";
    private static final String ABSTRACT_BUTTON_GET_JABSTRACT_BUTTON =
            "cubism.ui-main-toolbar.abstract-button.get-jabstract-button";
    private static final String ABSTRACT_BUTTON_SET_ICON = "cubism.ui-main-toolbar.abstract-button.set-icon";
    private static final String ABSTRACT_BUTTON_SET_PRESSED_ICON =
            "cubism.ui-main-toolbar.abstract-button.set-pressed-icon";
    private static final String ABSTRACT_BUTTON_SET_SELECTED_ICON =
            "cubism.ui-main-toolbar.abstract-button.set-selected-icon";
    private static final String ABSTRACT_BUTTON_SET_DISABLED_ICON =
            "cubism.ui-main-toolbar.abstract-button.set-disabled-icon";
    private static final String ABSTRACT_BUTTON_SET_DISABLED_SELECTED_ICON =
            "cubism.ui-main-toolbar.abstract-button.set-disabled-selected-icon";
    private static final String WIDGET_PARENT = "cubism.ui-main-toolbar.widget.parent";
    private static final String WIDGET_NAME = "cubism.ui-main-toolbar.widget.name";
    private static final String WIDGET_SET_NAME = "cubism.ui-main-toolbar.widget.set-name";
    private static final String WIDGET_SET_TOOLTIP = "cubism.ui-main-toolbar.widget.set-tooltip";
    private static final String WIDGET_SET_PREF_WIDTH = "cubism.ui-main-toolbar.widget.set-pref-width";
    private static final String WIDGET_SET_PREF_HEIGHT = "cubism.ui-main-toolbar.widget.set-pref-height";
    private static final String WIDGET_REVALIDATE = "cubism.ui-main-toolbar.widget.revalidate";
    private static final String WIDGET_REPAINT = "cubism.ui-main-toolbar.widget.repaint";
    private static final String CONTAINER_CHILDREN = "cubism.ui-main-toolbar.container.children";
    private static final String CONTAINER_ADD = "cubism.ui-main-toolbar.container.add";
    private static final String CONTAINER_REMOVE = "cubism.ui-main-toolbar.container.remove";
    private static final String ICON_BUTTON_CREATE = "cubism.ui-main-toolbar.icon-button.create";
    private static final String ICON_BUTTON_SET_ROLLOVER_ICON = "cubism.ui-main-toolbar.icon-button.set-rollover-icon";
    private static final String ICON_CLASS = "cubism.ui-main-toolbar.icon.class";
    private static final String ICON_CREATE = "cubism.ui-main-toolbar.icon.create";
    private static final String MESH_BUTTON_PREFIX = "turboism:mesh:";
    private static final String MESH_TOOL_PANEL_PANEL = "cubism.ui-main-toolbar.mesh-tool-panel.panel";
    private static final String SLIDER_CLASS = "cubism.ui-main-toolbar.slider.class";
    private static final String SLIDER_CREATE = "cubism.ui-main-toolbar.slider.create";
    private static final String SLIDER_VALUE = "cubism.ui-main-toolbar.slider.value";
    private static final String SLIDER_SET_VALUE = "cubism.ui-main-toolbar.slider.set-value";
    private static final String SLIDER_MIN = "cubism.ui-main-toolbar.slider.min";
    private static final String SLIDER_SET_MIN = "cubism.ui-main-toolbar.slider.set-min";
    private static final String SLIDER_MAX = "cubism.ui-main-toolbar.slider.max";
    private static final String SLIDER_SET_MAX = "cubism.ui-main-toolbar.slider.set-max";
    private static final String SLIDER_SET_ON_CHANGED = "cubism.ui-main-toolbar.slider.set-on-changed";
    private static final String MESH_SLIDER_PREFIX = "turboism:mesh-slider:";

    private final VerifiedMemberResolver resolver;
    private final EditorUiPluginResourceRegistry resources;
    private final MeshToolCoordinator coordinator;

    /** One framework-owned native-tool observer per mesh-toolbar host session. All session
     *  fields below are owned by the EDT: every install/close path serializes through
     *  {@link #onEdt}, so plain fields suffice; only the per-handle idempotence stays atomic. */
    private boolean peersCaptured;

    private boolean observerBound;
    private int liveButtons;
    private List<NativePeer> nativePeers = List.of();
    private ActionListener nativeCallback;

    /**
     * One captured pre-existing native mesh-toolbar entry: the exact host wrapper (the verified
     * {@code CAbstractButton} child, whose selected state is mutated through the verified
     * {@code setSelected} seam) paired with its unwrapped Swing {@link AbstractButton} peer (the
     * real native event source the session observer binds to). Both identities stay paired for
     * the whole host session; a wrapper is never mutated through its inner Swing model and a
     * peer is never treated as the wrapper.
     */
    private record NativePeer(Object wrapper, AbstractButton peer) {
        private NativePeer {
            Objects.requireNonNull(wrapper, "wrapper");
            Objects.requireNonNull(peer, "peer");
        }
    }

    public VerifiedMeshToolbarHostOperations(
            final VerifiedMemberResolver resolver,
            final EditorUiPluginResourceRegistry resources,
            final MeshToolCoordinator coordinator) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public ButtonHandle addButton(final MeshToolbarContributionDescriptor contribution, final Runnable action) {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(action, "action");
        return onEdt(() -> installButton(contribution, action));
    }

    @Override
    public Registration onRebuild(final Runnable reconcile) {
        return coordinator.onStateChanged(Objects.requireNonNull(reconcile, "reconcile"));
    }

    @Override
    public SliderHandle addSlider(
            final MeshToolbarSliderContributionDescriptor contribution,
            final java.util.function.IntConsumer onChanged) {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(onChanged, "onChanged");
        return onEdt(() -> installSlider(contribution, onChanged));
    }

    private SliderHandle installSlider(
            final MeshToolbarSliderContributionDescriptor contribution,
            final java.util.function.IntConsumer onChanged) {
        final Object panel = resolvePanel();
        final List<?> children = children(panel);
        final String nativeId = MESH_SLIDER_PREFIX
                + contribution.pluginId()
                + ":"
                + contribution.pluginGeneration()
                + ":"
                + contribution.controlId();
        if (children.stream().anyMatch(widget -> nativeId.equals(resolver.invoke(WIDGET_NAME, widget)))) {
            throw new IllegalStateException("mesh-toolbar slider contribution is already materialized");
        }

        final Object slider = resolver.construct(
                SLIDER_CREATE, 0, contribution.minimum(), contribution.maximum(), contribution.currentValue());
        if (!resolver.isInstance(SLIDER_CLASS, slider)) {
            throw new IllegalStateException("mesh-toolbar slider has an unverified type");
        }
        resolver.invoke(SLIDER_SET_MIN, slider, contribution.minimum());
        resolver.invoke(SLIDER_SET_MAX, slider, contribution.maximum());
        resolver.invoke(SLIDER_SET_VALUE, slider, contribution.currentValue());
        resolver.invoke(WIDGET_SET_NAME, slider, nativeId);
        resolver.invoke(WIDGET_SET_TOOLTIP, slider, contribution.label());
        resolver.invoke(WIDGET_SET_PREF_WIDTH, slider, 140);
        resolver.invoke(WIDGET_SET_PREF_HEIGHT, slider, 24);
        final Object callback = resolver.createFunctionalArgumentProxy(SLIDER_SET_ON_CHANGED, 0, ignored -> {
            onChanged.accept(valueOf(slider));
            return kotlinUnit();
        });
        resolver.invoke(SLIDER_SET_ON_CHANGED, slider, callback);

        resolver.invoke(CONTAINER_ADD, panel, slider, children.size());
        refresh(panel);

        final AtomicBoolean closed = new AtomicBoolean();
        return new SliderHandle() {
            @Override
            public int value() {
                return onEdt(() -> valueOf(slider));
            }

            @Override
            public void setValue(final int value) {
                onEdt(() -> {
                    resolver.invoke(SLIDER_SET_VALUE, slider, value);
                    return null;
                });
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                onEdtEventually(() -> {
                    resolver.invoke(CONTAINER_REMOVE, panel, slider);
                    refresh(panel);
                });
            }
        };
    }

    private int valueOf(final Object slider) {
        final Object value = resolver.invoke(SLIDER_VALUE, slider);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("mesh-toolbar slider value is unavailable");
        }
        return number.intValue();
    }

    private Object resolvePanel() {
        final Object instance = resolver.readStaticField(MESH_TOOL_MODE_INSTANCE);
        if (instance == null) {
            throw new IllegalStateException("mesh-toolbar tool mode is unavailable");
        }
        final Object panel = resolver.invoke(MESH_TOOL_MODE_TOOL_PANEL, instance);
        if (panel == null) {
            throw new IllegalStateException("mesh-toolbar tool panel is unavailable");
        }
        final Object container = resolver.invoke(MESH_TOOL_PANEL_PANEL, panel);
        if (container == null) {
            throw new IllegalStateException("mesh-toolbar slider anchor container is unavailable");
        }
        return container;
    }

    private ButtonHandle installButton(final MeshToolbarContributionDescriptor contribution, final Runnable action) {
        final Object arrow = resolveArrow();
        final Object container = resolver.invoke(WIDGET_PARENT, arrow);
        if (container == null) {
            throw new IllegalStateException("mesh-toolbar anchor parent is unavailable");
        }
        final List<?> children = children(container);
        final int arrowIndex = identityIndexOf(children, arrow);
        if (arrowIndex < 0) {
            throw new IllegalStateException("mesh-toolbar anchor is not a child of its parent");
        }

        final String nativeId = MESH_BUTTON_PREFIX
                + contribution.pluginId()
                + ":"
                + contribution.pluginGeneration()
                + ":"
                + contribution.toolId();
        if (children.stream().anyMatch(widget -> nativeId.equals(resolver.invoke(WIDGET_NAME, widget)))) {
            throw new IllegalStateException("mesh-toolbar contribution is already materialized");
        }

        final Icon normal =
                icon(contribution.pluginId(), contribution.pluginGeneration(), contribution.iconResourcePath());
        final Icon active =
                icon(contribution.pluginId(), contribution.pluginGeneration(), contribution.activeIconResourcePath());
        final Icon rollOver =
                icon(contribution.pluginId(), contribution.pluginGeneration(), contribution.rollOverIconResourcePath());
        final Icon selected =
                icon(contribution.pluginId(), contribution.pluginGeneration(), contribution.selectedIconResourcePath());
        final Icon disabled =
                icon(contribution.pluginId(), contribution.pluginGeneration(), contribution.disabledIconResourcePath());
        final Icon disabledSelected = icon(
                contribution.pluginId(),
                contribution.pluginGeneration(),
                contribution.disabledSelectedIconResourcePath());
        final Object callback = resolver.createFunctionalConstructorArgumentProxy(ICON_BUTTON_CREATE, 1, ignored -> {
            action.run();
            return kotlinUnit();
        });
        final Object button = resolver.construct(ICON_BUTTON_CREATE, normal, callback);

        // The host button is a CAbstractButton-derived wrapper, never a Swing component. The
        // six exact state icons are installed through the verified CAbstractButton CIcon state
        // setters: one distinct host CIcon value per owned 32x32 resource, constructed through
        // the verified CIcon(javax.swing.Icon) alias. Direct Swing icon slots are not the native
        // state contract, and any type/conversion/setter failure fails closed before any host
        // state change or container insertion.
        if (!resolver.isInstance(ABSTRACT_BUTTON_CLASS, button)) {
            throw new IllegalStateException("mesh-toolbar button has an unverified host type");
        }
        final Object unwrapped = resolver.invoke(ABSTRACT_BUTTON_GET_JABSTRACT_BUTTON, button);
        if (!(unwrapped instanceof AbstractButton swingButton)) {
            throw new IllegalStateException("mesh-toolbar button does not expose a Swing AbstractButton");
        }
        resolver.invoke(ABSTRACT_BUTTON_SET_ICON, button, hostIcon(normal));
        resolver.invoke(ABSTRACT_BUTTON_SET_PRESSED_ICON, button, hostIcon(active));
        resolver.invoke(ICON_BUTTON_SET_ROLLOVER_ICON, button, hostIcon(rollOver));
        resolver.invoke(ABSTRACT_BUTTON_SET_SELECTED_ICON, button, hostIcon(selected));
        resolver.invoke(ABSTRACT_BUTTON_SET_DISABLED_ICON, button, hostIcon(disabled));
        resolver.invoke(ABSTRACT_BUTTON_SET_DISABLED_SELECTED_ICON, button, hostIcon(disabledSelected));
        swingButton.setRolloverEnabled(true);
        resolver.invoke(WIDGET_SET_NAME, button, nativeId);
        resolver.invoke(WIDGET_SET_TOOLTIP, button, contribution.label());
        // No forced footprint: the host icon button adopts the native mesh-toolbar row sizing
        // exactly like its adjacent native peers; the six owned 32x32 rasters stay the baseline.

        // Native peer-tool mutual exclusion: every native unwrapped Swing AbstractButton peer
        // already present in the verified container (including the first arrow peer) is captured
        // BEFORE any Turboism contribution is inserted, and one framework-owned observer object
        // with a single common callback is bound once per mesh-toolbar host session. The real
        // host activates its native wrapper through the inner Swing button's ActionEvent, so
        // mouse, keyboard and programmatic native activation share one route: at native
        // activation the observer captures the exact custom-tool identity active at that moment
        // and queues exactly one exact-identity deactivation after the current EDT action turn;
        // a stale signal can never cancel a later custom tool, and the coordinator state-change
        // chain then reconciles the exact selection state of every contribution button in place.
        // The preflight also validates every existing native wrapper before the contribution is
        // inserted, so a broken peer fails closed before any visible contribution or listener
        // side effect.
        captureNativePeers(container, arrow);

        resolver.invoke(CONTAINER_ADD, container, button, meshInsertionIndex(children, arrowIndex));
        refresh(container);

        try {
            if (liveButtons == 0) {
                // First contribution of this host session: bind the session observer
                // transactionally, publishing bound/count state only after every listener is
                // installed, so a partial binding failure is rolled back and stays retryable.
                bindSessionObserver();
                liveButtons = 1;
            } else {
                liveButtons++;
            }
        } catch (RuntimeException | Error failure) {
            // Roll back the just-inserted contribution: the binding failed, so no handle must
            // be returned and the container must not keep a button with no working observer.
            try {
                resolver.invoke(CONTAINER_REMOVE, container, button);
                refresh(container);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }

        final AtomicBoolean closed = new AtomicBoolean();
        return new ButtonHandle() {
            @Override
            public void setSelected(final boolean selected) {
                onEdt(() -> {
                    if (selected) {
                        // Native/custom visual exclusivity, framework side: before any
                        // Turboism custom handle is shown selected, every captured pre-existing
                        // native wrapper is cleared through the same verified host
                        // {@code setSelected(Z)} seam. Applying {@code selected=false} to a
                        // Turboism handle never restores a native highlight (the user chose no
                        // restoration on voluntary exit), and a newly clicked native tool
                        // keeps whatever state Cubism itself set: the session observer only
                        // deactivates the exact custom tool, it never touches native state.
                        clearNativeSelections();
                    }
                    resolver.invoke(ABSTRACT_BUTTON_SET_SELECTED, button, selected);
                    return null;
                });
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                onEdtEventually(() -> {
                    // The session-observer bookkeeping must run even when the contribution
                    // removal fails: a throwing removal must never leak the listener binding.
                    // The primary removal/refresh failure (RuntimeException or Error) is held
                    // unchanged and rethrown with its original identity; any cleanup failure is
                    // suppressed onto that same object, exactly once.
                    Throwable primary = null;
                    try {
                        resolver.invoke(CONTAINER_REMOVE, container, button);
                        refresh(container);
                    } catch (RuntimeException | Error failure) {
                        primary = failure;
                    }
                    try {
                        if (liveButtons > 0) {
                            liveButtons--;
                        }
                        if (liveButtons == 0) {
                            unbindSessionObserver();
                        }
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (primary != null) {
                            primary.addSuppressed(cleanupFailure);
                        } else {
                            throw cleanupFailure;
                        }
                    }
                    if (primary instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    if (primary instanceof Error errorFailure) {
                        throw errorFailure;
                    }
                    if (primary != null) {
                        throw new RuntimeException("mesh-toolbar contribution removal failed", primary);
                    }
                });
            }
        };
    }

    /**
     * Preflight: classifies every native {@code CAbstractButton} wrapper already present in the
     * verified container, including the first arrow peer (the exact-host evidence proves the
     * arrow's unwrapped peer is the real native event source), and excludes every widget whose
     * name starts with the Turboism mesh-button prefix (a Turboism contribution must never be
     * mistaken for a native peer tool), unwrapping each wrapper through the verified
     * {@code getJAbstractButton} seam. A wrapper that does not expose a Swing
     * {@link AbstractButton} fails closed here, before any container insertion or listener side
     * effect.
     */
    private List<NativePeer> resolveNativePeers(final Object container, final Object arrow) {
        final ArrayList<NativePeer> peers = new ArrayList<>();
        for (Object child : children(container)) {
            if (!resolver.isInstance(ABSTRACT_BUTTON_CLASS, child)) {
                continue;
            }
            // The widget-system name is read through the verified alias (the host widget name
            // is not necessarily the Swing component name), so the exclusion matches exactly
            // what {@code WIDGET_SET_NAME} wrote.
            final Object name = resolver.invoke(WIDGET_NAME, child);
            if (name instanceof String value && value.startsWith(MESH_BUTTON_PREFIX)) {
                continue;
            }
            final Object unwrapped = resolver.invoke(ABSTRACT_BUTTON_GET_JABSTRACT_BUTTON, child);
            if (!(unwrapped instanceof AbstractButton peer)) {
                throw new IllegalStateException("mesh-toolbar native peer does not expose a Swing AbstractButton");
            }
            peers.add(new NativePeer(child, peer));
        }
        // The arrow anchor's exact unwrapped peer must be part of the captured native set
        // (exact-host evidence: it is the real native event source). If exact classification
        // ever drifts so the arrow is not captured, fail closed before any insertion or
        // listener side effect instead of silently losing the native switch signal.
        final Object unwrappedArrow = resolver.invoke(ABSTRACT_BUTTON_GET_JABSTRACT_BUTTON, arrow);
        if (!(unwrappedArrow instanceof AbstractButton arrowPeer) || !identityContains(peers, arrowPeer)) {
            throw new IllegalStateException(
                    "mesh-toolbar native arrow peer is missing from the captured native peer set");
        }
        return List.copyOf(peers);
    }

    private static boolean identityContains(final List<NativePeer> peers, final AbstractButton peer) {
        for (NativePeer captured : peers) {
            if (captured.peer() == peer) {
                return true;
            }
        }
        return false;
    }

    /**
     * Captures the pre-existing native peer set exactly once per mesh-toolbar host session,
     * before any Turboism contribution of this session is inserted. Turboism-owned siblings are
     * excluded by construction: peers are resolved before their insertion and any widget whose
     * name carries the Turboism mesh-button prefix is skipped.
     */
    private void captureNativePeers(final Object container, final Object arrow) {
        if (peersCaptured) {
            return;
        }
        // Resolve and validate first, then publish: a broken native wrapper must fail closed
        // and leave the session retryable, never cached as an empty fail-open peer set.
        final List<NativePeer> resolved = resolveNativePeers(container, arrow);
        nativePeers = resolved;
        peersCaptured = true;
    }

    /**
     * Clears selected state on every captured pre-existing native host wrapper through the
     * verified {@code CAbstractButton.setSelected(Z)} seam, never through the inner Swing
     * model. Runs before a Turboism custom handle is shown selected; a throwing wrapper fails
     * closed before any custom selected state is applied. It never re-selects and never
     * restores: the user chose no restoration of a prior native highlight on voluntary custom
     * exit, so this is the only direction native state is ever changed by the framework.
     */
    private void clearNativeSelections() {
        for (NativePeer nativePeer : nativePeers) {
            resolver.invoke(ABSTRACT_BUTTON_SET_SELECTED, nativePeer.wrapper(), Boolean.FALSE);
        }
    }

    /**
     * Binds the single framework-owned observer callback to every captured native peer exactly
     * once per mesh-toolbar host session. Swing dispatches the most recently installed listener
     * first, so this observer revokes the current custom activation before Cubism's pre-existing
     * native listener continues. The callback never guesses, activates, selects, or restores a
     * native tool, and plugin callback failures are contained at this host ingress.
     */
    private void bindSessionObserver() {
        if (observerBound) {
            return;
        }
        final ActionListener callback = event -> {
            try {
                coordinator.nativeToolActivated();
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
                // Native Cubism action ingress must remain non-throwing.
            }
        };
        // Transactional: publish the bound state only after every listener is installed; a
        // partial failure removes the listeners already bound and rethrows so the caller can
        // roll the just-inserted contribution back.
        try {
            for (NativePeer nativePeer : nativePeers) {
                nativePeer.peer().addActionListener(callback);
            }
        } catch (RuntimeException | Error failure) {
            for (NativePeer nativePeer : nativePeers) {
                try {
                    nativePeer.peer().removeActionListener(callback);
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        nativeCallback = callback;
        observerBound = true;
    }

    /** Removes the session observer callback from every captured peer exactly once. */
    private void unbindSessionObserver() {
        if (!observerBound) {
            return;
        }
        // Attempt every captured peer: one throwing peer must never leave later peers bound.
        // The first unchecked removal failure is preserved with its original identity and any
        // subsequent failures are suppressed onto it; the published observer state is cleared
        // after all removal attempts so the session stays retryable.
        Throwable first = null;
        for (NativePeer nativePeer : nativePeers) {
            try {
                nativePeer.peer().removeActionListener(nativeCallback);
            } catch (RuntimeException | Error failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        nativeCallback = null;
        observerBound = false;
        if (first instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (first instanceof Error errorFailure) {
            throw errorFailure;
        }
    }

    private Object resolveArrow() {
        final Object instance = resolver.readStaticField(MESH_TOOL_MODE_INSTANCE);
        if (instance == null) {
            throw new IllegalStateException("mesh-toolbar tool mode is unavailable");
        }
        final Object panel = resolver.invoke(MESH_TOOL_MODE_TOOL_PANEL, instance);
        if (panel == null) {
            throw new IllegalStateException("mesh-toolbar tool panel is unavailable");
        }
        final Object arrow = resolver.invoke(MESH_TOOL_PANEL_ARROW_BUTTON, panel);
        if (arrow == null) {
            throw new IllegalStateException("mesh-toolbar anchor button is unavailable");
        }
        return arrow;
    }

    private List<?> children(final Object container) {
        final Object result = resolver.invoke(CONTAINER_CHILDREN, container);
        if (!(result instanceof List<?> list)) {
            throw new IllegalStateException("mesh-toolbar container children are unavailable");
        }
        return List.copyOf(list);
    }

    private int meshInsertionIndex(final List<?> children, final int arrowIndex) {
        int index = arrowIndex + 1;
        while (index < children.size()) {
            final Object name = resolver.invoke(WIDGET_NAME, children.get(index));
            if (!(name instanceof String value) || !value.startsWith(MESH_BUTTON_PREFIX)) {
                break;
            }
            index++;
        }
        return index;
    }

    private static int identityIndexOf(final List<?> values, final Object target) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == target) {
                return index;
            }
        }
        return -1;
    }

    private Icon icon(final String pluginId, final long pluginGeneration, final String path) {
        final URL resource = resources
                .resource(pluginId, pluginGeneration, path)
                .orElseThrow(() -> new IllegalStateException("mesh-toolbar icon resource is unavailable"));
        return new ImageIcon(resource);
    }

    /**
     * Constructs one host {@code CIcon} value from the given owned Swing icon through the
     * verified {@code icon.create} alias and verifies its exact host type; any mismatch fails
     * closed before container insertion.
     */
    private Object hostIcon(final Icon icon) {
        final Object value = resolver.construct(ICON_CREATE, icon);
        if (!resolver.isInstance(ICON_CLASS, value)) {
            throw new IllegalStateException("mesh-toolbar icon has an unverified host type");
        }
        return value;
    }

    private Object kotlinUnit() {
        try {
            final Class<?> unit = Class.forName("kotlin.Unit", false, resolver.hostClassLoader());
            return unit.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Kotlin Unit is unavailable for mesh-toolbar callback", exception);
        }
    }

    private void refresh(final Object container) {
        resolver.invoke(WIDGET_REVALIDATE, container);
        resolver.invoke(WIDGET_REPAINT, container);
    }

    private static <T> T onEdt(final Operation<T> operation) {
        return EdtDispatch.call("mesh-toolbar EDT operation", operation::run);
    }

    /**
     * Idempotent removal work: on acceptance timeout the task stays queued and still runs
     * exactly once when the EDT drains, so a closed contribution is never orphaned and the
     * session observer is never leaked.
     */
    private static void onEdtEventually(final Runnable operation) {
        EdtDispatch.runEventually("mesh-toolbar EDT removal", operation);
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
