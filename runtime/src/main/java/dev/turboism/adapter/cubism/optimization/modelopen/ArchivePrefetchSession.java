package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prefetches and inflates archive entries on bounded background workers using a dedicated
 * read-only {@link FileChannel} with strict unconsumed memory backpressure.
 */
final class ArchivePrefetchSession {

    static final ArchivePrefetchSession DISABLED = new ArchivePrefetchSession();

    static final int MIN_PREFETCH_ENTRIES = 4;
    private static final int MIN_POOL_THREADS = 2;
    private static final int MAX_POOL_THREADS = 6;
    private static final int POOL_QUEUE_CAPACITY = 64;
    private static final long MIN_PREFETCH_BUDGET_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_PREFETCH_BUDGET_BYTES = 192L * 1024L * 1024L;
    private static final long HEAP_BUDGET_DIVISOR = 16L;
    private static final long DEFAULT_MAX_PREFETCH_BYTES =
            computeMaxPrefetchBytes(Runtime.getRuntime().maxMemory());
    private static final String PREFETCH_THREAD_NAME = "turboism-archive-prefetch";

    private static final String FIELD_INPUT = "input";
    private static final String FIELD_HEADER = "header";
    private static final String FIELD_PATH_TO_FILE_INFO_MAP = "pathToFileInfoMap";
    private static final String FIELD_RANDOM_ACCESS_FILE = "a";
    private static final String RANDOM_ACCESS_FILE_IMPL = "com.live2d.serialize.archiver.RandomAccess$c";
    private static final String ARCHIVE_FORMAT_ENTRY = "com.live2d.serialize.archiver.ArchiveFormat$b";
    private static final String ARCHIVE_FORMAT_HEADER = "com.live2d.serialize.archiver.ArchiveFormat$c";
    private static final String COMPRESS_OPTION_CLASS = "com.live2d.serialize.CompressOption";
    private static final String COMPRESS_OPTION_RAW = "RAW";
    private static final String METHOD_ENTRY_OFFSET = "c";
    private static final String METHOD_ENTRY_SIZE = "d";
    private static final String METHOD_ENTRY_COMPRESS_OPTION = "f";
    private static final String METHOD_HEADER_XOR_KEY = "e";

    private static final int STATE_QUEUED = 0;
    private static final int STATE_IN_PROGRESS = 1;
    private static final int STATE_READY = 2;
    private static final int STATE_CLAIMED = 3;
    private static final int STATE_FAILED = 4;

    private static volatile ExecutorService prefetchExecutor;
    private static volatile ArchiveReaderHandles cachedHandles;

    private final boolean active;
    private final FileChannel channel;
    private final int xorKey;
    private final long maxBudgetBytes;
    private final PrefetchEntry[] orderedEntries;
    private final Map<String, PrefetchEntry> entriesByPath;
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final AtomicInteger remainingWorkers = new AtomicInteger();
    private final Object budgetLock = new Object();

    private long inFlightPrefetchedBytes;
    private volatile boolean closed;

    private ArchivePrefetchSession() {
        this.active = false;
        this.channel = null;
        this.xorKey = 0;
        this.maxBudgetBytes = 0L;
        this.orderedEntries = new PrefetchEntry[0];
        this.entriesByPath = Map.of();
        this.closed = true;
    }

    private ArchivePrefetchSession(
            final FileChannel channel,
            final int xorKey,
            final long maxBudgetBytes,
            final PrefetchEntry[] orderedEntries,
            final Map<String, PrefetchEntry> entriesByPath) {
        this.active = true;
        this.channel = channel;
        this.xorKey = xorKey;
        this.maxBudgetBytes = maxBudgetBytes;
        this.orderedEntries = orderedEntries;
        this.entriesByPath = entriesByPath;
    }

    /** Starts a prefetch session for a Cubism {@code ArchiveReader} using default memory bounds. */
    static ArchivePrefetchSession tryStart(final Object archiveReader, final String excludePath) {
        return tryStart(archiveReader, excludePath, DEFAULT_MAX_PREFETCH_BYTES);
    }

