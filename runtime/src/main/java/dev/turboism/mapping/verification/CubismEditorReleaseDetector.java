package dev.turboism.mapping.verification;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Detector for the host-declared Editor release identity inside the located
 * Editor JAR. The declaration is a set of constants the host assigns to its
 * own static fields from {@code <clinit>} (or a {@code ConstantValue}
 * attribute): product marker, semantic version, release date, and build
 * number. The classfile is parsed with the JDK only; no Cubism class is
 * initialized or exposed and no class loader is touched.
 *
 * <p>The observed declaration location is {@value #DECLARATION_CLASS} for the
 * reviewed 5.2/5.3 artifacts. Because an obfuscated build may rename that
 * class, a declaration that cannot be read there triggers a bounded metadata
 * scan over {@code com/live2d/cubism/} top-level classes: every class whose
 * own assignments carry a complete product/version/build declaration is a
 * candidate, all candidates must agree, and none may be truncated.</p>
 *
 * <p>Fail-closed contract: {@link #probe(Path)} reports a distinct status for
 * a missing artifact, a non-Cubism archive, a missing/duplicated/malformed
 * declaration, conflicting declarations, or a scan-limit violation. The
 * declared build is reported verbatim — it is never derived from the version
 * or pinned to a reviewed table.</p>
 */
public final class CubismEditorReleaseDetector {

    /** Declaration class established by the reviewed 5.2/5.3 artifacts. */
    static final String DECLARATION_CLASS = "com/live2d/cubism/h.class";

    /** Host anchor: the application entry class that makes an archive a Cubism Editor host. */
    static final String HOST_ANCHOR_CLASS = "com/live2d/cubism/CEAppCtrl.class";

    /** Package prefix scanned for a renamed declaration class. */
    static final String DECLARATION_SCAN_PREFIX = "com/live2d/cubism/";

    static final String PRODUCT_MARKER = "Cubism Editor";

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern DATE = Pattern.compile("\\d{4}/\\d{2}/\\d{2}");

    /** Declaration classes are bounded; cap extraction before allocating or parsing. */
    static final int MAX_DECLARATION_CLASS_BYTES = 1024 * 1024;

    /** Fallback scan reads at most this many candidate classes. */
    static final int MAX_DECLARATION_SCAN_CLASSES = 512;

    /** Fallback scan reads at most this many cumulative class bytes. */
    static final long MAX_DECLARATION_SCAN_BYTES = 64L * 1024 * 1024;

    /** Version-build integers are nine-digit values assigned verbatim by the host. */
    private static final long VERSION_BUILD_FLOOR = 100_000_000L;

    private CubismEditorReleaseDetector() {}

    /**
     * Probes the declared identity of the Editor JAR without pinning a version.
     *
     * @param editorJar located Editor JAR
     * @return the probe verdict and, on success, the declared identity
     */
    public static HostIdentityProbe probe(final Path editorJar) {
        Objects.requireNonNull(editorJar, "editorJar");
        if (!Files.isRegularFile(editorJar)) {
            return HostIdentityProbe.rejected(HostIdentityProbe.Status.UNREADABLE, "artifact missing or not a file");
        }
        final HostArtifactDigest artifact;
        try {
            artifact = HostArtifactDigest.from(editorJar);
        } catch (IOException | RuntimeException failure) {
            return HostIdentityProbe.rejected(HostIdentityProbe.Status.UNREADABLE, "artifact unreadable");
        }
        try (JarFile jar = new JarFile(editorJar.toFile())) {
            if (jar.getJarEntry(HOST_ANCHOR_CLASS) == null) {
                return HostIdentityProbe.rejected(
                        HostIdentityProbe.Status.NOT_CUBISM, "cubism editor anchor class absent");
            }
            return scanDeclaration(jar, artifact);
        } catch (IOException | RuntimeException failure) {
            return HostIdentityProbe.rejected(HostIdentityProbe.Status.UNREADABLE, "artifact unreadable as jar");
        }
    }

    /**
     * Reads the declared identity inside an already-opened archive: canonical
     * declaration class first, bounded top-level scan as fallback. The anchor
     * check is the caller's job — declaration reading is deliberately usable
     * without it for the legacy {@link #detect(Path)} surface.
     */
    private static HostIdentityProbe scanDeclaration(final JarFile jar, final HostArtifactDigest artifact) {
        final List<JarEntry> declarations = new ArrayList<>();
        final List<JarEntry> scanEntries = new ArrayList<>();
        final Set<String> duplicateNames = new LinkedHashSet<>();
        jar.stream().forEach(entry -> {
            final String name = entry.getName();
            if (DECLARATION_CLASS.equals(name)) {
                declarations.add(entry);
            }
            if (!entry.isDirectory()
                    && name.endsWith(".class")
                    && name.startsWith(DECLARATION_SCAN_PREFIX)
                    && name.indexOf('/', DECLARATION_SCAN_PREFIX.length()) < 0) {
                if (!scanEntries.isEmpty()
                        && scanEntries.stream().anyMatch(e -> e.getName().equals(name))) {
                    duplicateNames.add(name);
                } else {
                    scanEntries.add(entry);
                }
            }
        });
        if (!duplicateNames.isEmpty()) {
            return HostIdentityProbe.rejected(
                    HostIdentityProbe.Status.DECLARATION_AMBIGUOUS,
                    "duplicate zip entry in declaration scope: "
                            + duplicateNames.iterator().next());
        }
        if (declarations.size() > 1) {
            return HostIdentityProbe.rejected(
                    HostIdentityProbe.Status.DECLARATION_AMBIGUOUS, "duplicate declaration class entry");
        }

        boolean malformed = false;
        if (declarations.size() == 1) {
            final DeclaredAssignment assignment = readDeclaration(jar, declarations.get(0));
            if (assignment == null) {
                malformed = true;
            } else {
                final Evaluation evaluation = evaluate(assignment, stripClassSuffix(DECLARATION_CLASS), artifact);
                if (evaluation.outcome() == Outcome.COMPLETE) {
                    return HostIdentityProbe.declared(evaluation.identity());
                }
                if (evaluation.outcome() == Outcome.CONFLICTING) {
                    return HostIdentityProbe.rejected(
                            HostIdentityProbe.Status.DECLARATION_AMBIGUOUS,
                            "conflicting values inside declaration class");
                }
            }
        }

        // Declaration class missing, unreadable, or incomplete: scan the bounded
        // top-level package for any class assigning a complete declaration.
        final List<CubismHostIdentity> candidates = new ArrayList<>();
        boolean conflictingSeen = false;
        if (scanEntries.size() > MAX_DECLARATION_SCAN_CLASSES) {
            return HostIdentityProbe.rejected(
                    HostIdentityProbe.Status.SCAN_LIMIT_EXCEEDED, "declaration scan class budget exceeded");
        }
        long scanned = 0L;
        for (final JarEntry entry : scanEntries) {
            if (entry.getSize() > MAX_DECLARATION_CLASS_BYTES) {
                continue;
            }
            if (scanned + Math.max(0, entry.getSize()) > MAX_DECLARATION_SCAN_BYTES) {
                return HostIdentityProbe.rejected(
                        HostIdentityProbe.Status.SCAN_LIMIT_EXCEEDED, "declaration scan byte budget exceeded");
            }
            final DeclaredAssignment assignment = readDeclaration(jar, entry);
            scanned += Math.max(0, entry.getSize());
            if (assignment == null) {
                continue;
            }
            final Evaluation evaluation = evaluate(assignment, stripClassSuffix(entry.getName()), artifact);
            if (evaluation.outcome() == Outcome.CONFLICTING) {
                conflictingSeen = true;
                continue;
            }
            if (evaluation.outcome() == Outcome.COMPLETE
                    && evaluation.identity().isCubismEditor()) {
                candidates.add(evaluation.identity());
            }
        }
        if (conflictingSeen && candidates.isEmpty()) {
            return HostIdentityProbe.rejected(
                    HostIdentityProbe.Status.DECLARATION_AMBIGUOUS, "declaration candidates carry conflicting values");
        }
        if (candidates.isEmpty()) {
            return HostIdentityProbe.rejected(
                    malformed
                            ? HostIdentityProbe.Status.DECLARATION_MALFORMED
                            : HostIdentityProbe.Status.DECLARATION_MISSING,
                    malformed
                            ? "declaration class present but malformed"
                            : "no class declares a complete cubism identity");
        }
        final CubismHostIdentity first = candidates.get(0);
        for (final CubismHostIdentity candidate : candidates) {
            if (!candidate.product().equals(first.product())
                    || !candidate.version().equals(first.version())
                    || candidate.build() != first.build()
                    || !candidate.date().equals(first.date())) {
                return HostIdentityProbe.rejected(
                        HostIdentityProbe.Status.DECLARATION_AMBIGUOUS,
                        "declaration classes disagree: " + first.declarationClass() + " vs "
                                + candidate.declarationClass());
            }
        }
        return HostIdentityProbe.declared(first);
    }

    /**
     * Detects the release declaration inside a located Editor JAR or fails
     * closed. Unlike {@link #probe(Path)} this is declaration-level only: the
     * Cubism anchor class is not required because callers use this view to
     * compare the host's own declaration against a separately verified
     * artifact identity.
     *
     * <p>The view is stricter than {@link #probe(Path)} on completeness: a
     * declaration without a release date does not qualify, and the returned
     * declaration always carries every field the reviewed artifacts declared.</p>
     *
     * @param editorJar located Editor JAR
     * @return the host-declared release identity, or empty when the declaration
     *     is missing, ambiguous, malformed, or incomplete
     */
    public static Optional<CubismEditorReleaseDeclaration> detect(final Path editorJar) {
        Objects.requireNonNull(editorJar, "editorJar");
        if (!Files.isRegularFile(editorJar)) {
            return Optional.empty();
        }
        final HostArtifactDigest artifact;
        try {
            artifact = HostArtifactDigest.from(editorJar);
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
        try (JarFile jar = new JarFile(editorJar.toFile())) {
            final HostIdentityProbe probe = scanDeclaration(jar, artifact);
            if (!probe.declared() || probe.identity().orElseThrow().date().isEmpty()) {
                return Optional.empty();
            }
            final CubismHostIdentity identity = probe.identity().orElseThrow();
            return Optional.of(new CubismEditorReleaseDeclaration(
                    identity.product(), identity.version(), identity.date().orElseThrow(), identity.build()));
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** Byte-level seam retained for focused declaration tests. */
    static Optional<CubismEditorReleaseDeclaration> parse(final byte[] classBytes) {
        final DeclaredAssignment assignment = parseAssignment(classBytes);
        if (assignment == null) {
            return Optional.empty();
        }
        final Evaluation evaluation = evaluate(assignment, "declared", null);
        if (evaluation.outcome() != Outcome.COMPLETE
                || evaluation.identity().date().isEmpty()) {
            return Optional.empty();
        }
        final CubismHostIdentity identity = evaluation.identity();
        return Optional.of(new CubismEditorReleaseDeclaration(
                identity.product(), identity.version(), identity.date().orElseThrow(), identity.build()));
    }

    private enum Outcome {
        INCOMPLETE,
        COMPLETE,
        CONFLICTING
    }

    private record Evaluation(Outcome outcome, CubismHostIdentity identity) {}

    private static Evaluation evaluate(
            final DeclaredAssignment assignment, final String declarationClass, final HostArtifactDigest artifact) {
        String product = null;
        String version = null;
        String date = null;
        Integer build = null;
        for (final String value : assignment.strings()) {
            if (VERSION.matcher(value).matches()) {
                if (version != null && !version.equals(value)) {
                    return new Evaluation(Outcome.CONFLICTING, null);
                }
                version = value;
            } else if (DATE.matcher(value).matches()) {
                if (date != null && !date.equals(value)) {
                    return new Evaluation(Outcome.CONFLICTING, null);
                }
                date = value;
            } else if (value.contains(PRODUCT_MARKER)) {
                if (product != null && !product.equals(value)) {
                    return new Evaluation(Outcome.CONFLICTING, null);
                }
                product = value;
            }
        }
        for (final int value : assignment.ints()) {
            if (value >= VERSION_BUILD_FLOOR) {
                if (build != null && build != value) {
                    return new Evaluation(Outcome.CONFLICTING, null);
                }
                build = value;
            }
        }
        if (product == null || version == null || build == null) {
            return new Evaluation(Outcome.INCOMPLETE, null);
        }
        return new Evaluation(
                Outcome.COMPLETE,
                new CubismHostIdentity(
                        product,
                        version,
                        Optional.ofNullable(date),
                        build,
                        declarationClass,
                        artifact == null ? new HostArtifactDigest(0, "0".repeat(64)) : artifact));
    }

    private static String stripClassSuffix(final String entryName) {
        return entryName.endsWith(".class")
                ? entryName.substring(0, entryName.length() - ".class".length())
                : entryName;
    }

    private static DeclaredAssignment readDeclaration(final JarFile jar, final JarEntry entry) {
        if (entry.getSize() > MAX_DECLARATION_CLASS_BYTES) {
            return null;
        }
        final byte[] bytes;
        try (InputStream input = jar.getInputStream(entry)) {
            bytes = dev.turboism.sdk.io.BoundedInput.readNBytes(input, MAX_DECLARATION_CLASS_BYTES);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
        return parseAssignment(bytes);
    }

    /** Parsed constants a class assigns to its own static fields. */
    private record DeclaredAssignment(Set<String> strings, Set<Integer> ints) {}

    /**
     * Kept for focused tests: parses raw class bytes into the declared assignment surface.
     */
    static DeclaredAssignment parseAssignment(final byte[] classBytes) {
        if (classBytes == null
                || classBytes.length < 10
                || classBytes[0] != (byte) 0xCA
                || classBytes[1] != (byte) 0xFE
                || classBytes[2] != (byte) 0xBA
                || classBytes[3] != (byte) 0xBE) {
            return null;
        }
        try {
            final DataInputStream data = new DataInputStream(new ByteArrayInputStream(classBytes));
            data.readInt();
            data.readUnsignedShort();
            data.readUnsignedShort();
            final ConstantPool pool = ConstantPool.parse(data);
            data.readUnsignedShort(); // access flags
            final String thisClass = pool.classInternalName(data.readUnsignedShort());
            data.readUnsignedShort(); // super class
            final int interfaceCount = data.readUnsignedShort();
            data.skipNBytes((long) interfaceCount * 2);
            final DeclaredAssignment assignment = new DeclaredAssignment(new LinkedHashSet<>(), new LinkedHashSet<>());
            readFields(data, pool, assignment);
            readClinit(data, pool, thisClass, assignment);
            return assignment;
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    private static void readFields(
            final DataInputStream data, final ConstantPool pool, final DeclaredAssignment assignment)
            throws IOException {
        final int fieldCount = data.readUnsignedShort();
        for (int field = 0; field < fieldCount; field++) {
            data.readUnsignedShort(); // access
            data.readUnsignedShort(); // name
            data.readUnsignedShort(); // descriptor
            final int attributeCount = data.readUnsignedShort();
            for (int attribute = 0; attribute < attributeCount; attribute++) {
                final String name = pool.utf8(data.readUnsignedShort());
                final int length = data.readInt();
                if (length < 0) {
                    throw new IOException("negative attribute length");
                }
                if ("ConstantValue".equals(name) && length == 2) {
                    final int index = data.readUnsignedShort();
                    final String string = pool.stringValue(index);
                    if (string != null) {
                        assignment.strings().add(string);
                    }
                    final Integer integer = pool.integerValue(index);
                    if (integer != null) {
                        assignment.ints().add(integer);
                    }
                } else {
                    data.skipNBytes(length);
                }
            }
        }
    }

    private static void readClinit(
            final DataInputStream data,
            final ConstantPool pool,
            final String thisClass,
            final DeclaredAssignment assignment)
            throws IOException {
        final int methodCount = data.readUnsignedShort();
        for (int method = 0; method < methodCount; method++) {
            data.readUnsignedShort(); // access
            final String name = pool.utf8(data.readUnsignedShort());
            data.readUnsignedShort(); // descriptor
            final int attributeCount = data.readUnsignedShort();
            for (int attribute = 0; attribute < attributeCount; attribute++) {
                final String attributeName = pool.utf8(data.readUnsignedShort());
                final int length = data.readInt();
                if (length < 0) {
                    throw new IOException("negative attribute length");
                }
                if ("<clinit>".equals(name) && "Code".equals(attributeName)) {
                    readClinitCode(data, pool, thisClass, assignment);
                } else {
                    data.skipNBytes(length);
                }
            }
        }
    }

    /**
     * Walks {@code <clinit>} bytecode tracking the most recently pushed
     * constant; when an own-class {@code putstatic} consumes it, the constant
     * joins the declared assignment surface. Any non-push opcode clears the
     * pending constant so a call/load sequence never binds a stale value.
     */
    private static void readClinitCode(
            final DataInputStream data,
            final ConstantPool pool,
            final String thisClass,
            final DeclaredAssignment assignment)
            throws IOException {
        data.readUnsignedShort(); // max stack
        data.readUnsignedShort(); // max locals
        final int codeLength = data.readInt();
        if (codeLength < 0 || codeLength > MAX_DECLARATION_CLASS_BYTES) {
            throw new IOException("unbounded clinit code");
        }
        final byte[] code = data.readNBytes(codeLength);
        if (code.length != codeLength) {
            throw new IOException("truncated clinit code");
        }
        // exception table and nested attributes are skipped wholesale: the
        // bytecode scan below already consumed the code array in place.
        final int exceptionCount = data.readUnsignedShort();
        data.skipNBytes((long) exceptionCount * 8);
        final int nestedCount = data.readUnsignedShort();
        for (int nested = 0; nested < nestedCount; nested++) {
            data.readUnsignedShort();
            final int nestedLength = data.readInt();
            if (nestedLength < 0) {
                throw new IOException("negative nested attribute length");
            }
            data.skipNBytes(nestedLength);
        }
        scanAssignedConstants(code, pool, thisClass, assignment);
    }

    private static void scanAssignedConstants(
            final byte[] code, final ConstantPool pool, final String thisClass, final DeclaredAssignment assignment) {
        Object pending = null;
        int pc = 0;
        while (pc < code.length) {
            final int opcode = code[pc] & 0xFF;
            switch (opcode) {
                case 0x01: // aconst_null
                case 0x09:
                case 0x0A: // lconst
                case 0x0B:
                case 0x0C:
                case 0x0D: // fconst
                case 0x0E:
                case 0x0F: // dconst
                    pending = null;
                    pc += 1;
                    break;
                case 0x02:
                case 0x03:
                case 0x04:
                case 0x05: // iconst_m1..iconst_2
                case 0x06:
                case 0x07:
                case 0x08: // iconst_3..iconst_5
                    pending = opcode - 0x03;
                    pc += 1;
                    break;
                case 0x10: // bipush
                    pending = (int) code[pc + 1];
                    pc += 2;
                    break;
                case 0x11: // sipush
                    pending = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    pc += 3;
                    break;
                case 0x12: { // ldc
                    pending = pool.pushable(code[pc + 1] & 0xFF);
                    pc += 2;
                    break;
                }
                case 0x13: { // ldc_w
                    pending = pool.pushable(((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF));
                    pc += 3;
                    break;
                }
                case 0x14: // ldc2_w — long/double are not declaration constants
                    pending = null;
                    pc += 3;
                    break;
                case 0x59: // dup: keep the pending constant on top
                    pc += 1;
                    break;
                case 0xB3: { // putstatic
                    final int fieldRef = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    if (pending != null && pool.fieldOwnerIs(fieldRef, thisClass)) {
                        if (pending instanceof String string) {
                            assignment.strings().add(string);
                        } else if (pending instanceof Integer integer) {
                            assignment.ints().add(integer);
                        }
                    }
                    pending = null;
                    pc += 3;
                    break;
                }
                case 0xAA: { // tableswitch
                    pending = null;
                    int offset = (pc + 4) & ~3;
                    if (offset + 12 > code.length) return;
                    final int low = intAt(code, offset + 4);
                    final int high = intAt(code, offset + 8);
                    final int entries = high - low + 1;
                    if (entries < 0 || entries > code.length) return;
                    offset = (int) (offset + (12 + (long) entries * 4));
                    if (offset > code.length) return;
                    pc = offset;
                    break;
                }
                case 0xAB: { // lookupswitch
                    pending = null;
                    int offset = (pc + 4) & ~3;
                    if (offset + 8 > code.length) return;
                    final int pairs = intAt(code, offset + 4);
                    if (pairs < 0 || pairs > code.length) return;
                    offset = (int) (offset + (8 + (long) pairs * 8));
                    if (offset > code.length) return;
                    pc = offset;
                    break;
                }
                case 0xC4: { // wide
                    pending = null;
                    if (pc + 1 >= code.length) return;
                    pc += (code[pc + 1] & 0xFF) == 0x84 ? 6 : 4;
                    break;
                }
                default: {
                    pending = null;
                    final int operands = OPERAND_LENGTHS[opcode];
                    if (operands < 0) return; // desync guard: bail on unknown opcode
                    pc += 1 + operands;
                    break;
                }
            }
        }
    }

    private static int intAt(final byte[] code, final int offset) {
        return ((code[offset] & 0xFF) << 24)
                | ((code[offset + 1] & 0xFF) << 16)
                | ((code[offset + 2] & 0xFF) << 8)
                | (code[offset + 3] & 0xFF);
    }

    /** Operand byte counts per opcode; {@code -1} marks variable/special forms handled above. */
    private static final int[] OPERAND_LENGTHS = new int[256];

    static {
        final int[] lengths = OPERAND_LENGTHS;
        java.util.Arrays.fill(lengths, -1);
        // No-operand instructions.
        for (int opcode = 0x00; opcode <= 0x0F; opcode++) lengths[opcode] = 0;
        for (int opcode = 0x1A; opcode <= 0x35; opcode++) lengths[opcode] = 0;
        for (int opcode = 0x3B; opcode <= 0x83; opcode++) lengths[opcode] = 0;
        for (int opcode = 0x85; opcode <= 0x98; opcode++) lengths[opcode] = 0;
        for (int opcode = 0xAC; opcode <= 0xB1; opcode++) lengths[opcode] = 0;
        for (int opcode = 0xBE; opcode <= 0xC3; opcode++) lengths[opcode] = 0;
        for (int opcode = 0xC5; opcode <= 0xC9; opcode++) lengths[opcode] = 0;
        for (int opcode = 0xCA; opcode <= 0xFE; opcode++) lengths[opcode] = 0;
        // One-byte operand.
        lengths[0x12] = 1; // ldc — handled explicitly above
        lengths[0x15] = 1;
        lengths[0x16] = 1;
        lengths[0x17] = 1;
        lengths[0x18] = 1;
        lengths[0x19] = 1; // iload..aload
        lengths[0x36] = 1;
        lengths[0x37] = 1;
        lengths[0x38] = 1;
        lengths[0x39] = 1;
        lengths[0x3A] = 1; // istore..astore
        lengths[0xA9] = 1; // ret
        lengths[0xBC] = 1; // newarray
        // Two-byte operands.
        for (int opcode = 0x99; opcode <= 0xA8; opcode++) lengths[opcode] = 2; // branches, jsr
        for (int opcode = 0xB2; opcode <= 0xB8; opcode++) lengths[opcode] = 2; // field/method refs
        lengths[0xBB] = 2; // new
        lengths[0xBD] = 2; // anewarray
        lengths[0xC0] = 2;
        lengths[0xC1] = 2; // checkcast, instanceof
        lengths[0xC6] = 2;
        lengths[0xC7] = 2; // ifnull, ifnonnull
        // Three/four-byte operands.
        lengths[0x13] = 2;
        lengths[0x14] = 2; // ldc_w, ldc2_w — handled explicitly
        lengths[0x84] = 2; // iinc
        lengths[0xC8] = 4;
        lengths[0xC9] = 4; // goto_w, jsr_w
        lengths[0xB9] = 4; // invokeinterface
        lengths[0xBA] = 4; // invokedynamic
        lengths[0xC5] = 3; // multianewarray
    }

    /**
     * Minimal classfile constant-pool reader (JDK only). Long/double entries
     * occupy two pool slots; the second slot is skipped.
     */
    private static final class ConstantPool {

        private final String[] utf8;
        private final int[] stringIndex;
        private final int[] classNameIndex;
        private final int[] nameAndTypeIndex;
        private final int[] nameAndTypeDescriptor;
        private final int[] memberRefClassIndex;
        private final int[] memberRefNameTypeIndex;
        private final Integer[] integers;

        private ConstantPool(final int count) {
            utf8 = new String[count];
            stringIndex = new int[count];
            classNameIndex = new int[count];
            nameAndTypeIndex = new int[count];
            nameAndTypeDescriptor = new int[count];
            memberRefClassIndex = new int[count];
            memberRefNameTypeIndex = new int[count];
            integers = new Integer[count];
        }

        static ConstantPool parse(final DataInputStream data) throws IOException {
            final int count = data.readUnsignedShort();
            final ConstantPool pool = new ConstantPool(count);
            for (int index = 1; index < count; index++) {
                final int tag = data.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool.utf8[index] = data.readUTF();
                    case 3 -> pool.integers[index] = data.readInt();
                    case 4 -> data.skipNBytes(4);
                    case 5, 6 -> {
                        data.skipNBytes(8);
                        index++;
                    }
                    case 7 -> pool.classNameIndex[index] = data.readUnsignedShort();
                    case 8 -> pool.stringIndex[index] = data.readUnsignedShort();
                    case 9, 10, 11 -> {
                        pool.memberRefClassIndex[index] = data.readUnsignedShort();
                        pool.memberRefNameTypeIndex[index] = data.readUnsignedShort();
                    }
                    case 12 -> {
                        pool.nameAndTypeIndex[index] = data.readUnsignedShort();
                        pool.nameAndTypeDescriptor[index] = data.readUnsignedShort();
                    }
                    case 15 -> data.skipNBytes(3);
                    case 16, 19, 20 -> data.skipNBytes(2);
                    case 17, 18 -> data.skipNBytes(4);
                    default -> throw new IOException("unsupported constant-pool tag " + tag);
                }
            }
            return pool;
        }

        String utf8(final int index) {
            return index > 0 && index < utf8.length ? utf8[index] : null;
        }

        String classInternalName(final int classIndex) {
            return classIndex > 0 && classIndex < classNameIndex.length ? utf8(classNameIndex[classIndex]) : null;
        }

        String stringValue(final int index) {
            if (index <= 0 || index >= stringIndex.length) return null;
            // CONSTANT_String resolves through its utf8 index; a Utf8 entry
            // resolves directly for ConstantValue flexibility.
            if (utf8[index] != null) return utf8[index];
            return utf8(stringIndex[index]);
        }

        Integer integerValue(final int index) {
            return index > 0 && index < integers.length ? integers[index] : null;
        }

        /** Value an {@code ldc}/{@code ldc_w} pushes, or {@code null} when it is not a constant. */
        Object pushable(final int index) {
            if (index <= 0 || index >= utf8.length) return null;
            if (stringIndex[index] != 0) return utf8(stringIndex[index]);
            if (integers[index] != null) return integers[index];
            return null;
        }

        /** Whether a field reference targets a field of the declaring class itself. */
        boolean fieldOwnerIs(final int fieldRefIndex, final String internalName) {
            if (internalName == null || fieldRefIndex <= 0 || fieldRefIndex >= memberRefClassIndex.length) {
                return false;
            }
            final int classIndex = memberRefClassIndex[fieldRefIndex];
            if (classIndex <= 0 || classIndex >= classNameIndex.length) return false;
            return internalName.equals(utf8(classNameIndex[classIndex]));
        }
    }
}
