package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.plugin.Registration;

import javax.swing.SwingUtilities;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime registry and lifecycle owner for the native-position mirror-angle control.
 *
 * <p>The control tree is built from the exact host UI classes ({@code CLabel},
 * {@code CSlidableFloat}, {@code CButton}, {@code CHBox}, {@code CSpacer}) through
 * the panel's own class loader, mirroring the reviewed legacy injection. Any
 * reflection failure fails closed and leaves the native UI unchanged.
 */
public final class RuntimeMeshEditUiService implements MeshEditUiService {

    private final AtomicReference<MirrorAxisAngleControl> contribution = new AtomicReference<>();
    private final List<Attachment> attachments = new ArrayList<>();
    private final AtomicReference<java.util.function.Consumer<Boolean>> contributionObserver =
        new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong();

    @Override
    public Registration contributeMirrorAxisAngleControl(final MirrorAxisAngleControl requested) {
        Objects.requireNonNull(requested, "contribution");
        if (!contribution.compareAndSet(null, requested)) {
            throw new IllegalStateException("mesh mirror-axis angle control is already registered");
        }
        notifyContribution(true);
        return () -> clear(requested);
    }

    /**
     * Watches whether a plugin currently contributes the mirror-angle control.
     *
     * <p>At most one observer may be registered at a time. The observer is invoked immediately with
     * the present state, so a late registrant is not left waiting for the next change.
     *
     * @param observer receives {@code true} while a control is contributed, {@code false} once it is
     *                 withdrawn; non-null
     * @return a registration that removes this observer; closing it after another observer replaced
     *         it is a no-op
     * @throws IllegalStateException if an observer is already registered
     * @throws NullPointerException  if {@code observer} is null
     */
    public Registration observeContribution(final java.util.function.Consumer<Boolean> observer) {
        Objects.requireNonNull(observer, "observer");
        if (!contributionObserver.compareAndSet(null, observer)) {
            throw new IllegalStateException("mesh mirror contribution observer is already registered");
        }
        observer.accept(contribution.get() != null);
        return () -> contributionObserver.compareAndSet(observer, null);
    }

    /**
     * @return the contributed mirror-angle control, or {@code null} when no plugin has contributed
     *         one or its registration has been closed
     */
    public MirrorAxisAngleControl contribution() {
        return contribution.get();
    }

    Attachment nativeAttachment() {
        synchronized (attachments) {
            return attachments.isEmpty() ? null : attachments.get(0);
        }
    }

    /**
     * Detaches every injected control and invalidates the current attachment epoch, so in-flight
     * attach work for the old session is discarded.
     *
     * <p>Called when the host swaps mesh-edit panels. The contributed control itself is kept — only
     * its native mounts are torn down, on the Swing event thread. The registry bookkeeping happens
     * synchronously; the actual removal is scheduled onto the EDT.
     */
    public void resetSession() {
        epoch.incrementAndGet();
        final List<Attachment> stale;
        synchronized (attachments) {
            stale = List.copyOf(attachments);
            attachments.clear();
        }
        runOnEdt(() -> stale.forEach(attachment -> remove(attachment.mount, attachment.root)));
    }

    void attachNative(
        final Object panel,
        final Object widget,
        final RuntimeMeshMirrorAxisService axis
    ) {
        final MirrorAxisAngleControl active = contribution.get();
        if (active == null || panel == null || widget == null) {
            diag("ATTACH_SKIPPED reason=" + (active == null ? "NO_CONTRIBUTION" : "NULL_HOST_ARGUMENT"));
            return;
        }
        final long attachmentEpoch = epoch.get();
        diag("ATTACH_BEGIN");
        runOnEdt(() -> {
            if (epoch.get() != attachmentEpoch) {
                diag("ATTACH_SKIPPED reason=STALE_SESSION");
                return;
            }
            if (contribution.get() != active) {
                diag("ATTACH_SKIPPED reason=CONTRIBUTION_CHANGED");
                return;
            }
            if (find(panel) != null) {
                diag("ATTACH_SKIPPED reason=ALREADY_ATTACHED");
                return;
            }
            try {
                final Object mount = mountTarget(panel, widget);
                if (mount == null) {
                    diag("ATTACH_FAILED reason=NO_MOUNT_TARGET owner=" + panel.getClass().getName());
                    return;
                }
                final Object root = build(active, axis, panel);
                addAfterPositionRow(mount, root);
                synchronized (attachments) {
                    if (contribution.get() == active) attachments.add(new Attachment(panel, mount, root));
                    else {
                        remove(mount, root);
                        diag("ATTACH_SKIPPED reason=CONTRIBUTION_CHANGED");
                        return;
                    }
                }
                tryInvoke(mount, "revalidate");
                tryInvoke(mount, "repaint");
                NativeMeshMirrorBridge.markControlAttached();
                diag("ATTACH_OK");
            } catch (ReflectiveOperationException | RuntimeException failure) {
                // Fail closed: the native UI remains unchanged.
                diag("ATTACH_FAILED reason=" + failure.getClass().getName());
            }
        });
    }