    /** Starts a prefetch session for a Cubism {@code ArchiveReader} with an explicit byte budget. */
    static ArchivePrefetchSession tryStart(
            final Object archiveReader, final String excludePath, final long maxBudgetBytes) {
        if (archiveReader == null) {
            return DISABLED;
        }
        final ArchiveReaderHandles handles = resolveHandles(archiveReader.getClass());
        if (handles == null) {
            return DISABLED;
        }
        try {
            final Object rawMap = handles.pathToFileInfoMapField.get(archiveReader);
            if (!(rawMap instanceof Map<?, ?> map) || map.size() < MIN_PREFETCH_ENTRIES) {
                return DISABLED;
            }
            final Object input = handles.inputField.get(archiveReader);
            if (input == null || !handles.randomAccessFileClass.isInstance(input)) {
                return DISABLED;
            }
            final Object rawFile = handles.randomAccessFileField.get(input);
            if (!(rawFile instanceof File file)) {
                return DISABLED;
            }
            final Path path = file.toPath();
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                return DISABLED;
            }
            final Object header = handles.headerField.get(archiveReader);
            final int xorKey = header != null ? (int) handles.headerXorKey.invoke(header) : 0;

            final List<PrefetchEntry> entries = new ArrayList<>(map.size());
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String entryPath) || entryPath.equals(excludePath)) {
                    continue;
                }
                final Object fileInfo = entry.getValue();
                if (fileInfo == null) {
                    continue;
                }
                final long offset = (long) handles.entryOffset.invoke(fileInfo);
                final int compressedSize = (int) handles.entrySize.invoke(fileInfo);
                if (offset < 0L || compressedSize < 0) {
                    continue;
                }
                final Object compressOption = handles.entryCompressOption.invoke(fileInfo);
                final boolean compressed = compressOption != handles.rawCompressOption;
                entries.add(new PrefetchEntry(entryPath, offset, compressedSize, compressed));
            }
            if (entries.isEmpty()) {
                return DISABLED;
            }
            return startFromEntries(path, xorKey, maxBudgetBytes, entries, handles.loader);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return DISABLED;
        }
    }

    /** Starts a prefetch session directly from explicit entry metadata for deterministic testing. */
    static ArchivePrefetchSession startForEntries(
            final Path archivePath, final int xorKey, final long maxBudgetBytes, final List<PrefetchEntrySpec> specs)
            throws IOException {
        if (specs == null || specs.isEmpty()) {
            return DISABLED;
        }
        final List<PrefetchEntry> entries = new ArrayList<>(specs.size());
        for (final PrefetchEntrySpec spec : specs) {
            entries.add(new PrefetchEntry(spec.path(), spec.offset(), spec.size(), spec.compressed()));
        }
        return startFromEntries(
                archivePath, xorKey, maxBudgetBytes, entries, ArchivePrefetchSession.class.getClassLoader());
    }

    private static ArchivePrefetchSession startFromEntries(
            final Path archivePath,
            final int xorKey,
            final long maxBudgetBytes,
            final List<PrefetchEntry> entries,
            final ClassLoader loader)
            throws IOException {
        entries.sort(Comparator.comparingLong(entry -> entry.offset));
        final PrefetchEntry[] ordered = entries.toArray(PrefetchEntry[]::new);
        final Map<String, PrefetchEntry> byPath = new HashMap<>(ordered.length * 2);
        for (final PrefetchEntry entry : ordered) {
            byPath.put(entry.path, entry);
        }
        final FileChannel channel = FileChannel.open(archivePath, StandardOpenOption.READ);
        final long effectiveBudget = Math.max(1L, maxBudgetBytes);
        final ArchivePrefetchSession session =
                new ArchivePrefetchSession(channel, xorKey, effectiveBudget, ordered, byPath);
        session.launchWorkers(loader);
        return session;
    }

    boolean isActive() {
        return this.active && !this.closed;
    }

    /**
     * Claims a prefetched stream for {@code path}, returning {@code null} on miss, cancellation,
     * or failure so callers fall through to the native archive read path.
     */
    InputStream tryTakeStream(final String path) {
        if (!this.active || this.closed || path == null) {
            return null;
        }
        final PrefetchEntry entry = this.entriesByPath.get(path);
        if (entry == null) {
            return null;
        }
        final byte[] payload;
        synchronized (entry) {
            if (entry.state == STATE_QUEUED) {
                entry.state = STATE_CLAIMED;
                return null;
            }
            while (entry.state == STATE_IN_PROGRESS && !this.closed) {
                try {
                    entry.wait();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (entry.state != STATE_READY) {
                return null;
            }
            payload = entry.payload;
            entry.payload = null;
            entry.state = STATE_CLAIMED;
        }
        if (payload == null) {
            return null;
        }
        releaseBudget(payload.length);
        return ArchiveReadBridge.wrapRawBytes(payload);
    }

    /** Cancels pending prefetch work, clears unconsumed buffers, and closes the dedicated channel. */
    void close() {
        if (!this.active) {
            return;
        }
        this.closed = true;
        synchronized (this.budgetLock) {
            this.inFlightPrefetchedBytes = 0L;
            this.budgetLock.notifyAll();
        }
        for (final PrefetchEntry entry : this.orderedEntries) {
            synchronized (entry) {
                entry.payload = null;
                if (entry.state != STATE_CLAIMED) {
                    entry.state = STATE_FAILED;
                }
                entry.notifyAll();
            }
        }
        closeChannelQuietly();
    }

    long inFlightPrefetchedBytes() {
        synchronized (this.budgetLock) {
            return this.inFlightPrefetchedBytes;
        }
    }

    boolean isChannelOpen() {
        return this.channel != null && this.channel.isOpen();
    }

    static long computeMaxPrefetchBytes(final long maxHeapBytes) {
        if (maxHeapBytes <= 0L) {
            return MIN_PREFETCH_BUDGET_BYTES;
        }
        final long heapScaled = maxHeapBytes / HEAP_BUDGET_DIVISOR;
        return Math.min(MAX_PREFETCH_BUDGET_BYTES, Math.max(MIN_PREFETCH_BUDGET_BYTES, heapScaled));
    }

    static int computeWorkerCount(final int cpuCount) {
        return Math.min(MAX_POOL_THREADS, Math.max(MIN_POOL_THREADS, cpuCount - 1));
    }

    private void launchWorkers(final ClassLoader loader) {
        final ExecutorService pool = getOrCreatePool(loader);
        final int workerTarget =
                Math.min(computeWorkerCount(Runtime.getRuntime().availableProcessors()), this.orderedEntries.length);
        this.remainingWorkers.set(workerTarget);
        int launched = 0;
        for (int i = 0; i < workerTarget; i++) {
            try {
                pool.execute(this::runWorkerLoop);
                launched++;
            } catch (final RejectedExecutionException ignored) {
                this.remainingWorkers.decrementAndGet();
                break;
            }
        }
        if (launched == 0) {
            close();
        }
    }

    private void runWorkerLoop() {
        try {
            while (!this.closed) {
                if (!waitForBudgetSlot()) {
                    return;
                }
                final int idx = this.nextIndex.getAndIncrement();
                if (idx >= this.orderedEntries.length) {
                    return;
                }
                final PrefetchEntry entry = this.orderedEntries[idx];
                synchronized (entry) {
                    if (entry.state != STATE_QUEUED) {
                        continue;
                    }
                    entry.state = STATE_IN_PROGRESS;
                }
                prefetchSingleEntry(entry);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        } finally {
            if (this.remainingWorkers.decrementAndGet() == 0) {
                closeChannelQuietly();
            }
        }
    }

    private void prefetchSingleEntry(final PrefetchEntry entry) {
        byte[] payload = null;
        try {
            if (!this.closed) {
                final byte[] raw = readFully(entry.offset, entry.compressedSize);
                if (raw != null && !this.closed) {
                    if (this.xorKey != 0) {
                        deobfuscateXor(raw, this.xorKey);
                    }
                    if (entry.compressed) {
                        try (InputStream stream = ArchiveReadBridge.openZipEntryStream(raw)) {
                            payload = ArchiveReadBridge.readAllBytes(stream);
                        }
                    } else {
                        payload = raw;
                    }
                }
            }
        } catch (final IOException ignored) {
            payload = null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            payload = null;
        }

        boolean stored = false;
        synchronized (entry) {
            if (payload != null && !this.closed && entry.state == STATE_IN_PROGRESS) {
                entry.payload = payload;
                entry.state = STATE_READY;
                stored = true;
            } else if (entry.state == STATE_IN_PROGRESS) {
                entry.state = STATE_FAILED;
            }
            entry.notifyAll();
        }
        if (stored) {
            addBudget(payload.length);
        }
    }

    private byte[] readFully(final long offset, final int length) throws IOException {
        final byte[] buffer = new byte[length];
        if (length == 0) {
            return buffer;
        }
        final ByteBuffer dst = ByteBuffer.wrap(buffer);
        long pos = offset;
        while (dst.hasRemaining()) {
            if (this.closed) {
                return null;
            }
            final int read = this.channel.read(dst, pos);
            if (read < 0) {
                return null;
            }
            pos += read;
        }
        return buffer;
    }

    private static void deobfuscateXor(final byte[] data, final int xorKey) {
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ xorKey);
        }
    }

    private boolean waitForBudgetSlot() {
        synchronized (this.budgetLock) {
            while (!this.closed
                    && this.inFlightPrefetchedBytes > 0L
                    && this.inFlightPrefetchedBytes >= this.maxBudgetBytes) {
                try {
                    this.budgetLock.wait();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !this.closed;
        }
    }

    private void addBudget(final int bytes) {
        if (bytes <= 0) {
            return;
        }
        synchronized (this.budgetLock) {
            if (!this.closed) {
                this.inFlightPrefetchedBytes += bytes;
            }
        }
    }

    private void releaseBudget(final int bytes) {
        if (bytes <= 0) {
            return;
        }
        synchronized (this.budgetLock) {
            this.inFlightPrefetchedBytes = Math.max(0L, this.inFlightPrefetchedBytes - bytes);
            this.budgetLock.notifyAll();
        }
    }

    private void closeChannelQuietly() {
        if (this.channel == null) {
            return;
        }
        try {
            this.channel.close();
        } catch (final IOException ignored) {
            // Best-effort channel release during archive close or worker completion.
        }
    }

    private static ExecutorService getOrCreatePool(final ClassLoader preferredLoader) {
        final ExecutorService existing = prefetchExecutor;
        if (existing != null) {
            return existing;
        }
        synchronized (ArchivePrefetchSession.class) {
            if (prefetchExecutor == null) {
                final int workers = computeWorkerCount(Runtime.getRuntime().availableProcessors());
                final ClassLoader contextLoader = preferredLoader != null
                        ? preferredLoader
                        : RuntimeThreads.contextClassLoader(ArchivePrefetchSession.class);
                prefetchExecutor = RuntimeThreads.fixedLane(
                        workers,
                        POOL_QUEUE_CAPACITY,
                        RuntimeThreads.daemonThreads(PREFETCH_THREAD_NAME, contextLoader));
            }
            return prefetchExecutor;
        }
    }

    private static ArchiveReaderHandles resolveHandles(final Class<?> readerClass) {
        final ArchiveReaderHandles current = cachedHandles;
        final ClassLoader loader = readerClass.getClassLoader();
        if (current != null && current.loader == loader && current.readerClass == readerClass) {
            return current;
        }
        try {
            final Field inputField = readerClass.getDeclaredField(FIELD_INPUT);
            final Field headerField = readerClass.getDeclaredField(FIELD_HEADER);
            final Field pathMapField = readerClass.getDeclaredField(FIELD_PATH_TO_FILE_INFO_MAP);
            inputField.setAccessible(true);
            headerField.setAccessible(true);
            pathMapField.setAccessible(true);

            final Class<?> randomAccessFileClass = Class.forName(RANDOM_ACCESS_FILE_IMPL, false, loader);
            final Field fileField = randomAccessFileClass.getDeclaredField(FIELD_RANDOM_ACCESS_FILE);
            fileField.setAccessible(true);

            final Class<?> entryClass = Class.forName(ARCHIVE_FORMAT_ENTRY, false, loader);
            final Class<?> headerClass = Class.forName(ARCHIVE_FORMAT_HEADER, false, loader);
            final Class<?> compressOptionClass = Class.forName(COMPRESS_OPTION_CLASS, false, loader);
            final Object rawOption =
                    compressOptionClass.getField(COMPRESS_OPTION_RAW).get(null);

            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            final MethodHandle entryOffset =
                    lookup.findVirtual(entryClass, METHOD_ENTRY_OFFSET, MethodType.methodType(long.class));
            final MethodHandle entrySize =
                    lookup.findVirtual(entryClass, METHOD_ENTRY_SIZE, MethodType.methodType(int.class));
            final MethodHandle entryCompressOption = lookup.findVirtual(
                    entryClass, METHOD_ENTRY_COMPRESS_OPTION, MethodType.methodType(compressOptionClass));
            final MethodHandle headerXorKey =
                    lookup.findVirtual(headerClass, METHOD_HEADER_XOR_KEY, MethodType.methodType(int.class));

            final ArchiveReaderHandles resolved = new ArchiveReaderHandles(
                    loader,
                    readerClass,
                    inputField,
                    headerField,
                    pathMapField,
                    randomAccessFileClass,
                    fileField,
                    entryOffset,
                    entrySize,
                    entryCompressOption,
                    headerXorKey,
                    rawOption);
            cachedHandles = resolved;
            return resolved;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    record PrefetchEntrySpec(String path, long offset, int size, boolean compressed) {}

    private static final class PrefetchEntry {
        private final String path;
        private final long offset;
        private final int compressedSize;
        private final boolean compressed;
        private int state = STATE_QUEUED;
        private byte[] payload;

        private PrefetchEntry(
                final String path, final long offset, final int compressedSize, final boolean compressed) {
            this.path = path;
            this.offset = offset;
            this.compressedSize = compressedSize;
            this.compressed = compressed;
        }
    }

    private record ArchiveReaderHandles(
            ClassLoader loader,
            Class<?> readerClass,
            Field inputField,
            Field headerField,
            Field pathToFileInfoMapField,
            Class<?> randomAccessFileClass,
            Field randomAccessFileField,
            MethodHandle entryOffset,
            MethodHandle entrySize,
            MethodHandle entryCompressOption,
            MethodHandle headerXorKey,
            Object rawCompressOption) {}
}
