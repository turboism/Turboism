package dev.turboism.plugin.acp;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Modal Swing dialog presenting structured agent Q&A prompts (single, multi-select, and freeform). */
final class AcpQuestionDialog {

    private static final Dimension MINIMUM_SIZE = new Dimension(500, 260);
    private static final int MAX_SCROLL_HEIGHT = 340;
    private static final int PREVIEW_MAX_CHARS = 600;
    private static final String DIALOG_NAME = "turboism-acp.question";
    private static final String SUBMIT_NAME = "turboism-acp.question.submit";
    private static final String CANCEL_NAME = "turboism-acp.question.cancel";
    private static final String NEXT_NAME = "turboism-acp.question.next";
    private static final String BACK_NAME = "turboism-acp.question.back";

    private AcpQuestionDialog() {}

    static AcpQuestion.Response show(
            final Frame owner, final PluginLocalization localization, final AcpQuestion.Request request) {
        Objects.requireNonNull(localization, "localization");
        Objects.requireNonNull(request, "request");
        if (request.items().isEmpty() || request.cancellation().isCancelled()) {
            return AcpQuestion.Response.CANCELLED;
        }
        final JDialog dialog = TurboismWindowFactory.dialog(owner, localization.text("question.title"), true);
        if (dialog == null) {
            return AcpQuestion.Response.CANCELLED;
        }
        final AtomicReference<AcpQuestion.Response> result = new AtomicReference<>(AcpQuestion.Response.CANCELLED);
        final var cancellation = request.cancellation().onCancel(() -> SwingUtilities.invokeLater(dialog::dispose));
        try {
            if (request.cancellation().isCancelled()) {
                return AcpQuestion.Response.CANCELLED;
            }
            dialog.setName(DIALOG_NAME);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setMinimumSize(MINIMUM_SIZE);
            final Session session = new Session(localization, request, response -> {
                result.set(response);
                dialog.dispose();
            });
            dialog.getRootPane()
                    .registerKeyboardAction(
                            ignored -> dialog.dispose(),
                            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                            JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.setContentPane(session.root);
            dialog.getRootPane().setDefaultButton(session.primaryAction());
            dialog.pack();
            AcpChatWindow.ensureMinimumSize(dialog, MINIMUM_SIZE);
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
            return request.cancellation().isCancelled() ? AcpQuestion.Response.CANCELLED : result.get();
        } finally {
            cancellation.close();
            dialog.dispose();
        }
    }

    static Session createSessionForTest(final PluginLocalization localization, final AcpQuestion.Request request) {
        return new Session(localization, request, ignored -> {});
    }

    static final class Session {
        final JPanel root;
        final List<QuestionCard> cards = new ArrayList<>();
        final JButton back = new JButton();
        final JButton next = new JButton();
        final JButton submit = new JButton();
        final JButton cancel = new JButton();
        private final PluginLocalization localization;
        private final List<JButton> stepTabs = new ArrayList<>();
        private final JPanel cardHost = new JPanel(new CardLayout());
        private final JLabel stepLabel = new JLabel();
        private int currentIndex;

        Session(
                final PluginLocalization localization,
                final AcpQuestion.Request request,
                final java.util.function.Consumer<AcpQuestion.Response> onComplete) {
            this.localization = Objects.requireNonNull(localization, "localization");
            this.root = new JPanel(new BorderLayout(0, 8));
            root.setBorder(BorderFactory.createEmptyBorder(14, 18, 10, 18));

            final JPanel headerBar = new JPanel(new BorderLayout(8, 6));
            headerBar.setOpaque(false);
            if (request.items().size() > 1) {
                final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                tabs.setOpaque(false);
                for (int i = 0; i < request.items().size(); i++) {
                    final int idx = i;
                    final AcpQuestion.Item item = request.items().get(i);
                    final String label =
                            item.header().isEmpty() ? String.valueOf(i + 1) : (i + 1) + ". " + item.header();
                    final JButton tab = new AcpComposerPanel.OptionButton(label);
                    tab.setFont(tab.getFont().deriveFont(Font.PLAIN));
                    tab.setContentAreaFilled(false);
                    tab.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                    tab.putClientProperty("html.disable", true);
                    tab.addActionListener(ignored -> showCard(idx));
                    stepTabs.add(tab);
                    tabs.add(tab);
                }
                headerBar.add(tabs, BorderLayout.CENTER);
                stepLabel.putClientProperty(AcpThemeControls.PRESERVE_FOREGROUND_PROPERTY, Boolean.TRUE);
                stepLabel.setForeground(AcpChatTheme.muted());
                headerBar.add(stepLabel, BorderLayout.EAST);
                root.add(headerBar, BorderLayout.NORTH);
            }

            cardHost.setOpaque(false);
            for (int i = 0; i < request.items().size(); i++) {
                final QuestionCard card =
                        new QuestionCard(localization, request.items().get(i), this::refreshState);
                cards.add(card);
                cardHost.add(card.panel, String.valueOf(i));
            }
            root.add(cardHost, BorderLayout.CENTER);

            cancel.setName(CANCEL_NAME);
            cancel.setText(localization.text("question.cancel"));
            AcpThemeControls.styleButton(cancel, AcpThemeControls.ButtonVariant.SECONDARY);
            cancel.addActionListener(ignored -> onComplete.accept(AcpQuestion.Response.CANCELLED));

            back.setName(BACK_NAME);
            back.setText(localization.text("question.back"));
            AcpThemeControls.styleButton(back, AcpThemeControls.ButtonVariant.SECONDARY);
            back.addActionListener(ignored -> showCard(currentIndex - 1));

            next.setName(NEXT_NAME);
            next.setText(localization.text("question.next"));
            AcpThemeControls.styleButton(next, AcpThemeControls.ButtonVariant.PRIMARY);
            next.addActionListener(ignored -> showCard(currentIndex + 1));

            submit.setName(SUBMIT_NAME);
            submit.setText(localization.text("question.submit"));
            AcpThemeControls.styleButton(submit, AcpThemeControls.ButtonVariant.PRIMARY);
            submit.addActionListener(ignored -> onComplete.accept(collectResponse()));

            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            buttons.setOpaque(false);
            buttons.add(cancel);
            if (request.items().size() > 1) {
                buttons.add(back);
                buttons.add(next);
            }
            buttons.add(submit);
            root.add(buttons, BorderLayout.SOUTH);

            AcpThemeControls.applyThemeTree(root);
            showCard(0);
            refreshState();
        }

        JButton primaryAction() {
            return currentIndex + 1 < cards.size() ? next : submit;
        }

        AcpQuestion.Response collectResponse() {
            final List<AcpQuestion.Answer> answers = new ArrayList<>(cards.size());
            for (QuestionCard card : cards) {
                answers.add(card.toAnswer());
            }
            return new AcpQuestion.Response(false, answers);
        }

        private void showCard(final int index) {
            if (index < 0 || index >= cards.size()) return;
            currentIndex = index;
            ((CardLayout) cardHost.getLayout()).show(cardHost, String.valueOf(index));
            if (cards.size() > 1) {
                stepLabel.setText(localization.format("question.step-of", index + 1, cards.size()));
            }
            for (int i = 0; i < stepTabs.size(); i++) {
                stepTabs.get(i).putClientProperty(AcpComposerPanel.ACTIVE_MODE_PROPERTY, i == index);
                stepTabs.get(i).repaint();
            }
            refreshState();
            cards.get(index).focusDefaultControl();
        }

        private void refreshState() {
            final boolean multiStep = cards.size() > 1;
            final boolean onLast = currentIndex + 1 >= cards.size();
            if (multiStep) {
                back.setEnabled(currentIndex > 0);
                next.setVisible(!onLast);
                next.setEnabled(cards.get(currentIndex).isSatisfied());
            }
            submit.setVisible(!multiStep || onLast);
            boolean allSatisfied = true;
            for (QuestionCard card : cards) {
                if (!card.isSatisfied()) {
                    allSatisfied = false;
                    break;
                }
            }
            submit.setEnabled(allSatisfied);
            final var rootPane = SwingUtilities.getRootPane(root);
            if (rootPane != null) {
                rootPane.setDefaultButton(primaryAction());
            }
        }
    }

    static final class QuestionCard {
        final AcpQuestion.Item item;
        final JPanel panel = new JPanel(new BorderLayout(0, 10));
        final List<JRadioButton> radioButtons = new ArrayList<>();
        final List<JCheckBox> checkBoxes = new ArrayList<>();
        JRadioButton otherRadio;
        final JTextField customField;

        QuestionCard(final PluginLocalization localization, final AcpQuestion.Item item, final Runnable onChange) {
            this.item = item;
            panel.setOpaque(false);

            final JPanel top = new JPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
            top.setOpaque(false);
            if (!item.header().isEmpty()) {
                final JLabel headerLabel = new JLabel(item.header());
                headerLabel.putClientProperty("html.disable", true);
                headerLabel.putClientProperty(AcpThemeControls.PRESERVE_FOREGROUND_PROPERTY, Boolean.TRUE);
                headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
                headerLabel.setForeground(AcpChatTheme.accent());
                headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                top.add(headerLabel);
                top.add(Box.createVerticalStrut(4));
            }
            final JTextArea promptArea = new JTextArea(item.question());
            promptArea.setEditable(false);
            promptArea.setLineWrap(true);
            promptArea.setWrapStyleWord(true);
            promptArea.setOpaque(false);
            promptArea.setBorder(BorderFactory.createEmptyBorder());
            promptArea.setFont(promptArea
                    .getFont()
                    .deriveFont(Font.BOLD, promptArea.getFont().getSize2D() + 1.0F));
            promptArea.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(promptArea);
            panel.add(top, BorderLayout.NORTH);

            final JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);

            final ButtonGroup singleGroup = item.multiSelect() ? null : new ButtonGroup();
            for (AcpQuestion.Option option : item.options()) {
                final JPanel row = new JPanel();
                row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
                row.setOpaque(false);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.setBorder(BorderFactory.createEmptyBorder(3, 0, 5, 4));

                if (item.multiSelect()) {
                    final JCheckBox check = new JCheckBox(option.label());
                    check.putClientProperty("html.disable", true);
                    check.setOpaque(false);
                    check.setFont(check.getFont().deriveFont(Font.BOLD));
                    check.setAlignmentX(Component.LEFT_ALIGNMENT);
                    check.addActionListener(ignored -> onChange.run());
                    checkBoxes.add(check);
                    row.add(check);
                } else {
                    final JRadioButton radio = new JRadioButton(option.label());
                    radio.putClientProperty("html.disable", true);
                    radio.setOpaque(false);
                    radio.setFont(radio.getFont().deriveFont(Font.BOLD));
                    radio.setAlignmentX(Component.LEFT_ALIGNMENT);
                    radio.addActionListener(ignored -> onChange.run());
                    singleGroup.add(radio);
                    radioButtons.add(radio);
                    row.add(radio);
                }

                if (!option.description().isEmpty()) {
                    final JTextArea desc = new JTextArea(option.description());
                    desc.setEditable(false);
                    desc.setLineWrap(true);
                    desc.setWrapStyleWord(true);
                    desc.setOpaque(false);
                    desc.setBorder(BorderFactory.createEmptyBorder(2, 22, 0, 4));
                    desc.putClientProperty(AcpThemeControls.PRESERVE_FOREGROUND_PROPERTY, Boolean.TRUE);
                    desc.setForeground(AcpChatTheme.muted());
                    desc.setAlignmentX(Component.LEFT_ALIGNMENT);
                    row.add(desc);
                }

                if (!option.preview().isEmpty()) {
                    final String slice = option.preview().length() > PREVIEW_MAX_CHARS
                            ? option.preview().substring(0, PREVIEW_MAX_CHARS) + "\n…"
                            : option.preview();
                    final JTextArea preview = new JTextArea(slice);
                    preview.setEditable(false);
                    preview.setLineWrap(true);
                    preview.setWrapStyleWord(false);
                    preview.setFont(new Font(
                            Font.MONOSPACED,
                            Font.PLAIN,
                            Math.max(11, preview.getFont().getSize() - 1)));
                    preview.setBorder(BorderFactory.createCompoundBorder(
                            AcpThemeControls.dynamicLineBorder(), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
                    preview.setAlignmentX(Component.LEFT_ALIGNMENT);
                    row.add(Box.createVerticalStrut(4));
                    row.add(preview);
                }
                body.add(row);
            }

            if (item.allowOther()) {
                final String placeholder = item.placeholder().isEmpty()
                        ? localization.text("question.other-placeholder")
                        : item.placeholder();
                customField =
                        item.secret() ? new JPasswordField(28) : new AcpComposerPanel.PromptSearchField(placeholder);
                AcpThemeControls.styleTextField(customField);
                customField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
                customField.setAlignmentX(Component.LEFT_ALIGNMENT);

                if (!item.options().isEmpty() && !item.multiSelect()) {
                    otherRadio = new JRadioButton(localization.text("question.other-option"));
                    otherRadio.putClientProperty("html.disable", true);
                    otherRadio.setOpaque(false);
                    otherRadio.setAlignmentX(Component.LEFT_ALIGNMENT);
                    singleGroup.add(otherRadio);
                    otherRadio.addActionListener(ignored -> {
                        if (otherRadio.isSelected()) customField.requestFocusInWindow();
                        onChange.run();
                    });
                    body.add(otherRadio);
                    body.add(Box.createVerticalStrut(4));
                }

                customField.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(final DocumentEvent event) {
                        activateOtherOnType();
                    }

                    @Override
                    public void removeUpdate(final DocumentEvent event) {
                        onChange.run();
                    }

                    @Override
                    public void changedUpdate(final DocumentEvent event) {
                        onChange.run();
                    }

                    private void activateOtherOnType() {
                        if (otherRadio != null
                                && !otherRadio.isSelected()
                                && !customText().isEmpty()) {
                            otherRadio.setSelected(true);
                        }
                        onChange.run();
                    }
                });
                body.add(customField);
            } else {
                customField = null;
            }

            if (!item.multiSelect() && !radioButtons.isEmpty() && item.options().size() > 0) {
                radioButtons.get(0).setSelected(true);
            }

            final JScrollPane scroll = new JScrollPane(
                    body, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            AcpThemeControls.configureScrollPane(scroll, true);
            final Dimension pref = body.getPreferredSize();
            scroll.setPreferredSize(new Dimension(460, Math.min(MAX_SCROLL_HEIGHT, Math.max(80, pref.height + 8))));
            panel.add(scroll, BorderLayout.CENTER);
        }

        void focusDefaultControl() {
            if (customField != null && item.options().isEmpty()) {
                customField.requestFocusInWindow();
            } else if (!radioButtons.isEmpty()) {
                radioButtons.get(0).requestFocusInWindow();
            } else if (!checkBoxes.isEmpty()) {
                checkBoxes.get(0).requestFocusInWindow();
            }
        }

        String customText() {
            if (customField == null) return "";
            if (customField instanceof JPasswordField pw) {
                return new String(pw.getPassword()).strip();
            }
            return customField.getText().strip();
        }

        boolean isSatisfied() {
            if (item.allowEmpty()) return true;
            if (item.multiSelect()) {
                for (JCheckBox box : checkBoxes) {
                    if (box.isSelected()) return true;
                }
                return !customText().isEmpty();
            }
            if (otherRadio != null && otherRadio.isSelected()) {
                return !customText().isEmpty();
            }
            for (JRadioButton radio : radioButtons) {
                if (radio.isSelected()) return true;
            }
            return item.options().isEmpty() && !customText().isEmpty();
        }

        AcpQuestion.Answer toAnswer() {
            final List<String> selected = new ArrayList<>();
            if (item.multiSelect()) {
                for (int i = 0; i < checkBoxes.size(); i++) {
                    if (checkBoxes.get(i).isSelected()) {
                        selected.add(item.options().get(i).label());
                    }
                }
                return new AcpQuestion.Answer(item.id(), item.header(), item.question(), selected, customText());
            }
            if (otherRadio != null && otherRadio.isSelected()) {
                return new AcpQuestion.Answer(item.id(), item.header(), item.question(), List.of(), customText());
            }
            for (int i = 0; i < radioButtons.size(); i++) {
                if (radioButtons.get(i).isSelected()) {
                    selected.add(item.options().get(i).label());
                    break;
                }
            }
            final String custom = item.options().isEmpty() ? customText() : "";
            return new AcpQuestion.Answer(item.id(), item.header(), item.question(), selected, custom);
        }
    }
}
