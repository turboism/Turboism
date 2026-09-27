import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Minimal build-time ASM namespace relocator; no ASM or external framework is
 * needed to run it. It rewrites only constant-pool UTF-8 strings and class
 * entry names for the known ASM package.
 */
public final class AsmNamespaceRelocator {
    private static final String FROM_SLASHED = "org/objectweb/asm";
    private static final String TO_SLASHED =
        "dev/turboism/validation/atlasimage/shaded/asm97";
    private static final String FROM_DOTTED = "org.objectweb.asm";
    private static final String TO_DOTTED =
        "dev.turboism.validation.atlasimage.shaded.asm97";
    private static final byte[] FROM_SLASHED_BYTES =
        FROM_SLASHED.getBytes(StandardCharsets.UTF_8);
    private static final byte[] TO_SLASHED_BYTES =
        TO_SLASHED.getBytes(StandardCharsets.UTF_8);
    private static final byte[] FROM_DOTTED_BYTES =
        FROM_DOTTED.getBytes(StandardCharsets.UTF_8);
    private static final byte[] TO_DOTTED_BYTES =
        TO_DOTTED.getBytes(StandardCharsets.UTF_8);
    private static final String LICENSE_NOTICE = String.join("\n",
        "ASM 9.7.1 relocation notice",
        "Artifact: org.ow2.asm:asm:9.7.1",
        "Original license: BSD-3-Clause",
        "License metadata: https://asm.ow2.io/license.html",
        "Original artifact SHA-256 is recorded by the enclosing build manifest.",
        "This validation-only JAR contains a namespace-relocated copy.",
        "");

