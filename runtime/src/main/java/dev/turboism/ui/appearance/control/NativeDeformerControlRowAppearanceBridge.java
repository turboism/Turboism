package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed ingress from native deformer control-row renderer hooks. */
public final class NativeDeformerControlRowAppearanceBridge {
    private static final AtomicReference<Installed> INSTALLED = new AtomicReference<>();

    private NativeDeformerControlRowAppearanceBridge() { }

    public static Component afterRender(final Component component, final Object renderer, final int row) {
        if (component == null) return null;
        final Installed installed = INSTALLED.get();
        if (installed == null || !javax.swing.SwingUtilities.isEventDispatchThread()) return component;
        try {
            final String id = installed.selectors().deformerId(renderer, row);
            if (id == null) installed.provider().restore();
            else installed.provider().apply(installed.generation(), id, component);
        } catch (Throwable ignored) {
            installed.provider().restore();
        }
        return component;
    }

    public static void install(
        final long generation,
        final Selectors selectors,
        final DeformerControlRowAppearanceProvider provider
    ) {
        if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
        if (!INSTALLED.compareAndSet(null, new Installed(generation,
            Objects.requireNonNull(selectors, "selectors"), Objects.requireNonNull(provider, "provider")))) {
            throw new IllegalStateException("native deformer control-row bridge is already installed");
        }
    }

    public static void uninstall() {
        final Installed installed = INSTALLED.getAndSet(null);
        if (installed != null) installed.provider().close();
    }

    static void clearForTesting() { uninstall(); }

    private record Installed(long generation, Selectors selectors, DeformerControlRowAppearanceProvider provider) { }

    public record Selectors(
        String rendererOwner,
        String outerField,
        String outerOwner,
        String treeAccessorMethod,
        String treeOwner,
        String pathForRowMethod,
        String rowOwner,
        String rowSourceMethod,
        String deformerSourceOwner,
        String deformerIdMethod,
        String idStringMethod,
        ClassLoader hostClassLoader
    ) {
        public Selectors {
            for (String value : new String[]{rendererOwner, outerField, outerOwner, treeAccessorMethod,
                treeOwner, pathForRowMethod, rowOwner, rowSourceMethod, deformerSourceOwner,
                deformerIdMethod, idStringMethod}) requireText(value);
            Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        }

        String deformerId(final Object renderer, final int row) throws ReflectiveOperationException {
            if (!exact(renderer, rendererOwner)) return null;
            final Object outer = field(renderer, outerField);
            if (!exact(outer, outerOwner)) return null;
            final Class<?> outerType = Class.forName(outerOwner.replace('/', '.'), false, hostClassLoader);
            final Method accessor = outerType.getMethod(treeAccessorMethod, outerType);
            final Object tree = accessor.invoke(null, outer);
            if (tree == null || tree.getClass().getClassLoader() != hostClassLoader
                || !isTypeOrSuper(tree.getClass(), treeOwner.replace('/', '.'))) return null;
            final Object path = invoke(tree, pathForRowMethod, int.class, row);
            final Object rowValue = path == null ? null : invoke(path, "getLastPathComponent");
            if (!exact(rowValue, rowOwner)) return null;
            final Object source = invoke(rowValue, rowSourceMethod);
            if (source == null || source.getClass().getClassLoader() != hostClassLoader
                || !isTypeOrSuper(source.getClass(), deformerSourceOwner.replace('/', '.'))) return null;
            final Object id = invoke(source, deformerIdMethod);
            final Object value = id == null ? null : invoke(id, idStringMethod);
            return value instanceof String text && !text.isBlank() ? text : null;
        }

        private boolean exact(final Object value, final String owner) {
            return value != null && value.getClass().getClassLoader() == hostClassLoader
                && value.getClass().getName().equals(owner.replace('/', '.'));
        }

        private static boolean isTypeOrSuper(final Class<?> type, final String expected) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (current.getName().equals(expected)) return true;
            }
            return false;
        }

        private static void requireText(final String value) {
            Objects.requireNonNull(value, "selector");
            if (value.isBlank()) throw new IllegalArgumentException("selector must not be blank");
        }
    }

    private static Object field(final Object target, final String name) throws ReflectiveOperationException {
        final Field field = target.getClass().getDeclaredField(name);
        if (!field.canAccess(target) && !field.trySetAccessible()) return null;
        return field.get(target);
    }

    private static Object invoke(final Object target, final String name, final Class<?> type, final Object argument)
        throws ReflectiveOperationException {
        final Method method = target.getClass().getMethod(name, type);
        if (!method.canAccess(target) && !method.trySetAccessible()) return null;
        return method.invoke(target, argument);
    }

    private static Object invoke(final Object target, final String name) throws ReflectiveOperationException {
        final Method method = target.getClass().getMethod(name);
        if (!method.canAccess(target) && !method.trySetAccessible()) return null;
        return method.invoke(target);
    }
}
