package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.Registration;
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
 * Attach control-flow tests on a real compiled class.
 *
 * <p>{@link ContainerFixture} stands in for a native container: the same constructor shape, loaded
 * from its own bytes through a child-first loader, so the JVM verifies and executes the injected
 * bytecode. The fixture counters show whether the constructor body ran and whether the bridge was
 * called exactly once.</p>
 */
class AutoMeshPanelAttachTransformerTest {

    private static final String OWNER = "dev/turboism/automesh/host/ContainerFixture";
    private static final String OWNER_BINARY_NAME = OWNER.replace('/', '.');
    private static final String DESCRIPTOR = "(Ljava/lang/Object;)V";
    private static final String ATTACH_KEY = "turboism.automesh.attach";
    private static final String PROPERTY_LOOKUP =
            "INVOKEVIRTUAL java/util/Properties.get(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String FUNCTION_APPLY =
            "INVOKEINTERFACE java/util/function/Function.apply(Ljava/lang/Object;)Ljava/lang/Object;";

    private Registration bridge;

    @AfterEach
    void closeBridge() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    @Test
    void injectsTheAttachCallBeforeTheSingleConstructorReturn() {
        final GuardedLoader loader = new GuardedLoader();
        final List<String> diagnostics = new ArrayList<>();
        final byte[] transformed =
                transformer(loader, diagnostics).transform(null, loader, OWNER, null, null, fixtureBytes());

        assertNotNull(transformed, "the exact constructor shape must transform");
        assertTrue(diagnostics.isEmpty(), "a proven shape reports no diagnostic");
        assertFalse(Arrays.equals(fixtureBytes(), transformed), "the attach call must change the sourced bytes");
        assertFalse(
                new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .contains("dev/turboism/automesh/host/AutoMeshPanelAttachTransformer"),
                "transformed host bytecode must not name the transformer");
        assertFalse(
                new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .contains("dev/turboism/automesh/host/NativeAutoMeshBridge"),
                "transformed host bytecode must not name the bridge");
        assertTrue(
                Arrays.equals(
                        transformed,
                        transformer(loader, diagnostics).transform(null, loader, OWNER, null, null, fixtureBytes())),
                "the same host bytes must always produce the same decorated bytes");
    }

    @Test
    void callsTheBridgeExactlyOnceOnConstruction() {
        final GuardedLoader loader = new GuardedLoader();
        final AtomicInteger attachCalls = new AtomicInteger();
        final AtomicReference<Object> attached = new AtomicReference<>();
        final AtomicReference<AutoMeshContainer> container = new AtomicReference<>();
        bridge = NativeAutoMeshBridge.install((funnel, arguments) -> Boolean.FALSE, message -> {});
        bridge.close();
        bridge = NativeAutoMeshBridge.install(
                new NativeAutoMeshBridge.Handler() {
                    @Override
                    public Boolean handle(final AutoMeshFunnel funnel, final Object[] hostArguments) {
                        return Boolean.FALSE;
                    }

                    @Override
                    public void attach(final AutoMeshContainer requested, final Object instance) {
                        container.set(requested);
                        attached.set(instance);
                        attachCalls.incrementAndGet();
                    }
                },
                message -> {});

        final byte[] transformed =
                transformer(loader, new ArrayList<>()).transform(null, loader, OWNER, null, null, fixtureBytes());
        assertNotNull(transformed);
        loader.register(OWNER_BINARY_NAME, transformed);

        final Object pack = new Object();
        final Object instance = construct(loader, pack);

        assertEquals(1, attachCalls.get(), "the bridge must be called exactly once per construction");
        assertEquals(AutoMeshContainer.MODELING_DIALOG_PANEL, container.get(), "the site carries its container");
        org.junit.jupiter.api.Assertions.assertSame(instance, attached.get(), "the bridge receives the container");
        assertEquals(1, new FixtureAccess(instance).constructorCalls(), "the native body must still run");
    }

