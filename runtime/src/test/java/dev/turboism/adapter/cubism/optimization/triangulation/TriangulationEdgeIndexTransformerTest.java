package dev.turboism.adapter.cubism.optimization.triangulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Behaviour tests for the triangulation edge-index weave.
 *
 * <p>The fixture mirrors the reviewed instruction shape — same names, descriptors, prologue and
 * call sites — so the test exercises the real selector and the real patch, then drives the woven
 * class reflectively through adds, queries, removes, an iterator-side mutation (the unwoven path
 * the index must detect) and clear.</p>
 */
final class TriangulationEdgeIndexTransformerTest {

    private static final Map<String, byte[]> FIXTURE = TriangulationEdgeIndexFixture.classes();
    private static final String LIST = TriangulationEdgeIndexFixture.LIST.replace('/', '.');
    private static final String EDGE = TriangulationEdgeIndexFixture.EDGE.replace('/', '.');
    private static final String TRIANGLE = TriangulationEdgeIndexFixture.TRIANGLE.replace('/', '.');
    private static final String POINT = TriangulationEdgeIndexFixture.POINT.replace('/', '.');

    @Test
    void wovenContainsPreservesIdentityAndGeometricEquality() throws Exception {
        final Host host = wovenHost();
        final Object t = host.triangle(10f, 1, 2, 3);
        host.add(t);
        host.query(1, 2);
        assertEquals(true, host.contains(t));
        assertEquals(true, host.contains(host.triangle(10f, 90, 91, 92)));
        assertEquals(false, host.contains(host.triangle(20f, 1, 2, 3)));
        host.remove(t);
        assertEquals(false, host.contains(t));
    }

