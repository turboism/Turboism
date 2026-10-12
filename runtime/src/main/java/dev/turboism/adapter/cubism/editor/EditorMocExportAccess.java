package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorMocExportSelectorContract;
import dev.turboism.sdk.cubism.model.CubismExportTexture;
import dev.turboism.sdk.cubism.model.CubismMocExport;
import dev.turboism.sdk.cubism.model.CubismMocExportSettings;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Official exporter boundary. No native objects or borrowed texture arrays escape. */
final class EditorMocExportAccess {
    private static final String INSTANCES = "cubism.editor-model.model-source.model-instances";
    private static final String CURRENT = "cubism.editor-model.model-source.current-instance";
    private static final String MANAGER = "cubism.texture-atlas.model-source.texture-manager";
    private static final String ATLASES = "cubism.texture-atlas.texture-manager.atlases";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorMocExportAccess(
            final VerifiedMemberResolver resolver, final EditorParameterCombinedAccess.ModelGuard modelGuard) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    CubismMocExport exportMoc(
            final String identity, final Object source, final Object model, final CubismMocExportSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (EditorMocExportSelectorContract.SUPPORTED_CUBISM_VERSIONS.stream()
                        .noneMatch(resolver::isExactCubismVersion)
                || !resolver.authorizesFeature(
                        EditorMocExportSelectorContract.ADAPTER_SLICE_ID,
                        EditorMocExportSelectorContract.CAPABILITY_ID,
                        EditorMocExportSelectorContract.REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException("Official MOC 5.3 export is unavailable on Cubism "
                    + resolver.cubismVersion() + "; the host exporter has no MOC 5.3 format.");
        }
        return EditorHostThread.dispatch(
                "Cubism official MOC export", () -> exportOnEdt(identity, source, model, settings));
    }

    private CubismMocExport exportOnEdt(
            final String identity, final Object source, final Object model, final CubismMocExportSettings settings) {
        modelGuard.requireCurrent(identity, model);
        final List<?> instances = snapshot(resolver.invoke(INSTANCES, source));
        final Object current = resolver.invoke(CURRENT, source);
        final Object manager = resolver.invoke(MANAGER, source);
        final List<?> atlases = snapshot(resolver.invoke(ATLASES, manager));
        if (atlases.isEmpty() || atlases.size() > CubismMocExport.MAX_TEXTURES) {
            throw new IllegalStateException("Official MOC export requires a bounded, populated texture atlas library.");
        }
        Throwable primary = null;
        try {
            final Object nativeSettings = resolver.construct("cubism.editor-model.official-export.settings-create");
            resolver.invoke(
                    "cubism.editor-model.official-export.set-format",
                    nativeSettings,
                    resolver.readStaticField("cubism.editor-model.official-export.format-530"));
            resolver.invoke("cubism.editor-model.official-export.set-origin-x", nativeSettings, settings.originX());
            resolver.invoke("cubism.editor-model.official-export.set-origin-y", nativeSettings, settings.originY());
            resolver.invoke(
                    "cubism.editor-model.official-export.set-pixels-per-unit",
                    nativeSettings,
                    settings.pixelsPerUnit());
            resolver.invoke(
                    "cubism.editor-model.official-export.set-invisible-part",
                    nativeSettings,
                    settings.includeInvisibleParts());
            resolver.invoke(
                    "cubism.editor-model.official-export.set-sketch-part",
                    nativeSettings,
                    settings.includeSketchParts());
            resolver.invoke(
                    "cubism.editor-model.official-export.set-invisible-mesh",
                    nativeSettings,
                    settings.includeInvisibleMeshes());
            resolver.invoke(
                    "cubism.editor-model.official-export.set-invisible-alias",
                    nativeSettings,
                    settings.includeInvisibleAliases());
            resolver.invoke(
                    "cubism.editor-model.official-export.set-extend-edge", nativeSettings, settings.extendEdgeColors());
            final Object exporter = resolver.construct("cubism.editor-model.official-export.exporter-create");
            resolver.invoke("cubism.editor-model.official-export.exporter-settings", exporter, nativeSettings);
            // The native exporter entry supplies its own progress implementation. The official
            // exporter clones the source before preparing MOC data and texture resources.
            final Object result = resolver.invoke("cubism.editor-model.official-export.export", exporter, source, null);
            if (result == null) throw new IllegalStateException("Official exporter returned no result.");
            return copy(result, settings);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                modelGuard.requireCurrent(identity, model);
                if (resolver.invoke(CURRENT, source) != current
                        || !sameIdentities(instances, snapshot(resolver.invoke(INSTANCES, source)))
                        || resolver.invoke(MANAGER, source) != manager
                        || !sameIdentities(atlases, snapshot(resolver.invoke(ATLASES, manager)))) {
                    throw new IllegalStateException(
                            "Official export changed the active model or its resource membership.");
                }
            } catch (RuntimeException | Error failure) {
                if (primary != null) primary.addSuppressed(failure);
                else throw failure;
            }
        }
    }

