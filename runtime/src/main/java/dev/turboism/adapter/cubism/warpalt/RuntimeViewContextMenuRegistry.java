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
        Object currentButton,
        Map<Integer, Object> stateEntities
    ) { }

    /** Button plus the self-built per-state icon entities mounted inside it. */
    private record BuiltButton(Object button, Map<Integer, Object> stateEntities) { }

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
            final BuiltButton built = createButton(contribution, initialImage, initialState);
            insertIntoGroup(strip, built.button(), 2);
            entries.put(contribution.contributionId(),
                new Entry(contribution, built.button(), built.stateEntities()));
            applyInitialState(built.button(), built.stateEntities(), initialState);
            diagnostic("BUTTON_MOUNTED id=" + contribution.contributionId()
                + " state=" + initialState
                + " entities=" + built.stateEntities().size());
        } catch (Throwable failure) {
            final String phase = failure instanceof PhaseTagged tagged ? tagged.phase : "UNKNOWN";
            diagnostic("BUTTON_MOUNT_FAILED phase=" + phase
                + " reason=" + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    /** Replicates the host's own lock-button construction with a plugin-provided icon. */
    private BuiltButton createButton(
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

        final BufferedImage offImg = contribution.stateIcons().getOrDefault(0, iconImage);
        final BufferedImage vertImg = contribution.stateIcons().getOrDefault(1, iconImage);
        final BufferedImage horizImg = contribution.stateIcons().getOrDefault(2, iconImage);
        final Object[] resources = stateResources(offImg, vertImg, horizImg, hostLoader);
        final Object iconSet = iconSetFor(resources, hostLoader);
        final Object button = factory.invoke(null,
            stripInstance, "warpAltMirrorAxis" + state, null, null, false, false, iconSet, 30, null);

        final Map<Integer, Object> stateEntities = mountStateIcons(button, resources, hostLoader);
        setOnAction(button, contribution, state);
        return new BuiltButton(button, stateEntities);
    }

    /**
     * Builds one icon entity per state with the host's own icon-entity factory
     * ({@code AGSimpleIconButtonEntity$c.a(CImageResource, String)}), registers
     * each in the button's {@code items} list and scene-graph children, and
     * returns them keyed by state. State visuals are driven by calling
     * {@code setSelected(entity)} — the host enables only the chosen item — so
     * the three-state cycle does not depend on the two-state toggle flags.
     * Entity names must be resolvable by the host's {@code _selectedState}
     * parser: Disabled / Normal / Selected.
     */
    private static Map<Integer, Object> mountStateIcons(
        final Object button,
        final Object[] resources,
        final ClassLoader hostLoader
    ) throws ReflectiveOperationException {
        final Class<?> gEntityClass =
            Class.forName("com.live2d.graphics3d.entity.GEntity", false, hostLoader);
        final Object icon = button.getClass().getMethod("getIcon").invoke(button);
        final Method createIcon = icon.getClass().getMethod("a",
            Class.forName(RESOURCE_CLASS, false, hostLoader), String.class);
        final Object items = button.getClass().getMethod("getItems").invoke(button);
        final Object children = button.getClass().getMethod("getChildren").invoke(button);
        final Method addChild = children.getClass().getMethod("add", gEntityClass, int.class);

        final String[] names = {"Disabled", "Normal", "Selected"};
        final Map<Integer, Object> entities = new LinkedHashMap<>();
        for (int state = 0; state < resources.length; state++) {
            final Object entity = createIcon.invoke(icon, resources[state], names[state]);
            @SuppressWarnings("unchecked")
            final List<Object> itemList = (List<Object>) items;
            itemList.add(entity);
            addChild.invoke(children, entity, 0);
            entities.put(state, entity);
        }
        return entities;
    }

    /**
     * Builds the three state {@code CImageResource}s (off / vertical /
     * horizontal) via {@code CImageResource(byte[], n, boolean)} — the
     * constructor only stores bytes and defers decoding to first render, so
     * "Not impl" errors are impossible at construction time.
     */
    private static Object[] stateResources(
        final BufferedImage offImage,
        final BufferedImage verticalImage,
        final BufferedImage horizontalImage,
        final ClassLoader hostLoader
    ) throws ReflectiveOperationException {
        final Class<?> resourceClass = Class.forName(RESOURCE_CLASS, false, hostLoader);
        final Class<?> typeClass = Class.forName(TYPE_CLASS, false, hostLoader);
        // n.c = TYPE_INT_ARGB (the host's default color type)
        final Object colorType = typeClass.getField("c").get(null);
        final var ctor = resourceClass.getConstructor(
            byte[].class, typeClass, boolean.class);
        return new Object[]{
            ctor.newInstance(toBytes(offImage), colorType, true),
            ctor.newInstance(toBytes(verticalImage), colorType, true),
            ctor.newInstance(toBytes(horizontalImage), colorType, true),
        };
    }

    /**
     * Assembles the seven-slot fallback icon set from the state resources.
     * Primary state visuals are driven by the self-built entities (see
     * {@link #mountStateIcons}); this set only covers transient
     * {@code updateAppearance()} passes triggered by the host's
     * enabled/selected flag writes, so each slot carries a sensible icon:
     *   a=NORMAL(vertical) b=SELECTED(horizontal) c/d=vertical hover/press
     *   e=DISABLED(off) f/g=horizontal hover/press.
     */
    private static Object iconSetFor(
        final Object[] resources,
        final ClassLoader hostLoader
    ) throws ReflectiveOperationException {
        final Class<?> resourceClass = Class.forName(RESOURCE_CLASS, false, hostLoader);
        final Class<?> setClass = Class.forName(ICON_SET_CLASS, false, hostLoader);
        final Object offRes = resources[0];
        final Object vertRes = resources[1];
        final Object horizRes = resources[2];
        return setClass.getConstructor(resourceClass, resourceClass, resourceClass,
            resourceClass, resourceClass, resourceClass, resourceClass)
            .newInstance(vertRes,   // a  NORMAL      (vertical)
                horizRes,           // b  SELECTED    (horizontal)
                vertRes,            // c  ROLLOVER    (vertical hover)
                vertRes,            // d  PRESSED     (vertical press)
                offRes,             // e  DISABLED    (off)
                horizRes,           // f  SELECTEDROLLOVER
                horizRes);          // g  SELECTEDPRESSED
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
                    contribution.onClick().accept(state);
                } catch (Throwable failure) {
                    final StringBuilder sb = new StringBuilder();
                    for (Throwable c = failure; c != null; c = c.getCause()) {
                        if (sb.length() > 0) sb.append(" <- ");
                        sb.append(c.getClass().getName()).append(": ").append(c.getMessage());
                        for (final StackTraceElement st : c.getStackTrace()) {
                            if (st.getClassName().contains("turboism") || st.getClassName().contains("plugin")) {
                                sb.append(" at ").append(st.getClassName()).append(".").append(st.getMethodName()).append(":").append(st.getLineNumber());
                                break;
                            }
                        }
                    }
                    diagnostic("CLICK_FAILED chain=" + sb);
                }
                return null;
            });
        final Method setOnAction = button.getClass().getMethod("setOnAction", function3);
        setOnAction.invoke(button, handler);
    }

    /** Applies the initial state visuals so the state entity shows at mount. */
    private static void applyInitialState(
        final Object button,
        final Map<Integer, Object> stateEntities,
        final int state
    ) throws ReflectiveOperationException {
        final boolean enabled = state != 0;
        final boolean selected = state == 2;
        final Class<?> buttonClass = button.getClass();
        buttonClass.getMethod("setButtonEnabled", boolean.class).invoke(button, enabled);
        buttonClass.getMethod("setButtonSelected", boolean.class).invoke(button, selected);
        final Object entity = stateEntities.get(state);
        if (entity != null) {
            buttonClass.getMethod("setSelected",
                Class.forName("com.live2d.graphics3d.entity.GEntity",
                    false, buttonClass.getClassLoader()))
                .invoke(button, entity);
        }
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
    /**
     * Updates the button's visual state. Flag writes first (they fire the
     * native {@code updateAppearance()} over the fallback q slots), then
     * {@code setSelected(self-built entity)} — the host enables only the
     * chosen item — so the displayed icon is always ours:
     * off → Disabled entity, vertical → Normal entity, horizontal →
     * Selected entity.
     */
    public void updateButtonState(final String contributionId, final int axis) {
        synchronized (lock) {
            for (final Entry entry : entries.values()) {
                if (!contributionId.equals(entry.contribution().contributionId())) continue;
                try {
                    final Object button = entry.currentButton();
                    final Class<?> buttonClass = button.getClass();
                    final boolean enabled = axis != 0;
                    final boolean selected = axis == 2;
                    buttonClass.getMethod("setButtonEnabled", boolean.class)
                        .invoke(button, enabled);
                    buttonClass.getMethod("setButtonSelected", boolean.class)
                        .invoke(button, selected);
                    final Object entity = entry.stateEntities().get(axis);
                    if (entity != null) {
                        buttonClass.getMethod("setSelected",
                            Class.forName("com.live2d.graphics3d.entity.GEntity",
                                false, buttonClass.getClassLoader()))
                            .invoke(button, entity);
                    }
                    diagnostic("BUTTON_STATE_UPDATED axis=" + axis
                        + " enabled=" + enabled + " selected=" + selected
                        + " entity=" + (entity != null));
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
