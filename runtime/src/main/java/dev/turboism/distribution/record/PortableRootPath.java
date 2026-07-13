package dev.turboism.distribution.record;

import java.util.Locale;
import java.util.Set;

final class PortableRootPath {
    private static final Set<String> RESERVED = Set.of(
        "CON", "PRN", "AUX", "NUL", "CLOCK$",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private PortableRootPath() {}

    static boolean isValid(String value) {
        if (value.equals("/")) return true;
        int start = prefixLength(value);
        if (start < 0) return false;
        if (start == value.length()) return start == 3;
        if (value.indexOf('\\') >= 0 || value.indexOf("//", start) >= 0) return false;
        return validSegments(value.substring(start));
    }

    private static int prefixLength(String value) {
        if (value.startsWith("/")) return value.startsWith("//") ? -1 : 1;
        if (value.length() >= 3 && value.charAt(0) >= 'A' && value.charAt(0) <= 'Z'
            && value.charAt(1) == ':' && value.charAt(2) == '/') return 3;
        return -1;
    }

    private static boolean validSegments(String value) {
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (!validSegment(segment)) return false;
        }
        return true;
    }

    private static boolean validSegment(String value) {
        if (value.isEmpty() || value.equals(".") || value.equals("..")) return false;
        if (value.endsWith(".") || value.endsWith(" ") || reserved(value)) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ':' || Character.isISOControl(character)) return false;
        }
        return true;
    }

    private static boolean reserved(String value) {
        String base = value.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return RESERVED.contains(base);
    }
}