    private AsmNamespaceRelocator() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "usage: input.jar output.jar license.pom expected-sha256");
        }
        final Path input = Path.of(args[0]);
        final Path output = Path.of(args[1]);
        final Path pom = Path.of(args[2]);
        final String expectedSha256 = args[3];
        final String actualSha256 = sha256(input);
        if (!expectedSha256.equals(actualSha256)) {
            throw new IllegalArgumentException("ASM source SHA-256 mismatch: " + actualSha256);
        }
        final Map<String, byte[]> entries = new TreeMap<>();
        int classCount = 0;
        try (JarFile jar = new JarFile(input.toFile())) {
            final Enumeration<JarEntry> sourceEntries = jar.entries();
            while (sourceEntries.hasMoreElements()) {
                final JarEntry sourceEntry = sourceEntries.nextElement();
                final String sourceName = sourceEntry.getName();
                if (sourceEntry.isDirectory() || skipEntry(sourceName)) {
                    continue;
                }
                final byte[] sourceBytes;
                try (InputStream stream = jar.getInputStream(sourceEntry)) {
                    sourceBytes = stream.readAllBytes();
                }
                final String targetName = relocateEntryName(sourceName);
                final byte[] targetBytes = sourceName.endsWith(".class")
                    ? relocateClass(sourceBytes) : sourceBytes;
                entries.put(targetName, targetBytes);
                if (sourceName.startsWith(FROM_SLASHED + "/")
                        && sourceName.endsWith(".class")) {
                    classCount++;
                }
            }
        }
        entries.put("META-INF/asm-9.7.1.pom", Files.readAllBytes(pom));
        entries.put("META-INF/NOTICE-ASM-9.7.1.txt",
            LICENSE_NOTICE.getBytes(StandardCharsets.UTF_8));
        if (classCount == 0 || !entries.containsKey(
                TO_SLASHED + "/ClassReader.class")) {
            throw new IllegalStateException("relocation produced no ASM ClassReader");
        }
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        try (OutputStream stream = Files.newOutputStream(output);
             JarOutputStream jar = new JarOutputStream(stream)) {
            for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
                final JarEntry targetEntry = new JarEntry(entry.getKey());
                targetEntry.setTime(0L);
                jar.putNextEntry(targetEntry);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        verifyOutput(output, classCount);
        System.out.println("asmNamespace=PASS from=" + FROM_DOTTED
            + " to=" + TO_DOTTED + " classes=" + classCount);
        System.out.println("asmSourceSha256=" + actualSha256);
        System.out.println("asmPomSha256=" + sha256(pom));
        System.out.println("asmShadedJarSha256=" + sha256(output));
        System.out.println("asmLicense=BSD-3-Clause");
    }

    private static boolean skipEntry(final String name) {
        return name.equals("META-INF/MANIFEST.MF")
            || name.equals("module-info.class")
            || name.endsWith(".SF")
            || name.endsWith(".RSA")
            || name.endsWith(".DSA");
    }

    private static String relocateEntryName(final String name) {
        return name.startsWith(FROM_SLASHED + "/")
            ? TO_SLASHED + name.substring(FROM_SLASHED.length()) : name;
    }

    private static byte[] relocateClass(final byte[] source) throws IOException {
        if (source.length < 10 || readInt(source, 0) != 0xCAFEBABE) {
            throw new IOException("invalid ASM classfile");
        }
        final int constantPoolCount = readUnsignedShort(source, 8);
        int cursor = 10;
        final ByteArrayOutputStream result = new ByteArrayOutputStream(source.length + 256);
        result.write(source, 0, 10);
        for (int index = 1; index < constantPoolCount; index++) {
            final int tag = readUnsignedByte(source, cursor++);
            result.write(tag);
            switch (tag) {
                case 1 -> {
                    final int length = readUnsignedShort(source, cursor);
                    cursor += 2;
                    if (cursor + length > source.length) {
                        throw new IOException("truncated ASM UTF-8 constant");
                    }
                    final byte[] value = Arrays.copyOfRange(source, cursor, cursor + length);
                    cursor += length;
                    final byte[] relocated = replace(value, FROM_SLASHED_BYTES,
                        TO_SLASHED_BYTES);
                    final byte[] fullyRelocated = replace(relocated, FROM_DOTTED_BYTES,
                        TO_DOTTED_BYTES);
                    if (fullyRelocated.length > 0xffff) {
                        throw new IOException("oversized relocated UTF-8 constant");
                    }
                    writeUnsignedShort(result, fullyRelocated.length);
                    result.write(fullyRelocated);
                }
                case 3, 4 -> {
                    copy(result, source, cursor, 4);
                    cursor += 4;
                }
                case 5, 6 -> {
                    copy(result, source, cursor, 8);
                    cursor += 8;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> {
                    copy(result, source, cursor, 2);
                    cursor += 2;
                }
                case 9, 10, 11, 12, 17, 18 -> {
                    copy(result, source, cursor, 4);
                    cursor += 4;
                }
                case 15 -> {
                    copy(result, source, cursor, 3);
                    cursor += 3;
                }
                default -> throw new IOException("unsupported constant-pool tag " + tag);
            }
        }
        if (cursor > source.length) {
            throw new IOException("truncated ASM classfile body");
        }
        result.write(source, cursor, source.length - cursor);
        return result.toByteArray();
    }

    private static byte[] replace(
        final byte[] source,
        final byte[] from,
        final byte[] to
    ) {
        final ByteArrayOutputStream result = new ByteArrayOutputStream(source.length);
        int cursor = 0;
        while (cursor < source.length) {
            if (matches(source, cursor, from)) {
                result.write(to, 0, to.length);
                cursor += from.length;
            } else {
                result.write(source[cursor]);
                cursor++;
            }
        }
        return result.toByteArray();
    }

    private static boolean matches(final byte[] source, final int offset, final byte[] value) {
        if (offset + value.length > source.length) {
            return false;
        }
        for (int index = 0; index < value.length; index++) {
            if (source[offset + index] != value[index]) {
                return false;
            }
        }
        return true;
    }

    private static void copy(
        final ByteArrayOutputStream target,
        final byte[] source,
        final int offset,
        final int length
    ) throws IOException {
        if (offset + length > source.length) {
            throw new IOException("truncated ASM constant pool");
        }
        target.write(source, offset, length);
    }

    private static int readUnsignedByte(final byte[] source, final int offset) throws IOException {
        if (offset >= source.length) {
            throw new IOException("truncated ASM classfile");
        }
        return source[offset] & 0xff;
    }

    private static int readUnsignedShort(final byte[] source, final int offset) throws IOException {
        if (offset + 2 > source.length) {
            throw new IOException("truncated ASM classfile");
        }
        return ((source[offset] & 0xff) << 8) | (source[offset + 1] & 0xff);
    }

    private static int readInt(final byte[] source, final int offset) throws IOException {
        if (offset + 4 > source.length) {
            throw new IOException("truncated ASM classfile");
        }
        return ((source[offset] & 0xff) << 24)
            | ((source[offset + 1] & 0xff) << 16)
            | ((source[offset + 2] & 0xff) << 8)
            | (source[offset + 3] & 0xff);
    }

    private static void writeUnsignedShort(
        final ByteArrayOutputStream target,
        final int value
    ) {
        target.write((value >>> 8) & 0xff);
        target.write(value & 0xff);
    }

    private static String sha256(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            final StringBuilder result = new StringBuilder(64);
            for (final byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (final NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 unavailable", exception);
        }
    }

    private static void verifyOutput(final Path output, final int expectedClassCount)
        throws IOException {
        int classCount = 0;
        try (JarFile jar = new JarFile(output.toFile())) {
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                final byte[] bytes;
                try (InputStream stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (contains(bytes, FROM_SLASHED_BYTES) || contains(bytes, FROM_DOTTED_BYTES)) {
                    throw new IOException("unrelocated ASM reference: " + entry.getName());
                }
                classCount++;
            }
        }
        if (classCount != expectedClassCount) {
            throw new IOException("relocated class count mismatch");
        }
    }

    private static boolean contains(final byte[] source, final byte[] value) {
        for (int offset = 0; offset <= source.length - value.length; offset++) {
            if (matches(source, offset, value)) {
                return true;
            }
        }
        return false;
    }
}
