package dev.turboism.adapter.cubism.optimization.atlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies exact functional and pixel parity for mesh outlines, off-EDT triangulation apply,
 * row-major edge fill, UI list filtering, and Path2D pre-sizing against real Cubism bytecode.
 */
final class TextureAtlasPipelineParityTest {

    private static final String REF_PROPERTY = "turboism.test.cubismRefDir";
    private static final String REF_ENV = "TURBOISM_CUBISM_REF";
    private static final String FULL_REF_PROPERTY = "turboism.cubismRefRoot";
    private static final String FULL_REF_ENV = "TURBOISM_CUBISM_REF_ROOT";
    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final Path DEFAULT_FULL_REF_ROOT = Path.of("/opt/dev/projects/turboism/cubism-ref");
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JDOM_JAR_NAME = "jdom-1.1.jar";
    private static final String JAR_SUFFIX = ".jar";
    private static final String CLASS_SUFFIX = ".class";
    private static final String STUB_PACKAGE_PREFIX = "org.jdom.";
    private static final int SEED = 0x5A17_93F1;
    private static final int GRID_SIDE = 10;
    private static final int SMALL_GRID_SIDE = 4;
    private static final float POINT_SPACING = 25.0f;
    private static final int EDGE_ALPHA_MOD = 16;

    @TempDir
    private static Path tempHostDir;

    private static String previousLogFilename;
    private static String previousLogsFilePath;
    private static boolean dataExistedBefore;

    @BeforeAll
    static void redirectHostTempOutputs() {
        dataExistedBefore = Files.exists(Path.of("data"));
        previousLogFilename = System.getProperty("logFilename");
        previousLogsFilePath = System.getProperty("logsFilePath");
        System.setProperty("logFilename", tempHostDir.resolve("cubism.log").toString());
        System.setProperty("logsFilePath", tempHostDir.toString());
    }

    @AfterAll
    static void restoreHostTempOutputs() throws IOException {
        restoreProperty("logFilename", previousLogFilename);
        restoreProperty("logsFilePath", previousLogsFilePath);
        if (!dataExistedBefore) {
            deleteRecursively(Path.of("data"));
        }
        deleteRecursively(Path.of("${sys:logFilename}"));
    }

