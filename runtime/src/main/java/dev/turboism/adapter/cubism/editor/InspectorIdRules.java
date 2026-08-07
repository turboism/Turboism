package dev.turboism.adapter.cubism.editor;

import java.util.regex.Pattern;

/**
 * Cubism object-ID rules mirrored from the Inspector's
 * {@code checkIdStringAndAlert} path (evidence: 5302-src
 * {@code com/live2d/core/id/c.java} + {@code com/live2d/core/id/b.java},
 * identical in 5.2). Rules: length &lt; 64, all characters in
 * {@code [0-9a-zA-Z_@]}, no deprecated characters ({@code [ -/:-?\[-^`{-~]}),
 * and no leading digit.
 */
final class InspectorIdRules {

    static final int CUBISM_42_TARGET_VERSION = 4_020_000;

    private static final Pattern AVAILABLE_CHARS = Pattern.compile("^[0-9a-zA-Z_@]+$");
    private static final Pattern DEPRECATED_CHARS = Pattern.compile("[ -/:-?\\[-^`{-~]");

    static boolean isValidCubismId(final String id) {
        return id.length() < 64
            && AVAILABLE_CHARS.matcher(id).matches()
            && !DEPRECATED_CHARS.matcher(id).find()
            && !(id.charAt(0) >= '0' && id.charAt(0) <= '9');
    }

    private InspectorIdRules() {
    }
}
