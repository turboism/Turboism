package dev.turboism.gradle.internal.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The byte-level plugin boundary scan.
 *
 * <p>The scan reads raw class-file bytes rather than a parsed constant pool, so the fixtures are
 * plain byte arrays holding the exact ASCII references a real class file would store. The two
 * reference shapes are covered apart: a binary {@code dev/turboism/<segment>} reference is a type
 * the plugin links, a dotted {@code dev.turboism.<segment>} occurrence is a string payload.
 */
class PluginClassReferencesTest {

    private static final String CLASS = "plugins/fixture/build/classes/java/main/Fixture.class";

    private static final Set<String> ALLOWED = Set.of("sdk", "plugin");

    private static final Set<String> FORBIDDEN_DOTTED = Set.of("internal", "persistence", "bootstrap");

    @Test
    void acceptsAllowedBinaryRoots() {
        assertEquals(
                List.of(),
                PluginClassReferences.scan(
                        CLASS, bytes("dev/turboism/sdk/Plugin dev/turboism/plugin/Fixture"), FORBIDDEN_DOTTED, ALLOWED));
    }

    @Test
    void acceptsAPerPluginExtraRoot() {
        assertEquals(
                List.of(),
                PluginClassReferences.scan(
                        CLASS,
                        bytes("dev/turboism/validation/modelimport3d/Probe"),
                        FORBIDDEN_DOTTED,
                        Set.of("sdk", "plugin", "validation")));
    }

    @Test
    void reportsAForbiddenBinaryRoot() {
        assertEquals(
                List.of("Plugin class " + CLASS + " references forbidden package dev/turboism/internal"),
                PluginClassReferences.scan(
                        CLASS, bytes("dev/turboism/internal/core/ShellServices"), FORBIDDEN_DOTTED, ALLOWED));
    }

    @Test
    void reportsABareRootPackageClass() {
        assertEquals(
                List.of("Plugin class " + CLASS + " references forbidden package dev/turboism/Fixture"),
                PluginClassReferences.scan(CLASS, bytes("dev/turboism/Fixture"), FORBIDDEN_DOTTED, ALLOWED));
    }

    @Test
    void stopsTheSegmentAtTheFirstNonNameByte() {
        // `dev/turboism/sdk.Config` is the root `sdk` followed by a non-name byte, so the binary
        // scan accepts it; the dotted payload rules never see a `dev.turboism.` occurrence.
        assertEquals(
                List.of(),
                PluginClassReferences.scan(CLASS, bytes("dev/turboism/sdk.Config"), FORBIDDEN_DOTTED, ALLOWED));
    }

    @Test
    void keepsDollarAndDigitsInTheSegment() {
        assertEquals(
                List.of("Plugin class " + CLASS + " references forbidden package dev/turboism/Fixture$1"),
                PluginClassReferences.scan(CLASS, bytes("dev/turboism/Fixture$1"), FORBIDDEN_DOTTED, ALLOWED));
    }

    @Test
    void reportsAForbiddenDottedRoot() {
        assertEquals(
                List.of("Plugin class " + CLASS + " references forbidden package dev.turboism.internal"),
                PluginClassReferences.scan(
                        CLASS, bytes("dev.turboism.internal.core.ShellServices"), FORBIDDEN_DOTTED, ALLOWED));
    }

    @Test
    void acceptsTheAllowedSideOfADottedPayload() {
        // `dev.turboism.sdk...` is the plugin's own SDK surface, and a kebab-case config key stops
        // at its first hyphen: the dotted scan denies roots, it does not allowlist.
        assertEquals(
                List.of(),
                PluginClassReferences.scan(
                        CLASS,
                        bytes("dev.turboism.sdk.event.SelectionChanged dev.turboism.texture-atlas.maxSize"),
                        FORBIDDEN_DOTTED,
                        ALLOWED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"com/live2d/csm/Model", "com.live2d.csm.Model"})
    void reportsCubismInternals(String reference) {
        assertEquals(
                List.of("Plugin class " + CLASS + " references forbidden Cubism internals (com.live2d)"),
                PluginClassReferences.scan(CLASS, bytes(reference), FORBIDDEN_DOTTED, ALLOWED));
    }

    @Test
    void reportsCubismInternalsOnceForBothShapes() {
        assertEquals(
                List.of("Plugin class " + CLASS + " references forbidden Cubism internals (com.live2d)"),
                PluginClassReferences.scan(
                        CLASS, bytes("com/live2d/csm/Model com.live2d.csm.Document"), FORBIDDEN_DOTTED, ALLOWED));
    }

    @Test
    void reportsInByteOrderThenCubismLast() {
        List<String> violations = PluginClassReferences.scan(
                CLASS,
                bytes("dev/turboism/persistence/TypedConfig com/live2d/csm/Model dev/turboism/bootstrap/Agent"),
                FORBIDDEN_DOTTED,
                ALLOWED);

        assertEquals(
                List.of(
                        "Plugin class " + CLASS + " references forbidden package dev/turboism/persistence",
                        "Plugin class " + CLASS + " references forbidden package dev/turboism/bootstrap",
                        "Plugin class " + CLASS + " references forbidden Cubism internals (com.live2d)"),
                violations);
    }

    @Test
    void findsNeedlesAtEveryOffset() {
        byte[] haystack = bytes("xxABCxxABC");

        assertEquals(2, PluginClassReferences.indexOfNeedle(haystack, bytes("ABC"), 0));
        assertEquals(7, PluginClassReferences.indexOfNeedle(haystack, bytes("ABC"), 3));
        assertEquals(-1, PluginClassReferences.indexOfNeedle(haystack, bytes("ABC"), 8));
        assertEquals(-1, PluginClassReferences.indexOfNeedle(haystack, bytes("ABCX"), 0));
        assertEquals(-1, PluginClassReferences.indexOfNeedle(bytes(""), bytes("ABC"), 0));
        // An empty needle is found at the search offset, which is what the callers' loop expects.
        assertEquals(4, PluginClassReferences.indexOfNeedle(haystack, bytes(""), 4));
    }

    @Test
    void acceptsOnlyBinaryNameBytesInASegment() {
        assertTrue(PluginClassReferences.isBinaryNameByte((byte) 'a'));
        assertTrue(PluginClassReferences.isBinaryNameByte((byte) 'Z'));
        assertTrue(PluginClassReferences.isBinaryNameByte((byte) '0'));
        assertTrue(PluginClassReferences.isBinaryNameByte((byte) '_'));
        assertTrue(PluginClassReferences.isBinaryNameByte((byte) '$'));
        assertFalse(PluginClassReferences.isBinaryNameByte((byte) '-'));
        assertFalse(PluginClassReferences.isBinaryNameByte((byte) '.'));
        assertFalse(PluginClassReferences.isBinaryNameByte((byte) '/'));
        assertFalse(PluginClassReferences.isBinaryNameByte((byte) ' '));
        assertFalse(PluginClassReferences.isBinaryNameByte((byte) 0xE9));
    }

    private static byte[] bytes(String reference) {
        return reference.getBytes(StandardCharsets.UTF_8);
    }
}
