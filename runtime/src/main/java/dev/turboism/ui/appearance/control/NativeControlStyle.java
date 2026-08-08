package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.PaletteEntryState;
import dev.turboism.sdk.ui.appearance.UiColor;

import java.awt.Color;
import java.awt.Font;
import java.util.Objects;

/** Complete native component state after applying one bounded appearance overlay. */
record NativeControlStyle(Font font, Color foreground, Color background, boolean opaque) {

    static NativeControlStyle apply(
        final Font nativeFont,
        final Color nativeForeground,
        final Color nativeBackground,
        final boolean nativeOpaque,
        final PaletteEntryState state
    ) {
        Objects.requireNonNull(state, "state");
        final Font font = font(nativeFont, state);
        return new NativeControlStyle(
            font,
            state.textColor().map(NativeControlStyle::swing).orElse(nativeForeground),
            state.backgroundColor().map(NativeControlStyle::swing).orElse(nativeBackground),
            state.backgroundColor().isPresent() || nativeOpaque
        );
    }

    private static Color swing(final UiColor color) {
        return new Color(color.red(), color.green(), color.blue(), color.alpha());
    }

    void restore(final java.awt.Component component) {
        component.setFont(font);
        component.setForeground(foreground);
        component.setBackground(background);
        if (component instanceof javax.swing.JComponent swing) swing.setOpaque(opaque);
        component.repaint();
    }

    private static Font font(
        final Font nativeFont,
        final PaletteEntryState state
    ) {
        final Font base = Objects.requireNonNull(nativeFont, "nativeFont");
        if (state.fontSize().isEmpty() && state.bold().isEmpty() && state.italic().isEmpty()) return base;

        int style = base.getStyle();
        if (state.bold().isPresent()) {
            style = state.bold().orElseThrow() ? style | Font.BOLD : style & ~Font.BOLD;
        }
        if (state.italic().isPresent()) {
            style = state.italic().orElseThrow() ? style | Font.ITALIC : style & ~Font.ITALIC;
        }
        final float size = state.fontSize().orElse(base.getSize2D());
        return base.deriveFont(style, size);
    }
}
