package dev.turboism.update;

import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.PublishGuard;
import dev.turboism.core.io.WriteOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Locale;

/** Exact signed installation receipt. The mutable index is advanced by the applier's rollback journal, not here. */
final class FrameworkUpdateReceipt {
    private FrameworkUpdateReceipt() {}

    /** Receipt documents and the index are private: owner-only before the first byte, atomicity required. */
    private static final WriteOptions PRIVATE_WRITE =
            WriteOptions.DEFAULT.withPermissions(WriteOptions.Permissions.OWNER_ONLY);

    /** Bounds of the receipt index read back for collision detection. */
    private static final int MAX_INDEX_BYTES = 4096;

    /**
     * Windows keeps the receipt state's historical owner/SYSTEM/Administrators DACL. The staged file is
     * already owner-only, and this widens it to exactly the trusted principals the pre-migration code
     * applied before the bytes were written.
     */
    private static final PublishGuard PRIVATE_ACL = new PublishGuard() {
        @Override
        public void beforePublish(final Path staged, final Path target) {}

        @Override
        public void afterPublish(final Path staged, final Path target) throws IOException {
            applyPrivateAcl(target);
        }
    };

    static final class Prepared {
        private final Path root;
        private final UpdateManifest manifest;
        private final byte[] document;
        private final byte[] envelope;
        private final byte[] index;

        private Prepared(
                final Path root,
                final UpdateManifest manifest,
                final byte[] document,
                final byte[] envelope,
                final byte[] index) {
            this.root = root;
            this.manifest = manifest;
            this.document = document.clone();
            this.envelope = envelope.clone();
            this.index = index.clone();
        }

        UpdateManifest manifest() {
            return manifest;
        }

        Path root() {
            return root;
        }

        byte[] index() {
            return index.clone();
        }

        byte[] document() {
            return document.clone();
        }

        byte[] envelope() {
            return envelope.clone();
        }

        /** Fixed location of the active signed documents; the index names no digest. */
        Path currentDocuments() {
            return indexPath().getParent().resolve(FrameworkInstallationReceiptLoader.CURRENT_RECEIPT);
        }

        Path indexPath() {
            return root.resolve(FrameworkInstallationReceiptLoader.STATE).resolve("installed-receipt.json");
        }

        /**
         * Publishes the active signed documents at the fixed current location.
         *
         * <p>An existing different document set is replaced only after the caller authenticated the
         * new bytes, so the fixed path always holds one exact signed receipt. A crash between the two
         * documents leaves the previous manifest with a mismatched envelope, which the signature
         * refuses; the next apply republishes both.</p>
         */
        void publishDocuments() throws IOException {
            final Path directory = currentDocuments();
            privateDirectories(root, directory);
            publishDocument(directory.resolve("manifest.json"), document);
            publishDocument(directory.resolve("manifest.json.sig"), envelope);
        }

        /** Content address of the authenticated manifest bytes; never stored in the index. */
        String digest() {
            return FrameworkUpdateTaskStore.hash(document);
        }
    }

    static Prepared prepare(
            final Path installation,
            final byte[] document,
            final byte[] envelope,
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
        final var index = UpdateFileSupport.JSON
                .createObjectNode()
                .put("format", FrameworkInstallationReceiptLoader.FORMAT)
                .put("schemaVersion", 1)
                .put("layout", FrameworkInstallationReceiptLoader.LAYOUT)
                .put("rootIdentity", rootIdentity);
        return new Prepared(
                root, manifest, manifestBytes, signatureBytes, UpdateFileSupport.JSON.writeValueAsBytes(index));
    }

