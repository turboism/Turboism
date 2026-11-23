package dev.turboism.plugin.acp;

import java.awt.Color;
import javax.swing.UIManager;

/** Reads the same semantic defaults published by the host's appearance/theme service. */
final class AcpChatTheme {
    private static final int CODE_BACKGROUND_TINT_PERCENT = 7;
    private static final int FULL_PERCENT = 100;

    private AcpChatTheme() {}

    static Color background() {
        return color("CubismCommon.background", "Panel.background", Color.WHITE);
    }

    static Color surface() {
        return color("CubismCommon.surface", "Table.background", background());
    }

    /** Derives a subtle code fill from background toward foreground so light themes avoid stark white boxes. */
    static Color codeBackground() {
        return blend(background(), foreground(), CODE_BACKGROUND_TINT_PERCENT, FULL_PERCENT);
    }

    static Color inputBackground() {
        return color("CubismCommon.inputBackground", "TextArea.background", surface());
    }

    static Color foreground() {
        return color("CubismCommon.foreground", "Label.foreground", Color.BLACK);
    }

    static Color muted() {
        return color("CubismCommon.mutedForeground", "Label.disabledForeground", foreground());
    }

    static Color accent() {
        return color("CubismCommon.blue", "Component.accentColor", foreground());
    }

    static Color border() {
        return color(
                "CubismCommon.border",
                "Component.borderColor",
                color("Separator.foreground", "Panel.foreground", muted()));
    }

    static Color selectionBackground() {
        return color("CubismCommon.selectionBackground", "List.selectionBackground", surface());
    }

    static Color selectionForeground() {
        return color("CubismCommon.selectionForeground", "List.selectionForeground", foreground());
    }

    static Color success() {
        return color("CubismCommon.green", "Actions.Green", new Color(0x59A869));
    }

    static Color error() {
        return color(
                "CubismCommon.red",
                "Component.error.focusedBorderColor",
                color("Actions.Red", "nb.errorForeground", new Color(0xDB5860)));
    }

    static Color warning() {
        return color("Component.warning.focusedBorderColor", "Actions.Yellow", accent());
    }

    private static Color color(final String semantic, final String fallback, final Color defaultValue) {
        final Color value = UIManager.getColor(semantic);
        if (value != null) return value;
        final Color nativeValue = UIManager.getColor(fallback);
        return nativeValue == null ? defaultValue : nativeValue;
    }

    private static Color blend(final Color base, final Color target, final int weight, final int scale) {
        final int keep = scale - weight;
        return new Color(
                (base.getRed() * keep + target.getRed() * weight) / scale,
                (base.getGreen() * keep + target.getGreen() * weight) / scale,
                (base.getBlue() * keep + target.getBlue() * weight) / scale);
    }
}
