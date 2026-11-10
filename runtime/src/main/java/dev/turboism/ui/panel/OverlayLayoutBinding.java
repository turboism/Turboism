package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.LayoutManager2;
import java.util.LinkedHashSet;
import java.util.Set;

/** Synchronizes anchored overlays before the native layout pass can reach painting. */
final class OverlayLayoutBinding implements LayoutManager2 {
    private static final float CENTER_ALIGNMENT = 0.5f;
    private final LayoutManager delegate;
    private final Set<Runnable> updates = new LinkedHashSet<>();

    private OverlayLayoutBinding(final LayoutManager delegate) {
        this.delegate = delegate;
    }

    static Registration attach(final Container parent, final Runnable update) {
        final OverlayLayoutBinding binding;
        if (parent.getLayout() instanceof OverlayLayoutBinding existing) {
            binding = existing;
        } else {
            binding = new OverlayLayoutBinding(parent.getLayout());
            parent.setLayout(binding);
        }
        binding.updates.add(update);
        update.run();
        return () -> {
            binding.updates.remove(update);
            if (binding.updates.isEmpty() && parent.getLayout() == binding) {
                parent.setLayout(binding.delegate);
            }
        };
    }

    @Override
    public void layoutContainer(final Container parent) {
        if (delegate != null) delegate.layoutContainer(parent);
        for (Runnable update : Set.copyOf(updates)) update.run();
    }

    @Override
    public void addLayoutComponent(final String name, final Component component) {
        if (delegate != null) delegate.addLayoutComponent(name, component);
    }

    @Override
    public void addLayoutComponent(final Component component, final Object constraints) {
        if (delegate instanceof LayoutManager2 advanced) advanced.addLayoutComponent(component, constraints);
        else if (delegate != null) delegate.addLayoutComponent((String) constraints, component);
    }

    @Override
    public void removeLayoutComponent(final Component component) {
        if (delegate != null) delegate.removeLayoutComponent(component);
    }

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
        return delegate == null ? parent.getSize() : delegate.preferredLayoutSize(parent);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
        return delegate == null ? new Dimension() : delegate.minimumLayoutSize(parent);
    }

    @Override
    public Dimension maximumLayoutSize(final Container parent) {
        return delegate instanceof LayoutManager2 advanced
                ? advanced.maximumLayoutSize(parent)
                : new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public float getLayoutAlignmentX(final Container parent) {
        return delegate instanceof LayoutManager2 advanced ? advanced.getLayoutAlignmentX(parent) : CENTER_ALIGNMENT;
    }

    @Override
    public float getLayoutAlignmentY(final Container parent) {
        return delegate instanceof LayoutManager2 advanced ? advanced.getLayoutAlignmentY(parent) : CENTER_ALIGNMENT;
    }

    @Override
    public void invalidateLayout(final Container parent) {
        if (delegate instanceof LayoutManager2 advanced) advanced.invalidateLayout(parent);
    }
}
