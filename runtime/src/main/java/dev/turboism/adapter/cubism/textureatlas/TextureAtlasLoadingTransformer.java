package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.core.io.Sha256;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Adds progress feedback at the native texture atlas editor's construction boundary. */
public final class TextureAtlasLoadingTransformer implements ClassFileTransformer {
    private static final String ATLAS_PACKAGE = "com/live2d/cubism/doc/modeling/ui/atlasEditor/";
    private static final String LAYER_PACKAGE = ATLAS_PACKAGE + "impl/";
    private static final String MODEL = LAYER_PACKAGE + "TAE_DataModel";
    private static final String CONSTRUCTOR = "<init>";
    private static final String MODEL_ARGUMENTS =
            "(Lcom/live2d/cubism/doc/modeling/CModelingDocument;Lcom/live2d/cubism/doc/model/CModelSource;L"
                    + ATLAS_PACKAGE + "f;Z)V";
    private static final String SETUP_LAYER = "setupEditLayer";
    private static final String VOID = "()V";
    private static final String BRIDGE = Type.getInternalName(TextureAtlasLoading.class);
    private static final String RUN = "run";
    private static final String RUN_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/invoke/MethodHandle;)V";
    private static final String LAYER_READY = "layerReady";
    private static final String PARALLEL_BRIDGE = Type.getInternalName(TextureAtlasParallelMeshes.class);
    private static final String PRELOAD_METHOD = "preload";
    private static final String PRELOAD_DESC = "(Ljava/util/Map;Ljava/lang/Class;)V";
    private static final String DRAWABLE_GROUPS_METHOD = "a";
    private static final String DRAWABLE_GROUPS_DESC =
            "(Lcom/live2d/cubism/doc/model/CModelSource;)Ljava/util/HashMap;";
    private static final String DRAWABLE_GROUPS_EARLY = LAYER_PACKAGE + "S";
    private static final String DRAWABLE_GROUPS_MIDDLE = LAYER_PACKAGE + "U";
    private static final String DRAWABLE_GROUPS_RECENT = LAYER_PACKAGE + "W";
    private static final Map<String, String> DRAWABLE_GROUPS_BY_MODEL = Map.of(
            "ce286072d8aebd87ba7aa154fbc005a2470f69a2270fda1e8b1fccca8d3d03b8", DRAWABLE_GROUPS_EARLY,
            "bc532b90bb9d13ddf72fa8d6f69bbb6e8a488c57d2afc22038c1a9b3d0195e2b", DRAWABLE_GROUPS_EARLY,
            "ff99130a5f68fd1fde47951e2efcba251cf3f92e1cce456833ec188eec366f6c", DRAWABLE_GROUPS_MIDDLE,
            "0b1c85404abbe2509c3f170787f350cc4aa33b3e18bd71aaf9a5eae787abb0f1", DRAWABLE_GROUPS_MIDDLE,
            "2b3981e25968662fde21accb73f2dc6a2e87f03133fd54c36673b67ec0ba818c", DRAWABLE_GROUPS_RECENT,
            "dbeef29b0565569e0b9a2a34f929c262d16da7daaf70a064a3acdfc738c70119", DRAWABLE_GROUPS_RECENT);

    private final boolean parallel;

    public TextureAtlasLoadingTransformer(final boolean parallel) {
        this.parallel = parallel;
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] bytes) {
        if (bytes == null || classBeingRedefined != null) return null;
        if (parallel && TextureAtlasMeshSolverTransformer.MESH_OPS.equals(className)) {
            return TextureAtlasMeshSolverTransformer.transform(bytes);
        }
        if (!MODEL.equals(className)) return null;
        final ClassReader reader = new ClassReader(bytes);
        if (!MODEL.equals(reader.getClassName())) return null;
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final LoadingVisitor visitor =
                new LoadingVisitor(writer, parallel ? DRAWABLE_GROUPS_BY_MODEL.get(Sha256.hex(bytes)) : null);
        reader.accept(visitor, 0);
        return visitor.matches() ? writer.toByteArray() : null;
    }

    private static final class LoadingVisitor extends ClassVisitor {
        private String constructorTarget;
        private String initializer;
        private int layerSites;
        private int wrappedSites;
        private final String groupsOwner;

        LoadingVisitor(final ClassWriter writer, final String groupsOwner) {
            super(Opcodes.ASM9, writer);
            this.groupsOwner = groupsOwner;
        }

        boolean matches() {
            return layerSites == 1 && wrappedSites == 1 && initializer.equals(constructorTarget);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions) {
            final MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
            final boolean constructor = CONSTRUCTOR.equals(name) && MODEL_ARGUMENTS.equals(descriptor);
            final boolean privateInitializer = (access & Opcodes.ACC_PRIVATE) != 0 && VOID.equals(descriptor);
            if (!constructor && !privateInitializer) return output;
            return new MethodVisitor(Opcodes.ASM9, output) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String member,
                        final String type,
                        final boolean isInterface) {
                    if (constructor
                            && opcode == Opcodes.INVOKESPECIAL
                            && MODEL.equals(owner)
                            && VOID.equals(type)
                            && !CONSTRUCTOR.equals(member)) {
                        constructorTarget = member;
                        wrappedSites++;
                        super.visitLdcInsn(new Handle(Opcodes.H_INVOKESPECIAL, owner, member, type, false));
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, RUN, RUN_DESCRIPTOR, false);
                    } else {
                        super.visitMethodInsn(opcode, owner, member, type, isInterface);
                    }
                    if (groupsOwner != null
                            && privateInitializer
                            && opcode == Opcodes.INVOKEVIRTUAL
                            && groupsOwner.equals(owner)
                            && DRAWABLE_GROUPS_METHOD.equals(member)
                            && DRAWABLE_GROUPS_DESC.equals(type)) {
                        super.visitInsn(Opcodes.DUP);
                        super.visitLdcInsn(Type.getObjectType(TextureAtlasMeshSolverTransformer.MESH_OPS));
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, PARALLEL_BRIDGE, PRELOAD_METHOD, PRELOAD_DESC, false);
                    }
                    if (privateInitializer
                            && opcode == Opcodes.INVOKEINTERFACE
                            && owner.startsWith(LAYER_PACKAGE)
                            && SETUP_LAYER.equals(member)
                            && VOID.equals(type)) {
                        initializer = name;
                        layerSites++;
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, LAYER_READY, VOID, false);
                    }
                }
            };
        }
    }
}
