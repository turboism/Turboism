package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed ingress from exact parameter-row constructor and selection hooks. */
public final class NativeParameterAppearanceBridge {
    private static final AtomicReference<Installed> INSTALLED = new AtomicReference<>();

    private NativeParameterAppearanceBridge() { }

    public static void afterParameterRow(final Object row) { after(row, false); }
    public static void afterParameterFolder(final Object row) { after(row, true); }

    private static void after(final Object row, final boolean folder) {
        final Installed installed = INSTALLED.get();
        if (installed == null || row == null || !javax.swing.SwingUtilities.isEventDispatchThread()) return;
        try {
            if (folder) installed.bindFolder(row);
            else installed.bindParameter(row);
        } catch (Throwable ignored) {
            // Native callback must remain fail-open.
        }
    }

    public static void install(
        final Selectors selectors,
        final ParameterControlAppearanceProvider provider
    ) {
        final Installed value = new Installed(
            Objects.requireNonNull(selectors, "selectors"),
            Objects.requireNonNull(provider, "provider")
        );
        if (!INSTALLED.compareAndSet(null, value)) {
            throw new IllegalStateException("native parameter appearance bridge is already installed");
        }
    }

    public static void uninstall() {
        final Installed installed = INSTALLED.getAndSet(null);
        if (installed != null) installed.provider().close();
    }

    static void clearForTesting() { uninstall(); }

    private record Installed(Selectors selectors, ParameterControlAppearanceProvider provider) {
        void bindParameter(final Object row) throws ReflectiveOperationException {
            if (!selectors.parameterRow(row)) return;
            bind(row, selectors.parameterSourceMethod(), selectors.parameterLabelField(), ParameterControlAppearanceProvider.Kind.PARAMETER);
            if (row.getClass().getName().equals(selectors.doubleRowOwner().replace('/', '.'))) {
                bind(row, selectors.secondaryParameterSourceMethod(), selectors.secondaryParameterLabelField(), ParameterControlAppearanceProvider.Kind.PARAMETER);
            }
        }

        void bindFolder(final Object row) throws ReflectiveOperationException {
            if (!selectors.folderRow(row)) return;
            final Object source = invoke(row, selectors.folderSourceMethod());
            final String id = selectors.id(source, selectors.folderIdMethod());
            final Object cLabel = invoke(row, selectors.folderLabelMethod());
            final Component component = selectors.swingLabel(cLabel);
            if (id != null && component != null) provider.bind(ParameterControlAppearanceProvider.Kind.FOLDER, id, component);
        }

        private void bind(
            final Object row, final String sourceMethod, final String labelField,
            final ParameterControlAppearanceProvider.Kind kind
        ) throws ReflectiveOperationException {
            final Object source = invoke(row, sourceMethod);
            final String id = selectors.id(source, selectors.parameterIdMethod());
            final Component component = selectors.swingLabel(field(row, labelField));
            if (id != null && component != null) provider.bind(kind, id, component);
        }
    }

    public record Selectors(
        String singleRowOwner,
        String doubleRowOwner,
        String folderRowOwner,
        String parameterSourceMethod,
        String secondaryParameterSourceMethod,
        String folderSourceMethod,
        String parameterLabelField,
        String secondaryParameterLabelField,
        String folderLabelMethod,
        String parameterSourceOwner,
        String folderSourceOwner,
        String parameterIdMethod,
        String folderIdMethod,
        String idStringMethod,
        String cLabelOwner,
        String cLabelSwingMethod,
        ClassLoader hostClassLoader
    ) {
        public Selectors {
            for (String value : new String[]{singleRowOwner, doubleRowOwner, folderRowOwner, parameterSourceMethod,
                secondaryParameterSourceMethod, folderSourceMethod, parameterLabelField, secondaryParameterLabelField,
                folderLabelMethod, parameterSourceOwner, folderSourceOwner, parameterIdMethod, folderIdMethod,
                idStringMethod, cLabelOwner, cLabelSwingMethod}) requireText(value);
            Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        }

        boolean parameterRow(final Object row) {
            return exact(row, singleRowOwner) || exact(row, doubleRowOwner);
        }
        boolean folderRow(final Object row) { return exact(row, folderRowOwner); }

        String id(final Object source, final String idMethod) throws ReflectiveOperationException {
            if (source == null || source.getClass().getClassLoader() != hostClassLoader
                || !(isTypeOrSuper(source.getClass(), parameterSourceOwner.replace('/', '.'))
                || isTypeOrSuper(source.getClass(), folderSourceOwner.replace('/', '.')))) return null;
            final Object id = invoke(source, idMethod);
            final Object value = id == null ? null : invoke(id, idStringMethod);
            return value instanceof String text && !text.isBlank() ? text : null;
        }

        Component swingLabel(final Object cLabel) throws ReflectiveOperationException {
            if (cLabel == null || cLabel.getClass().getClassLoader() != hostClassLoader
                || !isTypeOrSuper(cLabel.getClass(), cLabelOwner.replace('/', '.'))) return null;
            final Object value = invoke(cLabel, cLabelSwingMethod);
            return value instanceof Component component ? component : null;
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

    private static Object invoke(final Object target, final String methodName) throws ReflectiveOperationException {
        final Method method = target.getClass().getMethod(methodName);
        if (!method.canAccess(target) && !method.trySetAccessible()) return null;
        return method.invoke(target);
    }

    private static Object field(final Object target, final String fieldName) throws ReflectiveOperationException {
        final Field field = target.getClass().getDeclaredField(fieldName);
        if (!field.canAccess(target) && !field.trySetAccessible()) return null;
        return field.get(target);
    }
}
