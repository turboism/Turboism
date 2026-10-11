package dev.turboism.adapter.cubism.interpolation;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Hash-admitted installer with an official fallback and a task-only diagnostic overload. */
public final class VerifiedMeshInterpolationInstaller {
    private static final AtomicBoolean OWNED = new AtomicBoolean();
    private VerifiedMeshInterpolationInstaller() { }

    /** Handle over one installed interpolation transformer; closing restores nothing. */
    public static final class Installation implements AutoCloseable {
        private final Instrumentation instrumentation;
        private final MeshInterpolationTransformer transformer;
        private final AtomicBoolean closed = new AtomicBoolean();
        Installation(Instrumentation instrumentation, MeshInterpolationTransformer transformer) {
            this.instrumentation = instrumentation; this.transformer = transformer;
        }
        /**
         * @return {@code true} when the pinned interpolation extension was transformed
         */
        public boolean patched() { return transformer.patched(); }
        /**
         * @return {@code true} when both serialization projection sites were transformed
         */
        public boolean serializationPatched() { return transformer.serializationPatched(); }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            NativeMeshInterpolationBridge.enabled(false);
            NativeMeshInterpolationBridge.coefficientDefault(false);
            DerivedCacheSerializationBridge.enabled(false);
            DerivedCacheSerializationBridge.ready(false);
            DerivedCacheSerializationBridge.modelRootOnly(false);
            DerivedCacheSerializationBridge.gridEnabled(false);
            NativeXmlStreamingBridge.enabled(false);
            try { instrumentation.removeTransformer(transformer); }
            catch (RuntimeException ignored) { /* Disabled bridge keeps existing definitions on the official path. */ }
            finally { OWNED.set(false); }
        }
    }

    /** Returns null on uncertain/late/duplicate admission; normal host startup continues. */
    public static Installation install(Instrumentation instrumentation, Consumer<String> diagnostic) {
        return install(instrumentation,diagnostic,true,Boolean.getBoolean("turboism.meshInterpolation.serializationProjection"),false);
    }

    /** Product serialization admission is independent of experimental interpolation acceleration. */
    public static Installation install(Instrumentation instrumentation,Consumer<String> diagnostic,
            boolean interpolation,boolean serialization) {
        return install(instrumentation,diagnostic,interpolation,serialization,true);
    }
    private static Installation install(Instrumentation instrumentation,Consumer<String> diagnostic,
            boolean interpolation,boolean serialization,boolean productMode) {
        java.util.Objects.requireNonNull(instrumentation);
        java.util.Objects.requireNonNull(diagnostic);
        if (!OWNED.compareAndSet(false, true)) return null;
        if(!interpolation && !serialization) { OWNED.set(false);return null; }
        MeshInterpolationTransformer transformer = new MeshInterpolationTransformer(diagnostic,serialization,interpolation);
        boolean registered = false;
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (interpolation && loaded.getName().equals(NativeMeshInterpolationBridge.EXTENSION)
                        || serialization && (loaded.getName().equals(DerivedCacheSerializationPatch.OWNER.replace('/','.'))
                        || loaded.getName().equals(DerivedCacheSerializationPatch.PROPERTY_OWNER.replace('/','.')))) { OWNED.set(false); return null; }
            }
            instrumentation.addTransformer(transformer, false);
            registered = true;
            NativeMeshInterpolationBridge.enabled(interpolation);
            NativeMeshInterpolationBridge.coefficientDefault(productMode && interpolation);
            DerivedCacheSerializationBridge.ready(false);
            DerivedCacheSerializationBridge.modelRootOnly(productMode);
            DerivedCacheSerializationBridge.enabled(serialization);
            DerivedCacheSerializationBridge.gridEnabled(serialization && (productMode || Boolean.getBoolean("turboism.meshInterpolation.serializationGridProjection")));
            NativeXmlStreamingBridge.enabled(serialization && (productMode || Boolean.getBoolean("turboism.meshInterpolation.serializationStreaming")));
            return new Installation(instrumentation, transformer);
        } catch (RuntimeException | LinkageError failure) {
            if (registered) {
                try { instrumentation.removeTransformer(transformer); } catch (RuntimeException ignored) { }
            }
            NativeMeshInterpolationBridge.enabled(false);
            NativeMeshInterpolationBridge.coefficientDefault(false);
            DerivedCacheSerializationBridge.enabled(false);
            DerivedCacheSerializationBridge.ready(false);
            DerivedCacheSerializationBridge.modelRootOnly(false);
            DerivedCacheSerializationBridge.gridEnabled(false);
            NativeXmlStreamingBridge.enabled(false);
            OWNED.set(false);
            return null;
        }
    }
}
