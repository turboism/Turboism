package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;

import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Owns long-lived parameter-row bindings and transient native styles. */
public final class ParameterControlAppearanceProvider implements AutoCloseable {
    public enum Kind { PARAMETER, FOLDER }

    private final ControlAppearanceCoordinator coordinator;
    private final NativeStyleTracker styles = new NativeStyleTracker();
    private final List<Binding> bindings = new ArrayList<>();
    private final AutoCloseable changeSubscription;
    private volatile long hostGeneration;

    public ParameterControlAppearanceProvider(
        final long hostGeneration,
        final ControlAppearanceCoordinator coordinator
    ) {
        if (hostGeneration <= 0) throw new IllegalArgumentException("hostGeneration must be positive");
        this.hostGeneration = hostGeneration;
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changeSubscription = coordinator.onChange(this::reapply);
    }

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
        final Optional<ControlAppearanceStyle> style = hostGeneration == coordinator.hostGeneration()
            ? (kind == Kind.PARAMETER ? coordinator.parameterLabel(id) : coordinator.parameterFolder(id))
            : Optional.empty();
        styles.apply(component, style);
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