    private static void restoreProperty(final String key, final String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private static void deleteRecursively(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (final Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void rowMajorEdgeFillMatchesOriginalColumnMajorAcrossRandomizedPatterns() throws Exception {
        final Random rng = new Random(SEED);
        final int[] widths = {1, 7, 16, 64, 129, 257};
        final int[] heights = {1, 13, 16, 64, 129, 173};
        final int[] distances = {0, 1, 3, 8, 24};

        for (int idx = 0; idx < widths.length; idx++) {
            final int w = widths[idx];
            final int h = heights[idx];
            final BufferedImage expectedImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage actualImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            final int[] expected = ((DataBufferInt) expectedImg.getRaster().getDataBuffer()).getData();
            final int[] actual = ((DataBufferInt) actualImg.getRaster().getDataBuffer()).getData();
            populatePatternPixels(rng, expected);
            System.arraycopy(expected, 0, actual, 0, expected.length);

            final int distance = distances[idx % distances.length];
            referenceColumnMajorEdgeFill(expected, w, h, distance);
            assertTrue(TextureAtlasEdgeFillDelegate.tryEdgeFill(actualImg, distance));
            assertArrayEquals(expected, actual, "Mismatch for " + w + "x" + h + " dist=" + distance);
        }

        final Path jar5304 = hostJar("5.3.04");
        Assumptions.assumeTrue(Files.isRegularFile(jar5304));
        try (IsolatedJarLoader nativeLoader = new IsolatedJarLoader(jar5304, null);
                IsolatedJarLoader patchedLoader =
                        new IsolatedJarLoader(jar5304, new TextureAtlasEdgeFillTransformer())) {
            final Method nativeFill =
                    nativeLoader.loadClass("jp.noids.graphics.h").getMethod("a", BufferedImage.class, int.class);
            final Method patchedFill =
                    patchedLoader.loadClass("jp.noids.graphics.h").getMethod("a", BufferedImage.class, int.class);
            final BufferedImage nativeImg = new BufferedImage(97, 83, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage fastImg = new BufferedImage(97, 83, BufferedImage.TYPE_INT_ARGB);
            final int[] nativePix = ((DataBufferInt) nativeImg.getRaster().getDataBuffer()).getData();
            final int[] fastPix = ((DataBufferInt) fastImg.getRaster().getDataBuffer()).getData();
            populatePatternPixels(rng, nativePix);
            System.arraycopy(nativePix, 0, fastPix, 0, nativePix.length);

            nativeFill.invoke(null, nativeImg, 6);
            patchedFill.invoke(null, fastImg, 6);
            assertArrayEquals(nativePix, fastPix, "Real Cubism h.a output diverged");
        }
    }

    @Test
    void fastMeshOutlinesAndOffEdtApplyMatchNativeAcrossRealHostVersions() throws Exception {
        final Path jar5000 = hostJar("5.0.00");
        final Path jar5304 = hostJar("5.3.04");
        Assumptions.assumeTrue(Files.isRegularFile(jar5000) && Files.isRegularFile(jar5304));
        for (final Path jarPath : List.of(jar5000, jar5304)) {
            verifyMeshParityOnJar(jarPath);
        }
    }

    private static void verifyMeshParityOnJar(final Path jarPath) throws Exception {
        final TextureAtlasLoadingTransformer transformer = new TextureAtlasLoadingTransformer(true);
        try (IsolatedJarLoader nativeLoader = new IsolatedJarLoader(jarPath, null);
                IsolatedJarLoader fastLoader = new IsolatedJarLoader(jarPath, transformer)) {
            // Large meshes (>= 48 pts) for off-EDT pipeline + small (< 48 pts) for O(E+T) fallback + non-Delaunay.
            final Object nativeLarge = createGridMesh(nativeLoader, GRID_SIDE, true, true);
            final Object fastLarge = createGridMesh(fastLoader, GRID_SIDE, true, true);
            final Object nativeLargePath = createGridMesh(nativeLoader, GRID_SIDE, true, false);
            final Object fastLargePath = createGridMesh(fastLoader, GRID_SIDE, true, false);
            final Object nativeSmall = createGridMesh(nativeLoader, SMALL_GRID_SIDE, true, true);
            final Object fastSmall = createGridMesh(fastLoader, SMALL_GRID_SIDE, true, true);
            final Object nativeNonDelaunay = createGridMesh(nativeLoader, GRID_SIDE, false, false);
            final Object fastNonDelaunay = createGridMesh(fastLoader, GRID_SIDE, false, false);

            // Compare b.b outline extraction bit-for-bit before solve mutates auto edges.
            final Object nativeOps = nativeLoader
                    .loadClass("com.live2d.graphics3d.editableMesh.b")
                    .getField("a")
                    .get(null);
            final Object fastOps = fastLoader
                    .loadClass("com.live2d.graphics3d.editableMesh.b")
                    .getField("a")
                    .get(null);
            final Method nativeOutline = nativeOps.getClass().getMethod("b", nativeLarge.getClass());
            final Method fastOutline = fastOps.getClass().getMethod("b", fastLarge.getClass());
            assertContourListsEqual((List<?>) nativeOutline.invoke(nativeOps, nativeLarge), (List<?>)
                    fastOutline.invoke(fastOps, fastLarge));

            solveNativeMesh(nativeLarge);
            solveNativeMesh(nativeLargePath);
            solveNativeMesh(nativeSmall);
            solveNativeMesh(nativeNonDelaunay);

            // Stage CArtMeshSource (extension & direct), CArtPathSource, and non-Delaunay through
            // resolveDrawableGroups.
            final List<Object> fastArtSources = List.of(
                    buildDrawableHolder(fastLoader, fastLarge, false),
                    buildArtPathHolder(fastLoader, fastLargePath),
                    buildArtPathHolder(fastLoader, fastSmall),
                    buildDrawableHolder(fastLoader, fastNonDelaunay, true));
            final Map<Object, List<Object>> drawables = Map.of("img", fastArtSources);
            final MethodHandle computeGroups = MethodHandles.lookup()
                    .findStatic(
                            TextureAtlasPipelineParityTest.class,
                            "returnDrawables",
                            MethodType.methodType(HashMap.class, Object.class, Object.class));
            final MethodHandle runInit = MethodHandles.lookup()
                    .findStatic(
                            TextureAtlasPipelineParityTest.class,
                            "runFastInit",
                            MethodType.methodType(void.class, Object.class));
            final InitState initState = new InitState(
                    drawables,
                    fastOps.getClass(),
                    computeGroups,
                    List.of(fastLarge, fastLargePath, fastSmall, fastNonDelaunay));

            assertFalse(TextureAtlasLoading.isInitializing());
            EventQueue.invokeAndWait(() -> {
                try {
                    TextureAtlasLoading.run(initState, runInit);
                } catch (Throwable failure) {
                    throw new RuntimeException(failure);
                }
            });
            assertFalse(TextureAtlasLoading.isInitializing());

            assertMeshStateEqual(nativeLarge, fastLarge);
            assertMeshStateEqual(nativeLargePath, fastLargePath);
            assertMeshStateEqual(nativeSmall, fastSmall);
            assertMeshStateEqual(nativeNonDelaunay, fastNonDelaunay);
        }
    }

    @Test
    void parallelPreviewScalingMatchesNativePixelByPixel() throws Throwable {
        final Path jar5000 = hostJar("5.0.00");
        final Path jar5304 = hostJar("5.3.04");
        Assumptions.assumeTrue(Files.isRegularFile(jar5000) && Files.isRegularFile(jar5304));
        for (final Path jarPath : List.of(jar5000, jar5304)) {
            verifyPreviewParityOnJar(jarPath);
        }
    }

    @Test
    void confirmBridgeAtlasMapMatchesNativeAndDropsGc() throws Exception {
        final Path jar5000 = hostJar("5.0.00");
        final Path jar5304 = hostJar("5.3.04");
        Assumptions.assumeTrue(Files.isRegularFile(jar5000) && Files.isRegularFile(jar5304));
        for (final Path jarPath : List.of(jar5000, jar5304)) {
            verifyConfirmParityOnJar(jarPath);
        }
    }

    @Test
    void uiListDelegateBindsCleanlyAcrossHostVersions() throws Exception {
        final Path jar5000 = hostJar("5.0.00");
        final Path jar5304 = hostJar("5.3.04");
        Assumptions.assumeTrue(Files.isRegularFile(jar5000) && Files.isRegularFile(jar5304));
        for (final Path jarPath : List.of(jar5000, jar5304)) {
            try (IsolatedJarLoader patchedLoader =
                    new IsolatedJarLoader(jarPath, new TextureAtlasUiListTransformer())) {
                final Class<?> listClass =
                        patchedLoader.loadClass(TextureAtlasUiListTransformer.MODEL_IMAGE_LIST.replace('/', '.'));
                // Verify real host binding succeeds across versions and null short-circuits cleanly.
                assertTrue(TextureAtlasUiListDelegate.canBind(listClass), "UiListHost failed to bind on " + jarPath);
                assertFalse(TextureAtlasUiListDelegate.tryUpdateList(null));
                assertNotNull(listClass);
            }
        }
    }

    private static void verifyPreviewParityOnJar(final Path jarPath) throws Throwable {
        final TextureAtlasLoadingTransformer transformer = new TextureAtlasLoadingTransformer(true);
        try (IsolatedJarLoader nativeLoader = new IsolatedJarLoader(jarPath, null);
                IsolatedJarLoader patchedLoader = new IsolatedJarLoader(jarPath, transformer)) {
            final BufferedImage largeSrc = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage smallSrc = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
            final Random rng = new Random(SEED + 7);
            populatePatternPixels(rng, ((DataBufferInt) largeSrc.getRaster().getDataBuffer()).getData());
            populatePatternPixels(rng, ((DataBufferInt) smallSrc.getRaster().getDataBuffer()).getData());

            final Object nativeLargeLayer = createPreviewLayer(nativeLoader, largeSrc, false);
            final Object nativeSmallLayer = createPreviewLayer(nativeLoader, smallSrc, false);

            final List<Object> patchedLayers = new ArrayList<>();
            final MethodHandle previewInit = MethodHandles.lookup()
                    .findStatic(
                            TextureAtlasPipelineParityTest.class,
                            "buildPatchedPreviewLayers",
                            MethodType.methodType(void.class, Object.class));
            final PreviewInitState state = new PreviewInitState(patchedLoader, largeSrc, smallSrc, patchedLayers);
            EventQueue.invokeAndWait(() -> {
                try {
                    TextureAtlasLoading.run(state, previewInit);
                } catch (Throwable failure) {
                    throw new RuntimeException(failure);
                }
            });

            final int[] nativeLargePixels = extractPreviewPixels(nativeLargeLayer);
            final int[] patchedFirstPixels = extractPreviewPixels(patchedLayers.get(0));
            final int[] patchedSecondPixels = extractPreviewPixels(patchedLayers.get(1));
            assertArrayEquals(nativeLargePixels, patchedFirstPixels, "Parallel preview pixels diverged");
            assertArrayEquals(nativeLargePixels, patchedSecondPixels, "Deduplicated preview pixels diverged");
            assertEquals(extractPreviewPixels(nativeSmallLayer), extractPreviewPixels(patchedLayers.get(2)));
        }
    }

    @SuppressWarnings("unused")
    private static void buildPatchedPreviewLayers(final Object rawState) throws Exception {
        final PreviewInitState state = (PreviewInitState) rawState;
        final Object sharedLayer1 = createPreviewLayer(state.loader(), state.largeSrc(), false);
        final Object sharedLayer2 = createPreviewLayer(state.loader(), state.largeSrc(), true, sharedLayer1);
        final Object smallLayer = createPreviewLayer(state.loader(), state.smallSrc(), false);
        final Method getPreview = sharedLayer1.getClass().getMethod("getPreviewImg$cubism");
        // Verify previews > 250_000 pixels remain deferred until TextureAtlasLoading.run calls awaitAll().
        assertNull(getPreview.invoke(sharedLayer1));
        assertNull(getPreview.invoke(sharedLayer2));
        state.out().add(sharedLayer1);
        state.out().add(sharedLayer2);
        state.out().add(smallLayer);
    }

    private static Object createPreviewLayer(
            final ClassLoader loader,
            final BufferedImage src,
            final boolean shareResource,
            final Object... existingLayer)
            throws Exception {
        final Class<?> modelSourceClass = loader.loadClass("com.live2d.cubism.doc.model.CModelSource");
        final Class<?> atlasClass = loader.loadClass("com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas");
        final Class<?> entryClass =
                loader.loadClass("com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas$ModelImageEntry");
        final Class<?> modelImageClass = loader.loadClass("com.live2d.cubism.doc.model.texture.modelImage.CModelImage");
        final Class<?> groupClass = loader.loadClass("com.live2d.cubism.doc.model.texture.modelImage.CModelImageGroup");
        final Class<?> imgResClass = loader.loadClass("com.live2d.graphics.CImageResource");
        final Class<?> guidClass = loader.loadClass("com.live2d.type.CModelImageGuid");
        final Class<?> affineClass = loader.loadClass("com.live2d.type.CAffine");
        final Class<?> dataModelClass =
                loader.loadClass("com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.TAE_DataModel");
        final Class<?> layerClass =
                loader.loadClass("com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.TAE__EditLayer_ModelImage");

        final Object modelSource = modelSourceClass.getConstructor().newInstance();
        final Object texMgr = modelSourceClass.getMethod("getTextureManager").invoke(modelSource);
        final Object group = groupClass.getConstructor(String.class).newInstance("default");
        @SuppressWarnings("unchecked")
        final List<Object> groups = (List<Object>)
                findDeclaredField(texMgr.getClass(), "_modelImageGroups").get(texMgr);
        groups.add(group);

        final Object guid = guidClass.getConstructor(UUID.class).newInstance(UUID.randomUUID());
        final Object modelImage = modelImageClass
                .getConstructor(modelSourceClass, String.class, guidClass)
                .newInstance(modelSource, "img", guid);
        final Object imgRes = shareResource && existingLayer.length > 0
                ? existingLayer[0].getClass().getMethod("getSrcImage").invoke(existingLayer[0])
                : imgResClass.getConstructor(BufferedImage.class, boolean.class).newInstance(src, false);
        findDeclaredField(modelImageClass, "_filteredImage").set(modelImage, imgRes);
        findDeclaredField(modelImageClass, "_materialLocalToCanvasTransform")
                .set(modelImage, affineClass.getConstructor().newInstance());
        groupClass
                .getMethod("addModelImage$default", groupClass, modelImageClass, int.class, int.class, Object.class)
                .invoke(null, group, modelImage, -1, 2, null);

        final Object atlas = atlasClass.getConstructor().newInstance();
        findDeclaredField(atlasClass, "_modelSource").set(atlas, modelSource);
        final Object entry = entryClass
                .getConstructor(atlasClass, guidClass, affineClass)
                .newInstance(atlas, guid, affineClass.getConstructor().newInstance());
        final Object dummyDataModel = allocateInstance(dataModelClass);

        return layerClass
                .getConstructor(dataModelClass, atlasClass, entryClass, affineClass, List.class)
                .newInstance(
                        dummyDataModel,
                        atlas,
                        entry,
                        affineClass.getConstructor().newInstance(),
                        List.of());
    }

    private static int[] extractPreviewPixels(final Object layer) throws Exception {
        final Object writable =
                layer.getClass().getMethod("getPreviewImg$cubism").invoke(layer);
        if (writable == null) {
            return null;
        }
        final BufferedImage buf = (BufferedImage)
                writable.getClass().getMethod("getJBufferedImage").invoke(writable);
        return buf.getRGB(0, 0, buf.getWidth(), buf.getHeight(), null, 0, buf.getWidth());
    }

    private static void verifyConfirmParityOnJar(final Path jarPath) throws Exception {
        final TextureAtlasUiListTransformer uiTransformer = new TextureAtlasUiListTransformer();
        try (IsolatedJarLoader nativeLoader = new IsolatedJarLoader(jarPath, null);
                IsolatedJarLoader patchedLoader = new IsolatedJarLoader(jarPath, uiTransformer)) {
            final byte[] rawConfirmTask = readJarEntry(jarPath, TextureAtlasUiListTransformer.CONFIRM_TASK);
            final byte[] patchedConfirmTask = uiTransformer.transform(
                    null, TextureAtlasUiListTransformer.CONFIRM_TASK, null, null, rawConfirmTask);
            assertNotNull(patchedConfirmTask);
            final AtomicInteger gcCount = new AtomicInteger();
            new ClassReader(patchedConfirmTask)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String desc,
                                        final String sig,
                                        final String[] ex) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String m,
                                                final String d,
                                                final boolean itf) {
                                            if ("java/lang/System".equals(owner) && "gc".equals(m)) {
                                                gcCount.incrementAndGet();
                                            }
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            assertEquals(0, gcCount.get(), "Unconditional System.gc() must be removed from confirm task");

            final Class<?> modelSourceClass = patchedLoader.loadClass("com.live2d.cubism.doc.model.CModelSource");
            final Class<?> atlasClass =
                    patchedLoader.loadClass("com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas");
            final Class<?> entryClass = patchedLoader.loadClass(
                    "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas$ModelImageEntry");
            final Class<?> modelImageClass =
                    patchedLoader.loadClass("com.live2d.cubism.doc.model.texture.modelImage.CModelImage");
            final Class<?> groupClass =
                    patchedLoader.loadClass("com.live2d.cubism.doc.model.texture.modelImage.CModelImageGroup");
            final Class<?> guidClass = patchedLoader.loadClass("com.live2d.type.CModelImageGuid");
            final Class<?> affineClass = patchedLoader.loadClass("com.live2d.type.CAffine");

            final Object modelSource = modelSourceClass.getConstructor().newInstance();
            final Object texMgr =
                    modelSourceClass.getMethod("getTextureManager").invoke(modelSource);
            final Object group = groupClass.getConstructor(String.class).newInstance("default");
            @SuppressWarnings("unchecked")
            final List<Object> groups = (List<Object>)
                    findDeclaredField(texMgr.getClass(), "_modelImageGroups").get(texMgr);
            groups.add(group);
            @SuppressWarnings("unchecked")
            final List<Object> atlases = (List<Object>)
                    findDeclaredField(texMgr.getClass(), "_textureAtlases").get(texMgr);

            final Object guidA = guidClass.getConstructor(UUID.class).newInstance(UUID.randomUUID());
            final Object guidB = guidClass.getConstructor(UUID.class).newInstance(UUID.randomUUID());
            for (final Object guid : List.of(guidA, guidB)) {
                final Object img = modelImageClass
                        .getConstructor(modelSourceClass, String.class, guidClass)
                        .newInstance(modelSource, "img", guid);
                findDeclaredField(modelImageClass, "_materialLocalToCanvasTransform")
                        .set(img, affineClass.getConstructor().newInstance());
                groupClass
                        .getMethod(
                                "addModelImage$default",
                                groupClass,
                                modelImageClass,
                                int.class,
                                int.class,
                                Object.class)
                        .invoke(null, group, img, -1, 2, null);
            }
            final Object atlas1 = atlasClass.getConstructor().newInstance();
            final Object atlas2 = atlasClass.getConstructor().newInstance();
            findDeclaredField(atlasClass, "_modelSource").set(atlas1, modelSource);
            findDeclaredField(atlasClass, "_modelSource").set(atlas2, modelSource);
            final Object entryA1 = entryClass
                    .getConstructor(atlasClass, guidClass, affineClass)
                    .newInstance(atlas1, guidA, affineClass.getConstructor().newInstance());
            final Object entryB1 = entryClass
                    .getConstructor(atlasClass, guidClass, affineClass)
                    .newInstance(atlas1, guidB, affineClass.getConstructor().newInstance());
            final Object entryA2 = entryClass
                    .getConstructor(atlasClass, guidClass, affineClass)
                    .newInstance(atlas2, guidA, affineClass.getConstructor().newInstance());
            ((List<Object>) atlasClass.getMethod("getModelImages").invoke(atlas1)).addAll(List.of(entryA1, entryB1));
            ((List<Object>) atlasClass.getMethod("getModelImages").invoke(atlas2)).add(entryA2);
            atlases.addAll(List.of(atlas1, atlas2));

            final Map<Object, Object> actualMap = new HashMap<>();
            assertTrue(TextureAtlasUiListDelegate.tryPopulateAtlasEntries(modelSource, actualMap));
            assertEquals(2, actualMap.size());
            assertSame(entryA1, actualMap.get(guidA), "First occurrence must win when duplicate guids exist");
            assertSame(entryB1, actualMap.get(guidB));

            // Execute full patched doc.model.texture.r.a(CModelSource) as well.
            final Object nativeSource = nativeLoader
                    .loadClass("com.live2d.cubism.doc.model.CModelSource")
                    .getConstructor()
                    .newInstance();
            final Object patchedBridge = patchedLoader
                    .loadClass("com.live2d.cubism.doc.model.texture.r")
                    .getField("a")
                    .get(null);
            final Object nativeBridge = nativeLoader
                    .loadClass("com.live2d.cubism.doc.model.texture.r")
                    .getField("a")
                    .get(null);
            patchedBridge.getClass().getMethod("a", modelSourceClass).invoke(patchedBridge, modelSource);
            nativeBridge.getClass().getMethod("a", nativeSource.getClass()).invoke(nativeBridge, nativeSource);
        }
    }

    private static byte[] readJarEntry(final Path jarPath, final String internalName) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static Object allocateInstance(final Class<?> type) throws Exception {
        final Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type);
    }

    @Test
    void pathWrapperPreSizingPreservesExactShapeSemantics() throws Exception {
        final Path jar5304 = hostJar("5.3.04");
        Assumptions.assumeTrue(Files.isRegularFile(jar5304));
        try (IsolatedJarLoader nativeLoader = new IsolatedJarLoader(jar5304, null);
                IsolatedJarLoader patchedLoader = new IsolatedJarLoader(jar5304, new TextureAtlasUiListTransformer())) {
            final Constructor<?> nativeCtor = nativeLoader
                    .loadClass("com.live2d.graphics3d.type.d")
                    .getConstructor(float[].class, int[].class, AffineTransform.class);
            final Constructor<?> patchedCtor = patchedLoader
                    .loadClass("com.live2d.graphics3d.type.d")
                    .getConstructor(float[].class, int[].class, AffineTransform.class);

            final float[] pts = {0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f};
            final int[] tris = {0, 1, 2, 0, 2, 3};
            final AffineTransform xform = AffineTransform.getTranslateInstance(5.0, -3.0);

            final Path2D.Float expectedPath = (Path2D.Float)
                    nativeCtor.getDeclaringClass().getMethod("a").invoke(nativeCtor.newInstance(pts, tris, xform));
            final Path2D.Float actualPath = (Path2D.Float)
                    patchedCtor.getDeclaringClass().getMethod("a").invoke(patchedCtor.newInstance(pts, tris, xform));

            assertPathsEqual(expectedPath, actualPath);
        }
    }

    @Test
    void drawableGroupsAreComputedOncePerSessionAcrossMultiplePages() throws Throwable {
        final AtomicInteger computeCalls = new AtomicInteger();
        final Map<Object, List<Object>> cached = new HashMap<>();
        final MethodHandle compute = MethodHandles.lookup()
                .findStatic(
                        TextureAtlasPipelineParityTest.class,
                        "countDrawableGroupCompute",
                        MethodType.methodType(Object.class, Object.class, Object.class));
        final MethodHandle runPages = MethodHandles.lookup()
                .findStatic(
                        TextureAtlasPipelineParityTest.class,
                        "simulateMultiPageGroupCalls",
                        MethodType.methodType(void.class, Object.class));

        final MultiPageState state = new MultiPageState(computeCalls, cached, compute);
        EventQueue.invokeAndWait(() -> {
            try {
                TextureAtlasLoading.run(state, runPages);
            } catch (Throwable failure) {
                throw new RuntimeException(failure);
            }
        });
        assertEquals(1, computeCalls.get(), "Drawable groups map must only compute once per session");
    }

    @SuppressWarnings("unused")
    private static Object countDrawableGroupCompute(final Object helper, final Object modelSource) {
        final MultiPageState state = (MultiPageState) helper;
        state.calls().incrementAndGet();
        return state.map();
    }

    @SuppressWarnings("unused")
    private static void simulateMultiPageGroupCalls(final Object rawState) throws Throwable {
        final MultiPageState state = (MultiPageState) rawState;
        for (int page = 0; page < 5; page++) {
            final Object got = TextureAtlasLoading.resolveDrawableGroups(state, rawState, null, state.compute());
            assertSame(state.map(), got);
        }
    }

    @SuppressWarnings("unused")
    private static HashMap<?, ?> returnDrawables(final Object helper, final Object modelSource) {
        return new HashMap<>((Map<?, ?>) helper);
    }

    @SuppressWarnings("unused")
    private static void runFastInit(final Object rawState) throws Throwable {
        final InitState state = (InitState) rawState;
        assertTrue(TextureAtlasLoading.isInitializing());
        TextureAtlasLoading.resolveDrawableGroups(
                state.drawables(), new Object(), state.meshOpsClass(), state.compute());
        assertTrue(
                TextureAtlasLoading.meshBatch().isPrepared(state.meshes().get(0)),
                "Large ArtMesh must be scheduled for off-EDT preparation");
        assertTrue(
                TextureAtlasLoading.meshBatch().isPrepared(state.meshes().get(1)),
                "Large ArtPath must be scheduled for off-EDT preparation");
        for (final Object mesh : state.meshes()) {
            solveNativeMesh(mesh);
        }
    }

    private static void solveNativeMesh(final Object mesh) throws Exception {
        final Class<?> meshClass = mesh.getClass();
        meshClass.getMethod("getGlPositions").invoke(mesh);
        for (final Method m : meshClass.getMethods()) {
            if ("getGlIndices$default".equals(m.getName()) && m.getParameterCount() == 5) {
                m.invoke(null, mesh, null, false, 3, null);
                return;
            }
        }
        throw new IllegalStateException("Missing getGlIndices$default");
    }

    private static Object createGridMesh(
            final ClassLoader loader, final int side, final boolean delaunay, final boolean includeNotInitializedEdge)
            throws Exception {
        final Class<?> meshClass = loader.loadClass("com.live2d.graphics3d.editableMesh.GEditableMesh2");
        final Class<?> pointTypeClass = loader.loadClass("com.live2d.graphics3d.editableMesh.MPoint2$PointType");
        final Class<?> edgeTypeClass = loader.loadClass("com.live2d.graphics3d.editableMesh.MEdge$EdgeType");
        final Object mesh = meshClass.getConstructor().newInstance();
        meshClass.getMethod("setUseDelaunayTriangulation", boolean.class).invoke(mesh, delaunay);

        final Object normalPt = pointTypeClass.getField("NORMAL").get(null);
        final Object normalEdge = edgeTypeClass.getField("NORMAL").get(null);
        final Object lockedEdge = edgeTypeClass.getField("LOCKED").get(null);
        final Object userTriEdge = edgeTypeClass.getField("USER_TRIANGULATION").get(null);
        final Object autoTriEdge = edgeTypeClass.getField("AUTO_TRIANGULATION").get(null);
        final Object notInitEdge = edgeTypeClass.getField("NOT_INITIALIZED").get(null);

        final Method addPoint = meshClass.getMethod("addPoint", float.class, float.class, pointTypeClass, long.class);
        final Method addEdge =
                meshClass.getMethod("addEdgeIfNotExists", int.class, int.class, edgeTypeClass, boolean.class);

        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                final float jitter = ((x + y) % 2 == 0) ? 0.5f : -0.35f;
                addPoint.invoke(mesh, x * POINT_SPACING + jitter, y * POINT_SPACING - jitter, normalPt, -1L);
            }
        }
        for (int x = 0; x < side - 1; x++) {
            addEdge.invoke(mesh, x, x + 1, lockedEdge, false);
            addEdge.invoke(mesh, (side - 1) * side + x, (side - 1) * side + x + 1, normalEdge, false);
        }
        for (int y = 0; y < side - 1; y++) {
            addEdge.invoke(mesh, y * side, (y + 1) * side, normalEdge, false);
            addEdge.invoke(mesh, y * side + side - 1, (y + 1) * side + side - 1, normalEdge, false);
        }
        if (side >= GRID_SIDE) {
            // Inner cutout polygon + pre-existing typed edges + stale auto edge.
            final int c = 3;
            addEdge.invoke(mesh, c * side + c, c * side + c + 1, userTriEdge, false);
            addEdge.invoke(mesh, c * side + c + 1, (c + 1) * side + c + 1, normalEdge, false);
            addEdge.invoke(mesh, (c + 1) * side + c + 1, (c + 1) * side + c, normalEdge, false);
            addEdge.invoke(mesh, (c + 1) * side + c, c * side + c, normalEdge, false);
            addEdge.invoke(mesh, 1, side + 1, autoTriEdge, false);
            if (includeNotInitializedEdge) {
                addEdge.invoke(mesh, 0, side + 1, notInitEdge, false);
            }
        }
        return mesh;
    }

