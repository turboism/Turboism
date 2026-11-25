package dev.turboism.adapter.cubism.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;
import static org.objectweb.asm.Opcodes.V1_8;

import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckBridge;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTarget;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.DeferredGlErrorReporter;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.ClassFileTransformer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies multi-cycle install, execution, close rollback, and reinstall semantics for
 * {@code ConstantDynamic} slot dispatch and {@link DeferredGlErrorCheckTransformer}.
 */
final class CondySlotRetransformRollbackTest {

    private static final String TEST_SLOT_PROPERTY = "turboism.test.condy-rollback-slot";
    private static final String ERROR_HELPER_OWNER = DeferredGlErrorCheckTarget.ERROR_OWNER;
    private static final String FRAME_RENDERER_OWNER = DeferredGlErrorCheckTarget.FRAME_OWNER;
    private static final String GL_INTERFACE = "com/jogamp/opengl/GL";
    private static final String GL_IMPL = "com/jogamp/opengl/FakeGL";
    private static final String GL_CONTEXT_CLASS = "com/jogamp/opengl/GLContext";
    private static final String GL_EXCEPTION_CLASS = "com/jogamp/opengl/GLException";
    private static final String GL_LOGGER_CLASS = "com/live2d/util/log/a";
    private static final String FRAME_WRAPPER_CLASS = "com/live2d/graphics3d/a";
    private static final int CYCLES = 3;
    private static final int IN_FRAME_FAST_CALLS = 16;

    @AfterEach
    void cleanupProperties() {
        final Properties properties = System.getProperties();
        properties.remove(TEST_SLOT_PROPERTY);
        properties.remove(UniformLocationHookBridge.BEGIN_PROPERTY);
        properties.remove(UniformLocationHookBridge.END_PROPERTY);
        properties.remove(UniformLocationHookBridge.ERROR_PROPERTY);
        properties.remove(UniformLocationHookBridge.SYNTHETIC_ERROR_PROPERTY);
        properties.remove(UniformLocationHookBridge.INVALIDATE_PROPERTY);
        properties.remove(UniformLocationHookBridge.MUTATION_BEGIN_PROPERTY);
        properties.remove(UniformLocationHookBridge.MUTATION_END_PROPERTY);
        properties.remove(UniformLocationHookBridge.DEFER_QUERY_PROPERTY);
        properties.remove(UniformLocationHookBridge.DEFER_REPORT_PROPERTY);
        properties.remove(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY);
        properties.remove(UniformLocationHookBridge.STATS_PROPERTY);
        properties.remove(UniformLocationHookBridge.ENABLE_PROPERTY);
        properties.remove(DeferredGlErrorCheckTransformer.ENABLE_PROPERTY);
    }

