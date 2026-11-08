package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.WindowConstants;

/**
 * Schema-driven modal dialog that edits one algorithm's parameters.
 *
 * <p>The dialog renders what {@link AutoMeshParameterModel} reports and stores nothing itself: every
 * edit goes through the model, which validates it with {@link AutoMeshValueCodec} and keeps the
 * previous value when the input is rejected. That split is what makes the whole contract testable
 * without a display.</p>
 *
 * <p>Only plain Swing is used, so the host's own look and feel supplies every colour, font and
 * border. Nothing here hardcodes a colour, and the dialog is never shown on a non-EDT thread.</p>
 */
public final class AutoMeshParameterDialog {

    private static final int LABEL_COLUMN_WIDTH = 180;
    private static final int DIALOG_MIN_WIDTH = 420;
    private static final int DIALOG_MIN_HEIGHT = 260;
    private static final int ROW_INSET = 4;
    private static final int LABEL_GAP = 12;
    private static final int GROUP_GAP = 8;
    private static final int BUTTON_GAP = 6;

    private AutoMeshParameterDialog() {}

    /**
     * Shows the dialog and stores the accepted values on OK.
     *
     * <p>The dialog is modal, so it must run on the event dispatch thread. A rejected value blocks
     * OK with an inline message, which is why the dialog can never store a value the algorithm would
     * refuse at run time.</p>
     *
     * @param parent the window the dialog centres on; {@code null} centres on the screen
     * @param controller the row controller that owns the selection state
     * @param locale the host-effective locale for the framework's own text
     * @return {@code true} when the user confirmed and the values were stored
     */
    public static boolean show(final Window parent, final AutoMeshSelectorController controller, final Locale locale) {
        Objects.requireNonNull(controller, "controller");
        final AutoMeshParameterModel model = controller.parameterModel().orElse(null);
        if (model == null) {
            return false;
        }
        return new View(parent, controller, model, locale).open();
    }

    /** One rendered dialog session. */
    private static final class View {

        private final AutoMeshSelectorController controller;
        private final AutoMeshParameterModel model;
        private final Locale locale;
        private final JDialog dialog;
        private final JLabel statusLabel = new JLabel(" ");
        private final Map<String, AutoMeshParameterControl> controls = new LinkedHashMap<>();

        private View(
                final Window parent,
                final AutoMeshSelectorController controller,
                final AutoMeshParameterModel model,
                final Locale locale) {
            this.controller = controller;
            this.model = model;
            this.locale = locale;
            this.dialog = new JDialog(parent, AutoMeshText.text(locale, "dialog.parameters.title"));
            this.dialog.setModal(true);
            this.dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        }

        private boolean open() {
            final boolean[] accepted = {false};
            dialog.getContentPane().add(buildHeader(), BorderLayout.NORTH);
            dialog.getContentPane().add(buildRows(), BorderLayout.CENTER);
            dialog.getContentPane().add(buildFooter(accepted), BorderLayout.SOUTH);
            dialog.setMinimumSize(new Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT));
            dialog.pack();
            dialog.setLocationRelativeTo(dialog.getOwner());
            dialog.setVisible(true);
            dialog.dispose();
            return accepted[0];
        }

        private JPanel buildHeader() {
            final JPanel header = new JPanel(new GridBagLayout());
            final GridBagConstraints constraints = new GridBagConstraints();
            constraints.insets = new Insets(ROW_INSET, ROW_INSET, ROW_INSET, LABEL_GAP);
            constraints.anchor = GridBagConstraints.WEST;
            constraints.gridx = 0;
            constraints.gridy = 0;
            header.add(new JLabel(AutoMeshText.text(locale, "dialog.parameters.algorithm")), constraints);
            constraints.gridx = 1;
            constraints.weightx = 1.0;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            header.add(new JLabel(model.algorithm().displayName()), constraints);
            return header;
        }

