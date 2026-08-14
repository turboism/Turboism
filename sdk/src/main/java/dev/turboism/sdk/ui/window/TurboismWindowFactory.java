package dev.turboism.sdk.ui.window;

import dev.turboism.sdk.PreviewApi;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Window;
import java.net.URL;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JFrame;

/**
 * Preview factory for plugin-owned JDK Swing windows.
 *
 * <p>This type is an exception to the SDK UI contract: unlike the semantic
 * host UI request types in {@code dev.turboism.sdk.ui} (for example
 * {@code DialogRequest} or {@code PanelView}) and unlike
 * {@code UiHostCapabilityService}, which must not expose Swing, AWT, Cubism
 * host widgets, native handles, or raw host objects, this factory is a
 * <em>plugin-owned</em> convenience: a plugin builds its own Swing window in
 * the host JVM anyway, and this factory only constructs plain JDK
 * {@link JDialog}/{@link JFrame} instances and applies the Turboism window
 * icon. It is <em>not</em> part of the {@code UiHostCapabilityService} host
 * contract, it does not expose any Cubism host type, and it does not render
 * host dialogs.
 *
 * <p>All methods are headless-safe in the fail-closed sense: factory methods
 * refuse to construct AWT windows when
 * {@link GraphicsEnvironment#isHeadless()} is {@code true} and return
 * {@code null} instead, and {@link #style(Window)} is a no-op for a
 * {@code null} window. The window icon is loaded from the SDK classpath and
 * cached lazily; a missing or corrupted resource degrades gracefully to
 * {@code null} without throwing.
 */
@PreviewApi
public final class TurboismWindowFactory {

    private static final String WINDOW_ICON_RESOURCE =
        "dev/turboism/sdk/ui/window/turboism-window-icon.png";

    private static volatile Image windowIcon;

    private TurboismWindowFactory() {
    }

    /**
     * Returns the Turboism window icon loaded from the SDK classpath, or
     * {@code null} when the resource is missing or cannot be decoded.
     *
     * <p>The icon is loaded once and cached; a failed load is also cached so
     * repeated calls do not rescan the classpath.</p>
     *
     * @return the window icon image, or {@code null} when unavailable
     */
    public static Image windowIcon() {
        final Image cached = windowIcon;
        if (cached != null) {
            return cached;
        }
        final Image loaded = loadWindowIcon();
        windowIcon = loaded;
        return loaded;
    }

    /**
     * Creates a plugin-owned modeless or modal {@link JDialog} with the
     * Turboism window icon applied.
     *
     * <p>The owner follows the legacy three-state semantics: a {@link Frame}
     * owner is used directly, a {@link Dialog} owner is used directly, and
     * any other owner (including {@code null}) falls back to an ownerless
     * dialog constructed with a {@code null} frame owner.</p>
     *
     * @param owner the dialog owner, or {@code null} for a top-level dialog
     * @param title the dialog title
     * @param modal whether the dialog blocks its owner
     * @return the constructed dialog, or {@code null} in a headless JVM
     */
    public static JDialog dialog(final Window owner, final String title, final boolean modal) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        final JDialog dialog;
        if (owner instanceof Frame) {
            dialog = new JDialog((Frame) owner, title, modal);
        } else if (owner instanceof Dialog) {
            dialog = new JDialog((Dialog) owner, title, modal);
        } else {
            dialog = new JDialog((Frame) null, title, modal);
        }
        style(dialog);
        return dialog;
    }

    /**
     * Creates a plugin-owned {@link JFrame} with the Turboism window icon
     * applied.
     *
     * @param title the frame title
     * @return the constructed frame, or {@code null} in a headless JVM
     */
    public static JFrame frame(final String title) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        final JFrame frame = new JFrame(title);
        style(frame);
        return frame;
    }

    /**
     * Applies the Turboism window icon to an already constructed window.
     *
     * <p>A {@code null} window is a no-op, and any failure during styling is
     * swallowed so styling never throws to the caller.</p>
     *
     * @param window the window to style, or {@code null}
     */
    public static void style(final Window window) {
        if (window == null) {
            return;
        }
        final Image icon = windowIcon();
        if (icon == null) {
            return;
        }
        try {
            window.setIconImage(icon);
        } catch (Throwable ignored) {
            // graceful degradation: an undecorated or hostile window must not
            // break construction because icon styling failed
        }
    }

    private static Image loadWindowIcon() {
        return loadWindowIcon(WINDOW_ICON_RESOURCE);
    }

    /**
     * Package-private seam for headless-safe tests: loads the icon from an
     * arbitrary classpath resource and degrades to {@code null} when the
     * resource is missing or cannot be decoded. Never throws.
     */
    static Image loadWindowIcon(final String resource) {
        try {
            final ClassLoader loader = TurboismWindowFactory.class.getClassLoader();
            if (loader == null) {
                return null;
            }
            final URL url = loader.getResource(resource);
            if (url == null) {
                return null;
            }
            final ImageIcon icon = new ImageIcon(url);
            return icon.getIconWidth() > 0 && icon.getIconHeight() > 0
                ? icon.getImage()
                : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