    @Test
    void deferredErrorCheckCondyFastPathResetsCleanlyAcrossMultiCycleRetransformRollback() throws Exception {
        final Path artifact = Files.createTempFile("deferred-condy-rollback", ".jar");
        final Map<String, byte[]> pristine = buildDeferredHostClasses();
        final byte[] pristineError = pristine.get(ERROR_HELPER_OWNER);
        final byte[] pristineFrame = pristine.get(FRAME_RENDERER_OWNER);
        final DeferredGlErrorCheckTarget target = DeferredGlErrorCheckTarget.all().stream()
                .filter(t -> ERROR_HELPER_OWNER.equals(t.errorOwner()))
                .findFirst()
                .orElseThrow();

        final CycleClassLoader baseLoader = new CycleClassLoader(artifact, pristine);
        assertEquals(1, executeSingleFrameAndReturnErrorCalls(baseLoader, pristineError, pristineFrame));

        for (int cycle = 0; cycle < CYCLES; cycle++) {
            final CycleClassLoader cycleLoader = new CycleClassLoader(artifact, pristine);
            final Class<?> glType = cycleLoader.loadClass(GL_INTERFACE.replace('/', '.'));
            final Class<?> glImplType = cycleLoader.loadClass(GL_IMPL.replace('/', '.'));
            final Class<?> contextType = cycleLoader.loadClass(GL_CONTEXT_CLASS.replace('/', '.'));
            final Class<?> frameType = cycleLoader.loadClass(FRAME_WRAPPER_CLASS.replace('/', '.'));
            final Class<?> loggerType = cycleLoader.loadClass(GL_LOGGER_CLASS.replace('/', '.'));
            final Class<?> exceptionType = cycleLoader.loadClass(GL_EXCEPTION_CLASS.replace('/', '.'));

            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            final DeferredGlErrorReporter.FrameContextAccessors frameAccessors =
                    new DeferredGlErrorReporter.FrameContextAccessors(
                            lookup.findVirtual(frameType, "a", MethodType.methodType(glImplType))
                                    .asType(MethodType.methodType(Object.class, Object.class)),
                            lookup.findVirtual(glType, "getContext", MethodType.methodType(contextType))
                                    .asType(MethodType.methodType(Object.class, Object.class)),
                            lookup.findStatic(contextType, "getCurrent", MethodType.methodType(contextType))
                                    .asType(MethodType.methodType(Object.class)),
                            lookup.findVirtual(contextType, "isShared", MethodType.methodType(boolean.class))
                                    .asType(MethodType.methodType(boolean.class, Object.class)),
                            lookup.findVirtual(contextType, "isCreated", MethodType.methodType(boolean.class))
                                    .asType(MethodType.methodType(boolean.class, Object.class)));
            final Object logger = loggerType.getField("a").get(null);
            final UniformLocationHookBridge.DeferredAccessors deferredAccessors =
                    new UniformLocationHookBridge.DeferredAccessors(
                            lookup.findVirtual(glType, "glGetError", MethodType.methodType(int.class))
                                    .asType(MethodType.methodType(int.class, Object.class)),
                            logger,
                            lookup.findStatic(
                                            loggerType,
                                            "b",
                                            MethodType.methodType(
                                                    void.class,
                                                    loggerType,
                                                    Object.class,
                                                    boolean.class,
                                                    String.class,
                                                    int.class,
                                                    Object.class))
                                    .asType(MethodType.methodType(
                                            void.class,
                                            Object.class,
                                            Object.class,
                                            boolean.class,
                                            String.class,
                                            int.class,
                                            Object.class)),
                            lookup.findConstructor(exceptionType, MethodType.methodType(void.class, String.class)));

            final DeferredGlErrorCheckBridge bridge = newBridgeInstance(frameAccessors, deferredAccessors, glImplType);
            bridge.install();
            final Object publishedActive = System.getProperties().get(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY);
            assertTrue(publishedActive instanceof AtomicBoolean);
            final AtomicBoolean deferredActive = (AtomicBoolean) publishedActive;
            assertFalse(deferredActive.get(), "deferredActive starts false outside an active render3d frame");

            final DeferredGlErrorCheckTransformer transformer = new DeferredGlErrorCheckTransformer(
                    cycleLoader, artifact, pristineError, pristineFrame, target, true);
            final byte[] wovenError = transformer.transform(
                    null, cycleLoader, ERROR_HELPER_OWNER, null, cycleLoader.domain, pristineError);
            final byte[] wovenFrame = transformer.transform(
                    null, cycleLoader, FRAME_RENDERER_OWNER, null, cycleLoader.domain, pristineFrame);
            assertNotNull(wovenError, String.valueOf(transformer.failure()));
            assertNotNull(wovenFrame, String.valueOf(transformer.failure()));
            assertTrue(containsConstantDynamic(wovenError), "V17 class must emit ConstantDynamic fast path");

            final Class<?> errorHelperCls = cycleLoader.define(ERROR_HELPER_OWNER, wovenError);
            final Class<?> frameRendererCls = cycleLoader.define(FRAME_RENDERER_OWNER, wovenFrame);
            final Object errorHelper = errorHelperCls.getConstructor().newInstance();
            final Object frameRenderer = frameRendererCls.getConstructor().newInstance();
            final Object ctx = contextType.getConstructor().newInstance();
            contextType.getMethod("makeCurrent").invoke(ctx);
            final Object gl = glImplType.getConstructor(contextType).newInstance(ctx);
            final Object frame = frameType.getConstructor(glImplType).newInstance(gl);
            final Method errorCheck = errorHelperCls.getMethod("a", glType, String.class, boolean.class);
            final Method renderFrame = frameRendererCls.getMethod(DeferredGlErrorCheckTarget.FRAME_METHOD, frameType);
            frameRendererCls.getField("errorHelper").set(null, errorHelper);
            frameRendererCls.getField("glArg").set(null, gl);
            frameRendererCls.getField("callsPerFrame").setInt(null, IN_FRAME_FAST_CALLS);

            assertEquals(0, (int) errorCheck.invoke(errorHelper, gl, "pre-frame", true));
            assertEquals(1, glImplType.getField("queries").getInt(gl));
            assertEquals(1L, bridge.statistics().get("deferredFallbacks"));

            renderFrame.invoke(frameRenderer, frame);
            assertEquals(
                    2,
                    glImplType.getField("queries").getInt(gl),
                    "cycle " + cycle + ": frame runs 1 boundary glGetError (plus 1 pre-frame)");
            assertEquals(
                    1L,
                    bridge.statistics().get("deferredChecks"),
                    "cycle " + cycle
                            + ": only call #1 invokes deferQuery; remaining calls use AtomicBoolean fast path");
            assertEquals(1L, bridge.statistics().get("deferredFrames"));
            assertFalse(deferredActive.get(), "deferredActive resets false at end of frame");

            bridge.close();
            assertFalse(deferredActive.get(), "close() must clear deferredActive");
            assertNull(System.getProperties().get(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY));
            assertEquals(0, (int) errorCheck.invoke(errorHelper, gl, "post-close", true));
            assertEquals(3, glImplType.getField("queries").getInt(gl));

            final CycleClassLoader rollbackLoader = new CycleClassLoader(artifact, pristine);
            assertEquals(1, executeSingleFrameAndReturnErrorCalls(rollbackLoader, pristineError, pristineFrame));
        }
    }