    private static Object buildDrawableHolder(
            final ClassLoader loader, final Object editableMesh, final boolean directField) throws Exception {
        try {
            final Class<?> artMeshClass =
                    loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource");
            final Object artMesh = artMeshClass.getConstructor().newInstance();
            if (directField) {
                artMeshClass
                        .getMethod("setEditableMesh", editableMesh.getClass())
                        .invoke(artMesh, editableMesh);
                return artMesh;
            }
            final Class<?> extClass =
                    loader.loadClass("com.live2d.cubism.doc.model.extension.editableMesh.CEditableMeshExtension");
            final Object ext = extClass.getConstructor().newInstance();
            final Field meshField = findDeclaredField(extClass, "editableMesh");
            meshField.set(ext, editableMesh);
            @SuppressWarnings("unchecked")
            final List<Object> exts =
                    (List<Object>) artMeshClass.getMethod("getExtensions").invoke(artMesh);
            exts.add(ext);
            return artMesh;
        } catch (LinkageError noJdomInStandaloneJar) {
            return buildArtPathHolder(loader, editableMesh);
        }
    }

    private static Object buildArtPathHolder(final ClassLoader loader, final Object editableMesh) throws Exception {
        final Class<?> modelSourceClass = loader.loadClass("com.live2d.cubism.doc.model.CModelSource");
        final Class<?> artPathClass = loader.loadClass("com.live2d.cubism.doc.model.drawable.artPath.CArtPathSource");
        final Class<?> brushClass = loader.loadClass("com.live2d.cubism.doc.model.drawable.artPath.Line.CArtPathBrush");
        final Class<?> brushIdClass = loader.loadClass("com.live2d.cubism.doc.model.id.CArtPathBrushId");
        final Class<?> brushGuidClass = loader.loadClass("com.live2d.type.CArtPathBrushGuid");
        final Class<?> defaultMarkerClass = loader.loadClass("kotlin.jvm.internal.DefaultConstructorMarker");
        final Class<?> inputClass =
                loader.loadClass("com.live2d.cubism.doc.model.drawable.artPath.Line.CArtPathBrush_TextureInput");

        final Object modelSource = modelSourceClass.getConstructor().newInstance();
        final Object brush = brushClass
                .getConstructor(modelSourceClass, String.class, brushIdClass, int.class, defaultMarkerClass)
                .newInstance(modelSource, "brush", null, 4, null);
        final Object brushGuid = brushGuidClass.getConstructor(UUID.class).newInstance(UUID.randomUUID());
        brushClass.getMethod("setGuid", brushGuidClass).invoke(brush, brushGuid);
        final Object input = brushClass.getMethod("getTextureInput").invoke(brush);
        findDeclaredField(inputClass, "editableMesh").set(input, editableMesh);

        final Object brushSetting =
                modelSourceClass.getMethod("getArtPathBrushesSetting").invoke(modelSource);
        @SuppressWarnings("unchecked")
        final List<Object> brushes =
                (List<Object>) brushSetting.getClass().getMethod("getBrushes").invoke(brushSetting);
        brushes.add(brush);

        final Object artPath = artPathClass.getConstructor().newInstance();
        artPathClass.getMethod("set_modelSource", modelSourceClass).invoke(artPath, modelSource);
        artPathClass.getMethod("setBrushGuid", brushGuidClass).invoke(artPath, brushGuid);
        return artPath;
    }

