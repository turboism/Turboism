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
 * Runtime implementation of the canvas-strip button surface, built entirely
 * reflectively against the reviewed 5.3.03 host classes.
 *
 * <p>The strip mounts its own buttons in {@code a.b.R()}; the injected hook
 * calls {@link #mount(Object)} at that point with the strip instance. Plugins
 * usually contribute before the modeling view exists, so contributions queue
 * here and build on mount; anything contributed afterwards builds in place.</p>
 */
public final class RuntimeViewContextMenuRegistry implements ViewContextMenuRegistry {

    private static final RuntimeViewContextMenuRegistry INSTANCE = new RuntimeViewContextMenuRegistry();

    /** Reviewed 5.3.03 selectors (disassembly-verified). */
    private static final String BAR_CLASS = "com.live2d.cubism.view.context.a.b";
    private static final String BUTTON_CLASS = "com.live2d.cubism.view.context.guiEntity.GToggleIconButtonEntity";
    private static final String BUTTON_NAME = "WARP_ALT_MIRROR_AXIS";
    private static final int INSERT_INDEX = 2; // right after H[0] separator + H[1] lock button

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> pendingBuilds = new ArrayList<>();
    private Object bar;
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
            if (mountAttempted && bar != null) {
                build.run();
            } else {
                pendingBuilds.add(build);
            }
        }
        return () -> {
            synchronized (lock) {
                entries.remove(contribution.contributionId());
                // Host-side removal is not supported by the strip's static layout;
                // the button stays until the view is disposed. Consumers should
                // treat removal as "stop responding to clicks".
                final Entry entry = entries.get(contribution.contributionId());
                if (entry != null && entry.button() != null) {
                    setButtonResponding(entry.button(), false);
                }
            }
        };
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
     * Stores the bar and builds any contributions that queued before the view
     * existed. Idempotent across repeated mount calls.
     */
    public void mount(final Object strip) {
        synchronized (lock) {
            if (strip == null) return;
            bar = strip;
            mountAttempted = true;
            final List<Runnable> queued = new ArrayList<>(pendingBuilds);
            pendingBuilds.clear();
            for (final Runnable build : queued) {
                build.run();
            }
        }
        diagnostic("STRIP_MOUNTED pendingBuilt");
    }

    private void buildAndMount(final ViewContextMenuRegistry.ButtonContribution contribution) {
        try {
            if (bar == null) {
                diagnostic("BUILD_SKIPPED reason=NO_BAR");
                return;
            }
            final Object button = createButton(bar, contribution);
            insertIntoGroup(bar, button);
            entries.put(contribution.contributionId(), new Entry(contribution, button));
            diagnostic("BUTTON_MOUNTED id=" + contribution.contributionId());
        } catch (Throwable failure) {
            diagnostic("BUTTON_MOUNT_FAILED reason=" + failure.getClass().getName());
        }
    }

    /** Replicates the host's own lock-button construction through the private factory. */
    private static Object createButton(
        final Object strip,
        final ViewContextMenuRegistry.ButtonContribution contribution
    ) throws ReflectiveOperationException {
        final Class<?> barClass = strip.getClass();
        final Class<?> factoryParameterTypes = barClass;

        // private static GToggleIconButtonEntity a(b, String, CFont, GRectF, Z, Z, q, int, Object)
        Method factory = null;
        for (final Method method : barClass.getDeclaredMethods()) {
            if (!method.getName().equals("a")
                || method.getParameterCount() != 9
                || method.getReturnType() != Class.forName(BUTTON_CLASS)) {
                continue;
            }
            final Class<?>[] types = method.getParameterTypes();
            if (types[0] == factoryParameterTypes
                && types[1] == String.class
                && types[3] == Class.forName("com.live2d.graphics3d.type.GRectF")
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

        final Object iconSet = defaultIconSet(barClass);
        final Object button = factory.invoke(null,
            strip, contribution.name(), null, null, false, false, iconSet, 30, null);

        setOnAction(button, contribution);
        setupTooltip(strip, button, contribution);
        return button;
    }

    /** Icon set of the reviewed 5.3.03 strip: static b$a singleton, accessor c(). */
    private static Object defaultIconSet(final Class<?> barClass)
        throws ReflectiveOperationException {
        final Field singleton = barClass.getDeclaredField("a");
        singleton.setAccessible(true);
        final Object iconRegistry = singleton.get(null);
        final Method accessor = iconRegistry.getClass().getMethod("c");
        return accessor.invoke(iconRegistry);
    }

    /** Click cycle is owned by the contributing consumer; selected visual follows. */
    private static void setOnAction(
        final Object button,
        final ViewContextMenuRegistry.ButtonContribution contribution
    ) throws ReflectiveOperationException {
        final Class<?> function3 = Class.forName("kotlin.jvm.functions.Function3");
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
        final Method setOnAction = button.getClass()
            .getMethod("setOnAction", function3);
        setOnAction.invoke(button, handler);
    }

    /** Tooltip via the host helper on the pack reachable from the strip. */
    private static void setupTooltip(
        final Object strip,
        final Object button,
        final ViewContextMenuRegistry.ButtonContribution contribution
    ) {
        try {
            final Method packAccessor = strip.getClass().getMethod("d");
            final Object pack = packAccessor.invoke(strip);
            final Method setup = pack.getClass().getMethod(
                "setupToolTipTextGToggleButton",
                Class.forName(BUTTON_CLASS),
                String.class, String.class, String.class);
            setup.invoke(null, button,
                contribution.tooltipTitle(), contribution.tooltipDescription(), "");
        } catch (Throwable failure) {
            diagnostic("TOOLTIP_SETUP_FAILED reason=" + failure.getClass().getName());
        }
    }

    /** Inserts the button into the top-left group at the reviewed index. */
    private static void insertIntoGroup(final Object strip, final Object button)
        throws ReflectiveOperationException {
        final Field groupField = strip.getClass().getDeclaredField("H");
        groupField.setAccessible(true);
        final Object group = groupField.get(strip);
        if (!(group instanceof List<?> list)) {
            throw new IllegalStateException("strip group H is not a list");
        }
        if (list.contains(button)) return;
        ((List<Object>) group).add(Math.min(INSERT_INDEX, list.size()), button);
    }

    /** Disables the click path of a removed button without touching the strip layout. */
    private static void setButtonResponding(final Object button, final boolean responding) {
        try {
            final Class<?> function3 = Class.forName("kotlin.jvm.functions.Function3");
            final Object noop = Proxy.newProxyInstance(
                function3.getClassLoader(),
                new Class<?>[]{function3},
                (proxy, method, args) -> null);
            final Method setOnAction = button.getClass().getMethod("setOnAction", function3);
            setOnAction.invoke(button, responding ? null : noop);
        } catch (Throwable failure) {
            diagnostic("SET_RESPONDING_FAILED reason=" + failure.getClass().getName());
        }
    }

    private static void diagnostic(final String stage) {
        try {
            NativeWarpAltMirrorBridge.diagStrip(stage);
        } catch (Throwable ignored) {
            // never reach the host call site
        }
    }
}
