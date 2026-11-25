package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Compiles monomorphic bytecode host accessors for {@link EditSubtreeHandles}.
 *
 * <p>Eliminates per-call {@code MethodHandle.invokeExact} overhead on EDT and DAG worker loops by
 * generating direct typed virtual calls against verified Cubism host classes.
 */
public final class EditSubtreeCompiledAccessors {

    private static final String GENERATED_PACKAGE_PREFIX =
            EditSubtreeCompiledAccessors.class.getPackageName().replace('.', '/') + "/";
    private static final String GENERATED_CLASS_PREFIX = GENERATED_PACKAGE_PREFIX + "CompiledHostOps_";
    private static final String OVERLAY_CLASS_PREFIX = GENERATED_PACKAGE_PREFIX + "CompiledOverlayOps_";
    private static final String OPS_INTERNAL = HostOps.class.getName().replace('.', '/');
    private static final String OVERLAY_OPS_INTERNAL =
            OverlayOps.class.getName().replace('.', '/');
    private static final String OBJECT_INTERNAL = "java/lang/Object";
    private static final String LIST_INTERNAL = "java/util/List";
    private static final String OBJECT_CTORS_DESC = "()V";

    private static final String CMODEL = "com/live2d/cubism/doc/model/CModel";
    private static final String CPARAM_SET = "com/live2d/cubism/doc/model/param/CParameterSet";
    private static final String CPARAM = "com/live2d/cubism/doc/model/param/CParameter";
    private static final String CPARAM_SRC = "com/live2d/cubism/doc/model/param/CParameterSource";
    private static final String AC_CONTROLLABLE = "com/live2d/cubism/doc/model/ACParameterControllable";
    private static final String AC_CONTROLLABLE_SRC = "com/live2d/cubism/doc/model/ACParameterControllableSource";
    private static final String AC_FORM = "com/live2d/cubism/doc/model/ACForm";
    private static final String AC_DEFORMER = "com/live2d/cubism/doc/model/deformer/ACDeformer";
    private static final String AC_DEFORMER_FORM = "com/live2d/cubism/doc/model/deformer/ACDeformerForm";
    private static final String AC_DEFORMER_SRC = "com/live2d/cubism/doc/model/deformer/ACDeformerSource";
    private static final String WARP_FORM = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm";
    private static final String WARP_DEFORMER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformer";
    private static final String ROT_FORM = "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformerForm";
    private static final String ROT_DEFORMER = "com/live2d/cubism/doc/model/deformer/rotation/CRotationDeformer";
    private static final String ART_MESH = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMesh";
    private static final String ART_MESH_FORM = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshForm";
    private static final String ART_MESH_SRC = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource";
    private static final String CPART = "com/live2d/cubism/doc/model/parts/CPart";
    private static final String CPART_SRC = "com/live2d/cubism/doc/model/parts/CPartSource";
    private static final String CGLUE = "com/live2d/cubism/doc/model/affecter/glue/CGlue";
    private static final String CGLUE_SRC = "com/live2d/cubism/doc/model/affecter/glue/CGlueSource";
    private static final String CGLUE_FORM = "com/live2d/cubism/doc/model/affecter/glue/CGlueForm";
    private static final String AC_AFFECTER = "com/live2d/cubism/doc/model/affecter/ACAffecter";
    private static final String CFLOAT_COLOR = "com/live2d/type/CFloatColor";
    private static final String CFLOAT_COLOR_DESC = "L" + CFLOAT_COLOR + ";";
    private static final String COORD_TYPE = "com/live2d/doc/CoordType";
    private static final String ICOPYABLE = "com/live2d/core/ICopyable";
    private static final String IBLENDABLE = "com/live2d/core/IBlendable";
    private static final String TRANSFORM_D = "com/live2d/doc/selection/d";
    private static final String KEYFORM_GRID_SRC = "com/live2d/cubism/doc/model/interpolator/KeyformGridSource";
    private static final String KEYFORM_BINDING_SRC = "com/live2d/cubism/doc/model/interpolator/KeyformBindingSource";
    private static final String CPART_GUID = "com/live2d/type/CPartGuid";
    private static final String MORPH_SET = "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet";
    private static final String EXT_MORPH_SET =
            "com/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet";
    private static final String CDEFORMER_GUID = "com/live2d/type/CDeformerGuid";
    private static final String CDRAWABLE_GUID = "com/live2d/type/CDrawableGuid";
    private static final String CPARAM_GUID = "com/live2d/type/CParameterGuid";
    private static final String GMESH_CONTAINER = "com/live2d/graphics3d/component/GMeshContainer";
    private static final String GMESH_BASE = "com/live2d/graphics3d/mesh/a";
    private static final String GENTITY = "com/live2d/graphics3d/entity/GEntity";
    private static final String GTRANSFORM = "com/live2d/graphics3d/component/GTransform";
    private static final String GVECTOR3 = "com/live2d/graphics3d/type/GVector3";
    private static final String GSIMPLE_MESH_ENTITY = "com/live2d/graphics3d/geometry/GSimpleMeshEntity";
    private static final String GSIMPLE_LINES_ENTITY = "com/live2d/graphics3d/geometry/GSimpleLinesEntity";
    private static final String AG_RENDERER = "com/live2d/graphics3d/component/AGRenderer";
    private static final String GMESH_RENDERER = "com/live2d/graphics3d/component/GMeshRenderer";
    private static final String GLINE_RENDERER = "com/live2d/graphics3d/component/GLineRenderer";
    private static final String GMATERIAL = "com/live2d/graphics3d/material/GMaterial";
    private static final String SORTING_LAYER = "com/live2d/graphics3d/component/a/c";
    private static final String FLOAT_BUFFER = "com/live2d/graphics3d/mesh/a/b";
    private static final String INT_BUFFER = "com/live2d/graphics3d/mesh/a/c";
    private static final String CCOLOR = "com/live2d/type/CColor";
    private static final String COPY_INFO = "com/live2d/core/a";
    private static final String CARRAY_LIST = "com/live2d/type/CArrayList";

    private static final String DESC_OBJ_TO_OBJ = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String DESC_OBJ_TO_LIST = "(Ljava/lang/Object;)Ljava/util/List;";
    private static final String DESC_OBJ_TO_INT = "(Ljava/lang/Object;)I";
    private static final String DESC_OBJ_TO_LONG = "(Ljava/lang/Object;)J";
    private static final String DESC_OBJ_TO_FLOAT = "(Ljava/lang/Object;)F";
    private static final String DESC_OBJ_TO_BOOL = "(Ljava/lang/Object;)Z";
    private static final String DESC_OBJ_TO_FLOATS = "(Ljava/lang/Object;)[F";
    private static final String DESC_OBJ_TO_INTS = "(Ljava/lang/Object;)[I";
    private static final String DESC_OBJ_VOID = "(Ljava/lang/Object;)V";
    private static final String DESC_OBJ_OBJ_VOID = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String DESC_OBJ_INT_VOID = "(Ljava/lang/Object;I)V";
    private static final String DESC_OBJ_FLOATS_VOID = "(Ljava/lang/Object;[F)V";
    private static final String DESC_OBJ_INTS_VOID = "(Ljava/lang/Object;[I)V";
    private static final String DESC_OBJ_BOOL_VOID = "(Ljava/lang/Object;Z)V";
    private static final String DESC_OBJ_FLOAT_VOID = "(Ljava/lang/Object;F)V";
    private static final String DESC_OBJ_LONG_VOID = "(Ljava/lang/Object;J)V";
    private static final String DESC_OBJ_OBJ_OBJ = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String DESC_OBJ_OBJ_BOOL = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String DESC_OBJ_OBJ_BOOL_VOID = "(Ljava/lang/Object;Ljava/lang/Object;Z)V";
    private static final String DESC_OBJ_OBJ_OBJ_VOID = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String DESC_OBJ_OBJ_FLOAT_VOID = "(Ljava/lang/Object;Ljava/lang/Object;F)V";

    private static final String[][] REQUIRED_HOST_CLASSES = {
        {CMODEL, "getAllDeformers"},
        {CPARAM_SET, "getUpdateVersion"},
        {CPARAM, "getValue"},
        {CPARAM_SRC, "getMaxValue"},
        {AC_CONTROLLABLE, "getPreDeformForm"},
        {AC_CONTROLLABLE_SRC, "getKeyformMorphTargets"},
        {AC_FORM, "getEditVersion"},
        {AC_DEFORMER, "getCalculatedTotalScale"},
        {AC_DEFORMER_FORM, "getMultiplyColor"},
        {AC_DEFORMER_SRC, "getUpdateVersion"},
        {WARP_FORM, "getPositions"},
        {WARP_DEFORMER, "setDeformedForm"},
        {ROT_FORM, "setAngle"},
        {ROT_DEFORMER, "setDeformedForm"},
        {ART_MESH, "getCopiedMeshVersion"},
        {ART_MESH_FORM, "transform"},
        {ART_MESH_SRC, "getMeshVersion"},
        {CPART, "getSource"},
        {CPART_SRC, "isVisibleInHierarchy"},
        {AC_AFFECTER, "getCached_editVersion"},
        {CGLUE, "getInterpolatedForm"},
        {CGLUE_SRC, "getTargetArtMeshA_guid"},
        {CGLUE_FORM, "getIntensity"},
        {CFLOAT_COLOR, "clamp"},
        {GMESH_CONTAINER, "getMesh"},
        {GMESH_BASE, "getPositions"}
    };

    private static final AtomicLong CLASS_SEQ = new AtomicLong(0L);
    private static final float UNIT_SCALE = 1.0f;

    private EditSubtreeCompiledAccessors() {}

