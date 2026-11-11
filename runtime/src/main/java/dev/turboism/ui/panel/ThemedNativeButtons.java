package dev.turboism.ui.panel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JToolBar;
import javax.swing.UIManager;

/**
 * Paints the theme colours behind the buttons of a native host surface.
 *
 * <p>FlatLaf resolves a button background when the look and feel installs. An existing button
 * therefore keeps the native tile after a runtime theme change. A plain button keeps
 * {@code Button.background}. A plain toggle keeps {@code ToggleButton.background}. A selected
 * toolbar button keeps the derived {@code toolbar.selectedBackground}. This owner replaces that
 * tile through a per-button custom background. It also gives the hover, pressed, selected and
 * disabled states the theme colours through the FlatLaf style client property. The host toolbar
 * styling and the host glyphs stay in place.</p>
 *
 * <p>A toolbar button keeps the FlatLaf toolbar styling. Its resting state paints the surface
 * colour instead of a light tile. Its selected state paints the theme selection colour. The same
 * ownership records and restores every touched property, so a theme removal restores the native
 * appearance exactly. Call the mutators on the Swing event dispatch thread. Call
 * {@link #refresh()} when the look and feel changes, so a live theme switch repaints the same
 * buttons.</p>
 */
final class ThemedNativeButtons {

    /** FlatLaf reads this client property as the per-button style override. */
    private static final String STYLE_KEY = "FlatLaf.style";

    /** FlatLaf reads this client property as the button style kind. */
    private static final String BUTTON_TYPE_KEY = "JButton.buttonType";

    private static final String TOOLBAR_BUTTON_TYPE = "toolBarButton";
    private static final String PLAIN_HOVER_STYLE = "hoverBackground";
    private static final String PLAIN_PRESSED_STYLE = "pressedBackground";
    private static final String PLAIN_SELECTED_STYLE = "selectedBackground";
    private static final String PLAIN_DISABLED_STYLE = "disabledBackground";
    private static final String PLAIN_DISABLED_SELECTED_STYLE = "disabledSelectedBackground";
    private static final String TOOLBAR_HOVER_STYLE = "toolbar.hoverBackground";
    private static final String TOOLBAR_PRESSED_STYLE = "toolbar.pressedBackground";
    private static final String TOOLBAR_SELECTED_STYLE = "toolbar.selectedBackground";
    private static final String TOOLBAR_DISABLED_SELECTED_STYLE = "toolbar.disabledSelectedBackground";
    private static final String SELECTION_KEY = "CubismCommon.selectionBackground";
    private static final String SELECTION_FALLBACK_KEY = "ToggleButton.selectedBackground";
    private static final String HOVER_KEY = "Button.hoverBackground";
    private static final String PRESSED_KEY = "CubismCommon.activeColor";
    private static final String STYLE_SEPARATOR = "; ";
    private static final String STYLE_ASSIGNMENT = ": ";
    private static final String STYLE_TERMINATOR = ";";
    private static final String COLOR_FORMAT = "#%02X%02X%02X";
    private static final int FIRST_OWNER = 1;

    private final BooleanSupplier themeActive;
    private final Map<JComponent, List<AbstractButton>> hosts = new IdentityHashMap<>();
    private final Map<AbstractButton, Integer> owners = new IdentityHashMap<>();
    private final Map<AbstractButton, State> originals = new IdentityHashMap<>();

    /**
     * Creates an owner with an explicit theme verdict, so a test can pin the gate.
     *
     * @param themeActive the active-theme verdict; not {@code null}
     * @throws NullPointerException if {@code themeActive} is {@code null}
     */
    ThemedNativeButtons(final BooleanSupplier themeActive) {
        this.themeActive = Objects.requireNonNull(themeActive, "themeActive");
    }

    /**
     * Records the host buttons inside one subtree and themes them while a theme is active.
     *
     * <p>Repeated calls for the same subtree keep one record per button, so a subtree that several
     * controls share is themed once per button identity.</p>
     *
     * @param host the host subtree to walk; not {@code null}
     * @throws NullPointerException if {@code host} is {@code null}
     */
    void decorate(final JComponent host) {
        Objects.requireNonNull(host, "host");
        final List<AbstractButton> owned = hosts.computeIfAbsent(host, key -> new ArrayList<>());
        track(host, owned);
        if (!themeActive.getAsBoolean()) return;
        apply(owned);
    }

    /**
     * Reapplies the current theme colours to every recorded host button.
     *
     * <p>Call this after the look and feel changed. An active theme repaints the buttons with the new
     * colours. A removed theme restores the native appearance.</p>
     */
    void refresh() {
        if (!themeActive.getAsBoolean()) {
            for (List<AbstractButton> owned : hosts.values()) {
                for (AbstractButton button : owned) revert(button);
            }
            return;
        }
        for (List<AbstractButton> owned : hosts.values()) apply(owned);
    }

    /**
     * Drops one ownership of a subtree and restores the native buttons of the last owner.
     *
     * <p>A subtree this owner does not record is ignored, and a button that another subtree still
     * covers keeps the themed appearance.</p>
     *
     * @param host the subtree to release; {@code null} is ignored
     */
    void restore(final JComponent host) {
        if (host == null) return;
        final List<AbstractButton> owned = hosts.remove(host);
        if (owned == null) return;
        for (AbstractButton button : owned) {
            final Integer remaining = owners.get(button);
            if (remaining == null) continue;
            final int left = remaining - FIRST_OWNER;
            if (left > 0) {
                owners.put(button, left);
                continue;
            }
            owners.remove(button);
            revert(button);
            originals.remove(button);
        }
    }

