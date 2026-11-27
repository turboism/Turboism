package dev.turboism.adapter.cubism.optimization.psd;

import dev.turboism.core.runtime.work.FatalErrors;
import java.util.List;

/**
 * Coordinates bytecode transformations accelerating PSD decoding, layer preparation, and image
 * analysis across Cubism {@code 5.0.00} through {@code 5.3.05}.
 */
public final class PsdOptimizationTransformer {

    /** Host PackBits RLE codec internal class name. */
    public static final String RLE_DECODER = PsdRlePatcher.TARGET_INTERNAL_NAME;

    /** Host PSD layer-info reader companion internal class name. */
    public static final String LAYER_INFO_READER = PsdLayerDecodePatcher.LAYER_INFO_READER;

    /** Cubism 5.3.00+ PSD layer model internal class name. */
    public static final String LAYER_MODERN = PsdLayerDecodePatcher.LAYER_MODERN;

    /** Cubism 5.0.00–5.2.03 PSD layer model internal class name. */
    public static final String LAYER_LEGACY = PsdLayerDecodePatcher.LAYER_LEGACY;

    /** Host layered PSD document resource internal class name. */
    public static final String LAYERED_IMAGE = PsdLayerDecodePatcher.LAYERED_IMAGE;

    /** Host PSD layer resource entry internal class name. */
    public static final String LAYER_ENTRY = PsdLayerDecodePatcher.LAYER_ENTRY;

    /** Host image pre-analyze step internal class name. */
    public static final String STEP001_PREANALYZE = PsdAnalysisPatcher.STEP001_PREANALYZE;

    /** Host Laplacian edge-filter internal class name. */
    public static final String EDGE_FILTER = PsdAnalysisPatcher.EDGE_FILTER;

    /** Host 3x3 edge-blur step internal class name. */
    public static final String STEP003_BLUR = PsdAnalysisPatcher.STEP003_BLUR;

    /** Ordered list of fixed PSD optimization targets present in all supported Cubism versions. */
    public static final List<String> FIXED_TARGETS = List.of(
            RLE_DECODER, LAYER_INFO_READER, LAYERED_IMAGE, LAYER_ENTRY, STEP001_PREANALYZE, EDGE_FILTER, STEP003_BLUR);

    private PsdOptimizationTransformer() {}

    /**
     * Transforms a candidate PSD or image-analysis host class during agent class loading.
     *
     * @param loader host class loader loading {@code className}
     * @param className JVM internal binary name of the candidate class
     * @param classfileBuffer original classfile bytes
     * @return transformed classfile bytes, or {@code null} when untouched
     */
    public static byte[] tryTransform(final ClassLoader loader, final String className, final byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        try {
            if (PsdRlePatcher.TARGET_INTERNAL_NAME.equals(className)) {
                return PsdRlePatcher.tryPatch(loader, classfileBuffer);
            }
            if (PsdLayerDecodePatcher.isSupportedTarget(className)) {
                return PsdLayerDecodePatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (PsdAnalysisPatcher.isSupportedTarget(className)) {
                return PsdAnalysisPatcher.tryPatch(className, loader, classfileBuffer);
            }
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }
}
