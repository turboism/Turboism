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

    /** @return the process-wide registry the injected bridge calls mount through */
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
            insertIntoGroup(strip, built.button());
            entries.put(contribution.contributionId(),
                new Entry(contribution, built.button(), built.stateEntities()));
            applyInitialState(built.button(), built.stateEntities(), initialState);
            diagnostic("BUTTON_MOUNTED id=" + contribution.contributionId()
                + " state=" + initialState
                + " entities=" + built.stateEntities().size());
            scheduleDeferredSeat(built.button());
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
            addChild.invoke(children, entity, -1);
            entities.put(state, entity);
        }
        return entities;
    }

    /**
     * Builds the three state {@code CImageResource}s (off / vertical /
     * horizontal) via {@code CImageResource(BufferedImage, boolean)} — wraps
     * the image in a {@code CWritableImage} directly so no deferred file-byte
     * decoding is involved on the render path.
     */
    private static Object[] stateResources(
        final BufferedImage offImage,
        final BufferedImage verticalImage,
        final BufferedImage horizontalImage,
        final ClassLoader hostLoader
    ) throws ReflectiveOperationException {
        final Class<?> resourceClass = Class.forName(RESOURCE_CLASS, false, hostLoader);
        final var ctor = resourceClass.getConstructor(BufferedImage.class, boolean.class);
        return new Object[]{
            ctor.newInstance(offImage, true),
            ctor.newInstance(verticalImage, true),
            ctor.newInstance(horizontalImage, true),
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

    /**
     * Inserts the contributed button into the strip layout group directly
     * before the view dropdown arrow (strip field {@code z}, whose handler
     * opens the view menu). The view cluster is laid out right-to-left from
     * the strip's maxX by {@code a(N)}, so list index 0 renders rightmost:
     * inserting before {@code z} seats the button literally right of the
     * dropdown arrow. Unlike H (which {@code R()} re-parents and re-lays
     * out every pass), K members are re-parented only once by {@code V()}
     * at strip construction, so the button must also be added to the strip
     * scene graph here — list membership alone positions it but never
     * renders it. Falls back to group H at {@code index} when the dropdown
     * is absent (e.g. FormAnimation view).
     */
    /**
     * Appends the contributed button at the END of the strip's left tool
     * cluster (group H), after the last native button F — the position the
     * operator picked among strip-first/cluster-end options. Disassembly-
     * verified 5.3.03 order: H=[Sep,l,Sep,j,Sep,k,Sep,q,Sep,(m),Sep,n,Sep,r,F]
     * laid out left-flow, followed by I, a divider, J and the right-aligned
     * K view cluster (▾). Appending to H seats the button between F and the
     * o/I segment, before the divider.
     */
    private static void insertIntoGroup(
        final Object stripInstance, final Object button)
        throws ReflectiveOperationException {
        final Field groupField = stripInstance.getClass().getDeclaredField("H");
        groupField.setAccessible(true);
        final Object group = groupField.get(stripInstance);
        if (!(group instanceof List<?> list)) {
            throw new IllegalStateException("strip group H is not a list");
        }
        if (list.contains(button)) return;
        @SuppressWarnings("unchecked")
        final List<Object> target = (List<Object>) group;
        target.add(target.size(), button);
        diagnostic("BUTTON_INSERTED group=H index=" + target.size()
            + " after=leftClusterEnd(F)");
        reparentToStrip(stripInstance, button);
        ensureRect(button);
    }

    /**
     * Adds the button to the strip's scene-graph object children, mirroring
     * {@code V()}'s {@code e().getObjectsOnComponent().getChildren().add}
     * reparent pass. The host appends members ({@code Entities.add} defaults
     * index to {@code -1} → {@code addOrInsertAt} appends), so the button is
     * appended too — inserting at index 0 would seat it under the strip's
     * background pane in draw order and render it invisible.
     */
    private static void reparentToStrip(
        final Object stripInstance, final Object button)
        throws ReflectiveOperationException {
        final ClassLoader hostLoader = stripInstance.getClass().getClassLoader();
        final Class<?> gEntityClass = Class.forName(
            "com.live2d.graphics3d.entity.GEntity", false, hostLoader);
        final Object sceneGraph =
            stripInstance.getClass().getMethod("e").invoke(stripInstance);
        final Object objects = sceneGraph.getClass()
            .getMethod("getObjectsOnComponent").invoke(sceneGraph);
        final Object children =
            objects.getClass().getMethod("getChildren").invoke(objects);
        final Object list =
            children.getClass().getMethod("getList").invoke(children);
        if (list instanceof List<?> entities && entities.contains(button)) {
            diagnostic("BUTTON_REPARENT_SKIP childIndex=" + entities.indexOf(button));
            return;
        }
        children.getClass().getMethod("add", gEntityClass, int.class)
            .invoke(children, button, -1);
        final Object after =
            children.getClass().getMethod("getList").invoke(children);
        diagnostic("BUTTON_REPARENTED childIndex="
            + (after instanceof List<?> entities ? entities.indexOf(button) : -1));
    }

    /**
     * Guarantees a non-empty rect so {@code a(N)}'s per-pass layout reads a
     * real width from {@code getRectOnComponent()} instead of a null or
     * zero-size rect (the factory is invoked with a null rect argument).
     */
    private static void ensureRect(final Object button)
        throws ReflectiveOperationException {
        final Object rect = rectOf(button);
        if (rect == null || width(rect) <= 0f || height(rect) <= 0f) {
            setBoundsOnComponent(button, newRect(0f, 0f, 40f, 24f, button));
            diagnostic("BUTTON_RECT_DEFAULTED 40x24");
        }
    }

    private static Object rectOf(final Object entity) {
        try {
            return entity.getClass().getMethod("getRectOnComponent").invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Object newRect(
        final float x, final float y, final float w, final float h,
        final Object sibling) throws ReflectiveOperationException {
        final Class<?> rectClass = Class.forName(
            "com.live2d.graphics3d.type.GRectF", false,
            sibling.getClass().getClassLoader());
        return rectClass.getConstructor(
            float.class, float.class, float.class, float.class)
            .newInstance(x, y, w, h);
    }

    private static void setBoundsOnComponent(
        final Object entity, final Object rect)
        throws ReflectiveOperationException {
        entity.getClass().getMethod("setBoundsOnComponent",
            rect.getClass(), float.class).invoke(entity, rect, 1.0f);
    }

    private static float width(final Object rect) throws ReflectiveOperationException {
        return (Float) rect.getClass().getMethod("getWidth").invoke(rect);
    }

    private static float height(final Object rect) throws ReflectiveOperationException {
        return (Float) rect.getClass().getMethod("getHeight").invoke(rect);
    }

    private static Object readField(final Object owner, final String name)
            throws IllegalAccessException {
        for (final Field field : owner.getClass().getDeclaredFields()) {
            if (!field.getName().equals(name)) continue;
            field.setAccessible(true);
            return field.get(owner);
        }
        return null;
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

    /**
     * Invoked at the tail of the strip's {@code a(N)} layout dispatch via the
     * bridge's {@code positionStripButton} injection. {@code a(N)} lays K
     * members right-to-left from the strip's maxX — with the button inserted
     * at list index 0 the host math already seats it at the right edge,
     * immediately right of the dropdown arrow ({@code z.maxX == our.minX}).
     * This pass re-seats the button flush against the arrow's live rect so it
     * stays correctly positioned even when its own rect was defaulted before
     * the first layout ran.
     */
    public void positionButton(final Object stripInstance) {
        synchronized (lock) {
            if (stripInstance == null || stripInstance != strip || entries.isEmpty()) return;
            try {
                final Object dropdown = readField(stripInstance, "z");
                final Object zRect = dropdown == null ? null : rectOf(dropdown);
                if (zRect == null || width(zRect) <= 0f) return;
                final float seatX = minX(zRect) + width(zRect);
                final float seatY = minY(zRect);
                for (final Entry entry : entries.values()) {
                    final Object button = entry.currentButton();
                    final Object our = rectOf(button);
                    final float w = our != null && width(our) > 0f ? width(our) : 40f;
                    final float h = our != null && height(our) > 0f ? height(our) : 24f;
                    if (our != null
                        && Math.abs(minX(our) - seatX) < 0.5f
                        && Math.abs(minY(our) - seatY) < 0.5f) {
                        continue;
                    }
                    setBoundsOnComponent(button, newRect(seatX, seatY, w, h, button));
                    diagnostic("BUTTON_POSITIONED x=" + seatX + " y=" + seatY
                        + " w=" + w + " h=" + h + " zRight=" + seatX);
                }
            } catch (Throwable failure) {
                diagnostic("BUTTON_POSITION_FAILED " + failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Deferred geometry probes at +1.5/+6/+15/+40s after mount. {@code a(N)}
     * is the only layout pass for H members and it runs on action dispatch;
     * these probes record when the contributed button lands in the H flow,
     * its parent/enabled state and child index — enough to distinguish
     * "never positioned" from "positioned but not rendered".
     */
    private void scheduleDeferredSeat(final Object button) {
        final Thread worker = new Thread(() -> {
            for (final long delay : new long[]{1500, 6000, 15000, 40000}) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    return;
                }
                synchronized (lock) {
                    dumpGeometry(button, delay);
                }
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void dumpGeometry(final Object button, final long atMs) {
        try {
            final StringBuilder sb = new StringBuilder("STRIP_GEOMETRY t=").append(atMs);
            Entry owner = null;
            for (final Entry candidate : entries.values()) {
                if (candidate.currentButton() == button) { owner = candidate; break; }
            }
            if (owner != null) {
                for (final Map.Entry<Integer, Object> state : owner.stateEntities().entrySet()) {
                    sb.append(" item").append(state.getKey()).append('[');
                    try {
                        final Object transform = state.getValue().getClass()
                            .getMethod("getTransform").invoke(state.getValue());
                        final Object pos = transform.getClass()
                            .getMethod("getPosition").invoke(transform);
                        final Object scale = transform.getClass()
                            .getMethod("getScale").invoke(transform);
                        sb.append("pos=(").append(vec(pos, "getX"))
                            .append(',').append(vec(pos, "getY")).append(')')
                            .append(",scale=(").append(vec(scale, "getX"))
                            .append(',').append(vec(scale, "getY")).append(')');
                    } catch (ReflectiveOperationException ignored) {
                        sb.append("noTransform");
                    }
                    Object enabled = "?";
                    Object invisible = "?";
                    try {
                        enabled = state.getValue().getClass()
                            .getMethod("getEnabled").invoke(state.getValue());
                        invisible = state.getValue().getClass()
                            .getMethod("isInvisible").invoke(state.getValue());
                    } catch (ReflectiveOperationException ignored) { }
                    sb.append(",en=").append(enabled)
                        .append(",inv=").append(invisible);
                    sb.append(rendererDiag(state.getValue()));
                    sb.append(']');
                }
            }
            final Object dropdown = strip == null ? null : readField(strip, "z");
            if (dropdown != null) {
                try {
                    final Object zItems = dropdown.getClass()
                        .getMethod("getItems").invoke(dropdown);
                    if (zItems instanceof List<?> zl) {
                        int zi = 0;
                        for (final Object zItem : zl) {
                            if (zItem == null || zi++ > 3) continue;
                            sb.append(" zItem").append(zi).append('[');
                            try {
                                final Object tr = zItem.getClass()
                                    .getMethod("getTransform").invoke(zItem);
                                final Object pos = tr.getClass()
                                    .getMethod("getPosition").invoke(tr);
                                final Object sc = tr.getClass()
                                    .getMethod("getScale").invoke(tr);
                                sb.append("pos=(").append(vec(pos, "getX"))
                                    .append(',').append(vec(pos, "getY")).append(')')
                                    .append(",sc=(").append(vec(sc, "getX"))
                                    .append(',').append(vec(sc, "getY")).append(')');
                            } catch (ReflectiveOperationException ignored) {
                                sb.append("noTransform");
                            }
                            try {
                                sb.append(",en=").append(zItem.getClass()
                                    .getMethod("getEnabled").invoke(zItem))
                                    .append(",inv=").append(zItem.getClass()
                                        .getMethod("isInvisible").invoke(zItem));
                            } catch (ReflectiveOperationException ignored) { }
                            sb.append(rendererDiag(zItem));
                            sb.append(']');
                        }
                    }
                } catch (ReflectiveOperationException ignored) {
                    sb.append(" zItems=unreadable");
                }
            }
            final Object zRect = dropdown == null ? null : rectOf(dropdown);
            final Object our = rectOf(button);
            final Object parent = button.getClass()
                .getMethod("getParentEntity").invoke(button);
            if (zRect != null) {
                sb.append(" z[x=").append(minX(zRect))
                    .append(",y=").append(minY(zRect))
                    .append(",w=").append(width(zRect)).append(']');
            } else {
                sb.append(" z=null");
            }
            if (our != null) {
                sb.append(" our[x=").append(minX(our))
                    .append(",y=").append(minY(our))
                    .append(",w=").append(width(our))
                    .append(",h=").append(height(our)).append(']');
            } else {
                sb.append(" our=null");
            }
            sb.append(" parent=").append(parent != null);
            try {
                sb.append(" enabled=").append(button.getClass()
                    .getMethod("getEnabled").invoke(button))
                    .append(" enHier=").append(button.getClass()
                        .getMethod("getEnabledInHierarchy").invoke(button))
                    .append(" inv=").append(button.getClass()
                        .getMethod("isInvisible").invoke(button));
            } catch (ReflectiveOperationException ignored) {
                // flags optional
            }
            if (dropdown != null) {
                try {
                    sb.append(" zEnHier=").append(dropdown.getClass()
                        .getMethod("getEnabledInHierarchy").invoke(dropdown));
                } catch (ReflectiveOperationException ignored) { }
            }
            sb.append(" btn").append(rendererDiag(button));
            if (dropdown != null) {
                sb.append(" zBtn").append(rendererDiag(dropdown));
            }
            try {
                final Object sceneGraph =
                    strip.getClass().getMethod("e").invoke(strip);
                final Object objects = sceneGraph.getClass()
                    .getMethod("getObjectsOnComponent").invoke(sceneGraph);
                final Object children =
                    objects.getClass().getMethod("getChildren").invoke(objects);
                final Object list =
                    children.getClass().getMethod("getList").invoke(children);
                if (list instanceof List<?> entities) {
                    sb.append(" childIdx=").append(entities.indexOf(button))
                        .append('/').append(entities.size());
                }
            } catch (ReflectiveOperationException ignored) {
                // child index optional
            }
            if (zRect != null && width(zRect) > 0f && minX(zRect) > 1f) {
                final float w = our != null && width(our) > 0f ? width(our) : 40f;
                final float h = our != null && height(our) > 0f ? height(our) : 24f;
                final float seatX = minX(zRect) + width(zRect);
                if (our == null
                    || Math.abs(minX(our) - seatX) > 0.5f
                    || Math.abs(minY(our) - minY(zRect)) > 0.5f) {
                    setBoundsOnComponent(button,
                        newRect(seatX, minY(zRect), w, h, button));
                    sb.append(" seatedX=").append(seatX);
                }
            }
            diagnostic(sb.toString());
        } catch (Throwable failure) {
            diagnostic("STRIP_GEOMETRY_FAILED " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Mesh-renderer level diagnosis: last rendering order (NOT_INITIALIZED
     * when the renderer was never collected into a render pass), sorting
     * layer presence and order — separates "entity invisible" from
     * "renderer never registered".
     */
    private static String rendererDiag(final Object entity) {
        try {
            final Object renderer = entity.getClass()
                .getMethod("getMeshRenderer").invoke(entity);
            if (renderer == null) return ",mr=null";
            final StringBuilder sb = new StringBuilder(",mr[");
            try {
                sb.append("lastOrder=").append(renderer.getClass()
                    .getMethod("getLast_renderingOrder$core").invoke(renderer));
            } catch (ReflectiveOperationException ignored) {
                sb.append("lastOrder=?");
            }
            try {
                final Object layer = renderer.getClass()
                    .getMethod("getSortingLayer").invoke(renderer);
                sb.append(",layer=").append(layer != null);
            } catch (ReflectiveOperationException ignored) {
                sb.append(",layer=?");
            }
            try {
                sb.append(",order=").append(renderer.getClass()
                    .getMethod("getOrderInLayer").invoke(renderer));
            } catch (ReflectiveOperationException ignored) {
                sb.append(",order=?");
            }
            return sb.append(']').toString();
        } catch (Throwable failure) {
            return ",mr=ERR";
        }
    }

    private static float vec(final Object vector, final String getter)
        throws ReflectiveOperationException {
        return (Float) vector.getClass().getMethod(getter).invoke(vector);
    }

    private static float minX(final Object rect) throws ReflectiveOperationException {
        return (Float) rect.getClass().getMethod("getMinX").invoke(rect);
    }

    private static float minY(final Object rect) throws ReflectiveOperationException {
        return (Float) rect.getClass().getMethod("getMinY").invoke(rect);
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
