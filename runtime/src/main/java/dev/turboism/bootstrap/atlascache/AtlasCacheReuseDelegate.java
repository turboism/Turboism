package dev.turboism.bootstrap.atlascache;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Content-signature guard for {@code CTextureAtlas.updateTexture(boolean, boolean, a.a)}.
 *
 * <p>The host rebuilds the whole atlas page on every explicit {@code updateTexture(true, …)}
 * call — including the export path, which iterates the live document's texture atlases and
 * recomposites every page even when nothing has changed since the editor session already
 * built the same pixels. The host's own {@code isDirty_cachedAtlasImage} flag is only ever
 * set at construction/copy time; no mutation path marks it, so callers cannot use it to
 * avoid redundant rebuilds.</p>
 *
 * <p>This delegate derives a full input signature for the page composite —
 * {@code setupCacheImage$cubism} reads exactly: page width/height, the ordered
 * {@code modelImages} entry list (each entry's guid and
 * {@code calcModelImageLocalToAtlasTransform()}), each resolved {@code CModelImage}'s
 * {@code modelImageVersion} (incremented by {@code CModelImage.reset()}, the single
 * invalidation point for the filtered image), and the filtered image's pixels themselves.
 * Hashing the pixels — not just version counters — keeps the guard sound against any
 * in-place mutation path the invalidation analysis did not reach.</p>
 *
 * <p>A recorded signature is stored per atlas instance in a {@link WeakHashMap} together
 * with the identity of the {@code cachedAtlasImage} it was built for, and in a bounded
 * signature→output table retaining the produced {@code CWritableImage}. Fresh deep-copied
 * atlas instances arrive with no cache at all (the document template's cache stays
 * {@code null}), so for an unknown instance whose signature matches a recorded build the
 * delegate supplies a {@code copyAs} duplicate of the recorded output — the same deep
 * copy the host's own {@code reinit} performs — rather than rebuilding. {@link #tryReuse}
 * returns {@code true} only when the installed or supplied cache is provably the exact
 * output the current inputs would produce. Every failure, unknown shape, or uncomputable
 * input returns {@code false}, which simply runs the host's original rebuild — the guard
 * can only ever fall back to stock behavior.</p>
 *
 * <p>Semantics preserved on the skip path: {@code cachedImageManager.a(cachedAtlasImage)}
 * still runs (it lives in the patched caller's tail), {@code atlasVersion} is not
 * incremented — nothing external reads it — and {@code isDirty} stays false, matching the
 * post-build state the host itself would have produced.</p>
 *
 * <p>Because each {@code CTextureAtlas} instance is one atlas page, the guard also yields
 * per-page dirty tracking for free: an edit that only moves tiles on some pages leaves the
 * other pages' signatures intact, so only changed pages rebuild.</p>
 */
public final class AtlasCacheReuseDelegate {

    /** Bound on one filtered image's pixels for hashing; larger → fall back to rebuild. */
    private static final long MAX_PIXELS = 64L * 1024L * 1024L;

    /**
     * Per-instance record of the last build: the exact {@code cachedAtlasImage} object the
     * signature belongs to plus the signature itself. Weak keys keep atlas lifetime owned
     * by the host; the stored cache reference is one the atlas itself already retains.
     */
    private static final Map<Object, Record> RECORDS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Signature → produced-cache record, for atlas instances the record table has never
     * seen. Real-host runs showed editor re-opens hand {@code updateTexture} fresh
     * deep-copied atlas instances whose {@code cachedAtlasImage} is {@code null} — the
     * source copies' built images die with them. Retaining the produced
     * {@code CWritableImage} here lets a later instance whose draw signature matches a
     * recorded build receive a {@code CWritableImage.copyAs} duplicate of that exact
     * output (the same copy {@code CTextureAtlas.reinit} performs) instead of rebuilding.
     * Entries are bounded by count and by retained pixel bytes, eldest-evicted.
     */
    private static final int MAX_OUTPUTS = 128;
    private static final long MAX_RETAINED_BYTES = Long.parseLong(System.getProperty(
        "turboism.atlasCacheReuse.retainedImageBytes",
        String.valueOf(1024L * 1024L * 1024L)));
    private static final Map<Object, Output> OUTPUTS =
        new LinkedHashMap<>(64, 0.75f, false);
    private static long retainedBytes;

    private static volatile HostAccess access;

    /**
     * Optional file mirror for verdicts; set once via
     * {@code -Dturboism.atlasCacheReuse.evidenceFile=<path>}. Validation runs only.
     */
    private static final String evidenceFile =
        System.getProperty("turboism.atlasCacheReuse.evidenceFile");

    private AtlasCacheReuseDelegate() {
    }

    /**
     * Proves every host handle resolves through the loader that defined the patched class.
     *
     * @return {@code null} when all handles resolve, otherwise a diagnostic detail
     */
    public static String verifyHostAccess(final ClassLoader hostLoader) {
        // MUST NOT run inside a ClassFileTransformer callback: getMethod on a side class
        // resolves its method signature types, and ModelImageEntry's synthetic constructor
        // references CTextureAtlas — resolving it mid-transform recursively defines the
        // very class being patched; the nested definition bypasses the transformer, wins
        // registration, and the patched outer definition dies on a duplicate-class
        // LinkageError that then fails host admission. The same verification already runs
        // lazily in load() at the first updateTexture call, when the class is fully
        // defined; this entry point exists for tests only.
        try {
            loadSideHandles(hostLoader);
            return null;
        } catch (IllegalStateException unavailable) {
            return String.valueOf(unavailable.getMessage());
        }
    }

    /**
     * Called from the patched {@code updateTexture} before {@code setupCacheImage$cubism}.
     *
     * @return {@code true} only when the installed cache was built for exactly the inputs
     *         that would be drawn now — skipping the rebuild is then pixel-identical
     */
    public static boolean tryReuse(final Class<?> hostClass, final Object atlas,
                                   final boolean privatePath) {
        try {
            final HostAccess host = resolve(hostClass);
            final Object current = signature(host, atlas, privatePath);
            if (current == null) return report(atlas, "miss:unreadable-signature");
            final Object cache = host.cachedAtlasImage.get(atlas);
            final Record stored = RECORDS.get(atlas);
            if (cache == null) {
                // Fresh deep copies arrive with no cache at all; when a recorded build
                // for the same inputs still retains its output image, install a copy of
                // it — the rebuild would reproduce those exact pixels.
                final Output output = output(current);
                if (output != null && output.image != null
                        && supply(host, atlas, output.image)) {
                    RECORDS.put(atlas,
                        new Record(host.cachedAtlasImage.get(atlas), current));
                    return report(atlas, "reuse:supplied");
                }
                return report(atlas,
                    stored == null ? "miss:no-cache" : "miss:cache-cleared");
            }
            if (stored != null) {
                if (stored.cache != cache) return report(atlas, "miss:cache-replaced");
                return report(atlas,
                    current.equals(stored.signature) ? "reuse" : "miss:signature-changed");
            }
            // Unknown instance with an installed cache — a deep copy carrying the
            // source's pixels. Reuse when the installed pixels already equal the
            // recorded output; when they differ the recorded output is still the
            // correct result for these inputs, so a supplied copy is equally sound.
            final BufferedImage installed = bufferedImage(host, cache);
            final String installedDigest =
                installed == null ? null : pixelDigest(installed);
            final Output output = output(current);
            if (output == null) {
                return report(atlas, "miss:first-build installed="
                    + shortDigest(installedDigest));
            }
            if (output.digest.equals(installedDigest)) {
                return report(atlas, "reuse:content");
            }
            if (output.image != null && supply(host, atlas, output.image)) {
                RECORDS.put(atlas,
                    new Record(host.cachedAtlasImage.get(atlas), current));
                return report(atlas, "reuse:supplied installed="
                    + shortDigest(installedDigest));
            }
            return report(atlas, "miss:cache-content-differs installed="
                + shortDigest(installedDigest));
        } catch (Throwable unreadable) {
            return report(atlas, "miss:exception");
        }
    }

    /**
     * Called from the patched {@code updateTexture} immediately after
     * {@code setupCacheImage$cubism} returns normally, recording the signature the freshly
     * built cache was produced from. A failed rebuild never reaches this call, so no stale
     * signature can be recorded for an aborted build.
     */
    public static void rebuilt(final Class<?> hostClass, final Object atlas,
                               final boolean privatePath) {
        try {
            final HostAccess host = resolve(hostClass);
            final Object cache = host.cachedAtlasImage.get(atlas);
            if (cache == null) return;
            final Object signature = signature(host, atlas, privatePath);
            if (signature != null) {
                RECORDS.put(atlas, new Record(cache, signature));
                final BufferedImage produced = bufferedImage(host, cache);
                if (produced != null) {
                    recordOutput(signature, pixelDigest(produced),
                        host.resImage.invoke(cache),
                        (long) produced.getWidth() * produced.getHeight() * 4L);
                }
            }
        } catch (Throwable unrecordable) {
            // Losing the record only loses reuse; the host rebuild stays correct.
        }
    }

    /**
     * One bounded diagnostic per {@code updateTexture} decision. Calls are rare (one per
     * page per rebuild request), so the volume stays at a handful of lines per session.
     * The verdict always goes to stderr; when {@code turboism.atlasCacheReuse.evidenceFile}
     * names a path it is also appended there — the host's late-session stderr is swallowed,
     * so validation runs set the property to keep hit evidence durable.
     */
    private static boolean report(final Object atlas, final String verdict) {
        final String line = "[turboism] atlas-cache-reuse " + verdict
            + " atlas=" + Integer.toHexString(System.identityHashCode(atlas))
            + " loader=" + Integer.toHexString(System.identityHashCode(
                atlas.getClass().getClassLoader()))
            + " thread=" + Thread.currentThread().getName();
        System.err.println(line);
        final String evidence = evidenceFile;
        if (evidence != null) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(evidence),
                    line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Evidence capture must never affect the guard decision.
            }
        }
        return verdict.startsWith("reuse");
    }

    /** First 8 hex chars of a digest for bounded verdict diagnostics. */
    private static String shortDigest(final String digest) {
        if (digest == null) return "null";
        return digest.length() <= 8 ? digest : digest.substring(0, 8);
    }

    private static Output output(final Object signature) {
        synchronized (OUTPUTS) {
            return OUTPUTS.get(signature);
        }
    }

    /**
     * Records the output a build produced, retaining the {@code CWritableImage} so a
     * later instance with the same signature can be supplied a copy. Retained bytes are
     * bounded by {@code turboism.atlasCacheReuse.retainedImageBytes}; an image that alone
     * exceeds the budget is not retained (its digest still is).
     */
    private static void recordOutput(final Object signature, final String digest,
                                     final Object image, final long bytes) {
        synchronized (OUTPUTS) {
            final Output previous = OUTPUTS.remove(signature);
            if (previous != null) retainedBytes -= previous.bytes;
            final boolean retain = image != null && bytes <= MAX_RETAINED_BYTES;
            OUTPUTS.put(signature,
                new Output(digest, retain ? image : null, retain ? bytes : 0));
            if (retain) retainedBytes += bytes;
            final Iterator<Map.Entry<Object, Output>> eldest =
                OUTPUTS.entrySet().iterator();
            while (retainedBytes > MAX_RETAINED_BYTES && eldest.hasNext()) {
                final Map.Entry<Object, Output> entry = eldest.next();
                if (entry.getValue().image == null) continue;
                retainedBytes -= entry.getValue().bytes;
                entry.setValue(new Output(entry.getValue().digest, null, 0));
            }
            final Iterator<Map.Entry<Object, Output>> excess =
                OUTPUTS.entrySet().iterator();
            while (OUTPUTS.size() > MAX_OUTPUTS && excess.hasNext()) {
                retainedBytes -= excess.next().getValue().bytes;
                excess.remove();
            }
        }
    }

    /**
     * Installs a {@code CWritableImage.copyAs} duplicate of a recorded output as this
     * atlas's cache — the same deep copy {@code CTextureAtlas.reinit} performs
     * ({@code copyAs(null, true)} wrapped in {@code new CImageResource(image,
     * DEFAULT_COLOR_TYPE, false)}). A fresh copy is mandatory: the installed resource is
     * per-atlas mutable state and must never be aliased between instances.
     *
     * @return {@code true} when the supplied resource is installed
     */
    private static boolean supply(final HostAccess host, final Object atlas,
                                  final Object image) {
        try {
            final Object copy = host.wiCopyAs.invoke(image, null, true);
            if (copy == null) return false;
            final Object resource =
                host.resCtor.newInstance(copy, host.defaultColorType, false);
            host.setCachedAtlasImage.invoke(atlas, resource);
            if (host.cachedAtlasImage.get(atlas) != resource) return false;
            // Post-build parity: setupCacheImage$cubism's tail clears the dirty flag
            // after installing the cache; the supplied output is that same end state.
            host.setDirty.invoke(atlas, false);
            return true;
        } catch (Throwable unsupplied) {
            return false;
        }
    }

    /** Test-only visibility into the record table size. */
    static int recordCount() {
        return RECORDS.size();
    }

    /** Test-only visibility into the output-digest table size. */
    static int outputCount() {
        return OUTPUTS.size();
    }

    /** Test-only removal of all recorded signatures. */
    static void clearRecords() {
        RECORDS.clear();
        synchronized (OUTPUTS) {
            OUTPUTS.clear();
            retainedBytes = 0;
        }
    }

    /**
     * The complete draw-input signature, or {@code null} when any part cannot be read —
     * the caller then rebuilds, which is always safe.
     */
    private static Object signature(final HostAccess host, final Object atlas,
                                    final boolean privatePath) throws Exception {
        final List<Object> sig = new ArrayList<>();
        sig.add(host.width.getInt(atlas));
        sig.add(host.height.getInt(atlas));
        sig.add(privatePath);
        final Object entries = host.modelImages.get(atlas);
        if (!(entries instanceof Iterable<?> list)) return null;
        for (final Object entry : list) {
            if (entry == null) {
                sig.add("entry:null");
                continue;
            }
            final List<Object> term = new ArrayList<>(4);
            final Object guid = host.entryGuid.invoke(entry);
            term.add(guid == null
                ? "guid:null"
                : String.valueOf(host.guidUuid.invoke(guid)));
            final Object affine = host.entryTransform.invoke(entry);
            term.add(affine == null ? "tx:null" : matrixKey(host, affine));
            final Object modelImage = host.entryModelImage.invoke(entry);
            term.add(modelImage == null ? "mi:null" : modelImageTerm(host, modelImage));
            sig.add(term);
        }
        return sig;
    }

    private static Object modelImageTerm(final HostAccess host, final Object modelImage)
            throws Exception {
        final List<Object> term = new ArrayList<>(3);
        term.add(host.miVersion.invoke(modelImage));
        final Object resource = host.miFiltered.invoke(modelImage);
        if (resource == null) {
            term.add("img:null");
            return term;
        }
        final BufferedImage pixels = bufferedImage(host, resource);
        if (pixels == null) {
            term.add("img:unreadable");
            return term;
        }
        term.add(pixels.getWidth());
        term.add(pixels.getHeight());
        term.add(pixelDigest(pixels));
        return term;
    }

    private static BufferedImage bufferedImage(final HostAccess host, final Object resource)
            throws Exception {
        final Object writable = host.resImage.invoke(resource);
        if (writable == null) return null;
        final Object image = host.wiBuffered.invoke(writable);
        return image instanceof BufferedImage buffered ? buffered : null;
    }

    /** Canonical float[6] key — bit-exact compare, no tolerance. */
    private static Object matrixKey(final HostAccess host, final Object affine)
            throws Exception {
        final Object matrix = host.affineMatrix.invoke(affine);
        if (!(matrix instanceof float[] m) || m.length != 6) return "tx:bad";
        final StringBuilder key = new StringBuilder(6 * 9);
        for (final float v : m) {
            key.append(Integer.toHexString(Float.floatToIntBits(v))).append(',');
        }
        return key.toString();
    }

    /** SHA-256 over the image's ARGB int raster; DataBufferInt fast path when available. */
    private static String pixelDigest(final BufferedImage image) {
        final long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= 0 || pixels > MAX_PIXELS) return "img:" + pixels;
        final MessageDigest sha = sha256();
        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if (buffer instanceof DataBufferInt ints
                && image.getRaster().getDataBuffer().getSize()
                        == image.getWidth() * image.getHeight()) {
            for (final int v : ints.getData()) {
                sha.update((byte) (v >>> 24));
                sha.update((byte) (v >>> 16));
                sha.update((byte) (v >>> 8));
                sha.update((byte) v);
            }
        } else {
            final int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
                for (final int v : row) {
                    sha.update((byte) (v >>> 24));
                    sha.update((byte) (v >>> 16));
                    sha.update((byte) (v >>> 8));
                    sha.update((byte) v);
                }
            }
        }
        final byte[] digest = sha.digest();
        final StringBuilder hex = new StringBuilder(64);
        for (final byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static HostAccess resolve(final Class<?> hostClass) {
        HostAccess resolved = access;
        if (resolved == null || resolved.atlasClass != hostClass) {
            synchronized (AtlasCacheReuseDelegate.class) {
                resolved = access;
                if (resolved == null || resolved.atlasClass != hostClass) {
                    resolved = load(hostClass);
                    access = resolved;
                }
            }
        }
        return resolved;
    }

    private static HostAccess load(final Class<?> atlas) {
        final ClassLoader loader = atlas.getClassLoader();
        if (loader == null) {
            throw new IllegalStateException(
                "atlas cache-reuse: patched class is bootstrap-loaded; host unreachable");
        }
        final SideHandles side = loadSideHandles(loader);
        try {
            return new HostAccess(atlas,
                field(atlas, "cachedAtlasImage"),
                field(atlas, "width"),
                field(atlas, "height"),
                field(atlas, "modelImages"),
                atlas.getMethod("setCachedAtlasImage", side.resourceClass),
                atlas.getMethod("setDirty_cachedAtlasImage", boolean.class),
                side);
        } catch (NoSuchFieldException | NoSuchMethodException missing) {
            throw new IllegalStateException(
                "atlas cache-reuse atlas member missing", missing);
        }
    }

    /**
     * Resolves every non-atlas handle. Call only after {@code CTextureAtlas} is fully
     * defined — {@code getMethod} on {@code ModelImageEntry} resolves its synthetic
     * constructor's signature, which references the enclosing atlas class.
     */
    private static SideHandles loadSideHandles(final ClassLoader loader) {
        try {
            final Class<?> entry = Class.forName(
                "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas$ModelImageEntry",
                false, loader);
            final Class<?> guid = Class.forName("com.live2d.type.Guid", false, loader);
            final Class<?> affine = Class.forName("com.live2d.type.CAffine", false, loader);
            final Class<?> modelImage = Class.forName(
                "com.live2d.cubism.doc.model.texture.modelImage.CModelImage", false, loader);
            final Class<?> resource =
                Class.forName("com.live2d.graphics.CImageResource", false, loader);
            final Class<?> writable =
                Class.forName("com.live2d.graphics.CWritableImage", false, loader);
            final Class<?> colorType =
                Class.forName("com.live2d.graphics.n", false, loader);
            final Field defaultColorType =
                resource.getDeclaredField("DEFAULT_COLOR_TYPE");
            defaultColorType.setAccessible(true);
            return new SideHandles(
                entry.getMethod("getModelImageGuid"),
                guid.getMethod("getUuidString"),
                entry.getMethod("calcModelImageLocalToAtlasTransform"),
                affine.getMethod("getMatrix"),
                entry.getMethod("getModelImage"),
                modelImage.getMethod("getModelImageVersion"),
                modelImage.getMethod("getFilteredImage"),
                resource.getMethod("getImage"),
                writable.getMethod("getJBufferedImage"),
                writable.getMethod("copyAs", colorType, boolean.class),
                resource.getConstructor(writable, colorType, boolean.class),
                defaultColorType.get(null));
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException(
                "atlas cache-reuse host member missing", missing);
        }
    }

    private static Field field(final Class<?> owner, final String name)
            throws NoSuchFieldException {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Record {
        private final Object cache;
        private final Object signature;

        private Record(final Object cache, final Object signature) {
            this.cache = cache;
            this.signature = signature;
        }
    }

    /**
     * One recorded build output: the produced pixels' digest plus the produced
     * {@code CWritableImage} while retained. {@code image} is nulled on byte-budget
     * eviction; the digest then still serves content-verified reuse.
     */
    private static final class Output {
        private final String digest;
        private final Object image;
        private final long bytes;

        private Output(final String digest, final Object image, final long bytes) {
            this.digest = digest;
            this.image = image;
            this.bytes = bytes;
        }
    }

    /** The host members the delegate reads; atlas fields bind the exact patched class. */
    private static final class HostAccess {
        private final Class<?> atlasClass;
        private final Field cachedAtlasImage;
        private final Field width;
        private final Field height;
        private final Field modelImages;
        private final Method entryGuid;
        private final Method guidUuid;
        private final Method entryTransform;
        private final Method affineMatrix;
        private final Method entryModelImage;
        private final Method miVersion;
        private final Method miFiltered;
        private final Method resImage;
        private final Method wiBuffered;
        private final Method wiCopyAs;
        private final Constructor<?> resCtor;
        private final Object defaultColorType;
        private final Method setCachedAtlasImage;
        private final Method setDirty;

        private HostAccess(final Class<?> atlasClass, final Field cachedAtlasImage,
                           final Field width, final Field height, final Field modelImages,
                           final Method setCachedAtlasImage, final Method setDirty,
                           final SideHandles side) {
            this.atlasClass = atlasClass;
            this.cachedAtlasImage = cachedAtlasImage;
            this.width = width;
            this.height = height;
            this.modelImages = modelImages;
            this.setCachedAtlasImage = setCachedAtlasImage;
            this.setDirty = setDirty;
            this.entryGuid = side.entryGuid;
            this.guidUuid = side.guidUuid;
            this.entryTransform = side.entryTransform;
            this.affineMatrix = side.affineMatrix;
            this.entryModelImage = side.entryModelImage;
            this.miVersion = side.miVersion;
            this.miFiltered = side.miFiltered;
            this.resImage = side.resImage;
            this.wiBuffered = side.wiBuffered;
            this.wiCopyAs = side.wiCopyAs;
            this.resCtor = side.resCtor;
            this.defaultColorType = side.defaultColorType;
        }
    }

    /** Non-atlas host handles, resolvable before the patched class is defined. */
    private static final class SideHandles {
        private final Method entryGuid;
        private final Method guidUuid;
        private final Method entryTransform;
        private final Method affineMatrix;
        private final Method entryModelImage;
        private final Method miVersion;
        private final Method miFiltered;
        private final Method resImage;
        private final Method wiBuffered;
        private final Method wiCopyAs;
        private final Constructor<?> resCtor;
        private final Object defaultColorType;
        private final Class<?> resourceClass;

        private SideHandles(final Method entryGuid, final Method guidUuid,
                            final Method entryTransform, final Method affineMatrix,
                            final Method entryModelImage, final Method miVersion,
                            final Method miFiltered, final Method resImage,
                            final Method wiBuffered, final Method wiCopyAs,
                            final Constructor<?> resCtor,
                            final Object defaultColorType) {
            this.entryGuid = entryGuid;
            this.guidUuid = guidUuid;
            this.entryTransform = entryTransform;
            this.affineMatrix = affineMatrix;
            this.entryModelImage = entryModelImage;
            this.miVersion = miVersion;
            this.miFiltered = miFiltered;
            this.resImage = resImage;
            this.wiBuffered = wiBuffered;
            this.wiCopyAs = wiCopyAs;
            this.resCtor = resCtor;
            this.defaultColorType = defaultColorType;
            this.resourceClass = resImage.getDeclaringClass();
        }
    }
}
