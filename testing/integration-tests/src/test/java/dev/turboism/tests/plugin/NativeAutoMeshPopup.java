package dev.turboism.tests.plugin;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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
import javax.swing.JComboBox;
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

    /**
     * Primary container class: the modeling-mode dialog panel.
     *
     * <p>This panel is the container that carries the native Preset and Parameters groups, which is
     * exactly the centre the plugin groups insert into.</p>
     */
    private static final String MODELING_DIALOG_PANEL =
            CONTAINER_PACKAGE + "AutoMeshGenerate_DialogType$AutoGenerateMeshPanel";

    /** Fallback container class, used only when the modeling panel is unavailable. */
    private static final String MODELING_POPUP_DIALOG =
            CONTAINER_PACKAGE + "AutoMeshGenerate_PopupType$AutoGenerateMeshDialog";

    private static final List<String> CONTAINER_CLASSES = List.of(MODELING_DIALOG_PANEL, MODELING_POPUP_DIALOG);

    private static final String COMPLETE_PACK = "getCompletePack";
    private static final String APP_CONTROLLER = "com.live2d.cubism.CEAppCtrl";
    private static final String APP_CONTROLLER_INSTANCE = "_instance";
    private static final String APP_CONTROLLER_INSTANCE_ACCESSOR = "access$get_instance$cp";
    private static final String CENTER = "getCenter";
    private static final String COMPONENT_GETTER = "getJComponent";
    private static final String HOST_CHILDREN = "getChildren";
    private static final String COMBO = "com.live2d.ui.control.CComboBox";
    private static final String COMBO_ITEMS = "getItems";
    private static final String COMBO_SELECTED_INDEX = "getSelectedIndex";
    private static final String COMBO_SET_SELECTED_INDEX = "setSelectedIndex";
    private static final String STOP_POLLING = "stopPolling";
    private static final int MAX_WIDGET_DEPTH = 24;
    private static final String DISPOSE = "dispose";
    private static final String SCREENSHOT_FILE = "automesh-selector-row.png";
    private static final String PARAMETER_DIALOG_FILE = "automesh-parameter-dialog.png";
    private static final String NATIVE_MODE_FILE = "automesh-native-mode.png";
    private static final String PNG_FORMAT = "png";
    private static final int MINIMUM_POPUP_EDGE = 16;
    private static final int MINIMUM_POPUP_WIDTH = 460;
    private static final int MINIMUM_POPUP_HEIGHT = 280;
    private static final int MAXIMUM_STACK_LINES = 24;

    /**
     * Index of the Editor's own generator in the algorithm combo.
     *
     * <p>The controller always lists the native generator first, so one entry names it without
     * reading the host locale.</p>
     */
    private static final int NATIVE_ALGORITHM_ENTRY = 0;

    /** Centre child index of the injected Algorithm group. */
    private static final int ALGORITHM_GROUP_INDEX = 0;

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
            for (final String className : CONTAINER_CLASSES) {
                final Class<?> containerType =
                        loadHostClass(className, pack.getClass().getClassLoader());
                if (containerType == null) {
                    continue;
                }
                final Constructor<?> constructor = singlePackConstructor(containerType, pack);
                if (constructor == null) {
                    return notBuilt("no-single-pack-constructor");
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
            return notBuilt("no-container-class");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return new Build(null, MODELING_DIALOG_PANEL, null, -1, Optional.of(stackTrace(unavailable)));
        }
    }

    /**
     * Resolves the constructor that takes one complete pack.
     *
     * <p>Both reviewed containers declare exactly one such constructor, and that constructor is the
     * only point where the container is reachable before the host shows it.</p>
     *
     * @return the constructor, or {@code null} when the class declares none
     */
    private static Constructor<?> singlePackConstructor(final Class<?> containerType, final Object pack) {
        for (final Constructor<?> constructor : containerType.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0].isInstance(pack)) {
                return constructor;
            }
        }
        return null;
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
     * Paints the container in three states into the dump directory.
     *
     * <p>The first artifact is the selector state the user sees: the Algorithm group at the top, the
     * plugin Preset group in the native layout, and the inline parameter controls of the selected
     * plugin algorithm. The second artifact shows the other registered plugin algorithm, so its own
     * parameter rows appear. The third shows the Editor's generator, where the native groups alone
     * are visible.</p>
     *
     * <p>The algorithm combo is the first combo of the centre's first child, because the runtime
     * inserts the Algorithm group exactly there. The host suppresses its own selection event for a
     * programmatic pick, so the capture delivers the pick the way Swing delivers one. Every switch is
     * restored afterwards.</p>
     *
     * @param container the live container
     * @param dumpRoot the dump directory the images belong to
     * @return the selector capture, or the reason no image could be produced
     */
    static Capture capture(final Object container, final Path dumpRoot) {
        final Component component = asAwtComponent(container);
        if (component == null) {
            return Capture.failed("the container exposes no AWT component");
        }
        final Capture selector = paint(component, dumpRoot.resolve(SCREENSHOT_FILE));
        if (selector.file().isEmpty()) {
            return selector;
        }
        final Object combo = algorithmCombo(container);
        if (combo == null) {
            return Capture.failed("the container exposes no algorithm combo below its centre");
        }
        final int original = selectedIndexOf(combo);
        try {
            final int other = otherPluginIndex(combo, original);
            if (other >= 0) {
                selectAlgorithm(combo, other);
                paint(component, dumpRoot.resolve(PARAMETER_DIALOG_FILE));
            }
            selectAlgorithm(combo, NATIVE_ALGORITHM_ENTRY);
            paint(component, dumpRoot.resolve(NATIVE_MODE_FILE));
        } finally {
            selectAlgorithm(combo, original);
        }
        return selector;
    }

    /**
     * Resolves the algorithm combo of one decorated container.
     *
     * <p>The runtime inserts the Algorithm group as the first child of the centre, so the first combo
     * below that child is the algorithm combo. A container that carries no group answers
     * {@code null}, and the capture then reports it instead of switching a preset combo.</p>
     */
    private static Object algorithmCombo(final Object container) {
        final Object center = invokeByName(container, CENTER);
        final Object children = invokeByName(center, HOST_CHILDREN);
        if (!(children instanceof List<?> list) || list.size() <= ALGORITHM_GROUP_INDEX) {
            return null;
        }
        return firstCombo(list.get(ALGORITHM_GROUP_INDEX), 0);
    }

    private static Object firstCombo(final Object widget, final int depth) {
        if (widget == null || depth > MAX_WIDGET_DEPTH) {
            return null;
        }
        if (widget.getClass().getName().equals(COMBO)) {
            return widget;
        }
        final Object children = invokeByName(widget, HOST_CHILDREN);
        if (children instanceof List<?> list) {
            for (final Object child : list) {
                final Object found = firstCombo(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Returns the combo index of the registered algorithm that is not the selected one.
     *
     * <p>The Editor's generator leads the listing and the selected entry is the first artifact, so the
     * remaining plugin entry is the one the parameter artifact must show. A listing without a second
     * plugin entry answers {@code -1}.</p>
     *
     * @return the index of the other plugin algorithm, or {@code -1} when none exists
     */
    private static int otherPluginIndex(final Object combo, final int selected) {
        final Object items = invokeByName(combo, COMBO_ITEMS);
        if (!(items instanceof List<?> list)) {
            return -1;
        }
        for (int index = NATIVE_ALGORITHM_ENTRY + 1; index < list.size(); index++) {
            if (index != selected) {
                return index;
            }
        }
        return -1;
    }

    private static int selectedIndexOf(final Object combo) {
        final Object index = invokeByName(combo, COMBO_SELECTED_INDEX);
        return index instanceof Number number ? number.intValue() : NATIVE_ALGORITHM_ENTRY;
    }

    /**
     * Selects one algorithm the way a user does.
     *
     * <p>The host suppresses its own selection callback while the index is written, so the Swing
     * action listeners are then run explicitly. That is the same route a real click takes.</p>
     */
    private static void selectAlgorithm(final Object combo, final int index) {
        if (!invoke(combo, COMBO_SET_SELECTED_INDEX, index)) {
            return;
        }
        final Object component = invokeByName(combo, COMPONENT_GETTER);
        if (!(component instanceof JComboBox<?> swing)) {
            return;
        }
        final ActionEvent event = new ActionEvent(
                swing, ActionEvent.ACTION_PERFORMED, swing.getClass().getName());
        for (final ActionListener listener : swing.getActionListeners()) {
            listener.actionPerformed(event);
        }
    }

    /**
     * Reports the parameter artifact the validation run painted from the live container.
     *
     * <p>The capture paints the container itself, so the artifact is whatever the host dialog showed
     * with the plugin algorithm selected. The runtime also paints its parameter dialog when a
     * validation run names a dump directory, and this probe reads the file back either way.</p>
     *
     * @param dumpRoot the dump directory the image belongs to
     * @return the written file, or the reason no image exists
     */
    static Capture parameterDialog(final Path dumpRoot) {
        final Path file = dumpRoot.resolve(PARAMETER_DIALOG_FILE);
        if (!Files.isRegularFile(file)) {
            return Capture.failed("no parameter dialog artifact exists at " + file);
        }
        try {
            final BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                return Capture.failed("the parameter dialog file is not a readable PNG: " + file);
            }
            return new Capture(Optional.of(file), "", image.getWidth(), image.getHeight());
        } catch (java.io.IOException | RuntimeException unreadable) {
            return Capture.failed(stackTrace(unreadable));
        }
    }

    /**
     * Resolves the Swing component the host widget paints through.
     *
     * <p>A host widget exposes its own {@code JComponent}, and a container that answers none is
     * reached through its centre child, which is where the selector row attaches.</p>
     */
    private static Component asAwtComponent(final Object container) {
        if (container instanceof Component component) {
            return component;
        }
        final Object widget = invokeByName(container, COMPONENT_GETTER);
        if (widget instanceof Component component) {
            return component;
        }
        final Object center = invokeByName(container, CENTER);
        final Object centerWidget = invokeByName(center, COMPONENT_GETTER);
        return centerWidget instanceof Component component ? component : null;
    }

    private static Capture paint(final Component component, final Path file) {
        try {
            prepare(component);
            final int width = Math.max(MINIMUM_POPUP_EDGE, component.getWidth());
            final int height = Math.max(MINIMUM_POPUP_EDGE, component.getHeight());
            final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            final java.awt.Graphics2D graphics = image.createGraphics();
            try {
                component.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            Files.createDirectories(file.getParent());
            if (!ImageIO.write(image, PNG_FORMAT, file.toFile())) {
                return Capture.failed("no PNG writer accepted the image");
            }
            return new Capture(Optional.of(file), "", width, height);
        } catch (java.io.IOException | RuntimeException | LinkageError unavailable) {
            return Capture.failed(stackTrace(unavailable));
        }
    }

    /**
     * Gives the widget tree real bounds before it is painted.
     *
     * <p>A container built without a window keeps a zero size, and every child then paints into a
     * zero rectangle. The outermost non-window ancestor is sized from its preferred size, and each
     * container in turn distributes that size to its children. The layout runs on every paint,
     * because a capture that follows an algorithm switch must place the rows that switch added.</p>
     */
    private static void prepare(final Component component) {
        Component root = component;
        while (root.getParent() != null && !(root.getParent() instanceof Window)) {
            root = root.getParent();
        }
        final Dimension preferred = root.getPreferredSize();
        root.setSize(Math.max(MINIMUM_POPUP_WIDTH, preferred.width), Math.max(MINIMUM_POPUP_HEIGHT, preferred.height));
        layout(root);
    }

    private static void layout(final Component component) {
        if (!(component instanceof Container container)) {
            return;
        }
        container.doLayout();
        for (final Component child : container.getComponents()) {
            layout(child);
        }
        container.validate();
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
     * <p>The modeling panel polls the Editor selection on a Swing timer, so the poll must stop before
     * the container is dropped. Leaving the timer running keeps a disposed dialog reachable and keeps
     * it updating from a selection it no longer owns.</p>
     *
     * @param container the live container
     */
    static void close(final Object container) {
        if (container == null) {
            return;
        }
        invoke(container, STOP_POLLING);
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
        if (center instanceof Container widget) {
            return widget.getComponentCount();
        }
        // Editor widgets are not AWT containers. The row can mount below the centre, so the count
        // covers the whole subtree and any depth of attachment shows as a change.
        return descendantCount(center, 0);
    }

    private static int descendantCount(final Object widget, final int depth) {
        if (widget == null || depth > MAX_WIDGET_DEPTH) {
            return widget == null ? -1 : 0;
        }
        if (!(invokeByName(widget, HOST_CHILDREN) instanceof List<?> children)) {
            return 0;
        }
        int total = children.size();
        for (final Object child : children) {
            total += Math.max(0, descendantCount(child, depth + 1));
        }
        return total;
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

    /**
     * Invokes one host method by name and argument count.
     *
     * <p>The probe cannot link host classes, so a member is matched by name and arity. The methods it
     * calls declare no overload of the same arity, which keeps the match unambiguous.</p>
     *
     * @return the answer, or {@code null} when the host declares no such member or refused the call
     */
    private static boolean invoke(final Object owner, final String name, final Object... arguments) {
        if (owner == null) {
            return false;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (final Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(owner, arguments);
                    return true;
                } catch (ReflectiveOperationException | RuntimeException refused) {
                    return false;
                }
            }
        }
        return false;
    }
}
