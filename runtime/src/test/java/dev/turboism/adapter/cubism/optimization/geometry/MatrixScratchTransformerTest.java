package dev.turboism.adapter.cubism.optimization.geometry;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatrixScratchTransformerTest {
    @TempDir
    Path root;

    private static final String ADMISSION = MatrixScratchTransformer.ADMISSION_PROPERTY;
    private static final String CALLBACK = MatrixScratchTransformer.CALLBACK_PROPERTY;
    private static final String KEY_CALLS = "calls";
    private static final String KEY_CACHE_HITS = "cacheHits";
    private static final String KEY_CACHE_MISSES = "cacheMisses";
    private static final String KEY_FAILURES = "failures";
    private final String previous = System.getProperty(MatrixScratchTransformer.ENABLE_PROPERTY);
    private final Object previousAdmission = System.getProperties().get(ADMISSION);
    private final Object previousCallback = System.getProperties().get(CALLBACK);

    @AfterEach
    void restore() {
        if (previous == null) System.clearProperty(MatrixScratchTransformer.ENABLE_PROPERTY);
        else System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, previous);
        if (previousAdmission == null) System.getProperties().remove(ADMISSION);
        else System.getProperties().put(ADMISSION, previousAdmission);
        if (previousCallback == null) System.getProperties().remove(CALLBACK);
        else System.getProperties().put(CALLBACK, previousCallback);
    }

    @Test
    void missingMalformedAndRetiredAdmissionAlwaysUseNativeEvenWhenRequested() throws Exception {
        try (Fixture f = fixture()) {
            Object leaf = f.chain(8);
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "true");
            for (Object gate : new Object[] {"true", new java.util.concurrent.atomic.AtomicBoolean(false)}) {
                System.getProperties().put(ADMISSION, gate);
                f.reset();
                f.result(leaf);
                assertEquals(8, f.allocations(), "request alone must not bypass exact installation admission");
            }
            System.getProperties().remove(ADMISSION);
            f.reset();
            f.result(leaf);
            assertEquals(8, f.allocations(), "missing admission must retain native multiplication");
            var gate = new java.util.concurrent.atomic.AtomicBoolean(true);
            System.getProperties().put(ADMISSION, gate);
            f.reset();
            f.result(leaf);
            assertEquals(2, f.allocations());
            gate.set(false);
            f.reset();
            f.result(leaf);
            assertEquals(8, f.allocations(), "retirement is visible without changing the user's preference");
        }
    }

    @Test
    void deepChainKeepsOperandOrderAndUsesAtMostTwoProductObjects() throws Exception {
        try (Fixture f = fixture()) {
            Object leaf = f.chain(12);
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "false");
            f.reset();
            Object original = f.result(leaf);
            float[] expected = f.values(original).clone();
            assertEquals(12, f.allocations());
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "true");
            f.reset();
            Object actual = f.result(leaf);
            assertEquals(2, f.allocations(), "deep traversal must reuse two local destinations");
            rawEquals(expected, f.values(actual));
            rawEquals(expected, f.values(original));
            assertEquals(13, f.calls(), "one local getter per chain node remains unchanged");
            f.reset();
            Object again = f.result(leaf);
            assertNotSame(actual, again, "returned matrices must not be shared across invocations");
            f.values(again)[0] = 123f;
            rawEquals(expected, f.values(actual));
        }
    }

    @Test
    void rootAliasAndShortChainsPreserveNativeOwnership() throws Exception {
        try (Fixture f = fixture()) {
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, "true");
            for (int depth = 0; depth <= 2; depth++) {
                Object leaf = f.chain(depth);
                f.reset();
                Object actual = f.result(leaf);
                assertEquals(depth, f.allocations());
                Object local = f.transform.getField("local").get(leaf);
                if (depth == 0) assertSame(local, actual);
                else assertNotSame(local, actual);
            }
        }
    }

    @Test
    void changedParentAndInPlaceMatrixMutationAreReadOnEveryInvocation() throws Exception {
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
            leaf = f.chain(8);
            f.reset();
            f.result(leaf);
            assertEquals(8, f.allocations(), "candidate remains off without opt-in");
        }
    }

    @Test
    void rejectsWrongLoaderSourceAndChangedMethodWithoutTransforming() throws Exception {
        try (Fixture f = fixture()) {
            byte[] original =
                    Files.readAllBytes(root.resolve("classes/com/live2d/graphics3d/component/GTransform.class"));
            var transformer = new MatrixScratchTransformer(f.loader, root.resolve("classes"), original);
            var wrongDomain = new ProtectionDomain(
                    new CodeSource(root.resolve("other").toUri().toURL(), (Certificate[]) null), null);
            assertNull(
                    transformer.transform(null, f.loader, MatrixScratchTransformer.OWNER, null, wrongDomain, original));
            assertNull(transformer.transform(
                    null, getClass().getClassLoader(), MatrixScratchTransformer.OWNER, null, f.domain, original));
        }
    }

    @Test
    void bridgeCachesLocalToParentWithoutIntermediateAllocationsAndMatchesNativeBits() throws Exception {
        final int depth = 4;
        try (BridgeFixture f = bridgeFixture()) {
            Object[] nodes = f.chain(depth);
            Object rootNode = nodes[0];
            Object leaf = nodes[depth - 1];
            AtomicBoolean admission = new AtomicBoolean(true);
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, Boolean.TRUE.toString());
            System.getProperties().put(ADMISSION, admission);

            try (MatrixScratchBridge bridge = new MatrixScratchBridge(f.loader, admission)) {
                bridge.activate();
                System.getProperties().put(CALLBACK, bridge.callback());

                admission.set(false);
                f.reset();
                float[] nativeBits = f.values(f.result(leaf)).clone();
                assertEquals(depth, f.localCalls());
                assertEquals((depth * 2) - 1, f.allocations());

                admission.set(true);
                f.reset();
                Object firstWorld = f.result(leaf);
                rawEquals(nativeBits, f.values(firstWorld));
                assertEquals(0, f.localCalls());
                assertEquals(1, f.allocations());

                f.reset();
                Object secondWorld = f.result(leaf);
                assertNotSame(firstWorld, secondWorld);
                rawEquals(nativeBits, f.values(secondWorld));
                assertEquals(0, f.localCalls());
                assertEquals(1, f.allocations());

                f.values(secondWorld)[0] = 9999f;
                f.reset();
                rawEquals(nativeBits, f.values(f.result(leaf)));
                assertEquals(0, f.localCalls());
                assertEquals(1, f.allocations());

                f.setEulerX(nodes[1], -0f);
                f.setPositionY(nodes[2], 3.25f);
                f.setScaleZ(leaf, -1.75f);
                admission.set(false);
                float[] mutatedNative = f.values(f.result(leaf)).clone();
                admission.set(true);
                f.reset();
                rawEquals(mutatedNative, f.values(f.result(leaf)));
                assertEquals(0, f.localCalls());
                assertEquals(1, f.allocations());

                f.reset();
                rawEquals(mutatedNative, f.values(f.result(leaf)));
                assertEquals(0, f.localCalls());
                assertEquals(1, f.allocations());

                Object override = f.staticMatrix(7.5f);
                f.setStaticMatrix(nodes[2], override);
                admission.set(false);
                float[] overrideNative = f.values(f.result(leaf)).clone();
                admission.set(true);
                f.reset();
                rawEquals(overrideNative, f.values(f.result(leaf)));
                assertEquals(0, f.localCalls());
                assertEquals(1, f.allocations());

                f.setStaticMatrix(rootNode, override);
                f.reset();
                assertSame(override, f.result(rootNode));
                assertEquals(0, f.allocations());

                f.setStaticMatrix(nodes[2], null);
                f.setStaticMatrix(rootNode, null);
                admission.set(false);
                float[] restoredNative = f.values(f.result(leaf)).clone();
                admission.set(true);
                rawEquals(restoredNative, f.values(f.result(leaf)));

                bridge.deactivate();
                f.reset();
                rawEquals(restoredNative, f.values(f.result(leaf)));
                assertEquals(depth, f.localCalls());
            }
        }
    }

    @Test
    void bridgeAggregatesThreadStateCountersAndEvictsSlotsSafely() throws Exception {
        final int chainCount = 8500;
        final int chainDepth = 2;
        final int workerChainDepth = 3;
        final int workerRepeat = 2;
        try (BridgeFixture f = bridgeFixture()) {
            Object[] leaves = new Object[chainCount];
            for (int i = 0; i < chainCount; i++) {
                Object[] chain = f.chain(chainDepth);
                leaves[i] = chain[chainDepth - 1];
            }
            AtomicBoolean admission = new AtomicBoolean(true);
            System.setProperty(MatrixScratchTransformer.ENABLE_PROPERTY, Boolean.TRUE.toString());
            System.getProperties().put(ADMISSION, admission);

            try (MatrixScratchBridge bridge = new MatrixScratchBridge(f.loader, admission)) {
                bridge.activate();
                System.getProperties().put(CALLBACK, bridge.callback());

                admission.set(false);
                float[] expectedLast = f.values(f.result(leaves[chainCount - 1])).clone();
                admission.set(true);
                for (int i = 0; i < chainCount; i++) {
                    assertNotNull(f.result(leaves[i]));
                }
                rawEquals(expectedLast, f.values(f.result(leaves[chainCount - 1])));

                long mainMisses = bridge.snapshot().get(KEY_CACHE_MISSES);
                assertEquals((long) chainCount * chainDepth, mainMisses);

                Object[] shortChain = f.chain(workerChainDepth);
                Object shortLeaf = shortChain[workerChainDepth - 1];
                admission.set(false);
                float[] expectedShort = f.values(f.result(shortLeaf)).clone();
                admission.set(true);

                java.util.concurrent.atomic.AtomicReference<Throwable> workerError =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try {
                        for (int i = 0; i < workerRepeat; i++) {
                            rawEquals(expectedShort, f.values(f.result(shortLeaf)));
                        }
                    } catch (Throwable failure) {
                        workerError.set(failure);
                    }
                });
                worker.start();
                worker.join();
                assertNull(workerError.get());

                java.util.Map<String, Long> afterWorker = bridge.snapshot();
                assertEquals((long) chainCount + 1L + workerRepeat, afterWorker.get(KEY_CALLS));
                assertEquals(mainMisses + workerChainDepth, afterWorker.get(KEY_CACHE_MISSES));
                assertEquals((long) chainDepth + workerChainDepth, afterWorker.get(KEY_CACHE_HITS));
                assertEquals(0L, afterWorker.get(KEY_FAILURES));
                // Re-query after worker thread exit to verify retired totals remain intact.
                assertEquals(afterWorker, bridge.snapshot());
            }
        }
    }

    private BridgeFixture bridgeFixture() throws Exception {
        Path base = root.resolve("bridge");
        Path src = base.resolve("src");
        Path classes = base.resolve("classes");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        String vector = """
            package com.live2d.graphics3d.type;
            public final class GVector3 {
              public float x, y, z;
              public float getX() { return x; }
              public float getY() { return y; }
              public float getZ() { return z; }
              public void setX(float v) { x = v; }
              public void setY(float v) { y = v; }
              public void setZ(float v) { z = v; }
              public void set(float vx, float vy, float vz) { x = vx; y = vy; z = vz; }
            }
            """;
        String matrix = """
            package com.live2d.graphics3d.type;
            public final class GMatrix44 {
              public static int allocations;
              public final float[] data = new float[16];
              public GMatrix44() {
                allocations++;
                data[0] = 1f; data[5] = 1f; data[10] = 1f; data[15] = 1f;
              }
              public float[] a() { return data; }
              public void a(GVector3 v) {
                float px = v.x, py = v.y, pz = v.z;
                data[12] = ((data[0] * px + data[4] * py) + data[8] * pz) + data[12];
                data[13] = ((data[1] * px + data[5] * py) + data[9] * pz) + data[13];
                data[14] = ((data[2] * px + data[6] * py) + data[10] * pz) + data[14];
              }
              public void r(float rad) {
                float s = (float) Math.sin(rad), c = (float) Math.cos(rad);
                float[] tmp = new float[16];
                tmp[0] = 1f; tmp[15] = 1f;
                tmp[5] = c; tmp[6] = s; tmp[9] = -s; tmp[10] = c;
                float[] out = new float[16];
                b(data, tmp, out, false);
                System.arraycopy(out, 0, data, 0, 16);
              }
              public void q(float rad) {
                float s = (float) Math.sin(rad), c = (float) Math.cos(rad);
                float[] tmp = new float[16];
                tmp[5] = 1f; tmp[15] = 1f;
                tmp[0] = c; tmp[2] = -s; tmp[8] = s; tmp[10] = c;
                float[] out = new float[16];
                b(data, tmp, out, false);
                System.arraycopy(out, 0, data, 0, 16);
              }
              public void s(float rad) {
                float s = (float) Math.sin(rad), c = (float) Math.cos(rad);
                float[] tmp = new float[16];
                tmp[10] = 1f; tmp[15] = 1f;
                tmp[0] = c; tmp[1] = s; tmp[4] = -s; tmp[5] = c;
                float[] out = new float[16];
                b(data, tmp, out, false);
                System.arraycopy(out, 0, data, 0, 16);
              }
              public void b(float sx, float sy, float sz) {
                for (int i = 0; i < 4; i++) { data[i] *= sx; data[4 + i] *= sy; data[8 + i] *= sz; }
              }
              public void b(float[] l, float[] r, float[] out, boolean reduced) {
                if (reduced || out == l || out == r) throw new AssertionError("alias/reduced arithmetic");
                for (int c = 0; c < 4; c++)
                  for (int row = 0; row < 4; row++)
                    out[c * 4 + row] = l[row] * r[c * 4] + l[4 + row] * r[c * 4 + 1]
                        + l[8 + row] * r[c * 4 + 2] + l[12 + row] * r[c * 4 + 3];
              }
            }
            """;
        String product = """
            package com.live2d.graphics3d.type;
            public final class a {
              public static GMatrix44 a(GMatrix44 l, GMatrix44 r) {
                GMatrix44 result = new GMatrix44(); l.b(l.a(), r.a(), result.a(), false); return result;
              }
            }
            """;
        String entity = """
            package com.live2d.graphics3d.entity;
            import com.live2d.graphics3d.component.GTransform;
            public final class GEntity {
              public GEntity parent; public GTransform transform;
              public GEntity getParentEntity() { return parent; }
              public GTransform getTransform() { return transform; }
            }
            """;
        String transform = """
            package com.live2d.graphics3d.component;
            import com.live2d.graphics3d.entity.GEntity;
            import com.live2d.graphics3d.type.GMatrix44;
            import com.live2d.graphics3d.type.GVector3;
            public final class GTransform {
              private static final float DEG_TO_RAD = 0.017453292f;
              public final GVector3 position = new GVector3();
              public final GVector3 eulerAngles = new GVector3();
              public final GVector3 scale = new GVector3();
              private GMatrix44 internalStaticMatrixLocalToParent;
              public GEntity entity;
              public static int calls;
              public GTransform() { scale.set(1f, 1f, 1f); }
              public GVector3 getPosition() { return position; }
              public GVector3 getEulerAngles() { return eulerAngles; }
              public GVector3 getScale() { return scale; }
              public void setStaticMatrixLocalToParent(GMatrix44 m) { internalStaticMatrixLocalToParent = m; }
              public GEntity getEntity() { return entity; }
              public GMatrix44 getLocalToParentMatrix() {
                calls++;
                GMatrix44 m = internalStaticMatrixLocalToParent;
                if (m != null) return m;
                GMatrix44 out = new GMatrix44();
                out.a(position);
                out.q(eulerAngles.getY() * DEG_TO_RAD);
                out.r(eulerAngles.getX() * DEG_TO_RAD);
                out.s(eulerAngles.getZ() * DEG_TO_RAD);
                out.b(scale.getX(), scale.getY(), scale.getZ());
                return out;
              }
              public GMatrix44 getLocalToWorldMatrix() {
                GMatrix44 result = getLocalToParentMatrix();
                GEntity parent = getEntity().getParentEntity();
                while (parent != null) {
                  GMatrix44 left = parent.getTransform().getLocalToParentMatrix();
                  result = com.live2d.graphics3d.type.a.a(left, result);
                  parent = parent.getParentEntity();
                }
                return result;
              }
            }
            """;
        String[] names = {"GVector3", "GMatrix44", "a", "GEntity", "GTransform"};
        String[] sources = {vector, matrix, product, entity, transform};
        java.util.List<String> args =
                new java.util.ArrayList<>(java.util.List.of("--release", "17", "-d", classes.toString()));
        for (int i = 0; i < names.length; i++) {
            Path file = src.resolve(names[i] + ".java");
            Files.writeString(file, sources[i]);
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        byte[] original = Files.readAllBytes(classes.resolve(MatrixScratchTransformer.OWNER + ".class"));
        URLClassLoader loader =
                new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader()) {
                    @Override
                    protected Class<?> findClass(String name) throws ClassNotFoundException {
                        if (!name.equals(MatrixScratchTransformer.OWNER.replace('/', '.')))
                            return super.findClass(name);
                        try {
                            var domain = new ProtectionDomain(
                                    new CodeSource(classes.toUri().toURL(), (Certificate[]) null), null);
                            var transformer = new MatrixScratchTransformer(this, classes, original);
                            byte[] rewritten = transformer.transform(
                                    null, this, MatrixScratchTransformer.OWNER, null, domain, original);
                            assertNotNull(rewritten, transformer.failure());
                            return defineClass(name, rewritten, 0, rewritten.length, domain);
                        } catch (Exception failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                };
        return new BridgeFixture(loader);
    }

    private static final class BridgeFixture implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> transform, matrix, entity, vector;

        BridgeFixture(URLClassLoader loader) throws Exception {
            this.loader = loader;
            this.transform = loader.loadClass("com.live2d.graphics3d.component.GTransform");
            this.matrix = loader.loadClass("com.live2d.graphics3d.type.GMatrix44");
            this.entity = loader.loadClass("com.live2d.graphics3d.entity.GEntity");
            this.vector = loader.loadClass("com.live2d.graphics3d.type.GVector3");
        }

        Object[] chain(int depth) throws Exception {
            Object[] nodes = new Object[depth];
            Object parent = null;
            var vecSet = vector.getMethod("set", float.class, float.class, float.class);
            var getPos = transform.getMethod("getPosition");
            var getEuler = transform.getMethod("getEulerAngles");
            var getScale = transform.getMethod("getScale");
            for (int n = 0; n < depth; n++) {
                Object e = entity.getConstructor().newInstance();
                Object t = transform.getConstructor().newInstance();
                vecSet.invoke(getPos.invoke(t), n * 1.25f, -n * 0.5f, n * 0.75f);
                vecSet.invoke(getEuler.invoke(t), n * 15f - 10f, n * 25f, -n * 30f);
                vecSet.invoke(getScale.invoke(t), 1f + n * 0.1f, -0.5f - n * 0.05f, 0.8f + n * 0.2f);
                entity.getField("parent").set(e, parent);
                entity.getField("transform").set(e, t);
                transform.getField("entity").set(t, e);
                parent = e;
                nodes[n] = t;
            }
            return nodes;
        }

        void reset() throws Exception {
            matrix.getField("allocations").setInt(null, 0);
            transform.getField("calls").setInt(null, 0);
        }

        int allocations() throws Exception {
            return matrix.getField("allocations").getInt(null);
        }

        int localCalls() throws Exception {
            return transform.getField("calls").getInt(null);
        }

        Object result(Object target) throws Exception {
            return transform.getMethod("getLocalToWorldMatrix").invoke(target);
        }

        float[] values(Object m) throws Exception {
            return (float[]) matrix.getMethod("a").invoke(m);
        }

        void setEulerX(Object target, float v) throws Exception {
            Object euler = transform.getMethod("getEulerAngles").invoke(target);
            vector.getMethod("setX", float.class).invoke(euler, v);
        }

        void setPositionY(Object target, float v) throws Exception {
            Object pos = transform.getMethod("getPosition").invoke(target);
            vector.getMethod("setY", float.class).invoke(pos, v);
        }

        void setScaleZ(Object target, float v) throws Exception {
            Object s = transform.getMethod("getScale").invoke(target);
            vector.getMethod("setZ", float.class).invoke(s, v);
        }

        Object staticMatrix(float base) throws Exception {
            Object m = matrix.getConstructor().newInstance();
            float[] arr = values(m);
            for (int i = 0; i < arr.length; i++) {
                arr[i] = base + i * 0.125f;
            }
            return m;
        }

        void setStaticMatrix(Object target, Object value) throws Exception {
            transform.getMethod("setStaticMatrixLocalToParent", matrix).invoke(target, value);
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }
    }

    private Fixture fixture() throws Exception {
        System.getProperties().put(ADMISSION, new java.util.concurrent.atomic.AtomicBoolean(true));
        Path src = root.resolve("src"), classes = root.resolve("classes");
        Files.createDirectories(src);
        Files.createDirectories(classes);
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
        java.util.List<String> args =
                new java.util.ArrayList<>(java.util.List.of("--release", "17", "-d", classes.toString()));
        for (int i = 0; i < names.length; i++) {
            Path file = src.resolve(names[i] + ".java");
            Files.writeString(file, sources[i]);
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        byte[] original = Files.readAllBytes(classes.resolve(MatrixScratchTransformer.OWNER + ".class"));
        URLClassLoader loader =
                new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader()) {
                    @Override
                    protected Class<?> findClass(String name) throws ClassNotFoundException {
                        if (!name.equals(MatrixScratchTransformer.OWNER.replace('/', '.')))
                            return super.findClass(name);
                        try {
                            var domain = new ProtectionDomain(
                                    new CodeSource(classes.toUri().toURL(), (Certificate[]) null), null);
                            var transformer = new MatrixScratchTransformer(this, classes, original);
                            byte[] rewritten = transformer.transform(
                                    null, this, MatrixScratchTransformer.OWNER, null, domain, original);
                            assertNotNull(rewritten, transformer.failure());
                            return defineClass(name, rewritten, 0, rewritten.length, domain);
                        } catch (Exception failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                };
        return new Fixture(loader, classes);
    }

    private static void rawEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]), "element " + i);
    }

    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> transform, matrix, entity;
        final ProtectionDomain domain;

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
                Object e = entity.getConstructor().newInstance(),
                        t = transform.getConstructor().newInstance();
                Object m = matrix.getConstructor().newInstance();
                float[] values = values(m);
                for (int i = 0; i < 16; i++) values[i] = i % 5 == 0 ? 1f : (n + i - 8) * .001f;
                entity.getField("parent").set(e, parent);
                entity.getField("transform").set(e, t);
                transform.getField("entity").set(t, e);
                transform.getField("local").set(t, m);
                parent = e;
                leaf = t;
            }
            return leaf;
        }

        void reset() throws Exception {
            matrix.getField("allocations").setInt(null, 0);
            transform.getField("calls").setInt(null, 0);
        }

        int allocations() throws Exception {
            return matrix.getField("allocations").getInt(null);
        }

        int calls() throws Exception {
            return transform.getField("calls").getInt(null);
        }

        Object result(Object target) throws Exception {
            return transform.getMethod("getLocalToWorldMatrix").invoke(target);
        }

        float[] values(Object m) throws Exception {
            return (float[]) matrix.getMethod("a").invoke(m);
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }
    }
}
