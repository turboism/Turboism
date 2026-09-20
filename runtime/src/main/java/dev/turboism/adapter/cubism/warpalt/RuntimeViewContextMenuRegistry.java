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
 * Runtime implementation of the canvas-top GL strip text-button surface, built
 * reflectively against the reviewed 5.3.03 host classes.
 *
 * <p>Target: the GL-drawn view context strip {@code a.b}. The strip's H group
 * only accepts {@code GToggleIconButtonEntity} (icon buttons), so the
 * contributed control is a {@code GToggleButtonEntity} (text toggle button)
 * mounted directly into the scene graph after the strip's caret (▼) button.
 * The strip's per-frame layout {@code a(N, GEntity)} re-positions the button
 * each frame via a tail-injected hook. The button's text shows the armed-axis
 * i18n label and cycles on click, with native hover/selected/pressed
 * backgrounds from {@code AGSimpleButtonEntity}.</p>
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
    private static final float BUTTON_GAP = 6f;
    private static final int BUTTON_WIDTH = 88;
    private static final int BUTTON_HEIGHT = 24;

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> pendingBuilds = new ArrayList<>();
    private Object strip;
    private boolean mountAttempted;

    private record Entry(
        ViewContextMenuRegistry.ButtonContribution contribution,
        Object button
    ) { }

    public static RuntimeViewContextMenuRegistry getInstance() {
        return INSTANCE;
    }

    private RuntimeViewContextMenuRegistry() { }

    /**
     * Injected at the head of the strip's mount routine with the strip instance.
     * Stores the strip and builds any contributions that queued before the view
     * existed. Idempotent across repeated mount calls.
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
     * Injected at the tail of the strip's per-frame re-layout. Positions each
     * contributed text button to the right of the strip's last (caret) button.
     */
    public void positionStripButton(final Object stripInstance) {
        synchronized (lock) {
            if (stripInstance == null || stripInstance != strip || entries.isEmpty()) return;
            try {
                final Object caret = lastStripButton(stripInstance);
                if (caret == null) return;
                final Method getRect = caret.getClass()
                    .getMethod("getBoundsOnComponent", float.class);
                final Object caretRect = getRect.invoke(caret, 1.0f);
                if (caretRect == null) return;
                final float x = floatField(caretRect, "x")
                    + floatField(caretRect, "w") + BUTTON_GAP;
                final float y = floatField(caretRect, "y");
                final Class<?> rectClass = Class.forName(RECT_CLASS, false,
                    stripInstance.getClass().getClassLoader());
                final Object bounds = rectClass.getConstructor(
                    float.class, float.class, float.class, float.class)
                    .newInstance(x, y, (float) BUTTON_WIDTH, (float) BUTTON_HEIGHT);
                for (final Entry entry : entries.values()) {
                    final Method setBounds = entry.button().getClass()
                        .getMethod("setBoundsOnComponent", rectClass, float.class);
                    setBounds.invoke(entry.button(), bounds, 1.0f);
                }
            } catch (Throwable failure) {
                diagnostic("POSITION_FAILED reason=" + failure.getClass().getName());
            }
        }
    }

    private static float floatField(final Object rect, final String name)
        throws ReflectiveOperationException {
        final Field field = rect.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(rect);
    }

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
                final Entry entry = entries.remove(contribution.contributionId());
                if (entry != null) {
                    removeFromSceneGraph(entry.button());
                }
            }
        };
    }

    @Override
    public void setText(final String contributionId, final String text) {
        synchronized (lock) {
            final Entry entry = entries.get(contributionId);
            if (entry == null) return;
            try {
                final Method setText = entry.button().getClass().getMethod("setText", String.class);
                setText.invoke(entry.button(), text);
                diagnostic("BUTTON_TEXT_SET text=" + text);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnostic("SET_TEXT_FAILED " + failure.getClass().getSimpleName());
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

            final Object button = buttonClass
                .getConstructor(String.class, fontClass, rectClass)
                .newInstance(contribution.text(), font, rect);

            setOnAction(button, contribution);
            setupTooltip(button, contribution.tooltip());

            addToSceneGraph(strip, button);
            entries.put(contribution.contributionId(), new Entry(contribution, button));
            diagnostic("BUTTON_MOUNTED id=" + contribution.contributionId()
                + " text=" + contribution.text());
        } catch (Throwable failure) {
            final String phase = failure instanceof PhaseTagged tagged ? tagged.phase : "UNKNOWN";
            diagnostic("BUTTON_MOUNT_FAILED phase=" + phase
                + " reason=" + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private static void setOnAction(
        final Object button,
        final ViewContextMenuRegistry.ButtonContribution contribution
    ) throws ReflectiveOperationException {
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
    }

    private static void setupTooltip(final Object button, final String tooltip)
        throws ReflectiveOperationException {
        final ClassLoader hostLoader = button.getClass().getClassLoader();
        final Class<?> function0 = Class.forName(
            "kotlin.jvm.functions.Function0", false, hostLoader);
        final Object supplier = Proxy.newProxyInstance(
            function0.getClassLoader(),
            new Class<?>[]{function0},
            (proxy, method, args) -> method.getName().equals("invoke") ? tooltip : null);
        final Method setToolTipText = button.getClass().getMethod(
            "setToolTipText", function0);
        setToolTipText.invoke(button, supplier);
    }

    private static void addToSceneGraph(final Object stripInstance, final Object button)
        throws ReflectiveOperationException {
        final Object sceneGraph = stripInstance.getClass().getMethod("e").invoke(stripInstance);
        final Object objectsOnComponent = sceneGraph.getClass()
            .getMethod("getObjectsOnComponent").invoke(sceneGraph);
        final Object children = objectsOnComponent.getClass()
            .getMethod("getChildren").invoke(objectsOnComponent);
        final Method add = children.getClass().getMethod("add",
            objectsOnComponent.getClass(), int.class);
        add.invoke(children, button, 0);
    }

    private void removeFromSceneGraph(final Object button) {
        try {
            final Object parent = button.getClass()
                .getMethod("getParentEntity").invoke(button);
            if (parent != null) {
                parent.getClass().getMethod("getChildren").invoke(parent)
                    .getClass().getMethod("remove", Object.class)
                    .invoke(parent.getClass().getMethod("getChildren")
                        .invoke(parent), button);
            }
        } catch (Throwable ignored) {
            // never reach the host call site
        }
    }

    private static Object lastStripButton(final Object stripInstance)
        throws ReflectiveOperationException {
        final Field groupField = stripInstance.getClass().getDeclaredField("H");
        groupField.setAccessible(true);
        final Object group = groupField.get(stripInstance);
        if (!(group instanceof List<?> list) || list.isEmpty()) return null;
        return list.get(list.size() - 1);
    }

    private static void diagnostic(final String stage) {
        try {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                "warp-alt-mirror", "STRIP_DIAG stage=" + stage);
        } catch (Throwable ignored) {
            // never reach the host call site
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
}
