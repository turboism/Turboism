package dev.turboism.core.event;

import dev.turboism.core.archive.ArchivePathPolicy;
import dev.turboism.core.archive.ArchivePaths;
import dev.turboism.core.archive.ArchiveStructureException;
import dev.turboism.core.archive.StrictZipArchive;
import dev.turboism.sdk.event.EventBus;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.jar.Manifest;

/**
 * Shared no-execution preflight for declared public event contract artifacts.
 *
 * <p>Both managed admission ({@code dev.turboism.distribution}) and load-time binding
 * ({@link PublicEventContractCatalog}) run the exact same rule set here, against the
 * same per-plugin {@link Session} budget: declared size bound, sha256 pinning, strict
 * central-directory archive validation (EOCD closure, CEN↔LOC consistency, unique
 * normalized paths, counted expansion, recomputed CRC), the class-only archive shape,
 * member namespace ownership, loose-class collision, entry-name↔internal-name
 * agreement, and the payload type-reference closure evaluated with ASM — contract
 * bytes are never defined, loaded, initialized, or executed in this JVM.</p>
 *
 * <p>The byte-level walk mirrors {@link PublicEventContractClosure}'s reflective
 * semantics: every erased descriptor/exception type of every member of a visited type
 * must be resolvable (the E bucket, including private/package-private/synthetic
 * members), and a member reference is only good when that member's ancestor subgraph
 * is definable — resolvable, acyclic, kind-compatible. Only the API surface
 * (public/protected, non-synthetic — constructors excepted) recurses into the payload
 * closure (the A bucket). Trusted platform/SDK resolvability is decided by
 * {@link ContractTypeOracle}, never by package-name guesses. Load-time binding keeps
 * {@link PublicEventContractClosure} as the reflective defensive re-verification once
 * classes are actually bound.</p>
 */
public final class PublicEventContractPreflight {

    /** Maximum byte size of a single contract artifact (frozen policy, kept). */
    public static final long MAX_ARTIFACT_BYTES = 8L * 1024 * 1024;

    /** Per-artifact expanded bytes of a single entry (frozen Amendment A-1.2). */
    static final long MAX_MEMBER_BYTES = 8L * 1024 * 1024;
    /** Per-artifact total expanded bytes (frozen Amendment A-1.2). */
    static final long MAX_EXPANDED_BYTES = 32L * 1024 * 1024;
    /** Per-artifact entry count including directories and the manifest (frozen). */
    static final int MAX_ENTRIES = 1024;
    /** Per-artifact compression ratio bound (frozen, same figure as outer policy). */
    static final double MAX_RATIO = 100.0;
    /** Per-artifact entry path UTF-8 byte bound (frozen). */
    static final int MAX_PATH_BYTES = 1024;
    /** Per-artifact entry path depth bound (frozen). */
    static final int MAX_PATH_DEPTH = 32;
    /** Signature nesting bound shared by every signature parse round (frozen A-1.4). */
    static final int MAX_SIGNATURE_NESTING = 512;

    private static final StrictZipArchive.Limits CONTRACT_LIMITS =
        new StrictZipArchive.Limits(
            MAX_ARTIFACT_BYTES, MAX_MEMBER_BYTES, MAX_EXPANDED_BYTES,
            MAX_ENTRIES, MAX_RATIO);

    /**
     * Contract-artifact path policy: relative normalized paths under the same
     * byte/depth/identity rules as outer plugin archives.
     */
    private static final ArchivePathPolicy CONTRACT_PATHS = new ArchivePathPolicy() {
        @Override
        public void validateEntry(final String name, final boolean directory)
                throws ArchiveStructureException {
            final String value = directory && name.endsWith("/")
                ? name.substring(0, name.length() - 1) : name;
            if (value.isEmpty()
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES
                || value.split("/", -1).length > MAX_PATH_DEPTH
                || !ArchivePaths.relativePath(value)) {
                throw new ArchiveStructureException(
                    "ARCHIVE_PATH_UNSAFE", "Unsafe contract archive path", name);
            }
        }

        @Override
        public void validateCollisions(final List<String> names)
                throws ArchiveStructureException {
            final String collision = ArchivePaths.pathCollision(names);
            if (collision != null) {
                throw new ArchiveStructureException(
                    "ARCHIVE_PATH_COLLISION", "Contract archive path collision",
                    collision);
            }
        }
    };

    /** Machine-readable rejection kinds; admission surfaces map them to stable codes. */
    public enum Rejection {
        TOO_LARGE,
        HASH_MISMATCH,
        CONTENT,
        CLOSURE,
        COLLISION
    }

    /** A single contract preflight refusal. */
    public static final class ContractViolation extends Exception {
        private final Rejection kind;

        private ContractViolation(final Rejection kind, final String message) {
            super(message);
            this.kind = kind;
        }

