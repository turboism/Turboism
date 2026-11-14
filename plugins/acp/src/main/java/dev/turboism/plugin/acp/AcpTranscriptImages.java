package dev.turboism.plugin.acp;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;

/** Clipboard and file-export operations for images rendered in the ACP transcript. */
final class AcpTranscriptImages {

    private static final String DEFAULT_IMAGE_STEM = "image";
    private static final String DEFAULT_EXTENSION = "png";

    private AcpTranscriptImages() {}

    /** Decodes the full-resolution attachment image, falling back to the rendered icon. */
    static BufferedImage toBufferedImage(final AcpAttachment attachment, final Icon fallbackIcon) throws IOException {
        if (attachment != null
                && attachment.data() != null
                && !attachment.data().isEmpty()) {
            final byte[] bytes = Base64.getDecoder().decode(attachment.data());
            final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded != null) return decoded;
        }
        if (fallbackIcon != null) {
            return iconToBufferedImage(fallbackIcon);
        }
        throw new IOException("No image data available");
    }

    /** Places {@code image} onto {@code clipboard} using Swing's standard image flavor. */
    static void copyToClipboard(final Image image, final Clipboard clipboard) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(clipboard, "clipboard");
        final Transferable transferable = new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DataFlavor.imageFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(final DataFlavor flavor) {
                return DataFlavor.imageFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
                if (!DataFlavor.imageFlavor.equals(flavor)) throw new UnsupportedFlavorException(flavor);
                return image;
            }
        };
        clipboard.setContents(transferable, null);
    }

    /** Copies {@code image} onto the AWT system clipboard. */
    static void copyToSystemClipboard(final Image image) {
        copyToClipboard(image, Toolkit.getDefaultToolkit().getSystemClipboard());
    }

    /** Returns a clean default file name for Save As dialogs, avoiding raw tool provenance strings. */
    static String suggestedFileName(final AcpAttachment attachment) {
        final String ext = extensionForMime(attachment == null ? null : attachment.mimeType());
        if (attachment == null) return DEFAULT_IMAGE_STEM + "." + ext;
        final String rawName = attachment.name();
        if (rawName == null || rawName.isBlank() || AcpToolImageLabel.isToolImageLabel(rawName)) {
            return DEFAULT_IMAGE_STEM + "." + ext;
        }
        final String leaf = sanitizeFileName(rawName);
        if (leaf.isEmpty() || "image".equals(leaf)) {
            return DEFAULT_IMAGE_STEM + "." + ext;
        }
        return fileExtension(leaf).isEmpty() ? leaf + "." + ext : leaf;
    }

    /** Maps common image MIME types to canonical file extensions. */
    static String extensionForMime(final String mimeType) {
        if (mimeType == null) return DEFAULT_EXTENSION;
        final String normalized = mimeType.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/webp" -> "webp";
            default -> DEFAULT_EXTENSION;
        };
    }

    /** Appends {@code defaultExtension} when the user-selected path has no file extension. */
    static Path ensureExtension(final Path selected, final String defaultExtension) {
        Objects.requireNonNull(selected, "selected");
        final Path fileName = selected.getFileName();
        if (fileName == null) return selected;
        final String name = fileName.toString();
        if (!fileExtension(name).isEmpty()) return selected;
        final String trimmed = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        final String stem = trimmed.isEmpty() ? DEFAULT_IMAGE_STEM : trimmed;
        final String ext = (defaultExtension == null || defaultExtension.isBlank())
                ? DEFAULT_EXTENSION
                : defaultExtension.toLowerCase(Locale.ROOT);
        return selected.resolveSibling(stem + "." + ext);
    }

    /** Writes full-resolution image bytes to {@code target}, transcoding only when format differs. */
    static void saveToFile(final AcpAttachment attachment, final Icon fallbackIcon, final Path target)
            throws IOException {
        Objects.requireNonNull(target, "target");
        final Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        final String targetExt = fileExtension(
                target.getFileName() == null ? "" : target.getFileName().toString());
        if (attachment != null
                && attachment.data() != null
                && !attachment.data().isEmpty()) {
            final String sourceExt = extensionForMime(attachment.mimeType());
            final String normalizedTarget = normalizeWriteFormat(targetExt);
            final String normalizedSource = normalizeWriteFormat(sourceExt);
            if (targetExt.isEmpty()
                    || normalizedTarget.equals(normalizedSource)
                    || !ImageIO.getImageWritersByFormatName(normalizedTarget).hasNext()) {
                Files.write(target, Base64.getDecoder().decode(attachment.data()));
                return;
            }
        }
        final String format = targetExt.isEmpty() ? DEFAULT_EXTENSION : normalizeWriteFormat(targetExt);
        final BufferedImage image = toBufferedImage(attachment, fallbackIcon);
        final BufferedImage writable = requiresOpaqueRgb(format) ? toOpaqueRgb(image) : image;
        if (!ImageIO.write(writable, format, target.toFile())) {
            throw new IOException("Unsupported image format: " + format);
        }
    }

    private static BufferedImage iconToBufferedImage(final Icon icon) throws IOException {
        if (icon instanceof ImageIcon imageIcon && imageIcon.getImage() instanceof BufferedImage buffered) {
            return buffered;
        }
        final int width = icon.getIconWidth();
        final int height = icon.getIconHeight();
        if (width <= 0 || height <= 0) throw new IOException("Empty icon");
        final BufferedImage buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = buffer.createGraphics();
        try {
            if (icon instanceof ImageIcon imageIcon && imageIcon.getImage() != null) {
                g.drawImage(imageIcon.getImage(), 0, 0, null);
            } else {
                icon.paintIcon(null, g, 0, 0);
            }
        } finally {
            g.dispose();
        }
        return buffer;
    }

    private static String sanitizeFileName(final String rawName) {
        final int slash = Math.max(rawName.lastIndexOf('/'), rawName.lastIndexOf('\\'));
        final String leaf = slash >= 0 ? rawName.substring(slash + 1) : rawName;
        return leaf.replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").strip();
    }

    private static String fileExtension(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot >= fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeWriteFormat(final String extension) {
        if ("jpeg".equals(extension)) return "jpg";
        return extension;
    }

    private static boolean requiresOpaqueRgb(final String format) {
        return "jpg".equals(format) || "bmp".equals(format);
    }

    private static BufferedImage toOpaqueRgb(final BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) return source;
        final BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(source, 0, 0, java.awt.Color.WHITE, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }
}
