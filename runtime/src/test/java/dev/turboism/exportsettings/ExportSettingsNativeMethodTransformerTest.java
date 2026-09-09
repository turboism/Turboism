package dev.turboism.exportsettings;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-shape tests for the Export Settings dialog transformer.
 *
 * <p>The fixture classes replicate the verified 5.3.02 host shape
 * ({@code exporter/e} with the private content builder {@code b()V}, the modal
 * {@code a(...)Z} with its {@code iconst_0; ireturn} cancel pair and confirm return,
 * the {@code c} window field and {@code window/y.e()} JDialog accessor) without
 * copying any host method body.</p>
 */
class ExportSettingsNativeMethodTransformerTest {

    private static final String OWNER = "com/live2d/cubism/doc/model/exporter/e";
    private static final String WINDOW = "com/live2d/ui/window/y";
    private static final String SHOW_DESCRIPTOR =
        "(Lcom/live2d/ui/window/V;Lcom/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData;"
            + "Lcom/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData;)Z";

    @Test
    void injectsExactAttachCancelAndGateSitesWithLoaderNeutralReferences() {
        final FixtureLoader loader = new FixtureLoader();
        final ExportSettingsNativeMethodTransformer transformer = transformer(loader);

        final byte[] original = fixtureClass(loader, FixtureShape.VALID);
        final byte[] transformed = transformer.transform(
            null, loader, OWNER, null, null, original
        );
        assertNotNull(transformed, "valid fixture must transform");

        final Counts counts = countSites(transformed);
        assertEquals(1, counts.bReturns, "content builder must keep exactly one RETURN");
        assertEquals(1, counts.attachCalls, "content builder must carry exactly one attach call");
        assertEquals(1, counts.cancelCalls, "gate method must carry exactly one cancel call");
        assertEquals(1, counts.decideCalls, "gate method must carry exactly one decide call");
        assertEquals(4, counts.ireturnCount, "gate method must carry the cancel/reject/handler/confirm returns");
        assertFalse(
            new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("dev/turboism")
                || new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("org/objectweb"),
            "transformed bytecode must not reference SDK/runtime/ASM types"
        );
    }

