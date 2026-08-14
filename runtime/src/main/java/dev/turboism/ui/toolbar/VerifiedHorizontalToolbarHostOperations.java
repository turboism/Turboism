package dev.turboism.ui.toolbar;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.HorizontalToolbarContribution;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Exact-version horizontal tool-strip operations restricted to verified aliases.
 *
 * <p>The strip is a Swing panel attached above or below the modeling canvas
 * container (the vertical parent that stacks the canvas and its bottom bar).</p>
 */
public final class VerifiedHorizontalToolbarHostOperations implements HorizontalToolbarHostOperations {

    private static final String APP_INSTANCE =
        "cubism.ui-main-toolbar.app-controller.instance";
    private static final String APP_MAIN_FRAME =
        "cubism.ui-main-toolbar.app-controller.main-frame";
    private static final String MAIN_FRAME_VIEW =
        "cubism.ui-main-toolbar.main-frame.view";
    private static final String MAIN_CONTAINER =
        "cubism.ui-main-toolbar.main-frame-view.main-container";
    private static final String WIDGET_JCOMPONENT =
        "cubism.ui-main-toolbar.widget.jcomponent";
    private static final String WIDGET_SET_NAME = "cubism.ui-main-toolbar.widget.set-name";
    private static final String WIDGET_SET_TOOLTIP = "cubism.ui-main-toolbar.widget.set-tooltip";
    private static final String WIDGET_SET_PREF_WIDTH =
        "cubism.ui-main-toolbar.widget.set-pref-width";
    private static final String WIDGET_SET_PREF_HEIGHT =
        "cubism.ui-main-toolbar.widget.set-pref-height";
    private static final String ICON_BUTTON_CREATE =
        "cubism.ui-main-toolbar.icon-button.create";

    private static final int STRIP_HEIGHT = 32;
    private static final int BUTTON_SIZE = 28;

    private final VerifiedMemberResolver resolver;
    private final EditorUiPluginResourceRegistry resources;

    public VerifiedHorizontalToolbarHostOperations(
        final VerifiedMemberResolver resolver,
        final EditorUiPluginResourceRegistry resources
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Override
    public Registration attach(
        final HorizontalToolbarContributionDescriptor descriptor,
        final Consumer<String> click
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(click, "click");
        return onEdt(() -> install(descriptor, click));
    }

    private Registration install(
        final HorizontalToolbarContributionDescriptor descriptor,
        final Consumer<String> click
    ) {
        final Object mainContainer = resolver.readField(
            MAIN_CONTAINER,
            resolver.invoke(
                MAIN_FRAME_VIEW,
                resolver.invoke(
                    APP_MAIN_FRAME,
                    resolver.invokeStatic(APP_INSTANCE)
                )
            )
        );
        final JComponent root = jComponent(mainContainer);
        final JComponent canvas = VerifiedVerticalToolbarHostOperations.canvasContainer(root);
        final JComponent host = canvas == null
            ? null
            : VerifiedVerticalToolbarHostOperations.canvasParent(root);
        if (host == null) {
            throw new IllegalStateException("Cubism canvas host container is unavailable");
        }

        final boolean top = descriptor.contribution().side()
            == HorizontalToolbarContribution.HorizontalSide.TOP;
        final String stripId = "turboism:" + descriptor.pluginId() + ":" + descriptor.contributionId();
        final JPanel strip = new JPanel();
        strip.setLayout(new BoxLayout(strip, BoxLayout.X_AXIS));
        strip.setName(stripId);
        strip.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        strip.setMinimumSize(new java.awt.Dimension(28, STRIP_HEIGHT));
        strip.setPreferredSize(new java.awt.Dimension(200, STRIP_HEIGHT));
        strip.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, STRIP_HEIGHT));

        for (final dev.turboism.sdk.ui.VerticalToolbarContribution.ToolButton button
            : descriptor.contribution().buttons()) {
            final Object nativeButton = nativeButton(
                descriptor.pluginId(),
                button,
                stripId + "." + button.id(),
                click
            );
            strip.add(jComponent(nativeButton));
            strip.add(Box.createHorizontalStrut(4));
        }

        // TOP: insert before the canvas container; BOTTOM: append after it.
        final int index = top ? 0 : host.getComponentCount();
        host.add(strip, index);
        host.revalidate();
        host.repaint();

        return () -> onEdt(() -> {
            host.remove(strip);
            host.revalidate();
            host.repaint();
            return null;
        });
    }

    private Object nativeButton(
        final String pluginId,
        final dev.turboism.sdk.ui.VerticalToolbarContribution.ToolButton button,
        final String nativeId,
        final Consumer<String> click
    ) {
        final Object callback = resolver.createFunctionalConstructorArgumentProxy(
            ICON_BUTTON_CREATE,
            1,
            ignored -> {
                click.accept(button.actionId());
                return kotlinUnit();
            }
        );
        final Object nativeButton = resolver.construct(
            ICON_BUTTON_CREATE,
            icon(pluginId, button.iconResourcePath()),
            callback
        );
        resolver.invoke(WIDGET_SET_NAME, nativeButton, nativeId);
        resolver.invoke(WIDGET_SET_TOOLTIP, nativeButton, button.tooltipKey());
        resolver.invoke(WIDGET_SET_PREF_WIDTH, nativeButton, BUTTON_SIZE);
        resolver.invoke(WIDGET_SET_PREF_HEIGHT, nativeButton, BUTTON_SIZE);
        return nativeButton;
    }

    private Object kotlinUnit() {
        try {
            final Class<?> unit = Class.forName("kotlin.Unit", false, resolver.hostClassLoader());
            return unit.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Kotlin Unit is unavailable for toolbar callback", failure);
        }
    }

    private JComponent jComponent(final Object widget) {
        final Object value = resolver.invoke(WIDGET_JCOMPONENT, widget);
        if (!(value instanceof JComponent component)) {
            throw new IllegalStateException("Cubism widget JComponent is unavailable");
        }
        return component;
    }

    private Icon icon(final String pluginId, final String resourcePath) {
        final URL url = resources.resource(pluginId, resourcePath).orElse(null);
        if (url == null) {
            throw new IllegalStateException(
                "horizontal-toolbar icon is unavailable: " + pluginId + ":" + resourcePath
            );
        }
        return new ImageIcon(url);
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
            throw new IllegalStateException("horizontal-toolbar EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("horizontal-toolbar EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("horizontal-toolbar EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
