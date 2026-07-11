package dev.turboism.mapping.draft;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Bounded archive and class inspection for mapping update candidates. */
final class BoundedJarScanner {
    private static final int MAX_SUPPORTED_CLASS_MAJOR = 61;

    private final JarScanPolicy policy;
    private final ArtifactSnapshotter snapshotter;

    BoundedJarScanner(final JarScanPolicy policy, final ArtifactSnapshotter snapshotter) {
        this.policy = policy;
        this.snapshotter = snapshotter;
    }

    ArtifactScan scanSnapshot(final Path artifact, final GenerateRequest request) {
        try (ArtifactSnapshotter.ArtifactSnapshot snapshot = snapshotter.create(artifact, policy.maxArtifactBytes())) {
            final FileSafety.Digest currentDigest = FileSafety.digest(
                artifact, "ARTIFACT_NOT_REGULAR", policy.maxArtifactBytes(), "JAR_SIZE_LIMIT"
            );
            if (!snapshot.digest().equals(currentDigest)) {
                fail("ARTIFACT_CHANGED_DURING_SNAPSHOT", "artifact changed while creating the scan snapshot");
            }
            return scan(snapshot.path(), request, snapshot.digest());
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DraftMappingException("ARTIFACT_SNAPSHOT_FAILED", "could not create private artifact snapshot", exception);
        }
    }

