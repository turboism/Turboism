package dev.turboism.adapter.cubism.optimization.modelupdate.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.FRETURN;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.LRETURN;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

class IncrementalUpdateBridgeTest {

    private static final String N = "com/live2d/cubism/";
    private static final IncrementalUpdateTarget T5303 =
        IncrementalUpdateTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();

    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();
        Loader() { super(IncrementalUpdateBridgeTest.class.getClassLoader()); }
        Loader add(String name, byte[] bytes) { classes.put(name.replace('/', '.'), bytes); return this; }
        Class<?> of(String name) {
            try { return loadClass(name.replace('/', '.')); }
            catch (ClassNotFoundException failure) { throw new IllegalStateException(failure); }
        }
        /** Child-first for registered stubs: the test classpath carries real com.live2d stand-ins. */
        @Override protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && classes.containsKey(name)) type = findClass(name);
                if (type == null) type = super.loadClass(name, false);
                if (resolve) resolveClass(type);
                return type;
            }
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Emits a stub class: public fields plus trivial getters returning them. */
    private static byte[] emit(String name, String sup, String[] ifaces,
                               String[] fields, String[] statics, String[][] methods) {
        ClassWriter w = new ClassWriter(0);
        if ("interface".equals(sup)) {
            w.visit(V17, ACC_PUBLIC | 0x0600, name, null, "java/lang/Object", null);
            w.visitEnd();
            return w.toByteArray();
        }
        w.visit(V17, ACC_PUBLIC, name, null, sup == null ? "java/lang/Object" : sup, ifaces);
        for (String field : fields) {
            String[] p = field.split(":", 2);
            w.visitField(ACC_PUBLIC, p[0], p[1], null, null).visitEnd();
        }
        for (String field : statics) {
            String[] p = field.split(":", 2);
            w.visitField(ACC_PUBLIC | ACC_STATIC, p[0], p[1], null, null).visitEnd();
        }
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, sup == null ? "java/lang/Object" : sup,
            "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        for (String[] method : methods) {
            String ret = method[1].substring(method[1].indexOf(')') + 1);
            m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, method[0], method[1], null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, name, method[2], ret);
            m.visitInsn(ret.startsWith("L") || ret.startsWith("[") ? ARETURN
                : ret.equals("J") ? LRETURN : ret.equals("F") ? FRETURN : IRETURN);
            m.visitMaxs(2, 3);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    private static String[] f(String... fields) { return fields; }
    private static String[][] m(String[]... methods) { return methods; }
    private static String[] g(String name, String desc, String field) {
        return new String[]{name, desc, field};
    }

    /** The updater singleton: static instance {@code a} plus static skip flag {@code e}. */
    private static byte[] emitUpdater(String name) {
        ClassWriter w = new ClassWriter(0);
        w.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "a", "L" + name + ";", null, null).visitEnd();
        w.visitField(ACC_PUBLIC | ACC_STATIC, "e", "Z", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "b", "()Z", null, null);
        m.visitCode();
        m.visitFieldInsn(GETSTATIC, name, "e", "Z");
        m.visitInsn(IRETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "b", "(Z)V", null, null);
        m.visitCode();
        m.visitVarInsn(ILOAD, 1);
        m.visitFieldInsn(PUTSTATIC, name, "e", "Z");
        m.visitInsn(RETURN);
        m.visitMaxs(1, 2);
        m.visitEnd();
        m = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        m.visitCode();
        m.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, name);
        m.visitInsn(org.objectweb.asm.Opcodes.DUP);
        m.visitMethodInsn(INVOKESPECIAL, name, "<init>", "()V", false);
        m.visitFieldInsn(PUTSTATIC, name, "a", "L" + name + ";");
        m.visitInsn(RETURN);
        m.visitMaxs(2, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** CModel: list fields plus {@code getDeformer(guid)} resolving through a guid map. */
    private static byte[] emitModel(String name) {
        ClassWriter w = new ClassWriter(0);
        String M = N + "doc/model/";
        w.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        for (String field : new String[] {"deformers", "paths", "affecters", "drawables",
                "guidMap", "paramSet"}) {
            String desc = field.equals("paramSet")
                ? "L" + M + "param/CParameterSet;" : "Ljava/util/List;";
            if (field.equals("guidMap")) desc = "Ljava/util/Map;";
            w.visitField(ACC_PUBLIC, field, desc, null, null).visitEnd();
        }
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        for (String[] g : new String[][] {
            {"getAllDeformers", "()Ljava/util/List;", "deformers"},
            {"getAllArtPaths", "()Ljava/util/List;", "paths"},
            {"getAllAffecters", "()Ljava/util/List;", "affecters"},
            {"getAllDrawables", "()Ljava/util/List;", "drawables"},
            {"getParameterSet", "()L" + M + "param/CParameterSet;", "paramSet"}}) {
            String ret = g[1].substring(g[1].indexOf(')') + 1);
            m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, g[0], g[1], null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, name, g[2], ret);
            m.visitInsn(ARETURN);
            m.visitMaxs(2, 3);
            m.visitEnd();
        }
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "getDeformer",
            "(Lcom/live2d/type/CDeformerGuid;)L" + M + "deformer/ACDeformer;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, name, "guidMap", "Ljava/util/Map;");
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, M + "deformer/ACDeformer");
        m.visitInsn(ARETURN);
        m.visitMaxs(3, 3);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** ACDeformer: stateful dirty flag plus form/parent/transform getters. */
    private static byte[] emitDeformer(String name) {
        ClassWriter w = new ClassWriter(0);
        String M = N + "doc/model/";
        w.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC, "dirty", "Z", null, null).visitEnd();
        w.visitField(ACC_PUBLIC, "iform", "L" + M + "deformer/ACDeformerForm;", null, null).visitEnd();
        w.visitField(ACC_PUBLIC, "aform", "L" + M + "deformer/ACDeformerForm;", null, null).visitEnd();
        w.visitField(ACC_PUBLIC, "tguid", "Lcom/live2d/type/CDeformerGuid;", null, null).visitEnd();
        w.visitField(ACC_PUBLIC, "guid", "Lcom/live2d/type/CDeformerGuid;", null, null).visitEnd();
        w.visitField(ACC_PUBLIC, "transform", "Lcom/live2d/doc/selection/d;", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        for (String[] g : new String[][] {
            {"getDirtyDeformedForm", "()Z", "dirty"},
            {"getInterpolatedForm", "()L" + M + "deformer/ACDeformerForm;", "iform"},
            {"getLocalAnimatedForm", "()L" + M + "deformer/ACDeformerForm;", "aform"},
            {"getTargetDeformerGuid", "()Lcom/live2d/type/CDeformerGuid;", "tguid"},
            {"getGuid", "()Lcom/live2d/type/CDeformerGuid;", "guid"},
            {"getCreateLocalToCanvasTransform", "()Lcom/live2d/doc/selection/d;", "transform"}}) {
            String ret = g[1].substring(g[1].indexOf(')') + 1);
            m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, g[0], g[1], null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, name, g[2], ret);
            m.visitInsn(ret.equals("Z") ? IRETURN : ARETURN);
            m.visitMaxs(2, 3);
            m.visitEnd();
        }
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "setDirtyDeformedForm", "(Z)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ILOAD, 1);
        m.visitFieldInsn(PUTFIELD, name, "dirty", "Z");
        m.visitInsn(RETURN);
        m.visitMaxs(2, 2);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** CArtMeshForm: positions getter plus a transform that counts invocations. */
    private static byte[] emitMeshForm(String name, String sup) {
        ClassWriter w = new ClassWriter(0);
        String M = N + "doc/model/";
        w.visit(V17, ACC_PUBLIC, name, null, sup, null);
        w.visitField(ACC_PUBLIC, "positions", "[F", null, null).visitEnd();
        w.visitField(ACC_PUBLIC | ACC_STATIC, "transformCalls", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, sup, "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "getPositions", "()[F", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, name, "positions", "[F");
        m.visitInsn(ARETURN);
        m.visitMaxs(2, 2);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "transform",
            "(Lcom/live2d/doc/selection/d;L" + M + "ACForm;)L" + name + ";", null, null);
        m.visitCode();
        m.visitFieldInsn(GETSTATIC, name, "transformCalls", "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTSTATIC, name, "transformCalls", "I");
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ARETURN);
        m.visitMaxs(2, 3);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** The complete 5.3.03 dependency surface for slice B. */
    private static Loader host() {
        String C = N + "view/context/", M = N + "doc/model/";
        return new Loader()
            .add("com/live2d/cubism/doc/IDocument",
                emit("com/live2d/cubism/doc/IDocument", "interface", null, f(), f(), m()))
            .add("com/live2d/type/CDeformerGuid",
                emit("com/live2d/type/CDeformerGuid", null, null, f(), f(), m()))
            .add("com/live2d/doc/selection/d",
                emit("com/live2d/doc/selection/d", null, null, f(), f(), m()))
            .add("com/live2d/type/CArrayList",
                emit("com/live2d/type/CArrayList", "java/util/ArrayList", null, f(), f(), m()))
            .add(N + "doc/modeling/CModelingDocument",
                emit(N + "doc/modeling/CModelingDocument", null,
                    new String[]{"com/live2d/cubism/doc/IDocument"},
                    f("lastModified:J"), f(), m(g("getLastModifiedTime", "()J", "lastModified"))))
            .add(C + "CEViewContext",
                emit(C + "CEViewContext", null, null, f("doc:Lcom/live2d/cubism/doc/IDocument;"),
                    f(), m(g("getDoc", "()Lcom/live2d/cubism/doc/IDocument;", "doc"))))
            .add(C + "bL", emit(C + "bL", null, null, f(), f(), m()))
            .add(M + "ax", emit(M + "ax", null, null, f(), f(), m()))
            .add(M + "CModel", emitModel(M + "CModel"))
            .add(M + "param/CParameterSet",
                emit(M + "param/CParameterSet", null, null,
                    f("params:Ljava/util/List;", "version:I"), f(),
                    m(g("getParameters", "()Ljava/util/List;", "params"),
                        g("getUpdateVersion", "()I", "version"))))
            .add(M + "param/CParameter",
                emit(M + "param/CParameter", null, null, f("value:F"), f(),
                    m(g("getValue", "()F", "value"))))
            .add(M + "ACForm", emit(M + "ACForm", null, null, f(), f(), m()))
            .add(M + "deformer/ACDeformer", emitDeformer(M + "deformer/ACDeformer"))
            .add(M + "deformer/ACDeformerForm",
                emit(M + "deformer/ACDeformerForm", M + "ACForm", null, f(), f(), m()))
            .add(M + "deformer/rotation/CRotationDeformerForm",
                emit(M + "deformer/rotation/CRotationDeformerForm",
                    M + "deformer/ACDeformerForm", null, f(), f(), m()))
            .add(M + "deformer/warp/CWarpDeformerForm",
                emit(M + "deformer/warp/CWarpDeformerForm",
                    M + "deformer/ACDeformerForm", null, f(), f(), m()))
            .add(M + "drawable/ACDrawableForm",
                emit(M + "drawable/ACDrawableForm", M + "ACForm", null, f(), f(), m()))
            .add(M + "drawable/ACDrawable",
                emit(M + "drawable/ACDrawable", null, null,
                    f("form:L" + M + "drawable/ACDrawableForm;", "order:I"), f(),
                    m(g("getDeformedForm", "()L" + M + "drawable/ACDrawableForm;", "form"),
                        g("getDrawOrder", "()I", "order"))))
            .add(M + "drawable/artMesh/CArtMesh",
                emit(M + "drawable/artMesh/CArtMesh", M + "drawable/ACDrawable", null,
                    f("iform:L" + M + "drawable/artMesh/CArtMeshForm;",
                        "aform:L" + M + "drawable/artMesh/CArtMeshForm;"), f(),
                    m(g("getInterpolatedForm",
                            "()L" + M + "drawable/artMesh/CArtMeshForm;", "iform"),
                        g("getLocalAnimatedForm",
                            "()L" + M + "drawable/artMesh/CArtMeshForm;", "aform"))))
            .add(M + "drawable/artMesh/CArtMeshForm",
                emitMeshForm(M + "drawable/artMesh/CArtMeshForm", M + "drawable/ACDrawableForm"))
            .add(M + "drawable/artPath/CArtPathForm",
                emit(M + "drawable/artPath/CArtPathForm", M + "drawable/ACDrawableForm", null,
                    f("points:Lcom/live2d/type/CArrayList;"), f(),
                    m(g("getPositions", "()Lcom/live2d/type/CArrayList;", "points"))))
            .add(M + "drawable/artPath/CArtPathPoint",
                emit(M + "drawable/artPath/CArtPathPoint", null, null,
                    f("curve:Lcom/live2d/graphics/splineCurve/CSplineCurvePoint;",
                        "width:F", "opacity:F"), f(),
                    m(g("getCurvePointPosition",
                            "()Lcom/live2d/graphics/splineCurve/CSplineCurvePoint;", "curve"),
                        g("getWidth", "()F", "width"), g("getOpacity", "()F", "opacity"))))
            .add("com/live2d/graphics/splineCurve/CSplineCurvePoint",
                emit("com/live2d/graphics/splineCurve/CSplineCurvePoint", null, null,
                    f("p:Lcom/live2d/graphics3d/type/GVector2;",
                        "s:Lcom/live2d/graphics3d/type/GVector2;",
                        "e:Lcom/live2d/graphics3d/type/GVector2;"), f(),
                    m(g("getPoint", "()Lcom/live2d/graphics3d/type/GVector2;", "p"),
                        g("getStartVelocity", "()Lcom/live2d/graphics3d/type/GVector2;", "s"),
                        g("getEndVelocity", "()Lcom/live2d/graphics3d/type/GVector2;", "e"))))
            .add("com/live2d/graphics3d/type/GVector2",
                emit("com/live2d/graphics3d/type/GVector2", null, null, f("x:F", "y:F"), f(),
                    m(g("getX", "()F", "x"), g("getY", "()F", "y"))))
            .add(N + "view/ay", emitUpdater(N + "view/ay"));
    }

    private static Object make(Loader loader, String name) {
        try { return loader.of(name).getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = (target instanceof Class<?> c ? c : target.getClass()).getField(field);
            if (f.getType() == boolean.class && value instanceof Boolean b) f.setBoolean(target, b);
            else if (f.getType() == int.class && value instanceof Integer i) f.setInt(target, i);
            else if (f.getType() == long.class && value instanceof Long l) f.setLong(target, l);
            else if (f.getType() == float.class && value instanceof Float x) f.setFloat(target, x);
            else f.set(target, value);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static Object field(Object target, String name) {
        try {
            final Class<?> owner = target instanceof Class<?> c ? c : target.getClass();
            return owner.getField(name).get(target instanceof Class ? null : target);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    /** A small host world: two deformers (parent/child) and one artMesh. */
    private static final class World {
        final Loader loader;
        final Object model, ctx, ax, parent, child, mesh, meshForm, deformedOut, doc;
        final Object parentForm, childForm;
        final List<Object> deformers = new ArrayList<>();

        World(Loader loader) {
            this.loader = loader;
            String M = N + "doc/model/";
            model = make(loader, M + "CModel");
            ctx = make(loader, N + "view/context/CEViewContext");
            ax = make(loader, M + "ax");
            doc = make(loader, N + "doc/modeling/CModelingDocument");
            set(doc, "lastModified", 7L);
            set(ctx, "doc", doc);
            parent = make(loader, M + "deformer/ACDeformer");
            child = make(loader, M + "deformer/ACDeformer");
            parentForm = make(loader, M + "deformer/warp/CWarpDeformerForm");
            childForm = make(loader, M + "deformer/rotation/CRotationDeformerForm");
            set(parent, "iform", parentForm);
            set(child, "iform", childForm);
            set(parent, "transform", make(loader, "com/live2d/doc/selection/d"));
            set(child, "transform", make(loader, "com/live2d/doc/selection/d"));
            Object parentGuid = make(loader, "com/live2d/type/CDeformerGuid");
            Object childGuid = make(loader, "com/live2d/type/CDeformerGuid");
            set(parent, "guid", parentGuid);
            set(child, "guid", childGuid);
            set(child, "tguid", parentGuid);
            deformers.add(parent);
            deformers.add(child);
            set(model, "deformers", deformers);
            set(model, "paths", new ArrayList<>());
            set(model, "affecters", new ArrayList<>());
            set(model, "drawables", new ArrayList<>());
            Map<Object, Object> guidMap = new HashMap<>();
            guidMap.put(parentGuid, parent);
            guidMap.put(childGuid, child);
            set(model, "guidMap", guidMap);
            Object paramSet = make(loader, M + "param/CParameterSet");
            set(paramSet, "params", new ArrayList<>());
            set(paramSet, "version", 1);
            set(model, "paramSet", paramSet);
            mesh = make(loader, M + "drawable/artMesh/CArtMesh");
            meshForm = make(loader, M + "drawable/artMesh/CArtMeshForm");
            set(meshForm, "positions", new float[] {1f, 2f});
            set(mesh, "iform", meshForm);
            set(mesh, "form", meshForm);
            deformedOut = make(loader, M + "drawable/artMesh/CArtMeshForm");
        }

        Object[] epoch() { return new Object[] {model, ctx}; }
    }

    private static IncrementalUpdateBridge installed(Loader loader) throws Exception {
        IncrementalUpdateBridge bridge = new IncrementalUpdateBridge(T5303, loader);
        bridge.install();
        return bridge;
    }

    private static Properties props() { return System.getProperties(); }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> begin() {
        return (Consumer<Object>) props().get(IncrementalUpdateBridge.BEGIN_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> end() {
        return (Consumer<Object>) props().get(IncrementalUpdateBridge.END_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private static BiConsumer<Object, Object> mark() {
        return (BiConsumer<Object, Object>) props().get(IncrementalUpdateBridge.MARK_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private static Function<Object[], Object> deform() {
        return (Function<Object[], Object>) props().get(IncrementalUpdateBridge.DEFORM_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> form() {
        return (Consumer<Object>) props().get(IncrementalUpdateBridge.FORM_PROPERTY);
    }

    @AfterEach void cleanup() {
        System.clearProperty(IncrementalUpdateBridge.ENABLE_PROPERTY);
        System.clearProperty(IncrementalUpdateBridge.PROBE_PROPERTY);
        System.clearProperty(IncrementalUpdateBridge.RESULT_PROPERTY);
    }

    @Test void installOccupiesSlotsAndEnablesHostFlag() throws Exception {
        Loader loader = host();
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            assertNotNull(begin());
            assertNotNull(end());
            assertNotNull(mark());
            assertNotNull(deform());
            assertNotNull(form());
            assertNotNull(props().get(IncrementalUpdateBridge.STATS_PROPERTY));
            assertTrue((boolean) field(loader.of(N + "view/ay"), "e"));
        } finally {
            bridge.close();
        }
        assertNull(props().get(IncrementalUpdateBridge.BEGIN_PROPERTY));
        assertFalse((boolean) field(loader.of(N + "view/ay"), "e"));
    }

    @Test void unchangedEpochMarksNothingDirty() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            mark().accept(world.parent, world.model);
            mark().accept(world.child, world.model);
            assertFalse((boolean) field(world.parent, "dirty"));
            assertFalse((boolean) field(world.child, "dirty"));
            Map<String, Long> stats = bridge.snapshot();
            assertEquals(2L, stats.get("markedClean"));
            assertEquals(0L, stats.get("markedDirty"));
        } finally {
            bridge.close();
        }
    }

    @Test void interpolatedFormMarksSelfAndDescendants() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            form().accept(world.parentForm);
            mark().accept(world.parent, world.model);
            assertTrue((boolean) field(world.parent, "dirty"));
            // Eager propagation: the child was flag-set even though its own mark ran clean path.
            assertTrue((boolean) field(world.child, "dirty"));
            Map<String, Long> stats = bridge.snapshot();
            assertEquals(1L, stats.get("markedDirty"));
            assertEquals(1L, stats.get("descendantsMarked"));
        } finally {
            bridge.close();
        }
    }

    @Test void childMarkedDirtyWhenAncestorChanged() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            // Child processed BEFORE parent: ancestor check finds nothing, stays clean.
            mark().accept(world.child, world.model);
            assertFalse((boolean) field(world.child, "dirty"));
            // Parent later turns out changed: eager descendant marking fixes the child flag.
            form().accept(world.parentForm);
            mark().accept(world.parent, world.model);
            assertTrue((boolean) field(world.parent, "dirty"));
            assertTrue((boolean) field(world.child, "dirty"));
        } finally {
            bridge.close();
        }
    }

    @Test void animatedFormMarksDirty() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            set(world.parent, "aform", make(loader, N + "doc/model/deformer/warp/CWarpDeformerForm"));
            mark().accept(world.parent, world.model);
            assertTrue((boolean) field(world.parent, "dirty"));
        } finally {
            bridge.close();
        }
    }

    @Test void disabledEnableMarksEverything() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "false");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            mark().accept(world.parent, world.model);
            mark().accept(world.child, world.model);
            assertTrue((boolean) field(world.parent, "dirty"));
            assertTrue((boolean) field(world.child, "dirty"));
            assertFalse((boolean) field(loader.of(N + "view/ay"), "e"));
        } finally {
            bridge.close();
        }
    }

    @Test void artMeshDeformSkippedWhenUnchanged() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            mark().accept(world.parent, world.model);
            mark().accept(world.child, world.model);
            Object out = deform().apply(new Object[] {
                world.meshForm, world.child, world.deformedOut, world.mesh});
            assertSame(world.deformedOut, out);
            assertEquals(0, (int) field(loader.of(N + "doc/model/drawable/artMesh/CArtMeshForm"),
                "transformCalls"));
            Map<String, Long> stats = bridge.snapshot();
            assertEquals(1L, stats.get("meshSkipped"));
        } finally {
            bridge.close();
        }
    }

    @Test void artMeshDeformRunsWhenTargetDirty() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            form().accept(world.childForm);
            mark().accept(world.child, world.model);
            Object out = deform().apply(new Object[] {
                world.meshForm, world.child, world.deformedOut, world.mesh});
            assertSame(world.meshForm, out);
            assertEquals(1, (int) field(loader.of(N + "doc/model/drawable/artMesh/CArtMeshForm"),
                "transformCalls"));
        } finally {
            bridge.close();
        }
    }

    @Test void artMeshDeformRunsWhenOwnFormChanged() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            form().accept(world.meshForm);
            deform().apply(new Object[] {
                world.meshForm, world.child, world.deformedOut, world.mesh});
            assertEquals(1, (int) field(loader.of(N + "doc/model/drawable/artMesh/CArtMeshForm"),
                "transformCalls"));
        } finally {
            bridge.close();
        }
    }

    @Test void artPathPresenceForcesDeform() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        set(world.model, "paths", List.of(new Object()));
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            deform().apply(new Object[] {
                world.meshForm, world.child, world.deformedOut, world.mesh});
            assertEquals(1, (int) field(loader.of(N + "doc/model/drawable/artMesh/CArtMeshForm"),
                "transformCalls"));
        } finally {
            bridge.close();
        }
    }

    @Test void probeAlternatesFullAndComparesDigests() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        Object drawable = make(loader, N + "doc/model/drawable/ACDrawable");
        set(drawable, "form", world.meshForm);
        set(world.model, "drawables", List.of(drawable));
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        System.setProperty(IncrementalUpdateBridge.PROBE_PROPERTY, "true");
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            // Epoch 1: forced full baseline.
            begin().accept(world.epoch());
            assertFalse((boolean) field(loader.of(N + "view/ay"), "e"));
            end().accept(world.epoch());
            // Epoch 2: narrowed.
            begin().accept(world.epoch());
            assertTrue((boolean) field(loader.of(N + "view/ay"), "e"));
            end().accept(world.epoch());
            // Epoch 3: forced full → digest compared against epoch 2.
            begin().accept(world.epoch());
            assertFalse((boolean) field(loader.of(N + "view/ay"), "e"));
            end().accept(world.epoch());
            Map<String, Long> stats = bridge.snapshot();
            assertEquals(1L, stats.get("probePairs"));
            assertEquals(0L, stats.get("probeMismatch"));
        } finally {
            bridge.close();
        }
    }

    @Test void digestMismatchRecordsProbeFailure() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        Object drawable = make(loader, N + "doc/model/drawable/ACDrawable");
        set(drawable, "form", world.meshForm);
        set(world.model, "drawables", List.of(drawable));
        java.nio.file.Path report = java.nio.file.Files.createTempFile("inc-probe", ".jsonl");
        System.setProperty(IncrementalUpdateBridge.ENABLE_PROPERTY, "true");
        System.setProperty(IncrementalUpdateBridge.PROBE_PROPERTY, "true");
        System.setProperty(IncrementalUpdateBridge.RESULT_PROPERTY, report.toString());
        IncrementalUpdateBridge bridge = installed(loader);
        try {
            begin().accept(world.epoch());
            end().accept(world.epoch());
            begin().accept(world.epoch());
            end().accept(world.epoch());
            // Mutate mesh output between narrowed and forced-full epochs.
            set(world.meshForm, "positions", new float[] {9f, 9f});
            // inputsVersion must stay equal: positions are not an input term.
            begin().accept(world.epoch());
            end().accept(world.epoch());
            Map<String, Long> stats = bridge.snapshot();
            assertEquals(1L, stats.get("probeMismatch"));
        } finally {
            bridge.close();
            java.nio.file.Files.deleteIfExists(report);
        }
    }

    @Test void closeRestoresPriorSkipFlag() throws Exception {
        Loader loader = host();
        set(loader.of(N + "view/ay"), "e", true);
        IncrementalUpdateBridge bridge = installed(loader);
        bridge.close();
        assertTrue((boolean) field(loader.of(N + "view/ay"), "e"));
    }
}
