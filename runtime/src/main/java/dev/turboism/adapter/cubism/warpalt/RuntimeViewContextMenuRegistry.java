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
 * Runtime implementation of the canvas-strip menu-item surface, built entirely
 * reflectively against the reviewed 5.3.03 host classes (CMenuItem + the strip's
 * caret view context menu).
 *
 * <p>The strip's caret menu is created with the strip itself; the injected hook
 * calls {@link #mount(Object)} once the strip exists. Plugins usually contribute
 * before the modeling view exists, so contributions queue here and build on
 * mount; anything contributed afterwards builds in place.</p>
 */
public final class RuntimeViewContextMenuRegistry implements ViewContextMenuRegistry {

    private static final RuntimeViewContextMenuRegistry INSTANCE = new RuntimeViewContextMenuRegistry();

    /** Reviewed 5.3.03 selectors (disassembly-verified). */
    private static final String STRIP_CLASS = "com.live2d.cubism.view.context.a.b";
    private static final String MENU_FIELD = "G";
    private static final String MENU_CLASS = "com.live2d.ui.menu.k";
    private static final String MENU_ITEM_CLASS = "com.live2d.ui.menu.CMenuItem";
    private static final String EVENT_CLASS = "com.live2d.ui.event.a";
    private static final String FUNCTION1_CLASS = "kotlin.jvm.functions.Function1";

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> pendingBuilds = new ArrayList<>();
    private Object menu;
    private boolean mountAttempted;

    private record Entry(ViewContextMenuRegistry.MenuItemContribution contribution, Object item) { }

    public static RuntimeViewContextMenuRegistry getInstance() {
        return INSTANCE;
    }

    private RuntimeViewContextMenuRegistry() { }

    @Override
    public Registration contributeMenuItem(final ViewContextMenuRegistry.MenuItemContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        final Runnable build = () -> buildAndMount(contribution);
        synchronized (lock) {
            if (mountAttempted && menu != null) {
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

    @Override
    public void setText(final String contributionId, final String text) {
        synchronized (lock) {
            final Entry entry = entries.get(contributionId);
            if (entry == null || entry.item() == null) return;
            try {
                final Method getJMenuItem = entry.item().getClass().getMethod("getJMenuItem");
                final Object jMenuItem = getJMenuItem.invoke(entry.item());
                final Method setText = jMenuItem.getClass().getMethod("setText", String.class);
                setText.invoke(jMenuItem, text);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnostic("SET_TEXT_FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Injected at the head of the strip's mount routine with the strip instance.
     * Stores the strip's caret menu and builds any contributions that queued
     * before the view existed. Idempotent across repeated mount calls.
     */
    public void mount(final Object stripInstance) {
        final int queuedCount;
        synchronized (lock) {
            if (stripInstance == null) return;
            if (menu == null) {
                try {
                    final Field menuField = stripInstance.getClass().getDeclaredField(MENU_FIELD);
                    menuField.setAccessible(true);
                    menu = menuField.get(stripInstance);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    diagnostic("MENU_LOOKUP_FAILED reason=" + failure.getClass().getName());
                    return;
                }
            }
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

    private void buildAndMount(final ViewContextMenuRegistry.MenuItemContribution contribution) {
        try {
            if (menu == null) {
                diagnostic("BUILD_SKIPPED reason=NO_MENU");
                return;
            }
            final Object item = createMenuItem(contribution);
            final Method add = menu.getClass().getMethod("a", itemClassOf(menu));
            add.invoke(menu, item);
            entries.put(contribution.contributionId(), new Entry(contribution, item));
            diagnostic("MENU_ITEM_MOUNTED id=" + contribution.contributionId()
                + " text=" + contribution.text());
        } catch (Throwable failure) {
            final String phase = failure instanceof PhaseTagged tagged ? tagged.phase : "UNKNOWN";
            diagnostic("MENU_ITEM_MOUNT_FAILED phase=" + phase
                + " reason=" + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private static Class<?> itemClassOf(final Object menu) throws ClassNotFoundException {
        return Class.forName(MENU_ITEM_CLASS, false, menu.getClass().getClassLoader());
    }

    /** Builds the host CMenuItem reflectively through the host loader. */
    private Object createMenuItem(
        final ViewContextMenuRegistry.MenuItemContribution contribution
    ) throws ReflectiveOperationException {
        final ClassLoader hostLoader = menu.getClass().getClassLoader();
        final Class<?> itemClass = Class.forName(MENU_ITEM_CLASS, false, hostLoader);
        final Class<?> eventClass = Class.forName(EVENT_CLASS, false, hostLoader);
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

        // CMenuItem(String text, CIcon icon = null tolerated, Function1 handler)
        return itemClass.getConstructor(String.class, Class.forName(
            "com.live2d.type.CIcon", false, hostLoader), function1)
            .newInstance(contribution.text(), null, handler);
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
