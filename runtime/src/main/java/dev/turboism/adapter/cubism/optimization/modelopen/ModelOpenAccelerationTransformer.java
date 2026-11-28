package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.adapter.cubism.optimization.psd.PsdOptimizationTransformer;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Coordinates JVMTI classfile transformations that accelerate Cubism model open and texture loading.
 *
 * <p>Registered during agent initialization to patch GL texture uploads, parallel RGBA texture
 * packing, memory-stream PNG decode, zero-copy bounded parallel pre-decoding, redundant setup
 * elision, explicit GC elision, MethodHandle-backed XML deserialization, primitive array
 * deserialization, buffered archive reading, and zero-copy archive reads across Cubism
 * {@code 5.0.00} through {@code 5.3.05}.
 */
public final class ModelOpenAccelerationTransformer implements ClassFileTransformer {

    /**
     * Constructs the model-open acceleration transformer for agent registration.
     */
    public ModelOpenAccelerationTransformer() {}

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        try {
            if (className.startsWith(DirectTextureUploadPatcher.SHADER_PACKAGE_PREFIX)) {
                return DirectTextureUploadPatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (ImageDecodePatcher.UT_IMAGE.equals(className) || ImageDecodePatcher.IMAGE_RESOURCE.equals(className)) {
                return ImageDecodePatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (ModelOpenFlowPatcher.MODEL_SOURCE_COMPANION.equals(className)
                    || ModelOpenFlowPatcher.DOCUMENT_LOADER.equals(className)) {
                return ModelOpenFlowPatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (FloatArrayDeserializerPatcher.TARGET_INTERNAL_NAME.equals(className)) {
                return FloatArrayDeserializerPatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (IntArrayDeserializerPatcher.TARGET_INTERNAL_NAME.equals(className)) {
                return IntArrayDeserializerPatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (ArchiveReadPatcher.ARCHIVE_READER.equals(className)
                    || ArchiveReadPatcher.GRAPHICS_READ_LAMBDA.equals(className)
                    || ArchiveReadPatcher.SERIALIZE_READ_LAMBDA.equals(className)) {
                return ArchiveReadPatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (BufferedRandomAccessReadPatcher.TARGET.equals(className)) {
                return BufferedRandomAccessReadPatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME.equals(className)) {
                byte[] current = classfileBuffer;
                boolean modified = false;
                final byte[] gcPatched = DeserializeGcElisionPatcher.tryPatch(className, loader, current);
                if (gcPatched != null) {
                    current = gcPatched;
                    modified = true;
                }
                final byte[] streamPatched = StreamingXmlDeserializePatcher.tryPatch(className, loader, current);
                if (streamPatched != null) {
                    current = streamPatched;
                    modified = true;
                }
                return modified ? current : null;
            }
            if (DeserializeReflectFastPatcher.isSupportedTarget(className)) {
                return DeserializeReflectFastPatcher.tryPatch(className, loader, classfileBuffer);
            }
            if (ExtendedInterpolationParallelPatcher.isSupportedTarget(className)) {
                final byte[] extPatched =
                        ExtendedInterpolationParallelPatcher.tryPatch(className, loader, classfileBuffer);
                if (extPatched != null) {
                    return extPatched;
                }
            }
            if (ParameterPaletteCoalescePatcher.isSupportedTarget(className)) {
                final byte[] palettePatched =
                        ParameterPaletteCoalescePatcher.tryPatch(className, loader, classfileBuffer);
                if (palettePatched != null) {
                    return palettePatched;
                }
            }
            if (SaxBuilderFastPatcher.isCandidateTarget(className)) {
                return SaxBuilderFastPatcher.tryPatch(className, loader, classfileBuffer);
            }
            final byte[] psdPatched = PsdOptimizationTransformer.tryTransform(loader, className, classfileBuffer);
            if (psdPatched != null) {
                return psdPatched;
            }
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }
}
