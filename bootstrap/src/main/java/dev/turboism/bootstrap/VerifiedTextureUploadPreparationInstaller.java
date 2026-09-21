package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationBridge;
import dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/** Owns exact Editor/JOGL/JVM admission and restoration for native texture preparation. */
final class VerifiedTextureUploadPreparationInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.texture-upload-preparation";
    static final String JOGL_SHA256 = "7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9";
    private final Instrumentation instrumentation;
    private final Class<?> target;
    private final TextureUploadPreparationTransformer transformer;
    private final TextureUploadPreparationBridge bridge;
    private boolean installed;
    private boolean restored;

    static boolean admitted(HostArtifactDigest digest, RuntimeStartupConfig config, boolean requested, int jvmFeature) {
        return requested && jvmFeature == 17 && config.hookEnabled(HOOK_ID) && ReviewedHostArtifacts.CUBISM_5_3_02.equals(digest);
    }

    VerifiedTextureUploadPreparationInstaller(Instrumentation instrumentation, Path artifact, ClassLoader loader) throws Exception {
        if (!ReviewedHostArtifacts.CUBISM_5_3_02.equals(HostArtifactDigest.from(artifact)) || Runtime.version().feature() != 17) {
            throw new IllegalArgumentException("texture preparation requires exact Cubism 5.3.02 on JVM 17");
        }
        Path jogl = artifact.toAbsolutePath().normalize().getParent().resolve("jogl/jogl-all.jar");
        if (!JOGL_SHA256.equals(sha256(jogl))) throw new IllegalArgumentException("texture preparation requires reviewed JOGL artifact");
        this.instrumentation = instrumentation;
        target = Class.forName(TextureUploadPreparationTransformer.TARGET.replace('/', '.'), false, loader);
        Class<?> profile = Class.forName("com.jogamp.opengl.GLProfile", false, loader);
        if (target.getClassLoader() != loader || profile.getClassLoader() != loader
            || !source(target).equals(artifact.toAbsolutePath().normalize()) || !source(profile).equals(jogl)) {
            throw new IllegalArgumentException("texture factory/profile loader or code source mismatch");
        }
        try (JarFile jar = new JarFile(artifact.toFile());
             var input = jar.getInputStream(jar.getJarEntry(TextureUploadPreparationTransformer.TARGET + ".class"))) {
            transformer = new TextureUploadPreparationTransformer(loader, artifact, input.readAllBytes());
        }
        bridge = new TextureUploadPreparationBridge(profile);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isRetransformClassesSupported() || !instrumentation.isModifiableClass(target)) throw new IllegalStateException("texture factory cannot be transformed");
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true); installed = true;
            instrumentation.retransformClasses(target);
            if (transformer.matches() != 1 || transformer.failure() != null) throw new IllegalStateException("texture factory not admitted: " + transformer.failure());
        } catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            bridge.close(); throw failure;
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
                if (redefined == target) try { observed.set(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
                catch (Exception failure) { observed.set("unavailable"); }
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(target);
            restored = transformer.beforeSha256() != null && transformer.beforeSha256().equals(observed.get());
            if (!restored) throw new IllegalStateException("native texture factory restoration not proven");
            installed = false;
        } catch (java.lang.instrument.UnmodifiableClassException failure) {
            throw new IllegalStateException("native texture factory restoration failed", failure);
        } finally { instrumentation.removeTransformer(observer); }
    }

    boolean restored() { return restored; }
    private static Path source(Class<?> type) throws Exception { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize(); }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
