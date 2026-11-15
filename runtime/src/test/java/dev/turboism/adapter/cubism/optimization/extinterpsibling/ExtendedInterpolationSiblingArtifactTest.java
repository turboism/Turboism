package dev.turboism.adapter.cubism.optimization.extinterpsibling;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies bytecode rewriting across all 20 reviewed Cubism artifacts, fail-closed guards,
 * constant-time sibling lookup, and behavioral parity with skipped unmodified sibling rebuilds.
 */
class ExtendedInterpolationSiblingArtifactTest {

    private static final String REF_DIR_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_DIR_ENV = "TURBOISM_CUBISM_REF";
    private static final String CUBISM_JAR = "Live2D_Cubism.jar";
    private static final String JAR_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";
    private static final String OWNER_DOT_NAME = ExtendedInterpolationSiblingTarget.OWNER.replace('/', '.');
    private static final String MODEL_SOURCE_DOT_NAME =
            ExtendedInterpolationSiblingTarget.MODEL_SOURCE_OWNER.replace('/', '.');
    private static final String CONTROLLABLE_DOT_NAME =
            ExtendedInterpolationSiblingTarget.CONTROLLABLE_SOURCE_OWNER.replace('/', '.');
    private static final String EXTENSION_DOT_NAME =
            ExtendedInterpolationSiblingTarget.EXTENSION_OWNER.replace('/', '.');
    private static final String WARP_DEFORMER_SOURCE_DOT_NAME =
            "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerSource";
    private static final String WARP_DEFORMER_DOT_NAME =
            "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformer";
    private static final String WARP_DEFORMER_FORM_DOT_NAME =
            "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm";
    private static final String COORD_TYPE_DOT_NAME = "com.live2d.doc.CoordType";
    private static final String PART_SOURCE_DOT_NAME =
            ExtendedInterpolationSiblingTarget.PART_SOURCE_OWNER.replace('/', '.');
    private static final String PARAMETER_SOURCE_DOT_NAME = "com.live2d.cubism.doc.model.param.CParameterSource";
    private static final String PARAMETER_GUID_DOT_NAME = "com.live2d.type.CParameterGuid";
    private static final String KEYFORM_GRID_DOT_NAME = "com.live2d.cubism.doc.model.interpolator.KeyformGridSource";
    private static final String KEYFORM_BINDING_DOT_NAME =
            "com.live2d.cubism.doc.model.interpolator.KeyformBindingSource";
    private static final String EXT_INTERP_TYPE_DOT_NAME =
            "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType";
    private static final String AC_EXTENSION_DOT_NAME = "com.live2d.cubism.doc.model.extension.ACExtension";
    private static final String PREFERRED_PARITY_VERSION = "5.3.04";
    private static final String LIVE2D_PACKAGE_PREFIX = "com.live2d.";
    private static final String NOIDS_PACKAGE_PREFIX = "jp.";
    private static final String JDOM_PACKAGE_PREFIX = "org.jdom.";
    private static final String LOG4J_LEVEL_DOT_NAME = "org.apache.logging.log4j.Level";
    private static final String LOG4J_LEVEL_INTERNAL = "org/apache/logging/log4j/Level";
    private static final String LOG4J_LEVEL_DESC = "Lorg/apache/logging/log4j/Level;";
    private static final String[] LOG4J_LEVEL_FIELDS = {"FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"};
    private static final String COMPANION_FIELD = "Companion";
    private static final String EXTENDED_INTERP_ENUM = "ELLIPSE";
    private static final String MODEL_NAME = "SiblingParity";
    private static final int CANVAS_SIZE = 1000;
    private static final int MAX_WALK_DEPTH = 2;
    private static final int DEFORMER_GRID_SEGMENTS = 2;
    private static final int CONTROL_POINTS_PER_AXIS = 7;
    private static final int FLOAT_COMPONENTS = 2;
    private static final int WARP_ARRAY_LENGTH =
            CONTROL_POINTS_PER_AXIS * CONTROL_POINTS_PER_AXIS * FLOAT_COMPONENTS;
    private static final int INSERT_POINT_COUNT = 1;
    private static final float[] KEY_VALUES = {-30.0f, -10.0f, 10.0f, 30.0f};
    private static final float EDITED_OFFSET = 12.5f;
    private static final float CANARY_VALUE = -9876.5f;
    private static final float FLOAT_TOLERANCE = 1.0e-5f;

