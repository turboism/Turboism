package dev.turboism.distribution;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ArchivePolicy {
    static final long PACKAGE_MAX = 64L * 1024 * 1024;
    static final long ENTRY_MAX = 32L * 1024 * 1024;
    static final long TOTAL_MAX = 96L * 1024 * 1024;
    static final int ENTRIES_MAX = 256;
    static final double RATIO_MAX = 200.0;

    private ArchivePolicy() {}

    static void validatePackagePath(Path path, java.nio.file.attribute.BasicFileAttributes attributes)
        throws DistributionValidationException {
        if (!attributes.isRegularFile()) {
            throw problem(DistributionErrors.PACKAGE_PATH_INVALID,
                "Package input must be a NOFOLLOW regular file", path.toString());
        }
    }

    static void validateArchive(ZipFile zip) throws DistributionValidationException {
        Set<String> exact = new HashSet<>();
        Set<String> folded = new HashSet<>();
        long total = 0;
        int count = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            safeRelative(name, "ARCHIVE_PATH_UNSAFE", archiveProblemPath(name));
            if (!exact.add(name) || !folded.add(name.toLowerCase(Locale.ROOT))) {
                throw problem("ARCHIVE_PATH_COLLISION", "Duplicate or path-identity-colliding archive path", name);
            }
            if (++count > ENTRIES_MAX) throw problem("ARCHIVE_ENTRY_LIMIT", "Too many archive entries", name);
            long size = entry.getSize();
            if (size > ENTRY_MAX) throw problem("ARCHIVE_ENTRY_TOO_LARGE", "Archive entry exceeds limit", name);
            if (size >= 0 && (total += size) > TOTAL_MAX) {
                throw problem("ARCHIVE_TOTAL_TOO_LARGE", "Archive expanded total exceeds limit", name);
            }
            long compressed = entry.getCompressedSize();
            if (size > 0 && compressed == 0 || compressed > 0 && (double) size / compressed > RATIO_MAX) {
                throw problem("ARCHIVE_COMPRESSION_RATIO", "Archive compression ratio exceeds limit", name);
            }
        }
    }

    private static String archiveProblemPath(String name) {
        if (ManifestReader.NAME.equals(name)) return ManifestReader.NAME;
        return name;
    }

    static void safeRelative(String value, String code, String path) throws DistributionValidationException {
        boolean valid = value != null && !value.isBlank() && printableAscii(value)
            && value.indexOf('\\') < 0 && value.indexOf(':') < 0
            && !value.startsWith("/") && !value.endsWith("/") && !value.contains("//")
            && !value.equals(".") && !value.equals("..") && !value.startsWith("../")
            && !value.contains("/../") && !value.contains("/./") && !value.endsWith("/..")
            && !value.endsWith("/.");
        valid &= validSegments(value);
        if (!valid) throw problem(code, "Path must be printable ASCII canonical POSIX relative path", path);
    }

    private static boolean validSegments(String value) {
        if (value == null) return false;
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.endsWith(".") || segment.endsWith(" ")) return false;
            String basename = segment.substring(0, segment.indexOf('.') < 0 ? segment.length() : segment.indexOf('.'));
            if (windowsDevice(basename)) return false;
        }
        return true;
    }

    private static boolean windowsDevice(String basename) {
        String name = basename.toUpperCase(Locale.ROOT);
        if (name.equals("CON") || name.equals("PRN") || name.equals("AUX") || name.equals("NUL")) return true;
        return name.matches("(?:COM|LPT)[1-9]");
    }

    private static boolean printableAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x21 || character > 0x7e) return false;
        }
        return true;
    }

    static DistributionValidationException problem(String code, String message, String path) {
        return new DistributionValidationException(code, message, path);
    }
}
