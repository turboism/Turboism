package dev.turboism.adapter.cubism.optimization.save;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

import dev.turboism.adapter.cubism.exportperf.RuntimeExportAccelerationTransformer;
import dev.turboism.adapter.cubism.modelopen.ModelOpenAccelerationTransformer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies save-path bytecode transformation, multi-transformer coexistence, strict
 * {@code -Xverify:all} class verification across all 21 Cubism JARs, and real host serialization
 * round-trips with JDOM and Cubism archiver classes.
 */
public final class SaveOptimizationRealHostVerificationTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_SUFFIX = ".jar";
    private static final String CLASS_SUFFIX = ".class";
    private static final String JAR_5305_REL =
            ".local/share/cubism-reference/5.3.05/proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/"
                    + JAR_NAME;
    private static final int EXPECTED_TOTAL_JARS = 21;
    private static final long VERIFY_TIMEOUT_SECONDS = 90L;

    private static final String ARCHIVE_WRITER = "com/live2d/serialize/archiver/ArchiveWriter";
    private static final String ARCHIVE_READER = "com/live2d/serialize/archiver/ArchiveReader";
    private static final String COMPRESS_OPTION = "com/live2d/serialize/CompressOption";
    private static final String WRITABLE_IMAGE = "com/live2d/graphics/CWritableImage";
    private static final String IMAGE_RESOURCE = "com/live2d/graphics/CImageResource";
    private static final String XML_WRITER = "com/live2d/serialize/XmlWriter";

    private static final List<String> SAVE_TARGETS = List.of(
            ARCHIVE_WRITER,
            BufferedRandomAccessPatcher.TARGET,
            StreamingXmlArchivePatcher.TARGET,
            PrimitiveArraySerializerPatcher.FLOAT_SERIALIZER,
            PrimitiveArraySerializerPatcher.INT_SERIALIZER,
            UtImagePngPatcher.TARGET);

    private static final List<String> COEXISTENCE_TARGETS = List.of(
            PrimitiveArraySerializerPatcher.FLOAT_SERIALIZER,
            PrimitiveArraySerializerPatcher.INT_SERIALIZER,
            UtImagePngPatcher.TARGET);

    @Test
    void patchesSaveTargetsVerifyBytecodeAndEnforcesIdempotencyAcrossTwentyOneJars(@TempDir final Path tempDir)
            throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(
                jars.size() == EXPECTED_TOTAL_JARS,
                "All 21 Cubism JARs (5.0.00-5.3.05) must exist locally; found " + jars.size());

        final RuntimeExportAccelerationTransformer transformer = new RuntimeExportAccelerationTransformer();
        final List<String> manifestLines = new ArrayList<>();

        for (final Path jarPath : jars) {
            final String version = versionLabel(jarPath);
            final Path outRoot = tempDir.resolve("save-" + version);
            Files.createDirectories(outRoot);

            try (JarFile jar = new JarFile(jarPath.toFile(), false);
                    URLClassLoader hostLoader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                for (final String internalName : SAVE_TARGETS) {
                    final byte[] original = readEntry(jar, internalName);
                    final byte[] patched = transformer.transform(hostLoader, internalName, null, null, original);
                    assertNotNull(patched, "Expected save patch bytes for " + internalName + " in " + version);
                    assertNull(
                            transformer.transform(hostLoader, internalName, null, null, patched),
                            "Save patch must be idempotent for " + internalName + " in " + version);
                    writeClass(outRoot, internalName, patched);
                    manifestLines.add(version + "\t" + jarPath + "\t" + outRoot + "\t" + internalName);
                }
            }
        }

        final Path manifestList = tempDir.resolve("save-manifest.txt");
        Files.write(manifestList, manifestLines);
        runStrictBytecodeVerifierSubprocess(manifestList);
    }

    @Test
    void chainsWithModelOpenAndSimulatedIntArrayLoadPatchInBothOrders(@TempDir final Path tempDir) throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(
                jars.size() == EXPECTED_TOTAL_JARS,
                "All 21 Cubism JARs (5.0.00-5.3.05) must exist locally; found " + jars.size());

        final ModelOpenAccelerationTransformer openTransformer = new ModelOpenAccelerationTransformer();
        final RuntimeExportAccelerationTransformer exportTransformer = new RuntimeExportAccelerationTransformer();
        final List<String> manifestLines = new ArrayList<>();

        for (final Path jarPath : jars) {
            final String version = versionLabel(jarPath);
            final Path openThenExportDir = tempDir.resolve("open-first-" + version);
            final Path exportThenOpenDir = tempDir.resolve("export-first-" + version);
            Files.createDirectories(openThenExportDir);
            Files.createDirectories(exportThenOpenDir);

            try (JarFile jar = new JarFile(jarPath.toFile(), false);
                    URLClassLoader hostLoader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                for (final String internalName : COEXISTENCE_TARGETS) {
                    final byte[] original = readEntry(jar, internalName);

                    final byte[] openFirst =
                            applyChain(hostLoader, internalName, original, openTransformer, exportTransformer);
                    writeClass(openThenExportDir, internalName, openFirst);
                    manifestLines.add(version + "-OF\t" + jarPath + "\t" + openThenExportDir + "\t" + internalName);

                    final byte[] exportFirst =
                            applyChain(hostLoader, internalName, original, exportTransformer, openTransformer);
                    writeClass(exportThenOpenDir, internalName, exportFirst);
                    manifestLines.add(version + "-EO\t" + jarPath + "\t" + exportThenOpenDir + "\t" + internalName);
                }

                // Chain simulated int[] load patch on impl/H.a(int, List) with createElement in both orders.
                final byte[] originalH = readEntry(jar, PrimitiveArraySerializerPatcher.INT_SERIALIZER);
                final byte[] loadThenSave = exportTransformer.transform(
                        hostLoader,
                        PrimitiveArraySerializerPatcher.INT_SERIALIZER,
                        null,
                        null,
                        simulateIntLoadPatch(hostLoader, originalH));
                assertNotNull(loadThenSave);
                assertNull(exportTransformer.transform(
                        hostLoader, PrimitiveArraySerializerPatcher.INT_SERIALIZER, null, null, loadThenSave));
                final Path hLoadFirstDir = tempDir.resolve("h-lf-" + version);
                writeClass(hLoadFirstDir, PrimitiveArraySerializerPatcher.INT_SERIALIZER, loadThenSave);
                manifestLines.add(version + "-HLF\t" + jarPath + "\t" + hLoadFirstDir + "\t"
                        + PrimitiveArraySerializerPatcher.INT_SERIALIZER);

                final byte[] saveFirst = exportTransformer.transform(
                        hostLoader, PrimitiveArraySerializerPatcher.INT_SERIALIZER, null, null, originalH);
                final byte[] saveThenLoad = simulateIntLoadPatch(hostLoader, saveFirst);
                assertNull(exportTransformer.transform(
                        hostLoader, PrimitiveArraySerializerPatcher.INT_SERIALIZER, null, null, saveThenLoad));
                final Path hSaveFirstDir = tempDir.resolve("h-sf-" + version);
                writeClass(hSaveFirstDir, PrimitiveArraySerializerPatcher.INT_SERIALIZER, saveThenLoad);
                manifestLines.add(version + "-HSF\t" + jarPath + "\t" + hSaveFirstDir + "\t"
                        + PrimitiveArraySerializerPatcher.INT_SERIALIZER);
            }
        }

        final Path manifestList = tempDir.resolve("chain-manifest.txt");
        Files.write(manifestList, manifestLines);
        runStrictBytecodeVerifierSubprocess(manifestList);
    }

    @Test
    void executesPatchedHostClassesEndToEndWithRealJdomAndArchiveRoundTrip(@TempDir final Path tempDir)
            throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(!jars.isEmpty(), "Host Cubism JAR absent");
        final Path jarPath = jars.get(jars.size() - 1);

        final RuntimeExportAccelerationTransformer transformer = new RuntimeExportAccelerationTransformer();
        final Path patchRoot = tempDir.resolve("patched-5305");
        Files.createDirectories(patchRoot);

        try (JarFile jar = new JarFile(jarPath.toFile(), false);
                URLClassLoader hostLoader =
                        new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            final List<String> runtimeTargets = new ArrayList<>(SAVE_TARGETS);
            runtimeTargets.add(IMAGE_RESOURCE);
            runtimeTargets.add(WRITABLE_IMAGE);
            for (final String target : runtimeTargets) {
                final byte[] original = readEntry(jar, target);
                final byte[] patched = transformer.transform(hostLoader, target, null, null, original);
                assertNotNull(patched, "Patch failed for " + target);
                writeClass(patchRoot, target, patched);
            }
        }

        try (PatchedJarLoader loader =
                new PatchedJarLoader(jarPath, patchRoot, getClass().getClassLoader())) {
            final Class<?> archiveFormatClass = load(loader, "com.live2d.serialize.archiver.ArchiveFormat");
            final Object archiveFormat = archiveFormatClass.getField("INSTANCE").get(null);
            final char[] archiveFmt =
                    (char[]) archiveFormatClass.getMethod("getARCHIVE_FORMAT").invoke(archiveFormat);
            final int[] archiveVer =
                    (int[]) archiveFormatClass.getMethod("getARCHIVE_VERSION").invoke(archiveFormat);

            // 1. Real impl.G and impl.H createElement with float[] and int[].
            final Class<?> xmlWriterClass = load(loader, XML_WRITER);
            final Object xmlWriter = xmlWriterClass
                    .getConstructor(boolean.class, char[].class, int[].class, HashMap.class)
                    .newInstance(false, archiveFmt, archiveVer, new HashMap<>());

            final Class<?> floatSerClass = load(loader, PrimitiveArraySerializerPatcher.FLOAT_SERIALIZER);
            final Constructor<?> floatSerCtor = floatSerClass.getDeclaredConstructor(String.class);
            floatSerCtor.setAccessible(true);
            final Object floatSer = floatSerCtor.newInstance(" ");
            final float[] floats = {0.0f, -0.0f, Float.NaN, Float.POSITIVE_INFINITY, 1e-7f, 42.5f};
            final Object floatElem = floatSerClass
                    .getMethod("createElement", String.class, Object.class, xmlWriterClass)
                    .invoke(floatSer, "pts", floats, xmlWriter);
            final String floatText =
                    (String) floatElem.getClass().getMethod("getText").invoke(floatElem);
            assertEquals(PrimitiveArraySerializerBridge.formatFloatArray(floats, " "), floatText);

            final Class<?> intSerClass = load(loader, PrimitiveArraySerializerPatcher.INT_SERIALIZER);
            final Constructor<?> intSerCtor = intSerClass.getDeclaredConstructor(String.class);
            intSerCtor.setAccessible(true);
            final Object intSer = intSerCtor.newInstance(" ");
            final int[] ints = {Integer.MIN_VALUE, -1, 0, 1, 2048, Integer.MAX_VALUE};
            final Object intElem = intSerClass
                    .getMethod("createElement", String.class, Object.class, xmlWriterClass)
                    .invoke(intSer, "idx", ints, xmlWriter);
            final String intText =
                    (String) intElem.getClass().getMethod("getText").invoke(intElem);
            assertEquals(PrimitiveArraySerializerBridge.formatIntArray(ints, " "), intText);

            // 2. Build large JDOM document (> 600 KB -> triggers multi-chunk parallel ZIP deflate) and
            // stream through ArchiveWriter + Buffered RandomAccess$d, then verify with ArchiveReader.
            final Class<?> elemClass = load(loader, "org.jdom.Element");
            final Class<?> docClass = load(loader, "org.jdom.Document");
            final Class<?> fmtClass = load(loader, "org.jdom.output.Format");
            final Class<?> outClass = load(loader, "org.jdom.output.XMLOutputter");

            final Object root = elemClass.getConstructor(String.class).newInstance("model");
            final Method addContent = elemClass.getMethod("addContent", load(loader, "org.jdom.Content"));
            final Method setText = elemClass.getMethod("setText", String.class);
            final float[] bigMesh = new float[80_000];
            for (int i = 0; i < bigMesh.length; i++) {
                bigMesh[i] = (i - 40_000) * 0.125f;
            }
            final String bigFloatText = PrimitiveArraySerializerBridge.formatFloatArray(bigMesh, " ");
            final Object meshElem = elemClass.getConstructor(String.class).newInstance("vertices");
            setText.invoke(meshElem, bigFloatText);
            addContent.invoke(root, meshElem);
            final Object document = docClass.getConstructor(elemClass).newInstance(root);

            final Object prettyFormat = fmtClass.getMethod("getPrettyFormat").invoke(null);
            fmtClass.getMethod("setIndent", String.class).invoke(prettyFormat, "");
            final Object outputter = outClass.getConstructor(fmtClass).newInstance(prettyFormat);
            final ByteArrayOutputStream expectedXmlStream = new ByteArrayOutputStream();
            outClass.getMethod("output", docClass, java.io.OutputStream.class)
                    .invoke(outputter, document, expectedXmlStream);
            final byte[] expectedXmlBytes = expectedXmlStream.toByteArray();
            assertTrue(expectedXmlBytes.length > 600_000);

            final Class<?> optionClass = load(loader, COMPRESS_OPTION);
            final Object fastOption = Enum.valueOf(optionClass.asSubclass(Enum.class), "FAST");
            final Object rawOption = Enum.valueOf(optionClass.asSubclass(Enum.class), "RAW");

            final byte[] pngBytes = createTestPng(BufferedImage.TYPE_4BYTE_ABGR, 64, 48);
            final byte[] pngSnapshot = pngBytes.clone();

            for (final boolean obfuscate : new boolean[] {false, true}) {
                final File archiveFile =
                        tempDir.resolve("roundtrip-obf-" + obfuscate + ".dat").toFile();
                final Class<?> writerClass = load(loader, ARCHIVE_WRITER);
                final Object writer = writerClass
                        .getConstructor(boolean.class, char[].class, int[].class)
                        .newInstance(obfuscate, archiveFmt, archiveVer);

                StreamingXmlArchiveBridge.captureDocumentForArchive(null, document, "", true);
                final String xmlEntryPath =
                        StreamingXmlArchiveBridge.addMainXmlEntry(writer, "main.xml", "main_xml", fastOption, null);
                assertEquals("main.xml", xmlEntryPath);

                final Method addBytes = writerClass.getMethod(
                        "turboism$addEntryBytes", String.class, String.class, optionClass, byte[].class);
                addBytes.invoke(writer, "images/tex_0.png", "image", rawOption, pngBytes);

                writerClass.getMethod("write", File.class).invoke(writer, archiveFile);
                assertArrayEquals(pngSnapshot, pngBytes, "Obfuscated write must not mutate owned PNG byte[]");
                assertTrue((boolean)
                        writerClass.getMethod("verifyAfterWrite", File.class).invoke(writer, archiveFile));

                final Class<?> readerClass = load(loader, ARCHIVE_READER);
                final Object reader = readerClass.getConstructor(File.class).newInstance(archiveFile);
                final Class<?> fn1Class = load(loader, "kotlin.jvm.functions.Function1");
                final byte[][] readXml = new byte[1][];
                readerClass
                        .getMethod("readFileByFilePath", String.class, fn1Class)
                        .invoke(
                                reader,
                                "main.xml",
                                createReadLambda(loader, fn1Class, in -> readXml[0] = in.readAllBytes()));
                assertArrayEquals(expectedXmlBytes, readXml[0], "XML payload mismatch (obf=" + obfuscate + ")");

                final byte[][] readPng = new byte[1][];
                readerClass
                        .getMethod("readFileByFilePath", String.class, fn1Class)
                        .invoke(
                                reader,
                                "images/tex_0.png",
                                createReadLambda(loader, fn1Class, in -> readPng[0] = in.readAllBytes()));
                assertArrayEquals(pngBytes, readPng[0], "RAW PNG payload mismatch (obf=" + obfuscate + ")");
                readerClass.getMethod("close").invoke(reader);
            }

            // 3. Multi-threaded ImageSavePreEncoder + full UUSerialize.serializeToFile / deserializeFromFile.
            verifyPreEncodeOnRealModelSource(loader, tempDir, optionClass, fastOption);
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyPreEncodeOnRealModelSource(
            final ClassLoader loader, final Path tempDir, final Class<?> optionClass, final Object fastOption)
            throws Exception {
        final Class<?> modelSourceClass = load(loader, "com.live2d.cubism.doc.model.CModelSource");
        final Class<?> resClass = load(loader, IMAGE_RESOURCE);
        final Class<?> writableClass = load(loader, WRITABLE_IMAGE);
        final Class<?> atlasClass = load(loader, "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas");
        final Class<?> texImgClass = load(loader, "com.live2d.cubism.doc.model.texture.CTextureImage");
        final Class<?> uuSerializeClass = load(loader, StreamingXmlArchivePatcher.TARGET);

        final Object modelSource = modelSourceClass.getConstructor().newInstance();
        final Object texMgr = modelSourceClass.getMethod("getTextureManager").invoke(modelSource);

        final BufferedImage imgA = createSampleImage(BufferedImage.TYPE_INT_ARGB, 64, 64, 0x112233L);
        final BufferedImage imgB = createSampleImage(BufferedImage.TYPE_3BYTE_BGR, 64, 64, 0x445566L);
        final Object resA =
                resClass.getConstructor(BufferedImage.class, boolean.class).newInstance(imgA, false);
        final Object resB =
                resClass.getConstructor(BufferedImage.class, boolean.class).newInstance(imgB, false);

        final Object atlas = atlasClass.getConstructor().newInstance();
        atlasClass.getMethod("setCachedAtlasImage", resClass).invoke(atlas, resA);
        texMgr.getClass().getMethod("addTextureAtlas", atlasClass, int.class).invoke(texMgr, atlas, -1);

        final Object texImg = texImgClass.getConstructor().newInstance();
        texImgClass.getMethod("setImageResource", resClass).invoke(texImg, resB);
        final Object texGroup = texMgr.getClass().getMethod("getTextureList").invoke(texMgr);
        final List<Object> children =
                (List<Object>) texGroup.getClass().getMethod("getChildren").invoke(texGroup);
        children.add(texImg);

        final Field imageFileBufField = resClass.getDeclaredField("imageFileBuf");
        imageFileBufField.setAccessible(true);
        assertNull(imageFileBufField.get(resA));
        assertNull(imageFileBufField.get(resB));

        final File cmoFile = tempDir.resolve("model-roundtrip.cmo3").toFile();
        final Object uuSerialize = uuSerializeClass.getField("INSTANCE").get(null);
        final boolean saved = (boolean) uuSerializeClass
                .getMethod(
                        "serializeToFile",
                        File.class,
                        Object.class,
                        boolean.class,
                        optionClass,
                        writableClass,
                        HashMap.class,
                        boolean.class,
                        int.class)
                .invoke(uuSerialize, cmoFile, modelSource, false, fastOption, null, new HashMap<>(), false, -1);
        assertTrue(saved, "Full UUSerialize.serializeToFile + verifyAfterWrite must succeed");

        final byte[] bufA = (byte[]) imageFileBufField.get(resA);
        final byte[] bufB = (byte[]) imageFileBufField.get(resB);
        assertNotNull(bufA, "Dirty atlas CImageResource should be pre-encoded during serializeToFile");
        assertNotNull(bufB, "Dirty texture CImageResource should be pre-encoded during serializeToFile");

        final BufferedImage decA = ImageIO.read(new java.io.ByteArrayInputStream(bufA));
        final BufferedImage decB = ImageIO.read(new java.io.ByteArrayInputStream(bufB));
        assertNotNull(decA);
        assertNotNull(decB);
        assertArrayEquals(imgA.getRGB(0, 0, 64, 64, null, 0, 64), decA.getRGB(0, 0, 64, 64, null, 0, 64));
        assertArrayEquals(imgB.getRGB(0, 0, 64, 64, null, 0, 64), decB.getRGB(0, 0, 64, 64, null, 0, 64));

        final Object reloaded = uuSerializeClass
                .getMethod("deserializeFromFile", File.class, HashMap.class, int.class, boolean.class)
                .invoke(uuSerialize, cmoFile, new HashMap<>(), -1, false);
        assertNotNull(reloaded);
        assertTrue(modelSourceClass.isInstance(reloaded));
    }

    private static byte[] applyChain(
            final ClassLoader loader,
            final String internalName,
            final byte[] original,
            final java.lang.instrument.ClassFileTransformer first,
            final java.lang.instrument.ClassFileTransformer second)
            throws Exception {
        byte[] current = original;
        final byte[] step1 = first.transform(loader, internalName, null, null, current);
        if (step1 != null) {
            current = step1;
        }
        final byte[] step2 = second.transform(loader, internalName, null, null, current);
        if (step2 != null) {
            current = step2;
        }
        assertNull(first.transform(loader, internalName, null, null, current));
        assertNull(second.transform(loader, internalName, null, null, current));
        return current;
    }

    private static byte[] simulateIntLoadPatch(final ClassLoader loader, final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = SavePerfClassWriters.newFrameWriter(reader, loader);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!"a".equals(name) || !"(ILjava/util/List;)Ljava/lang/Object;".equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(ILOAD, 1);
                                super.visitVarInsn(ALOAD, 2);
                                super.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "hash", "(I)I", false);
                                super.visitInsn(org.objectweb.asm.Opcodes.POP);
                                super.visitInsn(org.objectweb.asm.Opcodes.POP);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] createTestPng(final int type, final int w, final int h) throws IOException {
        final BufferedImage img = createSampleImage(type, w, h, 0xCAFEBABEL);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageSaveFastPngSupport.trySavePng(img, out, "png"));
        return out.toByteArray();
    }

    private static BufferedImage createSampleImage(final int type, final int w, final int h, final long seed) {
        final BufferedImage img = new BufferedImage(w, h, type);
        final Random rng = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rng.nextInt());
            }
        }
        return img;
    }

    @FunctionalInterface
    private interface StreamReader {
        void accept(InputStream in) throws Exception;
    }

    private static Object createReadLambda(
            final ClassLoader loader, final Class<?> fn1Class, final StreamReader reader) {
        return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {fn1Class}, (proxy, method, args) -> {
            if ("invoke".equals(method.getName())
                    && args != null
                    && args.length == 1
                    && args[0] instanceof InputStream in) {
                reader.accept(in);
                return null;
            }
            return method.invoke(proxy, args);
        });
    }

    private static Class<?> load(final ClassLoader loader, final String nameOrInternal) throws ClassNotFoundException {
        return Class.forName(nameOrInternal.replace('/', '.'), true, loader);
    }

    private static byte[] readEntry(final JarFile jar, final String internalName) throws IOException {
        final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
        assertNotNull(entry, "Missing entry: " + internalName);
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static void writeClass(final Path root, final String internalName, final byte[] bytes) throws IOException {
        final Path out = root.resolve(internalName + CLASS_SUFFIX);
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);
    }

    private static List<Path> discoverAllTwentyOneJars() throws IOException {
        final Path home = Path.of(System.getProperty("user.home"));
        final List<Path> jars = new ArrayList<>();
        final Path artifactsDir = home.resolve(ARTIFACTS_DIR_NAME);
        if (Files.isDirectory(artifactsDir)) {
            try (var dirs = Files.list(artifactsDir)) {
                dirs.filter(Files::isDirectory)
                        .sorted()
                        .map(dir -> dir.resolve(JAR_NAME))
                        .filter(Files::isRegularFile)
                        .forEach(jars::add);
            }
        }
        final Path jar5305 = home.resolve(JAR_5305_REL);
        if (Files.isRegularFile(jar5305)) {
            jars.add(jar5305);
        }
        return jars;
    }

    private static String versionLabel(final Path jarPath) {
        return jarPath.toString().contains(ARTIFACTS_DIR_NAME)
                ? jarPath.getParent().getFileName().toString()
                : "5.3.05";
    }

    private static void runStrictBytecodeVerifierSubprocess(final Path manifestList) throws Exception {
        final String javaExe =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final String classpath = System.getProperty("java.class.path");
        final ProcessBuilder pb = new ProcessBuilder(
                javaExe, "-Xverify:all", "-cp", classpath, ChildVerifier.class.getName(), manifestList.toString());
        pb.redirectErrorStream(true);
        final Process proc = pb.start();
        final String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proc.waitFor(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Verifier subprocess timed out");
        assertEquals(0, proc.exitValue(), "Strict -Xverify:all verification failed:\n" + output);
    }

    /** Child entrypoint run under {@code -Xverify:all} to link all patched classes strictly. */
    public static final class ChildVerifier {
        public static void main(final String[] args) throws Exception {
            final Path manifest = Path.of(args[0]);
            final List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            final ClassLoader platform = ClassLoader.getPlatformClassLoader();
            final ClassLoader runtime = ChildVerifier.class.getClassLoader();
            final HashMap<Path, PatchedJarLoader> loaders = new HashMap<>();
            try {
                for (final String line : lines) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final String[] parts = line.split("\t", 4);
                    final Path jarPath = Path.of(parts[1]);
                    final Path patchRoot = Path.of(parts[2]);
                    final String internalName = parts[3];
                    PatchedJarLoader loader = loaders.get(patchRoot);
                    if (loader == null) {
                        loader = new PatchedJarLoader(jarPath, patchRoot, runtime);
                        loaders.put(patchRoot, loader);
                    }
                    final Class<?> defined = Class.forName(internalName.replace('/', '.'), false, loader);
                    assertNotNull(defined, "Failed to verify " + parts[0] + " " + internalName);
                }
            } finally {
                for (final PatchedJarLoader loader : loaders.values()) {
                    loader.close();
                }
            }
            assertNotNull(platform);
        }
    }

    private static final class PatchedJarLoader extends URLClassLoader {

        private final JarFile hostJar;
        private final Path patchRoot;
        private final ClassLoader runtimeParent;
        private final ProtectionDomain hostDomain;

        private PatchedJarLoader(final Path jarPath, final Path patchRoot, final ClassLoader runtimeParent)
                throws IOException {
            super(discoverSiblingJars(jarPath), ClassLoader.getPlatformClassLoader());
            this.hostJar = new JarFile(jarPath.toFile(), false);
            this.patchRoot = patchRoot;
            this.runtimeParent = runtimeParent;
            this.hostDomain =
                    new ProtectionDomain(new CodeSource(jarPath.toUri().toURL(), (Certificate[]) null), null);
        }

        private static URL[] discoverSiblingJars(final Path jarPath) throws IOException {
            final List<URL> urls = new ArrayList<>();
            final Path home = Path.of(System.getProperty("user.home"));
            final Path libDir = home.resolve(JAR_5305_REL).getParent();
            if (Files.isDirectory(libDir)) {
                try (var files = Files.walk(libDir, 2)) {
                    for (final Path file : files.toList()) {
                        final String fname = file.getFileName().toString();
                        if (Files.isRegularFile(file) && fname.endsWith(JAR_SUFFIX) && !JAR_NAME.equals(fname)) {
                            urls.add(file.toUri().toURL());
                        }
                    }
                }
            }
            urls.add(jarPath.toUri().toURL());
            return urls.toArray(URL[]::new);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String internal = name.replace('.', '/');
            final Path patchedFile = this.patchRoot.resolve(internal + CLASS_SUFFIX);
            if (Files.isRegularFile(patchedFile)) {
                try {
                    final byte[] bytes = Files.readAllBytes(patchedFile);
                    return defineClass(name, bytes, 0, bytes.length, this.hostDomain);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            final JarEntry entry = this.hostJar.getJarEntry(internal + CLASS_SUFFIX);
            if (entry != null) {
                try (InputStream in = this.hostJar.getInputStream(entry)) {
                    final byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length, this.hostDomain);
                } catch (final IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            try {
                return super.findClass(name);
            } catch (final ClassNotFoundException ignored) {
                return this.runtimeParent.loadClass(name);
            }
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("dev.turboism.")) {
                return this.runtimeParent.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                this.hostJar.close();
            }
        }
    }
}
