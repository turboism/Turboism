package dev.turboism.ui.appearance.control;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed ingress from exact-version native Part-tree renderer hooks. */
public final class NativePartTreeAppearanceBridge {
    private static final AtomicReference<Callback> CALLBACK = new AtomicReference<>();
    private static final AtomicReference<PartTreeControlAppearanceProvider> PROVIDER = new AtomicReference<>();

    private NativePartTreeAppearanceBridge() { }

    public static Component afterRender(final Component component, final Object value) {
        if (component == null) return null;
        final Callback callback = CALLBACK.get();
        if (callback == null || !javax.swing.SwingUtilities.isEventDispatchThread()) return component;
        try { return Objects.requireNonNullElse(callback.apply(component, value), component); }
        catch (Throwable ignored) { return component; }
    }

    public static void install(
        final long hostGeneration,
        final Selectors selectors,
        final PartTreeControlAppearanceProvider provider
    ) {
        Objects.requireNonNull(selectors, "selectors");
        Objects.requireNonNull(provider, "provider");
        PROVIDER.set(provider);
        install((component, value) -> {
            final Component label = label(component);
            if (label == null) return component;
            try {
                final Selectors.Part part = selectors.part(value);
                if (part == null) provider.restore();
                else provider.apply(hostGeneration, part.id(), part.folder(), label);
            } catch (ReflectiveOperationException ignored) {
                provider.restore();
            }
            return component;
        });
    }

    static void install(final Callback callback) {
        if (!CALLBACK.compareAndSet(null, Objects.requireNonNull(callback, "callback"))) {
            throw new IllegalStateException("native Part appearance bridge is already installed");
        }
    }

    public static void uninstall() {
        CALLBACK.set(null);
        final PartTreeControlAppearanceProvider provider = PROVIDER.getAndSet(null);
        if (provider != null) provider.close();
    }

    static void clearForTesting() { uninstall(); }

    private static Component label(final Component component) {
        if (component instanceof JLabel) return component;
        if (component instanceof Container container && container.getComponentCount() > 0
            && container.getComponent(0) instanceof JLabel label) return label;
        return null;
    }

    @FunctionalInterface
    interface Callback { Component apply(Component component, Object value); }

    public record Selectors(
        String nodeOwner,
        String nodeSourceMethod,
        String partSourceOwner,
        String partIdMethod,
        String idStringMethod,
        String childrenMethod,
        ClassLoader hostClassLoader
    ) {
        public Selectors {
            requireText(nodeOwner, "nodeOwner");
            requireText(nodeSourceMethod, "nodeSourceMethod");
            requireText(partSourceOwner, "partSourceOwner");
            requireText(partIdMethod, "partIdMethod");
            requireText(idStringMethod, "idStringMethod");
            requireText(childrenMethod, "childrenMethod");
            Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        }

        Part part(final Object node) throws ReflectiveOperationException {
            if (node == null || node.getClass().getClassLoader() != hostClassLoader
                || !isTypeOrSuper(node.getClass(), nodeOwner.replace('/', '.'))) return null;
            final Object source = invoke(node, nodeSourceMethod);
            if (source == null || source.getClass().getClassLoader() != hostClassLoader
                || !isTypeOrSuper(source.getClass(), partSourceOwner.replace('/', '.'))) return null;
            final Object id = invoke(source, partIdMethod);
            final Object value = id == null ? null : invoke(id, idStringMethod);
            if (!(value instanceof String text) || text.isBlank()) return null;
            final Object children = invoke(source, childrenMethod);
            if (!(children instanceof Collection<?> values)) return null;
            return new Part(text, !values.isEmpty());
        }

        private static Object invoke(final Object target, final String methodName)
            throws ReflectiveOperationException {
            final Method method = target.getClass().getMethod(methodName);
            if (!method.canAccess(target) && !method.trySetAccessible()) return null;
            return method.invoke(target);
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

        record Part(String id, boolean folder) { }
    }
}
