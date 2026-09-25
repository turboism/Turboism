package dev.turboism.core.event;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * No-execution resolvability oracle for the trusted domains a contract artifact may
 * reference: the shared {@code dev.turboism.sdk.*} surface and the JDK platform view —
 * exactly the names {@link SdkContractParent} lets a bound contract loader resolve.
 *
 * <p>Resolvability is decided by reading the trusted class <em>resource</em> through the
 * same loader the binding will delegate to — never by defining, loading, or
 * initializing the class:</p>
 *
 * <ul>
 *   <li>{@code dev.turboism.sdk.*} names are looked up as {@code .class} resources
 *       through the real SDK loader — the same parent-delegation view
 *       {@code sdkLoader.loadClass} uses. When the SDK is bootstrap-loaded
 *       ({@code sdkLoader == null}) the lookup goes through the SDK anchor's
 *       {@code Module.getResourceAsStream}, which is the bootstrap classpath view;
 *       neither TCCL nor {@code ClassLoader.getSystemResource} is consulted — the
 *       system view can see names bootstrap cannot, and vice versa.</li>
 *   <li>Every other name must live in a package owned by a named {@code java.*} or
 *       {@code jdk.*} module of the boot layer whose loader is bootstrap or the
 *       platform loader — mirroring {@code SdkContractParent}'s platform-load plus
 *       named-module check. The candidate class resource is then read from that
 *       module, so a package that exists proves nothing about a missing class.</li>
 * </ul>
 *
 * <p>For definability checks the oracle also exposes the trusted class's access flags
 * and permitted-subclass list, parsed from the resource bytes with ASM — still only a
 * resource read, never a class definition.</p>
 */
final class ContractTypeOracle {
    /** Defensive bound on a trusted class resource; a legitimate class file never nears it. */
    private static final long TRUSTED_CLASS_MAX = 8L * 1024 * 1024;

    /** Module metadata the definability check needs for a trusted ancestor. */
    record TrustedInfo(int accessFlags, Set<String> permits) {}

    private final ClassLoader sdkLoader;
    private final Class<?> sdkAnchor;
    private final Map<String, Module> platformPackages;
    private final Map<String, TrustedInfo> cache = new HashMap<>();
    private final Set<String> missing = new java.util.HashSet<>();

    private ContractTypeOracle(
        final ClassLoader sdkLoader,
        final Class<?> sdkAnchor,
        final Map<String, Module> platformPackages
    ) {
        this.sdkLoader = sdkLoader;
        this.sdkAnchor = sdkAnchor;
        this.platformPackages = platformPackages;
    }

    /**
     * Builds the oracle for the SDK anchor used by contract bindings —
     * {@code EventBus.class} — and the platform view of the current boot layer.
     */
    static ContractTypeOracle forSdkAnchor(final Class<?> sdkAnchor) {
        final ClassLoader platform = ClassLoader.getPlatformClassLoader();
        final Map<String, Module> packages = new HashMap<>();
        for (final Module module : ModuleLayer.boot().modules()) {
            final ClassLoader loader = module.getClassLoader();
            if (loader != null && loader != platform) {
                continue;
            }
            if (!module.isNamed()
                || !(module.getName().startsWith("java.")
                    || module.getName().startsWith("jdk."))) {
                continue;
            }
            for (final String pkg : module.getPackages()) {
                packages.putIfAbsent(pkg, module);
            }
        }
        return new ContractTypeOracle(sdkAnchor.getClassLoader(), sdkAnchor, packages);
    }

    /**
     * Test seam: an oracle over an explicit SDK loader/anchor pair. Passing
     * {@code null} exercises the bootstrap-loaded SDK branch, which reads the anchor's
     * module resources instead of a loader view.
     */
    static ContractTypeOracle forLoaders(
        final ClassLoader sdkLoader,
        final Class<?> sdkAnchor
    ) {
        final ContractTypeOracle oracle = forSdkAnchor(sdkAnchor);
        return new ContractTypeOracle(sdkLoader, sdkAnchor, oracle.platformPackages);
    }

    /**
     * @return the trusted class's flags and permits, or {@code null} when the name is
     *     not resolvable in the trusted domains
     */
    TrustedInfo lookup(final String binaryName) {
        if (missing.contains(binaryName)) {
            return null;
        }
        final TrustedInfo cached = cache.get(binaryName);
        if (cached != null) {
            return cached;
        }
        final byte[] bytes = trustedBytes(binaryName);
        if (bytes == null) {
            missing.add(binaryName);
            return null;
        }
        final TrustedInfo info = parseHeader(binaryName, bytes);
        if (info == null) {
            missing.add(binaryName);
            return null;
        }
        cache.put(binaryName, info);
        return info;
    }

    private byte[] trustedBytes(final String binaryName) {
        final String resource = binaryName.replace('.', '/') + ".class";
        try {
            if (binaryName.startsWith(ContractClosurePolicy.SDK_PACKAGE_PREFIX)) {
                return sdkLoader != null
                    ? sdkResource(resource)
                    : moduleResource(sdkAnchor.getModule(), resource);
            }
            final Module module =
                platformPackages.get(ContractClosurePolicy.packageName(binaryName));
            return module == null ? null : moduleResource(module, resource);
        } catch (final IOException failure) {
            return null;
        }
    }

    private byte[] sdkResource(final String resource) throws IOException {
        final URL url = sdkLoader.getResource(resource);
        if (url == null) {
            return null;
        }
        try (InputStream stream = url.openStream()) {
            return readBounded(stream);
        }
    }

    private static byte[] moduleResource(final Module module, final String resource)
            throws IOException {
        try (InputStream stream = module.getResourceAsStream(resource)) {
            return stream == null ? null : readBounded(stream);
        }
    }

    private static byte[] readBounded(final InputStream stream) throws IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        long size = 0;
        for (int read; (read = stream.read(buffer)) >= 0;) {
            if (read == 0) {
                continue;
            }
            size += read;
            if (size > TRUSTED_CLASS_MAX) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Reads only the trusted class's header facts needed for definability checks.
     * A resource that exists but is not a parseable class file counts as unresolvable —
     * fail-closed.
     */
    private static TrustedInfo parseHeader(final String binaryName, final byte[] bytes) {
        final Set<String> permits = new LinkedHashSet<>();
        try {
            final ClassReader reader = new ClassReader(bytes);
            if (!binaryName.equals(reader.getClassName().replace('/', '.'))) {
                return null;
            }
            reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitPermittedSubclass(final String permitted) {
                        permits.add(permitted.replace('/', '.'));
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
            );
            return new TrustedInfo(reader.getAccess(), Set.copyOf(permits));
        } catch (final RuntimeException failure) {
            return null;
        }
    }
}
