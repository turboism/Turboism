package dev.turboism.adapter.cubism.optimization.modelupdate.incremental;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Verifies the five guarded injection sites on synthetic classes carrying the exact
 * reviewed descriptors and call-site patterns for every supported version. Bytes stand
 * in for the official host shapes; admission still requires the reviewed artifact path.
 */
public class IncrementalUpdateTransformerTest {

    private static final String N = "com/live2d/cubism/";
    private static final String CTX = N + "view/context/CEViewContext";
    private static final String MODEL = N + "doc/model/CModel";
    private static final String PSET = N + "doc/model/param/CParameterSet";
    private static final String AX = N + "doc/model/ax";
    private static final String BL = N + "view/context/bL";
    private static final String ACD = N + "doc/model/deformer/ACDeformer";
    private static final String CAM = N + "doc/model/drawable/artMesh/CArtMesh";
    private static final String MFORM = N + "doc/model/drawable/artMesh/CArtMeshForm";
    private static final String ACFORM = N + "doc/model/ACForm";
    private static final String SELD = "com/live2d/doc/selection/d";
    private static final String KGS = N + "doc/model/interpolator/KeyformGridSource";
    private static final String RFORM = N + "doc/model/deformer/rotation/CRotationDeformerForm";
    private static final String WFORM = N + "doc/model/deformer/warp/CWarpDeformerForm";
    private static final String DFORM = N + "doc/model/deformer/ACDeformerForm";

    private static final IncrementalUpdateTarget T5303 =
        IncrementalUpdateTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();

