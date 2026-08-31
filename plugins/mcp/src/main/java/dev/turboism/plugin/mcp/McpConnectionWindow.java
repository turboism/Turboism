package dev.turboism.plugin.mcp;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** User-controlled view of the current local MCP endpoint, bearer, and connection history. */
final class McpConnectionWindow {

    private static final DateTimeFormatter TIME = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final PluginLocalization localization;
    private final PluginLogger logger;
    private final JFrame frame;
    private final JTextField endpoint = new JTextField();
    private final JTextField token = new JTextField();
    private final HistoryModel history = new HistoryModel();
    private final JTable historyTable = new JTable(history);
    private final JButton refresh = new JButton();
    private java.util.function.Supplier<McpConnectionSnapshot> snapshot =
        () -> new McpConnectionSnapshot(null, "", List.of());

    McpConnectionWindow(
        final PluginLocalization localization,
        final PluginLogger logger
    ) {
        this.localization = Objects.requireNonNull(localization, "localization");
        this.logger = Objects.requireNonNull(logger, "logger");
        frame = TurboismWindowFactory.frame(text("window.connection-title", "MCP Connection"));
        if (frame == null) throw new IllegalStateException("Swing is unavailable in a headless JVM");
        configure();
    }

    void bind(final java.util.function.Supplier<McpConnectionSnapshot> source) {
        snapshot = Objects.requireNonNull(source, "source");
    }

    void showAndFront() {
        refresh();
        frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
        SwingUtilities.invokeLater(() -> {
            if (!frame.isVisible()) return;
            frame.toFront();
            frame.requestFocus();
        });
    }

    void dispose() {
        frame.dispose();
    }

    private void configure() {
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 480));
        frame.setContentPane(content());
        frame.pack();
        frame.setLocationByPlatform(true);
        endpoint.setEditable(false);
        token.setEditable(false);
        endpoint.setFont(new Font(Font.MONOSPACED, Font.PLAIN, endpoint.getFont().getSize()));
        token.setFont(new Font(Font.MONOSPACED, Font.PLAIN, token.getFont().getSize()));
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyTable.setFillsViewportHeight(true);
        historyTable.getTableHeader().setReorderingAllowed(false);
        refresh.setText(text("button.refresh", "Refresh"));
        refresh.addActionListener(ignored -> refresh());
    }

    private JPanel content() {
        final JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        final JPanel credentials = new JPanel(new GridBagLayout());
        credentials.setBorder(BorderFactory.createTitledBorder(
            text("section.connection", "Connection")
        ));
        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridy = 0;
        addRow(
            credentials,
            constraints,
            text("label.endpoint", "Address"),
            endpoint,
            text("button.copy-endpoint", "Copy address"),
            () -> copy(endpoint.getText(), "status.endpoint-copied")
        );
        constraints.gridy++;
        addRow(
            credentials,
            constraints,
            text("label.token", "Bearer token"),
            token,
            text("button.copy-token", "Copy token"),
            () -> copy(token.getText(), "status.token-copied")
        );
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        credentials.add(new JLabel("<html>" + text(
            "label.token-warning",
            "The token is a local secret. Copy it only into a trusted local coding agent."
        ) + "</html>"), constraints);

        final JPanel historyPanel = new JPanel(new BorderLayout(6, 6));
        historyPanel.setBorder(BorderFactory.createTitledBorder(
            text("section.history", "Connection history")
        ));
        historyPanel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(refresh);
        historyPanel.add(actions, BorderLayout.SOUTH);

        root.add(credentials, BorderLayout.NORTH);
        root.add(historyPanel, BorderLayout.CENTER);
        return root;
    }

    private static void addRow(
        final JPanel panel,
        final GridBagConstraints constraints,
        final String label,
        final JTextField value,
        final String buttonText,
        final Runnable copy
    ) {
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(value, constraints);
        final JButton button = new JButton(buttonText);
        button.addActionListener(ignored -> copy.run());
        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(button, constraints);
    }

    private void refresh() {
        final McpConnectionSnapshot current = snapshot.get();
        endpoint.setText(current.endpoint() == null ? "" : current.endpoint().toString());
        token.setText(current.authorization());
        endpoint.setCaretPosition(0);
        token.setCaretPosition(0);
        history.replace(current.history());
    }

    private void copy(final String value, final String statusKey) {
        if (value == null || value.isBlank()) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(value), null);
            logger.info(copyStatus(statusKey));
        } catch (RuntimeException failure) {
            logger.warn("MCP connection copy failed safely");
        }
    }

    private String copyStatus(final String key) {
        return text(key, switch (key) {
            case "status.endpoint-copied" -> "MCP address copied by explicit user action";
            case "status.token-copied" -> "MCP bearer token copied by explicit user action";
            default -> "MCP connection value copied by explicit user action";
        });
    }

    private String text(final String key, final String fallback) {
        try {
            final String value = localization.text(key);
            return value == null || value.isBlank() || key.equals(value) ? fallback : value;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    record McpConnectionSnapshot(
        URI endpoint,
        String authorization,
        List<McpConnectionHistory.Entry> history
    ) {
        McpConnectionSnapshot {
            authorization = Objects.requireNonNullElse(authorization, "");
            history = List.copyOf(Objects.requireNonNull(history, "history"));
        }
    }

    private final class HistoryModel extends AbstractTableModel {
        private final ArrayList<McpConnectionHistory.Entry> rows = new ArrayList<>();

        void replace(final List<McpConnectionHistory.Entry> values) {
            rows.clear();
            for (int index = values.size() - 1; index >= 0; index--) {
                rows.add(values.get(index));
            }
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 4; }
        @Override public String getColumnName(final int column) {
            return switch (column) {
                case 0 -> text("history.time", "Time");
                case 1 -> text("history.event", "Event");
                case 2 -> text("history.client", "Client");
                default -> text("history.detail", "Detail");
            };
        }
        @Override public Object getValueAt(final int row, final int column) {
            final McpConnectionHistory.Entry entry = rows.get(row);
            return switch (column) {
                case 0 -> TIME.format(entry.timestamp());
                case 1 -> eventText(entry.event());
                case 2 -> entry.client();
                default -> entry.detail();
            };
        }

        private String eventText(final McpConnectionHistory.Event event) {
            return text(
                "history.event." + event.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                event.name().replace('_', ' ')
            );
        }
    }
}
