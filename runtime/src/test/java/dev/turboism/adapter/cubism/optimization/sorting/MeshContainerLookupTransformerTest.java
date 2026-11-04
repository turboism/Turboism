package dev.turboism.adapter.cubism.optimization.sorting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Exercises the indexed replacement for {@code GMeshRenderer.getMeshContainer()} on a
 * synthetic class with the exact reviewed owner, method name and descriptor. The
 * synthetic {@code Components} counts iterator allocations so the test proves the
 * iterator path is gone rather than merely observing the same returned container.
 */
public class MeshContainerLookupTransformerTest {

    private static final String RENDERER = SortingInfoCacheTarget.MESH_RENDERER_OWNER;
    private static final String ENTITY = SortingInfoCacheTarget.ENTITY_OWNER;
    private static final String COMPONENTS = SortingInfoCacheTarget.COMPONENTS_OWNER;
    private static final String CONTAINER = SortingInfoCacheTarget.MESH_CONTAINER_OWNER;
    private static final String COMPONENT = "com/live2d/graphics3d/component/IGComponent";

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;

        Loader(final Path artifact) throws Exception {
            super(MeshContainerLookupTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null), new Permissions());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    private static byte[] emptyClass(final String name) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] componentInterface() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, COMPONENT, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] containerClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, CONTAINER, null, "java/lang/Object", new String[] {COMPONENT});
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] plainComponentClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, "TestComponent", null, "java/lang/Object", new String[] {COMPONENT});
        final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A {@code Components} stand-in with the reviewed {@code a() List} accessor and an
     * {@code iterator()} that counts allocations. The backing list is exposed through
     * {@code a()} exactly like the host's {@code CArrayList}.
     */
    private static byte[] componentsClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, COMPONENTS, null, "java/lang/Object", new String[] {"java/lang/Iterable"});
        writer.visitField(ACC_PRIVATE | ACC_FINAL, "list", "Ljava/util/List;", null, null)
                .visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "iteratorCalls", "I", null, null)
                .visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitTypeInsn(NEW, "java/util/ArrayList");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        method.visitFieldInsn(PUTFIELD, COMPONENTS, "list", "Ljava/util/List;");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(
                ACC_PUBLIC | ACC_FINAL,
                SortingInfoCacheTarget.COMPONENTS_LIST_METHOD,
                SortingInfoCacheTarget.COMPONENTS_LIST_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, COMPONENTS, "list", "Ljava/util/List;");
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(ACC_PUBLIC, "iterator", "()Ljava/util/Iterator;", null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, COMPONENTS, "iteratorCalls", "I");
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTSTATIC, COMPONENTS, "iteratorCalls", "I");
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, COMPONENTS, "list", "Ljava/util/List;");
        method.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] entityClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, ENTITY, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_FINAL, "components", "L" + COMPONENTS + ";", null, null)
                .visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitTypeInsn(NEW, COMPONENTS);
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, COMPONENTS, "<init>", "()V", false);
        method.visitFieldInsn(PUTFIELD, ENTITY, "components", "L" + COMPONENTS + ";");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(ACC_PUBLIC | ACC_FINAL, "getComponents", "()L" + COMPONENTS + ";", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, ENTITY, "components", "L" + COMPONENTS + ";");
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * The reviewed lookup semantics before the rewrite: iterate components, cast to
     * {@code IGComponent}, and return the first {@code GMeshContainer}. {@code drift}
     * inserts a NOP so the method shape differs from the reference.
     */
    private static byte[] rendererClass(final boolean drift) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, RENDERER, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_FINAL, "entity", "L" + ENTITY + ";", null, null)
                .visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitTypeInsn(NEW, ENTITY);
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, ENTITY, "<init>", "()V", false);
        method.visitFieldInsn(PUTFIELD, RENDERER, "entity", "L" + ENTITY + ";");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(ACC_PUBLIC, "getEntity", "()L" + ENTITY + ";", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, RENDERER, "entity", "L" + ENTITY + ";");
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        method = writer.visitMethod(
                ACC_PUBLIC | ACC_FINAL,
                SortingInfoCacheTarget.MESH_CONTAINER_METHOD,
                SortingInfoCacheTarget.MESH_CONTAINER_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        if (drift) method.visitInsn(NOP);
        final Label loop = new Label();
        final Label done = new Label();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, RENDERER, "getEntity", "()L" + ENTITY + ";", false);
        method.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "getComponents", "()L" + COMPONENTS + ";", false);
        method.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
        method.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
        method.visitVarInsn(ASTORE, 1);
        method.visitLabel(loop);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        method.visitJumpInsn(IFEQ, done);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        method.visitVarInsn(ASTORE, 2);
        method.visitVarInsn(ALOAD, 2);
        method.visitTypeInsn(CHECKCAST, COMPONENT);
        method.visitTypeInsn(INSTANCEOF, CONTAINER);
        method.visitJumpInsn(IFEQ, loop);
        method.visitVarInsn(ALOAD, 2);
        method.visitTypeInsn(CHECKCAST, CONTAINER);
        method.visitInsn(ARETURN);
        method.visitLabel(done);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Environment {
        final Loader loader;
        final Class<?> renderer;
        final Class<?> entity;
        final Class<?> components;
        final Class<?> container;
        final Class<?> plainComponent;
        final Method lookup;

        Environment(final Path artifact) throws Exception {
            loader = new Loader(artifact);
            loader.define(COMPONENT, componentInterface());
            container = loader.define(CONTAINER, containerClass());
            plainComponent = loader.define("TestComponent", plainComponentClass());
            components = loader.define(COMPONENTS, componentsClass());
            entity = loader.define(ENTITY, entityClass());
            final byte[] reference = rendererClass(false);
            final MeshContainerLookupTransformer transformer =
                    new MeshContainerLookupTransformer(loader, artifact, reference);
            final byte[] output = transformer.transform(null, loader, RENDERER, null, loader.domain, reference);
            assertNotNull(output, transformer.failure());
            renderer = loader.define(RENDERER, output);
            lookup = renderer.getMethod(SortingInfoCacheTarget.MESH_CONTAINER_METHOD);
        }

        Object instance() throws Exception {
            return renderer.getDeclaredConstructor().newInstance();
        }

        Object container() throws Exception {
            return container.getDeclaredConstructor().newInstance();
        }

        Object plainComponent() throws Exception {
            return plainComponent.getDeclaredConstructor().newInstance();
        }

        List<Object> components(final Object rendererInstance) throws Exception {
            final Object entityInstance =
                    rendererInstance.getClass().getMethod("getEntity").invoke(rendererInstance);
            final Object componentsInstance = entity.getMethod("getComponents").invoke(entityInstance);
            @SuppressWarnings("unchecked")
            final List<Object> list = (List<Object>) components
                    .getMethod(SortingInfoCacheTarget.COMPONENTS_LIST_METHOD)
                    .invoke(componentsInstance);
            return list;
        }

        int iteratorCalls() throws Exception {
            return components.getField("iteratorCalls").getInt(null);
        }

        void resetIteratorCalls() throws Exception {
            components.getField("iteratorCalls").setInt(null, 0);
        }
    }

    private static Path artifact() throws Exception {
        return Files.createTempFile("mesh-container-host", ".jar");
    }

    private static Object invokeLookup(final Environment environment, final Object instance) throws Exception {
        return environment.lookup.invoke(instance);
    }

    @Test
    void indexedLookupReturnsFirstContainerWithoutIterator() throws Exception {
        final Environment environment = new Environment(artifact());
        final Object renderer = environment.instance();
        final Object first = environment.container();
        final Object second = environment.container();
        final Object notAContainer = environment.plainComponent();
        final List<Object> components = environment.components(renderer);
        components.add(notAContainer);
        components.add(first);
        components.add(second);
        environment.resetIteratorCalls();
        assertSame(first, invokeLookup(environment, renderer));
        assertEquals(0, environment.iteratorCalls(), "rewritten lookup must not allocate an iterator");
    }

    @Test
    void originalLookupUsedIteratorAndReturnedTheSameContainer() throws Exception {
        final Path artifact = artifact();
        final Loader loader = new Loader(artifact);
        loader.define(COMPONENT, componentInterface());
        final Class<?> containerType = loader.define(CONTAINER, containerClass());
        final Class<?> components = loader.define(COMPONENTS, componentsClass());
        final Class<?> entity = loader.define(ENTITY, entityClass());
        final Class<?> renderer = loader.define(RENDERER, rendererClass(false));
        final Object instance = renderer.getDeclaredConstructor().newInstance();
        final Object entityInstance = renderer.getMethod("getEntity").invoke(instance);
        final Object componentsInstance = entity.getMethod("getComponents").invoke(entityInstance);
        @SuppressWarnings("unchecked")
        final List<Object> list = (List<Object>) components
                .getMethod(SortingInfoCacheTarget.COMPONENTS_LIST_METHOD)
                .invoke(componentsInstance);
        final Object first = containerType.getDeclaredConstructor().newInstance();
        list.add(first);
        components.getField("iteratorCalls").setInt(null, 0);
        assertSame(
                first,
                renderer.getMethod(SortingInfoCacheTarget.MESH_CONTAINER_METHOD).invoke(instance));
        assertEquals(1, components.getField("iteratorCalls").getInt(null));
    }

    @Test
    void emptyOrNonContainerComponentsReturnNull() throws Exception {
        final Environment environment = new Environment(artifact());
        final Object renderer = environment.instance();
        environment.resetIteratorCalls();
        assertNull(invokeLookup(environment, renderer));

        environment.components(renderer).add(environment.plainComponent());
        assertNull(invokeLookup(environment, renderer));
        assertEquals(0, environment.iteratorCalls());
    }

    @Test
    void shapeDriftIsRejected() throws Exception {
        final Path artifact = artifact();
        final Loader loader = new Loader(artifact);
        final byte[] reference = rendererClass(false);
        final byte[] drifted = rendererClass(true);
        final MeshContainerLookupTransformer transformer =
                new MeshContainerLookupTransformer(loader, artifact, reference);
        assertNull(transformer.transform(null, loader, RENDERER, null, loader.domain, drifted));
        assertTrue(transformer.failure().contains("shape"));
    }

    @Test
    void foreignArtifactIsRejected() throws Exception {
        final Loader loader = new Loader(artifact());
        final byte[] reference = rendererClass(false);
        final MeshContainerLookupTransformer transformer =
                new MeshContainerLookupTransformer(loader, artifact(), reference);
        assertNull(transformer.transform(null, loader, RENDERER, null, loader.domain, reference));
        assertTrue(transformer.failure().contains("source"));
    }

    @Test
    void missingMethodIsRejectedAtConstruction() throws Exception {
        final Loader loader = new Loader(artifact());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshContainerLookupTransformer(loader, artifact(), emptyClass(RENDERER)));
    }

    @Test
    void targetExposesReviewedDependencies() {
        final SortingInfoCacheTarget target =
                SortingInfoCacheTarget.of(ReviewedHostArtifacts.CUBISM_5_3_04).orElseThrow();
        assertEquals(2, target.dependencies().size());
        assertEquals(
                SortingInfoCacheTarget.ENTITY_OWNER,
                target.dependencies().get(0).owner());
        assertEquals(
                SortingInfoCacheTarget.COMPONENTS_OWNER,
                target.dependencies().get(1).owner());
    }
}
