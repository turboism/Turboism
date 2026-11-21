package dev.turboism.adapter.cubism.exportperf;

import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eliminates redundant PNG round-trips and 2nd model setups during runtime-export cloning.
 *
 * <p>Scoped strictly around the export model serializer-deserializer clone via ThreadLocal tokens so
 * normal project save, auto-backup, and copy-paste serialization paths keep stock ZIP behavior.</p>
 */
public final class RuntimeExportScope {

    /** XML attribute key storing opaque resource tokens inside the transient export clone archive. */
    public static final String IMAGE_RESOURCE_TOKEN_ATTR = "turboismExportToken";

    /** XML attribute key storing opaque writable-image tokens inside the export clone archive. */
    public static final String WRITABLE_IMAGE_TOKEN_ATTR = "turboismWritableToken";

    private static final String IMAGE_RESOURCE_CLASS = "com.live2d.graphics.CImageResource";
    private static final String WRITABLE_IMAGE_CLASS = "com.live2d.graphics.CWritableImage";
    private static final String CIMAGE_CLASS = "com.live2d.graphics.CImage";
    private static final String COLOR_TYPE_CLASS = "com.live2d.graphics.n";
    private static final String EXTENDED_INTERP_EXT_SIMPLE = "CExtendedInterpolationExtension";
    private static final String ATTR_WIDTH = "width";
    private static final String ATTR_HEIGHT = "height";
    private static final String ATTR_TYPE = "type";

    private static final ThreadLocal<ScopeState> ACTIVE_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<Object> PENDING_ATLAS = new ThreadLocal<>();

    private static volatile ResourceHandles resourceHandles;
    private static volatile WritableHandles writableHandles;
    private static volatile ElementHandles elementHandles;
    private static volatile AtlasHandles atlasHandles;
    private static volatile ModelSourceHandles modelSourceHandles;

    private RuntimeExportScope() {}

    /**
     * Enters a scoped export clone context and records clean source atlas pages before cloning.
     *
     * @param sourceModel the source {@code CModelSource} being cloned for runtime export
     */
    public static void enter(final Object sourceModel) {
        final ScopeState existing = ACTIVE_SCOPE.get();
        if (existing != null) {
            existing.depth++;
            return;
        }
        final ScopeState created = new ScopeState();
        ACTIVE_SCOPE.set(created);
        if (sourceModel != null) {
            captureCleanAtlases(sourceModel, created);
        }
    }

    /**
     * Leaves the active export clone context and releases all retained token references on exit.
     */
    public static void exit() {
        final ScopeState state = ACTIVE_SCOPE.get();
        if (state == null) {
            PENDING_ATLAS.remove();
            return;
        }
        state.depth--;
        if (state.depth <= 0) {
            PENDING_ATLAS.remove();
            ACTIVE_SCOPE.remove();
        }
    }

    /**
     * Returns whether the current thread is inside an active runtime export clone scope.
     *
     * @return {@code true} when token-based image transfer is enabled on this thread
     */
    public static boolean isActive() {
        return ACTIVE_SCOPE.get() != null;
    }

