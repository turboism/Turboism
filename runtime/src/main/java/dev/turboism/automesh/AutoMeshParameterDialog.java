package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.WindowConstants;

/**
 * Schema-driven modal dialog that edits one algorithm's parameters and owns its preset list.
 *
 * <p>The dialog renders what {@link AutoMeshParameterModel} reports and stores nothing itself: every
 * edit goes through the model, which validates it with {@link AutoMeshValueCodec} and keeps the
 * previous value when the input is rejected. Preset changes go through {@link
 * AutoMeshSelectorController}, which persists them, so a renamed preset survives a restart. That
 * split is what makes the whole contract testable without a display.</p>
 *
 * <p>The header mirrors the Editor's own Auto Mesh panel: one algorithm row, one preset row and the
 * preset actions beneath it. Only plain Swing is used, so the host's own look and feel supplies
 * every colour, font and border. Nothing here hardcodes a colour, and the dialog is never shown on a
 * non-EDT thread.</p>
 */
public final class AutoMeshParameterDialog {

    /** Preferred width of the previewed content, in pixels. */
    public static final int PREVIEW_WIDTH = 560;

    /** Preferred height of the previewed content, in pixels. */
    public static final int PREVIEW_HEIGHT = 520;

    private static final String ALGORITHM_KEY = "dialog.parameters.algorithm";
    private static final String PRESET_KEY = "dialog.parameters.preset";

    /**
     * Title of the parameter dialog, also the fallback title of the inline Parameters group.
     *
     * <p>The native Auto Mesh popup offers the same parameters inline, so both surfaces read one
     * key. A second copy of the text could drift from the dialog it names.</p>
     */
    static final String TITLE_KEY = "dialog.parameters.title";

    /** Save action of the current preset, shared with the inline Preset group. */
    static final String PRESET_SAVE_KEY = "dialog.parameters.preset.save";

    /** Add action that names a new preset, shared with the inline Preset group. */
    static final String PRESET_ADD_KEY = "dialog.parameters.preset.add";

    /** Rename action of the current preset, shared with the inline Preset group. */
    static final String PRESET_RENAME_KEY = "dialog.parameters.preset.rename";

    /** Delete action of the current preset, shared with the inline Preset group. */
    static final String PRESET_DELETE_KEY = "dialog.parameters.preset.delete";

    /** Restore action that returns the preset list to the declared one. */
    static final String PRESET_RESTORE_KEY = "dialog.parameters.preset.restoreDefaults";

    /** Prompt that asks for the name of a new preset, shared with the inline Preset group. */
    static final String PRESET_PROMPT_KEY = "dialog.parameters.preset.promptName";

    /** Prompt that asks for the new name of a renamed preset, shared with the inline Preset group. */
    static final String PRESET_RENAME_PROMPT_KEY = "dialog.parameters.preset.promptRename";

