package dev.turboism.plugin.acp;

import java.util.EnumMap;
import java.util.Map;
import javax.swing.Icon;

/** Functional UI glyphs share Lucide v0.468.0 geometry and theme tinting. */
final class AcpIcons {
    enum Kind {
        AGENT("square-terminal"),
        FAST("zap"),
        PLAN("list-checks"),
        SETTINGS("settings"),
        ATTACH("plus"),
        SEND("arrow-up"),
        STOP("square"),
        FILE("file-text"),
        REMOVE("x");

        private final String asset;

        Kind(final String asset) {
            this.asset = asset;
        }
    }

    private static final int COMPACT_ICON_SIZE = 14;
    private static final int MODE_ICON_SIZE = 16;
    private static final int DEFAULT_ICON_SIZE = 20;

    private static final Map<Kind, Icon> ICONS = loadIcons(DEFAULT_ICON_SIZE);
    private static final Map<Kind, Icon> COMPACT_ICONS = loadIcons(COMPACT_ICON_SIZE);

    private AcpIcons() {}

    static Icon icon(final Kind kind) {
        return ICONS.get(kind);
    }

    /** Returns a compact 14px themed glyph for composer pills and inline selectors. */
    static Icon compactIcon(final Kind kind) {
        return COMPACT_ICONS.get(kind);
    }

    private static Map<Kind, Icon> loadIcons(final int defaultSize) {
        final Map<Kind, Icon> result = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            final int size = defaultSize == COMPACT_ICON_SIZE
                    ? COMPACT_ICON_SIZE
                    : (kind == Kind.FAST || kind == Kind.PLAN ? MODE_ICON_SIZE : defaultSize);
            result.put(kind, AcpThemedIcon.load("/icons/lucide/" + kind.asset + ".png", size));
        }
        return Map.copyOf(result);
    }
}