    /** First conventional full install: adopt only an explicitly signed complete layout with matching bytes.
     * Never scan for ownership or overwrite an existing, different receipt. Caller holds the transaction lock. */
    static void initialize(
            final Path root, final byte[] document, final byte[] envelope, final UpdateManifestVerifier verifier)
            throws IOException, InterruptedException, UpdateManifestVerifier.UpdateManifestVerificationException {
        final Prepared receipt = prepare(root, document, envelope, verifier);
        try (var files = FrameworkInstallationFiles.system(receipt.root())) {
            for (final var entry : receipt.manifest().entries()) files.verify(entry);
            if (FrameworkUpdateCache.attributes(receipt.indexPath()) != null) {
                // The index no longer records a digest, so the signed documents themselves decide
                // whether this is the same receipt. A different one is never silently replaced.
                if (!MessageDigest.isEqual(files.document(receipt.indexPath(), MAX_INDEX_BYTES), receipt.index())
                        || !MessageDigest.isEqual(
                                files.document(
                                        receipt.currentDocuments().resolve("manifest.json"),
                                        UpdateManifest.MAX_MANIFEST_BYTES),
                                receipt.document())
                        || !MessageDigest.isEqual(
                                files.document(
                                        receipt.currentDocuments().resolve("manifest.json.sig"),
                                        UpdateManifestVerifier.MAX_ENVELOPE_BYTES),
                                receipt.envelope())) {
                    throw new IOException("An existing different receipt must not be silently replaced");
                }
                // Idempotent only after both current payload and all exact signed documents are checked.
                receipt.publishDocuments();
                return;
            }
        }
        receipt.publishDocuments();
        // Create-once publication: an existing index is never replaced, and the guard keeps the explicit
        // "appeared during initialization" report for a race the checks above cannot see.
        DurableFile.createOnce(receipt.indexPath())
                .withOptions(PRIVATE_WRITE)
                .guardedBy(new PublishGuard() {
                    @Override
                    public void beforePublish(final Path staged, final Path target) throws IOException {
                        if (FrameworkUpdateCache.attributes(target) != null) {
                            throw new IOException("Receipt appeared during initialization");
                        }
                    }

                    @Override
                    public void afterPublish(final Path staged, final Path target) throws IOException {
                        applyPrivateAcl(target);
                    }
                })
                .replace(receipt.index());
    }

    /** POSIX keeps private state via owner-only mode bits; Windows needs an explicit restrictive DACL. */
    private static void applyPrivateAcl(final Path path) throws IOException {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
            FrameworkUpdateCache.restrictAcl(path);
        }
    }

    private static void privateDirectories(final Path root, final Path target) throws IOException {
        final var owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        Path current = root;
        for (final Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (FrameworkUpdateCache.attributes(current) == null) {
                if (Files.getFileStore(current.getParent()).supportsFileAttributeView("posix")) {
                    Files.createDirectory(
                            current,
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(current);
                    FrameworkUpdateCache.restrictAcl(current);
                }
            }
            final var attributes = FrameworkUpdateCache.attributes(current);
            if (attributes == null
                    || !attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || !owner.equals(Files.getOwner(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Unsafe receipt directory");
            }
            requirePrivate(current, true);
        }
    }

    /**
     * Publishes one signed document at the fixed current path.
     *
     * <p>The path is mutable now, so a later release replaces the previous signed receipt instead of
     * appending a second immutable copy. The bytes are still authenticated before publication and are
     * re-read afterwards, so a torn or redirected write is refused rather than trusted.</p>
     */
    private static void publishDocument(final Path target, final byte[] bytes) throws IOException {
        DurableFile.at(target).withOptions(PRIVATE_WRITE).guardedBy(PRIVATE_ACL).replace(bytes);
        final var attributes = FrameworkUpdateCache.attributes(target);
        if (attributes == null
                || !attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || attributes.size() != bytes.length
                || !FrameworkUpdateCache.singleLink(target)) {
            throw new IOException("Receipt document conflicts with the signed bytes");
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
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))) {
                throw new IOException("Receipt state is not private");
            }
        } else {
            final var view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("Receipt private ACL unavailable");
            final var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            for (final var entry : view.getAcl()) {
                final String name = entry.principal().getName().toUpperCase(Locale.ROOT);
                if (entry.type() == AclEntryType.ALLOW
                        && !entry.principal().equals(owner)
                        && !name.endsWith("\\SYSTEM")
                        && !name.endsWith("\\ADMINISTRATORS")) {
                    throw new IOException("Receipt state is not private");
                }
            }
        }
    }
}