    @Test
    void leavesTheClassByteIdenticalWhenTheConstructorHasTwoReturns() {
        final GuardedLoader loader = new GuardedLoader();
        final List<String> diagnostics = new ArrayList<>();
        final byte[] twoReturns = rewriteConstructor(fixtureBytes(), 2);

        assertNull(
                transformer(loader, diagnostics).transform(null, loader, OWNER, null, null, twoReturns),
                "a constructor with two returns must stay byte-identical");
        assertTrue(
                diagnostics.stream().anyMatch(message -> message.contains("returns")),
                "the refusal must name the cardinality: " + diagnostics);
    }

    @Test
    void leavesTheClassByteIdenticalWhenTheDescriptorDrifted() {
        final GuardedLoader loader = new GuardedLoader();
        final List<String> diagnostics = new ArrayList<>();
        final AutoMeshPanelAttachTransformer drifted = new AutoMeshPanelAttachTransformer(
                new AutoMeshPanelAttachTransformer.Site(
                        AutoMeshContainer.MODELING_POPUP_DIALOG, OWNER, "(Ljava/lang/Object;I)V"),
                loader,
                diagnostics::add);

        assertNull(
                drifted.transform(null, loader, OWNER, null, null, fixtureBytes()),
                "a drifted descriptor must stay byte-identical");
        assertTrue(
                diagnostics.stream().anyMatch(message -> message.contains("missing")),
                "the refusal must name the missing constructor: " + diagnostics);
    }

    @Test
    void refusesASecondTransformationOfTheSameBytes() {
        final GuardedLoader loader = new GuardedLoader();
        final List<String> diagnostics = new ArrayList<>();
        final byte[] transformed =
                transformer(loader, diagnostics).transform(null, loader, OWNER, null, null, fixtureBytes());
        assertNotNull(transformed);

        assertNull(
                transformer(loader, diagnostics).transform(null, loader, OWNER, null, null, transformed),
                "already decorated bytes must stay unchanged");
        assertTrue(
                diagnostics.stream().anyMatch(message -> message.contains("already decorated")),
                "the refusal must name the marker: " + diagnostics);
    }

    @Test
    void ignoresAnotherClassLoaderAndAnotherOwner() {
        final GuardedLoader loader = new GuardedLoader();
        final List<String> diagnostics = new ArrayList<>();
        final AutoMeshPanelAttachTransformer transformer = transformer(loader, diagnostics);

        assertNull(
                transformer.transform(null, ClassLoader.getSystemClassLoader(), OWNER, null, null, fixtureBytes()),
                "another loader must be ignored");
        assertNull(
                transformer.transform(null, loader, "dev/turboism/automesh/host/Other", null, null, fixtureBytes()),
                "another owner must be ignored");
        assertTrue(diagnostics.isEmpty(), "an ignored class reports no diagnostic");
    }

    @Test
    void survivesAThrowingBridgeAndStillRunsTheConstructorBody() {
        final GuardedLoader loader = new GuardedLoader();
        bridge = NativeAutoMeshBridge.install(
                new NativeAutoMeshBridge.Handler() {
                    @Override
                    public Boolean handle(final AutoMeshFunnel funnel, final Object[] hostArguments) {
                        return Boolean.FALSE;
                    }

                    @Override
                    public void attach(final AutoMeshContainer container, final Object instance) {
                        throw new IllegalStateException("plugin policy failed");
                    }
                },
                message -> {});

        final byte[] transformed =
                transformer(loader, new ArrayList<>()).transform(null, loader, OWNER, null, null, fixtureBytes());
        assertNotNull(transformed);
        loader.register(OWNER_BINARY_NAME, transformed);

        final Object instance = construct(loader, new Object());
        assertEquals(
                1,
                new FixtureAccess(instance).constructorCalls(),
                "a throwing attach policy must not stop the native constructor");
    }

