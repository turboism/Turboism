package dev.turboism.ui.panel;

import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.UIManager;

/**
 * Owns the themed background of a Swing surface the runtime injects into a native host.
 *
 * <p>A host container built by the editor keeps the background it resolved when it was created, so
 * a container that the runtime injects into after a theme change would otherwise show the native
 * colour. One surface paints exactly one themed background behind the host's own content, and the
 * same ownership recolours the plated icons of the hosted buttons, so the host never draws a light
 * box behind each of them.</p>
 *
 * <p>The owner count keeps the original appearance safe when several controls share one host
 * container: the surface is restored only after the last owner releases it. Call the mutators on
 * the Swing event dispatch thread, and call {@link #refresh()} when the look and feel changes so a
 * live theme switch repaints the same surface and recolours the same icons.</p>
 */
public final class ThemedHostSurface {

    private static final String BACKGROUND_KEY = "Panel.background";
    private static final Color FALLBACK_BACKGROUND = new Color(0x2b2b2b);
    private static final int FIRST_OWNER = 1;

    private final Map<JComponent, Surface> surfaces = new IdentityHashMap<>();
    private final ThemedNativeIcons icons = new ThemedNativeIcons();

    /**
     * Paints the themed background on one host surface, recolours its plated icons, and records the
     * original appearance.
     *
     * <p>Repeated calls for the same component add owners instead of nesting state, so a control
     * that installs and uninstalls repeatedly never loses the appearance it must restore.</p>
     *
     * @param surface the host container that must paint the themed background; not {@code null}
     * @throws NullPointerException if {@code surface} is {@code null}
     */
    public void paint(final JComponent surface) {
        Objects.requireNonNull(surface, "surface");
        final Surface existing = surfaces.get(surface);
        if (existing == null) {
            surfaces.put(surface, new Surface(surface.getBackground(), surface.isOpaque(), FIRST_OWNER));
        } else {
            existing.owners++;
        }
        apply(surface);
        icons.decorate(surface);
    }

    /**
     * Releases one ownership of a host surface and restores it when the last owner leaves.
     *
     * <p>A surface this instance does not own is ignored, so a caller may release defensively
     * without tracking whether the peer was available.</p>
     *
     * @param surface the host container to release; {@code null} is ignored
     */
    public void release(final JComponent surface) {
        if (surface == null) return;
        final Surface existing = surfaces.get(surface);
        if (existing == null) return;
        existing.owners--;
        if (existing.owners > 0) return;
        surfaces.remove(surface);
        icons.restore(surface);
        surface.setBackground(existing.background);
        surface.setOpaque(existing.opaque);
        surface.repaint();
    }

    /**
     * Reapplies the current themed appearance to every owned surface.
     *
     * <p>Call this after the look and feel changed, because a component that was already resolved
     * would otherwise keep the previous theme's colour and the previous theme's glyph tint. A theme
     * that left restores the shipped host icons.</p>
     */
    public void refresh() {
        icons.refresh();
        for (JComponent surface : List.copyOf(surfaces.keySet())) {
            apply(surface);
            surface.repaint();
        }
    }

    private static void apply(final JComponent surface) {
        surface.setOpaque(true);
        surface.setBackground(themedBackground());
    }

    private static Color themedBackground() {
        final Color themed = UIManager.getColor(BACKGROUND_KEY);
        return themed == null ? FALLBACK_BACKGROUND : themed;
    }

    /** Original appearance of one surface and the number of controls that currently own it. */
    private static final class Surface {
        private final Color background;
        private final boolean opaque;
        private int owners;

        private Surface(final Color background, final boolean opaque, final int owners) {
            this.background = background;
            this.opaque = opaque;
            this.owners = owners;
        }
    }
}