    /** Child-first for registered stubs: the test classpath carries com.live2d stand-ins. */
    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;
        private final Map<String, byte[]> classes = new HashMap<>();
        Loader(Path artifact) throws Exception {
            super(IncrementalUpdateTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null),
                new Permissions());
        }
        Loader add(String name, byte[] bytes) {
            classes.put(name.replace('/', '.'), bytes);
            return this;
        }
        Class<?> of(String name) {
            try { return loadClass(name.replace('/', '.')); }
            catch (ClassNotFoundException failure) { throw new IllegalStateException(failure); }
        }
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
            return defineClass(name, bytes, 0, bytes.length, domain);
        }
    }

    private static byte[] empty(String name, String sup) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, name, null, sup == null ? "java/lang/Object" : sup, null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, sup == null ? "java/lang/Object" : sup,
            "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** Stub ACDeformer: counted setDirtyDeformedForm + getCreateLocalToCanvasTransform. */
    private static byte[] deformer() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, ACD, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "setDirtyCalls", "I", null, null).visitEnd();
        w.visitField(ACC_PUBLIC | ACC_STATIC, "ltctCalls", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "setDirtyDeformedForm", "(Z)V", null, null);
        m.visitCode();
        m.visitFieldInsn(GETSTATIC, ACD, "setDirtyCalls", "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTSTATIC, ACD, "setDirtyCalls", "I");
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "getCreateLocalToCanvasTransform",
            "()Lcom/live2d/doc/selection/d;", null, null);
        m.visitCode();
        m.visitFieldInsn(GETSTATIC, ACD, "ltctCalls", "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTSTATIC, ACD, "ltctCalls", "I");
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** Stub CArtMeshForm: counted transform + interpolate__testImpl body. */
    private static byte[] meshForm(final String descriptor) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, MFORM, null, ACFORM, null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "transformCalls", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, ACFORM, "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "transform",
            "(Lcom/live2d/doc/selection/d;L" + ACFORM + ";)L" + MFORM + ";", null, null);
        m.visitCode();
        m.visitFieldInsn(GETSTATIC, MFORM, "transformCalls", "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTSTATIC, MFORM, "transformCalls", "I");
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "interpolate__testImpl", descriptor,
            null, null);
        m.visitCode();
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** Stub deformer form: empty interpolate__testImpl body with the reviewed descriptor. */
    private static byte[] deformerForm(final String name, final String descriptor) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, name, null, DFORM, null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, DFORM, "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "interpolate__testImpl", descriptor, null, null);
        m.visitCode();
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * Stub updater carrying the three reviewed methods: a trivial core body, the loop-1
     * {@code setDirtyDeformedForm(true)} call site and the six-instruction deform sequence.
     */
    private static byte[] updater(final IncrementalUpdateTarget target,
                                  final boolean markSite, final boolean deformSite) {
        final String up = target.updater();
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, up, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "coreCalls", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "a", target.coreDescriptor(), null, null);
        m.visitCode();
        m.visitFieldInsn(GETSTATIC, up, "coreCalls", "I");
        m.visitInsn(ICONST_1);
        m.visitInsn(IADD);
        m.visitFieldInsn(PUTSTATIC, up, "coreCalls", "I");
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "a", target.deformerUpdateDescriptor(), null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, ACD);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, ACD, "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 7);
        m.visitVarInsn(ALOAD, 7);
        m.visitInsn(ICONST_1);
        if (markSite) {
            m.visitMethodInsn(INVOKEVIRTUAL, ACD, "setDirtyDeformedForm", "(Z)V", false);
        } else {
            m.visitInsn(POP);
            m.visitInsn(POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "a", target.artMeshUpdateDescriptor(), null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, MFORM);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, MFORM, "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 13);
        m.visitTypeInsn(NEW, ACD);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, ACD, "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 14);
        m.visitTypeInsn(NEW, MFORM);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, MFORM, "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 17);
        if (deformSite) {
            m.visitVarInsn(ALOAD, 13);
            m.visitVarInsn(ALOAD, 14);
            m.visitMethodInsn(INVOKEVIRTUAL, ACD, "getCreateLocalToCanvasTransform",
                "()Lcom/live2d/doc/selection/d;", false);
            m.visitVarInsn(ALOAD, 17);
            m.visitTypeInsn(CHECKCAST, ACFORM);
            m.visitMethodInsn(INVOKEVIRTUAL, MFORM, "transform",
                "(Lcom/live2d/doc/selection/d;L" + ACFORM + ";)L" + MFORM + ";", false);
            m.visitInsn(POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static List<byte[]> references(final IncrementalUpdateTarget t,
                                           final boolean markSite, final boolean deformSite) {
        return List.of(
            updater(t, markSite, deformSite),
            deformerForm(RFORM, t.deformerInterpolateDescriptor()),
            deformerForm(WFORM, t.deformerInterpolateDescriptor()),
            meshForm(t.meshInterpolateDescriptor()));
    }

    private static void put(final String key, final Object value) {
        if (value == null) System.getProperties().remove(key);
        else System.getProperties().put(key, value);
    }

    private static final String[] SLOTS = {
        IncrementalUpdateBridge.BEGIN_PROPERTY, IncrementalUpdateBridge.END_PROPERTY,
        IncrementalUpdateBridge.MARK_PROPERTY, IncrementalUpdateBridge.DEFORM_PROPERTY,
        IncrementalUpdateBridge.FORM_PROPERTY};

    /**
     * Registers every dependency stub before transformation: frame computation at the
     * deform site loads {@code CArtMeshForm} to merge the callback result with the
     * native result, so the mesh form resolves to its reference stub during transform.
     */
    private static void registerStubs(final Loader loader, final IncrementalUpdateTarget t) {
        for (String dep : new String[] {CTX, MODEL, PSET, SELD, KGS, CAM, DFORM, ACFORM}) {
            loader.add(dep, empty(dep, null));
        }
        loader.add(t.updateContext(), empty(t.updateContext(), null));
        loader.add(t.contextParam(), empty(t.contextParam(), null));
        loader.add(ACD, deformer());
        loader.add(MFORM, meshForm(t.meshInterpolateDescriptor()));
    }

    private static Host transform(final IncrementalUpdateTarget t, final boolean markSite,
                                  final boolean deformSite) throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        registerStubs(loader, t);
        List<byte[]> refs = references(t, markSite, deformSite);
        var transformer = new IncrementalUpdateTransformer(loader, artifact, refs, t);
        List<String> names = transformer.classNames();
        List<byte[]> output = new ArrayList<>(4);
        for (int i = 0; i < names.size(); i++) {
            byte[] out = transformer.transform(null, loader, names.get(i), null,
                loader.domain, refs.get(i));
            assertNotNull(out, names.get(i) + " rejected: " + transformer.failure());
            output.add(out);
        }
        assertEquals(6, transformer.matches());
        loader.add(names.get(0), output.get(0));
        loader.add(names.get(1), output.get(1));
        loader.add(names.get(2), output.get(2));
        loader.add(names.get(3), output.get(3));
        return new Host(loader, names);
    }

    private static final class Host {
        final Loader loader;
        final Class<?> updater, rotForm, warpForm, meshForm;
        Host(final Loader loader, final List<String> names) throws Exception {
            this.loader = loader;
            updater = loader.of(names.get(0));
            rotForm = loader.of(names.get(1));
            warpForm = loader.of(names.get(2));
            meshForm = loader.of(names.get(3));
        }
    }

    @Test void allSitesInvokeTheirSlots() throws Exception {
        Host host = transform(T5303, true, true);
        List<Object> begins = new ArrayList<>(), ends = new ArrayList<>(),
            marks = new ArrayList<>(), forms = new ArrayList<>(), deforms = new ArrayList<>();
        try {
            put(IncrementalUpdateBridge.BEGIN_PROPERTY, (Consumer<Object>) begins::add);
            put(IncrementalUpdateBridge.END_PROPERTY, (Consumer<Object>) ends::add);
            put(IncrementalUpdateBridge.MARK_PROPERTY,
                (BiConsumer<Object, Object>) (d, m) -> marks.add(d));
            put(IncrementalUpdateBridge.FORM_PROPERTY, (Consumer<Object>) forms::add);
            put(IncrementalUpdateBridge.DEFORM_PROPERTY,
                (Function<Object[], Object>) args -> {
                    deforms.add(args);
                    return args[2];
                });
            Object instance = host.updater.getDeclaredConstructor().newInstance();
            Method core = host.updater.getMethod("a", host.loader.of(MODEL),
                java.util.List.class, host.loader.of(AX), host.loader.of(CTX),
                host.loader.of(BL));
            Object model = host.loader.of(MODEL).getDeclaredConstructor().newInstance();
            Object ctx = host.loader.of(CTX).getDeclaredConstructor().newInstance();
            core.invoke(instance, model, null, null, ctx, null);
            assertEquals(1, begins.size());
            assertEquals(1, ends.size());
            Object[] beginArgs = (Object[]) begins.get(0);
            assertSame(model, beginArgs[0]);
            assertSame(ctx, beginArgs[1]);
            Method defUpdate = host.updater.getMethod("a", host.loader.of(MODEL),
                host.loader.of(PSET), java.util.List.class, host.loader.of(AX),
                host.loader.of(CTX), host.loader.of(BL));
            defUpdate.invoke(instance, model, null, null, null, ctx, null);
            assertEquals(1, marks.size());
            Method meshUpdate = host.updater.getMethod("a", host.loader.of(MODEL),
                host.loader.of(PSET), host.loader.of(CAM), host.loader.of(AX),
                host.loader.of(CTX), host.loader.of(BL));
            meshUpdate.invoke(instance, model, null, null, null, ctx, null);
            assertEquals(1, deforms.size());
            Object[] deformArgs = (Object[]) deforms.get(0);
            assertEquals(4, deformArgs.length);
            assertNotNull(deformArgs[0]);
            assertNotNull(deformArgs[1]);
            assertNotNull(deformArgs[2]);
            Method rotInterpolate = host.rotForm.getMethod("interpolate__testImpl",
                host.loader.of(KGS), host.loader.of(PSET), host.loader.of(AX));
            Object rot = host.rotForm.getDeclaredConstructor().newInstance();
            rotInterpolate.invoke(rot, null, null, null);
            assertEquals(1, forms.size());
            assertSame(rot, forms.get(0));
            Method warpInterpolate = host.warpForm.getMethod("interpolate__testImpl",
                host.loader.of(KGS), host.loader.of(PSET), host.loader.of(AX));
            warpInterpolate.invoke(host.warpForm.getDeclaredConstructor().newInstance(),
                null, null, null);
            assertEquals(2, forms.size());
            Method meshInterpolate = host.meshForm.getMethod("interpolate__testImpl",
                host.loader.of(AX), host.loader.of(KGS), host.loader.of(PSET));
            meshInterpolate.invoke(host.meshForm.getDeclaredConstructor().newInstance(),
                null, null, null);
            assertEquals(2, forms.size());
        } finally {
            for (String key : SLOTS) put(key, null);
        }
    }

    @Test void missingSlotsReplayNativeSequences() throws Exception {
        Host host = transform(T5303, true, true);
        for (String key : SLOTS) put(key, null);
        Object instance = host.updater.getDeclaredConstructor().newInstance();
        Method defUpdate = host.updater.getMethod("a", host.loader.of(MODEL),
            host.loader.of(PSET), java.util.List.class, host.loader.of(AX),
            host.loader.of(CTX), host.loader.of(BL));
        defUpdate.invoke(instance, null, null, null, null, null, null);
        assertEquals(1, host.loader.of(ACD).getField("setDirtyCalls").getInt(null));
        Method meshUpdate = host.updater.getMethod("a", host.loader.of(MODEL),
            host.loader.of(PSET), host.loader.of(CAM), host.loader.of(AX),
            host.loader.of(CTX), host.loader.of(BL));
        meshUpdate.invoke(instance, null, null, null, null, null, null);
        assertEquals(1, host.loader.of(ACD).getField("ltctCalls").getInt(null));
        assertEquals(1, host.loader.of(MFORM).getField("transformCalls").getInt(null));
    }

    @Test void throwingAndWrongTypeSlotsReplayNative() throws Exception {
        Host host = transform(T5303, true, true);
        try {
            put(IncrementalUpdateBridge.MARK_PROPERTY, "not a consumer");
            put(IncrementalUpdateBridge.DEFORM_PROPERTY,
                (Function<Object[], Object>) args -> {
                    throw new AssertionError("deform failure");
                });
            Object instance = host.updater.getDeclaredConstructor().newInstance();
            Method defUpdate = host.updater.getMethod("a", host.loader.of(MODEL),
                host.loader.of(PSET), java.util.List.class, host.loader.of(AX),
                host.loader.of(CTX), host.loader.of(BL));
            defUpdate.invoke(instance, null, null, null, null, null, null);
            assertEquals(1, host.loader.of(ACD).getField("setDirtyCalls").getInt(null));
            Method meshUpdate = host.updater.getMethod("a", host.loader.of(MODEL),
                host.loader.of(PSET), host.loader.of(CAM), host.loader.of(AX),
                host.loader.of(CTX), host.loader.of(BL));
            meshUpdate.invoke(instance, null, null, null, null, null, null);
            assertEquals(1, host.loader.of(MFORM).getField("transformCalls").getInt(null));
        } finally {
            put(IncrementalUpdateBridge.MARK_PROPERTY, null);
            put(IncrementalUpdateBridge.DEFORM_PROPERTY, null);
        }
    }

    @Test void missingCallSiteFailsClosed() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        registerStubs(loader, T5303);
        List<byte[]> refs = references(T5303, false, true);
        var transformer = new IncrementalUpdateTransformer(loader, artifact, refs, T5303);
        assertNull(transformer.transform(null, loader, T5303.updater(), null,
            loader.domain, refs.get(0)));
        assertNotNull(transformer.failure());
    }

    @Test void wrongLoaderArtifactAndNameAreRejected() throws Exception {
        Path artifact = Files.createTempFile("host", ".jar");
        Loader loader = new Loader(artifact);
        List<byte[]> refs = references(T5303, true, true);
        var transformer = new IncrementalUpdateTransformer(loader, artifact, refs, T5303);
        assertNull(transformer.transform(null, new Loader(artifact), T5303.updater(), null,
            loader.domain, refs.get(0)));
        assertNull(transformer.transform(null, loader, "com/live2d/cubism/view/au", null,
            loader.domain, refs.get(0)));
        assertNull(transformer.transform(null, loader, T5303.updater(), null, null,
            refs.get(0)));
        Loader alien = new Loader(Files.createTempFile("other", ".jar"));
        assertNull(transformer.transform(null, loader, T5303.updater(), null, alien.domain,
            refs.get(0)));
        assertNotNull(transformer.failure());
    }

    @Test void everyVersionSiteSetIsAdmitted() throws Exception {
        for (IncrementalUpdateTarget t : IncrementalUpdateTarget.all()) {
            Path artifact = Files.createTempFile("host", ".jar");
            Loader loader = new Loader(artifact);
            registerStubs(loader, t);
            List<byte[]> refs = references(t, true, true);
            var transformer = new IncrementalUpdateTransformer(loader, artifact, refs, t);
            for (int i = 0; i < transformer.classNames().size(); i++) {
                byte[] out = transformer.transform(null, loader,
                    transformer.classNames().get(i), null, loader.domain, refs.get(i));
                assertNotNull(out, t.version() + " rejected: " + transformer.failure());
            }
            assertEquals(6, transformer.matches());
            assertEquals(4, transformer.touchedClasses().size());
            assertNotNull(transformer.beforeSha256(t.updater()));
        }
    }
}
