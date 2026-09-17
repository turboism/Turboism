package dev.turboism.adapter.cubism.optimization.geometry;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MatrixScratchTransformerTest {
    @TempDir Path root;
    private static final String ADMISSION = "turboism.matrix-scratch.admission";
    private final String previous = System.getProperty(MatrixScratchTransformer.ENABLE_PROPERTY);
    private final Object previousAdmission = System.getProperties().get(ADMISSION);
    @AfterEach void restore() {
        if (previous == null) System.clearProperty(MatrixScratchTransformer.ENABLE_PROPERTY);
        else System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, previous);
        if (previousAdmission == null) System.getProperties().remove(ADMISSION);
        else System.getProperties().put(ADMISSION, previousAdmission);
    }
    @Test void missingMalformedAndRetiredAdmissionAlwaysUseNativeEvenWhenRequested() throws Exception {
        try (Fixture f = fixture()) {
            Object leaf = f.chain(8);
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "true");
            for (Object gate : new Object[] {"true", new java.util.concurrent.atomic.AtomicBoolean(false)}) {
                System.getProperties().put(ADMISSION, gate);
                f.reset(); f.result(leaf);
                assertEquals(8, f.allocations(), "request alone must not bypass exact installation admission");
            }
            System.getProperties().remove(ADMISSION);
            f.reset(); f.result(leaf);
            assertEquals(8, f.allocations(), "missing admission must retain native multiplication");
            var gate = new java.util.concurrent.atomic.AtomicBoolean(true);
            System.getProperties().put(ADMISSION, gate);
            f.reset(); f.result(leaf);
            assertEquals(2, f.allocations());
            gate.set(false);
            f.reset(); f.result(leaf);
            assertEquals(8, f.allocations(), "retirement is visible without changing the user's preference");
        }
    }
    @Test void deepChainKeepsOperandOrderAndUsesAtMostTwoProductObjects() throws Exception {
        try (Fixture f = fixture()) {
            Object leaf = f.chain(12);
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "false");
            f.reset(); Object original = f.result(leaf); float[] expected = f.values(original).clone();
            assertEquals(12, f.allocations());
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "true");
            f.reset(); Object actual = f.result(leaf);
            assertEquals(2, f.allocations(), "deep traversal must reuse two local destinations");
            rawEquals(expected, f.values(actual));
            rawEquals(expected, f.values(original));
            assertEquals(13, f.calls(), "one local getter per chain node remains unchanged");
            f.reset(); Object again = f.result(leaf);
            assertNotSame(actual, again, "returned matrices must not be shared across invocations");
            f.values(again)[0] = 123f;
            rawEquals(expected, f.values(actual));
        }
    }
    @Test void rootAliasAndShortChainsPreserveNativeOwnership() throws Exception {
        try (Fixture f = fixture()) {
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "true");
            for (int depth = 0; depth <= 2; depth++) {
                Object leaf = f.chain(depth); f.reset();
                Object actual = f.result(leaf);
                assertEquals(depth, f.allocations());
                Object local = f.transform.getField("local").get(leaf);
                if (depth == 0) assertSame(local, actual); else assertNotSame(local, actual);
            }
        }
    }
    @Test void changedParentAndInPlaceMatrixMutationAreReadOnEveryInvocation() throws Exception {
        try (Fixture f = fixture()) {
            Object leaf = f.chain(7);
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "true");
            float[] before = f.values(f.result(leaf)).clone();
            f.values(f.transform.getField("local").get(leaf))[3] += 4f;
            float[] after = f.values(f.result(leaf)).clone();
            assertFalse(java.util.Arrays.equals(before, after));
            f.entity.getField("parent").set(f.transform.getField("entity").get(leaf), null);
            assertSame(f.transform.getField("local").get(leaf), f.result(leaf));
            System.clearProperty(MatrixScratchTransformer.ENABLE_PROPERTY);
            leaf = f.chain(8); f.reset(); f.result(leaf);
            assertEquals(8, f.allocations(), "candidate remains off without opt-in");
        }
    }
    @Test void rejectsWrongLoaderSourceAndChangedMethodWithoutTransforming() throws Exception {
        try (Fixture f = fixture()) {
            byte[] original = Files.readAllBytes(root.resolve("classes/com/live2d/graphics3d/component/GTransform.class"));
            var transformer = new MatrixScratchTransformer(f.loader, root.resolve("classes"), original);
            var wrongDomain = new ProtectionDomain(new CodeSource(root.resolve("other").toUri().toURL(), (Certificate[]) null), null);
            assertNull(transformer.transform(null, f.loader, MatrixScratchTransformer.OWNER, null, wrongDomain, original));
            assertNull(transformer.transform(null, getClass().getClassLoader(), MatrixScratchTransformer.OWNER, null, f.domain, original));
        }
    }
    private Fixture fixture() throws Exception {
        System.getProperties().put(ADMISSION, new java.util.concurrent.atomic.AtomicBoolean(true));
        Path src = root.resolve("src"), classes = root.resolve("classes");
        Files.createDirectories(src); Files.createDirectories(classes);
        String matrix = """
            package com.live2d.graphics3d.type;
            public final class GMatrix44 {
              public static int allocations;
              public final float[] data = new float[16];
              public GMatrix44() { allocations++; }
              public float[] a() { return data; }
              public void b(float[] l,float[] r,float[] out,boolean reduced) {
                if (reduced || out==l || out==r) throw new AssertionError("alias/reduced arithmetic");
                for(int c=0;c<4;c++) for(int row=0;row<4;row++)
                  out[c*4+row]=l[row]*r[c*4]+l[4+row]*r[c*4+1]+l[8+row]*r[c*4+2]+l[12+row]*r[c*4+3];
              }
            }
            """;
        String product = """
            package com.live2d.graphics3d.type;
            public final class a {
              public static GMatrix44 a(GMatrix44 l,GMatrix44 r) {
                GMatrix44 result=new GMatrix44(); l.b(l.a(),r.a(),result.a(),false); return result;
              }
            }
            """;
        String entity = """
            package com.live2d.graphics3d.entity;
            import com.live2d.graphics3d.component.GTransform;
            public final class GEntity {
              public GEntity parent; public GTransform transform;
              public GEntity getParentEntity(){return parent;}
              public GTransform getTransform(){return transform;}
            }
            """;
        String transform = """
            package com.live2d.graphics3d.component;
            import com.live2d.graphics3d.type.GMatrix44;
            import com.live2d.graphics3d.entity.GEntity;
            public final class GTransform {
              public GMatrix44 local; public GEntity entity; public static int calls;
              public GMatrix44 getLocalToParentMatrix(){calls++;return local;}
              public GEntity getEntity(){return entity;}
              public GMatrix44 getLocalToWorldMatrix(){
                GMatrix44 result=getLocalToParentMatrix();
                GEntity parent=getEntity().getParentEntity();
                while(parent!=null){
                  GMatrix44 left=parent.getTransform().getLocalToParentMatrix();
                  result=com.live2d.graphics3d.type.a.a(left,result);
                  parent=parent.getParentEntity();
                }
                return result;
              }
            }
            """;
        String[] names = {"GMatrix44", "a", "GEntity", "GTransform"};
        String[] sources = {matrix, product, entity, transform};
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("--release", "17", "-d", classes.toString()));
        for (int i = 0; i < names.length; i++) {
            Path file = src.resolve(names[i] + ".java"); Files.writeString(file, sources[i]); args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        byte[] original = Files.readAllBytes(classes.resolve(MatrixScratchTransformer.OWNER + ".class"));
        URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(MatrixScratchTransformer.OWNER.replace('/', '.'))) return super.findClass(name);
                try {
                    var domain = new ProtectionDomain(new CodeSource(classes.toUri().toURL(), (Certificate[]) null), null);
                    var transformer = new MatrixScratchTransformer(this, classes, original);
                    byte[] rewritten = transformer.transform(null, this, MatrixScratchTransformer.OWNER, null, domain, original);
                    assertNotNull(rewritten, transformer.failure());
                    return defineClass(name, rewritten, 0, rewritten.length, domain);
                } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
            }
        };
        return new Fixture(loader, classes);
    }
    private static void rawEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]), "element " + i);
    }
    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader; final Class<?> transform, matrix, entity; final ProtectionDomain domain;
        Fixture(URLClassLoader loader, Path source) throws Exception {
            this.loader = loader;
            transform = loader.loadClass("com.live2d.graphics3d.component.GTransform");
            matrix = loader.loadClass("com.live2d.graphics3d.type.GMatrix44");
            entity = loader.loadClass("com.live2d.graphics3d.entity.GEntity");
            domain = new ProtectionDomain(new CodeSource(source.toUri().toURL(), (Certificate[]) null), null);
        }
        Object chain(int depth) throws Exception {
            Object parent = null, leaf = null;
            for (int n = depth; n >= 0; n--) {
                Object e = entity.getConstructor().newInstance(), t = transform.getConstructor().newInstance();
                Object m = matrix.getConstructor().newInstance(); float[] values = values(m);
                for (int i = 0; i < 16; i++) values[i] = i % 5 == 0 ? 1f : (n + i - 8) * .001f;
                entity.getField("parent").set(e, parent); entity.getField("transform").set(e, t);
                transform.getField("entity").set(t, e); transform.getField("local").set(t, m);
                parent = e; leaf = t;
            }
            return leaf;
        }
        void reset() throws Exception { matrix.getField("allocations").setInt(null, 0); transform.getField("calls").setInt(null, 0); }
        int allocations() throws Exception { return matrix.getField("allocations").getInt(null); }
        int calls() throws Exception { return transform.getField("calls").getInt(null); }
        Object result(Object target) throws Exception { return transform.getMethod("getLocalToWorldMatrix").invoke(target); }
        float[] values(Object m) throws Exception { return (float[]) matrix.getMethod("a").invoke(m); }
        @Override public void close() throws Exception { loader.close(); }
    }
}