    private static Field findDeclaredField(final Class<?> type, final String name) {
        Class<?> cur = type;
        while (cur != null) {
            try {
                final Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new IllegalStateException("Field not found: " + name + " on " + type);
    }

    private static void assertContourListsEqual(final List<?> expected, final List<?> actual) {
        assertEquals(expected.size(), actual.size(), "Contour count mismatch");
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals((int[]) expected.get(i), (int[]) actual.get(i), "Contour[" + i + "] mismatch");
        }
    }

    private static void assertMeshStateEqual(final Object expectedMesh, final Object actualMesh) throws Exception {
        final Class<?> ec = expectedMesh.getClass();
        final Class<?> ac = actualMesh.getClass();
        assertEquals(
                ec.getMethod("get_edge_edit_version").invoke(expectedMesh),
                ac.getMethod("get_edge_edit_version").invoke(actualMesh),
                "_edge_edit_version mismatch");
        assertEquals(
                ec.getMethod("getCache_version_gl_indices$core").invoke(expectedMesh),
                ac.getMethod("getCache_version_gl_indices$core").invoke(actualMesh),
                "cache_version_gl_indices mismatch");
        assertArrayEquals(
                (int[]) ec.getMethod("getCached_indices$core").invoke(expectedMesh),
                (int[]) ac.getMethod("getCached_indices$core").invoke(actualMesh),
                "cached_indices mismatch");

        final List<?> expectedEdges = (List<?>) ec.getMethod("getEdges").invoke(expectedMesh);
        final List<?> actualEdges = (List<?>) ac.getMethod("getEdges").invoke(actualMesh);
        assertEquals(expectedEdges.size(), actualEdges.size(), "_edges count mismatch");
        for (int i = 0; i < expectedEdges.size(); i++) {
            final Object ee = expectedEdges.get(i);
            final Object ae = actualEdges.get(i);
            assertEquals(
                    ee.getClass().getMethod("getIndex1").invoke(ee),
                    ae.getClass().getMethod("getIndex1").invoke(ae));
            assertEquals(
                    ee.getClass().getMethod("getIndex2").invoke(ee),
                    ae.getClass().getMethod("getIndex2").invoke(ae));
            assertEquals(
                    ee.getClass().getMethod("getType").invoke(ee).toString(),
                    ae.getClass().getMethod("getType").invoke(ae).toString(),
                    "EdgeType mismatch at " + i);
        }
    }

    private static void assertPathsEqual(final Path2D.Float expected, final Path2D.Float actual) {
        assertEquals(expected.getWindingRule(), actual.getWindingRule());
        final PathIterator itE = expected.getPathIterator(null);
        final PathIterator itA = actual.getPathIterator(null);
        final float[] coordsE = new float[6];
        final float[] coordsA = new float[6];
        while (!itE.isDone() || !itA.isDone()) {
            assertFalse(itE.isDone());
            assertFalse(itA.isDone());
            assertEquals(itE.currentSegment(coordsE), itA.currentSegment(coordsA));
            assertArrayEquals(coordsE, coordsA);
            itE.next();
            itA.next();
        }
    }

    private static void populatePatternPixels(final Random rng, final int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            final int mode = rng.nextInt(EDGE_ALPHA_MOD);
            final int alpha;
            if (mode == 0) alpha = 0;
            else if (mode == 1) alpha = 1 + rng.nextInt(3);
            else if (mode <= 3) alpha = 4 + rng.nextInt(252);
            else alpha = 0;
            final int rgb = rng.nextInt(0x1_000_000);
            pixels[i] = (alpha << 24) | rgb;
        }
    }

