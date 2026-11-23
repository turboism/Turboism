package dev.turboism.adapter.cubism.optimization.drag;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Loader-neutral runtime coordinator for canvas {@code MOUSE_DRAGGED} coalescing, drag-scoped
 * inspector and deformer-tree update deferral with release flush, and same-cursor elision.
 *
 * <p>All consults cross through standard JDK functional interfaces registered in
 * {@link System#getProperties()} so rewritten host classes never reference Turboism types.</p>
 */
public final class EditDragResponsivenessBridge implements AutoCloseable {

    /** Shared preference property gating edit-mode acceleration features. */
    public static final String ENABLE_PROPERTY = EditDragResponsivenessTarget.ENABLE_PROPERTY;

    /** Explicit validation override property isolated from the persisted launcher preference. */
    public static final String VALIDATION_PROPERTY = EditDragResponsivenessTarget.VALIDATION_PROPERTY;

    /** Property key for canvas drag press notification ({@code BiPredicate<Object, Object>}). */
    public static final String PRESS_PROPERTY = "turboism.edit-drag.press";

    /** Property key for drag event coalescing ({@code BiPredicate<Object, Object>}). */
    public static final String DRAG_PROPERTY = "turboism.edit-drag.drag";

    /** Property key for release-entry state transition ({@code BiPredicate<Object, Object>}). */
    public static final String RELEASE_BEGIN_PROPERTY = "turboism.edit-drag.release-begin";

    /** Property key for release-exit deferred flush ({@code BiPredicate<Object, Object>}). */
    public static final String RELEASE_END_PROPERTY = "turboism.edit-drag.release-end";

    /** Property key for {@code ViewCtrlInspector.update(int)} deferral ({@code Predicate<Object>}). */
    public static final String DEFER_INSPECTOR_PROPERTY = "turboism.edit-drag.defer-inspector";

    /** Property key for internal inspector {@code d.a(List)} deferral ({@code Predicate<Object>}). */
    public static final String DEFER_INSPECTOR_APPLY_PROPERTY = "turboism.edit-drag.defer-inspector-apply";

    /** Property key for {@code DeformerSelectorUiFactory.updateWidget()} deferral ({@code Predicate<Object>}). */
    public static final String DEFER_DEFORMER_SELECTOR_PROPERTY = "turboism.edit-drag.defer-deformer-selector";

    /** Property key for {@code ViewCtrlDeformer.update(boolean, boolean)} deferral ({@code BiPredicate<Object, Object>}). */
    public static final String DEFER_DEFORMER_PALETTE_PROPERTY = "turboism.edit-drag.defer-deformer-palette";

    /** Property key for {@code CWidget.setCursor(CCursor)} same-cursor elision ({@code BiPredicate<Object, Object>}). */
    public static final String CURSOR_PROPERTY = "turboism.edit-drag.cursor";

    /** Property key exposing diagnostic counters ({@code Supplier<Map<String, Long>>}). */
    public static final String STATS_PROPERTY = "turboism.edit-drag.stats";

    private static final int DEFORMER_SYNC_BIT = 2;
    private static final int DEFORMER_SKIP_EXPAND_BIT = 1;
    private static final int FLUSH_IMMEDIATE_DELAY_MS = 0;
    private static final long DRAG_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long TRACKED_AWT_MASK = AWTEvent.WINDOW_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK;
    private static final int[] DRAG_BOUNDARY_EVENT_IDS = {
        MouseEvent.MOUSE_RELEASED,
        MouseEvent.MOUSE_PRESSED,
        MouseEvent.MOUSE_EXITED,
        MouseEvent.MOUSE_WHEEL,
        KeyEvent.KEY_PRESSED,
        KeyEvent.KEY_RELEASED,
        FocusEvent.FOCUS_LOST,
        WindowEvent.WINDOW_DEACTIVATED,
        WindowEvent.WINDOW_LOST_FOCUS,
        WindowEvent.WINDOW_ICONIFIED
    };

    private static final String ALREADY_INSTALLED_MESSAGE = "edit drag responsiveness bridge already installed";
    private static final String SLOTS_OCCUPIED_MESSAGE = "edit drag responsiveness slots occupied: ";

    private static volatile boolean configuredDefault;

    private final HostBindings host;
    private final Supplier<EventQueue> eventQueueSupplier;
    private final LongSupplier clockNanos;
    private final AtomicBoolean active = new AtomicBoolean();
    private final Object stateLock = new Object();

    private final BiPredicate<Object, Object> pressCallback = this::onMousePressed;
    private final BiPredicate<Object, Object> dragCallback = this::shouldCoalesceDrag;
    private final BiPredicate<Object, Object> releaseBeginCallback = this::onMouseReleasedBegin;
    private final BiPredicate<Object, Object> releaseEndCallback = this::onMouseReleasedEnd;
    private final Predicate<Object> deferInspectorCallback = this::shouldDeferInspector;
    private final Predicate<Object> deferInspectorApplyCallback = this::shouldDeferInspectorApply;
    private final Predicate<Object> deferDeformerSelectorCallback = this::shouldDeferDeformerSelector;
    private final BiPredicate<Object, Object> deferDeformerPaletteCallback = this::shouldDeferDeformerPalette;
    private final BiPredicate<Object, Object> cursorElideCallback = this::shouldElideCursor;
    private final Supplier<Map<String, Long>> statisticsCallback = this::snapshot;
    private final AWTEventListener awtListener = this::onAwtEvent;

    private volatile boolean armed;
    private volatile boolean closed;
    private volatile boolean listenerInstalled;
    private Properties installedProperties;

    private boolean canvasDragging;
    private WeakReference<Object> activeCanvasListener = new WeakReference<>(null);
    private long lastDragActivityNanos;
    private int insideReleaseDepth;

    private WeakReference<Object> pendingInspectorCtrl = new WeakReference<>(null);
    private WeakReference<Object> pendingInspectorPanel = new WeakReference<>(null);
    private WeakReference<Object> pendingDeformerSelector = new WeakReference<>(null);
    private WeakReference<Object> pendingDeformerCtrl = new WeakReference<>(null);
    private boolean hasPendingDeformerFlags;
    private boolean pendingDeformerSyncChildren;
    private boolean pendingDeformerSkipExpand;

    private WeakReference<Component> lastCursorComponent = new WeakReference<>(null);
    private WeakReference<Window> lastCursorWindow = new WeakReference<>(null);

    private long dragCalls;
    private long dragCoalesced;
    private long dragPassed;
    private long inspectorDeferred;
    private long inspectorApplyDeferred;
    private long deformerSelectorDeferred;
    private long deformerPaletteDeferred;
    private long flushesExecuted;
    private long cursorCalls;
    private long cursorElided;
    private long cursorPassed;
    private long observerFailures;

    /**
     * Resolves required host handles on the admitted host class loader.
     *
     * @param loader admitted host class loader
     * @throws ReflectiveOperationException when any reviewed host member is missing or inaccessible
     */
    public EditDragResponsivenessBridge(final ClassLoader loader) throws ReflectiveOperationException {
        this(
                ReflectiveHostBindings.resolve(Objects.requireNonNull(loader, "loader")),
                EditDragResponsivenessBridge::systemEventQueue,
                System::nanoTime);
    }

    EditDragResponsivenessBridge(
            final HostBindings host, final Supplier<EventQueue> eventQueueSupplier, final LongSupplier clockNanos) {
        this.host = Objects.requireNonNull(host, "host");
        this.eventQueueSupplier = Objects.requireNonNull(eventQueueSupplier, "eventQueueSupplier");
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos");
    }

    /** Updates the persisted launcher preference default supplied by bootstrap admission. */
    public static void setConfiguredDefault(final boolean enabled) {
        configuredDefault = enabled;
    }

    /** Returns whether persisted preference and JVM overrides enable drag responsiveness hooks. */
    public static boolean enabledByPreference() {
        try {
            final String explicit = System.getProperty(ENABLE_PROPERTY);
            if (explicit != null) {
                return Boolean.parseBoolean(explicit);
            }
            if (Boolean.parseBoolean(System.getProperty(VALIDATION_PROPERTY, "false"))) {
                return true;
            }
            return configuredDefault;
        } catch (final SecurityException denied) {
            return false;
        }
    }

    /** Installs all property slots and immediately arms the bridge. */
    public synchronized void install() {
        install(true);
    }

    /**
     * Publishes all callback slots into {@link System#getProperties()} and configures initial arming.
     *
     * @param initialArmed whether callbacks optimize immediately on install
     */
    public synchronized void install(final boolean initialArmed) {
        if (active.get()) {
            throw new IllegalStateException(ALREADY_INSTALLED_MESSAGE);
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            for (final String key : propertyKeys()) {
                if (properties.containsKey(key)) {
                    throw new IllegalStateException(SLOTS_OCCUPIED_MESSAGE + key);
                }
            }
            try {
                properties.put(PRESS_PROPERTY, pressCallback);
                properties.put(DRAG_PROPERTY, dragCallback);
                properties.put(RELEASE_BEGIN_PROPERTY, releaseBeginCallback);
                properties.put(RELEASE_END_PROPERTY, releaseEndCallback);
                properties.put(DEFER_INSPECTOR_PROPERTY, deferInspectorCallback);
                properties.put(DEFER_INSPECTOR_APPLY_PROPERTY, deferInspectorApplyCallback);
                properties.put(DEFER_DEFORMER_SELECTOR_PROPERTY, deferDeformerSelectorCallback);
                properties.put(DEFER_DEFORMER_PALETTE_PROPERTY, deferDeformerPaletteCallback);
                properties.put(CURSOR_PROPERTY, cursorElideCallback);
                properties.put(STATS_PROPERTY, statisticsCallback);
                installedProperties = properties;
                closed = false;
                armed = initialArmed;
                active.set(true);
            } catch (final RuntimeException | Error failure) {
                removeSlots(properties);
                throw failure;
            }
        }
        ensureAwtListenerInstalled();
    }

    private static String[] propertyKeys() {
        return new String[] {
            PRESS_PROPERTY,
            DRAG_PROPERTY,
            RELEASE_BEGIN_PROPERTY,
            RELEASE_END_PROPERTY,
            DEFER_INSPECTOR_PROPERTY,
            DEFER_INSPECTOR_APPLY_PROPERTY,
            DEFER_DEFORMER_SELECTOR_PROPERTY,
            DEFER_DEFORMER_PALETTE_PROPERTY,
            CURSOR_PROPERTY,
            STATS_PROPERTY
        };
    }

    private void ensureAwtListenerInstalled() {
        if (listenerInstalled || GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, TRACKED_AWT_MASK);
            listenerInstalled = true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
    }

    private void removeAwtListener() {
        if (!listenerInstalled) {
            return;
        }
        listenerInstalled = false;
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
    }

    private void onAwtEvent(final AWTEvent event) {
        if (closed || !active.get() || event == null) {
            return;
        }
        try {
            if (event instanceof WindowEvent windowEvent) {
                final int id = windowEvent.getID();
                if (id == WindowEvent.WINDOW_DEACTIVATED
                        || id == WindowEvent.WINDOW_LOST_FOCUS
                        || id == WindowEvent.WINDOW_ICONIFIED
                        || id == WindowEvent.WINDOW_CLOSED) {
                    endDragOnEdt();
                }
                return;
            }
            if (event instanceof MouseEvent mouseEvent && mouseEvent.getID() == MouseEvent.MOUSE_RELEASED) {
                final boolean shouldScheduleCheck;
                synchronized (stateLock) {
                    shouldScheduleCheck = canvasDragging && insideReleaseDepth == 0;
                }
                if (shouldScheduleCheck) {
                    SwingUtilities.invokeLater(this::endDragIfStillIdleAfterRelease);
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
    }

    private void endDragIfStillIdleAfterRelease() {
        final boolean needEnd;
        synchronized (stateLock) {
            needEnd = canvasDragging && insideReleaseDepth == 0;
        }
        if (needEnd) {
            endDragOnEdt();
        }
    }

    private void endDragOnEdt() {
        final boolean ended;
        synchronized (stateLock) {
            ended = canvasDragging;
            canvasDragging = false;
            activeCanvasListener = new WeakReference<>(null);
        }
        if (ended) {
            flushDeferredUpdatesOnEdt();
        }
    }

    /** Returns whether a canvas drag session is currently active and within the safety timeout. */
    public boolean isCanvasDragging() {
        final boolean expired;
        synchronized (stateLock) {
            if (!canvasDragging) {
                return false;
            }
            if (clockNanos.getAsLong() - lastDragActivityNanos <= DRAG_TIMEOUT_NANOS) {
                return true;
            }
            canvasDragging = false;
            activeCanvasListener = new WeakReference<>(null);
            expired = true;
        }
        if (expired) {
            flushDeferredUpdatesAsyncOrEdt();
        }
        return false;
    }

    boolean onMousePressed(final Object listener, final Object eventObj) {
        if (closed || !active.get() || listener == null) {
            return false;
        }
        try {
            if (host.isCanvasListener(listener)) {
                synchronized (stateLock) {
                    canvasDragging = true;
                    activeCanvasListener = new WeakReference<>(listener);
                    lastDragActivityNanos = clockNanos.getAsLong();
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
        return false;
    }

    boolean shouldCoalesceDrag(final Object listener, final Object eventObj) {
        dragCalls++;
        if (closed || !active.get() || listener == null) {
            dragPassed++;
            return false;
        }
        try {
            if (host.isCanvasListener(listener)) {
                synchronized (stateLock) {
                    canvasDragging = true;
                    activeCanvasListener = new WeakReference<>(listener);
                    lastDragActivityNanos = clockNanos.getAsLong();
                }
            }
            if (!armed || !enabledByPreference() || !(eventObj instanceof MouseEvent mouseEvent)) {
                dragPassed++;
                return false;
            }
            if (mouseEvent.getID() != MouseEvent.MOUSE_DRAGGED || !host.hasOpenDragSession(listener)) {
                dragPassed++;
                return false;
            }
            final EventQueue queue = eventQueueSupplier.get();
            if (queue == null) {
                dragPassed++;
                return false;
            }
            // Coalesce an unblocked head drag directly, or scan past Paint/Invocation events when no boundary is
            // queued.
            final AWTEvent head = queue.peekEvent();
            if (isMatchingDrag(mouseEvent, head)) {
                dragCoalesced++;
                return true;
            }
            final AWTEvent next = queue.peekEvent(MouseEvent.MOUSE_DRAGGED);
            if (isMatchingDrag(mouseEvent, next) && !hasPendingBoundaryEvent(queue)) {
                dragCoalesced++;
                return true;
            }
            dragPassed++;
            return false;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            dragPassed++;
            return false;
        }
    }

    private static boolean hasPendingBoundaryEvent(final EventQueue queue) {
        for (final int boundaryId : DRAG_BOUNDARY_EVENT_IDS) {
            if (queue.peekEvent(boundaryId) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMatchingDrag(final MouseEvent current, final AWTEvent next) {
        if (!(next instanceof MouseEvent nextMouse)) {
            return false;
        }
        return nextMouse.getID() == MouseEvent.MOUSE_DRAGGED
                && nextMouse.getSource() != null
                && nextMouse.getSource() == current.getSource()
                && nextMouse.getModifiersEx() == current.getModifiersEx()
                && nextMouse.getButton() == current.getButton()
                && nextMouse.getClickCount() == current.getClickCount();
    }

    boolean onMouseReleasedBegin(final Object listener, final Object eventObj) {
        if (closed || !active.get() || listener == null) {
            return false;
        }
        try {
            synchronized (stateLock) {
                insideReleaseDepth++;
                if (matchesActiveCanvasDrag(listener)) {
                    canvasDragging = false;
                    activeCanvasListener = new WeakReference<>(null);
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
        return false;
    }

    boolean onMouseReleasedEnd(final Object listener, final Object eventObj) {
        boolean shouldFlush = false;
        try {
            synchronized (stateLock) {
                if (insideReleaseDepth > 0) {
                    insideReleaseDepth--;
                }
                if (listener != null && matchesActiveCanvasDrag(listener)) {
                    canvasDragging = false;
                    activeCanvasListener = new WeakReference<>(null);
                }
                shouldFlush = !canvasDragging && hasPendingUpdatesLocked();
            }
            if (shouldFlush) {
                flushDeferredUpdatesOnEdt();
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
        return false;
    }

    private boolean matchesActiveCanvasDrag(final Object listener) throws Throwable {
        if (activeCanvasListener.get() == listener) {
            return true;
        }
        return host.isCanvasListener(listener);
    }

    boolean shouldDeferInspector(final Object inspectorCtrl) {
        if (closed || !active.get() || inspectorCtrl == null || !armed || !enabledByPreference()) {
            return false;
        }
        try {
            final boolean dragging = isCanvasDragging();
            synchronized (stateLock) {
                if (!dragging || !canvasDragging) {
                    if (pendingInspectorCtrl.get() == inspectorCtrl) {
                        pendingInspectorCtrl = new WeakReference<>(null);
                    }
                    return false;
                }
                pendingInspectorCtrl = new WeakReference<>(inspectorCtrl);
                inspectorDeferred++;
                return true;
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            return false;
        }
    }

    boolean shouldDeferInspectorApply(final Object inspectorPanel) {
        if (closed || !active.get() || inspectorPanel == null || !armed || !enabledByPreference()) {
            return false;
        }
        try {
            final boolean dragging = isCanvasDragging();
            synchronized (stateLock) {
                if (!dragging || !canvasDragging) {
                    if (pendingInspectorPanel.get() == inspectorPanel) {
                        pendingInspectorPanel = new WeakReference<>(null);
                    }
                    return false;
                }
                pendingInspectorPanel = new WeakReference<>(inspectorPanel);
                inspectorApplyDeferred++;
                return true;
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            return false;
        }
    }

    boolean shouldDeferDeformerSelector(final Object selectorFactory) {
        if (closed || !active.get() || selectorFactory == null || !armed || !enabledByPreference()) {
            return false;
        }
        try {
            final boolean dragging = isCanvasDragging();
            synchronized (stateLock) {
                if (!dragging || !canvasDragging) {
                    if (pendingDeformerSelector.get() == selectorFactory) {
                        pendingDeformerSelector = new WeakReference<>(null);
                    }
                    return false;
                }
                pendingDeformerSelector = new WeakReference<>(selectorFactory);
                deformerSelectorDeferred++;
                return true;
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            return false;
        }
    }

    boolean shouldDeferDeformerPalette(final Object deformerCtrl, final Object encodedFlags) {
        if (closed
                || !active.get()
                || deformerCtrl == null
                || !(encodedFlags instanceof Integer flags)
                || !armed
                || !enabledByPreference()) {
            return false;
        }
        final int bits = flags;
        final boolean syncChildren = (bits & DEFORMER_SYNC_BIT) != 0;
        final boolean skipExpand = (bits & DEFORMER_SKIP_EXPAND_BIT) != 0;
        try {
            final boolean dragging = isCanvasDragging();
            synchronized (stateLock) {
                if (!dragging || !canvasDragging) {
                    if (pendingDeformerCtrl.get() == deformerCtrl) {
                        pendingDeformerCtrl = new WeakReference<>(null);
                        hasPendingDeformerFlags = false;
                        pendingDeformerSyncChildren = false;
                        pendingDeformerSkipExpand = false;
                    }
                    return false;
                }
                final Object existing = pendingDeformerCtrl.get();
                pendingDeformerCtrl = new WeakReference<>(deformerCtrl);
                if (!hasPendingDeformerFlags || existing != deformerCtrl) {
                    pendingDeformerSyncChildren = syncChildren;
                    pendingDeformerSkipExpand = skipExpand;
                    hasPendingDeformerFlags = true;
                } else {
                    pendingDeformerSyncChildren |= syncChildren;
                    pendingDeformerSkipExpand &= skipExpand;
                }
                deformerPaletteDeferred++;
                return true;
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            return false;
        }
    }

    boolean shouldElideCursor(final Object widget, final Object packCursor) {
        cursorCalls++;
        if (closed || !active.get() || !armed || widget == null || !enabledByPreference()) {
            cursorPassed++;
            return false;
        }
        try {
            final Component component = host.componentOfWidget(widget);
            if (component == null) {
                cursorPassed++;
                return false;
            }
            final Cursor target = packCursor == null ? null : host.cursorOfPack(packCursor);
            final boolean elided = cursorUnchanged(component, target);
            if (elided) {
                cursorElided++;
            } else {
                cursorPassed++;
            }
            return elided;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            cursorPassed++;
            return false;
        }
    }

    private boolean cursorUnchanged(final Component component, final Cursor target) {
        if (target == null) {
            if (!component.isCursorSet()) {
                return true;
            }
            lastCursorComponent = new WeakReference<>(null);
            return false;
        }
        if (!component.isCursorSet()) {
            lastCursorComponent = new WeakReference<>(null);
            return false;
        }
        final Cursor current = component.getCursor();
        if (!isSameCursor(current, target)) {
            lastCursorComponent = new WeakReference<>(null);
            return false;
        }
        if (component.isShowing()) {
            final Window window =
                    component instanceof Window owned ? owned : SwingUtilities.getWindowAncestor(component);
            if (window != null) {
                final Window activeWindow =
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
                if (activeWindow != null && activeWindow != window) {
                    lastCursorWindow = new WeakReference<>(null);
                    lastCursorComponent = new WeakReference<>(null);
                    return false;
                }
                if (lastCursorWindow.get() != window || lastCursorComponent.get() != component) {
                    lastCursorWindow = new WeakReference<>(window);
                    lastCursorComponent = new WeakReference<>(component);
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSameCursor(final Cursor current, final Cursor target) {
        if (current == target) {
            return true;
        }
        if (current == null) {
            return false;
        }
        return target.getType() != Cursor.CUSTOM_CURSOR
                && current.getType() == target.getType()
                && Objects.equals(current.getName(), target.getName());
    }

    private boolean hasPendingUpdatesLocked() {
        return pendingInspectorCtrl.get() != null
                || pendingInspectorPanel.get() != null
                || pendingDeformerSelector.get() != null
                || pendingDeformerCtrl.get() != null;
    }

    private void flushDeferredUpdatesAsyncOrEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            flushDeferredUpdatesOnEdt();
        } else {
            SwingUtilities.invokeLater(this::flushDeferredUpdatesOnEdt);
        }
    }

    private void flushDeferredUpdatesOnEdt() {
        final Object inspectorCtrl;
        final Object inspectorPanel;
        final Object selectorFactory;
        final Object deformerCtrl;
        final boolean syncChildren;
        final boolean skipExpand;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            inspectorCtrl = pendingInspectorCtrl.get();
            inspectorPanel = pendingInspectorPanel.get();
            selectorFactory = pendingDeformerSelector.get();
            deformerCtrl = pendingDeformerCtrl.get();
            syncChildren = pendingDeformerSyncChildren;
            skipExpand = pendingDeformerSkipExpand;
            pendingInspectorCtrl = new WeakReference<>(null);
            pendingInspectorPanel = new WeakReference<>(null);
            pendingDeformerSelector = new WeakReference<>(null);
            pendingDeformerCtrl = new WeakReference<>(null);
            hasPendingDeformerFlags = false;
            pendingDeformerSyncChildren = false;
            pendingDeformerSkipExpand = false;
        }
        if (inspectorCtrl == null && inspectorPanel == null && selectorFactory == null && deformerCtrl == null) {
            return;
        }
        flushesExecuted++;
        try {
            boolean flushedInspector = false;
            if (inspectorCtrl != null) {
                host.updateInspectorCtrl(inspectorCtrl, FLUSH_IMMEDIATE_DELAY_MS);
                flushedInspector = true;
            } else if (inspectorPanel != null) {
                host.updateInspectorPanel(inspectorPanel, FLUSH_IMMEDIATE_DELAY_MS);
                flushedInspector = true;
            }
            if (selectorFactory != null && !flushedInspector) {
                host.updateDeformerSelector(selectorFactory);
            }
            if (deformerCtrl != null) {
                host.updateDeformerCtrl(deformerCtrl, syncChildren, skipExpand);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
    }

    /** Returns an immutable snapshot of bridge activity counters for diagnostics and tests. */
    public Map<String, Long> snapshot() {
        final Map<String, Long> result = new LinkedHashMap<>();
        result.put("dragCalls", dragCalls);
        result.put("dragCoalesced", dragCoalesced);
        result.put("dragPassed", dragPassed);
        result.put("inspectorDeferred", inspectorDeferred);
        result.put("inspectorApplyDeferred", inspectorApplyDeferred);
        result.put("deformerSelectorDeferred", deformerSelectorDeferred);
        result.put("deformerPaletteDeferred", deformerPaletteDeferred);
        result.put("flushesExecuted", flushesExecuted);
        result.put("cursorCalls", cursorCalls);
        result.put("cursorElided", cursorElided);
        result.put("cursorPassed", cursorPassed);
        result.put("observerFailures", observerFailures);
        result.put("armed", armed ? 1L : 0L);
        return Map.copyOf(result);
    }

    /** Unregisters listeners, clears property slots, and resets pending drag state. */
    @Override
    public synchronized void close() {
        closed = true;
        active.set(false);
        armed = false;
        removeAwtListener();
        synchronized (stateLock) {
            canvasDragging = false;
            activeCanvasListener = new WeakReference<>(null);
            insideReleaseDepth = 0;
            pendingInspectorCtrl = new WeakReference<>(null);
            pendingInspectorPanel = new WeakReference<>(null);
            pendingDeformerSelector = new WeakReference<>(null);
            pendingDeformerCtrl = new WeakReference<>(null);
            hasPendingDeformerFlags = false;
            pendingDeformerSyncChildren = false;
            pendingDeformerSkipExpand = false;
        }
        lastCursorComponent = new WeakReference<>(null);
        lastCursorWindow = new WeakReference<>(null);
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                removeSlots(properties);
            }
        }
    }

    private void removeSlots(final Properties properties) {
        properties.remove(PRESS_PROPERTY, pressCallback);
        properties.remove(DRAG_PROPERTY, dragCallback);
        properties.remove(RELEASE_BEGIN_PROPERTY, releaseBeginCallback);
        properties.remove(RELEASE_END_PROPERTY, releaseEndCallback);
        properties.remove(DEFER_INSPECTOR_PROPERTY, deferInspectorCallback);
        properties.remove(DEFER_INSPECTOR_APPLY_PROPERTY, deferInspectorApplyCallback);
        properties.remove(DEFER_DEFORMER_SELECTOR_PROPERTY, deferDeformerSelectorCallback);
        properties.remove(DEFER_DEFORMER_PALETTE_PROPERTY, deferDeformerPaletteCallback);
        properties.remove(CURSOR_PROPERTY, cursorElideCallback);
        properties.remove(STATS_PROPERTY, statisticsCallback);
    }

    private static EventQueue systemEventQueue() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return Toolkit.getDefaultToolkit().getSystemEventQueue();
    }

    /** Host access abstraction enabling unit tests without loading Cubism classes. */
    interface HostBindings {
        boolean isCanvasListener(Object listener) throws Throwable;

        boolean hasOpenDragSession(Object listener) throws Throwable;

        Component componentOfWidget(Object widget) throws Throwable;

        Cursor cursorOfPack(Object packCursor) throws Throwable;

        void updateInspectorCtrl(Object ctrl, int delay) throws Throwable;

        void updateInspectorPanel(Object panel, int delay) throws Throwable;

        void updateDeformerSelector(Object selector) throws Throwable;

        void updateDeformerCtrl(Object ctrl, boolean syncChildren, boolean skipExpand) throws Throwable;
    }

    private static final class ReflectiveHostBindings implements HostBindings {

        private final Class<?> canvasWidgetClass;
        private final HostHandles.HostField listenerDelegateField;
        private final HostHandles.HostField listenerSessionField;
        private final MethodHandle delegateWidgetHandle;
        private final MethodHandle widgetComponentHandle;
        private final MethodHandle cursorPackHandle;
        private final MethodHandle inspectorCtrlUpdateHandle;
        private final MethodHandle inspectorPanelUpdateHandle;
        private final MethodHandle selectorUpdateHandle;
        private final MethodHandle deformerCtrlUpdateHandle;

        private ReflectiveHostBindings(
                final Class<?> canvasWidgetClass,
                final HostHandles.HostField listenerDelegateField,
                final HostHandles.HostField listenerSessionField,
                final MethodHandle delegateWidgetHandle,
                final MethodHandle widgetComponentHandle,
                final MethodHandle cursorPackHandle,
                final MethodHandle inspectorCtrlUpdateHandle,
                final MethodHandle inspectorPanelUpdateHandle,
                final MethodHandle selectorUpdateHandle,
                final MethodHandle deformerCtrlUpdateHandle) {
            this.canvasWidgetClass = canvasWidgetClass;
            this.listenerDelegateField = listenerDelegateField;
            this.listenerSessionField = listenerSessionField;
            this.delegateWidgetHandle = delegateWidgetHandle;
            this.widgetComponentHandle = widgetComponentHandle;
            this.cursorPackHandle = cursorPackHandle;
            this.inspectorCtrlUpdateHandle = inspectorCtrlUpdateHandle;
            this.inspectorPanelUpdateHandle = inspectorPanelUpdateHandle;
            this.selectorUpdateHandle = selectorUpdateHandle;
            this.deformerCtrlUpdateHandle = deformerCtrlUpdateHandle;
        }

        static ReflectiveHostBindings resolve(final ClassLoader loader) throws ReflectiveOperationException {
            final Class<?> canvasClass = HostHandles.load(loader, EditDragResponsivenessTarget.CANVAS_WIDGET_OWNER);
            final Class<?> listenerClass = HostHandles.load(loader, EditDragResponsivenessTarget.MOUSE_LISTENER_OWNER);
            final Class<?> delegateClass = HostHandles.load(loader, EditDragResponsivenessTarget.MOUSE_DELEGATE_OWNER);
            final Class<?> widgetClass = HostHandles.load(loader, EditDragResponsivenessTarget.WIDGET_OWNER);
            final Class<?> cursorClass = HostHandles.load(loader, EditDragResponsivenessTarget.CURSOR_OWNER);
            final Class<?> inspectorCtrlClass =
                    HostHandles.load(loader, EditDragResponsivenessTarget.INSPECTOR_VIEW_CTRL_OWNER);
            final Class<?> inspectorPanelClass =
                    HostHandles.load(loader, EditDragResponsivenessTarget.INSPECTOR_INTERNAL_OWNER);
            final Class<?> selectorClass =
                    HostHandles.load(loader, EditDragResponsivenessTarget.DEFORMER_SELECTOR_OWNER);
            final Class<?> deformerCtrlClass =
                    HostHandles.load(loader, EditDragResponsivenessTarget.DEFORMER_VIEW_CTRL_OWNER);

            final HostHandles.HostField listenerDelegateField =
                    HostHandles.field(listenerClass, EditDragResponsivenessTarget.LISTENER_DELEGATE_FIELD);
            final HostHandles.HostField listenerSessionField =
                    HostHandles.field(listenerClass, EditDragResponsivenessTarget.LISTENER_SESSION_FIELD);
            final MethodHandle delegateWidgetHandle = HostHandles.virtual(
                    delegateClass,
                    EditDragResponsivenessTarget.DELEGATE_WIDGET_METHOD,
                    EditDragResponsivenessTarget.DELEGATE_WIDGET_DESCRIPTOR,
                    MethodType.methodType(Object.class, Object.class));
            final MethodHandle widgetComponentHandle = HostHandles.virtual(
                    widgetClass,
                    EditDragResponsivenessTarget.GET_COMPONENT_METHOD,
                    EditDragResponsivenessTarget.GET_COMPONENT_DESCRIPTOR,
                    MethodType.methodType(JComponent.class, Object.class));
            final MethodHandle cursorPackHandle = HostHandles.virtual(
                    cursorClass,
                    EditDragResponsivenessTarget.GET_CURSOR_METHOD,
                    EditDragResponsivenessTarget.GET_CURSOR_DESCRIPTOR,
                    MethodType.methodType(Cursor.class, Object.class));
            final MethodHandle inspectorCtrlUpdateHandle = HostHandles.virtual(
                    inspectorCtrlClass,
                    EditDragResponsivenessTarget.UPDATE_METHOD,
                    EditDragResponsivenessTarget.UPDATE_DELAY_DESCRIPTOR,
                    MethodType.methodType(void.class, Object.class, int.class));
            final MethodHandle inspectorPanelUpdateHandle = HostHandles.virtual(
                    inspectorPanelClass,
                    EditDragResponsivenessTarget.INSPECTOR_INTERNAL_METHOD,
                    EditDragResponsivenessTarget.UPDATE_DELAY_DESCRIPTOR,
                    MethodType.methodType(void.class, Object.class, int.class));
            final MethodHandle selectorUpdateHandle = HostHandles.virtual(
                    selectorClass,
                    EditDragResponsivenessTarget.UPDATE_WIDGET_METHOD,
                    EditDragResponsivenessTarget.VOID_DESCRIPTOR,
                    MethodType.methodType(void.class, Object.class));
            final MethodHandle deformerCtrlUpdateHandle = HostHandles.virtual(
                    deformerCtrlClass,
                    EditDragResponsivenessTarget.UPDATE_METHOD,
                    EditDragResponsivenessTarget.UPDATE_DEFORMER_DESCRIPTOR,
                    MethodType.methodType(void.class, Object.class, boolean.class, boolean.class));

            return new ReflectiveHostBindings(
                    canvasClass,
                    listenerDelegateField,
                    listenerSessionField,
                    delegateWidgetHandle,
                    widgetComponentHandle,
                    cursorPackHandle,
                    inspectorCtrlUpdateHandle,
                    inspectorPanelUpdateHandle,
                    selectorUpdateHandle,
                    deformerCtrlUpdateHandle);
        }

        @Override
        public boolean isCanvasListener(final Object listener) throws Throwable {
            final Object delegate = listenerDelegateField.get(listener);
            if (delegate == null) {
                return false;
            }
            return canvasWidgetClass.isInstance(delegateWidgetHandle.invoke(delegate));
        }

        @Override
        public boolean hasOpenDragSession(final Object listener) throws Throwable {
            return listenerSessionField.get(listener) != null;
        }

        @Override
        public Component componentOfWidget(final Object widget) throws Throwable {
            return (JComponent) widgetComponentHandle.invoke(widget);
        }

        @Override
        public Cursor cursorOfPack(final Object packCursor) throws Throwable {
            return (Cursor) cursorPackHandle.invoke(packCursor);
        }

        @Override
        public void updateInspectorCtrl(final Object ctrl, final int delay) throws Throwable {
            inspectorCtrlUpdateHandle.invoke(ctrl, delay);
        }

        @Override
        public void updateInspectorPanel(final Object panel, final int delay) throws Throwable {
            inspectorPanelUpdateHandle.invoke(panel, delay);
        }

        @Override
        public void updateDeformerSelector(final Object selector) throws Throwable {
            selectorUpdateHandle.invoke(selector);
        }

        @Override
        public void updateDeformerCtrl(final Object ctrl, final boolean syncChildren, final boolean skipExpand)
                throws Throwable {
            deformerCtrlUpdateHandle.invoke(ctrl, syncChildren, skipExpand);
        }
    }
}
