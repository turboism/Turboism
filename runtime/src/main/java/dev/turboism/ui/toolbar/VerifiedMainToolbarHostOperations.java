package dev.turboism.ui.toolbar;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.ui.host.EdtDispatch;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.Icon;
import javax.swing.JSeparator;

/** Exact Cubism 5.3.02 main-toolbar operations restricted to verified aliases. */
public final class VerifiedMainToolbarHostOperations implements MainToolbarHostOperations {

    /**
     * Cubism's main-toolbar icons are 32×32 and the owning CHBox supplies its native three-pixel
     * inter-child spacing. Keeping contributions at the same footprint avoids the visibly narrower
     * gap caused by shrinking only plugin buttons to 28×28.
     */
    private static final int NATIVE_BUTTON_SIZE = 32;

    private static final String APP_INSTANCE = "cubism.ui-main-toolbar.app-controller.instance";
    private static final String APP_MAIN_FRAME = "cubism.ui-main-toolbar.app-controller.main-frame";
    private static final String MAIN_FRAME_VIEW = "cubism.ui-main-toolbar.main-frame.view";
    private static final String HOME_BUTTON = "cubism.ui-main-toolbar.main-frame-view.home-button";
    private static final String WIDGET_PARENT = "cubism.ui-main-toolbar.widget.parent";
    private static final String WIDGET_NAME = "cubism.ui-main-toolbar.widget.name";
    private static final String WIDGET_JCOMPONENT = "cubism.ui-main-toolbar.widget.jcomponent";
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
    private static final String ICON_BUTTON_SET_ROLLOVER = "cubism.ui-main-toolbar.icon-button.set-rollover-icon";
    private static final String ICON_CREATE = "cubism.ui-main-toolbar.icon.create";
    private static final String ABSTRACT_BUTTON_PEER = "cubism.ui-main-toolbar.abstract-button.get-jabstract-button";

    private final VerifiedMemberResolver resolver;
    private final EditorUiPluginResourceRegistry resources;
    private final VerifiedMemberResolver modelingResolver;
    private final dev.turboism.adapter.cubism.modeling.ModelingToolCoordinator modeling;
    private final java.util.Map<Object, Object> installedParents = new java.util.IdentityHashMap<>();

    public VerifiedMainToolbarHostOperations(
            final VerifiedMemberResolver resolver, final EditorUiPluginResourceRegistry resources) {
        this(resolver, resources, null, null);
    }

