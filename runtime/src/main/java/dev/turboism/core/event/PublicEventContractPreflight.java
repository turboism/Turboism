package dev.turboism.core.event;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Shared no-execution preflight for declared public event contract artifacts.
 *
 * <p>Both managed admission ({@code dev.turboism.distribution}) and load-time binding
 * ({@link PublicEventContractCatalog}) run the exact same rule set here: declared
 * size bound, sha256 pinning, the class-only archive shape (no {@code Class-Path}
 * manifest, no services/multi-release/module-info entries, no non-class payload),
 * member namespace ownership, loose-class collision, and the payload type-reference
 * closure. The closure is evaluated by walking constant-pool type references with
 * ASM — contract bytes are never defined, loaded, initialized, or executed in this
 * JVM. Load-time binding keeps {@link PublicEventContractClosure} as the reflective
 * defensive re-verification once classes are actually bound.
 */
public final class PublicEventContractPreflight {

    /** Maximum byte size of a single contract artifact. */
    public static final long MAX_ARTIFACT_BYTES = 8L * 1024 * 1024;

    /** Packages a contract artifact must never define classes in. */
    static final List<String> FORBIDDEN_CLASS_PREFIXES = List.of(
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "com.live2d.",
        "dev.turboism."
    );

    /** Binary-name prefix of the shared SDK surface contract types may reference. */
    static final String SDK_PACKAGE_PREFIX = "dev.turboism.sdk.";

    /**
     * Packages resolvable through the restricted contract parent: every module in the
     * boot layer defined by the boot or platform loader — the exact same reachability
     * {@link SdkContractParent} grants bound contract classes.
     */
    private static final Set<String> PLATFORM_PACKAGES = platformPackages();

    /** No-op visitor used to prove a member class file parses structurally. */
    private static final ClassVisitor PARSE_PROBE = new ClassVisitor(Opcodes.ASM9) {};

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

    private PublicEventContractPreflight() {}

