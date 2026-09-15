package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTarget;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/**
 * Exact incremental-update admission, dependency-body verification and restoration.
 *
 * <p>Four host classes are rewritten: the updater singleton (three methods) and the three
 * interpolated-form classes (one method each). Every dependency the bridge resolves is
 * verified against the official artifact bytecode via {@link ReviewedMethodShape} before
 * installation; any absent, renamed or changed member fails closed. On close every touched
 * class is re-captured and must hash back to its pre-rewrite SHA-256.</p>
 */
final class VerifiedIncrementalUpdateInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.model-update.incremental";

    private final Instrumentation instrumentation;
    private final IncrementalUpdateTarget target;
    private final Class<?>[] entries;
    private final IncrementalUpdateTransformer transformer;
    private final IncrementalUpdateBridge bridge;
    private boolean installed, restored;

    static boolean admitted(final HostArtifactDigest digest, final RuntimeStartupConfig config,
                            final boolean requested, final int jvm) {
        return requested && jvm == 17 && config.hookEnabled(HOOK_ID)
            && IncrementalUpdateTarget.of(digest).isPresent();
    }

    VerifiedIncrementalUpdateInstaller(final Instrumentation instrumentation,
                                       final Path artifact, final ClassLoader loader)
            throws Exception {
        target = IncrementalUpdateTarget.of(HostArtifactDigest.from(artifact))
            .orElseThrow(() -> new IllegalArgumentException(
                "incremental update unsupported host artifact"));
        if (Runtime.version().feature() != 17) {
            throw new IllegalArgumentException("incremental update requires exact JVM17");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        entries = new Class<?>[] {
            Class.forName(target.updater().replace('/', '.'), false, loader),
            Class.forName(IncrementalUpdateTarget.ROTATION_FORM, false, loader),
            Class.forName(IncrementalUpdateTarget.WARP_FORM, false, loader),
            Class.forName(IncrementalUpdateTarget.MESH_FORM, false, loader),
        };
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final List<byte[]> references = new ArrayList<>(3);
            for (final Class<?> entry : entries) {
                attest(entry, loader, artifact);
                references.add(reference(jar, entry));
            }
            transformer = new IncrementalUpdateTransformer(loader, artifact, references, target);
            for (final IncrementalUpdateTarget.Dep dep : target.dependencies()) {
                final Class<?> owner = Class.forName(dep.owner(), false, loader);
                attest(owner, loader, artifact);
                verifyMethod(jar, owner, dep.name(), dep.descriptor(), observed);
            }
        }
        bridge = new IncrementalUpdateBridge(target, loader);
    }

    private static void attest(final Class<?> type, final ClassLoader loader, final Path artifact)
            throws Exception {
        if (type.getClassLoader() != loader
            || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize().equals(artifact.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("incremental-update dependency loader/source mismatch");
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
            verifyAbstract(type, name, descriptor);
            return;
        }
        if (!expected.equals(ReviewedMethodShape.read(actual, owner, name, descriptor))) {
            throw new IllegalStateException("incremental-update dependency body changed: "
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
        throw new NoSuchMethodException(
            "abstract incremental-update dependency absent or concrete: "
                + type.getName() + "." + name + descriptor);
    }

    private static byte[] reference(final JarFile jar, final Class<?> type) throws Exception {
        try (var input = jar.getInputStream(
                jar.getJarEntry(type.getName().replace('.', '/') + ".class"))) {
            return input.readAllBytes();
        }
    }

    private byte[] capture(final Class<?> type) throws Exception {
        if (!instrumentation.isModifiableClass(type)) {
            throw new IllegalStateException("incremental-update class unmodifiable");
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
            throw new IllegalStateException("incremental-update inspection absent");
        }
        return result.get();
    }

    synchronized void install() throws Exception {
        if (installed) return;
        for (final Class<?> entry : entries) {
            if (!instrumentation.isModifiableClass(entry)) {
                throw new IllegalStateException("incremental-update entry unmodifiable");
            }
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(entries);
            if (transformer.matches() != 6 || transformer.failure() != null
                || transformer.touchedClasses().size() != 4) {
                throw new IllegalStateException("incremental-update entry not admitted: "
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
            for (final String owner : transformer.touchedClasses()) {
                final Class<?> type = Class.forName(owner.replace('/', '.'), false,
                    entries[0].getClassLoader());
                final byte[] original = capture(type);
                final String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(original));
                if (!hash.equals(transformer.beforeSha256(owner))) {
                    throw new IllegalStateException(
                        "native incremental-update restoration not proven: " + owner);
                }
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            throw new IllegalStateException("incremental-update restoration failed", failure);
        }
    }

    boolean restored() {
        return restored;
    }
}
