package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Regression and contract tests for {@link WarpAltMirrorActionContext} and its fail-closed
 * integration into {@link WarpAltMirrorNativeMethodTransformer}.
 */
final class WarpAltMirrorActionContextTest {

    @Test
    void configuredHostHasPinnedReadOnlyActionView() throws Exception {
        final String hostJar = System.getProperty("turboism.cubismHostJar", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!hostJar.isBlank(), "Requires a local licensed Cubism JAR");
        final String version = System.getProperty("turboism.cubismHostVersion", "5.3.04");
        final var profile = WarpAltMirrorHostProfile.forReviewedVersion(version).orElseThrow();
        try (var jar = new java.util.jar.JarFile(hostJar)) {
            final var entry = jar.getJarEntry(profile.actionDispatchOwner() + ".class");
            assertNotNull(entry);
            final byte[] bytes;
            try (var input = jar.getInputStream(entry)) {
                bytes = input.readAllBytes();
            }
            final var pins = WarpAltMirrorHostProfile.reviewedClassSha256().get(version);
            assertEquals(
                    pins.get(profile.actionDispatchOwner()),
                    java.util.HexFormat.of()
                            .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(bytes)));
            assertTrue(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(bytes), profile));
            final var transformer = new WarpAltMirrorNativeMethodTransformer(
                    profile, getClass().getClassLoader(), null, pins, ignored -> {});
            assertNotNull(transformer.transform(
                    null, getClass().getClassLoader(), profile.actionDispatchOwner(), null, null, bytes));
        }
    }

    @Test
    void exactPublicFinalGetterAcceptsAndInjectsDiagMouseAction() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final byte[] fixtureBytes = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                });

        assertTrue(
                WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(fixtureBytes), profile),
                "Exact read-only getter must pass admission");

        final List<String> diagnostics = new ArrayList<>();
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null, null, Map.of(), diagnostics::add);

        final byte[] transformed =
                transformer.transform(null, null, profile.actionDispatchOwner(), null, null, fixtureBytes);

        assertNotNull(transformed, "Action dispatch manager must transform when getter is exact read-only");
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_TRANSFORMED, transformer.outcome());
        assertTrue(transformer.targetTransformed());
        assertEquals(Set.of(profile.actionDispatchOwner()), transformer.transformedOwners());
        assertTrue(
                containsDiagMouseAction(transformed), "Transformed bytecode must inject diagMouseAction bridge call");
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("TARGET_TRANSFORMED")));
    }

    @Test
    void missingGetterRejectsWithViewAccessRejectedAndNoTransformedOwner() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final byte[] fixtureBytes = buildActionManagerFixture(profile, true, 0, null, null, null);

        assertFalse(
                WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(fixtureBytes), profile),
                "Missing getter must be rejected");

        final List<String> diagnostics = new ArrayList<>();
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null, null, Map.of(), diagnostics::add);

        final byte[] transformed =
                transformer.transform(null, null, profile.actionDispatchOwner(), null, null, fixtureBytes);

        assertNull(transformed, "Transformation must be rejected when view getter is missing");
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertFalse(transformer.targetTransformed());
        assertTrue(transformer.transformedOwners().isEmpty(), "No owners must be transformed upon rejection");
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("VIEW_ACCESS_REJECTED")));
    }

    @Test
    void voidReturnGetterRejectsWithViewAccessRejected() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final byte[] fixtureBytes = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                "()V",
                mv -> mv.visitInsn(Opcodes.RETURN));

        assertFalse(
                WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(fixtureBytes), profile),
                "Void return getter must be rejected");

        final List<String> diagnostics = new ArrayList<>();
        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null, null, Map.of(), diagnostics::add);

        final byte[] transformed =
                transformer.transform(null, null, profile.actionDispatchOwner(), null, null, fixtureBytes);

        assertNull(transformed, "Transformation must be rejected when view getter returns void");
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertFalse(transformer.targetTransformed());
        assertTrue(transformer.transformedOwners().isEmpty());
    }

    @Test
    void getterCallingMutatorBeforeFieldReadRejectsWithViewAccessRejected() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final byte[] fixtureBytes = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    // Call a mutator method before loading the field
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL, profile.actionDispatchOwner(), "sideEffect", "()V", false);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                });

        assertFalse(
                WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(fixtureBytes), profile),
                "Getter invoking mutator before field read must be rejected");

        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);
        final byte[] transformed =
                transformer.transform(null, null, profile.actionDispatchOwner(), null, null, fixtureBytes);

        assertNull(transformed);
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertFalse(transformer.targetTransformed());
        assertTrue(transformer.transformedOwners().isEmpty());
    }

    @Test
    void getterWithDisallowedBranchRejectsWithViewAccessRejected() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final byte[] fixtureBytes = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    final Label label = new Label();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitJumpInsn(Opcodes.IFNULL, label);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitLabel(label);
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
                });

        assertFalse(
                WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(fixtureBytes), profile),
                "Getter with jump/branch instruction must be rejected");

        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);
        assertNull(transformer.transform(null, null, profile.actionDispatchOwner(), null, null, fixtureBytes));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertTrue(transformer.transformedOwners().isEmpty());
    }

    @Test
    void getterWithDisallowedFieldWriteRejectsWithViewAccessRejected() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final byte[] fixtureBytes = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    // Disallowed PUTFIELD mutating state during getter execution
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitFieldInsn(
                            Opcodes.PUTFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                });

        assertFalse(
                WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(fixtureBytes), profile),
                "Getter performing PUTFIELD must be rejected");

        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);
        assertNull(transformer.transform(null, null, profile.actionDispatchOwner(), null, null, fixtureBytes));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertTrue(transformer.transformedOwners().isEmpty());
    }

    @Test
    void getterWithWrongFieldOwnerOrDescriptorRejects() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();

        // 1. Wrong field owner in GETFIELD
        final byte[] wrongOwnerBytes = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            "com/live2d/cubism/view/context/actionManager/UnrelatedOwner",
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                });
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(wrongOwnerBytes), profile));

        // 2. Wrong return descriptor on getter method (returning Object instead of CEViewContext)
        final byte[] wrongReturnBytes = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                "()Ljava/lang/Object;",
                mv -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD, profile.actionDispatchOwner(), "viewContext", "Ljava/lang/Object;");
                    mv.visitInsn(Opcodes.ARETURN);
                });
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(wrongReturnBytes), profile));

        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);
        assertNull(transformer.transform(null, null, profile.actionDispatchOwner(), null, null, wrongOwnerBytes));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertNull(transformer.transform(null, null, profile.actionDispatchOwner(), null, null, wrongReturnBytes));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertTrue(transformer.transformedOwners().isEmpty());
    }

    @Test
    void nonPublicOrNonFinalGetterRejectsWithViewAccessRejected() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final int[] rejectedModifiers = {
            Opcodes.ACC_PUBLIC, // not final
            Opcodes.ACC_FINAL, // package-private
            Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNCHRONIZED
        };

        for (final int access : rejectedModifiers) {
            final byte[] fixtureBytes = buildActionManagerFixture(
                    profile, true, access, profile.actionViewMethod(), profile.actionViewDescriptor(), mv -> {
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitFieldInsn(
                                Opcodes.GETFIELD,
                                profile.actionDispatchOwner(),
                                "viewContext",
                                "Lcom/live2d/cubism/view/context/CEViewContext;");
                        mv.visitInsn(Opcodes.ARETURN);
                    });

            assertFalse(
                    WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(fixtureBytes), profile),
                    "Getter with access flags 0x" + Integer.toHexString(access) + " must be rejected");

            final WarpAltMirrorNativeMethodTransformer transformer =
                    new WarpAltMirrorNativeMethodTransformer(profile, null);
            assertNull(transformer.transform(null, null, profile.actionDispatchOwner(), null, null, fixtureBytes));
            assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
            assertTrue(transformer.transformedOwners().isEmpty());
        }
    }

    @Test
    void extraInstructionsOrStepSequenceMismatchRejects() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();

        // 1. ALOAD 1 instead of ALOAD 0
        final byte[] aload1 = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                });
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(aload1), profile));

        // 2. Extra instruction (e.g. NOP or LDC) inserted
        final byte[] extraLdc = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    mv.visitLdcInsn("unexpected");
                    mv.visitInsn(Opcodes.POP);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                });
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(extraLdc), profile));

        // 3. Early return without field read
        final byte[] earlyReturn = buildActionManagerFixture(
                profile,
                true,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitInsn(Opcodes.ARETURN);
                });
        assertFalse(WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(earlyReturn), profile));

        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);
        assertNull(transformer.transform(null, null, profile.actionDispatchOwner(), null, null, aload1));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertNull(transformer.transform(null, null, profile.actionDispatchOwner(), null, null, extraLdc));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertNull(transformer.transform(null, null, profile.actionDispatchOwner(), null, null, earlyReturn));
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.VIEW_ACCESS_REJECTED, transformer.outcome());
        assertTrue(transformer.transformedOwners().isEmpty());
    }

    @Test
    void dispatcherMouseActionMethodMustExistSoTestsArentVacuous() {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        // Getter is completely valid, but dispatcher mouseAction method is deliberately absent
        final byte[] fixtureBytes = buildActionManagerFixture(
                profile,
                false, // no mouseAction method
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                });

        assertTrue(
                WarpAltMirrorActionContext.hasReadOnlyGetter(new ClassReader(fixtureBytes), profile),
                "Getter itself is valid");

        final WarpAltMirrorNativeMethodTransformer transformer =
                new WarpAltMirrorNativeMethodTransformer(profile, null);
        final byte[] transformed =
                transformer.transform(null, null, profile.actionDispatchOwner(), null, null, fixtureBytes);

        // Crucial: Because mouseAction is missing, outcome is TARGET_UNCHANGED (not VIEW_ACCESS_REJECTED).
        // This proves tests containing mouseAction aren't vacuous.
        assertNull(transformed, "Transformation returns null when dispatch method is not present");
        assertEquals(WarpAltMirrorNativeMethodTransformer.Outcome.TARGET_UNCHANGED, transformer.outcome());
        assertFalse(transformer.targetTransformed());
        assertTrue(transformer.transformedOwners().isEmpty());
    }

    @Test
    void actionContextReflectiveViewBehavior() throws Exception {
        final WarpAltMirrorHostProfile profile = WarpAltMirrorHostProfile.reviewed5302And5303();
        final WarpAltMirrorActionContext context = new WarpAltMirrorActionContext(profile);

        // 1. null manager returns null
        assertNull(context.view(null), "null manager must produce null view");

        // 2. Unrelated class throws NoSuchMethodException
        assertThrows(
                NoSuchMethodException.class,
                () -> context.view(new Object()),
                "Unrelated object must throw NoSuchMethodException");

        // 3. Dynamically loaded class matching manager name and loader
        final FixtureClassLoader loader = new FixtureClassLoader(getClass().getClassLoader());
        final byte[] viewBytes = buildViewContextFixture();
        final Class<?> viewClass = loader.define("com.live2d.cubism.view.context.CEViewContext", viewBytes);
        final Object expectedView = viewClass.getConstructor().newInstance();

        final byte[] managerBytes = buildActionManagerWithConstructor(
                profile,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionViewMethod(),
                profile.actionViewDescriptor(),
                mv -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            profile.actionDispatchOwner(),
                            "viewContext",
                            "Lcom/live2d/cubism/view/context/CEViewContext;");
                    mv.visitInsn(Opcodes.ARETURN);
                });

        final Class<?> managerClass =
                loader.define(profile.actionDispatchOwner().replace('/', '.'), managerBytes);
        final Object manager = managerClass.getConstructor(viewClass).newInstance(expectedView);

        final Object retrievedView = context.view(manager);
        assertSame(
                expectedView, retrievedView, "Reflective view accessor must return the exact CEViewContext instance");

        // 4. Mismatched classloader on return type throws NoSuchMethodException
        final FixtureClassLoader otherLoader = new FixtureClassLoader(loader);
        final Class<?> splitManagerClass =
                otherLoader.define(profile.actionDispatchOwner().replace('/', '.'), managerBytes);
        // Even with same name, if return type classloader doesn't match manager's loader, getter is refused
        assertThrows(
                NoSuchMethodException.class,
                () -> context.view(splitManagerClass.getConstructor(viewClass).newInstance(expectedView)));
    }

    private static boolean containsDiagMouseAction(final byte[] classBytes) {
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
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String calledName,
                                            final String calledDescriptor,
                                            final boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && owner.equals(
                                                        "dev/turboism/adapter/cubism/warpalt/NativeWarpAltMirrorBridge")
                                                && calledName.equals("diagMouseAction")
                                                && calledDescriptor.equals("(Ljava/lang/Object;Ljava/lang/Object;)V")) {
                                            found[0] = true;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return found[0];
    }

    private static byte[] buildActionManagerFixture(
            final WarpAltMirrorHostProfile profile,
            final boolean includeMouseAction,
            final int getterAccess,
            final String getterName,
            final String getterDescriptor,
            final Consumer<MethodVisitor> getterBody) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionDispatchOwner(),
                null,
                "java/lang/Object",
                null);

        writer.visitField(
                        Opcodes.ACC_PRIVATE,
                        "viewContext",
                        "Lcom/live2d/cubism/view/context/CEViewContext;",
                        null,
                        null)
                .visitEnd();

        // Mutator side-effect method available for mutator tests
        final MethodVisitor sideEffect = writer.visitMethod(Opcodes.ACC_PUBLIC, "sideEffect", "()V", null, null);
        sideEffect.visitCode();
        sideEffect.visitInsn(Opcodes.RETURN);
        sideEffect.visitMaxs(1, 1);
        sideEffect.visitEnd();

        if (includeMouseAction) {
            final MethodVisitor mouseAction = writer.visitMethod(
                    Opcodes.ACC_PUBLIC, profile.actionDispatchMethod(), profile.actionDispatchDescriptor(), null, null);
            mouseAction.visitCode();
            mouseAction.visitInsn(Opcodes.RETURN);
            mouseAction.visitMaxs(1, 2);
            mouseAction.visitEnd();
        }

        if (getterName != null && getterBody != null) {
            final MethodVisitor getter = writer.visitMethod(getterAccess, getterName, getterDescriptor, null, null);
            getter.visitCode();
            getterBody.accept(getter);
            getter.visitMaxs(4, 4);
            getter.visitEnd();
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] buildViewContextFixture() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "com/live2d/cubism/view/context/CEViewContext",
                null,
                "java/lang/Object",
                null);
        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] buildActionManagerWithConstructor(
            final WarpAltMirrorHostProfile profile,
            final int getterAccess,
            final String getterName,
            final String getterDescriptor,
            final Consumer<MethodVisitor> getterBody) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                profile.actionDispatchOwner(),
                null,
                "java/lang/Object",
                null);

        writer.visitField(
                        Opcodes.ACC_PRIVATE,
                        "viewContext",
                        "Lcom/live2d/cubism/view/context/CEViewContext;",
                        null,
                        null)
                .visitEnd();

        final MethodVisitor init = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(Lcom/live2d/cubism/view/context/CEViewContext;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(
                Opcodes.PUTFIELD,
                profile.actionDispatchOwner(),
                "viewContext",
                "Lcom/live2d/cubism/view/context/CEViewContext;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 2);
        init.visitEnd();

        if (getterName != null && getterBody != null) {
            final MethodVisitor getter = writer.visitMethod(getterAccess, getterName, getterDescriptor, null, null);
            getter.visitCode();
            getterBody.accept(getter);
            getter.visitMaxs(2, 2);
            getter.visitEnd();
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureClassLoader extends ClassLoader {
        FixtureClassLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
