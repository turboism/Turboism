package dev.turboism.adapter.cubism.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

final class ShaderUniformTransformerTest {
    static final String OWNER = ShaderUniformTransformer.OWNER;
    static final String DRAW = ShaderUniformTransformer.DRAW_METHOD;
    static final String DRAW_DESC = ShaderUniformTransformer.DRAW_DESC;

    static byte[] fixture(String owner, int drawCalls, boolean lifecycleA, boolean lifecycleB) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor draw = writer.visitMethod(Opcodes.ACC_PUBLIC, DRAW, DRAW_DESC, null, null);
        draw.visitCode();
        for (int i = 0; i < drawCalls; i++) {
            draw.visitInsn(Opcodes.ACONST_NULL);
            draw.visitTypeInsn(Opcodes.CHECKCAST, ShaderUniformTransformer.GL_OWNER);
            draw.visitInsn(Opcodes.ICONST_0);
            draw.visitLdcInsn("u");
            draw.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    ShaderUniformTransformer.GL_OWNER,
                    ShaderUniformTransformer.QUERY_METHOD,
                    ShaderUniformTransformer.QUERY_DESC,
                    true);
            draw.visitInsn(Opcodes.POP);
        }
        draw.visitInsn(Opcodes.RETURN);
        draw.visitMaxs(4, 4);
        draw.visitEnd();
        if (lifecycleA) {
            MethodVisitor init = writer.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    ShaderUniformTransformer.LIFECYCLE_METHODS[0],
                    ShaderUniformTransformer.LIFECYCLE_DESCS[0],
                    null,
                    null);
            init.visitCode();
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
        }
        if (lifecycleB) {
            MethodVisitor program = writer.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    ShaderUniformTransformer.LIFECYCLE_METHODS[1],
                    ShaderUniformTransformer.LIFECYCLE_DESCS[1],
                    null,
                    null);
            program.visitCode();
            program.visitInsn(Opcodes.RETURN);
            program.visitMaxs(1, 2);
            program.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void patchRewritesSingleDrawQueryAndAddsTwoLifecycleInvalidations() {
        byte[] patched = ShaderUniformTransformer.patch(fixture(OWNER, 1, true, true));
        int[] count = new int[4];
        new ClassReader(patched)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String method, String descriptor, boolean itf) {
                                        if (owner.equals(ShaderUniformTransformer.BRIDGE_OWNER)) {
                                            if (method.equals("lookup")) count[0]++;
                                            if (method.equals("invalidate")) count[1]++;
                                        }
                                        if (method.equals(ShaderUniformTransformer.QUERY_METHOD)) count[2]++;
                                        if (method.equals("<init>")) count[3]++;
                                    }
                                };
                            }
                        },
                        0);
        assertEquals(1, count[0]);
        assertEquals(2, count[1]);
        assertEquals(0, count[2], "native query must be fully delegated to the bridge");
    }

    @Test
    void wrongOwnerMissingDrawOrExtraCallsCannotPatch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderUniformTransformer.patch(fixture("other/Owner", 1, true, true)));
        assertThrows(
                IllegalArgumentException.class, () -> ShaderUniformTransformer.patch(fixture(OWNER, 0, true, true)));
        assertThrows(
                IllegalArgumentException.class, () -> ShaderUniformTransformer.patch(fixture(OWNER, 2, true, true)));
        assertThrows(
                IllegalArgumentException.class, () -> ShaderUniformTransformer.patch(fixture(OWNER, 1, false, true)));
        assertThrows(
                IllegalArgumentException.class, () -> ShaderUniformTransformer.patch(fixture(OWNER, 1, true, false)));
    }

    @Test
    void unreviewedBytesAndRedefinitionsAreNeverTransformed() throws Exception {
        var reports = new ArrayList<String>();
        var transformer = new ShaderUniformTransformer(reports::add);
        byte[] bytes = fixture(OWNER, 1, true, true);
        var source = new java.security.CodeSource(
                java.net.URI.create("file:/tmp/unreviewed.jar").toURL(), (java.security.cert.Certificate[]) null);
        var domain = new java.security.ProtectionDomain(source, null);
        // An unreachable or unreviewed artifact declines before any class bytes are rewritten.
        assertNull(transformer.transform(getClass().getClassLoader(), OWNER, null, domain, bytes));
        assertEquals(List.of("SHADER_UNIFORM_DECLINED_SHAPE_OR_SOURCE"), reports);
        assertFalse(transformer.patched());
        assertNull(transformer.transform(getClass().getClassLoader(), OWNER, Object.class, domain, bytes));
        assertEquals(List.of("SHADER_UNIFORM_DECLINED_SHAPE_OR_SOURCE", "SHADER_UNIFORM_DECLINED_DEFINITION"), reports);
    }

    @Test
    void unrelatedClassesPassThroughUntouched() {
        var transformer = new ShaderUniformTransformer(ignored -> {});
        assertNull(transformer.transform(getClass().getClassLoader(), "unrelated/Class", null, null, new byte[0]));
        assertFalse(transformer.patched());
    }

    @Test
    void everyAdmittedVersionDeclaresTheShaderOwner() {
        var targets = ShaderUniformTransformer.targetClasses();
        assertEquals(
                dev.turboism.mapping.verification.ReviewedHostArtifacts.reviewedCubismVersions().stream()
                        .sorted()
                        .toList(),
                targets.keySet().stream().sorted().toList(),
                "the hook must declare every adapted host version");
        for (var entry : targets.entrySet()) {
            assertEquals(
                    java.util.List.of(ShaderUniformTransformer.OWNER),
                    entry.getValue(),
                    entry.getKey() + " must declare the reviewed shader owner");
            assertTrue(ShaderUniformTransformer.admitsVersion(entry.getKey()));
        }
        assertFalse(ShaderUniformTransformer.admitsVersion("5.9.99"));
    }

    @Test
    void reviewedArtifactsResolveTheirDeclaredOwnerAndRejectForeignArtifacts() {
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_02));
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_3_04));
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_2_03));
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_2_00));
        assertTrue(
                ShaderUniformTransformer.admits(dev.turboism.mapping.verification.ReviewedHostArtifacts.CUBISM_5_2_02));
        assertFalse(ShaderUniformTransformer.admits(
                new dev.turboism.mapping.verification.HostArtifactDigest(1L, "0".repeat(64))));
    }

    @Test
    void patchRecordsAreLoaderAndOwnerExact() {
        var loader = new java.net.URLClassLoader(new java.net.URL[0], null);
        assertFalse(ShaderUniformTransformer.isPatched(loader, OWNER));
        ShaderUniformTransformer.markPatched(loader, OWNER);
        assertTrue(ShaderUniformTransformer.isPatched(loader, OWNER));
        assertFalse(ShaderUniformTransformer.isPatched(getClass().getClassLoader(), OWNER));
        assertFalse(ShaderUniformTransformer.isPatched(loader, "other/Owner"));
        ShaderUniformTransformer.clearPatched(loader, OWNER);
        assertFalse(ShaderUniformTransformer.isPatched(loader, OWNER));
    }

    @Test
    void composedShapeIsTheRewrittenBodyAndNullForUnpatchableBytes() {
        byte[] official = fixture(OWNER, 1, true, true);
        var composed = ShaderUniformTransformer.composedShape(official);
        assertNotNull(composed);
        assertEquals(
                dev.turboism.adapter.cubism.optimization.ReviewedMethodShape.read(
                        ShaderUniformTransformer.patch(official), OWNER, DRAW, DRAW_DESC),
                composed);
        assertNotEquals(
                dev.turboism.adapter.cubism.optimization.ReviewedMethodShape.read(official, OWNER, DRAW, DRAW_DESC),
                composed,
                "the composed shape must differ from the official one");
        assertNull(ShaderUniformTransformer.composedShape(fixture(OWNER, 2, true, true)));
        assertNull(ShaderUniformTransformer.composedShape(fixture("other/Owner", 1, true, true)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void kotlinLoopShapeReplacesEntrySetIteratorAndForEachLambdaWithPooledEntries(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path out = compileLoopStubs(dir);
        final byte[] original = kotlinLoopFixture();
        final byte[] patched = ShaderUniformTransformer.patch(original);

        int[] stats = new int[5];
        new ClassReader(patched)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int op, String owner, String called, String md, boolean itf) {
                                        if (owner.equals("java/util/HashMap") && called.equals("entrySet")) stats[0]++;
                                        if (owner.equals("java/util/HashMap") && called.equals("forEach")) stats[1]++;
                                        if (owner.equals(ShaderUniformTransformer.BRIDGE_OWNER)
                                                && called.equals("borrowMapEntries")) stats[2]++;
                                        if (owner.equals(OWNER) && called.equals("bindTexture")) stats[3]++;
                                        if (owner.equals(ShaderUniformTransformer.BRIDGE_OWNER)
                                                && called.equals("releaseMapEntries")) stats[4]++;
                                    }
                                };
                            }
                        },
                        0);
        assertEquals(0, stats[0]);
        assertEquals(0, stats[1]);
        assertEquals(2, stats[2]);
        assertEquals(1, stats[3]);
        assertEquals(4, stats[4], "normal and exception handlers for both loops must release entries");

        try (var loader =
                new java.net.URLClassLoader(
                        new java.net.URL[] {out.toUri().toURL()}, ShaderUniformTransformerTest.class.getClassLoader()) {
                    Class<?> defineShader(final byte[] bytes) {
                        return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
                    }
                }) {
            final Class<?> shaderClass = loader.defineShader(patched);
            final Class<?> ctxClass = loader.loadClass("com.live2d.graphics3d.a");
            final Class<?> matClass = loader.loadClass("com.live2d.graphics3d.material.GMaterial");
            final Class<?> texClass = loader.loadClass("com.live2d.graphics3d.texture.GTexture");
            final Class<?> matrixClass = loader.loadClass("com.live2d.graphics3d.type.GMatrix44");
            final var draw = shaderClass.getDeclaredMethod(DRAW, ctxClass, matClass, matrixClass);
            draw.setAccessible(true);

            final Object shader = shaderClass.getConstructor().newInstance();
            final Object ctx = ctxClass.getConstructor().newInstance();
            final Object mat = matClass.getConstructor().newInstance();
            final Object matrix = matrixClass.getConstructor().newInstance();
            final var uniforms = (java.util.HashMap<String, Object>)
                    matClass.getField("uniforms").get(mat);
            final var textures = (java.util.HashMap<String, Object>)
                    matClass.getField("textures").get(mat);

            // Empty maps execute cleanly without failing.
            draw.invoke(shader, ctx, mat, matrix);
            assertEquals(List.of(), getRecordedCalls(shader));

            // Populated maps iterate all entries and invoke bindTexture directly.
            uniforms.put("uAlpha", 1.0f);
            uniforms.put("uBeta", 2.0f);
            textures.put("tex0", texClass.getConstructor().newInstance());
            final java.util.Map.Entry<?, ?>[] baselineBuffer = NativeShaderUniformBridge.borrowMapEntries(uniforms);
            NativeShaderUniformBridge.releaseMapEntries(baselineBuffer);
            draw.invoke(shader, ctx, mat, matrix);
            final List<String> calls = getRecordedCalls(shader);
            assertTrue(calls.contains("uAlpha"));
            assertTrue(calls.contains("uBeta"));
            assertTrue(calls.contains("tex0:tex0"));

            // Pooled buffer must have been released cleanly and reused without allocation.
            final java.util.Map.Entry<?, ?>[] afterDrawBuffer = NativeShaderUniformBridge.borrowMapEntries(uniforms);
            assertSame(baselineBuffer, afterDrawBuffer);
            NativeShaderUniformBridge.releaseMapEntries(afterDrawBuffer);

            // Even when the loop body throws mid-iteration, catch-release must unlock the pooled buffer.
            shaderClass.getField("failNext").setBoolean(shader, true);
            assertThrows(
                    java.lang.reflect.InvocationTargetException.class, () -> draw.invoke(shader, ctx, mat, matrix));
            final java.util.Map.Entry<?, ?>[] afterThrowBuffer = NativeShaderUniformBridge.borrowMapEntries(uniforms);
            assertSame(baselineBuffer, afterThrowBuffer);
            NativeShaderUniformBridge.releaseMapEntries(afterThrowBuffer);
        }
    }

    private static java.nio.file.Path compileLoopStubs(final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path src = dir.resolve("Sources");
        final java.nio.file.Path out = dir.resolve("Out");
        java.nio.file.Files.createDirectories(src);
        java.nio.file.Files.createDirectories(out);
        final java.util.Map<String, String> files = java.util.Map.of(
                "GL3.java",
                "package com.jogamp.opengl; public interface GL3 { int glGetUniformLocation(int p, String n); }",
                "a.java",
                "package com.live2d.graphics3d; public class a { public GL3Impl gl = new GL3Impl(); }",
                "GL3Impl.java",
                """
                package com.live2d.graphics3d;
                import com.jogamp.opengl.GL3;
                public final class GL3Impl implements GL3 {
                    public Object getContext() { return null; }
                    @Override public int glGetUniformLocation(int p, String n) { return 7; }
                }
                """,
                "GMatrix44.java",
                "package com.live2d.graphics3d.type; public class GMatrix44 {}",
                "GTexture.java",
                "package com.live2d.graphics3d.texture; public class GTexture {}",
                "GMaterial.java",
                """
                package com.live2d.graphics3d.material;
                import java.util.HashMap;
                public class GMaterial {
                    public final HashMap<String, Object> uniforms = new HashMap<>();
                    public final HashMap<String, com.live2d.graphics3d.texture.GTexture> textures = new HashMap<>();
                    public HashMap<String, Object> getUniformMap$core() { return uniforms; }
                    public HashMap<String, com.live2d.graphics3d.texture.GTexture> getOptionalTextures$core() { return textures; }
                }
                """,
                "Intrinsics.java",
                "package kotlin.jvm.internal; public class Intrinsics { public static void checkNotNullExpressionValue(Object o, String s) {} }",
                "Function2.java",
                "package kotlin.jvm.functions; public interface Function2<A, B, R> { R invoke(A a, B b); }");
        final var args = new ArrayList<String>(List.of("--release", "17", "-d", out.toString()));
        for (final var e : files.entrySet()) {
            final java.nio.file.Path p = src.resolve(e.getKey());
            java.nio.file.Files.writeString(p, e.getValue());
            args.add(p.toString());
        }
        assertEquals(
                0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return out;
    }

    private static byte[] kotlinLoopFixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "log", "Ljava/util/List;", null, null)
                .visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "failNext", "Z", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "log", "Ljava/util/List;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor initExe = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                ShaderUniformTransformer.LIFECYCLE_METHODS[0],
                ShaderUniformTransformer.LIFECYCLE_DESCS[0],
                null,
                null);
        initExe.visitCode();
        initExe.visitInsn(Opcodes.RETURN);
        initExe.visitMaxs(0, 0);
        initExe.visitEnd();

        MethodVisitor setProg = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                ShaderUniformTransformer.LIFECYCLE_METHODS[1],
                ShaderUniformTransformer.LIFECYCLE_DESCS[1],
                null,
                null);
        setProg.visitCode();
        setProg.visitInsn(Opcodes.RETURN);
        setProg.visitMaxs(0, 0);
        setProg.visitEnd();

        MethodVisitor bind = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "bindTexture",
                "(Lcom/live2d/graphics3d/a;Lcom/jogamp/opengl/GL3;Lcom/live2d/graphics3d/texture/GTexture;Ljava/lang/String;)V",
                null,
                null);
        bind.visitCode();
        bind.visitVarInsn(Opcodes.ALOAD, 0);
        bind.visitFieldInsn(Opcodes.GETFIELD, OWNER, "log", "Ljava/util/List;");
        bind.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        bind.visitInsn(Opcodes.DUP);
        bind.visitLdcInsn("tex0:");
        bind.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        bind.visitVarInsn(Opcodes.ALOAD, 4);
        bind.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        bind.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        bind.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        bind.visitInsn(Opcodes.POP);
        bind.visitInsn(Opcodes.RETURN);
        bind.visitMaxs(0, 0);
        bind.visitEnd();

        // preDraw_exe with local 4 = GL3, locals 8..12 = uniform loop
        MethodVisitor draw = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, DRAW, DRAW_DESC, null, null);
        draw.visitCode();
        draw.visitVarInsn(Opcodes.ALOAD, 1);
        draw.visitFieldInsn(Opcodes.GETFIELD, "com/live2d/graphics3d/a", "gl", "Lcom/live2d/graphics3d/GL3Impl;");
        draw.visitVarInsn(Opcodes.ASTORE, 4);

        Label loopTop = new Label();
        Label loopEnd = new Label();
        Label noThrow = new Label();
        draw.visitVarInsn(Opcodes.ALOAD, 2);
        draw.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "com/live2d/graphics3d/material/GMaterial",
                "getUniformMap$core",
                "()Ljava/util/HashMap;",
                false);
        draw.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "entrySet", "()Ljava/util/Set;", false);
        draw.visitInsn(Opcodes.DUP);
        draw.visitLdcInsn("");
        draw.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlin/jvm/internal/Intrinsics",
                "checkNotNullExpressionValue",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false);
        draw.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Iterable");
        draw.visitVarInsn(Opcodes.ASTORE, 8);
        draw.visitInsn(Opcodes.ICONST_0);
        draw.visitVarInsn(Opcodes.ISTORE, 9);
        draw.visitVarInsn(Opcodes.ALOAD, 8);
        draw.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
        draw.visitVarInsn(Opcodes.ASTORE, 10);
        draw.visitLabel(loopTop);
        draw.visitVarInsn(Opcodes.ALOAD, 10);
        draw.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        draw.visitJumpInsn(Opcodes.IFEQ, loopEnd);
        draw.visitVarInsn(Opcodes.ALOAD, 10);
        draw.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        draw.visitVarInsn(Opcodes.ASTORE, 11);
        draw.visitVarInsn(Opcodes.ALOAD, 11);
        draw.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map$Entry");
        draw.visitVarInsn(Opcodes.ASTORE, 12);
        draw.visitVarInsn(Opcodes.ALOAD, 0);
        draw.visitFieldInsn(Opcodes.GETFIELD, OWNER, "failNext", "Z");
        draw.visitJumpInsn(Opcodes.IFEQ, noThrow);
        draw.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        draw.visitInsn(Opcodes.DUP);
        draw.visitLdcInsn("injected");
        draw.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        draw.visitInsn(Opcodes.ATHROW);
        draw.visitLabel(noThrow);
        draw.visitVarInsn(Opcodes.ALOAD, 0);
        draw.visitFieldInsn(Opcodes.GETFIELD, OWNER, "log", "Ljava/util/List;");
        draw.visitVarInsn(Opcodes.ALOAD, 12);
        draw.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        draw.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        draw.visitInsn(Opcodes.POP);
        draw.visitVarInsn(Opcodes.ALOAD, 4);
        draw.visitInsn(Opcodes.ICONST_1);
        draw.visitVarInsn(Opcodes.ALOAD, 12);
        draw.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        draw.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        draw.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                ShaderUniformTransformer.GL_OWNER,
                ShaderUniformTransformer.QUERY_METHOD,
                ShaderUniformTransformer.QUERY_DESC,
                true);
        draw.visitInsn(Opcodes.POP);
        draw.visitJumpInsn(Opcodes.GOTO, loopTop);
        draw.visitLabel(loopEnd);

        draw.visitVarInsn(Opcodes.ALOAD, 2);
        draw.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "com/live2d/graphics3d/material/GMaterial",
                "getOptionalTextures$core",
                "()Ljava/util/HashMap;",
                false);
        draw.visitTypeInsn(Opcodes.NEW, "com/live2d/graphics3d/shader/s");
        draw.visitInsn(Opcodes.DUP);
        draw.visitVarInsn(Opcodes.ALOAD, 0);
        draw.visitVarInsn(Opcodes.ALOAD, 1);
        draw.visitVarInsn(Opcodes.ALOAD, 4);
        draw.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "com/live2d/graphics3d/shader/s",
                "<init>",
                "(Lcom/live2d/graphics3d/shader/GShader;Lcom/live2d/graphics3d/a;Lcom/jogamp/opengl/GL3;)V",
                false);
        draw.visitTypeInsn(Opcodes.CHECKCAST, "kotlin/jvm/functions/Function2");
        draw.visitInvokeDynamicInsn(
                "accept",
                "(Lkotlin/jvm/functions/Function2;)Ljava/util/function/BiConsumer;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/Object", "bsm", "()V", false));
        draw.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "forEach", "(Ljava/util/function/BiConsumer;)V", false);

        draw.visitInsn(Opcodes.RETURN);
        draw.visitMaxs(5, 13);
        draw.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @SuppressWarnings("unchecked")
    void matrixBlocksElideMissingNormalMatrixAndInvariantCameraUploads(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path out = compileMatrixStubs(dir);
        final byte[] original = matrixShaderFixture();
        final byte[] patched = ShaderUniformTransformer.patch(original);

        final boolean previousEnabled = NativeShaderUniformBridge.enabled();
        NativeShaderUniformBridge.enabled(true);
        try (var loader =
                new java.net.URLClassLoader(
                        new java.net.URL[] {out.toUri().toURL()}, ShaderUniformTransformerTest.class.getClassLoader()) {
                    Class<?> defineShader(final byte[] bytes) {
                        return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
                    }
                }) {
            final Class<?> shaderClass = loader.defineShader(patched);
            final Class<?> ctxClass = loader.loadClass("com.live2d.graphics3d.a");
            final Class<?> matClass = loader.loadClass("com.live2d.graphics3d.material.GMaterial");
            final Class<?> matrixClass = loader.loadClass("com.live2d.graphics3d.type.GMatrix44");
            final Class<?> paramClass = loader.loadClass("com.live2d.graphics3d.shader.ShaderParameter");
            final Class<?> checkHelper = loader.loadClass("com.live2d.graphics3d.shader.a");

            final var draw = shaderClass.getDeclaredMethod(DRAW, ctxClass, matClass, matrixClass);
            final var initExe = shaderClass.getDeclaredMethod("init_exe", ctxClass);
            final var setGlProgramID = shaderClass.getDeclaredMethod("setGlProgramID", int.class);
            draw.setAccessible(true);

            final Object shader = shaderClass.getConstructor().newInstance();
            final Object ctx = ctxClass.getConstructor().newInstance();
            final Object mat = matClass.getConstructor().newInstance();
            final Object localToWorld = matrixClass.getConstructor().newInstance();
            final var ops = (List<String>) ctxClass.getField("ops").get(ctx);
            final var bindChecks =
                    (List<String>) checkHelper.getField("bindChecks").get(null);
            final var companion = matrixClass.getField("Companion").get(null);
            final var normalComputeField = companion.getClass().getField("normalComputeCount");
            bindChecks.clear();
            normalComputeField.setInt(companion, 0);

            final var uniformMap = (java.util.HashMap<String, Object>)
                    shaderClass.getField("uniformMap").get(shader);
            uniformMap.put(
                    "projectionMatrix", paramClass.getConstructor(int.class).newInstance(1));
            uniformMap.put(
                    "worldToCameraMatrix", paramClass.getConstructor(int.class).newInstance(2));
            // Omit normalMatrix (typical 2D ArtMesh shader) -> must skip computation, upload, and bind check.

            final long uploadsBefore = NativeShaderUniformBridge.matrixUploads();
            final long hitsBefore = NativeShaderUniformBridge.matrixHits();

            // Draw 1: uploads projectionMatrix & worldToCameraMatrix once and performs bind checks for those two only.
            draw.invoke(shader, ctx, mat, localToWorld);
            assertEquals(0, normalComputeField.getInt(companion));
            assertFalse(bindChecks.contains("bind normalMatrix"));
            assertEquals(List.of("upload:1:1.0", "upload:2:2.0"), ops);
            assertEquals(List.of("bind projectionMatrix", "bind worldToCameraMatrix"), bindChecks);
            assertEquals(uploadsBefore + 2L, NativeShaderUniformBridge.matrixUploads());
            assertEquals(hitsBefore, NativeShaderUniformBridge.matrixHits());

            // Draw 2: unchanged program and camera matrices must skip both glUniformMatrix4fv and bind error checks.
            ops.clear();
            bindChecks.clear();
            draw.invoke(shader, ctx, mat, localToWorld);
            assertEquals(List.of(), ops);
            assertEquals(List.of(), bindChecks);
            assertEquals(uploadsBefore + 2L, NativeShaderUniformBridge.matrixUploads());
            assertEquals(hitsBefore + 2L, NativeShaderUniformBridge.matrixHits());

            // In-place float mutation in projection matrix invalidates cached coefficients and triggers re-upload.
            final Object camera = ctxClass.getMethod("getCamera").invoke(ctx);
            final Object projMat =
                    camera.getClass().getMethod("getProjectionMatrix").invoke(camera);
            ((float[]) matrixClass.getField("data").get(projMat))[0] = 9.0f;
            draw.invoke(shader, ctx, mat, localToWorld);
            assertEquals(List.of("upload:1:9.0"), ops);
            assertEquals(List.of("bind projectionMatrix"), bindChecks);
            assertEquals(uploadsBefore + 3L, NativeShaderUniformBridge.matrixUploads());
            assertEquals(hitsBefore + 3L, NativeShaderUniformBridge.matrixHits());

            // Program switch (setGlProgramID) invalidates shader matrix cache and forces re-upload of both matrices.
            ops.clear();
            bindChecks.clear();
            setGlProgramID.invoke(shader, 11);
            draw.invoke(shader, ctx, mat, localToWorld);
            assertEquals(List.of("upload:1:9.0", "upload:2:2.0"), ops);
            assertEquals(uploadsBefore + 5L, NativeShaderUniformBridge.matrixUploads());

            // Shader re-init (init_exe) clears cached state and triggers fresh upload on the next draw.
            ops.clear();
            bindChecks.clear();
            initExe.invoke(shader, ctx);
            draw.invoke(shader, ctx, mat, localToWorld);
            assertEquals(List.of("upload:1:9.0", "upload:2:2.0"), ops);
            assertEquals(uploadsBefore + 7L, NativeShaderUniformBridge.matrixUploads());
        } finally {
            NativeShaderUniformBridge.enabled(previousEnabled);
        }
    }

    private static java.nio.file.Path compileMatrixStubs(final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path src = dir.resolve("MatrixSources");
        final java.nio.file.Path out = dir.resolve("MatrixOut");
        java.nio.file.Files.createDirectories(src);
        java.nio.file.Files.createDirectories(out);
        final java.util.Map<String, String> files = java.util.Map.ofEntries(
                java.util.Map.entry(
                        "com/jogamp/opengl/GL.java",
                        "package com.jogamp.opengl; public interface GL { int glGetError(); }"),
                java.util.Map.entry("com/jogamp/opengl/GL3.java", """
                        package com.jogamp.opengl;
                        public interface GL3 extends GL {
                            int glGetUniformLocation(int p, String n);
                            void glUniformMatrix4fv(int loc, int count, boolean transpose, float[] v, int off);
                        }
                        """),
                java.util.Map.entry("com/live2d/graphics3d/type/GMatrix44.java", """
                        package com.live2d.graphics3d.type;
                        public class GMatrix44 {
                            public static final Companion Companion = new Companion();
                            public final float[] data = new float[16];
                            public GMatrix44() {}
                            public GMatrix44(final float v0) { data[0] = v0; }
                            public float[] a() { return data; }
                            public static final class Companion {
                                public int normalComputeCount;
                                public GMatrix44 a(final GMatrix44 m) {
                                    normalComputeCount++;
                                    return m;
                                }
                            }
                        }
                        """),
                java.util.Map.entry("com/live2d/graphics3d/GCamera.java", """
                        package com.live2d.graphics3d;
                        import com.live2d.graphics3d.type.GMatrix44;
                        public class GCamera {
                            public final GMatrix44 projection = new GMatrix44(1.0f);
                            public final GMatrix44 view = new GMatrix44(2.0f);
                            public GMatrix44 getProjectionMatrix() { return projection; }
                            public GMatrix44 getWorldToCameraMatrix() { return view; }
                        }
                        """),
                java.util.Map.entry("com/jogamp/opengl/GLContext.java", """
                        package com.jogamp.opengl;
                        public final class GLContext {
                            private static GLContext current;
                            private final long handle = 100L;
                            private final boolean created = true;
                            public GLContext() { current = this; }
                            public long getHandle() { return handle; }
                            public boolean isCreated() { return created; }
                            public static GLContext getCurrent() { return current; }
                        }
                        """),
                java.util.Map.entry("com/live2d/graphics3d/a.java", """
                        package com.live2d.graphics3d;
                        import com.jogamp.opengl.GL3;
                        import com.jogamp.opengl.GLContext;
                        import java.util.ArrayList;
                        import java.util.List;
                        public class a {
                            public final GLContext glContext = new GLContext();
                            public final List<String> ops = new ArrayList<>();
                            public final GL3 gl = new GL3Impl(this);
                            public final GCamera camera = new GCamera();
                            public GCamera getCamera() { return camera; }
                            public static final class GL3Impl implements GL3 {
                                private final a ctx;
                                public GL3Impl(final a ctx) { this.ctx = ctx; }
                                public GLContext getContext() { return ctx.glContext; }
                                @Override public int glGetError() { return 0; }
                                @Override public int glGetUniformLocation(int p, String n) { return 7; }
                                @Override public void glUniformMatrix4fv(int loc, int count, boolean transpose, float[] v, int off) {
                                    ctx.ops.add("upload:" + loc + ":" + v[0]);
                                }
                            }
                        }
                        """),
                java.util.Map.entry("com/live2d/graphics3d/shader/ShaderParameter.java", """
                        package com.live2d.graphics3d.shader;
                        public class ShaderParameter {
                            private final int location;
                            public ShaderParameter(final int location) { this.location = location; }
                            public int getLocation() { return location; }
                        }
                        """),
                java.util.Map.entry("com/live2d/graphics3d/shader/a.java", """
                        package com.live2d.graphics3d.shader;
                        import com.jogamp.opengl.GL;
                        import java.util.ArrayList;
                        import java.util.List;
                        public final class a {
                            public static final List<String> bindChecks = new ArrayList<>();
                            public static boolean a(final GL gl, final String label) {
                                bindChecks.add(label);
                                return false;
                            }
                        }
                        """),
                java.util.Map.entry(
                        "com/live2d/graphics3d/texture/GTexture.java",
                        "package com.live2d.graphics3d.texture; public class GTexture {}"),
                java.util.Map.entry(
                        "com/live2d/graphics3d/material/GMaterial.java",
                        "package com.live2d.graphics3d.material; public class GMaterial {}"));
        final var args = new ArrayList<String>(List.of("--release", "17", "-d", out.toString()));
        for (final var e : files.entrySet()) {
            final java.nio.file.Path p = src.resolve(e.getKey());
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, e.getValue());
            args.add(p.toString());
        }
        assertEquals(
                0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return out;
    }

    private static byte[] matrixShaderFixture() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "uniformMap", "Ljava/util/HashMap;", null, null)
                .visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "glProgramID", "I", null, null).visitEnd();

        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "uniformMap", "Ljava/util/HashMap;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitIntInsn(Opcodes.BIPUSH, 10);
        init.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "glProgramID", "I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 1);
        init.visitEnd();

        final MethodVisitor initExe =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "init_exe", "(Lcom/live2d/graphics3d/a;)V", null, null);
        initExe.visitCode();
        initExe.visitInsn(Opcodes.RETURN);
        initExe.visitMaxs(0, 2);
        initExe.visitEnd();

        final MethodVisitor setProg = cw.visitMethod(Opcodes.ACC_PUBLIC, "setGlProgramID", "(I)V", null, null);
        setProg.visitCode();
        setProg.visitVarInsn(Opcodes.ALOAD, 0);
        setProg.visitVarInsn(Opcodes.ILOAD, 1);
        setProg.visitFieldInsn(Opcodes.PUTFIELD, OWNER, "glProgramID", "I");
        setProg.visitInsn(Opcodes.RETURN);
        setProg.visitMaxs(2, 2);
        setProg.visitEnd();

        final MethodVisitor getProg = cw.visitMethod(Opcodes.ACC_PUBLIC, "getGlProgramID", "()I", null, null);
        getProg.visitCode();
        getProg.visitVarInsn(Opcodes.ALOAD, 0);
        getProg.visitFieldInsn(Opcodes.GETFIELD, OWNER, "glProgramID", "I");
        getProg.visitInsn(Opcodes.IRETURN);
        getProg.visitMaxs(1, 1);
        getProg.visitEnd();

        final MethodVisitor getMap =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "getUniformMap", "()Ljava/util/HashMap;", null, null);
        getMap.visitCode();
        getMap.visitVarInsn(Opcodes.ALOAD, 0);
        getMap.visitFieldInsn(Opcodes.GETFIELD, OWNER, "uniformMap", "Ljava/util/HashMap;");
        getMap.visitInsn(Opcodes.ARETURN);
        getMap.visitMaxs(1, 1);
        getMap.visitEnd();

        final MethodVisitor draw = cw.visitMethod(Opcodes.ACC_PUBLIC, DRAW, DRAW_DESC, null, null);
        draw.visitCode();
        draw.visitVarInsn(Opcodes.ALOAD, 1);
        draw.visitFieldInsn(Opcodes.GETFIELD, "com/live2d/graphics3d/a", "gl", "Lcom/jogamp/opengl/GL3;");
        draw.visitVarInsn(Opcodes.ASTORE, 4);
        draw.visitVarInsn(Opcodes.ALOAD, 1);
        draw.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "com/live2d/graphics3d/a",
                "getCamera",
                "()Lcom/live2d/graphics3d/GCamera;",
                false);
        draw.visitVarInsn(Opcodes.ASTORE, 5);

        // Single uniform query required by ShaderUniformTransformer shape guard.
        draw.visitVarInsn(Opcodes.ALOAD, 4);
        draw.visitVarInsn(Opcodes.ALOAD, 0);
        draw.visitFieldInsn(Opcodes.GETFIELD, OWNER, "glProgramID", "I");
        draw.visitLdcInsn("uBase");
        draw.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                ShaderUniformTransformer.GL_OWNER,
                ShaderUniformTransformer.QUERY_METHOD,
                ShaderUniformTransformer.QUERY_DESC,
                true);
        draw.visitInsn(Opcodes.POP);

        emitSyntheticMatrixBlock(draw, "projectionMatrix", "getProjectionMatrix", "bind projectionMatrix", false);
        emitSyntheticMatrixBlock(
                draw, "worldToCameraMatrix", "getWorldToCameraMatrix", "bind worldToCameraMatrix", false);
        emitSyntheticMatrixBlock(draw, "normalMatrix", null, "bind normalMatrix", true);

        draw.visitInsn(Opcodes.RETURN);
        draw.visitMaxs(7, 8);
        draw.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitSyntheticMatrixBlock(
            final MethodVisitor draw,
            final String uniformName,
            final String cameraGetter,
            final String bindLabel,
            final boolean isNormal) {
        final Label popLabel = new Label();
        final Label tailLabel = new Label();
        draw.visitVarInsn(Opcodes.ALOAD, 0);
        draw.visitFieldInsn(Opcodes.GETFIELD, OWNER, "uniformMap", "Ljava/util/HashMap;");
        draw.visitLdcInsn(uniformName);
        draw.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        draw.visitTypeInsn(Opcodes.CHECKCAST, "com/live2d/graphics3d/shader/ShaderParameter");
        draw.visitInsn(Opcodes.DUP);
        draw.visitJumpInsn(Opcodes.IFNULL, popLabel);
        draw.visitVarInsn(Opcodes.ASTORE, 6);
        draw.visitVarInsn(Opcodes.ALOAD, 4);
        draw.visitVarInsn(Opcodes.ALOAD, 6);
        draw.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "com/live2d/graphics3d/shader/ShaderParameter", "getLocation", "()I", false);
        draw.visitInsn(Opcodes.ICONST_1);
        draw.visitInsn(Opcodes.ICONST_0);
        if (isNormal) {
            draw.visitFieldInsn(
                    Opcodes.GETSTATIC,
                    "com/live2d/graphics3d/type/GMatrix44",
                    "Companion",
                    "Lcom/live2d/graphics3d/type/GMatrix44$Companion;");
            draw.visitVarInsn(Opcodes.ALOAD, 3);
            draw.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "com/live2d/graphics3d/type/GMatrix44$Companion",
                    "a",
                    "(Lcom/live2d/graphics3d/type/GMatrix44;)Lcom/live2d/graphics3d/type/GMatrix44;",
                    false);
        } else {
            draw.visitVarInsn(Opcodes.ALOAD, 5);
            draw.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "com/live2d/graphics3d/GCamera",
                    cameraGetter,
                    "()Lcom/live2d/graphics3d/type/GMatrix44;",
                    false);
        }
        draw.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/live2d/graphics3d/type/GMatrix44", "a", "()[F", false);
        draw.visitInsn(Opcodes.ICONST_0);
        draw.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/jogamp/opengl/GL3", "glUniformMatrix4fv", "(IIZ[FI)V", true);
        draw.visitJumpInsn(Opcodes.GOTO, tailLabel);
        draw.visitLabel(popLabel);
        draw.visitInsn(Opcodes.POP);
        draw.visitLabel(tailLabel);
        draw.visitVarInsn(Opcodes.ALOAD, 4);
        draw.visitTypeInsn(Opcodes.CHECKCAST, "com/jogamp/opengl/GL");
        draw.visitLdcInsn(bindLabel);
        draw.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/live2d/graphics3d/shader/a",
                "a",
                "(Lcom/jogamp/opengl/GL;Ljava/lang/String;)Z",
                false);
        draw.visitInsn(Opcodes.POP);
    }

    @SuppressWarnings("unchecked")
    private static List<String> getRecordedCalls(final Object shader) throws Exception {
        return (List<String>) shader.getClass().getField("log").get(shader);
    }
}
