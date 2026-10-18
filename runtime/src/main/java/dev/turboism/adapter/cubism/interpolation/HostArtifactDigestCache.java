package dev.turboism.adapter.cubism.interpolation;

import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

/** Reuses measured host fingerprints only while their canonical file identity is unchanged. */
final class HostArtifactDigestCache {
    private final Map<Path, Entry> entries = new HashMap<>();
    private final DigestReader reader;

    HostArtifactDigestCache() {
        this(HostArtifactDigest::from);
    }

    HostArtifactDigestCache(final DigestReader reader) {
        this.reader = reader;
    }

    synchronized HostArtifactDigest digest(final Path artifact) throws IOException {
        final Path path = artifact.toRealPath();
        final FileStamp before = FileStamp.from(path);
        final Entry cached = entries.get(path);
        if (cached != null && cached.stamp().equals(before)) {
            return cached.digest();
        }
        entries.remove(path);
        final HostArtifactDigest digest = reader.read(path);
        if (digest.size() != before.size() || !before.equals(FileStamp.from(path))) {
            throw new IOException("host artifact changed during fingerprinting");
        }
        entries.put(path, new Entry(before, digest));
        return digest;
    }

    @FunctionalInterface
    interface DigestReader {
        HostArtifactDigest read(Path artifact) throws IOException;
    }

    private record Entry(FileStamp stamp, HostArtifactDigest digest) {}

    private record FileStamp(long size, FileTime modified, FileTime created, Object fileKey, FileTime changed) {
        static FileStamp from(final Path path) throws IOException {
            final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            // Unix change time also detects in-place rewrites that restore the modification time.
            final FileTime changed =
                    path.getFileSystem().supportedFileAttributeViews().contains("unix")
                            ? (FileTime) Files.getAttribute(path, "unix:ctime")
                            : null;
            return new FileStamp(
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.creationTime(),
                    attributes.fileKey(),
                    changed);
        }
    }
}
