package dev.turboism.ui.toolbar;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.VerticalToolbarContribution;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Exact-version vertical tool-strip operations restricted to verified aliases. */
public final class VerifiedVerticalToolbarHostOperations implements VerticalToolbarHostOperations {

    private static final String APP_INSTANCE =
        "cubism.ui-main-toolbar.app-controller.instance";
    private static final String APP_MAIN_FRAME =
        "cubism.ui-main-toolbar.app-controller.main-frame";
    private static final String MAIN_FRAME_VIEW =
        "cubism.ui-main-toolbar.main-frame.view";
    private static final String MAIN_CONTAINER =
        "cubism.ui-main-toolbar.main-frame-view.main-container";
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
    private static final String VBOX_CREATE = "cubism.ui-main-toolbar.vbox.create";
    private static final String ICON_BUTTON_CREATE =
        "cubism.ui-main-toolbar.icon-button.create";
    private static final String ICON_CREATE = "cubism.ui-main-toolbar.icon.create";

    private final VerifiedMemberResolver resolver;
    private final EditorUiPluginResourceRegistry resources;

    public VerifiedVerticalToolbarHostOperations(
        final VerifiedMemberResolver resolver,
        final EditorUiPluginResourceRegistry resources
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Override
    public Registration attach(
        final VerticalToolbarContributionDescriptor descriptor,
        final Consumer<String> click
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(click, "click");
        return onEdt(() -> install(descriptor, click));
    }

    private Registration install(
        final VerticalToolbarContributionDescriptor descriptor,
        final Consumer<String> click
    ) {
        final Object mainContainer = mainContainer();
        final List<?> children = children(mainContainer);
        final String stripId = "turboism:" + descriptor.pluginId() + ":" + descriptor.contributionId();
        if (children.stream().anyMatch(widget -> stripId.equals(resolver.invoke(WIDGET_NAME, widget)))) {
            throw new IllegalStateException("vertical tool strip is already materialized");
        }

        final Object strip = resolver.construct(VBOX_CREATE);
        resolver.invoke(WIDGET_SET_NAME, strip, stripId);

        for (final VerticalToolbarContribution.ToolButton button : descriptor.contribution().buttons()) {
            final Icon icon = icon(descriptor.pluginId(), button.iconResourcePath());
            final Object callback = resolver.createFunctionalConstructorArgumentProxy(
                ICON_BUTTON_CREATE,
                1,
                ignored -> {
                    click.accept(button.actionId());
                    return kotlinUnit();
                }
            );
            final Object nativeButton = resolver.construct(ICON_BUTTON_CREATE, icon, callback);
            resolver.invoke(WIDGET_SET_NAME, nativeButton, stripId + "." + button.id());
            resolver.invoke(WIDGET_SET_TOOLTIP, nativeButton, button.tooltipKey());
            resolver.invoke(WIDGET_SET_PREF_WIDTH, nativeButton, 28);
            resolver.invoke(WIDGET_SET_PREF_HEIGHT, nativeButton, 28);
            resolver.invoke(CONTAINER_ADD, strip, nativeButton, children(strip).size());
        }

        // Left edge of the main frame: first position in mainContainer.
        resolver.invoke(CONTAINER_ADD, mainContainer, strip, 0);
        resolver.invoke(WIDGET_REVALIDATE, mainContainer);
        resolver.invoke(WIDGET_REPAINT, mainContainer);

        return () -> onEdt(() -> {
            resolver.invoke(CONTAINER_REMOVE, mainContainer, strip);
            resolver.invoke(WIDGET_REVALIDATE, mainContainer);
            resolver.invoke(WIDGET_REPAINT, mainContainer);
            return null;
        });
    }

    private Object mainContainer() {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object mainFrame = resolver.invoke(APP_MAIN_FRAME, app);
        if (mainFrame == null) {
            throw new IllegalStateException("Cubism main frame is not ready");
        }
        final Object view = resolver.invoke(MAIN_FRAME_VIEW, mainFrame);
        if (view == null) {
            throw new IllegalStateException("Cubism main frame view is not ready");
        }
        final Object container = resolver.readField(MAIN_CONTAINER, view);
        if (container == null) {
            throw new IllegalStateException("Cubism main container is not ready");
        }
        return container;
    }

    private List<?> children(final Object container) {
        final Object raw = resolver.invoke(CONTAINER_CHILDREN, container);
        if (!(raw instanceof List<?> values)) {
            throw new IllegalStateException("Cubism container children are unavailable");
        }
        return values;
    }

    private Icon icon(final String pluginId, final String resourcePath) {
        final URL url = resources.resource(pluginId, resourcePath).orElse(null);
        if (url == null) {
            throw new IllegalStateException(
                "vertical-toolbar icon is unavailable: " + pluginId + ":" + resourcePath
            );
        }
        return new ImageIcon(url);
    }

    private Object kotlinUnit() {
        try {
            final Class<?> unit = Class.forName("kotlin.Unit", false, resolver.hostClassLoader());
            return unit.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Kotlin Unit is unavailable for toolbar callback", failure);
        }
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
            throw new IllegalStateException("vertical-toolbar EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("vertical-toolbar EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("vertical-toolbar EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