    /** Attachment evidence shares the mesh-mirror host diagnostic channel. */
    private static void diag(final String stage) {
        NativeMeshMirrorBridge.diagnostic(stage);
    }

    /**
     * Builds the legacy host-native angle row: CLabel + CSlidableFloat + CButton in a
     * CHBox, wrapped by the reviewed host {@code createWidgetMirrorEditForMeshEdit$createComp}
     * when available. Runs on the EDT; every step is reflective through the panel loader.
     */
    private Object build(
        final MirrorAxisAngleControl active,
        final RuntimeMeshMirrorAxisService axis,
        final Object panel
    ) throws ReflectiveOperationException {
        final ClassLoader loader = panel.getClass().getClassLoader();
        final Class<?> function1 = Class.forName("kotlin.jvm.functions.Function1", false, loader);
        final Object unitInstance = Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null);

        final Object label = instantiate(loader, "com.live2d.ui.control.CLabel", active.label());

        final Object slider = instantiate(loader, "com.live2d.ui.control.CSlidableFloat");
        invoke(slider, "setMin", active.minimumDegrees());
        invoke(slider, "setMax", active.maximumDegrees());
        invoke(slider, "setKeta", 1);
        invoke(slider, "setValue", axis.currentAngleDegrees());
        final Object changed = proxy(function1, unitInstance, (proxy, method, arguments) -> {
            if ("invoke".equals(method.getName())) {
                active.onAngleChanged().accept(number(invoke(slider, "getValue")).floatValue());
                return unitInstance;
            }
            return unitInstance;
        });
        invoke(slider, "setOnChanged", changed);
        // CSlidableFloat has no addOnAction (that is CAbstractButton's method); the
        // legacy manager called it best-effort, so keep the same tolerance here.
        tryInvoke(slider, "addOnAction", changed);

        final Object reset = instantiate(loader, "com.live2d.ui.control.CButton");
        invoke(reset, "setText", "0");
        invoke(reset, "setPrefWidth", 24);
        invoke(reset, "setPrefHeight", 25);
        if (!active.resetToolTip().isEmpty()) invoke(reset, "setToolTipText", active.resetToolTip());
        final Object onReset = proxy(function1, unitInstance, (proxy, method, arguments) -> {
            if ("invoke".equals(method.getName())) {
                invoke(slider, "setValue", 0.0f);
                active.onAngleChanged().accept(number(invoke(slider, "getValue")).floatValue());
                return unitInstance;
            }
            return unitInstance;
        });
        invoke(reset, "addOnAction", onReset);

        final Object box = instantiate(loader, "com.live2d.ui.container.CHBox");
        invoke(box, "unaryPlus", slider);
        final Object spacer = instantiate(loader, "com.live2d.ui.container.CSpacer", 2, 0, 2, null);
        invoke(box, "unaryPlus", spacer);
        invoke(box, "unaryPlus", reset);

