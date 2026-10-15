package dev.turboism.plugin.modelimport3d.viewer;

import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.text.MessageFormat;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;

/** Plugin-owned viewer. The Cubism document is never changed. */
public final class ModelViewerWindow implements AutoCloseable {
    private final JFrame frame;
    private final ModelViewerController controller;
    private final JLabel status;
    private final Canvas canvas;

    /** Creates the localized viewer controls and their background session. */
    public ModelViewerWindow(UserFileAccessService files, Function<String, String> text, Consumer<Throwable> log) {
        frame = TurboismWindowFactory.frame(text.apply("viewer.title"));
        if (frame == null) throw new IllegalStateException("Viewer requires a graphical environment");
        frame.setName("turboism-model-viewer");
        status = new JLabel(text.apply("viewer.hint"));
        canvas = new Canvas();
        canvas.setName("model-viewer-canvas");
        controller = new ModelViewerController(
                files,
                new ModelViewerController.View() {
                    @Override
                    public void status(String message) {
                        status.setText(message);
                    }

                    @Override
                    public void loaded(ViewerScene.Stats stats) {
                        status.setText(MessageFormat.format(
                                text.apply("viewer.stats"),
                                stats.meshCount(),
                                stats.triangleCount(),
                                stats.textureCount()));
                    }

                    @Override
                    public void frame(BufferedImage image) {
                        canvas.image = image;
                        canvas.repaint();
                    }
                },
                text,
                log);
        JButton open = new JButton(text.apply("viewer.open"));
        open.setName("model-viewer-open");
        open.addActionListener(event -> controller.choose());
        JButton reset = new JButton(text.apply("viewer.reset"));
        reset.setName("model-viewer-reset");
        reset.addActionListener(event -> controller.reset());
        JCheckBox geometry = new JCheckBox(text.apply("viewer.geometry"));
        geometry.setName("model-viewer-geometry");
        geometry.setToolTipText(text.apply("viewer.geometry-hint"));
        geometry.addActionListener(event -> controller.geometry(geometry.isSelected()));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        controls.add(open);
        controls.add(reset);
        controls.add(geometry);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(status, BorderLayout.NORTH);
        footer.add(new JLabel(text.apply("viewer.hint")), BorderLayout.SOUTH);
        frame.add(controls, BorderLayout.NORTH);
        frame.add(canvas, BorderLayout.CENTER);
        frame.add(footer, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                controller.close();
                canvas.image = null;
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            int lastX, lastY;

            @Override
            public void mousePressed(MouseEvent event) {
                lastX = event.getX();
                lastY = event.getY();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                controller.rotate((event.getX() - lastX) * .01, (event.getY() - lastY) * .01);
                lastX = event.getX();
                lastY = event.getY();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                controller.zoom(Math.pow(1.12, -event.getPreciseWheelRotation()));
            }
        };
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(mouse);
        frame.setSize(760, 820);
        frame.setMinimumSize(new Dimension(440, 480));
        frame.setLocationRelativeTo(null);
    }

    /** Shows and raises this plugin-owned window on the EDT. */
    public void show() {
        frame.setVisible(true);
        frame.toFront();
    }

    /** Reports whether the window session has been closed. */
    public boolean isClosed() {
        return controller.isClosed();
    }

    @Override
    public void close() {
        controller.close();
        if (SwingUtilities.isEventDispatchThread()) {
            canvas.image = null;
            frame.dispose();
        } else
            SwingUtilities.invokeLater(() -> {
                canvas.image = null;
                frame.dispose();
            });
    }

    private static final class Canvas extends JPanel {
        BufferedImage image;

        Canvas() {
            setBackground(new Color(32, 34, 40));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) return;
            double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
            int width = (int) (image.getWidth() * scale), height = (int) (image.getHeight() * scale);
            graphics.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
        }
    }
}