        public Rejection kind() { return kind; }
    }

    /** The verified artifact: its computed sha256 and owned binary class names. */
    public record Inspection(String sha256, Set<String> classNames) {}

    /**
     * Per-plugin verification session carrying the frozen cumulative budgets
     * (Amendment A-1.2/A-1.4): declared contracts, artifact bytes, expanded bytes,
     * inner entries, unique type names, processed references, and parsed
     * descriptor/signature text. Charges happen before the work they pay for; quota
     * exhaustion rejects immediately with {@link Rejection#TOO_LARGE} and names the
     * exhausted dimension. A {@code Session} spans one plugin's contract set —
     * install admission and catalog re-verification each create their own.
     */
    public static final class Session {
        static final int MAX_CONTRACTS = 32;
        static final long MAX_ARTIFACT_BYTES_TOTAL = 64L * 1024 * 1024;
        static final long MAX_EXPANDED_BYTES_TOTAL = 64L * 1024 * 1024;
        static final int MAX_ENTRIES_TOTAL = 4096;
        static final int MAX_UNIQUE_TYPES = 16_384;
        static final long MAX_REFERENCES = 65_536;
        static final long MAX_SIGNATURE_TEXT = 16_777_216;

        private final ContractTypeOracle oracle;
        private final Map<String, Inspection> verified = new HashMap<>();
        private final Set<String> uniqueTypes = new HashSet<>();
        private int contracts;
        private long artifactBytes;
        private long expandedBytes;
        private int entries;
        private long references;
        private long signatureText;

        private Session(final ContractTypeOracle oracle) {
            this.oracle = oracle;
        }

        private void chargeContract(final String contractId) throws ContractViolation {
            contracts++;
            quota(contracts <= MAX_CONTRACTS, "declared contracts", contractId);
        }

        private void chargeArtifactBytes(final long bytes, final String contractId)
                throws ContractViolation {
            artifactBytes += bytes;
            quota(artifactBytes <= MAX_ARTIFACT_BYTES_TOTAL
                && bytes >= 0, "session artifact bytes", contractId);
        }

        private void chargeEntries(final int count, final String contractId)
                throws ContractViolation {
            entries += count;
            quota(entries <= MAX_ENTRIES_TOTAL && count >= 0,
                "session inner entries", contractId);
        }

        private void chargeExpanded(final long bytes, final String contractId)
                throws ContractViolation {
            expandedBytes += bytes;
            quota(expandedBytes <= MAX_EXPANDED_BYTES_TOTAL && bytes >= 0,
                "session expanded bytes", contractId);
        }

        private void chargeUniqueType(final String name, final String contractId)
                throws ContractViolation {
            quota(!uniqueTypes.add(name) || uniqueTypes.size() <= MAX_UNIQUE_TYPES,
                "session unique types", contractId);
        }

        private void chargeReference(final String contractId)
                throws ContractViolation {
            references++;
            quota(references <= MAX_REFERENCES, "session type references", contractId);
        }

        private void chargeText(final int units, final String contractId)
                throws ContractViolation {
            signatureText += units;
            quota(signatureText <= MAX_SIGNATURE_TEXT && units >= 0,
                "session parsed descriptor/signature text", contractId);
        }

        private void quota(final boolean ok, final String dimension,
                final String contractId) throws ContractViolation {
            if (!ok) {
                throw new ContractViolation(
                    Rejection.TOO_LARGE,
                    "public event contract " + contractId
                        + " exhausts the verification session budget on " + dimension
                );
            }
        }
    }

    /**
     * @return a fresh per-plugin verification session over the real contract-binding
     *     SDK anchor
     */
    public static Session newSession() {
        return new Session(ContractTypeOracle.forSdkAnchor(EventBus.class));
    }

    /** Test seam: a session over an explicit type-resolution oracle. */
    static Session newSession(final ContractTypeOracle oracle) {
        return new Session(oracle);
    }

    /**
     * The event types a descriptor pins for route binding — the single-source
     * payload-closure seeds shared by admission and bind-time verification.
     */
    public static Set<String> payloadSeeds(
        final dev.turboism.sdk.plugin.PluginDescriptor descriptor
    ) {
        return ContractClosurePolicy.payloadSeeds(descriptor);
    }

    private PublicEventContractPreflight() {}

