package dev.turboism.plugin.acp;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/** A detached attachment snapshot; files cannot change between preview and submission. */
record AcpAttachment(String name, String mimeType, String uri, String data, int byteSize) {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_COUNT = 8;

    static AcpAttachment fromPath(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Not a regular file: " + path.getFileName());
        final byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        final String mime = Files.probeContentType(path);
        return fromBytes(
                path.getFileName().toString(),
                mime == null ? "application/octet-stream" : mime,
                path.toAbsolutePath().toUri().toString(),
                bytes);
    }

    static AcpAttachment fromClipboard(final Image image) throws IOException {
        final int width = image.getWidth(null), height = image.getHeight(null);
        if (width <= 0 || height <= 0 || (long) width * height > 20_000_000L)
            throw new IOException("Invalid or oversized clipboard image");
        final BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final var graphics = copy.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(copy, "png", output);
        return fromBytes(
                "clipboard.png",
                "image/png",
                "attachment:///" + UUID.randomUUID() + "/clipboard.png",
                output.toByteArray());
    }

    private static AcpAttachment fromBytes(final String name, final String mime, final String uri, final byte[] bytes)
            throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Attachment exceeds 4 MiB: " + name);
        return new AcpAttachment(name, mime, uri, Base64.getEncoder().encodeToString(bytes), bytes.length);
    }

    static AcpAttachment receivedImage(final String mime, final String data) {
        if (mime == null || !mime.startsWith("image/") || data == null || data.length() > MAX_BYTES * 4L / 3 + 8)
            throw new IllegalArgumentException("Invalid or oversized image");
        final byte[] bytes = Base64.getDecoder().decode(data);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Oversized image");
        return new AcpAttachment("image", mime, "", data, bytes.length);
    }

    Map<String, Object> promptBlock(final boolean images, final boolean embedded) {
        if (mimeType.startsWith("image/") && images) return Map.of("type", "image", "mimeType", mimeType, "data", data);
        if (embedded)
            return Map.of("type", "resource", "resource", Map.of("uri", uri, "mimeType", mimeType, "blob", data));
        if (!uri.startsWith("file:"))
            throw new IllegalArgumentException("This agent does not support clipboard images");
        return Map.of("type", "resource_link", "name", name, "uri", uri, "mimeType", mimeType, "size", byteSize);
    }

    ImageIcon preview() throws IOException {
        if (!mimeType.startsWith("image/")) return null;
        final byte[] bytes = Base64.getDecoder().decode(data);
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            final var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image format: " + mimeType);
            final var reader = readers.next();
            try {
                reader.setInput(input);
                final int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 20_000_000L)
                    throw new IOException("Image dimensions exceed preview limit");
                final var parameters = reader.getDefaultReadParam();
                final int sample = Math.max(1, Math.max(width / 560, height / 320));
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                final BufferedImage image = reader.read(0, parameters);
                final double scale = Math.min(1, Math.min(560.0 / image.getWidth(), 320.0 / image.getHeight()));
                return new ImageIcon(image.getScaledInstance(
                        Math.max(1, (int) (image.getWidth() * scale)),
                        Math.max(1, (int) (image.getHeight() * scale)),
                        Image.SCALE_SMOOTH));
            } finally {
                reader.dispose();
            }
        }
    }
}
