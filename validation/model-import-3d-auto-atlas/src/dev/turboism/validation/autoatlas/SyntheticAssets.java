package dev.turboism.validation.autoatlas;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.Point2;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/** Programmatically synthesized procedural assets without proprietary data. */
public final class SyntheticAssets {

    private SyntheticAssets() {}

    public static byte[] generatePng(final int width, final int height, final Color primary, final Color accent) {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        try {
            g.setColor(primary);
            g.fillRect(0, 0, width, height);
            g.setColor(accent);
            for (int x = 0; x < width; x += 32) {
                g.drawLine(x, 0, x, height);
            }
            for (int y = 0; y < height; y += 32) {
                g.drawLine(0, y, width, y);
            }
        } finally {
            g.dispose();
        }
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", baos);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return baos.toByteArray();
    }

    public static AuthorizedImageContent generateAuthorizedImage(
            final String name, final int width, final int height, final Color primary, final Color accent) {
        final byte[] png = generatePng(width, height, primary, accent);
        return AuthorizedImageContent.ofDimensions(png, name, width, height);
    }

    public static ArtMeshGeometry quadGeometry(final float x, final float y, final float width, final float height) {
        final List<Point2> positions = List.of(
                new Point2(x, y),
                new Point2(x + width, y),
                new Point2(x + width, y + height),
                new Point2(x, y + height));
        final List<Point2> uvs = List.of(
                new Point2(0f, 0f),
                new Point2(1f, 0f),
                new Point2(1f, 1f),
                new Point2(0f, 1f));
        final List<Integer> indices = List.of(0, 1, 2, 0, 2, 3);
        return new ArtMeshGeometry(positions, uvs, indices);
    }
}