    private ArtifactScan scan(final Path artifact, final GenerateRequest request, final FileSafety.Digest digest) {
        try {
            final List<Edge> targets = new ArrayList<>();
            int entries = 0;
            int classes = 0;
            long expanded = 0;
            final Set<String> names = new HashSet<>();
            final Set<String> classNames = new HashSet<>();
            final java.util.Map<String, Set<MethodSignature>> methodsByOwner = new HashMap<>();
            boolean callerFound = false;
            boolean callerMethodFound = false;
            try (ZipFile zip = new ZipFile(artifact.toFile(), StandardCharsets.UTF_8)) {
                final var enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    final ZipEntry entry = enumeration.nextElement();
                    entries++;
                    if (entries > policy.maxEntries()) fail("JAR_ENTRY_LIMIT", "too many archive entries");
                    final String name = entry.getName();
                    if (!names.add(name)) fail("JAR_DUPLICATE_ENTRY", "duplicate archive entry");
                    validateEntryName(name);
                    if (name.startsWith("META-INF/versions/")) fail("JAR_MULTI_RELEASE_UNSUPPORTED", "multi-release JARs are unsupported");
                    if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) fail("JAR_NESTED_UNSUPPORTED", "nested JARs are unsupported");
                    if (entry.isDirectory()) continue;
                    final byte[] bytes = readEntry(zip, entry);
                    expanded += bytes.length;
                    if (expanded > policy.maxExpandedBytes()) fail("JAR_EXPANDED_LIMIT", "expanded bytes exceed scan policy");
                    if ("META-INF/MANIFEST.MF".equals(name)) rejectMultiReleaseManifest(bytes);
                    if (!name.endsWith(".class")) continue;
                    classes++;
                    if (classes > policy.maxClassEntries()) fail("JAR_CLASS_LIMIT", "too many class entries");
                    final ClassMetadata metadata = classMetadata(bytes);
                    final String entryOwner = name.substring(0, name.length() - ".class".length());
                    if (!entryOwner.equals(metadata.internalName())) {
                        fail("CLASS_IDENTITY_MISMATCH", "class entry path does not match its internal name");
                    }
                    if (!classNames.add(metadata.internalName())) {
                        fail("CLASS_DUPLICATE_INTERNAL", "class internal name occurs more than once");
                    }
                    methodsByOwner.put(metadata.internalName(), metadata.methods());
                    if (metadata.internalName().equals(request.callerOwner())) {
                        callerFound = true;
                        if (metadata.methods().contains(new MethodSignature(request.callerName(), request.callerDescriptor()))) {
                            callerMethodFound = true;
                            targets.addAll(findEdges(bytes, request));
                        }
                    }
                }
            } catch (ZipException exception) {
                throw new DraftMappingException("JAR_MALFORMED", "artifact is not a valid JAR", exception);
            }
            if (!callerFound) fail("SCAN_CALLER_OWNER_MISSING", "caller class was not found");
            if (!callerMethodFound) fail("SCAN_CALLER_METHOD_MISSING", "caller method was not found");
            for (Edge target : targets) {
                final Set<MethodSignature> methods = methodsByOwner.get(target.owner());
                if (methods == null) fail("SCAN_TARGET_OWNER_MISSING", "selected target owner is absent from the artifact");
                if (!methods.contains(new MethodSignature(target.name(), target.descriptor()))) {
                    fail("SCAN_TARGET_METHOD_MISSING", "selected target method is not declared by its owner");
                }
            }
            return new ArtifactScan(digest.size(), digest.sha256(), List.copyOf(targets));
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DraftMappingException("JAR_MALFORMED", "could not scan artifact", exception);
        }
    }

    private byte[] readEntry(final ZipFile zip, final ZipEntry entry) throws IOException {
        if (entry.getSize() > policy.maxEntryBytes()) fail("JAR_ENTRY_SIZE_LIMIT", "archive entry exceeds scan policy");
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            long count = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                count += read;
                if (count > policy.maxEntryBytes()) fail("JAR_ENTRY_SIZE_LIMIT", "archive entry exceeds scan policy");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static List<Edge> findEdges(final byte[] bytes, final GenerateRequest request) {
        final List<Edge> edges = new ArrayList<>();
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(
                    final int access, final String name, final String descriptor, final String signature, final String[] exceptions
                ) {
                    if (!request.callerName().equals(name) || !request.callerDescriptor().equals(descriptor)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(
                            final int opcode, final String owner, final String targetName, final String targetDescriptor, final boolean isInterface
                        ) {
                            if (request.targetMethodName().equals(targetName)
                                && request.targetMethodDescriptor().equals(targetDescriptor)
                                && invocationMatches(request.invocationConstraint(), opcode)) {
                                edges.add(new Edge(owner, targetName, targetDescriptor, invocationName(opcode)));
                            }
                        }

                        @Override public void visitInvokeDynamicInsn(
                            final String name, final String descriptor, final org.objectweb.asm.Handle bootstrapMethodHandle,
                            final Object... bootstrapMethodArguments
                        ) {
                            if (request.targetMethodName().equals(name) && request.targetMethodDescriptor().equals(descriptor)) {
                                fail("SCAN_INVOKEDYNAMIC_UNSUPPORTED", "matching invokedynamic recipes are unsupported");
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return edges;
        } catch (DraftMappingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DraftMappingException("CLASS_MALFORMED", "caller class metadata is malformed", exception);
        }
    }

    private static boolean invocationMatches(final InvocationConstraint constraint, final int opcode) {
        return switch (constraint) {
            case ANY -> true;
            case STATIC -> opcode == Opcodes.INVOKESTATIC;
            case INSTANCE -> opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKESPECIAL;
        };
    }

    private static String invocationName(final int opcode) {
        return opcode == Opcodes.INVOKESTATIC ? "STATIC" : "INSTANCE";
    }

    private static ClassMetadata classMetadata(final byte[] bytes) {
        if (bytes.length < 8 || (bytes[0] & 0xff) != 0xca || (bytes[1] & 0xff) != 0xfe
            || (bytes[2] & 0xff) != 0xba || (bytes[3] & 0xff) != 0xbe) {
            fail("CLASS_MALFORMED", "class entry is truncated or malformed");
        }
        final int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        if (major > MAX_SUPPORTED_CLASS_MAJOR) fail("CLASS_UNSUPPORTED_MAJOR", "class major exceeds Java 17");
        try {
            final ClassReader reader = new ClassReader(bytes);
            final Set<MethodSignature> methods = new HashSet<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(
                    final int access, final String name, final String descriptor, final String signature, final String[] exceptions
                ) {
                    methods.add(new MethodSignature(name, descriptor));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new ClassMetadata(reader.getClassName(), Set.copyOf(methods));
        } catch (RuntimeException exception) {
            throw new DraftMappingException("CLASS_MALFORMED", "class entry is malformed", exception);
        }
    }

    private static void rejectMultiReleaseManifest(final byte[] bytes) throws IOException {
        try (InputStream input = new java.io.ByteArrayInputStream(bytes)) {
            final String value = new Manifest(input).getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE);
            if (value != null && Boolean.parseBoolean(value.trim())) {
                fail("JAR_MULTI_RELEASE_UNSUPPORTED", "multi-release JARs are unsupported");
            }
        }
    }

    private static void validateEntryName(final String name) {
        if (name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")
            || name.contains("..") || name.contains(":") || name.indexOf('\0') >= 0) {
            fail("JAR_ILLEGAL_ENTRY_NAME", "archive entry name is unsafe");
        }
    }

    private static void fail(final String code, final String message) {
        throw new DraftMappingException(code, message);
    }

    private record MethodSignature(String name, String descriptor) { }
    private record ClassMetadata(String internalName, Set<MethodSignature> methods) { }
    record Edge(String owner, String name, String descriptor, String invocation) { }
    record ArtifactScan(long size, String sha256, List<Edge> targets) { }
}
