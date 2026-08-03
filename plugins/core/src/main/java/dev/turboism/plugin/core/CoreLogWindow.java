package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.runtime.RuntimeLogReader;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Built-in core viewer for the Runtime-owned Turboism log. */
final class CoreLogWindow implements AutoCloseable {

    private static final Pattern LEVEL = Pattern.compile(
        "^\\S+ \\[(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)] \\[",
        Pattern.MULTILINE
    );

    private final PluginLocalization i18n;
    private final RuntimeLogReader logs;
    private JDialog dialog;
    private JTextPane output;
    private JTextField keyword;
    private JComboBox<String> minimumLevel;
    private Timer refreshTimer;

    CoreLogWindow(final PluginLocalization i18n, final RuntimeLogReader logs) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.logs = Objects.requireNonNull(logs, "logs");
    }

    void show() {
        CoreDialogs.onEdt(() -> {
            if (dialog == null) dialog = createDialog();
            refresh();
            refreshTimer.start();
            CoreDialogs.show(dialog);
        });
    }

    @Override
    public void close() {
        CoreDialogs.onEdt(() -> {
            if (refreshTimer != null) refreshTimer.stop();
            if (dialog != null) dialog.dispose();
            dialog = null;
            output = null;
            keyword = null;
            minimumLevel = null;
            refreshTimer = null;
        });
    }

    private JDialog createDialog() {
        final JDialog value = CoreDialogs.create(i18n.text("window.logs.title"), 900, 600);
        value.setLayout(new BorderLayout(8, 8));

        keyword = new JTextField(22);
        minimumLevel = new JComboBox<>(new String[]{"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"});
        minimumLevel.setSelectedItem("TRACE");
        output = new JTextPane();
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        final JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        filters.add(new JLabel(i18n.text("logs.filter")));
        filters.add(keyword);
        filters.add(new JLabel(i18n.text("logs.minimum-level")));
        filters.add(minimumLevel);

        final JButton openDirectory = new JButton(i18n.text("logs.open-directory"));
        openDirectory.addActionListener(ignored -> openDirectory());
        final JButton copy = new JButton(i18n.text("logs.copy"));
        copy.addActionListener(ignored -> copy());
        final JButton refresh = new JButton(i18n.text("common.refresh"));
        refresh.addActionListener(ignored -> refresh());
        final JButton close = new JButton(i18n.text("common.close"));
        close.addActionListener(ignored -> hide());
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(openDirectory);
        buttons.add(copy);
        buttons.add(refresh);
        buttons.add(close);

        keyword.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(final DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(final DocumentEvent event) { refresh(); }
        });
        minimumLevel.addActionListener(ignored -> refresh());
        refreshTimer = new Timer(1_000, ignored -> refresh());
        value.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(final WindowEvent event) { refreshTimer.stop(); }
            @Override public void windowClosed(final WindowEvent event) { refreshTimer.stop(); }
        });

        value.add(filters, BorderLayout.NORTH);
        value.add(new JScrollPane(output), BorderLayout.CENTER);
        value.add(buttons, BorderLayout.SOUTH);
        return value;
    }

    private void refresh() {
        if (output == null) return;
        render(output, filter(
            logs.snapshot().lines(),
            keyword.getText(),
            Objects.toString(minimumLevel.getSelectedItem(), "INFO")
        ));
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void hide() {
        refreshTimer.stop();
        dialog.setVisible(false);
    }

    private void openDirectory() {
        final Path directory = logs.snapshot().directory().orElse(null);
        try {
            if (directory == null || !Desktop.isDesktopSupported()) throw new IOException("desktop unavailable");
            Desktop.getDesktop().open(directory.toFile());
        } catch (IOException | UnsupportedOperationException failure) {
            CoreDialogs.message(dialog, i18n.text("common.turboism"), i18n.text("logs.open-failed"));
        }
    }

    private void copy() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(output.getText()), null);
    }

    static void render(final JTextPane target, final String content) {
        final JTextPane output = Objects.requireNonNull(target, "target");
        final String text = Objects.requireNonNull(content, "content");
        output.setText(text);
        final StyledDocument document = output.getStyledDocument();
        final SimpleAttributeSet[] styles = levelStyles(output);
        final Matcher matcher = LEVEL.matcher(text);
        int start = 0;
        int level = severity("INFO");
        while (matcher.find()) {
            applyStyle(document, start, matcher.start() - start, styles[level]);
            start = matcher.start();
            level = severity(matcher.group(1));
        }
        applyStyle(document, start, text.length() - start, styles[level]);
    }

    private static void applyStyle(
        final StyledDocument document,
        final int start,
        final int length,
        final SimpleAttributeSet style
    ) {
        if (length > 0) document.setCharacterAttributes(start, length, style, false);
    }

    private static SimpleAttributeSet[] levelStyles(final JTextPane output) {
        final boolean dark = isDark(output.getBackground());
        final Color foreground = Objects.requireNonNullElse(
            output.getForeground(),
            dark ? Color.WHITE : Color.BLACK
        );
        final SimpleAttributeSet[] styles = new SimpleAttributeSet[6];
        for (int level = 0; level < styles.length; level++) {
            styles[level] = new SimpleAttributeSet();
            StyleConstants.setForeground(styles[level], levelColor(level, dark, foreground));
        }
        return styles;
    }

    private static Color levelColor(final int level, final boolean dark, final Color foreground) {
        return switch (level) {
            case 0 -> new Color(dark ? 0xA0A7B4 : 0x5F6368);
            case 1 -> new Color(dark ? 0x6CB6FF : 0x0057B8);
            case 3 -> new Color(dark ? 0xFFD166 : 0x8A5A00);
            case 4, 5 -> new Color(dark ? 0xFF6B6B : 0xB00020);
            default -> foreground;
        };
    }

    private static boolean isDark(final Color color) {
        if (color == null) return false;
        return (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1_000 < 128;
    }

    static String filter(final List<String> lines, final String keyword, final String minimumLevel) {
        final String query = Objects.toString(keyword, "").trim().toLowerCase(Locale.ROOT);
        final int minimum = severity(minimumLevel);
        final List<String> matches = new ArrayList<>();
        final List<String> entry = new ArrayList<>();
        int entryLevel = severity("INFO");
        for (String line : Objects.requireNonNull(lines, "lines")) {
            final Matcher matcher = LEVEL.matcher(line);
            if (matcher.find()) {
                appendIfMatching(matches, entry, entryLevel, minimum, query);
                entry.clear();
                entryLevel = severity(matcher.group(1));
            }
            entry.add(line);
        }
        appendIfMatching(matches, entry, entryLevel, minimum, query);
        return String.join("\n", matches);
    }

    private static void appendIfMatching(
        final List<String> matches,
        final List<String> entry,
        final int level,
        final int minimum,
        final String query
    ) {
        if (entry.isEmpty() || level < minimum) return;
        if (!query.isEmpty() && entry.stream().noneMatch(line -> line.toLowerCase(Locale.ROOT).contains(query))) return;
        matches.addAll(entry);
    }

    private static int severity(final String level) {
        return switch (Objects.toString(level, "INFO")) {
            case "TRACE" -> 0;
            case "DEBUG" -> 1;
            case "WARN" -> 3;
            case "ERROR" -> 4;
            case "FATAL" -> 5;
            default -> 2;
        };
    }
}
