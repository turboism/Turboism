package dev.turboism.plugin.modelimport3d;

import dev.turboism.plugin.modelimport3d.viewer.ModelPreviewPanel;
import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * One window for choosing a preset and confirming it.
 *
 * <p>The preset list used to appear as its own prompt, and the preview only after the import had
 * been prepared. The choice is the input to that preparation, so it belongs in the window that
 * shows its result: every selection rebuilds the preview and the description, and the operator
 * confirms what is on screen instead of a list of names.</p>
 *
 * <p>Preparation runs on one plugin-owned worker. A newer selection supersedes an older one, and a
 * closing window cancels the task it would have shown.</p>
 */
final class PresetImportDialog implements AutoCloseable {

    /** Builds one preset for the preview; called on the worker thread. */
    @FunctionalInterface
    interface Preparer {
        ModelImportPlugin.PreparedImport prepare(ModelPreset preset) throws Exception;
    }

    /** Describes one prepared import; called on the event thread. */
    @FunctionalInterface
    interface Describer {
        String describe(ModelImportPlugin.PreparedImport prepared) throws Exception;
    }

    private static final int DESCRIPTION_ROWS = 9;

    private final JDialog dialog;
    private final Function<String, String> text;
    private final Consumer<Throwable> log;
    private final Preparer preparer;
    private final Describer describer;
    private final JComboBox<ModelPreset> presets;
    private final JPanel previewHost = new JPanel(new BorderLayout());
    private final JTextArea description = new JTextArea(DESCRIPTION_ROWS, 48);
    private final JButton confirm;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "turboism-import-preset-preview");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();

    private volatile boolean closed;
    private ModelPreviewPanel preview;
    private ModelImportPlugin.PreparedImport prepared;

    PresetImportDialog(
            final Window owner,
            final List<ModelPreset> choices,
            final ModelPreset initial,
            final Preparer preparer,
            final Describer describer,
            final Function<String, String> text,
            final Consumer<Throwable> log) {
        this.text = Objects.requireNonNull(text, "text");
        this.log = Objects.requireNonNull(log, "log");
        this.preparer = Objects.requireNonNull(preparer, "preparer");
        this.describer = Objects.requireNonNull(describer, "describer");
        this.dialog = TurboismWindowFactory.dialog(owner, text.apply("dialog.preset.title"), true);
        if (dialog == null) throw new IllegalStateException("A preset preview needs a graphical environment");
        dialog.setName("model-import-preset");
        this.presets = new JComboBox<>(choices.toArray(ModelPreset[]::new));
        this.presets.setRenderer(new PresetRenderer());
        this.presets.setSelectedItem(initial);
        this.confirm = new JButton(text.apply("dialog.preset.confirm"));
        JButton cancel = new JButton(text.apply("dialog.preset.cancel"));
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setBorder(null);
        description.setName("model-import-preset-description");

        JPanel header = new JPanel(new BorderLayout(8, 0));
        JLabel label = new JLabel(text.apply("dialog.preset.select"));
        label.setLabelFor(presets);
        header.add(label, BorderLayout.WEST);
        header.add(presets, BorderLayout.CENTER);

        previewHost.setPreferredSize(new Dimension(320, 300));
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.add(previewHost, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(description);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        body.add(scroll, BorderLayout.SOUTH);
        body.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JPanel decisions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        decisions.add(cancel);
        decisions.add(confirm);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(header, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        content.add(decisions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(confirm);
        dialog.getRootPane()
                .registerKeyboardAction(
                        event -> dialog.dispose(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        confirm.setEnabled(false);
        confirm.addActionListener(event -> dialog.dispose());
        cancel.addActionListener(event -> dialog.dispose());
        presets.addActionListener(event -> requestPreview());
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        requestPreview();
    }

    /**
     * Shows the window and waits for the operator.
     *
     * @return the confirmed import, or empty when the window closed without a prepared selection
     */
    Optional<ModelImportPlugin.PreparedImport> show() {
        if (closed) return Optional.empty();
        dialog.setVisible(true);
        final ModelImportPlugin.PreparedImport accepted = prepared;
        return confirm.isEnabled() && accepted != null ? Optional.of(accepted) : Optional.empty();
    }

    /** Supersedes one selection: the newest request is the only one whose result is shown. */
    private void requestPreview() {
        final ModelPreset preset = (ModelPreset) presets.getSelectedItem();
        if (preset == null || closed) return;
        final int ticket = generation.incrementAndGet();
        prepared = null;
        confirm.setEnabled(false);
        showPreview(null);
        description.setText(text.apply("dialog.preset.preparing"));
        worker.execute(() -> {
            try {
                final ModelImportPlugin.PreparedImport built = preparer.prepare(preset);
                SwingUtilities.invokeLater(() -> publish(ticket, preset, built, null));
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                SwingUtilities.invokeLater(() -> publish(ticket, preset, null, failure));
            }
        });
    }

    private void publish(
            final int ticket,
            final ModelPreset preset,
            final ModelImportPlugin.PreparedImport built,
            final Throwable failure) {
        if (closed || ticket != generation.get() || presets.getSelectedItem() != preset) return;
        if (failure != null) {
            log.accept(failure);
            prepared = null;
            confirm.setEnabled(false);
            showPreview(null);
            description.setText(text.apply("dialog.preset.failed") + " " + messageOf(failure));
            return;
        }
        try {
            final String summary = describer.describe(built);
            prepared = built;
            showPreview(built.previewScene());
            description.setText(summary);
            description.setCaretPosition(0);
            confirm.setEnabled(true);
        } catch (Exception describeFailure) {
            log.accept(describeFailure);
            prepared = null;
            confirm.setEnabled(false);
            showPreview(null);
            description.setText(text.apply("dialog.preset.failed") + " " + messageOf(describeFailure));
        }
    }

    private void showPreview(final ViewerScene scene) {
        previewHost.removeAll();
        if (preview != null) {
            preview.close();
            preview = null;
        }
        if (scene != null) {
            preview = new ModelPreviewPanel(scene, text, log);
            previewHost.add(preview, BorderLayout.CENTER);
        }
        previewHost.revalidate();
        previewHost.repaint();
    }

    private static String messageOf(final Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        worker.shutdownNow();
        if (preview != null) {
            preview.close();
            preview = null;
        }
        dialog.dispose();
    }

    /** Shows the localized preset name instead of the enum constant. */
    private final class PresetRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(
                final javax.swing.JList<?> list,
                final Object value,
                final int index,
                final boolean selected,
                final boolean focused) {
            final String label =
                    value instanceof ModelPreset preset ? text.apply(preset.labelKey()) : String.valueOf(value);
            return super.getListCellRendererComponent(list, label, index, selected, focused);
        }
    }
}
