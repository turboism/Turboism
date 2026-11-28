package dev.turboism.adapter.cubism.optimization.save;

import dev.turboism.core.runtime.work.FatalErrors;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

/**
 * Streams JDOM XML documents directly into ZIP or raw archive payloads without allocating an
 * intermediate full-document XML string or growing 32-byte byte buffers.
 */
public final class StreamingXmlArchiveBridge {

    private static final String EMPTY_TEXT = "";
    private static final String SENTINEL_TEXT = "";
    private static final String JDOM_FORMAT_CLASS = "org.jdom.output.Format";
    private static final String JDOM_OUTPUTTER_CLASS = "org.jdom.output.XMLOutputter";
    private static final String JDOM_DOCUMENT_CLASS = "org.jdom.Document";
    private static final String GET_PRETTY_FORMAT = "getPrettyFormat";
    private static final String GET_COMPACT_FORMAT = "getCompactFormat";
    private static final String SET_INDENT = "setIndent";
    private static final String SET_FORMAT = "setFormat";
    private static final String OUTPUT_METHOD = "output";
    private static final String ARCHIVE_ADD_ENTRY_BYTES_METHOD = "turboism$addEntryBytes";
    private static final String GET_ZIP_LEVEL_METHOD = "getZipLevel";
    private static final String COMPRESS_OPTION_NAME_METHOD = "name";
    private static final String RAW_OPTION_NAME = "RAW";
    private static final String FUNCTION2_CLASS = "kotlin.jvm.functions.Function2";
    private static final String FUNCTION2_INVOKE_METHOD = "invoke";
    private static final String ADD_FILE_ENTRY_METHOD = "addFileEntry";
    private static final int FUNCTION2_ARITY = 2;
    private static final int DEFAULT_FAST_ZIP_LEVEL = 1;
    private static final int INITIAL_RAW_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_RAW_CHUNK_SIZE = 512 * 1024;

    private static final ThreadLocal<PendingDocument> PENDING_DOCUMENT = new ThreadLocal<>();

    private static volatile JdomHandles cachedJdomHandles;
    private static volatile ArchiveHandles cachedArchiveHandles;

    private StreamingXmlArchiveBridge() {}

    /**
     * Stashes the pending JDOM document for the immediately following {@code addFileEntry} call.
     *
     * @param xmlUtil unused Cubism XML utility singleton
     * @param document JDOM {@code Document} instance
     * @param indent requested indentation string
     * @param pretty {@code true} for pretty format, {@code false} for compact format
     * @return empty placeholder string
     */
    public static String captureDocumentForArchive(
            final Object xmlUtil, final Object document, final String indent, final boolean pretty) {
        if (document == null) {
            return SENTINEL_TEXT;
        }
        PENDING_DOCUMENT.set(new PendingDocument(document, indent == null ? EMPTY_TEXT : indent, pretty));
        return SENTINEL_TEXT;
    }

    /**
     * Clears any stashed pending document on normal or exceptional exit from {@code serialize_exe}.
     */
    public static void clearPendingDocument() {
        PENDING_DOCUMENT.remove();
    }