    @Test
    void multiCycleCondySlotPublishRollbackAndReinstallNeverCachesStaleNull() throws Exception {
        final String owner = "dev/turboism/adapter/cubism/optimization/CondyRollbackTarget";
        final byte[] pristine = buildCondyTargetClass(owner, V17);
        final RetransformHarness harness = new RetransformHarness(owner, pristine);

        assertEquals("native", harness.invokeCurrent());

        final List<String> cycleResults = new ArrayList<>();
        for (int cycle = 1; cycle <= CYCLES; cycle++) {
            final int expectedCycle = cycle;
            final Supplier<String> callback = () -> "cycle-" + expectedCycle;
            System.getProperties().put(TEST_SLOT_PROPERTY, callback);

            final ClassFileTransformer transformer = new ClassFileTransformer() {
                @Override
                public byte[] transform(
                        final Module module,
                        final ClassLoader loader,
                        final String className,
                        final Class<?> classBeingRedefined,
                        final ProtectionDomain domain,
                        final byte[] bytes) {
                    if (!owner.equals(className)) {
                        return null;
                    }
                    final ClassReader reader = HostClassWriters.newClassReader(bytes);
                    final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
                    reader.accept(
                            new ClassVisitor(Opcodes.ASM9, writer) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String descriptor,
                                        final String signature,
                                        final String[] exceptions) {
                                    final MethodVisitor delegate =
                                            super.visitMethod(access, name, descriptor, signature, exceptions);
                                    if (!"probe".equals(name)) {
                                        return delegate;
                                    }
                                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                                        @Override
                                        public void visitCode() {
                                            super.visitCode();
                                            final Label miss = new Label();
                                            SlotBridgeEmitter.emitGetSlot(this, TEST_SLOT_PROPERTY);
                                            super.visitInsn(DUP);
                                            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Supplier");
                                            super.visitJumpInsn(Opcodes.IFEQ, miss);
                                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Supplier");
                                            super.visitMethodInsn(
                                                    INVOKEINTERFACE,
                                                    "java/util/function/Supplier",
                                                    "get",
                                                    "()Ljava/lang/Object;",
                                                    true);
                                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                                            super.visitInsn(ARETURN);
                                            super.visitLabel(miss);
                                            super.visitInsn(Opcodes.POP);
                                        }
                                    };
                                }
                            },
                            ClassReader.EXPAND_FRAMES);
                    return writer.toByteArray();
                }
            };

            harness.installAndRetransform(transformer);
            assertTrue(containsConstantDynamic(harness.currentBytes()));
            assertEquals("cycle-" + cycle, harness.invokeCurrent());
            System.getProperties().remove(TEST_SLOT_PROPERTY);
            final String repeatResult = harness.invokeCurrent();
            assertEquals("cycle-" + cycle, repeatResult);
            cycleResults.add(repeatResult);

            harness.removeAndRollback(transformer);
            assertArrayEquals(pristine, harness.currentBytes());
            assertEquals("native", harness.invokeCurrent());
        }
        assertEquals(List.of("cycle-1", "cycle-2", "cycle-3"), cycleResults);

        final byte[] v8 = buildCondyTargetClass(owner, V1_8);
        final ClassReader v8Reader = new ClassReader(v8);
        final ClassWriter v8Writer = HostClassWriters.newFrameWriter(v8Reader, null);
        v8Reader.accept(
                new ClassVisitor(Opcodes.ASM9, v8Writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor delegate =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!"probe".equals(name)) {
                            return delegate;
                        }
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                final Label miss = new Label();
                                SlotBridgeEmitter.emitGetSlot(this, TEST_SLOT_PROPERTY);
                                super.visitInsn(DUP);
                                super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Supplier");
                                super.visitJumpInsn(Opcodes.IFEQ, miss);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Supplier");
                                super.visitMethodInsn(
                                        INVOKEINTERFACE,
                                        "java/util/function/Supplier",
                                        "get",
                                        "()Ljava/lang/Object;",
                                        true);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                                super.visitInsn(ARETURN);
                                super.visitLabel(miss);
                                super.visitInsn(Opcodes.POP);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        final byte[] v8Transformed = v8Writer.toByteArray();
        assertFalse(containsConstantDynamic(v8Transformed), "major version 52 (<55) must not emit ConstantDynamic");
        final Class<?> v8Type = new ByteArrayLoader().define(owner, v8Transformed);
        assertEquals("native", v8Type.getMethod("probe").invoke(null));
        System.getProperties().put(TEST_SLOT_PROPERTY, (Supplier<String>) () -> "v8-updated");
        assertEquals("v8-updated", v8Type.getMethod("probe").invoke(null));
    }

    @Test
    void realReviewedHostArtifactInstallRollbackReinstallCyclesComposeCleanly() throws Exception {
        final String configuredJar = System.getProperty("turboism.test.cubism5305Jar", "");
        final Path artifact = !configuredJar.isBlank()
                ? Path.of(configuredJar)
                : Path.of(
                        System.getProperty("user.home"),
                        ".local",
                        "share",
                        "cubism-reference",
                        "5.3.05",
                        "proton",
                        "pfx",
                        "drive_c",
                        "Program Files",
                        "Live2D Cubism 5.3",
                        "app",
                        "lib",
                        "Live2D_Cubism.jar");
        final Path libDir = artifact.getParent();
        assumeTrue(Files.isRegularFile(artifact) && libDir != null, "Cubism 5.3.05 reference lib directory absent");

        final List<URL> urls = new ArrayList<>();
        try (var jars = Files.walk(libDir, 2)) {
            for (final Path jar :
                    jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                urls.add(jar.toUri().toURL());
            }
        }
        try (URLClassLoader loader =
                new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final DeferredGlErrorCheckTarget target =
                    DeferredGlErrorCheckTarget.of(digest).orElseThrow();
            final byte[] rawError = readJarBytes(artifact, target.errorOwner());
            final byte[] rawFrame = readJarBytes(artifact, target.frameOwner());
            final ProtectionDomain domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null),
                    new Permissions());

            for (int cycle = 0; cycle < 2; cycle++) {
                try (UniformLocationHookBridge bridge = new UniformLocationHookBridge(loader)) {
                    bridge.install();
                    final Object published =
                            System.getProperties().get(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY);
                    assertTrue(published instanceof AtomicBoolean);
                    assertFalse(((AtomicBoolean) published).get());

                    final UniformLocationLifecycleTransformer errorLifecycle = new UniformLocationLifecycleTransformer(
                            loader, artifact, rawError, UniformLocationLifecycleTransformer.Role.ERROR, digest);
                    final UniformLocationLifecycleTransformer frameLifecycle = new UniformLocationLifecycleTransformer(
                            loader, artifact, rawFrame, UniformLocationLifecycleTransformer.Role.FRAME, digest);
                    final byte[] lifecycleError =
                            errorLifecycle.transform(null, loader, target.errorOwner(), null, domain, rawError);
                    final byte[] lifecycleFrame =
                            frameLifecycle.transform(null, loader, target.frameOwner(), null, domain, rawFrame);
                    assertNotNull(lifecycleError, String.valueOf(errorLifecycle.failure()));
                    assertNotNull(lifecycleFrame, String.valueOf(frameLifecycle.failure()));

                    final DeferredGlErrorCheckTransformer deferred =
                            new DeferredGlErrorCheckTransformer(loader, artifact, rawError, rawFrame, target, false);
                    deferred.acceptComposedShape(
                            target.errorOwner(),
                            ReviewedMethodShape.read(
                                    lifecycleError,
                                    target.errorOwner(),
                                    DeferredGlErrorCheckTarget.ERROR_METHOD,
                                    DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR));
                    deferred.acceptComposedShape(
                            target.frameOwner(),
                            ReviewedMethodShape.read(
                                    lifecycleFrame,
                                    target.frameOwner(),
                                    DeferredGlErrorCheckTarget.FRAME_METHOD,
                                    DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR));
                    final byte[] deferredError =
                            deferred.transform(null, loader, target.errorOwner(), null, domain, lifecycleError);
                    final byte[] deferredFrame =
                            deferred.transform(null, loader, target.frameOwner(), null, domain, lifecycleFrame);
                    assertNotNull(deferredError, String.valueOf(deferred.failure()));
                    assertNotNull(deferredFrame, String.valueOf(deferred.failure()));
                    assertTrue(containsConstantDynamic(deferredError));
                    assertTrue(containsConstantDynamic(deferredFrame));
                }
                assertNull(System.getProperties().get(UniformLocationHookBridge.DEFER_ACTIVE_PROPERTY));
            }
        }
    }

    private static DeferredGlErrorCheckBridge newBridgeInstance(
            final DeferredGlErrorReporter.FrameContextAccessors frameAccessors,
            final UniformLocationHookBridge.DeferredAccessors deferredAccessors,
            final Class<?> supportedGlType)
            throws Exception {
        final var ctor = DeferredGlErrorCheckBridge.class.getDeclaredConstructor(
                DeferredGlErrorReporter.FrameContextAccessors.class,
                UniformLocationHookBridge.DeferredAccessors.class,
                Class.class);
        ctor.setAccessible(true);
        return ctor.newInstance(frameAccessors, deferredAccessors, supportedGlType);
    }

    private static int executeSingleFrameAndReturnErrorCalls(
            final CycleClassLoader loader, final byte[] errorBytes, final byte[] frameBytes) throws Exception {
        final Class<?> glImplType = loader.loadClass(GL_IMPL.replace('/', '.'));
        final Class<?> contextType = loader.loadClass(GL_CONTEXT_CLASS.replace('/', '.'));
        final Class<?> frameType = loader.loadClass(FRAME_WRAPPER_CLASS.replace('/', '.'));
        final Class<?> errorCls = loader.define(ERROR_HELPER_OWNER, errorBytes);
        final Class<?> frameCls = loader.define(FRAME_RENDERER_OWNER, frameBytes);
        final Object ctx = contextType.getConstructor().newInstance();
        contextType.getMethod("makeCurrent").invoke(ctx);
        final Object gl = glImplType.getConstructor(contextType).newInstance(ctx);
        final Object frame = frameType.getConstructor(glImplType).newInstance(gl);
        final Object errorHelper = errorCls.getConstructor().newInstance();
        final Object frameRenderer = frameCls.getConstructor().newInstance();
        frameCls.getField("errorHelper").set(null, errorHelper);
        frameCls.getField("glArg").set(null, gl);
        frameCls.getField("callsPerFrame").setInt(null, 1);
        frameCls.getMethod(DeferredGlErrorCheckTarget.FRAME_METHOD, frameType).invoke(frameRenderer, frame);
        return glImplType.getField("queries").getInt(gl);
    }

    private static byte[] readJarBytes(final Path artifact, final String owner) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final JarEntry entry = jar.getJarEntry(owner + ".class");
            assertNotNull(entry, owner);
            try (var in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static boolean containsConstantDynamic(final byte[] classBytes) {
        final boolean[] found = {false};
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        if (value instanceof ConstantDynamic) {
                                            found[0] = true;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static byte[] buildCondyTargetClass(final String owner, final int version) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(version, ACC_PUBLIC | ACC_FINAL, owner, null, "java/lang/Object", null);
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor probe =
                cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "probe", "()Ljava/lang/String;", null, null);
        probe.visitCode();
        probe.visitLdcInsn("native");
        probe.visitInsn(ARETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Map<String, byte[]> buildDeferredHostClasses() {
        final Map<String, byte[]> map = new LinkedHashMap<>();
        map.put(GL_CONTEXT_CLASS, buildContextClass());
        map.put(GL_INTERFACE, buildGlInterface());
        map.put(GL_IMPL, buildGlImplClass());
        map.put(GL_EXCEPTION_CLASS, buildExceptionClass());
        map.put(GL_LOGGER_CLASS, buildLoggerClass());
        map.put(FRAME_WRAPPER_CLASS, buildFrameWrapperClass());
        map.put(ERROR_HELPER_OWNER, buildErrorHelperClass());
        map.put(FRAME_RENDERER_OWNER, buildFrameRendererClass());
        return map;
    }

    private static byte[] buildContextClass() {
        final String desc = "L" + GL_CONTEXT_CLASS + ";";
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, GL_CONTEXT_CLASS, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "current", desc, null, null).visitEnd();
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (final String[] m : new String[][] {{"isCreated", "1"}, {"isShared", "0"}}) {
            final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, m[0], "()Z", null, null);
            mv.visitCode();
            mv.visitInsn("1".equals(m[1]) ? ICONST_1 : ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        final MethodVisitor mk = cw.visitMethod(ACC_PUBLIC, "makeCurrent", "()V", null, null);
        mk.visitCode();
        mk.visitVarInsn(ALOAD, 0);
        mk.visitFieldInsn(PUTSTATIC, GL_CONTEXT_CLASS, "current", desc);
        mk.visitInsn(RETURN);
        mk.visitMaxs(0, 0);
        mk.visitEnd();
        final MethodVisitor cur = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getCurrent", "()" + desc, null, null);
        cur.visitCode();
        cur.visitFieldInsn(GETSTATIC, GL_CONTEXT_CLASS, "current", desc);
        cur.visitInsn(ARETURN);
        cur.visitMaxs(0, 0);
        cur.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildGlInterface() {
        final String ctxDesc = "L" + GL_CONTEXT_CLASS + ";";
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, GL_INTERFACE, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getContext", "()" + ctxDesc, null, null)
                .visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "glGetError", "()I", null, null)
                .visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildGlImplClass() {
        final String ctxDesc = "L" + GL_CONTEXT_CLASS + ";";
        final ClassWriter concrete = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        concrete.visit(V17, ACC_PUBLIC, GL_IMPL, null, "java/lang/Object", new String[] {GL_INTERFACE});
        concrete.visitField(ACC_PUBLIC | ACC_FINAL, "context", ctxDesc, null, null)
                .visitEnd();
        concrete.visitField(ACC_PUBLIC, "queries", "I", null, null).visitEnd();
        final MethodVisitor init = concrete.visitMethod(ACC_PUBLIC, "<init>", "(" + ctxDesc + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, GL_IMPL, "context", ctxDesc);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor getCtx = concrete.visitMethod(ACC_PUBLIC, "getContext", "()" + ctxDesc, null, null);
        getCtx.visitCode();
        getCtx.visitVarInsn(ALOAD, 0);
        getCtx.visitFieldInsn(Opcodes.GETFIELD, GL_IMPL, "context", ctxDesc);
        getCtx.visitInsn(ARETURN);
        getCtx.visitMaxs(0, 0);
        getCtx.visitEnd();
        final MethodVisitor glErr = concrete.visitMethod(ACC_PUBLIC, "glGetError", "()I", null, null);
        glErr.visitCode();
        glErr.visitVarInsn(ALOAD, 0);
        glErr.visitInsn(DUP);
        glErr.visitFieldInsn(Opcodes.GETFIELD, GL_IMPL, "queries", "I");
        glErr.visitInsn(ICONST_1);
        glErr.visitInsn(Opcodes.IADD);
        glErr.visitFieldInsn(Opcodes.PUTFIELD, GL_IMPL, "queries", "I");
        glErr.visitInsn(ICONST_0);
        glErr.visitInsn(IRETURN);
        glErr.visitMaxs(0, 0);
        glErr.visitEnd();
        concrete.visitEnd();
        return concrete.toByteArray();
    }

    private static byte[] buildExceptionClass() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, GL_EXCEPTION_CLASS, null, "java/lang/RuntimeException", null);
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildLoggerClass() {
        final String desc = "L" + GL_LOGGER_CLASS + ";";
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, GL_LOGGER_CLASS, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "a", desc, null, null)
                .visitEnd();
        final MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, GL_LOGGER_CLASS);
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, GL_LOGGER_CLASS, "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, GL_LOGGER_CLASS, "a", desc);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor log = cw.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "b",
                "(" + desc + "Ljava/lang/Object;ZLjava/lang/String;ILjava/lang/Object;)V",
                null,
                null);
        log.visitCode();
        log.visitInsn(RETURN);
        log.visitMaxs(0, 0);
        log.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildFrameWrapperClass() {
        final String glDesc = "L" + GL_IMPL + ";";
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, FRAME_WRAPPER_CLASS, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "gl", glDesc, null, null).visitEnd();
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + glDesc + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, FRAME_WRAPPER_CLASS, "gl", glDesc);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor getter = cw.visitMethod(ACC_PUBLIC, "a", "()" + glDesc, null, null);
        getter.visitCode();
        getter.visitVarInsn(ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, FRAME_WRAPPER_CLASS, "gl", glDesc);
        getter.visitInsn(ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildErrorHelperClass() {
        final String glDesc = "L" + GL_INTERFACE + ";";
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, ERROR_HELPER_OWNER, null, "java/lang/Object", null);
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor check = cw.visitMethod(ACC_PUBLIC, "a", "(" + glDesc + "Ljava/lang/String;Z)I", null, null);
        check.visitCode();
        check.visitVarInsn(ALOAD, 1);
        check.visitMethodInsn(INVOKEINTERFACE, GL_INTERFACE, "glGetError", "()I", true);
        check.visitInsn(IRETURN);
        check.visitMaxs(0, 0);
        check.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildFrameRendererClass() {
        final String helperDesc = "L" + ERROR_HELPER_OWNER + ";";
        final String glDesc = "L" + GL_INTERFACE + ";";
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, FRAME_RENDERER_OWNER, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "errorHelper", helperDesc, null, null)
                .visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "glArg", glDesc, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "callsPerFrame", "I", null, null).visitEnd();
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final MethodVisitor render = cw.visitMethod(
                ACC_PUBLIC,
                DeferredGlErrorCheckTarget.FRAME_METHOD,
                DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR,
                null,
                null);
        render.visitCode();
        final Label loop = new Label(), done = new Label();
        render.visitInsn(ICONST_0);
        render.visitVarInsn(Opcodes.ISTORE, 2);
        render.visitLabel(loop);
        render.visitVarInsn(ILOAD, 2);
        render.visitFieldInsn(GETSTATIC, FRAME_RENDERER_OWNER, "callsPerFrame", "I");
        render.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        render.visitFieldInsn(GETSTATIC, FRAME_RENDERER_OWNER, "errorHelper", helperDesc);
        render.visitFieldInsn(GETSTATIC, FRAME_RENDERER_OWNER, "glArg", glDesc);
        render.visitLdcInsn("draw");
        render.visitInsn(ICONST_1);
        render.visitMethodInsn(INVOKEVIRTUAL, ERROR_HELPER_OWNER, "a", "(" + glDesc + "Ljava/lang/String;Z)I", false);
        render.visitInsn(Opcodes.POP);
        render.visitIincInsn(2, 1);
        render.visitJumpInsn(Opcodes.GOTO, loop);
        render.visitLabel(done);
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class CycleClassLoader extends ClassLoader {
        private final ProtectionDomain domain;
        private final Map<String, byte[]> fixtures;

        CycleClassLoader(final Path artifact, final Map<String, byte[]> fixtures) throws Exception {
            super(ClassLoader.getPlatformClassLoader());
            this.domain = new ProtectionDomain(
                    new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null),
                    new Permissions());
            this.fixtures = fixtures;
        }

        Class<?> define(final String owner, final byte[] bytes) {
            return defineClass(owner.replace('/', '.'), bytes, 0, bytes.length, domain);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] raw = fixtures.get(name.replace('.', '/'));
            if (raw == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, raw, 0, raw.length, domain);
        }
    }

    private static final class ByteArrayLoader extends ClassLoader {
        ByteArrayLoader() {
            super(ClassLoader.getPlatformClassLoader());
        }

        Class<?> define(final String owner, final byte[] bytes) {
            return defineClass(owner.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static final class RetransformHarness {
        private final String targetInternalName;
        private final byte[] originalBytes;
        private byte[] currentBytes;
        private Class<?> currentClass;

        RetransformHarness(final String targetInternalName, final byte[] originalBytes) {
            this.targetInternalName = targetInternalName;
            this.originalBytes = originalBytes;
            this.currentBytes = originalBytes;
            this.currentClass = new ByteArrayLoader().define(targetInternalName, originalBytes);
        }

        void installAndRetransform(final ClassFileTransformer transformer) throws Exception {
            final byte[] transformed = transformer.transform(null, null, targetInternalName, null, null, originalBytes);
            this.currentBytes = transformed != null ? transformed : originalBytes;
            this.currentClass = new ByteArrayLoader().define(targetInternalName, currentBytes);
        }

        void removeAndRollback(final ClassFileTransformer unused) {
            this.currentBytes = originalBytes;
            this.currentClass = new ByteArrayLoader().define(targetInternalName, originalBytes);
        }

        byte[] currentBytes() {
            return currentBytes;
        }

        String invokeCurrent() throws Exception {
            return (String) currentClass.getMethod("probe").invoke(null);
        }
    }
}
