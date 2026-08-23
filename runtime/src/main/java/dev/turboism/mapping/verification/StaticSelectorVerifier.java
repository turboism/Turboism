package dev.turboism.mapping.verification;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verifies class/member signatures from JAR metadata without loading host classes. */
public final class StaticSelectorVerifier {

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    /**
     * Checks each selector against the class metadata in the jar, parsing class
     * files directly rather than loading any host class, so verification runs
     * without executing host code.
     *
     * <p>If the artifact's size and hash do not match the expected fingerprint,
     * no selector is examined at all: every result comes back
     * {@code ARTIFACT_MISMATCH}. Otherwise each selector is resolved by owner,
     * name, descriptor, and access flags, with per-selector failures reported
     * as statuses rather than thrown. Aliases requested more than once are all
     * reported {@code DUPLICATE_ALIAS} instead of being checked.</p>
     *
     * @param artifact host jar to verify
     * @param expectedFingerprint fingerprint the artifact must match
     * @param selectors selectors to check; must not be empty
     * @return a report carrying both fingerprints and one result per selector
     * @throws IOException if the artifact cannot be read or opened as a jar
     * @throws IllegalArgumentException if {@code selectors} is empty
     */
    public StaticVerificationReport verify(
        final Path artifact,
        final HostArtifactFingerprint expectedFingerprint,
        final List<StaticSelector> selectors
    ) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(expectedFingerprint, "expectedFingerprint");
        final List<StaticSelector> requested = List.copyOf(Objects.requireNonNull(selectors, "selectors"));
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("selectors must not be empty");
        }

        final HostArtifactDigest actualDigest = HostArtifactDigest.from(artifact);
        final HostArtifactFingerprint actual = new HostArtifactFingerprint(
            "artifact-version-unattested",
            actualDigest.size(),
            actualDigest.sha256()
        );
        if (actual.size() != expectedFingerprint.size()
            || !actual.sha256().equals(expectedFingerprint.sha256())) {
            return new StaticVerificationReport(
                expectedFingerprint,
                actual,
                false,
                requested.stream()
                    .map(selector -> result(
                        selector,
                        StaticVerificationStatus.ARTIFACT_MISMATCH,
                        "Host artifact fingerprint does not match the verified exact-version artifact."
                    ))
                    .toList()
            );
        }

        final Set<String> duplicateAliases = duplicateAliases(requested);
        final Map<String, ClassMetadata> cache = new HashMap<>();
        final List<StaticSelectorResult> results = new ArrayList<>();
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            for (StaticSelector selector : requested) {
                if (duplicateAliases.contains(selector.alias())) {
                    results.add(result(
                        selector,
                        StaticVerificationStatus.DUPLICATE_ALIAS,
                        "Selector alias is duplicated in the verification request."
                    ));
                    continue;
                }
                results.add(verifySelector(jar, selector, cache));
            }
        }
        return new StaticVerificationReport(expectedFingerprint, actual, true, results);
    }

    private StaticSelectorResult verifySelector(
        final JarFile jar,
        final StaticSelector selector,
        final Map<String, ClassMetadata> cache
    ) {
        final ClassMetadata metadata;
        try {
            metadata = metadata(jar, selector.ownerInternalName(), cache);
        } catch (MissingClassException exception) {
            return result(selector, StaticVerificationStatus.CLASS_MISSING, "Selector owner class is missing.");
        } catch (IOException | RuntimeException exception) {
            return result(selector, StaticVerificationStatus.INVALID_CLASS_FILE, "Selector owner class metadata is invalid.");
        }

        if (selector.kind() == StaticSelector.Kind.CLASS) {
            return hasExpectedAccess(
                metadata.accessFlags(),
                selector.requiredAccessFlags(),
                selector.forbiddenAccessFlags()
            )
                ? result(selector, StaticVerificationStatus.VERIFIED_STATIC, "Class signature is verified statically.")
                : result(selector, StaticVerificationStatus.ACCESS_MISMATCH, "Class access flags do not match.");
        }

        final List<MemberMetadata> sameName = metadata.members(selector.kind(), selector.memberName());
        if (sameName.isEmpty()) {
            return result(selector, StaticVerificationStatus.MEMBER_MISSING, "Selector member is missing.");
        }
        final List<MemberMetadata> sameDescriptor = sameName.stream()
            .filter(member -> member.descriptor().equals(selector.descriptor()))
            .toList();
        if (sameDescriptor.isEmpty()) {
            return result(selector, StaticVerificationStatus.DESCRIPTOR_MISMATCH, "Selector member descriptor does not match.");
        }
        if (sameDescriptor.stream().noneMatch(member -> hasExpectedAccess(
            member.accessFlags(),
            selector.requiredAccessFlags(),
            selector.forbiddenAccessFlags()
        ))) {
            return result(selector, StaticVerificationStatus.ACCESS_MISMATCH, "Selector member access flags do not match.");
        }
        return result(selector, StaticVerificationStatus.VERIFIED_STATIC, "Member signature is verified statically.");
    }

    private ClassMetadata metadata(
        final JarFile jar,
        final String ownerInternalName,
        final Map<String, ClassMetadata> cache
    ) throws IOException, MissingClassException {
        final ClassMetadata existing = cache.get(ownerInternalName);
        if (existing != null) {
            return existing;
        }
        final JarEntry entry = jar.getJarEntry(ownerInternalName + ".class");
        if (entry == null) {
            throw new MissingClassException();
        }
        try (InputStream input = jar.getInputStream(entry)) {
            final ClassMetadata parsed = parseClass(input);
            if (!ownerInternalName.equals(parsed.internalName())) {
                throw new IOException("Class entry name does not match class metadata owner");
            }
            cache.put(ownerInternalName, parsed);
            return parsed;
        }
    }

    private ClassMetadata parseClass(final InputStream input) throws IOException {
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
            if (data.readInt() != CLASS_MAGIC) {
                throw new IOException("Invalid class magic");
            }
            data.readUnsignedShort();
            data.readUnsignedShort();
            final ConstantPool constantPool = readConstantPool(data);
            final int classAccess = data.readUnsignedShort();
            final int thisClassIndex = data.readUnsignedShort();
            data.readUnsignedShort();
            skipInterfaces(data);
            final List<MemberMetadata> fields = readMembers(data, constantPool.utf8());
            final List<MemberMetadata> methods = readMembers(data, constantPool.utf8());
            skipAttributes(data);
            return new ClassMetadata(
                classAccess,
                constantPool.classInternalName(thisClassIndex),
                fields,
                methods
            );
        } catch (EOFException exception) {
            throw new IOException("Truncated class metadata", exception);
        }
    }

    private ConstantPool readConstantPool(final DataInputStream data) throws IOException {
        final int count = data.readUnsignedShort();
        final String[] utf8 = new String[count];
        final int[] classNameIndexes = new int[count];
        for (int index = 1; index < count; index++) {
            final int tag = data.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[index] = data.readUTF();
                case 3, 4 -> data.skipNBytes(4);
                case 5, 6 -> {
                    data.skipNBytes(8);
                    index++;
                }
                case 7 -> classNameIndexes[index] = data.readUnsignedShort();
                case 8, 16, 19, 20 -> data.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> data.skipNBytes(4);
                case 15 -> data.skipNBytes(3);
                default -> throw new IOException("Unsupported constant-pool tag " + tag);
            }
        }
        return new ConstantPool(utf8, classNameIndexes);
    }

    private void skipInterfaces(final DataInputStream data) throws IOException {
        final int count = data.readUnsignedShort();
        data.skipNBytes((long) count * 2);
    }

    private List<MemberMetadata> readMembers(final DataInputStream data, final String[] utf8) throws IOException {
        final int count = data.readUnsignedShort();
        final List<MemberMetadata> members = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final int access = data.readUnsignedShort();
            final String name = requireUtf8(utf8, data.readUnsignedShort());
            final String descriptor = requireUtf8(utf8, data.readUnsignedShort());
            members.add(new MemberMetadata(name, descriptor, access));
            skipAttributes(data);
        }
        return List.copyOf(members);
    }

    private void skipAttributes(final DataInputStream data) throws IOException {
        final int count = data.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            data.readUnsignedShort();
            final long length = Integer.toUnsignedLong(data.readInt());
            data.skipNBytes(length);
        }
    }

    private String requireUtf8(final String[] utf8, final int index) throws IOException {
        if (index <= 0 || index >= utf8.length || utf8[index] == null) {
            throw new IOException("Invalid UTF-8 constant-pool index");
        }
        return utf8[index];
    }

    private Set<String> duplicateAliases(final List<StaticSelector> selectors) {
        final Set<String> seen = new HashSet<>();
        final Set<String> duplicates = new HashSet<>();
        for (StaticSelector selector : selectors) {
            if (!seen.add(selector.alias())) {
                duplicates.add(selector.alias());
            }
        }
        return Set.copyOf(duplicates);
    }

    private static boolean hasExpectedAccess(final int actual, final int required, final int forbidden) {
        return (actual & required) == required && (actual & forbidden) == 0;
    }

    private static StaticSelectorResult result(
        final StaticSelector selector,
        final StaticVerificationStatus status,
        final String message
    ) {
        return new StaticSelectorResult(selector, status, message);
    }

    private record ConstantPool(String[] utf8, int[] classNameIndexes) {
        private String classInternalName(final int classIndex) throws IOException {
            if (classIndex <= 0 || classIndex >= classNameIndexes.length) {
                throw new IOException("Invalid class constant-pool index");
            }
            final int nameIndex = classNameIndexes[classIndex];
            if (nameIndex <= 0 || nameIndex >= utf8.length || utf8[nameIndex] == null) {
                throw new IOException("Invalid class name constant-pool index");
            }
            return utf8[nameIndex];
        }
    }

    private record ClassMetadata(
        int accessFlags,
        String internalName,
        List<MemberMetadata> fields,
        List<MemberMetadata> methods
    ) {
        private List<MemberMetadata> members(final StaticSelector.Kind kind, final String name) {
            final List<MemberMetadata> source = kind == StaticSelector.Kind.FIELD ? fields : methods;
            return source.stream().filter(member -> member.name().equals(name)).toList();
        }
    }

    private record MemberMetadata(String name, String descriptor, int accessFlags) {
    }

    private static final class MissingClassException extends Exception {
    }
}
