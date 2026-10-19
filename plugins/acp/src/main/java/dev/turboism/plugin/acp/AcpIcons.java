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

    private static final Map<Kind, Icon> ICONS = loadIcons();

    private AcpIcons() {}

    static Icon icon(final Kind kind) {
        return ICONS.get(kind);
    }

    private static Map<Kind, Icon> loadIcons() {
        final Map<Kind, Icon> result = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            final int size = kind == Kind.FAST || kind == Kind.PLAN ? 16 : 20;
            result.put(kind, AcpThemedIcon.load("/icons/lucide/" + kind.asset + ".png", size));
        }
        return Map.copyOf(result);
    }
}
