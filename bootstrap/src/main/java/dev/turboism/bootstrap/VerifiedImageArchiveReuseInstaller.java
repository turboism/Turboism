package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseBridge;
import dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Owns one opt-in, exact-5.3.02 PNG archive optimization and its bytecode restoration. */
final class VerifiedImageArchiveReuseInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.image-archive-reuse";
    private final Instrumentation instrumentation;
    private final Class<?> target;
    private final ImageArchiveReuseTransformer transformer;
    private final ImageArchiveReuseBridge bridge;
    private boolean installed;
    private boolean restored;

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES =
        ReviewedHostContract.candidates(
            ImageArchiveReuseTransformer.reviewedClassSha256(), version -> HOOK_ID);

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config,
                            final boolean requested) {
        return requested && config.hookEnabled(HOOK_ID)
            && ReviewedHostContract.resolved(artifact, CANDIDATES);
    }

    VerifiedImageArchiveReuseInstaller(final Instrumentation instrumentation, final Path artifact,
                                      final ClassLoader hostLoader) throws Exception {
        final var contract = ReviewedHostContract.requireBound(
            ReviewedHostContract.resolve(artifact, CANDIDATES), "image archive reuse");
        this.instrumentation = instrumentation;
        target = Class.forName("com.live2d.graphics.CImageResource",false,hostLoader);
        final Class<?> image = Class.forName("com.live2d.graphics.CWritableImage",false,hostLoader);
        if (target.getClassLoader() != hostLoader || image.getClassLoader() != hostLoader
            || !artifact.toAbsolutePath().normalize().equals(Path.of(target.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("image archive host loader or source mismatch");
        }
        transformer = new ImageArchiveReuseTransformer(hostLoader,artifact);
        bridge = new ImageArchiveReuseBridge(target,image);
        contract.requireUnchanged(artifact);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isRetransformClassesSupported() || !instrumentation.isModifiableClass(target)) {
            throw new IllegalStateException("native image class cannot be transformed");
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer,true);
            installed = true;
            instrumentation.retransformClasses(target);
            if (transformer.matches() != 1 || transformer.failure() != null) {
                throw new IllegalStateException("image archive transformation not admitted: " + transformer.failure());
            }
        } catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            bridge.close(); throw failure;
        }
    }

    @Override public synchronized void close() {
        bridge.close();
        if (!installed) return;
        installed = false;
        instrumentation.removeTransformer(transformer);
        final AtomicReference<String> observed = new AtomicReference<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(Module module,ClassLoader loader,String name,Class<?> redefined,
                                              ProtectionDomain domain,byte[] bytes) {
                if (redefined == target) try {
                    observed.set(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                } catch (Exception failure) { observed.set("unavailable"); }
                return null;
            }
        };
        instrumentation.addTransformer(observer,true);
        try {
            instrumentation.retransformClasses(target);
            restored = transformer.beforeSha256() != null && transformer.beforeSha256().equals(observed.get());
            if (!restored) throw new IllegalStateException("image archive bytecode restoration not proven");
        } catch (java.lang.instrument.UnmodifiableClassException failure) {
            throw new IllegalStateException("image archive bytecode restoration failed",failure);
        } finally {
            instrumentation.removeTransformer(observer);
        }
    }

    boolean restored() { return restored; }
}
