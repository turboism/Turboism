package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.UiFont;

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
        final ControlAppearanceStyle style
    ) {
        Objects.requireNonNull(style, "style");
        return new NativeControlStyle(
            style.font().map(value -> font(nativeFont, value)).orElse(nativeFont),
            style.foreground().map(value -> new Color(value.argb(), true)).orElse(nativeForeground),
            style.background().map(value -> new Color(value.argb(), true)).orElse(nativeBackground),
            style.background().isPresent() || nativeOpaque
        );
    }


    void restore(final java.awt.Component component) {
        component.setFont(font);
        component.setForeground(foreground);
        component.setBackground(background);
        if (component instanceof javax.swing.JComponent swing) swing.setOpaque(opaque);
        component.repaint();
    }

    private static Font font(final Font nativeFont, final UiFont overlay) {
        final Font base = Objects.requireNonNull(nativeFont, "nativeFont");
        final String family = overlay.family().orElse(base.getFamily());
        final float size = overlay.size().orElse(base.getSize2D());
        final int weight = switch (overlay.weight()) {
            case INHERIT -> base.isBold() ? Font.BOLD : Font.PLAIN;
            case REGULAR -> Font.PLAIN;
            case BOLD -> Font.BOLD;
        };
        final int posture = switch (overlay.posture()) {
            case INHERIT -> base.isItalic() ? Font.ITALIC : Font.PLAIN;
            case NORMAL -> Font.PLAIN;
            case ITALIC -> Font.ITALIC;
        };
        return new Font(family, weight | posture, Math.round(size)).deriveFont(size);
    }
}
