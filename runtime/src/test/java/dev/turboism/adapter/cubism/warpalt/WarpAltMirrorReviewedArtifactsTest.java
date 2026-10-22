package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Checks actual reviewed bytecode, rather than fixtures generated from the profile under test. */
final class WarpAltMirrorReviewedArtifactsTest {
    @TestFactory
    Stream<DynamicTest> everyReviewedVersionHasAllHookTargetsAndButtonSurfaces() {
        final String root = System.getProperty("turboism.cubismRefRoot", "");
        assumeTrue(!root.isBlank(), "Run :runtime:warpAltMirrorArtifactTest with TURBOISM_CUBISM_REF_ROOT set");
        return WarpAltMirrorHostProfile.reviewedClassSha256().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(version -> DynamicTest.dynamicTest(version.getKey(), () -> verifyVersion(root, version)));
    }

    private void verifyVersion(String root, Map.Entry<String, Map<String, String>> version) throws Exception {
        final var profile =
                WarpAltMirrorHostProfile.forReviewedVersion(version.getKey()).orElseThrow();
        try (var jar =
                new JarFile(Path.of(root, version.getKey(), "Live2D_Cubism.jar").toFile())) {
            for (var pin : version.getValue().entrySet()) {
                assertEquals(
                        pin.getValue(),
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(jar, pin.getKey()))),
                        version.getKey() + " pin " + pin.getKey());
            }
            final List<String[]> targets = List.of(
                    new String[] {
                        profile.pointMoveOwner(),
                        profile.pointMoveMethod(),
                        profile.pointMoveDescriptor(),
                        "mirrorPointMove"
                    },
                    new String[] {
                        profile.dragTickOwner(),
                        profile.dragTickMethod(),
                        profile.dragTickDescriptor(),
                        "mirrorWarpDragMove"
                    },
                    new String[] {
                        profile.stripOwner(),
                        profile.stripMountMethod(),
                        profile.stripMountDescriptor(),
                        "mountViewContextMenu"
                    },
                    new String[] {
                        profile.stripOwner(),
                        profile.stripLayoutMethod(),
                        profile.stripLayoutDescriptor(),
                        "positionStripButton"
                    },
                    new String[] {
                        profile.greenTickOwner(),
                        profile.greenTickMethod(),
                        profile.greenTickDescriptor(),
                        "mirrorGreenTick"
                    },
                    new String[] {
                        profile.weightWriteOwner(),
                        profile.weightAddMethod(),
                        profile.weightAddDescriptor(),
                        "mirrorWeightAdd"
                    },
                    new String[] {
                        profile.weightWriteOwner(),
                        profile.weightSetMethod(),
                        profile.weightSetDescriptor(),
                        "mirrorWeightSet"
                    },
                    new String[] {
                        profile.actionDispatchOwner(),
                        profile.actionDispatchMethod(),
                        profile.actionDispatchDescriptor(),
                        "diagMouseAction"
                    },
                    new String[] {
                        profile.actionDispatchOwner(),
                        profile.actionClaimMethod(),
                        profile.actionClaimDescriptor(),
                        "diagSetAction"
                    },
                    new String[] {
                        profile.inputIngressOwner(),
                        profile.inputIngressMethod(),
                        profile.inputIngressDescriptor(),
                        "diagInputEvent"
                    });
            for (var target : targets) {
                final byte[] original = bytes(jar, target[0]);
                final String label = version.getKey() + " " + target[0] + "." + target[1] + target[2];
                assertTrue(methods(original).contains(target[1] + target[2]), label);
                final var transformer = new WarpAltMirrorNativeMethodTransformer(
                        profile, getClass().getClassLoader(), null, version.getValue(), ignored -> {});
                final byte[] patched =
                        transformer.transform(null, getClass().getClassLoader(), target[0], null, null, original);
                assertNotNull(patched, label + " must transform");
                final Set<String> calls = new HashSet<>();
                new ClassReader(patched)
                        .accept(
                                new ClassVisitor(Opcodes.ASM9) {
                                    @Override
                                    public MethodVisitor visitMethod(
                                            int access,
                                            String name,
                                            String descriptor,
                                            String signature,
                                            String[] exceptions) {
                                        if (!(name + descriptor).equals(target[1] + target[2])) return null;
                                        return new MethodVisitor(Opcodes.ASM9) {
                                            @Override
                                            public void visitMethodInsn(
                                                    int opcode,
                                                    String owner,
                                                    String name,
                                                    String descriptor,
                                                    boolean isInterface) {
                                                if (owner.equals(
                                                        "dev/turboism/adapter/cubism/warpalt/NativeWarpAltMirrorBridge"))
                                                    calls.add(name);
                                            }
                                        };
                                    }
                                },
                                0);
                assertTrue(calls.contains(target[3]), label + " missing bridge call " + target[3]);
            }
            final byte[] strip = bytes(jar, profile.stripOwner());
            final Set<String> fields = new HashSet<>();
            new ClassReader(strip)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public FieldVisitor visitField(
                                        int access, String name, String descriptor, String signature, Object value) {
                                    fields.add(name + descriptor);
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE);
            assertTrue(fields.contains("HLjava/util/ArrayList;"), version.getKey() + " left toolbar group");
            assertTrue(
                    methods(strip)
                            .contains("a(L" + profile.stripOwner()
                                    + ";Ljava/lang/String;Lcom/live2d/type/CFont;Lcom/live2d/graphics3d/type/GRectF;ZZ"
                                    + "Lcom/live2d/cubism/view/context/guiEntity/q;ILjava/lang/Object;)"
                                    + "Lcom/live2d/cubism/view/context/guiEntity/GToggleIconButtonEntity;"),
                    version.getKey() + " button factory");
            assertTrue(
                    methods(strip).stream()
                            .anyMatch(m -> m.endsWith("()Lcom/live2d/graphics3d/sceneGraph/GSceneGraph;")),
                    version.getKey() + " scene graph accessor");
        }
    }

    private static byte[] bytes(JarFile jar, String owner) throws Exception {
        final var entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry, owner);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static Set<String> methods(byte[] bytes) {
        final Set<String> result = new HashSet<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                result.add(name + descriptor);
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE);
        return result;
    }
}
