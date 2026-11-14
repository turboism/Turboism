package dev.turboism.core.reflect;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/** Resolves the artifact path that defined a class, for exact-owner admission checks. */
public final class CodeSources {

    private static final String FILE_SCHEME = "file";

    /**
     * Returns the normalized absolute path of a protection domain's code source.
     *
     * @param protectionDomain the domain to resolve, nullable
     * @return the defining artifact path, or {@code null} when the domain has no file location
     */
    public static Path pathOf(final ProtectionDomain protectionDomain) {
        if (protectionDomain == null || protectionDomain.getCodeSource() == null) {
            return null;
        }
        final CodeSource source = protectionDomain.getCodeSource();
        if (source.getLocation() == null) {
            return null;
        }
        try {
            final URI location = source.getLocation().toURI();
            return FILE_SCHEME.equalsIgnoreCase(location.getScheme())
                    ? Path.of(location).toAbsolutePath().normalize()
                    : null;
        } catch (URISyntaxException | RuntimeException failure) {
            return null;
        }
    }

    private CodeSources() {}
}
