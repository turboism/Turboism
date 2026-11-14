package dev.turboism.adapter.cubism.optimization.cursor;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Tracks AWT pointer and cursor dirtiness through loader-neutral system property callbacks.
 *
 * <p>Bootstrap-loaded {@code java.awt.Container} and {@code java.awt.Component} bytecode consults
 * standard JDK functional interfaces registered in {@link System#getProperties()}. Any missing slot
 * or unexpected runtime exception falls back to the unmodified JDK behavior.</p>
 */
public final class CursorValidationElisionBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.cursorValidationElision";

    /** Property key for the {@code BooleanSupplier} consulted by {@code Container.validate()}. */
    public static final String VALIDATE_PROPERTY = "turboism.cursor-elision.validate";

    /** Property key for the {@code Predicate<Object>} consulted by {@code Container.getMousePosition(boolean)}. */
    public static final String OUTSIDE_PROPERTY = "turboism.cursor-elision.outside";

    /** Property key for the {@code Runnable} invoked when cursor or visibility state mutates. */
    public static final String DIRTY_PROPERTY = "turboism.cursor-elision.dirty";

    /** Property key exposing diagnostic counters. */
    public static final String STATS_PROPERTY = "turboism.cursor-elision.stats";

    private static final long POINTER_CACHE_TTL_NANOS = 32_000_000L;

    private static final long TRACKED_EVENT_MASK = AWTEvent.MOUSE_EVENT_MASK
            | AWTEvent.MOUSE_MOTION_EVENT_MASK
            | AWTEvent.MOUSE_WHEEL_EVENT_MASK
            | AWTEvent.WINDOW_EVENT_MASK
            | AWTEvent.WINDOW_STATE_EVENT_MASK;

    private final AtomicBoolean active = new AtomicBoolean();
    private final BooleanSupplier validateConsult = this::shouldUpdateCursorOnValidate;
    private final Predicate<Object> outsideConsult = this::isPointerOutsideContainer;
    private final Runnable dirtyCallback = this::markDirty;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private final AWTEventListener eventListener = this::onAwtEvent;

    private volatile boolean cursorDirty = true;
    private volatile boolean pointerDirty = true;
    private volatile boolean hasPointerSample;
    private volatile int cachedScreenX;
    private volatile int cachedScreenY;
    private volatile long pointerSampleNanos;
    private volatile boolean listenerInstalled;
    private Properties installedProperties;

    private long validateCalls;
    private long validateElided;
    private long validatePassed;
    private long mousePosCalls;
    private long mousePosElided;
    private long mousePosPassed;
    private long dirtyMarks;
    private long observerFailures;

    /** Registers callback slots and attaches the AWT event listener when non-headless. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException("cursor validation elision already installed");
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(VALIDATE_PROPERTY)
                    || properties.containsKey(OUTSIDE_PROPERTY)
                    || properties.containsKey(DIRTY_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("cursor validation elision slots occupied");
            }
            properties.put(VALIDATE_PROPERTY, validateConsult);
            properties.put(OUTSIDE_PROPERTY, outsideConsult);
            properties.put(DIRTY_PROPERTY, dirtyCallback);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
            cursorDirty = true;
            pointerDirty = true;
            active.set(true);
        }
        ensureEventListenerInstalled();
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        if (listenerInstalled) {
            listenerInstalled = false;
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(eventListener);
            } catch (final Throwable ignored) {
                // Toolkit shutdown or headless teardown needs no further cleanup.
            }
        }
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(VALIDATE_PROPERTY, validateConsult);
                properties.remove(OUTSIDE_PROPERTY, outsideConsult);
                properties.remove(DIRTY_PROPERTY, dirtyCallback);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
    }

    /** Returns an immutable snapshot of bridge invocation and elision counters. */
    public Map<String, Long> snapshot() {
        return Map.of(
                "validateCalls", validateCalls,
                "validateElided", validateElided,
                "validatePassed", validatePassed,
                "mousePosCalls", mousePosCalls,
                "mousePosElided", mousePosElided,
                "mousePosPassed", mousePosPassed,
                "dirtyMarks", dirtyMarks,
                "observerFailures", observerFailures);
    }

    private void onAwtEvent(final AWTEvent event) {
        cursorDirty = true;
        if (event instanceof MouseEvent mouseEvent) {
            if (mouseEvent.getID() == MouseEvent.MOUSE_EXITED && mouseEvent.getComponent() instanceof Window) {
                pointerDirty = true;
                return;
            }
            try {
                final Point screen = mouseEvent.getLocationOnScreen();
                cachedScreenX = screen.x;
                cachedScreenY = screen.y;
                pointerSampleNanos = System.nanoTime();
                hasPointerSample = true;
                pointerDirty = false;
                return;
            } catch (final Throwable ignored) {
                // Fall back to lazy PointerInfo refresh on next query.
            }
        }
        pointerDirty = true;
    }

    private void ensureEventListenerInstalled() {
        if (listenerInstalled || !active.get() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        synchronized (this) {
            if (listenerInstalled || !active.get()) {
                return;
            }
            try {
                Toolkit.getDefaultToolkit().addAWTEventListener(eventListener, TRACKED_EVENT_MASK);
                listenerInstalled = true;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                observerFailures++;
            }
        }
    }

    private void markDirty() {
        dirtyMarks++;
        cursorDirty = true;
        pointerDirty = true;
    }

    private boolean shouldUpdateCursorOnValidate() {
        validateCalls++;
        if (!active.get()) {
            validatePassed++;
            return true;
        }
        try {
            ensureEventListenerInstalled();
            if (!listenerInstalled) {
                validatePassed++;
                return true;
            }
            if (cursorDirty) {
                cursorDirty = false;
                validatePassed++;
                return true;
            }
            validateElided++;
            return false;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            validatePassed++;
            return true;
        }
    }

    private boolean isPointerOutsideContainer(final Object candidate) {
        mousePosCalls++;
        if (!active.get() || !(candidate instanceof Component component)) {
            mousePosPassed++;
            return false;
        }
        try {
            ensureEventListenerInstalled();
            final int width = component.getWidth();
            final int height = component.getHeight();
            if (width <= 0 || height <= 0 || !component.isVisible()) {
                mousePosElided++;
                return true;
            }
            int screenX = 0;
            int screenY = 0;
            Component cursor = component;
            Window rootWindow = null;
            while (cursor != null) {
                if (!cursor.isVisible()) {
                    mousePosElided++;
                    return true;
                }
                screenX += cursor.getX();
                screenY += cursor.getY();
                if (cursor instanceof Window window) {
                    if (!window.isShowing()) {
                        mousePosElided++;
                        return true;
                    }
                    rootWindow = window;
                    break;
                }
                cursor = cursor.getParent();
            }
            if (rootWindow == null) {
                mousePosElided++;
                return true;
            }
            if (!refreshPointerSampleIfStale()) {
                mousePosPassed++;
                return false;
            }
            final int px = cachedScreenX;
            final int py = cachedScreenY;
            if (px < screenX || py < screenY || px >= screenX + width || py >= screenY + height) {
                mousePosElided++;
                return true;
            }
            mousePosPassed++;
            return false;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            mousePosPassed++;
            return false;
        }
    }

    private boolean refreshPointerSampleIfStale() {
        final long now = System.nanoTime();
        if (!pointerDirty && hasPointerSample && now - pointerSampleNanos <= POINTER_CACHE_TTL_NANOS) {
            return true;
        }
        final PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) {
            hasPointerSample = false;
            return false;
        }
        final Point location = info.getLocation();
        if (location == null) {
            hasPointerSample = false;
            return false;
        }
        cachedScreenX = location.x;
        cachedScreenY = location.y;
        pointerSampleNanos = now;
        hasPointerSample = true;
        pointerDirty = false;
        return true;
    }
}
