package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owned result from the official Editor exporter. Texture order is the MOC texture-index order.
 * No host objects or filesystem destinations escape through this value. Callers choose how to
 * persist the bytes and encode the texture pixels using their own authorized file access.
 * The optional display information contains the official .cdi3.json text.
 */
@CubismEditor(from = "5.3.00")
public record CubismMocExport(
        byte[] moc3,
        List<CubismExportTexture> textures,
        CubismMocExportSettings settings,
        Optional<String> displayInfoJson) {
    public static final int MAX_MOC_BYTES = 256 * 1024 * 1024;
    public static final int MAX_TEXTURES = 32;
    /** Up to two unscaled 4096-square pages (128 MiB of owned ARGB pixels). */
    public static final long MAX_TOTAL_TEXTURE_PIXELS = 33_554_432L;

    public static final int MAX_DISPLAY_INFO_CHARS = 16 * 1024 * 1024;

    /** Creates a result for export settings that request only MOC3 and textures. */
    public CubismMocExport(
            final byte[] moc3, final List<CubismExportTexture> textures, final CubismMocExportSettings settings) {
        this(moc3, textures, settings, Optional.empty());
    }

    public CubismMocExport {
        Objects.requireNonNull(moc3, "moc3");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(displayInfoJson, "displayInfoJson");
        if (displayInfoJson.isPresent() != settings.includeArtMeshDisplayInfo()) {
            throw new IllegalArgumentException("Display information must match the export settings.");
        }
        displayInfoJson.ifPresent(json -> {
            if (json.length() > MAX_DISPLAY_INFO_CHARS || json.isBlank()) {
                throw new IllegalArgumentException("Official export must contain bounded display information.");
            }
        });
        if (moc3.length < 64
                || moc3.length > MAX_MOC_BYTES
                || moc3[0] != 'M'
                || moc3[1] != 'O'
                || moc3[2] != 'C'
                || moc3[3] != '3') {
            throw new IllegalArgumentException("Official export must contain bounded MOC3 bytes: length=" + moc3.length
                    + ", maximum=" + MAX_MOC_BYTES + ".");
        }
        Objects.requireNonNull(textures, "textures");
        if (textures.size() > MAX_TEXTURES) {
            throw new IllegalArgumentException("Export texture count exceeds the budget.");
        }
        textures = List.copyOf(textures);
        long pixels = 0;
        for (CubismExportTexture texture : textures) {
            pixels += (long) texture.width() * texture.height();
        }
        if (pixels > MAX_TOTAL_TEXTURE_PIXELS) {
            throw new IllegalArgumentException("Export total texture pixel budget exceeded.");
        }
        moc3 = moc3.clone();
    }

    @Override
    public byte[] moc3() {
        return moc3.clone();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof CubismMocExport export
                && Arrays.equals(moc3, export.moc3)
                && textures.equals(export.textures)
                && settings.equals(export.settings)
                && displayInfoJson.equals(export.displayInfoJson);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(moc3), textures, settings, displayInfoJson);
    }

    @Override
    public String toString() {
        return "CubismMocExport[bytes=" + moc3.length + ", textures=" + textures.size() + "]";
    }
}
