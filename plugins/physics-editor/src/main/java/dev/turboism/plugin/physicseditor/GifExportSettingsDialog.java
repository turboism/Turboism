package dev.turboism.plugin.physicseditor;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginLogger;
import java.awt.Component;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.prefs.BackingStoreException;
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

/**
 * Post-recording export settings, followed by a remembered GIF destination chooser.
 *
 * <p>Feature-owned policy: the scale/pixel/lock model, the target-size limit, the remembered
 * directory and the Gifsicle path live here because they describe this plugin's export, not a
 * framework capability. Every member that touches Swing is called on the event dispatch thread by
 * {@link GifRecordingController}.</p>
 */
final class GifExportSettingsDialog {

    private static final String DIRECTORY_KEY = "lastGifExportDirectory";

    /** The Cubism Editor window title the owner lookup matches, as the SDK progress window does. */
    private static final String EDITOR_TITLE_FRAGMENT = "Live2D Cubism Editor";

    private final PluginLocalization i18n;
    private final PluginLogger logger;
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
    private volatile boolean closed;

    GifExportSettingsDialog(
            final PluginLocalization i18n,
            final PluginLogger logger,
            final int originalWidth,
            final int originalHeight) {
        this.i18n = i18n;
        this.logger = logger;
        this.originalWidth = originalWidth;
        this.originalHeight = originalHeight;
        width = new JSpinner(new SpinnerNumberModel(originalWidth, 1, 32768, 1));
        height = new JSpinner(new SpinnerNumberModel(originalHeight, 1, 32768, 1));
        lock = new JCheckBox(tr("gif.lock"), true);
        optimize = new JCheckBox(tr("gif.optimize"), true);
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
        return i18n.text(key);
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

    Settings show(final String previousFailure) {
        final Component parent = owner();
        final JPanel settings = createSettingsPanel();
        final JPanel content = new JPanel(new java.awt.BorderLayout(0, 8));
        content.add(settings, java.awt.BorderLayout.CENTER);
        content.add(new JLabel(tr("gif.optimization-hint")), java.awt.BorderLayout.SOUTH);
        if (previousFailure != null) content.add(new JLabel(previousFailure), java.awt.BorderLayout.NORTH);

        while (true) {
            if (closed) return null;
            final JOptionPane pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            activeDialog = pane.createDialog(parent, tr("gif.export"));
            try {
                activeDialog.setVisible(true);
            } finally {
                activeDialog.dispose();
                activeDialog = null;
            }
            if (closed || !(pane.getValue() instanceof Integer value) || value != JOptionPane.OK_OPTION) return null;
            try {
                commitSettingsEdits();
                if (executable.getText().isBlank()) throw new IllegalArgumentException(tr("gif.executable-hint"));
                break;
            } catch (java.text.ParseException | IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(parent, tr("gif.invalid"), tr("gif.export"), JOptionPane.ERROR_MESSAGE);
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
            if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif")) {
                destination = destination.resolveSibling(destination.getFileName() + ".gif");
            }
            if (Files.exists(destination)
                    && JOptionPane.showConfirmDialog(
                                    parent, tr("gif.overwrite"), tr("gif.export"), JOptionPane.YES_NO_OPTION)
                            != JOptionPane.YES_OPTION) {
                continue;
            }
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
        addSetting(settings, "gif.scale", percent);
        addSetting(settings, "gif.width", width);
        addSetting(settings, "gif.height", height);
        settings.add(lock);
        settings.add(new JLabel());
        settings.add(optimize);
        settings.add(new JLabel());
        addSetting(settings, "gif.limit", limit);
        addSetting(settings, "gif.executable", executable);
        executable.setToolTipText(tr("gif.executable-hint"));
        return settings;
    }

    private void addSetting(final JPanel settings, final String key, final javax.swing.JComponent input) {
        final JLabel label = new JLabel(tr(key));
        label.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(tr(key));
        settings.add(label);
        settings.add(input);
    }

    /** Disposes the open dialog, if any; safe from any thread. */
    void close() {
        closed = true;
        final javax.swing.JDialog dialog = activeDialog;
        if (dialog != null) dialog.dispose();
    }

    Path lastDirectory() {
        try {
            final String value = preferences().get(DIRECTORY_KEY, "");
            return value.isBlank() ? null : Path.of(value);
        } catch (RuntimeException failure) {
            logger.error("GIF export directory could not be read", failure);
            return null;
        }
    }

    void rememberDirectory(final Path destination) {
        try {
            preferences()
                    .put(DIRECTORY_KEY, destination.toAbsolutePath().getParent().toString());
            preferences().flush();
        } catch (BackingStoreException | RuntimeException failure) {
            logger.error("GIF export directory could not be remembered", failure);
        }
    }

    private static Preferences preferences() {
        return Preferences.userRoot().node("turboism/plugin/physics-editor");
    }

    /**
     * Resolves the window the export dialogs are owned by.
     *
     * <p>A modal dialog needs the Editor as its owner, so it blocks the Editor rather than floating
     * beside it. This is the same two-step lookup the SDK progress window uses: the visible Editor
     * frame, then the active window's root owner.</p>
     */
    static Window owner() {
        for (Frame frame : Frame.getFrames()) {
            if (frame.isShowing() && frame.getTitle().contains(EDITOR_TITLE_FRAGMENT)) return frame;
        }
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        while (active != null && active.getOwner() != null) active = active.getOwner();
        return active;
    }

    /**
     * The chosen export policy.
     *
     * @param destination where the finalized GIF is written
     * @param width the requested pixel width
     * @param height the requested pixel height
     * @param maxBytes the size ceiling, or {@link Long#MAX_VALUE} when optimization is off
     * @param executable the Gifsicle executable to run
     */
    record Settings(Path destination, int width, int height, long maxBytes, String executable) {}
}
