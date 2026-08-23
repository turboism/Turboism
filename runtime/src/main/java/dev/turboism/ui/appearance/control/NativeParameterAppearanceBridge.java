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

    /**
     * Entry point the instrumented host parameter-row constructor calls once the row is built, binding
     * that row's label so it can be styled.
     *
     * <p>Fail-open: does nothing when no bridge is installed or the row is {@code null}, and swallows
     * any {@code Throwable} raised while reading the row. Calls arriving off the Swing event dispatch
     * thread are re-posted with {@code invokeLater} — and dropped if the bridge is replaced in the
     * meantime — rather than touching Swing from the wrong thread.
     *
     * @param row the newly constructed host parameter row
     */
    public static void afterParameterRow(final Object row) { after(row, false); }
    /**
     * Entry point the instrumented host parameter-folder constructor calls once the folder row is
     * built, binding its label so it can be styled.
     *
     * <p>Behaves exactly like the parameter-row entry point — fail-open, thread-hopping onto the
     * Swing event dispatch thread when needed — but binds against the parameter-group palette.
     *
     * @param row the newly constructed host parameter folder row
     */
    public static void afterParameterFolder(final Object row) { after(row, true); }

    private static void after(final Object row, final boolean folder) {
        final Installed installed = INSTALLED.get();
        if (installed == null || row == null) return;
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (INSTALLED.get() == installed) after(row, folder);
            });
            return;
        }
        try {
            if (folder) installed.bindFolder(row);
            else installed.bindParameter(row);
        } catch (Throwable ignored) {
            // Native callback must remain fail-open.
        }
    }

    /**
     * Installs the single process-wide bridge, wiring the host row hooks to a provider.
     *
     * <p>Exactly one installation may be live at a time; a second attempt is refused rather than
     * silently replacing the first.
     *
     * @param selectors reflective coordinates of the host row, label and id accessors; must not be
     *     {@code null}
     * @param provider the provider that owns the row bindings, closed on uninstall; must not be
     *     {@code null}
     * @throws IllegalStateException if a bridge is already installed
     * @throws NullPointerException if {@code selectors} or {@code provider} is {@code null}
     */
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

    /**
     * Removes the installed bridge and closes its provider, which unbinds and restores every row the
     * provider had styled.
     *
     * <p>Idempotent — uninstalling when nothing is installed does nothing.
     */
    public static void uninstall() {
        final Installed installed = INSTALLED.getAndSet(null);
        if (installed != null) installed.provider().close();
    }

    static void clearForTesting() { uninstall(); }

    /** Replays exact host row widgets supplied by the verified palette-operation selector. */
    public static void replayExistingRows(final Iterable<?> rows) {
        Objects.requireNonNull(rows, "rows");
        final Runnable replay = () -> {
            final Installed installed = INSTALLED.get();
            if (installed == null) return;
            for (Object row : rows) {
                if (INSTALLED.get() != installed) return;
                if (installed.selectors().folderRow(row)) after(row, true);
                else if (installed.selectors().parameterRow(row)) after(row, false);
            }
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) replay.run();
        else javax.swing.SwingUtilities.invokeLater(replay);
    }

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

    /**
     * Reflective coordinates of the host's parameter-palette row internals: which classes are rows,
     * how to reach their label widgets, and how to read the parameter or folder id behind them.
     *
     * <p>Rows are matched by exact class name, not by assignability, so an unexpected host subclass is
     * ignored rather than mis-bound. The double row carries a second parameter and label, which is why
     * the secondary accessors exist. *
     * <p>All owner and member names are internal/binary names of host classes, and are validated only
     * for being non-blank — a name that does not match the running Editor build simply makes lookup
     * fail, which the bridge treats as "no id" rather than an error.
     *
     * @param singleRowOwner internal name of the host row class holding one parameter
     * @param doubleRowOwner internal name of the host row class holding two parameters
     * @param folderRowOwner internal name of the host parameter-folder row class
     * @param parameterSourceMethod method on a row returning its (first) parameter object
     * @param secondaryParameterSourceMethod method on a double row returning its second parameter
     * @param folderSourceMethod method on a folder row returning its group object
     * @param parameterLabelField field on a row holding its (first) label widget
     * @param secondaryParameterLabelField field on a double row holding its second label widget
     * @param folderLabelMethod method on a folder row returning its label widget
     * @param parameterSourceOwner internal name of the host parameter class
     * @param folderSourceOwner internal name of the host parameter-group class
     * @param parameterIdMethod method on a parameter returning its id object
     * @param folderIdMethod method on a group returning its id object
     * @param idStringMethod method on an id object returning the id as a string
     * @param cLabelOwner internal name of the host's own label wrapper class
     * @param cLabelSwingMethod method on that wrapper returning the underlying Swing component
     * @param hostClassLoader the loader host classes must come from, so foreign look-alike classes are
     *     ignored; must not be {@code null}
     * @throws IllegalArgumentException if any name is blank
     * @throws NullPointerException if any component is {@code null}
     */
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
