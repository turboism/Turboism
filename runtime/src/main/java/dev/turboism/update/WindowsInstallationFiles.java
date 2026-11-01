package dev.turboism.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * W2 validation-only adapter. No production caller or automatic native extraction/loading. Each
 * operation hashes/reads the verified native handle, retaining its directory pins until close.
 */
final class WindowsInstallationFiles implements FrameworkInstallationFiles {
    private static Path loadedLibrary;
    private static String loadedDigest;
    private final Path root;
    private final Runnable afterChunk;
    private long token;

    /** Explicit test supply-chain seam, not authorization for a production DLL. */
    static synchronized Factory validationFactory(final Path dll, final String sha256) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new IOException("Windows native validation requires native Windows");
        }
        if (!sha256.matches("[a-f0-9]{64}")) throw new IOException("Explicit DLL SHA256 required");
        final Path path = dll.toAbsolutePath().normalize();
        if (loadedLibrary != null) {
            if (!loadedLibrary.equals(path) || !loadedDigest.equals(sha256))
                throw new IOException("A different native library was already loaded; restart validation JVM");
        } else {
            // Test operator supplies an isolated build artifact. W4 must replace this check/load
            // sequence with a protected, immutable installation supply chain before production use.
            if (!Files.isRegularFile(path) || Files.size(path) > 1024 * 1024)
                throw new IOException("Invalid validation DLL");
            if (!FrameworkUpdateTaskStore.hash(Files.readAllBytes(path)).equals(sha256))
                throw new IOException("Validation DLL digest mismatch");
            System.load(path.toString());
            loadedLibrary = path;
            loadedDigest = sha256;
        }
        return WindowsInstallationFiles::new;
    }

    /** Deterministic cancellation/fault validation seam; never configured by the product. */
    static Factory validationFactory(final Path dll, final String sha256, final Runnable afterChunk)
            throws IOException {
        validationFactory(dll, sha256);
        return root -> new WindowsInstallationFiles(root, afterChunk);
    }

    private WindowsInstallationFiles(final Path root) throws IOException {
        this(root, () -> {});
    }

    private WindowsInstallationFiles(final Path root, final Runnable afterChunk) throws IOException {
        if (!root.isAbsolute() || !root.equals(root.normalize()))
            throw new IOException("An absolute normalized installation root is required");
        this.root = root;
        this.afterChunk = java.util.Objects.requireNonNull(afterChunk);
        token = openRoot(root.toString());
    }

    @Override
    public synchronized String rootIdentity() throws IOException {
        requireOpen();
        return FrameworkUpdateTaskStore.hash(identity(token));
    }

    @Override
    public synchronized byte[] document(final Path path, final int limit) throws IOException, InterruptedException {
        requireOpen();
        if (limit <= 0 || limit > UpdateManifest.MAX_MANIFEST_BYTES) throw new IOException("Invalid document bound");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        consume(path, limit, -1, bytes, null);
        if (bytes.size() == 0) throw new IOException("Empty receipt document");
        return bytes.toByteArray();
    }

    @Override
    public synchronized void verify(final UpdateManifestEntry entry) throws IOException, InterruptedException {
        requireOpen();
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        consume(root.resolve(entry.relativePath()), entry.bytes(), entry.bytes(), null, digest);
        if (!HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))
            throw new IOException("Installed bytes conflict with signed receipt");
    }

    private void consume(
            final Path path,
            final long limit,
            final long exact,
            final ByteArrayOutputStream output,
            final MessageDigest digest)
            throws IOException, InterruptedException {
        interrupted();
        if (!path.startsWith(root) || path.equals(root) || !path.equals(path.normalize()))
            throw new IOException("Receipt path outside installation or alias");
        final long file = openFile(token, root.relativize(path).toString(), limit);
        try {
            if (exact >= 0 && size(file) != exact) throw new IOException("Signed file length mismatch");
            final byte[] buffer = new byte[32768];
            while (true) {
                interrupted();
                final int count = read(file, buffer);
                if (count == 0) break;
                if (output != null) output.write(buffer, 0, count);
                if (digest != null) digest.update(buffer, 0, count);
                afterChunk.run();
            }
            finish(file);
            interrupted();
        } finally {
            closeToken(file, false);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (token != 0) {
            closeToken(token, true);
            token = 0;
        }
    }

    private void requireOpen() throws IOException {
        if (token == 0) throw new IOException("Installation handle closed");
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Receipt verification cancelled");
    }

    private static native long openRoot(String root) throws IOException;

    private static native byte[] identity(long root) throws IOException;

    private static native long openFile(long root, String path, long limit) throws IOException;

    private static native long size(long file) throws IOException;

    private static native int read(long file, byte[] buffer) throws IOException;

    private static native void finish(long file) throws IOException;

    private static native void closeToken(long token, boolean root) throws IOException;
}
