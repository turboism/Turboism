package dev.turboism.update;

import dev.turboism.core.FrameworkBuildInfo;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;

/** Location of the runtime's actual artifact, including classes loaded via agent Boot-Class-Path. */
final class FrameworkRuntimeArtifact {
    private FrameworkRuntimeArtifact() {}

    static Path resolve() {
        try {
            final var source = FrameworkBuildInfo.class.getProtectionDomain().getCodeSource();
            if (source != null && "file".equals(source.getLocation().getProtocol())) {
                return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            }
            // Bootstrap-loaded agent classes may have no CodeSource. Resolve the defining class resource,
            // not a path supplied by the feed, installed-build.json or a system property. Callers still bind
            // the result to the exact configured home and rehash that agent against authenticated evidence.
            final URL resource = FrameworkBuildInfo.class.getResource("FrameworkBuildInfo.class");
            if (resource != null && resource.openConnection() instanceof JarURLConnection jar) {
                final URL file = jar.getJarFileURL();
                if ("file".equals(file.getProtocol())) {
                    return Path.of(file.toURI()).toAbsolutePath().normalize();
                }
            }
        } catch (java.io.IOException | java.net.URISyntaxException | SecurityException invalid) {
            return null;
        }
        return null;
    }
}
