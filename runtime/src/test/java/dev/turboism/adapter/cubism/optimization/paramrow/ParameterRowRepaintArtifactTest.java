package dev.turboism.adapter.cubism.optimization.paramrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.awt.Color;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Exact-artifact admission, fail-closed rejection, and state-change elision tests for
 * {@link ParameterRowRepaintTransformer} and {@link ParameterRowRepaintBridge}.
 */
class ParameterRowRepaintArtifactTest {

    private static final int EXPECTED_MATCHES = 1;
    private static final int MUTATION_OPCODE = Opcodes.NOP;
    private static final int JAR_WALK_DEPTH = 2;
    private static final int SIBLING_ROW_COUNT = 8;
    private static final float INITIAL_PARAM_VALUE = 0.0f;
    private static final float UPDATED_PARAM_VALUE = 15.0f;
    private static final float UPDATED_MAX_VALUE = 45.0f;
    private static final float INITIAL_MIN_VALUE = -30.0f;
    private static final float INITIAL_MAX_VALUE = 30.0f;
    private static final float KEY_POINT_ZERO = 0.0f;
    private static final float KEY_POINT_ONE = 1.0f;
    private static final float MORPH_KEY_HALF = 0.5f;
    private static final int SHIFTED_BOUNDS_Y = 24;
    private static final int DEFAULT_WIDTH = 200;
    private static final int DEFAULT_HEIGHT = 22;
    private static final int HOVER_RGB = 0xFF334455;
    private static final int ALT_VIS_RGB = 0x80AABBCC;

    private static final String TEST_SYNTHETIC_CONTROLLER =
            "com/live2d/cubism/view/palette/parameter/s";
    private static final String TEST_HIGHLIGHT_FIELD = "y";
    private static final String TEST_SHOW_KEYFORMS_METHOD = "o";
    private static final String TEST_CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String TEST_CLASS_SUFFIX = ".class";
    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String STAT_ELIDED_ROWS = "elidedRows";
    private static final String STAT_BASELINE_PASSES = "baselinePasses";
    private static final String STAT_INCREMENTAL_PASSES = "incrementalPasses";
    private static final String STAT_INVALIDATIONS = "invalidations";
    private static final String STAT_FAILURES = "failures";
    private static final String STAT_RESIZED_DOUBLES = "resizedDoubles";

