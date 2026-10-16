package dev.turboism.plugin.frameanimation.ui;

import dev.turboism.plugin.frameanimation.domain.AnchorPoint;
import dev.turboism.plugin.frameanimation.domain.DisplayStrategy;
import dev.turboism.plugin.frameanimation.domain.FrameAnimationImportPlan;
import dev.turboism.plugin.frameanimation.domain.FrameItem;
import dev.turboism.plugin.frameanimation.domain.KeyformBindingMode;
import dev.turboism.plugin.frameanimation.domain.ParameterConfig;
import dev.turboism.plugin.frameanimation.domain.SpriteSheetConfig;
import dev.turboism.plugin.frameanimation.slice.SpriteSheetSlicer;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Modal import wizard dialog configuring frame animation parameters, slice options, and placement.
 */
public final class FrameAnimationDialog extends JDialog {

    private final PluginLocalization loc;

    // Sprite sheet tab controls
    private final JTextField txtSheetPath = new JTextField(20);
    private final JSpinner spinRows = new JSpinner(new SpinnerNumberModel(2, 1, 64, 1));
    private final JSpinner spinCols = new JSpinner(new SpinnerNumberModel(4, 1, 64, 1));
    private final JSpinner spinFrameCount = new JSpinner(new SpinnerNumberModel(7, 0, 4096, 1));
    private final JCheckBox chkColMajor = new JCheckBox();
    private final SpriteSheetPreviewPanel previewPanel = new SpriteSheetPreviewPanel();
    private BufferedImage currentSpriteSheet;

    // Image sequence tab controls
    private ImageSequenceListPanel imageListPanel;

    // Shared parameter & placement controls
    private final JTextField txtParamName = new JTextField("Frame Animation", 15);
    private final JTextField txtParamId = new JTextField("PARAM_FRAME", 15);
    private final JCheckBox chkRepeat = new JCheckBox();
    private final JComboBox<DisplayStrategy> comboStrategy = new JComboBox<>(DisplayStrategy.values());
    private final JComboBox<KeyformBindingMode> comboBinding = new JComboBox<>(KeyformBindingMode.values());
    private final JTextField txtPartName = new JTextField("Part_FrameAnim", 15);
    private final JComboBox<AnchorPoint> comboAnchor = new JComboBox<>(AnchorPoint.values());
    private final JSpinner spinDrawOrder = new JSpinner(new SpinnerNumberModel(500, 0, 1000, 10));
    private final JCheckBox chkFlipVertical = new JCheckBox();

    private final JTabbedPane tabbedPane = new JTabbedPane();
    private FrameAnimationImportPlan confirmedPlan;

