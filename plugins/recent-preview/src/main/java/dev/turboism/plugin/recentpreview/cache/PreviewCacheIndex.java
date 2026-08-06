package dev.turboism.plugin.recentpreview.cache;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Disk cache of bounded preview PNGs plus a no-path index, stored under the plugin's
 * {@link StorageRoot#CACHE} root. The cache key is derived from the opaque
 * {@link RecentFileId} only ({@code SHA-256(id.value())}) — never from the file path —
 * so the layout stays stable across sessions and hosts. Index entries carry the id,
 * display name and image dimensions but intentionally contain no path of any kind;
 * the PNG location is deterministic from the key.
 */
public final class PreviewCacheIndex implements PreviewCache {

    private static final int MAX_THUMBNAIL_SIZE = 150;
    private static final int MAX_INDEXED_FILES = 100;
    private static final int MAX_PNG_BYTES = 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private final PluginStorage storage;

    public PreviewCacheIndex(final PluginStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<PreviewCacheWriteResult> store(
        final RecentFileSummary file,
        final ScreenshotImage image
    ) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(image, "image");
        if (!isReadablePng(image.png(), image.width(), image.height())) {
            return CompletableFuture.completedStage(PreviewCacheWriteResult.IMAGE_WRITE_FAILED);
        }
        final String key = key(file.id().value());
        final StoragePath imagePath = path("recent-preview/images/" + key + ".png");
        final StoragePath indexPath = path("recent-preview/index/" + key + ".entry");
        return storage.writeBytesAtomic(imagePath, image.png()).thenCompose(written -> {
            if (!written.written()) {
                return CompletableFuture.completedStage(PreviewCacheWriteResult.IMAGE_WRITE_FAILED);
            }
            return storage.writeUtf8Atomic(indexPath, entry(file, image)).thenCompose(indexed -> {
                if (indexed.written()) {
                    return CompletableFuture.completedStage(PreviewCacheWriteResult.STORED);
                }
                return storage.delete(imagePath, false).thenApply(
                    ignored -> PreviewCacheWriteResult.INDEX_WRITE_FAILED
                );
            });
        });
    }

    /**
     * Loads the cached PNG bytes for the given recent files, in input order.
     * Corrupt, unbounded, or missing entries are silently skipped.
     */
    public CompletionStage<Map<RecentFileId, byte[]>> loadPng(
        final List<RecentFileSummary> files
    ) {
        Objects.requireNonNull(files, "files");
        CompletionStage<Map<RecentFileId, byte[]>> stage =
            CompletableFuture.completedStage(new LinkedHashMap<>());
        for (RecentFileSummary file : files.stream().limit(MAX_INDEXED_FILES).toList()) {
            stage = stage.thenCompose(loaded -> storage.readBytes(
                path("recent-preview/images/" + key(file.id().value()) + ".png"),
                MAX_PNG_BYTES
            ).thenApply(read -> {
                read.value().filter(bytes -> isReadablePng(bytes, -1, -1))
                    .ifPresent(bytes -> loaded.put(file.id(), bytes));
                return loaded;
            }));
        }
        return stage.thenApply(Map::copyOf);
    }

    /** Deterministic cache key for one recent-file id (SHA-256 of the id value). */
    public static String key(final String recentFileIdValue) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(recentFileIdValue.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The relative cache path of the PNG for one recent-file id. */
    public static String imageRelativePath(final String recentFileIdValue) {
        return "recent-preview/images/" + key(recentFileIdValue) + ".png";
    }

    private static StoragePath path(final String value) {
        return new StoragePath(StorageRoot.CACHE, value);
    }

    private static boolean isReadablePng(
        final byte[] value,
        final int expectedWidth,
        final int expectedHeight
    ) {
        if (!isPng(value)) return false;
        try {
            final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(value));
            return decoded != null
                && decoded.getWidth() > 0
                && decoded.getHeight() > 0
                && decoded.getWidth() <= MAX_THUMBNAIL_SIZE
                && decoded.getHeight() <= MAX_THUMBNAIL_SIZE
                && (expectedWidth < 0 || decoded.getWidth() == expectedWidth)
                && (expectedHeight < 0 || decoded.getHeight() == expectedHeight);
        } catch (java.io.IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isPng(final byte[] value) {
        if (value == null || value.length < PNG_SIGNATURE.length) return false;
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (value[index] != PNG_SIGNATURE[index]) return false;
        }
        return true;
    }

    /** No-path index entry: version, opaque id, display name, decoded dimensions. */
    private static String entry(final RecentFileSummary file, final ScreenshotImage image) {
        return "v2\n"
            + file.id().value() + "\n"
            + file.displayName() + "\n"
            + image.width() + "\n"
            + image.height() + "\n";
    }
}
