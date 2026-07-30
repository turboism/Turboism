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

class ObjectContextMenuNativeMethodTransformerTest {

    @Test
    void injectsOnlyOneExactMenuBuildPointAndCarriesLocationAndSource() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final ObjectContextMenuNativeMethodTransformer transformer =
            new ObjectContextMenuNativeMethodTransformer(
                "fixture/Builder",
                "build",
                "(Ljava/lang/Object;)Lfixture/Menu;",
                loader,
                Location.PART_TAB
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

        loader.define("fixture.Menu", menuClass());
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
            final Method build = builderType.getMethod("build", Object.class);
            final Object menu = build.invoke(builder, source);
            assertSame(menu, observed.get(0));
        }

        assertEquals(Location.PART_TAB, observed.get(1));
        assertSame(source, observed.get(2));
    }

    private static byte[] builderClass(final int matchingReturns) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Builder", null, "java/lang/Object", null);
        constructor(writer, "fixture/Builder");

        final MethodVisitor build = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "build",
            "(Ljava/lang/Object;)Lfixture/Menu;",
            null,
            null
        );
        build.visitCode();
        build.visitTypeInsn(Opcodes.NEW, "fixture/Menu");
        build.visitInsn(Opcodes.DUP);
        build.visitMethodInsn(Opcodes.INVOKESPECIAL, "fixture/Menu", "<init>", "()V", false);
        if (matchingReturns == 2) {
            build.visitInsn(Opcodes.DUP);
            final org.objectweb.asm.Label second = new org.objectweb.asm.Label();
            build.visitJumpInsn(Opcodes.IFNONNULL, second);
            build.visitInsn(Opcodes.ARETURN);
            build.visitLabel(second);
        }
        build.visitInsn(Opcodes.ARETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] menuClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Menu", null, "java/lang/Object", null);
        constructor(writer, "fixture/Menu");
        writer.visitEnd();
        return writer.toByteArray();
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
            super(ObjectContextMenuNativeMethodTransformerTest.class.getClassLoader());
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
