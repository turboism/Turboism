package dev.turboism.pluginmanagement;

import dev.turboism.core.version.PluginVersion;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts versioned changes from the localized README at a catalog release's pinned source. */
final class PluginReleaseNotes {
    private static final Pattern VERSION = Pattern.compile("^###\\s+\\[?v?(\\d+\\.\\d+\\.\\d+)(?:]|\\s|$).*$");

    private PluginReleaseNotes() {}

    static String extract(final String readme, final PluginVersion installed, final PluginVersion target) {
        final StringBuilder result = new StringBuilder();
        boolean include = false;
        boolean fenced = false;
        for (String line : readme.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) fenced = !fenced;
            if (!fenced && line.startsWith("#")) {
                final Matcher version = VERSION.matcher(line);
                if (version.matches()) {
                    final PluginVersion parsed = PluginVersion.parse(version.group(1));
                    include = parsed.compareTo(installed) > 0 && parsed.compareTo(target) <= 0;
                } else if (line.matches("^#{1,3}\\s.*")) {
                    include = false;
                }
            }
            if (include) result.append(line).append('\n');
        }
        return result.toString().strip();
    }
}
