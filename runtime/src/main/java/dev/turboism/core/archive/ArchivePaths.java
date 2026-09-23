package dev.turboism.core.archive;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Archive-neutral path primitives shared by every strict-archive caller. */
public final class ArchivePaths {
    private ArchivePaths() {}

    /**
     * Returns the fixed Turboism v1 path identity key. It normalizes to NFC, then maps
     * each code point with {@code Character.toLowerCase(Character.toUpperCase(codePoint))}
     * using Java 17 {@link Character} tables. It is deliberately not Unicode Default Case
     * Folding and never performs multi-code-point expansions such as {@code ß -> ss}.
     */
    public static String pathIdentityKey(final String value) {
        final String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        final StringBuilder key = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length();) {
            final int point = normalized.codePointAt(index);
            key.appendCodePoint(Character.toLowerCase(Character.toUpperCase(point)));
            index += Character.charCount(point);
        }
        return key.toString();
    }

    /**
     * Reports whether {@code value} is a safe relative archive entry path: non-empty,
     * NFC-normalized, at most 1024 UTF-8 bytes, without a leading or trailing slash,
     * backslash, colon, drive prefix, NUL or ISO control characters, and with every
     * {@code /}-separated segment passing {@link #safeSegment}. Directory entry names
     * are checked by callers after stripping their trailing slash.
     */
    public static boolean relativePath(final String value) {
        if (value == null || value.isEmpty()
            || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            return false;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > 1024
            || value.startsWith("/") || value.endsWith("/")) {
            return false;
        }
        if (value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || drivePrefix(value)) {
            return false;
        }
        for (int index = 0; index < value.length();) {
            final int point = value.codePointAt(index);
            if (point == 0 || Character.isISOControl(point)) {
                return false;
            }
            index += Character.charCount(point);
        }
        for (final String segment : value.split("/", -1)) {
            if (!safeSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Detects path-identity collisions across an archive's entry names: the same identity
     * key claimed by two files, two directories, or a file and a directory (including a
     * file occupying a directory's prefix position). {@code null} means no collision;
     * otherwise the first offending path is returned for attribution.
     */
    public static String pathCollision(final List<String> paths) {
        final Set<String> files = new HashSet<>();
        final Set<String> directories = new HashSet<>();
        for (final String path : paths) {
            final boolean directory = path.endsWith("/");
            final String value = directory ? path.substring(0, path.length() - 1) : path;
            final String key = pathIdentityKey(value);
            if (!(directory ? directories : files).add(key)) {
                return path;
            }
            if (directory ? files.contains(key) : directories.contains(key)) {
                return path;
            }
            final String[] segments = value.split("/");
            String prefix = "";
            for (int index = 0; index < segments.length - 1; index++) {
                prefix = prefix.isEmpty() ? segments[index] : prefix + "/" + segments[index];
                if (files.contains(pathIdentityKey(prefix))) {
                    return path;
                }
            }
        }
        return null;
    }

    /**
     * Reports whether a single path segment is safe: non-empty, neither {@code .} nor
     * {@code ..}, not ending in a dot or space, and not a DOS reserved device base
     * name ({@code CON}, {@code PRN}, {@code AUX}, {@code NUL}, {@code COM1}–{@code COM9},
     * {@code LPT1}–{@code LPT9}) before its first dot.
     */
    public static boolean safeSegment(final String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            return false;
        }
        if (segment.endsWith(".") || segment.endsWith(" ")) {
            return false;
        }
        final int dot = segment.indexOf('.');
        final String base = segment
            .substring(0, dot < 0 ? segment.length() : dot)
            .toUpperCase(Locale.ROOT);
        return !base.matches("CON|PRN|AUX|NUL|(?:COM|LPT)[1-9]");
    }

    private static boolean drivePrefix(final String value) {
        return value.length() >= 2
            && Character.isLetter(value.charAt(0))
            && value.charAt(1) == ':';
    }
}
