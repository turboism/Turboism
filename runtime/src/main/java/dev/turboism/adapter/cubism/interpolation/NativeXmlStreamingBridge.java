package dev.turboism.adapter.cubism.interpolation;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Optional pinned archive callback: emits official JDOM formatting without a giant XML String. */
public final class NativeXmlStreamingBridge {
    private static volatile boolean enabled;
    private static final java.util.concurrent.atomic.AtomicLong ARCHIVES = new java.util.concurrent.atomic.AtomicLong();
    /**
     * @return total archives emitted through the streaming path
     */
    public static long streamedArchives() {
        return ARCHIVES.get();
    }

    private static final ClassValue<Optional<Binding>> BINDINGS = new ClassValue<>() {
        @Override
        protected Optional<Binding> computeValue(Class<?> writer) {
            try {
                return Optional.of(new Binding(writer));
            } catch (Exception | LinkageError refused) {
                return Optional.empty();
            }
        }
    };

    private NativeXmlStreamingBridge() {}
    /**
     * @param value installer lifecycle switch for the streaming bridge
     */
    public static void enabled(boolean value) {
        enabled = value;
    }
    /**
     * @return {@code true} while the streaming bridge is armed
     */
    public static boolean isEnabled() {
        return enabled;
    }
    /**
     * Streams one archive through the official writer without materializing the XML string.
     *
     * @param writer host XmlWriter instance
     * @param document document to serialize
     * @param compression host compression settings
     * @return {@code false} when the call is ineligible and must run the official path
     */
    public static boolean archive(Object writer, Object document, Object compression) {
        if (!enabled
                || writer == null
                || document == null
                || compression == null
                || !DerivedCacheSerializationBridge.mayStream(document)) return false;
        var binding = BINDINGS.get(writer.getClass());
        if (binding.isEmpty()) return false;
        try {
            binding.get().write(writer, document, compression);
            ARCHIVES.incrementAndGet();
            return true;
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Native streaming archive failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native streaming archive failed", failure);
        } finally {
            DerivedCacheSerializationBridge.releaseDocument();
        }
    }

    private static final class Binding {
        final Method fileWriter, addEntry, output, setFormat, pretty, indent;
        final Constructor<?> outputter;
        final Class<?> function, documentType, compressionType;
        final Object unit;
        final String mainTag;

        Binding(Class<?> writer) throws Exception {
            if (!writer.getName().equals("com.live2d.serialize.XmlWriter"))
                throw new IllegalArgumentException("writer");
            Path artifact = Path.of(writer.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toRealPath();
            NativeMeshInterpolationBridge.requireAdmittedHostArtifact(artifact);
            ClassLoader loader = writer.getClassLoader();
            function = Class.forName("kotlin.jvm.functions.Function2", false, loader);
            documentType = Class.forName("org.jdom.Document", false, loader);
            compressionType = Class.forName("com.live2d.serialize.CompressOption", false, loader);
            Class<?> archive = Class.forName("com.live2d.serialize.archiver.ArchiveWriter", false, loader);
            if (archive.getClassLoader() != loader
                    || !Path.of(archive.getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                            .toRealPath()
                            .equals(artifact)) throw new IllegalArgumentException("archive identity");
            fileWriter = writer.getMethod("getFileWriter");
            addEntry = archive.getMethod("addFileEntry", String.class, String.class, compressionType, function);
            Class<?> format = Class.forName("org.jdom.output.Format", false, loader);
            Class<?> xml = Class.forName("org.jdom.output.XMLOutputter", false, loader);
            outputter = xml.getConstructor();
            pretty = format.getMethod("getPrettyFormat");
            indent = format.getMethod("setIndent", String.class);
            setFormat = xml.getMethod("setFormat", format);
            output = xml.getMethod("output", documentType, OutputStream.class);
            Class<?> archiveFormat = Class.forName("com.live2d.serialize.archiver.ArchiveFormat", false, loader);
            mainTag = (String) archiveFormat
                    .getMethod("getTAG_MAIN_XML")
                    .invoke(archiveFormat.getField("INSTANCE").get(null));
            unit = Class.forName("kotlin.Unit", false, loader)
                    .getField("INSTANCE")
                    .get(null);
        }

        void write(Object writer, Object document, Object compression) throws ReflectiveOperationException {
            if (!documentType.isInstance(document) || !compressionType.isInstance(compression))
                throw new IllegalArgumentException("native payload types");
            Object callback = Proxy.newProxyInstance(
                    function.getClassLoader(), new Class<?>[] {function}, (proxy, method, args) -> {
                        if (method.getName().equals("invoke") && args != null && args.length == 2) {
                            if (!(args[0] instanceof String) || !(args[1] instanceof OutputStream stream))
                                throw new IllegalArgumentException("archive callback");
                            Object xml = outputter.newInstance(), format = pretty.invoke(null);
                            indent.invoke(format, "");
                            setFormat.invoke(xml, format);
                            // ArchiveWriter owns the compression stream. Flush, but never close it here.
                            var buffered = new BufferedOutputStream(stream, 65536);
                            try {
                                output.invoke(xml, document, buffered);
                                buffered.flush();
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                            return unit;
                        }
                        return switch (method.getName()) {
                            case "toString" -> "NativeXmlStreamingCallback";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
            addEntry.invoke(fileWriter.invoke(writer), "main.xml", mainTag, compression, callback);
        }
    }
}
