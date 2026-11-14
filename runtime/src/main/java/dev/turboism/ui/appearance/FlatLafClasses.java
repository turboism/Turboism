package dev.turboism.ui.appearance;

/** Recognizes the FlatLaf-derived look-and-feel classes the appearance layer manages. */
final class FlatLafClasses {

    private static final String FLAT_LAF_PACKAGE_PREFIX = "com.formdev.flatlaf.";
    private static final String CUBISM_LIGHT_THEME_MARKER = "CubismLightTheme";
    private static final String CUBISM_DARK_THEME_MARKER = "CubismDarkTheme";

    /**
     * Reports whether a look-and-feel class name belongs to FlatLaf or a Turboism Cubism theme.
     *
     * @param className the fully qualified class name, nullable
     * @return {@code true} when the name is a managed FlatLaf-derived type
     */
    static boolean isFlatLaf(final String className) {
        return className != null
                && (className.startsWith(FLAT_LAF_PACKAGE_PREFIX)
                        || className.contains(CUBISM_LIGHT_THEME_MARKER)
                        || className.contains(CUBISM_DARK_THEME_MARKER));
    }

    private FlatLafClasses() {}
}
