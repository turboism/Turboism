package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.PaletteEntryState;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Restores native component state around transient appearance overlays. */
final class NativeStyleTracker {
    private final Map<Component, AppliedStyle> applied = Collections.synchronizedMap(new WeakHashMap<>());

    void apply(final Component component, final Optional<PaletteEntryState> style) {
        final AppliedStyle previous = applied.get(component);
        final ComponentState observed = ComponentState.capture(component);
        final ComponentState nativeState = previous == null || !observed.equals(previous.applied())
            ? observed : previous.nativeState();
        nativeState.restore(component);
        if (style.isEmpty()) {
            applied.remove(component);
            return;
        }
        final NativeControlStyle overlay = NativeControlStyle.apply(
            nativeState.font(), nativeState.foreground(), nativeState.background(), nativeState.opaque(), style.orElseThrow()
        );
        overlay.restore(component);
        final ComponentState result = ComponentState.capture(component);
        if (result.equals(nativeState)) applied.remove(component);
        else applied.put(component, new AppliedStyle(nativeState, result));
    }

    void restoreAll() {
        final Map<Component, AppliedStyle> restore;
        synchronized (applied) {
            restore = Map.copyOf(applied);
            applied.clear();
        }
        restore.forEach((component, style) -> style.nativeState().restore(component));
    }

    private record AppliedStyle(ComponentState nativeState, ComponentState applied) {
    }

    private record ComponentState(Font font, Color foreground, Color background, boolean opaque) {
        static ComponentState capture(final Component component) {
            return new ComponentState(
                component.getFont(), component.getForeground(), component.getBackground(),
                component instanceof JComponent swing && swing.isOpaque()
            );
        }

        void restore(final Component component) {
            component.setFont(font);
            component.setForeground(foreground);
            component.setBackground(background);
            if (component instanceof JComponent swing) swing.setOpaque(opaque);
            component.repaint();
        }
    }
}