    public FrameAnimationDialog(final Frame owner, final PluginLocalization localization) {
        super(owner, localization.text("dialog.title"), true);
        TurboismWindowFactory.style(this);
        this.loc = Objects.requireNonNull(localization, "localization");

        buildUi();
        pack();
        setMinimumSize(new Dimension(680, 560));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the modal dialog and returns the confirmed import plan, or {@code null} on cancel.
     */
    public FrameAnimationImportPlan showDialog() {
        setVisible(true);
        return confirmedPlan;
    }

    private void buildUi() {
        final JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // Center Tabs
        tabbedPane.addTab(loc.text("dialog.tab.spriteSheet"), buildSpriteSheetTab());
        imageListPanel = new ImageSequenceListPanel(loc);
        tabbedPane.addTab(loc.text("dialog.tab.images"), imageListPanel);
        root.add(tabbedPane, BorderLayout.NORTH);

        // Options Panel
        root.add(buildOptionsPanel(), BorderLayout.CENTER);

        // Buttons
        root.add(buildButtonPanel(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel buildSpriteSheetTab() {
        final JPanel tab = new JPanel(new BorderLayout(10, 8));
        tab.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        final JPanel form = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.gridx = 0;
        gbc.gridy = 0;

        // File picker
        form.add(new JLabel(loc.text("dialog.file.spriteSheetPath")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        txtSheetPath.setEditable(false);
        form.add(txtSheetPath, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0.0;
        final JButton btnBrowse = new JButton(loc.text("dialog.file.choose"));
        btnBrowse.addActionListener(e -> chooseSpriteSheet());
        form.add(btnBrowse, gbc);

        // Rows
        gbc.gridy++;
        gbc.gridx = 0;
        form.add(new JLabel(loc.text("dialog.grid.rows")), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        spinRows.addChangeListener(e -> refreshPreview());
        form.add(spinRows, gbc);

        // Columns
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        form.add(new JLabel(loc.text("dialog.grid.columns")), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        spinCols.addChangeListener(e -> refreshPreview());
        form.add(spinCols, gbc);

        // Frame Count
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        form.add(new JLabel(loc.text("dialog.grid.frameCount")), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        spinFrameCount.addChangeListener(e -> refreshPreview());
        form.add(spinFrameCount, gbc);

        // Column major
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        chkColMajor.setText(loc.text("dialog.grid.columnMajor"));
        chkColMajor.addActionListener(e -> refreshPreview());
        form.add(chkColMajor, gbc);

        tab.add(form, BorderLayout.CENTER);
        tab.add(previewPanel, BorderLayout.EAST);
        return tab;
    }

    private JPanel buildOptionsPanel() {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(loc.text("dialog.param.title")));
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);

        // Row 0: Param Name & ID
        gbc.gridy = 0;
        gbc.gridx = 0;
        panel.add(new JLabel(loc.text("dialog.param.name")), gbc);
        gbc.gridx = 1;
        panel.add(txtParamName, gbc);

        gbc.gridx = 2;
        panel.add(new JLabel(loc.text("dialog.param.id")), gbc);
        gbc.gridx = 3;
        panel.add(txtParamId, gbc);

        // Row 1: Repeat & Display Strategy
        gbc.gridy = 1;
        gbc.gridx = 0;
        chkRepeat.setSelected(false);
        chkRepeat.setText(loc.text("dialog.param.repeat"));
        panel.add(chkRepeat, gbc);

        gbc.gridx = 2;
        panel.add(new JLabel(loc.text("dialog.strategy.display")), gbc);
        gbc.gridx = 3;
        panel.add(comboStrategy, gbc);

        // Row 2: Binding Mode & Part Name
        gbc.gridy = 2;
        gbc.gridx = 0;
        panel.add(new JLabel(loc.text("dialog.strategy.bindingMode")), gbc);
        gbc.gridx = 1;
        panel.add(comboBinding, gbc);

        gbc.gridx = 2;
        panel.add(new JLabel(loc.text("dialog.placement.partName")), gbc);
        gbc.gridx = 3;
        panel.add(txtPartName, gbc);

        // Row 3: Anchor & Draw Order
        gbc.gridy = 3;
        gbc.gridx = 0;
        panel.add(new JLabel(loc.text("dialog.placement.anchor")), gbc);
        gbc.gridx = 1;
        panel.add(comboAnchor, gbc);

        gbc.gridx = 2;
        panel.add(new JLabel(loc.text("dialog.placement.drawOrder")), gbc);
        gbc.gridx = 3;
        panel.add(spinDrawOrder, gbc);

        // Row 4: Flip vertical
        gbc.gridy = 4;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkFlipVertical.setSelected(false);
        chkFlipVertical.setText(loc.text("dialog.placement.flipVertical"));
        panel.add(chkFlipVertical, gbc);
        gbc.gridwidth = 1;

        return panel;
    }

    private JPanel buildButtonPanel() {
        final JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        final JButton btnImport = new JButton(loc.text("dialog.import"));
        final JButton btnCancel = new JButton(loc.text("dialog.cancel"));

        btnImport.addActionListener(e -> onImportConfirmed());
        btnCancel.addActionListener(e -> dispose());

        p.add(btnImport);
        p.add(btnCancel);
        return p;
    }

    private void chooseSpriteSheet() {
        final JFileChooser chooser = new JFileChooser();
        final File defaultDocs = new File(System.getProperty("user.home"), "Documents");
        if (defaultDocs.exists()) {
            chooser.setCurrentDirectory(defaultDocs);
        }
        chooser.setFileFilter(new FileNameExtensionFilter("PNG Images (*.png)", "png"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            final File f = chooser.getSelectedFile();
            if (f != null && f.exists()) {
                try {
                    currentSpriteSheet = ImageIO.read(f);
                    txtSheetPath.setText(f.getAbsolutePath());
                    refreshPreview();
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(this, "Failed to load sprite sheet: " + e.getMessage());
                }
            }
        }
    }

    private void refreshPreview() {
        if (currentSpriteSheet == null) return;
        final int rows = ((Number) spinRows.getValue()).intValue();
        final int cols = ((Number) spinCols.getValue()).intValue();
        final int fc = ((Number) spinFrameCount.getValue()).intValue();
        final boolean colMajor = chkColMajor.isSelected();

        final SpriteSheetConfig cfg = new SpriteSheetConfig(rows, cols, fc, colMajor);
        previewPanel.update(currentSpriteSheet, cfg);
    }

    private void onImportConfirmed() {
        final List<FrameItem> frames = new ArrayList<>();

        if (tabbedPane.getSelectedIndex() == 0) {
            // Sprite Sheet Mode
            if (currentSpriteSheet == null) {
                JOptionPane.showMessageDialog(this, loc.text("status.noFrames"));
                return;
            }
            final int rows = ((Number) spinRows.getValue()).intValue();
            final int cols = ((Number) spinCols.getValue()).intValue();
            final int fc = ((Number) spinFrameCount.getValue()).intValue();
            final boolean colMajor = chkColMajor.isSelected();
            final SpriteSheetConfig cfg = new SpriteSheetConfig(rows, cols, fc, colMajor);

            frames.addAll(SpriteSheetSlicer.slice(currentSpriteSheet, cfg));
        } else {
            // Image Sequence Mode
            final List<File> files = imageListPanel.getFiles();
            if (files.isEmpty()) {
                JOptionPane.showMessageDialog(this, loc.text("status.noFrames"));
                return;
            }
            for (int i = 0; i < files.size(); i++) {
                final File f = files.get(i);
                try {
                    final BufferedImage img = ImageIO.read(f);
                    if (img != null) {
                        frames.add(new FrameItem(i, f.getName(), img));
                    }
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Failed to read image: " + f.getName());
                    return;
                }
            }
        }

        if (frames.isEmpty()) {
            JOptionPane.showMessageDialog(this, loc.text("status.noFrames"));
            return;
        }

        final String paramId = txtParamId.getText().trim();
        final String paramName = txtParamName.getText().trim();
        final String partName = txtPartName.getText().trim();
        final boolean repeat = chkRepeat.isSelected();
        final DisplayStrategy strategy = (DisplayStrategy) comboStrategy.getSelectedItem();
        final KeyformBindingMode bindingMode = (KeyformBindingMode) comboBinding.getSelectedItem();
        final AnchorPoint anchor = (AnchorPoint) comboAnchor.getSelectedItem();
        final int drawOrder = ((Number) spinDrawOrder.getValue()).intValue();
        final boolean flipVertical = chkFlipVertical.isSelected();

        final ParameterConfig paramCfg = ParameterConfig.ofFrames(paramId, paramName, frames.size(), repeat);
        this.confirmedPlan = new FrameAnimationImportPlan(
                partName, frames, paramCfg, strategy, bindingMode, anchor, 0f, 0f, drawOrder, flipVertical);

        dispose();
    }
}
