package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.plugin.PluginPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Installs one exact reviewed fx release asset for optional Thin-package repair. */
final class FxManagedRuntimeService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int TAR_BLOCK_BYTES = 512;
    private static final int TAR_NAME_BYTES = 100;
    private static final long MAX_ENTRY_BYTES = 32L * 1024L * 1024L;
    private static final String RELEASE_HOST = "github.com";
    private static final String RELEASE_ASSET_HOST = "release-assets.githubusercontent.com";
    private static final String USER_AGENT = "Turboism-with-fx/0.1";
    private static final Set<String> ARCHIVE_ENTRIES = Set.of(
        "fx", FxRuntimeManifest.LICENSE.name(), FxRuntimeManifest.THIRD_PARTY_NOTICES.name()
    );

    private final FxRuntimeResolver resolver;
    private final FxRuntimeResolver.PlatformDetector platformDetector;
    private final EntrySelector entrySelector;
    private final HttpClient client;
    private final Consumer<Code> diagnostic;
    private final Path homeRoot;
    private final Path cacheRoot;
    private final byte[] distributionLicense;
    private final byte[] distributionThirdPartyNotices;
    private final byte[] distributionNotice;
    private final byte[] distributionManifest;
    private final FailureInjector failureInjector;

    FxManagedRuntimeService(
        final PluginPaths paths,
        final Consumer<Code> diagnostic
    ) {
        this(
            paths,
            FxRuntimePlatform::detect,
            FxRuntimeManifest::entry,
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            diagnostic,
            resource("META-INF/turboism/fx-runtime/LICENSE"),
            resource("META-INF/turboism/fx-runtime/THIRD_PARTY_NOTICES.md"),
            resource("META-INF/turboism/fx-runtime/TURBOISM-DISTRIBUTION-NOTICE.txt"),
            resource("META-INF/turboism/fx-runtime/manifest.properties"),
            ignored -> { }
        );
    }

    FxManagedRuntimeService(
        final PluginPaths paths,
        final FxRuntimeResolver.PlatformDetector platformDetector,
        final EntrySelector entrySelector,
        final HttpClient client,
        final Consumer<Code> diagnostic,
        final byte[] distributionLicense,
        final byte[] distributionThirdPartyNotices,
        final byte[] distributionNotice,
        final byte[] distributionManifest,
        final FailureInjector failureInjector
    ) {
        this.resolver = new FxRuntimeResolver(paths, platformDetector);
        this.platformDetector = Objects.requireNonNull(platformDetector, "platformDetector");
        this.entrySelector = Objects.requireNonNull(entrySelector, "entrySelector");
        this.client = Objects.requireNonNull(client, "client");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.distributionLicense = Objects.requireNonNull(
            distributionLicense, "distributionLicense"
        ).clone();
        this.distributionThirdPartyNotices = Objects.requireNonNull(
            distributionThirdPartyNotices, "distributionThirdPartyNotices"
        ).clone();
        this.distributionNotice = Objects.requireNonNull(
            distributionNotice, "distributionNotice"
        ).clone();
        this.distributionManifest = Objects.requireNonNull(
            distributionManifest, "distributionManifest"
        ).clone();
        this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
        final Path managedRoot = resolver.managedRoot();
        this.homeRoot = managedRoot.getParent().getParent().toAbsolutePath().normalize();
        final Path cache = paths.cacheDir().toAbsolutePath().normalize();
        if (!cache.startsWith(homeRoot)) {
            throw new IllegalArgumentException("managed fx cache is outside Turboism home");
        }
        this.cacheRoot = confined(cache.resolve("managed-runtime"));
    }

    /** Downloads, verifies, and atomically installs the current reviewed platform payload. */
    synchronized Result installOrRepair() {
        final FxRuntimePlatform platform = platformDetector.detect().orElse(null);
        if (platform == null) return Result.PLATFORM_UNSUPPORTED;
        final FxRuntimeManifest.Entry entry = entrySelector.entry(platform).orElse(null);
        if (entry == null) return Result.PLATFORM_UNSUPPORTED;
        final Path root = resolver.managedRoot();
        final Path versionRoot = confined(root.resolve(FxRuntimeManifest.VERSION));
        final Path target = confined(versionRoot.resolve(platform.id()));
        final Path archive = confined(cacheRoot.resolve(entry.archiveName() + ".part"));
        final Path staging = confined(versionRoot.resolve("." + platform.id() + ".installing"));
        final Path previous = confined(versionRoot.resolve("." + platform.id() + ".previous"));
        boolean movedPrevious = false;
        try {
            createPrivateDirectory(cacheRoot);
            createPrivateDirectory(versionRoot);
            reconcileTransaction(entry, target, staging, previous);
            requireSafeDirectoryChain(cacheRoot);
            requireSafeDirectoryChain(versionRoot);
            Files.deleteIfExists(archive);
            download(entry, archive);
            createPrivateDirectory(staging);
            extract(entry, archive, staging);
            replaceLegalFile(
                staging.resolve(FxRuntimeManifest.LICENSE.name()),
                FxRuntimeManifest.LICENSE,
                distributionLicense
            );
            replaceLegalFile(
                staging.resolve(FxRuntimeManifest.THIRD_PARTY_NOTICES.name()),
                FxRuntimeManifest.THIRD_PARTY_NOTICES,
                distributionThirdPartyNotices
            );
            writeStatic(staging.resolve("TURBOISM-DISTRIBUTION-NOTICE.txt"), distributionNotice);
            writeStatic(staging.resolve("manifest.properties"), distributionManifest);
            verifyStaging(entry, staging);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireOrdinaryTree(target);
                atomicMove(target, previous);
                movedPrevious = true;
            }
            try {
                atomicMove(staging, target);
            } catch (IOException activationFailure) {
                if (movedPrevious && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    atomicMove(previous, target);
                    movedPrevious = false;
                }
                throw activationFailure;
            }
            failureInjector.afterActivation(target);
            try {
                verifyStaging(entry, target);
            } catch (IOException verificationFailure) {
                deleteOrdinaryTree(target);
                if (movedPrevious) {
                    atomicMove(previous, target);
                    movedPrevious = false;
                }
                diagnostic.accept(Code.ACTIVATED_RUNTIME_INVALID);
                return Result.FAILED;
            }
            if (movedPrevious) {
                try {
                    deleteOrdinaryTree(previous);
                } catch (IOException cleanupFailure) {
                    diagnostic.accept(Code.CLEANUP_FAILED);
                }
                movedPrevious = false;
            }
            diagnostic.accept(Code.INSTALLED);
            return Result.INSTALLED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            diagnostic.accept(Code.INTERRUPTED);
            return Result.FAILED;
        } catch (IOException | RuntimeException failure) {
            diagnostic.accept(Code.FAILED);
            return Result.FAILED;
        } finally {
            deleteOrdinaryFile(archive);
            deleteOrdinaryTreeIfPresent(staging);
            if (movedPrevious && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    atomicMove(previous, target);
                } catch (IOException rollbackFailure) {
                    diagnostic.accept(Code.ROLLBACK_FAILED);
                }
            }
        }
    }

    private void download(final FxRuntimeManifest.Entry entry, final Path archive)
        throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(entry.sourceUri())
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        HttpResponse<InputStream> response = client.send(
            request, HttpResponse.BodyHandlers.ofInputStream()
        );
        if (redirect(response.statusCode())) {
            try (InputStream ignored = response.body()) {
                final URI redirect = redirect(response, entry);
                response = client.send(
                    HttpRequest.newBuilder(redirect)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
                );
            }
        }
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) throw new IOException("unexpected response status");
            final Optional<String> contentLength = response.headers().firstValue("Content-Length");
            if (contentLength.isPresent()) {
                final long length;
                try {
                    length = Long.parseLong(contentLength.orElseThrow());
                } catch (NumberFormatException failure) {
                    throw new IOException("invalid content length", failure);
                }
                if (length != entry.archiveSize()) {
                    throw new IOException("archive content length mismatch");
                }
            }
            final MessageDigest digest = sha256Digest();
            long copied = 0L;
            final OpenOption[] options = {
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            };
            try (OutputStream output = Files.newOutputStream(archive, options)) {
                final byte[] buffer = new byte[BUFFER_BYTES];
                while (true) {
                    final int read = input.read(buffer);
                    if (read < 0) break;
                    copied += read;
                    if (copied > entry.archiveSize()) throw new IOException("archive exceeds limit");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (copied != entry.archiveSize()
                || !entry.archiveSha256().equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("archive identity mismatch");
            }
        }
    }

    private static URI redirect(
        final HttpResponse<?> response,
        final FxRuntimeManifest.Entry entry
    ) throws IOException {
        final String location = response.headers().firstValue("Location")
            .orElseThrow(() -> new IOException("release redirect is missing"));
        final URI uri;
        try {
            uri = URI.create(location);
        } catch (RuntimeException failure) {
            throw new IOException("release redirect is invalid", failure);
        }
        if (!"https".equals(uri.getScheme())
            || !RELEASE_ASSET_HOST.equals(uri.getHost())
            || uri.getPort() != -1
            || uri.getRawUserInfo() != null
            || uri.getRawFragment() != null
            || !entry.releaseAssetPath().equals(uri.getRawPath())) {
            throw new IOException("release redirect is not reviewed");
        }
        return uri;
    }

    private static boolean redirect(final int status) {
        return status >= 300 && status <= 399;
    }

    private static void extract(
        final FxRuntimeManifest.Entry entry,
        final Path archive,
        final Path target
    ) throws IOException {
        final LinkedHashSet<String> found = new LinkedHashSet<>();
        try (InputStream compressed = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS);
             GZIPInputStream input = new GZIPInputStream(compressed, BUFFER_BYTES)) {
            final byte[] header = new byte[TAR_BLOCK_BYTES];
            while (true) {
                readFully(input, header, 0, TAR_BLOCK_BYTES);
                if (allZero(header)) {
                    final byte[] second = new byte[TAR_BLOCK_BYTES];
                    readFully(input, second, 0, TAR_BLOCK_BYTES);
                    if (!allZero(second)) throw new IOException("invalid tar terminator");
                    final byte[] trailing = new byte[BUFFER_BYTES];
                    while (true) {
                        final int read = input.read(trailing);
                        if (read < 0) break;
                        for (int index = 0; index < read; index++) {
                            if (trailing[index] != 0) {
                                throw new IOException("non-zero trailing tar content");
                            }
                        }
                    }
                    break;
                }
                verifyTarChecksum(header);
                final String name = tarText(header, 0, TAR_NAME_BYTES);
                final String prefix = tarText(header, 345, 155);
                final String path = prefix.isEmpty() ? name : prefix + "/" + name;
                final long size = tarOctal(header, 124, 12);
                final int type = header[156] & 0xff;
                if ((type != 0 && type != '0') || !ARCHIVE_ENTRIES.contains(path)
                    || !found.add(path) || size < 0L || size > MAX_ENTRY_BYTES) {
                    throw new IOException("unexpected tar entry");
                }
                final Path output = confinedChild(target, path);
                try (OutputStream file = Files.newOutputStream(
                    output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                )) {
                    copyExact(input, file, size);
                }
                skipExact(input, padding(size));
            }
        }
        if (!found.equals(new LinkedHashSet<>(ARCHIVE_ENTRIES))) {
            throw new IOException("tar inventory is incomplete");
        }
        if (!entry.archiveName().endsWith(".tar.gz")) {
            throw new IOException("archive type is unsupported");
        }
    }

    private static void verifyStaging(
        final FxRuntimeManifest.Entry entry,
        final Path staging
    ) throws IOException {
        requireExactFile(
            staging.resolve("fx"), entry.executableSize(), entry.executableSha256()
        );
        requireExactFile(
            staging.resolve(FxRuntimeManifest.LICENSE.name()),
            FxRuntimeManifest.LICENSE.size(), FxRuntimeManifest.LICENSE.sha256()
        );
        requireExactFile(
            staging.resolve(FxRuntimeManifest.THIRD_PARTY_NOTICES.name()),
            FxRuntimeManifest.THIRD_PARTY_NOTICES.size(),
            FxRuntimeManifest.THIRD_PARTY_NOTICES.sha256()
        );
        ensureOwnerExecutable(staging.resolve("fx"));
        requireOrdinaryTree(staging);
    }

    private static void requireExactFile(
        final Path path,
        final long size,
        final String sha256
    ) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(path)
            || Files.size(path) != size
            || !sha256.equals(sha256(path))) {
            throw new IOException("staged file identity mismatch");
        }
    }

    private static void requireOrdinaryTree(final Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("runtime tree is not an ordinary directory");
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                    throw new IOException("runtime tree contains a special entry");
                }
            }
        }
    }

    private void createPrivateDirectory(final Path path) throws IOException {
        requireSafeExistingAncestors(path);
        Files.createDirectories(path);
        requireSafeDirectoryChain(path);
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Native Windows permissions are owned by the product installer/runtime boundary.
        }
    }

    private void requireSafeExistingAncestors(final Path path) throws IOException {
        final Path normalized = confined(path);
        Path current = homeRoot;
        if (Files.isSymbolicLink(current)
            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Turboism home is not an ordinary directory");
        }
        for (Path segment : homeRoot.relativize(normalized)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(current)) {
                throw new IOException("managed runtime ancestor is a symbolic link");
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("managed runtime ancestor is not a directory");
            }
        }
    }

    private void requireSafeDirectoryChain(final Path path) throws IOException {
        final Path normalized = confined(path);
        Path current = homeRoot;
        for (Path segment : homeRoot.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)
                || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("managed runtime directory chain is unsafe");
            }
        }
    }

    private void reconcileTransaction(
        final FxRuntimeManifest.Entry entry,
        final Path target,
        final Path staging,
        final Path previous
    ) throws IOException {
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed runtime staging state requires manual review");
        }
        if (!Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireOrdinaryTree(target);
            }
            return;
        }
        requireOrdinaryTree(previous);
        verifyStaging(entry, previous);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            atomicMove(previous, target);
            return;
        }
        requireOrdinaryTree(target);
        try {
            verifyStaging(entry, target);
            deleteOrdinaryTree(previous);
        } catch (IOException invalidTarget) {
            deleteOrdinaryTree(target);
            atomicMove(previous, target);
        }
    }

    private static void replaceLegalFile(
        final Path path,
        final FxRuntimeManifest.LegalFile identity,
        final byte[] content
    ) throws IOException {
        final String contentHash = HexFormat.of().formatHex(sha256Digest().digest(content));
        if (content.length != identity.size() || !contentHash.equals(identity.sha256())) {
            throw new IOException("bundled legal resource identity mismatch");
        }
        requireExactFile(path, identity.size(), identity.sha256());
        Files.delete(path);
        writeStatic(path, content);
    }

    private static void writeStatic(final Path path, final byte[] content) throws IOException {
        Files.write(
            path,
            content,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        );
    }

    private static void atomicMove(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("managed runtime activation is not atomic", failure);
        }
    }

    private static void deleteOrdinaryFile(final Path path) {
        try {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Best-effort cleanup of this operation's private temporary file.
        }
    }

    private static void deleteOrdinaryTreeIfPresent(final Path root) {
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) deleteOrdinaryTree(root);
        } catch (IOException ignored) {
            // Unsafe or externally replaced trees are deliberately left untouched.
        }
    }

    private static void deleteOrdinaryTree(final Path root) throws IOException {
        requireOrdinaryTree(root);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) Files.delete(path);
        }
        Files.delete(root);
    }

    private static Path confinedChild(final Path root, final String name) throws IOException {
        final Path child = root.resolve(name).normalize();
        if (!child.getParent().equals(root) || !child.startsWith(root)) {
            throw new IOException("archive path escapes staging");
        }
        return child;
    }

    private Path confined(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(homeRoot)) {
            throw new IllegalArgumentException("managed fx path escapes Turboism home");
        }
        return normalized;
    }

    private static void ensureOwnerExecutable(final Path executable) throws IOException {
        try {
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                executable, LinkOption.NOFOLLOW_LINKS
            );
            if (!permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                final EnumSet<PosixFilePermission> updated = EnumSet.copyOf(permissions);
                updated.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(executable, updated);
            }
        } catch (UnsupportedOperationException ignored) {
            // Windows uses native executable semantics.
        }
    }

    private static void copyExact(
        final InputStream input,
        final OutputStream output,
        final long bytes
    ) throws IOException {
        final byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = bytes;
        while (remaining > 0L) {
            final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("truncated tar entry");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExact(final InputStream input, final long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0L) {
            final long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
            } else if (input.read() == -1) {
                throw new IOException("truncated tar padding");
            } else {
                remaining--;
            }
        }
    }

    private static void readFully(
        final InputStream input,
        final byte[] buffer,
        final int offset,
        final int length
    ) throws IOException {
        int copied = 0;
        while (copied < length) {
            final int read = input.read(buffer, offset + copied, length - copied);
            if (read < 0) throw new IOException("truncated tar archive");
            copied += read;
        }
    }

    private static boolean allZero(final byte[] block) {
        for (byte value : block) if (value != 0) return false;
        return true;
    }

    private static void verifyTarChecksum(final byte[] header) throws IOException {
        final long expected = tarOctal(header, 148, 8);
        long actual = 0L;
        for (int index = 0; index < header.length; index++) {
            actual += index >= 148 && index < 156 ? 0x20 : header[index] & 0xff;
        }
        if (actual != expected) throw new IOException("tar checksum mismatch");
    }

    private static String tarText(final byte[] header, final int offset, final int length)
        throws IOException {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        for (int index = offset; index < end; index++) {
            final int value = header[index] & 0xff;
            if (value < 0x20 || value > 0x7e) throw new IOException("tar name is not ASCII");
        }
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static long tarOctal(final byte[] header, final int offset, final int length)
        throws IOException {
        long value = 0L;
        boolean digit = false;
        for (int index = offset; index < offset + length; index++) {
            final int next = header[index] & 0xff;
            if (next == 0 || next == 0x20) {
                if (digit) break;
                continue;
            }
            if (next < '0' || next > '7') throw new IOException("tar number is invalid");
            digit = true;
            value = Math.addExact(Math.multiplyExact(value, 8L), next - '0');
        }
        return value;
    }

    private static long padding(final long size) {
        return (TAR_BLOCK_BYTES - size % TAR_BLOCK_BYTES) % TAR_BLOCK_BYTES;
    }

    private static String sha256(final Path path) throws IOException {
        return FxRuntimeResolver.sha256(path);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] resource(final String name) {
        try (InputStream input = FxManagedRuntimeService.class.getClassLoader()
            .getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("managed fx resource is missing");
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("managed fx resource could not be loaded", failure);
        }
    }

    @FunctionalInterface
    interface EntrySelector {
        Optional<FxRuntimeManifest.Entry> entry(FxRuntimePlatform platform);
    }

    @FunctionalInterface
    interface FailureInjector {
        void afterActivation(Path target) throws IOException;
    }

    enum Result {
        INSTALLED,
        PLATFORM_UNSUPPORTED,
        FAILED
    }

    enum Code {
        INSTALLED,
        FAILED,
        INTERRUPTED,
        ACTIVATED_RUNTIME_INVALID,
        ROLLBACK_FAILED,
        CLEANUP_FAILED
    }
}