    @Test
    void everyConfiguredReviewedArtifactRewritesVerifiesAndResolvesBridge() throws Exception {
        final Path root = referenceRoot();
        int rewritten = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve(TEST_CUBISM_JAR);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final ParameterRowRepaintTarget target = ParameterRowRepaintTarget.of(digest).orElseThrow();
            assertEquals(version, target.version());

            try (URLClassLoader loader = loader(artifact.getParent())) {
                final Class<?> controllerClass = Class.forName(
                        target.controllerOwner().replace('/', '.'), false, loader);
                for (final ParameterRowRepaintTarget.MethodDep dep : target.methodDependencies()) {
                    verifyMethodPresent(
                            Class.forName(dep.owner().replace('/', '.'), false, loader),
                            dep.name(),
                            dep.descriptor());
                }
                for (final ParameterRowRepaintTarget.FieldDep dep : target.fieldDependencies()) {
                    verifyFieldPresent(
                            Class.forName(dep.owner().replace('/', '.'), false, loader),
                            dep.name(),
                            dep.descriptor());
                }

                final byte[] reference = bytes(artifact, target.controllerOwner());
                final ParameterRowRepaintTransformer transformer =
                        new ParameterRowRepaintTransformer(loader, artifact, reference, target);
                final ProtectionDomain domain = domain(artifact);
                final byte[] transformed = transformer.transform(
                        null, loader, target.controllerOwner(), controllerClass, domain, reference);
                assertNotNull(transformed, version + ": " + transformer.failure());
                assertEquals(EXPECTED_MATCHES, transformer.matches(), version);
                assertEquals(ParameterRowRepaintTransformer.EXPECTED_SITES, transformer.sites(), version);
                assertNull(transformer.failure(), version);

                // Verify frame verification passes in an isolated child loader before bridge resolution.
                final InMemoryDefiningLoader frameCheck = new InMemoryDefiningLoader(
                        loader,
                        Map.of(target.controllerOwner().replace('/', '.'), transformed));
                final Class<?> checked =
                        Class.forName(target.controllerOwner().replace('/', '.'), false, frameCheck);
                assertNotNull(checked.getDeclaredMethods(), version);

                assertFailClosedRejections(loader, artifact, reference, target, domain);

                try (ParameterRowRepaintBridge bridge = new ParameterRowRepaintBridge(loader, target)) {
                    assertNotNull(bridge.snapshot(), version);
                }
            }
            rewritten++;
            System.out.println("PARAM_ROW_REPAINT artifact PASS version=" + version);
        }
        assertTrue(rewritten > 0, "no reviewed exact artifact found under " + root);
    }

    @Test
    @SuppressWarnings("unchecked")
    void incrementalGuardElidesUnchangedRowsAndDetectsEveryVisualChange() throws Exception {
        final Map<String, byte[]> stubBytecode = buildSyntheticHostClasses();
        final InMemoryDefiningLoader syntheticLoader =
                new InMemoryDefiningLoader(getClass().getClassLoader(), stubBytecode);

        final ParameterRowRepaintTarget target = new ParameterRowRepaintTarget(
                ReviewedHostArtifacts.CUBISM_5_3_04,
                ReviewedHostArtifacts.CUBISM_5_3_04_VERSION,
                TEST_SYNTHETIC_CONTROLLER,
                TEST_HIGHLIGHT_FIELD,
                TEST_SHOW_KEYFORMS_METHOD);

        try (ParameterRowRepaintBridge bridge = new ParameterRowRepaintBridge(syntheticLoader, target)) {
            bridge.install();
            final Predicate<Object[]> selectionUpdate = (Predicate<Object[]>)
                    System.getProperties().get(ParameterRowRepaintBridge.SELECTION_UPDATE_PROPERTY);
            final Consumer<Object> invalidate = (Consumer<Object>)
                    System.getProperties().get(ParameterRowRepaintBridge.INVALIDATE_PROPERTY);
            assertNotNull(selectionUpdate);
            assertNotNull(invalidate);

            final SyntheticPaletteFixture fixture = SyntheticPaletteFixture.create(syntheticLoader);
            final Object[] args = {fixture.controller, fixture.doc, fixture.model};

            // 1. Cold baseline updates all rows, double-row layout, and group header once.
            assertTrue(selectionUpdate.test(args));
            assertEquals(1L, bridge.snapshot().get(STAT_BASELINE_PASSES));
            assertEquals(0L, bridge.snapshot().get(STAT_FAILURES));
            assertEquals(1, fixture.doubleResizeCount());
            assertEquals(1, fixture.groupRepaintCount());
            assertAllRowsRepaintedOnce(fixture);
            fixture.clearCounters();

            // 2. Completely unchanged frame elides every row, double resize, and folder repaint.
            assertTrue(selectionUpdate.test(args));
            assertEquals(1L, bridge.snapshot().get(STAT_INCREMENTAL_PASSES));
            assertEquals(SIBLING_ROW_COUNT + 2L, bridge.snapshot().get(STAT_ELIDED_ROWS));
            assertEquals(0, fixture.totalRepaints());
            assertEquals(0, fixture.doubleResizeCount());
            assertEquals(0, fixture.groupRepaintCount());
            assertEquals(0, fixture.selectedObjectsUpdates());

            // 3. Updating 1 parameter syncs and repaints only that single row.
            fixture.setParamValue(0, UPDATED_PARAM_VALUE);
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.rowRepaints(0));
            assertEquals(1, fixture.totalRepaints());
            assertEquals(0, fixture.groupRepaintCount());
            assertEquals(0, fixture.doubleResizeCount());
            fixture.clearCounters();

            // 4. Changing parameter min/max metadata repaints only that affected row.
            fixture.setParamMax(1, UPDATED_MAX_VALUE);
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.rowRepaints(1));
            assertEquals(1, fixture.totalRepaints());
            fixture.clearCounters();

            // 5. Adding a secondary keyform triggers DoubleParameterRowWidget.resize() + repaint only on double row.
            fixture.addSecondaryKeyform(KEY_POINT_ZERO, KEY_POINT_ONE);
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.doubleResizeCount());
            assertEquals(1, fixture.doubleRowRepaints());
            assertEquals(0, fixture.rowRepaints(0));
            fixture.clearCounters();

            // 6. Morph-target change triggers resize and repaint on affected double row without full palette repaint.
            fixture.setSecondaryMorphKey(MORPH_KEY_HALF);
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.doubleResizeCount());
            assertEquals(1, fixture.doubleRowRepaints());
            fixture.clearCounters();

            // 7. Highlight clear marks only the targeted parameter row dirty.
            fixture.setHighlightToRow(2);
            assertTrue(selectionUpdate.test(args));
            assertNull(fixture.getHighlightField());
            assertEquals(1, fixture.rowRepaints(2));
            assertEquals(1, fixture.totalRepaints());
            fixture.clearCounters();

            // 8. Canvas selection change updates selectedObjects$cubism and repaints parameter rows, not group.
            fixture.toggleSelectedObject();
            assertTrue(selectionUpdate.test(args));
            assertTrue(fixture.selectedObjectsUpdates() >= SIBLING_ROW_COUNT);
            assertEquals(0, fixture.groupRepaintCount());
            fixture.clearCounters();

            // 9. Lock state toggle on one row repaints only that row.
            fixture.toggleLock(3);
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.rowRepaints(3));
            assertEquals(1, fixture.totalRepaints());
            fixture.clearCounters();

            // 10. Bounds / hover background shift on one row repaints only that row.
            fixture.shiftRowBoundsAndBg(4, SHIFTED_BOUNDS_Y, HOVER_RGB);
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.rowRepaints(4));
            assertEquals(1, fixture.totalRepaints());
            fixture.clearCounters();

            // 11. Parameter visibility color change repaints affected row; group visibility repaints group only.
            fixture.setVisibilityColor(5, ALT_VIS_RGB);
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.rowRepaints(5));
            assertEquals(1, fixture.totalRepaints());
            fixture.clearCounters();

            fixture.toggleGroupExpanded();
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.groupRepaintCount());
            assertEquals(1, fixture.totalRepaints());
            fixture.clearCounters();

            // 12. Show-keyforms flag toggle triggers resize on double rows and repaint on parameter rows.
            fixture.toggleShowKeyforms();
            assertTrue(selectionUpdate.test(args));
            assertEquals(1, fixture.doubleResizeCount());
            assertEquals(0, fixture.groupRepaintCount());
            fixture.clearCounters();

            // 13. Structure invalidation resets controller baseline so the next pass rebuilds state cleanly.
            invalidate.accept(fixture.controller);
            assertEquals(1L, bridge.snapshot().get(STAT_INVALIDATIONS));
            assertTrue(selectionUpdate.test(args));
            assertEquals(2L, bridge.snapshot().get(STAT_BASELINE_PASSES));
            assertFalse(bridge.snapshot().get(STAT_RESIZED_DOUBLES) == 0L);
        }
    }

    private static void assertAllRowsRepaintedOnce(final SyntheticPaletteFixture fixture)
            throws Exception {
        for (int i = 0; i < SIBLING_ROW_COUNT; i++) {
            assertEquals(1, fixture.rowRepaints(i), "row " + i);
        }
        assertTrue(fixture.doubleRowRepaints() >= 1);
    }

    private static void assertFailClosedRejections(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final ParameterRowRepaintTarget target,
            final ProtectionDomain domain)
            throws Exception {
        final ParameterRowRepaintTransformer wrongLoader =
                new ParameterRowRepaintTransformer(loader, artifact, reference, target);
        assertNull(wrongLoader.transform(
                null, ClassLoader.getPlatformClassLoader(), target.controllerOwner(), null, domain, reference));
        assertNotNull(wrongLoader.failure());

        final ProtectionDomain wrongDomain = domain(artifact.getParent().resolve("wrong.jar"));
        final ParameterRowRepaintTransformer wrongCodeSource =
                new ParameterRowRepaintTransformer(loader, artifact, reference, target);
        assertNull(wrongCodeSource.transform(
                null, loader, target.controllerOwner(), null, wrongDomain, reference));
        assertNotNull(wrongCodeSource.failure());

        final byte[] mutated = prependNopInUpdateMethod(reference, target);
        final ParameterRowRepaintTransformer mutatedShape =
                new ParameterRowRepaintTransformer(loader, artifact, reference, target);
        assertNull(mutatedShape.transform(
                null, loader, target.controllerOwner(), null, domain, mutated));
        assertNotNull(mutatedShape.failure());
    }

    private static byte[] prependNopInUpdateMethod(
            final byte[] reference, final ParameterRowRepaintTarget target) {
        final ClassReader reader = new ClassReader(reference);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!ParameterRowRepaintTarget.UPDATE_METHOD.equals(name)
                                || !ParameterRowRepaintTarget.UPDATE_DESCRIPTOR.equals(descriptor)) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitInsn(MUTATION_OPCODE);
                            }
                        };
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static void verifyMethodPresent(
            final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        if (matchesMethod(owner.getMethods(), name, descriptor)
                || matchesMethod(owner.getDeclaredMethods(), name, descriptor)) {
            return;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + descriptor);
    }

    private static boolean matchesMethod(
            final Method[] methods, final String name, final String descriptor) {
        for (final Method method : methods) {
            if (method.getName().equals(name)
                    && MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                            .descriptorString()
                            .equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static void verifyFieldPresent(
            final Class<?> owner, final String name, final String descriptor)
            throws NoSuchFieldException {
        for (final Field field : owner.getDeclaredFields()) {
            if (field.getName().equals(name)
                    && field.getType().descriptorString().equals(descriptor)) {
                return;
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name + ":" + descriptor);
    }

    private static URLClassLoader loader(final Path directory) throws Exception {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(directory, JAR_WALK_DEPTH)) {
            for (final Path path :
                    files.filter(path -> path.toString().endsWith(".jar")).toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return new ChildFirstHostClassLoader(
                urls.toArray(URL[]::new), ParameterRowRepaintArtifactTest.class.getClassLoader());
    }

    private static byte[] bytes(final Path artifact, final String owner) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(owner + TEST_CLASS_SUFFIX);
            assertNotNull(entry, owner + " absent from " + artifact);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ProtectionDomain domain(final Path artifact) throws Exception {
        return new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null), null);
    }

    private static Path referenceRoot() {
        final String configured =
                System.getProperty(REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    private static Map<String, byte[]> buildSyntheticHostClasses() {
        final Map<String, byte[]> out = new LinkedHashMap<>();

        defineSimpleClass(out, "com/live2d/type/CColor", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "awtColor", "Ljava/awt/Color;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getJColor", "()Ljava/awt/Color;", "com/live2d/type/CColor", "awtColor", "Ljava/awt/Color;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, "com/live2d/type/CParameterGuid", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "key", "Ljava/lang/String;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/id/CParameterId", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "id", "Ljava/lang/String;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/param/CParameterSource$Type", "java/lang/Object", cw ->
                addDefaultInit(cw, "java/lang/Object"));

        defineSimpleClass(out, "com/live2d/type/CArrayList", "java/util/ArrayList", cw ->
                addDefaultInit(cw, "java/util/ArrayList"));

        defineSimpleClass(out, "com/live2d/ui/CWidget", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "repaintCount", "I", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "visible", "Z", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "bgColor", "Lcom/live2d/type/CColor;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "jcomp", "Ljavax/swing/JComponent;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            final MethodVisitor repaint = cw.visitMethod(Opcodes.ACC_PUBLIC, "repaint", "()V", null, null);
            repaint.visitCode();
            repaint.visitVarInsn(Opcodes.ALOAD, 0);
            repaint.visitInsn(Opcodes.DUP);
            repaint.visitFieldInsn(Opcodes.GETFIELD, "com/live2d/ui/CWidget", "repaintCount", "I");
            repaint.visitInsn(Opcodes.ICONST_1);
            repaint.visitInsn(Opcodes.IADD);
            repaint.visitFieldInsn(Opcodes.PUTFIELD, "com/live2d/ui/CWidget", "repaintCount", "I");
            repaint.visitInsn(Opcodes.RETURN);
            repaint.visitMaxs(3, 1);
            repaint.visitEnd();
            addGetter(cw, "isVisible", "()Z", "com/live2d/ui/CWidget", "visible", "Z", Opcodes.IRETURN);
            addGetter(cw, "getBackgroundColor", "()Lcom/live2d/type/CColor;", "com/live2d/ui/CWidget", "bgColor", "Lcom/live2d/type/CColor;", Opcodes.ARETURN);
            addGetter(cw, "getJComponent", "()Ljavax/swing/JComponent;", "com/live2d/ui/CWidget", "jcomp", "Ljavax/swing/JComponent;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, "com/live2d/ui/control/CBlocker", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "blocked", "Z", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "isBlock", "()Z", "com/live2d/ui/control/CBlocker", "blocked", "Z", Opcodes.IRETURN);
        });

        defineSimpleClass(out, "com/live2d/ui/container/CFoldingPane", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "expand", "Z", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "isExpand", "()Z", "com/live2d/ui/container/CFoldingPane", "expand", "Z", Opcodes.IRETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "children", "Ljava/util/List;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getChildren", "()Ljava/util/List;", "com/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox", "children", "Ljava/util/List;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/param/CParameterSource", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "guid", "Lcom/live2d/type/CParameterGuid;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "id", "Lcom/live2d/cubism/doc/model/id/CParameterId;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "name", "Ljava/lang/String;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "minValue", "F", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "maxValue", "F", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "defaultValue", "F", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "repeat", "Z", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "combined", "Z", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "morphTarget", "Z", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "paramType", "Lcom/live2d/cubism/doc/model/param/CParameterSource$Type;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "visColor", "Lcom/live2d/type/CColor;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getGuid", "()Lcom/live2d/type/CParameterGuid;", "com/live2d/cubism/doc/model/param/CParameterSource", "guid", "Lcom/live2d/type/CParameterGuid;", Opcodes.ARETURN);
            addGetter(cw, "getId", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", "com/live2d/cubism/doc/model/param/CParameterSource", "id", "Lcom/live2d/cubism/doc/model/id/CParameterId;", Opcodes.ARETURN);
            addGetter(cw, "getName", "()Ljava/lang/String;", "com/live2d/cubism/doc/model/param/CParameterSource", "name", "Ljava/lang/String;", Opcodes.ARETURN);
            addGetter(cw, "getMinValue", "()F", "com/live2d/cubism/doc/model/param/CParameterSource", "minValue", "F", Opcodes.FRETURN);
            addGetter(cw, "getMaxValue", "()F", "com/live2d/cubism/doc/model/param/CParameterSource", "maxValue", "F", Opcodes.FRETURN);
            addGetter(cw, "getDefaultValue", "()F", "com/live2d/cubism/doc/model/param/CParameterSource", "defaultValue", "F", Opcodes.FRETURN);
            addGetter(cw, "isRepeat", "()Z", "com/live2d/cubism/doc/model/param/CParameterSource", "repeat", "Z", Opcodes.IRETURN);
            addGetter(cw, "getCombined", "()Z", "com/live2d/cubism/doc/model/param/CParameterSource", "combined", "Z", Opcodes.IRETURN);
            addGetter(cw, "isMorphTarget", "()Z", "com/live2d/cubism/doc/model/param/CParameterSource", "morphTarget", "Z", Opcodes.IRETURN);
            addGetter(cw, "getParamType", "()Lcom/live2d/cubism/doc/model/param/CParameterSource$Type;", "com/live2d/cubism/doc/model/param/CParameterSource", "paramType", "Lcom/live2d/cubism/doc/model/param/CParameterSource$Type;", Opcodes.ARETURN);
            addGetter(cw, "getVisibilityColorAlpha50", "()Lcom/live2d/type/CColor;", "com/live2d/cubism/doc/model/param/CParameterSource", "visColor", "Lcom/live2d/type/CColor;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/param/group/CParameterGroup", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "name", "Ljava/lang/String;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "visColor", "Lcom/live2d/type/CColor;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "folderOpened", "Z", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getName", "()Ljava/lang/String;", "com/live2d/cubism/doc/model/param/group/CParameterGroup", "name", "Ljava/lang/String;", Opcodes.ARETURN);
            addGetter(cw, "getVisibilityColor", "()Lcom/live2d/type/CColor;", "com/live2d/cubism/doc/model/param/group/CParameterGroup", "visColor", "Lcom/live2d/type/CColor;", Opcodes.ARETURN);
            addGetter(cw, "getFolderIsOpened", "()Z", "com/live2d/cubism/doc/model/param/group/CParameterGroup", "folderOpened", "Z", Opcodes.IRETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/param/CParameter", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "value", "F", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getValue", "()F", "com/live2d/cubism/doc/model/param/CParameter", "value", "F", Opcodes.FRETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/param/CParameterSet", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "map", "Ljava/util/Map;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            final MethodVisitor get = cw.visitMethod(
                    Opcodes.ACC_PUBLIC,
                    "get",
                    "(Lcom/live2d/cubism/doc/model/id/CParameterId;)Lcom/live2d/cubism/doc/model/param/CParameter;",
                    null,
                    null);
            get.visitCode();
            get.visitVarInsn(Opcodes.ALOAD, 0);
            get.visitFieldInsn(Opcodes.GETFIELD, "com/live2d/cubism/doc/model/param/CParameterSet", "map", "Ljava/util/Map;");
            get.visitVarInsn(Opcodes.ALOAD, 1);
            get.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            get.visitTypeInsn(Opcodes.CHECKCAST, "com/live2d/cubism/doc/model/param/CParameter");
            get.visitInsn(Opcodes.ARETURN);
            get.visitMaxs(2, 2);
            get.visitEnd();
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/interpolator/KeyformBindingSource", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "guid", "Lcom/live2d/type/CParameterGuid;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "keys", "Ljava/util/List;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getParameterGuid", "()Lcom/live2d/type/CParameterGuid;", "com/live2d/cubism/doc/model/interpolator/KeyformBindingSource", "guid", "Lcom/live2d/type/CParameterGuid;", Opcodes.ARETURN);
            addGetter(cw, "getKeys", "()Ljava/util/List;", "com/live2d/cubism/doc/model/interpolator/KeyformBindingSource", "keys", "Ljava/util/List;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/interpolator/KeyformGridSource", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "bindings", "Ljava/util/List;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getKeyformBindings", "()Ljava/util/List;", "com/live2d/cubism/doc/model/interpolator/KeyformGridSource", "bindings", "Ljava/util/List;", Opcodes.ARETURN);
        });

        defineInterface(out, "com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTarget", cw -> {
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getParameterGuid", "()Lcom/live2d/type/CParameterGuid;", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getKeyValue", "()Ljava/lang/Float;", null, null).visitEnd();
        });

        defineClassWithInterfaces(
                out,
                "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget",
                "java/lang/Object",
                new String[] {"com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTarget"},
                cw -> {
                    cw.visitField(Opcodes.ACC_PUBLIC, "guid", "Lcom/live2d/type/CParameterGuid;", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "keyValue", "Ljava/lang/Float;", null, null).visitEnd();
                    addDefaultInit(cw, "java/lang/Object");
                    addGetter(cw, "getParameterGuid", "()Lcom/live2d/type/CParameterGuid;", "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget", "guid", "Lcom/live2d/type/CParameterGuid;", Opcodes.ARETURN);
                    addGetter(cw, "getKeyValue", "()Ljava/lang/Float;", "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget", "keyValue", "Ljava/lang/Float;", Opcodes.ARETURN);
                });

        defineInterface(out, "com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTargetSet", cw ->
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getMorphTargets", "()Ljava/util/List;", null, null).visitEnd());

        defineClassWithInterfaces(
                out,
                "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet",
                "java/lang/Object",
                new String[] {"com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTargetSet"},
                cw -> {
                    cw.visitField(Opcodes.ACC_PUBLIC, "morphTargets", "Ljava/util/List;", null, null).visitEnd();
                    addDefaultInit(cw, "java/lang/Object");
                    addGetter(cw, "getMorphTargets", "()Ljava/util/List;", "com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet", "morphTargets", "Ljava/util/List;", Opcodes.ARETURN);
                });

        defineClassWithInterfaces(
                out,
                "com/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet",
                "java/lang/Object",
                new String[] {"com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTargetSet"},
                cw -> {
                    cw.visitField(Opcodes.ACC_PUBLIC, "morphTargets", "Ljava/util/List;", null, null).visitEnd();
                    addDefaultInit(cw, "java/lang/Object");
                    addGetter(cw, "getMorphTargets", "()Ljava/util/List;", "com/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet", "morphTargets", "Ljava/util/List;", Opcodes.ARETURN);
                });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/ACParameterControllableSource", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "grid", "Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "extGrid", "Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "morphSet", "Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "extMorphSet", "Lcom/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getKeyformGridSource", "()Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;", "com/live2d/cubism/doc/model/ACParameterControllableSource", "grid", "Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;", Opcodes.ARETURN);
            addGetter(cw, "getExtendedKeyformGridSourceIfAvailable", "()Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;", "com/live2d/cubism/doc/model/ACParameterControllableSource", "extGrid", "Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;", Opcodes.ARETURN);
            addGetter(cw, "getKeyformMorphTargetSet", "()Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet;", "com/live2d/cubism/doc/model/ACParameterControllableSource", "morphSet", "Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet;", Opcodes.ARETURN);
            addGetter(cw, "getExtendedKeyformMorphTargetSetIfAvailable", "()Lcom/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet;", "com/live2d/cubism/doc/model/ACParameterControllableSource", "extMorphSet", "Lcom/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/ACParameterControllable", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "source", "Lcom/live2d/cubism/doc/model/ACParameterControllableSource;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getSource", "()Lcom/live2d/cubism/doc/model/ACParameterControllableSource;", "com/live2d/cubism/doc/model/ACParameterControllable", "source", "Lcom/live2d/cubism/doc/model/ACParameterControllableSource;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/doc/model/StubControllable", "com/live2d/cubism/doc/model/ACParameterControllable", cw ->
                addDefaultInit(cw, "com/live2d/cubism/doc/model/ACParameterControllable"));

        defineSimpleClass(out, "com/live2d/cubism/doc/model/CModel", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "parameterSet", "Lcom/live2d/cubism/doc/model/param/CParameterSet;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "allObjects", "Ljava/util/List;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "getParameterSet", "()Lcom/live2d/cubism/doc/model/param/CParameterSet;", "com/live2d/cubism/doc/model/CModel", "parameterSet", "Lcom/live2d/cubism/doc/model/param/CParameterSet;", Opcodes.ARETURN);
            addGetter(cw, "getAllObjects", "()Ljava/util/List;", "com/live2d/cubism/doc/model/CModel", "allObjects", "Ljava/util/List;", Opcodes.ARETURN);
        });

        defineInterface(out, "com/live2d/cubism/view/palette/parameter/ui/B", cw -> {
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getParameter1_id", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getParam_currentValue", "()F", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "setParam_currentValue", "(F)V", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getParameter2_id", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getParamSec_currentValue", "()F", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "setParamSec_currentValue", "(F)V", null, null).visitEnd();
        });

        defineClassWithInterfaces(
                out,
                "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget",
                "com/live2d/ui/CWidget",
                new String[] {"com/live2d/cubism/view/palette/parameter/ui/B"},
                cw -> {
                    cw.visitField(Opcodes.ACC_PUBLIC, "parameter", "Lcom/live2d/cubism/doc/model/param/CParameterSource;", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "blocker", "Lcom/live2d/ui/control/CBlocker;", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "mainWidget", "Lcom/live2d/ui/CWidget;", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "currentVal", "F", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "selectedObjectsCalls", "I", null, null).visitEnd();
                    addDefaultInit(cw, "com/live2d/ui/CWidget");
                    addGetter(cw, "getParameter", "()Lcom/live2d/cubism/doc/model/param/CParameterSource;", "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget", "parameter", "Lcom/live2d/cubism/doc/model/param/CParameterSource;", Opcodes.ARETURN);
                    addGetter(cw, "getBlocker", "()Lcom/live2d/ui/control/CBlocker;", "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget", "blocker", "Lcom/live2d/ui/control/CBlocker;", Opcodes.ARETURN);
                    addGetter(cw, "getMainWidget", "()Lcom/live2d/ui/CWidget;", "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget", "mainWidget", "Lcom/live2d/ui/CWidget;", Opcodes.ARETURN);
                    addGetter(cw, "getParam_currentValue", "()F", "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget", "currentVal", "F", Opcodes.FRETURN);
                    addNullObjectMethod(cw, "getParameter2_id", "()Lcom/live2d/cubism/doc/model/id/CParameterId;");
                    addZeroFloatGetter(cw, "getParamSec_currentValue");
                    addVoidNoopMethod(cw, "setParamSec_currentValue", "(F)V");
                    addCounterVoidMethod(cw, "setSelectedObjects$cubism", "(Ljava/util/List;)V", "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget", "selectedObjectsCalls");

                    final MethodVisitor pid = cw.visitMethod(Opcodes.ACC_PUBLIC, "getParameter1_id", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", null, null);
                    pid.visitCode();
                    pid.visitVarInsn(Opcodes.ALOAD, 0);
                    pid.visitFieldInsn(Opcodes.GETFIELD, "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget", "parameter", "Lcom/live2d/cubism/doc/model/param/CParameterSource;");
                    pid.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/live2d/cubism/doc/model/param/CParameterSource", "getId", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", false);
                    pid.visitInsn(Opcodes.ARETURN);
                    pid.visitMaxs(1, 1);
                    pid.visitEnd();

                    final MethodVisitor setVal = cw.visitMethod(Opcodes.ACC_PUBLIC, "setParam_currentValue", "(F)V", null, null);
                    setVal.visitCode();
                    setVal.visitVarInsn(Opcodes.ALOAD, 0);
                    setVal.visitVarInsn(Opcodes.FLOAD, 1);
                    setVal.visitFieldInsn(Opcodes.PUTFIELD, "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget", "currentVal", "F");
                    setVal.visitVarInsn(Opcodes.ALOAD, 0);
                    setVal.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/live2d/ui/CWidget", "repaint", "()V", false);
                    setVal.visitInsn(Opcodes.RETURN);
                    setVal.visitMaxs(2, 2);
                    setVal.visitEnd();
                });

        defineClassWithInterfaces(
                out,
                "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget",
                "com/live2d/ui/CWidget",
                new String[] {"com/live2d/cubism/view/palette/parameter/ui/B"},
                cw -> {
                    cw.visitField(Opcodes.ACC_PUBLIC, "parameter", "Lcom/live2d/cubism/doc/model/param/CParameterSource;", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "parameterSec", "Lcom/live2d/cubism/doc/model/param/CParameterSource;", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "blocker", "Lcom/live2d/ui/control/CBlocker;", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "mainWidget", "Lcom/live2d/ui/CWidget;", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "combinedEnable", "Z", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "currentVal1", "F", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "currentVal2", "F", null, null).visitEnd();
                    cw.visitField(Opcodes.ACC_PUBLIC, "resizeCount", "I", null, null).visitEnd();
                    addDefaultInit(cw, "com/live2d/ui/CWidget");
                    addGetter(cw, "getParameter", "()Lcom/live2d/cubism/doc/model/param/CParameterSource;", "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "parameter", "Lcom/live2d/cubism/doc/model/param/CParameterSource;", Opcodes.ARETURN);
                    addGetter(cw, "getParameterSec", "()Lcom/live2d/cubism/doc/model/param/CParameterSource;", "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "parameterSec", "Lcom/live2d/cubism/doc/model/param/CParameterSource;", Opcodes.ARETURN);
                    addGetter(cw, "getBlocker", "()Lcom/live2d/ui/control/CBlocker;", "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "blocker", "Lcom/live2d/ui/control/CBlocker;", Opcodes.ARETURN);
                    addGetter(cw, "getMainWidget", "()Lcom/live2d/ui/CWidget;", "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "mainWidget", "Lcom/live2d/ui/CWidget;", Opcodes.ARETURN);
                    addGetter(cw, "getCombinedEnable", "()Z", "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "combinedEnable", "Z", Opcodes.IRETURN);
                    addGetter(cw, "getParam_currentValue", "()F", "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "currentVal1", "F", Opcodes.FRETURN);
                    addGetter(cw, "getParamSec_currentValue", "()F", "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "currentVal2", "F", Opcodes.FRETURN);
                    addCounterVoidMethod(cw, "resize", "()V", "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "resizeCount");

                    final MethodVisitor p1Id = cw.visitMethod(Opcodes.ACC_PUBLIC, "getParameter1_id", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", null, null);
                    p1Id.visitCode();
                    p1Id.visitVarInsn(Opcodes.ALOAD, 0);
                    p1Id.visitFieldInsn(Opcodes.GETFIELD, "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "parameter", "Lcom/live2d/cubism/doc/model/param/CParameterSource;");
                    p1Id.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/live2d/cubism/doc/model/param/CParameterSource", "getId", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", false);
                    p1Id.visitInsn(Opcodes.ARETURN);
                    p1Id.visitMaxs(1, 1);
                    p1Id.visitEnd();

                    final MethodVisitor p2Id = cw.visitMethod(Opcodes.ACC_PUBLIC, "getParameter2_id", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", null, null);
                    p2Id.visitCode();
                    p2Id.visitVarInsn(Opcodes.ALOAD, 0);
                    p2Id.visitFieldInsn(Opcodes.GETFIELD, "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "parameterSec", "Lcom/live2d/cubism/doc/model/param/CParameterSource;");
                    p2Id.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/live2d/cubism/doc/model/param/CParameterSource", "getId", "()Lcom/live2d/cubism/doc/model/id/CParameterId;", false);
                    p2Id.visitInsn(Opcodes.ARETURN);
                    p2Id.visitMaxs(1, 1);
                    p2Id.visitEnd();

                    final MethodVisitor setV1 = cw.visitMethod(Opcodes.ACC_PUBLIC, "setParam_currentValue", "(F)V", null, null);
                    setV1.visitCode();
                    setV1.visitVarInsn(Opcodes.ALOAD, 0);
                    setV1.visitVarInsn(Opcodes.FLOAD, 1);
                    setV1.visitFieldInsn(Opcodes.PUTFIELD, "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "currentVal1", "F");
                    setV1.visitVarInsn(Opcodes.ALOAD, 0);
                    setV1.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/live2d/ui/CWidget", "repaint", "()V", false);
                    setV1.visitInsn(Opcodes.RETURN);
                    setV1.visitMaxs(2, 2);
                    setV1.visitEnd();

                    final MethodVisitor setV2 = cw.visitMethod(Opcodes.ACC_PUBLIC, "setParamSec_currentValue", "(F)V", null, null);
                    setV2.visitCode();
                    setV2.visitVarInsn(Opcodes.ALOAD, 0);
                    setV2.visitVarInsn(Opcodes.FLOAD, 1);
                    setV2.visitFieldInsn(Opcodes.PUTFIELD, "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget", "currentVal2", "F");
                    setV2.visitVarInsn(Opcodes.ALOAD, 0);
                    setV2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/live2d/ui/CWidget", "repaint", "()V", false);
                    setV2.visitInsn(Opcodes.RETURN);
                    setV2.visitMaxs(2, 2);
                    setV2.visitEnd();
                });

        defineSimpleClass(out, "com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget", "com/live2d/ui/CWidget", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "parameterGroup", "Lcom/live2d/cubism/doc/model/param/group/CParameterGroup;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "foldingPane", "Lcom/live2d/ui/container/CFoldingPane;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "rowLayout", "Lcom/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "mainWidget", "Lcom/live2d/ui/CWidget;", null, null).visitEnd();
            addDefaultInit(cw, "com/live2d/ui/CWidget");
            addGetter(cw, "getParameterGroup", "()Lcom/live2d/cubism/doc/model/param/group/CParameterGroup;", "com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget", "parameterGroup", "Lcom/live2d/cubism/doc/model/param/group/CParameterGroup;", Opcodes.ARETURN);
            addGetter(cw, "getFoldingPane", "()Lcom/live2d/ui/container/CFoldingPane;", "com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget", "foldingPane", "Lcom/live2d/ui/container/CFoldingPane;", Opcodes.ARETURN);
            addGetter(cw, "getRowLayout", "()Lcom/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox;", "com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget", "rowLayout", "Lcom/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox;", Opcodes.ARETURN);
            addGetter(cw, "getMainWidget", "()Lcom/live2d/ui/CWidget;", "com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget", "mainWidget", "Lcom/live2d/ui/CWidget;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, "com/live2d/cubism/view/palette/parameter/s$b", "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "guid", "Lcom/live2d/type/CParameterGuid;", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "a", "()Lcom/live2d/type/CParameterGuid;", "com/live2d/cubism/view/palette/parameter/s$b", "guid", "Lcom/live2d/type/CParameterGuid;", Opcodes.ARETURN);
        });

        defineSimpleClass(out, TEST_SYNTHETIC_CONTROLLER, "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PRIVATE, "d", "Lcom/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE, "h", "Lcom/live2d/type/CArrayList;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE, "n", "Ljava/util/List;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE, "y", "Lcom/live2d/cubism/view/palette/parameter/s$b;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "selectedRows", "Ljava/util/List;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC, "showKeyforms", "Z", null, null).visitEnd();
            addDefaultInit(cw, "java/lang/Object");
            addGetter(cw, "g", "()Ljava/util/List;", TEST_SYNTHETIC_CONTROLLER, "selectedRows", "Ljava/util/List;", Opcodes.ARETURN);
            addGetter(cw, "o", "()Z", TEST_SYNTHETIC_CONTROLLER, "showKeyforms", "Z", Opcodes.IRETURN);
        });

        return out;
    }

    private static void defineSimpleClass(
            final Map<String, byte[]> out,
            final String internalName,
            final String superName,
            final Consumer<ClassWriter> customizer) {
        defineClassWithInterfaces(out, internalName, superName, null, customizer);
    }

    private static void defineInterface(
            final Map<String, byte[]> out, final String internalName, final Consumer<ClassWriter> customizer) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, internalName, null, "java/lang/Object", null);
        customizer.accept(cw);
        cw.visitEnd();
        out.put(internalName.replace('/', '.'), cw.toByteArray());
    }

    private static void defineClassWithInterfaces(
            final Map<String, byte[]> out,
            final String internalName,
            final String superName,
            final String[] interfaces,
            final Consumer<ClassWriter> customizer) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, interfaces);
        customizer.accept(cw);
        cw.visitEnd();
        out.put(internalName.replace('/', '.'), cw.toByteArray());
    }

    private static void addDefaultInit(final ClassWriter cw, final String superName) {
        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
    }

    private static void addGetter(
            final ClassWriter cw,
            final String methodName,
            final String methodDescriptor,
            final String owner,
            final String fieldName,
            final String fieldDescriptor,
            final int returnOpcode) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, methodDescriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, fieldName, fieldDescriptor);
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void addNullObjectMethod(
            final ClassWriter cw, final String methodName, final String descriptor) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void addZeroFloatGetter(final ClassWriter cw, final String methodName) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()F", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void addVoidNoopMethod(
            final ClassWriter cw, final String methodName, final String descriptor) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();
    }

    private static void addCounterVoidMethod(
            final ClassWriter cw,
            final String methodName,
            final String descriptor,
            final String owner,
            final String counterField) {
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, counterField, "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, counterField, "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 2);
        mv.visitEnd();
    }

    private static final class InMemoryDefiningLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        InMemoryDefiningLoader(final ClassLoader parent, final Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected synchronized Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                final byte[] raw = classes.get(name);
                if (raw != null) {
                    loaded = defineClass(name, raw, 0, raw.length);
                } else {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static final class ChildFirstHostClassLoader extends URLClassLoader {
        ChildFirstHostClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith("com.live2d.")) {
                    try {
                        loaded = findClass(name);
                    } catch (final ClassNotFoundException ignored) {
                        try {
                            loaded = super.loadClass(name, false);
                        } catch (final ClassNotFoundException absent) {
                            if (name.startsWith("org.jdom.")) {
                                loaded = defineStubClass(name);
                            } else {
                                throw absent;
                            }
                        }
                    }
                } else {
                    try {
                        loaded = super.loadClass(name, false);
                    } catch (final ClassNotFoundException absent) {
                        if (name.startsWith("org.jdom.")) {
                            loaded = defineStubClass(name);
                        } else {
                            throw absent;
                        }
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }

        private Class<?> defineStubClass(final String binaryName) {
            final ClassWriter cw = new ClassWriter(0);
            cw.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC,
                    binaryName.replace('.', '/'),
                    null,
                    "java/lang/Object",
                    null);
            cw.visitEnd();
            final byte[] raw = cw.toByteArray();
            return defineClass(binaryName, raw, 0, raw.length);
        }
    }

    private static final class SyntheticPaletteFixture {
        final ClassLoader loader;
        final Object controller;
        final Object doc = new Object();
        final Object model;
        final Object groupWidget;
        final Object foldingPane;
        final Object doubleWidget;
        final List<Object> singleWidgets = new ArrayList<>();
        final List<Object> paramSources = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        final List<Object> paramGuids = new ArrayList<>();
        final List<Object> blockers = new ArrayList<>();
        final List<JComponent> components = new ArrayList<>();
        final List<Object> bgColors = new ArrayList<>();
        final List<Object> visColors = new ArrayList<>();
        final List<Float> secKeys = new ArrayList<>();
        final Object secMorphTarget;
        final Object objOne;
        final List<Object> selectedObjects = new ArrayList<>();
        final Object colorClass;
        Object paramTypeEnum;

        private SyntheticPaletteFixture(
                final ClassLoader loader,
                final Object controller,
                final Object model,
                final Object groupWidget,
                final Object foldingPane,
                final Object doubleWidget,
                final Object secMorphTarget,
                final Object objOne) {
            this.loader = loader;
            this.controller = controller;
            this.model = model;
            this.groupWidget = groupWidget;
            this.foldingPane = foldingPane;
            this.doubleWidget = doubleWidget;
            this.secMorphTarget = secMorphTarget;
            this.objOne = objOne;
            this.colorClass = null;
        }

        static SyntheticPaletteFixture create(final ClassLoader loader) throws Exception {
            final Object typeInst = instantiate(loader, "com.live2d.cubism.doc.model.param.CParameterSource$Type");
            final Object paramSet = instantiate(loader, "com.live2d.cubism.doc.model.param.CParameterSet");
            final Map<Object, Object> paramMap = new HashMap<>();
            setField(paramSet, "map", paramMap);

            final List<Object> flatRows = instantiate(loader, "com.live2d.type.CArrayList");
            final List<Object> groupChildren = new ArrayList<>();
            final List<Object> rootChildren = new ArrayList<>();

            final Object controller = instantiate(loader, TEST_SYNTHETIC_CONTROLLER);
            final Object rootLayout = instantiate(loader, "com.live2d.cubism.view.palette.parameter.ui.RowLayoutVBox");
            setField(rootLayout, "children", rootChildren);
            setField(controller, "d", rootLayout);
            setField(controller, "h", flatRows);
            setField(controller, "selectedRows", new ArrayList<>());
            setField(controller, "showKeyforms", Boolean.TRUE);

            final Object groupWidget =
                    instantiate(loader, "com.live2d.cubism.view.palette.parameter.ui.ParameterGroupRowWidget");
            final Object foldingPane = instantiate(loader, "com.live2d.ui.container.CFoldingPane");
            setField(foldingPane, "expand", Boolean.TRUE);
            final Object groupData = instantiate(loader, "com.live2d.cubism.doc.model.param.group.CParameterGroup");
            setField(groupData, "name", "Folder");
            setField(groupData, "folderOpened", Boolean.TRUE);
            setField(groupData, "visColor", createColor(loader, Color.WHITE));
            final Object groupLayout = instantiate(loader, "com.live2d.cubism.view.palette.parameter.ui.RowLayoutVBox");
            setField(groupLayout, "children", groupChildren);
            setupWidgetVisuals(loader, groupWidget, Color.GRAY);
            setField(groupWidget, "parameterGroup", groupData);
            setField(groupWidget, "foldingPane", foldingPane);
            setField(groupWidget, "rowLayout", groupLayout);
            final Object groupMain = instantiate(loader, "com.live2d.ui.CWidget");
            setupWidgetVisuals(loader, groupMain, Color.GRAY);
            setField(groupWidget, "mainWidget", groupMain);
            rootChildren.add(groupWidget);

            final Object model = instantiate(loader, "com.live2d.cubism.doc.model.CModel");
            setField(model, "parameterSet", paramSet);

            final Object objOne = instantiate(loader, "com.live2d.cubism.doc.model.StubControllable");
            final Object ctrlSrc = instantiate(loader, "com.live2d.cubism.doc.model.ACParameterControllableSource");
            final Object grid = instantiate(loader, "com.live2d.cubism.doc.model.interpolator.KeyformGridSource");
            final List<Object> gridBindings = new ArrayList<>();
            setField(grid, "bindings", gridBindings);
            final Object morphSet = instantiate(loader, "com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTargetSet");
            final List<Object> morphTargets = new ArrayList<>();
            setField(morphSet, "morphTargets", morphTargets);
            setField(ctrlSrc, "grid", grid);
            setField(ctrlSrc, "morphSet", morphSet);
            setField(objOne, "source", ctrlSrc);
            setField(model, "allObjects", List.of(objOne));

            final Object secGuid = createGuid(loader, "P_SEC");
            final Object secSrc = createParamSource(loader, secGuid, "ParamSec", typeInst);
            final Object secParam = instantiate(loader, "com.live2d.cubism.doc.model.param.CParameter");
            setField(secParam, "value", INITIAL_PARAM_VALUE);
            paramMap.put(getField(secSrc, "id"), secParam);

            final Object dblPrimGuid = createGuid(loader, "P_DBL_PRIM");
            final Object dblPrimSrc = createParamSource(loader, dblPrimGuid, "ParamDblPrim", typeInst);
            final Object dblPrimParam = instantiate(loader, "com.live2d.cubism.doc.model.param.CParameter");
            setField(dblPrimParam, "value", INITIAL_PARAM_VALUE);
            paramMap.put(getField(dblPrimSrc, "id"), dblPrimParam);

            final Object secBinding = instantiate(loader, "com.live2d.cubism.doc.model.interpolator.KeyformBindingSource");
            setField(secBinding, "guid", secGuid);
            final List<Float> secKeys = new ArrayList<>();
            setField(secBinding, "keys", secKeys);
            gridBindings.add(secBinding);

            final Object secMorphTarget = instantiate(loader, "com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget");
            setField(secMorphTarget, "guid", secGuid);
            setField(secMorphTarget, "keyValue", null);
            morphTargets.add(secMorphTarget);

            final Object doubleWidget = instantiate(
                    loader, "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget".replace('/', '.'));
            setupWidgetVisuals(loader, doubleWidget, Color.GRAY);
            final Object dblMain = instantiate(loader, "com.live2d.ui.CWidget");
            setupWidgetVisuals(loader, dblMain, Color.GRAY);
            setField(doubleWidget, "mainWidget", dblMain);
            setField(doubleWidget, "parameter", dblPrimSrc);
            setField(doubleWidget, "parameterSec", secSrc);
            setField(doubleWidget, "blocker", instantiate(loader, "com.live2d.ui.control.CBlocker"));
            setField(doubleWidget, "combinedEnable", Boolean.TRUE);
            rootChildren.add(doubleWidget);
            flatRows.add(doubleWidget);

            final SyntheticPaletteFixture fixture = new SyntheticPaletteFixture(
                    loader, controller, model, groupWidget, foldingPane, doubleWidget, secMorphTarget, objOne);
            fixture.paramTypeEnum = typeInst;
            fixture.secKeys.addAll(secKeys);
            setField(secBinding, "keys", fixture.secKeys);

            for (int i = 0; i < SIBLING_ROW_COUNT; i++) {
                final Object guid = createGuid(loader, "P_" + i);
                final Object src = createParamSource(loader, guid, "Param" + i, typeInst);
                final Object param = instantiate(loader, "com.live2d.cubism.doc.model.param.CParameter");
                setField(param, "value", INITIAL_PARAM_VALUE);
                paramMap.put(getField(src, "id"), param);

                final Object row = instantiate(
                        loader, "com.live2d.cubism.view.palette.parameter.ui.SingleParameterRowWidget");
                final Object main = instantiate(loader, "com.live2d.ui.CWidget");
                final Object bgColor = createColor(loader, Color.GRAY);
                final JPanel panel = new JPanel();
                panel.setBounds(0, i * DEFAULT_HEIGHT, DEFAULT_WIDTH, DEFAULT_HEIGHT);
                setupWidgetWithFields(row, bgColor, panel);
                setupWidgetVisuals(loader, main, Color.GRAY);

                final Object blocker = instantiate(loader, "com.live2d.ui.control.CBlocker");
                setField(row, "parameter", src);
                setField(row, "blocker", blocker);
                setField(row, "mainWidget", main);

                if (i < SIBLING_ROW_COUNT / 2) {
                    groupChildren.add(row);
                } else {
                    rootChildren.add(row);
                }
                flatRows.add(row);

                fixture.singleWidgets.add(row);
                fixture.paramSources.add(src);
                fixture.params.add(param);
                fixture.paramGuids.add(guid);
                fixture.blockers.add(blocker);
                fixture.components.add(panel);
                fixture.bgColors.add(bgColor);
                fixture.visColors.add(getField(src, "visColor"));
            }

            fixture.selectedObjects.add(ctrlSrc);
            setField(controller, "n", fixture.selectedObjects);
            return fixture;
        }

        void setParamValue(final int index, final float value) throws Exception {
            setField(params.get(index), "value", value);
        }

        void setParamMax(final int index, final float maxValue) throws Exception {
            setField(paramSources.get(index), "maxValue", maxValue);
        }

        void addSecondaryKeyform(final float key1, final float key2) {
            secKeys.add(key1);
            secKeys.add(key2);
        }

        void setSecondaryMorphKey(final float value) throws Exception {
            setField(secMorphTarget, "keyValue", value);
        }

        void setHighlightToRow(final int index) throws Exception {
            final Object hl = instantiate(loader, "com.live2d.cubism.view.palette.parameter.s$b");
            setField(hl, "guid", paramGuids.get(index));
            setField(controller, "y", hl);
        }

        Object getHighlightField() throws Exception {
            return getField(controller, "y");
        }

        void toggleSelectedObject() {
            if (selectedObjects.isEmpty()) {
                selectedObjects.add(objOne);
            } else {
                selectedObjects.clear();
            }
        }

        void toggleLock(final int index) throws Exception {
            final Object blocker = blockers.get(index);
            final boolean current = (boolean) getField(blocker, "blocked");
            setField(blocker, "blocked", !current);
        }

        void shiftRowBoundsAndBg(final int index, final int y, final int rgb) throws Exception {
            components.get(index).setBounds(0, y, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            setField(bgColors.get(index), "awtColor", new Color(rgb, true));
        }

        void setVisibilityColor(final int index, final int rgb) throws Exception {
            setField(visColors.get(index), "awtColor", new Color(rgb, true));
        }

        void toggleGroupExpanded() throws Exception {
            final boolean expand = (boolean) getField(foldingPane, "expand");
            setField(foldingPane, "expand", !expand);
        }

        void toggleShowKeyforms() throws Exception {
            final boolean show = (boolean) getField(controller, "showKeyforms");
            setField(controller, "showKeyforms", !show);
        }

        int rowRepaints(final int index) throws Exception {
            return (int) getField(singleWidgets.get(index), "repaintCount");
        }

        int doubleRowRepaints() throws Exception {
            return (int) getField(doubleWidget, "repaintCount");
        }

        int doubleResizeCount() throws Exception {
            return (int) getField(doubleWidget, "resizeCount");
        }

        int groupRepaintCount() throws Exception {
            return (int) getField(groupWidget, "repaintCount");
        }

        int selectedObjectsUpdates() throws Exception {
            int sum = 0;
            for (final Object row : singleWidgets) {
                sum += (int) getField(row, "selectedObjectsCalls");
            }
            return sum;
        }

        int totalRepaints() throws Exception {
            int sum = doubleRowRepaints() + groupRepaintCount();
            for (int i = 0; i < singleWidgets.size(); i++) {
                sum += rowRepaints(i);
            }
            return sum;
        }

        void clearCounters() throws Exception {
            setField(doubleWidget, "repaintCount", 0);
            setField(doubleWidget, "resizeCount", 0);
            setField(groupWidget, "repaintCount", 0);
            for (final Object row : singleWidgets) {
                setField(row, "repaintCount", 0);
                setField(row, "selectedObjectsCalls", 0);
            }
        }

        private static Object createGuid(final ClassLoader loader, final String key) throws Exception {
            final Object guid = instantiate(loader, "com.live2d.type.CParameterGuid");
            setField(guid, "key", key);
            return guid;
        }

        private static Object createParamSource(
                final ClassLoader loader, final Object guid, final String name, final Object typeInst)
                throws Exception {
            final Object src = instantiate(loader, "com.live2d.cubism.doc.model.param.CParameterSource");
            final Object id = instantiate(loader, "com.live2d.cubism.doc.model.id.CParameterId");
            setField(id, "id", name);
            setField(src, "guid", guid);
            setField(src, "id", id);
            setField(src, "name", name);
            setField(src, "minValue", INITIAL_MIN_VALUE);
            setField(src, "maxValue", INITIAL_MAX_VALUE);
            setField(src, "defaultValue", INITIAL_PARAM_VALUE);
            setField(src, "paramType", typeInst);
            setField(src, "visColor", createColor(loader, Color.BLACK));
            return src;
        }

        private static Object createColor(final ClassLoader loader, final Color color) throws Exception {
            final Object cColor = instantiate(loader, "com.live2d.type.CColor");
            setField(cColor, "awtColor", color);
            return cColor;
        }

        private static void setupWidgetVisuals(
                final ClassLoader loader, final Object widget, final Color color) throws Exception {
            final JPanel panel = new JPanel();
            panel.setBounds(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            setupWidgetWithFields(widget, createColor(loader, color), panel);
        }

        private static void setupWidgetWithFields(
                final Object widget, final Object cColor, final JComponent comp) throws Exception {
            setField(widget, "visible", Boolean.TRUE);
            setField(widget, "bgColor", cColor);
            setField(widget, "jcomp", comp);
        }

        @SuppressWarnings("unchecked")
        private static <T> T instantiate(final ClassLoader loader, final String className) throws Exception {
            return (T) Class.forName(className.replace('/', '.'), true, loader).getConstructor().newInstance();
        }

        private static void setField(final Object target, final String fieldName, final Object value)
                throws Exception {
            findField(target.getClass(), fieldName).set(target, value);
        }

        private static Object getField(final Object target, final String fieldName) throws Exception {
            return findField(target.getClass(), fieldName).get(target);
        }

        private static Field findField(final Class<?> type, final String fieldName)
                throws NoSuchFieldException {
            Class<?> cursor = type;
            while (cursor != null) {
                try {
                    final Field field = cursor.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field;
                } catch (final NoSuchFieldException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
            throw new NoSuchFieldException(type.getName() + "." + fieldName);
        }
    }
}
