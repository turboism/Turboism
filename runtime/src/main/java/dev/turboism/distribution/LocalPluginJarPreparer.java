package dev.turboism.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Safely inspects and stages a plugin JAR selected directly from local disk. */
public final class LocalPluginJarPreparer {
    private final PackageAccess access;

    /** Creates a preparer backed by the local filesystem. */
    public LocalPluginJarPreparer() {
        this(PackageAccess.FILE_SYSTEM);
    }

    LocalPluginJarPreparer(final PackageAccess access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * Snapshots, strictly validates, and publishes a private staged copy of a direct plugin JAR.
     *
     * @param jarPath directly selected plugin JAR
     * @param stagingDirectory confined runtime-owned staging directory
     * @return prepared evidence or a stable rejection code; never throws
     */
    public Preparation prepare(final Path jarPath, final Path stagingDirectory) {
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        Path snapshot = null;
        try {
            final BasicFileAttributes initial = access.attributes(jarPath);
            ArchivePolicy.validatePackagePath(jarPath, initial);
            require(initial.size() <= PluginArchiveLimits.RAW_MAX, "PACKAGE_TOO_LARGE", jarPath.toString());

            snapshot = privateSnapshot();
            final Digest observed = snapshot(jarPath, snapshot);
            access.afterInitialHash(jarPath);
            unchanged(jarPath, initial);

            final PluginJarInspector.Inspected inspected = new PluginJarInspector().inspect(snapshot, jarPath.toString());
            require(strictApi(inspected.descriptor().turboismApi()),
                "PLUGIN_META_BAD_VERSION_RANGE", "META-INF/turboism/plugin.json");
            access.afterInspection(jarPath);
            unchanged(jarPath, initial);

            final Path staged = stage(snapshot, stagingDirectory, inspected.descriptor().id(), observed);
            return new Prepared(new PreparedPluginJar(
                inspected.descriptor(), inspected.descriptorSha256(), staged, observed.sha256(), observed.size()
            ));
        } catch (DistributionValidationException rejected) {
            return new PreparationRejected(rejected.code());
        } catch (IOException rejected) {
            return new PreparationRejected(DistributionErrors.PACKAGE_IO);
        } catch (Exception rejected) {
            return new PreparationRejected("PLUGIN_STAGE_FAILED");
        } finally {
            if (snapshot != null) try { Files.deleteIfExists(snapshot); } catch (IOException ignored) { }
        }
    }

    private Digest snapshot(final Path source, final Path target) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0L;
        final byte[] buffer = new byte[64 * 1024];
        try (InputStream input = access.open(source); OutputStream output = Files.newOutputStream(target)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                require(size <= PluginArchiveLimits.RAW_MAX - read, "PACKAGE_TOO_LARGE", source.toString());
                size += read;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return new Digest(HexFormat.of().formatHex(digest.digest()), size);
    }

    private void unchanged(final Path path, final BasicFileAttributes initial) throws Exception {
        final BasicFileAttributes current = access.attributes(path);
        require(current.isRegularFile()
                && initial.size() == current.size()
                && initial.lastModifiedTime().equals(current.lastModifiedTime())
                && Objects.equals(initial.fileKey(), current.fileKey()),
            DistributionErrors.PACKAGE_CHANGED, path.toString());
    }

    private static Path stage(
        final Path snapshot,
        final Path stagingDirectory,
        final String pluginId,
        final Digest expected
    ) throws Exception {
        final String name = pluginId + "-" + expected.sha256() + ".jar";
        final ConfinedStagingFiles.Target target = ConfinedStagingFiles.create(stagingDirectory, name);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0L;
        final byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(snapshot);
             OutputStream output = new DigestingOutputStream(target.output(), digest)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                size += read;
                output.write(buffer, 0, read);
            }
            require(size == expected.size(), "ARTIFACT_SIZE_MISMATCH", snapshot.toString());
            require(HexFormat.of().formatHex(digest.digest()).equals(expected.sha256()),
                "ARTIFACT_HASH_MISMATCH", snapshot.toString());
            target.publish();
            return target.target();
        } catch (Exception failure) {
            target.cleanup();
            throw failure;
        }
    }

    private static Path privateSnapshot() throws IOException {
        try {
            return Files.createTempFile("turboism-plugin-jar-", ".jar",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempFile("turboism-plugin-jar-", ".jar");
        }
    }

    private static boolean strictApi(final String value) {
        final String version = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";
        return value != null && (value.matches(version) || value.matches("\\[" + version + "," + version + "\\)"));
    }

    private static void require(final boolean valid, final String code, final String path) throws Exception {
        if (!valid) throw ArchivePolicy.problem(code, "Invalid plugin JAR", path);
    }

    /** Result of direct plugin-JAR preparation. */
    public sealed interface Preparation permits Prepared, PreparationRejected { }

    /** Successfully validated and staged plugin JAR. */
    public record Prepared(PreparedPluginJar value) implements Preparation {
        /** Requires prepared evidence. */
        public Prepared {
            value = Objects.requireNonNull(value, "value");
        }
    }

    /** Safely rejected plugin JAR. */
    public record PreparationRejected(String code) implements Preparation {
        /** Requires a stable non-blank rejection code. */
        public PreparationRejected {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        }
    }

    private record Digest(String sha256, long size) { }

    private static final class DigestingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final MessageDigest digest;

        private DigestingOutputStream(final OutputStream delegate, final MessageDigest digest) {
            this.delegate = delegate;
            this.digest = digest;
        }

        @Override public void write(final int value) throws IOException {
            delegate.write(value);
            digest.update((byte) value);
        }

        @Override public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            delegate.write(bytes, offset, length);
            digest.update(bytes, offset, length);
        }

        @Override public void close() throws IOException {
            delegate.close();
        }
    }
}
