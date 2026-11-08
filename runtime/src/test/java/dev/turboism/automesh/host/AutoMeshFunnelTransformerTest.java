package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.Registration;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Guard control-flow tests on a real compiled class.
 *
 * <p>{@link FunnelFixture} stands in for a host funnel class: the same two method shapes, loaded
 * from its own bytes through a child-first loader, so the JVM verifies and executes the injected
 * bytecode. The fixture counters show whether a confirmation reached the native body.</p>
 */
class AutoMeshFunnelTransformerTest {

    private static final String OWNER = "dev/turboism/automesh/host/FunnelFixture";
    private static final String OWNER_BINARY_NAME = OWNER.replace('/', '.');
    private static final String MODELING = "updateMeshFromInspector_inModelingMode";
    private static final String MESH_EDITOR = "updateMeshFromInspector_inMeshEditorMode";
    private static final String MODELING_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String MESH_EDITOR_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;)V";
    private static final String MODELING_KEY = "turboism.automesh.guard.modeling-mode";
    private static final String MESH_EDITOR_KEY = "turboism.automesh.guard.mesh-editor-mode";
    private static final String PROPERTY_LOOKUP =
            "INVOKEVIRTUAL java/util/Properties.get" + "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String SYSTEM_PROPERTIES =
            "INVOKESTATIC java/lang/System.getProperties()Ljava/util/Properties;";
    private static final Class<?>[] MODELING_PARAMETERS = {
        Object.class, Object.class, List.class, Object.class, Object.class
    };
    private static final Class<?>[] MESH_EDITOR_PARAMETERS = {Object.class, Object.class, List.class};
    private static final int FIRST_INSTRUCTION_COUNT = 3;

    private Registration bridge;

    @AfterEach
    void closeBridge() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    @Test
    void injectsTheGuardAtOffsetZeroOfBothFunnelsWithLoaderNeutralReferences() {
        final GuardedLoader loader = new GuardedLoader();
        final List<String> diagnostics = new ArrayList<>();
        final byte[] transformed =
                transformer(loader, diagnostics).transform(null, loader, OWNER, null, null, fixtureBytes());

        assertNotNull(transformed, "the exact funnel shape must transform");
        assertTrue(diagnostics.isEmpty(), "a proven shape reports no diagnostic");
        assertEquals(
                List.of(SYSTEM_PROPERTIES, "LDC " + MODELING_KEY, PROPERTY_LOOKUP),
                AutoMeshFunnelTestSupport.firstInstructions(transformed, MODELING, FIRST_INSTRUCTION_COUNT),
                "the guard must be the first code of the modeling-mode funnel");
        assertEquals(
                List.of(SYSTEM_PROPERTIES, "LDC " + MESH_EDITOR_KEY, PROPERTY_LOOKUP),
                AutoMeshFunnelTestSupport.firstInstructions(transformed, MESH_EDITOR, FIRST_INSTRUCTION_COUNT),
                "the guard must be the first code of the mesh-editor funnel");
        final String constants = new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (final String forbidden : List.of(
                "org/objectweb/asm",
                "dev/turboism/automesh/host/NativeAutoMeshBridge",
                "dev/turboism/automesh/host/AutoMeshFunnelTransformer",
                "dev/turboism/automesh/host/AutoMeshFunnel")) {
            assertFalse(constants.contains(forbidden), "transformed bytecode must not name " + forbidden);
        }
    }

    @Test
    void handledConfirmationSkipsTheNativeBodyAndForwardsTheFunnelArguments() throws Exception {
        final List<AutoMeshFunnel> funnels = new ArrayList<>();
        final AtomicReference<Object[]> arguments = new AtomicReference<>();
        bridge = NativeAutoMeshBridge.install(
                (funnel, hostArguments) -> {
                    funnels.add(funnel);
                    arguments.set(hostArguments);
                    return Boolean.TRUE;
                },
                message -> {});

        final Object fixture = transformedFixture();
        final Object pack = new Object();
        invoke(fixture, MODELING, new Object[] {pack, "event", List.of(), "undo", "callback"});

        assertEquals(0, counter(fixture, "modelingCalls"), "a handled confirmation must not reach the native body");
        assertEquals(List.of(AutoMeshFunnel.MODELING_MODE), funnels);
        final Object[] forwarded = arguments.get();
        assertEquals(AutoMeshFunnel.MODELING_MODE.token(), forwarded[0], "the token identifies the funnel");
        assertSame(fixture, forwarded[1], "the receiver must be forwarded");
        assertSame(pack, forwarded[2], "the pack must be forwarded");
        assertEquals(7, forwarded.length, "token, receiver and the five declared arguments");
    }

    @Test
    void unhandledConfirmationRunsTheNativeBody() throws Exception {
        final AtomicInteger policyCalls = new AtomicInteger();
        bridge = NativeAutoMeshBridge.install(
                (funnel, hostArguments) -> {
                    policyCalls.incrementAndGet();
                    return Boolean.FALSE;
                },
                message -> {});

        final Object fixture = transformedFixture();
        invoke(fixture, MODELING, new Object[] {null, null, List.of(), null, null});
        invoke(fixture, MESH_EDITOR, new Object[] {null, null, List.of()});

        assertEquals(2, policyCalls.get(), "both funnels must reach the policy");
        assertEquals(1, counter(fixture, "modelingCalls"), "an unhandled confirmation keeps the native body");
        assertEquals(1, counter(fixture, "meshEditorCalls"), "an unhandled confirmation keeps the native body");
    }

    @Test
    void throwingPolicyRunsTheNativeBodyAndReportsADiagnostic() throws Exception {
        final List<String> diagnostics = new ArrayList<>();
        bridge = NativeAutoMeshBridge.install(
                (funnel, hostArguments) -> {
                    throw new IllegalStateException("plugin algorithm failed");
                },
                diagnostics::add);

        final Object fixture = transformedFixture();
        invoke(fixture, MESH_EDITOR, new Object[] {null, null, List.of()});

        assertEquals(1, counter(fixture, "meshEditorCalls"), "a throwing policy must not block the native algorithm");
        assertEquals(1, diagnostics.size(), "the swallowed failure must be reported once");
        assertTrue(diagnostics.get(0).contains("IllegalStateException"), diagnostics.get(0));
    }

    @Test
    void missingBridgeCallbackRunsTheNativeBody() throws Exception {
        final Object fixture = transformedFixture();
        invoke(fixture, MODELING, new Object[] {null, null, List.of(), null, null});
        assertEquals(1, counter(fixture, "modelingCalls"), "an uninstalled bridge must leave the native path intact");
    }

    @Test
    void nestedConfirmationFromThePolicyIsNotGuardedAgain() throws Exception {
        final List<String> diagnostics = new ArrayList<>();
        final AtomicInteger policyCalls = new AtomicInteger();
        final AtomicReference<Object> fixture = new AtomicReference<>();
        bridge = NativeAutoMeshBridge.install(
                (funnel, hostArguments) -> {
                    if (policyCalls.incrementAndGet() == 1) {
                        invokeUnchecked(fixture.get(), MODELING, new Object[] {null, null, List.of(), null, null});
                    }
                    return Boolean.TRUE;
                },
                diagnostics::add);

        final Object subject = transformedFixture();
        fixture.set(subject);
        invoke(subject, MODELING, new Object[] {null, null, List.of(), null, null});

        assertEquals(1, policyCalls.get(), "the nested confirmation must not re-enter the policy");
        assertEquals(1, counter(subject, "modelingCalls"), "only the nested confirmation reaches the native body");
        assertTrue(
                diagnostics.stream().anyMatch(message -> message.contains("nested")),
                "the ignored nested confirmation must be reported: " + diagnostics);
    }

    @Test
    void repeatedTransformationIsDeterministicAndIdempotent() {
        final GuardedLoader loader = new GuardedLoader();
        final AutoMeshFunnelTransformer transformer = transformer(loader, new ArrayList<>());
        final byte[] original = fixtureBytes();

        final byte[] first = transformer.transform(null, loader, OWNER, null, null, original);
        final byte[] second = transformer.transform(null, loader, OWNER, null, null, original);

        assertNotNull(first, "the first transformation must produce bytes");
        assertTrue(Arrays.equals(first, second), "the same input must always produce the same guarded bytes");
        assertNull(
                transformer.transform(null, loader, OWNER, null, null, first),
                "a second transform of already guarded bytes must be a no-op");
    }

    @Test
    void unexpectedShapesStayByteIdentical() {
        final GuardedLoader loader = new GuardedLoader();
        final List<String> diagnostics = new ArrayList<>();
        final AutoMeshFunnelTransformer transformer = transformer(loader, diagnostics);

        assertNull(
                transformer.transform(null, loader, OWNER, null, null, rewriteWithSecondOverload()),
                "a second member with the funnel name must fail closed");
        assertNull(
                transformer.transform(null, loader, OWNER, null, null, rewriteWithoutModelingFunnel()),
                "a missing funnel must fail closed");
        assertNull(
                transformer.transform(null, loader, OWNER, null, null, rewriteAsStaticFunnel()),
                "a static funnel must fail closed");
        assertEquals(3, diagnostics.size(), "every refusal must leave a diagnostic: " + diagnostics);
    }

    @Test
    void driftedDescriptorIsRefused() {
        final GuardedLoader loader = new GuardedLoader();
        final List<String> diagnostics = new ArrayList<>();
        final AutoMeshFunnelTransformer drifted = new AutoMeshFunnelTransformer(
                OWNER,
                List.of(
                        new AutoMeshFunnelTransformer.Site(
                                AutoMeshFunnel.MODELING_MODE,
                                OWNER,
                                MODELING,
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;)V"),
                        new AutoMeshFunnelTransformer.Site(
                                AutoMeshFunnel.MESH_EDITOR_MODE, OWNER, MESH_EDITOR, MESH_EDITOR_DESCRIPTOR)),
                loader,
                diagnostics::add);

        assertNull(
                drifted.transform(null, loader, OWNER, null, null, fixtureBytes()),
                "a funnel whose descriptor drifted must stay byte-identical");
        assertTrue(diagnostics.get(0).contains("descriptor"), diagnostics.get(0));
    }

    @Test
    void foreignClassAndForeignLoaderAreIgnored() {
        final GuardedLoader loader = new GuardedLoader();
        final AutoMeshFunnelTransformer transformer = transformer(loader, new ArrayList<>());

        assertNull(
                transformer.transform(null, loader, "dev/turboism/automesh/host/Other", null, null, fixtureBytes()),
                "a foreign class must not transform");
        assertNull(
                transformer.transform(null, getClass().getClassLoader(), OWNER, null, null, fixtureBytes()),
                "a foreign loader must not transform");
    }

    @Test
    void guardSiteRejectsSignaturesItCannotForward() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshFunnelTransformer.Site(AutoMeshFunnel.MODELING_MODE, OWNER, MODELING, "()Z"),
                "a guard cannot return a value");
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshFunnelTransformer.Site(AutoMeshFunnel.MODELING_MODE, OWNER, MODELING, "(I)V"),
                "a guard cannot forward a primitive parameter");
    }

    // ------------------------------------------------------------------
    // Fixture plumbing
    // ------------------------------------------------------------------

    private static AutoMeshFunnelTransformer transformer(final ClassLoader loader, final List<String> diagnostics) {
        return new AutoMeshFunnelTransformer(
                OWNER,
                List.of(
                        new AutoMeshFunnelTransformer.Site(
                                AutoMeshFunnel.MODELING_MODE, OWNER, MODELING, MODELING_DESCRIPTOR),
                        new AutoMeshFunnelTransformer.Site(
                                AutoMeshFunnel.MESH_EDITOR_MODE, OWNER, MESH_EDITOR, MESH_EDITOR_DESCRIPTOR)),
                loader,
                diagnostics::add);
    }

    private static Object transformedFixture() {
        final GuardedLoader loader = new GuardedLoader();
        final byte[] transformed =
                transformer(loader, new ArrayList<>()).transform(null, loader, OWNER, null, null, fixtureBytes());
        assertNotNull(transformed, "the fixture must transform");
        try {
            return loader.defineGuarded(OWNER_BINARY_NAME, transformed)
                    .getConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("the transformed fixture must load and link", failure);
        }
    }

    private static void invoke(final Object fixture, final String method, final Object[] arguments) {
        try {
            fixture.getClass().getMethod(method, parameterTypesOf(method)).invoke(fixture, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("a guarded funnel must stay callable", failure);
        }
    }

    private static void invokeUnchecked(final Object fixture, final String method, final Object[] arguments) {
        try {
            fixture.getClass().getMethod(method, parameterTypesOf(method)).invoke(fixture, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("nested fixture call failed", failure);
        }
    }

    private static Class<?>[] parameterTypesOf(final String method) {
        return MODELING.equals(method) ? MODELING_PARAMETERS : MESH_EDITOR_PARAMETERS;
    }

    private static int counter(final Object fixture, final String field) {
        try {
            return fixture.getClass().getField(field).getInt(fixture);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] fixtureBytes() {
        try (InputStream input = FunnelFixture.class.getResourceAsStream("FunnelFixture.class")) {
            assertNotNull(input, "the fixture class file must be on the test classpath");
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] rewriteWithSecondOverload() {
        final ClassReader reader = new ClassReader(fixtureBytes());
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public void visitEnd() {
                        final MethodVisitor overload =
                                super.visitMethod(Opcodes.ACC_PUBLIC, MODELING, "(Ljava/lang/Object;)V", null, null);
                        overload.visitCode();
                        overload.visitInsn(Opcodes.RETURN);
                        overload.visitMaxs(0, 0);
                        overload.visitEnd();
                        super.visitEnd();
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static byte[] rewriteWithoutModelingFunnel() {
        final ClassReader reader = new ClassReader(fixtureBytes());
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (MODELING.equals(name)) {
                            return null;
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static byte[] rewriteAsStaticFunnel() {
        final ClassReader reader = new ClassReader(fixtureBytes());
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final int updated = MODELING.equals(name) ? access | Opcodes.ACC_STATIC : access;
                        return super.visitMethod(updated, name, descriptor, signature, exceptions);
                    }
                },
                0);
        return writer.toByteArray();
    }

    /** Child-first loader: the guarded fixture is defined from the transformer's own output. */
    private static final class GuardedLoader extends ClassLoader {

        private final Map<String, byte[]> guarded = new HashMap<>();

        private GuardedLoader() {
            super(FunnelFixture.class.getClassLoader());
        }

        private Class<?> defineGuarded(final String name, final byte[] bytes) {
            guarded.put(name, bytes);
            try {
                return loadClass(name);
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!guarded.containsKey(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    final byte[] bytes = guarded.get(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
