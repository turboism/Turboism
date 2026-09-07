package dev.turboism.mapping.verification;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.overlay.BoundingBoxOverlayButtonHookInstaller;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable coverage for {@link BoundingBoxOverlayButtonHookInstaller} close restoration,
 * including an owner that loads only after installation: close must fresh-discover it and
 * retransform it back to the exact original bytes after the transformer is removed.
 */
class BoundingBoxOverlayButtonHookInstallerTest {

    private static final String OWNER = "fixture/BoundingBoxDrawEntity";
    private static final String OWNER_DOT = "fixture.BoundingBoxDrawEntity";
    private static final String UPDATE_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String HELPER_NAME = "update$setupButton";
    private static final String HELPER_DESCRIPTOR =
        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
            + "Ljava/lang/Object;Ljava/lang/Object;)V";

    @Test
    void initiallyUnloadedOwnerIsTransformedOnLoadAndRestoredToExactOriginalBytesOnClose()
        throws Exception {
        final byte[] original = fixtureBytes();
        final FakeInstrumentation fake = new FakeInstrumentation();
        final HostFixtureLoader loader = new HostFixtureLoader(fake, original);
        final VerifiedMemberResolver resolver = resolver(loader);

        final Registration registration =
            new BoundingBoxOverlayButtonHookInstaller(fake.instrumentation()).install(resolver);
        assertEquals(List.of("add"), fake.calls, "owner is initially absent");

        // The owner loads after installation and the initial-load path transforms it.
        loader.allowOwner(true);
        final Class<?> owner = loader.loadClass(OWNER_DOT);
        assertFalse(
            Arrays.equals(original, fake.currentBytes.get(owner)),
            "initial-load path must transform the late-loaded owner"
        );

        // Close fresh-discovers the owner and retransforms it to the exact original bytes.
        registration.close();
        assertTrue(
            Arrays.equals(original, fake.currentBytes.get(owner)),
            "close must restore the exact original bytes of the late-loaded owner"
        );
        assertEquals(List.of("add", "remove", "retransform"), fake.calls);
    }

    @Test
    void loadedAtInstallOwnerIsRetransformedAtInstallAndRestoredOnClose() throws Exception {
        final byte[] original = fixtureBytes();
        final FakeInstrumentation fake = new FakeInstrumentation();
        final HostFixtureLoader loader = new HostFixtureLoader(fake, original);
        loader.allowOwner(true);
        final VerifiedMemberResolver resolver = resolver(loader);

        final Registration registration =
            new BoundingBoxOverlayButtonHookInstaller(fake.instrumentation()).install(resolver);
        assertEquals(List.of("add", "retransform"), fake.calls, "install retransforms the loaded owner");

        final Class<?> owner = loader.loadClass(OWNER_DOT);
        registration.close();
        assertTrue(
            Arrays.equals(original, fake.currentBytes.get(owner)),
            "close must restore the exact original bytes"
        );
        assertEquals(List.of("add", "retransform", "remove", "retransform"), fake.calls);
    }

