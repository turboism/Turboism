package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTarget;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/**
 * Exact model-update entry admission, dependency-body verification and restoration.
 *
 * <p>Every getter the predicate or probe reads is verified against the official artifact
 * bytecode via {@link ReviewedMethodShape} before the entry method is rewritten; any
 * absent, renamed or changed dependency fails the installation closed. On close the
 * original entry bytes are re-captured and must hash back to the pre-rewrite SHA-256.</p>
 */
final class VerifiedModelUpdateSkipInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.model-update.unchanged-frame-skip";

    private final Instrumentation instrumentation;
    private final ModelUpdateSkipTarget target;
    private final Class<?> entry;
    private final ModelUpdateSkipTransformer transformer;
    private final ModelUpdateSkipBridge bridge;
    private boolean installed, restored;

    static boolean admitted(final HostArtifactDigest digest, final RuntimeStartupConfig config,
                            final boolean requested, final int jvm) {
        return requested && jvm >= 17 && config.hookEnabled(HOOK_ID)
            && ModelUpdateSkipTarget.of(digest).isPresent();
    }

    VerifiedModelUpdateSkipInstaller(final Instrumentation instrumentation, final Path artifact,
                                     final ClassLoader loader) throws Exception {
        target = ModelUpdateSkipTarget.of(HostArtifactDigest.from(artifact))
            .orElseThrow(() -> new IllegalArgumentException(
                "model-update skip unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("model-update skip requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        entry = Class.forName(target.owner().replace('/', '.'), false, loader);
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            attest(entry, loader, artifact);
            transformer = new ModelUpdateSkipTransformer(loader, artifact, reference(jar, entry), target);
            for (final ModelUpdateSkipTarget.Dep dep : target.dependencies()) {
                final Class<?> owner = Class.forName(dep.owner(), false, loader);
                attest(owner, loader, artifact);
                verifyMethod(jar, owner, dep.name(), dep.descriptor(), observed);
            }
        }
        bridge = new ModelUpdateSkipBridge(target, loader);
    }

    private static void attest(final Class<?> type, final ClassLoader loader, final Path artifact)
            throws Exception {
        if (type.getClassLoader() != loader
            || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize().equals(artifact.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("model-update dependency loader/source mismatch");
        }
    }

    private void verifyMethod(final JarFile jar, final Class<?> type, final String name,
                              final String descriptor, final Map<Class<?>, byte[]> observed)
            throws Exception {
        byte[] actual = observed.get(type);
        if (actual == null) {
            actual = capture(type);
            observed.put(type, actual);
        }
        final String owner = type.getName().replace('.', '/');
        final var expected = ReviewedMethodShape.read(reference(jar, type), owner, name, descriptor);
        if (expected == null) {
            // Abstract dependencies have no body to compare; the reviewed table pins the
            // signature and the loaded declaration must still be abstract on the attested class.
            verifyAbstract(type, name, descriptor);
            return;
        }
        if (!expected.equals(ReviewedMethodShape.read(actual, owner, name, descriptor))) {
            throw new IllegalStateException("model-update dependency body changed: "
                + owner + "." + name);
        }
    }

    private static void verifyAbstract(final Class<?> type, final String name,
                                       final String descriptor) throws NoSuchMethodException {
        for (final java.lang.reflect.Method method : type.getMethods()) {
            if (!method.getName().equals(name)) continue;
            final String actual = java.lang.invoke.MethodType.methodType(
                method.getReturnType(), method.getParameterTypes()).descriptorString();
            if (actual.equals(descriptor)
                && java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                return;
            }
        }
        throw new NoSuchMethodException("abstract model-update dependency absent or concrete: "
            + type.getName() + "." + name + descriptor);
    }

    private static byte[] reference(final JarFile jar, final Class<?> type) throws Exception {
        try (var input = jar.getInputStream(jar.getJarEntry(type.getName().replace('.', '/') + ".class"))) {
            return input.readAllBytes();
        }
    }

    private byte[] capture(final Class<?> type) throws Exception {
        if (!instrumentation.isModifiableClass(type)) {
            throw new IllegalStateException("model-update dependency unmodifiable");
        }
        final AtomicReference<byte[]> result = new AtomicReference<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(final Module module, final ClassLoader loader,
                                              final String name, final Class<?> redefined,
                                              final ProtectionDomain domain, final byte[] bytes) {
                if (redefined == type) result.set(bytes.clone());
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(type);
        } finally {
            instrumentation.removeTransformer(observer);
        }
        if (result.get() == null) {
            throw new IllegalStateException("model-update dependency inspection absent");
        }
        return result.get();
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(entry)) {
            throw new IllegalStateException("model-update entry unmodifiable");
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(entry);
            if (transformer.matches() != 1 || transformer.failure() != null) {
                throw new IllegalStateException("model-update entry not admitted: "
                    + transformer.failure());
            }
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            bridge.close();
            throw failure;
        }
    }

    @Override public synchronized void close() {
        bridge.close();
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        try {
            final byte[] original = capture(entry);
            final String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(original));
            restored = hash.equals(transformer.beforeSha256());
            if (!restored) {
                throw new IllegalStateException("native model-update entry restoration not proven");
            }
            installed = false;
        } catch (Exception failure) {
            throw new IllegalStateException("model-update restoration failed", failure);
        }
    }

    boolean restored() {
        return restored;
    }
}