    @Test
    void constructsUnchangedWhenNoBridgeIsInstalled() {
        final GuardedLoader loader = new GuardedLoader();
        final byte[] transformed =
                transformer(loader, new ArrayList<>()).transform(null, loader, OWNER, null, null, fixtureBytes());
        assertNotNull(transformed);
        loader.register(OWNER_BINARY_NAME, transformed);

        final Object instance = construct(loader, new Object());
        assertEquals(
                1,
                new FixtureAccess(instance).constructorCalls(),
                "an absent bridge must leave the native constructor untouched");
    }

    @Test
    void theAttachCallSitsImmediatelyBeforeTheReturn() {
        final GuardedLoader loader = new GuardedLoader();
        final byte[] transformed =
                transformer(loader, new ArrayList<>()).transform(null, loader, OWNER, null, null, fixtureBytes());
        assertNotNull(transformed);
        final List<String> instructions = AutoMeshFunnelTestSupport.instructions(transformed, "<init>");
        assertEquals(
                1,
                instructions.stream().filter("RETURN"::equals).count(),
                "the decorated constructor must keep exactly one return");
        assertEquals("RETURN", instructions.get(instructions.size() - 1), "the native return must stay last");
        assertTrue(
                instructions.indexOf(FUNCTION_APPLY) > instructions.indexOf(PROPERTY_LOOKUP),
                "the callback must be looked up before it is applied");
        assertTrue(
                instructions.indexOf(FUNCTION_APPLY) < instructions.size() - 1,
                "the callback must be applied before the native return");
    }

    private static AutoMeshPanelAttachTransformer transformer(
            final ClassLoader loader, final List<String> diagnostics) {
        return new AutoMeshPanelAttachTransformer(
                new AutoMeshPanelAttachTransformer.Site(AutoMeshContainer.MODELING_DIALOG_PANEL, OWNER, DESCRIPTOR),
                loader,
                diagnostics::add);
    }

    private static Object construct(final ClassLoader loader, final Object pack) {
        try {
            return Class.forName(OWNER_BINARY_NAME, true, loader)
                    .getConstructor(Object.class)
                    .newInstance(pack);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("the decorated constructor must link and run", failure);
        }
    }

    private static byte[] fixtureBytes() {
        try (java.io.InputStream input = ContainerFixture.class.getResourceAsStream("ContainerFixture.class")) {
            assertNotNull(input, "the fixture class file must be on the test classpath");
            return input.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Adds a second {@code RETURN} to the constructor so the cardinality check can be proven. */
    private static byte[] rewriteConstructor(final byte[] original, final int returns) {
        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
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
                        if (!"<init>".equals(name) || !DESCRIPTOR.equals(descriptor)) {
                            return delegate;
                        }
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            private int seen;

                            @Override
                            public void visitInsn(final int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    seen++;
                                    if (seen < returns) {
                                        // An extra return is unreachable but still counts, which is
                                        // exactly the shape the anchor must refuse.
                                        super.visitLabel(new org.objectweb.asm.Label());
                                        super.visitInsn(Opcodes.RETURN);
                                    }
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },
                0);
        return writer.toByteArray();
    }

    /** Reads the fixture counters through reflection, because the loader is a child loader. */
    private record FixtureAccess(Object instance) {

        private int constructorCalls() {
            try {
                return instance.getClass().getField("constructorCalls").getInt(instance);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("the fixture counter must be readable", failure);
            }
        }
    }

    /** Child-first loader that defines the decorated fixture from the transformer output. */
    private static final class GuardedLoader extends java.net.URLClassLoader {

        private final Map<String, byte[]> guarded = new HashMap<>();

        private GuardedLoader() {
            super(new java.net.URL[0], ClassLoader.getPlatformClassLoader());
        }

        private void register(final String name, final byte[] bytes) {
            guarded.put(name, bytes);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = guarded.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }
}