    @Test
    void unmodifiableTransformedOwnerIsAStructuredCleanupFailure() throws Exception {
        final byte[] original = fixtureBytes();
        final FakeInstrumentation fake = new FakeInstrumentation();
        final HostFixtureLoader loader = new HostFixtureLoader(fake, original);
        final VerifiedMemberResolver resolver = resolver(loader);
        final Registration registration =
            new BoundingBoxOverlayButtonHookInstaller(fake.instrumentation()).install(resolver);

        loader.allowOwner(true);
        loader.loadClass(OWNER_DOT);
        fake.modifiable = false;

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            registration::close
        );
        assertTrue(
            failure.getMessage().contains("not modifiable"),
            "restoration failure must be structured: " + failure.getMessage()
        );
        assertEquals(List.of("add", "remove"), fake.calls);
    }

    @Test
    void retransformFailureIsAStructuredCleanupFailure() throws Exception {
        final byte[] original = fixtureBytes();
        final FakeInstrumentation fake = new FakeInstrumentation();
        final HostFixtureLoader loader = new HostFixtureLoader(fake, original);
        final VerifiedMemberResolver resolver = resolver(loader);
        final Registration registration =
            new BoundingBoxOverlayButtonHookInstaller(fake.instrumentation()).install(resolver);

        loader.allowOwner(true);
        loader.loadClass(OWNER_DOT);
        fake.retransformThrows = true;

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            registration::close
        );
        assertTrue(
            failure.getMessage().contains("restore exact original bytes"),
            "restoration failure must be structured: " + failure.getMessage()
        );
        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    private static VerifiedMemberResolver resolver(final ClassLoader loader) {
        final StaticSelector update = StaticSelector.method(
            "cubism.ui-bounding-box-overlay.bounding-box.update",
            OWNER, "update", UPDATE_DESCRIPTOR,
            StaticSelector.ACCESS_PUBLIC | 0x0010
        );
        final StaticSelector setup = StaticSelector.staticMethod(
            "cubism.ui-bounding-box-overlay.bounding-box.setup-button",
            OWNER, HELPER_NAME, HELPER_DESCRIPTOR,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL
        );
        final StaticSelector times = StaticSelector.method(
            "cubism.ui-bounding-box-overlay.vector.times",
            "fixture/Offset", "times", "(F)Lfixture/Offset;",
            StaticSelector.ACCESS_PUBLIC | 0x0010
        );
        final StaticSelector plus = StaticSelector.method(
            "cubism.ui-bounding-box-overlay.vector.plus",
            "fixture/Offset", "plus", "(Lfixture/Offset;)Lfixture/Offset;",
            StaticSelector.ACCESS_PUBLIC | 0x0010
        );
        final HostArtifactFingerprint fingerprint =
            new HostArtifactFingerprint("5.3.02", 1, "a".repeat(64));
        final StaticVerificationRecord record = new StaticVerificationRecord(
            "fixture.overlay-buttons",
            "adapter.editor-ui.bounding-box-overlay-button",
            List.of("cubism.editor-ui.bounding-box-overlay-button"),
            "5.3.02",
            "cubism-5.3.02",
            fingerprint,
            "cubism-ref/verification/fixture.json",
            "runtime-adapter",
            "test",
            Instant.parse("2026-07-10T00:00:00Z"),
            "Fail closed.",
            List.of(update, setup, times, plus)
        );
        final StaticVerificationReport report = new StaticVerificationReport(
            fingerprint,
            fingerprint,
            true,
            List.of(update, setup, times, plus).stream()
                .map(selector -> new StaticSelectorResult(
                    selector,
                    StaticVerificationStatus.VERIFIED_STATIC,
                    "verified"
                ))
                .toList()
        );
        return new VerifiedMemberResolver(VerifiedAccessPlan.from(record, report), loader);
    }

    /** Exact-owner fixture: three native setup-helper calls in a straight-line update. */
    private static byte[] fixtureBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);

        final MethodVisitor update = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "update", UPDATE_DESCRIPTOR, null, null
        );
        update.visitCode();
        for (int call = 0; call < 3; call++) {
            for (int argument = 0; argument < 6; argument++) {
                update.visitInsn(Opcodes.ACONST_NULL);
            }
            update.visitMethodInsn(
                Opcodes.INVOKESTATIC, OWNER, HELPER_NAME, HELPER_DESCRIPTOR, false
            );
        }
        update.visitInsn(Opcodes.RETURN);
        update.visitMaxs(0, 0);
        update.visitEnd();

        final MethodVisitor helper = writer.visitMethod(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            HELPER_NAME,
            HELPER_DESCRIPTOR,
            null,
            null
        );
        helper.visitCode();
        helper.visitInsn(Opcodes.RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Minimal vector fixture referenced by the augmentation's offset arithmetic. */
    private static byte[] offsetBytes() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Offset", null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false
        );
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        for (String method : new String[] {"times", "plus"}) {
            final MethodVisitor visitor = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                method,
                method.equals("times") ? "(F)Lfixture/Offset;" : "(Lfixture/Offset;)Lfixture/Offset;",
                null,
                null
            );
            visitor.visitCode();
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitInsn(Opcodes.ARETURN);
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Simulates the JVM instrumentation pipeline: transform-on-initial-load through the
     * registered transformers and retransformation against the current bytes.
     */
    private static final class FakeInstrumentation {
        private final List<ClassFileTransformer> transformers = new ArrayList<>();
        private final Map<Class<?>, byte[]> currentBytes = new IdentityHashMap<>();
        private final Map<Class<?>, byte[]> originalBytes = new IdentityHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private boolean modifiable = true;
        private boolean retransformThrows;

        private Instrumentation instrumentation() {
            return (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isRetransformClassesSupported" -> true;
                    case "addTransformer" -> {
                        transformers.add((ClassFileTransformer) arguments[0]);
                        calls.add("add");
                        yield null;
                    }
                    case "removeTransformer" -> {
                        transformers.remove(arguments[0]);
                        calls.add("remove");
                        yield true;
                    }
                    case "isModifiableClass" -> modifiable;
                    case "retransformClasses" -> {
                        calls.add("retransform");
                        if (retransformThrows) {
                            throw new IllegalStateException("injected retransform failure");
                        }
                        for (Class<?> type : (Class<?>[]) arguments[0]) {
                            // Retransformation resets the class to its original defining
                            // bytes and then applies the registered transformers.
                            byte[] bytes = originalBytes.get(type);
                            for (ClassFileTransformer transformer : transformers) {
                                final byte[] next = transformer.transform(
                                    null,
                                    type.getClassLoader(),
                                    type.getName().replace('.', '/'),
                                    type,
                                    null,
                                    bytes
                                );
                                if (next != null) {
                                    bytes = next;
                                }
                            }
                            currentBytes.put(type, bytes);
                        }
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
            );
        }

        private byte[] instrumentInitial(
            final ClassLoader loader,
            final String internalName,
            final byte[] bytes
        ) throws java.lang.instrument.IllegalClassFormatException {
            byte[] result = bytes;
            for (ClassFileTransformer transformer : transformers) {
                final byte[] next = transformer.transform(
                    null, loader, internalName, null, null, result
                );
                if (next != null) {
                    result = next;
                }
            }
            return result;
        }

        private static Object defaultValue(final Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            if (type == double.class) {
                return 0D;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == char.class) {
                return '\0';
            }
            return null;
        }
    }

    /** Host-style loader: the owner is absent until {@link #allowOwner(boolean)}. */
    private static final class HostFixtureLoader extends ClassLoader {
        private final FakeInstrumentation fake;
        private final byte[] originalBytes;
        private boolean allowOwner;

        private HostFixtureLoader(final FakeInstrumentation fake, final byte[] originalBytes) {
            super(BoundingBoxOverlayButtonHookInstallerTest.class.getClassLoader());
            this.fake = fake;
            this.originalBytes = originalBytes;
        }

        private void allowOwner(final boolean allowed) {
            allowOwner = allowed;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve)
            throws ClassNotFoundException {
            try {
                return loadClassInternal(name);
            } catch (java.lang.instrument.IllegalClassFormatException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }

        private Class<?> loadClassInternal(final String name)
            throws ClassNotFoundException, java.lang.instrument.IllegalClassFormatException {
            final boolean resolveClass = false;
            synchronized (getClassLoadingLock(name)) {
                final Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                if (OWNER_DOT.equals(name)) {
                    if (!allowOwner) {
                        throw new ClassNotFoundException(name);
                    }
                    final byte[] transformed =
                        fake.instrumentInitial(this, OWNER, originalBytes);
                    final Class<?> defined = defineClass(name, transformed, 0, transformed.length);
                    fake.originalBytes.put(defined, originalBytes);
                    fake.currentBytes.put(defined, transformed);
                    return defined;
                }
                if ("fixture.Offset".equals(name)) {
                    final Class<?> defined = defineClass(name, offsetBytes(), 0, offsetBytes().length);
                    return defined;
                }
                return super.loadClass(name, resolveClass);
            }
        }
    }
}
