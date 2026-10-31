package dev.turboism.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Locale;

/** Exact signed installation receipt. The mutable index is advanced by the applier's rollback journal, not here. */
final class FrameworkUpdateReceipt {
    private FrameworkUpdateReceipt() {}

    static final class Prepared {
        private final Path root;
        private final UpdateManifest manifest;
        private final byte[] document;
        private final byte[] envelope;
        private final byte[] index;
        private final String digest;

        private Prepared(final Path root, final UpdateManifest manifest, final byte[] document,
                final byte[] envelope, final byte[] index, final String digest) {
            this.root = root;
            this.manifest = manifest;
            this.document = document.clone();
            this.envelope = envelope.clone();
            this.index = index.clone();
            this.digest = digest;
        }

        UpdateManifest manifest() { return manifest; }
        Path root() { return root; }
        byte[] index() { return index.clone(); }
        Path indexPath() { return root.resolve(FrameworkInstallationReceiptLoader.STATE).resolve("installed-receipt.json"); }

        /** Append immutable documents only; a crash may leave harmless unreferenced documents. */
        void publishDocuments() throws IOException {
            final Path directory = indexPath().getParent().resolve("receipts").resolve(digest);
            privateDirectories(root, directory);
            immutable(directory.resolve("manifest.json"), document);
            immutable(directory.resolve("manifest.json.sig"), envelope);
        }
    }

    static Prepared prepare(final Path installation, final byte[] document, final byte[] envelope,
            final UpdateManifestVerifier verifier)
            throws IOException, UpdateManifestVerifier.UpdateManifestVerificationException {
        // Snapshot caller bytes before authentication: never authorize one array and persist a later mutation.
        final byte[] manifestBytes = document.clone();
        final byte[] signatureBytes = envelope.clone();
        final UpdateManifest manifest = verifier.authenticate(manifestBytes, signatureBytes);
        FrameworkInstallationReceiptLoader.validateLayout(manifest);
        final Path root = installation.toAbsolutePath().normalize();
        final String rootIdentity;
        try (var files = FrameworkInstallationFiles.system(root)) {
            rootIdentity = files.rootIdentity();
        }
        final String digest = FrameworkUpdateTaskStore.hash(manifestBytes);
        final var index = UpdateFileSupport.JSON.createObjectNode()
                .put("format", FrameworkInstallationReceiptLoader.FORMAT)
                .put("schemaVersion", 1)
                .put("layout", FrameworkInstallationReceiptLoader.LAYOUT)
                .put("manifestSha256", digest)
                .put("rootIdentity", rootIdentity);
        return new Prepared(root, manifest, manifestBytes, signatureBytes,
                UpdateFileSupport.JSON.writeValueAsBytes(index), digest);
    }

    /** First conventional full install: adopt only an explicitly signed complete layout with matching bytes.
     * Never scan for ownership or overwrite an existing, different receipt. Caller holds the transaction lock. */
    static void initialize(final Path root, final byte[] document, final byte[] envelope,
            final UpdateManifestVerifier verifier) throws IOException, InterruptedException,
                    UpdateManifestVerifier.UpdateManifestVerificationException {
        final Prepared receipt = prepare(root, document, envelope, verifier);
        try (var files = FrameworkInstallationFiles.system(receipt.root())) {
            for (final var entry : receipt.manifest().entries()) files.verify(entry);
            if (FrameworkUpdateCache.attributes(receipt.indexPath()) != null) {
                if (!MessageDigest.isEqual(files.document(receipt.indexPath(), 4096), receipt.index())) {
                    throw new IOException("An existing different receipt must not be silently replaced");
                }
                // Idempotent only after both current payload and all exact signed documents are checked.
                receipt.publishDocuments();
                return;
            }
        }
        receipt.publishDocuments();
        final Path temporary = Files.createTempFile(receipt.indexPath().getParent(), ".receipt-index-", ".writing");
        try {
            if (!Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                FrameworkUpdateCache.restrictAcl(temporary);
            }
            Files.write(temporary, receipt.index(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            if (FrameworkUpdateCache.attributes(receipt.indexPath()) != null) {
                throw new IOException("Receipt appeared during initialization");
            }
            Files.move(temporary, receipt.indexPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void privateDirectories(final Path root, final Path target) throws IOException {
        final var owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        Path current = root;
        for (final Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (FrameworkUpdateCache.attributes(current) == null) {
                if (Files.getFileStore(current.getParent()).supportsFileAttributeView("posix")) {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(current);
                    FrameworkUpdateCache.restrictAcl(current);
                }
            }
            final var attributes = FrameworkUpdateCache.attributes(current);
            if (attributes == null || !attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                    || !owner.equals(Files.getOwner(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Unsafe receipt directory");
            }
            requirePrivate(current, true);
        }
    }

    private static void immutable(final Path target, final byte[] bytes) throws IOException {
        if (FrameworkUpdateCache.attributes(target) == null) {
            final Path temporary = Files.createTempFile(target.getParent(), ".receipt-", ".writing");
            try {
                if (!Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                    FrameworkUpdateCache.restrictAcl(temporary);
                }
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    final var buffer = java.nio.ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                // Publication is serialized by the transaction lock. No partially written canonical document.
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        final var attributes = FrameworkUpdateCache.attributes(target);
        if (attributes == null || !attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() != bytes.length || !FrameworkUpdateCache.singleLink(target)) {
            throw new IOException("Receipt document conflicts with immutable storage");
        }
        requirePrivate(target, false);
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!MessageDigest.isEqual(input.readNBytes(bytes.length + 1), bytes)) {
                throw new IOException("Receipt document bytes differ from authenticated bytes");
            }
        }
    }

    private static void requirePrivate(final Path path, final boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))) {
                throw new IOException("Receipt state is not private");
            }
        } else {
            final var view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("Receipt private ACL unavailable");
            final var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            for (final var entry : view.getAcl()) {
                final String name = entry.principal().getName().toUpperCase(Locale.ROOT);
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                        && !name.endsWith("\\SYSTEM") && !name.endsWith("\\ADMINISTRATORS")) {
                    throw new IOException("Receipt state is not private");
                }
            }
        }
    }
}
