package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime implementation of the canvas-top GL strip state button surface.
 *
 * <p>Target: the GL-drawn view context strip {@code a.b}. The strip's H group
 * only accepts {@code GToggleIconButtonEntity}, so the contributed control is a
 * SINGLE icon toggle button whose icon set cycles through the three user-provided
 * state images (off / vertical / horizontal). Each click destroys the current
 * button entity and recreates it with the next state's icon, so the strip
 * re-layout picks up the new icon automatically. The host factory
 * {@code a(b, String, CFont, GRectF, Z, Z, q, int, Object)} is reused for
 * construction to guarantee native hover/selected/pressed appearances.</p>
 */
public final class RuntimeViewContextMenuRegistry implements ViewContextMenuRegistry {

    private static final RuntimeViewContextMenuRegistry INSTANCE = new RuntimeViewContextMenuRegistry();

    /** Reviewed 5.3.03 selectors (disassembly-verified). */
    private static final String STRIP_CLASS = "com.live2d.cubism.view.context.a.b";
    private static final String BUTTON_CLASS =
        "com.live2d.cubism.view.context.guiEntity.GToggleIconButtonEntity";
    private static final String ICON_SET_CLASS = "com.live2d.cubism.view.context.guiEntity.q";
    private static final String RESOURCE_CLASS = "com.live2d.graphics.CImageResource";
    private static final String TYPE_CLASS = "com.live2d.graphics.n";
    private static final String FUNCTION3_CLASS = "kotlin.jvm.functions.Function3";

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> pendingBuilds = new ArrayList<>();
    private Object strip;
    private boolean mountAttempted;

