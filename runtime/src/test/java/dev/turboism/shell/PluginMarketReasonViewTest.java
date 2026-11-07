package dev.turboism.shell;

import static dev.turboism.pluginmanagement.PluginCatalogVerifier.MAX_REASON_CODE_POINTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogDependency;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPermission;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.Color;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.PropertyResourceBundle;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import org.junit.jupiter.api.Test;

class PluginMarketReasonViewTest {
    private static final String README_URL = "https://turboism.dev/plugins/mcp";

    @Test
    void appendsLinkOnlyAfterAnActuallyTruncatedReason() throws Exception {
        final String reason = "r".repeat(MAX_REASON_CODE_POINTS);
        SwingUtilities.invokeAndWait(() -> {
            for (boolean truncated : List.of(false, true)) {
                final var permission = new CatalogPermission("turboism.file.read", "application", reason, truncated);
                final var release = release(List.of(), List.of(permission));
                final JEditorPane pane = PluginMarketReasonView.permissions(
                        localization("_zh_Hans"), release, README_URL, ignored -> {});
                assertEquals(truncated ? 1 : 0, linkCount(pane));
                assertEquals(truncated, release.hasTruncatedReasons());
                if (truncated) {
                    final String html = pane.getText();
                    final String text = plainText(pane);
                    assertTrue(text.indexOf(reason) < text.indexOf("内容过长, 请在线查看"));
                    assertTrue(html.contains("href=\"" + README_URL + "\""));
                }
            }
        });
    }

    @Test
    void localizesTheLinkInEveryBundledCatalog() throws Exception {
        final String[][] translations = {
            {"", "Content too long, view online"},
            {"_en", "Content too long, view online"},
            {"_zh_Hans", "内容过长, 请在线查看"},
            {"_zh_Hant", "內容過長, 請線上查看"},
            {"_ja", "内容が長すぎるため、オンラインでご確認ください"},
            {"_ko", "내용이 너무 깁니다. 온라인에서 확인하세요"}
        };
        SwingUtilities.invokeAndWait(() -> {
            for (String[] translation : translations) {
                final PluginLocalization i18n = localization(translation[0]);
                assertEquals(translation[1], i18n.text("market.reason.truncated"));
                final JEditorPane pane = PluginMarketReasonView.truncationNotice(i18n, README_URL, ignored -> {});
                assertTrue(plainText(pane).contains(translation[1]));
                assertEquals(1, linkCount(pane));
            }
        });
    }

    @Test
    void opensOnlyTheOnlineReadmeOnExplicitActivation() throws Exception {
        final List<String> opened = new ArrayList<>();
        final URL readme = new URL(README_URL);
        final URL unrelated = new URL("https://example.com/untrusted");
        SwingUtilities.invokeAndWait(() -> {
            final JEditorPane pane =
                    PluginMarketReasonView.truncationNotice(localization("_en"), README_URL, opened::add);
            fire(pane, HyperlinkEvent.EventType.ENTERED, readme);
            fire(pane, HyperlinkEvent.EventType.ACTIVATED, unrelated);
            fire(pane, HyperlinkEvent.EventType.ACTIVATED, null);
            assertTrue(opened.isEmpty());
            fire(pane, HyperlinkEvent.EventType.ACTIVATED, readme);
            assertEquals(List.of(README_URL), opened);
        });
    }

    @Test
    void treatsCatalogReasonMarkupAsPlainText() throws Exception {
        final String reason = "<img src=\"https://example.com/image\"><a href=\"https://example.com\">bad</a>\n"
                + "[Markdown](https://example.com) & text";
        SwingUtilities.invokeAndWait(() -> {
            final var permission = new CatalogPermission("turboism.file.read", "application", reason, true);
            final JEditorPane pane = PluginMarketReasonView.permissions(
                    localization("_en"), release(List.of(), List.of(permission)), README_URL, ignored -> {});
            final HTMLDocument document = (HTMLDocument) pane.getDocument();
            assertEquals(1, linkCount(pane), "only the generated README link may be active");
            assertFalse(document.getIterator(HTML.Tag.IMG).isValid());
            assertTrue(pane.getText().contains("&lt;img"));
            assertTrue(pane.getText().contains("&amp; text"));
        });
    }

    @Test
    void updatesLinkColorsWhenTheThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JEditorPane pane =
                    PluginMarketReasonView.truncationNotice(localization("_en"), README_URL, ignored -> {});
            pane.setForeground(Color.WHITE);
            pane.setBackground(Color.BLACK);
            assertTrue(pane.getText().contains("#8ab4f8"));
            assertEquals(1, linkCount(pane));
            pane.setBackground(Color.WHITE);
            pane.setForeground(Color.BLACK);
            assertTrue(pane.getText().contains("#155dfc"));
            assertEquals(1, linkCount(pane));
        });
    }

    @Test
    void noticesDependencyTruncationAndPreservesLegacyConstructorDefaults() {
        final var normal =
                new CatalogDependency("dev.turboism.plugin.peer", "1.0.0", "optional", "none", Optional.empty());
        final var truncated = new CatalogDependency(
                "dev.turboism.plugin.peer", "1.0.0", "optional", "none", Optional.of("reason"), true);
        final var permission = new CatalogPermission("turboism.file.read", "application", "reason");
        assertFalse(normal.reasonTruncated());
        assertFalse(permission.reasonTruncated());
        assertFalse(release(List.of(normal), List.of(permission)).hasTruncatedReasons());
        assertTrue(release(List.of(truncated), List.of(permission)).hasTruncatedReasons());
    }

    private static CatalogRelease release(List<CatalogDependency> dependencies, List<CatalogPermission> permissions) {
        return new CatalogRelease(
                "1.0.0",
                "stable",
                "active",
                Instant.EPOCH,
                "integration",
                List.of(),
                "1.0.0",
                false,
                List.of(),
                List.of("windows-x64"),
                dependencies,
                permissions,
                "https://github.com/turboism/Turboism/releases/tag/v1",
                Optional.empty(),
                new CatalogArtifact(
                        "application/java-archive",
                        "demo.jar",
                        "https://github.com/turboism/Turboism/releases/download/v1/demo.jar",
                        "a".repeat(64),
                        "b".repeat(64),
                        1));
    }

    private static String plainText(JEditorPane pane) {
        try {
            return pane.getDocument().getText(0, pane.getDocument().getLength());
        } catch (javax.swing.text.BadLocationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static int linkCount(JEditorPane pane) {
        int count = 0;
        for (var iterator = ((HTMLDocument) pane.getDocument()).getIterator(HTML.Tag.A);
                iterator.isValid();
                iterator.next()) {
            count++;
        }
        return count;
    }

    private static void fire(JEditorPane pane, HyperlinkEvent.EventType type, URL url) {
        final var event = new HyperlinkEvent(pane, type, url);
        for (var listener : pane.getHyperlinkListeners()) listener.hyperlinkUpdate(event);
    }

    private static PluginLocalization localization(String suffix) {
        final PropertyResourceBundle bundle;
        try (var stream = PluginMarketReasonViewTest.class.getResourceAsStream(
                "/META-INF/turboism/i18n/messages" + suffix + ".properties")) {
            bundle = new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        return new PluginLocalization() {
            public Locale locale() {
                return Locale.ROOT;
            }

            public String text(String key) {
                return bundle.getString(key);
            }

            public String format(String key, Object... arguments) {
                return MessageFormat.format(text(key), arguments);
            }

            public boolean contains(String key) {
                return bundle.containsKey(key);
            }
        };
    }
}
