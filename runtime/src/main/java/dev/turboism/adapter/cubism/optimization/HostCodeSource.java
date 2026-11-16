package dev.turboism.adapter.cubism.optimization;

import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Attests the {@link CodeSource} of a class definition against the reviewed host artifact or
 * against the JDK's own module path.
 *
 * <p>Every weave hook refuses a definition whose code source is not the reviewed artifact, so the
 * admission decision lives here instead of being re-derived per transformer. A definition without
 * a code source, without a location, or with an unparsable location never matches: the hooks fail
 * closed.</p>
 */
public final class HostCodeSource {

    private HostCodeSource() {}

    /**
     * Reports whether {@code domain} is defined by exactly {@code normalizedArtifact}.
     *
     * @param domain protection domain of the class definition, or {@code null}
     * @param normalizedArtifact absolute normalized reviewed artifact path
     * @return {@code true} only when the domain's code source location equals the artifact
     */
    public static boolean matchesArtifact(final ProtectionDomain domain, final Path normalizedArtifact) {
        if (normalizedArtifact == null || domain == null) {
            return false;
        }
        final Path source = sourcePath(domain);
        return source != null && source.equals(normalizedArtifact);
    }

    /**
     * Reports whether {@code domain} belongs to the JDK: the platform or bootstrap loader defines
     * it and the definition carries no code source location.
     *
     * <p>JDK classes ship inside the runtime image, so an artifact path can never attest them. The
     * hooks that rewrite {@code java.desktop} bytes therefore bind the loader identity instead and
     * let their method-shape gate prove the exact runtime build.</p>
     *
     * @param loader loader that defined the class, or {@code null} for the bootstrap loader
     * @param domain protection domain of the class definition, or {@code null}
     * @return {@code true} when the definition comes from the runtime image
     */
    public static boolean isJdkBootOrPlatform(final ClassLoader loader, final ProtectionDomain domain) {
        if (loader != null && loader != ClassLoader.getPlatformClassLoader()) {
            return false;
        }
        return domain == null || domain.getCodeSource() == null;
    }

    /**
     * Resolves the absolute normalized code source location of {@code domain}.
     *
     * @param domain protection domain of the class definition
     * @return the resolved artifact path, or {@code null} when the location is absent or invalid
     */
    private static Path sourcePath(final ProtectionDomain domain) {
        final CodeSource source = domain.getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        try {
            return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (final Exception invalidLocation) {
            return null;
        }
    }
}
