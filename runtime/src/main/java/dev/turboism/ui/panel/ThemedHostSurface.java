package dev.turboism.ui.panel;

import dev.turboism.ui.appearance.ThemeRuntimeProperties;
import java.awt.Color;
import java.awt.Component;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;

/**
 * Owns the themed appearance of the Swing surfaces the runtime injects into a native host.
 *
 * <p>A host container built by the editor keeps the background it resolved when it was created. The
 * ArtMesh tool-details tab therefore keeps the native colour after a theme change. This owner
 * paints the theme panel background on the whole native container tree of one host root. It also
 * recolours the plated icons of the hosted buttons and gives the buttons the theme state colours.
 * The host then never paints a light tile behind an icon and never leaves a native plate on a
 * disabled toggle.</p>
 *
 * <p>The owner count keeps the original appearance safe when several controls share one host
 * surface or one host root. State is restored only after the last owner releases it. Call the
 * mutators on the Swing event dispatch thread. Call {@link #refresh()} when the look and feel
 * changes, so a live theme switch repaints the same surfaces, buttons and icons.</p>
 */
public final class ThemedHostSurface {

    private static final String BACKGROUND_KEY = "Panel.background";
    private static final Color FALLBACK_BACKGROUND = new Color(0x2b2b2b);
    private static final int FIRST_OWNER = 1;

    private final Map<JComponent, Surface> surfaces = new IdentityHashMap<>();
    private final Map<JComponent, Tree> trees = new IdentityHashMap<>();
    private final ThemedNativeIcons icons;
    private final ThemedNativeButtons buttons;

    /** Creates an owner that follows the theme the runtime currently publishes. */
    public ThemedHostSurface() {
        this(ThemeRuntimeProperties::isPublished);
    }

    /**
     * Creates an owner with an explicit theme verdict, so a test can pin the gate.
     *
     * @param themeActive the active-theme verdict; not {@code null}
     * @throws NullPointerException if {@code themeActive} is {@code null}
     */
    ThemedHostSurface(final BooleanSupplier themeActive) {
        final BooleanSupplier verdict = Objects.requireNonNull(themeActive, "themeActive");
        this.icons = new ThemedNativeIcons(verdict);
        this.buttons = new ThemedNativeButtons(verdict);
    }

    /**
     * Paints the themed background on one host surface, recolours its plated icons, and records the
     * original appearance.
     *
     * <p>Repeated calls for the same component add owners instead of nesting state. A control that
     * installs and uninstalls repeatedly therefore never loses the appearance it must restore.</p>
     *
     * @param surface the host container that must paint the themed background; not {@code null}
     * @throws NullPointerException if {@code surface} is {@code null}
     */
    public void paint(final JComponent surface) {
        Objects.requireNonNull(surface, "surface");
        own(surface);
        icons.decorate(surface);
        buttons.decorate(surface);
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
        buttons.restore(surface);
        restore(surface, existing);
    }

    /**
     * Paints the themed background on every native container panel below one host root and themes
     * the host buttons of that tree.
     *
     * <p>The ArtMesh tool-details tab is one native tree. Theming only the container that receives
     * a contributed control leaves every sibling panel of the same tab on the native colour. The
     * walk reaches the whole tab content. It skips the components this runtime creates, so a
     * rendered panel keeps its transparent stacks. Repeated calls for the same root add owners
     * instead of nesting state.</p>
     *
     * @param root the native root of the themed host subtree; not {@code null}
     * @throws NullPointerException if {@code root} is {@code null}
     */
    public void paintTree(final JComponent root) {
        Objects.requireNonNull(root, "root");
        final Tree existing = trees.get(root);
        if (existing == null) {
            trees.put(root, new Tree(FIRST_OWNER));
        } else {
            existing.owners++;
        }
        applyTree(root);
    }

    /**
     * Releases one ownership of a themed host subtree and restores it when the last owner leaves.
     *
     * <p>A root this instance does not own is ignored, so a caller may release defensively
     * without tracking whether the host exposed the peer.</p>
     *
     * @param root the host root to release; {@code null} is ignored
     */
    public void releaseTree(final JComponent root) {
        if (root == null) return;
        final Tree existing = trees.get(root);
        if (existing == null) return;
        existing.owners--;
        if (existing.owners > 0) return;
        trees.remove(root);
        icons.restore(root);
        buttons.restore(root);
        for (JComponent surface : List.copyOf(existing.surfaces)) releaseOwnership(surface);
    }

    /**
     * Reapplies the current themed appearance to every owned surface and host tree.
     *
     * <p>Call this after the look and feel changed. A component that was already resolved would
     * otherwise keep the previous theme's colour, button states and glyph tint. A theme that left
     * restores the shipped host appearance.</p>
     */
    public void refresh() {
        icons.refresh();
        buttons.refresh();
        for (JComponent surface : List.copyOf(surfaces.keySet())) {
            apply(surface);
            surface.repaint();
        }
        for (JComponent root : List.copyOf(trees.keySet())) applyTree(root);
    }

    /**
     * Re-walks one host tree, themes every native container panel, and drops the panels that left
     * the tree since the previous pass.
     */
    private void applyTree(final JComponent root) {
        final Tree tree = trees.get(root);
        final Set<JComponent> present = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Component component : NativeComponentTree.flatten(root)) {
            if (!isThemedContainer(component)) continue;
            final JComponent surface = (JComponent) component;
            present.add(surface);
            if (tree.surfaces.add(surface)) {
                own(surface);
            } else {
                apply(surface);
            }
        }
        for (JComponent surface : List.copyOf(tree.surfaces)) {
            if (present.contains(surface)) continue;
            tree.surfaces.remove(surface);
            releaseOwnership(surface);
        }
        icons.decorate(root);
        buttons.decorate(root);
    }

    /** Records one ownership of a surface, capturing its native appearance on the first one. */
    private void own(final JComponent surface) {
        final Surface existing = surfaces.get(surface);
        if (existing == null) {
            surfaces.put(surface, new Surface(surface.getBackground(), surface.isOpaque(), FIRST_OWNER));
        } else {
            existing.owners++;
        }
        apply(surface);
    }

    /** Drops one ownership of a surface without touching the icons and buttons of its own host. */
    private void releaseOwnership(final JComponent surface) {
        final Surface existing = surfaces.get(surface);
        if (existing == null) return;
        existing.owners--;
        if (existing.owners > 0) return;
        surfaces.remove(surface);
        restore(surface, existing);
    }

    private static void restore(final JComponent surface, final Surface existing) {
        surface.setBackground(existing.background);
        surface.setOpaque(existing.opaque);
        surface.repaint();
    }

    private static void apply(final JComponent surface) {
        surface.setOpaque(true);
        surface.setBackground(themedBackground());
    }

    private static boolean isThemedContainer(final Component component) {
        return component instanceof JPanel && !NativeComponentTree.isRuntimeOwned(component);
    }

    static Color themedBackground() {
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

    /** Native container panels owned by one themed host root, plus that root's owner count. */
    private static final class Tree {
        private final Set<JComponent> surfaces = Collections.newSetFromMap(new IdentityHashMap<>());
        private int owners;

        private Tree(final int owners) {
            this.owners = owners;
        }
    }
}
