package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.reflect.CodeSources;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Substitutes the backing collection of reviewed native triangle lists. */
public final class TextureAtlasTriangleSetTransformer implements ClassFileTransformer {
    private static final String OWNER = TriangulationEdgeIndexPatcher.OWNER;
    private static final String SET_FIELD = TriangulationEdgeIndexPatcher.SET_FIELD;
    private static final String SET_TYPE = "Ljava/util/LinkedHashSet;";
    private static final String CONSTRUCTOR = "<init>";
    private static final String VOID = "()V";
    private static final String BRIDGE = Type.getInternalName(TextureAtlasTriangleSet.class);
    private static final String FACTORY = "forAtlas";
    private static final String FACTORY_TYPE = "(" + SET_TYPE + "Ljava/lang/Class;)" + SET_TYPE;
    private static final String ANGLE_CALLER = TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME;
    private static final String ANGLE_METHOD = "d";
    private static final String ANGLE_VECTOR = "com/live2d/graphics3d/type/GVector2";
    private static final String ANGLE_COMPUTE_DESC = "(L" + ANGLE_VECTOR + ";L" + ANGLE_VECTOR + ";)F";
    private static final String CLASS_SUFFIX = ".class";
    private static final String OBJECT_SUPER = "java/lang/Object";
    private static final String SINGLETON_FIELD = "a";
    private static final String THRESHOLD_FIELD = "i";
    private static final String THRESHOLD_METHOD = "f";
    private static final String FLOAT_DESCRIPTOR = "F";
    private static final String FLOAT_GETTER_DESC = "()F";
    private static final String GET_X = "getX";
    private static final String GET_Y = "getY";

    private final boolean indexEdges;

