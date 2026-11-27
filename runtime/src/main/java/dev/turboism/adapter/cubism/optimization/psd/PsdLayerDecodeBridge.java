package dev.turboism.adapter.cubism.optimization.psd;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates bounded parallel PSD layer channel decompression, edge bleed, and thumbnail
 * icon precomputation across independent layers while keeping host document state sequential.
 */
public final class PsdLayerDecodeBridge {

    private static final int MIN_PARALLEL_LAYERS = 2;
    private static final int MIN_PARALLEL_BYTES = 131_072;
    private static final int COMP_RAW = 0;
    private static final int COMP_RLE = 1;
    private static final int COMP_ZIP_PRED = 3;
    private static final int UINT16_BYTES = 2;
    private static final int ICON_SIZE_16 = 16;
    private static final int ICON_SIZE_64 = 64;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHIFT_HIGH_BYTE = 8;

    private static final String LAYER_INFO_CLASS = "com.live2d.graphics.psd.layer.k";
    private static final String PSD_READER_CLASS = "com.live2d.graphics.psd.b";
    private static final String PSD_DOCUMENT_CLASS = "com.live2d.graphics.psd.CPsdDocument";
    private static final String CHANNEL_DECODER_CLASS = "com.live2d.graphics.psd.layer.b";
    private static final String CHANNEL_DECODER_COMPANION = "com.live2d.graphics.psd.layer.b$a";
    private static final String LAYER_CLASS_MODERN = "com.live2d.graphics.psd.layer.o";
    private static final String LAYER_CLASS_LEGACY = "com.live2d.graphics.psd.layer.n";
    private static final String LAYER_ENUM_CLASS_MODERN = "com.live2d.graphics.psd.layer.o$b";
    private static final String LAYER_ENUM_CLASS_LEGACY = "com.live2d.graphics.psd.layer.n$b";
    private static final String LDIMAGE_CLASS = "jp.live2d.type_editor.image.LDImage";
    private static final String WRITABLE_IMAGE_CLASS = "com.live2d.graphics.CWritableImage";
    private static final String COLOR_TYPE_CLASS = "com.live2d.graphics.n";

    private static final ThreadLocal<WorkerCaptureState> WORKER_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<IdentityHashMap<BufferedImage, Object[]>> PREPARED_ICONS = new ThreadLocal<>();

    private static final Object HANDLES_LOCK = new Object();
    private static final Map<ClassLoader, DecodeHandles> HANDLES_BY_LOADER =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile DecodeHandles lastHandles;

    private PsdLayerDecodeBridge() {}

    /**
     * Captures a prebuilt layer image off-thread or constructs {@code LDImage} on the caller thread.
     *
     * <p>Prevents worker threads from mutating {@code LDImage.cacheList} and {@code createdCount}
     * concurrently while preserving exact caller-thread registration order.
     *
     * @param image finalized ARGB layer image
     * @param layerOwner host layer instance whose class loader provides {@code LDImage}
     * @return new {@code LDImage} instance on caller thread, or {@code null} when captured off-thread
     */
    public static Object createOrCaptureWorkerLdImage(final BufferedImage image, final Object layerOwner) {
        final WorkerCaptureState capture = WORKER_CAPTURE.get();
        if (capture != null) {
            capture.capturedImage = image;
            return null;
        }
        final ClassLoader loader = layerOwner != null
                ? layerOwner.getClass().getClassLoader()
                : Thread.currentThread().getContextClassLoader();
        final DecodeHandles handles = resolveHandlesFromLoader(loader);
        if (handles == null) {
            throw new IllegalStateException("LDImage constructor handles unavailable");
        }
        try {
            return handles.newLdImage.invoke(image);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("LDImage creation failed", failure);
        }
    }

