package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
        new org.objectweb.asm.ClassReader(FIXTURE.get(LIST)).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(final int access, final String name,
                            final String descriptor, final String signature, final String[] exceptions) {
                        if ("c".equals(name) && descriptor.equals("(L" + TRIANGLE.replace('.', '/') + ";)Z")) {
                            return null;
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                }, 0);
        final byte[] bytes = writer.toByteArray();
        final TriangulationEdgeIndexTransformer transformer = new TriangulationEdgeIndexTransformer(
                Set.of(TriangulationEdgeIndexTransformer.sha256(bytes)));
        assertNull(transformer.transform(null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME,
                null, null, bytes));
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
        final java.util.LinkedHashSet<Object> backing =
                (java.util.LinkedHashSet<Object>) set.get(host.list);
        backing.add(unwoven);

        // The index declines permanently and the untouched original body still answers.
        assertEquals(List.of(t1, unwoven), host.query(1, 2),
                "the original scan must take over on an unknown element");
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
        assertNull(transformer.transform(
                null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME, null, null, null));
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.NONE, transformer.outcome());
    }

    @Test
    void refusesClassesWhoseBytesAreNotAReviewedDigest() {
        final TriangulationEdgeIndexTransformer transformer = new TriangulationEdgeIndexTransformer();
        assertNull(transformer.transform(
                null,
                TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME,
                null,
                null,
                FIXTURE.get(LIST)));
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.HASH_MISMATCH, transformer.outcome());
        assertTrue(transformer.diagnostic().startsWith("observed="));
    }

    @Test
    void refusesAnAdmittedDigestWhenTheShapeIsUnexpected() throws Exception {
        // Same class name and digest admitted, but a(l) carries two add sites — the gate must
        // refuse rather than patch half a class.
        final byte[] bad = badShapeListBytes();
        final String digest = TriangulationEdgeIndexTransformer.sha256(bad);
        final TriangulationEdgeIndexTransformer transformer =
                new TriangulationEdgeIndexTransformer(Set.of(digest));
        assertNull(transformer.transform(
                null, TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME, null, null, bad));
        assertEquals(TriangulationEdgeIndexTransformer.Outcome.SHAPE_REJECTED, transformer.outcome());
        assertTrue(transformer.diagnostic().contains("add sites=2"), transformer.diagnostic());
    }

    @Test
    void admitsBothReviewedDigestFamilies() throws Exception {
        // The two pinned digests cover the 5.0–5.2 bytes and the 5.3.x bytes; a transformer
        // created with either pin alone must still select the fixture on its own digest.
        final byte[] fixture = FIXTURE.get(LIST);
        final String digest = TriangulationEdgeIndexTransformer.sha256(fixture);
        for (final Set<String> admit : List.of(
                Set.of(digest),
                Set.of(TriangulationEdgeIndexTransformer.REVIEWED_CLASS_SHA256_53X, digest),
                Set.of(TriangulationEdgeIndexTransformer.REVIEWED_CLASS_SHA256_5203, digest))) {
            final TriangulationEdgeIndexTransformer transformer =
                    new TriangulationEdgeIndexTransformer(admit);
            assertNotNull(
                    transformer.transform(
                            null,
                            TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME,
                            null,
                            null,
                            fixture),
                    "digest must be admitted from set " + admit);
            assertEquals(TriangulationEdgeIndexTransformer.Outcome.PATCHED, transformer.outcome());
        }
        // The two reviewed families are distinct pins.
        assertTrue(!TriangulationEdgeIndexTransformer.REVIEWED_CLASS_SHA256_53X
                .equals(TriangulationEdgeIndexTransformer.REVIEWED_CLASS_SHA256_5203));
    }

    // ------------------------------------------------------------------ fixture access

    private static Host wovenHost() throws Exception {
        final byte[] original = FIXTURE.get(LIST);
        final byte[] patched = new TriangulationEdgeIndexTransformer(
                        Set.of(TriangulationEdgeIndexTransformer.sha256(original)))
                .transform(
                        null,
                        TriangulationEdgeIndexTransformer.TARGET_INTERNAL_NAME,
                        null,
                        null,
                        original);
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
            this.point = Class.forName(POINT, true, loader)
                    .getDeclaredConstructor(float.class, float.class, int.class);
            this.triangle = Class.forName(TRIANGLE, true, loader)
                    .getDeclaredConstructor(
                            Class.forName(POINT, true, loader),
                            Class.forName(POINT, true, loader),
                            Class.forName(POINT, true, loader));
            this.edge = Class.forName(EDGE, true, loader)
                    .getDeclaredConstructor(
                            Class.forName(POINT, true, loader), Class.forName(POINT, true, loader));
        }

        /** Triangle whose three vertices share {@code base} as their coordinate seed. */
        Object triangle(final float base, final int ia, final int ib, final int ic)
                throws Exception {
            return triangle.newInstance(
                    point.newInstance(base, base + 0.25f, ia),
                    point.newInstance(base + 1f, base + 1.25f, ib),
                    point.newInstance(base + 2f, base + 2.25f, ic));
        }

        boolean add(final Object triangle) throws Exception {
            return (boolean) list.getClass()
                    .getMethod("a", triangle.getClass())
                    .invoke(list, triangle);
        }

        boolean remove(final Object triangle) throws Exception {
            return (boolean) list.getClass()
                    .getMethod("b", triangle.getClass())
                    .invoke(list, triangle);
        }

        boolean contains(final Object triangle) throws Exception {
            return (boolean) list.getClass().getMethod("c", triangle.getClass()).invoke(list, triangle);
        }

        void clear() throws Exception {
            list.getClass().getMethod("c").invoke(list);
        }

        List<?> query(final int ja, final int jb) throws Exception {
            final Object edgeInstance = edge.newInstance(
                    point.newInstance(0f, 0f, ja), point.newInstance(0f, 0f, jb));
            final Class<?> edgeClass = Class.forName(EDGE, true, loader);
            @SuppressWarnings("unchecked")
            final List<Object> hits = (List<Object>) list.getClass()
                    .getMethod("a", edgeClass)
                    .invoke(list, edgeInstance);
            return hits;
        }
    }

    /** A same-named class whose a(l) holds two LinkedHashSet.add sites — a shape the gate refuses. */
    private static byte[] badShapeListBytes() {
        final String name = TriangulationEdgeIndexFixture.LIST;
        final org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                name, null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_FINAL,
                "b", "Ljava/util/LinkedHashSet;", null, null).visitEnd();
        final String tri = TriangulationEdgeIndexFixture.TRIANGLE;
        final String edge = TriangulationEdgeIndexFixture.EDGE;
        final org.objectweb.asm.MethodVisitor add = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "a", "(L" + tri + ";)Z", null, null);
        add.visitCode();
        add.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        add.visitLdcInsn("");
        add.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                TriangulationEdgeIndexFixture.INTRINSICS, "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V", false);
        for (int site = 0; site < 2; site++) {
            add.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
            add.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, name, "b",
                    "Ljava/util/LinkedHashSet;");
            add.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
            add.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                    "java/util/LinkedHashSet", "add", "(Ljava/lang/Object;)Z", false);
            add.visitInsn(org.objectweb.asm.Opcodes.POP);
        }
        add.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
        add.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        add.visitMaxs(2, 2);
        add.visitEnd();
        final org.objectweb.asm.MethodVisitor remove = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "b", "(L" + tri + ";)Z", null, null);
        remove.visitCode();
        remove.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        remove.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, name, "b",
                "Ljava/util/LinkedHashSet;");
        remove.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        remove.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "java/util/LinkedHashSet", "remove", "(Ljava/lang/Object;)Z", false);
        remove.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        remove.visitMaxs(2, 2);
        remove.visitEnd();
        final org.objectweb.asm.MethodVisitor clear = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "c", "()V", null, null);
        clear.visitCode();
        clear.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        clear.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, name, "b",
                "Ljava/util/LinkedHashSet;");
        clear.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "java/util/LinkedHashSet", "clear", "()V", false);
        clear.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        clear.visitMaxs(1, 1);
        clear.visitEnd();
        final org.objectweb.asm.MethodVisitor query = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "a", "(L" + edge + ";)Ljava/util/List;",
                null, null);
        query.visitCode();
        query.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        query.visitLdcInsn("");
        query.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                TriangulationEdgeIndexFixture.INTRINSICS, "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V", false);
        query.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "java/util/ArrayList");
        query.visitInsn(org.objectweb.asm.Opcodes.DUP);
        query.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "java/util/ArrayList", "<init>", "()V", false);
        query.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        query.visitMaxs(2, 2);
        query.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