    @Test
    void everyConfiguredReviewedArtifactRewritesSiblingExtendedInterpolation() throws Exception {
        final Path root = referenceRoot();
        int rewritten = 0;
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path artifact = root.resolve(version).resolve(CUBISM_JAR);
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
            final ExtendedInterpolationSiblingTarget target = ExtendedInterpolationSiblingTarget.of(digest)
                    .orElseThrow(() -> new AssertionError("reviewed artifact refused: " + version));
            try (URLClassLoader loader = hostLoader(artifact.getParent())) {
                final byte[] original = readClassBytes(artifact, ExtendedInterpolationSiblingTarget.OWNER);
                final ExtendedInterpolationSiblingTransformer transformer =
                        new ExtendedInterpolationSiblingTransformer(loader, artifact, original, target);
                final byte[] rewrittenBytes = transformer.transform(
                        null,
                        loader,
                        ExtendedInterpolationSiblingTarget.OWNER,
                        null,
                        protectionDomain(artifact),
                        original);
                assertNotNull(rewrittenBytes, version + ": " + transformer.failure());
                assertEquals(1, transformer.matches(), version);
                assertEquals(ExtendedInterpolationSiblingTransformer.EXPECTED_SITES, transformer.sites(), version);
                assertNull(transformer.failure(), version);

                // Rewritten class must refuse double-weaving and fail closed on guard violations.
                assertFalse(ExtendedInterpolationSiblingTransformer.verify(rewrittenBytes).isEmpty(), version);
                assertNull(transformer.transform(
                        null,
                        loader,
                        ExtendedInterpolationSiblingTarget.OWNER,
                        null,
                        protectionDomain(artifact),
                        rewrittenBytes));
                try (URLClassLoader otherLoader = hostLoader(artifact.getParent())) {
                    assertNull(transformer.transform(
                            null,
                            otherLoader,
                            ExtendedInterpolationSiblingTarget.OWNER,
                            null,
                            protectionDomain(artifact),
                            original));
                }
                assertNull(transformer.transform(
                        null, loader, ExtendedInterpolationSiblingTarget.OWNER, null, null, original));
                assertNull(transformer.transform(
                        null,
                        loader,
                        ExtendedInterpolationSiblingTarget.OWNER,
                        null,
                        protectionDomain(artifact),
                        injectNopInTarget(original)));
            }
            rewritten++;
        }
        assertTrue(rewritten > 0, "no reviewed exact artifact found under " + root);
    }

    @Test
    void rewrittenCoordinatorMatchesEditedAndDirtyTargetsWhileSkippingUntouchedSiblings() throws Exception {
        final Path root = referenceRoot();
        final Path artifact = resolveParityArtifact(root);
        final HostArtifactDigest digest = HostArtifactDigest.from(artifact);
        final ExtendedInterpolationSiblingTarget target = ExtendedInterpolationSiblingTarget.of(digest)
                .orElseThrow(() -> new AssertionError("parity artifact refused: " + artifact));
        final byte[] originalBytes = readClassBytes(artifact, ExtendedInterpolationSiblingTarget.OWNER);

        try (HostClassLoader originalLoader = hostLoader(artifact.getParent());
                HostClassLoader rewrittenLoader = hostLoader(artifact.getParent())) {
            final ExtendedInterpolationSiblingTransformer transformer =
                    new ExtendedInterpolationSiblingTransformer(rewrittenLoader, artifact, originalBytes, target);
            final byte[] rewrittenBytes = transformer.transform(
                    null,
                    rewrittenLoader,
                    ExtendedInterpolationSiblingTarget.OWNER,
                    null,
                    protectionDomain(artifact),
                    originalBytes);
            assertNotNull(rewrittenBytes, transformer.failure());
            final ProtectionDomain signedDomain =
                    Class.forName(OWNER_DOT_NAME, false, originalLoader).getProtectionDomain();
            rewrittenLoader.installOverride(OWNER_DOT_NAME, rewrittenBytes, signedDomain);

            final HostHarness orig = new HostHarness(originalLoader);
            final HostHarness rewr = new HostHarness(rewrittenLoader);

            final Scenario origScenario = orig.buildSharedPartScenario();
            final Scenario rewrScenario = rewr.buildSharedPartScenario();

            orig.editKeyform(origScenario.targetDeformer, 1, EDITED_OFFSET);
            rewr.editKeyform(rewrScenario.targetDeformer, 1, EDITED_OFFSET);

            orig.editKeyform(origScenario.dirtySibling, 2, -EDITED_OFFSET);
            rewr.editKeyform(rewrScenario.dirtySibling, 2, -EDITED_OFFSET);

            // Plant an identifiable canary on the untouched sibling's interpolated form without
            // bumping editVersion: native l.a() overwrites it via force=true, while rewritten l.a()
            // passes force=false and preserves it.
            orig.plantInterpolatedCanary(origScenario.untouchedSibling, CANARY_VALUE);
            rewr.plantInterpolatedCanary(rewrScenario.untouchedSibling, CANARY_VALUE);

            orig.invokeSiblingUpdate(origScenario.targetDeformer);
            rewr.invokeSiblingUpdate(rewrScenario.targetDeformer);

            assertArrayEquals(
                    orig.interpolatedPositions(origScenario.targetDeformer),
                    rewr.interpolatedPositions(rewrScenario.targetDeformer),
                    FLOAT_TOLERANCE,
                    "edited target deformer interpolated positions must match original");
            assertArrayEquals(
                    orig.interpolatedPositions(origScenario.dirtySibling),
                    rewr.interpolatedPositions(rewrScenario.dirtySibling),
                    FLOAT_TOLERANCE,
                    "dirty sibling interpolated positions must still be updated");

            // Original forces recomputation over the untouched sibling and erases the canary.
            final float origUntouchedFirst = orig.interpolatedPositions(origScenario.untouchedSibling)[0];
            assertFalse(
                    Math.abs(origUntouchedFirst - CANARY_VALUE) < FLOAT_TOLERANCE,
                    "baseline host must forcibly overwrite untouched sibling");
            // Rewritten coordinator skips recomputation on the untouched sibling and preserves it.
            final float rewrUntouchedFirst = rewr.interpolatedPositions(rewrScenario.untouchedSibling)[0];
            assertEquals(
                    CANARY_VALUE,
                    rewrUntouchedFirst,
                    FLOAT_TOLERANCE,
                    "rewritten coordinator must skip untouched sibling rebuild");

            // Null and detached-object safety must match native try/catch contract.
            assertDoesNotThrow(() -> rewr.invokeSiblingUpdate(null));
            assertDoesNotThrow(() -> rewr.invokeSiblingUpdate(rewr.createDetachedDeformer(rewrScenario.modelSource)));
        }
    }

    private static Path resolveParityArtifact(final Path root) {
        final Path preferred = root.resolve(PREFERRED_PARITY_VERSION).resolve(CUBISM_JAR);
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Path candidate = root.resolve(version).resolve(CUBISM_JAR);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("no reviewed exact artifact found under " + root);
    }

    private static HostClassLoader hostLoader(final Path directory) throws Exception {
        return new HostClassLoader(urlsUnder(directory), ExtendedInterpolationSiblingArtifactTest.class.getClassLoader());
    }

    private static URL[] urlsUnder(final Path directory) throws Exception {
        final List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(directory, MAX_WALK_DEPTH)) {
            for (final Path path : files.filter(p -> p.toString().endsWith(JAR_EXTENSION)).toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static byte[] readClassBytes(final Path artifact, final String owner) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final var entry = jar.getJarEntry(owner + CLASS_EXTENSION);
            assertNotNull(entry, owner + " absent from " + artifact);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ProtectionDomain protectionDomain(final Path artifact) throws Exception {
        return new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (Certificate[]) null), null);
    }

    private static Path referenceRoot() {
        final String configured =
                System.getProperty(REF_DIR_PROPERTY, System.getenv().getOrDefault(REF_DIR_ENV, ""));
        assumeTrue(!configured.isBlank(), "exact Cubism reference directory not configured");
        final Path root = Path.of(configured);
        assumeTrue(Files.isDirectory(root), "reference directory absent: " + root);
        return root;
    }

    private static byte[] injectNopInTarget(final byte[] original) {
        final ClassReader reader = new ClassReader(original);
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
                        final MethodVisitor mv =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!ExtendedInterpolationSiblingTarget.TARGET_METHOD.equals(name)
                                || !ExtendedInterpolationSiblingTarget.TARGET_DESCRIPTOR.equals(descriptor)) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitInsn(Opcodes.NOP);
                            }
                        };
                    }
                },
                0);
        return writer.toByteArray();
    }

    private record Scenario(
            Object modelSource, Object targetDeformer, Object untouchedSibling, Object dirtySibling) {}

    private static final class HostHarness {
        private final Class<?> modelSourceClass;
        private final Class<?> warpDeformerSourceClass;
        private final Class<?> warpDeformerFormClass;
        private final Class<?> partSourceClass;
        private final Class<?> controllableSourceClass;
        private final Class<?> extensionClass;
        private final Object coordinatorInstance;
        private final Method updateSiblingMethod;
        private final Object localCoordType;
        private final Object ellipseType;

        private HostHarness(final ClassLoader loader) throws Exception {
            final Class<?> coordinatorClass = Class.forName(OWNER_DOT_NAME, true, loader);
            this.coordinatorInstance = coordinatorClass.getField("a").get(null);
            this.controllableSourceClass = Class.forName(CONTROLLABLE_DOT_NAME, true, loader);
            this.updateSiblingMethod = coordinatorClass.getDeclaredMethod(
                    ExtendedInterpolationSiblingTarget.TARGET_METHOD, controllableSourceClass);
            this.updateSiblingMethod.setAccessible(true);

            this.modelSourceClass = Class.forName(MODEL_SOURCE_DOT_NAME, true, loader);
            this.warpDeformerSourceClass = Class.forName(WARP_DEFORMER_SOURCE_DOT_NAME, true, loader);
            this.warpDeformerFormClass = Class.forName(WARP_DEFORMER_FORM_DOT_NAME, true, loader);
            this.partSourceClass = Class.forName(PART_SOURCE_DOT_NAME, true, loader);
            this.extensionClass = Class.forName(EXTENSION_DOT_NAME, true, loader);

            final Class<?> coordTypeClass = Class.forName(COORD_TYPE_DOT_NAME, true, loader);
            final Object coordCompanion = coordTypeClass.getField(COMPANION_FIELD).get(null);
            this.localCoordType = coordCompanion.getClass().getMethod("b").invoke(coordCompanion);

            @SuppressWarnings({"rawtypes", "unchecked"})
            final Class<? extends Enum> extTypeClass =
                    (Class<? extends Enum>) Class.forName(EXT_INTERP_TYPE_DOT_NAME, true, loader);
            this.ellipseType = Enum.valueOf(extTypeClass, EXTENDED_INTERP_ENUM);
        }

        private Scenario buildSharedPartScenario() throws Exception {
            final Object modelCompanion = modelSourceClass.getField(COMPANION_FIELD).get(null);
            final Object modelSource = modelCompanion
                    .getClass()
                    .getMethod("a", String.class, int.class, int.class)
                    .invoke(modelCompanion, MODEL_NAME, CANVAS_SIZE, CANVAS_SIZE);
            final Object rootPart = modelSourceClass.getMethod("getRootPart").invoke(modelSource);
            final Object paramSet = modelSourceClass.getMethod("getParameterSourceSet").invoke(modelSource);
            final List<?> params = (List<?>) paramSet.getClass().getMethod("getSources").invoke(paramSet);
            final Object param = params.get(0);
            final Object paramGuid = param.getClass().getMethod("getGuid").invoke(param);

            final Object targetDeformer = createExtendedDeformer(modelSource, rootPart, paramGuid, 0.0f);
            final Object untouchedSibling = createExtendedDeformer(modelSource, rootPart, paramGuid, 100.0f);
            final Object dirtySibling = createExtendedDeformer(modelSource, rootPart, paramGuid, 200.0f);
            return new Scenario(modelSource, targetDeformer, untouchedSibling, dirtySibling);
        }

        private Object createExtendedDeformer(
                final Object modelSource, final Object rootPart, final Object paramGuid, final float baseOffset)
                throws Exception {
            final Object deformer =
                    warpDeformerSourceClass.getConstructor(modelSourceClass).newInstance(modelSource);
            warpDeformerSourceClass.getMethod("setRow", int.class).invoke(deformer, DEFORMER_GRID_SEGMENTS);
            warpDeformerSourceClass.getMethod("setCol", int.class).invoke(deformer, DEFORMER_GRID_SEGMENTS);

            final Class<?> warpDeformerClass =
                    Class.forName(WARP_DEFORMER_DOT_NAME, true, modelSourceClass.getClassLoader());
            final Class<?> coordTypeClass =
                    Class.forName(COORD_TYPE_DOT_NAME, true, modelSourceClass.getClassLoader());
            final Object initialForm = warpDeformerFormClass
                    .getConstructor(warpDeformerSourceClass, warpDeformerClass, coordTypeClass)
                    .newInstance(deformer, null, localCoordType);
            final float[] initialPositions = new float[WARP_ARRAY_LENGTH];
            for (int i = 0; i < initialPositions.length; i++) {
                initialPositions[i] = baseOffset + i;
            }
            warpDeformerFormClass.getMethod("setPositions", float[].class).invoke(initialForm, initialPositions);
            @SuppressWarnings("unchecked")
            final List<Object> keyforms = (List<Object>)
                    controllableSourceClass.getMethod("getKeyforms").invoke(deformer);
            keyforms.add(initialForm);

            modelSourceClass
                    .getMethod("addParameterControllableSource", controllableSourceClass)
                    .invoke(modelSource, deformer);
            partSourceClass
                    .getMethod("addChild", controllableSourceClass, int.class)
                    .invoke(rootPart, deformer, -1);

            final Object grid = controllableSourceClass.getMethod("getKeyformGridSource").invoke(deformer);
            final Class<?> formGuidClass =
                    Class.forName("com.live2d.type.CFormGuid", true, modelSourceClass.getClassLoader());
            final Object initialGuid = initialForm.getClass().getMethod("getGuid").invoke(initialForm);
            grid.getClass().getMethod("setInitialKeyform", formGuidClass).invoke(grid, initialGuid);
            final Class<?> paramGuidClass =
                    Class.forName(PARAMETER_GUID_DOT_NAME, true, modelSourceClass.getClassLoader());
            final Method addKey = grid.getClass().getMethod("addKey", float.class, paramGuidClass);
            for (int k = 0; k < KEY_VALUES.length; k++) {
                addKey.invoke(grid, KEY_VALUES[k], paramGuid);
                final Object kf = keyforms.get(keyforms.size() - 1);
                final float[] pos = (float[]) warpDeformerFormClass.getMethod("getPositions").invoke(kf);
                for (int p = 0; p < pos.length; p += 2) {
                    pos[p] += k * 5.0f;
                    pos[p + 1] += k * k * 2.0f;
                }
            }

            final Object binding = grid.getClass().getMethod("findKeyformBinding", paramGuidClass).invoke(grid, paramGuid);
            final Class<?> extTypeClass =
                    Class.forName(EXT_INTERP_TYPE_DOT_NAME, true, modelSourceClass.getClassLoader());
            binding.getClass().getMethod("setExtendedInterpolationType", extTypeClass).invoke(binding, ellipseType);
            binding.getClass().getMethod("setInsertPointCount", int.class).invoke(binding, INSERT_POINT_COUNT);

            final Object ext = extensionClass.getConstructor(controllableSourceClass).newInstance(deformer);
            final Class<?> acExtClass =
                    Class.forName(AC_EXTENSION_DOT_NAME, true, modelSourceClass.getClassLoader());
            controllableSourceClass
                    .getMethod("addExtension", acExtClass, int.class)
                    .invoke(deformer, ext, 0);
            extensionClass.getMethod("setupFormStructure", boolean.class, boolean.class).invoke(ext, false, true);
            return deformer;
        }

        private Object createDetachedDeformer(final Object modelSource) throws Exception {
            return warpDeformerSourceClass.getConstructor(modelSourceClass).newInstance(modelSource);
        }

        private void editKeyform(final Object deformer, final int keyformIndex, final float delta) throws Exception {
            final List<?> keyforms =
                    (List<?>) controllableSourceClass.getMethod("getKeyforms").invoke(deformer);
            final Object form = keyforms.get(keyformIndex);
            final float[] positions = (float[]) warpDeformerFormClass.getMethod("getPositions").invoke(form);
            for (int i = 0; i < positions.length; i++) {
                positions[i] += delta;
            }
            form.getClass().getMethod("setUpdated").invoke(form);
        }

        private void plantInterpolatedCanary(final Object deformer, final float canary) throws Exception {
            final Object ext = findExtension(deformer);
            final List<?> extForms =
                    (List<?>) extensionClass.getMethod("getNormalExtendedKeyforms").invoke(ext);
            assertFalse(extForms.isEmpty(), "expected generated extended keyforms");
            final float[] positions =
                    (float[]) warpDeformerFormClass.getMethod("getPositions").invoke(extForms.get(0));
            positions[0] = canary;
        }

        private float[] interpolatedPositions(final Object deformer) throws Exception {
            final Object ext = findExtension(deformer);
            final List<?> extForms =
                    (List<?>) extensionClass.getMethod("getNormalExtendedKeyforms").invoke(ext);
            final float[] positions =
                    (float[]) warpDeformerFormClass.getMethod("getPositions").invoke(extForms.get(0));
            return positions.clone();
        }

        private Object findExtension(final Object deformer) throws Exception {
            final List<?> extensions =
                    (List<?>) controllableSourceClass.getMethod("getExtensions").invoke(deformer);
            for (final Object ext : extensions) {
                if (extensionClass.isInstance(ext)) {
                    return ext;
                }
            }
            throw new AssertionError("missing CExtendedInterpolationExtension");
        }

        private void invokeSiblingUpdate(final Object targetDeformer) throws Exception {
            updateSiblingMethod.invoke(coordinatorInstance, targetDeformer);
        }
    }

    private static final class HostClassLoader extends URLClassLoader {
        private String overrideName;
        private byte[] overrideBytes;
        private ProtectionDomain overrideDomain;

        private HostClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        private void installOverride(final String name, final byte[] bytes, final ProtectionDomain domain) {
            this.overrideName = name;
            this.overrideBytes = bytes;
            this.overrideDomain = domain;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(LIVE2D_PACKAGE_PREFIX)
                    || name.startsWith(NOIDS_PACKAGE_PREFIX)
                    || name.startsWith(JDOM_PACKAGE_PREFIX)
                    || LOG4J_LEVEL_DOT_NAME.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            if (name.equals(overrideName) && overrideBytes != null) {
                final byte[] bytes = overrideBytes;
                overrideBytes = null;
                return defineClass(name, bytes, 0, bytes.length, overrideDomain);
            }
            if (name.startsWith(JDOM_PACKAGE_PREFIX)) {
                return defineStubClass(name);
            }
            if (LOG4J_LEVEL_DOT_NAME.equals(name)) {
                return defineLog4jLevelStub();
            }
            return super.findClass(name);
        }

        private Class<?> defineLog4jLevelStub() {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, LOG4J_LEVEL_INTERNAL, null, "java/lang/Object", null);
            for (final String field : LOG4J_LEVEL_FIELDS) {
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field, LOG4J_LEVEL_DESC, null, null)
                        .visitEnd();
            }
            final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();

            final MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            for (final String field : LOG4J_LEVEL_FIELDS) {
                clinit.visitTypeInsn(Opcodes.NEW, LOG4J_LEVEL_INTERNAL);
                clinit.visitInsn(Opcodes.DUP);
                clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, LOG4J_LEVEL_INTERNAL, "<init>", "()V", false);
                clinit.visitFieldInsn(Opcodes.PUTSTATIC, LOG4J_LEVEL_INTERNAL, field, LOG4J_LEVEL_DESC);
            }
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            cw.visitEnd();
            final byte[] raw = cw.toByteArray();
            return defineClass(LOG4J_LEVEL_DOT_NAME, raw, 0, raw.length);
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
}
