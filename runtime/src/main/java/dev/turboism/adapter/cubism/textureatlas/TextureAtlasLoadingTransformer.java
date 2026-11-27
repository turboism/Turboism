package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.adapter.cubism.optimization.atlas.AtlasDirectCompositingPatcher;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Coordinates atlas editor initialization progress, drawable-group deduplication, off-EDT mesh
 * solving, parallel preview scaling, and non-mesh atlas UI/confirm speedups.
 */
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
    private static final String RESOLVE_DRAWABLES = "resolveDrawableGroups";
    private static final String RESOLVE_DRAWABLES_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";
    private static final String HASH_MAP_INTERNAL = "java/util/HashMap";
    private static final String DRAWABLE_GROUPS_METHOD = "a";
    private static final String DRAWABLE_GROUPS_DESC =
            "(Lcom/live2d/cubism/doc/model/CModelSource;)Ljava/util/HashMap;";

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
        if (parallel) {
            final byte[] meshPatched = TextureAtlasMeshApplyTransformer.patch(className, bytes);
            if (meshPatched != null) return meshPatched;
            final byte[] uiPatched = TextureAtlasUiListTransformer.patch(className, bytes);
            if (uiPatched != null) return uiPatched;
            final byte[] edgeFillPatched = TextureAtlasEdgeFillTransformer.TARGET.equals(className)
                    ? TextureAtlasEdgeFillTransformer.patch(bytes)
                    : null;
            if (edgeFillPatched != null) return edgeFillPatched;
            final byte[] atlasBakePatched = AtlasDirectCompositingPatcher.patch(className, bytes);
            if (atlasBakePatched != null) return atlasBakePatched;
        }
        if (!MODEL.equals(className)) return null;
        final ClassReader reader = new ClassReader(bytes);
        if (!MODEL.equals(reader.getClassName())) return null;
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final LoadingVisitor visitor = new LoadingVisitor(writer, parallel);
        reader.accept(visitor, 0);
        return visitor.matches() ? writer.toByteArray() : null;
    }

    private static final class LoadingVisitor extends ClassVisitor {
        private final boolean parallel;
        private String constructorTarget;
        private String initializer;
        private String preloadMethod;
        private int layerSites;
        private int wrappedSites;
        private int preloadSites;

        LoadingVisitor(final ClassWriter writer, final boolean parallel) {
            super(Opcodes.ASM9, writer);
            this.parallel = parallel;
        }

        boolean matches() {
            return layerSites == 1
                    && wrappedSites == 1
                    && initializer != null
                    && initializer.equals(constructorTarget)
                    && (!parallel || (preloadSites == 1 && initializer.equals(preloadMethod)));
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
                        return;
                    }
                    if (parallel
                            && privateInitializer
                            && opcode == Opcodes.INVOKEVIRTUAL
                            && owner.startsWith(LAYER_PACKAGE)
                            && DRAWABLE_GROUPS_METHOD.equals(member)
                            && DRAWABLE_GROUPS_DESC.equals(type)) {
                        preloadMethod = name;
                        preloadSites++;
                        super.visitLdcInsn(Type.getObjectType(TextureAtlasMeshSolverTransformer.MESH_OPS));
                        super.visitLdcInsn(new Handle(Opcodes.H_INVOKEVIRTUAL, owner, member, type, false));
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC, BRIDGE, RESOLVE_DRAWABLES, RESOLVE_DRAWABLES_DESC, false);
                        super.visitTypeInsn(Opcodes.CHECKCAST, HASH_MAP_INTERNAL);
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, member, type, isInterface);
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