        private JScrollPane buildRows() {
            final JPanel rows = new JPanel();
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            rows.setBorder(BorderFactory.createEmptyBorder(GROUP_GAP, GROUP_GAP, GROUP_GAP, GROUP_GAP));
            for (final AutoMeshParameterModel.Row row : model.rows()) {
                rows.add(buildRow(row));
                rows.add(Box.createVerticalStrut(ROW_INSET));
            }
            final JScrollPane scroll = new JScrollPane(rows);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            return scroll;
        }

        private JPanel buildRow(final AutoMeshParameterModel.Row row) {
            final JPanel line = new JPanel(new GridBagLayout());
            final GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.insets = new Insets(0, 0, 0, LABEL_GAP);
            labelConstraints.anchor = GridBagConstraints.WEST;
            labelConstraints.gridx = 0;
            labelConstraints.gridy = 0;
            final JLabel label = new JLabel(row.label());
            label.setPreferredSize(new Dimension(LABEL_COLUMN_WIDTH, label.getPreferredSize().height));
            if (!row.descriptor().description().isEmpty()) {
                label.setToolTipText(row.descriptor().description());
            }
            line.add(label, labelConstraints);

            final AutoMeshParameterControl control = AutoMeshParameterControl.of(
                    row.descriptor(), row.value(), value -> apply(row.parameterId(), value));
            controls.put(row.parameterId(), control);
            final GridBagConstraints controlConstraints = new GridBagConstraints();
            controlConstraints.gridx = 1;
            controlConstraints.gridy = 0;
            controlConstraints.weightx = 1.0;
            controlConstraints.fill = GridBagConstraints.HORIZONTAL;
            controlConstraints.anchor = GridBagConstraints.WEST;
            line.add(control.component(), controlConstraints);
            return line;
        }

        private JPanel buildFooter(final boolean[] accepted) {
            final JPanel footer = new JPanel(new BorderLayout(BUTTON_GAP, 0));
            footer.setBorder(BorderFactory.createEmptyBorder(GROUP_GAP, GROUP_GAP, GROUP_GAP, GROUP_GAP));
            statusLabel.setVisible(false);
            footer.add(statusLabel, BorderLayout.CENTER);
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, BUTTON_GAP, 0));
            final JButton reset = new JButton(AutoMeshText.text(locale, "dialog.parameters.reset"));
            reset.addActionListener(event -> {
                model.reset();
                refresh();
            });
            final JButton cancel = new JButton(AutoMeshText.text(locale, "dialog.parameters.cancel"));
            cancel.addActionListener(event -> dialog.dispose());
            final JButton ok = new JButton(AutoMeshText.text(locale, "dialog.parameters.ok"));
            ok.addActionListener(event -> {
                if (!model.valid()) {
                    showStatus(AutoMeshText.text(locale, "dialog.parameters.invalid"));
                    return;
                }
                controller.storeParameters(model.algorithm(), model.values());
                accepted[0] = true;
                dialog.dispose();
            });
            buttons.add(reset);
            buttons.add(cancel);
            buttons.add(ok);
            footer.add(buttons, BorderLayout.EAST);
            dialog.getRootPane().setDefaultButton(ok);
            return footer;
        }

        private void apply(final String parameterId, final String value) {
            if (model.set(parameterId, value)) {
                showStatus(null);
                return;
            }
            showStatus(model.rows().stream()
                    .filter(row -> row.parameterId().equals(parameterId))
                    .findFirst()
                    .flatMap(AutoMeshParameterModel.Row::message)
                    .orElse(AutoMeshText.text(locale, "dialog.parameters.invalid")));
        }

        private void showStatus(final String message) {
            statusLabel.setText(message == null ? " " : message);
            statusLabel.setVisible(message != null);
        }

        private void refresh() {
            for (final AutoMeshParameterModel.Row row : model.rows()) {
                final AutoMeshParameterControl control = controls.get(row.parameterId());
                if (control != null) {
                    control.show(row.value());
                }
            }
            showStatus(null);
        }
    }
}