    public VerifiedMainToolbarHostOperations(
            final VerifiedMemberResolver resolver,
            final EditorUiPluginResourceRegistry resources,
            final VerifiedMemberResolver modelingResolver,
            final dev.turboism.adapter.cubism.modeling.ModelingToolCoordinator modeling) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.modelingResolver = modelingResolver;
        this.modeling = modeling;
    }

    @Override
    public Optional<AnchorHandle> anchor(final MainToolbarRegistry.Anchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        if (anchor == MainToolbarRegistry.Anchor.HOST_BRUSH_SELECTION_TOOL) {
            if (!modelingAuthorized()) return Optional.empty();
            return onEdt(() -> Optional.of(new NativeAnchor(resolveBrushButton())));
        }
        return onEdt(() -> Optional.of(new NativeAnchor(resolveHomeButton())));
    }

    private boolean modelingAuthorized() {
        return modeling != null
                && dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.authorizes(modelingResolver);
    }

    @Override
    public Registration onRebuild(final Runnable rebuild) {
        return onEdt(() -> {
            final ToolbarLifecycleWatch watch = new ToolbarLifecycleWatch(
                    this::toolbarIdentity,
                    () -> installedParents.entrySet().stream()
                            .anyMatch(entry -> resolver.invoke(WIDGET_PARENT, entry.getKey()) != entry.getValue()
                                    || !children(entry.getValue()).contains(entry.getKey())),
                    rebuild);
            watch.start();
            return watch;
        });
    }

    private ToolbarIdentity toolbarIdentity() {
        final Object home = resolveHomeButton();
        final Object brush = modelingAuthorized() ? resolveBrushButton() : null;
        return new ToolbarIdentity(
                home,
                resolver.invoke(WIDGET_PARENT, home),
                brush,
                brush == null ? null : resolver.invoke(WIDGET_PARENT, brush));
    }

    private record ToolbarIdentity(Object home, Object homeParent, Object brush, Object brushParent) {}

    private Object resolveBrushButton() {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object frame = resolver.invoke(APP_MAIN_FRAME, app);
        final Object view = resolver.invoke(MAIN_FRAME_VIEW, frame);
        return modelingResolver.readField(
                dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.BRUSH_ANCHOR, view);
    }

    @Override
    public Optional<Registration> addModelingButton(final ModelingToolbarContributionDescriptor contribution) {
        if (!modelingAuthorized()) return Optional.empty();
        return onEdt(() -> {
            final Object anchor = contribution.placement().anchor().isPresent()
                    ? anchor(contribution.placement().anchor().orElseThrow())
                            .map(value -> ((NativeAnchor) value).widget())
                            .orElse(null)
                    : resolveBrushButton();
            if (anchor == null) return Optional.empty();
            return Optional.of(installModelingButton(contribution, anchor));
        });
    }

    private Registration installModelingButton(
            final ModelingToolbarContributionDescriptor descriptor, final Object anchor) {
        final Object container = resolver.invoke(WIDGET_PARENT, anchor);
        final List<?> children = children(container);
        final String nativeId = "turboism:modeling:" + descriptor.pluginId() + ":" + descriptor.pluginGeneration() + ":"
                + descriptor.toolId();
        if (children.stream().anyMatch(widget -> nativeId.equals(resolver.invoke(WIDGET_NAME, widget)))) {
            throw new IllegalStateException("ordinary tool is already materialized");
        }
        final List<Object> icons = descriptor.icons().stream()
                .map(path -> resolver.construct(
                        ICON_CREATE,
                        ToolbarIconLoader.load(resources, descriptor.pluginId(), descriptor.pluginGeneration(), path)))
                .toList();
        final String create = dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.TOGGLE_CREATE;
        final Object callback = modelingResolver.createFunctionalConstructorArgumentProxy(create, 1, ignored -> {
            try {
                modeling.toggle(descriptor.pluginId(), descriptor.pluginGeneration(), descriptor.toolId());
            } catch (Throwable failure) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
                dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                        "modeling-tool",
                        "activation failed: " + failure.getClass().getSimpleName());
            }
            return kotlinUnit();
        });
        final Object button = modelingResolver.construct(create, icons.get(0), callback);
        final String prefix = "cubism.ui-main-toolbar.abstract-button.";
        final Object peer = resolver.invoke(prefix + "get-jabstract-button", button);
        if (!(peer instanceof javax.swing.AbstractButton swingButton)) {
            throw new IllegalStateException("ordinary tool has no verified native button peer");
        }
        resolver.invoke(prefix + "set-icon", button, icons.get(0));
        resolver.invoke(prefix + "set-pressed-icon", button, icons.get(1));
        modelingResolver.invoke(
                dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.TOGGLE_ROLLOVER,
                button,
                icons.get(2));
        resolver.invoke(prefix + "set-selected-icon", button, icons.get(3));
        resolver.invoke(prefix + "set-disabled-icon", button, icons.get(4));
        resolver.invoke(prefix + "set-disabled-selected-icon", button, icons.get(5));
        swingButton.setRolloverEnabled(true);
        // Native tool buttons keep keyboard focus on the canvas. Otherwise Space release toggles
        // this button after a pan, and FlatLaf paints a persistent focus outline around the icon.
        swingButton.setFocusable(false);
        swingButton.setFocusPainted(false);
        swingButton.setBorderPainted(false);
        resolver.invoke(WIDGET_SET_NAME, button, nativeId);
        resolver.invoke(WIDGET_SET_TOOLTIP, button, descriptor.label());
        resolver.invoke(WIDGET_SET_PREF_WIDTH, button, NATIVE_BUTTON_SIZE);
        resolver.invoke(WIDGET_SET_PREF_HEIGHT, button, NATIVE_BUTTON_SIZE);
        final AtomicBoolean closed = new AtomicBoolean();
        final Runnable synchronize = () -> {
            if (closed.get()) return;
            onEdt(() -> {
                final boolean selected =
                        modeling.isActive(descriptor.pluginId(), descriptor.pluginGeneration(), descriptor.toolId());
                resolver.invoke(prefix + "set-selected", button, selected);
                swingButton.setEnabled(selected || modeling.isEligible());
                if (selected) clearNativeToolHighlights();
                return null;
            });
        };
        final List<javax.swing.AbstractButton> nativePeers = nativeToolPeers();
        final Registration nativeState = watchNativeSelection(
                nativePeers,
                () -> modeling.isActive(descriptor.pluginId(), descriptor.pluginGeneration(), descriptor.toolId()),
                this::clearNativeToolHighlights);
        final Registration state = modeling.onStateChanged(synchronize);
        try {
            synchronize.run();
            resolver.invoke(
                    CONTAINER_ADD,
                    container,
                    button,
                    insertionIndex(
                            children,
                            descriptor.placement(),
                            anchor,
                            widget -> resolver.invoke(WIDGET_JCOMPONENT, widget) instanceof JSeparator));
            refresh(container);
            installedParents.put(button, container);
        } catch (RuntimeException | Error failure) {
            state.close();
            nativeState.close();
            try {
                resolver.invoke(CONTAINER_REMOVE, container, button);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            state.close();
            nativeState.close();
            onEdtEventually(() -> {
                // Registrations own activation leases. Reconciliation only replaces the widget;
                // removing an old generation's widget must not deactivate a newer registration.
                resolver.invoke(CONTAINER_REMOVE, container, button);
                installedParents.remove(button);
                refresh(container);
            });
        };
    }

    private List<javax.swing.AbstractButton> nativeToolPeers() {
        return nativeToolButtons().stream()
                .map(widget -> resolver.invoke(ABSTRACT_BUTTON_PEER, widget))
                .map(peerButton -> {
                    if (!(peerButton instanceof javax.swing.AbstractButton abstractButton))
                        throw new IllegalStateException("native tool has no verified button peer");
                    return abstractButton;
                })
                .toList();
    }

    static Registration watchNativeSelection(
            List<javax.swing.AbstractButton> buttons, java.util.function.BooleanSupplier active, Runnable clear) {
        final AtomicBoolean disposed = new AtomicBoolean();
        final boolean[] clearing = {false};
        final javax.swing.event.ChangeListener listener = event -> {
            if (disposed.get()
                    || clearing[0]
                    || !((javax.swing.AbstractButton) event.getSource()).isSelected()
                    || !active.getAsBoolean()) return;
            clearing[0] = true;
            try {
                clear.run();
            } finally {
                clearing[0] = false;
            }
        };
        buttons.forEach(button -> button.addChangeListener(listener));
        return () -> {
            if (!disposed.compareAndSet(false, true)) return;
            onEdtEventually(() -> buttons.forEach(button -> button.removeChangeListener(listener)));
        };
    }

    void clearNativeToolHighlights() {
        clearNativeSelection(nativeToolPeers());
    }

    static void clearNativeSelection(List<javax.swing.AbstractButton> buttons) {
        for (javax.swing.AbstractButton button : buttons) {
            // A grouped ToggleButtonModel rejects setSelected(false) for its current selection.
            // Clear the owning group through the verified Swing peer without changing membership.
            if (button.getModel() instanceof javax.swing.DefaultButtonModel model && model.getGroup() != null)
                model.getGroup().clearSelection();
            button.setSelected(false);
        }
    }

    private List<Object> nativeToolButtons() {
        final Object app = modelingResolver.invokeStatic(
                dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.APP);
        final Object frame = modelingResolver.invoke(
                dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.MAIN_FRAME, app);
        final Object raw = modelingResolver.invoke(
                dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.NATIVE_TOOL_BUTTONS, frame);
        if (!(raw instanceof java.util.Map<?, ?> peers))
            throw new IllegalStateException("native tool buttons are unavailable");
        final java.util.Set<Object> buttons = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        buttons.addAll(peers.values());
        // Modeling and animation register different arrow buttons under the same native group.
        // The animator registration overwrites the map entry, so clear the modeling peer directly.
        final Object view = resolver.invoke(MAIN_FRAME_VIEW, frame);
        final Object modelingArrow = modelingResolver.readField(
                dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.ARROW_BUTTON, view);
        buttons.add(modelingArrow);
        return List.copyOf(buttons);
    }

    @Override
    public Registration addButton(
            final MainToolbarContributionDescriptor contribution,
            final Optional<AnchorHandle> anchor,
            final Runnable action) {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(action, "action");
        return onEdt(() -> installButton(contribution, anchor, action));
    }

    private Registration installButton(
            final MainToolbarContributionDescriptor contribution,
            final Optional<AnchorHandle> anchor,
            final Runnable action) {
        final Object semanticAnchor =
                anchor.map(value -> ((NativeAnchor) value).widget()).orElse(null);
        final Object homeButton = semanticAnchor == null ? resolveHomeButton() : semanticAnchor;
        final Object container = resolver.invoke(WIDGET_PARENT, homeButton);
        if (container == null) {
            throw new IllegalStateException("main-toolbar anchor parent is unavailable");
        }
        final List<?> children = children(container);
        final String nativeId = contribution.pluginId() + ":" + contribution.contributionId();
        if (children.stream().anyMatch(widget -> nativeId.equals(resolver.invoke(WIDGET_NAME, widget)))) {
            throw new IllegalStateException("main-toolbar contribution is already materialized");
        }

        final Icon normal = icon(contribution.pluginId(), contribution.icons().normal());
        final Object callback = resolver.createFunctionalConstructorArgumentProxy(ICON_BUTTON_CREATE, 1, ignored -> {
            action.run();
            return kotlinUnit();
        });
        final Object button = resolver.construct(ICON_BUTTON_CREATE, normal, callback);
        resolver.invoke(WIDGET_SET_NAME, button, nativeId);
        resolver.invoke(WIDGET_SET_TOOLTIP, button, contribution.tooltip());
        resolver.invoke(WIDGET_SET_PREF_WIDTH, button, NATIVE_BUTTON_SIZE);
        resolver.invoke(WIDGET_SET_PREF_HEIGHT, button, NATIVE_BUTTON_SIZE);
        contribution
                .icons()
                .hover()
                .ifPresent(path -> resolver.invoke(
                        ICON_BUTTON_SET_ROLLOVER,
                        button,
                        resolver.construct(ICON_CREATE, icon(contribution.pluginId(), path))));

        final int index = insertionIndex(
                children,
                contribution.placement(),
                homeButton,
                widget -> resolver.invoke(WIDGET_JCOMPONENT, widget) instanceof JSeparator);
        resolver.invoke(CONTAINER_ADD, container, button, index);
        refresh(container);
        installedParents.put(button, container);
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            onEdtEventually(() -> {
                resolver.invoke(CONTAINER_REMOVE, container, button);
                installedParents.remove(button);
                refresh(container);
            });
        };
    }

    private Object resolveHomeButton() {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object mainFrame = resolver.invoke(APP_MAIN_FRAME, app);
        if (mainFrame == null) {
            throw new IllegalStateException("Cubism main frame is not ready");
        }
        final Object view = resolver.invoke(MAIN_FRAME_VIEW, mainFrame);
        if (view == null) {
            throw new IllegalStateException("Cubism main-frame view is not ready");
        }
        final Object home = resolver.invoke(HOME_BUTTON, view);
        if (home == null) {
            throw new IllegalStateException("Cubism home toolbar anchor is not ready");
        }
        return home;
    }

    private List<?> children(final Object container) {
        final Object result = resolver.invoke(CONTAINER_CHILDREN, container);
        if (!(result instanceof List<?> list)) {
            throw new IllegalStateException("main-toolbar container children are unavailable");
        }
        return List.copyOf(list);
    }

    /**
     * Resolves a semantic placement without splitting Cubism's native Home group divider.
     *
     * <p>The exact 5.2.03 and 5.3.02 {@code CEMainFrame.cx} resources place a vertical
     * {@code CSeparator} immediately after the native Home button. AFTER contributions belong on
     * the far side of that host-owned boundary. Later contributions still insert at that same group
     * start, so the authority's ascending order yields the requested right-to-left visual order.</p>
     */
    static int nativeButtonSize() {
        return NATIVE_BUTTON_SIZE;
    }

    static int insertionIndex(
            final List<?> children,
            final MainToolbarRegistry.Placement placement,
            final Object homeButton,
            final java.util.function.Predicate<Object> separator) {
        Objects.requireNonNull(children, "children");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(separator, "separator");
        return switch (placement.position()) {
            case FIRST -> 0;
            case LAST -> -1;
            case BEFORE -> requiredAnchorIndex(children, homeButton);
            case AFTER ->
                placement.anchor().orElse(null) == MainToolbarRegistry.Anchor.HOST_BRUSH_SELECTION_TOOL
                        ? requiredAnchorIndex(children, homeButton) + 1
                        : afterHomeBoundary(children, homeButton, separator);
        };
    }

    private static int afterHomeBoundary(
            final List<?> children, final Object homeButton, final java.util.function.Predicate<Object> separator) {
        final int next = requiredAnchorIndex(children, homeButton) + 1;
        return next < children.size() && separator.test(children.get(next)) ? next + 1 : next;
    }

    private static int requiredAnchorIndex(final List<?> children, final Object anchor) {
        final int index = children.indexOf(anchor);
        if (index < 0) {
            throw new IllegalStateException("main-toolbar anchor is not a child of its parent");
        }
        return index;
    }

    private Icon icon(final String pluginId, final String path) {
        return ToolbarIconLoader.load(resources, pluginId, path);
    }

    private Object kotlinUnit() {
        try {
            final Class<?> unit = Class.forName("kotlin.Unit", false, resolver.hostClassLoader());
            return unit.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Kotlin Unit is unavailable for toolbar callback", exception);
        }
    }

    private void refresh(final Object container) {
        resolver.invoke(WIDGET_REVALIDATE, container);
        resolver.invoke(WIDGET_REPAINT, container);
    }

    private static <T> T onEdt(final Operation<T> operation) {
        return EdtDispatch.call("main-toolbar EDT operation", operation::run);
    }

    /**
     * Idempotent removal work: on acceptance timeout the task stays queued and still runs
     * exactly once when the EDT drains, so a closed contribution is never orphaned.
     */
    private static void onEdtEventually(final Runnable operation) {
        EdtDispatch.runEventually("main-toolbar EDT removal", operation);
    }

    private record NativeAnchor(Object widget) implements AnchorHandle {
        private NativeAnchor {
            Objects.requireNonNull(widget, "widget");
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
