package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.lifecycle.ParameterNativeMethodTransformer;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHostProfile;
import dev.turboism.ui.appearance.control.ParameterRowMethodTransformer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Uses original host bytes, so obfuscation drift cannot hide behind generated fixtures. */
final class ParameterLockReviewedArtifactsTest {
    private static final String OPERATION = "cubism.editor-model.parameter-operation.set-value";
    private static final String SOURCE_ID = "cubism.editor-model.parameter-source.id";
    private static final String ID_VALUE = "cubism.editor-model.id.value";
    private static final String BRIDGE = "dev/turboism/adapter/cubism/lifecycle/NativeParameterLifecycleBridge";

    @TestFactory
    Stream<DynamicTest> everyReviewedVersionBindsParameterLockHooks() {
        final String root = System.getProperty("turboism.cubismRefRoot", "");
        assumeTrue(!root.isBlank(), "Run :runtime:parameterLockArtifactTest with TURBOISM_CUBISM_REF_ROOT set");
        return ReviewedHostArtifacts.all().stream().map(artifact -> {
            final String version =
                    ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow();
            return DynamicTest.dynamicTest(version, () -> verifyVersion(Path.of(root), version));
        });
    }

    private void verifyVersion(final Path root, final String version) throws Exception {
        final Path artifact = root.resolve(version).resolve("Live2D_Cubism.jar");
        final Path recordPath = EditorSelectorContractTestPaths.projectRoot()
                .resolve("compatibility/cubism/verification/cubism-" + version + "-editor-model.json");
        final StaticVerificationRecord record =
                new StaticVerificationRecordLoader().load(recordPath).record();
        assertEquals(version, record.cubismVersion());
        assertTrue(new StaticVerificationCli().verify(recordPath, artifact).allSelectorsVerified());
        final StaticSelector operation = selector(record, OPERATION);
        final StaticSelector sourceId = selector(record, SOURCE_ID);
        final StaticSelector idValue = selector(record, ID_VALUE);
        final ClassLoader loader = getClass().getClassLoader();
        final var transformer = new ParameterNativeMethodTransformer(
                operation.ownerInternalName(),
                operation.memberName(),
                operation.descriptor(),
                loader,
                sourceId.ownerInternalName(),
                sourceId.memberName(),
                sourceId.descriptor(),
                idValue.ownerInternalName(),
                idValue.memberName(),
                idValue.descriptor());
        try (var jar = new JarFile(artifact.toFile())) {
            final byte[] original = bytes(jar, operation.ownerInternalName());
            assertNull(transformer.transform(null, null, operation.ownerInternalName(), null, null, original));
            final byte[] patched =
                    transformer.transform(null, loader, operation.ownerInternalName(), null, null, original);
            assertNotNull(patched, version + " parameter operation must transform");
            final Set<String> calls = methodCalls(patched, operation.memberName(), operation.descriptor());
            assertTrue(calls.contains(call(sourceId)), version + " must use its own source-id selector");
            assertTrue(calls.contains(call(idValue)), version + " must use its own id-value selector");
            assertTrue(calls.contains(BRIDGE + ".beforeNative(Ljava/lang/String;F)F"));
            assertTrue(calls.contains(BRIDGE + ".afterNative()V"));
            assertTrue(calls.contains(BRIDGE + ".failedNative()V"));
            final var profile =
                    ProjectLifecycleHostProfile.forReviewedVersion(version).orElseThrow();
            for (var binding : profile.bindings()) {
                final Set<String> methods = declaredMethods(bytes(jar, binding.ownerInternalName()));
                assertTrue(
                        methods.contains(binding.methodName() + binding.descriptor()),
                        version + " lifecycle " + binding);
            }
            verifyPaletteRows(
                    jar, recordPath.resolveSibling("cubism-" + version + "-ui-control-appearance.json"), artifact);
        }
    }

    private void verifyPaletteRows(final JarFile jar, final Path recordPath, final Path artifact) throws Exception {
        final StaticVerificationRecord record =
                new StaticVerificationRecordLoader().load(recordPath).record();
        assertTrue(new StaticVerificationCli().verify(recordPath, artifact).allSelectorsVerified());
        final String prefix = "cubism.ui-control-appearance.parameter.";
        for (String shape : new String[] {"single", "double", "folder"}) {
            final boolean folder = shape.equals("folder");
            final Set<StaticSelector> selectors = new HashSet<>();
            selectors.add(selector(record, prefix + shape + "-selection"));
            if (folder) {
                selectors.add(selector(record, prefix + "folder-create-primary"));
                selectors.add(selector(record, prefix + "folder-create-secondary"));
            } else {
                selectors.add(selector(record, prefix + shape + "-create"));
            }
            final String owner = selector(record, prefix + shape + "-class").ownerInternalName();
            final Set<ParameterRowMethodTransformer.MethodSelector> methods = new HashSet<>();
            for (var selector : selectors) {
                assertEquals(owner, selector.ownerInternalName());
                methods.add(
                        new ParameterRowMethodTransformer.MethodSelector(selector.memberName(), selector.descriptor()));
            }
            final var transformer = new ParameterRowMethodTransformer(
                    owner, methods, folder, getClass().getClassLoader());
            final byte[] original = bytes(jar, owner);
            assertNull(transformer.transform(null, null, owner, null, null, original));
            final byte[] patched =
                    transformer.transform(null, getClass().getClassLoader(), owner, null, null, original);
            assertNotNull(patched, record.cubismVersion() + " " + shape + " rows must transform");
            final String callback = folder ? "afterParameterFolder" : "afterParameterRow";
            for (var method : methods) {
                final Set<String> calls = methodCalls(patched, method.name(), method.descriptor());
                assertTrue(
                        calls.contains("dev/turboism/ui/appearance/control/NativeParameterAppearanceBridge." + callback
                                + "(Ljava/lang/Object;)V"),
                        record.cubismVersion() + " " + shape + " " + method);
                if (!folder && method.descriptor().equals("(Z)V")) {
                    assertTrue(calls.contains("dev/turboism/ui/appearance/control/NativeParameterAppearanceBridge."
                            + "afterParameterSelection(Ljava/lang/Object;Z)V"));
                }
            }
        }
    }

    private static StaticSelector selector(final StaticVerificationRecord record, final String alias) {
        return record.selectors().stream()
                .filter(s -> s.alias().equals(alias))
                .findFirst()
                .orElseThrow();
    }

    private static String call(final StaticSelector selector) {
        return selector.ownerInternalName() + "." + selector.memberName() + selector.descriptor();
    }

    private static byte[] bytes(final JarFile jar, final String owner) throws Exception {
        final var entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry, owner);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static Set<String> declaredMethods(final byte[] bytes) {
        final Set<String> methods = new HashSet<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                methods.add(name + descriptor);
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static Set<String> methodCalls(final byte[] bytes, final String target, final String targetDescriptor) {
        final Set<String> calls = new HashSet<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (!name.equals(target) || !descriptor.equals(targetDescriptor)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String name,
                                            String descriptor,
                                            boolean isInterface) {
                                        calls.add(owner + "." + name + descriptor);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }
}
