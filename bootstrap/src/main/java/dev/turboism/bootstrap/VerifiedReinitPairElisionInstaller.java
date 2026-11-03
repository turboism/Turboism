package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.pair.ReinitPairElisionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/** Exact-host admission, one-shot rewrite and verified restoration for the reinit Pair elision. */
final class VerifiedReinitPairElisionInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.model.reinit-pair-elision";
    private final Instrumentation instrumentation;
    private final Class<?> target;
    private final ReinitPairElisionTransformer transformer;
    private boolean installed;
    private boolean restored;

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES =
            ReviewedHostContract.candidates(ReinitPairElisionTransformer.reviewedClassSha256(), version -> HOOK_ID);

    static boolean admitted(Path artifact, RuntimeStartupConfig config, boolean requested) {
        return requested
                && config != null
                && config.hookEnabled(HOOK_ID)
                && ReviewedHostContract.resolved(artifact, CANDIDATES);
    }

    VerifiedReinitPairElisionInstaller(Instrumentation instrumentation, Path artifact, ClassLoader loader)
            throws Exception {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        final Path source =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        final var contract = ReviewedHostContract.requireBound(
                ReviewedHostContract.resolve(source, CANDIDATES), "reinit Pair elision");
        target = Class.forName(ReinitPairElisionTransformer.OWNER.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                target, loader, source, "reinit Pair host loader or artifact mismatch");
        try (JarFile jar = new JarFile(source.toFile())) {
            transformer = new ReinitPairElisionTransformer(
                    loader, source, VerifiedHookInstallerSupport.readReferenceBytes(jar, target));
        }
        contract.requireUnchanged(source);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isRetransformClassesSupported() || !instrumentation.isModifiableClass(target)) {
            throw new IllegalStateException("reinit Pair host cannot be transformed");
        }
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(target);
            if (transformer.matches() != 1 || transformer.failure() != null) {
                throw new IllegalStateException("reinit Pair rewrite was not admitted: " + transformer.failure());
            }
        } catch (Exception | Error failure) {
            // @containment-exempt: close runs cleanup before rethrowing with fatal error prioritized
            try {
                close();
            } catch (Exception | Error cleanup) {
                // @containment-exempt: cleanup failure merged, prioritizing fatal error
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(
                        dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, cleanup));
            }
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        AtomicReference<String> observed = new AtomicReference<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    Module module,
                    ClassLoader loader,
                    String name,
                    Class<?> redefined,
                    ProtectionDomain domain,
                    byte[] bytes) {
                if (redefined == target) {
                    try {
                        observed.set(HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    } catch (Exception failure) {
                        observed.set("unavailable");
                    }
                }
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(target);
            restored = transformer.beforeSha256() != null
                    && transformer.beforeSha256().equals(observed.get());
            if (!restored) throw new IllegalStateException("reinit Pair restoration not proven");
            installed = false;
        } catch (java.lang.instrument.UnmodifiableClassException failure) {
            throw new IllegalStateException("reinit Pair restoration failed", failure);
        } finally {
            instrumentation.removeTransformer(observer);
        }
    }

    boolean restored() {
        return restored;
    }
}
