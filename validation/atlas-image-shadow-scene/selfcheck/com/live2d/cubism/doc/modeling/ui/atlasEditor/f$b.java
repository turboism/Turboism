package com.live2d.cubism.doc.modeling.ui.atlasEditor;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;

/** Offline-only fake window with the reviewed binary name; never enters the production JAR. */
public final class f$b extends JDialog {
    private static final long serialVersionUID = 1L;
    public static final AtomicInteger OK_CLICKS = new AtomicInteger();
    public static final AtomicInteger SAVE_CLICKS = new AtomicInteger();
    public static final AtomicInteger EXIT_PROMPT_CLICKS = new AtomicInteger();
    public static final AtomicInteger LAYOUT_OPENS = new AtomicInteger();
    public static volatile boolean SHOW_SAVE_PROMPT;
    public static volatile boolean DUPLICATE_OK;
    public static volatile long SLOW_OK_MILLIS;
    public static volatile boolean SHOW_STRAY_DIALOG;
    public static volatile JDialog SAVE_PROMPT;
    public static volatile JDialog STRAY_DIALOG;
    public static volatile long PROGRESS_MILLIS;
    public static volatile JDialog PROGRESS_DIALOG;
    /** NONE, NO_BUTTON (host-shaped three-button pane) or UNMATCHABLE (no Look-and-Feel no-button). */
    public static volatile String EXIT_PROMPT = "NONE";

    public f$b(final Frame owner) {
        super(owner, "offline-editor", true);
        final JButton ok = new JButton("OK");
        ok.addActionListener(event -> onOk());
        add(ok);
        if (DUPLICATE_OK) add(new JButton("OK"));
        final JButton layout = new JButton("自动排版...");
        layout.addActionListener(event -> {
            LAYOUT_OPENS.incrementAndGet();
            new com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f(this).setVisible(true);
        });
        add(layout);
        pack();
    }

    public static void reset() {
        OK_CLICKS.set(0);
        SAVE_CLICKS.set(0);
        EXIT_PROMPT_CLICKS.set(0);
        LAYOUT_OPENS.set(0);
        com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f.reset();
        EXIT_PROMPT = "NONE";
        SHOW_SAVE_PROMPT = false;
        DUPLICATE_OK = false;
        SLOW_OK_MILLIS = 0L;
        PROGRESS_MILLIS = 0L;
        SHOW_STRAY_DIALOG = false;
        disposeStray();
        disposePrompt();
        disposeProgress();
    }

    public static void disposePrompt() {
        final JDialog prompt = SAVE_PROMPT;
        SAVE_PROMPT = null;
        if (prompt != null) prompt.dispose();
    }

    public static void disposeStray() {
        final JDialog stray = STRAY_DIALOG;
        STRAY_DIALOG = null;
        if (stray != null) stray.dispose();
    }

    public static void disposeProgress() {
        final JDialog progress = PROGRESS_DIALOG;
        PROGRESS_DIALOG = null;
        if (progress != null) progress.dispose();
    }

    public static void disposeLayout() {
        com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f.disposeDialogs();
    }

    /** Editors are created lazily by the menu action, so no fixture instance owns them. */
    public static void disposeEditors() {
        for (final Window window : Window.getWindows()) {
            if (window instanceof f$b) window.dispose();
        }
    }

    private void onOk() {
        OK_CLICKS.incrementAndGet();
        // The offline host apply stand-in: the real OK runs the atlas edit apply on the EDT.
        final long slowMillis = SLOW_OK_MILLIS;
        if (slowMillis > 0L) {
            try {
                Thread.sleep(slowMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (PROGRESS_MILLIS > 0L) {
            runProgress(PROGRESS_MILLIS);
            return;
        }
        if (!SHOW_SAVE_PROMPT) {
            dispose();
            if (SHOW_STRAY_DIALOG) showStray();
            return;
        }
        final JDialog prompt = new JDialog(this, "offline-save", Dialog.ModalityType.APPLICATION_MODAL);
        final JButton save = new JButton("Save");
        save.addActionListener(event -> {
            SAVE_CLICKS.incrementAndGet();
            prompt.dispose();
        });
        prompt.add(save);
        prompt.pack();
        SAVE_PROMPT = prompt;
        prompt.setVisible(true);
    }

    /**
     * Host-shaped exit prompt: {@code com.live2d.util.UUOption} shows three buttons built from the
     * Look-and-Feel texts {@code OptionPane.yesButtonText} / {@code noButtonText} /
     * {@code cancelButtonText} inside a modal {@code JOptionPane}. The fixture mirrors that shape so
     * the driver's answer contract is exercised offline instead of only on the host.
     */
    public static void showExitPromptIfConfigured(final Frame owner) {
        final String mode = EXIT_PROMPT;
        if (mode == null || "NONE".equals(mode)) return;
        final Object[] options;
        if ("UNMATCHABLE".equals(mode)) {
            options = new Object[] {new JButton("OK")};
        } else {
            options = new Object[] {
                exitOption("OptionPane.yesButtonText", " (Y)"),
                exitOption("OptionPane.noButtonText", " (N)"),
                exitOption("OptionPane.cancelButtonText", ""),
            };
        }
        if ("UNMATCHABLE".equals(mode)) {
            final Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                javax.swing.SwingUtilities.invokeLater(() -> {
                    for (final Window window : Window.getWindows()) {
                        if (window instanceof JDialog dialog && dialog.isModal()) dialog.dispose();
                    }
                });
            }, "offline-exit-prompt-release");
            releaser.setDaemon(true);
            releaser.start();
        }
        javax.swing.JOptionPane.showOptionDialog(owner, "offline-exit-prompt", "offline-exit",
            javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE,
            null, options, options[0]);
    }

    private static JButton exitOption(final String key, final String suffix) {
        final String label = javax.swing.UIManager.getString(key);
        final JButton button = new JButton((label == null ? key : label) + suffix);
        button.addActionListener(event -> {
            EXIT_PROMPT_CLICKS.incrementAndGet();
            for (final Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isModal()) dialog.dispose();
            }
        });
        return button;
    }

    /** A window that exists only after the scene acted; the post-baseline fail-closed stand-in. */
    private static void showStray() {
        final JDialog stray = new JDialog((Frame) null, "offline-stray",
            Dialog.ModalityType.APPLICATION_MODAL);
        stray.setSize(60, 40);
        STRAY_DIALOG = stray;
        stray.setVisible(true);
    }

    /**
     * Host-shaped apply: the editor stays open while the host shows its own modal progress window and
     * finishes from another thread. The driver must wait this out instead of failing on first sight.
     */
    private void runProgress(final long progressMillis) {
        final JDialog progress = new JDialog(this, "offline-progress",
            Dialog.ModalityType.APPLICATION_MODAL);
        progress.add(new javax.swing.JProgressBar());
        progress.pack();
        PROGRESS_DIALOG = progress;
        final Thread worker = new Thread(() -> {
            try {
                Thread.sleep(progressMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                progress.dispose();
                dispose();
            });
        }, "offline-atlas-apply");
        worker.setDaemon(true);
        worker.start();
        progress.setVisible(true);
    }
}