    private void track(final Component container, final List<AbstractButton> owned) {
        if (!(container instanceof Container nested)) return;
        for (Component child : nested.getComponents()) {
            if (child instanceof AbstractButton button
                    && !NativeComponentTree.isRuntimeOwned(button)
                    && !owned.contains(button)) {
                owned.add(button);
                owners.merge(button, FIRST_OWNER, Integer::sum);
                originals.putIfAbsent(button, State.of(button));
            }
            track(child, owned);
        }
    }

    private void apply(final List<AbstractButton> owned) {
        for (AbstractButton button : owned) {
            final State state = originals.get(button);
            if (state == null) continue;
            button.setOpaque(false);
            button.setBackground(surface());
            applyStyle(button, state);
            button.repaint();
        }
    }

    /** Keeps a host style intact and appends the theme pairs, because later pairs win. */
    private static void applyStyle(final AbstractButton button, final State state) {
        final String themed = style(button);
        if (themed == null) {
            button.putClientProperty(STYLE_KEY, state.style());
            return;
        }
        button.putClientProperty(STYLE_KEY, merged(state.style(), themed));
    }

    private static String merged(final Object hostStyle, final String themed) {
        if (!(hostStyle instanceof String existing) || existing.isBlank()) return themed;
        String base = existing.strip();
        while (base.endsWith(STYLE_TERMINATOR)) {
            base = base.substring(0, base.length() - STYLE_TERMINATOR.length()).strip();
        }
        return base.isEmpty() ? themed : base + STYLE_SEPARATOR + themed;
    }

    private void revert(final AbstractButton button) {
        final State state = originals.get(button);
        if (state == null) return;
        button.putClientProperty(STYLE_KEY, state.style());
        button.setOpaque(state.opaque());
        button.setBackground(state.background());
        button.repaint();
    }

    /**
     * Builds the state style of one button.
     *
     * <p>A toolbar button reads the toolbar colour family. A plain button reads the plain family.
     * FlatLaf chooses the family from the button kind in the same way. A theme that publishes no
     * selection colour keeps the native selected tile. An invisible selection would be worse than
     * a native one.</p>
     *
     * @param button the host button to style
     * @return the style pairs, or {@code null} when the theme publishes no colour for this kind
     */
    private static String style(final AbstractButton button) {
        final StringBuilder style = new StringBuilder();
        final Color selection = selection();
        final Color pressed = pressed();
        if (isToolbarButton(button)) {
            appendState(style, TOOLBAR_HOVER_STYLE, hover());
            appendState(style, TOOLBAR_PRESSED_STYLE, pressed);
            appendState(style, TOOLBAR_SELECTED_STYLE, selection);
            appendState(style, TOOLBAR_DISABLED_SELECTED_STYLE, selection);
        } else {
            appendState(style, PLAIN_HOVER_STYLE, hover());
            appendState(style, PLAIN_PRESSED_STYLE, pressed);
            appendState(style, PLAIN_SELECTED_STYLE, selection);
            appendState(style, PLAIN_DISABLED_STYLE, surface());
            appendState(style, PLAIN_DISABLED_SELECTED_STYLE, selection);
        }
        return style.isEmpty() ? null : style.toString();
    }

    private static void appendState(final StringBuilder style, final String key, final Color color) {
        if (color == null) return;
        if (!style.isEmpty()) style.append(STYLE_SEPARATOR);
        style.append(key).append(STYLE_ASSIGNMENT).append(hex(color));
    }

    private static boolean isToolbarButton(final AbstractButton button) {
        if (TOOLBAR_BUTTON_TYPE.equals(button.getClientProperty(BUTTON_TYPE_KEY))) return true;
        return button.getParent() instanceof JToolBar;
    }

    /** Copies the themed surface as a custom colour, so FlatLaf paints it over its own tile. */
    private static Color surface() {
        return new Color(ThemedHostSurface.themedBackground().getRGB());
    }

    private static Color selection() {
        return firstColor(SELECTION_KEY, SELECTION_FALLBACK_KEY);
    }

    private static Color hover() {
        return firstColor(HOVER_KEY, SELECTION_KEY, SELECTION_FALLBACK_KEY);
    }

    private static Color pressed() {
        return firstColor(PRESSED_KEY, SELECTION_KEY, SELECTION_FALLBACK_KEY);
    }

    private static Color firstColor(final String... keys) {
        for (String key : keys) {
            final Color value = UIManager.getColor(key);
            if (value != null) return new Color(value.getRGB());
        }
        return null;
    }

    private static String hex(final Color color) {
        return String.format(Locale.ROOT, COLOR_FORMAT, color.getRed(), color.getGreen(), color.getBlue());
    }

    /** Original appearance of one host button. */
    private record State(Color background, boolean opaque, Object style) {
        private static State of(final AbstractButton button) {
            return new State(button.getBackground(), button.isOpaque(), button.getClientProperty(STYLE_KEY));
        }
    }
}