    private static void referenceColumnMajorEdgeFill(
            final int[] pixels, final int width, final int height, final int distance) {
        for (int y = 0; y < height; y++) {
            int dist = 0;
            int rgb = -1;
            for (int x = 0; x < width; x++) {
                final int px = pixels[x + y * width];
                final int a = (px >>> 24) & 0xFF;
                if (a > 3) {
                    dist = 0;
                    rgb = px & 0x00FF_FFFF;
                } else if (rgb != -1 && dist < distance) {
                    pixels[x + y * width] = (px & 0xFF00_0000) | rgb;
                    if (a == 0) dist++;
                } else {
                    pixels[x + y * width] = 0x0080_8080;
                }
            }
            dist = 0;
            rgb = -1;
            for (int x = width - 1; x >= 0; x--) {
                final int px = pixels[x + y * width];
                final int a = (px >>> 24) & 0xFF;
                if (a > 3) {
                    dist = 0;
                    rgb = px & 0x00FF_FFFF;
                } else if (rgb != -1 && dist < distance) {
                    pixels[x + y * width] = (px & 0xFF00_0000) | rgb;
                    if (a == 0) dist++;
                }
            }
        }
        for (int x = 0; x < width; x++) {
            int dist = 0;
            int rgb = -1;
            for (int y = 0; y < height; y++) {
                final int px = pixels[x + y * width];
                final int a = (px >>> 24) & 0xFF;
                if (a > 3) {
                    dist = 0;
                    rgb = px & 0x00FF_FFFF;
                } else if (rgb != -1 && dist < distance) {
                    pixels[x + y * width] = (px & 0xFF00_0000) | rgb;
                    if (a == 0) dist++;
                }
            }
            dist = 0;
            rgb = -1;
            for (int y = height - 1; y >= 0; y--) {
                final int px = pixels[x + y * width];
                final int a = (px >>> 24) & 0xFF;
                if (a > 3) {
                    dist = 0;
                    rgb = px & 0x00FF_FFFF;
                } else if (rgb != -1 && dist < distance) {
                    pixels[x + y * width] = (px & 0xFF00_0000) | rgb;
                    if (a == 0) dist++;
                }
            }
        }
    }

