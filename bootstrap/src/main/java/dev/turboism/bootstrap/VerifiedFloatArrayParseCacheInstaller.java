package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseBridge;
import dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/** Exact native serializer admission, callback lifetime and verified bytecode restoration. */
final class VerifiedFloatArrayParseCacheInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.float-array-parse-cache";
    private final Instrumentation instrumentation;
    private final Class<?> target;
    private final FloatArrayParseTransformer transformer;
    private final FloatArrayParseBridge bridge = new FloatArrayParseBridge();
    private boolean installed;
    private boolean restored;

    static boolean admitted(HostArtifactDigest digest, RuntimeStartupConfig config, boolean requested) {
        return requested && config.hookEnabled(HOOK_ID) && ReviewedHostArtifacts.CUBISM_5_3_02.equals(digest);
    }

    VerifiedFloatArrayParseCacheInstaller(Instrumentation instrumentation, Path artifact, ClassLoader loader) throws Exception {
        if (!ReviewedHostArtifacts.CUBISM_5_3_02.equals(HostArtifactDigest.from(artifact))) {
            throw new IllegalArgumentException("float array reuse requires exact Cubism 5.3.02");
        }
        this.instrumentation = instrumentation;
        target = Class.forName(FloatArrayParseTransformer.TARGET.replace('/', '.'), false, loader);
        if (target.getClassLoader() != loader || !artifact.toAbsolutePath().normalize().equals(
            Path.of(target.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("float array host loader or artifact mismatch");
        }
        try (JarFile jar = new JarFile(artifact.toFile());
             var input = jar.getInputStream(jar.getJarEntry(FloatArrayParseTransformer.TARGET + ".class"))) {
            transformer = new FloatArrayParseTransformer(loader, artifact, input.readAllBytes());
        }
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isRetransformClassesSupported() || !instrumentation.isModifiableClass(target)) {
            throw new IllegalStateException("native float serializer cannot be transformed");
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(target);
            if (transformer.matches() != 1 || transformer.failure() != null) {
                throw new IllegalStateException("native float serializer not admitted: " + transformer.failure());
            }
        } catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            bridge.close();
            throw failure;
        }
    }

    @Override public synchronized void close() {
        bridge.close();
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        AtomicReference<String> observed = new AtomicReference<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                              ProtectionDomain domain, byte[] bytes) {
                if (redefined == target) try {
                    observed.set(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                } catch (Exception failure) { observed.set("unavailable"); }
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(target);
            restored = transformer.beforeSha256() != null && transformer.beforeSha256().equals(observed.get());
            if (!restored) throw new IllegalStateException("native float serializer restoration not proven");
            installed = false;
        } catch (java.lang.instrument.UnmodifiableClassException failure) {
            throw new IllegalStateException("native float serializer restoration failed", failure);
        } finally {
            instrumentation.removeTransformer(observer);
        }
    }

    boolean restored() { return restored; }
}