    private CubismMocExport copy(final Object result, final CubismMocExportSettings settings) {
        final Object rawBytes = resolver.invoke("cubism.editor-model.official-export.moc-bytes", result);
        if (!(rawBytes instanceof List<?> bytes)) {
            throw new IllegalStateException("Official exporter returned a non-list MOC byte container: "
                    + (rawBytes == null ? "null" : rawBytes.getClass().getName()) + ".");
        }
        final int byteCount = bytes.size();
        if (byteCount < 64 || byteCount > CubismMocExport.MAX_MOC_BYTES) {
            throw new IllegalStateException("Official exporter returned invalid or oversized MOC data: length="
                    + byteCount + ", maximum=" + CubismMocExport.MAX_MOC_BYTES + ".");
        }
        final byte[] moc = new byte[byteCount];
        int index = 0;
        for (Object value : bytes) {
            if (!(value instanceof Byte b) || index >= moc.length) {
                throw new IllegalStateException("Official MOC byte list is malformed.");
            }
            moc[index++] = b;
        }
        if (index != moc.length) throw new IllegalStateException("Official MOC byte count changed.");
        final Object rawTextures = resolver.invoke("cubism.editor-model.official-export.texture-images", result);
        if (!(rawTextures instanceof List<?> images) || images.size() > CubismMocExport.MAX_TEXTURES) {
            throw new IllegalStateException("Official exporter returned invalid or oversized texture data.");
        }
        final List<CubismExportTexture> textures = new ArrayList<>();
        long pixels = 0;
        for (Object image : images) {
            if (textures.size() >= CubismMocExport.MAX_TEXTURES) {
                throw new IllegalStateException("Official texture count changed beyond budget.");
            }
            final int width = dimension(resolver.invoke("cubism.editor-model.image-resource.width", image));
            final int height = dimension(resolver.invoke("cubism.editor-model.image-resource.height", image));
            pixels += (long) width * height;
            if (pixels > CubismMocExport.MAX_TOTAL_TEXTURE_PIXELS) {
                throw new IllegalStateException("Official texture pixel budget exceeded.");
            }
            final Object nativeImage = resolver.invoke("cubism.editor-model.image-resource.image", image);
            final Object rawRaster =
                    resolver.invoke("cubism.editor-model.writable-image.j-buffered-image", nativeImage);
            if (!(rawRaster instanceof BufferedImage raster)
                    || raster.getWidth() != width
                    || raster.getHeight() != height) {
                throw new IllegalStateException("Official texture raster dimensions differ from its resource.");
            }
            // Cached export textures are borrowed from CImageResource, unlike an owned
            // offscreen render. Copy pixels; never flush or modify that native cache.
            textures.add(new CubismExportTexture(width, height, raster.getRGB(0, 0, width, height, null, 0, width)));
        }
        return new CubismMocExport(moc, textures, settings);
    }

    private static int dimension(final Object value) {
        if (!(value instanceof Integer size) || size < 1 || size > CubismExportTexture.MAX_DIMENSION) {
            throw new IllegalStateException("Official texture dimension is invalid.");
        }
        return size;
    }

    private static List<?> snapshot(final Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException("Native model list is unavailable.");
        return new ArrayList<>(list);
    }

    private static boolean sameIdentities(final List<?> left, final List<?> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (left.get(i) != right.get(i)) return false;
        return true;
    }
}
