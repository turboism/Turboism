package dev.turboism.mapping.verification;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Minimal internal detector for the host-declared Editor release identity
 * ({@code com/live2d/cubism/h} product/version/date/build) inside the located
 * Editor JAR. The classfile constant pool is parsed with the JDK only; no
 * Cubism class is initialized or exposed and no class loader is touched.
 *
 * <p>Fail-closed contract: {@link #detect(Path)} returns empty for a missing
 * artifact, a missing or duplicated declaration class, a malformed classfile,
 * a missing/ambiguous version, date, product or build declaration, or an
 * unexpected extra version-build integer constant. No partial declaration is
 * ever returned.
 */
public final class CubismEditorReleaseDetector {

    /** Declaration class established by the 5.3.02/5.3.03 exact artifacts. */
    static final String DECLARATION_CLASS = "com/live2d/cubism/h.class";

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern DATE = Pattern.compile("\\d{4}/\\d{2}/\\d{2}");
    private static final String PRODUCT_MARKER = "Cubism Editor";
    /** Declaration classes are tiny; cap extraction before allocating or parsing. */
    static final int MAX_DECLARATION_CLASS_BYTES = 1024 * 1024;
    /** Version-build integers are nine-digit values (major.minor.patch + build). */
    private static final long VERSION_BUILD_FLOOR = 100_000_000L;

    private CubismEditorReleaseDetector() {
    }

    /**
     * Detects the release declaration of the Editor JAR or fails closed.
     *
     * @param editorJar exact located Editor JAR
     * @return the host-declared release identity, or empty when the declaration
     *     is missing, ambiguous or malformed
     */
    public static Optional<CubismEditorReleaseDeclaration> detect(final Path editorJar) {
        Objects.requireNonNull(editorJar, "editorJar");
        if (!Files.isRegularFile(editorJar)) {
            return Optional.empty();
        }
        final byte[] classBytes;
        try (JarFile jar = new JarFile(editorJar.toFile())) {
            classBytes = readDeclarationClass(jar);
        } catch (IOException | SecurityException failure) {
            return Optional.empty();
        }
        if (classBytes == null) {
            return Optional.empty();
        }
        return parse(classBytes);
    }

    private static byte[] readDeclarationClass(final JarFile jar) throws IOException {
        final List<JarEntry> declarations = new ArrayList<>();
        jar.stream().forEach(entry -> {
            if (DECLARATION_CLASS.equals(entry.getName())) {
                declarations.add(entry);
            }
        });
        if (declarations.size() != 1) {
            return null;
        }
        final JarEntry declaration = declarations.get(0);
        final long declaredSize = declaration.getSize();
        if (declaredSize > MAX_DECLARATION_CLASS_BYTES) {
            return null;
        }
        try (InputStream input = jar.getInputStream(declaration)) {
            return readBounded(input);
        }
    }

    private static byte[] readBounded(final InputStream input) throws IOException {
        final byte[] bytes = input.readNBytes(MAX_DECLARATION_CLASS_BYTES + 1);
        return bytes.length > MAX_DECLARATION_CLASS_BYTES ? null : bytes;
    }

    static Optional<CubismEditorReleaseDeclaration> parse(final byte[] classBytes) {
        if (classBytes == null || classBytes.length < 10
            || classBytes[0] != (byte) 0xCA || classBytes[1] != (byte) 0xFE
            || classBytes[2] != (byte) 0xBA || classBytes[3] != (byte) 0xBE) {
            return Optional.empty();
        }
        final ConstantPool pool;
        try {
            pool = ConstantPool.parse(classBytes);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }

        String version = null;
        String date = null;
        String product = null;
        for (String value : pool.utf8()) {
            if (VERSION.matcher(value).matches()) {
                if (version != null && !version.equals(value)) {
                    return Optional.empty(); // ambiguous version declaration
                }
                version = value;
            } else if (DATE.matcher(value).matches()) {
                if (date != null && !date.equals(value)) {
                    return Optional.empty(); // ambiguous date declaration
                }
                date = value;
            } else if (value.contains(PRODUCT_MARKER)) {
                if (product != null && !product.equals(value)) {
                    return Optional.empty(); // ambiguous product declaration
                }
                product = value;
            }
        }
        if (version == null || date == null || product == null) {
            return Optional.empty();
        }

        // Version-build integers are NOT formula-derived: each reviewed release
        // pins its exact declared build (5.2.03 -> 502030002, 5.3.02 -> 503020001,
        // 5.3.03 -> 503030001 measured from the exact JARs). A naive
        // major*10000000+minor*100000+patch*10000+1 formula would yield
        // 502030001 for 5.2.03, which is NOT the declared build, so builds are
        // pinned per reviewed version and anything else fails closed.
        final int expectedBuild = pinnedBuild(version);
        if (expectedBuild < 0) {
            return Optional.empty();
        }
        boolean buildFound = false;
        for (int value : pool.integers()) {
            if (value == expectedBuild) {
                buildFound = true;
            } else if (value >= VERSION_BUILD_FLOOR) {
                return Optional.empty(); // extra version-build integer: ambiguous
            }
        }
        if (!buildFound) {
            return Optional.empty();
        }
        return Optional.of(new CubismEditorReleaseDeclaration(
            product,
            version,
            date,
            expectedBuild
        ));
    }

    /**
     * Exact declared build pinned from the reviewed host JARs' {@code h}
     * declaration class (5.2.03 -> 502030002, 5.3.02 -> 503020001,
     * 5.3.03 -> 503030001). Unknown versions have no pinned build and fail
     * closed.
     */
    private static int pinnedBuild(final String version) {
        return switch (version) {
            case "5.2.03" -> 502_030_002;
            case "5.3.02" -> 503_020_001;
            case "5.3.03" -> 503_030_001;
            default -> -1;
        };
    }

    /**
     * Minimal classfile constant-pool reader (JDK only). Long/double entries
     * occupy two pool slots; the second slot is skipped.
     */
    private static final class ConstantPool {

        private final List<String> utf8 = new ArrayList<>();
        private final List<Integer> integers = new ArrayList<>();

        static ConstantPool parse(final byte[] bytes) {
            final ConstantPool pool = new ConstantPool();
            final int count = ((bytes[8] & 0xFF) << 8) | (bytes[9] & 0xFF);
            int offset = 10;
            for (int index = 1; index < count; index++) {
                if (offset >= bytes.length) {
                    throw new IllegalArgumentException("truncated constant pool");
                }
                final int tag = bytes[offset] & 0xFF;
                offset++;
                switch (tag) {
                    case 1: { // CONSTANT_Utf8
                        if (offset + 2 > bytes.length) {
                            throw new IllegalArgumentException("truncated UTF8 length");
                        }
                        final int length = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
                        offset += 2;
                        if (offset + length > bytes.length) {
                            throw new IllegalArgumentException("truncated UTF8 constant");
                        }
                        pool.utf8.add(new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8));
                        offset += length;
                        break;
                    }
                    case 3: { // CONSTANT_Integer
                        if (offset + 4 > bytes.length) {
                            throw new IllegalArgumentException("truncated integer constant");
                        }
                        pool.integers.add(
                            ((bytes[offset] & 0xFF) << 24)
                                | ((bytes[offset + 1] & 0xFF) << 16)
                                | ((bytes[offset + 2] & 0xFF) << 8)
                                | (bytes[offset + 3] & 0xFF)
                        );
                        offset += 4;
                        break;
                    }
                    case 4: // CONSTANT_Float
                        if (offset + 4 > bytes.length) {
                            throw new IllegalArgumentException("truncated float constant");
                        }
                        offset += 4;
                        break;
                    case 5: // CONSTANT_Long
                    case 6: // CONSTANT_Double
                        if (offset + 8 > bytes.length) {
                            throw new IllegalArgumentException("truncated long/double constant");
                        }
                        offset += 8;
                        index++; // two-slot entries
                        break;
                    case 7: // CONSTANT_Class
                    case 8: // CONSTANT_String
                    case 16: // CONSTANT_MethodType
                    case 19: // CONSTANT_Module
                    case 20: // CONSTANT_Package
                        if (offset + 2 > bytes.length) {
                            throw new IllegalArgumentException("truncated two-byte constant");
                        }
                        offset += 2;
                        break;
                    case 9: // CONSTANT_Fieldref
                    case 10: // CONSTANT_Methodref
                    case 11: // CONSTANT_InterfaceMethodref
                    case 12: // CONSTANT_NameAndType
                    case 17: // CONSTANT_Dynamic
                    case 18: // CONSTANT_InvokeDynamic
                        if (offset + 4 > bytes.length) {
                            throw new IllegalArgumentException("truncated four-byte constant");
                        }
                        offset += 4;
                        break;
                    case 15: // CONSTANT_MethodHandle
                        if (offset + 3 > bytes.length) {
                            throw new IllegalArgumentException("truncated method-handle constant");
                        }
                        offset += 3;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown constant pool tag " + tag);
                }
            }
            return pool;
        }

        List<String> utf8() {
            return List.copyOf(utf8);
        }

        List<Integer> integers() {
            return List.copyOf(integers);
        }
    }
}