    @Test
    void missingContainsSiteRejectsTheWholePatch() {
        final org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(FIXTURE.get(LIST))
                .accept(
                        new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
                            @Override
                            public org.objectweb.asm.MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if ("c".equals(name) && descriptor.equals("(L" + TRIANGLE.replace('.', '/') + ";)Z")) {
                                    return null;
                                }
                                return super.visitMethod(access, name, descriptor, signature, exceptions);
                            }
                        },
                        0);
        final byte[] bytes = writer.toByteArray();
        final TriangulationEdgeIndexTransformer transformer = new TriangulationEdgeIndexTransformer();
        assertNull(
                transformer.transform(null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME, null, null, bytes));
        assertTrue(transformer.diagnostic().contains("contains sites=0"));
    }

    @Test
    void patchedListAnswersIndexedQueriesInInsertionOrder() throws Exception {
        final Host host = wovenHost();

        // Three triangles: t1 shares edge (1,2) with t2; t3 touches only vertex 1.
        final Object t1 = host.triangle(10f, 1, 2, 3);
        final Object t2 = host.triangle(20f, 1, 2, 7);
        final Object t3 = host.triangle(30f, 1, 8, 9);
        host.add(t1);
        host.add(t2);
        host.add(t3);

        final List<?> hits = host.query(1, 2);
        assertEquals(List.of(t1, t2), hits, "insertion order must be preserved");
        assertSame(t1, hits.get(0));
        assertSame(t2, hits.get(1));

        // Reversed endpoints hit the same undirected bucket.
        assertEquals(List.of(t1, t2), host.query(2, 1));

        // A side no triangle has yields an empty list, and a single-side hit is exact.
        assertEquals(List.of(), host.query(4, 5));
        assertEquals(List.of(t2), host.query(2, 7));
        assertEquals(List.of(t3), host.query(8, 9));
    }

    @Test
    void removeDeindexesTheActuallyRemovedElement() throws Exception {
        final Host host = wovenHost();
        final Object t1 = host.triangle(10f, 1, 2, 3);
        host.add(t1);
        // Removing an equal-by-coordinates object with different vertex indices removes the
        // stored t1 (official equals semantics) — and must deindex t1's recorded keys, never
        // the argument's.
        assertEquals(true, host.remove(host.triangle(10f, 91, 92, 93)));
        assertEquals(List.of(), host.query(1, 2), "t1's endpoint keys must be deindexed");
        assertEquals(List.of(), host.query(91, 92), "the argument's keys are never indexed");
        // And a stored object with distinct indices keeps its own keys after the equal-object
        // removal above.
        final Object equalDifferent = host.triangle(10f, 50, 60, 70);
        host.add(equalDifferent);
        assertEquals(List.of(equalDifferent), host.query(50, 60));
        assertEquals(List.of(), host.query(1, 2));
    }

    @Test
    void iteratorSideRemovalIsCaughtAndRebuiltAround() throws Exception {
        final Host host = wovenHost();
        final Object t1 = host.triangle(10f, 1, 2, 3);
        final Object t2 = host.triangle(20f, 1, 2, 7);
        host.add(t1);
        host.add(t2);
        assertEquals(List.of(t1, t2), host.query(1, 2), "index path must answer first");

        // Mutate behind the index: Iterator.remove() is the unwoven side path. The size
        // precheck detects the drift and the rebuild resyncs — every remaining element still
        // carries its recorded keys.
        final Field set = host.list.getClass().getDeclaredField("b");
        set.setAccessible(true);
        final java.util.LinkedHashSet<?> backing = (java.util.LinkedHashSet<?>) set.get(host.list);
        final Iterator<?> iterator = backing.iterator();
        assertSame(t1, iterator.next());
        iterator.remove();

        assertEquals(List.of(t2), host.query(1, 2), "rebuild must answer correctly");
        assertEquals(List.of(t2), host.query(2, 1));
    }

    @Test
    void anUnwovenElementMakesTheOriginalScanTakeOver() throws Exception {
        final Host host = wovenHost();
        final Object t1 = host.triangle(10f, 1, 2, 3);
        host.add(t1);
        assertEquals(List.of(t1), host.query(1, 2));

        // Add through the set itself — no index keys exist for this element.
        final Object unwoven = host.triangle(20f, 1, 2, 7);
        final Field set = host.list.getClass().getDeclaredField("b");
        set.setAccessible(true);
        @SuppressWarnings("unchecked")
        final java.util.LinkedHashSet<Object> backing = (java.util.LinkedHashSet<Object>) set.get(host.list);
        backing.add(unwoven);

        // The index declines permanently and the untouched original body still answers.
        assertEquals(List.of(t1, unwoven), host.query(1, 2), "the original scan must take over on an unknown element");
        assertEquals(List.of(t1, unwoven), host.query(2, 1));
    }

    @Test
    void clearResetsTheIndex() throws Exception {
        final Host host = wovenHost();
        final Object t1 = host.triangle(10f, 1, 2, 3);
        host.add(t1);
        assertEquals(List.of(t1), host.query(1, 2));
        host.clear();
        assertEquals(List.of(), host.query(1, 2));
        // Re-adding after clear must index fresh state.
        final Object t2 = host.triangle(20f, 1, 2, 7);
        host.add(t2);
        assertEquals(List.of(t2), host.query(1, 2));
    }

    @Test
    void resultListsAreSnapshotsNotLiveViews() throws Exception {
        final Host host = wovenHost();
        final Object t1 = host.triangle(10f, 1, 2, 3);
        host.add(t1);
        final List<Object> first = new ArrayList<>(host.query(1, 2));
        first.clear();
        assertEquals(List.of(t1), host.query(1, 2), "mutating a result must not corrupt the index");
    }

    @Test
    void ignoresEveryOtherClassAndNullBytes() {
        final TriangulationEdgeIndexTransformer transformer = new TriangulationEdgeIndexTransformer();
        assertNull(transformer.transform(null, "com/example/Other", null, null, FIXTURE.get(LIST)));
        assertNull(
                transformer.transform(null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME, null, null, null));
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.NONE, transformer.outcome());
    }

    @Test
    void refusesAClassWhoseBodyIsNotTheReviewedShape() throws Exception {
        // a(l) carries two add sites — the gate must refuse rather than patch half a class.
        final byte[] bad = badShapeListBytes();
        final TriangulationEdgeIndexTransformer transformer = new TriangulationEdgeIndexTransformer();
        assertNull(
                transformer.transform(null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME, null, null, bad));
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.SHAPE_REJECTED, transformer.outcome());
        assertTrue(transformer.diagnostic().contains("add sites=2"), transformer.diagnostic());
    }

    @Test
    void patchesTheReviewedShapeWithoutAnyDigestPin() {
        final byte[] fixture = FIXTURE.get(LIST);
        final TriangulationEdgeIndexTransformer transformer = new TriangulationEdgeIndexTransformer();
        assertNotNull(transformer.transform(
                null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME, null, null, fixture));
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.outcome());
    }

    // ------------------------------------------------------------------ fixture access

    private static Host wovenHost() throws Exception {
        final byte[] original = FIXTURE.get(LIST);
        final byte[] patched = new TriangulationEdgeIndexTransformer()
                .transform(null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME, null, null, original);
        assertNotNull(patched, "the reviewed shape must be patched");
        final Loader loader = new Loader(FIXTURE, patched);
        final Class<?> listClass = Class.forName(LIST, true, loader);
        return new Host(loader, listClass.getDeclaredConstructor().newInstance());
    }

    /** Child loader that supplies every fixture class plus the patched TriangleList bytes. */
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;
        private final byte[] patchedList;

        Loader(final Map<String, byte[]> classes, final byte[] patchedList) {
            super(TriangulationEdgeIndexTransformerTest.class.getClassLoader());
            this.classes = classes;
            this.patchedList = patchedList;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if (LIST.equals(name)) return defineClass(name, patchedList, 0, patchedList.length);
            final byte[] bytes = classes.get(name);
            if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
            throw new ClassNotFoundException(name);
        }
    }

    /** Reflective driving surface over one woven TriangleList instance. */
    private static final class Host {
        final ClassLoader loader;
        final Object list;
        final Constructor<?> point;
        final Constructor<?> triangle;
        final Constructor<?> edge;

        Host(final ClassLoader loader, final Object list) throws Exception {
            this.loader = loader;
            this.list = list;
            this.point = Class.forName(POINT, true, loader).getDeclaredConstructor(float.class, float.class, int.class);
            this.triangle = Class.forName(TRIANGLE, true, loader)
                    .getDeclaredConstructor(
                            Class.forName(POINT, true, loader),
                            Class.forName(POINT, true, loader),
                            Class.forName(POINT, true, loader));
            this.edge = Class.forName(EDGE, true, loader)
                    .getDeclaredConstructor(Class.forName(POINT, true, loader), Class.forName(POINT, true, loader));
        }

        /** Triangle whose three vertices share {@code base} as their coordinate seed. */
        Object triangle(final float base, final int ia, final int ib, final int ic) throws Exception {
            return triangle.newInstance(
                    point.newInstance(base, base + 0.25f, ia),
                    point.newInstance(base + 1f, base + 1.25f, ib),
                    point.newInstance(base + 2f, base + 2.25f, ic));
        }

        boolean add(final Object triangle) throws Exception {
            return (boolean) list.getClass().getMethod("a", triangle.getClass()).invoke(list, triangle);
        }

        boolean remove(final Object triangle) throws Exception {
            return (boolean) list.getClass().getMethod("b", triangle.getClass()).invoke(list, triangle);
        }

        boolean contains(final Object triangle) throws Exception {
            return (boolean) list.getClass().getMethod("c", triangle.getClass()).invoke(list, triangle);
        }

        void clear() throws Exception {
            list.getClass().getMethod("c").invoke(list);
        }

        List<?> query(final int ja, final int jb) throws Exception {
            final Object edgeInstance = edge.newInstance(point.newInstance(0f, 0f, ja), point.newInstance(0f, 0f, jb));
            final Class<?> edgeClass = Class.forName(EDGE, true, loader);
            @SuppressWarnings("unchecked")
            final List<Object> hits =
                    (List<Object>) list.getClass().getMethod("a", edgeClass).invoke(list, edgeInstance);
            return hits;
        }
    }

    /** A same-named class whose a(l) holds two LinkedHashSet.add sites — a shape the gate refuses. */
    private static byte[] badShapeListBytes() {
        final String name = TriangulationEdgeIndexFixture.LIST;
        final org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                name,
                null,
                "java/lang/Object",
                null);
        writer.visitField(
                        org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_FINAL,
                        "b",
                        "Ljava/util/LinkedHashSet;",
                        null,
                        null)
                .visitEnd();
        final String tri = TriangulationEdgeIndexFixture.TRIANGLE;
        final String edge = TriangulationEdgeIndexFixture.EDGE;
        final org.objectweb.asm.MethodVisitor add =
                writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "a", "(L" + tri + ";)Z", null, null);
        add.visitCode();
        add.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        add.visitLdcInsn("");
        add.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESTATIC,
                TriangulationEdgeIndexFixture.INTRINSICS,
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false);
        for (int site = 0; site < 2; site++) {
            add.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            add.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, name, "b", "Ljava/util/LinkedHashSet;");
            add.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
            add.visitMethodInsn(
                    org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                    "java/util/LinkedHashSet",
                    "add",
                    "(Ljava/lang/Object;)Z",
                    false);
            add.visitInsn(org.objectweb.asm.Opcodes.POP);
        }
        add.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
        add.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        add.visitMaxs(2, 2);
        add.visitEnd();
        final org.objectweb.asm.MethodVisitor remove =
                writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "b", "(L" + tri + ";)Z", null, null);
        remove.visitCode();
        remove.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        remove.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, name, "b", "Ljava/util/LinkedHashSet;");
        remove.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        remove.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "java/util/LinkedHashSet",
                "remove",
                "(Ljava/lang/Object;)Z",
                false);
        remove.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        remove.visitMaxs(2, 2);
        remove.visitEnd();
        final org.objectweb.asm.MethodVisitor clear =
                writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "c", "()V", null, null);
        clear.visitCode();
        clear.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        clear.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, name, "b", "Ljava/util/LinkedHashSet;");
        clear.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashSet", "clear", "()V", false);
        clear.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        clear.visitMaxs(1, 1);
        clear.visitEnd();
        final org.objectweb.asm.MethodVisitor query = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "a", "(L" + edge + ";)Ljava/util/List;", null, null);
        query.visitCode();
        query.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        query.visitLdcInsn("");
        query.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESTATIC,
                TriangulationEdgeIndexFixture.INTRINSICS,
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false);
        query.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "java/util/ArrayList");
        query.visitInsn(org.objectweb.asm.Opcodes.DUP);
        query.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        query.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        query.visitMaxs(2, 2);
        query.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void triangleSetTransformerRejectsUnreviewedProtectionDomains(@TempDir final Path temp) throws Exception {
        final Path fakeJar = temp.resolve("fake.jar");
        Files.writeString(fakeJar, "not reviewed");
        final ProtectionDomain unreviewed = new ProtectionDomain(
                new CodeSource(fakeJar.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
        final var transformer = new TextureAtlasTriangleSetTransformer(true);
        assertNull(transformer.transform(
                getClass().getClassLoader(), LIST.replace('.', '/'), null, null, FIXTURE.get(LIST)));
        assertNull(transformer.transform(
                getClass().getClassLoader(), LIST.replace('.', '/'), null, unreviewed, FIXTURE.get(LIST)));
        assertNull(transformer.transform(
                getClass().getClassLoader(),
                TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME,
                null,
                unreviewed,
                angleCallerBytes("com/live2d/util/L")));
    }

    @Test
    void triangleSetTransformerPatchesReviewedShapesAndFailsClosedOnDrift() {
        final String listInternal = TriangulationEdgeIndexFixture.LIST;
        final var scopedOnly = new TextureAtlasTriangleSetTransformer(false);
        final var indexed = new TextureAtlasTriangleSetTransformer(true);
        assertNotNull(scopedOnly.transformReviewed(listInternal, FIXTURE.get(LIST), "5.3.04", ignored -> null));
        assertNotNull(indexed.transformReviewed(listInternal, FIXTURE.get(LIST), "5.3.04", ignored -> null));
        assertNull(indexed.transformReviewed(listInternal, badShapeListBytes(), "5.3.04", ignored -> null));

        final Map<String, byte[]> earlyEntries = Map.of(
                "com/live2d/graphics3d/editableMesh/triangulation/r", geometryBytes(true),
                "com/live2d/graphics3d/type/GVector2", vectorBytes(true),
                "com/live2d/util/K", mathBytes("com/live2d/util/K", true));
        final byte[] earlyCaller = angleCallerBytes("com/live2d/util/K");
        assertNotNull(indexed.transformReviewed(
                TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME, earlyCaller, "5.1.03", earlyEntries::get));

        final Map<String, byte[]> missingVectorGetter = new HashMap<>(earlyEntries);
        missingVectorGetter.put("com/live2d/graphics3d/type/GVector2", vectorBytes(false));
        assertNull(indexed.transformReviewed(
                TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME,
                earlyCaller,
                "5.1.03",
                missingVectorGetter::get));

        assertNull(indexed.transformReviewed(
                TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME, earlyCaller, "5.3.04", earlyEntries::get));
    }

    @Test
    void triangleSetRejectsUnreviewedOwnerAndVerifiesMemberShape() throws Exception {
        final LinkedHashSet<Object> original = new LinkedHashSet<>();
        assertSame(original, TextureAtlasTriangleSet.forAtlas(original, TriangulationEdgeIndexTransformerTest.class));

        final Map<String, byte[]> classes = new HashMap<>(FIXTURE);
        classes.put("com/live2d/graphics3d/type/GVector2".replace('/', '.'), vectorBytes(true));
        classes.put(POINT, pointWithVectorSuperBytes());
        classes.put(TRIANGLE, triangleWithFinalGettersBytes());
        final Loader loader = new Loader(classes, FIXTURE.get(LIST));
        final Class<?> triangleClass = Class.forName(TRIANGLE, true, loader);
        final Class<?> pointClass = Class.forName(POINT, true, loader);
        final Class<?> vectorClass = Class.forName("com.live2d.graphics3d.type.GVector2", true, loader);

        final var cornerHash = TextureAtlasTriangleSet.cornerHash(triangleClass, pointClass, vectorClass)
                .orElseThrow();
        final Constructor<?> pointCtor = pointClass.getDeclaredConstructor(float.class, float.class, int.class);
        final Constructor<?> triCtor = triangleClass.getDeclaredConstructor(pointClass, pointClass, pointClass);
        final Object a1 = triCtor.newInstance(
                pointCtor.newInstance(1f, 2f, 1), pointCtor.newInstance(3f, 4f, 2), pointCtor.newInstance(5f, 6f, 3));
        final Object a2 = triCtor.newInstance(
                pointCtor.newInstance(1f, 2f, 9), pointCtor.newInstance(3f, 4f, 8), pointCtor.newInstance(5f, 6f, 7));
        final Object b = triCtor.newInstance(
                pointCtor.newInstance(9f, 2f, 1), pointCtor.newInstance(3f, 4f, 2), pointCtor.newInstance(5f, 6f, 3));
        assertEquals(cornerHash.applyAsInt(a1), cornerHash.applyAsInt(a2));
        assertNotEquals(cornerHash.applyAsInt(a1), cornerHash.applyAsInt(b));

        assertTrue(TextureAtlasTriangleSet.cornerHash(Object.class, pointClass, vectorClass)
                .isEmpty());
    }

    private static byte[] angleCallerBytes(final String mathOwner) {
        final String h = TriangulationEdgeIndexTransformer.MEMBERSHIP_INTERNAL_NAME;
        final String r = "com/live2d/graphics3d/editableMesh/triangulation/r";
        final String v = "com/live2d/graphics3d/type/GVector2";
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, h, null, "java/lang/Object", null);
        final MethodVisitor d = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "d", "()V", null, null);
        d.visitCode();
        final Label loop = new Label();
        d.visitLabel(loop);
        d.visitFieldInsn(Opcodes.GETSTATIC, r, "a", "L" + r + ";");
        d.visitInsn(Opcodes.ACONST_NULL);
        d.visitInsn(Opcodes.ACONST_NULL);
        d.visitMethodInsn(Opcodes.INVOKEVIRTUAL, r, "a", "(L" + v + ";L" + v + ";)F", false);
        d.visitVarInsn(Opcodes.FSTORE, 13);
        d.visitVarInsn(Opcodes.FLOAD, 13);
        d.visitFieldInsn(Opcodes.GETSTATIC, mathOwner, "a", "L" + mathOwner + ";");
        d.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mathOwner, "f", "()F", false);
        d.visitInsn(Opcodes.FCMPL);
        d.visitJumpInsn(Opcodes.IFGT, loop);
        d.visitInsn(Opcodes.RETURN);
        d.visitMaxs(3, 14);
        d.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] geometryBytes(final boolean withSingleton) {
        final String r = "com/live2d/graphics3d/editableMesh/triangulation/r";
        final String v = "com/live2d/graphics3d/type/GVector2";
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, r, null, "java/lang/Object", null);
        if (withSingleton) {
            writer.visitField(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "a", "L" + r + ";", null, null)
                    .visitEnd();
        }
        final MethodVisitor angle = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "a", "(L" + v + ";L" + v + ";)F", null, null);
        angle.visitCode();
        angle.visitInsn(Opcodes.FCONST_0);
        angle.visitInsn(Opcodes.FRETURN);
        angle.visitMaxs(1, 3);
        angle.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] vectorBytes(final boolean withGetX) {
        final String v = "com/live2d/graphics3d/type/GVector2";
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, v, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PROTECTED, "x", "F", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PROTECTED, "y", "F", null, null).visitEnd();
        final MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        for (final String getter : withGetX ? new String[] {"getX", "getY"} : new String[] {"getY"}) {
            final MethodVisitor mv =
                    writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, getter, "()F", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, v, "getX".equals(getter) ? "x" : "y", "F");
            mv.visitInsn(Opcodes.FRETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mathBytes(final String owner, final boolean withThreshold) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "a", "L" + owner + ";", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "i", "F", null, null)
                .visitEnd();
        if (withThreshold) {
            final MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "f", "()F", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitInsn(Opcodes.FRETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] triangleWithFinalGettersBytes() {
        final ClassWriter writer = new ClassWriter(0);
        new ClassReader(FIXTURE.get(TRIANGLE))
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                final boolean corner = "a".equals(name) || "b".equals(name) || "c".equals(name);
                                return super.visitMethod(
                                        corner && descriptor.startsWith("()") ? access | Opcodes.ACC_FINAL : access,
                                        name,
                                        descriptor,
                                        signature,
                                        exceptions);
                            }
                        },
                        0);
        return writer.toByteArray();
    }

    private static byte[] pointWithVectorSuperBytes() {
        final ClassWriter writer = new ClassWriter(0);
        new ClassReader(FIXTURE.get(POINT))
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public void visit(
                                    final int version,
                                    final int access,
                                    final String name,
                                    final String signature,
                                    final String superName,
                                    final String[] interfaces) {
                                super.visit(
                                        version,
                                        access,
                                        name,
                                        signature,
                                        "com/live2d/graphics3d/type/GVector2",
                                        interfaces);
                            }

                            @Override
                            public org.objectweb.asm.FieldVisitor visitField(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final Object value) {
                                if ("x".equals(name) || "y".equals(name)) return null;
                                return super.visitField(access, name, descriptor, signature, value);
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if ("getX".equals(name) || "getY".equals(name)) return null;
                                final int updatedAccess = "getIndex".equals(name) ? access | Opcodes.ACC_FINAL : access;
                                final var mv =
                                        super.visitMethod(updatedAccess, name, descriptor, signature, exceptions);
                                return new MethodVisitor(Opcodes.ASM9, mv) {
                                    @Override
                                    public void visitFieldInsn(
                                            final int opcode,
                                            final String owner,
                                            final String fieldName,
                                            final String fieldDesc) {
                                        final String targetOwner =
                                                POINT.replace('.', '/').equals(owner)
                                                                && ("x".equals(fieldName) || "y".equals(fieldName))
                                                        ? "com/live2d/graphics3d/type/GVector2"
                                                        : owner;
                                        super.visitFieldInsn(opcode, targetOwner, fieldName, fieldDesc);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String member,
                                            final String desc,
                                            final boolean itf) {
                                        super.visitMethodInsn(
                                                opcode,
                                                "<init>".equals(name) && "java/lang/Object".equals(owner)
                                                        ? "com/live2d/graphics3d/type/GVector2"
                                                        : owner,
                                                member,
                                                desc,
                                                itf);
                                    }
                                };
                            }
                        },
                        0);
        return writer.toByteArray();
    }
}
