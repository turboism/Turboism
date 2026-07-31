package dev.turboism.ui.appearance.control;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-owned, fail-closed ingress from the verified native deformer renderer hook. */
public final class NativeDeformerTreeAppearanceBridge {

    private static final AtomicReference<Callback> CALLBACK = new AtomicReference<>();
    private static final AtomicReference<DeformerTreeControlAppearanceProvider> PROVIDER = new AtomicReference<>();

    private NativeDeformerTreeAppearanceBridge() {
    }

    public static Component afterRender(
        final Component component,
        final Object value,
        final boolean selected,
        final boolean focused
    ) {
        if (component == null) return null;
        final Callback callback = CALLBACK.get();
        if (callback == null || !javax.swing.SwingUtilities.isEventDispatchThread()) return component;
        try {
            return Objects.requireNonNullElse(
                callback.apply(component, value, selected, focused),
                component
            );
        } catch (Throwable ignored) {
            return component;
        }
    }

    public static void install(
        final long hostGeneration,
        final Selectors selectors,
        final DeformerTreeControlAppearanceProvider provider
    ) {
        Objects.requireNonNull(selectors, "selectors");
        Objects.requireNonNull(provider, "provider");
        PROVIDER.set(provider);
        install((component, value, selected, focused) -> {
            final Component label = label(component);
            if (label == null) return component;
            try {
                final String deformerId = selectors.deformerId(value);
                if (deformerId != null) provider.apply(hostGeneration, deformerId, label, selected, focused);
                else provider.restore();
            } catch (ReflectiveOperationException ignored) {
                provider.restore();
            }
            return component;
        });
    }

    static void install(final Callback callback) {
        if (!CALLBACK.compareAndSet(null, Objects.requireNonNull(callback, "callback"))) {
            throw new IllegalStateException("native deformer appearance bridge is already installed");
        }
    }

    public static void uninstall() {
        CALLBACK.set(null);
        final DeformerTreeControlAppearanceProvider provider = PROVIDER.getAndSet(null);
        if (provider != null) provider.close();
    }

    static void clearForTesting() {
        uninstall();
    }

    private static Component label(final Component component) {
        if (component instanceof JLabel) return component;
        if (component instanceof Container container
            && container.getComponentCount() > 0
            && container.getComponent(0) instanceof JLabel label) {
            return label;
        }
        return null;
    }


    @FunctionalInterface
    interface Callback {
        Component apply(Component component, Object value, boolean selected, boolean focused);
    }

    public record Selectors(
        String rowSourceOwner,
        String rowSourceMethod,
        String deformerSourceOwner,
        String deformerIdMethod,
        String idStringMethod,
        ClassLoader hostClassLoader
    ) {
        public Selectors {
            requireText(rowSourceOwner, "rowSourceOwner");
            requireText(rowSourceMethod, "rowSourceMethod");
            requireText(deformerSourceOwner, "deformerSourceOwner");
            requireText(deformerIdMethod, "deformerIdMethod");
            requireText(idStringMethod, "idStringMethod");
            Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        }

        String deformerId(final Object row) throws ReflectiveOperationException {
            if (row == null || row.getClass().getClassLoader() != hostClassLoader
                || !row.getClass().getName().equals(rowSourceOwner.replace('/', '.'))) return null;
            final java.lang.reflect.Method rowSource = row.getClass().getDeclaredMethod(rowSourceMethod);
            if (!rowSource.canAccess(row) && !rowSource.trySetAccessible()) return null;
            final Object source = rowSource.invoke(row);
            if (source == null || source.getClass().getClassLoader() != hostClassLoader
                || !isTypeOrSuper(source.getClass(), deformerSourceOwner.replace('/', '.'))) return null;
            final Object id = source.getClass().getMethod(deformerIdMethod).invoke(source);
            final Object value = id == null ? null : id.getClass().getMethod(idStringMethod).invoke(id);
            return value instanceof String text && !text.isBlank() ? text : null;
        }

        private static boolean isTypeOrSuper(final Class<?> type, final String expected) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (current.getName().equals(expected)) return true;
            }
            return false;
        }

        private static String requireText(final String value, final String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }
}