    /**
     * Verifies one declared contract artifact without loading any of its classes.
     *
     * @param session the plugin's shared verification budget; charged before reads
     * @param pluginId plugin being admitted, for diagnostics
     * @param contractId declared contract id
     * @param artifactPath declared artifact entry path inside the plugin JAR
     * @param declaredSha256 lowercase hex sha256 pinned by the descriptor
     * @param bytes embedded artifact bytes
     * @param loosePluginClasses binary class names the plugin JAR defines outside
     *        {@code META-INF/}; a contract member must never be shadowed by one
     * @param payloadSeeds binary names of the descriptor's declared event types;
     *        seeds that are artifact members anchor the payload closure walk
     * @return the verified inspection result
     * @throws ContractViolation on the first violated rule
     */
    public static Inspection verify(
        final Session session,
        final String pluginId,
        final String contractId,
        final String artifactPath,
        final String declaredSha256,
        final byte[] bytes,
        final Collection<String> loosePluginClasses,
        final Collection<String> payloadSeeds
    ) throws ContractViolation {
        session.chargeContract(contractId);
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            throw new ContractViolation(
                Rejection.TOO_LARGE,
                "public event contract " + contractId + " artifact " + artifactPath
                    + " exceeds the " + MAX_ARTIFACT_BYTES
                    + " byte artifact limit"
            );
        }
        session.chargeArtifactBytes(bytes.length, contractId);
        final String sha256 = sha256Hex(bytes);
        if (!declaredSha256.equals(sha256)) {
            throw new ContractViolation(
                Rejection.HASH_MISMATCH,
                "public event contract " + contractId + " artifact sha256 mismatch:"
                    + " descriptor declares " + declaredSha256
                    + " but the embedded artifact hashes to " + sha256
            );
        }
        final Inspection cached = session.verified.get(sha256);
        if (cached != null) {
            return cached;
        }
        final Map<String, MemberInfo> members =
            readContractClasses(session, contractId, bytes);
        for (final String className : members.keySet()) {
            if (loosePluginClasses.contains(className)) {
                throw new ContractViolation(
                    Rejection.COLLISION,
                    "plugin " + pluginId + " JAR also defines contract class "
                        + className + " outside the published contract artifact "
                        + contractId
                );
            }
        }
        verifyPayloadClosure(session, contractId, members, payloadSeeds);
        final Inspection result = new Inspection(sha256, Set.copyOf(members.keySet()));
        session.verified.put(sha256, result);
        return result;
    }

    /**
     * Member facts collected in the single structural pass: the internal binary name
     * (authoritative — cross-checked against the entry path), access flags, erased
     * supertype names and the permitted-subclass list, plus the class bytes retained
     * for the lazily-scoped E/A reference pass.
     */
    private record MemberInfo(
        String binaryName,
        int access,
        String superName,
        List<String> interfaces,
        Set<String> permits,
        byte[] bytes
    ) {
        boolean isInterface() {
            return (access & Opcodes.ACC_INTERFACE) != 0;
        }
    }

    /** Header-only pass: captures definability metadata; parses no signatures. */
    private static final class MemberHeader extends ClassVisitor {
        String internalName;
        int access;
        String superName;
        List<String> interfaces = List.of();
        final Set<String> permits = new LinkedHashSet<>();

        MemberHeader() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
            final int version,
            final int access,
            final String name,
            final String signature,
            final String superName,
            final String[] interfaces
        ) {
            this.access = access;
            this.internalName = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        }

        @Override
        public void visitPermittedSubclass(final String permittedSubclass) {
            permits.add(permittedSubclass.replace('/', '.'));
        }
    }

    /**
     * Enforces the class-only archive shape over the strict central-directory view and
     * collects member class bytes plus definability metadata.
     */
    private static Map<String, MemberInfo> readContractClasses(
        final Session session,
        final String contractId,
        final byte[] bytes
    ) throws ContractViolation {
        final Map<String, MemberInfo> members = new LinkedHashMap<>();
        try (StrictZipArchive archive =
                StrictZipArchive.open(bytes, CONTRACT_LIMITS, CONTRACT_PATHS)) {
            session.chargeEntries(archive.entries().size(), contractId);
            long declaredExpanded = 0;
            for (final StrictZipArchive.Entry entry : archive.entries()) {
                declaredExpanded += entry.expanded();
            }
            session.chargeExpanded(declaredExpanded, contractId);
            for (final StrictZipArchive.Entry entry : archive.entries()) {
                if (entry.directory()) {
                    continue;
                }
                final String name = entry.name();
                final byte[] entryBytes = consumeEntry(archive, entry, contractId);
                if (name.equals("META-INF/MANIFEST.MF")) {
                    final Manifest manifest =
                        new Manifest(new ByteArrayInputStream(entryBytes));
                    if (manifest.getMainAttributes().getValue("Class-Path") != null) {
                        throw new ContractViolation(
                            Rejection.CONTENT,
                            "public event contract " + contractId
                                + " artifact manifest must not declare Class-Path"
                        );
                    }
                    continue;
                }
                if (name.equals("module-info.class")
                    || name.endsWith("/module-info.class")
                    || name.startsWith("META-INF/services/")
                    || name.startsWith("META-INF/versions/")) {
                    throw new ContractViolation(
                        Rejection.CONTENT,
                        "public event contract " + contractId
                            + " artifact must not contain " + name
                    );
                }
                if (!name.endsWith(".class")) {
                    throw new ContractViolation(
                        Rejection.CONTENT,
                        "public event contract " + contractId
                            + " artifact is a class-only JAR and must not contain "
                            + name
                    );
                }
                final MemberInfo member = parseMember(contractId, entryBytes);
                final String declaredName = binaryName(name);
                if (!member.binaryName().equals(declaredName)) {
                    throw new ContractViolation(
                        Rejection.CONTENT,
                        "public event contract " + contractId + " class "
                            + member.binaryName() + " is stored at mismatched entry "
                            + name
                    );
                }
                for (final String forbidden : ContractClosurePolicy.FORBIDDEN_CLASS_PREFIXES) {
                    if (member.binaryName().startsWith(forbidden)) {
                        throw new ContractViolation(
                            Rejection.CONTENT,
                            "public event contract " + contractId
                                + " contains forbidden class " + member.binaryName()
                        );
                    }
                }
                if (members.put(member.binaryName(), member) != null) {
                    throw new ContractViolation(
                        Rejection.CONTENT,
                        "public event contract " + contractId
                            + " defines class " + member.binaryName() + " twice"
                    );
                }
            }
        } catch (final ContractViolation violation) {
            throw violation;
        } catch (final ArchiveStructureException structure) {
            throw structureViolation(contractId, structure);
        } catch (final Exception failure) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId
                    + " artifact is not a readable JAR: " + failure.getMessage()
            );
        }
        if (members.isEmpty()) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId + " artifact contains no classes"
            );
        }
        return members;
    }

    /**
     * Streams one entry through the shared counted/CRC-verified consume and returns
     * its bytes — the per-entry expanded bound aborts over-delivery while reading.
     */
    private static byte[] consumeEntry(
        final StrictZipArchive archive,
        final StrictZipArchive.Entry entry,
        final String contractId
    ) throws ContractViolation {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(entry.expanded(), 64 * 1024));
            archive.consume(entry, out);
            return out.toByteArray();
        } catch (final ArchiveStructureException structure) {
            throw structureViolation(contractId, structure);
        } catch (final Exception failure) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId + " entry " + entry.name()
                    + " cannot be read"
            );
        }
    }

    /**
     * Maps a structural rejection to the stable surface: quota dimensions keep the
     * {@link Rejection#TOO_LARGE} surface with the dimension named; everything else
     * is invalid content.
     */
    private static ContractViolation structureViolation(
        final String contractId,
        final ArchiveStructureException structure
    ) {
        final String code = structure.code();
        final boolean quota = code.endsWith("TOO_LARGE")
            || code.equals("ARCHIVE_ENTRY_LIMIT")
            || code.equals("ARCHIVE_COMPRESSION_RATIO")
            || code.equals("ARCHIVE_TRUNCATED");
        return new ContractViolation(
            quota ? Rejection.TOO_LARGE : Rejection.CONTENT,
            "public event contract " + contractId + " artifact rejected: "
                + code + " at " + structure.problemPath()
        );
    }

    /** Parses one member class file's header facts; malformed bytes reject. */
    private static MemberInfo parseMember(
        final String contractId,
        final byte[] bytes
    ) throws ContractViolation {
        final MemberHeader header = new MemberHeader();
        try {
            final ClassReader reader = new ClassReader(bytes);
            reader.accept(
                header,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
            );
            return new MemberInfo(
                header.internalName.replace('/', '.'),
                header.access,
                header.superName,
                header.interfaces,
                Set.copyOf(header.permits),
                bytes
            );
        } catch (final RuntimeException failure) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId
                    + " member is not a readable class file"
            );
        }
    }

    /**
     * Byte-level mirror of {@link PublicEventContractClosure}: starting from the
     * declared event types owned by this artifact, every type referenced on the API
     * surface must resolve to another artifact member, the
     * {@code dev.turboism.sdk.*} surface, or a JDK platform class — resolvable through
     * the trusted oracle, never assumed from a package prefix.
     */
    private static void verifyPayloadClosure(
        final Session session,
        final String contractId,
        final Map<String, MemberInfo> members,
        final Collection<String> payloadSeeds
    ) throws ContractViolation {
        final Set<String> visited = new HashSet<>();
        final Deque<String> pending = new ArrayDeque<>();
        final Map<String, Integer> definability = new HashMap<>();
        for (final String seed : payloadSeeds) {
            if (members.containsKey(seed)) {
                pending.add(seed);
            }
        }
        while (!pending.isEmpty()) {
            final String name = pending.poll();
            if (!visited.add(name)) {
                continue;
            }
            session.chargeUniqueType(name, contractId);
            final MemberInfo member = members.get(name);
            requireDefinable(
                name, members, session.oracle, definability, contractId);
            final MemberSurface surface =
                collectSurface(session, contractId, member);
            for (final String reference : surface.references()) {
                session.chargeReference(contractId);
                session.chargeUniqueType(reference, contractId);
                if (members.containsKey(reference)) {
                    requireDefinable(
                        reference, members, session.oracle, definability, contractId);
                    if (surface.api().contains(reference)) {
                        pending.add(reference);
                    }
                    continue;
                }
                if (session.oracle.lookup(reference) == null) {
                    throw new ContractViolation(
                        Rejection.CLOSURE,
                        "public event contract " + contractId + " type " + name
                            + " references " + reference
                            + " which is not part of the contract payload closure"
                            + " (the contract artifact, dev.turboism.sdk.*, or JDK"
                            + " platform classes)"
                    );
                }
            }
        }
    }

    /**
     * Collected references of one visited member: the E bucket holds every erased
     * descriptor/exception type of every member (definability requirements only); the
     * A bucket holds the API surface (recursive closure targets).
     */
    private record MemberSurface(Set<String> references, Set<String> api) {}

    private static MemberSurface collectSurface(
        final Session session,
        final String contractId,
        final MemberInfo member
    ) throws ContractViolation {
        final SurfaceVisitor visitor = new SurfaceVisitor(session, contractId);
        try {
            new ClassReader(member.bytes()).accept(
                visitor,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
            );
        } catch (final SignatureBudgetExceeded exceeded) {
            throw exceeded.violation();
        } catch (final RuntimeException failure) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId + " class "
                    + member.binaryName() + " is not a readable class file"
            );
        } catch (final StackOverflowError overflow) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId + " class "
                    + member.binaryName() + " signature nesting is unbounded"
            );
        }
        // Every name a signature mentions is a resolvability requirement too — the
        // reference set is the union of erased descriptors and API-signature names.
        final Set<String> references = new LinkedHashSet<>(visitor.erased);
        references.addAll(visitor.api);
        return new MemberSurface(references, visitor.api);
    }

    /**
     * E/A dual-bucket member collector. Erased descriptor types and Exceptions of
     * every member (any visibility, synthetic included) are definability
     * requirements — they mirror the eager resolution the JVM performs when the
     * reflective closure enumerates declared members. Only API members contribute
     * generic signatures and recursive references; constructors keep the reflective
     * exemption from the synthetic filter. Record components are gated on
     * {@code ACC_RECORD} to mirror {@code Class.isRecord()}.
     */
    private static final class SurfaceVisitor extends ClassVisitor {
        final Set<String> erased = new LinkedHashSet<>();
        final Set<String> api = new LinkedHashSet<>();
        private final Session session;
        private final String contractId;
        private int classAccess;
        private String classSuperName;

        SurfaceVisitor(final Session session, final String contractId) {
            super(Opcodes.ASM9);
            this.session = session;
            this.contractId = contractId;
        }

        @Override
        public void visit(
            final int version,
            final int access,
            final String name,
            final String signature,
            final String superName,
            final String[] interfaces
        ) {
            classAccess = access;
            classSuperName = superName;
            addInternal(superName, true);
            if (interfaces != null) {
                for (final String iface : interfaces) {
                    addInternal(iface, true);
                }
            }
            if (signature != null) {
                parseSignature(session, contractId, signature, api);
            }
        }

        @Override
        public void visitPermittedSubclass(final String permittedSubclass) {
            final String name = permittedSubclass.replace('/', '.');
            erased.add(name);
            api.add(name);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(
            final String name,
            final String descriptor,
            final String signature
        ) {
            // Mirrors Class.isRecord(): the flag alone is not the gate — the VM also
            // requires java.lang.Record as the direct superclass.
            if ((classAccess & Opcodes.ACC_RECORD) == 0
                || !"java/lang/Record".equals(classSuperName)) {
                return null;
            }
            addType(descriptorType(descriptor), true);
            if (signature != null) {
                parseSignature(session, contractId, signature, api);
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(
            final int access,
            final String name,
            final String descriptor,
            final String signature,
            final Object value
        ) {
            final boolean apiMember =
                ContractClosurePolicy.isApiMember(access, false);
            addType(descriptorType(descriptor), apiMember);
            if (apiMember && signature != null) {
                parseSignature(session, contractId, signature, api);
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
            final int access,
            final String name,
            final String descriptor,
            final String signature,
            final String[] exceptions
        ) {
            final boolean constructor = name.equals("<init>");
            final boolean apiMember =
                !name.equals("<clinit>")
                    && ContractClosurePolicy.isApiMember(access, constructor);
            chargeText(descriptor);
            addType(Type.getReturnType(descriptor), apiMember);
            for (final Type argument : Type.getArgumentTypes(descriptor)) {
                addType(argument, apiMember);
            }
            if (exceptions != null) {
                for (final String exception : exceptions) {
                    addInternal(exception, apiMember);
                }
            }
            if (apiMember && signature != null) {
                parseSignature(session, contractId, signature, api);
            }
            return null;
        }

        private void addType(final Type type, final boolean apiMember) {
            Type unwrapped = type;
            while (unwrapped.getSort() == Type.ARRAY) {
                unwrapped = unwrapped.getElementType();
            }
            if (unwrapped.getSort() == Type.OBJECT) {
                final String name = unwrapped.getClassName();
                erased.add(name);
                if (apiMember) {
                    api.add(name);
                }
            }
        }

        private void addInternal(final String internalName, final boolean apiMember) {
            if (internalName == null) {
                return;
            }
            final String name = internalName.replace('/', '.');
            erased.add(name);
            if (apiMember) {
                api.add(name);
            }
        }

        private Type descriptorType(final String descriptor) {
            chargeText(descriptor);
            return Type.getType(descriptor);
        }

        private void chargeText(final String text) {
            try {
                session.chargeText(text.length(), contractId);
            } catch (final ContractViolation violation) {
                throw new SignatureBudgetExceeded(violation);
            }
        }
    }

    /**
     * Runs a bounded, strictly-consumed signature parse: the text is charged before
     * each round, the collecting visitor and the {@link SignatureWriter} round-trip
     * are both wrapped in the same depth guard, a round-trip mismatch proves the
     * parser did not consume the input the writer reproduces, and a stray
     * {@link StackOverflowError} becomes an attributable invalid-content rejection.
     */
    private static void parseSignature(
        final Session session,
        final String contractId,
        final String signature,
        final Set<String> api
    ) {
        try {
            session.chargeText(signature.length(), contractId);
            new SignatureReader(signature).accept(
                new DepthLimitedSignatureVisitor(
                    new SignatureCollector(api), 0, contractId));
            session.chargeText(signature.length(), contractId);
            final SignatureWriter roundTrip = new SignatureWriter();
            new SignatureReader(signature).accept(
                new DepthLimitedSignatureVisitor(roundTrip, 0, contractId));
            if (!signature.equals(roundTrip.toString())) {
                throw new ContractViolation(
                    Rejection.CONTENT,
                    "public event contract " + contractId
                        + " carries a signature that is not strictly consumed: "
                        + abbreviate(signature)
                );
            }
        } catch (final SignatureBudgetExceeded exceeded) {
            throw propagate(exceeded);
        } catch (final ContractViolation violation) {
            throw propagate(violation);
        } catch (final RuntimeException | StackOverflowError failure) {
            throw propagate(new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId
                    + " carries a malformed signature: " + abbreviate(signature)
            ));
        }
    }

    private static String abbreviate(final String signature) {
        return signature.length() <= 96
            ? signature
            : signature.substring(0, 96) + "...";
    }

    /**
     * The visitor pipeline cannot throw checked violations, so they are boxed into
     * this unchecked carrier and unboxed at the collection boundary.
     */
    private static final class SignatureBudgetExceeded extends RuntimeException {
        SignatureBudgetExceeded(final ContractViolation violation) {
            super(violation);
        }

        ContractViolation violation() {
            return (ContractViolation) getCause();
        }
    }

    private static RuntimeException propagate(final ContractViolation violation) {
        return new SignatureBudgetExceeded(violation);
    }

    private static RuntimeException propagate(final SignatureBudgetExceeded exceeded) {
        return exceeded;
    }

    /**
     * Depth-counting signature-visitor decorator (Amendment A-1.4): every recursive
     * descent — type arguments, arrays, type-parameter bounds, superclass/interface/
     * parameter/return/exception positions, and inner-class segments — charges depth
     * +1 before ASM recurses further, so a pathological signature rejects at
     * {@link #MAX_SIGNATURE_NESTING} instead of overflowing the parser stack.
     */
    private static final class DepthLimitedSignatureVisitor extends SignatureVisitor {
        private final SignatureVisitor delegate;
        private final String contractId;
        private int depth;

        DepthLimitedSignatureVisitor(
            final SignatureVisitor delegate,
            final int depth,
            final String contractId
        ) {
            super(Opcodes.ASM9);
            this.delegate = delegate;
            this.depth = depth;
            this.contractId = contractId;
        }

        private SignatureVisitor wrap(final SignatureVisitor nested) {
            if (nested == null) {
                return null;
            }
            if (depth + 1 > MAX_SIGNATURE_NESTING) {
                throw propagate(new ContractViolation(
                    Rejection.TOO_LARGE,
                    "public event contract " + contractId
                        + " signature nesting exceeds the "
                        + MAX_SIGNATURE_NESTING + " level bound"
                ));
            }
            return new DepthLimitedSignatureVisitor(nested, depth + 1, contractId);
        }

        private void descend() {
            depth++;
            if (depth > MAX_SIGNATURE_NESTING) {
                throw propagate(new ContractViolation(
                    Rejection.TOO_LARGE,
                    "public event contract " + contractId
                        + " signature nesting exceeds the "
                        + MAX_SIGNATURE_NESTING + " level bound"
                ));
            }
        }

        @Override public SignatureVisitor visitClassBound() {
            return wrap(delegate.visitClassBound());
        }

        @Override public SignatureVisitor visitInterfaceBound() {
            return wrap(delegate.visitInterfaceBound());
        }

        @Override public SignatureVisitor visitSuperclass() {
            return wrap(delegate.visitSuperclass());
        }

        @Override public SignatureVisitor visitInterface() {
            return wrap(delegate.visitInterface());
        }

        @Override public SignatureVisitor visitParameterType() {
            return wrap(delegate.visitParameterType());
        }

        @Override public SignatureVisitor visitReturnType() {
            return wrap(delegate.visitReturnType());
        }

        @Override public SignatureVisitor visitExceptionType() {
            return wrap(delegate.visitExceptionType());
        }

        @Override public SignatureVisitor visitArrayType() {
            return wrap(delegate.visitArrayType());
        }

        @Override public SignatureVisitor visitTypeArgument(final char wildcard) {
            return wrap(delegate.visitTypeArgument(wildcard));
        }

        @Override public void visitInnerClassType(final String name) {
            descend();
            delegate.visitInnerClassType(name);
        }

        @Override public void visitFormalTypeParameter(final String name) {
            delegate.visitFormalTypeParameter(name);
        }

        @Override public void visitBaseType(final char descriptor) {
            delegate.visitBaseType(descriptor);
        }

        @Override public void visitTypeVariable(final String name) {
            delegate.visitTypeVariable(name);
        }

        @Override public void visitClassType(final String name) {
            delegate.visitClassType(name);
        }

        @Override public void visitTypeArgument() {
            delegate.visitTypeArgument();
        }

        @Override public void visitEnd() {
            delegate.visitEnd();
        }
    }

    /**
     * Walks a generic signature and records every class binary name it mentions.
     * Inner-class segments extend the enclosing binary name accumulated by
     * {@link #visitClassType}.
     */
    private static final class SignatureCollector extends SignatureVisitor {
        private final Set<String> referenced;
        private StringBuilder current;

        SignatureCollector(final Set<String> referenced) {
            super(Opcodes.ASM9);
            this.referenced = referenced;
        }

        @Override
        public void visitClassType(final String name) {
            current = new StringBuilder(name.replace('/', '.'));
            referenced.add(current.toString());
        }

        @Override
        public void visitInnerClassType(final String name) {
            if (current != null) {
                current.append('$').append(name);
                referenced.add(current.toString());
            }
        }

        @Override
        public void visitEnd() {
            current = null;
        }

        @Override
        public SignatureVisitor visitArrayType() {
            return new SignatureCollector(referenced);
        }

        @Override
        public SignatureVisitor visitTypeArgument(final char wildcard) {
            return new SignatureCollector(referenced);
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return new SignatureCollector(referenced);
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return new SignatureCollector(referenced);
        }

        @Override
        public SignatureVisitor visitExceptionType() {
            return new SignatureCollector(referenced);
        }

        @Override
        public SignatureVisitor visitReturnType() {
            return new SignatureCollector(referenced);
        }

        @Override
        public SignatureVisitor visitParameterType() {
            return new SignatureCollector(referenced);
        }

        @Override
        public SignatureVisitor visitInterface() {
            return new SignatureCollector(referenced);
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            return new SignatureCollector(referenced);
        }
    }

    /**
     * Proves a member type is <em>definable</em>: its erased super/interface subgraph
     * resolves (member or trusted oracle), is acyclic, and respects kind/finality/
     * sealed rules — the install-time mirror of the {@code NoClassDefFoundError} /
     * {@code ClassCircularityError} / {@code IncompatibleClassChangeError} family the
     * VM would raise when the class is defined. Trusted ancestors are leaves: the
     * JDK/SDK graph is the environment's trusted precondition.
     */
    private static void requireDefinable(
        final String root,
        final Map<String, MemberInfo> members,
        final ContractTypeOracle oracle,
        final Map<String, Integer> marks,
        final String contractId
    ) throws ContractViolation {
        final Deque<AncestorWalk> stack = new ArrayDeque<>();
        enter(root, members, oracle, marks, contractId, stack);
        while (!stack.isEmpty()) {
            final AncestorWalk frame = stack.peek();
            if (!frame.hasNext()) {
                marks.put(frame.name, 2);
                stack.pop();
                continue;
            }
            final Edge edge = frame.next();
            final String ancestor = edge.name;
            final MemberInfo memberAncestor = members.get(ancestor);
            final int ancestorAccess;
            final Set<String> ancestorPermits;
            if (memberAncestor != null) {
                ancestorAccess = memberAncestor.access();
                ancestorPermits = memberAncestor.permits();
            } else {
                final ContractTypeOracle.TrustedInfo trusted =
                    oracle.lookup(ancestor);
                if (trusted == null) {
                    throw new ContractViolation(
                        Rejection.CLOSURE,
                        "public event contract " + contractId + " type "
                            + frame.name + " has supertype " + ancestor
                            + " which cannot be resolved"
                    );
                }
                ancestorAccess = trusted.accessFlags();
                ancestorPermits = trusted.permits();
            }
            checkAncestorEdge(
                contractId, frame.name, frame.isInterface, edge.superEdge,
                ancestor, ancestorAccess, ancestorPermits);
            if (memberAncestor != null) {
                enter(ancestor, members, oracle, marks, contractId, stack);
            }
        }
    }

    /**
     * Pushes a member onto the definability walk: marks it gray, computes its
     * supertype edges, and rejects a gray re-entry as an inheritance cycle.
     */
    private static void enter(
        final String name,
        final Map<String, MemberInfo> members,
        final ContractTypeOracle oracle,
        final Map<String, Integer> marks,
        final String contractId,
        final Deque<AncestorWalk> stack
    ) throws ContractViolation {
        final Integer mark = marks.get(name);
        if (mark != null && mark == 2) {
            return;
        }
        if (mark != null) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId + " type " + name
                    + " has a circular supertype graph"
            );
        }
        final MemberInfo member = members.get(name);
        marks.put(name, 1);
        final List<Edge> edges = new ArrayList<>();
        if (member.superName() == null) {
            throw invalidAncestor(
                contractId, name, "<none>", "declares no superclass");
        }
        edges.add(new Edge(member.superName().replace('/', '.'), true));
        for (final String iface : member.interfaces()) {
            edges.add(new Edge(iface.replace('/', '.'), false));
        }
        stack.push(new AncestorWalk(name, member.isInterface(), edges));
    }

    /**
     * Mirrors the VM's kind checks on an inheritance edge: a superclass must be a
     * non-final class, a superinterface must be an interface, and a sealed ancestor
     * must permit the member.
     */
    private static void checkAncestorEdge(
        final String contractId,
        final String childName,
        final boolean childInterface,
        final boolean superEdge,
        final String ancestor,
        final int ancestorAccess,
        final Set<String> ancestorPermits
    ) throws ContractViolation {
        final boolean ancestorInterface =
            (ancestorAccess & Opcodes.ACC_INTERFACE) != 0;
        if (superEdge) {
            if (childInterface && !ancestor.equals("java.lang.Object")) {
                throw invalidAncestor(
                    contractId, childName, ancestor, "is not java.lang.Object");
            }
            if (!childInterface && ancestorInterface) {
                throw invalidAncestor(
                    contractId, childName, ancestor, "is an interface");
            }
            if (!childInterface && (ancestorAccess & Opcodes.ACC_FINAL) != 0) {
                throw invalidAncestor(
                    contractId, childName, ancestor, "is final");
            }
        } else if (!ancestorInterface) {
            throw invalidAncestor(
                contractId, childName, ancestor, "is not an interface");
        }
        if (!ancestorPermits.isEmpty() && !ancestorPermits.contains(childName)) {
            throw invalidAncestor(
                contractId, childName, ancestor, "is sealed and does not permit it");
        }
    }

    private static ContractViolation invalidAncestor(
        final String contractId,
        final String childName,
        final String ancestor,
        final String detail
    ) {
        return new ContractViolation(
            Rejection.CONTENT,
            "public event contract " + contractId + " type " + childName
                + " has invalid supertype " + ancestor + ": " + detail
        );
    }

    private record Edge(String name, boolean superEdge) {}

    private static final class AncestorWalk {
        final String name;
        final boolean isInterface;
        private final List<Edge> edges;
        private int cursor;

        AncestorWalk(
            final String name,
            final boolean isInterface,
            final List<Edge> edges
        ) {
            this.name = name;
            this.isInterface = isInterface;
            this.edges = edges;
        }

        boolean hasNext() {
            return cursor < edges.size();
        }

        Edge next() {
            return edges.get(cursor++);
        }
    }

    /** Maps a {@code .class} entry path to its binary class name. */
    public static String binaryName(final String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length())
            .replace('/', '.');
    }

    private static String sha256Hex(final byte[] bytes) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
