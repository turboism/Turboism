package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
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
 * Runtime implementation of the modeling-mode tool-strip button surface, built
 * reflectively against the reviewed 5.3.03 host classes.
 *
 * <p>Target strip: {@code CEMainFrameViewCtrl.toolSet_modelingMode} — the
 * horizontal widget bar below the workspace tabs and above the canvas, whose
 * controls include the glue toggle ({@code toolGroup_glue}, CIconToggleButton).
 * Contributed buttons are host {@code CButton} text widgets inserted
 * immediately LEFT of the glue toggle through {@code CContainer.add(CWidget,
 * int)}; they resolve through the reviewed alias chain
 * (app instance -> main frame -> main-frame view) via the bound
 * {@link VerifiedMemberResolver}.</p>
 */
public final class RuntimeViewContextMenuRegistry implements ViewContextMenuRegistry {

    private static final RuntimeViewContextMenuRegistry INSTANCE = new RuntimeViewContextMenuRegistry();

    /** Reviewed 5.3.03 selectors (disassembly-verified). */
    private static final String APP_INSTANCE_ALIAS = "cubism.ui-main-toolbar.app-controller.instance";
    private static final String APP_MAIN_FRAME_ALIAS = "cubism.ui-main-toolbar.app-controller.main-frame";
    private static final String MAIN_FRAME_VIEW_ALIAS = "cubism.ui-main-toolbar.main-frame.view";
    private static final String TOOLSET_FIELD = "toolSet_modelingMode";
    private static final String GLUE_FIELD = "toolGroup_glue";
    private static final String WIDGET_CLASS = "com.live2d.ui.CWidget";
    private static final String BUTTON_CLASS = "com.live2d.ui.control.CButton";
    private static final String ICON_CLASS = "com.live2d.type.CIcon";
    private static final String FUNCTION1_CLASS = "kotlin.jvm.functions.Function1";
    private static final String AXIS_BUTTON_ID = "warp-deformer-alt-symmetry.axis";

    private final Object lock = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> pendingBuilds = new ArrayList<>();
    private VerifiedMemberResolver resolver;
    private Object toolSet;
    private boolean mountAttempted;

    private record Entry(ViewContextMenuRegistry.ButtonContribution contribution, Object item) { }

    public static RuntimeViewContextMenuRegistry getInstance() {
        return INSTANCE;
    }

    private RuntimeViewContextMenuRegistry() { }

