package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.io.Sha256;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
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
    private static final String ANGLE_GEOMETRY = "com/live2d/graphics3d/editableMesh/triangulation/r.class";
    private static final String ANGLE_VECTOR = "com/live2d/graphics3d/type/GVector2.class";
    private static final String EARLY_MATH = "com/live2d/util/K.class";
    private static final String RECENT_MATH = "com/live2d/util/L.class";
    private static final String EARLY_VECTOR_DIGEST =
            "c6fc13cdbc80d13a2a2eb236d494a0242bd76fb1c03668ba4cfbbcfd940058fe";
    private static final String RECENT_VECTOR_DIGEST =
            "91d06613e29fe8d0b1b03a30594cbc28d9e2a49adcdcd544be60e2576e0aac31";
    private static final String RECENT_MATH_DIGEST = "c437c78034e6ed6017eb9c28b0f4bbbe940b6e9eeb62248589888c01df434f1e";
    private static final AngleContract EARLY_ANGLE = new AngleContract(
            "6ed0c12e32e0b6175e6adf6c25c7f8ea76d34af20583d21c51b03857ba0221bb",
            EARLY_VECTOR_DIGEST,
            EARLY_MATH,
            "a58d2ce6a18c9e34086dfa753af4bca850745ee4c95c80f8beca5e286a94658f");
    private static final AngleContract MIDDLE_ANGLE = new AngleContract(
            "3399b43080466f9745b213e5bf10b90a6f8d9a00f20da180c5b504790d6270a4",
            RECENT_VECTOR_DIGEST,
            RECENT_MATH,
            RECENT_MATH_DIGEST);
    private static final AngleContract RECENT_ANGLE = new AngleContract(
            "fe3f13657139cdb8286a04b2ec139078511354395a57202cbc7fb3526193006e",
            RECENT_VECTOR_DIGEST,
            RECENT_MATH,
            RECENT_MATH_DIGEST);
    private static final Map<String, AngleContract> ANGLES_BY_CALLER = Map.of(
            "8b41fd0cd91b8d69f5ed57159dc592a0384a97bdc5fcb99e93953893d984eeca", EARLY_ANGLE,
            "70605f37a6b5a91efcac13e7e836eda01c0fe52bc7628c5ec63225de824eadf5", EARLY_ANGLE,
            "0361e54535558881636ff21914e4c37812d84931a4d6d75e873934fb130452cc", EARLY_ANGLE,
            "ef4a5eb2f0e1b0ac0295f76146a513a326729cfe4526884104cbb27978c52543", MIDDLE_ANGLE,
            "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d", RECENT_ANGLE);
    private static final Set<String> DIGESTS = Set.of(
            "b0a11ffc8969e5a8d1266ca01db85dacb75ca4de64e14b9f282ba4c32169d920",
            "87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29");

    private final boolean indexEdges;

    public TextureAtlasTriangleSetTransformer(final boolean indexEdges) {
        this.indexEdges = indexEdges;
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] bytes) {
        if (classBeingRedefined != null || bytes == null) return null;
        if (indexEdges && ANGLE_CALLER.equals(className)) {
            return patchAngleGuard(loader, bytes);
        }
        if (!OWNER.equals(className) || !DIGESTS.contains(Sha256.hex(bytes))) return null;
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
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
                                    super.visitLdcInsn(Type.getObjectType(OWNER));
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, FACTORY, FACTORY_TYPE, false);
                                }
                                super.visitFieldInsn(opcode, owner, member, type);
                            }
                        };
                    }
                },
                0);
        final byte[] scoped = writer.toByteArray();
        if (!indexEdges) return scoped;
        try {
            return new TriangulationEdgeIndexPatcher(BRIDGE).patch(scoped);
        } catch (TriangulationEdgeIndexPatcher.NotApplicable changed) {
            return scoped;
        }
    }

    private static byte[] patchAngleGuard(final ClassLoader loader, final byte[] bytes) {
        if (loader == null) return null;
        final var contract = ANGLES_BY_CALLER.get(Sha256.hex(bytes));
        if (contract == null) return null;
        try {
            if (!matches(loader, ANGLE_GEOMETRY, contract.geometry())
                    || !matches(loader, ANGLE_VECTOR, contract.vector())
                    || !matches(loader, contract.mathResource(), contract.math())) return null;
            return TriangulationAngleGuardPatcher.patchForAtlas(bytes);
        } catch (IOException | IllegalArgumentException | SecurityException unsupported) {
            return null;
        }
    }

    private static boolean matches(final ClassLoader loader, final String name, final String digest)
            throws IOException {
        try (var resource = loader.getResourceAsStream(name)) {
            return resource != null && digest.equals(Sha256.hex(resource.readAllBytes()));
        }
    }

    private record AngleContract(String geometry, String vector, String mathResource, String math) {}
}
