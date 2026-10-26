package dev.turboism.plugin.modelimport3d.viewer;

import dev.turboism.sdk.ui.UserFileAccessService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Small interactive preview sharing the import scene and the viewer's bounded render worker. */
public final class ModelPreviewPanel extends JPanel implements AutoCloseable {
    private final PreviewCanvas canvas = new PreviewCanvas();
    private final ModelViewerController controller;

    /** Creates an import preview with localized controls; closing it releases its worker. */
    public ModelPreviewPanel(ViewerScene scene, Function<String, String> text, Consumer<Throwable> log) {
        super(new BorderLayout(0, 6));
        setName("model-import-preview");
        var status = new JLabel(text.apply("viewer.hint"), JLabel.CENTER);
        var renderer = new SoftwareModelRenderer();
        controller = new ModelViewerController(
                UserFileAccessService.unavailable(),
                new ModelViewerController.View() {
                    @Override
                    public void status(String message) {
                        status.setText(message);
                    }

                    @Override
                    public void loaded(ViewerScene.Stats stats) {}

                    @Override
                    public void frame(BufferedImage image) {
                        canvas.image = image;
                        canvas.repaint();
                    }
                },
                text,
                log,
                GlbViewerLoader::load,
                (value, yaw, pitch, zoom, geometry) -> renderer.render(value, 256, 240, yaw, pitch, zoom * .65));
        MouseAdapter mouse = new MouseAdapter() {
            int x, y;

            @Override
            public void mousePressed(MouseEvent event) {
                x = event.getX();
                y = event.getY();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                controller.rotate((event.getX() - x) * .01, (event.getY() - y) * .01);
                x = event.getX();
                y = event.getY();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                controller.zoom(Math.pow(1.12, -event.getPreciseWheelRotation()));
            }
        };
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(mouse);
        JButton reset = new JButton(text.apply("viewer.reset"));
        reset.setName("model-import-preview-reset");
        reset.addActionListener(event -> controller.reset());
        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.add(status, BorderLayout.NORTH);
        footer.add(reset, BorderLayout.SOUTH);
        add(canvas, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        controller.load(scene);
    }

    @Override
    public void close() {
        controller.close();
        canvas.image = null;
    }

    private static final class PreviewCanvas extends JPanel {
        BufferedImage image;

        PreviewCanvas() {
            setName("model-import-preview-canvas");
            setPreferredSize(new Dimension(256, 240));
            setBackground(new Color(SoftwareModelRenderer.DEFAULT_BACKGROUND_COLOR, true));
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