        final Object wrapped = createMirrorEditComp(panel, label, box);
        return wrapped == null ? box : wrapped;
    }

    /** Preferred host wrapper (CVBox); null when the reviewed static helper is absent. */
    private static Object createMirrorEditComp(final Object panel, final Object label, final Object row) {
        try {
            for (Method method : panel.getClass().getDeclaredMethods()) {
                if (method.getParameterCount() == 2
                    && method.getName().equals("createWidgetMirrorEditForMeshEdit$createComp")
                    && Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    return method.invoke(null, label, row);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The exact host may not expose the reviewed wrapper; use the plain row.
        }
        return null;
    }

    /**
     * Mount target for the angle row. The host widget (the CVBox returned by
     * {@code createWidgetMirrorEditForMeshEdit}) is available at hook time, while the
     * panel's {@code mirrorEditFoldingPane} field is assigned only after that method
     * returns; prefer the widget, and fall back to the folding-pane child when the
     * widget cannot be used as a container.
     */
    private static Object mountTarget(final Object panel, final Object widget) throws ReflectiveOperationException {
        if (widget != null) return widget;
        Object foldingPane = tryInvoke(panel, "getMirrorEditFoldingPane");
        Object content = foldingPane == null ? null : tryInvoke(foldingPane, "getChild");
        if (content == null) {
            foldingPane = field(panel, "mirrorEditFoldingPane");
            content = foldingPane == null ? null : tryInvoke(foldingPane, "getChild");
        }
        return content;
    }

    /**
     * Inserts the root after the host's position row (index 1): index = min(2, size);
     * a container with fewer than two children appends at the end.
     */
    private static void addAfterPositionRow(final Object container, final Object child)
        throws ReflectiveOperationException {
        final int index = Math.max(0, Math.min(2, childrenSize(container)));
        try {
            invoke(container, "add", child, index);
            return;
        } catch (ReflectiveOperationException ignored) {
            // The exact host container may only expose the children list.
        }
        final Object children = property(container, "children");
        if (children instanceof List) {
            @SuppressWarnings("rawtypes")
            final List raw = (List) children;
            raw.remove(child);
            raw.add(index, child);
        } else {
            invoke(container, "unaryPlus", child);
        }
    }

    private static int childrenSize(final Object container) {
        try {
            final Object children = property(container, "children");
            if (children instanceof List) return ((List<?>) children).size();
        } catch (ReflectiveOperationException ignored) {
            // Fall back to add(child, 0) / unaryPlus when the children list is unavailable.
        }
        return 0;
    }

    private void clear(final MirrorAxisAngleControl expected) {
        if (!contribution.compareAndSet(expected, null)) return;
        notifyContribution(false);
        epoch.incrementAndGet();
        final List<Attachment> stale;
        synchronized (attachments) {
            stale = List.copyOf(attachments);
            attachments.clear();
        }
        runOnEdt(() -> stale.forEach(attachment -> remove(attachment.mount, attachment.root)));
    }

    private Attachment find(final Object panel) {
        synchronized (attachments) {
            return attachments.stream().filter(value -> value.panel == panel).findFirst().orElse(null);
        }
    }

    private void notifyContribution(final boolean available) {
        final java.util.function.Consumer<Boolean> observer = contributionObserver.get();
        if (observer != null) observer.accept(available);
    }

    private static void remove(final Object mount, final Object root) {
        try {
            final Object children = property(mount, "children");
            if (children instanceof List) {
                @SuppressWarnings("rawtypes")
                final List raw = (List) children;
                raw.remove(root);
            } else invoke(mount, "remove", root);
            tryInvoke(mount, "revalidate");
            tryInvoke(mount, "repaint");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Best-effort cleanup; the host rebuilds its UI on the next session.
        }
    }

    /** getXxx() → getXxx$cubism() → field; a null target throws (fail-closed). */
    private static Object property(final Object target, final String name) throws ReflectiveOperationException {
        if (target == null) throw new NoSuchFieldException(name);
        final String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Object value = tryInvoke(target, getter);
        if (value == null) value = tryInvoke(target, getter + "$cubism");
        if (value == null) value = field(target, name);
        return value;
    }

    private static Object field(final Object target, final String name) throws ReflectiveOperationException {
        if (target == null) throw new NoSuchFieldException(name);
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object instantiate(
        final ClassLoader loader,
        final String className,
        final Object... arguments
    ) throws ReflectiveOperationException {
        final Class<?> type = Class.forName(className, false, loader);
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() != arguments.length) continue;
            try {
                return constructor.newInstance(arguments);
            } catch (IllegalArgumentException | InvocationTargetException ignored) {
                // Try the next public constructor (boxed/null arguments).
            }
        }
        throw new NoSuchMethodException(className + " constructor with " + arguments.length + " arguments");
    }

    private static Object proxy(
        final Class<?> function1,
        final Object unitInstance,
        final InvocationHandler body
    ) {
        return Proxy.newProxyInstance(function1.getClassLoader(), new Class<?>[] { function1 },
            (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "toString":
                        return "TurboismMirrorAngleCallback";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
                    default:
                        return body.invoke(proxy, method, arguments);
                }
            });
    }

    private static Object tryInvoke(final Object target, final String name, final Object... arguments) {
        try {
            return invoke(target, name, arguments);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invoke(final Object target, final String name, final Object... arguments)
        throws ReflectiveOperationException {
        if (target == null) throw new NoSuchMethodException(name);
        for (Method method : MethodHandleCache.overloads(target.getClass(), name, arguments.length)) {
            try {
                return method.invoke(target, arguments);
            } catch (IllegalArgumentException ignored) {
                // Try the next overload.
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    private static Number number(final Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("expected number");
    }

    private static void runOnEdt(final Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    record Attachment(Object panel, Object mount, Object root) { }
}
