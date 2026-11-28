package dev.turboism.adapter.cubism.optimization.save;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Concurrently pre-encodes dirty {@code CImageResource} buffers before {@code UUSerialize}
 * traverses and serializes a model source.
 */
public final class ImageSavePreEncoder {

    private static final String MODEL_SOURCE_CLASS = "com.live2d.cubism.doc.model.CModelSource";
    private static final String IMAGE_RESOURCE_CLASS = "com.live2d.graphics.CImageResource";
    private static final String WRITABLE_IMAGE_CLASS = "com.live2d.graphics.CWritableImage";
    private static final String TEXTURE_MANAGER_CLASS = "com.live2d.cubism.doc.model.texture.CTextureManager";
    private static final String TEXTURE_ATLAS_CLASS = "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas";
    private static final String MODEL_IMAGE_CLASS = "com.live2d.cubism.doc.model.texture.modelImage.CModelImage";
    private static final String TEXTURE_GROUP_CLASS = "com.live2d.cubism.doc.model.texture.TextureImageGroup";
    private static final String TEXTURE_IMAGE_CLASS = "com.live2d.cubism.doc.model.texture.CTextureImage";
    private static final String LAYERED_WRAPPER_CLASS = "com.live2d.cubism.doc.model.texture.LayeredImageWrapper";
    private static final String LAYERED_IMAGE_CLASS = "com.live2d.cubism.doc.resources.CLayeredImage";
    private static final String LAYER_SET_CLASS = "com.live2d.cubism.doc.resources.LayerSet";
    private static final String LAYER_CLASS = "com.live2d.cubism.doc.resources.CLayer";

    private static final int MIN_PARALLEL_RESOURCES = 2;
    private static final int MIN_POOL_THREADS = 2;
    private static final int MAX_POOL_THREADS = 6;
    private static final int MAX_QUEUE_CAPACITY = 4096;
    private static final long MAX_IN_FLIGHT_PIXELS = 64_000_000L;
    private static final String WORKER_THREAD_PREFIX = "save-png-encode";

    private static final Object BUDGET_LOCK = new Object();
    private static long inFlightPixels;
    private static volatile SaveHandles cachedHandles;
    private static volatile ExecutorService encodePool;

    private ImageSavePreEncoder() {}

    /**
     * Pre-encodes dirty image resources reachable from {@code rootObject} when at least two
     * resources lack serialized PNG bytes.
     *
     * @param rootObject serialized model or document root passed into {@code UUSerialize.serialize_exe}
     */
    public static void preEncodeBeforeSerialize(final Object rootObject) {
        if (rootObject == null
                || !MODEL_SOURCE_CLASS.equals(rootObject.getClass().getName())) {
            return;
        }
        final SaveHandles handles = resolveHandles(rootObject.getClass().getClassLoader());
        if (handles == null) {
            return;
        }
        final List<PendingEncode> candidates = collectDirtyResources(rootObject, handles);
        if (candidates.size() < MIN_PARALLEL_RESOURCES) {
            return;
        }
        final ExecutorService pool = getOrCreatePool(handles.loader);
        final List<CompletableFuture<byte[]>> futures = new ArrayList<>(candidates.size());
        for (final PendingEncode task : candidates) {
            try {
                futures.add(CompletableFuture.supplyAsync(() -> encodeCandidate(task, handles), pool));
            } catch (final RejectedExecutionException saturated) {
                futures.add(CompletableFuture.completedFuture(encodeCandidate(task, handles)));
            }
        }
        long addedByteCount = 0L;
        for (int i = 0; i < candidates.size(); i++) {
            final byte[] encoded = awaitFutureQuietly(futures.get(i));
            if (encoded == null) {
                continue;
            }
            final Object resource = candidates.get(i).resource;
            synchronized (resource) {
                try {
                    if (!(boolean) handles.isDisposed.invoke(resource)
                            && (byte[]) handles.getImageFileBuf.invoke(resource) == null) {
                        handles.setImageFileBuf.invoke(resource, encoded);
                        addedByteCount += encoded.length;
                    }
                } catch (final Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                }
            }
        }
        if (addedByteCount > 0L && handles.getByteDataBytes != null && handles.setByteDataBytes != null) {
            try {
                final int currentBytes = (int) handles.getByteDataBytes.invoke();
                handles.setByteDataBytes.invoke((int) Math.min(Integer.MAX_VALUE, currentBytes + addedByteCount));
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
            }
        }
    }

