package dev.turboism.gradle.internal.boundary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The compiled half of the plugin boundary: which {@code dev.turboism} and {@code com.live2d}
 * references a shipped class file carries.
 *
 * <p>Pure: bytes in, messages out. The caller owns the walk, the per-plugin allowlist extras and
 * the allowed/forbidden root sets, so the rule stays one predicate over bytes.
 *
 * <p>Two reference shapes are distinct. A <em>binary</em> {@code dev/turboism/<segment>} class
 * reference must stay inside the allowed roots (the SDK, the plugin's own classes, plus a documented
 * per-plugin extra), because it is a type the plugin actually links against. A <em>dotted</em>
 * {@code dev.turboism.<segment>} occurrence is a string payload - a {@code Class.forName} target, a
 * config-key namespace like {@code dev.turboism.texture-atlas.*}, an event id - so it may be
 * kebab-case and the scan denies the known runtime/internal roots instead of allowlisting. A bare
 * {@code dev/turboism/<ClassName>} root-package reference fails the binary scan because it matches
 * no allowed root.
 *
 * <p>Message text names the caller-supplied relative path; violations come back in the order the
 * needles appear in the file, with the Cubism check last, so the gate's output is stable.
 */
public final class PluginClassReferences {

    private static final byte[] BINARY_NEEDLE = "dev/turboism/".getBytes(StandardCharsets.UTF_8);

    private static final byte[] DOTTED_NEEDLE = "dev.turboism.".getBytes(StandardCharsets.UTF_8);

    private static final byte[] CUBISM_BINARY_NEEDLE = "com/live2d/".getBytes(StandardCharsets.UTF_8);

    private static final byte[] CUBISM_DOTTED_NEEDLE = "com.live2d.".getBytes(StandardCharsets.UTF_8);

    private PluginClassReferences() {
    }

    /**
     * Every forbidden reference in one compiled class file.
     *
     * @param relativePath the path messages name, relative to the project directory
     * @param bytes the class file bytes, which are not parsed here
     * @param forbiddenDottedRoots {@code dev.turboism} segments a dotted payload may not name
     * @param allowedRoots {@code dev/turboism} segments a binary reference may name
     */
    public static List<String> scan(
            String relativePath, byte[] bytes, Set<String> forbiddenDottedRoots, Set<String> allowedRoots) {
        List<String> violations = new ArrayList<>();
        int offset = indexOfNeedle(bytes, BINARY_NEEDLE, 0);
        while (offset >= 0) {
            String segment = segmentAfter(bytes, BINARY_NEEDLE, offset);
            if (!segment.isEmpty() && !allowedRoots.contains(segment)) {
                violations.add(
                        "Plugin class " + relativePath + " references forbidden package dev/turboism/" + segment);
            }
            offset = indexOfNeedle(bytes, BINARY_NEEDLE, offset + BINARY_NEEDLE.length);
        }
        offset = indexOfNeedle(bytes, DOTTED_NEEDLE, 0);
        while (offset >= 0) {
            String segment = segmentAfter(bytes, DOTTED_NEEDLE, offset);
            if (forbiddenDottedRoots.contains(segment)) {
                violations.add(
                        "Plugin class " + relativePath + " references forbidden package dev.turboism." + segment);
            }
            offset = indexOfNeedle(bytes, DOTTED_NEEDLE, offset + DOTTED_NEEDLE.length);
        }
        if (indexOfNeedle(bytes, CUBISM_BINARY_NEEDLE, 0) >= 0
                || indexOfNeedle(bytes, CUBISM_DOTTED_NEEDLE, 0) >= 0) {
            violations.add("Plugin class " + relativePath + " references forbidden Cubism internals (com.live2d)");
        }
        return violations;
    }

    /**
     * True for the bytes a binary class name may contain beyond a package separator.
     *
     * <p>Shared with the persistence-kernel and admission-policy bytecode scans, which read a
     * {@code dev/turboism/<segment>} reference the same way.
     */
    public static boolean isBinaryNameByte(byte value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '_'
                || value == '$';
    }

    /**
     * The first index at or after {@code from} where {@code needle} occurs, or {@code -1}.
     *
     * <p>Shared with the persistence-kernel and admission-policy bytecode scans: their needles are
     * ASCII prefixes, and a class file is not a text format, so this stays a raw byte search.
     */
    public static int indexOfNeedle(byte[] haystack, byte[] needle, int from) {
        if (haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int index = from; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static String segmentAfter(byte[] bytes, byte[] needle, int offset) {
        int start = offset + needle.length;
        int end = start;
        while (end < bytes.length && isBinaryNameByte(bytes[end])) {
            end++;
        }
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }
}
