package dev.turboism.adapter.cubism.shader;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Single-owner installer for the verified shader uniform-location reuse hook. */
public final class VerifiedShaderUniformInstaller {
    private static final AtomicBoolean OWNED = new AtomicBoolean();
    private VerifiedShaderUniformInstaller() { }

    /** Owns the transformer registration until closed. */
    public static final class Installation implements AutoCloseable {
        private final Instrumentation instrumentation;
        private final ShaderUniformTransformer transformer;
        private final AtomicBoolean closed = new AtomicBoolean();
        Installation(Instrumentation instrumentation, ShaderUniformTransformer transformer) {
            this.instrumentation = instrumentation;
            this.transformer = transformer;
        }

        /**
         * Returns whether the exact reviewed shader definition was transformed.
         *
         * @return {@code true} when the host shader class was patched on first definition
         */
        public boolean patched() { return transformer.patched(); }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            NativeShaderUniformBridge.enabled(false);
            try { instrumentation.removeTransformer(transformer); }
            catch (RuntimeException ignored) { /* Disabled bridge keeps existing definitions on the official path. */ }
            finally { OWNED.set(false); }
        }
    }

    /**
     * Registers the first-definition transformer and enables the bridge. Returns null on
     * duplicate ownership or when the host shader class was already defined: a late install
     * can never silently patch a retransform.
     *
     * @param instrumentation agent instrumentation
     * @param diagnostic receives {@code SHADER_UNIFORM_*} admission codes; failures are swallowed
     * @return the owned installation, or null when admission is unavailable
     */
    public static Installation install(Instrumentation instrumentation, Consumer<String> diagnostic) {
        java.util.Objects.requireNonNull(instrumentation);
        java.util.Objects.requireNonNull(diagnostic);
        if (!OWNED.compareAndSet(false, true)) return null;
        ShaderUniformTransformer transformer = new ShaderUniformTransformer(diagnostic);
        boolean registered = false;
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals(ShaderUniformTransformer.OWNER.replace('/', '.'))) { OWNED.set(false); return null; }
            }
            instrumentation.addTransformer(transformer, false);
            registered = true;
            NativeShaderUniformBridge.enabled(true);
            return new Installation(instrumentation, transformer);
        } catch (RuntimeException | LinkageError failure) {
            if (registered) {
                try { instrumentation.removeTransformer(transformer); } catch (RuntimeException ignored) { }
            }
            NativeShaderUniformBridge.enabled(false);
            OWNED.set(false);
            return null;
        }
    }
}
