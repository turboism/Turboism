package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runtime implementation of the canvas-strip text-button surface, built entirely
 * reflectively against the reviewed 5.3.03 host classes (GToggleButtonEntity).
 *
 * <p>The strip constructs and mounts its own buttons in {@code a.b} (constructor
 * + {@code R()}); the injected hooks call {@link #mount(Object)} at construction
 * and {@link #positionStripButton(Object)} at the tail of every strip re-layout,
 * which places contributed text buttons right of the strip's last (caret)
 * button. Plugins usually contribute before the modeling view exists, so
 * contributions queue here and build on mount; anything contributed afterwards
 * builds in place.</p>
 */
public final class RuntimeViewContextMenuRegistry implements ViewContextMenuRegistry {

    private static final RuntimeViewContextMenuRegistry INSTANCE = new RuntimeViewContextMenuRegistry();

    /** Reviewed 5.3.03 selectors (disassembly-verified). */
    private static final String STRIP_CLASS = "com.live2d.cubism.view.context.a.b";
    private static final String BUTTON_CLASS =
        "com.live2d.cubism.view.context.guiEntity.GToggleButtonEntity";
    private static final String FONT_CLASS = "com.live2d.type.CFont";
    private static final String RECT_CLASS = "com.live2d.graphics3d.type.GRectF";
    private static final String FUNCTION1_CLASS = "kotlin.jvm.functions.Function1";
    private static final int BUTTON_WIDTH = 96;
    private static final int BUTTON_HEIGHT = 24;
    private static final float BUTTON_GAP = 6f;

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> pendingBuilds = new ArrayList<>();
    private Object strip;
    private Object button;
    private boolean mountAttempted;

    private record Entry(ViewContextMenuRegistry.ButtonContribution contribution, Object button) { }

    public static RuntimeViewContextMenuRegistry getInstance() {
        return INSTANCE;
    }

    private RuntimeViewContextMenuRegistry() { }

    @Override
    public Registration contributeButton(final ViewContextMenuRegistry.ButtonContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        final Runnable build = () -> buildAndMount(contribution);
        synchronized (lock) {
            if (mountAttempted && strip != null) {
                build.run();
            } else {
                pendingBuilds.add(build);
            }
        }
        return () -> {
            synchronized (lock) {
                entries.remove(contribution.contributionId());
                if (button != null) {
                    setButtonResponding(button, false);
                }
            }
        };
    }

    @Override
    public void setText(final String contributionId, final String text) {
        synchronized (lock) {
            if (button == null) return;
            try {
                final Method setText = button.getClass().getMethod("setText", String.class);
                setText.invoke(button, text);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnostic("SET_TEXT_FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Injected at the head of the strip's mount routine with the strip instance.
     * Stores the strip and builds any contributions that queued before the view
     * existed, adding the button to the scene graph directly (the strip's H
     * group only accepts icon-button entities).
     */
    public void mount(final Object stripInstance) {
        synchronized (lock) {
            if (stripInstance == null) return;
            strip = stripInstance;
            mountAttempted = true;
            final List<Runnable> queued = new ArrayList<>(pendingBuilds);
            pendingBuilds.clear();
            for (final Runnable build : queued) {
                build.run();
            }
        }
        diagnostic("STRIP_MOUNTED instance=" + Integer.toHexString(System.identityHashCode(this)));
    }

    /**
     * Injected at the tail of the strip's per-frame re-layout. Positions every
     * contributed text button to the right of the strip's last (caret) button.
     */
    public void positionStripButton(final Object stripInstance) {
        synchronized (lock) {
            if (stripInstance == null || stripInstance != strip || button == null) return;
            try {
                final Object caret = lastStripButton(stripInstance);
                if (caret == null) return;
                final Object caretRect = caret.getClass()
                    .getMethod("getRectOnComponent").invoke(caret);
                final float x = rectValue(caretRect, "getX")
                    + rectValue(caretRect, "getWidth") + BUTTON_GAP;
                final float y = rectValue(caretRect, "getY");
                final Class<?> rectClass = Class.forName(RECT_CLASS, false,
                    button.getClass().getClassLoader());
                final Object bounds = rectClass.getConstructor(
                    float.class, float.class, float.class, float.class)
                    .newInstance(x, y, (float) BUTTON_WIDTH, (float) BUTTON_HEIGHT);
                final Method setBounds = button.getClass()
                    .getMethod("setBoundsOnComponent", rectClass, float.class);
                setBounds.invoke(button, bounds, 1.0f);
            } catch (Throwable failure) {
                diagnostic("POSITION_FAILED reason=" + failure.getClass().getName());
            }
        }
    }

    private void buildAndMount(final ViewContextMenuRegistry.ButtonContribution contribution) {
        try {
            if (strip == null) {
                diagnostic("BUILD_SKIPPED reason=NO_STRIP");
                return;
            }
            final ClassLoader hostLoader = strip.getClass().getClassLoader();
            final Class<?> buttonClass = Class.forName(BUTTON_CLASS, false, hostLoader);
            final Class<?> fontClass = Class.forName(FONT_CLASS, false, hostLoader);
            final Class<?> rectClass = Class.forName(RECT_CLASS, false, hostLoader);

            final Object font = fontClass.getConstructor(java.awt.Font.class)
                .newInstance(new java.awt.Font("Dialog", java.awt.Font.PLAIN, 12));
            final Object rect = rectClass
                .getConstructor(float.class, float.class, float.class, float.class)
                .newInstance(0f, 0f, (float) BUTTON_WIDTH, (float) BUTTON_HEIGHT);

            final Object built = buttonClass
                .getConstructor(String.class, fontClass, rectClass)
                .newInstance(contribution.text(), font, rect);

            setOnAction(built, contribution);
            setupTooltip(built, contribution.tooltip());

            button = built;
            addToSceneGraph(stripInstance(), built);
            diagnostic("BUTTON_MOUNTED id=" + contribution.contributionId()
                + " text=" + contribution.text());
        } catch (Throwable failure) {
            final String phase = failure instanceof PhaseTagged tagged ? tagged.phase : "UNKNOWN";
            diagnostic("BUTTON_MOUNT_FAILED phase=" + phase
                + " reason=" + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private Object stripInstance() {
        synchronized (lock) {
            return strip;
        }
    }

    /** Adds the button to the strip's scene-graph overlay (objects on component). */
    private static void addToSceneGraph(final Object stripInstance, final Object button)
        throws ReflectiveOperationException {
        final Object sceneGraph = stripInstance.getClass().getMethod("e").invoke(stripInstance);
        final Object objectsOnComponent = sceneGraph.getClass()
            .getMethod("getObjectsOnComponent").invoke(sceneGraph);
        final Object children = objectsOnComponent.getClass().getMethod("getChildren")
            .invoke(objectsOnComponent);
        final Method add = children.getClass().getMethod("add",
            objectsOnComponent.getClass(), int.class);
        add.invoke(children, button, 0);
    }

    /** Returns the strip's last mounted (caret) button, or null. */
    private static Object lastStripButton(final Object stripInstance)
        throws ReflectiveOperationException {
        final Field groupField = stripInstance.getClass().getDeclaredField("H");
        groupField.setAccessible(true);
        final Object group = groupField.get(stripInstance);
        if (!(group instanceof List<?> list) || list.isEmpty()) return null;
        return list.get(list.size() - 1);
    }

    private static float rectValue(final Object rect, final String accessor)
        throws ReflectiveOperationException {
        final Object value = rect.getClass().getMethod(accessor).invoke(rect);
        return value instanceof Number number ? number.floatValue() : 0f;
    }

    private static void setOnAction(
        final Object button,
        final ViewContextMenuRegistry.ButtonContribution contribution
    ) {
        try {
            final ClassLoader hostLoader = button.getClass().getClassLoader();
            final Class<?> function1 = Class.forName(FUNCTION1_CLASS, false, hostLoader);
            final Object handler = Proxy.newProxyInstance(
                function1.getClassLoader(),
                new Class<?>[]{function1},
                (proxy, method, args) -> {
                    if (!method.getName().equals("invoke")) return null;
                    try {
                        contribution.onClick().accept(null);
                    } catch (Throwable failure) {
                        diagnostic("CLICK_FAILED reason=" + failure.getClass().getName());
                    }
                    return null;
                });
            final Method setOnAction = button.getClass().getMethod("setOnAction", function1);
            setOnAction.invoke(button, handler);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new PhaseTagged("SET_ON_ACTION", failure);
        }
    }

    private static void setupTooltip(final Object button, final String tooltip) {
        try {
            final ClassLoader hostLoader = button.getClass().getClassLoader();
            final Class<?> function0 = Class.forName(
                "kotlin.jvm.functions.Function0", false, hostLoader);
            final Object supplier = Proxy.newProxyInstance(
                function0.getClassLoader(),
                new Class<?>[]{function0},
                (proxy, method, args) -> method.getName().equals("invoke") ? tooltip : null);
            final Method setToolTipText = button.getClass()
                .getMethod("setToolTipText", function0);
            setToolTipText.invoke(button, supplier);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new PhaseTagged("TOOLTIP", failure);
        }
    }

    /** Disables the click path of a removed button without touching the strip layout. */
    private static void setButtonResponding(final Object button, final boolean responding) {
        try {
            final ClassLoader hostLoader = button.getClass().getClassLoader();
            final Class<?> function1 = Class.forName(FUNCTION1_CLASS, false, hostLoader);
            final Object noop = Proxy.newProxyInstance(
                function1.getClassLoader(),
                new Class<?>[]{function1},
                (proxy, method, args) -> null);
            final Method setOnAction = button.getClass().getMethod("setOnAction", function1);
            setOnAction.invoke(button, noop);
        } catch (Throwable failure) {
            diagnostic("SET_RESPONDING_FAILED reason=" + failure.getClass().getName());
        }
    }

    /** Carries the reflective phase that failed, for mount diagnostics. */
    public static final class PhaseTagged extends RuntimeException {
        public final String phase;

        PhaseTagged(final String phase, final Throwable cause) {
            super(phase + ": " + cause, cause);
            this.phase = phase;
        }
    }

    private static void diagnostic(final String stage) {
        try {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                "warp-alt-mirror", "STRIP_DIAG stage=" + stage);
        } catch (Throwable ignored) {
            // never reach the host call site
        }
    }
}
