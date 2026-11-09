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
    private final javax.swing.JComboBox<Format> format = new javax.swing.JComboBox<>(Format.values());
    private boolean updating;
    private javax.swing.JDialog activeDialog;
    private volatile javax.swing.JDialog activePrompt;
    private volatile JFileChooser activeChooser;
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
        optimize.addActionListener(
                event -> limit.setEnabled(format.getSelectedItem() == Format.GIF && optimize.isSelected()));
        format.addActionListener(event -> refreshFormatControls());
    }

    /** APNG needs no optimizer, so the GIF-only controls are disabled while it is selected. */
    private void refreshFormatControls() {
        final boolean gif = format.getSelectedItem() == Format.GIF;
        optimize.setEnabled(gif);
        executable.setEnabled(gif && optimize.isSelected());
        limit.setEnabled(gif && optimize.isSelected());
    }

    Format selectedFormat() {
        return format.getSelectedItem() instanceof Format chosen ? chosen : Format.GIF;
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
                validateForExport();
                break;
            } catch (java.text.ParseException | IllegalArgumentException invalid) {
                showPrompt(parent, tr("gif.invalid"), JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION);
            }
        }
        final JFileChooser chooser = createChooser();
        final Path previous = lastDirectory();
        if (previous != null && Files.isDirectory(previous)) chooser.setCurrentDirectory(previous.toFile());
        final Format chosen = selectedFormat();
        chooser.setFileFilter(new FileNameExtensionFilter(chosen.name(), chosen.extension()));
        chooser.setSelectedFile(new java.io.File(chooser.getCurrentDirectory(), "physics." + chosen.extension()));
        activeChooser = chooser;
        try {
            while (true) {
                if (closed || chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION || closed) return null;
                Path destination = chooser.getSelectedFile().toPath().toAbsolutePath();
                final String suffix = "." + chosen.extension();
                if (!destination
                        .getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT)
                        .endsWith(suffix)) {
                    destination = destination.resolveSibling(destination.getFileName() + suffix);
                }
                if (Files.exists(destination)
                        && showPrompt(parent, tr("gif.overwrite"), JOptionPane.PLAIN_MESSAGE, JOptionPane.YES_NO_OPTION)
                                != JOptionPane.YES_OPTION) {
                    continue;
                }
                return exportSettings(destination);
            }
        } finally {
            activeChooser = null;
        }
    }

    /**
     * Builds the save chooser.
     *
     * <p>The instance must stay exactly {@link JFileChooser}. {@code UIDefaults.getUI} resolves the
     * installed FileChooserUI through the component class loader, and a plugin-defined subclass
     * carries the plugin loader, which cannot see the host look-and-feel class. That lookup failed
     * before this dialog could show the chooser at all.</p>
     *
     * @return a standard chooser owned by the host look and feel
     */
    static JFileChooser createChooser() {
        return new JFileChooser();
    }

    /**
     * Shows one short modal prompt and returns the chosen option.
     *
     * <p>The {@code JOptionPane} convenience methods own a dialog this class cannot reach, so a
     * plugin shutdown during such a prompt left a modal window over the Editor. A tracked prompt
     * lets {@link #close()} dispose it and release the event thread.</p>
     *
     * @return the chosen option, or {@link JOptionPane#CLOSED_OPTION} when no prompt ran
     */
    int showPrompt(final Component parent, final Object message, final int messageType, final int optionType) {
        if (closed) return JOptionPane.CLOSED_OPTION;
        final JOptionPane pane = new JOptionPane(message, messageType, optionType);
        final javax.swing.JDialog dialog = pane.createDialog(parent, tr("gif.export"));
        activePrompt = dialog;
        try {
            dialog.setVisible(true);
        } finally {
            activePrompt = null;
            dialog.dispose();
        }
        return pane.getValue() instanceof Integer value ? value : JOptionPane.CLOSED_OPTION;
    }

    /** Test hook: the prompt window that is waiting for an answer, if any. */
    javax.swing.JDialog activePromptWindow() {
        return activePrompt;
    }

    /**
     * Confirms the edited controls can produce an export before a destination is chosen.
     *
     * <p>Only an optimized export runs Gifsicle, so only that path requires an executable. An
     * unoptimized export re-encodes the recording in process and accepts a blank or unusable path,
     * because the path is never spawned.</p>
     *
     * @throws java.text.ParseException when an edited spinner value is not a number
     * @throws IllegalArgumentException when optimization is on and the executable is blank
     */
    void validateForExport() throws java.text.ParseException {
        commitSettingsEdits();
        if (selectedFormat() == Format.GIF
                && optimize.isSelected()
                && executable.getText().isBlank()) {
            throw new IllegalArgumentException(tr("gif.executable-hint"));
        }
    }

    /**
     * Builds the export policy the recorder applies to the finished recording.
     *
     * @param destination where the finalized GIF is written
     * @return the policy, with an unlimited size ceiling when optimization is off
     */
    Settings exportSettings(final Path destination) {
        final Format chosen = selectedFormat();
        final boolean optimized = chosen == Format.GIF && optimize.isSelected();
        return new Settings(
                destination,
                ((Number) width.getValue()).intValue(),
                ((Number) height.getValue()).intValue(),
                optimized ? (long) (((Number) limit.getValue()).doubleValue() * 1_000_000) : Long.MAX_VALUE,
                optimized ? executable.getText().trim() : "",
                chosen);
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
        addSetting(settings, "gif.format", format);
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
        refreshFormatControls();
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
    /** Disposes every open export window and rejects later prompts; safe from any thread. */
    void close() {
        closed = true;
        final javax.swing.JDialog dialog = activeDialog;
        if (dialog != null) dialog.dispose();
        final javax.swing.JDialog prompt = activePrompt;
        if (prompt != null) prompt.dispose();
        dismissChooser();
    }

    /**
     * Closes the save chooser and the window that shows it.
     *
     * <p>The chooser is a standard component, so the container that shows it is discovered from the
     * component tree instead of a tracked subclass. The close is queued: the chooser window is a
     * nested event loop, so the task runs while that window is showing and disposing it unblocks
     * the caller.</p>
     */
    private void dismissChooser() {
        final JFileChooser chooser = activeChooser;
        if (chooser == null) return;
        javax.swing.SwingUtilities.invokeLater(() -> {
            final Window window = javax.swing.SwingUtilities.getWindowAncestor(chooser);
            if (window != null) window.dispose();
        });
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
    /** Export formats offered by this dialog; both run in process except GIF optimization. */
    enum Format {
        GIF("gif", "gif.format.gif"),
        APNG("png", "gif.format.apng");

        private final String extension;
        private final String labelKey;

        Format(final String extension, final String labelKey) {
            this.extension = extension;
            this.labelKey = labelKey;
        }

        String extension() {
            return extension;
        }

        String labelKey() {
            return labelKey;
        }

        @Override
        public String toString() {
            return labelKey;
        }
    }

    record Settings(Path destination, int width, int height, long maxBytes, String executable, Format format) {}
}
