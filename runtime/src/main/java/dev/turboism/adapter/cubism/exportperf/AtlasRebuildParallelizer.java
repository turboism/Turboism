package dev.turboism.adapter.cubism.exportperf;

import dev.turboism.adapter.cubism.optimization.atlas.AtlasPageRebuildCoordinator;
import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Parallelizes multi-page {@code CTextureAtlas.updateTexture} rebuilds with safe source warm-up.
 */
public final class AtlasRebuildParallelizer {

    private static final int MIN_PARALLEL_PAGES = 2;
    private static final String FIELD_CACHED_ATLAS_IMAGE = "cachedAtlasImage";
    private static final String FIELD_LOCK_CACHED_ATLAS_IMAGE = "lockCachedAtlasImage";
    private static final String FIELD_IS_DIRTY_CACHED_ATLAS_IMAGE = "isDirty_cachedAtlasImage";
    private static final String METHOD_GET_TEXTURE_ATLASES = "getTextureAtlases";

    private static volatile Handles handles;
    private static volatile TextureManagerHandles managerHandles;

    private AtlasRebuildParallelizer() {}

    /**
     * Rebuilds dirty atlas pages on {@code textureManager} concurrently before serial ArtMesh
     * switching calls {@code CTextureAtlas.getCachedImage()} one page at a time.
     *
     * @param textureManager host {@code CTextureManager} switching to atlas input mode
     */
    public static void prewarmDirtyAtlases(final Object textureManager) {
        if (textureManager == null) {
            return;
        }
        try {
            final TextureManagerHandles mh = resolveManagerHandles(textureManager.getClass());
            final Object atlasesObj = mh.getTextureAtlases.invoke(textureManager);
            if (!(atlasesObj instanceof List<?> allAtlases) || allAtlases.size() < MIN_PARALLEL_PAGES) {
                return;
            }
            final List<Object> dirtyAtlases = new ArrayList<>(allAtlases.size());
            final Handles h = resolveHandles(allAtlases.get(0).getClass());
            for (final Object atlas : allAtlases) {
                if (atlas == null) {
                    continue;
                }
                final Object cached = h.cachedAtlasImage.get(atlas);
                final boolean locked = h.lockCachedAtlasImage.getBoolean(atlas);
                final boolean dirty = h.isDirty.getBoolean(atlas);
                if (cached == null || (!locked && dirty)) {
                    dirtyAtlases.add(atlas);
                }
            }
            if (dirtyAtlases.size() >= MIN_PARALLEL_PAGES) {
                runParallelUpdate(dirtyAtlases, null);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Rebuilds all atlas pages in {@code atlases}, running bounded parallel CPU rasterization with
     * strictly owner-thread resource, cache, and progress commits.
     *
     * @param atlases list of {@code CTextureAtlas} pages queued for update
     * @param progress host progress reporter instance
     * @return {@code true} if handled here, {@code false} to fall back to the stock loop
     */
    public static boolean runParallelUpdate(final List<?> atlases, final Object progress) {
        if (atlases == null || atlases.size() < MIN_PARALLEL_PAGES) {
            return false;
        }
        try {
            return AtlasPageRebuildCoordinator.runParallelTextureUpdates(atlases, progress);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private static Handles resolveHandles(final Class<?> atlasClass) throws Exception {
        Handles cached = handles;
        if (cached != null && cached.atlasClass == atlasClass) {
            return cached;
        }
        synchronized (AtlasRebuildParallelizer.class) {
            cached = handles;
            if (cached != null && cached.atlasClass == atlasClass) {
                return cached;
            }
            cached = new Handles(atlasClass);
            handles = cached;
            return cached;
        }
    }

    private static TextureManagerHandles resolveManagerHandles(final Class<?> managerClass) throws Exception {
        TextureManagerHandles cached = managerHandles;
        if (cached != null && cached.managerClass == managerClass) {
            return cached;
        }
        synchronized (AtlasRebuildParallelizer.class) {
            cached = managerHandles;
            if (cached != null && cached.managerClass == managerClass) {
                return cached;
            }
            cached = new TextureManagerHandles(managerClass);
            managerHandles = cached;
            return cached;
        }
    }

    private static Field openField(final Class<?> owner, final String name) throws NoSuchFieldException {
        final Field field = owner.getDeclaredField(name);
        ReflectAccess.open(field);
        return field;
    }

    private static final class TextureManagerHandles {
        private final Class<?> managerClass;
        private final Method getTextureAtlases;

        TextureManagerHandles(final Class<?> managerClass) throws NoSuchMethodException {
            this.managerClass = managerClass;
            this.getTextureAtlases = managerClass.getMethod(METHOD_GET_TEXTURE_ATLASES);
        }
    }

    private static final class Handles {
        private final Class<?> atlasClass;
        private final Field cachedAtlasImage;
        private final Field lockCachedAtlasImage;
        private final Field isDirty;

        Handles(final Class<?> atlasClass) throws NoSuchFieldException {
            this.atlasClass = atlasClass;
            this.cachedAtlasImage = openField(atlasClass, FIELD_CACHED_ATLAS_IMAGE);
            this.lockCachedAtlasImage = openField(atlasClass, FIELD_LOCK_CACHED_ATLAS_IMAGE);
            this.isDirty = openField(atlasClass, FIELD_IS_DIRTY_CACHED_ATLAS_IMAGE);
        }
    }
}
