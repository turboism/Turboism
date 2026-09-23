package com.live2d.ui.control.a.a;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Offline stand-in with the reviewed binary name of the host's layout scale control. The host keeps
 * its editing buffer in a {@code JTextField} that only joins the component tree while the value
 * label is being edited, and the host enters that edit mode from the label's {@code mouseClicked}
 * handler; the stand-in mirrors exactly that event. The dialog reads {@link #text()} the same way the host OK handler reads its own
 * control. Never enters the production JAR.
 */
public final class j extends JPanel {
    private static final long serialVersionUID = 1L;

    private final JTextField field = new JTextField("100.0");
    private final JLabel label = new JLabel("100.0");

    public j() {
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                removeAll();
                add(field);
                revalidate();
                repaint();
            }
        });
        add(label);
    }

    /** The host OK handler parses this text as a percentage of the original image size. */
    public String text() {
        return field.getText();
    }
}