    private static byte[] awaitFutureQuietly(final CompletableFuture<byte[]> future) {
        try {
            return future.get();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final ExecutionException failed) {
            if (failed.getCause() != null) {
                FatalErrors.rethrowIfFatal(failed.getCause());
            }
            return null;
        }
    }

    private static byte[] encodeCandidate(final PendingEncode candidate, final SaveHandles handles) {
        final long pixels = (long) candidate.bufferedImage.getWidth() * candidate.bufferedImage.getHeight();
        if (!acquirePixelBudget(pixels)) {
            return null;
        }
        try {
            final byte[] fast = ImageSaveFastPngSupport.encodePngIfSupported(candidate.bufferedImage);
            if (fast != null) {
                return fast;
            }
            return (byte[]) handles.getPngBytes.invoke(candidate.writableImage);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        } finally {
            releasePixelBudget(pixels);
        }
    }

    private static List<PendingEncode> collectDirtyResources(final Object modelSource, final SaveHandles handles) {
        final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Object> resources = new ArrayList<>();
        try {
            final Object textureManager = handles.getTextureManager.invoke(modelSource);
            if (textureManager == null) {
                return List.of();
            }
            collectFromList(
                    handles.getTextureAtlases.invoke(textureManager), handles.getCachedAtlasImage, seen, resources);
            collectFromList(
                    handles.getAllModelImages.invoke(textureManager), handles.getFilteredImage, seen, resources);
            if (handles.getTextureList != null && handles.getTextureGroupChildren != null) {
                final Object texGroup = handles.getTextureList.invoke(textureManager);
                if (texGroup != null) {
                    collectFromList(
                            handles.getTextureGroupChildren.invoke(texGroup),
                            handles.getTextureImageResource,
                            seen,
                            resources);
                }
            }
            if (handles.getRawImages != null
                    && handles.getWrappedLayeredImage != null
                    && handles.getLayerSet != null
                    && handles.getLayerEntryList != null
                    && handles.getLayerImageResource != null) {
                final Object rawImages = handles.getRawImages.invoke(textureManager);
                if (rawImages instanceof List<?> wrapperList) {
                    for (final Object wrapper : wrapperList) {
                        if (wrapper == null) {
                            continue;
                        }
                        final Object layered = handles.getWrappedLayeredImage.invoke(wrapper);
                        if (layered == null) {
                            continue;
                        }
                        final Object layerSet = handles.getLayerSet.invoke(layered);
                        if (layerSet == null) {
                            continue;
                        }
                        collectLayerList(
                                handles.getLayerEntryList.invoke(layerSet),
                                handles.layerClass,
                                handles.getLayerImageResource,
                                seen,
                                resources);
                    }
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return List.of();
        }
        if (resources.isEmpty()) {
            return List.of();
        }
        final List<PendingEncode> dirty = new ArrayList<>(resources.size());
        for (final Object resource : resources) {
            synchronized (resource) {
                try {
                    if ((boolean) handles.isDisposed.invoke(resource)) {
                        continue;
                    }
                    if ((byte[]) handles.getImageFileBuf.invoke(resource) != null) {
                        continue;
                    }
                    final Object writable = handles.getImageOrNull.invoke(resource);
                    if (writable == null) {
                        continue;
                    }
                    final BufferedImage bi = (BufferedImage) handles.getJBufferedImage.invoke(writable);
                    if (bi == null || bi.getWidth() <= 0 || bi.getHeight() <= 0) {
                        continue;
                    }
                    dirty.add(new PendingEncode(resource, writable, bi));
                } catch (final Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                }
            }
        }
        return dirty;
    }

    private static void collectFromList(
            final Object maybeList, final MethodHandle getter, final Set<Object> seen, final List<Object> out)
            throws Throwable {
        if (getter == null || !(maybeList instanceof List<?> items)) {
            return;
        }
        for (final Object item : items) {
            if (item == null) {
                continue;
            }
            final Object resource = getter.invoke(item);
            if (resource != null && seen.add(resource)) {
                out.add(resource);
            }
        }
    }

    private static void collectLayerList(
            final Object maybeList,
            final Class<?> layerClass,
            final MethodHandle getter,
            final Set<Object> seen,
            final List<Object> out)
            throws Throwable {
        if (layerClass == null || !(maybeList instanceof List<?> entries)) {
            return;
        }
        for (final Object entry : entries) {
            if (layerClass.isInstance(entry)) {
                final Object resource = getter.invoke(entry);
                if (resource != null && seen.add(resource)) {
                    out.add(resource);
                }
            }
        }
    }

    private static boolean acquirePixelBudget(final long pixels) {
        synchronized (BUDGET_LOCK) {
            while (inFlightPixels > 0L && inFlightPixels + pixels > MAX_IN_FLIGHT_PIXELS) {
                try {
                    BUDGET_LOCK.wait();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            inFlightPixels += pixels;
            return true;
        }
    }

    private static void releasePixelBudget(final long pixels) {
        synchronized (BUDGET_LOCK) {
            inFlightPixels = Math.max(0L, inFlightPixels - pixels);
            BUDGET_LOCK.notifyAll();
        }
    }

    private static ExecutorService getOrCreatePool(final ClassLoader loader) {
        final ExecutorService existing = encodePool;
        if (existing != null) {
            return existing;
        }
        synchronized (ImageSavePreEncoder.class) {
            if (encodePool == null) {
                final int cpus = Runtime.getRuntime().availableProcessors();
                final int workers = Math.min(MAX_POOL_THREADS, Math.max(MIN_POOL_THREADS, cpus - 1));
                final ClassLoader effective =
                        loader != null ? loader : RuntimeThreads.contextClassLoader(ImageSavePreEncoder.class);
                encodePool = RuntimeThreads.fixedLane(
                        workers, MAX_QUEUE_CAPACITY, RuntimeThreads.daemonThreads(WORKER_THREAD_PREFIX, effective));
            }
            return encodePool;
        }
    }

    private static SaveHandles resolveHandles(final ClassLoader loader) {
        final SaveHandles current = cachedHandles;
        if (current != null && current.loader == loader) {
            return current;
        }
        synchronized (ImageSavePreEncoder.class) {
            final SaveHandles recheck = cachedHandles;
            if (recheck != null && recheck.loader == loader) {
                return recheck;
            }
            final SaveHandles created = buildHandles(loader);
            if (created != null) {
                cachedHandles = created;
            }
            return created;
        }
    }

    private static SaveHandles buildHandles(final ClassLoader loader) {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            final Class<?> modelSourceClass = Class.forName(MODEL_SOURCE_CLASS, false, loader);
            final Class<?> textureManagerClass = Class.forName(TEXTURE_MANAGER_CLASS, false, loader);
            final Class<?> textureAtlasClass = Class.forName(TEXTURE_ATLAS_CLASS, false, loader);
            final Class<?> modelImageClass = Class.forName(MODEL_IMAGE_CLASS, false, loader);
            final Class<?> textureGroupClass = Class.forName(TEXTURE_GROUP_CLASS, false, loader);
            final Class<?> textureImageClass = Class.forName(TEXTURE_IMAGE_CLASS, false, loader);
            final Class<?> wrapperClass = Class.forName(LAYERED_WRAPPER_CLASS, false, loader);
            final Class<?> layeredClass = Class.forName(LAYERED_IMAGE_CLASS, false, loader);
            final Class<?> layerSetClass = Class.forName(LAYER_SET_CLASS, false, loader);
            final Class<?> layerClass = Class.forName(LAYER_CLASS, false, loader);
            final Class<?> resourceClass = Class.forName(IMAGE_RESOURCE_CLASS, false, loader);
            final Class<?> writableClass = Class.forName(WRITABLE_IMAGE_CLASS, false, loader);

            return new SaveHandles(
                    loader,
                    lookup.findVirtual(
                            modelSourceClass, "getTextureManager", MethodType.methodType(textureManagerClass)),
                    lookup.findVirtual(textureManagerClass, "getTextureAtlases", MethodType.methodType(List.class)),
                    lookup.findVirtual(textureAtlasClass, "getCachedAtlasImage", MethodType.methodType(resourceClass)),
                    lookup.findVirtual(textureManagerClass, "getAllModelImages", MethodType.methodType(List.class)),
                    lookup.findVirtual(modelImageClass, "getFilteredImage", MethodType.methodType(resourceClass)),
                    findOptionalVirtual(lookup, textureManagerClass, "getTextureList", textureGroupClass),
                    findOptionalVirtual(
                            lookup,
                            textureGroupClass,
                            "getChildren",
                            Class.forName("com.live2d.type.CArrayList", false, loader)),
                    findOptionalVirtual(lookup, textureImageClass, "getImageResource", resourceClass),
                    findOptionalVirtual(lookup, textureManagerClass, "getRawImages", List.class),
                    findOptionalVirtual(lookup, wrapperClass, "getImage", layeredClass),
                    findOptionalVirtual(lookup, layeredClass, "getLayerSet", layerSetClass),
                    findOptionalVirtual(lookup, layerSetClass, "getLayerEntryList", List.class),
                    layerClass,
                    findOptionalVirtual(lookup, layerClass, "getImageResource", resourceClass),
                    lookup.findVirtual(resourceClass, "isDisposed", MethodType.methodType(boolean.class)),
                    lookup.findStatic(
                            resourceClass,
                            "access$getImageFileBuf$p",
                            MethodType.methodType(byte[].class, resourceClass)),
                    lookup.findStatic(
                            resourceClass,
                            "access$setImageFileBuf$p",
                            MethodType.methodType(void.class, resourceClass, byte[].class)),
                    findOptionalStatic(
                            lookup, resourceClass, "access$getByteDataBytes$cp", MethodType.methodType(int.class)),
                    findOptionalStatic(
                            lookup,
                            resourceClass,
                            "access$setByteDataBytes$cp",
                            MethodType.methodType(void.class, int.class)),
                    lookup.findVirtual(resourceClass, "getImageOrNullIfDisposed", MethodType.methodType(writableClass)),
                    lookup.findVirtual(writableClass, "getJBufferedImage", MethodType.methodType(BufferedImage.class)),
                    lookup.findVirtual(writableClass, "getPngBytes", MethodType.methodType(byte[].class)));
        } catch (final Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private static MethodHandle findOptionalVirtual(
            final MethodHandles.Lookup lookup, final Class<?> owner, final String name, final Class<?> returnType) {
        try {
            return lookup.findVirtual(owner, name, MethodType.methodType(returnType));
        } catch (final NoSuchMethodException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static MethodHandle findOptionalStatic(
            final MethodHandles.Lookup lookup, final Class<?> owner, final String name, final MethodType type) {
        try {
            return lookup.findStatic(owner, name, type);
        } catch (final NoSuchMethodException | IllegalAccessException ignored) {
            return null;
        }
    }

    private record PendingEncode(Object resource, Object writableImage, BufferedImage bufferedImage) {}

    private record SaveHandles(
            ClassLoader loader,
            MethodHandle getTextureManager,
            MethodHandle getTextureAtlases,
            MethodHandle getCachedAtlasImage,
            MethodHandle getAllModelImages,
            MethodHandle getFilteredImage,
            MethodHandle getTextureList,
            MethodHandle getTextureGroupChildren,
            MethodHandle getTextureImageResource,
            MethodHandle getRawImages,
            MethodHandle getWrappedLayeredImage,
            MethodHandle getLayerSet,
            MethodHandle getLayerEntryList,
            Class<?> layerClass,
            MethodHandle getLayerImageResource,
            MethodHandle isDisposed,
            MethodHandle getImageFileBuf,
            MethodHandle setImageFileBuf,
            MethodHandle getByteDataBytes,
            MethodHandle setByteDataBytes,
            MethodHandle getImageOrNull,
            MethodHandle getJBufferedImage,
            MethodHandle getPngBytes) {}
}
