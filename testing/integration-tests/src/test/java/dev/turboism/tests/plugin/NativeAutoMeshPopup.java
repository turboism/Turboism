package dev.turboism.tests.plugin;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Builds, inspects, captures and disposes one native Auto Mesh Generate container.
 *
 * <p>The container class name comes from the reviewed host profile, so the probe builds the same
 * widget the Editor's own popup builds. Building the real container is what makes the selector-row
 * check meaningful: the attach hook runs inside the container constructor, which no test double can
 * reproduce.</p>
 *
 * <p>Every failure answers empty instead of throwing. A host that cannot build the popup reports a
 * skipped selector check rather than failing the framework verdict on a step the framework does not
 * own.</p>
 */
final class NativeAutoMeshPopup {

    private static final String CONTAINER_PACKAGE = "com.live2d.cubism.doc.model.extension.meshGenerator.ui.";
    private static final String MODELING_POPUP_DIALOG =
            CONTAINER_PACKAGE + "AutoMeshGenerate_PopupType$AutoGenerateMeshDialog";
    private static final String COMPLETE_PACK = "getCompletePack";
    private static final String APP_CONTROLLER = "com.live2d.cubism.CEAppCtrl";
    private static final String APP_CONTROLLER_INSTANCE = "_instance";
    private static final String APP_CONTROLLER_INSTANCE_ACCESSOR = "access$get_instance$cp";
    private static final String CENTER = "getCenter";
    private static final String DISPOSE = "dispose";
    private static final String SCREENSHOT_FILE = "automesh-selector-row.png";
    private static final String PNG_FORMAT = "png";
    private static final int MINIMUM_POPUP_EDGE = 16;

    private NativeAutoMeshPopup() {}

    /**
     * Builds one native Auto Mesh container.
     *
     * <p>Runs on the Editor UI thread. The container constructor is where the attach hook runs, so a
     * successful construction is the moment the selector row is either added or refused.</p>
     *
     * @return the live container, or empty when the host cannot build it
     */
    static Optional<Object> open() {
        try {
            final Object app = appController();
            if (app == null) {
                return Optional.empty();
            }
            final Object pack = invokeByName(app, COMPLETE_PACK);
            if (pack == null) {
                return Optional.empty();
            }
            final Class<?> containerType =
                    loadHostClass(MODELING_POPUP_DIALOG, pack.getClass().getClassLoader());
            if (containerType == null) {
                return Optional.empty();
            }
            for (final Constructor<?> constructor : containerType.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 1 || !constructor.getParameterTypes()[0].isInstance(pack)) {
                    continue;
                }
                constructor.setAccessible(true);
                return Optional.of(constructor.newInstance(pack));
            }
            return Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Counts the children of the container's centre widget.
     *
     * <p>The selector row is appended to that centre, so a count above the host's own children is the
     * observable evidence that the attach hook ran.</p>
     *
     * @param container the live container
     * @return the child count, or {@code 0} when the centre is unreachable
     */
    static int childCount(final Object container) {
        final Object center = invokeByName(container, CENTER);
        return center instanceof Container widget ? widget.getComponentCount() : 0;
    }

    /**
     * Paints the container into a PNG file.
     *
     * <p>Painting through {@link Component#paint} works without window-compositor access, so the
     * capture succeeds on hosts where a screen grab would need extra tooling. A widget with no size
     * yet answers empty instead of writing a blank image.</p>
     *
     * @param container the live container
     * @param dumpRoot the dump directory the image belongs to
     * @return the written file, or empty when no image could be produced
     */
    static Optional<Path> capture(final Object container, final Path dumpRoot) {
        if (!(container instanceof Component component)) {
            return Optional.empty();
        }
        try {
            final BufferedImage image = new BufferedImage(
                    Math.max(MINIMUM_POPUP_EDGE, component.getWidth()),
                    Math.max(MINIMUM_POPUP_EDGE, component.getHeight()),
                    BufferedImage.TYPE_INT_ARGB);
            final java.awt.Graphics2D graphics = image.createGraphics();
            try {
                component.paint(graphics);
            } finally {
                graphics.dispose();
            }
            Files.createDirectories(dumpRoot);
            final Path file = dumpRoot.resolve(SCREENSHOT_FILE);
            return ImageIO.write(image, PNG_FORMAT, file.toFile()) ? Optional.of(file) : Optional.empty();
        } catch (java.io.IOException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Releases one container the probe built.
     *
     * @param container the live container
     */
    static void close(final Object container) {
        final Object window =
                container instanceof Component component ? SwingUtilities.getWindowAncestor(component) : null;
        if (window instanceof Window ancestor) {
            ancestor.dispose();
            return;
        }
        invokeByName(container, DISPOSE);
    }

    private static Object appController() {
        final Class<?> controller = loadHostClass(APP_CONTROLLER, ClassLoader.getSystemClassLoader());
        if (controller == null) {
            return null;
        }
        final Object instance = staticField(controller, APP_CONTROLLER_INSTANCE);
        return instance != null ? instance : invokeStaticNoArg(controller, APP_CONTROLLER_INSTANCE_ACCESSOR);
    }

    private static Class<?> loadHostClass(final String name, final ClassLoader preferred) {
        final List<ClassLoader> loaders = new ArrayList<>(2);
        if (preferred != null) {
            loaders.add(preferred);
        }
        loaders.add(Thread.currentThread().getContextClassLoader());
        for (final ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next loader.
            }
        }
        return null;
    }

    private static Object staticField(final Class<?> owner, final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    private static Object invokeStaticNoArg(final Class<?> owner, final String name) {
        for (final Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                try {
                    method.setAccessible(true);
                    return method.invoke(null);
                } catch (ReflectiveOperationException | RuntimeException absent) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object invokeByName(final Object owner, final String name) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (final Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(owner);
                } catch (ReflectiveOperationException | RuntimeException absent) {
                    return null;
                }
            }
        }
        return null;
    }
}
