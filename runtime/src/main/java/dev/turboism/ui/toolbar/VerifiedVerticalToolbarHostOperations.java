package dev.turboism.ui.toolbar;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.VerticalToolbarContribution;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Exact-version vertical tool-strip operations restricted to verified aliases.
 *
 * <p>The strip is a Swing panel attached to the right edge of the modeling
 * canvas container (the container that holds the OpenGL canvas and the native
 * draw-depth control on its left), so it hugs the canvas instead of the main
 * frame border.</p>
 */
public final class VerifiedVerticalToolbarHostOperations implements VerticalToolbarHostOperations {

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

    private static final String GL_CANVAS_TYPE = "com.jogamp.opengl.awt.GLJPanel";
    private static final int STRIP_WIDTH = 32;
    private static final int BUTTON_SIZE = 28;
    private static final int ICON_SIZE = 22;

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
        final JComponent root = jComponent(mainContainer);
        final JComponent canvasContainer = canvasContainer(root);
        if (canvasContainer == null) {
            throw new IllegalStateException("Cubism modeling canvas container is unavailable");
        }
        final JComponent host = canvasContainer;
        final boolean left = descriptor.contribution().side()
            == VerticalToolbarContribution.VerticalSide.LEFT;
        final boolean right = !left;

        final String stripId = "turboism:" + descriptor.pluginId() + ":" + descriptor.contributionId();
        final JPanel strip = new JPanel();
        strip.setLayout(new BoxLayout(strip, right ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS));
        strip.setName(stripId);
        strip.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        // Lock the strip to its exact width so custom canvas layouts cannot
        // stretch it (the native draw-depth control on the left is ~20px).
        strip.setMinimumSize(new java.awt.Dimension(STRIP_WIDTH, 28));
        strip.setPreferredSize(new java.awt.Dimension(STRIP_WIDTH, 200));
        strip.setMaximumSize(new java.awt.Dimension(STRIP_WIDTH, Integer.MAX_VALUE));

        for (final VerticalToolbarContribution.ToolButton button : descriptor.contribution().buttons()) {
            final Object nativeButton = nativeButton(
                descriptor.pluginId(),
                button,
                stripId + "." + button.id(),
                click
            );
            strip.add(jComponent(nativeButton));
            strip.add(right ? Box.createVerticalStrut(4) : Box.createHorizontalStrut(4));
        }

        // RIGHT: append after the GL canvas; LEFT: insert before the draw-depth control.
        host.add(strip, left ? 0 : host.getComponentCount());
        host.revalidate();
        host.repaint();

        return () -> onEdt(() -> {
            host.remove(strip);
            host.revalidate();
            host.repaint();
            return null;
        });
    }

    static JComponent canvasContainer(final JComponent root) {
        return findCanvasContainer(root, 0);
    }

    static JComponent canvasParent(final JComponent root) {
        final JComponent canvas = canvasContainer(root);
        if (canvas == null) {
            return null;
        }
        return canvas.getParent() instanceof JComponent parent ? parent : null;
    }

    private static JComponent findCanvasContainer(final JComponent component, final int depth) {
        if (depth > 12) {
            return null;
        }
        for (final Component child : component.getComponents()) {
            if (!(child instanceof JComponent swing)) {
                continue;
            }
            // The container whose DIRECT child is the GL canvas holds the
            // horizontal [draw-depth | canvas] layout.
            if (containsGlCanvas(swing)) {
                return swing;
            }
            final JComponent found = findCanvasContainer(swing, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }


    private static boolean containsGlCanvas(final JComponent component) {
        for (final Component child : component.getComponents()) {
            if (child.getClass().getName().equals(GL_CANVAS_TYPE)) {
                return true;
            }
        }
        return false;
    }

    private JComponent jComponent(final Object widget) {
        final Object value = resolver.invoke(WIDGET_JCOMPONENT, widget);
        if (!(value instanceof JComponent component)) {
            throw new IllegalStateException("Cubism widget JComponent is unavailable");
        }
        return component;
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

    /** Builds a native Cubism CIconButton (host hover/pressed visuals) and wires the click. */
    private Object nativeButton(
        final String pluginId,
        final VerticalToolbarContribution.ToolButton button,
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

    private Icon icon(final String pluginId, final String resourcePath) {
        final URL url = resources.resource(pluginId, resourcePath).orElse(null);
        if (url == null) {
            throw new IllegalStateException(
                "vertical-toolbar icon is unavailable: " + pluginId + ":" + resourcePath
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
