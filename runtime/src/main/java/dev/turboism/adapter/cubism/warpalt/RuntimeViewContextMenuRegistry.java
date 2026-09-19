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

/**
 * Runtime implementation of the canvas-strip text-button surface, built entirely
 * reflectively against the reviewed 5.3.03 host classes (GToggleButtonEntity).
 *
 * <p>The strip mounts its own buttons in {@code a.b.R()}; the injected hook calls
 * {@link #mount(Object)} at that point with the strip instance. Plugins usually
 * contribute before the modeling view exists, so contributions queue here and
 * build on mount; anything contributed afterwards builds in place.</p>
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
    private static final int BUTTON_WIDTH = 88;
    private static final int BUTTON_HEIGHT = 24;

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> pendingBuilds = new ArrayList<>();
    private Object strip;
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
                final Entry entry = entries.get(contribution.contributionId());
                if (entry != null && entry.button() != null) {
                    setButtonResponding(entry.button(), false);
                }
            }
        };
    }

    @Override
    public void setText(final String contributionId, final String text) {
        synchronized (lock) {
            final Entry entry = entries.get(contributionId);
            if (entry == null || entry.button() == null) return;
            try {
                final ClassLoader hostLoader = entry.button().getClass().getClassLoader();
                final Method setText = entry.button().getClass()
                    .getMethod("setText", String.class);
                setText.invoke(entry.button(), text);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnostic("SET_TEXT_FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void setSelected(final String contributionId, final boolean selected) {
        synchronized (lock) {
            final Entry entry = entries.get(contributionId);
            if (entry == null || entry.button() == null) return;
            try {
                final Method setSelected = entry.button().getClass()
                    .getMethod("setButtonSelected", boolean.class);
                setSelected.invoke(entry.button(), selected);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnostic("SET_SELECTED_FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Injected at the head of the strip's mount routine with the strip instance.
     * Stores the strip and builds any contributions that queued before the view
     * existed. Idempotent across repeated mount calls.
     */
    public void mount(final Object stripInstance) {
        final int queuedCount;
        synchronized (lock) {
            if (stripInstance == null) return;
            strip = stripInstance;
            mountAttempted = true;
            queuedCount = pendingBuilds.size();
            final List<Runnable> queued = new ArrayList<>(pendingBuilds);
            pendingBuilds.clear();
            for (final Runnable build : queued) {
                build.run();
            }
        }
        diagnostic("STRIP_MOUNTED queueSize=" + queuedCount
            + " instance=" + Integer.toHexString(System.identityHashCode(this)));
    }

    private void buildAndMount(final ViewContextMenuRegistry.ButtonContribution contribution) {
        try {
            if (strip == null) {
                diagnostic("BUILD_SKIPPED reason=NO_STRIP");
                return;
            }
            final Object button = createButton(strip, contribution);
            insertIntoGroup(strip, button);
            entries.put(contribution.contributionId(), new Entry(contribution, button));
            diagnostic("BUTTON_MOUNTED id=" + contribution.contributionId()
                + " text=" + contribution.text());
        } catch (Throwable failure) {
            final String phase = failure instanceof PhaseTagged tagged ? tagged.phase : "UNKNOWN";
            diagnostic("BUTTON_MOUNT_FAILED phase=" + phase
                + " reason=" + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    /** Replicates the host's text-button construction with the plugin-provided text. */
    private static Object createButton(
        final Object stripInstance,
        final ViewContextMenuRegistry.ButtonContribution contribution
    ) {
        try {
            final ClassLoader hostLoader = stripInstance.getClass().getClassLoader();
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

            setOnAction(stripInstance, button, contribution);
            setupTooltip(stripInstance, button, contribution.tooltip());
            return button;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (failure instanceof PhaseTagged tagged) {
                throw tagged;
            }
            throw new PhaseTagged("CREATE", failure);
        }
    }

    private static void setOnAction(
        final Object stripInstance,
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

    private static void setupTooltip(
        final Object stripInstance,
        final Object button,
        final String tooltip
    ) {
        try {
            final ClassLoader hostLoader = button.getClass().getClassLoader();
            final Class<?> buttonEntityClass = Class.forName(
                "com.live2d.cubism.view.context.guiEntity.AGButtonEntity", false, hostLoader);
            final Class<?> function0 = Class.forName(
                "kotlin.jvm.functions.Function0", false, hostLoader);
            final Object supplier = Proxy.newProxyInstance(
                function0.getClassLoader(),
                new Class<?>[]{function0},
                (proxy, method, args) -> method.getName().equals("invoke") ? tooltip : null);
            final Method setToolTipText = buttonEntityClass.getMethod(
                "setToolTipText", function0);
            setToolTipText.invoke(button, supplier);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new PhaseTagged("TOOLTIP", failure);
        }
    }

    /** Inserts the button into the top-left group right after the host lock button. */
    private static void insertIntoGroup(final Object stripInstance, final Object button) {
        try {
            final Field groupField = stripInstance.getClass().getDeclaredField("H");
            groupField.setAccessible(true);
            final Object group = groupField.get(stripInstance);
            if (!(group instanceof List<?> list)) {
                throw new IllegalStateException("strip group H is not a list");
            }
            if (list.contains(button)) return;
            ((List<Object>) group).add(Math.min(2, list.size()), button);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new PhaseTagged("INSERT", failure);
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

    private static Class<?> hostClass(final ClassLoader hostLoader, final String name)
        throws ClassNotFoundException {
        if (name.equals("java.lang.String")) return String.class;
        return Class.forName(name, false, hostLoader);
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
            dev.turboism.runtime.log.RuntimeDiagnostics.info("warp-alt-mirror", "STRIP_DIAG stage=" + stage);
        } catch (Throwable ignored) {
            // never reach the host call site
        }
    }
}