    private record InitState(
            Map<Object, List<Object>> drawables, Class<?> meshOpsClass, MethodHandle compute, List<Object> meshes) {}

    private record PreviewInitState(
            ClassLoader loader, BufferedImage largeSrc, BufferedImage smallSrc, List<Object> out) {}

    private record MultiPageState(AtomicInteger calls, Map<Object, List<Object>> map, MethodHandle compute) {}

    private static Path hostJar(final String version) {
        final String prop = System.getProperty(REF_PROPERTY);
        final Path root = prop != null && !prop.isBlank()
                ? Path.of(prop)
                : System.getenv(REF_ENV) != null && !System.getenv(REF_ENV).isBlank()
                        ? Path.of(System.getenv(REF_ENV))
                        : Path.of(System.getProperty("user.home"), ARTIFACTS_DIR_NAME);
        return root.resolve(version).resolve(JAR_NAME);
    }

    private static final class IsolatedJarLoader extends URLClassLoader {
        private final Path jarPath;
        private final JarFile hostJar;
        private final ProtectionDomain hostDomain;
        private final java.lang.instrument.ClassFileTransformer transformer;
        private final ClassLoader testParent;

        IsolatedJarLoader(final Path jarPath, final java.lang.instrument.ClassFileTransformer transformer)
                throws Exception {
            super(siblingUrls(jarPath), ClassLoader.getPlatformClassLoader());
            this.jarPath = jarPath;
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.hostDomain =
                    new ProtectionDomain(new CodeSource(jarPath.toUri().toURL(), (Certificate[]) null), null);
            this.transformer = transformer;
            this.testParent = TextureAtlasPipelineParityTest.class.getClassLoader();
        }

