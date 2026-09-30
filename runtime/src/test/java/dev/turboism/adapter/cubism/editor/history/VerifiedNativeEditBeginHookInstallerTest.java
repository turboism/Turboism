package dev.turboism.adapter.cubism.editor.history;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistoryIngressSelectorContract;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Pins the hook's admission gate.
 *
 * <p>The selectors use the exact reviewed owner names but are resolved through an in-memory plan, so
 * these assertions are about the gate's shape — version, capability, alias set and the exact public
 * instance signature — and never about a live artifact. The live artifact is pinned by the reviewed
 * records and the manifest.</p>
 */
class VerifiedNativeEditBeginHookInstallerTest {

    private static final String MODELING_OWNER = "com/live2d/cubism/doc/modeling/CModelingEditMode_Main";
    private static final String INHERITED_OWNER = "com/live2d/cubism/doc/ACEditMode";
    private static final String DESCRIPTOR = "(Ljava/lang/String;)Lcom/live2d/undo/GroupUndo;";
    private static final ClassLoader LOADER = VerifiedNativeEditBeginHookInstallerTest.class.getClassLoader();

    @Test
    void theHookRequiresTheReviewedVersionAndTheAdmittedAliases() {
        assertDoesNotThrow(
                () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                        instrumentation(), resolver("5.3.03", bothEntries()), LOADER),
                "5.3.03 admits the native entry hook from its own reviewed record");

        assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                        instrumentation(), resolver("5.3.02", List.of()), LOADER),
                "the ingress aliases are not admitted");
    }

    @Test
    void theModelingEntryMustBeTheExactAdmittedTarget() {
        final List<StaticSelector> wrongOwner = new ArrayList<>();
        wrongOwner.add(instanceEntry(
                EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS, "fixture/NotTheModelingEntry"));
        wrongOwner.add(instanceEntry(EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, INHERITED_OWNER));

        assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                        instrumentation(), resolver("5.3.02", wrongOwner), LOADER));
    }

    @Test
    void theInheritedEntryMustBeTheExactAdmittedTarget() {
        final List<StaticSelector> wrongOwner = new ArrayList<>();
        wrongOwner.add(instanceEntry(EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS, MODELING_OWNER));
        wrongOwner.add(
                instanceEntry(EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, "fixture/NotTheBaseEntry"));

        assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                        instrumentation(), resolver("5.3.02", wrongOwner), LOADER));
    }

    @Test
    void aTargetWithoutTheExactSignatureIsRefused() {
        final List<StaticSelector> wrongDescriptor = new ArrayList<>();
        wrongDescriptor.add(StaticSelector.method(
                EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS,
                MODELING_OWNER,
                "beginEdit",
                "(I)Lcom/live2d/undo/GroupUndo;",
                StaticSelector.ACCESS_PUBLIC));
        wrongDescriptor.add(instanceEntry(EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, INHERITED_OWNER));

        assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                        instrumentation(), resolver("5.3.02", wrongDescriptor), LOADER));
    }

    @Test
    void aStaticTargetIsRefusedBecauseBeginEditIsAnInstanceMethod() {
        final List<StaticSelector> staticEntry = new ArrayList<>();
        staticEntry.add(StaticSelector.staticMethod(
                EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS,
                MODELING_OWNER,
                "beginEdit",
                DESCRIPTOR,
                StaticSelector.ACCESS_PUBLIC));
        staticEntry.add(instanceEntry(EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, INHERITED_OWNER));

        assertThrows(
                IllegalArgumentException.class,
                () -> VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                        instrumentation(), resolver("5.3.02", staticEntry), LOADER));
    }

    @Test
    void anAcceptedResolutionBuildsAnUninstalledInstaller() throws Exception {
        final VerifiedNativeEditBeginHookInstaller installer =
                VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                        instrumentation(), resolver("5.2.03", bothEntries()), LOADER);

        assertFalse(installer.isInstalled(), "nothing is instrumented before install");
        assertFalse(
                System.getProperties().containsKey(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY),
                "the receiver is registered only by install");
        installer.close();
        assertFalse(installer.isInstalled());
    }

    @Test
    void aRefusedInstallLeavesNoReceiverAndNoTransformerBehind() {
        final VerifiedNativeEditBeginHookInstaller installer =
                VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                        instrumentation(false), resolver("5.3.02", bothEntries()), LOADER);

        assertThrows(IllegalStateException.class, () -> installer.install(NativeEditBeginBridge.ingress()));
        assertFalse(installer.isInstalled());
        assertFalse(
                System.getProperties().containsKey(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY),
                "a refused install must not leave the receiver registered");
    }

    @Test
    void retransformationWithoutAnActualPatchDoesNotCountAsInstalled() {
        final InstrumentedHost fixture = new InstrumentedHost(false, false, 0);
        final var installer = fixture.installer();

        try (installer) {
            assertThrows(IllegalStateException.class, () -> installer.install(value -> {}));
            assertFalse(installer.isInstalled());
            assertTrue(fixture.transformers.isEmpty());
            assertFalse(System.getProperties().containsKey(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY));
        }
    }

    @Test
    void changedTargetIsRejectedDespiteAnAdmittedSelector() {
        final InstrumentedHost fixture = new InstrumentedHost(true, true, 0);
        final var installer = fixture.installer();

        try (installer) {
            assertThrows(IllegalStateException.class, () -> installer.install(value -> {}));
            assertFalse(installer.isInstalled());
            assertTrue(fixture.transformers.isEmpty());
            assertFalse(System.getProperties().containsKey(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY));
        }
    }

    @Test
    void partialTransformerRegistrationFailureIsRolledBack() {
        final InstrumentedHost fixture = new InstrumentedHost(true, false, 2);
        final var installer = fixture.installer();

        try (installer) {
            assertThrows(IllegalStateException.class, () -> installer.install(value -> {}));
            assertFalse(installer.isInstalled());
            assertTrue(fixture.transformers.isEmpty());
            assertFalse(System.getProperties().containsKey(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY));
        }
    }

    @Test
    void bothInstalledEntryMethodsActuallyCallTheReceiverAndCloseRestoresThem() throws Exception {
        final InstrumentedHost fixture = new InstrumentedHost(true, false, 0);
        final var installer = fixture.installer();
        final List<String> received = new ArrayList<>();
        try {
            installer.install(received::add);
            assertTrue(installer.isInstalled());
            assertEquals(
                    Set.of(MODELING_OWNER.replace('/', '.'), INHERITED_OWNER.replace('/', '.')),
                    Set.copyOf(installer.retransformedClassNames()));
            final ClassLoader patchedLoader = fixture.loader(fixture.applied);
            for (final String owner : List.of(MODELING_OWNER, INHERITED_OWNER)) {
                final Class<?> type = Class.forName(owner.replace('/', '.'), true, patchedLoader);
                type.getMethod("beginEdit", String.class)
                        .invoke(type.getConstructor().newInstance(), owner);
            }
            assertEquals(List.of(MODELING_OWNER, INHERITED_OWNER), received);
            installer.install(received::add);
            assertEquals(2, fixture.transformers.size(), "install is idempotent");
        } finally {
            installer.close();
        }
        assertFalse(installer.isInstalled());
        assertTrue(fixture.transformers.isEmpty());
        assertFalse(System.getProperties().containsKey(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY));
        for (final String owner : List.of(MODELING_OWNER, INHERITED_OWNER)) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(fixture.original.get(owner), fixture.applied.get(owner));
        }
    }

    @Test
    void anotherInstallersReceiverIsNeverReplacedOrRemoved() {
        final InstrumentedHost fixture = new InstrumentedHost(true, false, 0);
        final var installer = fixture.installer();
        final Object existing = new Object();
        System.getProperties().put(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY, existing);
        try {
            assertThrows(IllegalStateException.class, () -> installer.install(value -> {}));
            org.junit.jupiter.api.Assertions.assertSame(
                    existing, System.getProperties().get(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY));
            assertTrue(fixture.transformers.isEmpty());
        } finally {
            installer.close();
            System.getProperties().remove(VerifiedNativeEditBeginHookInstaller.CALLBACK_KEY, existing);
        }
    }

    private static final class InstrumentedHost {
        private final Map<String, byte[]> original = new LinkedHashMap<>();
        private final Map<String, byte[]> applied = new LinkedHashMap<>();
        private final Map<String, Class<?>> loaded = new LinkedHashMap<>();
        private final List<ClassFileTransformer> transformers = new ArrayList<>();
        private final ClassLoader hostLoader;
        private final Instrumentation instrumentation;
        private int registrations;

        private InstrumentedHost(final boolean transform, final boolean changed, final int failRegistration) {
            original.put(MODELING_OWNER, classBytes(MODELING_OWNER, changed ? "changedEntry" : "beginEdit"));
            original.put(INHERITED_OWNER, classBytes(INHERITED_OWNER, "beginEdit"));
            original.put("com/live2d/undo/GroupUndo", classBytes("com/live2d/undo/GroupUndo", null));
            applied.putAll(original);
            hostLoader = loader(original);
            instrumentation = (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                    LOADER, new Class<?>[] {Instrumentation.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "isRetransformClassesSupported", "isModifiableClass" -> true;
                        case "getAllLoadedClasses" -> loaded.values().toArray(Class<?>[]::new);
                        case "addTransformer" -> {
                            if (++registrations == failRegistration) {
                                throw new IllegalStateException("registration failed");
                            }
                            transformers.add((ClassFileTransformer) args[0]);
                            yield null;
                        }
                        case "removeTransformer" -> transformers.remove(args[0]);
                        case "retransformClasses" -> {
                            for (final Class<?> type : (Class<?>[]) args[0]) {
                                final String name = type.getName().replace('.', '/');
                                byte[] bytes = original.get(name);
                                if (transform) {
                                    for (final ClassFileTransformer transformer : List.copyOf(transformers)) {
                                        final byte[] patched = transformer.transform(
                                                type.getModule(), hostLoader, name, type, null, bytes);
                                        if (patched != null) bytes = patched;
                                    }
                                }
                                applied.put(name, bytes);
                            }
                            yield null;
                        }
                        case "toString" -> "InstrumentedHost";
                        default -> null;
                    });
        }

        private ClassLoader loader(final Map<String, byte[]> definitions) {
            return new ClassLoader(LOADER) {
                @Override
                protected Class<?> findClass(final String name) throws ClassNotFoundException {
                    final byte[] bytes = definitions.get(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    final Class<?> type = defineClass(name, bytes, 0, bytes.length);
                    loaded.put(name, type);
                    return type;
                }
            };
        }

        private VerifiedNativeEditBeginHookInstaller installer() {
            return VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                    instrumentation,
                    TestVerifiedResolvers.create(
                            "5.3.02",
                            EditorHistoryIngressSelectorContract.ADAPTER_SLICE_ID,
                            Set.of(EditorHistoryIngressSelectorContract.CAPABILITY_ID),
                            bothEntries(),
                            hostLoader),
                    hostLoader);
        }

        private static byte[] classBytes(final String owner, final String method) {
            final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
            final var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(0, 0);
            constructor.visitEnd();
            if (method != null) {
                final var entry = writer.visitMethod(Opcodes.ACC_PUBLIC, method, DESCRIPTOR, null, null);
                entry.visitCode();
                entry.visitInsn(Opcodes.ACONST_NULL);
                entry.visitInsn(Opcodes.ARETURN);
                entry.visitMaxs(0, 0);
                entry.visitEnd();
            }
            writer.visitEnd();
            return writer.toByteArray();
        }
    }

    @Test
    void theHookIsKeyedOnTheTwoReviewedAliases() {
        assertEquals(
                "cubism.editor-model.edit-mode.begin",
                EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS,
                "the modeling entry keeps the alias the mature Editor surface already uses");
        assertEquals(
                "cubism.editor-history.edit-mode.begin", EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS);
        assertTrue(
                EditorHistoryIngressSelectorContract.REQUIRED_ALIASES.contains(
                        EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS),
                "the inherited entry is part of the reviewed ingress family");
        assertFalse(
                EditorHistoryIngressSelectorContract.REQUIRED_ALIASES.contains(
                        EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS),
                "the modeling entry is not re-declared by the ingress");
    }

    private static List<StaticSelector> bothEntries() {
        return List.of(
                instanceEntry(EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS, MODELING_OWNER),
                instanceEntry(EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS, INHERITED_OWNER));
    }

    private static StaticSelector instanceEntry(final String alias, final String owner) {
        return StaticSelector.method(alias, owner, "beginEdit", DESCRIPTOR, StaticSelector.ACCESS_PUBLIC);
    }

    private static VerifiedMemberResolver resolver(final String version, final List<StaticSelector> selectors) {
        return TestVerifiedResolvers.create(
                version,
                EditorHistoryIngressSelectorContract.ADAPTER_SLICE_ID,
                Set.of(EditorHistoryIngressSelectorContract.CAPABILITY_ID),
                selectors,
                VerifiedNativeEditBeginHookInstallerTest.class.getClassLoader());
    }

    private static Instrumentation instrumentation() {
        return instrumentation(true);
    }

    private static Instrumentation instrumentation(final boolean retransformSupported) {
        return (Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                VerifiedNativeEditBeginHookInstallerTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isRetransformClassesSupported" -> retransformSupported;
                    case "getAllLoadedClasses" -> new Class<?>[0];
                    case "isModifiableClass" -> false;
                    case "toString" -> "InstrumentationDouble";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