    /**
     * Streams the stashed JDOM document directly into {@code archiveWriter} as a compressed or raw
     * archive entry, falling back to stock {@code addFileEntry} if streaming cannot resolve handles.
     *
     * @param archiveWriter host {@code ArchiveWriter}
     * @param entryPath archive entry name (for example {@code "main.xml"})
     * @param tag archive entry tag
     * @param compressOption host {@code CompressOption}
     * @param fallbackWriter original Kotlin lambda (used only on fallback)
     * @return resolved unique entry path inside {@code archiveWriter}
     */
    public static String addMainXmlEntry(
            final Object archiveWriter,
            final String entryPath,
            final String tag,
            final Object compressOption,
            final Object fallbackWriter) {
        final PendingDocument pending = PENDING_DOCUMENT.get();
        PENDING_DOCUMENT.remove();
        if (pending == null || archiveWriter == null || compressOption == null) {
            return invokeFallbackAddEntry(archiveWriter, entryPath, tag, compressOption, fallbackWriter);
        }
        try {
            final byte[] payload = serializeDocumentToEntryBytes(
                    pending.document(), pending.indent(), pending.pretty(), compressOption);
            final ArchiveHandles handles = resolveArchiveHandles(archiveWriter, compressOption);
            if (handles != null && handles.addEntryBytes != null) {
                return (String) handles.addEntryBytes.invoke(archiveWriter, entryPath, tag, compressOption, payload);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
        return invokeStockDocumentAddEntry(archiveWriter, entryPath, tag, compressOption, pending);
    }

    /**
     * Serializes a JDOM {@code Document} directly into either raw UTF-8 bytes or a single-entry ZIP
     * byte array containing {@code "contents"}.
     */
    static byte[] serializeDocumentToEntryBytes(
            final Object document, final String indent, final boolean pretty, final Object compressOption)
            throws Throwable {
        final JdomHandles jdom = resolveJdomHandles(document);
        final Object format = pretty ? jdom.getPrettyFormat.invoke() : jdom.getCompactFormat.invoke();
        if (pretty) {
            jdom.setIndent.invoke(format, indent == null ? EMPTY_TEXT : indent);
        }
        final Object outputter = jdom.newOutputter.invoke();
        jdom.setFormat.invoke(outputter, format);

        final ArchiveHandles archiveHandles = resolveArchiveHandles(null, compressOption);
        final boolean isRaw = isRawOption(archiveHandles, compressOption);
        if (isRaw) {
            final ChunkedRawStream rawStream = new ChunkedRawStream();
            jdom.outputDocument.invoke(outputter, document, (OutputStream) rawStream);
            return rawStream.toByteArray();
        }
        final int zipLevel = resolveZipLevel(archiveHandles, compressOption);
        try (ParallelZipEntryBuilder zipBuilder = new ParallelZipEntryBuilder(zipLevel)) {
            jdom.outputDocument.invoke(outputter, document, (OutputStream) zipBuilder);
            return zipBuilder.toZipBytes();
        }
    }

    private static boolean isRawOption(final ArchiveHandles handles, final Object compressOption) throws Throwable {
        if (handles != null && handles.optionName != null) {
            return RAW_OPTION_NAME.equals(handles.optionName.invoke(compressOption));
        }
        return RAW_OPTION_NAME.equals(compressOption.toString());
    }

    private static int resolveZipLevel(final ArchiveHandles handles, final Object compressOption) throws Throwable {
        if (handles != null && handles.getZipLevel != null) {
            return (int) handles.getZipLevel.invoke(compressOption);
        }
        return DEFAULT_FAST_ZIP_LEVEL;
    }

    private static String invokeFallbackAddEntry(
            final Object archiveWriter,
            final String entryPath,
            final String tag,
            final Object compressOption,
            final Object fallbackWriter) {
        if (archiveWriter == null) {
            return entryPath;
        }
        try {
            final Class<?> lambdaIface = Class.forName(
                    FUNCTION2_CLASS, false, archiveWriter.getClass().getClassLoader());
            final java.lang.reflect.Method method = archiveWriter
                    .getClass()
                    .getMethod(
                            ADD_FILE_ENTRY_METHOD, String.class, String.class, compressOption.getClass(), lambdaIface);
            return (String) method.invoke(archiveWriter, entryPath, tag, compressOption, fallbackWriter);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("Failed to add main XML entry", failure);
        }
    }

    private static String invokeStockDocumentAddEntry(
            final Object archiveWriter,
            final String entryPath,
            final String tag,
            final Object compressOption,
            final PendingDocument pending) {
        try {
            final byte[] rawXml = serializeRawFallback(pending);
            final ClassLoader loader = archiveWriter.getClass().getClassLoader();
            final Class<?> lambdaIface = Class.forName(FUNCTION2_CLASS, false, loader);
            final java.lang.reflect.Method addEntry = archiveWriter
                    .getClass()
                    .getMethod(
                            ADD_FILE_ENTRY_METHOD, String.class, String.class, compressOption.getClass(), lambdaIface);
            final Object writerLambda = java.lang.reflect.Proxy.newProxyInstance(
                    loader, new Class<?>[] {lambdaIface}, (proxy, method, args) -> {
                        if (FUNCTION2_INVOKE_METHOD.equals(method.getName())
                                && args != null
                                && args.length == FUNCTION2_ARITY) {
                            final OutputStream out = (OutputStream) args[1];
                            out.write(rawXml);
                            return null;
                        }
                        return method.invoke(proxy, args);
                    });
            return (String) addEntry.invoke(archiveWriter, entryPath, tag, compressOption, writerLambda);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("Failed fallback XML serialization", failure);
        }
    }

    private static byte[] serializeRawFallback(final PendingDocument pending) throws Throwable {
        final JdomHandles jdom = resolveJdomHandles(pending.document());
        final Object format = pending.pretty() ? jdom.getPrettyFormat.invoke() : jdom.getCompactFormat.invoke();
        if (pending.pretty()) {
            jdom.setIndent.invoke(format, pending.indent());
        }
        final Object outputter = jdom.newOutputter.invoke();
        jdom.setFormat.invoke(outputter, format);
        final ChunkedRawStream rawStream = new ChunkedRawStream();
        jdom.outputDocument.invoke(outputter, pending.document(), (OutputStream) rawStream);
        return rawStream.toByteArray();
    }

    private static JdomHandles resolveJdomHandles(final Object document) throws Throwable {
        final ClassLoader loader =
                Objects.requireNonNull(document, "document").getClass().getClassLoader();
        final JdomHandles current = cachedJdomHandles;
        if (current != null && current.loader == loader) {
            return current;
        }
        synchronized (StreamingXmlArchiveBridge.class) {
            final JdomHandles recheck = cachedJdomHandles;
            if (recheck != null && recheck.loader == loader) {
                return recheck;
            }
            final JdomHandles created = buildJdomHandles(loader);
            cachedJdomHandles = created;
            return created;
        }
    }

    private static JdomHandles buildJdomHandles(final ClassLoader loader) throws Throwable {
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        final Class<?> formatClass = Class.forName(JDOM_FORMAT_CLASS, false, loader);
        final Class<?> outputterClass = Class.forName(JDOM_OUTPUTTER_CLASS, false, loader);
        final Class<?> documentClass = Class.forName(JDOM_DOCUMENT_CLASS, false, loader);
        return new JdomHandles(
                loader,
                lookup.findStatic(formatClass, GET_PRETTY_FORMAT, MethodType.methodType(formatClass)),
                lookup.findStatic(formatClass, GET_COMPACT_FORMAT, MethodType.methodType(formatClass)),
                lookup.findVirtual(formatClass, SET_INDENT, MethodType.methodType(formatClass, String.class)),
                lookup.findConstructor(outputterClass, MethodType.methodType(void.class)),
                lookup.findVirtual(outputterClass, SET_FORMAT, MethodType.methodType(void.class, formatClass)),
                lookup.findVirtual(
                        outputterClass,
                        OUTPUT_METHOD,
                        MethodType.methodType(void.class, documentClass, OutputStream.class)));
    }

    private static ArchiveHandles resolveArchiveHandles(final Object archiveWriter, final Object compressOption)
            throws Throwable {
        final ClassLoader loader = archiveWriter != null
                ? archiveWriter.getClass().getClassLoader()
                : compressOption.getClass().getClassLoader();
        final ArchiveHandles current = cachedArchiveHandles;
        if (current != null && current.loader == loader && (archiveWriter == null || current.addEntryBytes != null)) {
            return current;
        }
        synchronized (StreamingXmlArchiveBridge.class) {
            final ArchiveHandles recheck = cachedArchiveHandles;
            if (recheck != null
                    && recheck.loader == loader
                    && (archiveWriter == null || recheck.addEntryBytes != null)) {
                return recheck;
            }
            final ArchiveHandles created = buildArchiveHandles(
                    loader, archiveWriter != null ? archiveWriter.getClass() : null, compressOption.getClass());
            cachedArchiveHandles = created;
            return created;
        }
    }

    private static ArchiveHandles buildArchiveHandles(
            final ClassLoader loader, final Class<?> writerClass, final Class<?> optionClass) throws Throwable {
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        final MethodHandle optionName =
                lookup.findVirtual(optionClass, COMPRESS_OPTION_NAME_METHOD, MethodType.methodType(String.class));
        final MethodHandle getZipLevel =
                lookup.findVirtual(optionClass, GET_ZIP_LEVEL_METHOD, MethodType.methodType(int.class));
        MethodHandle addEntryBytes = null;
        if (writerClass != null) {
            try {
                addEntryBytes = lookup.findVirtual(
                        writerClass,
                        ARCHIVE_ADD_ENTRY_BYTES_METHOD,
                        MethodType.methodType(String.class, String.class, String.class, optionClass, byte[].class));
            } catch (final NoSuchMethodException ignored) {
                addEntryBytes = null;
            }
        }
        return new ArchiveHandles(loader, optionName, getZipLevel, addEntryBytes);
    }

    /**
     * Collects UTF-8 output chunks without exponential resizing and emits one exact-sized array.
     */
    static final class ChunkedRawStream extends OutputStream {

        private final Deque<byte[]> fullChunks = new ArrayDeque<>();
        private final byte[] singleByte = new byte[1];
        private byte[] current = new byte[INITIAL_RAW_BUFFER_SIZE];
        private int currentPos;
        private long totalSize;

        @Override
        public void write(final int b) throws IOException {
            this.singleByte[0] = (byte) b;
            write(this.singleByte, 0, 1);
        }

        @Override
        public void write(final byte[] bytes, final int off, final int len) {
            if (len == 0) {
                return;
            }
            int offset = off;
            int remaining = len;
            while (remaining > 0) {
                final int space = this.current.length - this.currentPos;
                if (space == 0) {
                    this.fullChunks.addLast(this.current);
                    this.current = new byte[Math.max(INITIAL_RAW_BUFFER_SIZE, Math.min(len, MAX_RAW_CHUNK_SIZE))];
                    this.currentPos = 0;
                    continue;
                }
                final int copy = Math.min(remaining, space);
                System.arraycopy(bytes, offset, this.current, this.currentPos, copy);
                this.currentPos += copy;
                this.totalSize += copy;
                offset += copy;
                remaining -= copy;
            }
        }

        byte[] toByteArray() {
            final int total = Math.toIntExact(this.totalSize);
            if (this.fullChunks.isEmpty()) {
                return this.currentPos == this.current.length
                        ? this.current
                        : Arrays.copyOf(this.current, this.currentPos);
            }
            final byte[] result = new byte[total];
            int pos = 0;
            for (final byte[] chunk : this.fullChunks) {
                System.arraycopy(chunk, 0, result, pos, chunk.length);
                pos += chunk.length;
            }
            if (this.currentPos > 0) {
                System.arraycopy(this.current, 0, result, pos, this.currentPos);
            }
            return result;
        }
    }

    private record PendingDocument(Object document, String indent, boolean pretty) {}

    private record JdomHandles(
            ClassLoader loader,
            MethodHandle getPrettyFormat,
            MethodHandle getCompactFormat,
            MethodHandle setIndent,
            MethodHandle newOutputter,
            MethodHandle setFormat,
            MethodHandle outputDocument) {}

    private record ArchiveHandles(
            ClassLoader loader, MethodHandle optionName, MethodHandle getZipLevel, MethodHandle addEntryBytes) {}
}
