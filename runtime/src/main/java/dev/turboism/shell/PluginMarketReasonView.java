package dev.turboism.shell;

import dev.turboism.pluginmanagement.PluginCatalog.CatalogPermission;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.util.function.Consumer;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;

/** Plain-text catalog reasons with localized, explicitly activated online README links. */
final class PluginMarketReasonView {
    private PluginMarketReasonView() {}

    static JEditorPane permissions(
            final PluginLocalization i18n,
            final CatalogRelease release,
            final String readmeUrl,
            final Consumer<String> openUrl) {
        final StringBuilder body = new StringBuilder("<p>")
                .append(escape(i18n.text("market.permission.confirm.message")))
                .append("</p><ul>");
        if (release.permissions().isEmpty()) {
            body.append("<li>").append(escape(i18n.text("common.none"))).append("</li>");
        }
        for (CatalogPermission permission : release.permissions()) {
            body.append("<li>")
                    .append(escape(permission.id()))
                    .append(" (")
                    .append(escape(permission.scope()))
                    .append(")");
            if (!permission.reason().isBlank()) {
                body.append(": ").append(escape(permission.reason()));
            }
            if (permission.reasonTruncated()) {
                body.append(" ").append(onlineLink(i18n, readmeUrl));
            }
            body.append("</li>");
        }
        return htmlPane(body.append("</ul>").toString(), readmeUrl, openUrl);
    }

    static JEditorPane truncationNotice(
            final PluginLocalization i18n, final String readmeUrl, final Consumer<String> openUrl) {
        return htmlPane(onlineLink(i18n, readmeUrl), readmeUrl, openUrl);
    }

    private static String onlineLink(final PluginLocalization i18n, final String readmeUrl) {
        return "<a href=\"" + escape(readmeUrl) + "\">" + escape(i18n.text("market.reason.truncated")) + "</a>";
    }

    private static JEditorPane htmlPane(final String body, final String readmeUrl, final Consumer<String> openUrl) {
        final JEditorPane pane = new JEditorPane("text/html", "");
        pane.setEditable(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        pane.setFont(new JLabel().getFont());
        if (UIManager.getColor("Panel.background") != null) {
            pane.setBackground(UIManager.getColor("Panel.background"));
        }
        final Runnable render = () -> {
            pane.setText(PluginReadmeRenderer.render("", pane.getForeground(), pane.getBackground())
                    .replace("<body>", "<body>" + body));
            pane.setCaretPosition(0);
        };
        pane.addPropertyChangeListener("foreground", ignored -> render.run());
        pane.addPropertyChangeListener("background", ignored -> render.run());
        render.run();
        pane.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED
                    && event.getURL() != null
                    && readmeUrl.equals(event.getURL().toExternalForm())) {
                openUrl.accept(readmeUrl);
            }
        });
        return pane;
    }

    private static String escape(final String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "<br>");
    }
}
