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
            boolean[] triangleSurface = {false};
            new ClassReader(bytes(jar, "com/live2d/graphics3d/a/d"))
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    if (name.equals("a") && descriptor.equals("(FFFFFFFFFLcom/live2d/type/CColor;)V"))
                                        triangleSurface[0] = true;
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE);
            assertTrue(triangleSurface[0], version.getKey() + " native filled triangle surface");
            final List<String[]> targets = List.of(
                    new String[] {profile.artMeshMoveOwner(), "a", profile.pointMoveDescriptor(), "move"},
                    new String[] {
                        profile.inputIngressOwner(),
                        "renderScene_exe",
                        "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
                        "draw"
                    },
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
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        patched,
                        transformer.transform(
                                null, getClass().getClassLoader(), target[0], Object.class, null, original),
                        label + " must survive retransformation by another optimization");
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
                                                                "dev/turboism/adapter/cubism/warpalt/NativeWarpAltMirrorBridge")
                                                        || owner.equals(
                                                                "dev/turboism/adapter/cubism/warpalt/NativeArtMeshSymmetryBridge"))
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

    @org.junit.jupiter.api.Test
    void actualArtMeshRefsMirrorWeightedMotionIncludingNonlinearParentCoordinates() throws Exception {
        String root = System.getProperty("turboism.cubismRefRoot", "");
        assumeTrue(!root.isBlank());
        System.setProperty("java.awt.headless", "true");
        for (String version : List.of("5.2.03", "5.3.02", "5.3.04")) {
            java.net.URL[] urls;
            try (var paths = java.nio.file.Files.list(Path.of(root, version))) {
                urls = paths.filter(path -> path.toString().endsWith(".jar"))
                        .map(path -> {
                            try {
                                return path.toUri().toURL();
                            } catch (java.net.MalformedURLException failure) {
                                throw new IllegalArgumentException(failure);
                            }
                        })
                        .toArray(java.net.URL[]::new);
            }
            try (var loader = new java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                verifyCalculatedMeshAxis(loader);
                for (boolean nonlinear : List.of(false, true)) verifyActualMeshMove(loader, nonlinear);
            }
        }
    }

    private static void verifyCalculatedMeshAxis(ClassLoader loader) throws Exception {
        Class<?> formType = loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm");
        Object first = formType.getConstructor().newInstance();
        Object second = formType.getConstructor().newInstance();
        formType.getMethod("setPositions", float[].class).invoke(first, (Object) new float[] {400, 500, 450, 550});
        formType.getMethod("setPositions", float[].class).invoke(second, (Object) new float[] {500, 550, 600, 600});
        var axis = NativeArtMeshSymmetryBridge.axisForCalculatedForms(List.of(first, second), 1);
        assertEquals(new ArtMeshMirrorAxis.Point(500, 550), axis.pivot());
        assertEquals(new ArtMeshMirrorAxis.Point(500, 560), axis.reflect(new ArtMeshMirrorAxis.Point(500, 540)));
    }

    private static void verifyActualMeshMove(ClassLoader loader, boolean nonlinear) throws Exception {
        Class<?> sourceType = loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource");
        Class<?> formType = loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm");
        Class<?> meshType = loader.loadClass("com.live2d.graphics3d.editableMesh.GEditableMesh2");
        Class<?> coordType = loader.loadClass("com.live2d.doc.CoordType");
        Class<?> vectorType = loader.loadClass("com.live2d.graphics3d.type.GVector2");
        Class<?> baseType = loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRef");
        Class<?> exType = loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRefEx");
        Class<?> transformType = loader.loadClass("com.live2d.doc.selection.ITransformBetweenLocalAndCanvas");
        float[] positions = {-1, 0, 1, 0, 0, 2};
        Object source = sourceType.getConstructor().newInstance();
        Object form = formType.getConstructor().newInstance();
        formType.getMethod("setPositions", float[].class).invoke(form, (Object) positions);
        Object mesh = meshType.getConstructor().newInstance();
        Object coord = coordType.getConstructor().newInstance();
        meshType.getMethod("initByIndices", float[].class, int[].class, coordType)
                .invoke(mesh, positions, new int[] {0, 1, 2}, coord);
        sourceType.getMethod("setEditableMesh", meshType).invoke(source, mesh);
        Object transform = nonlinear
                ? java.lang.reflect.Proxy.newProxyInstance(
                        loader, new Class<?>[] {transformType}, (proxy, method, args) -> {
                            if (method.getName().equals("transformLocalToCanvas")
                                    || method.getName().equals("transformCanvasToLocal")) {
                                float x = ((Number) vectorType.getMethod("getX").invoke(args[0])).floatValue();
                                float y = ((Number) vectorType.getMethod("getY").invoke(args[0])).floatValue();
                                boolean forward = method.getName().equals("transformLocalToCanvas");
                                return vectorType
                                        .getConstructor(float.class, float.class)
                                        .newInstance(
                                                forward ? x * x * x : (float) Math.cbrt(x), forward ? y * 2 : y / 2);
                            }
                            throw new UnsupportedOperationException(method.getName());
                        })
                : null;
        var baseConstructor =
                baseType.getConstructor(sourceType, int.class, float[].class, int.class, coordType, formType);
        Object base = baseConstructor.newInstance(source, 0, positions, 0, coord, form);
        Object ref = exType.getConstructor(baseType, transformType, formType).newInstance(base, transform, form);
        NativeWarpAltMirrorBridge.install();
        var participation = NativeArtMeshSymmetryBridge.service().participate();
        try {
            NativeArtMeshSymmetryBridge.service().setEnabled(true);
            Class<?> stateType = Class.forName(NativeArtMeshSymmetryBridge.class.getName() + "$State");
            var constructor = stateType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object state = constructor.newInstance();
            var axis = stateType.getDeclaredField("axis");
            axis.setAccessible(true);
            axis.set(state, new ArtMeshMirrorAxis(new ArtMeshMirrorAxis.Point(0, 0), false));
            var gesture = stateType.getDeclaredField("vertexGesture");
            gesture.setAccessible(true);
            gesture.setBoolean(state, true);
            var current = NativeArtMeshSymmetryBridge.class.getDeclaredField("CURRENT");
            current.setAccessible(true);
            @SuppressWarnings("unchecked")
            var thread = (ThreadLocal<Object>) current.get(null);
            thread.set(state);
            Object target = vectorType
                    .getConstructor(float.class, float.class)
                    .newInstance(nonlinear ? -8f : -3f, nonlinear ? 8f : 4f);
            com.live2d.cubism.view.context.action.U$b.writeArtMesh(ref, target, .5f);
            assertEquals(nonlinear ? -1.5f : -2f, positions[0], 1e-5);
            assertEquals(2f, positions[1], 1e-5);
            assertEquals(nonlinear ? 1.5f : 2f, positions[2], 1e-5);
            assertEquals(2f, positions[3], 1e-5);
            // If both sides are selected, the mirrored counterpart must not move a second time.
            Object other = exType.getConstructor(baseType, transformType, formType)
                    .newInstance(baseConstructor.newInstance(source, 1, positions, 0, coord, form), transform, form);
            com.live2d.cubism.view.context.action.U$b.writeArtMesh(other, target, .5f);
            assertEquals(nonlinear ? 1.5f : 2f, positions[2], 1e-5);
            assertEquals(2f, positions[3], 1e-5);
        } finally {
            participation.close();
            NativeWarpAltMirrorBridge.uninstall();
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
