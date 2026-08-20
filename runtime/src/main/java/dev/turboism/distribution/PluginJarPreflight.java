package dev.turboism.distribution;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Revalidates a staged JAR against the immutable strict inspection plan before publication. */
public final class PluginJarPreflight {
    private PluginJarPreflight() { }

    /**
     * Re-reads a staged JAR from disk and checks it still matches what inspection recorded.
     *
     * <p>Verifies, in order: the path is a regular file and not a symbolic link (resolved with
     * {@link java.nio.file.LinkOption#NOFOLLOW_LINKS}), its length and SHA-256 equal the expected
     * values, and re-inspecting it yields the same descriptor hash, plugin id, and version. This
     * closes the window between inspection and publication in which the staged file could have been
     * swapped.
     *
     * <p>Never throws: any I/O or inspection failure is treated as a mismatch.
     *
     * @param jar path to the staged JAR to revalidate
     * @param pluginId plugin id the descriptor must declare
     * @param version plugin version the descriptor must declare
     * @param descriptorSha256 expected SHA-256 of the descriptor bytes inside the JAR
     * @param jarSha256 expected SHA-256 of the whole JAR file
     * @param jarSize expected byte length of the JAR file
     * @return {@code true} only if every check passes; {@code false} on any mismatch or failure
     */
    public static boolean matches(
        final Path jar,
        final String pluginId,
        final String version,
        final String descriptorSha256,
        final String jarSha256,
        final long jarSize
    ) {
        try {
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)) return false;
            if (Files.size(jar) != jarSize || !sha256(jar).equals(jarSha256)) return false;
            final PluginJarInspector.Inspected actual = new PluginJarInspector().inspect(jar, "plugin/plugin.jar");
            return actual.descriptorSha256().equals(descriptorSha256)
                && actual.descriptor().id().equals(pluginId)
                && actual.descriptor().version().equals(version);
        } catch (Exception failure) {
            return false;
        }
    }

    private static String sha256(final Path path) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
