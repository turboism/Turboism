package dev.turboism.ui.appearance.control;

import dev.turboism.core.reflect.MethodHandleCache;

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

    /**
     * Entry point the instrumented host Part-tree renderer calls after producing a row component.
     *
     * <p>Fail-closed and never disruptive: returns {@code component} unchanged when no bridge is
     * installed or when called off the Swing event dispatch thread, and swallows any {@code Throwable}
     * the callback raises. Only a {@code JLabel}, or a container whose first child is one, is styled.
     *
     * @param component the component the host renderer produced; {@code null} is returned as-is
     * @param value the host tree node the selectors resolve a part, deformer or art mesh from
     * @return {@code component}, styled where possible and otherwise untouched
     */
    public static Component afterRender(final Component component, final Object value) {
        if (component == null) return null;
        final Callback callback = CALLBACK.get();
        if (callback == null || !javax.swing.SwingUtilities.isEventDispatchThread()) return component;
        try { return Objects.requireNonNullElse(callback.apply(component, value), component); }
        catch (Throwable ignored) { return component; }
    }

    /**
     * Installs the single process-wide bridge, wiring the host renderer hook to a provider.
     *
     * <p>Exactly one installation may be live at a time. A node whose backing object cannot be
     * classified restores the provider's styling rather than leaving a stale colour behind.
     *
     * @param hostGeneration the host generation styling is applied under
     * @param selectors reflective coordinates of the host node and source accessors; must not be
     *     {@code null}
     * @param provider the provider that applies and restores styling, closed on uninstall; must not be
     *     {@code null}
     * @throws IllegalStateException if a bridge is already installed
     * @throws NullPointerException if {@code selectors} or {@code provider} is {@code null}
     */
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
                else provider.apply(hostGeneration, part.id(), part.folder(), part.kind(), label);
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

    /**
     * Detaches the host hook and closes the installed provider, restoring every component it had
     * styled.
     *
     * <p>Idempotent — uninstalling when nothing is installed does nothing.
     */
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

    /**
     * Reflective coordinates of the host's Part-tree internals: how to get from a tree node to the
     * part, deformer or art mesh it displays, and from that to its id string.
     *
     * <p>Node and source classes are matched by type or supertype and must come from
     * {@code hostClassLoader}, so a look-alike class from another loader is ignored. *
     * <p>All owner and member names are internal/binary names of host classes, and are validated only
     * for being non-blank — a name that does not match the running Editor build simply makes lookup
     * fail, which the bridge treats as "no id" rather than an error.
     *
     * @param nodeOwner internal name of the host tree-node class the hook receives
     * @param nodeSourceMethod method on a node returning the model object it displays
     * @param partSourceOwner internal name of the host part class, resolving to the PART palette
     * @param deformerSourceOwner internal name of the host deformer class, resolving to DEFORMER_PART
     * @param artMeshSourceOwner internal name of the host art-mesh class, also resolving to
     *     DEFORMER_PART
     * @param partIdMethod method on a source returning its id object
     * @param idStringMethod method on an id object returning the id as a string
     * @param childrenMethod method on a source returning its children, used to decide folder-ness
     * @param hostClassLoader the loader host classes must come from; must not be {@code null}
     * @throws IllegalArgumentException if any name is blank
     * @throws NullPointerException if any component is {@code null}
     */
    public record Selectors(
        String nodeOwner,
        String nodeSourceMethod,
        String partSourceOwner,
        String deformerSourceOwner,
        String artMeshSourceOwner,
        String partIdMethod,
        String idStringMethod,
        String childrenMethod,
        ClassLoader hostClassLoader
    ) {
        public Selectors {
            requireText(nodeOwner, "nodeOwner");
            requireText(nodeSourceMethod, "nodeSourceMethod");
            requireText(partSourceOwner, "partSourceOwner");
            requireText(deformerSourceOwner, "deformerSourceOwner");
            requireText(artMeshSourceOwner, "artMeshSourceOwner");
            requireText(partIdMethod, "partIdMethod");
            requireText(idStringMethod, "idStringMethod");
            requireText(childrenMethod, "childrenMethod");
            Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        }

        /** Row source kind: parts resolve the PART palette; deformers and art meshes share DEFORMER_PART. */
        public enum SourceKind { PART, DEFORMER, ART_MESH }

        Part part(final Object node) throws ReflectiveOperationException {
            if (node == null || node.getClass().getClassLoader() != hostClassLoader
                || !isTypeOrSuper(node.getClass(), nodeOwner.replace('/', '.'))) return null;
            final Object source = invoke(node, nodeSourceMethod);
            if (source == null || source.getClass().getClassLoader() != hostClassLoader) return null;
            final SourceKind kind;
            if (isTypeOrSuper(source.getClass(), partSourceOwner.replace('/', '.'))) {
                kind = SourceKind.PART;
            } else if (isTypeOrSuper(source.getClass(), deformerSourceOwner.replace('/', '.'))) {
                kind = SourceKind.DEFORMER;
            } else if (isTypeOrSuper(source.getClass(), artMeshSourceOwner.replace('/', '.'))) {
                kind = SourceKind.ART_MESH;
            } else {
                return null;
            }
            final Object id = invoke(source, partIdMethod);
            final Object value = id == null ? null : invoke(id, idStringMethod);
            if (!(value instanceof String text) || text.isBlank()) return null;
            if (kind != SourceKind.PART) {
                return new Part(text, false, kind);
            }
            final Object children = invoke(source, childrenMethod);
            if (!(children instanceof Collection<?> values)) return null;
            return new Part(text, !values.isEmpty(), kind);
        }

        private static Object invoke(final Object target, final String methodName)
            throws ReflectiveOperationException {
            final Method method = MethodHandleCache.method(target.getClass(), methodName);
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

        record Part(String id, boolean folder, SourceKind kind) { }
    }
}