    public TextureAtlasTriangleSetTransformer(final boolean indexEdges) {
        this.indexEdges = indexEdges;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] bytes) {
        if (classBeingRedefined != null || bytes == null || loader == null || (module != null && module.isNamed())) {
            return null;
        }
        if (!OWNER.equals(className) && !(indexEdges && ANGLE_CALLER.equals(className))) {
            return null;
        }
        final Path hostArtifact = CodeSources.pathOf(protectionDomain);
        if (hostArtifact == null) return null;
        final String cubismVersion;
        try {
            cubismVersion = ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.cached(hostArtifact))
                    .orElse(null);
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
        if (cubismVersion == null) return null;
        if (OWNER.equals(className)) {
            return transformTriangleList(bytes);
        }
        try (ZipFile hostJar = new ZipFile(hostArtifact.toFile())) {
            return patchAngleGuard(bytes, cubismVersion, internalName -> readJarEntry(hostJar, internalName));
        } catch (IOException | RuntimeException unsupported) {
            return null;
        }
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] bytes) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, bytes);
    }

    byte[] transformReviewed(
            final String className, final byte[] bytes, final String cubismVersion, final EntryReader entries) {
        if (bytes == null || cubismVersion == null) return null;
        if (OWNER.equals(className)) {
            return transformTriangleList(bytes);
        }
        if (indexEdges && ANGLE_CALLER.equals(className)) {
            return patchAngleGuard(bytes, cubismVersion, entries);
        }
        return null;
    }

    private byte[] transformTriangleList(final byte[] bytes) {
        final ClassReader reader;
        try {
            reader = new ClassReader(bytes);
        } catch (RuntimeException malformed) {
            return null;
        }
        if (!OWNER.equals(reader.getClassName())) return null;
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] counts = new int[2]; // [0] = final field b, [1] = putfield sites in <init>()V
        final boolean[] headerValid = new boolean[1];
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public void visit(
                            final int version,
                            final int access,
                            final String name,
                            final String signature,
                            final String superName,
                            final String[] interfaces) {
                        headerValid[0] = OWNER.equals(name)
                                && (access & Opcodes.ACC_FINAL) != 0
                                && OBJECT_SUPER.equals(superName);
                        super.visit(version, access, name, signature, superName, interfaces);
                    }

                    @Override
                    public FieldVisitor visitField(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final Object value) {
                        if (SET_FIELD.equals(name)
                                && SET_TYPE.equals(descriptor)
                                && (access & Opcodes.ACC_FINAL) != 0) {
                            counts[0]++;
                        }
                        return super.visitField(access, name, descriptor, signature, value);
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!CONSTRUCTOR.equals(name) || !VOID.equals(descriptor)) return output;
                        return new MethodVisitor(Opcodes.ASM9, output) {
                            @Override
                            public void visitFieldInsn(
                                    final int opcode, final String owner, final String member, final String type) {
                                if (opcode == Opcodes.PUTFIELD
                                        && OWNER.equals(owner)
                                        && SET_FIELD.equals(member)
                                        && SET_TYPE.equals(type)) {
                                    counts[1]++;
                                    super.visitLdcInsn(Type.getObjectType(OWNER));
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, FACTORY, FACTORY_TYPE, false);
                                }
                                super.visitFieldInsn(opcode, owner, member, type);
                            }
                        };
                    }
                },
                0);
        if (!headerValid[0] || counts[0] != 1 || counts[1] != 1) return null;
        final byte[] scoped = writer.toByteArray();
        if (!indexEdges) return scoped;
        try {
            return new TriangulationEdgeIndexPatcher(BRIDGE).patch(scoped);
        } catch (TriangulationEdgeIndexPatcher.NotApplicable changed) {
            return scoped;
        }
    }

    private static byte[] patchAngleGuard(final byte[] bytes, final String cubismVersion, final EntryReader entries) {
        if (entries == null) return null;
        try {
            final String mathOwner = LazyTriangulationEdgePreparation.mathSingleton(cubismVersion)
                    .replace('.', '/');
            final String geometryOwner = findGeometryOwner(bytes);
            if (geometryOwner == null) return null;
            if (!hasReviewedGeometryShape(entries.read(geometryOwner), geometryOwner)
                    || !hasReviewedVectorShape(entries.read(ANGLE_VECTOR))
                    || !hasReviewedMathShape(entries.read(mathOwner), mathOwner)) {
                return null;
            }
            return TriangulationAngleGuardPatcher.patchForAtlas(bytes);
        } catch (IOException | IllegalArgumentException | SecurityException unsupported) {
            return null;
        }
    }

    private static String findGeometryOwner(final byte[] callerBytes) {
        final String[] owner = new String[1];
        final int[] count = new int[1];
        try {
            final ClassReader reader = new ClassReader(callerBytes);
            if (!ANGLE_CALLER.equals(reader.getClassName())) return null;
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String name,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            if (!ANGLE_METHOD.equals(name) || !VOID.equals(descriptor)) return null;
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitMethodInsn(
                                        final int opcode,
                                        final String callOwner,
                                        final String callName,
                                        final String callDesc,
                                        final boolean isInterface) {
                                    if (opcode == Opcodes.INVOKEVIRTUAL
                                            && SINGLETON_FIELD.equals(callName)
                                            && ANGLE_COMPUTE_DESC.equals(callDesc)) {
                                        owner[0] = callOwner;
                                        count[0]++;
                                    }
                                }
                            };
                        }
                    },
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException malformed) {
            return null;
        }
        return count[0] == 1 ? owner[0] : null;
    }

    private static boolean hasReviewedGeometryShape(final byte[] bytes, final String expectedOwner) {
        if (bytes == null) return false;
        final boolean[] checks = new boolean[3]; // [0]=header, [1]=singleton field, [2]=angle method
        try {
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(
                                        final int version,
                                        final int access,
                                        final String name,
                                        final String signature,
                                        final String superName,
                                        final String[] interfaces) {
                                    checks[0] = expectedOwner.equals(name)
                                            && (access & Opcodes.ACC_FINAL) != 0
                                            && OBJECT_SUPER.equals(superName);
                                }

                                @Override
                                public FieldVisitor visitField(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final Object value) {
                                    final int required = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                                    if (SINGLETON_FIELD.equals(name)
                                            && ("L" + expectedOwner + ";").equals(descriptor)
                                            && (access & required) == required) {
                                        checks[1] = true;
                                    }
                                    return null;
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final String[] exceptions) {
                                    if (SINGLETON_FIELD.equals(name)
                                            && ANGLE_COMPUTE_DESC.equals(descriptor)
                                            && (access & Opcodes.ACC_FINAL) != 0) {
                                        checks[2] = true;
                                    }
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException malformed) {
            return false;
        }
        return checks[0] && checks[1] && checks[2];
    }

    private static boolean hasReviewedVectorShape(final byte[] bytes) {
        if (bytes == null) return false;
        final boolean[] checks = new boolean[3]; // [0]=header, [1]=getX, [2]=getY
        try {
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(
                                        final int version,
                                        final int access,
                                        final String name,
                                        final String signature,
                                        final String superName,
                                        final String[] interfaces) {
                                    checks[0] = ANGLE_VECTOR.equals(name);
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final String[] exceptions) {
                                    final int required = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
                                    if ((access & required) == required && FLOAT_GETTER_DESC.equals(descriptor)) {
                                        if (GET_X.equals(name)) checks[1] = true;
                                        else if (GET_Y.equals(name)) checks[2] = true;
                                    }
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException malformed) {
            return false;
        }
        return checks[0] && checks[1] && checks[2];
    }

    private static boolean hasReviewedMathShape(final byte[] bytes, final String expectedOwner) {
        if (bytes == null) return false;
        final boolean[] checks = new boolean[4]; // [0]=header, [1]=singleton, [2]=epsilon, [3]=threshold method
        try {
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(
                                        final int version,
                                        final int access,
                                        final String name,
                                        final String signature,
                                        final String superName,
                                        final String[] interfaces) {
                                    checks[0] = expectedOwner.equals(name)
                                            && (access & Opcodes.ACC_FINAL) != 0
                                            && OBJECT_SUPER.equals(superName);
                                }

                                @Override
                                public FieldVisitor visitField(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final Object value) {
                                    final int staticFinal = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                                    if (SINGLETON_FIELD.equals(name)
                                            && ("L" + expectedOwner + ";").equals(descriptor)
                                            && (access & staticFinal) == staticFinal) {
                                        checks[1] = true;
                                    } else if (THRESHOLD_FIELD.equals(name)
                                            && FLOAT_DESCRIPTOR.equals(descriptor)
                                            && (access & (Opcodes.ACC_PRIVATE | staticFinal))
                                                    == (Opcodes.ACC_PRIVATE | staticFinal)) {
                                        checks[2] = true;
                                    }
                                    return null;
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final String[] exceptions) {
                                    if (THRESHOLD_METHOD.equals(name)
                                            && FLOAT_GETTER_DESC.equals(descriptor)
                                            && (access & Opcodes.ACC_FINAL) != 0) {
                                        checks[3] = true;
                                    }
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException malformed) {
            return false;
        }
        return checks[0] && checks[1] && checks[2] && checks[3];
    }

    private static byte[] readJarEntry(final ZipFile jar, final String internalName) throws IOException {
        final ZipEntry entry = jar.getEntry(internalName + CLASS_SUFFIX);
        if (entry == null) return null;
        try (InputStream input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    @FunctionalInterface
    interface EntryReader {
        byte[] read(String internalName) throws IOException;
    }
}
