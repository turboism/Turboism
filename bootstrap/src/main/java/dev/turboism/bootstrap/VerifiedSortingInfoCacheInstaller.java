package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.sorting.MeshContainerLookupTransformer;
import dev.turboism.adapter.cubism.optimization.sorting.SortingInfoCacheTarget;
import dev.turboism.adapter.cubism.optimization.sorting.SortingInfoCacheTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/**
 * Exact-host admission, rewrite and verified restoration for the skipped-frame
 * sorting-info cache and allocation-free {@code GMeshRenderer.getMeshContainer()} lookup.
 */
final class VerifiedSortingInfoCacheInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.sorting-info-cache";
    static final String ENABLE_PROPERTY = "turboism.optimization.sortingInfoCache";

    private final Instrumentation instrumentation;
    private final Class<?> sortingOwner;
    private final Class<?> meshRendererOwner;
    private final SortingInfoCacheTransformer sortingTransformer;
    private final MeshContainerLookupTransformer containerTransformer;
    private boolean installed;
    private boolean restored;

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws java.io.IOException {
        return requested
                && config != null
                && config.hookEnabled(HOOK_ID)
                && SortingInfoCacheTarget.of(HostArtifactDigest.from(artifact)).isPresent();
    }

    VerifiedSortingInfoCacheInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        final Path source =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        final SortingInfoCacheTarget target = SortingInfoCacheTarget.of(HostArtifactDigest.from(source))
                .orElseThrow(() -> new IllegalArgumentException("sorting-info cache unsupported host artifact"));

        sortingOwner = Class.forName(SortingInfoCacheTarget.SORTING_OWNER.replace('/', '.'), false, loader);
        meshRendererOwner = Class.forName(SortingInfoCacheTarget.MESH_RENDERER_OWNER.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                sortingOwner, loader, source, "sorting-info host loader or artifact mismatch");
        VerifiedHookInstallerSupport.attestClassSource(
                meshRendererOwner, loader, source, "mesh-renderer host loader or artifact mismatch");

        for (final SortingInfoCacheTarget.Dep dep : target.dependencies()) {
            final Class<?> depOwner = Class.forName(dep.owner().replace('/', '.'), false, loader);
            VerifiedHookInstallerSupport.attestClassSource(
                    depOwner, loader, source, "sorting dependency loader/source mismatch: " + depOwner.getName());
            verifyMethodPresent(depOwner, dep.name(), dep.descriptor());
        }

        try (JarFile jar = new JarFile(source.toFile())) {
            sortingTransformer = new SortingInfoCacheTransformer(
                    loader, source, VerifiedHookInstallerSupport.readReferenceBytes(jar, sortingOwner), target);
            containerTransformer = new MeshContainerLookupTransformer(
                    loader, source, VerifiedHookInstallerSupport.readReferenceBytes(jar, meshRendererOwner));
        }
    }

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        for (final Method method : owner.getMethods()) {
            if (!method.getName().equals(name)) continue;
            final String desc = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
            if (desc.equals(descriptor)) return;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + descriptor);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isRetransformClassesSupported()
                || !instrumentation.isModifiableClass(sortingOwner)
                || !instrumentation.isModifiableClass(meshRendererOwner)) {
            throw new IllegalStateException("sorting-info host classes cannot be transformed");
        }
        try {
            instrumentation.addTransformer(sortingTransformer, true);
            instrumentation.addTransformer(containerTransformer, true);
            installed = true;
            instrumentation.retransformClasses(sortingOwner, meshRendererOwner);
            if (sortingTransformer.matches() != 1 || sortingTransformer.failure() != null) {
                throw new IllegalStateException(
                        "sorting-info rewrite was not admitted: " + sortingTransformer.failure());
            }
            if (containerTransformer.matches() != 1 || containerTransformer.failure() != null) {
                throw new IllegalStateException(
                        "mesh-container rewrite was not admitted: " + containerTransformer.failure());
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
        instrumentation.removeTransformer(containerTransformer);
        instrumentation.removeTransformer(sortingTransformer);
        final AtomicReference<String> observedSorting = new AtomicReference<>();
        final AtomicReference<String> observedContainer = new AtomicReference<>();
        final ClassFileTransformer observer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    final Module module,
                    final ClassLoader loader,
                    final String name,
                    final Class<?> redefined,
                    final ProtectionDomain domain,
                    final byte[] bytes) {
                if (redefined == sortingOwner) {
                    observedSorting.set(sha256OrUnavailable(bytes));
                } else if (redefined == meshRendererOwner) {
                    observedContainer.set(sha256OrUnavailable(bytes));
                }
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try {
            instrumentation.retransformClasses(sortingOwner, meshRendererOwner);
            restored = sortingTransformer.beforeSha256() != null
                    && sortingTransformer.beforeSha256().equals(observedSorting.get())
                    && containerTransformer.beforeSha256() != null
                    && containerTransformer.beforeSha256().equals(observedContainer.get());
            if (!restored) {
                throw new IllegalStateException("sorting-info restoration not proven");
            }
            installed = false;
        } catch (java.lang.instrument.UnmodifiableClassException failure) {
            throw new IllegalStateException("sorting-info restoration failed", failure);
        } finally {
            instrumentation.removeTransformer(observer);
        }
    }

    private static String sha256OrUnavailable(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            return "unavailable";
        }
    }

    boolean restored() {
        return restored;
    }
}
