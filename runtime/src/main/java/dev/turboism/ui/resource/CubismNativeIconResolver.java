package dev.turboism.ui.resource;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.resource.UiIconAvailability;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runtime-owned, bounded native PNG cache. No Cubism resource bytes escape to plugins.
 *
 * <p>Preloading is an explicit off-EDT composition step, not a render-time fallback. The owner
 * must close this provider on host replacement/disposal. This class does not install itself in
 * plugin contexts or claim visual/native-operation readiness.</p>
 */
public final class CubismNativeIconResolver implements AutoCloseable {
    private static final int MAX_ARTIFACT_BYTES = 48 * 1024 * 1024;
    private static final int MAX_PNG_BYTES = 4096;
    private final Map<NativeIconVariant, BufferedImage> images;
    private final Map<NativeIconVariant, Icon> handles = new HashMap<>();
    private final UiIconAvailability unavailable;
    private boolean closed;

    // Prepared-cache seam stays package-private; production callers must use the attested factory.
    CubismNativeIconResolver(
        final Map<NativeIconVariant, BufferedImage> prepared,
        final UiIconAvailability unavailable
    ) {
        this.unavailable = Objects.requireNonNull(unavailable, "unavailable");
        if (unavailable == UiIconAvailability.AVAILABLE || prepared.size() > 80) {
            throw new IllegalArgumentException("invalid native icon cache");
        }
        images = new HashMap<>();
        prepared.forEach((key, image) -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(image, "image");
            if (image.getWidth() != key.physicalSize() || image.getHeight() != key.physicalSize()) {
                throw new IllegalArgumentException("native icon dimensions do not match the variant");
            }
            images.put(key, image);
            handles.put(key, new CachedIcon(key));
        });
    }

    /**
     * Preloads from an already attested selector owner's code source, without initializing the
     * class or invoking its methods. The alias must belong to the supplied verified access plan.
     * Never accepts a plugin path, context classloader or claimed version as the public trust root.
     */
    public static CubismNativeIconResolver preload(
        final VerifiedMemberResolver resolver,
        final String anchorAlias
    ) {
        requireOffEdt();
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(anchorAlias, "anchorAlias");
        if (CubismNativeIconCatalog.artifact(resolver.cubismVersion()).isEmpty()) return unverified();
        try {
            final String ownerName = resolver.verifiedSelector(anchorAlias).ownerInternalName().replace('/', '.');
            final ClassLoader loader = resolver.hostClassLoader();
            final Class<?> owner = Class.forName(ownerName, false, loader);
            if (owner.getClassLoader() != loader) return unverified();
            final var source = owner.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null
                || !"file".equals(source.getLocation().getProtocol())) return unverified();
            return preloadArtifact(resolver.cubismVersion(), Path.of(source.getLocation().toURI()));
        } catch (ClassNotFoundException | java.net.URISyntaxException | RuntimeException | LinkageError failure) {
            return unverified();
        }
    }

    // Offline validation seam: still enforces the production version/size/hash pins. Not an SDK API.
    static CubismNativeIconResolver preloadArtifact(final String version, final Path artifact) {
        requireOffEdt();
        Objects.requireNonNull(artifact, "artifact");
        final var expected = CubismNativeIconCatalog.artifact(Objects.requireNonNull(version, "version"));
        if (expected.isEmpty()) return unverified();
        try {
            if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                || Files.size(artifact) != expected.get().size()
                || expected.get().size() > MAX_ARTIFACT_BYTES) return unverified();
            final byte[] snapshot;
            try (var stream = Files.newInputStream(artifact)) {
                snapshot = stream.readNBytes((int) expected.get().size() + 1);
            }
            // Decode precisely these measured bytes, never reopen the path after validation.
            if (snapshot.length != expected.get().size() || !sha256(snapshot).equals(expected.get().sha256())) {
                return unverified();
            }
            return decodeArchive(snapshot);
        } catch (IOException | RuntimeException failure) {
            return unverified();
        }
    }

    private static CubismNativeIconResolver decodeArchive(final byte[] snapshot) throws IOException {
        final Map<String, NativeIconVariant> allowed = new HashMap<>();
        CubismNativeIconCatalog.resources().keySet().forEach(key -> allowed.put(key.resourcePath(), key));
        final Map<NativeIconVariant, BufferedImage> prepared = new HashMap<>();
        final Set<String> seen = new HashSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(snapshot))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                final NativeIconVariant key = allowed.get(entry.getName());
                if (key == null) continue;
                if (!seen.add(entry.getName())) {
                    final BufferedImage duplicate = prepared.remove(key);
                    if (duplicate != null) duplicate.flush();
                    continue;
                }
                if (entry.isDirectory() || entry.getSize() > MAX_PNG_BYTES) continue;
                try {
                    final byte[] bytes = zip.readNBytes(MAX_PNG_BYTES + 1);
                    prepared.put(key, decodePng(bytes, CubismNativeIconCatalog.resources().get(key), key.physicalSize()));
                } catch (IOException | RuntimeException invalidResource) {
                    // One bad/missing variant cannot disable the other admitted variants.
                }
            }
            return new CubismNativeIconResolver(prepared, UiIconAvailability.RESOURCE_UNAVAILABLE);
        } catch (IOException | RuntimeException failure) {
            prepared.values().forEach(BufferedImage::flush);
            throw failure;
        }
    }

    static BufferedImage decodePng(final byte[] bytes, final String expectedHash, final int physicalSize)
        throws IOException {
        if (bytes.length < 33 || bytes.length > MAX_PNG_BYTES
            || physicalSize < 16 || physicalSize > 32 || physicalSize % 4 != 0
            || !sha256(bytes).equals(expectedHash)) throw new IOException("invalid native PNG identity or budget");
        final ByteBuffer header = ByteBuffer.wrap(bytes);
        if (header.getLong(0) != 0x89504e470d0a1a0aL || header.getInt(8) != 13
            || header.getInt(12) != 0x49484452 || header.getInt(16) != physicalSize
            || header.getInt(20) != physicalSize) throw new IOException("invalid native PNG header");
        // Explicit memory stream avoids ImageIO's optional process-global disk cache.
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            final var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("no native PNG decoder available");
            final var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != physicalSize || reader.getHeight(0) != physicalSize) {
                    throw new IOException("native PNG dimensions changed during decoding");
                }
                final BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("unreadable native PNG");
                if (image.getWidth() != physicalSize || image.getHeight() != physicalSize) {
                    image.flush();
                    throw new IOException("decoded native PNG dimensions do not match");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Constant-space cached query; no filesystem, host access or decoding. */
    public synchronized UiIconAvailability availability(final NativeIconVariant variant) {
        Objects.requireNonNull(variant, "variant");
        if (closed) return UiIconAvailability.SERVICE_UNAVAILABLE;
        return images.containsKey(variant) ? UiIconAvailability.AVAILABLE : unavailable;
    }

    /** Runtime-only display handle, not exposed by UiResourceService. */
    public synchronized Optional<Icon> resolve(final NativeIconVariant variant) {
        Objects.requireNonNull(variant, "variant");
        return closed ? Optional.empty() : Optional.ofNullable(handles.get(variant));
    }

    @Override
    public synchronized void close() {
        closed = true;
        images.values().forEach(BufferedImage::flush);
        images.clear();
        handles.clear();
    }

    private static CubismNativeIconResolver unverified() {
        return new CubismNativeIconResolver(Map.of(), UiIconAvailability.HOST_UNVERIFIED);
    }

    private static void requireOffEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("native icon preload must run off the EDT");
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private final class CachedIcon implements Icon {
        private final NativeIconVariant key;
        private CachedIcon(final NativeIconVariant key) { this.key = key; }
        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }
        @Override public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
            synchronized (CubismNativeIconResolver.this) {
                final BufferedImage image = images.get(key);
                if (!closed && image != null) graphics.drawImage(image, x, y, 16, 16, null);
            }
        }
    }
}