    /**
     * Binds the verified host resolver (same reviewed alias table as the
     * horizontal-toolbar operations) and mounts any queued contributions.
     */
    public void bindResolver(final VerifiedMemberResolver verifiedResolver) {
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

    @Override
    public Registration contributeButton(final ViewContextMenuRegistry.ButtonContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        final Runnable build = () -> buildAndMount(contribution);
        synchronized (lock) {
            if (mountAttempted) {
                build.run();
            } else {
                pendingBuilds.add(build);
            }
        }
        return () -> {
            synchronized (lock) {
                final Entry entry = entries.remove(contribution.contributionId());
                if (entry != null) {
                    removeFromToolSet(entry.item());
                }
            }
        };
    }

    @Override
    public void setText(final String contributionId, final String text) {
        synchronized (lock) {
            final Entry entry = entries.get(contributionId);
            if (entry == null || entry.item() == null) return;
            try {
                final Method setText = entry.item().getClass().getMethod("setText", String.class);
                setText.invoke(entry.item(), text);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnostic("SET_TEXT_FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    private void buildAndMount(final ViewContextMenuRegistry.ButtonContribution contribution) {
        try {
            if (resolver == null) {
                diagnostic("BUILD_SKIPPED reason=NO_RESOLVER");
                return;
            }
            final Object item = createButton(contribution);
            insertLeftOfGlue(item);
            entries.put(contribution.contributionId(), new Entry(contribution, item));
            diagnostic("BUTTON_MOUNTED id=" + contribution.contributionId()
                + " text=" + contribution.text());
        } catch (Throwable failure) {
            if (hostNotReady(failure)) {
                diagnostic("HOST_NOT_READY retry=" + retryCount.get());
                scheduleRetry(contribution);
                return;
            }
            final String phase = failure instanceof PhaseTagged tagged ? tagged.phase : "UNKNOWN";
            diagnostic("BUTTON_MOUNT_FAILED phase=" + phase
                + " reason=" + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private static boolean hostNotReady(final Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof dev.turboism.mapping.verification.VerifiedAccessException) {
                return true;
            }
        }
        return false;
    }

    private final java.util.concurrent.atomic.AtomicInteger retryCount =
        new java.util.concurrent.atomic.AtomicInteger();

    private void scheduleRetry(final ViewContextMenuRegistry.ButtonContribution contribution) {
        final int attempt = retryCount.incrementAndGet();
        if (attempt > 120) {
            diagnostic("RETRY_GAVE_UP after=" + attempt);
            return;
        }
        final javax.swing.Timer timer = new javax.swing.Timer(1_000, ev -> {
            synchronized (lock) {
                if (entries.containsKey(contribution.contributionId())) return;
            }
            buildAndMount(contribution);
        });
        timer.setRepeats(false);
        timer.start();
    }

    /** Builds the host CButton text widget through the host class loader. */
    private Object createButton(final ViewContextMenuRegistry.ButtonContribution contribution)
        throws ReflectiveOperationException {
        final ClassLoader hostLoader = resolver.hostClassLoader();
        final Class<?> itemClass = Class.forName(BUTTON_CLASS, false, hostLoader);
        final Class<?> iconClass = Class.forName(ICON_CLASS, false, hostLoader);
        final Class<?> widgetClass = Class.forName(WIDGET_CLASS, false, hostLoader);
        final Class<?> eventClass = Class.forName("com.live2d.ui.event.a", false, hostLoader);
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

        // CButton(String text, CIcon icon, Function1<ui.event.a, Unit> handler)
        return itemClass.getConstructor(String.class, iconClass, function1)
            .newInstance(contribution.text(), null, handler);
    }

    /** Inserts the built button into toolSet_modelingMode immediately left of the glue toggle. */
    private void insertLeftOfGlue(final Object item) throws ReflectiveOperationException {
        final Object toolSetInstance = toolSet();
        final ClassLoader hostLoader = resolver.hostClassLoader();
        final Class<?> widgetClass = Class.forName(WIDGET_CLASS, false, hostLoader);
        final Object glue = toolSetInstance.getClass().getField(GLUE_FIELD).get(toolSetInstance);
        final Method getIndexOf = toolSetInstance.getClass().getMethod("getIndexOf", widgetClass);
        final int glueIndex = ((Number) getIndexOf.invoke(toolSetInstance, glue)).intValue();
        final Method add = toolSetInstance.getClass().getMethod("add", widgetClass, int.class);
        add.invoke(toolSetInstance, item, Math.max(0, glueIndex));
        diagnostic("INSERTED index=" + Math.max(0, glueIndex) + " text=" + contributionText(item));
    }

    private static String contributionText(final Object item) {
        try {
            final Object value = item.getClass().getMethod("getText").invoke(item);
            return String.valueOf(value);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private void removeFromToolSet(final Object item) {
        try {
            final Object toolSetInstance = toolSet();
            final ClassLoader hostLoader = resolver.hostClassLoader();
            final Method remove = toolSetInstance.getClass().getMethod(
                "remove", Class.forName(WIDGET_CLASS, false, hostLoader));
            remove.invoke(toolSetInstance, item);
        } catch (Throwable failure) {
            diagnostic("REMOVE_FAILED reason=" + failure.getClass().getName());
        }
    }

    /** Resolves the modeling-mode CHBox via the reviewed alias chain. */
    private Object toolSet() throws ReflectiveOperationException {
        synchronized (lock) {
            if (toolSet != null) return toolSet;
            final Object viewCtrl = mainFrameView();
            final Field field = viewCtrl.getClass().getField(TOOLSET_FIELD);
            final Object resolved = field.get(viewCtrl);
            if (resolved == null) {
                throw new IllegalStateException("toolSet_modelingMode is not initialised");
            }
            toolSet = resolved;
            return toolSet;
        }
    }

    /**
     * Walks the reviewed alias chain used by the horizontal-toolbar operations:
     * app instance -> app main frame -> main-frame view (CEMainFrameViewCtrl).
     */
    private Object mainFrameView() throws ReflectiveOperationException {
        if (resolver == null) {
            throw new IllegalStateException("verified resolver is not bound");
        }
        final Object appInstance = resolver.invokeStatic(APP_INSTANCE_ALIAS);
        final Object mainFrame = resolver.invoke(APP_MAIN_FRAME_ALIAS, appInstance);
        return resolver.readField(MAIN_FRAME_VIEW_ALIAS, mainFrame);
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