    /**
     * Serializes {@code CImageResource} into a lightweight scope token when exporting on-thread.
     *
     * @param resource target {@code CImageResource} instance
     * @param element XML element receiving metadata attributes
     * @return {@code true} if token transfer handled serialization, {@code false} for stock path
     */
    public static boolean trySerializeImageResource(final Object resource, final Object element) {
        final ScopeState state = ACTIVE_SCOPE.get();
        if (state == null || resource == null || element == null) {
            return false;
        }
        try {
            final ResourceHandles res = resolveResourceHandles(resource.getClass());
            final Object image = res.image.get(resource);
            final byte[] imageBuf = (byte[]) res.imageFileBuf.get(resource);
            if (image == null && imageBuf == null) {
                return false;
            }
            final Object previewImage = res.previewImage.get(resource);
            final byte[] previewBuf = (byte[]) res.previewImageFileBuf.get(resource);
            final int width = res.width.getInt(resource);
            final int height = res.height.getInt(resource);
            final Object type = res.type.get(resource);

            final long id = state.nextTokenId++;
            state.resources.put(
                    id, new ResourceSnapshot(width, height, type, image, imageBuf, previewImage, previewBuf));
            final ElementHandles elem = resolveElementHandles(element.getClass());
            elem.setAttribute.invoke(element, IMAGE_RESOURCE_TOKEN_ATTR, Long.toString(id));
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Restores a {@code CImageResource} from its scope token without decoding PNG payloads.
     *
     * @param resource newly allocated {@code CImageResource} target
     * @param element XML element carrying serialization attributes
     * @return {@code true} if token transfer restored the resource, {@code false} for stock path
     */
    public static boolean tryDeserializeImageResource(final Object resource, final Object element) {
        final ScopeState state = ACTIVE_SCOPE.get();
        if (state == null || resource == null || element == null) {
            return false;
        }
        try {
            final ElementHandles elem = resolveElementHandles(element.getClass());
            final Object attr = elem.getAttributeValue.invoke(element, IMAGE_RESOURCE_TOKEN_ATTR);
            if (!(attr instanceof String tokenStr) || tokenStr.isEmpty()) {
                return false;
            }
            final long id = Long.parseLong(tokenStr);
            final ResourceSnapshot snap = state.resources.get(id);
            if (snap == null) {
                return false;
            }
            final ResourceHandles res = resolveResourceHandles(resource.getClass());
            res.width.setInt(resource, snap.width);
            res.height.setInt(resource, snap.height);
            res.type.set(resource, snap.type);
            res.image.set(resource, snap.image);
            res.imageFileBuf.set(resource, snap.imageBuf);
            res.previewImage.set(resource, snap.previewImage);
            res.previewImageFileBuf.set(resource, snap.previewBuf);
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Serializes standalone {@code CWritableImage} metadata via an opaque scope token.
     *
     * @param writable source {@code CWritableImage} instance
     * @param element XML element receiving serialization attributes
     * @return {@code true} if token transfer handled serialization, {@code false} for stock path
     */
    public static boolean trySerializeWritableImage(final Object writable, final Object element) {
        final ScopeState state = ACTIVE_SCOPE.get();
        if (state == null || writable == null || element == null) {
            return false;
        }
        try {
            final WritableHandles wi = resolveWritableHandles(writable.getClass());
            final BufferedImage buf = (BufferedImage) wi.getJBufferedImage.invoke(writable);
            final byte[] preloadedBytes = (byte[]) wi.preloadedBytes.get(writable);
            final Object preloadedType = wi.preloadedType.get(writable);
            final Integer preloadedW = (Integer) wi.preloadedWidth.get(writable);
            final Integer preloadedH = (Integer) wi.preloadedHeight.get(writable);

            final long id = state.nextTokenId++;
            state.writables.put(id, new WritableSnapshot(buf, preloadedBytes, preloadedType, preloadedW, preloadedH));
            final ElementHandles elem = resolveElementHandles(element.getClass());
            elem.setAttribute.invoke(element, WRITABLE_IMAGE_TOKEN_ATTR, Long.toString(id));
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Restores a standalone {@code CWritableImage} from its scope token without PNG re-encoding.
     *
     * @param writable newly created {@code CWritableImage} instance
     * @param element XML element carrying serialization attributes
     * @return {@code true} if token transfer restored the image, {@code false} for stock path
     */
    public static boolean tryDeserializeWritableImage(final Object writable, final Object element) {
        final ScopeState state = ACTIVE_SCOPE.get();
        if (state == null || writable == null || element == null) {
            return false;
        }
        try {
            final ElementHandles elem = resolveElementHandles(element.getClass());
            final Object attr = elem.getAttributeValue.invoke(element, WRITABLE_IMAGE_TOKEN_ATTR);
            if (!(attr instanceof String tokenStr) || tokenStr.isEmpty()) {
                return false;
            }
            final long id = Long.parseLong(tokenStr);
            final WritableSnapshot snap = state.writables.get(id);
            if (snap == null) {
                return false;
            }
            final WritableHandles wi = resolveWritableHandles(writable.getClass());
            wi.jImageField.set(writable, snap.bufferedImage);
            wi.preloadedBytes.set(writable, snap.preloadedBytes);
            wi.preloadedType.set(writable, snap.preloadedType);
            wi.preloadedWidth.set(writable, snap.preloadedWidth);
            wi.preloadedHeight.set(writable, snap.preloadedHeight);
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Records the {@code CTextureAtlas} entering {@code onDeserialized} so post-deserialization
     * cleanup need not reload {@code this} at dead-local return points.
     *
     * @param atlas deserializing {@code CTextureAtlas} instance
     */
    public static void beginAtlasDeserialized(final Object atlas) {
        if (ACTIVE_SCOPE.get() != null && atlas != null) {
            PENDING_ATLAS.set(atlas);
        }
    }

    /**
     * Completes clean atlas dirty-state propagation for the atlas recorded by
     * {@link #beginAtlasDeserialized(Object)}.
     */
    public static void endAtlasDeserialized() {
        final Object atlas = PENDING_ATLAS.get();
        PENDING_ATLAS.remove();
        if (atlas != null) {
            restoreCleanAtlasState(atlas);
        }
    }

    /**
     * Propagates clean source atlas dirty state onto the deserialized clone so up-to-date pages
     * skip redundant {@code AtlasCacheReuseDelegate} hashing and second rebuilds.
     *
     * @param atlas deserialized {@code CTextureAtlas} instance
     */
    public static void restoreCleanAtlasState(final Object atlas) {
        final ScopeState state = ACTIVE_SCOPE.get();
        if (state == null || atlas == null || state.cleanAtlasGuids.isEmpty()) {
            return;
        }
        try {
            final AtlasHandles ah = resolveAtlasHandles(atlas.getClass());
            final Object guid = ah.guid.get(atlas);
            final String uuid = guid == null ? null : String.valueOf(ah.getUuidString.invoke(guid));
            if (uuid == null || !state.cleanAtlasGuids.contains(uuid)) {
                return;
            }
            final Object cachedResource = ah.cachedAtlasImage.get(atlas);
            if (cachedResource == null) {
                return;
            }
            final ResourceHandles res = resolveResourceHandles(cachedResource.getClass());
            if (res.image.get(cachedResource) != null) {
                ah.isDirty.setBoolean(atlas, false);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Fast {@code TYPE_INT_ARGB} replacement for {@code CWritableImage.forceCopy(BufferedImage, int)}.
     *
     * @param source input {@code BufferedImage}
     * @param targetType requested {@code BufferedImage} type constant
     * @return copied image preserving {@code source.flush()}, or {@code null} to fall back
     */
    public static BufferedImage fastForceCopy(final BufferedImage source, final int targetType) {
        if (source == null
                || targetType != BufferedImage.TYPE_INT_ARGB
                || source.getType() != BufferedImage.TYPE_INT_ARGB) {
            return null;
        }
        final DataBuffer buffer = source.getRaster().getDataBuffer();
        if (!(buffer instanceof DataBufferInt srcInt) || srcInt.getNumBanks() != 1 || srcInt.getOffset() != 0) {
            return null;
        }
        final int width = source.getWidth();
        final int height = source.getHeight();
        final int[] srcData = srcInt.getData();
        if ((long) width * height > srcData.length) {
            return null;
        }
        final BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final int[] dstData = ((DataBufferInt) copy.getRaster().getDataBuffer()).getData();
        System.arraycopy(srcData, 0, dstData, 0, width * height);
        source.flush();
        return copy;
    }

    /**
     * Checks whether any object in {@code modelSource} carries an extended-interpolation extension.
     *
     * @param modelSource cloned {@code CModelSource} instance
     * @return {@code true} if extended-interpolation post-pass and 2nd setup must execute
     */
    public static boolean needsExtendedInterpolationPass(final Object modelSource) {
        if (modelSource == null) {
            return true;
        }
        try {
            final ModelSourceHandles mh = resolveModelSourceHandles(modelSource.getClass());
            final Object objects = mh.getAllObjects.invoke(modelSource);
            if (!(objects instanceof Iterable<?> iterable)) {
                return true;
            }
            for (final Object controllable : iterable) {
                if (controllable == null) {
                    continue;
                }
                final Object extensions = mh.getExtensions.invoke(controllable);
                if (extensions instanceof Iterable<?> extList) {
                    for (final Object ext : extList) {
                        if (ext != null
                                && EXTENDED_INTERP_EXT_SIMPLE.equals(
                                        ext.getClass().getSimpleName())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return true;
        }
    }

    private static void captureCleanAtlases(final Object sourceModel, final ScopeState state) {
        try {
            final ModelSourceHandles mh = resolveModelSourceHandles(sourceModel.getClass());
            final Object texMgr = mh.getTextureManager.invoke(sourceModel);
            if (texMgr == null) {
                return;
            }
            final Object atlases = mh.getTextureAtlases.invoke(texMgr);
            if (!(atlases instanceof List<?> list) || list.isEmpty()) {
                return;
            }
            final AtlasHandles ah = resolveAtlasHandles(list.get(0).getClass());
            for (final Object atlas : list) {
                if (atlas == null) {
                    continue;
                }
                final boolean dirty = ah.isDirty.getBoolean(atlas);
                final boolean locked = ah.lockCachedAtlasImage.getBoolean(atlas);
                final Object cachedRes = ah.cachedAtlasImage.get(atlas);
                if (cachedRes == null || (!locked && dirty)) {
                    continue;
                }
                final ResourceHandles rh = resolveResourceHandles(cachedRes.getClass());
                if (rh.image.get(cachedRes) == null) {
                    continue;
                }
                final int w = ah.width.getInt(atlas);
                final int h = ah.height.getInt(atlas);
                if (rh.width.getInt(cachedRes) == w && rh.height.getInt(cachedRes) == h) {
                    final Object guid = ah.guid.get(atlas);
                    if (guid != null) {
                        state.cleanAtlasGuids.add(String.valueOf(ah.getUuidString.invoke(guid)));
                    }
                }
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static ResourceHandles resolveResourceHandles(final Class<?> resClass) throws Exception {
        ResourceHandles cached = resourceHandles;
        if (cached != null && cached.owner == resClass) {
            return cached;
        }
        synchronized (RuntimeExportScope.class) {
            cached = resourceHandles;
            if (cached != null && cached.owner == resClass) {
                return cached;
            }
            cached = new ResourceHandles(resClass);
            resourceHandles = cached;
            return cached;
        }
    }

    private static WritableHandles resolveWritableHandles(final Class<?> wiClass) throws Exception {
        WritableHandles cached = writableHandles;
        if (cached != null && cached.owner == wiClass) {
            return cached;
        }
        synchronized (RuntimeExportScope.class) {
            cached = writableHandles;
            if (cached != null && cached.owner == wiClass) {
                return cached;
            }
            cached = new WritableHandles(wiClass);
            writableHandles = cached;
            return cached;
        }
    }

    private static ElementHandles resolveElementHandles(final Class<?> elemClass) throws Exception {
        ElementHandles cached = elementHandles;
        if (cached != null && cached.owner == elemClass) {
            return cached;
        }
        synchronized (RuntimeExportScope.class) {
            cached = elementHandles;
            if (cached != null && cached.owner == elemClass) {
                return cached;
            }
            cached = new ElementHandles(elemClass);
            elementHandles = cached;
            return cached;
        }
    }

    private static AtlasHandles resolveAtlasHandles(final Class<?> atlasClass) throws Exception {
        AtlasHandles cached = atlasHandles;
        if (cached != null && cached.owner == atlasClass) {
            return cached;
        }
        synchronized (RuntimeExportScope.class) {
            cached = atlasHandles;
            if (cached != null && cached.owner == atlasClass) {
                return cached;
            }
            cached = new AtlasHandles(atlasClass);
            atlasHandles = cached;
            return cached;
        }
    }

    private static ModelSourceHandles resolveModelSourceHandles(final Class<?> msClass) throws Exception {
        ModelSourceHandles cached = modelSourceHandles;
        if (cached != null && cached.owner == msClass) {
            return cached;
        }
        synchronized (RuntimeExportScope.class) {
            cached = modelSourceHandles;
            if (cached != null && cached.owner == msClass) {
                return cached;
            }
            cached = new ModelSourceHandles(msClass);
            modelSourceHandles = cached;
            return cached;
        }
    }

    private static Field openField(final Class<?> owner, final String name) throws NoSuchFieldException {
        final Field field = owner.getDeclaredField(name);
        ReflectAccess.open(field);
        return field;
    }

    private static final class ScopeState {
        private int depth = 1;
        private long nextTokenId = 1L;
        private final Map<Long, ResourceSnapshot> resources = new HashMap<>();
        private final Map<Long, WritableSnapshot> writables = new HashMap<>();
        private final Set<String> cleanAtlasGuids = new HashSet<>();
    }

    private static final class ResourceSnapshot {
        private final int width;
        private final int height;
        private final Object type;
        private final Object image;
        private final byte[] imageBuf;
        private final Object previewImage;
        private final byte[] previewBuf;

        ResourceSnapshot(
                final int width,
                final int height,
                final Object type,
                final Object image,
                final byte[] imageBuf,
                final Object previewImage,
                final byte[] previewBuf) {
            this.width = width;
            this.height = height;
            this.type = type;
            this.image = image;
            this.imageBuf = imageBuf;
            this.previewImage = previewImage;
            this.previewBuf = previewBuf;
        }
    }

    private static final class WritableSnapshot {
        private final BufferedImage bufferedImage;
        private final byte[] preloadedBytes;
        private final Object preloadedType;
        private final Integer preloadedWidth;
        private final Integer preloadedHeight;

        WritableSnapshot(
                final BufferedImage bufferedImage,
                final byte[] preloadedBytes,
                final Object preloadedType,
                final Integer preloadedWidth,
                final Integer preloadedHeight) {
            this.bufferedImage = bufferedImage;
            this.preloadedBytes = preloadedBytes;
            this.preloadedType = preloadedType;
            this.preloadedWidth = preloadedWidth;
            this.preloadedHeight = preloadedHeight;
        }
    }

    private static final class ResourceHandles {
        private final Class<?> owner;
        private final Field width;
        private final Field height;
        private final Field type;
        private final Field image;
        private final Field imageFileBuf;
        private final Field previewImage;
        private final Field previewImageFileBuf;

        ResourceHandles(final Class<?> owner) throws NoSuchFieldException {
            this.owner = owner;
            this.width = openField(owner, ATTR_WIDTH);
            this.height = openField(owner, ATTR_HEIGHT);
            this.type = openField(owner, ATTR_TYPE);
            this.image = openField(owner, "image");
            this.imageFileBuf = openField(owner, "imageFileBuf");
            this.previewImage = openField(owner, "previewImage");
            this.previewImageFileBuf = openField(owner, "previewImageFileBuf");
        }
    }

    private static final class WritableHandles {
        private final Class<?> owner;
        private final Method getJBufferedImage;
        private final Field jImageField;
        private final Field preloadedBytes;
        private final Field preloadedType;
        private final Field preloadedWidth;
        private final Field preloadedHeight;

        WritableHandles(final Class<?> owner)
                throws NoSuchMethodException, NoSuchFieldException, ClassNotFoundException {
            this.owner = owner;
            this.getJBufferedImage = owner.getMethod("getJBufferedImage");
            final Class<?> baseClass = Class.forName(CIMAGE_CLASS, false, owner.getClassLoader());
            this.jImageField = findJImageField(baseClass);
            this.preloadedBytes = openField(owner, "preloadedBytes");
            this.preloadedType = openField(owner, "preloadedType");
            this.preloadedWidth = openField(owner, "preloadedWidth");
            this.preloadedHeight = openField(owner, "preloadedHeight");
        }

        private static Field findJImageField(final Class<?> baseClass) throws NoSuchFieldException {
            for (final String candidate : new String[] {"jImage", "_jImage"}) {
                try {
                    return openField(baseClass, candidate);
                } catch (NoSuchFieldException ignored) {
                    // Check remaining field names across Cubism versions.
                }
            }
            for (final Field f : baseClass.getDeclaredFields()) {
                if (java.awt.Image.class.isAssignableFrom(f.getType())) {
                    ReflectAccess.open(f);
                    return f;
                }
            }
            throw new NoSuchFieldException("jImage");
        }
    }

    private static final class ElementHandles {
        private final Class<?> owner;
        private final Method setAttribute;
        private final Method getAttributeValue;

        ElementHandles(final Class<?> owner) throws NoSuchMethodException {
            this.owner = owner;
            this.setAttribute = owner.getMethod("setAttribute", String.class, String.class);
            this.getAttributeValue = owner.getMethod("getAttributeValue", String.class);
        }
    }

    private static final class AtlasHandles {
        private final Class<?> owner;
        private final Field width;
        private final Field height;
        private final Field cachedAtlasImage;
        private final Field lockCachedAtlasImage;
        private final Field isDirty;
        private final Field guid;
        private final Method getUuidString;

        AtlasHandles(final Class<?> owner) throws NoSuchFieldException, NoSuchMethodException, ClassNotFoundException {
            this.owner = owner;
            this.width = openField(owner, ATTR_WIDTH);
            this.height = openField(owner, ATTR_HEIGHT);
            this.cachedAtlasImage = openField(owner, "cachedAtlasImage");
            this.lockCachedAtlasImage = openField(owner, "lockCachedAtlasImage");
            this.isDirty = openField(owner, "isDirty_cachedAtlasImage");
            this.guid = openField(owner, "guid");
            final Class<?> guidClass = Class.forName("com.live2d.type.Guid", false, owner.getClassLoader());
            this.getUuidString = guidClass.getMethod("getUuidString");
        }
    }

    private static final class ModelSourceHandles {
        private final Class<?> owner;
        private final Method getTextureManager;
        private final Method getTextureAtlases;
        private final Method getAllObjects;
        private final Method getExtensions;

        ModelSourceHandles(final Class<?> owner) throws NoSuchMethodException, ClassNotFoundException {
            this.owner = owner;
            this.getTextureManager = owner.getMethod("getTextureManager");
            this.getTextureAtlases = this.getTextureManager.getReturnType().getMethod("getTextureAtlases");
            this.getAllObjects = owner.getMethod("getAllObjects");
            final Class<?> controllableClass = Class.forName(
                    "com.live2d.cubism.doc.model.ACParameterControllableSource", false, owner.getClassLoader());
            this.getExtensions = controllableClass.getMethod("getExtensions");
        }
    }
}