    /**
     * Scans layer channel byte offsets sequentially and decodes independent layers in parallel.
     *
     * <p>Returns {@code false} cleanly without advancing {@code psdReader} if boundaries cannot be
     * verified or debug logging is enabled, allowing sequential fallback.
     *
     * @param layerInfo host {@code layer.k} instance holding parsed layer records
     * @param psdReader host {@code com.live2d.graphics.psd.b} positioned at layer 0 channel data
     * @return {@code true} if all layers decoded in parallel and reader advanced to end offset
     */
    public static boolean tryDecodeAllLayersParallel(final Object layerInfo, final Object psdReader) {
        if (layerInfo == null || psdReader == null || WORKER_CAPTURE.get() != null) {
            return false;
        }
        final DecodeHandles handles =
                resolveHandlesFromLoader(psdReader.getClass().getClassLoader());
        if (handles == null) {
            return false;
        }
        try {
            final boolean flagC = handles.readerFlagC.getBoolean(psdReader);
            if (!flagC) {
                // Sequential fallback preserves ordered debug hex logging when active.
                return false;
            }
            @SuppressWarnings("unchecked")
            final List<Object> layers = (List<Object>) handles.layerInfoList.invoke(layerInfo);
            final int layerCount = layers.size();
            if (layerCount < MIN_PARALLEL_LAYERS) {
                return false;
            }
            final Object dataReader = handles.wrapperDataReader.get(psdReader);
            final byte[] rawBytes = (byte[]) handles.streamBytes.get(dataReader);
            final int initialPos = handles.streamPos.getInt(dataReader);
            if (rawBytes == null || initialPos < 0 || initialPos > rawBytes.length) {
                return false;
            }

            final int[] layerOffsets = new int[layerCount + 1];
            if (!scanLayerChannelRanges(handles, rawBytes, initialPos, layers, layerOffsets)) {
                return false;
            }
            final int endPos = layerOffsets[layerCount];
            if (endPos - initialPos < MIN_PARALLEL_BYTES) {
                return false;
            }

            final Object psdDoc = handles.readerPsdDoc.invoke(psdReader);
            final boolean flagD = (boolean) handles.readerFlagD.invoke(psdReader);
            final int[] orderedIndices = sortIndicesByPayloadDesc(layerOffsets, layerCount);
            final BufferedImage[] prebuiltImages = new BufferedImage[layerCount];
            final AtomicInteger cursor = new AtomicInteger(0);
            final int taskCount = Math.min(PsdImageOps.workerCount(), layerCount);

            final List<Callable<Void>> tasks = new ArrayList<>(taskCount);
            for (int w = 0; w < taskCount; w++) {
                tasks.add(() -> {
                    final WorkerCaptureState state = new WorkerCaptureState();
                    WORKER_CAPTURE.set(state);
                    try {
                        while (true) {
                            final int slot = cursor.getAndIncrement();
                            if (slot >= layerCount) {
                                break;
                            }
                            final int layerIdx = orderedIndices[slot];
                            final Object layer = layers.get(layerIdx);
                            try {
                                final Object workerB = handles.newPsdReader.invoke(rawBytes, psdDoc);
                                handles.readerSetFlagC.invoke(workerB, flagC);
                                handles.readerSetFlagD.invoke(workerB, flagD);
                                final Object workerData = handles.wrapperDataReader.get(workerB);
                                handles.streamPos.setInt(workerData, layerOffsets[layerIdx]);

                                state.capturedImage = null;
                                handles.decodeChannels.invoke(handles.channelCompanionInstance, workerB, layer);
                                handles.assembleLayer.invoke(layer, workerB);
                            } catch (final Throwable failure) {
                                FatalErrors.rethrowIfFatal(failure);
                                throw new IllegalStateException("Worker layer decode failed", failure);
                            }
                            prebuiltImages[layerIdx] = state.capturedImage;
                            state.capturedImage = null;
                        }
                        return null;
                    } finally {
                        WORKER_CAPTURE.remove();
                    }
                });
            }

            final List<Future<Void>> futures = PsdImageOps.workerPool().invokeAll(tasks);
            for (final Future<Void> future : futures) {
                future.get();
            }

            // Instantiate LDImage entries deterministically on the caller thread in layer order.
            for (int i = 0; i < layerCount; i++) {
                final BufferedImage img = prebuiltImages[i];
                if (img != null) {
                    final Object ldImage = handles.newLdImage.invoke(img);
                    handles.layerLdImageField.set(layers.get(i), ldImage);
                }
            }
            handles.streamPos.setInt(dataReader, endPos);
            return true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Computes 16x16 and 64x64 {@code CWritableImage} thumbnails in parallel before sequential
     * {@code CLayer} construction in {@code CLayeredImage.<init>}.
     *
     * @param rawLayers raw {@code layer.o[]} or {@code layer.n[]} array from {@code CPsdDocument}
     */
    public static void prepareLayerIcons(final Object[] rawLayers) {
        clearPreparedLayerIcons();
        if (rawLayers == null || rawLayers.length < MIN_PARALLEL_LAYERS) {
            return;
        }
        final DecodeHandles handles =
                resolveHandlesFromLoader(rawLayers[0].getClass().getClassLoader());
        if (handles == null || !handles.supportsIcons()) {
            return;
        }
        try {
            final List<Object> leafItems = new ArrayList<>();
            for (final Object layer : rawLayers) {
                if (layer == null) {
                    continue;
                }
                if (handles.layerType.invoke(layer) != handles.leafEnumValue) {
                    continue;
                }
                final int w = (int) handles.layerWidth.invoke(layer);
                final int h = (int) handles.layerHeight.invoke(layer);
                if (w <= 0 || h <= 0) {
                    continue;
                }
                final Object ldImage = handles.layerLdImageField.get(layer);
                if (ldImage == null) {
                    continue;
                }
                final BufferedImage buf = (BufferedImage) handles.ldImageBufField.get(ldImage);
                if (buf != null) {
                    leafItems.add(buf);
                }
            }
            if (leafItems.size() < MIN_PARALLEL_LAYERS) {
                return;
            }
            final int count = leafItems.size();
            final BufferedImage[] sources = leafItems.toArray(new BufferedImage[0]);
            final Object[][] results = new Object[count][];
            final AtomicInteger next = new AtomicInteger(0);
            final int taskCount = Math.min(PsdImageOps.workerCount(), count);
            final List<Callable<Void>> tasks = new ArrayList<>(taskCount);
            for (int w = 0; w < taskCount; w++) {
                tasks.add(() -> {
                    while (true) {
                        final int idx = next.getAndIncrement();
                        if (idx >= count) {
                            break;
                        }
                        final BufferedImage srcBuf = sources[idx];
                        try {
                            final Object writable = handles.newWritableFromBuffered.invoke(srcBuf);
                            final Object icon16 =
                                    handles.createFit.invoke(writable, ICON_SIZE_16, ICON_SIZE_16, handles.colorTypeA);
                            final Object icon64 =
                                    handles.createFit.invoke(writable, ICON_SIZE_64, ICON_SIZE_64, handles.colorTypeA);
                            results[idx] = new Object[] {icon16, icon64};
                        } catch (final Throwable failure) {
                            FatalErrors.rethrowIfFatal(failure);
                            throw new IllegalStateException("Icon creation failed", failure);
                        }
                    }
                    return null;
                });
            }
            final List<Future<Void>> futures = PsdImageOps.workerPool().invokeAll(tasks);
            for (final Future<Void> future : futures) {
                future.get();
            }
            final IdentityHashMap<BufferedImage, Object[]> cache = new IdentityHashMap<>(count);
            for (int i = 0; i < count; i++) {
                if (results[i] != null) {
                    cache.put(sources[i], results[i]);
                }
            }
            PREPARED_ICONS.set(cache);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            PREPARED_ICONS.remove();
        }
    }

    /**
     * Releases precomputed thread-local layer thumbnails once {@code CLayeredImage} construction
     * finishes or throws.
     */
    public static void clearPreparedLayerIcons() {
        PREPARED_ICONS.remove();
    }

    /**
     * Returns a precomputed icon when available, falling back to {@code CWritableImage.createFit}.
     *
     * @param writableImg source {@code CWritableImage} inside {@code CLayer.setupIcon}
     * @param targetWidth requested icon width
     * @param targetHeight requested icon height
     * @param colorType host {@code com.live2d.graphics.n} color format
     * @return fit icon {@code CWritableImage}
     */
    public static Object consumeOrCreateFit(
            final Object writableImg, final int targetWidth, final int targetHeight, final Object colorType) {
        final Object precomputed = consumePreparedIcon(writableImg, targetWidth, targetHeight);
        if (precomputed != null) {
            return precomputed;
        }
        final DecodeHandles handles =
                resolveHandlesFromLoader(writableImg.getClass().getClassLoader());
        if (handles == null || handles.createFit == null) {
            throw new IllegalStateException("CWritableImage.createFit handle unavailable");
        }
        try {
            return handles.createFit.invoke(writableImg, targetWidth, targetHeight, colorType);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("CWritableImage.createFit invocation failed", failure);
        }
    }

    /**
     * Returns a precomputed fit icon when available, matching {@code CWritableImage.createFit}.
     *
     * @param writableImg source {@code CWritableImage} inside {@code CLayer.setupIcon}
     * @param targetWidth requested icon width ({@code 16} or {@code 64})
     * @param targetHeight requested icon height ({@code 16} or {@code 64})
     * @return precomputed icon {@code CWritableImage}, or {@code null} to compute directly
     */
    public static Object consumePreparedIcon(final Object writableImg, final int targetWidth, final int targetHeight) {
        final IdentityHashMap<BufferedImage, Object[]> map = PREPARED_ICONS.get();
        if (map == null || writableImg == null) {
            return null;
        }
        final DecodeHandles handles =
                resolveHandlesFromLoader(writableImg.getClass().getClassLoader());
        if (handles == null || handles.getJBufferedImage == null) {
            return null;
        }
        try {
            final BufferedImage key = (BufferedImage) handles.getJBufferedImage.invoke(writableImg);
            final Object[] pair = map.get(key);
            if (pair == null) {
                return null;
            }
            if (targetWidth == ICON_SIZE_16 && targetHeight == ICON_SIZE_16) {
                return pair[0];
            }
            if (targetWidth == ICON_SIZE_64 && targetHeight == ICON_SIZE_64) {
                return pair[1];
            }
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    static int[] sortIndicesByPayloadDesc(final int[] layerOffsets, final int layerCount) {
        final Integer[] boxed = new Integer[layerCount];
        for (int i = 0; i < layerCount; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> {
            final int sizeA = layerOffsets[a + 1] - layerOffsets[a];
            final int sizeB = layerOffsets[b + 1] - layerOffsets[b];
            return Integer.compare(sizeB, sizeA);
        });
        final int[] result = new int[layerCount];
        for (int i = 0; i < layerCount; i++) {
            result[i] = boxed[i];
        }
        return result;
    }

    static boolean scanLayerChannelRanges(
            final DecodeHandles handles,
            final byte[] stream,
            final int startPos,
            final List<Object> layers,
            final int[] layerOffsets)
            throws Throwable {
        int pos = startPos;
        final int count = layers.size();
        for (int i = 0; i < count; i++) {
            layerOffsets[i] = pos;
            final Object layer = layers.get(i);
            final int w = (int) handles.layerWidth.invoke(layer);
            final int h = (int) handles.layerHeight.invoke(layer);
            final int chCount = (int) handles.layerChannelCount.invoke(layer);
            final Object layerMeta = handles.layerMetaGetter.invoke(layer);
            if (layerMeta == null || w < 0 || h < 0 || chCount < 0) {
                return false;
            }
            @SuppressWarnings("unchecked")
            final List<Object> channels = (List<Object>) handles.metaChannelList.invoke(layerMeta);
            if (channels == null || channels.size() < chCount) {
                return false;
            }
            for (int c = 0; c < chCount; c++) {
                final Object chMeta = channels.get(c);
                final boolean isMask = (boolean) handles.channelIsMask.invoke(chMeta);
                final int cw;
                final int ch;
                if (isMask) {
                    final Object maskInfo = handles.metaMaskInfo.invoke(layerMeta);
                    if (maskInfo == null) {
                        return false;
                    }
                    final Object rect = handles.maskRect.invoke(maskInfo);
                    cw = handles.rectWidth.getInt(rect);
                    ch = handles.rectHeight.getInt(rect);
                } else {
                    cw = w;
                    ch = h;
                }
                if (pos + UINT16_BYTES > stream.length) {
                    return false;
                }
                final int compression = ((stream[pos] & BYTE_MASK) << SHIFT_HIGH_BYTE) | (stream[pos + 1] & BYTE_MASK);
                pos += UINT16_BYTES;
                if (compression == COMP_RAW) {
                    final long rawLen = (long) cw * ch;
                    if (rawLen < 0 || rawLen > stream.length - pos) {
                        return false;
                    }
                    pos += (int) rawLen;
                } else if (compression == COMP_ZIP_PRED) {
                    final int zipLen = (int) handles.channelDataLength.invoke(chMeta) - UINT16_BYTES;
                    if (zipLen < 0 || zipLen > stream.length - pos) {
                        return false;
                    }
                    pos += zipLen;
                } else if (compression == COMP_RLE) {
                    final long headerBytes = (long) ch * UINT16_BYTES;
                    if (ch < 0 || headerBytes > stream.length - pos) {
                        return false;
                    }
                    long rleTotal = 0L;
                    for (int row = 0; row < ch; row++) {
                        final int rowLen =
                                ((stream[pos] & BYTE_MASK) << SHIFT_HIGH_BYTE) | (stream[pos + 1] & BYTE_MASK);
                        pos += UINT16_BYTES;
                        rleTotal += rowLen;
                    }
                    if (rleTotal > stream.length - pos) {
                        return false;
                    }
                    pos += (int) rleTotal;
                } else {
                    return false;
                }
            }
        }
        layerOffsets[count] = pos;
        return true;
    }

    private static DecodeHandles resolveHandlesFromLoader(final ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        final DecodeHandles cached = lastHandles;
        if (cached != null && cached.loader == loader) {
            return cached;
        }
        synchronized (HANDLES_LOCK) {
            final DecodeHandles mapped = HANDLES_BY_LOADER.get(loader);
            if (mapped != null) {
                lastHandles = mapped;
                return mapped;
            }
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final Class<?> layerInfoClass = Class.forName(LAYER_INFO_CLASS, false, loader);
                final Class<?> readerClass = Class.forName(PSD_READER_CLASS, false, loader);
                final Class<?> psdDocClass = Class.forName(PSD_DOCUMENT_CLASS, false, loader);
                final Class<?> chDecoderClass = Class.forName(CHANNEL_DECODER_CLASS, false, loader);
                final Class<?> chCompClass = Class.forName(CHANNEL_DECODER_COMPANION, false, loader);

                boolean modernLayer = true;
                Class<?> layerClass;
                Class<?> enumClass;
                try {
                    layerClass = Class.forName(LAYER_CLASS_MODERN, false, loader);
                    enumClass = Class.forName(LAYER_ENUM_CLASS_MODERN, false, loader);
                } catch (final ClassNotFoundException ignored) {
                    modernLayer = false;
                    layerClass = Class.forName(LAYER_CLASS_LEGACY, false, loader);
                    enumClass = Class.forName(LAYER_ENUM_CLASS_LEGACY, false, loader);
                }
                final Object leafEnumValue = enumClass.getField("c").get(null);

                final Field wrapperDataReader = readerClass.getDeclaredField("a");
                wrapperDataReader.setAccessible(true);
                final Field readerFlagC = readerClass.getDeclaredField("c");
                readerFlagC.setAccessible(true);
                final Class<?> dataReaderClass = wrapperDataReader.getType();
                final Field streamBytes = dataReaderClass.getDeclaredField("b");
                streamBytes.setAccessible(true);
                final Field streamPos = dataReaderClass.getDeclaredField("c");
                streamPos.setAccessible(true);

                final MethodHandle layerInfoList = lookup.findVirtual(
                        layerInfoClass, "a", MethodType.methodType(java.util.concurrent.CopyOnWriteArrayList.class));
                final MethodHandle readerPsdDoc =
                        lookup.findVirtual(readerClass, "a", MethodType.methodType(psdDocClass));
                final MethodHandle readerFlagD =
                        lookup.findVirtual(readerClass, "b", MethodType.methodType(boolean.class));
                final MethodHandle readerSetFlagC =
                        lookup.findVirtual(readerClass, "a", MethodType.methodType(void.class, boolean.class));
                final MethodHandle readerSetFlagD =
                        lookup.findVirtual(readerClass, "b", MethodType.methodType(void.class, boolean.class));
                final MethodHandle newPsdReader =
                        lookup.unreflectConstructor(readerClass.getConstructor(byte[].class, psdDocClass));

                final Object channelCompanionInstance =
                        chDecoderClass.getField("a").get(null);
                final MethodHandle decodeChannels = lookup.findVirtual(
                        chCompClass, "a", MethodType.methodType(void.class, readerClass, layerClass));
                final MethodHandle assembleLayer =
                        lookup.findVirtual(layerClass, "a", MethodType.methodType(void.class, readerClass));
                final MethodHandle layerWidth = lookup.findVirtual(layerClass, "m", MethodType.methodType(int.class));
                final MethodHandle layerHeight = lookup.findVirtual(layerClass, "n", MethodType.methodType(int.class));
                final MethodHandle layerChannelCount =
                        lookup.findVirtual(layerClass, "o", MethodType.methodType(int.class));
                final MethodHandle layerType = lookup.findVirtual(layerClass, "d", MethodType.methodType(enumClass));

                final Class<?> metaClass = Class.forName("com.live2d.graphics.psd.layer.m", false, loader);
                final MethodHandle layerMetaGetter =
                        lookup.findVirtual(layerClass, "a", MethodType.methodType(metaClass));
                final MethodHandle metaChannelList =
                        lookup.findVirtual(metaClass, "c", MethodType.methodType(ArrayList.class));
                final Class<?> maskClass = Class.forName("com.live2d.graphics.psd.layer.l", false, loader);
                final MethodHandle metaMaskInfo = lookup.findVirtual(metaClass, "n", MethodType.methodType(maskClass));
                final Class<?> ldRectClass = Class.forName("jp.live2d.type_editor.LDRect", false, loader);
                final MethodHandle maskRect = lookup.findVirtual(maskClass, "b", MethodType.methodType(ldRectClass));
                final Field rectWidth = ldRectClass.getField("width");
                final Field rectHeight = ldRectClass.getField("height");

                final Class<?> chMetaClass = Class.forName("com.live2d.graphics.psd.layer.d", false, loader);
                final MethodHandle channelIsMask =
                        lookup.findVirtual(chMetaClass, "c", MethodType.methodType(boolean.class));
                final MethodHandle channelDataLength =
                        lookup.findVirtual(chMetaClass, "b", MethodType.methodType(int.class));

                final Class<?> ldImageClass = Class.forName(LDIMAGE_CLASS, false, loader);
                final Constructor<?> ldCtor = ldImageClass.getConstructor(BufferedImage.class);
                final MethodHandle newLdImage = lookup.unreflectConstructor(ldCtor);
                final Field layerLdImageField = layerClass.getDeclaredField("c");
                layerLdImageField.setAccessible(true);
                final Field ldImageBufField = ldImageClass.getDeclaredField("image");
                ldImageBufField.setAccessible(true);

                MethodHandle newWritableFromBuffered = null;
                MethodHandle createFit = null;
                MethodHandle getJBufferedImage = null;
                Object colorTypeA = null;
                try {
                    final Class<?> writableClass = Class.forName(WRITABLE_IMAGE_CLASS, false, loader);
                    final Class<?> colorTypeClass = Class.forName(COLOR_TYPE_CLASS, false, loader);
                    newWritableFromBuffered =
                            lookup.unreflectConstructor(writableClass.getConstructor(BufferedImage.class));
                    createFit = lookup.findVirtual(
                            writableClass,
                            "createFit",
                            MethodType.methodType(writableClass, int.class, int.class, colorTypeClass));
                    getJBufferedImage = lookup.findVirtual(
                            writableClass, "getJBufferedImage", MethodType.methodType(BufferedImage.class));
                    final Object companion = colorTypeClass.getField("a").get(null);
                    colorTypeA = companion.getClass().getMethod("a").invoke(companion);
                } catch (final Throwable ignored) {
                    FatalErrors.rethrowIfFatal(ignored);
                    // Optional icon helpers may not be present in isolated unit tests.
                }

                final DecodeHandles created = new DecodeHandles(
                        loader,
                        modernLayer,
                        wrapperDataReader,
                        readerFlagC,
                        streamBytes,
                        streamPos,
                        layerInfoList,
                        readerPsdDoc,
                        readerFlagD,
                        readerSetFlagC,
                        readerSetFlagD,
                        newPsdReader,
                        channelCompanionInstance,
                        decodeChannels,
                        assembleLayer,
                        layerWidth,
                        layerHeight,
                        layerChannelCount,
                        layerType,
                        leafEnumValue,
                        layerMetaGetter,
                        metaChannelList,
                        metaMaskInfo,
                        maskRect,
                        rectWidth,
                        rectHeight,
                        channelIsMask,
                        channelDataLength,
                        newLdImage,
                        layerLdImageField,
                        ldImageBufField,
                        newWritableFromBuffered,
                        createFit,
                        getJBufferedImage,
                        colorTypeA);
                HANDLES_BY_LOADER.put(loader, created);
                lastHandles = created;
                return created;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return null;
            }
        }
    }

    private static final class WorkerCaptureState {
        BufferedImage capturedImage;
    }

    private record DecodeHandles(
            ClassLoader loader,
            boolean modernLayer,
            Field wrapperDataReader,
            Field readerFlagC,
            Field streamBytes,
            Field streamPos,
            MethodHandle layerInfoList,
            MethodHandle readerPsdDoc,
            MethodHandle readerFlagD,
            MethodHandle readerSetFlagC,
            MethodHandle readerSetFlagD,
            MethodHandle newPsdReader,
            Object channelCompanionInstance,
            MethodHandle decodeChannels,
            MethodHandle assembleLayer,
            MethodHandle layerWidth,
            MethodHandle layerHeight,
            MethodHandle layerChannelCount,
            MethodHandle layerType,
            Object leafEnumValue,
            MethodHandle layerMetaGetter,
            MethodHandle metaChannelList,
            MethodHandle metaMaskInfo,
            MethodHandle maskRect,
            Field rectWidth,
            Field rectHeight,
            MethodHandle channelIsMask,
            MethodHandle channelDataLength,
            MethodHandle newLdImage,
            Field layerLdImageField,
            Field ldImageBufField,
            MethodHandle newWritableFromBuffered,
            MethodHandle createFit,
            MethodHandle getJBufferedImage,
            Object colorTypeA) {

        boolean supportsIcons() {
            return newWritableFromBuffered != null
                    && createFit != null
                    && getJBufferedImage != null
                    && colorTypeA != null;
        }
    }
}