    /**
     * Verifies one declared contract artifact without loading any of its classes.
     *
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
        final String pluginId,
        final String contractId,
        final String artifactPath,
        final String declaredSha256,
        final byte[] bytes,
        final Collection<String> loosePluginClasses,
        final Collection<String> payloadSeeds
    ) throws ContractViolation {
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            throw new ContractViolation(
                Rejection.TOO_LARGE,
                "public event contract " + contractId + " artifact " + artifactPath
                    + " exceeds the " + MAX_ARTIFACT_BYTES + " byte limit"
            );
        }
        final String sha256 = sha256Hex(bytes);
        if (!declaredSha256.equals(sha256)) {
            throw new ContractViolation(
                Rejection.HASH_MISMATCH,
                "public event contract " + contractId + " artifact sha256 mismatch:"
                    + " descriptor declares " + declaredSha256
                    + " but the embedded artifact hashes to " + sha256
            );
        }
        final Map<String, byte[]> members = readContractClasses(contractId, bytes);
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
        verifyPayloadClosure(contractId, members, payloadSeeds);
        return new Inspection(sha256, Set.copyOf(members.keySet()));
    }

    /**
     * Enforces the class-only archive shape and collects member class bytes for the
     * byte-level payload closure walk.
     */
    private static Map<String, byte[]> readContractClasses(
        final String contractId,
        final byte[] bytes
    ) throws ContractViolation {
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry();
                 entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                final String name = entry.getName();
                final byte[] entryBytes = zip.readAllBytes();
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
                final String className = binaryName(name);
                for (final String forbidden : FORBIDDEN_CLASS_PREFIXES) {
                    if (className.startsWith(forbidden)) {
                        throw new ContractViolation(
                            Rejection.CONTENT,
                            "public event contract " + contractId
                                + " contains forbidden class " + className
                        );
                    }
                }
                try {
                    new ClassReader(entryBytes).accept(
                        PARSE_PROBE,
                        ClassReader.SKIP_CODE
                            | ClassReader.SKIP_DEBUG
                            | ClassReader.SKIP_FRAMES
                    );
                } catch (final RuntimeException failure) {
                    throw new ContractViolation(
                        Rejection.CONTENT,
                        "public event contract " + contractId + " class "
                            + className + " is not a readable class file"
                    );
                }
                classes.putIfAbsent(className, entryBytes);
            }
        } catch (final IOException failure) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId
                    + " artifact is not a readable JAR: " + failure.getMessage()
            );
        }
        if (classes.isEmpty()) {
            throw new ContractViolation(
                Rejection.CONTENT,
                "public event contract " + contractId + " artifact contains no classes"
            );
        }
        return classes;
    }

    /**
     * Byte-level mirror of {@link PublicEventContractClosure}: starting from the
     * declared event types owned by this artifact, every type referenced on the API
     * surface (signatures, supertypes, record components, public/protected members,
     * throws clauses, permitted subclasses) must resolve to another artifact member,
     * the {@code dev.turboism.sdk.*} surface, or a JDK/platform package.
     */
    private static void verifyPayloadClosure(
        final String contractId,
        final Map<String, byte[]> members,
        final Collection<String> payloadSeeds
    ) throws ContractViolation {
        final Set<String> visited = new HashSet<>();
        final Deque<String> pending = new ArrayDeque<>();
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
            final Set<String> referenced = new LinkedHashSet<>();
            try {
                new ClassReader(members.get(name)).accept(
                    new ApiSurfaceVisitor(referenced),
                    ClassReader.SKIP_CODE
                        | ClassReader.SKIP_DEBUG
                        | ClassReader.SKIP_FRAMES
                );
            } catch (final RuntimeException failure) {
                throw new ContractViolation(
                    Rejection.CONTENT,
                    "public event contract " + contractId + " class " + name
                        + " is not a readable class file"
                );
            }
            for (final String reference : referenced) {
                if (members.containsKey(reference)) {
                    pending.add(reference);
                    continue;
                }
                if (reference.startsWith(SDK_PACKAGE_PREFIX)
                    || PLATFORM_PACKAGES.contains(packageName(reference))) {
                    continue;
                }
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

    /** Collects every class binary name referenced on a member's public API surface. */
    private static final class ApiSurfaceVisitor extends ClassVisitor {
        private final Set<String> referenced;

        ApiSurfaceVisitor(final Set<String> referenced) {
            super(Opcodes.ASM9);
            this.referenced = referenced;
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
            addInternalName(superName);
            if (interfaces != null) {
                for (final String iface : interfaces) {
                    addInternalName(iface);
                }
            }
            if (signature != null) {
                new SignatureReader(signature).accept(signatureVisitor());
            }
        }

        @Override
        public void visitPermittedSubclass(final String permittedSubclass) {
            addInternalName(permittedSubclass);
        }

        @Override
        public FieldVisitor visitField(
            final int access,
            final String name,
            final String descriptor,
            final String signature,
            final Object value
        ) {
            if (apiMember(access)) {
                addType(Type.getType(descriptor));
                if (signature != null) {
                    new SignatureReader(signature).accept(signatureVisitor());
                }
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
            if (apiMember(access)) {
                addType(Type.getReturnType(descriptor));
                for (final Type argument : Type.getArgumentTypes(descriptor)) {
                    addType(argument);
                }
                if (exceptions != null) {
                    for (final String exception : exceptions) {
                        addInternalName(exception);
                    }
                }
                if (signature != null) {
                    new SignatureReader(signature).accept(signatureVisitor());
                }
            }
            return null;
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(
            final String name,
            final String descriptor,
            final String signature
        ) {
            addType(Type.getType(descriptor));
            if (signature != null) {
                new SignatureReader(signature).accept(signatureVisitor());
            }
            return null;
        }

        private boolean apiMember(final int access) {
            return (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0;
        }

        private void addType(final Type type) {
            Type unwrapped = type;
            while (unwrapped.getSort() == Type.ARRAY) {
                unwrapped = unwrapped.getElementType();
            }
            if (unwrapped.getSort() == Type.OBJECT) {
                referenced.add(unwrapped.getClassName());
            }
        }

        private void addInternalName(final String internalName) {
            if (internalName != null) {
                referenced.add(internalName.replace('/', '.'));
            }
        }

        private SignatureVisitor signatureVisitor() {
            return new SignatureCollector(referenced);
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

    /** Maps a {@code .class} entry path to its binary class name. */
    public static String binaryName(final String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length())
            .replace('/', '.');
    }

    private static String packageName(final String binaryName) {
        final int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }

    private static Set<String> platformPackages() {
        final ClassLoader platform = ClassLoader.getPlatformClassLoader();
        final Set<String> packages = new HashSet<>();
        for (final Module module : ModuleLayer.boot().modules()) {
            final ClassLoader loader = module.getClassLoader();
            if (loader == null || loader == platform) {
                packages.addAll(module.getPackages());
            }
        }
        return Set.copyOf(packages);
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
