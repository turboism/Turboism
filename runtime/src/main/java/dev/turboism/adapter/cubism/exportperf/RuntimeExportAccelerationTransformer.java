package dev.turboism.adapter.cubism.exportperf;

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
            return ArchiveWriterPatcher.patch(classfileBuffer);
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
