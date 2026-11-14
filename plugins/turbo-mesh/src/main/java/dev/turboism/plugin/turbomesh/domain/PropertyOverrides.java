package dev.turboism.plugin.turbomesh.domain;

/**
 * Reads numeric bring-up overrides from {@link System#getProperty}.
 *
 * <p>An absent, unparsable, or out-of-range override never fails a placement run: the reader
 * returns the caller's fallback, or the parsed value clamped into the caller's range. Overrides are
 * a bring-up aid, so a bad value must not abort a run that would otherwise succeed.</p>
 *
 * <p>Public because {@code domain.features} reads the same overrides; the plugin boundary keeps the
 * type inside this plugin.</p>
 */
public final class PropertyOverrides {

    private PropertyOverrides() {}

    /**
     * Returns the {@code int} override named {@code name}, clamped to {@code [min, max]}.
     *
     * @param name system-property name
     * @param fallback value returned when the property is absent or unparsable
     * @param min inclusive lower clamp bound
     * @param max inclusive upper clamp bound
     * @return the clamped override, or {@code fallback}
     */
    public static int intProperty(final String name, final int fallback, final int min, final int max) {
        try {
            final int value = Integer.parseInt(System.getProperty(name, "").trim());
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /**
     * Returns the {@code double} override named {@code name}, clamped to {@code [min, max]}.
     *
     * @param name system-property name
     * @param fallback value returned when the property is absent or unparsable
     * @param min inclusive lower clamp bound
     * @param max inclusive upper clamp bound
     * @return the clamped override, or {@code fallback}
     */
    public static double doubleProperty(final String name, final double fallback, final double min, final double max) {
        try {
            final double value = Double.parseDouble(System.getProperty(name, "").trim());
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
