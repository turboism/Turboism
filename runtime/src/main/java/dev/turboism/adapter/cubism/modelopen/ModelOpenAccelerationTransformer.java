package dev.turboism.adapter.cubism.modelopen;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Coordinates JVMTI classfile transformations that accelerate Cubism model open and texture loading.
 *
 * <p>Registered during agent initialization to patch GL texture uploads, memory-stream PNG decode,
 * bounded parallel pre-decoding, redundant setup elision, float array deserialization, and
 * zero-copy archive reads across Cubism {@code 5.0.00} through {@code 5.3.05}.
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
            if (ArchiveReadPatcher.ARCHIVE_READER.equals(className)
                    || ArchiveReadPatcher.GRAPHICS_READ_LAMBDA.equals(className)
                    || ArchiveReadPatcher.SERIALIZE_READ_LAMBDA.equals(className)) {
                return ArchiveReadPatcher.tryPatch(className, loader, classfileBuffer);
            }
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }
}
