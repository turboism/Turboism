package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ObjectContextMenuAppendNativeMethodTransformerTest {

    @Test
    void injectsBeforeOneExactAppendAndCarriesMenuLocationAndBuilderSource() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final ObjectContextMenuAppendNativeMethodTransformer transformer =
            new ObjectContextMenuAppendNativeMethodTransformer(
                "fixture/Builder", "build", "(Ljava/lang/Object;)V", loader,
                "fixture/Menu", "append", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                Location.DEFORMER_TAB, 1, 1
            );

        assertNull(transformer.transform(
            null, getClass().getClassLoader(), "fixture/Builder", null, null, builderClass(1)
        ));
        assertNull(transformer.transform(
            null, loader, "fixture/Other", null, null, builderClass(1)
        ));
        assertNull(transformer.transform(
            null, loader, "fixture/Builder", null, null, builderClass(2)
        ));

        final byte[] transformed = transformer.transform(
            null, loader, "fixture/Builder", null, null, builderClass(1)
        );
        assertNotNull(transformed);
        assertLoaderNeutral(transformed);

        final Class<?> menuType = loader.define("fixture.Menu", menuClass());
        final Class<?> builderType = loader.define("fixture.Builder", transformed);
        final Object builder = builderType.getConstructor().newInstance();
        final Object source = new Object();
        final List<Object> observed = new ArrayList<>();

        try (Registration ignored = NativeObjectContextMenuBridge.install(
            (menu, location, actualSource) -> {
                observed.add(menu);
                observed.add(location);
                observed.add(actualSource);
                return menu;
            }
        )) {
            builderType.getMethod("build", Object.class).invoke(builder, source);
        }

        assertSame(builderType.getField("menu").get(builder), observed.get(0));
        assertEquals(Location.DEFORMER_TAB, observed.get(1));
        assertSame(builder, observed.get(2));
        assertEquals(1, menuType.getField("appends").getInt(builderType.getField("menu").get(builder)));
    }

    @Test
    void supportsSingleItemAppendShapeUsedByDeformerAndParameterMenus() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final ObjectContextMenuAppendNativeMethodTransformer transformer =
            new ObjectContextMenuAppendNativeMethodTransformer(
                "fixture/SingleBuilder", "build", "()V", loader,
                "fixture/SingleMenu", "append", "(Ljava/lang/Object;)V",
                Location.PARAMETER_TAB, 1, 1
            );

        final byte[] transformed = transformer.transform(
            null, loader, "fixture/SingleBuilder", null, null, singleBuilderClass()
        );
        assertNotNull(transformed);
        assertLoaderNeutral(transformed);

        final Class<?> menuType = loader.define("fixture.SingleMenu", singleMenuClass());
        final Class<?> builderType = loader.define("fixture.SingleBuilder", transformed);
        final Object builder = builderType.getConstructor().newInstance();
        final List<Object> observed = new ArrayList<>();
        try (Registration ignored = NativeObjectContextMenuBridge.install(
            (menu, location, actualSource) -> {
                observed.add(menu);
                observed.add(location);
                observed.add(actualSource);
                return menu;
            }
        )) {
            builderType.getMethod("build").invoke(builder);
        }

        assertSame(builderType.getField("menu").get(builder), observed.get(0));
        assertEquals(Location.PARAMETER_TAB, observed.get(1));
        assertSame(builder, observed.get(2));
        assertEquals(1, menuType.getField("appends").getInt(builderType.getField("menu").get(builder)));
    }

    private static byte[] singleBuilderClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/SingleBuilder", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "menu", "Lfixture/SingleMenu;", null, null).visitEnd();
        constructor(writer, "fixture/SingleBuilder");
        final MethodVisitor build = writer.visitMethod(Opcodes.ACC_PUBLIC, "build", "()V", null, null);
        build.visitCode();
        build.visitVarInsn(Opcodes.ALOAD, 0);
        build.visitTypeInsn(Opcodes.NEW, "fixture/SingleMenu");
        build.visitInsn(Opcodes.DUP);
        build.visitMethodInsn(Opcodes.INVOKESPECIAL, "fixture/SingleMenu", "<init>", "()V", false);
        build.visitFieldInsn(Opcodes.PUTFIELD, "fixture/SingleBuilder", "menu", "Lfixture/SingleMenu;");
        build.visitVarInsn(Opcodes.ALOAD, 0);
        build.visitFieldInsn(Opcodes.GETFIELD, "fixture/SingleBuilder", "menu", "Lfixture/SingleMenu;");
        build.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
        build.visitInsn(Opcodes.DUP);
        build.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        build.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, "fixture/SingleMenu", "append", "(Ljava/lang/Object;)V", false
        );
        build.visitInsn(Opcodes.RETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] singleMenuClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/SingleMenu", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "appends", "I", null, null).visitEnd();
        constructor(writer, "fixture/SingleMenu");
        final MethodVisitor append = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "append", "(Ljava/lang/Object;)V", null, null
        );
        append.visitCode();
        append.visitVarInsn(Opcodes.ALOAD, 0);
        append.visitInsn(Opcodes.DUP);
        append.visitFieldInsn(Opcodes.GETFIELD, "fixture/SingleMenu", "appends", "I");
        append.visitInsn(Opcodes.ICONST_1);
        append.visitInsn(Opcodes.IADD);
        append.visitFieldInsn(Opcodes.PUTFIELD, "fixture/SingleMenu", "appends", "I");
        append.visitInsn(Opcodes.RETURN);
        append.visitMaxs(0, 0);
        append.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] builderClass(final int matchingAppends) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Builder", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "menu", "Lfixture/Menu;", null, null).visitEnd();
        constructor(writer, "fixture/Builder");
        final MethodVisitor build = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "build", "(Ljava/lang/Object;)V", null, null
        );
        build.visitCode();
        build.visitVarInsn(Opcodes.ALOAD, 0);
        build.visitTypeInsn(Opcodes.NEW, "fixture/Menu");
        build.visitInsn(Opcodes.DUP);
        build.visitMethodInsn(Opcodes.INVOKESPECIAL, "fixture/Menu", "<init>", "()V", false);
        build.visitFieldInsn(Opcodes.PUTFIELD, "fixture/Builder", "menu", "Lfixture/Menu;");
        for (int index = 0; index < matchingAppends; index++) {
            build.visitVarInsn(Opcodes.ALOAD, 0);
            build.visitFieldInsn(Opcodes.GETFIELD, "fixture/Builder", "menu", "Lfixture/Menu;");
            build.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            build.visitInsn(Opcodes.DUP);
            build.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            build.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            build.visitInsn(Opcodes.DUP);
            build.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            build.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "fixture/Menu", "append",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false
            );
        }
        build.visitInsn(Opcodes.RETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] menuClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Menu", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "appends", "I", null, null).visitEnd();
        constructor(writer, "fixture/Menu");
        final MethodVisitor append = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "append", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null
        );
        append.visitCode();
        append.visitVarInsn(Opcodes.ALOAD, 0);
        append.visitInsn(Opcodes.DUP);
        append.visitFieldInsn(Opcodes.GETFIELD, "fixture/Menu", "appends", "I");
        append.visitInsn(Opcodes.ICONST_1);
        append.visitInsn(Opcodes.IADD);
        append.visitFieldInsn(Opcodes.PUTFIELD, "fixture/Menu", "appends", "I");
        append.visitInsn(Opcodes.RETURN);
        append.visitMaxs(0, 0);
        append.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void assertLoaderNeutral(final byte[] transformed) {
        org.junit.jupiter.api.Assertions.assertFalse(
            new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("dev/turboism/ui/context/NativeObjectContextMenuBridge")
        );
    }

    private static void constructor(final ClassWriter writer, final String owner) {
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader() {
            super(ObjectContextMenuAppendNativeMethodTransformerTest.class.getClassLoader());
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