    private static final String OK_KEY = "dialog.parameters.ok";
    private static final String CANCEL_KEY = "dialog.parameters.cancel";
    private static final String RESET_KEY = "dialog.parameters.reset";
    private static final String INVALID_KEY = "dialog.parameters.invalid";
    private static final String EMPTY_STATUS = " ";
    private static final int LABEL_COLUMN_WIDTH = 180;
    private static final int DIALOG_MIN_WIDTH = 420;
    private static final int DIALOG_MIN_HEIGHT = 260;
    private static final int ROW_INSET = 4;
    private static final int LABEL_GAP = 12;
    private static final int GROUP_GAP = 8;
    private static final int BUTTON_GAP = 6;
    private static final int NO_PRESET_INDEX = 0;

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
        final Optional<AutoMeshParameterModel> model = controller.parameterModel();
        if (model.isEmpty()) {
            return false;
        }
        final boolean[] accepted = {false};
        final JDialog dialog = new JDialog(parent, AutoMeshText.text(locale, TITLE_KEY));
        dialog.setModal(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        final View view = new View(controller, model.get(), locale);
        view.onAccepted = () -> {
            accepted[0] = true;
            dialog.dispose();
        };
        view.onCancelled = dialog::dispose;
        dialog.getContentPane().add(view.content());
        dialog.setMinimumSize(new Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.getRootPane().setDefaultButton(view.defaultButton());
        dialog.setVisible(true);
        dialog.dispose();
        return accepted[0];
    }

    /**
     * Builds the dialog content without opening a window.
     *
     * <p>A modal window cannot run inside an automated probe, so the same content is laid out and
     * painted directly. The preview is what an acceptance run captures as evidence that the preset
     * bar renders with the host's own look and feel.</p>
     *
     * @param controller the row controller that owns the selection state
     * @param locale the host-effective locale for the framework's own text
     * @return the complete content panel at its preferred size
     */
    public static JPanel buildPreviewPanel(final AutoMeshSelectorController controller, final Locale locale) {
        Objects.requireNonNull(controller, "controller");
        final JPanel preview = new JPanel(new BorderLayout());
        preview.setPreferredSize(new Dimension(PREVIEW_WIDTH, PREVIEW_HEIGHT));
        final Optional<AutoMeshParameterModel> model = controller.parameterModel();
        if (model.isEmpty()) {
            return preview;
        }
        final View view = new View(controller, model.get(), locale);
        view.onAccepted = view::refresh;
        view.onCancelled = view::refresh;
        preview.add(view.content(), BorderLayout.CENTER);
        return preview;
    }

    /** One rendered dialog session, built once for a dialog or for a paint preview. */
    private static final class View {

        private final AutoMeshSelectorController controller;
        private final Locale locale;
        private final JPanel content = new JPanel(new BorderLayout());
        private final JLabel statusLabel = new JLabel(EMPTY_STATUS);
        private final Map<String, AutoMeshParameterControl> controls = new LinkedHashMap<>();
        private final JComboBox<String> presetCombo = new JComboBox<>();
        private final JButton savePresetButton;
        private final JButton renamePresetButton;
        private final JButton deletePresetButton;
        private final JButton restorePresetButton;
        private final JButton okButton;
        private AutoMeshParameterModel model;
        private Runnable onAccepted = () -> {};
        private Runnable onCancelled = () -> {};
        private boolean syncingPresets;

        private View(
                final AutoMeshSelectorController controller, final AutoMeshParameterModel model, final Locale locale) {
            this.controller = controller;
            this.model = model;
            this.locale = locale;
            this.savePresetButton = button(PRESET_SAVE_KEY, this::savePreset);
            this.renamePresetButton = button(PRESET_RENAME_KEY, this::renamePreset);
            this.deletePresetButton = button(PRESET_DELETE_KEY, this::deletePreset);
            this.restorePresetButton = button(PRESET_RESTORE_KEY, this::restorePresets);
            this.okButton = button(OK_KEY, this::accept);
            this.presetCombo.addActionListener(event -> onPresetChosen());
            content.add(buildHeader(), BorderLayout.NORTH);
            content.add(buildRows(), BorderLayout.CENTER);
            content.add(buildFooter(), BorderLayout.SOUTH);
            refreshPresets();
        }

        private JPanel content() {
            return content;
        }

        private JButton defaultButton() {
            return okButton;
        }

        private JPanel buildHeader() {
            final JPanel header = new JPanel(new GridBagLayout());
            header.setBorder(BorderFactory.createEmptyBorder(GROUP_GAP, GROUP_GAP, 0, GROUP_GAP));
            addHeaderRow(
                    header,
                    0,
                    AutoMeshText.text(locale, ALGORITHM_KEY),
                    new JLabel(model.algorithm().displayName()));
            addHeaderRow(header, 1, AutoMeshText.text(locale, PRESET_KEY), presetCombo);
            final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_GAP, 0));
            actions.add(button(PRESET_ADD_KEY, this::addPreset));
            actions.add(savePresetButton);
            actions.add(renamePresetButton);
            actions.add(deletePresetButton);
            actions.add(restorePresetButton);
            final GridBagConstraints constraints = headerConstraints(2);
            constraints.gridwidth = 2;
            header.add(actions, constraints);
            return header;
        }

        private void addHeaderRow(final JPanel header, final int row, final String label, final JComponent component) {
            header.add(new JLabel(label), headerConstraints(row));
            final GridBagConstraints valueConstraints = headerConstraints(row);
            valueConstraints.gridx = 1;
            valueConstraints.weightx = 1.0;
            valueConstraints.fill = GridBagConstraints.HORIZONTAL;
            header.add(component, valueConstraints);
        }