        private static URL[] siblingUrls(final Path jarPath) throws IOException {
            final List<URL> urls = new ArrayList<>();
            boolean hasJdom = false;
            boolean hasLog4j = false;
            try (var files = Files.walk(jarPath.getParent(), 2)) {
                for (final Path file : files.toList()) {
                    if (Files.isRegularFile(file) && file.toString().endsWith(JAR_SUFFIX) && !file.equals(jarPath)) {
                        urls.add(file.toUri().toURL());
                        final String name = file.getFileName().toString();
                        if (JDOM_JAR_NAME.equals(name)) hasJdom = true;
                        if (name.startsWith("log4j-")) hasLog4j = true;
                    }
                }
            }
            if (!hasJdom || !hasLog4j) {
                final String version = jarPath.getParent().getFileName().toString();
                addOptionalRefJars(version, urls);
            }
            return urls.toArray(URL[]::new);
        }

        private static void addOptionalRefJars(final String version, final List<URL> urls) throws IOException {
            final String prop = System.getProperty(FULL_REF_PROPERTY);
            final String env = System.getenv(FULL_REF_ENV);
            final List<Path> roots = new ArrayList<>();
            if (prop != null && !prop.isBlank()) roots.add(Path.of(prop));
            if (env != null && !env.isBlank()) roots.add(Path.of(env));
            roots.add(DEFAULT_FULL_REF_ROOT);
            for (final Path root : roots) {
                for (final Path dir :
                        List.of(root.resolve(version), root.resolve(version).resolve("jars"))) {
                    if (!Files.isDirectory(dir)) continue;
                    try (var stream = Files.list(dir)) {
                        for (final Path candidate : stream.sorted().toList()) {
                            final String name = candidate.getFileName().toString();
                            if (Files.isRegularFile(candidate)
                                    && name.endsWith(JAR_SUFFIX)
                                    && (name.startsWith("jdom") || name.startsWith("log4j"))) {
                                urls.add(candidate.toUri().toURL());
                            }
                        }
                    }
                    return;
                }
            }
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String internal = name.replace('.', '/');
            final JarEntry entry = hostJar.getJarEntry(internal + CLASS_SUFFIX);
            if (entry != null) {
                try (InputStream in = hostJar.getInputStream(entry)) {
                    final byte[] original = in.readAllBytes();
                    byte[] bytes = original;
                    if (transformer != null) {
                        final byte[] patched = transformer.transform(this, internal, null, null, original);
                        if (patched != null) {
                            bytes = patched;
                        }
                    }
                    return defineClass(name, bytes, 0, bytes.length, hostDomain);
                } catch (IOException | java.lang.instrument.IllegalClassFormatException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            try {
                return super.findClass(name);
            } catch (ClassNotFoundException ignored) {
                try {
                    return testParent.loadClass(name);
                } catch (ClassNotFoundException absent) {
                    if (name.startsWith(STUB_PACKAGE_PREFIX)) {
                        final ClassWriter cw = new ClassWriter(0);
                        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
                        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                        init.visitCode();
                        init.visitVarInsn(Opcodes.ALOAD, 0);
                        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                        init.visitInsn(Opcodes.RETURN);
                        init.visitMaxs(1, 1);
                        init.visitEnd();
                        cw.visitEnd();
                        final byte[] stub = cw.toByteArray();
                        return defineClass(name, stub, 0, stub.length);
                    }
                    throw absent;
                }
            }
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("dev.turboism.")) {
                return testParent.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL findResource(final String name) {
            final URL hostUrl = hostJarResourceUrl(name);
            return hostUrl != null ? hostUrl : super.findResource(name);
        }

        @Override
        public java.util.Enumeration<URL> findResources(final String name) throws IOException {
            final List<URL> urls = new ArrayList<>();
            final URL hostUrl = hostJarResourceUrl(name);
            if (hostUrl != null) {
                urls.add(hostUrl);
            }
            final var delegate = super.findResources(name);
            while (delegate.hasMoreElements()) {
                urls.add(delegate.nextElement());
            }
            return java.util.Collections.enumeration(urls);
        }

        private URL hostJarResourceUrl(final String name) {
            if (hostJar.getJarEntry(name) == null) {
                return null;
            }
            try {
                return new URL("jar", "", jarPath.toUri().toURL() + "!/" + name);
            } catch (java.net.MalformedURLException ignored) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                hostJar.close();
            }
        }
    }
}
