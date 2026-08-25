package dev.turboism.plugin.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginReadmeRendererTest {

    @Test
    void hidesStoreMetadataFrontMatter() {
        final String html = PluginReadmeRenderer.render("""
            ---
            turboismReadmeSchema: 1
            pluginId: example.plugin
            status: preview
            ---

            # Example Plugin
            """);

        assertTrue(html.contains("<h1>Example Plugin</h1>"));
        assertFalse(html.contains("turboismReadmeSchema"));
        assertFalse(html.contains("pluginId:"));
    }

    @Test
    void rendersStoreReadmeTables() {
        final String html = PluginReadmeRenderer.render("""
            | Detail | Value |
            |---|---|
            | Plugin ID | `example.plugin` |
            | Requires Cubism | Yes |
            """);

        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<th>Detail</th>"));
        assertTrue(html.contains("<td><code>example.plugin</code></td>"));
        assertFalse(html.contains("|---|---|"));
    }

    @Test
    void rendersCommonMarkdownBlocksAndInlineFormatting() {
        final String html = PluginReadmeRenderer.render("""
            # Plugin title

            A **strong** and *emphasized* paragraph with `code`.

            - first
            - second

            1. one
            2. two

            > note

            [Website](https://example.test/docs)
            """);

        assertTrue(html.contains("<h1>Plugin title</h1>"));
        assertTrue(html.contains("<strong>strong</strong>"));
        assertTrue(html.contains("<em>emphasized</em>"));
        assertTrue(html.contains("<code>code</code>"));
        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<ol>"));
        assertTrue(html.contains("<blockquote>note</blockquote>"));
        assertTrue(html.contains("href=\"https://example.test/docs\""));
    }

    @Test
    void escapesRawHtmlAndDoesNotCreateUnsafeLinks() {
        final String html = PluginReadmeRenderer.render("""
            <script>alert('x')</script>

            [Local](file:///etc/passwd)
            [Script](javascript:alert(1))
            """);

        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("href=\"file:"));
        assertFalse(html.contains("href=\"javascript:"));
    }

    @Test
    void preservesMarkdownDelimitersInsideHttpsLinkDestinations() {
        final String html = PluginReadmeRenderer.render(
            "[Docs](https://example.test/**segment**/guide)"
        );

        assertTrue(html.contains("href=\"https://example.test/**segment**/guide\""));
        assertFalse(html.contains("href=\"https://example.test/&lt;strong&gt;"));
    }

    @Test
    void rendersFencedCodeAsEscapedPreformattedText() {
        final String html = PluginReadmeRenderer.render("""
            ```java
            if (left < right) {
                return "ok";
            }
            ```
            """);

        assertTrue(html.contains("<pre><code>"));
        assertTrue(html.contains("left &lt; right"));
        assertTrue(html.contains("&quot;ok&quot;"));
    }
}
