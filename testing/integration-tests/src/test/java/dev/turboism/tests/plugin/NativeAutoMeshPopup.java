package dev.turboism.tests.plugin;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.StringWriter;
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
 * <p>Every step answers a diagnostic instead of throwing. The probe records that diagnostic, so a
 * refused attach names the container it built, the child counts it observed and the exact failure
 * the host raised. A host that cannot build the popup therefore reports a skipped selector check
 * with a cause rather than failing the framework verdict on a step the framework does not own.</p>
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
    private static final int MAXIMUM_STACK_LINES = 24;

    private NativeAutoMeshPopup() {}

    /**
     * One container construction attempt and everything the probe learned about it.
     *
     * <p>The centre child count is sampled right after the constructor returns. The runtime posts the
     * attach to the Editor UI thread, so a construction that runs on that thread cannot have appended
     * the row yet: the sample is the host's own child count.</p>
     *
     * @param container the live container, or {@code null} when the host could not build it
     * @param containerClass the concrete container class name, or a stable reason token
     * @param centerClass the concrete centre widget class name, or a stable reason token
     * @param childrenBefore the centre child count the host itself produced
     * @param failure the full stack trace of the construction failure, or empty when it succeeded
     */
    record Build(
            Object container, String containerClass, String centerClass, int childrenBefore, Optional<String> failure) {

        Build {
            containerClass = containerClass == null ? "<unknown>" : containerClass;
            centerClass = centerClass == null ? "<none>" : centerClass;
            failure = failure == null ? Optional.empty() : failure;
        }

        /** @return whether the host produced a container */
        boolean built() {
            return container != null;
        }
    }

    /**
     * Builds one native Auto Mesh container.
     *
     * <p>Runs on the Editor UI thread. The container constructor is where the attach hook runs, so a
     * successful construction is the moment the selector row is either added or refused. The child
     * counts bracket the constructor, which separates "the hook never ran" from "the hook ran and
     * refused".</p>
     *
     * @return the attempt, carrying the live container or the exact failure
     */
    static Build open() {
        try {
            final Object app = appController();
            if (app == null) {
                return notBuilt("no-app-controller");
            }
            final Object pack = invokeByName(app, COMPLETE_PACK);
            if (pack == null) {
                return notBuilt("no-complete-pack");
            }
            final Class<?> containerType =
                    loadHostClass(MODELING_POPUP_DIALOG, pack.getClass().getClassLoader());
            if (containerType == null) {
                return notBuilt("no-container-class");
            }
            for (final Constructor<?> constructor : containerType.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 1 || !constructor.getParameterTypes()[0].isInstance(pack)) {
                    continue;
                }
                constructor.setAccessible(true);
                final Object container = constructor.newInstance(pack);
                final Object center = invokeByName(container, CENTER);
                return new Build(
                        container,
                        container.getClass().getName(),
                        center == null ? null : center.getClass().getName(),
                        childCountOf(center),
                        Optional.empty());
            }
            return notBuilt("no-single-pack-constructor");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return new Build(null, MODELING_POPUP_DIALOG, null, -1, Optional.of(stackTrace(unavailable)));
        }
    }

    /**
     * Counts the children of the container's centre widget.
     *
     * <p>The selector row is appended to that centre, so a count above the host's own children is the
     * observable evidence that the attach hook ran.</p>
     *
     * @param container the live container
     * @return the child count, or {@code -1} when the centre is unreachable
     */
    static int childCount(final Object container) {
        return childCountOf(invokeByName(container, CENTER));
    }

    /**
     * Paints the container into a PNG file.
     *
     * <p>Painting through {@link Component#paint} works without window-compositor access, so the
     * capture succeeds on hosts where a screen grab would need extra tooling. A widget with no size
     * yet answers a reason instead of writing a blank image.</p>
     *
     * @param container the live container
     * @param dumpRoot the dump directory the image belongs to
     * @return the written file, or the reason no image could be produced
     */
    static Capture capture(final Object container, final Path dumpRoot) {
        if (!(container instanceof Component component)) {
            return Capture.failed("the container is not an AWT component");
        }
        final int width = Math.max(MINIMUM_POPUP_EDGE, component.getWidth());
        final int height = Math.max(MINIMUM_POPUP_EDGE, component.getHeight());
        try {
            final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            final java.awt.Graphics2D graphics = image.createGraphics();
            try {
                component.paint(graphics);
            } finally {
                graphics.dispose();
            }
            Files.createDirectories(dumpRoot);
            final Path file = dumpRoot.resolve(SCREENSHOT_FILE);
            if (!ImageIO.write(image, PNG_FORMAT, file.toFile())) {
                return Capture.failed("no PNG writer accepted the image");
            }
            return new Capture(Optional.of(file), "", component.getWidth(), component.getHeight());
        } catch (java.io.IOException | RuntimeException | LinkageError unavailable) {
            return Capture.failed(stackTrace(unavailable));
        }
    }

    /**
     * One capture attempt.
     *
     * @param file the written PNG, or empty when nothing was written
     * @param reason the failure detail; empty when {@code file} is present
     * @param width the painted width
     * @param height the painted height
     */
    record Capture(Optional<Path> file, String reason, int width, int height) {
        Capture {
            file = file == null ? Optional.empty() : file;
            reason = reason == null ? "" : reason;
        }

        static Capture failed(final String reason) {
            return new Capture(Optional.empty(), reason, 0, 0);
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

    /**
     * Renders one throwable with its causes and the frames that identify the failing host member.
     *
     * @param failure the throwable to render
     * @return a single-line-safe stack trace, bounded to the leading frames
     */
    static String stackTrace(final Throwable failure) {
        final StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            failure.printStackTrace(writer);
        }
        final List<String> kept = new ArrayList<>(MAXIMUM_STACK_LINES);
        for (final String line : text.toString().lines().toList()) {
            if (kept.size() >= MAXIMUM_STACK_LINES) {
                kept.add("... truncated");
                break;
            }
            kept.add(line.strip());
        }
        return String.join(" | ", kept);
    }

    private static Build notBuilt(final String reason) {
        return new Build(null, reason, null, -1, Optional.empty());
    }

    private static int childCountOf(final Object center) {
        return center instanceof Container widget ? widget.getComponentCount() : -1;
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