    /**
     * Compiles a direct bytecode {@link HostOps} accessor against {@code hostLoader}.
     *
     * @param hostLoader admitted Cubism host class loader
     * @return compiled accessor instance, or {@code null} if the loader lacks required host types
     */
    static HostOps tryCompile(final ClassLoader hostLoader) {
        if (hostLoader == null || !hasAllRequiredHostClasses(hostLoader)) {
            return null;
        }
        try {
            final String internalName = GENERATED_CLASS_PREFIX + CLASS_SEQ.incrementAndGet();
            final byte[] bytecode = generateBytecode(internalName, hostLoader);
            final AccessorClassLoader defineLoader =
                    new AccessorClassLoader(hostLoader, HostOps.class.getClassLoader());
            final Class<?> generated = defineLoader.define(internalName.replace('/', '.'), bytecode);
            return (HostOps) generated.getDeclaredConstructor().newInstance();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /** Creates a fallback {@link HostOps} delegating every call to {@link EditSubtreeHandles}. */
    static HostOps fallback(final EditSubtreeHandles handles) {
        return new FallbackHostOps(handles);
    }

    /**
     * Compiles a monomorphic {@link OverlayOps} accessor class against {@code hostLoader}.
     * Returns {@code null} when overlay host types are absent, allowing {@link FallbackHostOps}
     * or MethodHandle fallback.
     */
    static OverlayOps tryCompileOverlay(final ClassLoader hostLoader) {
        if (hostLoader == null || !hasAllRequiredOverlayClasses(hostLoader)) {
            return null;
        }
        try {
            final long id = CLASS_SEQ.incrementAndGet();
            final String internalName = OVERLAY_CLASS_PREFIX + id;
            final byte[] bytecode = generateOverlayBytecode(internalName, hostLoader);
            final AccessorClassLoader defineLoader =
                    new AccessorClassLoader(hostLoader, OverlayOps.class.getClassLoader());
            final Class<?> generated = defineLoader.define(internalName.replace('/', '.'), bytecode);
            return (OverlayOps) generated.getDeclaredConstructor().newInstance();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static boolean hasAllRequiredOverlayClasses(final ClassLoader loader) {
        final String[][] checks = {
            {GENTITY, "getTransform"},
            {GTRANSFORM, "getPosition"},
            {GVECTOR3, "setZ"},
            {GSIMPLE_MESH_ENTITY, "getMeshContainer"},
            {GSIMPLE_LINES_ENTITY, "getLineRenderer"},
            {AG_RENDERER, "setSortingLayer"},
            {GLINE_RENDERER, "updateMeshData"},
            {GMESH_CONTAINER, "getMesh"},
            {GMESH_BASE, "getIndices"},
            {FLOAT_BUFFER, "l"},
            {INT_BUFFER, "l"},
            {CCOLOR, "getRed"}
        };
        for (final String[] check : checks) {
            try {
                final Class<?> type = Class.forName(check[0].replace('/', '.'), false, loader);
                boolean found = false;
                for (final Method method : type.getMethods()) {
                    if (method.getName().equals(check[1])) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return false;
            }
        }
        return true;
    }

    private static byte[] generateOverlayBytecode(final String internalName, final ClassLoader hostLoader) {
        final ClassWriter cw = HostClassWriters.newFrameWriter(hostLoader);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName,
                null,
                OBJECT_INTERNAL,
                new String[] {OVERLAY_OPS_INTERNAL});
        emitDefaultConstructor(cw);
        emitUnaryVirtual(cw, "entityGetTransform", DESC_OBJ_TO_OBJ, GENTITY, "getTransform", "()L" + GTRANSFORM + ";");
        emitUnaryVirtual(
                cw, "transformGetPosition", DESC_OBJ_TO_OBJ, GTRANSFORM, "getPosition", "()L" + GVECTOR3 + ";");
        emitSetterFloatVirtual(cw, "vec3SetZ", GVECTOR3, "setZ");
        emitUnaryVirtual(
                cw,
                "simpleMeshEntityGetContainer",
                DESC_OBJ_TO_OBJ,
                GSIMPLE_MESH_ENTITY,
                "getMeshContainer",
                "()L" + GMESH_CONTAINER + ";");
        emitUnaryVirtual(
                cw,
                "simpleMeshEntityGetRenderer",
                DESC_OBJ_TO_OBJ,
                GSIMPLE_MESH_ENTITY,
                "getMeshRenderer",
                "()L" + GMESH_RENDERER + ";");
        emitUnaryVirtual(
                cw, "meshContainerGetMesh", DESC_OBJ_TO_OBJ, GMESH_CONTAINER, "getMesh", "()L" + GMESH_BASE + ";");
        emitUnaryVirtual(
                cw, "rendererGetMaterial", DESC_OBJ_TO_OBJ, AG_RENDERER, "getMaterial", "()L" + GMATERIAL + ";");
        emitUnaryVirtual(
                cw,
                "simpleLinesEntityGetRenderer",
                DESC_OBJ_TO_OBJ,
                GSIMPLE_LINES_ENTITY,
                "getLineRenderer",
                "()L" + GLINE_RENDERER + ";");
        emitBinaryVoidVirtual(
                cw,
                "rendererSetSortingLayer",
                AG_RENDERER,
                "setSortingLayer",
                "(L" + SORTING_LAYER + ";)V",
                SORTING_LAYER);
        emitUnaryVirtual(cw, "lineRendererGetStep", DESC_OBJ_TO_INT, GLINE_RENDERER, "getStep", "()I", Opcodes.IRETURN);
        emitSetterIntVirtual(cw, "lineRendererSetStep", GLINE_RENDERER, "setStep");
        emitUnaryVirtual(cw, "lineRendererGetPositions", DESC_OBJ_TO_FLOATS, GLINE_RENDERER, "getPositions", "()[F");
        emitBinaryVoidVirtual(
                cw, "lineRendererSetPositions", DESC_OBJ_FLOATS_VOID, GLINE_RENDERER, "setPositions", "([F)V", null);
        emitUnaryVirtual(cw, "lineRendererGetIndices", DESC_OBJ_TO_INTS, GLINE_RENDERER, "getIndices", "()[I");
        emitBinaryVoidVirtual(
                cw, "lineRendererSetIndices", DESC_OBJ_INTS_VOID, GLINE_RENDERER, "setIndices", "([I)V", null);
        emitVoidVirtual(cw, "lineRendererUpdateMeshData", GLINE_RENDERER, "updateMeshData", "()V");
        emitUnaryVirtual(
                cw,
                "lineRendererGetPositionsBuffer",
                DESC_OBJ_TO_OBJ,
                GLINE_RENDERER,
                "getPositionsBuffer",
                "()L" + FLOAT_BUFFER + ";");
        emitUnaryVirtual(cw, "meshGetPositions", DESC_OBJ_TO_FLOATS, GMESH_BASE, "getPositions", "()[F");
        emitUnaryVirtual(cw, "meshGetIndices", DESC_OBJ_TO_INTS, GMESH_BASE, "getIndices", "()[I");
        emitUnaryVirtual(
                cw,
                "meshGetPositionsBuffer",
                DESC_OBJ_TO_OBJ,
                GMESH_BASE,
                "getPositionsBuffer",
                "()L" + FLOAT_BUFFER + ";");
        emitUnaryVirtual(
                cw, "meshGetIndicesBuffer", DESC_OBJ_TO_OBJ, GMESH_BASE, "getIndicesBuffer", "()L" + INT_BUFFER + ";");
        emitVoidVirtual(cw, "floatBufferUpload", FLOAT_BUFFER, "l", "()V");
        emitVoidVirtual(cw, "intBufferUpload", INT_BUFFER, "l", "()V");
        emitUnaryVirtual(cw, "colorGetRed", DESC_OBJ_TO_FLOAT, CCOLOR, "getRed", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "colorGetGreen", DESC_OBJ_TO_FLOAT, CCOLOR, "getGreen", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "colorGetBlue", DESC_OBJ_TO_FLOAT, CCOLOR, "getBlue", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "colorGetAlpha", DESC_OBJ_TO_FLOAT, CCOLOR, "getAlpha", "()F", Opcodes.FRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean hasAllRequiredHostClasses(final ClassLoader loader) {
        for (final String[] check : REQUIRED_HOST_CLASSES) {
            try {
                final Class<?> type = Class.forName(check[0].replace('/', '.'), false, loader);
                boolean found = false;
                for (final Method method : type.getMethods()) {
                    if (method.getName().equals(check[1])) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return false;
            }
        }
        return true;
    }

    private static byte[] generateBytecode(final String internalName, final ClassLoader hostLoader) {
        final ClassWriter cw = HostClassWriters.newFrameWriter(hostLoader);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName,
                null,
                OBJECT_INTERNAL,
                new String[] {OPS_INTERNAL});

        emitDefaultConstructor(cw);
        emitModelAccessors(cw);
        emitDeformerAccessors(cw);
        emitMeshAccessors(cw);
        emitPartGlueAndParamAccessors(cw);
        emitMorphAndFormAccessors(cw);
        emitUpdateDeformerTotals(cw);
        emitTransformDeformerIfChild(cw);
        emitPrepareAndTransformArtMesh(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitDefaultConstructor(final ClassWriter cw) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", OBJECT_CTORS_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJECT_INTERNAL, "<init>", OBJECT_CTORS_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitModelAccessors(final ClassWriter cw) {
        emitUnaryVirtual(cw, "modelGetAllDeformers", DESC_OBJ_TO_LIST, CMODEL, "getAllDeformers", "()Ljava/util/List;");
        emitUnaryVirtual(cw, "modelGetAllArtMeshes", DESC_OBJ_TO_LIST, CMODEL, "getAllArtMeshes", "()Ljava/util/List;");
        emitUnaryVirtual(cw, "modelGetAllParts", DESC_OBJ_TO_LIST, CMODEL, "getAllParts", "()Ljava/util/List;");
        emitUnaryVirtual(cw, "modelGetAllArtPaths", DESC_OBJ_TO_LIST, CMODEL, "getAllArtPaths", "()Ljava/util/List;");
        emitUnaryVirtual(cw, "modelGetAllAffecters", DESC_OBJ_TO_LIST, CMODEL, "getAllAffecters", "()Ljava/util/List;");
        emitUnaryVirtual(
                cw, "modelGetParameterSet", DESC_OBJ_TO_OBJ, CMODEL, "getParameterSet", "()L" + CPARAM_SET + ";");
        emitBinaryVirtual(
                cw,
                "modelGetDeformer",
                DESC_OBJ_OBJ_OBJ,
                CMODEL,
                "getDeformer",
                "(L" + CDEFORMER_GUID + ";)L" + AC_DEFORMER + ";",
                CDEFORMER_GUID);
        emitUnaryVirtual(
                cw,
                "modelGetLastUpdatedTime",
                DESC_OBJ_TO_LONG,
                CMODEL,
                "getLastUpdatedTimeMSec",
                "()J",
                Opcodes.LRETURN);
        emitSetterLongVirtual(cw, "modelSetLastUpdatedTime", CMODEL, "setLastUpdatedTimeMSec");
        emitUnaryVirtual(
                cw,
                "paramSetGetUpdateVersion",
                DESC_OBJ_TO_INT,
                CPARAM_SET,
                "getUpdateVersion",
                "()I",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw, "paramSetGetParameters", DESC_OBJ_TO_LIST, CPARAM_SET, "getParameters", "()Ljava/util/List;");
    }

    private static void emitDeformerAccessors(final ClassWriter cw) {
        emitUnaryVirtual(
                cw, "deformerGetSource", DESC_OBJ_TO_OBJ, AC_DEFORMER, "getSource", "()L" + AC_DEFORMER_SRC + ";");
        emitUnaryVirtual(
                cw,
                "sourceGetUpdateVersion",
                DESC_OBJ_TO_INT,
                AC_DEFORMER_SRC,
                "getUpdateVersion",
                "()I",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "deformerGetCopiedUpdateVersion",
                DESC_OBJ_TO_INT,
                AC_DEFORMER,
                "getCopiedUpdateVersion",
                "()I",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "deformerGetDirtyDeformedForm",
                DESC_OBJ_TO_BOOL,
                AC_DEFORMER,
                "getDirtyDeformedForm",
                "()Z",
                Opcodes.IRETURN);
        emitSetterBoolVirtual(cw, "deformerSetDirtyDeformedForm", AC_DEFORMER, "setDirtyDeformedForm");
        emitUnaryVirtual(
                cw,
                "deformerIsParamOutsideHierarchy",
                DESC_OBJ_TO_BOOL,
                AC_CONTROLLABLE,
                "isParameterOutsideInHierarchy",
                "()Z",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "deformerGetInterpolatedForm",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER,
                "getInterpolatedForm",
                "()L" + AC_DEFORMER_FORM + ";");
        emitUnaryVirtual(
                cw,
                "deformerGetDeformedForm",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER,
                "getDeformedForm",
                "()L" + AC_DEFORMER_FORM + ";");
        emitUnaryVirtual(
                cw,
                "deformerGetAffectedForm",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER,
                "getAffectedForm",
                "()L" + AC_DEFORMER_FORM + ";");
        emitUnaryVirtual(
                cw,
                "deformerGetCalculatedForm",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER,
                "getCalculatedForm",
                "()L" + AC_DEFORMER_FORM + ";");
        emitUnaryVirtual(
                cw,
                "deformerGetLocalAnimatedForm",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER,
                "getLocalAnimatedForm",
                "()L" + AC_DEFORMER_FORM + ";");
        emitUnaryVirtual(
                cw,
                "deformerGetAnimatedForm",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER,
                "getAnimatedForm",
                "()L" + AC_DEFORMER_FORM + ";");
        emitUnaryVirtual(
                cw,
                "deformerGetPreDeformForm",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE,
                "getPreDeformForm",
                "()L" + AC_FORM + ";");
        emitUnaryVirtual(
                cw,
                "deformerGetPreClampMorphedForm",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE,
                "getPreClampMorphedForm",
                "()L" + AC_FORM + ";");
        emitBinaryVoidVirtual(
                cw,
                "deformerSetPreClampMorphedForm",
                AC_CONTROLLABLE,
                "setPreClampMorphedForm",
                "(L" + AC_FORM + ";)V",
                AC_FORM);
        emitUnaryVirtual(cw, "deformerGetGuid", DESC_OBJ_TO_OBJ, AC_DEFORMER, "getGuid", "()L" + CDEFORMER_GUID + ";");
        emitUnaryVirtual(
                cw,
                "deformerGetTargetGuid",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER,
                "getTargetDeformerGuid",
                "()L" + CDEFORMER_GUID + ";");
        emitDeformerSetDeformedForm(cw);
        emitUnaryVirtual(cw, "warpFormGetPositions", DESC_OBJ_TO_FLOATS, WARP_FORM, "getPositions", "()[F");
        emitBinaryVoidVirtual(
                cw, "warpFormSetPositions", DESC_OBJ_FLOATS_VOID, WARP_FORM, "setPositions", "([F)V", null);
        emitUnaryVirtual(cw, "rotFormGetOriginX", DESC_OBJ_TO_FLOAT, ROT_FORM, "getOriginX", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "rotFormGetOriginY", DESC_OBJ_TO_FLOAT, ROT_FORM, "getOriginY", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "rotFormGetScale", DESC_OBJ_TO_FLOAT, ROT_FORM, "getScale", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "rotFormGetAngle", DESC_OBJ_TO_FLOAT, ROT_FORM, "getAngle", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "rotFormIsReflectX", DESC_OBJ_TO_BOOL, ROT_FORM, "isReflectX", "()Z", Opcodes.IRETURN);
        emitUnaryVirtual(cw, "rotFormIsReflectY", DESC_OBJ_TO_BOOL, ROT_FORM, "isReflectY", "()Z", Opcodes.IRETURN);
        emitSetterFloatVirtual(cw, "rotFormSetOriginX", ROT_FORM, "setOriginX");
        emitSetterFloatVirtual(cw, "rotFormSetOriginY", ROT_FORM, "setOriginY");
        emitSetterFloatVirtual(cw, "rotFormSetScale", ROT_FORM, "setScale");
        emitSetterFloatVirtual(cw, "rotFormSetAngle", ROT_FORM, "setAngle");
        emitSetterBoolVirtual(cw, "rotFormSetReflectX", ROT_FORM, "setReflectX");
        emitSetterBoolVirtual(cw, "rotFormSetReflectY", ROT_FORM, "setReflectY");
        emitUnaryVirtual(
                cw,
                "sourceGetKeyforms",
                DESC_OBJ_TO_LIST,
                AC_CONTROLLABLE_SRC,
                "getKeyforms",
                "()L" + CARRAY_LIST + ";");
        emitUnaryVirtual(
                cw,
                "sourceGetDefaultKeyForm",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE_SRC,
                "getDefaultKeyForm",
                "()L" + AC_FORM + ";");
        emitUnaryVirtual(
                cw,
                "sourceGetExtKeyformGrid",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE_SRC,
                "getExtendedKeyformGridSourceIfAvailable",
                "()L" + KEYFORM_GRID_SRC + ";");
        emitUnaryVirtual(
                cw,
                "sourceGetKeyformGrid",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE_SRC,
                "getKeyformGridSource",
                "()L" + KEYFORM_GRID_SRC + ";");
        emitUnaryVirtual(
                cw,
                "sourceGetCoordType",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE_SRC,
                "getCoordType",
                "()L" + COORD_TYPE + ";");
        emitUnaryVirtual(
                cw,
                "sourceGetMorphTargets",
                DESC_OBJ_TO_LIST,
                AC_CONTROLLABLE_SRC,
                "getKeyformMorphTargets",
                "()Ljava/util/List;");
        emitUnaryVirtual(
                cw,
                "sourceGetMorphSet",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE_SRC,
                "getKeyformMorphTargetSet",
                "()L" + MORPH_SET + ";");
        emitUnaryVirtual(
                cw,
                "sourceGetExtMorphSet",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE_SRC,
                "getExtendedKeyformMorphTargetSetIfAvailable",
                "()L" + EXT_MORPH_SET + ";");
        emitUnaryVirtual(
                cw,
                "controllableSourceIsVisible",
                DESC_OBJ_TO_BOOL,
                AC_CONTROLLABLE_SRC,
                "isVisible",
                "()Z",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "controllableSourceIsVisibleHierarchy",
                DESC_OBJ_TO_BOOL,
                AC_CONTROLLABLE_SRC,
                "isVisibleInHierarchy",
                "()Z",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "controllableSourceGetParent",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE_SRC,
                "getParent",
                "()L" + CPART_SRC + ";");
        emitUnaryVirtual(
                cw,
                "controllableSourceGetParentGuid",
                DESC_OBJ_TO_OBJ,
                AC_CONTROLLABLE_SRC,
                "getParentGuid",
                "()L" + CPART_GUID + ";");
    }

    private static void emitMeshAccessors(final ClassWriter cw) {
        emitUnaryVirtual(cw, "meshIsInvisible", DESC_OBJ_TO_BOOL, ART_MESH, "isInvisible", "()Z", Opcodes.IRETURN);
        emitUnaryVirtual(cw, "meshGetSource", DESC_OBJ_TO_OBJ, ART_MESH, "getSource", "()L" + ART_MESH_SRC + ";");
        emitUnaryVirtual(
                cw,
                "meshSourceGetMeshVersion",
                DESC_OBJ_TO_INT,
                ART_MESH_SRC,
                "getMeshVersion",
                "()I",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "meshGetCopiedMeshVersion",
                DESC_OBJ_TO_INT,
                ART_MESH,
                "getCopiedMeshVersion",
                "()I",
                Opcodes.IRETURN);
        emitUnaryVirtual(cw, "meshGetDrawOrder", DESC_OBJ_TO_INT, ART_MESH, "getDrawOrder", "()I", Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "meshGetInterpolatedForm",
                DESC_OBJ_TO_OBJ,
                ART_MESH,
                "getInterpolatedForm",
                "()L" + ART_MESH_FORM + ";");
        emitUnaryVirtual(
                cw, "meshGetDeformedForm", DESC_OBJ_TO_OBJ, ART_MESH, "getDeformedForm", "()L" + ART_MESH_FORM + ";");
        emitUnaryVirtual(
                cw, "meshGetAffectedForm", DESC_OBJ_TO_OBJ, ART_MESH, "getAffectedForm", "()L" + ART_MESH_FORM + ";");
        emitUnaryVirtual(
                cw,
                "meshGetCalculatedForm",
                DESC_OBJ_TO_OBJ,
                ART_MESH,
                "getCalculatedForm",
                "()L" + ART_MESH_FORM + ";");
        emitUnaryVirtual(
                cw,
                "meshGetLocalAnimatedForm",
                DESC_OBJ_TO_OBJ,
                ART_MESH,
                "getLocalAnimatedForm",
                "()L" + ART_MESH_FORM + ";");
        emitUnaryVirtual(
                cw, "meshGetAnimatedForm", DESC_OBJ_TO_OBJ, ART_MESH, "getAnimatedForm", "()L" + ART_MESH_FORM + ";");
        emitUnaryVirtual(cw, "meshFormGetPositions", DESC_OBJ_TO_FLOATS, ART_MESH_FORM, "getPositions", "()[F");
        emitUnaryVirtual(
                cw, "meshGetContainer", DESC_OBJ_TO_OBJ, ART_MESH, "getMeshContainer", "()L" + GMESH_CONTAINER + ";");
        emitUnaryVirtual(
                cw, "meshContainerGetMesh", DESC_OBJ_TO_OBJ, GMESH_CONTAINER, "getMesh", "()L" + GMESH_BASE + ";");
        emitUnaryVirtual(cw, "meshBaseGetPositions", DESC_OBJ_TO_FLOATS, GMESH_BASE, "getPositions", "()[F");
        emitUnaryVirtual(cw, "meshGetGuid", DESC_OBJ_TO_OBJ, ART_MESH, "getGuid", "()L" + CDRAWABLE_GUID + ";");
        emitUnaryVirtual(
                cw,
                "meshGetTargetGuid",
                DESC_OBJ_TO_OBJ,
                ART_MESH,
                "getTargetDeformerGuid",
                "()L" + CDEFORMER_GUID + ";");
        emitUnaryVirtual(
                cw, "meshFormGetOpacity", DESC_OBJ_TO_FLOAT, ART_MESH_FORM, "getOpacity", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(
                cw,
                "meshFormGetMultiplyColor",
                DESC_OBJ_TO_OBJ,
                ART_MESH_FORM,
                "getMultiplyColor",
                "()" + CFLOAT_COLOR_DESC);
        emitUnaryVirtual(
                cw,
                "meshFormGetScreenColor",
                DESC_OBJ_TO_OBJ,
                ART_MESH_FORM,
                "getScreenColor",
                "()" + CFLOAT_COLOR_DESC);
    }

    private static void emitPartGlueAndParamAccessors(final ClassWriter cw) {
        emitUnaryVirtual(cw, "partGetSource", DESC_OBJ_TO_OBJ, CPART, "getSource", "()L" + CPART_SRC + ";");
        emitUnaryVirtual(cw, "partSourceIsVisible", DESC_OBJ_TO_BOOL, CPART_SRC, "isVisible", "()Z", Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "partSourceIsVisibleHierarchy",
                DESC_OBJ_TO_BOOL,
                CPART_SRC,
                "isVisibleInHierarchy",
                "()Z",
                Opcodes.IRETURN);
        emitUnaryVirtual(cw, "partGetDrawOrder", DESC_OBJ_TO_INT, CPART, "getDrawOrder", "()I", Opcodes.IRETURN);
        emitUnaryVirtual(
                cw, "partGetInterpolatedForm", DESC_OBJ_TO_OBJ, CPART, "getInterpolatedForm", "()L" + AC_FORM + ";");
        emitUnaryVirtual(cw, "glueGetSource", DESC_OBJ_TO_OBJ, CGLUE, "getSource", "()L" + CGLUE_SRC + ";");
        emitUnaryVirtual(
                cw, "glueGetInterpolatedForm", DESC_OBJ_TO_OBJ, CGLUE, "getInterpolatedForm", "()L" + CGLUE_FORM + ";");
        emitUnaryVirtual(
                cw,
                "glueGetCachedEditVersion",
                DESC_OBJ_TO_INT,
                AC_AFFECTER,
                "getCached_editVersion",
                "()I",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "glueSourceGetEditVersion",
                DESC_OBJ_TO_INT,
                CGLUE_SRC,
                "getEditVersion$cubism",
                "()I",
                Opcodes.IRETURN);
        emitUnaryVirtual(
                cw, "glueFormGetIntensity", DESC_OBJ_TO_FLOAT, CGLUE_FORM, "getIntensity", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(
                cw,
                "glueSourceGetMeshAGuid",
                DESC_OBJ_TO_OBJ,
                CGLUE_SRC,
                "getTargetArtMeshA_guid",
                "()L" + CDRAWABLE_GUID + ";");
        emitUnaryVirtual(
                cw,
                "glueSourceGetMeshBGuid",
                DESC_OBJ_TO_OBJ,
                CGLUE_SRC,
                "getTargetArtMeshB_guid",
                "()L" + CDRAWABLE_GUID + ";");
        emitUnaryVirtual(cw, "paramGetValue", DESC_OBJ_TO_FLOAT, CPARAM, "getValue", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "paramGetGuid", DESC_OBJ_TO_OBJ, CPARAM, "getGuid", "()L" + CPARAM_GUID + ";");
        emitUnaryVirtual(cw, "paramGetSource", DESC_OBJ_TO_OBJ, CPARAM, "getSource", "()L" + CPARAM_SRC + ";");
        emitUnaryVirtual(cw, "paramSourceGetGuid", DESC_OBJ_TO_OBJ, CPARAM_SRC, "getGuid", "()L" + CPARAM_GUID + ";");
        emitUnaryVirtual(
                cw, "paramSourceGetMaxValue", DESC_OBJ_TO_FLOAT, CPARAM_SRC, "getMaxValue", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "colorGetR", DESC_OBJ_TO_FLOAT, CFLOAT_COLOR, "getR", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "colorGetG", DESC_OBJ_TO_FLOAT, CFLOAT_COLOR, "getG", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "colorGetB", DESC_OBJ_TO_FLOAT, CFLOAT_COLOR, "getB", "()F", Opcodes.FRETURN);
        emitUnaryVirtual(cw, "colorGetA", DESC_OBJ_TO_FLOAT, CFLOAT_COLOR, "getA", "()F", Opcodes.FRETURN);
        emitSetterFloatVirtual(cw, "colorSetR", CFLOAT_COLOR, "setR");
        emitSetterFloatVirtual(cw, "colorSetG", CFLOAT_COLOR, "setG");
        emitSetterFloatVirtual(cw, "colorSetB", CFLOAT_COLOR, "setB");
        emitSetterFloatVirtual(cw, "colorSetA", CFLOAT_COLOR, "setA");
        emitUnaryVirtual(cw, "floatColorClamp", DESC_OBJ_TO_OBJ, CFLOAT_COLOR, "clamp", "()" + CFLOAT_COLOR_DESC);
    }

    private static void emitMorphAndFormAccessors(final ClassWriter cw) {
        emitUnaryVirtual(cw, "formGetSource", DESC_OBJ_TO_OBJ, AC_FORM, "getSource", "()L" + AC_CONTROLLABLE_SRC + ";");
        emitUnaryVirtual(cw, "formGetEditVersion", DESC_OBJ_TO_INT, AC_FORM, "getEditVersion", "()I", Opcodes.IRETURN);
        emitUnaryVirtual(
                cw, "formIsParameterOutside", DESC_OBJ_TO_BOOL, AC_FORM, "isParameterOutside", "()Z", Opcodes.IRETURN);
        emitUnaryVirtual(
                cw,
                "deformerFormGetOpacity",
                DESC_OBJ_TO_FLOAT,
                AC_DEFORMER_FORM,
                "getOpacity",
                "()F",
                Opcodes.FRETURN);
        emitUnaryVirtual(
                cw,
                "deformerFormGetMultiplyColor",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER_FORM,
                "getMultiplyColor",
                "()" + CFLOAT_COLOR_DESC);
        emitUnaryVirtual(
                cw,
                "deformerFormGetScreenColor",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER_FORM,
                "getScreenColor",
                "()" + CFLOAT_COLOR_DESC);
        emitSetterFloatVirtual(cw, "deformerFormSetOpacity", AC_DEFORMER_FORM, "setOpacity");
        emitUnaryVirtual(
                cw,
                "deformerFormGetCoordType",
                DESC_OBJ_TO_OBJ,
                AC_DEFORMER_FORM,
                "getCoordType",
                "()L" + COORD_TYPE + ";");
        emitBinaryVoidVirtual(
                cw,
                "deformerFormSetCoordType",
                AC_DEFORMER_FORM,
                "setCoordType",
                "(L" + COORD_TYPE + ";)V",
                COORD_TYPE);

        // formZero(Object form): ((IBlendable) form).zero()
        final MethodVisitor z = cw.visitMethod(Opcodes.ACC_PUBLIC, "formZero", "(Ljava/lang/Object;)V", null, null);
        z.visitCode();
        z.visitVarInsn(Opcodes.ALOAD, 1);
        z.visitTypeInsn(Opcodes.CHECKCAST, IBLENDABLE);
        z.visitMethodInsn(Opcodes.INVOKEINTERFACE, IBLENDABLE, "zero", "()L" + IBLENDABLE + ";", true);
        z.visitInsn(Opcodes.POP);
        z.visitInsn(Opcodes.RETURN);
        z.visitMaxs(0, 0);
        z.visitEnd();

        // formBlend(Object dst, Object src, float weight)
        final MethodVisitor b = cw.visitMethod(Opcodes.ACC_PUBLIC, "formBlend", DESC_OBJ_OBJ_FLOAT_VOID, null, null);
        b.visitCode();
        b.visitVarInsn(Opcodes.ALOAD, 1);
        b.visitTypeInsn(Opcodes.CHECKCAST, IBLENDABLE);
        b.visitVarInsn(Opcodes.ALOAD, 2);
        b.visitTypeInsn(Opcodes.CHECKCAST, IBLENDABLE);
        b.visitVarInsn(Opcodes.FLOAD, 3);
        b.visitMethodInsn(Opcodes.INVOKEINTERFACE, IBLENDABLE, "blend", "(L" + IBLENDABLE + ";F)V", true);
        b.visitInsn(Opcodes.RETURN);
        b.visitMaxs(0, 0);
        b.visitEnd();

        // formPostBlend(Object form)
        final MethodVisitor pb =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "formPostBlend", "(Ljava/lang/Object;)V", null, null);
        pb.visitCode();
        pb.visitVarInsn(Opcodes.ALOAD, 1);
        pb.visitTypeInsn(Opcodes.CHECKCAST, IBLENDABLE);
        pb.visitMethodInsn(Opcodes.INVOKEINTERFACE, IBLENDABLE, "postBlend", "()V", true);
        pb.visitInsn(Opcodes.RETURN);
        pb.visitMaxs(0, 0);
        pb.visitEnd();

        // formIsCompatible(Object a, Object b)
        final MethodVisitor comp =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "formIsCompatible", DESC_OBJ_OBJ_BOOL, null, null);
        comp.visitCode();
        comp.visitVarInsn(Opcodes.ALOAD, 1);
        comp.visitTypeInsn(Opcodes.CHECKCAST, AC_FORM);
        comp.visitVarInsn(Opcodes.ALOAD, 2);
        comp.visitTypeInsn(Opcodes.CHECKCAST, AC_FORM);
        comp.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_FORM, "isCompatible", "(L" + AC_FORM + ";)Z", false);
        comp.visitInsn(Opcodes.IRETURN);
        comp.visitMaxs(0, 0);
        comp.visitEnd();

        // formDeepCopy(Object form, Object copyInfo)
        final MethodVisitor dc = cw.visitMethod(Opcodes.ACC_PUBLIC, "formDeepCopy", DESC_OBJ_OBJ_OBJ, null, null);
        dc.visitCode();
        dc.visitVarInsn(Opcodes.ALOAD, 1);
        dc.visitTypeInsn(Opcodes.CHECKCAST, ICOPYABLE);
        dc.visitVarInsn(Opcodes.ALOAD, 2);
        dc.visitTypeInsn(Opcodes.CHECKCAST, COPY_INFO);
        dc.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, ICOPYABLE, "deepCopy", "(L" + COPY_INFO + ";)L" + ICOPYABLE + ";", true);
        dc.visitInsn(Opcodes.ARETURN);
        dc.visitMaxs(0, 0);
        dc.visitEnd();

        // formReinit(Object dst, Object src, Object copyInfo)
        final MethodVisitor ri = cw.visitMethod(Opcodes.ACC_PUBLIC, "formReinit", DESC_OBJ_OBJ_OBJ_VOID, null, null);
        ri.visitCode();
        ri.visitVarInsn(Opcodes.ALOAD, 1);
        ri.visitTypeInsn(Opcodes.CHECKCAST, ICOPYABLE);
        ri.visitVarInsn(Opcodes.ALOAD, 2);
        ri.visitTypeInsn(Opcodes.CHECKCAST, ICOPYABLE);
        ri.visitVarInsn(Opcodes.ALOAD, 3);
        ri.visitTypeInsn(Opcodes.CHECKCAST, COPY_INFO);
        ri.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, ICOPYABLE, "reinit", "(L" + ICOPYABLE + ";L" + COPY_INFO + ";)V", true);
        ri.visitInsn(Opcodes.RETURN);
        ri.visitMaxs(0, 0);
        ri.visitEnd();
    }

    private static void emitDeformerSetDeformedForm(final ClassWriter cw) {
        final MethodVisitor mv =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "deformerSetDeformedForm", DESC_OBJ_OBJ_VOID, null, null);
        mv.visitCode();
        final Label rotLabel = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, WARP_DEFORMER);
        mv.visitJumpInsn(Opcodes.IFEQ, rotLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, WARP_DEFORMER);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, WARP_FORM);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WARP_DEFORMER, "setDeformedForm", "(L" + WARP_FORM + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(rotLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ROT_DEFORMER);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ROT_FORM);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ROT_DEFORMER, "setDeformedForm", "(L" + ROT_FORM + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitUpdateDeformerTotals(final ClassWriter cw) {
        // void updateDeformerTotals(Object d, Object parent, boolean isRotation)
        // Locals: 1=dObj, 2=parentObj, 3=isRotation
        // 4=d(ACDeformer), 5=localForm(ACDeformerForm), 6=localScale(F), 7=localOp(F),
        // 8=localMul, 9=localScr, 10=totalMul, 11=totalScr, 12=parent(ACDeformer),
        // 13=pMul, 14=pScr
        final MethodVisitor mv =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "updateDeformerTotals", DESC_OBJ_OBJ_BOOL_VOID, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, AC_DEFORMER);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "getInterpolatedForm", "()L" + AC_DEFORMER_FORM + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        final Label notRot = new Label();
        final Label scaleDone = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IFEQ, notRot);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ROT_FORM);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ROT_FORM, "getScale", "()F", false);
        mv.visitJumpInsn(Opcodes.GOTO, scaleDone);
        mv.visitLabel(notRot);
        mv.visitLdcInsn(UNIT_SCALE);
        mv.visitLabel(scaleDone);
        mv.visitVarInsn(Opcodes.FSTORE, 6);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER_FORM, "getOpacity", "()F", false);
        mv.visitVarInsn(Opcodes.FSTORE, 7);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AC_DEFORMER_FORM, "getMultiplyColor", "()" + CFLOAT_COLOR_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 8);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER_FORM, "getScreenColor", "()" + CFLOAT_COLOR_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 9);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "getCalculatedTotalMultiplyColor", "()" + CFLOAT_COLOR_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 10);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "getCalculatedTotalScreenColor", "()" + CFLOAT_COLOR_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 11);

        final Label hasParent = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNONNULL, hasParent);

        // Root deformer: no parent
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_CONTROLLABLE, "setParameterOutsideAnyParents", "(Z)V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.FLOAD, 6);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "setCalculatedTotalScale", "(F)V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.FLOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "setCalculatedTotalOpacity", "(F)V", false);

        emitCopyFloatColor(mv, 10, 8);
        emitCopyFloatColor(mv, 11, 9);
        mv.visitInsn(Opcodes.RETURN);

        // Child deformer: has parent
        mv.visitLabel(hasParent);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, AC_DEFORMER);
        mv.visitVarInsn(Opcodes.ASTORE, 12);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 12);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_CONTROLLABLE, "isParameterOutsideInHierarchy", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_CONTROLLABLE, "setParameterOutsideAnyParents", "(Z)V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.FLOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 12);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "getCalculatedTotalScale", "()F", false);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "setCalculatedTotalScale", "(F)V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.FLOAD, 7);
        mv.visitVarInsn(Opcodes.ALOAD, 12);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "getCalculatedTotalOpacity", "()F", false);
        mv.visitInsn(Opcodes.FMUL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "setCalculatedTotalOpacity", "(F)V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 12);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "getCalculatedTotalMultiplyColor", "()" + CFLOAT_COLOR_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 13);
        emitMultiplyFloatColor(mv, 10, 8, 13);

        mv.visitVarInsn(Opcodes.ALOAD, 12);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "getCalculatedTotalScreenColor", "()" + CFLOAT_COLOR_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 14);
        emitScreenFloatColor(mv, 11, 9, 14);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitCopyFloatColor(final MethodVisitor mv, final int dstSlot, final int srcSlot) {
        emitColorChannelCopy(mv, dstSlot, srcSlot, "getA", "setA");
        emitColorChannelCopy(mv, dstSlot, srcSlot, "getR", "setR");
        emitColorChannelCopy(mv, dstSlot, srcSlot, "getG", "setG");
        emitColorChannelCopy(mv, dstSlot, srcSlot, "getB", "setB");
    }

    private static void emitColorChannelCopy(
            final MethodVisitor mv, final int dstSlot, final int srcSlot, final String getter, final String setter) {
        mv.visitVarInsn(Opcodes.ALOAD, dstSlot);
        mv.visitVarInsn(Opcodes.ALOAD, srcSlot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFLOAT_COLOR, getter, "()F", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFLOAT_COLOR, setter, "(F)V", false);
    }

    private static void emitMultiplyFloatColor(
            final MethodVisitor mv, final int dstSlot, final int localSlot, final int parentSlot) {
        emitColorChannelCopy(mv, dstSlot, localSlot, "getA", "setA");
        for (final String[] pair : new String[][] {{"getR", "setR"}, {"getG", "setG"}, {"getB", "setB"}}) {
            mv.visitVarInsn(Opcodes.ALOAD, dstSlot);
            mv.visitVarInsn(Opcodes.ALOAD, localSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFLOAT_COLOR, pair[0], "()F", false);
            mv.visitVarInsn(Opcodes.ALOAD, parentSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFLOAT_COLOR, pair[0], "()F", false);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFLOAT_COLOR, pair[1], "(F)V", false);
        }
    }

    private static void emitScreenFloatColor(
            final MethodVisitor mv, final int dstSlot, final int localSlot, final int parentSlot) {
        emitColorChannelCopy(mv, dstSlot, localSlot, "getA", "setA");
        for (final String[] pair : new String[][] {{"getR", "setR"}, {"getG", "setG"}, {"getB", "setB"}}) {
            // (l + p) - (l * p) preserving IEEE 754 float order
            mv.visitVarInsn(Opcodes.ALOAD, dstSlot);
            mv.visitVarInsn(Opcodes.ALOAD, localSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFLOAT_COLOR, pair[0], "()F", false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, parentSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFLOAT_COLOR, pair[0], "()F", false);
            mv.visitInsn(Opcodes.DUP_X1);
            mv.visitInsn(Opcodes.FADD);
            mv.visitInsn(Opcodes.DUP_X2);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CFLOAT_COLOR, pair[1], "(F)V", false);
        }
    }

    private static void emitTransformDeformerIfChild(final ClassWriter cw) {
        final MethodVisitor mv =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "transformDeformerIfChild", DESC_OBJ_OBJ_VOID, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, AC_DEFORMER);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        final Label skipTransform = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNULL, skipTransform);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, AC_DEFORMER);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "transformDeformer_testImpl", "(L" + AC_DEFORMER + ";)V", false);
        mv.visitLabel(skipTransform);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_DEFORMER, "setDirtyDeformedForm", "(Z)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitPrepareAndTransformArtMesh(final ClassWriter cw) {
        // void prepareAndTransformArtMesh(Object mesh, Object parent, Object canvasCoordType)
        // Locals: 1=meshObj, 2=parentObj, 3=coordTypeObj, 4=mesh, 5=parent, 6=preForm,
        // 7=out, 8=tr, 9=transformed
        final MethodVisitor mv =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "prepareAndTransformArtMesh", DESC_OBJ_OBJ_OBJ_VOID, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ART_MESH);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        final Label hasParent = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNONNULL, hasParent);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ART_MESH, "setDeformedForm", "(L" + ART_MESH_FORM + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(hasParent);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, AC_DEFORMER);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ART_MESH, "getInterpolatedForm", "()L" + ART_MESH_FORM + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 6);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ART_MESH, "getDeformedForm", "()L" + ART_MESH_FORM + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 7);

        final Label keepOut = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitJumpInsn(Opcodes.IFNULL, keepOut);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ART_MESH_FORM, "isCompatible", "(L" + AC_FORM + ";)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, keepOut);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 7);

        mv.visitLabel(keepOut);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                AC_DEFORMER,
                "getCreateLocalToCanvasTransform",
                "()L" + TRANSFORM_D + ";",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, 8);

        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ART_MESH_FORM,
                "transform",
                "(L" + TRANSFORM_D + ";L" + AC_FORM + ";)L" + ART_MESH_FORM + ";",
                false);
        mv.visitVarInsn(Opcodes.ASTORE, 9);

        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitTypeInsn(Opcodes.CHECKCAST, COORD_TYPE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ART_MESH_FORM, "setCoordType", "(L" + COORD_TYPE + ";)V", false);

        final Label sameOut = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, sameOut);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ART_MESH, "setDeformedForm", "(L" + ART_MESH_FORM + ";)V", false);

        mv.visitLabel(sameOut);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_CONTROLLABLE, "isParameterOutsideInHierarchy", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AC_CONTROLLABLE, "setParameterOutsideAnyParents", "(Z)V", false);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitUnaryVirtual(
            final ClassWriter cw,
            final String name,
            final String desc,
            final String owner,
            final String targetMethod,
            final String targetDesc) {
        emitUnaryVirtual(cw, name, desc, owner, targetMethod, targetDesc, Opcodes.ARETURN);
    }

    private static void emitUnaryVirtual(
            final ClassWriter cw,
            final String name,
            final String desc,
            final String owner,
            final String targetMethod,
            final String targetDesc,
            final int returnOpcode) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetMethod, targetDesc, false);
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitBinaryVirtual(
            final ClassWriter cw,
            final String name,
            final String desc,
            final String owner,
            final String targetMethod,
            final String targetDesc,
            final String argType) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, argType);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetMethod, targetDesc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitBinaryVoidVirtual(
            final ClassWriter cw,
            final String name,
            final String owner,
            final String targetMethod,
            final String targetDesc,
            final String argType) {
        emitBinaryVoidVirtual(cw, name, DESC_OBJ_OBJ_VOID, owner, targetMethod, targetDesc, argType);
    }

    private static void emitBinaryVoidVirtual(
            final ClassWriter cw,
            final String name,
            final String methodDesc,
            final String owner,
            final String targetMethod,
            final String targetDesc,
            final String argType) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, methodDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        if (argType != null) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, argType);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetMethod, targetDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitVoidVirtual(
            final ClassWriter cw, final String name, final String owner, final String targetMethod, final String desc) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, DESC_OBJ_VOID, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetMethod, desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSetterIntVirtual(
            final ClassWriter cw, final String name, final String owner, final String targetMethod) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, DESC_OBJ_INT_VOID, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetMethod, "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSetterBoolVirtual(
            final ClassWriter cw, final String name, final String owner, final String targetMethod) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, DESC_OBJ_BOOL_VOID, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetMethod, "(Z)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSetterFloatVirtual(
            final ClassWriter cw, final String name, final String owner, final String targetMethod) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, DESC_OBJ_FLOAT_VOID, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitVarInsn(Opcodes.FLOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetMethod, "(F)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSetterLongVirtual(
            final ClassWriter cw, final String name, final String owner, final String targetMethod) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, DESC_OBJ_LONG_VOID, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitVarInsn(Opcodes.LLOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, targetMethod, "(J)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static final class AccessorClassLoader extends ClassLoader {
        private static final String RUNTIME_PREFIX = "dev.turboism.";
        private final ClassLoader hostLoader;

        AccessorClassLoader(final ClassLoader hostLoader, final ClassLoader runtimeLoader) {
            super(runtimeLoader);
            this.hostLoader = hostLoader;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(RUNTIME_PREFIX)) {
                return super.loadClass(name, resolve);
            }
            final Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            try {
                return Class.forName(name, resolve, hostLoader);
            } catch (ClassNotFoundException ignored) {
                return super.loadClass(name, resolve);
            }
        }

        Class<?> define(final String binaryName, final byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    /** Direct host-accessor contract invoked by subtree coordination and topology caches. */
    public interface HostOps {

        /** Returns all deformers attached to {@code model}. */
        List<?> modelGetAllDeformers(Object model);

        /** Returns all ArtMeshes attached to {@code model}. */
        List<?> modelGetAllArtMeshes(Object model);

        /** Returns all parts attached to {@code model}. */
        List<?> modelGetAllParts(Object model);

        /** Returns all ArtPaths attached to {@code model}. */
        List<?> modelGetAllArtPaths(Object model);

        /** Returns all affecters attached to {@code model}. */
        List<?> modelGetAllAffecters(Object model);

        /** Returns the parameter set owned by {@code model}. */
        Object modelGetParameterSet(Object model);

        /** Resolves a deformer by {@code guid} on {@code model}. */
        Object modelGetDeformer(Object model, Object guid);

        /** Reads the last updated timestamp in milliseconds from {@code model}. */
        long modelGetLastUpdatedTime(Object model);

        /** Updates the last updated timestamp in milliseconds on {@code model}. */
        void modelSetLastUpdatedTime(Object model, long timeMs);

        /** Reads the update version counter from {@code paramSet}. */
        int paramSetGetUpdateVersion(Object paramSet);

        /** Returns all parameters in {@code paramSet}. */
        List<?> paramSetGetParameters(Object paramSet);

        /** Returns the source of {@code deformer}. */
        Object deformerGetSource(Object deformer);

        /** Reads the update version of a controllable {@code source}. */
        int sourceGetUpdateVersion(Object source);

        /** Reads the last synchronized update version of {@code deformer}. */
        int deformerGetCopiedUpdateVersion(Object deformer);

        /** Checks whether {@code deformer} requires deformed-form recalculation. */
        boolean deformerGetDirtyDeformedForm(Object deformer);

        /** Sets the dirty deformed-form flag on {@code deformer}. */
        void deformerSetDirtyDeformedForm(Object deformer, boolean dirty);

        /** Checks whether {@code deformer} or any ancestor lies outside parameters. */
        boolean deformerIsParamOutsideHierarchy(Object deformer);

        /** Returns the interpolated form of {@code deformer}. */
        Object deformerGetInterpolatedForm(Object deformer);

        /** Returns the deformed form of {@code deformer}. */
        Object deformerGetDeformedForm(Object deformer);

        /** Returns the affected form of {@code deformer}. */
        Object deformerGetAffectedForm(Object deformer);

        /** Returns the active calculated form of {@code deformer}. */
        Object deformerGetCalculatedForm(Object deformer);

        /** Returns the local animated form of {@code deformer}. */
        Object deformerGetLocalAnimatedForm(Object deformer);

        /** Returns the animated form of {@code deformer}. */
        Object deformerGetAnimatedForm(Object deformer);

        /** Returns the pre-deform form of {@code deformer}. */
        Object deformerGetPreDeformForm(Object deformer);

        /** Returns the pre-clamp morphed form of {@code deformer}. */
        Object deformerGetPreClampMorphedForm(Object deformer);

        /** Stores the pre-clamp morphed form on {@code deformer}. */
        void deformerSetPreClampMorphedForm(Object deformer, Object form);

        /** Returns the GUID of {@code deformer}. */
        Object deformerGetGuid(Object deformer);

        /** Returns the parent target deformer GUID of {@code deformer}. */
        Object deformerGetTargetGuid(Object deformer);

        /** Assigns the deformed form buffer on {@code deformer}. */
        void deformerSetDeformedForm(Object deformer, Object form);

        /** Returns the backing float coordinates of warp {@code form}. */
        float[] warpFormGetPositions(Object form);

        /** Replaces the backing float coordinates of warp {@code form}. */
        void warpFormSetPositions(Object form, float[] positions);

        /** Reads origin X coordinate of rotation deformer {@code form}. */
        float rotFormGetOriginX(Object form);

        /** Reads origin Y coordinate of rotation deformer {@code form}. */
        float rotFormGetOriginY(Object form);

        /** Reads the local scale of rotation deformer {@code form}. */
        float rotFormGetScale(Object form);

        /** Reads the local angle of rotation deformer {@code form}. */
        float rotFormGetAngle(Object form);

        /** Checks horizontal reflection flag on rotation deformer {@code form}. */
        boolean rotFormIsReflectX(Object form);

        /** Checks vertical reflection flag on rotation deformer {@code form}. */
        boolean rotFormIsReflectY(Object form);

        /** Writes origin X coordinate of rotation deformer {@code form}. */
        void rotFormSetOriginX(Object form, float originX);

        /** Writes origin Y coordinate of rotation deformer {@code form}. */
        void rotFormSetOriginY(Object form, float originY);

        /** Writes the local scale of rotation deformer {@code form}. */
        void rotFormSetScale(Object form, float scale);

        /** Writes the local angle of rotation deformer {@code form}. */
        void rotFormSetAngle(Object form, float angle);

        /** Writes horizontal reflection flag on rotation deformer {@code form}. */
        void rotFormSetReflectX(Object form, boolean reflectX);

        /** Writes vertical reflection flag on rotation deformer {@code form}. */
        void rotFormSetReflectY(Object form, boolean reflectY);

        /** Returns keyforms stored on {@code source}. */
        List<?> sourceGetKeyforms(Object source);

        /** Returns the default keyform on {@code source}. */
        Object sourceGetDefaultKeyForm(Object source);

        /** Returns the extended keyform grid source when active on {@code source}. */
        Object sourceGetExtKeyformGrid(Object source);

        /** Returns the base keyform grid source on {@code source}. */
        Object sourceGetKeyformGrid(Object source);

        /** Returns the coordinate type on {@code source}. */
        Object sourceGetCoordType(Object source);

        /** Returns morph targets declared on {@code source}. */
        List<?> sourceGetMorphTargets(Object source);

        /** Returns the base morph-target set on {@code source}. */
        Object sourceGetMorphSet(Object source);

        /** Returns the extended morph-target set when active on {@code source}. */
        Object sourceGetExtMorphSet(Object source);

        /** Checks direct visibility on controllable {@code source}. */
        boolean controllableSourceIsVisible(Object source);

        /** Checks hierarchical visibility on controllable {@code source}. */
        boolean controllableSourceIsVisibleHierarchy(Object source);

        /** Returns enclosing parent {@code CPartSource} of {@code source}, or {@code null}. */
        Object controllableSourceGetParent(Object source);

        /** Returns enclosing parent {@code CPartGuid} of {@code source}, or {@code null}. */
        Object controllableSourceGetParentGuid(Object source);

        /** Checks runtime invisibility on {@code mesh}. */
        boolean meshIsInvisible(Object mesh);

        /** Returns the source object of {@code mesh}. */
        Object meshGetSource(Object mesh);

        /** Reads the mesh structure version on {@code source}. */
        int meshSourceGetMeshVersion(Object source);

        /** Reads copied mesh structure version on {@code mesh}. */
        int meshGetCopiedMeshVersion(Object mesh);

        /** Reads draw order on {@code mesh}. */
        int meshGetDrawOrder(Object mesh);

        /** Returns the interpolated form of {@code mesh}. */
        Object meshGetInterpolatedForm(Object mesh);

        /** Returns the deformed form of {@code mesh}. */
        Object meshGetDeformedForm(Object mesh);

        /** Returns the affected form of {@code mesh}. */
        Object meshGetAffectedForm(Object mesh);

        /** Returns the calculated form of {@code mesh}. */
        Object meshGetCalculatedForm(Object mesh);

        /** Returns the local animated form of {@code mesh}. */
        Object meshGetLocalAnimatedForm(Object mesh);

        /** Returns the animated form of {@code mesh}. */
        Object meshGetAnimatedForm(Object mesh);

        /** Returns vertex positions backing ArtMesh {@code form}. */
        float[] meshFormGetPositions(Object form);

        /** Returns the mesh container component of {@code mesh}. */
        Object meshGetContainer(Object mesh);

        /** Returns the underlying GMesh from {@code container}. */
        Object meshContainerGetMesh(Object container);

        /** Returns vertex positions from underlying {@code meshBase}. */
        float[] meshBaseGetPositions(Object meshBase);

        /** Returns the GUID of {@code mesh}. */
        Object meshGetGuid(Object mesh);

        /** Returns the parent deformer GUID of {@code mesh}. */
        Object meshGetTargetGuid(Object mesh);

        /** Reads opacity on ArtMesh {@code form}. */
        float meshFormGetOpacity(Object form);

        /** Returns multiply color on ArtMesh {@code form}. */
        Object meshFormGetMultiplyColor(Object form);

        /** Returns screen color on ArtMesh {@code form}. */
        Object meshFormGetScreenColor(Object form);

        /** Returns the source object of {@code part}. */
        Object partGetSource(Object part);

        /** Checks direct visibility on {@code partSource}. */
        boolean partSourceIsVisible(Object partSource);

        /** Checks hierarchical visibility on {@code partSource}. */
        boolean partSourceIsVisibleHierarchy(Object partSource);

        /** Reads draw order on {@code part}. */
        int partGetDrawOrder(Object part);

        /** Returns the interpolated form of {@code part}. */
        Object partGetInterpolatedForm(Object part);

        /** Returns the source object of {@code glue}. */
        Object glueGetSource(Object glue);

        /** Returns the interpolated form of {@code glue}. */
        Object glueGetInterpolatedForm(Object glue);

        /** Reads cached edit version from {@code glue} affecter. */
        int glueGetCachedEditVersion(Object glue);

        /** Reads source edit version from {@code glueSource}. */
        int glueSourceGetEditVersion(Object glueSource);

        /** Reads glue intensity from {@code form}. */
        float glueFormGetIntensity(Object form);

        /** Returns the first ArtMesh GUID linked by {@code glueSource}. */
        Object glueSourceGetMeshAGuid(Object glueSource);

        /** Returns the second ArtMesh GUID linked by {@code glueSource}. */
        Object glueSourceGetMeshBGuid(Object glueSource);

        /** Reads parameter value from {@code param}. */
        float paramGetValue(Object param);

        /** Returns the GUID of {@code param}. */
        Object paramGetGuid(Object param);

        /** Returns the source of {@code param}. */
        Object paramGetSource(Object param);

        /** Returns the GUID of {@code paramSource}. */
        Object paramSourceGetGuid(Object paramSource);

        /** Reads the maximum parameter value from {@code paramSource}. */
        float paramSourceGetMaxValue(Object paramSource);

        /** Reads red channel from {@code floatColor}. */
        float colorGetR(Object floatColor);

        /** Reads green channel from {@code floatColor}. */
        float colorGetG(Object floatColor);

        /** Reads blue channel from {@code floatColor}. */
        float colorGetB(Object floatColor);

        /** Reads alpha channel from {@code floatColor}. */
        float colorGetA(Object floatColor);

        /** Writes red channel on {@code floatColor}. */
        void colorSetR(Object floatColor, float value);

        /** Writes green channel on {@code floatColor}. */
        void colorSetG(Object floatColor, float value);

        /** Writes blue channel on {@code floatColor}. */
        void colorSetB(Object floatColor, float value);

        /** Writes alpha channel on {@code floatColor}. */
        void colorSetA(Object floatColor, float value);

        /** Clamps {@code floatColor} channels to valid bounds. */
        Object floatColorClamp(Object floatColor);

        /** Returns the source owning {@code form}. */
        Object formGetSource(Object form);

        /** Reads edit version counter on {@code form}. */
        int formGetEditVersion(Object form);

        /** Checks whether {@code form} evaluated outside parameter bounds. */
        boolean formIsParameterOutside(Object form);

        /** Reads opacity on deformer {@code form}. */
        float deformerFormGetOpacity(Object form);

        /** Returns multiply color on deformer {@code form}. */
        Object deformerFormGetMultiplyColor(Object form);

        /** Returns screen color on deformer {@code form}. */
        Object deformerFormGetScreenColor(Object form);

        /** Writes opacity on deformer {@code form}. */
        void deformerFormSetOpacity(Object form, float opacity);

        /** Reads coordinate type on deformer {@code form}. */
        Object deformerFormGetCoordType(Object form);

        /** Writes coordinate type on deformer {@code form}. */
        void deformerFormSetCoordType(Object form, Object coordType);

        /** Resets blendable {@code form} accumulators to zero. */
        void formZero(Object form);

        /** Accumulates weighted {@code src} into {@code dst}. */
        void formBlend(Object dst, Object src, float weight);

        /** Finalizes accumulated blend state on {@code form}. */
        void formPostBlend(Object form);

        /** Checks structural compatibility between forms {@code a} and {@code b}. */
        boolean formIsCompatible(Object a, Object b);

        /** Deep-copies {@code form} using {@code copyInfo}. */
        Object formDeepCopy(Object form, Object copyInfo);

        /** Reinitializes {@code dst} from {@code src} using {@code copyInfo}. */
        void formReinit(Object dst, Object src, Object copyInfo);

        /** Computes cumulative scale, opacity, and tint colors for {@code deformer}. */
        void updateDeformerTotals(Object deformer, Object parent, boolean isRotation);

        /** Transforms child {@code deformer} using {@code parent} and clears dirty state. */
        void transformDeformerIfChild(Object deformer, Object parent);

        /** Ensures buffer compatibility and transforms {@code mesh} through {@code parent}. */
        void prepareAndTransformArtMesh(Object mesh, Object parent, Object canvasCoordType);
    }

    /**
     * Compiled monomorphic host accessor contract for hot deformer corner and grid-line overlays.
     */
    public interface OverlayOps {

        /** Returns the {@code GTransform} component on {@code entity}. */
        Object entityGetTransform(Object entity);

        /** Returns the position {@code GVector3} on {@code transform}. */
        Object transformGetPosition(Object transform);

        /** Writes the Z coordinate on position {@code vec3}. */
        void vec3SetZ(Object vec3, float z);

        /** Returns the mesh container component on {@code entity}. */
        Object simpleMeshEntityGetContainer(Object entity);

        /** Returns the mesh renderer component on {@code entity}. */
        Object simpleMeshEntityGetRenderer(Object entity);

        /** Returns the underlying {@code GMesh} from {@code container}. */
        Object meshContainerGetMesh(Object container);

        /** Returns the {@code GMaterial} on {@code renderer}. */
        Object rendererGetMaterial(Object renderer);

        /** Returns the line renderer component on {@code entity}. */
        Object simpleLinesEntityGetRenderer(Object entity);

        /** Assigns {@code layer} onto line or mesh {@code renderer}. */
        void rendererSetSortingLayer(Object renderer, Object layer);

        /** Reads geometry step on line {@code renderer}. */
        int lineRendererGetStep(Object renderer);

        /** Sets geometry step on line {@code renderer}. */
        void lineRendererSetStep(Object renderer, int step);

        /** Returns position vertices on line {@code renderer}. */
        float[] lineRendererGetPositions(Object renderer);

        /** Sets position vertices on line {@code renderer}. */
        void lineRendererSetPositions(Object renderer, float[] positions);

        /** Returns index buffer values on line {@code renderer}. */
        int[] lineRendererGetIndices(Object renderer);

        /** Sets index buffer values on line {@code renderer}. */
        void lineRendererSetIndices(Object renderer, int[] indices);

        /** Rebuilds line mesh buffers on {@code renderer}. */
        void lineRendererUpdateMeshData(Object renderer);

        /** Returns position GPU buffer wrapper on line {@code renderer}. */
        Object lineRendererGetPositionsBuffer(Object renderer);

        /** Returns vertex position array on {@code mesh}. */
        float[] meshGetPositions(Object mesh);

        /** Returns index array on {@code mesh}. */
        int[] meshGetIndices(Object mesh);

        /** Returns float position GPU buffer wrapper on {@code mesh}. */
        Object meshGetPositionsBuffer(Object mesh);

        /** Returns int index GPU buffer wrapper on {@code mesh}. */
        Object meshGetIndicesBuffer(Object mesh);

        /** Uploads modified float GPU buffer contents. */
        void floatBufferUpload(Object buffer);

        /** Uploads modified int GPU buffer contents. */
        void intBufferUpload(Object buffer);

        /** Reads red channel on {@code color}. */
        float colorGetRed(Object color);

        /** Reads green channel on {@code color}. */
        float colorGetGreen(Object color);

        /** Reads blue channel on {@code color}. */
        float colorGetBlue(Object color);

        /** Reads alpha channel on {@code color}. */
        float colorGetAlpha(Object color);
    }

    /**
     * Compiled monomorphic host accessor contract for hot keyform grid and binding lookups.
     */
    public interface KeyformOps {

        /** Returns the list of parameter bindings on {@code grid}. */
        List<?> gridGetKeyformBindings(Object grid);

        /** Returns the flat list of keyforms on {@code grid}. */
        List<?> gridGetKeyformsOnGrid(Object grid);

        /** Returns the parameter guid bound to {@code binding}. */
        Object bindingGetParameterGuid(Object binding);

        /** Returns the parameter source bound to {@code binding}. */
        Object bindingGetParameter(Object binding);

        /** Returns the list of key values on {@code binding}. */
        List<?> bindingGetKeys(Object binding);

        /** Returns whether {@code paramSource} enables repeat wrapping. */
        boolean paramSourceIsRepeat(Object paramSource);

        /** Returns the minimum range limit on {@code paramSource}. */
        float paramSourceGetRangeMin(Object paramSource);

        /** Returns the maximum range limit on {@code paramSource}. */
        float paramSourceGetRangeMax(Object paramSource);

        /** Returns the key snap epsilon on {@code paramSource}. */
        float paramSourceGetSnapEpsilon(Object paramSource);

        /** Looks up a parameter instance in {@code paramSet} by {@code paramGuid}. */
        Object paramSetGet(Object paramSet, Object paramGuid);

        /** Reads the current scalar value on {@code param}. */
        float paramGetValue(Object param);
    }

    private static final String[][] REQUIRED_KEYFORM_ZERO_ARG_METHODS = {
        {KEYFORM_GRID_SRC, "getKeyformBindings"},
        {KEYFORM_GRID_SRC, "getKeyformsOnGrid"},
        {KEYFORM_BINDING_SRC, "getParameterGuid"},
        {KEYFORM_BINDING_SRC, "getParameter"},
        {KEYFORM_BINDING_SRC, "getKeys"},
        {CPARAM_SRC, "isRepeat"},
        {CPARAM_SRC, "getRangeMin"},
        {CPARAM_SRC, "getRangeMax"},
        {CPARAM_SRC, "getSnapEpsilon"},
        {CPARAM, "getValue"},
    };

    static KeyformOps tryCompileKeyform(final ClassLoader hostLoader) {
        if (hostLoader == null) {
            return null;
        }
        try {
            for (final String[] req : REQUIRED_KEYFORM_ZERO_ARG_METHODS) {
                final Class<?> cls = Class.forName(req[0].replace('/', '.'), false, hostLoader);
                cls.getMethod(req[1]);
            }
            final Class<?> paramSetCls = Class.forName(CPARAM_SET.replace('/', '.'), false, hostLoader);
            final Class<?> paramGuidCls = Class.forName(CPARAM_GUID.replace('/', '.'), false, hostLoader);
            paramSetCls.getMethod("get", paramGuidCls);

            final String internalName = GENERATED_PACKAGE_PREFIX + "CompiledKeyformOps_" + CLASS_SEQ.incrementAndGet();
            final ClassWriter cw = HostClassWriters.newFrameWriter(hostLoader);
            final String iface = KeyformOps.class.getName().replace('.', '/');
            cw.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                    internalName,
                    null,
                    OBJECT_INTERNAL,
                    new String[] {iface});
            emitDefaultConstructor(cw);

            emitUnaryVirtual(
                    cw,
                    "gridGetKeyformBindings",
                    DESC_OBJ_TO_LIST,
                    KEYFORM_GRID_SRC,
                    "getKeyformBindings",
                    "()Ljava/util/List;");
            emitUnaryVirtual(
                    cw,
                    "gridGetKeyformsOnGrid",
                    DESC_OBJ_TO_LIST,
                    KEYFORM_GRID_SRC,
                    "getKeyformsOnGrid",
                    "()Ljava/util/List;");
            emitUnaryVirtual(
                    cw,
                    "bindingGetParameterGuid",
                    DESC_OBJ_TO_OBJ,
                    KEYFORM_BINDING_SRC,
                    "getParameterGuid",
                    "()L" + CPARAM_GUID + ";");
            emitUnaryVirtual(
                    cw,
                    "bindingGetParameter",
                    DESC_OBJ_TO_OBJ,
                    KEYFORM_BINDING_SRC,
                    "getParameter",
                    "()L" + CPARAM_SRC + ";");
            emitUnaryVirtual(
                    cw, "bindingGetKeys", DESC_OBJ_TO_LIST, KEYFORM_BINDING_SRC, "getKeys", "()Ljava/util/List;");
            emitUnaryVirtual(
                    cw, "paramSourceIsRepeat", DESC_OBJ_TO_BOOL, CPARAM_SRC, "isRepeat", "()Z", Opcodes.IRETURN);
            emitUnaryVirtual(
                    cw, "paramSourceGetRangeMin", DESC_OBJ_TO_FLOAT, CPARAM_SRC, "getRangeMin", "()F", Opcodes.FRETURN);
            emitUnaryVirtual(
                    cw, "paramSourceGetRangeMax", DESC_OBJ_TO_FLOAT, CPARAM_SRC, "getRangeMax", "()F", Opcodes.FRETURN);
            emitUnaryVirtual(
                    cw,
                    "paramSourceGetSnapEpsilon",
                    DESC_OBJ_TO_FLOAT,
                    CPARAM_SRC,
                    "getSnapEpsilon",
                    "()F",
                    Opcodes.FRETURN);

            final MethodVisitor paramSetGetMv = cw.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    "paramSetGet",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    null,
                    null);
            paramSetGetMv.visitCode();
            paramSetGetMv.visitVarInsn(Opcodes.ALOAD, 1);
            paramSetGetMv.visitTypeInsn(Opcodes.CHECKCAST, CPARAM_SET);
            paramSetGetMv.visitVarInsn(Opcodes.ALOAD, 2);
            paramSetGetMv.visitTypeInsn(Opcodes.CHECKCAST, CPARAM_GUID);
            paramSetGetMv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, CPARAM_SET, "get", "(L" + CPARAM_GUID + ";)L" + CPARAM + ";", false);
            paramSetGetMv.visitInsn(Opcodes.ARETURN);
            paramSetGetMv.visitMaxs(2, 3);
            paramSetGetMv.visitEnd();

            emitUnaryVirtual(cw, "paramGetValue", DESC_OBJ_TO_FLOAT, CPARAM, "getValue", "()F", Opcodes.FRETURN);
            cw.visitEnd();

            final byte[] bytecode = cw.toByteArray();
            final AccessorClassLoader defineLoader =
                    new AccessorClassLoader(hostLoader, KeyformOps.class.getClassLoader());
            final Class<?> generated = defineLoader.define(internalName.replace('/', '.'), bytecode);
            return (KeyformOps) generated.getDeclaredConstructor().newInstance();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static final class FallbackHostOps implements HostOps {
        private static final float UNIT = 1.0f;
        private final EditSubtreeHandles h;

        FallbackHostOps(final EditSubtreeHandles handles) {
            this.h = handles;
        }

        @Override
        public List<?> modelGetAllDeformers(final Object model) {
            try {
                return (List<?>) (Object) h.modelGetAllDeformers.invokeExact(model);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public List<?> modelGetAllArtMeshes(final Object model) {
            try {
                return (List<?>) (Object) h.modelGetAllArtMeshes.invokeExact(model);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public List<?> modelGetAllParts(final Object model) {
            try {
                return (List<?>) (Object) h.modelGetAllParts.invokeExact(model);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public List<?> modelGetAllArtPaths(final Object model) {
            try {
                return (List<?>) (Object) h.modelGetAllArtPaths.invokeExact(model);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public List<?> modelGetAllAffecters(final Object model) {
            try {
                return (List<?>) (Object) h.modelGetAllAffecters.invokeExact(model);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object modelGetParameterSet(final Object model) {
            try {
                return (Object) h.modelGetParameterSet.invokeExact(model);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object modelGetDeformer(final Object model, final Object guid) {
            try {
                return (Object) h.modelGetDeformer.invokeExact(model, guid);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public long modelGetLastUpdatedTime(final Object model) {
            if (h.modelGetLastUpdatedTime == null) {
                return 0L;
            }
            try {
                return (long) h.modelGetLastUpdatedTime.invokeExact(model);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void modelSetLastUpdatedTime(final Object model, final long timeMs) {
            try {
                h.modelSetLastUpdatedTime.invokeExact(model, timeMs);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int paramSetGetUpdateVersion(final Object paramSet) {
            try {
                return (int) h.paramSetGetUpdateVersion.invokeExact(paramSet);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public List<?> paramSetGetParameters(final Object paramSet) {
            try {
                return (List<?>) (Object) h.paramSetGetParameters.invokeExact(paramSet);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetSource(final Object deformer) {
            try {
                return (Object) h.deformerGetSource.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int sourceGetUpdateVersion(final Object source) {
            try {
                return (int) h.sourceGetUpdateVersion.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int deformerGetCopiedUpdateVersion(final Object deformer) {
            try {
                return (int) h.deformerGetCopiedUpdateVersion.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean deformerGetDirtyDeformedForm(final Object deformer) {
            try {
                return (boolean) h.deformerGetDirtyDeformedForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void deformerSetDirtyDeformedForm(final Object deformer, final boolean dirty) {
            try {
                h.deformerSetDirtyDeformedForm.invokeExact(deformer, dirty);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean deformerIsParamOutsideHierarchy(final Object deformer) {
            try {
                return (boolean) h.deformerIsParamOutsideHierarchy.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetInterpolatedForm(final Object deformer) {
            try {
                return (Object) h.deformerGetInterpolatedForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetDeformedForm(final Object deformer) {
            try {
                return (Object) h.deformerGetDeformedForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetAffectedForm(final Object deformer) {
            if (h.deformerGetAffectedForm == null) {
                return null;
            }
            try {
                return (Object) h.deformerGetAffectedForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetCalculatedForm(final Object deformer) {
            try {
                return (Object) h.deformerGetCalculatedForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetLocalAnimatedForm(final Object deformer) {
            try {
                return (Object) h.deformerGetLocalAnimatedForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetAnimatedForm(final Object deformer) {
            try {
                return (Object) h.deformerGetAnimatedForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetPreDeformForm(final Object deformer) {
            if (h.controllableGetPreDeformForm == null) {
                final Object anim = deformerGetLocalAnimatedForm(deformer);
                return anim != null ? anim : deformerGetInterpolatedForm(deformer);
            }
            try {
                return (Object) h.controllableGetPreDeformForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetPreClampMorphedForm(final Object deformer) {
            if (h.controllableGetPreClampMorphedForm == null) {
                return null;
            }
            try {
                return (Object) h.controllableGetPreClampMorphedForm.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void deformerSetPreClampMorphedForm(final Object deformer, final Object form) {
            if (h.controllableSetPreClampMorphedForm == null) {
                return;
            }
            try {
                h.controllableSetPreClampMorphedForm.invokeExact(deformer, form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetGuid(final Object deformer) {
            try {
                return (Object) h.deformerGetGuid.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerGetTargetGuid(final Object deformer) {
            try {
                return (Object) h.deformerGetTargetGuid.invokeExact(deformer);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void deformerSetDeformedForm(final Object deformer, final Object form) {
            try {
                if (h.warpDeformerSetDeformedForm != null && h.warpDeformerClass.isInstance(deformer)) {
                    h.warpDeformerSetDeformedForm.invokeExact(deformer, form);
                } else if (h.rotDeformerSetDeformedForm != null && h.rotDeformerClass.isInstance(deformer)) {
                    h.rotDeformerSetDeformedForm.invokeExact(deformer, form);
                }
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float[] warpFormGetPositions(final Object form) {
            try {
                return (float[]) h.warpFormGetPositions.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void warpFormSetPositions(final Object form, final float[] positions) {
            if (h.warpFormSetPositions == null) {
                return;
            }
            try {
                h.warpFormSetPositions.invokeExact(form, positions);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float rotFormGetOriginX(final Object form) {
            try {
                return (float) h.rotFormGetOriginX.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float rotFormGetOriginY(final Object form) {
            try {
                return (float) h.rotFormGetOriginY.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float rotFormGetScale(final Object form) {
            try {
                return (float) h.rotFormGetScale.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float rotFormGetAngle(final Object form) {
            try {
                return (float) h.rotFormGetAngle.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean rotFormIsReflectX(final Object form) {
            try {
                return (boolean) h.rotFormIsReflectX.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean rotFormIsReflectY(final Object form) {
            try {
                return (boolean) h.rotFormIsReflectY.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void rotFormSetOriginX(final Object form, final float originX) {
            if (h.rotFormSetOriginX == null) {
                return;
            }
            try {
                h.rotFormSetOriginX.invokeExact(form, originX);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void rotFormSetOriginY(final Object form, final float originY) {
            if (h.rotFormSetOriginY == null) {
                return;
            }
            try {
                h.rotFormSetOriginY.invokeExact(form, originY);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void rotFormSetScale(final Object form, final float scale) {
            if (h.rotFormSetScale == null) {
                return;
            }
            try {
                h.rotFormSetScale.invokeExact(form, scale);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void rotFormSetAngle(final Object form, final float angle) {
            if (h.rotFormSetAngle == null) {
                return;
            }
            try {
                h.rotFormSetAngle.invokeExact(form, angle);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void rotFormSetReflectX(final Object form, final boolean reflectX) {
            if (h.rotFormSetReflectX == null) {
                return;
            }
            try {
                h.rotFormSetReflectX.invokeExact(form, reflectX);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void rotFormSetReflectY(final Object form, final boolean reflectY) {
            if (h.rotFormSetReflectY == null) {
                return;
            }
            try {
                h.rotFormSetReflectY.invokeExact(form, reflectY);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public List<?> sourceGetKeyforms(final Object source) {
            try {
                return (List<?>) (Object) h.sourceGetKeyforms.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object sourceGetDefaultKeyForm(final Object source) {
            if (h.sourceGetDefaultKeyForm == null) {
                return null;
            }
            try {
                return (Object) h.sourceGetDefaultKeyForm.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object sourceGetExtKeyformGrid(final Object source) {
            try {
                return (Object) h.sourceGetExtKeyformGrid.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object sourceGetKeyformGrid(final Object source) {
            try {
                return (Object) h.sourceGetKeyformGrid.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object sourceGetCoordType(final Object source) {
            try {
                return (Object) h.sourceGetCoordType.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public List<?> sourceGetMorphTargets(final Object source) {
            try {
                return (List<?>) (Object) h.sourceGetMorphTargets.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object sourceGetMorphSet(final Object source) {
            if (h.sourceGetMorphSet == null) {
                return null;
            }
            try {
                return (Object) h.sourceGetMorphSet.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object sourceGetExtMorphSet(final Object source) {
            if (h.sourceGetExtMorphSet == null) {
                return null;
            }
            try {
                return (Object) h.sourceGetExtMorphSet.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean controllableSourceIsVisible(final Object source) {
            try {
                return (boolean) h.meshSourceIsVisible.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean controllableSourceIsVisibleHierarchy(final Object source) {
            try {
                return (boolean) h.sourceIsVisibleHierarchy.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object controllableSourceGetParent(final Object source) {
            if (h.controllableSourceGetParent == null) {
                return null;
            }
            try {
                return (Object) h.controllableSourceGetParent.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object controllableSourceGetParentGuid(final Object source) {
            if (h.controllableSourceGetParentGuid == null) {
                return null;
            }
            try {
                return (Object) h.controllableSourceGetParentGuid.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean meshIsInvisible(final Object mesh) {
            if (h.meshIsInvisible == null) {
                return false;
            }
            try {
                return (boolean) h.meshIsInvisible.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetSource(final Object mesh) {
            try {
                return (Object) h.meshGetSource.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int meshSourceGetMeshVersion(final Object source) {
            try {
                return (int) h.meshSourceGetMeshVersion.invokeExact(source);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int meshGetCopiedMeshVersion(final Object mesh) {
            try {
                return (int) h.meshGetCopiedMeshVersion.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int meshGetDrawOrder(final Object mesh) {
            try {
                return (int) h.meshGetDrawOrder.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetInterpolatedForm(final Object mesh) {
            try {
                return (Object) h.meshGetInterpolatedForm.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetDeformedForm(final Object mesh) {
            try {
                return (Object) h.meshGetDeformedForm.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetAffectedForm(final Object mesh) {
            try {
                return (Object) h.meshGetAffectedForm.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetCalculatedForm(final Object mesh) {
            try {
                return (Object) h.meshGetCalculatedForm.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetLocalAnimatedForm(final Object mesh) {
            try {
                return (Object) h.meshGetLocalAnimatedForm.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetAnimatedForm(final Object mesh) {
            try {
                return (Object) h.meshGetAnimatedForm.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float[] meshFormGetPositions(final Object form) {
            try {
                return (float[]) h.meshFormGetPositions.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetContainer(final Object mesh) {
            try {
                return (Object) h.meshGetContainer.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshContainerGetMesh(final Object container) {
            try {
                return (Object) h.meshContainerGetMesh.invokeExact(container);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float[] meshBaseGetPositions(final Object meshBase) {
            try {
                return (float[]) h.meshBaseGetPositions.invokeExact(meshBase);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetGuid(final Object mesh) {
            try {
                return (Object) h.meshGetGuid.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshGetTargetGuid(final Object mesh) {
            try {
                return (Object) h.meshGetTargetGuid.invokeExact(mesh);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float meshFormGetOpacity(final Object form) {
            try {
                return (float) h.meshFormGetOpacity.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshFormGetMultiplyColor(final Object form) {
            try {
                return (Object) h.meshFormGetMultiplyColor.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object meshFormGetScreenColor(final Object form) {
            try {
                return (Object) h.meshFormGetScreenColor.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object partGetSource(final Object part) {
            try {
                return (Object) h.partGetSource.invokeExact(part);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean partSourceIsVisible(final Object partSource) {
            try {
                return (boolean) h.partSourceIsVisible.invokeExact(partSource);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean partSourceIsVisibleHierarchy(final Object partSource) {
            try {
                return (boolean) h.partSourceIsVisibleHierarchy.invokeExact(partSource);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int partGetDrawOrder(final Object part) {
            try {
                return (int) h.partGetDrawOrder.invokeExact(part);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object partGetInterpolatedForm(final Object part) {
            try {
                return (Object) h.partGetInterpolatedForm.invokeExact(part);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object glueGetSource(final Object glue) {
            try {
                return (Object) h.glueGetSource.invokeExact(glue);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object glueGetInterpolatedForm(final Object glue) {
            try {
                return (Object) h.glueGetInterpolatedForm.invokeExact(glue);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int glueGetCachedEditVersion(final Object glue) {
            try {
                return (int) h.glueGetCachedEditVersion.invokeExact(glue);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int glueSourceGetEditVersion(final Object glueSource) {
            try {
                return (int) h.glueSourceGetEditVersion.invokeExact(glueSource);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float glueFormGetIntensity(final Object form) {
            try {
                return (float) h.glueFormGetIntensity.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object glueSourceGetMeshAGuid(final Object glueSource) {
            try {
                return (Object) h.glueSourceGetMeshAGuid.invokeExact(glueSource);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object glueSourceGetMeshBGuid(final Object glueSource) {
            try {
                return (Object) h.glueSourceGetMeshBGuid.invokeExact(glueSource);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float paramGetValue(final Object param) {
            try {
                return (float) h.paramGetValue.invokeExact(param);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object paramGetGuid(final Object param) {
            if (h.paramGetGuid == null) {
                return null;
            }
            try {
                return (Object) h.paramGetGuid.invokeExact(param);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object paramGetSource(final Object param) {
            if (h.paramGetSource == null) {
                return null;
            }
            try {
                return (Object) h.paramGetSource.invokeExact(param);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object paramSourceGetGuid(final Object paramSource) {
            if (h.paramSourceGetGuid == null) {
                return null;
            }
            try {
                return (Object) h.paramSourceGetGuid.invokeExact(paramSource);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float paramSourceGetMaxValue(final Object paramSource) {
            if (h.paramSourceGetMaxValue == null) {
                return 0.0f;
            }
            try {
                return (float) h.paramSourceGetMaxValue.invokeExact(paramSource);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float colorGetR(final Object floatColor) {
            try {
                return (float) h.floatColorGetR.invokeExact(floatColor);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float colorGetG(final Object floatColor) {
            try {
                return (float) h.floatColorGetG.invokeExact(floatColor);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float colorGetB(final Object floatColor) {
            try {
                return (float) h.floatColorGetB.invokeExact(floatColor);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float colorGetA(final Object floatColor) {
            try {
                return (float) h.floatColorGetA.invokeExact(floatColor);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void colorSetR(final Object floatColor, final float value) {
            try {
                h.floatColorSetR.invokeExact(floatColor, value);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void colorSetG(final Object floatColor, final float value) {
            try {
                h.floatColorSetG.invokeExact(floatColor, value);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void colorSetB(final Object floatColor, final float value) {
            try {
                h.floatColorSetB.invokeExact(floatColor, value);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void colorSetA(final Object floatColor, final float value) {
            try {
                h.floatColorSetA.invokeExact(floatColor, value);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object floatColorClamp(final Object floatColor) {
            if (h.floatColorClamp == null) {
                return floatColor;
            }
            try {
                return (Object) h.floatColorClamp.invokeExact(floatColor);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object formGetSource(final Object form) {
            if (h.formGetSource == null) {
                return null;
            }
            try {
                return (Object) h.formGetSource.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public int formGetEditVersion(final Object form) {
            try {
                return (int) h.formGetEditVersion.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean formIsParameterOutside(final Object form) {
            if (h.formIsParameterOutside == null) {
                return false;
            }
            try {
                return (boolean) h.formIsParameterOutside.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public float deformerFormGetOpacity(final Object form) {
            try {
                return (float) h.deformerFormGetOpacity.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerFormGetMultiplyColor(final Object form) {
            try {
                return (Object) h.deformerFormGetMultiplyColor.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerFormGetScreenColor(final Object form) {
            try {
                return (Object) h.deformerFormGetScreenColor.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void deformerFormSetOpacity(final Object form, final float opacity) {
            if (h.deformerFormSetOpacity == null) {
                return;
            }
            try {
                h.deformerFormSetOpacity.invokeExact(form, opacity);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object deformerFormGetCoordType(final Object form) {
            if (h.deformerFormGetCoordType == null) {
                return null;
            }
            try {
                return (Object) h.deformerFormGetCoordType.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void deformerFormSetCoordType(final Object form, final Object coordType) {
            try {
                h.deformerFormSetCoordType.invokeExact(form, coordType);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void formZero(final Object form) {
            if (h.formZero == null) {
                return;
            }
            try {
                h.formZero.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void formBlend(final Object dst, final Object src, final float weight) {
            if (h.formBlend == null) {
                return;
            }
            try {
                h.formBlend.invokeExact(dst, src, weight);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void formPostBlend(final Object form) {
            if (h.formPostBlend == null) {
                return;
            }
            try {
                h.formPostBlend.invokeExact(form);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public boolean formIsCompatible(final Object a, final Object b) {
            try {
                return (boolean) h.meshFormIsCompatible.invokeExact(a, b);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public Object formDeepCopy(final Object form, final Object copyInfo) {
            if (h.formDeepCopy == null) {
                return null;
            }
            try {
                return (Object) h.formDeepCopy.invokeExact(form, copyInfo);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void formReinit(final Object dst, final Object src, final Object copyInfo) {
            if (h.formReinit == null) {
                return;
            }
            try {
                h.formReinit.invokeExact(dst, src, copyInfo);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void updateDeformerTotals(final Object d, final Object parent, final boolean isRotation) {
            try {
                final Object localForm = (Object) h.deformerGetInterpolatedForm.invokeExact(d);
                final float localScale = isRotation ? (float) h.rotFormGetScale.invokeExact(localForm) : UNIT;
                final float localOp = (float) h.deformerFormGetOpacity.invokeExact(localForm);
                final Object localMul = (Object) h.deformerFormGetMultiplyColor.invokeExact(localForm);
                final Object localScr = (Object) h.deformerFormGetScreenColor.invokeExact(localForm);
                final Object totalMul = (Object) h.deformerGetCalcTotalMultiplyColor.invokeExact(d);
                final Object totalScr = (Object) h.deformerGetCalcTotalScreenColor.invokeExact(d);
                if (parent == null) {
                    h.deformerSetParamOutsideAnyParents.invokeExact(d, false);
                    h.deformerSetCalcTotalScale.invokeExact(d, localScale);
                    h.deformerSetCalcTotalOpacity.invokeExact(d, localOp);
                    copyColor(totalMul, localMul);
                    copyColor(totalScr, localScr);
                } else {
                    h.deformerSetParamOutsideAnyParents.invokeExact(
                            d, (boolean) h.deformerIsParamOutsideHierarchy.invokeExact(parent));
                    h.deformerSetCalcTotalScale.invokeExact(
                            d, localScale * (float) h.deformerGetCalcTotalScale.invokeExact(parent));
                    h.deformerSetCalcTotalOpacity.invokeExact(
                            d, localOp * (float) h.deformerGetCalcTotalOpacity.invokeExact(parent));
                    final Object pMul = (Object) h.deformerGetCalcTotalMultiplyColor.invokeExact(parent);
                    final Object pScr = (Object) h.deformerGetCalcTotalScreenColor.invokeExact(parent);
                    multiplyColor(totalMul, localMul, pMul);
                    screenColor(totalScr, localScr, pScr);
                }
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void transformDeformerIfChild(final Object deformer, final Object parent) {
            try {
                if (parent != null) {
                    h.deformerTransformTestImpl.invokeExact(deformer, parent);
                }
                h.deformerSetDirtyDeformedForm.invokeExact(deformer, false);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        @Override
        public void prepareAndTransformArtMesh(final Object mesh, final Object parent, final Object canvasCoordType) {
            try {
                if (parent == null) {
                    h.meshSetDeformedForm.invokeExact(mesh, (Object) null);
                    return;
                }
                final Object preForm = (Object) h.meshGetInterpolatedForm.invokeExact(mesh);
                Object out = (Object) h.meshGetDeformedForm.invokeExact(mesh);
                if (out != null && !(boolean) h.meshFormIsCompatible.invokeExact(out, preForm)) {
                    out = null;
                }
                final Object tr = (Object) h.deformerGetCreateTransform.invokeExact(parent);
                final Object transformed = (Object) h.meshFormTransform.invokeExact(preForm, tr, out);
                h.meshFormSetCoordType.invokeExact(transformed, canvasCoordType);
                if (transformed != out) {
                    h.meshSetDeformedForm.invokeExact(mesh, transformed);
                }
                h.meshSetParamOutsideAnyParents.invokeExact(
                        mesh, (boolean) h.deformerIsParamOutsideHierarchy.invokeExact(parent));
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw propagate(failure);
            }
        }

        private void copyColor(final Object dst, final Object src) throws Throwable {
            h.floatColorSetA.invokeExact(dst, (float) h.floatColorGetA.invokeExact(src));
            h.floatColorSetR.invokeExact(dst, (float) h.floatColorGetR.invokeExact(src));
            h.floatColorSetG.invokeExact(dst, (float) h.floatColorGetG.invokeExact(src));
            h.floatColorSetB.invokeExact(dst, (float) h.floatColorGetB.invokeExact(src));
        }

        private void multiplyColor(final Object dst, final Object local, final Object pTotal) throws Throwable {
            h.floatColorSetA.invokeExact(dst, (float) h.floatColorGetA.invokeExact(local));
            h.floatColorSetR.invokeExact(
                    dst, (float) h.floatColorGetR.invokeExact(local) * (float) h.floatColorGetR.invokeExact(pTotal));
            h.floatColorSetG.invokeExact(
                    dst, (float) h.floatColorGetG.invokeExact(local) * (float) h.floatColorGetG.invokeExact(pTotal));
            h.floatColorSetB.invokeExact(
                    dst, (float) h.floatColorGetB.invokeExact(local) * (float) h.floatColorGetB.invokeExact(pTotal));
        }

        private void screenColor(final Object dst, final Object local, final Object pTotal) throws Throwable {
            h.floatColorSetA.invokeExact(dst, (float) h.floatColorGetA.invokeExact(local));
            final float lr = (float) h.floatColorGetR.invokeExact(local);
            final float lg = (float) h.floatColorGetG.invokeExact(local);
            final float lb = (float) h.floatColorGetB.invokeExact(local);
            final float pr = (float) h.floatColorGetR.invokeExact(pTotal);
            final float pg = (float) h.floatColorGetG.invokeExact(pTotal);
            final float pb = (float) h.floatColorGetB.invokeExact(pTotal);
            h.floatColorSetR.invokeExact(dst, (lr + pr) - (lr * pr));
            h.floatColorSetG.invokeExact(dst, (lg + pg) - (lg * pg));
            h.floatColorSetB.invokeExact(dst, (lb + pb) - (lb * pb));
        }

        private static RuntimeException propagate(final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            if (failure instanceof RuntimeException runtime) {
                return runtime;
            }
            return new IllegalStateException("host fallback invocation failed", failure);
        }
    }
}
