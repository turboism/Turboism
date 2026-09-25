package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * End-to-end bridge tests against an ASM-emitted stub host carrying every reviewed
 * dependency signature. No Cubism classes are loaded; the stub world reproduces the
 * exact getter surface the bridge resolves.
 */
public class ModelUpdateSkipBridgeTest {

    private static final String N = "com/live2d/cubism/";
    private static final ModelUpdateSkipTarget T5303 =
        ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();

    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();
        Loader() { super(ModelUpdateSkipBridgeTest.class.getClassLoader()); }
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
            w.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null,
                "java/lang/Object", null);
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
        if (statics.length > 0) {
            m = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            m.visitCode();
            for (String field : statics) {
                String[] p = field.split(":", 2);
                m.visitTypeInsn(NEW, name);
                m.visitInsn(DUP);
                m.visitMethodInsn(INVOKESPECIAL, name, "<init>", "()V", false);
                m.visitFieldInsn(PUTSTATIC, name, p[0], p[1]);
            }
            m.visitInsn(RETURN);
            m.visitMaxs(2, 0);
            m.visitEnd();
        }
        for (String[] method : methods) {
            String ret = method[1].substring(method[1].indexOf(')') + 1);
            m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, method[0], method[1], null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, name, method[2], ret);
            m.visitInsn(ret.startsWith("L") || ret.startsWith("[") ? ARETURN
                : ret.equals("J") ? LRETURN : ret.equals("F") ? FRETURN
                : ret.equals("D") ? DRETURN : IRETURN);
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

    /** The complete 5.3.03 dependency surface. */
    private static Loader host() {
        String C = N + "view/context/", S = N + "setting/AppSetting$", M = N + "doc/model/";
        return new Loader()
            .add("com/live2d/cubism/doc/IDocument",
                emit("com/live2d/cubism/doc/IDocument", "interface", null, f(), f(), m()))
            .add("com/live2d/doc/IEditMode",
                emit("com/live2d/doc/IEditMode", "interface", null, f(), f(), m()))
            .add(N + "doc/modeling/CModelingDocument",
                emit(N + "doc/modeling/CModelingDocument", null,
                    new String[]{"com/live2d/cubism/doc/IDocument"},
                    f("lastModified:J"), f(),
                    m(g("getLastModifiedTime", "()J", "lastModified"))))
            .add(N + "doc/modeling/CModelingEditMode_Main",
                emit(N + "doc/modeling/CModelingEditMode_Main", null,
                    new String[]{"com/live2d/doc/IEditMode"}, f(), f(), m()))
            .add(C + "CEViewContext",
                emit(C + "CEViewContext", null, null,
                    f("doc:Lcom/live2d/cubism/doc/IDocument;",
                        "develop:L" + C + "bS$b;", "appear:L" + C + "bS$a;",
                        "editMode:Lcom/live2d/doc/IEditMode;"), f(),
                    m(g("getDoc", "()Lcom/live2d/cubism/doc/IDocument;", "doc"),
                        g("getDevelopSetting", "()L" + C + "bS$b;", "develop"),
                        g("getAppearanceSetting", "()L" + C + "bS$a;", "appear"),
                        g("getCurrentEditMode", "()Lcom/live2d/doc/IEditMode;", "editMode"))))
            .add(C + "CEViewContext_ModelingView",
                emit(C + "CEViewContext_ModelingView", C + "CEViewContext", null,
                    f("z1:Z", "z2:Z", "z3:Z", "viewMode:L" + C + "CEViewContext_ModelingView$c;"),
                    f(),
                    m(g("isRandomPoseAnimation", "()Z", "z1"),
                        g("isExternalAppAnimation", "()Z", "z2"),
                        g("isRecording", "()Z", "z3"),
                        g("getCurrentViewMode", "()L" + C + "CEViewContext_ModelingView$c;", "viewMode"))))
            .add(C + "CEViewContext_ModelingView$c",
                emit(C + "CEViewContext_ModelingView$c", null, null, f(), f(), m()))
            .add(C + "bS$b", emit(C + "bS$b", null, null, f("h:Z", "k:Z"), f(),
                m(g("h", "()Z", "h"), g("k", "()Z", "k"))))
            .add(C + "bS$a", emit(C + "bS$a", null, null, f("d:F"), f(), m(g("d", "()F", "d"))))
            .add(C + "bL", emit(C + "bL", null, null, f(), f(), m()))
            .add(M + "CModel",
                emit(M + "CModel", null, null,
                    f("cur:L" + M + "param/CParameterSet;", "last:L" + M + "param/CParameterSet;",
                        "source:L" + M + "CModelSource;", "drawables:Ljava/util/List;"), f(),
                    m(g("getParameterSet", "()L" + M + "param/CParameterSet;", "cur"),
                        g("getLastUpdatedParameterSet", "()L" + M + "param/CParameterSet;", "last"),
                        g("getSource", "()L" + M + "CModelSource;", "source"),
                        g("getAllDrawables", "()Ljava/util/List;", "drawables"))))
            .add(M + "CModelSource", emit(M + "CModelSource", null, null, f("editing:Z"), f(),
                m(g("isModelEditing", "()Z", "editing"))))
            .add(M + "param/CParameterSet",
                emit(M + "param/CParameterSet", null, null, f("params:Ljava/util/List;", "version:I"),
                    f(), m(g("getParameters", "()Ljava/util/List;", "params"),
                        g("getUpdateVersion", "()I", "version"))))
            .add(M + "param/CParameter",
                emit(M + "param/CParameter", null, null,
                    f("value:F", "id:L" + M + "id/CParameterId;"), f(),
                    m(g("getValue", "()F", "value"),
                        g("getId", "()L" + M + "id/CParameterId;", "id"))))
            .add(M + "id/CParameterId", emit(M + "id/CParameterId", null, null, f(), f(), m()))
            .add(M + "ax",
                emit(M + "ax", null, null,
                    f("a:Z", "b:Z", "c:F", "d:Z", "e:Z", "f:Z",
                        "g:L" + C + "CEViewContext_ModelingView;",
                        "h:L" + N + "doc/modeling/CModelingEditMode_Main;",
                        "i:Ljava/util/List;", "l:Ljava/util/ArrayList;",
                        "m:Ljava/lang/Integer;", "n:Ljava/util/ArrayList;"), f(),
                    m(g("a", "()Z", "a"), g("b", "()Z", "b"), g("c", "()F", "c"),
                        g("d", "()Z", "d"), g("e", "()Z", "e"), g("f", "()Z", "f"),
                        g("g", "()L" + C + "CEViewContext_ModelingView;", "g"),
                        g("h", "()L" + N + "doc/modeling/CModelingEditMode_Main;", "h"),
                        g("i", "()Ljava/util/List;", "i"),
                        g("l", "()Ljava/util/ArrayList;", "l"),
                        g("m", "()Ljava/lang/Integer;", "m"),
                        g("n", "()Ljava/util/ArrayList;", "n"))))
            .add(N + "doc/animation/formAnimation/t",
                emit(N + "doc/animation/formAnimation/t", null, null, f("z:Z"),
                    f("a:L" + N + "doc/animation/formAnimation/t;"),
                    m(g("a", "(L" + C + "CEViewContext;)Z", "z"))))
            .add(N + "setting/AppSetting",
                emit(N + "setting/AppSetting", null, null,
                    f("draw:L" + S + "DrawSetting;", "gui:L" + S + "GuiSetting;",
                        "canvas:L" + S + "CanvasSetting;", "developer:L" + S + "DeveloperSetting;"),
                    f("INSTANCE:L" + N + "setting/AppSetting;"),
                    m(g("getDraw", "()L" + S + "DrawSetting;", "draw"),
                        g("getGui", "()L" + S + "GuiSetting;", "gui"),
                        g("getCanvas", "()L" + S + "CanvasSetting;", "canvas"),
                        g("getDeveloper", "()L" + S + "DeveloperSetting;", "developer"))))
            .add(S + "DrawSetting",
                emit(S + "DrawSetting", null, null, f("a:Z", "b:Z", "c:Z", "d:Z"), f(),
                    m(g("getOptimizeArtMesh", "()Z", "a"), g("getOptimizeDeformer", "()Z", "b"),
                        g("getOptimizeDrawOrder", "()Z", "c"), g("getOptimizeHierarchy", "()Z", "d"))))
            .add(S + "GuiSetting",
                emit(S + "GuiSetting", null, null, f("warn:L" + S + "Warning;"), f(),
                    m(g("getWarning", "()L" + S + "Warning;", "warn"))))
            .add(S + "Warning",
                emit(S + "Warning", null, null, f("x:Z", "y:Z"), f(),
                    m(g("isVisibleMaskWarning", "()Z", "x"),
                        g("isBlendModeAppearanceWarning", "()Z", "y"))))
            .add(S + "CanvasSetting", emit(S + "CanvasSetting", null, null, f("x:Z"), f(),
                m(g("getHideSelectedState", "()Z", "x"))))
            .add(S + "DeveloperSetting", emit(S + "DeveloperSetting", null, null, f("x:Z"), f(),
                m(g("getHighLightDeformerChild", "()Z", "x"))))
            .add(M + "drawable/ACDrawable",
                emit(M + "drawable/ACDrawable", null, null,
                    f("form:L" + M + "drawable/ACDrawableForm;", "order:I"), f(),
                    m(g("getDeformedForm", "()L" + M + "drawable/ACDrawableForm;", "form"),
                        g("getDrawOrder", "()I", "order"))))
            .add(M + "drawable/ACDrawableForm",
                emit(M + "drawable/ACDrawableForm", null, null, f(), f(), m()))
            .add(M + "drawable/artMesh/CArtMeshForm",
                emit(M + "drawable/artMesh/CArtMeshForm", M + "drawable/ACDrawableForm", null,
                    f("positions:[F"), f(), m(g("getPositions", "()[F", "positions"))))
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
            .add("com/live2d/type/CArrayList",
                emit("com/live2d/type/CArrayList", "java/util/ArrayList", null, f(), f(), m()))
            .add(N + "view/ay",
                emit(N + "view/ay", null, null, f("a:Z", "b:Z"), f(),
                    m(g("a", "()Z", "a"), g("b", "()Z", "b"))));
    }

    private static Object make(Loader loader, String name) {
        try { return loader.of(name).getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getField(field);
            if (f.getType() == boolean.class && value instanceof Boolean b) f.setBoolean(target, b);
            else if (f.getType() == int.class && value instanceof Integer i) f.setInt(target, i);
            else if (f.getType() == long.class && value instanceof Long l) f.setLong(target, l);
            else if (f.getType() == float.class && value instanceof Float x) f.setFloat(target, x);
            else f.set(target, value);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static Object field(Object target, String name) {
        try { return target.getClass().getField(name).get(target); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    /** A fully consistent 5.3.03 host world whose update inputs never drift. */
    private static final class World {
        final Object updater, view, doc, model, ax, source, paramSet, lastUpdated;
        final Object meshForm, drawable, appSetting, formAnimation;
        final List<Object> currentParams = new ArrayList<>(), updatedParams = new ArrayList<>();

        World(Loader loader) {
            updater = make(loader, N + "view/ay");
            view = make(loader, N + "view/context/CEViewContext_ModelingView");
            doc = make(loader, N + "doc/modeling/CModelingDocument");
            set(doc, "lastModified", 42L);
            set(view, "doc", doc);
            Object develop = make(loader, N + "view/context/bS$b");
            set(view, "develop", develop);
            Object appear = make(loader, N + "view/context/bS$a");
            set(appear, "d", 0.5f);
            set(view, "appear", appear);
            Object editMode = make(loader, N + "doc/modeling/CModelingEditMode_Main");
            set(view, "editMode", editMode);
            set(view, "viewMode", make(loader, N + "view/context/CEViewContext_ModelingView$c"));
            appSetting = make(loader, N + "setting/AppSetting");
            set(appSetting, "draw", make(loader, N + "setting/AppSetting$DrawSetting"));
            Object gui = make(loader, N + "setting/AppSetting$GuiSetting");
            set(gui, "warn", make(loader, N + "setting/AppSetting$Warning"));
            set(appSetting, "gui", gui);
            set(appSetting, "canvas", make(loader, N + "setting/AppSetting$CanvasSetting"));
            set(appSetting, "developer", make(loader, N + "setting/AppSetting$DeveloperSetting"));
            try { loader.of(N + "setting/AppSetting").getField("INSTANCE").set(null, appSetting); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            try {
                formAnimation = loader.of(N + "doc/animation/formAnimation/t")
                    .getField("a").get(null);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            source = make(loader, N + "doc/model/CModelSource");
            paramSet = make(loader, N + "doc/model/param/CParameterSet");
            lastUpdated = make(loader, N + "doc/model/param/CParameterSet");
            for (int i = 0; i < 3; i++) {
                Object id = make(loader, N + "doc/model/id/CParameterId");
                Object current = make(loader, N + "doc/model/param/CParameter");
                set(current, "value", (float) i);
                set(current, "id", id);
                currentParams.add(current);
                Object updated = make(loader, N + "doc/model/param/CParameter");
                set(updated, "value", (float) i);
                set(updated, "id", id);
                updatedParams.add(updated);
            }
            set(paramSet, "params", currentParams);
            set(paramSet, "version", 7);
            set(lastUpdated, "params", updatedParams);
            set(lastUpdated, "version", 7);
            meshForm = make(loader, N + "doc/model/drawable/artMesh/CArtMeshForm");
            set(meshForm, "positions", new float[]{1f, 2f, 3f, 4f});
            drawable = make(loader, N + "doc/model/drawable/ACDrawable");
            set(drawable, "form", meshForm);
            set(drawable, "order", 5);
            model = make(loader, N + "doc/model/CModel");
            set(model, "cur", paramSet);
            set(model, "last", lastUpdated);
            set(model, "source", source);
            set(model, "drawables", List.of(drawable));
            ax = make(loader, N + "doc/model/ax");
            set(ax, "g", view);
            set(ax, "h", editMode);
            set(ax, "i", List.of());
            set(ax, "l", new ArrayList<>());
            set(ax, "m", Integer.valueOf(3));
            set(ax, "n", new ArrayList<>());
        }

        Object[] args() {
            return new Object[]{updater, view, model, Boolean.FALSE, ax, Boolean.FALSE, null,
                Boolean.FALSE};
        }
    }

    @SuppressWarnings("unchecked")
    private static Predicate<Object[]> predicate() {
        return (Predicate<Object[]>) System.getProperties()
            .get(ModelUpdateSkipBridge.CALLBACK_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> after() {
        return (Consumer<Object>) System.getProperties()
            .get(ModelUpdateSkipBridge.AFTER_PROPERTY);
    }

    private static void withProperties(String... pairs) {
        PropertiesBackup.restore();
        for (int i = 0; i < pairs.length; i += 2) {
            System.setProperty(pairs[i], pairs[i + 1]);
        }
    }

    private static final class PropertiesBackup {
        private static final Map<String, Object> saved = new HashMap<>();
        static void restore() {
            for (String key : List.of(ModelUpdateSkipBridge.ENABLE_PROPERTY,
                    ModelUpdateSkipBridge.PROBE_PROPERTY, ModelUpdateSkipBridge.RESULT_PROPERTY,
                    ModelUpdateSkipBridge.TIMING_PROPERTY)) {
                if (!saved.containsKey(key)) {
                    saved.put(key, System.getProperties().get(key));
                }
            }
        }
        static void revert() {
            for (var entry : saved.entrySet()) {
                if (entry.getValue() == null) System.getProperties().remove(entry.getKey());
                else System.getProperties().put(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test void installsAndSkipsUnchangedFrame() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        try (ModelUpdateSkipBridge bridge = new ModelUpdateSkipBridge(T5303, loader)) {
            bridge.install();
            withProperties(ModelUpdateSkipBridge.ENABLE_PROPERTY, "true");
            try {
                assertNotNull(predicate());
                assertNotNull(after());
                assertNotNull(System.getProperties().get(ModelUpdateSkipBridge.STATS_PROPERTY));
                assertFalse(predicate().test(world.args()), "no completed baseline");
                after().accept(world.model);
                assertTrue(predicate().test(world.args()), "unchanged inputs");
            } finally {
                PropertiesBackup.revert();
            }
        }
    }

    @Test void everyDriftForcesFullUpdate() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        try (ModelUpdateSkipBridge bridge = new ModelUpdateSkipBridge(T5303, loader)) {
            bridge.install();
            withProperties(ModelUpdateSkipBridge.ENABLE_PROPERTY, "true");
            try {
                assertFalse(predicate().test(world.args()));
                after().accept(world.model);
                assertTrue(predicate().test(world.args()), "baseline");

                set(world.currentParams.get(0), "value", 9f);
                assertFalse(predicate().test(world.args()), "parameter value drift");
                set(world.currentParams.get(0), "value", 0f);
                assertTrue(predicate().test(world.args()));

                Object foreignId = make(loader, N + "doc/model/id/CParameterId");
                set(world.currentParams.get(0), "id", foreignId);
                assertFalse(predicate().test(world.args()), "parameter identity drift");
                set(world.currentParams.get(0), "id", field(world.updatedParams.get(0), "id"));

                set(world.doc, "lastModified", 43L);
                assertFalse(predicate().test(world.args()), "document generation drift");
                set(world.doc, "lastModified", 42L);

                set(world.view, "z3", true);
                assertFalse(predicate().test(world.args()), "recording");
                set(world.view, "z3", false);

                set(world.source, "editing", true);
                assertFalse(predicate().test(world.args()), "model editing");
                set(world.source, "editing", false);

                set(world.ax, "a", true);
                assertFalse(predicate().test(world.args()), "mesh-edit context");
                set(world.ax, "a", false);

                set(world.ax, "i", List.of(new Object()));
                assertFalse(predicate().test(world.args()), "selection drift");
                set(world.ax, "i", List.of());

                set(field(world.view, "develop"), "h", true);
                assertFalse(predicate().test(world.args()), "develop setting h");
                set(field(world.view, "develop"), "h", false);

                set(world.formAnimation, "z", true);
                assertFalse(predicate().test(world.args()), "form animation gate");
                set(world.formAnimation, "z", false);

                Object[] movie = world.args();
                movie[6] = new Object();
                assertFalse(predicate().test(movie), "context param present");

                Object[] conflict = world.args();
                conflict[7] = Boolean.TRUE;
                assertFalse(predicate().test(conflict), "conflict polygon");

                Object[] animated = world.args();
                animated[3] = Boolean.TRUE;
                assertFalse(predicate().test(animated), "animation arg");

                Object[] formArg = world.args();
                formArg[5] = Boolean.TRUE;
                assertFalse(predicate().test(formArg), "form-animation arg");

                set(world.ax, "l", new ArrayList<>(List.of(new Object())));
                assertFalse(predicate().test(world.args()), "pre-populated alias stack");
                set(world.ax, "l", new ArrayList<>());

                set(world.ax, "m", Integer.valueOf(4));
                assertFalse(predicate().test(world.args()), "render hash drift");
                set(world.ax, "m", Integer.valueOf(3));

                set(field(world.appSetting, "draw"), "a", true);
                assertFalse(predicate().test(world.args()), "optimize-artmesh setting drift");
                set(field(world.appSetting, "draw"), "a", false);

                set(world.updater, "a", true);
                assertFalse(predicate().test(world.args()), "updater flag drift");
                set(world.updater, "a", false);

                Object[] missingView = world.args();
                missingView[1] = make(loader, N + "view/context/CEViewContext");
                assertFalse(predicate().test(missingView), "non-modeling context");

                assertTrue(predicate().test(world.args()), "all restored");
            } finally {
                PropertiesBackup.revert();
            }
        }
    }

    @Test void failClosedOnMalformedOrAlienArguments() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        try (ModelUpdateSkipBridge bridge = new ModelUpdateSkipBridge(T5303, loader)) {
            bridge.install();
            withProperties(ModelUpdateSkipBridge.ENABLE_PROPERTY, "true");
            try {
                assertFalse(predicate().test(null));
                assertFalse(predicate().test(new Object[]{world.updater}));
                assertFalse(predicate().test(world.args()));
                after().accept(world.model);
                assertTrue(predicate().test(world.args()));
                Object[] alien = world.args();
                alien[4] = "not an update context";
                assertFalse(predicate().test(alien));
                assertTrue(bridge.snapshot().get("failures") > 0);
                assertTrue(predicate().test(world.args()), "baseline unaffected by the failure");
            } finally {
                PropertiesBackup.revert();
            }
        }
    }

    @Test void probeModeRunsNativeAndDiffsDigests() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        try (ModelUpdateSkipBridge bridge = new ModelUpdateSkipBridge(T5303, loader)) {
            bridge.install();
            withProperties(ModelUpdateSkipBridge.ENABLE_PROPERTY, "true",
                ModelUpdateSkipBridge.PROBE_PROPERTY, "true");
            try {
                assertFalse(predicate().test(world.args()));
                after().accept(world.model);
                assertFalse(predicate().test(world.args()), "probe never skips");
                after().accept(world.model);
                assertEquals(0L, (long) bridge.snapshot().get("probeMismatch"));
                assertTrue(bridge.snapshot().get("digestNanos") > 0, "digests were measured");
                assertFalse(predicate().test(world.args()), "decided skip runs native in probe");
                set(world.meshForm, "positions", new float[]{9f, 9f, 9f, 9f});
                after().accept(world.model);
                assertEquals(1L, (long) bridge.snapshot().get("probeMismatch"),
                    "geometry changed between decision and completion");
            } finally {
                PropertiesBackup.revert();
            }
        }
    }

    @Test void probeDigestFailureCountsMismatchAndWritesReport() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        final Path report = Files.createTempFile("mus-probe", ".jsonl");
        try (ModelUpdateSkipBridge bridge = new ModelUpdateSkipBridge(T5303, loader)) {
            bridge.install();
            withProperties(ModelUpdateSkipBridge.ENABLE_PROPERTY, "true",
                ModelUpdateSkipBridge.PROBE_PROPERTY, "true",
                ModelUpdateSkipBridge.RESULT_PROPERTY, report.toString());
            try {
                assertFalse(predicate().test(world.args()));
                after().accept(world.model);
                set(world.drawable, "form", null);
                assertFalse(predicate().test(world.args()),
                    "null form digests as an absent-value marker");
                set(world.model, "drawables", java.util.Arrays.asList((Object) null));
                assertFalse(predicate().test(world.args()), "undigestable frame runs native");
                assertEquals(1L, (long) bridge.snapshot().get("probeMismatch"));
                assertEquals(0L, (long) bridge.snapshot().get("failures"),
                    "digest errors are mismatches, not generic failures");
                final String json = Files.readString(report);
                assertTrue(json.contains("\"error\""), "report records the failure");
                assertTrue(json.contains("null drawable"), "report names the cause");
            } finally {
                PropertiesBackup.revert();
                Files.deleteIfExists(report);
            }
        }
    }

    @Test void disabledAndClosedSlotsNeverSkip() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        try (ModelUpdateSkipBridge bridge = new ModelUpdateSkipBridge(T5303, loader)) {
            bridge.install();
            System.setProperty(ModelUpdateSkipBridge.ENABLE_PROPERTY, "false");
            assertFalse(predicate().test(world.args()), "enable property false");
            bridge.close();
            assertNull(System.getProperties().get(ModelUpdateSkipBridge.CALLBACK_PROPERTY));
            assertNull(System.getProperties().get(ModelUpdateSkipBridge.AFTER_PROPERTY));
            assertNull(System.getProperties().get(ModelUpdateSkipBridge.STATS_PROPERTY));
        }
    }

    @Test void installRefusesOccupiedSlotsAndReinstallsAfterClose() throws Exception {
        Loader loader = host();
        ModelUpdateSkipBridge first = new ModelUpdateSkipBridge(T5303, loader);
        ModelUpdateSkipBridge second = new ModelUpdateSkipBridge(T5303, loader);
        first.install();
        assertThrows(IllegalStateException.class, second::install);
        assertThrows(IllegalStateException.class, first::install);
        first.close();
        second.install();
        second.close();
        assertNull(System.getProperties().get(ModelUpdateSkipBridge.CALLBACK_PROPERTY));
        first.close();
    }

    @Test void countersTrackCallsSkipsFulls() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        try (ModelUpdateSkipBridge bridge = new ModelUpdateSkipBridge(T5303, loader)) {
            bridge.install();
            withProperties(ModelUpdateSkipBridge.ENABLE_PROPERTY, "true");
            try {
                predicate().test(world.args());
                after().accept(world.model);
                predicate().test(world.args());
                predicate().test(world.args());
                Map<String, Long> stats = bridge.snapshot();
                assertEquals(3L, (long) stats.get("calls"));
                assertEquals(2L, (long) stats.get("skipped"));
                assertEquals(1L, (long) stats.get("full"));
                assertTrue(stats.get("predicateNanos") > 0);
                assertTrue(stats.containsKey("predicateMaxNanos"));
                assertEquals(2L, (long) stats.get("decidedSkip"));
                assertEquals(0L, (long) stats.get("readFrameSamples"));
                assertEquals(0L, (long) stats.get("decisionSamples"));
            } finally {
                PropertiesBackup.revert();
            }
        }
    }

    @Test void diagnosticsExposeBlockerAndSeparateHotPathCosts() throws Exception {
        Loader loader = host();
        World world = new World(loader);
        try (ModelUpdateSkipBridge bridge = new ModelUpdateSkipBridge(T5303, loader)) {
            bridge.install();
            withProperties(ModelUpdateSkipBridge.ENABLE_PROPERTY, "true",
                ModelUpdateSkipBridge.TIMING_PROPERTY, "true");
            try {
                predicate().test(world.args());
                after().accept(world.model);
                set(world.currentParams.get(0), "value", 9f);
                assertFalse(predicate().test(world.args()));
                Map<String, Long> stats = bridge.snapshot();
                assertEquals(1L, (long) stats.get("reject.PARAMETERS_CHANGED"));
                assertTrue(stats.get("readFrameNanos") > 0L);
                assertTrue(stats.get("decisionNanos") > 0L);
                assertTrue(stats.containsKey("readFrameMaxNanos"));
                assertTrue(stats.containsKey("decisionMaxNanos"));
                assertEquals(3L, (long) stats.get("parameterCount"));
                assertEquals(2L, (long) stats.get("readFrameSamples"));
                assertEquals(2L, (long) stats.get("decisionSamples"));
                assertEquals(1L, (long) stats.get("reject.NO_BASELINE"));
                assertEquals(0L, (long) stats.get("decidedSkip"));
                System.setProperty(ModelUpdateSkipBridge.TIMING_PROPERTY, "false");
                assertFalse(predicate().test(world.args()));
                Map<String, Long> untimed = bridge.snapshot();
                assertEquals(2L, (long) untimed.get("reject.PARAMETERS_CHANGED"));
                assertEquals(stats.get("readFrameSamples"), untimed.get("readFrameSamples"));
                assertEquals(stats.get("decisionSamples"), untimed.get("decisionSamples"));
            } finally {
                PropertiesBackup.revert();
            }
        }
    }
}
