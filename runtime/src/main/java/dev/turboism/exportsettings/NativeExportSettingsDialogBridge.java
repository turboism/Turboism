package dev.turboism.exportsettings;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Loader-neutral bridge from transformed Export Settings dialog bytecode to runtime policy.
 *
 * <p>Transformed host bytecode sees only JDK functional interfaces, {@link System} properties,
 * and {@code Object} arguments. Runtime callback ownership stays on the parent loader and is
 * removed only after the bootstrap installer has restored transformed host classes.</p>
 */
public final class NativeExportSettingsDialogBridge {

    static final String PROPERTY_PREFIX = "turboism.export-settings.dialog.";
    static final String ATTACH_KEY = PROPERTY_PREFIX + "attach";
    static final String CANCEL_KEY = PROPERTY_PREFIX + "cancel";
    static final String DECIDE_KEY = PROPERTY_PREFIX + "decide";
    static final String CHOOSER_PREFIX = "turboism.export-settings.chooser.";
    static final String REDIRECT_KEY = CHOOSER_PREFIX + "redirect";

    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();

    private NativeExportSettingsDialogBridge() {
    }

    /** Installs one handler and publishes the three loader-neutral JDK callbacks. */
    public static Registration install(final Handler handler) {
        final Handler requested = Objects.requireNonNull(handler, "handler");
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("export settings dialog bridge is already installed");
        }
        final Properties properties = System.getProperties();
        final BiFunction<Object, Object, Object> attachCallback = (owner, container) ->
            dispatchAttach(owner, container);
        final Consumer<Object> cancelCallback = NativeExportSettingsDialogBridge::dispatchCancel;
        final Function<Object, Object> decideCallback = NativeExportSettingsDialogBridge::dispatchDecide;
        final Function<Object, Object> redirectCallback = NativeExportSettingsDialogBridge::dispatchRedirect;
        try {
            synchronized (properties) {
                for (String key : new String[] {ATTACH_KEY, CANCEL_KEY, DECIDE_KEY, REDIRECT_KEY}) {
                    if (properties.containsKey(key)) {
                        throw new IllegalStateException(
                            "export settings dialog callback property is already installed"
                        );
                    }
                }
                properties.put(ATTACH_KEY, attachCallback);
                properties.put(CANCEL_KEY, cancelCallback);
                properties.put(DECIDE_KEY, decideCallback);
                properties.put(REDIRECT_KEY, redirectCallback);
            }
        } catch (RuntimeException | Error failure) {
            HANDLER.compareAndSet(requested, null);
            synchronized (properties) {
                properties.remove(ATTACH_KEY, attachCallback);
                properties.remove(CANCEL_KEY, cancelCallback);
                properties.remove(DECIDE_KEY, decideCallback);
                properties.remove(REDIRECT_KEY, redirectCallback);
            }
            throw failure;
        }

        return new Registration() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                synchronized (properties) {
                    properties.remove(ATTACH_KEY, attachCallback);
                    properties.remove(CANCEL_KEY, cancelCallback);
                    properties.remove(DECIDE_KEY, decideCallback);
                    properties.remove(REDIRECT_KEY, redirectCallback);
                }
                HANDLER.compareAndSet(requested, null);
            }
        };
    }

    /** Fail-open attach dispatch. */
    private static Object dispatchAttach(final Object owner, final Object container) {
        final Handler handler = HANDLER.get();
        if (handler == null || owner == null) {
            return null;
        }
        try {
            return handler.attach(owner, container);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Cancel cleanup dispatch; no plugin callback may be reached from this path. */
    private static void dispatchCancel(final Object owner) {
        final Handler handler = HANDLER.get();
        if (handler == null || owner == null) {
            return;
        }
        try {
            handler.cancel(owner);
        } catch (Throwable ignored) {
            // Native cancel must remain fail-open.
        }
    }

    /** Fail-closed confirmation dispatch. */
    private static Object dispatchDecide(final Object owner) {
        final Handler handler = HANDLER.get();
        if (handler == null) {
            return Boolean.FALSE;
        }
        try {
            final Boolean decision = handler.decide(owner);
            return decision == null ? Boolean.FALSE : decision;
        } catch (Throwable ignored) {
            return Boolean.FALSE;
        }
    }

    /**
     * Chooser-result dispatch for the exporter's file picks.
     *
     * <p>With no handler installed the picked value passes through untouched, keeping every
     * native export byte-identical. A present handler may substitute a staging destination
     * (armed protected-export sessions) or return {@code null} to cancel the export exactly
     * like a dismissed chooser. A throwing handler fails closed to {@code null} so a broken
     * redirect can never leak output to the user's real destination.</p>
     */
    private static Object dispatchRedirect(final Object picked) {
        final Handler handler = HANDLER.get();
        if (handler == null) {
            return picked;
        }
        try {
            return handler.redirectChooser(picked);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Runtime policy entry called from transformed host bytecode. */
    @FunctionalInterface
    public interface Handler {
        /** Materializes the contributed options; returns {@code null} for native continuation. */
        Object attach(Object owner, Object container);

        /** Removes dialog-scoped state without invoking plugin callbacks. */
        default void cancel(final Object owner) {
        }

        /** Returns whether native continuation is allowed; default is native continuation. */
        default Boolean decide(final Object owner) {
            return Boolean.TRUE;
        }

        /** Rewrites a native chooser pick; default passes the pick through unchanged. */
        default Object redirectChooser(final Object picked) {
            return picked;
        }
    }
}