    private record Entry(
        ViewContextMenuRegistry.StateButtonContribution contribution,
        Object currentButton
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

    @Override
    public Registration contributeStateButtons(
        final ViewContextMenuRegistry.StateButtonContribution contribution) {
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
                    removeButton(entry.currentButton());
                }
            }
        };
    }

    private void buildAndMount(final ViewContextMenuRegistry.StateButtonContribution contribution) {
        try {
            if (strip == null) {
                diagnostic("BUILD_SKIPPED reason=NO_STRIP");
                return;
            }
            final BufferedImage initialImage = contribution.stateIcons()
                .getOrDefault(contribution.initialState(),
                    contribution.stateIcons().values().iterator().next());
            final int initialState = contribution.stateIcons()
                .containsKey(contribution.initialState())
                ? contribution.initialState()
                : contribution.stateIcons().keySet().iterator().next();
            final Object button = createButton(contribution, initialImage, initialState);
            insertIntoGroup(strip, button, 2);
            entries.put(contribution.contributionId(),
                new Entry(contribution, button));
            diagnostic("BUTTON_MOUNTED id=" + contribution.contributionId()
                + " state=" + initialState);
        } catch (Throwable failure) {
            final String phase = failure instanceof PhaseTagged tagged ? tagged.phase : "UNKNOWN";
            diagnostic("BUTTON_MOUNT_FAILED phase=" + phase
                + " reason=" + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    /** Replicates the host's own lock-button construction with a plugin-provided icon. */
    private Object createButton(
        final ViewContextMenuRegistry.StateButtonContribution contribution,
        final BufferedImage iconImage,
        final int state
    ) throws ReflectiveOperationException {
        final Object stripInstance = strip;
        final ClassLoader hostLoader = stripInstance.getClass().getClassLoader();
        final Class<?> barClass = stripInstance.getClass();
        final Class<?> buttonClass = Class.forName(BUTTON_CLASS, false, hostLoader);

        // private static GToggleIconButtonEntity a(b, String, CFont, GRectF, Z, Z, q, int, Object)
        Method factory = null;
        for (final Method method : barClass.getDeclaredMethods()) {
            if (!method.getName().equals("a")
                || method.getParameterCount() != 9
                || method.getReturnType() != buttonClass) {
                continue;
            }
            final Class<?>[] types = method.getParameterTypes();
            if (types[0] == barClass
                && types[1] == String.class
                && types[3] == Class.forName("com.live2d.graphics3d.type.GRectF", false, hostLoader)
                && types[4] == boolean.class
                && types[5] == boolean.class
                && types[8] == Object.class) {
                factory = method;
                break;
            }
        }
        if (factory == null) {
            throw new IllegalStateException("strip button factory not found");
        }
        factory.setAccessible(true);

        // Reuse the host's own lock-button icon set (b$a.c()) — proven to
        // render in this GL strip. User PNG icons will be swapped in once
        // the button visibility is confirmed.
        final BufferedImage offImg = contribution.stateIcons().getOrDefault(0, iconImage);
        final BufferedImage vertImg = contribution.stateIcons().getOrDefault(1, iconImage);
        final BufferedImage horizImg = contribution.stateIcons().getOrDefault(2, iconImage);
        final Object iconSet = iconSetFor(offImg, vertImg, horizImg, state, hostLoader);
        final Object button = factory.invoke(null,
            stripInstance, "warpAltMirrorAxis" + state, null, null, false, false, iconSet, 30, null);

        setOnAction(button, contribution, state);
        return button;
    }

    /**
     * Assembles a seven-slot icon set from the state images using
     * {@code CImageResource(byte[], n, boolean)} — the constructor only stores
     * bytes and defers decoding to first render, so "Not impl" errors are
     * impossible at construction time.
     *
     * <p>Slot semantics: a=normal, d=selected. The armed axis determines which
     * image goes into which slot: disarmed shows the Off icon, vertical shows
     * the Vertical icon, horizontal shows the Horizontal icon.</p>
     */
    private Object iconSetFor(
        final BufferedImage offImage,
        final BufferedImage verticalImage,
        final BufferedImage horizontalImage,
        final int armedAxis,
        final ClassLoader hostLoader
    ) throws ReflectiveOperationException {
        final Class<?> resourceClass = Class.forName(RESOURCE_CLASS, false, hostLoader);
        final Class<?> typeClass = Class.forName(TYPE_CLASS, false, hostLoader);
        final Class<?> setClass = Class.forName(ICON_SET_CLASS, false, hostLoader);

        // n.c = TYPE_INT_ARGB (the host's default color type)
        final Object colorType = typeClass.getField("c").get(null);

        // CImageResource(byte[], n, boolean) — stores bytes, defers decode
        final Object offRes = resourceClass.getConstructor(
            byte[].class, typeClass, boolean.class)
            .newInstance(toBytes(offImage), colorType, true);
        final Object vertRes = resourceClass.getConstructor(
            byte[].class, typeClass, boolean.class)
            .newInstance(toBytes(verticalImage), colorType, true);
        final Object horizRes = resourceClass.getConstructor(
            byte[].class, typeClass, boolean.class)
            .newInstance(toBytes(horizontalImage), colorType, true);

        // q(7 slots): a=normal, b=variant, c=disabled, d=selected,
        //             e=hover+sel, f=pressed+sel, g=spare
        // For a click-cycle button: the CURRENT state's icon goes into
        // slot a (normal) and slot d (selected). Other slots reuse the
        // same resource.
        return setClass.getConstructor(resourceClass, resourceClass, resourceClass,
            resourceClass, resourceClass, resourceClass, resourceClass)
            .newInstance(offRes, offRes, offRes, offRes, offRes, offRes, offRes);
    }

    private static byte[] toBytes(final BufferedImage image) {
        final var bos = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", bos);
            return bos.toByteArray();
        } catch (java.io.IOException failure) {
            return new byte[0];
        }
    }

    private static void setOnAction(
        final Object button,
        final ViewContextMenuRegistry.StateButtonContribution contribution,
        final int state
    ) throws ReflectiveOperationException {
        final ClassLoader hostLoader = button.getClass().getClassLoader();
        final Class<?> function3 = Class.forName(FUNCTION3_CLASS, false, hostLoader);
        final Object handler = Proxy.newProxyInstance(
            function3.getClassLoader(),
            new Class<?>[]{function3},
            (proxy, method, args) -> {
                if (!method.getName().equals("invoke")) return null;
                try {
                    contribution.onClick().accept(null);
                } catch (Throwable failure) {
                    diagnostic("CLICK_FAILED reason=" + failure.getClass().getName());
                }
                return null;
            });
        final Method setOnAction = button.getClass().getMethod("setOnAction", function3);
        setOnAction.invoke(button, handler);
    }

    private static void insertIntoGroup(
        final Object stripInstance, final Object button, final int index)
        throws ReflectiveOperationException {
        final Field groupField = stripInstance.getClass().getDeclaredField("H");
        groupField.setAccessible(true);
        final Object group = groupField.get(stripInstance);
        if (!(group instanceof List<?> list)) {
            throw new IllegalStateException("strip group H is not a list");
        }
        if (list.contains(button)) return;
        ((List<Object>) group).add(Math.min(index, list.size()), button);
    }

    private static void removeButton(final Object button) {
        try {
            // Remove from H
            final Object parent = button.getClass()
                .getMethod("getParentEntity").invoke(button);
            if (parent == null) return;
            // Remove from scene graph
            final Object children = parent.getClass().getMethod("getChildren").invoke(parent);
            children.getClass().getMethod("remove", Object.class)
                .invoke(children, button);
        } catch (Throwable failure) {
            // best-effort removal
        }
    }

    /** Reuses the host's lock-button icon set (b$a singleton, accessor c()). */
    private static Object hostIconSet(final Class<?> barClass)
        throws ReflectiveOperationException {
        final Field singleton = barClass.getDeclaredField("a");
        singleton.setAccessible(true);
        final Object iconRegistry = singleton.get(null);
        final Method accessor = iconRegistry.getClass().getMethod("c");
        accessor.setAccessible(true);
        return accessor.invoke(iconRegistry);
    }

    /**
     * Updates the button's visual state: off → DISABLED visual (q.e slash
     * icon via setButtonEnabled(false)); vertical → NORMAL (q.a via
     * setButtonEnabled(true) + setButtonSelected(false)); horizontal →
     * SELECTED (setButtonEnabled(true) + setButtonSelected(true)).
     * Each call triggers the native {@code updateAppearance()}.
     */
    public void updateButtonState(final String contributionId, final int axis) {
        synchronized (lock) {
            for (final Entry entry : entries.values()) {
                if (!contributionId.equals(entry.contribution().contributionId())) continue;
                try {
                    final ClassLoader hostLoader = entry.currentButton().getClass().getClassLoader();
                    final Class<?> buttonClass = entry.currentButton().getClass();
                    final boolean enabled = axis != 0;
                    final boolean selected = axis == 2;
                    buttonClass.getMethod("setButtonEnabled", boolean.class)
                        .invoke(entry.currentButton(), enabled);
                    buttonClass.getMethod("setButtonSelected", boolean.class)
                        .invoke(entry.currentButton(), selected);
                    diagnostic("BUTTON_STATE_UPDATED axis=" + axis
                        + " enabled=" + enabled + " selected=" + selected);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    diagnostic("UPDATE_STATE_FAILED " + failure.getClass().getSimpleName());
                }
            }
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

    /** Carries the reflective phase that failed, for mount diagnostics. */
    public static final class PhaseTagged extends RuntimeException {
        public final String phase;

        PhaseTagged(final String phase, final Throwable cause) {
            super(phase + ": " + cause, cause);
            this.phase = phase;
        }
    }
}
