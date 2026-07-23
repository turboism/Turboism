package dev.turboism.ui.toolbar;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact Cubism 5.3.02 main-toolbar operations restricted to verified aliases. */
public final class VerifiedMainToolbarHostOperations implements MainToolbarHostOperations {

    private static final String APP_INSTANCE =
        "cubism.ui-main-toolbar.app-controller.instance";
    private static final String APP_MAIN_FRAME =
        "cubism.ui-main-toolbar.app-controller.main-frame";
    private static final String MAIN_FRAME_VIEW =
        "cubism.ui-main-toolbar.main-frame.view";
    private static final String HOME_BUTTON =
        "cubism.ui-main-toolbar.main-frame-view.home-button";
    private static final String WIDGET_PARENT = "cubism.ui-main-toolbar.widget.parent";
    private static final String WIDGET_NAME = "cubism.ui-main-toolbar.widget.name";
    private static final String WIDGET_SET_NAME = "cubism.ui-main-toolbar.widget.set-name";
    private static final String WIDGET_SET_TOOLTIP = "cubism.ui-main-toolbar.widget.set-tooltip";
    private static final String WIDGET_SET_PREF_WIDTH =
        "cubism.ui-main-toolbar.widget.set-pref-width";
    private static final String WIDGET_SET_PREF_HEIGHT =
        "cubism.ui-main-toolbar.widget.set-pref-height";
    private static final String WIDGET_REVALIDATE = "cubism.ui-main-toolbar.widget.revalidate";
    private static final String WIDGET_REPAINT = "cubism.ui-main-toolbar.widget.repaint";
    private static final String CONTAINER_CHILDREN =
        "cubism.ui-main-toolbar.container.children";
    private static final String CONTAINER_ADD = "cubism.ui-main-toolbar.container.add";
    private static final String CONTAINER_REMOVE = "cubism.ui-main-toolbar.container.remove";
    private static final String ICON_BUTTON_CREATE =
        "cubism.ui-main-toolbar.icon-button.create";
    private static final String ICON_BUTTON_SET_ROLLOVER =
        "cubism.ui-main-toolbar.icon-button.set-rollover-icon";
    private static final String ICON_CREATE = "cubism.ui-main-toolbar.icon.create";

    private final VerifiedMemberResolver resolver;
    private final EditorUiPluginResourceRegistry resources;

    public VerifiedMainToolbarHostOperations(
        final VerifiedMemberResolver resolver,
        final EditorUiPluginResourceRegistry resources
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Override
    public Optional<AnchorHandle> anchor(final MainToolbarRegistry.Anchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        if (anchor != MainToolbarRegistry.Anchor.HOST_HOME_ENTRY) {
            return Optional.empty();
        }
        return onEdt(() -> Optional.of(new NativeAnchor(resolveHomeButton())));
    }

    @Override
    public Registration addButton(
        final MainToolbarContributionDescriptor contribution,
        final Optional<AnchorHandle> anchor,
        final Runnable action
    ) {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(action, "action");
        return onEdt(() -> installButton(contribution, anchor, action));
    }

    private Registration installButton(
        final MainToolbarContributionDescriptor contribution,
        final Optional<AnchorHandle> anchor,
        final Runnable action
    ) {
        final Object semanticAnchor = anchor
            .map(value -> ((NativeAnchor) value).widget())
            .orElse(null);
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
        final Object callback = resolver.createFunctionalArgumentProxy(
            ICON_BUTTON_CREATE,
            1,
            ignored -> {
                action.run();
                return kotlinUnit();
            }
        );
        final Object button = resolver.construct(ICON_BUTTON_CREATE, normal, callback);
        resolver.invoke(WIDGET_SET_NAME, button, nativeId);
        resolver.invoke(WIDGET_SET_TOOLTIP, button, contribution.tooltip());
        resolver.invoke(WIDGET_SET_PREF_WIDTH, button, 28);
        resolver.invoke(WIDGET_SET_PREF_HEIGHT, button, 28);
        contribution.icons().hover().ifPresent(path -> resolver.invoke(
            ICON_BUTTON_SET_ROLLOVER,
            button,
            resolver.construct(ICON_CREATE, icon(contribution.pluginId(), path))
        ));

        final int index = insertionIndex(children, contribution.placement(), homeButton);
        resolver.invoke(CONTAINER_ADD, container, button, index);
        refresh(container);
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            onEdt(() -> {
                resolver.invoke(CONTAINER_REMOVE, container, button);
                refresh(container);
                return null;
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

    private static int insertionIndex(
        final List<?> children,
        final MainToolbarRegistry.Placement placement,
        final Object homeButton
    ) {
        return switch (placement.position()) {
            case FIRST -> 0;
            case LAST -> -1;
            case BEFORE -> requiredAnchorIndex(children, homeButton);
            case AFTER -> requiredAnchorIndex(children, homeButton) + 1;
        };
    }

    private static int requiredAnchorIndex(final List<?> children, final Object anchor) {
        final int index = children.indexOf(anchor);
        if (index < 0) {
            throw new IllegalStateException("main-toolbar anchor is not a child of its parent");
        }
        return index;
    }

    private Icon icon(final String pluginId, final String path) {
        final URL resource = resources.resource(pluginId, path)
            .orElseThrow(() -> new IllegalStateException("main-toolbar icon resource is unavailable"));
        return new ImageIcon(resource);
    }

    private Object kotlinUnit() {
        try {
            final Class<?> unit = Class.forName(
                "kotlin.Unit",
                false,
                resolver.hostClassLoader()
            );
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
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.run();
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = operation.run();
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("main-toolbar EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("main-toolbar EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("main-toolbar EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
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