    @Test
    void retransformIsIdempotentAndLeavesTheClassUnchanged() {
        final FixtureLoader loader = new FixtureLoader();
        final ExportSettingsNativeMethodTransformer transformer = transformer(loader);

        final byte[] transformed = transformer.transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.VALID)
        );
        assertNotNull(transformed);
        assertNull(
            transformer.transform(null, loader, OWNER, null, null, transformed),
            "a second transform of an already-transformed class must be a no-op"
        );
        assertNull(
            transformer.transform(null, loader, OWNER, null, null, transformed),
            "repeated retransformation must never duplicate UI or callback sites"
        );
    }

    @Test
    void failsClosedOnUnexpectedShapesAndForeignClasses() {
        final FixtureLoader loader = new FixtureLoader();
        final ExportSettingsNativeMethodTransformer transformer = transformer(loader);

        assertNull(transformer.transform(
            null, loader, "com/live2d/cubism/doc/model/exporter/other", null, null,
            fixtureClass(loader, FixtureShape.VALID)
        ), "foreign class must not transform");
        assertNull(transformer.transform(
            null, getClass().getClassLoader(), OWNER, null, null,
            fixtureClass(loader, FixtureShape.VALID)
        ), "wrong loader must not transform");
        assertNull(transformer.transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.TWO_RETURNS_IN_BUILDER)
        ), "content builder with two returns must fail closed");
        assertNull(transformer.transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.NO_CANCEL_PAIR)
        ), "gate method without the iconst_0/ireturn cancel pair must fail closed");
        assertNull(transformer.transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.REVERSED_GATE_RETURNS)
        ), "a true-return preceding the cancel return must not be treated as a confirmed gate");
        assertNull(transformer.transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.THREE_GATE_RETURNS)
        ), "a gate method with a malformed third return must fail closed");
        assertNull(transformer.transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.MISSING_BUILDER)
        ), "missing content builder must fail closed");
    }

    @Test
    void transformedFixtureExecutesFailOpenWhenTheWindowIsUnavailable() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final byte[] transformed = transformer(loader).transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.VALID)
        );
        final Class<?> ownerType = loader.define("com.live2d.cubism.doc.model.exporter.e", transformed);
        final Class<?> windowType = loader.loadClass("com.live2d.ui.window.y");

        final List<String> calls = new ArrayList<>();
        try (Registration ignored = NativeExportSettingsDialogBridge.install(
            new NativeExportSettingsDialogBridge.Handler() {
                @Override
                public Object attach(final Object owner, final Object container) {
                    calls.add("attach:" + owner.getClass().getName());
                    return null;
                }

                @Override
                public void cancel(final Object owner) {
                    calls.add("cancel");
                }

                @Override
                public Boolean decide(final Object owner) {
                    calls.add("decide");
                    return Boolean.TRUE;
                }
            }
        )) {
            final Object dialog = ownerType.getConstructor().newInstance();
            final Method build = ownerType.getDeclaredMethod("b");
            build.setAccessible(true);
            build.invoke(dialog);
            assertTrue(calls.isEmpty(), "attach must fail open when the window is unavailable");

            windowType.getField("confirmed").setBoolean(null, false);
            final Method show = ownerType.getDeclaredMethod(
                "a",
                Class.forName("com.live2d.ui.window.V", false, loader),
                Class.forName(
                    "com.live2d.cubism.doc.model.exporter.CModelExportSettingDialogData",
                    false,
                    loader
                ),
                Class.forName(
                    "com.live2d.cubism.doc.model.exporter.CModelExportSettingDialogData",
                    false,
                    loader
                )
            );
            show.setAccessible(true);
            assertEquals(Boolean.FALSE, show.invoke(dialog, null, null, null));
            assertEquals(List.of("cancel"), calls, "cancel must notify the bridge exactly once");

            calls.clear();
            windowType.getField("confirmed").setBoolean(null, true);
            assertEquals(Boolean.TRUE, show.invoke(dialog, null, null, null));
            assertEquals(List.of("decide"), calls, "confirm must ask the bridge exactly once");
        }
    }

    @Test
    void transformedGateRejectsConfirmWhenTheDecidePropertyIsMissing() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final byte[] transformed = transformer(loader).transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.VALID)
        );
        final Class<?> ownerType = loader.define("com.live2d.cubism.doc.model.exporter.e", transformed);
        final Class<?> windowType = loader.loadClass("com.live2d.ui.window.y");
        assertNull(System.getProperties().get(NativeExportSettingsDialogBridge.DECIDE_KEY));
        windowType.getField("confirmed").setBoolean(null, true);
        final Object dialog = ownerType.getConstructor().newInstance();
        final Method show = ownerType.getDeclaredMethod(
            "a",
            Class.forName("com.live2d.ui.window.V", false, loader),
            Class.forName(
                "com.live2d.cubism.doc.model.exporter.CModelExportSettingDialogData", false, loader
            ),
            Class.forName(
                "com.live2d.cubism.doc.model.exporter.CModelExportSettingDialogData", false, loader
            )
        );
        show.setAccessible(true);
        assertEquals(Boolean.FALSE, show.invoke(dialog, null, null, null),
            "the transformer must not accept a native true result without an emitted decision callback");
    }

    @Test
    void transformedGateRejectsContinuationWhenTheBridgeDecidesFalse() throws Exception {
        final FixtureLoader loader = new FixtureLoader();
        final byte[] transformed = transformer(loader).transform(
            null, loader, OWNER, null, null, fixtureClass(loader, FixtureShape.VALID)
        );
        final Class<?> ownerType = loader.define("com.live2d.cubism.doc.model.exporter.e", transformed);
        final Class<?> windowType = loader.loadClass("com.live2d.ui.window.y");
        try (Registration ignored = NativeExportSettingsDialogBridge.install(
            new NativeExportSettingsDialogBridge.Handler() {
                @Override
                public Object attach(final Object owner, final Object container) {
                    return null;
                }

                @Override
                public Boolean decide(final Object owner) {
                    return Boolean.FALSE;
                }
            }
        )) {
            windowType.getField("confirmed").setBoolean(null, true);
            final Object dialog = ownerType.getConstructor().newInstance();
            final Method show = ownerType.getDeclaredMethod(
                "a",
                Class.forName("com.live2d.ui.window.V", false, loader),
                Class.forName(
                    "com.live2d.cubism.doc.model.exporter.CModelExportSettingDialogData",
                    false, loader
                ),
                Class.forName(
                    "com.live2d.cubism.doc.model.exporter.CModelExportSettingDialogData",
                    false, loader
                )
            );
            show.setAccessible(true);
            assertEquals(Boolean.FALSE, show.invoke(dialog, null, null, null),
                "a rejected confirm must prevent native export continuation");
        }
    }

    private static ExportSettingsNativeMethodTransformer transformer(final ClassLoader loader) {
        return new ExportSettingsNativeMethodTransformer(
            OWNER,
            "b",
            "()V",
            "a",
            SHOW_DESCRIPTOR,
            "c",
            "L" + WINDOW + ";",
            WINDOW,
            "e",
            "()Ljavax/swing/JDialog;",
            loader
        );
    }

    private static byte[] fixtureClass(final FixtureLoader loader, final FixtureShape shape) {
        defineSupport(loader);
        final ClassWriter writer = new ClassWriter(
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        );
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, OWNER, null, "java/lang/Object", null);
        writer.visitField(
            Opcodes.ACC_PRIVATE, "c", "L" + WINDOW + ";", null, null
        );
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (shape != FixtureShape.MISSING_BUILDER) {
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitTypeInsn(Opcodes.NEW, WINDOW);
            constructor.visitInsn(Opcodes.DUP);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, WINDOW, "<init>", "()V", false);
            constructor.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "c", "L" + WINDOW + ";");
        }
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        if (shape != FixtureShape.MISSING_BUILDER) {
            final MethodVisitor builder = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "b", "()V", null, null
            );
            builder.visitCode();
            if (shape == FixtureShape.TWO_RETURNS_IN_BUILDER) {
                // Two reachable returns via a conditional branch: a plain second
                // sequential RETURN would be dropped as dead code by COMPUTE_FRAMES
                // and never exercise the transformer's cardinality rejection.
                final Label branch = new Label();
                builder.visitInsn(Opcodes.ICONST_0);
                builder.visitJumpInsn(Opcodes.IFEQ, branch);
                builder.visitInsn(Opcodes.RETURN);
                builder.visitLabel(branch);
            }
            builder.visitInsn(Opcodes.RETURN);
            builder.visitMaxs(0, 0);
            builder.visitEnd();
        }

        final MethodVisitor show = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "a", SHOW_DESCRIPTOR, null, null
        );
        show.visitCode();
        if (shape == FixtureShape.NO_CANCEL_PAIR) {
            show.visitInsn(Opcodes.ICONST_1);
            show.visitInsn(Opcodes.IRETURN);
            show.visitMaxs(0, 0);
            show.visitEnd();
            writer.visitEnd();
            return writer.toByteArray();
        }
        if (shape == FixtureShape.REVERSED_GATE_RETURNS) {
            show.visitVarInsn(Opcodes.ALOAD, 0);
            show.visitFieldInsn(Opcodes.GETFIELD, OWNER, "c", "L" + WINDOW + ";");
            show.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WINDOW, "confirmed", "()Z", false);
            final Label cancelled = new Label();
            show.visitJumpInsn(Opcodes.IFEQ, cancelled);
            show.visitInsn(Opcodes.ICONST_1);
            show.visitInsn(Opcodes.IRETURN);
            show.visitLabel(cancelled);
            show.visitInsn(Opcodes.ICONST_0);
            show.visitInsn(Opcodes.IRETURN);
            show.visitMaxs(0, 0);
            show.visitEnd();
            writer.visitEnd();
            return writer.toByteArray();
        }
        if (shape == FixtureShape.THREE_GATE_RETURNS) {
            final Label cancelled = new Label();
            final Label confirm = new Label();
            final Label extra = new Label();
            show.visitVarInsn(Opcodes.ALOAD, 0);
            show.visitFieldInsn(Opcodes.GETFIELD, OWNER, "c", "L" + WINDOW + ";");
            show.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WINDOW, "confirmed", "()Z", false);
            show.visitJumpInsn(Opcodes.IFEQ, cancelled);
            show.visitVarInsn(Opcodes.ALOAD, 0);
            show.visitFieldInsn(Opcodes.GETFIELD, OWNER, "c", "L" + WINDOW + ";");
            show.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WINDOW, "confirmed", "()Z", false);
            show.visitJumpInsn(Opcodes.IFEQ, extra);
            show.visitJumpInsn(Opcodes.GOTO, confirm);
            show.visitLabel(cancelled);
            show.visitInsn(Opcodes.ICONST_0);
            show.visitInsn(Opcodes.IRETURN);
            show.visitLabel(confirm);
            show.visitInsn(Opcodes.ICONST_1);
            show.visitInsn(Opcodes.IRETURN);
            show.visitLabel(extra);
            show.visitInsn(Opcodes.ICONST_0);
            show.visitInsn(Opcodes.IRETURN);
            show.visitMaxs(0, 0);
            show.visitEnd();
            writer.visitEnd();
            return writer.toByteArray();
        }
        show.visitVarInsn(Opcodes.ALOAD, 0);
        show.visitFieldInsn(Opcodes.GETFIELD, OWNER, "c", "L" + WINDOW + ";");
        show.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WINDOW, "confirmed", "()Z", false);
        final Label confirm = new Label();
        show.visitJumpInsn(Opcodes.IFNE, confirm);
        show.visitInsn(Opcodes.ICONST_0);
        show.visitInsn(Opcodes.IRETURN);
        show.visitLabel(confirm);
        show.visitInsn(Opcodes.ICONST_1);
        show.visitInsn(Opcodes.IRETURN);
        show.visitMaxs(0, 0);
        show.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void defineSupport(final FixtureLoader loader) {
        if (loader.hasClass(WINDOW.replace('/', '.'))) {
            return;
        }
        loader.define("com.live2d.ui.window.V", emptyClass("com/live2d/ui/window/V"));
        loader.define(
            "com.live2d.cubism.doc.model.exporter.CModelExportSettingDialogData",
            emptyClass("com/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData")
        );
        final ClassWriter window = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        window.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, WINDOW, null, "com/live2d/ui/window/V", null);
        window.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "confirmed", "Z", null, null
        );
        final MethodVisitor windowConstructor = window.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        windowConstructor.visitCode();
        windowConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        windowConstructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "com/live2d/ui/window/V", "<init>", "()V", false
        );
        windowConstructor.visitInsn(Opcodes.RETURN);
        windowConstructor.visitMaxs(0, 0);
        windowConstructor.visitEnd();
        final MethodVisitor confirmed = window.visitMethod(
            Opcodes.ACC_PUBLIC, "confirmed", "()Z", null, null
        );
        confirmed.visitCode();
        confirmed.visitFieldInsn(Opcodes.GETSTATIC, WINDOW, "confirmed", "Z");
        confirmed.visitInsn(Opcodes.IRETURN);
        confirmed.visitMaxs(0, 0);
        confirmed.visitEnd();
        final MethodVisitor jdialog = window.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "e", "()Ljavax/swing/JDialog;", null, null
        );
        jdialog.visitCode();
        jdialog.visitTypeInsn(Opcodes.NEW, "java/awt/HeadlessException");
        jdialog.visitInsn(Opcodes.DUP);
        jdialog.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/awt/HeadlessException", "<init>", "()V", false
        );
        jdialog.visitInsn(Opcodes.ATHROW);
        jdialog.visitMaxs(0, 0);
        jdialog.visitEnd();
        loader.define("com.live2d.ui.window.y", window.toByteArray());
    }

    private static byte[] emptyClass(final String internalName) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Counts countSites(final byte[] transformed) {
        final Counts counts = new Counts();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String methodName,
                        final String descriptor,
                        final boolean isInterface
                    ) {
                        if ("java/util/function/BiFunction".equals(owner) && "apply".equals(methodName)) {
                            counts.attachCalls++;
                        }
                        if ("java/util/function/Consumer".equals(owner) && "accept".equals(methodName)) {
                            counts.cancelCalls++;
                        }
                        if ("java/util/function/Function".equals(owner) && "apply".equals(methodName)) {
                            counts.decideCalls++;
                        }
                    }

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.IRETURN) {
                            counts.ireturnCount++;
                        }
                        if (opcode == Opcodes.RETURN && "b".equals(name)) {
                            counts.bReturns++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return counts;
    }

    private enum FixtureShape {
        VALID,
        TWO_RETURNS_IN_BUILDER,
        NO_CANCEL_PAIR,
        REVERSED_GATE_RETURNS,
        THREE_GATE_RETURNS,
        MISSING_BUILDER
    }

    private static final class Counts {
        private int attachCalls;
        private int cancelCalls;
        private int decideCalls;
        private int ireturnCount;
        private int bReturns;
    }

    private static final class FixtureLoader extends ClassLoader {
        private final java.util.Set<String> defined = new java.util.HashSet<>();

        private FixtureLoader() {
            super(ExportSettingsNativeMethodTransformerTest.class.getClassLoader());
        }

        private boolean hasClass(final String name) {
            return defined.contains(name);
        }

        private Class<?> define(final String name, final byte[] bytes) {
            defined.add(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
