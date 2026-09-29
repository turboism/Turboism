package dev.turboism.plugin.commandpalette;

import dev.turboism.sdk.action.ActionCatalogService;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The Ctrl+K command palette window: an undecorated launcher dialog holding one input
 * field and a dropdown result list that appears below the field while the query has
 * matches.
 *
 * <p>Interaction contract:
 *
 * <ul>
 *   <li>typing filters {@link CommandCatalog#entries(PluginContext) the available Editor
 *       commands and registered plugin actions} by subsequence over both the localized
 *       name and the entry id;
 *   <li>a gray in-field hint shows the remaining suffix of the top match, and
 *       {@code Tab} accepts that completion;
 *   <li>{@code Enter} executes the selected (or top) match and closes the palette;
 *       with no matches, an input equal to a command id still executes it;
 *   <li>{@code Esc} or focus loss closes the palette without executing.
 * </ul>
 *
 * <p>The result list lives inside the dialog (not a {@code JPopupMenu}) so the palette is
 * one focusable window and focus-loss dismissal stays reliable. All methods run on the
 * EDT; {@link CommandPalettePlugin} dispatches here through {@code invokeLater}.
 */
final class CommandPaletteDialog {

    private static final int MAX_VISIBLE_ROWS = 10;
    private static final int ROW_HEIGHT = 24;
    private static final int DIALOG_WIDTH = 560;
    private static final int FIELD_FONT_INCREMENT = 2;
    private static final int LAYOUT_DEBOUNCE_MS = 80;
    private static final String HIGHLIGHT_COLOR = "#009600";

    private final PluginContext context;
    private final PluginLogger logger;
    private final java.util.function.Supplier<String> hintText;
    private final JDialog dialog;
    private final HintField input;
    private final DefaultListModel<CommandMatcher.Match> model;
    private final JList<CommandMatcher.Match> list;
    private final JScrollPane listScroll;
    private final javax.swing.Timer layoutTimer;
    private List<CommandMatcher.Entry> entries = List.of();
    private List<CommandMatcher.Match> matches = List.of();
    private boolean showing;

    CommandPaletteDialog(final PluginContext context, final java.util.function.Supplier<String> hintText) {
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.hintText = Objects.requireNonNull(hintText, "hintText");
        dialog = TurboismWindowFactory.dialog(null, "", false);
        if (dialog == null) {
            input = null;
            model = null;
            list = null;
            listScroll = null;
            layoutTimer = null;
            return;
        }
        dialog.setUndecorated(true);
        input = new HintField();
        model = new DefaultListModel<>();
        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new MatchRenderer());
        list.setFocusable(false);
        list.setFixedCellHeight(ROW_HEIGHT);
        listScroll = new JScrollPane(list);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setVisible(false);

        final JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color("controlShadow", new Color(0x7A7A7A)), 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        input.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        input.setFont(input.getFont().deriveFont(input.getFont().getSize() + (float) FIELD_FONT_INCREMENT));
        content.add(input, BorderLayout.NORTH);
        content.add(listScroll, BorderLayout.CENTER);
        dialog.setContentPane(content);
        input.setColumns(36);
        dialog.setSize(DIALOG_WIDTH, input.getPreferredSize().height + 32);
        dialog.setLocationRelativeTo(null);
        dialog.setAutoRequestFocus(true);

        layoutTimer = new javax.swing.Timer(LAYOUT_DEBOUNCE_MS, event -> relayout());
        layoutTimer.setRepeats(false);

