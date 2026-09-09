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
        try {
            synchronized (properties) {
                for (String key : new String[] {ATTACH_KEY, CANCEL_KEY, DECIDE_KEY}) {
                    if (properties.containsKey(key)) {
                        throw new IllegalStateException(
                            "export settings dialog callback property is already installed"
                        );
                    }
                }
                properties.put(ATTACH_KEY, attachCallback);
                properties.put(CANCEL_KEY, cancelCallback);
                properties.put(DECIDE_KEY, decideCallback);
            }
        } catch (RuntimeException | Error failure) {
            HANDLER.compareAndSet(requested, null);
            synchronized (properties) {
                properties.remove(ATTACH_KEY, attachCallback);
                properties.remove(CANCEL_KEY, cancelCallback);
                properties.remove(DECIDE_KEY, decideCallback);
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
    }
}
