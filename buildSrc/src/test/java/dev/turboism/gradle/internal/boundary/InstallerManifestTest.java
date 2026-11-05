package dev.turboism.gradle.internal.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two installer-metadata rules, replacing the fixtures that lived inside the task bodies.
 *
 * <p>The escape rule is a lint on the JSON text before any parser sees it, so its cases are the
 * spellings a lenient parser accepts and a strict one must reject: Arabic-Indic and fullwidth digits
 * look identical to a reader and decode to a different plugin id. The manifest rule is the sole
 * release allowlist, so every rejection path is asserted.
 */
class InstallerManifestTest {

    @TempDir
    Path root;

    @Test
    void acceptsAnAsciiEscapeAndKeepsTheText() {
        String json = "{\"id\":\"dev.turboism.plugin.\\u006dcp\"}";

        String text = InstallerManifest.rejectNonAsciiJsonUnicodeEscapes(bytes(json), "valid fixture");

        assertEquals(json, text);
        // The escape is what the strict parser under test would resolve; proving it here needs a
        // JSON parser, so the assertion is the canonical id JsonSlurper produces in the task.
        Map<?, ?> parsed = (Map<?, ?>) new groovy.json.JsonSlurper().parseText(text);
        assertEquals("dev.turboism.plugin.mcp", parsed.get("id"));
    }

    @Test
    void rejectsArabicIndicDigitsInAnEscape() {
        GradleException rejected = assertThrows(
                GradleException.class,
                () -> InstallerManifest.rejectNonAsciiJsonUnicodeEscapes(
                        bytes("{\"id\":\"dev.turboism.plugin.\\u\u0660\u0660\u0666dcp\"}"), "Arabic-Indic fixture"));

        assertEquals("Arabic-Indic fixture: JSON Unicode escape must use ASCII hexadecimal digits", rejected.getMessage());
    }

    @Test
    void rejectsFullwidthDigitsInAnEscape() {
        GradleException rejected = assertThrows(
                GradleException.class,
                () -> InstallerManifest.rejectNonAsciiJsonUnicodeEscapes(
                        bytes("{\"id\":\"dev.turboism.plugin.\\u\uff10\uff10\uff16dcp\"}"), "fullwidth fixture"));

        assertEquals("fullwidth fixture: JSON Unicode escape must use ASCII hexadecimal digits", rejected.getMessage());
    }

    @Test
    void rejectsATruncatedEscape() {
        GradleException rejected = assertThrows(
                GradleException.class,
                () -> InstallerManifest.rejectNonAsciiJsonUnicodeEscapes(bytes("{\"id\":\"a\\u006"), "truncated fixture"));

        assertEquals("truncated fixture: truncated JSON Unicode escape", rejected.getMessage());
    }

    @Test
    void rejectsNonUtf8Bytes() {
        GradleException rejected = assertThrows(
                GradleException.class,
                () -> InstallerManifest.rejectNonAsciiJsonUnicodeEscapes(
                        new byte[] {'{', (byte) 0xFF, '}'}, "binary fixture"));

        assertEquals("binary fixture: plugin.json is not valid UTF-8", rejected.getMessage());
    }

    @Test
    void treatsAnEvenBackslashRunAsAnEscapedBackslash() {
        // `\\u006d` is a literal backslash followed by the text `u006d`, not an escape.
        String json = "{\"id\":\"a\\\\u006d\"}";

        assertEquals(json, InstallerManifest.rejectNonAsciiJsonUnicodeEscapes(bytes(json), "escaped backslash"));
    }

    @Test
    void ignoresNonUnicodeEscapes() {
        String json = "{\"id\":\"a\\n\\t\\\"b\"}";

        assertEquals(json, InstallerManifest.rejectNonAsciiJsonUnicodeEscapes(bytes(json), "plain escapes"));
    }

    @Test
    void readsTheReleasePluginManifest() throws IOException {
        Path manifest = manifest(":plugins:mcp\n:plugins:selection-brush\n");

        assertEquals(
                List.of(":plugins:mcp", ":plugins:selection-brush"),
                InstallerManifest.parseReleasePluginManifest(
                        manifest.toFile(), Set.of(":plugins:mcp", ":plugins:selection-brush", ":runtime")));
    }

    @Test
    void rejectsAMissingManifest() {
        GradleException rejected = assertThrows(
                GradleException.class,
                () -> InstallerManifest.parseReleasePluginManifest(root.resolve("absent.txt").toFile(), Set.of()));

        assertTrue(rejected.getMessage().startsWith("release-plugins.txt missing: "), rejected.getMessage());
    }

    @Test
    void rejectsBlankAndCommentLines() throws IOException {
        assertEquals(
                "release-plugins.txt forbids blank/comment lines: ''",
                parseFailure(manifest(":plugins:mcp\n\n:plugins:selection-brush\n")));
        assertEquals(
                "release-plugins.txt forbids blank/comment lines: '# a note'",
                parseFailure(manifest("# a note\n:plugins:mcp\n")));
    }

    @Test
    void rejectsANonPluginEntry() throws IOException {
        assertEquals(
                "release-plugins.txt contains a non-plugin entry: 'runtime'",
                parseFailure(manifest(":plugins:mcp\nruntime\n")));
        assertEquals(
                "release-plugins.txt contains a non-plugin entry: ':plugins:MCP'",
                parseFailure(manifest(":plugins:MCP\n")));
    }

    @Test
    void rejectsDuplicates() throws IOException {
        assertEquals(
                "release-plugins.txt contains duplicates: ':plugins:mcp'",
                parseFailure(manifest(":plugins:mcp\n:plugins:mcp\n")));
    }

    @Test
    void rejectsUnsortedEntries() throws IOException {
        assertEquals(
                "release-plugins.txt entries are not ASCII-sorted",
                parseFailure(manifest(":plugins:selection-brush\n:plugins:mcp\n")));
    }

    @Test
    void rejectsAnUnknownProject() throws IOException {
        assertEquals(
                "release-plugins.txt references unknown project: ':plugins:gone'",
                parseFailure(manifest(":plugins:gone\n")));
    }

    private String parseFailure(Path manifest) {
        GradleException rejected = assertThrows(
                GradleException.class,
                () -> InstallerManifest.parseReleasePluginManifest(manifest.toFile(), Set.of()));
        return rejected.getMessage();
    }

    private Path manifest(String content) throws IOException {
        Path file = root.resolve("release-plugins.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
