package com.live2d.cubism.doc.modeling.ui.atlasEditor.a;

import com.live2d.ui.control.a.a.j;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JRadioButton;

/**
 * Offline stand-in with the reviewed binary name of the host's automatic-layout dialog: an
 * {@code APPLICATION_MODAL} window whose OK handler reads the scale control's own text field. The
 * real dialog applies the layout to the in-memory model and closes; this stand-in only records what
 * the driver selected so the offline contract can assert it. Never enters the production JAR.
 */
public final class f extends JDialog {
    private static final long serialVersionUID = 1L;

    public static final AtomicInteger OK_CLICKS = new AtomicInteger();
    public static final AtomicInteger CANCEL_CLICKS = new AtomicInteger();
    /** Percentage the stand-in OK handler read from the scale control, or NONE before any OK. */
    public static volatile String APPLIED_PERCENT = "NONE";
    public static volatile boolean FIXED_SCALE_SELECTED;

    public f(final Window owner) {
        super(owner, "offline-auto-layout", ModalityType.APPLICATION_MODAL);
        final JRadioButton automatic = new JRadioButton("自动设置");
        automatic.setSelected(true);
        final JRadioButton fixed = new JRadioButton("用户指定");
        final j scale = new j();
        fixed.addActionListener(event -> FIXED_SCALE_SELECTED = true);
        final JButton ok = new JButton("OK");
        ok.addActionListener(event -> {
            OK_CLICKS.incrementAndGet();
            APPLIED_PERCENT = fixed.isSelected() ? scale.text() : "NONE";
            dispose();
        });
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> {
            CANCEL_CLICKS.incrementAndGet();
            dispose();
        });
        add(automatic);
        add(fixed);
        add(scale);
        add(ok);
        add(cancel);
        pack();
    }

    public static void reset() {
        OK_CLICKS.set(0);
        CANCEL_CLICKS.set(0);
        APPLIED_PERCENT = "NONE";
        FIXED_SCALE_SELECTED = false;
    }

    /** A failed run may leave the modal dialog up; the selfcheck fixture disposes it explicitly. */
    public static void disposeDialogs() {
        for (final Window window : Window.getWindows()) {
            if (window instanceof f dialog) dialog.dispose();
        }
    }
}
