package dev.turboism.adapter.cubism.optimization.export;

import dev.turboism.adapter.cubism.optimization.save.BufferedRandomAccessPatcher;
import dev.turboism.adapter.cubism.optimization.save.PrimitiveArraySerializerPatcher;
import dev.turboism.adapter.cubism.optimization.save.StreamingXmlArchivePatcher;
import dev.turboism.adapter.cubism.optimization.save.UtImagePngPatcher;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Coordinates JVMTI/agent bytecode acceleration for Cubism runtime file export.
 *
 * <p>Applies structural, version-agnostic optimizations across archive naming, export clone image
 * transfer, multi-page atlas rebuilds, edge processing, downscaling, lossless PNG encoding, and
 * linear-time MOC3 serialization without leaking ASM types in its public API.</p>
 */
public final class RuntimeExportAccelerationTransformer implements ClassFileTransformer {

    /** Constructs the runtime export acceleration transformer. */
    public RuntimeExportAccelerationTransformer() {}

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null || classBeingRedefined != null) {
            return null;
        }
        if (ArchiveWriterPatcher.TARGET.equals(className)) {
            return ArchiveWriterPatcher.patch(loader, classfileBuffer);
        }
        if (BufferedRandomAccessPatcher.TARGET.equals(className)) {
            return BufferedRandomAccessPatcher.tryPatch(className, loader, classfileBuffer);
        }
        if (UtImagePngPatcher.TARGET.equals(className)) {
            return UtImagePngPatcher.tryPatch(className, loader, classfileBuffer);
        }
        if (AtlasParallelPatcher.isSupportedTarget(className)) {
            return AtlasParallelPatcher.patch(className, classfileBuffer);
        }
        if (PngExportPatcher.TARGET.equals(className)) {
            return PngExportPatcher.patch(classfileBuffer);
        }

        byte[] current = classfileBuffer;
        boolean modified = false;

        if (ExportCloneBypassPatcher.isSupportedTarget(className)) {
            final byte[] next = ExportCloneBypassPatcher.patch(className, current);
            if (next != null) {
                current = next;
                modified = true;
            }
        }
        if (PrimitiveArraySerializerPatcher.isSupportedTarget(className)) {
            final byte[] next = PrimitiveArraySerializerPatcher.tryPatch(className, loader, current);
            if (next != null) {
                current = next;
                modified = true;
            }
        }
        if (StreamingXmlArchivePatcher.TARGET.equals(className)) {
            final byte[] next = StreamingXmlArchivePatcher.tryPatch(className, loader, current);
            if (next != null) {
                current = next;
                modified = true;
            }
        }
        if (EdgeAndDownscalePatcher.isSupportedTarget(className)) {
            final byte[] next = EdgeAndDownscalePatcher.patch(className, current);
            if (next != null) {
                current = next;
                modified = true;
            }
        }
        if (MocBuilderPatcher.isSupportedTarget(className)) {
            final byte[] next = MocBuilderPatcher.patch(className, current);
            if (next != null) {
                current = next;
                modified = true;
            }
        }
        return modified ? current : null;
    }
}
