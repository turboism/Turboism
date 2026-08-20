package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Owns long-lived parameter-row bindings and transient native palette entries. */
public final class ParameterControlAppearanceProvider implements AutoCloseable {
    public enum Kind { PARAMETER, FOLDER }

    private final PaletteAppearanceCoordinator coordinator;
    private final NativeStyleTracker styles = new NativeStyleTracker();
    private final List<Binding> bindings = new ArrayList<>();
    private final AutoCloseable changeSubscription;
    private volatile long hostGeneration;

    public ParameterControlAppearanceProvider(
        final long hostGeneration,
        final PaletteAppearanceCoordinator coordinator
    ) {
        if (hostGeneration <= 0) throw new IllegalArgumentException("hostGeneration must be positive");
        this.hostGeneration = hostGeneration;
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changeSubscription = coordinator.onChange(this::reapply);
    }

    /**
     * Registers one parameter or folder row label for styling and applies its palette entry
     * immediately.
     *
     * <p>Bindings are long-lived but hold the component only weakly, so a row the host discards is
     * dropped at the next reapply rather than leaked. Re-binding the same component replaces its
     * previous binding. Must be called on the Swing event dispatch thread; off the EDT the call is a
     * silent no-op and nothing is bound.
     *
     * @param kind whether the row is a single parameter or a parameter folder, selecting the PARAMETER
     *     or PARAMETER_GROUP palette; must not be {@code null}
     * @param id the parameter or group id used as the palette lookup key; must not be {@code null}
     * @param component the label component to style; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public void bind(final Kind kind, final String id, final Component component) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(component, "component");
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) return;
        bindings.removeIf(binding -> binding.component().get() == component);
        bindings.add(new Binding(kind, id, new WeakReference<>(component)));
        coordinator.bindParameterControl(kind == Kind.FOLDER, id, component);
        apply(kind, id, component);
    }

    /**
     * Restores every tracked component to its original styling and re-applies the current palette to
     * all live bindings, dropping bindings whose component has been garbage collected.
     *
     * <p>Invoked automatically whenever the coordinator reports a palette change. Safe to call from
     * any thread: off the event dispatch thread the work is posted with {@code invokeLater} rather
     * than run inline, so it may not have taken effect when this returns.
     */
    public void reapply() {
        final Runnable action = () -> {
            styles.restoreAll();
            final Iterator<Binding> iterator = bindings.iterator();
            while (iterator.hasNext()) {
                final Binding binding = iterator.next();
                final Component component = binding.component().get();
                if (component == null) iterator.remove();
                else apply(binding.kind(), binding.id(), component);
            }
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) action.run();
        else javax.swing.SwingUtilities.invokeLater(action);
    }

    private void apply(final Kind kind, final String id, final Component component) {
        styles.apply(
            component,
            coordinator.resolveCurrent(hostGeneration, kind == Kind.FOLDER
                ? PaletteAppearanceCoordinator.Palette.PARAMETER_GROUP
                : PaletteAppearanceCoordinator.Palette.PARAMETER, id)
        );
    }

    @Override
    public void close() {
        hostGeneration = 0;
        try { changeSubscription.close(); } catch (Exception ignored) { }
        final Runnable action = () -> {
            styles.restoreAll();
            for (Binding binding : bindings) {
                final Component component = binding.component().get();
                if (component != null) {
                    coordinator.unbindParameterControl(component);
                }
            }
            bindings.clear();
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) action.run();
        else javax.swing.SwingUtilities.invokeLater(action);
    }

    private record Binding(Kind kind, String id, WeakReference<Component> component) { }
}