        private static GridBagConstraints headerConstraints(final int row) {
            final GridBagConstraints constraints = new GridBagConstraints();
            constraints.insets = new Insets(ROW_INSET, ROW_INSET, ROW_INSET, LABEL_GAP);
            constraints.anchor = GridBagConstraints.WEST;
            constraints.gridx = 0;
            constraints.gridy = row;
            return constraints;
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

        private JPanel buildFooter() {
            final JPanel footer = new JPanel(new BorderLayout(BUTTON_GAP, 0));
            footer.setBorder(BorderFactory.createEmptyBorder(GROUP_GAP, GROUP_GAP, GROUP_GAP, GROUP_GAP));
            statusLabel.setVisible(false);
            footer.add(statusLabel, BorderLayout.CENTER);
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, BUTTON_GAP, 0));
            buttons.add(button(RESET_KEY, this::reset));
            buttons.add(button(CANCEL_KEY, this::cancel));
            buttons.add(okButton);
            footer.add(buttons, BorderLayout.EAST);
            return footer;
        }

        private JButton button(final String key, final Runnable action) {
            final JButton button = new JButton(AutoMeshText.text(locale, key));
            button.addActionListener(event -> action.run());
            return button;
        }

        private void accept() {
            if (!model.valid()) {
                showStatus(AutoMeshText.text(locale, INVALID_KEY));
                return;
            }
            controller.storeParameters(model.algorithm(), model.values());
            refreshPresets();
            onAccepted.run();
        }

        private void cancel() {
            onCancelled.run();
        }

        private void reset() {
            model.reset();
            refresh();
        }

        /**
         * Applies the preset the user picked in the combo box.
         *
         * <p>The combo lists the user's own presets, so the chosen name is resolved against
         * {@link AutoMeshSelectorController#presets()} instead of the plugin's declaration.</p>
         */
        private void onPresetChosen() {
            if (syncingPresets) {
                return;
            }
            final List<AlgorithmPreset> presets = controller.presets();
            final int position = presetCombo.getSelectedIndex() - 1;
            if (position < 0 || position >= presets.size() || !controller.selectPreset(position + 1)) {
                updatePresetButtons();
                return;
            }
            model.applyPreset(presets, presets.get(position).name());
            refresh();
            refreshPresets();
        }

        private void addPreset() {
            final String name = prompt(AutoMeshText.text(locale, PRESET_PROMPT_KEY), "");
            if (name == null || name.isBlank()) {
                return;
            }
            if (!savePreset(name)) {
                return;
            }
            refreshPresets();
        }

        private void savePreset() {
            selectedPresetName().ifPresent(this::savePreset);
        }

        private boolean savePreset(final String presetName) {
            try {
                controller.savePreset(presetName.trim(), model.values());
            } catch (IllegalArgumentException rejected) {
                showStatus(rejected.getMessage());
                return false;
            }
            refreshPresets();
            return true;
        }

        private void renamePreset() {
            final Optional<String> active = selectedPresetName();
            if (active.isEmpty()) {
                return;
            }
            final String name = prompt(AutoMeshText.text(locale, PRESET_RENAME_PROMPT_KEY), active.get());
            if (name == null || name.isBlank()) {
                return;
            }
            try {
                controller.renamePreset(active.get(), name.trim());
            } catch (IllegalArgumentException rejected) {
                showStatus(rejected.getMessage());
                return;
            }
            refreshPresets();
        }

        private void deletePreset() {
            final Optional<String> active = selectedPresetName();
            if (active.isEmpty() || !controller.deletePreset(active.get())) {
                return;
            }
            adoptStoredValues();
            refreshPresets();
        }

        private void restorePresets() {
            controller.restoreDefaultPresets();
            adoptStoredValues();
            refreshPresets();
        }

        /**
         * Re-seeds the model from the selection state after a preset change replaced the values.
         *
         * <p>The rows keep their descriptors, so only the shown values change; the fresh model also
         * makes "reset" return to the values the new preset carries.</p>
         */
        private void adoptStoredValues() {
            controller.parameterModel().ifPresent(replacement -> model = replacement);
            refresh();
        }

        private void refreshPresets() {
            syncingPresets = true;
            try {
                presetCombo.removeAllItems();
                controller.presetEntries().forEach(presetCombo::addItem);
                presetCombo.setSelectedIndex(controller.selectedPresetIndex());
            } finally {
                syncingPresets = false;
            }
            updatePresetButtons();
        }

        private void updatePresetButtons() {
            final boolean presetSelected = presetCombo.getSelectedIndex() > NO_PRESET_INDEX;
            savePresetButton.setEnabled(presetSelected && model.valid());
            renamePresetButton.setEnabled(presetSelected);
            deletePresetButton.setEnabled(presetSelected);
            restorePresetButton.setEnabled(true);
        }

        private Optional<String> selectedPresetName() {
            final List<AlgorithmPreset> presets = controller.presets();
            final int position = presetCombo.getSelectedIndex() - 1;
            if (position < 0 || position >= presets.size()) {
                return Optional.empty();
            }
            return Optional.of(presets.get(position).name());
        }

        private String prompt(final String message, final String initialValue) {
            return JOptionPane.showInputDialog(content, message, initialValue);
        }

        private void apply(final String parameterId, final String value) {
            if (model.set(parameterId, value)) {
                showStatus(null);
                updatePresetButtons();
                return;
            }
            showStatus(model.rows().stream()
                    .filter(row -> row.parameterId().equals(parameterId))
                    .findFirst()
                    .flatMap(AutoMeshParameterModel.Row::message)
                    .orElse(AutoMeshText.text(locale, INVALID_KEY)));
            updatePresetButtons();
        }

        private void showStatus(final String message) {
            statusLabel.setText(message == null ? EMPTY_STATUS : message);
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
            updatePresetButtons();
        }
    }
}
