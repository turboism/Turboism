package dev.turboism.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.zip.ZipFile;

/** Release activation and keys must come from the defining runtime JAR, not unrelated classpath resources. */
final class FrameworkUpdateResources {
    private FrameworkUpdateResources() {}

    static byte[] read(final String resource, final int limit) throws IOException {
        if (!resource.startsWith("/META-INF/turboism/") || resource.contains("..") || limit <= 0) {
            throw new IOException("Invalid bundled update resource");
        }
        final var artifact = FrameworkRuntimeArtifact.resolve();
        // Development class directories have no deployed activation policy. Tests exercise explicit parsers.
        if (artifact == null || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.size(artifact) > 128L * 1024 * 1024) throw new IOException("Runtime artifact exceeds resource limit");
        try (var jar = new ZipFile(artifact.toFile())) {
            final var entry = jar.getEntry(resource.substring(1));
            if (entry == null) return null;
            if (entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > limit) {
                throw new IOException("Invalid bundled update resource size");
            }
            try (var input = jar.getInputStream(entry)) {
                final byte[] bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException("Bundled update resource grew beyond limit");
                return bytes;
            }
        }
    }
}
