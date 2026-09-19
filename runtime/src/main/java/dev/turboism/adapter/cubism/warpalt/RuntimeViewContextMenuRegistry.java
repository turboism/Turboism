package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
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
 * Runtime implementation of the canvas-top GL strip state-button surface, built
 * reflectively against the reviewed 5.3.03 host classes.
 *
 * <p>Target: the GL-drawn view context strip {@code a.b} (the bar hosting the
 * "Lock Drawable Object" toggle and the display-visibility buttons). The strip's
 * H group only accepts {@code GToggleIconButtonEntity}, so the contributed
 * control is one toggle button per provided state, each carrying the
 * plugin-provided icon wrapped into a generated {@code q} icon set
 * ({@code CWritableImage} from the plugin PNG). Buttons join one exclusive
 * {@code CButtonGroup} (host radio behavior) and mount into H through the
 * strip's own {@code R()} routine, so per-frame layout, mode visibility and
 * scene-graph hit testing all come from the host.</p>
 */
public final class RuntimeViewContextMenuRegistry implements ViewContextMenuRegistry {

    private static final RuntimeViewContextMenuRegistry INSTANCE = new RuntimeViewContextMenuRegistry();

    /** Reviewed 5.3.03 selectors (disassembly-verified). */
    private static final String STRIP_CLASS = "com.live2d.cubism.view.context.a.b";
    private static final String BUTTON_CLASS =
        "com.live2d.cubism.view.context.guiEntity.GToggleIconButtonEntity";
    private static final String ICON_SET_CLASS = "com.live2d.cubism.view.context.guiEntity.q";
    private static final String WRITABLE_CLASS = "com.live2d.graphics.CWritableImage";
    private static final String GROUP_CLASS = "com.live2d.ui.control.CButtonGroup";
    private static final String FUNCTION3_CLASS = "kotlin.jvm.functions.Function3";

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> pendingBuilds = new ArrayList<>();
    private Object strip;
    private boolean mountAttempted;

    private record Entry(
        ViewContextMenuRegistry.StateButtonContribution contribution,
        Map<Integer, Object> buttons
    ) { }

    public static RuntimeViewContextMenuRegistry getInstance() {
        return INSTANCE;
    }

    private VerifiedMemberResolver resolver;

    private RuntimeViewContextMenuRegistry() { }

    /**
     * Binds the verified host resolver (same reviewed alias table as the
     * horizontal-toolbar operations) and mounts any queued contributions into
     * the modeling tool strip.
     */
    public void bindResolver(final dev.turboism.mapping.verification.VerifiedMemberResolver
        verifiedResolver) {
        Objects.requireNonNull(verifiedResolver, "resolver");
        synchronized (lock) {
            resolver = verifiedResolver;
            mountAttempted = true;
            final List<Runnable> queued = new ArrayList<>(pendingBuilds);
            pendingBuilds.clear();
            for (final Runnable build : queued) {
                build.run();
            }
        }
        diagnostic("RESOLVER_BOUND");
    }

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
                entries.remove(contribution.contributionId());
            }
        };
    }

    /**
     * Programmatically selects a contributed state button (keyboard sync).
     */
    public void selectState(final String contributionId, final int state) {
        synchronized (lock) {
            final Entry entry = entries.get(contributionId);
            if (entry == null) return;
            final Object target = entry.buttons().get(state);
            if (target == null) return;
            try {
                final ClassLoader hostLoader = target.getClass().getClassLoader();
                final Class<?> buttonClass = Class.forName(BUTTON_CLASS, false, hostLoader);
                final Method setSelected = buttonClass.getMethod("setSelected", boolean.class);
                for (final Map.Entry<Integer, Object> slot : entry.buttons().entrySet()) {
                    setSelected.invoke(slot.getValue(), slot.getKey() == state);
                }
                diagnostic("STATE_SELECTED state=" + state);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnostic("SET_STATE_FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    private void buildAndMount(final ViewContextMenuRegistry.StateButtonContribution contribution) {
        try {
            if (strip == null) {
                diagnostic("BUILD_SKIPPED reason=NO_STRIP");
                return;
            }
            final Object group = exclusiveGroup(strip);
            final Map<Integer, Object> stateButtons = new LinkedHashMap<>();
            for (final Map.Entry<Integer, BufferedImage> state
                : contribution.stateIcons().entrySet()) {
                final Object button = createButton(strip, contribution,
                    state.getValue(), state.getKey(), group);
                insertIntoGroup(strip, button);
                stateButtons.put(state.getKey(), button);
            }
            entries.put(contribution.contributionId(),
                new Entry(contribution, stateButtons));
            diagnostic("BUTTONS_MOUNTED id=" + contribution.contributionId()
                + " count=" + stateButtons.size());
        } catch (Throwable failure) {
            final String phase = failure instanceof PhaseTagged tagged ? tagged.phase : "UNKNOWN";
            diagnostic("BUTTON_MOUNT_FAILED phase=" + phase
                + " reason=" + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    /** Replicates the host's own lock-button construction with a plugin-provided icon. */
    private Object createButton(
        final Object stripInstance,
        final ViewContextMenuRegistry.StateButtonContribution contribution,
        final BufferedImage iconImage,
        final int state,
        final Object group
    ) throws ReflectiveOperationException {
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

        final Object iconSet = iconSetFor(iconImage, hostLoader);
        final Object button = factory.invoke(null,
            stripInstance, "warpAltMirrorAxis" + state, null, null, false, false, iconSet, 30, null);

        setOnAction(button, contribution, state);
        joinExclusiveGroup(button, group);
        return button;
    }

    /** Assembles a seven-slot icon set from one state image (all variants identical). */
    private Object iconSetFor(final BufferedImage image, final ClassLoader hostLoader)
        throws ReflectiveOperationException {
        final Class<?> writableClass = Class.forName(WRITABLE_CLASS, false, hostLoader);
        final Class<?> setClass = Class.forName(
            "com.live2d.cubism.view.context.guiEntity.q", false, hostLoader);

        final Object writable = writableClass.getConstructor(BufferedImage.class)
            .newInstance(image);
        return setClass.getConstructor(writableClass, writableClass, writableClass,
            writableClass, writableClass, writableClass, writableClass)
            .newInstance(writable, writable, writable, writable, writable, writable, writable);
    }

    private Object exclusiveGroup(final Object stripInstance)
        throws ReflectiveOperationException {
        final ClassLoader hostLoader = stripInstance.getClass().getClassLoader();
        final Class<?> groupClass = Class.forName(GROUP_CLASS, false, hostLoader);
        return groupClass.getConstructor().newInstance();
    }

    private static void joinExclusiveGroup(final Object button, final Object group)
        throws ReflectiveOperationException {
        final Method setButtonGroup = button.getClass()
            .getMethod("setButtonGroup", group.getClass());
        setButtonGroup.invoke(button, group);
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
                    contribution.onClick().accept(state);
                } catch (Throwable failure) {
                    diagnostic("CLICK_FAILED reason=" + failure.getClass().getName());
                }
                return null;
            });
        final Method setOnAction = button.getClass().getMethod("setOnAction", function3);
        setOnAction.invoke(button, handler);
    }

    private static void insertIntoGroup(final Object stripInstance, final Object button)
        throws ReflectiveOperationException {
        final Field groupField = stripInstance.getClass().getDeclaredField("H");
        groupField.setAccessible(true);
        final Object group = groupField.get(stripInstance);
        if (!(group instanceof List<?> list)) {
            throw new IllegalStateException("strip group H is not a list");
        }
        if (list.contains(button)) return;
        ((List<Object>) group).add(Math.min(2, list.size()), button);
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
