package dev.turboism.adapter.cubism.optimization.export;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Provides O(1) per-writer path deduplication for {@code com.live2d.serialize.archiver.ArchiveWriter}.
 *
 * <p>The host's stock {@code getFreeFilePath} resets its collision probe counter to zero on every
 * invocation, creating quadratic probing over thousands of identically named image entries during
 * model export cloning.</p>
 */
public final class ArchiveWriterIndexCache {

    private static final int MAX_INDEX_LIMIT = 100_000_000;
    private static final char WINDOWS_SEPARATOR = '\\';
    private static final char ARCHIVE_SEPARATOR = '/';
    private static final char EXTENSION_DOT = '.';
    private static final int INITIAL_STRING_PADDING = 12;
    private static final String TOO_MANY_FILES_ERROR = "too much file";
    private static final Map<Object, Map<String, Integer>> WRITER_NEXT_INDEX = new WeakHashMap<>();

    private ArchiveWriterIndexCache() {}

    /**
     * Resolves the next unused archive entry path for {@code rawPath} preserving stock naming.
     *
     * @param writer the owning {@code ArchiveWriter} instance
     * @param fileNameSet the writer's backing {@code fileNameSet}
     * @param rawPath candidate path requested by serialization
     * @return first free path equal to or suffixed from {@code rawPath}
     */
    public static String getFreeFilePath(final Object writer, final HashSet<String> fileNameSet, final String rawPath) {
        final String candidate = normalizePath(rawPath);
        if (!fileNameSet.contains(candidate)) {
            fileNameSet.add(candidate);
            return candidate;
        }
        final int lastSlash = candidate.lastIndexOf(ARCHIVE_SEPARATOR);
        final int rawDot = candidate.lastIndexOf(EXTENSION_DOT);
        final int dot = rawDot < lastSlash ? -1 : rawDot;
        final String stem = dot < 0 ? candidate : candidate.substring(0, dot);
        final String ext = dot < 0 ? "" : candidate.substring(dot);

        final Map<String, Integer> perWriterMap = getPerWriterMap(writer);
        int startIndex = 0;
        synchronized (perWriterMap) {
            final Integer cached = perWriterMap.get(candidate);
            if (cached != null) {
                startIndex = cached;
            }
            final StringBuilder builder = new StringBuilder(stem.length() + ext.length() + INITIAL_STRING_PADDING);
            for (int index = startIndex; index < MAX_INDEX_LIMIT; index++) {
                builder.setLength(0);
                builder.append(stem).append(index).append(ext);
                final String probe = builder.toString();
                if (!fileNameSet.contains(probe)) {
                    fileNameSet.add(probe);
                    perWriterMap.put(candidate, index + 1);
                    return probe;
                }
            }
        }
        throw new IllegalStateException(TOO_MANY_FILES_ERROR);
    }

    private static String normalizePath(final String rawPath) {
        return rawPath.indexOf(WINDOWS_SEPARATOR) >= 0
                ? rawPath.replace(WINDOWS_SEPARATOR, ARCHIVE_SEPARATOR)
                : rawPath;
    }

    private static Map<String, Integer> getPerWriterMap(final Object writer) {
        synchronized (WRITER_NEXT_INDEX) {
            return WRITER_NEXT_INDEX.computeIfAbsent(writer, ignored -> new HashMap<>());
        }
    }
}