        installKeys();
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent event) {
                refreshMatches();
            }

            @Override
            public void removeUpdate(final DocumentEvent event) {
                refreshMatches();
            }

            @Override
            public void changedUpdate(final DocumentEvent event) {
                refreshMatches();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                final int index = list.locationToIndex(event.getPoint());
                if (index >= 0) {
                    list.setSelectedIndex(index);
                    executeSelection();
                }
            }
        });
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(final WindowEvent event) {
                close();
            }
        });
    }

    /** Shows the palette, or brings it to the front when already open. No-op headless. */
    void show() {
        if (dialog == null) {
            return;
        }
        entries = CommandCatalog.entries(context);
        if (!showing) {
            input.setText("");
            model.clear();
            matches = List.of();
            listScroll.setVisible(false);
            showing = true;
            dialog.pack();
            dialog.setSize(Math.max(dialog.getWidth(), DIALOG_WIDTH), dialog.getHeight());
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
        } else {
            dialog.toFront();
        }
        input.requestFocusInWindow();
        updateGhost();
    }

    /** Whether the palette window is currently on screen. */
    boolean isShowing() {
        return showing && dialog != null && dialog.isShowing();
    }

    /** Toggles the palette: hides when showing, shows when hidden. */
    void toggle() {
        if (isShowing()) {
            close();
        } else {
            show();
        }
    }

    /** Hides and releases the dialog; safe to call repeatedly. */
    void close() {
        showing = false;
        matches = List.of();
        if (layoutTimer != null) {
            layoutTimer.stop();
        }
        if (dialog != null) {
            dialog.setVisible(false);
        }
    }

    /** Disposes the window for good; called on plugin disable/shutdown. */
    void dispose() {
        showing = false;
        if (layoutTimer != null) {
            layoutTimer.stop();
        }
        if (dialog != null) {
            dialog.dispose();
        }
    }

    private void installKeys() {
        final JComponent component = input;
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "execute");
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "complete");
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "selectNext");
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "selectPrevious");
        component.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                close();
            }
        });
        component.getActionMap().put("execute", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                executeSelection();
            }
        });
        component.getActionMap().put("complete", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                acceptCompletion();
            }
        });
        component.getActionMap().put("selectNext", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                moveSelection(1);
            }
        });
        component.getActionMap().put("selectPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                moveSelection(-1);
            }
        });
        component
                .getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "selectPrevious");
    }

    private void refreshMatches() {
        matches = CommandMatcher.match(input.getText(), entries);
        model.clear();
        for (final CommandMatcher.Match match : matches) {
            model.addElement(match);
        }
        if (!matches.isEmpty()) {
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
        }
        updateGhost();
        // pack() resizes the native window, which under Wine/Proton costs a
        // synchronous X11 round trip; debounce so typing never pays it per key.
        if (layoutTimer != null) {
            layoutTimer.restart();
        }
    }

    private void relayout() {
        if (!showing || dialog == null) {
            return;
        }
        if (matches.isEmpty()) {
            if (!listScroll.isVisible()) {
                return;
            }
            listScroll.setVisible(false);
        } else {
            final int listHeight = Math.min(matches.size(), MAX_VISIBLE_ROWS) * ROW_HEIGHT
                    + listScroll.getInsets().top
                    + listScroll.getInsets().bottom;
            if (listScroll.isVisible() && listScroll.getPreferredSize().height == listHeight) {
                return;
            }
            list.setVisibleRowCount(Math.min(matches.size(), MAX_VISIBLE_ROWS));
            listScroll.setPreferredSize(new Dimension(DIALOG_WIDTH - 20, listHeight));
            listScroll.setVisible(true);
        }
        dialog.pack();
        dialog.setSize(Math.max(dialog.getWidth(), DIALOG_WIDTH), dialog.getHeight());
    }

    private void moveSelection(final int delta) {
        if (model.isEmpty()) {
            return;
        }
        final int size = model.getSize();
        final int current = list.getSelectedIndex();
        final int next = Math.floorMod(current + delta, size);
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    /** Accepts the ghost suffix (or the top match's name) as the new query. */
    private void acceptCompletion() {
        if (matches.isEmpty()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        final String query = input.getText();
        final String completion = CommandMatcher.completion(query, matches.get(0));
        input.setText(completion != null ? completion : matches.get(0).entry().name());
        input.setCaretPosition(input.getText().length());
    }

    private void executeSelection() {
        CommandMatcher.Entry entry = selectedEntry();
        if (entry == null) {
            entry = exactIdMatch(input.getText());
        }
        if (entry == null) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        final CommandMatcher.Entry chosen = entry;
        close();
        // Fire after the palette is fully hidden so a modal native dialog raised by the
        // command never reactivates the closing window.
        SwingUtilities.invokeLater(() -> execute(chosen));
    }

    private CommandMatcher.Entry selectedEntry() {
        if (matches.isEmpty()) {
            return null;
        }
        final int index = list.getSelectedIndex();
        final CommandMatcher.Match match = index >= 0 ? matches.get(index) : matches.get(0);
        return match.entry();
    }

    /** Executes even when the list is empty but the input text is exactly an entry id. */
    private CommandMatcher.Entry exactIdMatch(final String query) {
        final String normalized = CommandMatcher.normalize(query);
        if (normalized.isEmpty()) {
            return null;
        }
        for (final CommandMatcher.Entry entry : entries) {
            if (CommandMatcher.normalize(entry.id()).equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    private void execute(final CommandMatcher.Entry entry) {
        switch (entry.kind()) {
            case COMMAND -> executeCommand(entry.command());
            case ACTION -> invokeAction(entry);
        }
    }

    private void executeCommand(final EditorCommand command) {
        final EditorCommandService service = context.services().get(EditorCommandService.class);
        if (service == null) {
            logger.warn("Command Palette cannot run " + command.id() + ": Editor commands unavailable");
            return;
        }
        final EditorCommandResult result = service.execute(command);
        if (!result.executed()) {
            logger.warn("Command Palette command " + command.id() + " was not executed: " + result.status());
        }
    }

    private void invokeAction(final CommandMatcher.Entry entry) {
        final ActionCatalogService catalog = context.services().get(ActionCatalogService.class);
        if (catalog == null) {
            logger.warn("Command Palette cannot run action " + entry.id() + ": action catalog unavailable");
            return;
        }
        try {
            catalog.invoke(entry.pluginId(), entry.id());
        } catch (RuntimeException failure) {
            logger.warn("Command Palette action " + entry.id() + " was not invoked: " + failure.getMessage());
        }
    }

    private void updateGhost() {
        final String query = input.getText();
        String ghost = null;
        if (!query.isEmpty() && !matches.isEmpty()) {
            final String completion = CommandMatcher.completion(query, matches.get(0));
            if (completion != null && completion.length() > query.length()) {
                ghost = completion.substring(query.length());
            }
        }
        input.setGhost(ghost);
    }

    private static Color color(final String key, final Color fallback) {
        final Color value = UIManager.getColor(key);
        return value != null ? value : fallback;
    }

    /**
     * Input field painting two kinds of gray hint text after the user text: the
     * localized placeholder while the field is empty, and the remaining completion
     * suffix of the top match while a completion exists (accepted with {@code Tab}).
     */
    private final class HintField extends JTextField {

        private String ghost;

        void setGhost(final String value) {
            ghost = value;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            final String text = getText();
            final String hint;
            if (text.isEmpty()) {
                hint = hintText.get();
            } else if (ghost != null) {
                hint = ghost;
            } else {
                return;
            }
            final FontMetrics metrics = graphics.getFontMetrics(getFont());
            final Insets insets = getInsets();
            final int x = insets.left + metrics.stringWidth(text) + (text.isEmpty() ? 0 : 2);
            final int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.setColor(color("TextField.inactiveForeground", new Color(0x888888)));
            graphics.setFont(getFont());
            graphics.drawString(hint, x, y);
        }
    }

    /**
     * Row renderer: localized name on the left, command id right-aligned. In each field
     * the characters the query matched render green; all other text keeps the list's
     * normal foreground.
     */
    private static final class MatchRenderer extends JPanel implements ListCellRenderer<CommandMatcher.Match> {

        private final JLabel name = new JLabel();
        private final JLabel id = new JLabel();

        MatchRenderer() {
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setOpaque(true);
            id.setHorizontalAlignment(JLabel.RIGHT);
            final Font idFont = id.getFont().deriveFont(id.getFont().getSize() - 1f);
            id.setFont(idFont);
            add(name);
            add(Box.createHorizontalGlue());
            add(Box.createHorizontalStrut(16));
            add(id);
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }

        @Override
        public Component getListCellRendererComponent(
                final JList<? extends CommandMatcher.Match> list,
                final CommandMatcher.Match value,
                final int index,
                final boolean selected,
                final boolean focused) {
            final Color background = selected
                    ? color("List.selectionBackground", new Color(0xD0D0D0))
                    : color("List.background", Color.WHITE);
            final Color foreground = color("List.foreground", Color.BLACK);
            setBackground(background);
            name.setForeground(foreground);
            id.setForeground(foreground);
            name.setText(markup(value.entry().name(), value.nameHits()));
            id.setText(markup(value.entry().id(), value.idHits()));
            return this;
        }

        /**
         * Wraps the hit characters in a green font tag. Normal text carries no color tag
         * so it keeps the label foreground (near-black in the bundled look and feel).
         */
        private static String markup(final String text, final int[] hits) {
            if (hits.length == 0) {
                return "<html>" + escape(text) + "</html>";
            }
            final boolean[] marked = new boolean[text.length()];
            for (final int hit : hits) {
                if (hit >= 0 && hit < marked.length) {
                    marked[hit] = true;
                }
            }
            final StringBuilder html = new StringBuilder("<html>");
            boolean open = false;
            for (int index = 0; index < text.length(); index++) {
                if (marked[index] && !open) {
                    html.append("<font color='").append(HIGHLIGHT_COLOR).append("'>");
                    open = true;
                } else if (!marked[index] && open) {
                    html.append("</font>");
                    open = false;
                }
                html.append(escape(text.charAt(index)));
            }
            if (open) {
                html.append("</font>");
            }
            return html.append("</html>").toString();
        }

        private static String escape(final String text) {
            final StringBuilder out = new StringBuilder(text.length() + 8);
            for (int index = 0; index < text.length(); index++) {
                out.append(escape(text.charAt(index)));
            }
            return out.toString();
        }

        private static String escape(final char character) {
            return switch (character) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                default -> String.valueOf(character);
            };
        }
    }
}
