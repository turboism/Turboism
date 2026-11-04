package dev.turboism.adapter.cubism.physics;

import java.awt.Component;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Post-recording export settings, followed by a remembered GIF destination chooser. */
final class PhysicsGifExportDialog {
    private static final String DIRECTORY_KEY = "lastGifExportDirectory";
    private final ResourceBundle messages;
    private final int originalWidth;
    private final int originalHeight;
    private final JSpinner percent = new JSpinner(new SpinnerNumberModel(100.0, 0.001, 3_276_800.0, 1.0));
    private final JSpinner width;
    private final JSpinner height;
    private final JCheckBox lock;
    private final JCheckBox optimize;
    private final JSpinner limit = new JSpinner(new SpinnerNumberModel(20.0, 0.01, 1024.0, 1.0));
    private final JTextField executable = new JTextField(System.getProperty("turboism.gifsicle.path", "gifsicle"), 24);
    private boolean updating;
    private javax.swing.JDialog activeDialog;
    private boolean closed;

    PhysicsGifExportDialog(final ResourceBundle messages, final int originalWidth, final int originalHeight) {
        this.messages = messages;
        this.originalWidth = originalWidth;
        this.originalHeight = originalHeight;
        width = new JSpinner(new SpinnerNumberModel(originalWidth, 1, 32768, 1));
        height = new JSpinner(new SpinnerNumberModel(originalHeight, 1, 32768, 1));
        lock = new JCheckBox(tr("lock"), true);
        optimize = new JCheckBox(tr("optimize"), true);
        percent.addChangeListener(event -> updateFromPercent());
        width.addChangeListener(event -> updateFromPixels(true));
        height.addChangeListener(event -> updateFromPixels(false));
        lock.addActionListener(event -> {
            percent.setEnabled(lock.isSelected());
            updateFromPixels(true);
        });
        optimize.addActionListener(event -> limit.setEnabled(optimize.isSelected()));
    }

    private String tr(final String key) {
        return messages.getString(key);
    }

    private void updateFromPercent() {
        if (updating) return;
        updating = true;
        try {
            final double factor = ((Number) percent.getValue()).doubleValue() / 100;
            width.setValue(clamp(originalWidth * factor));
            height.setValue(clamp(originalHeight * factor));
        } finally {
            updating = false;
        }
    }

    private void updateFromPixels(final boolean widthChanged) {
        if (updating) return;
        updating = true;
        try {
            final int currentWidth = ((Number) width.getValue()).intValue();
            final int currentHeight = ((Number) height.getValue()).intValue();
            final double factor =
                    widthChanged ? (double) currentWidth / originalWidth : (double) currentHeight / originalHeight;
            if (lock.isSelected()) {
                if (widthChanged) height.setValue(clamp(originalHeight * factor));
                else width.setValue(clamp(originalWidth * factor));
            }
            if (lock.isSelected()) percent.setValue(factor * 100);
        } finally {
            updating = false;
        }
    }

    private static int clamp(final double pixels) {
        return (int) Math.max(1, Math.min(32768, Math.round(pixels)));
    }

    Settings show(final Component parent, final String previousFailure) {
        final JPanel settings = createSettingsPanel();
        final JPanel content = new JPanel(new java.awt.BorderLayout(0, 8));
        content.add(settings, java.awt.BorderLayout.CENTER);
        final JLabel hint = new JLabel(tr("optimizationHint"));
        content.add(hint, java.awt.BorderLayout.SOUTH);
        if (previousFailure != null) content.add(new JLabel(previousFailure), java.awt.BorderLayout.NORTH);

        while (true) {
            if (closed) return null;
            final JOptionPane pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            activeDialog = pane.createDialog(parent, tr("export"));
            try {
                activeDialog.setVisible(true);
            } finally {
                activeDialog.dispose();
                activeDialog = null;
            }
            if (closed || !(pane.getValue() instanceof Integer value) || value != JOptionPane.OK_OPTION) return null;
            try {
                commitSettingsEdits();
                if (executable.getText().isBlank()) throw new IllegalArgumentException(tr("executableHint"));
                break;
            } catch (java.text.ParseException | IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(parent, tr("invalid"), tr("export"), JOptionPane.ERROR_MESSAGE);
            }
        }
        final JFileChooser chooser = new JFileChooser() {
            @Override
            protected javax.swing.JDialog createDialog(final Component owner) throws java.awt.HeadlessException {
                activeDialog = super.createDialog(owner);
                return activeDialog;
            }
        };
        final Path previous = lastDirectory();
        if (previous != null && Files.isDirectory(previous)) chooser.setCurrentDirectory(previous.toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("GIF", "gif"));
        chooser.setSelectedFile(new java.io.File(chooser.getCurrentDirectory(), "physics.gif"));
        while (true) {
            if (closed || chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION || closed) return null;
            activeDialog = null;
            Path destination = chooser.getSelectedFile().toPath().toAbsolutePath();
            if (!destination
                    .getFileName()
                    .toString()
                    .toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".gif")) {
                destination = destination.resolveSibling(destination.getFileName() + ".gif");
            }
            if (Files.exists(destination)
                    && JOptionPane.showConfirmDialog(parent, tr("overwrite"), tr("export"), JOptionPane.YES_NO_OPTION)
                            != JOptionPane.YES_OPTION) continue;
            return new Settings(
                    destination,
                    ((Number) width.getValue()).intValue(),
                    ((Number) height.getValue()).intValue(),
                    optimize.isSelected()
                            ? (long) (((Number) limit.getValue()).doubleValue() * 1_000_000)
                            : Long.MAX_VALUE,
                    executable.getText().trim());
        }
    }

    void commitSettingsEdits() throws java.text.ParseException {
        // An inactive percentage must never reapply proportional scaling to custom dimensions.
        if (lock.isSelected()) percent.commitEdit();
        width.commitEdit();
        height.commitEdit();
        limit.commitEdit();
    }

    JPanel createSettingsPanel() {
        final JPanel settings = new JPanel(new GridLayout(0, 2, 8, 8));
        addSetting(settings, "scale", percent);
        addSetting(settings, "width", width);
        addSetting(settings, "height", height);
        settings.add(lock);
        settings.add(new JLabel());
        settings.add(optimize);
        settings.add(new JLabel());
        addSetting(settings, "limit", limit);
        addSetting(settings, "executable", executable);
        executable.setToolTipText(tr("executableHint"));
        return settings;
    }

    private void addSetting(final JPanel settings, final String key, final javax.swing.JComponent input) {
        final JLabel label = new JLabel(tr(key));
        label.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(tr(key));
        settings.add(label);
        settings.add(input);
    }

    void close() {
        closed = true;
        if (activeDialog != null) activeDialog.dispose();
    }

    static Path lastDirectory() {
        try {
            final String value = preferences().get(DIRECTORY_KEY, "");
            return value.isBlank() ? null : Path.of(value);
        } catch (RuntimeException failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "physics-editor", "GIF directory could not be read", failure);
            return null;
        }
    }

    static void rememberDirectory(final Path destination) {
        try {
            preferences()
                    .put(DIRECTORY_KEY, destination.toAbsolutePath().getParent().toString());
            preferences().flush();
        } catch (java.util.prefs.BackingStoreException | RuntimeException failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "physics-editor", "GIF directory could not be remembered", failure);
        }
    }

    private static Preferences preferences() {
        return Preferences.userRoot().node("dev/turboism/physics-gif");
    }

    record Settings(Path destination, int width, int height, long maxBytes, String executable) {}
}
