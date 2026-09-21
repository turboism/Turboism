package dev.turboism.validation.atlastiming.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Relocates every class in the input JAR under a private prefix, so the validation agent can carry
 * its own bytecode library without exposing or depending on the original namespace at runtime.
 *
 * <p>Usage: {@code RelocateJar <input.jar> <output.jar> <from/prefix> <to/prefix>}</p>
 */
public final class RelocateJar {
    private RelocateJar() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalStateException(
                "usage: RelocateJar <input.jar> <output.jar> <fromPrefix> <toPrefix>");
        }
        final Path source = Path.of(args[0]);
        final Path target = Path.of(args[1]);
        final String from = args[2];
        final String to = args[3];
        final Remapper remapper = new PrefixRemapper(from, to);

        final Map<String, byte[]> resources = new LinkedHashMap<>();
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        try (ZipFile archive = new ZipFile(source.toFile())) {
            final var entries = archive.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream stream = archive.getInputStream(entry)) {
                    final byte[] bytes = stream.readAllBytes();
                    if (entry.getName().endsWith("module-info.class")) {
                        // A module descriptor is meaningless once the classes are relocated, and a
                        // stale one makes the packaged jar fail validation.
                        continue;
                    }
                    if (entry.getName().endsWith(".class")) {
                        classes.put(entry.getName(), bytes);
                    } else if (!entry.getName().startsWith("META-INF/MANIFEST.MF")) {
                        resources.put(entry.getName(), bytes);
                    }
                }
            }
        }

        int relocated = 0;
        final Map<String, byte[]> written = new TreeMap<>();
        for (final Map.Entry<String, byte[]> entry : classes.entrySet()) {
            final ClassReader reader = new ClassReader(entry.getValue());
            final ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, remapper), 0);
            // The output entry name follows the remapped class name, so the same tool both relocates
            // a library and rewrites references inside classes that keep their own package.
            final String outputName = remapper.map(reader.getClassName()) + ".class";
            written.put(outputName, writer.toByteArray());
            relocated++;
        }
        for (final Map.Entry<String, byte[]> entry : resources.entrySet()) {
            written.put(entry.getKey(), entry.getValue());
        }

        final Attributes attributes = new Attributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Implementation-Title", "Relocated " + from);
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            final var manifest = new java.util.jar.Manifest();
            manifest.getMainAttributes().putAll(attributes);
            manifest.write(jar);
            jar.closeEntry();
            for (final Map.Entry<String, byte[]> entry : written.entrySet()) {
                final JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                jar.putNextEntry(jarEntry);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        System.out.println("RELOCATE_DONE classes=" + relocated
            + " resources=" + resources.size()
            + " from=" + from + " to=" + to);
    }

    /** Rewrites internal names, descriptors and string constants that reference the old prefix. */
    private static final class PrefixRemapper extends Remapper {
        private final String from;
        private final String to;

        PrefixRemapper(final String from, final String to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String map(final String internalName) {
            if (internalName != null && internalName.startsWith(from)) {
                return to + internalName.substring(from.length());
            }
            return internalName;
        }

        @Override
        public Object mapValue(final Object value) {
            if (value instanceof String text && text.startsWith(from)) {
                return to + text.substring(from.length());
            }
            return super.mapValue(value);
        }
    }

}
