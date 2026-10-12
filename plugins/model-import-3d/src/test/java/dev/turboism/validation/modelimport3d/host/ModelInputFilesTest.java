package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.ui.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ModelInputFilesTest {
    static final byte[] OBJ = text(
            "mtllib materials/paint.mtl\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nusemtl paint\nf 1/1 2/2 3/3 4/4\n");
    static final byte[] MTL = text("newmtl paint\nKd 1 1 1\nmap_Kd ../textures/paint.png\n");

    static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] image() throws Exception {
        var i = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        i.setRGB(0, 0, 0xffc01020);
        i.setRGB(1, 0, 0xff2040a0);
        i.setRGB(0, 1, 0xff10a020);
        i.setRGB(1, 1, 0xffe0b010);
        var o = new ByteArrayOutputStream();
        ImageIO.write(i, "png", o);
        return o.toByteArray();
    }

    static final class Handle implements UserFileHandle {
        final String id;
        boolean closed;

        Handle(int n) {
            id = "resource-" + n;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return "chosen-file";
        }

        public UserFileMode mode() {
            return UserFileMode.READ;
        }

        public UserFileLifetime lifetime() {
            return UserFileLifetime.ONE_OPERATION;
        }

        public UserFileHandleState state() {
            return closed ? UserFileHandleState.CLOSED : UserFileHandleState.ACTIVE;
        }

        public void revoke() {
            closed = true;
        }

        public void close() {
            closed = true;
        }
    }

    static final class FilesService {
        final List<UserFileRequest> requests = new CopyOnWriteArrayList<>();
        final List<Handle> handles = new CopyOnWriteArrayList<>();
        int reads;
        Function<UserFileRequest, CompletionStage<UserFileRequestResult>> select;
        Function<Integer, CompletionStage<UserFileReadResult<byte[]>>> read;

        FilesService(byte[]... contents) {
            select = q -> CompletableFuture.completedFuture(grant());
            read = i -> CompletableFuture.completedFuture(result(contents[i], false));
        }

        UserFileRequestResult grant() {
            var h = new Handle(handles.size());
            handles.add(h);
            return new UserFileRequestResult(UserFileRequestStatus.GRANTED, Optional.of(h), Optional.empty());
        }

        UserFileAccessService sdk() {
            return (UserFileAccessService) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {UserFileAccessService.class}, (o, m, a) -> {
                        if (m.getName().equals("request")) {
                            var q = (UserFileRequest) a[0];
                            assertEquals(UserFileMode.READ, q.mode());
                            assertEquals(UserFileLifetime.ONE_OPERATION, q.lifetime());
                            requests.add(q);
                            return select.apply(q);
                        }
                        if (m.getName().equals("readBytes")) {
                            assertEquals(GlbSurfaceReader.MAX_BYTES, a[1]);
                            assertEquals(UserFileHandleState.ACTIVE, ((Handle) a[0]).state());
                            return read.apply(reads++);
                        }
                        throw new AssertionError("unexpected SDK call " + m);
                    });
        }
    }

    static UserFileReadResult<byte[]> result(byte[] b, boolean truncated) {
        return new UserFileReadResult<>(Optional.of(b), Optional.empty(), truncated);
    }

    static Optional<byte[]> load(byte[] input, FilesService f) throws Exception {
        return ModelInputFiles.normalize(input, f.sdk(), "model-test", () -> true)
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
    }

    static Throwable failure(byte[] input, FilesService f) {
        return assertThrows(ExecutionException.class, () -> load(input, f)).getCause();
    }

    static byte[] zip(Map<String, byte[]> files) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(out)) {
            for (var e : new TreeMap<>(files).entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void separateObjMaterialAndTextureProduceExactlyThePackagedModel() throws Exception {
        byte[] png = image();
        var f = new FilesService(MTL, png);
        byte[] normalized = load(OBJ, f).orElseThrow();
        byte[] packaged = ModelInputNormalizer.normalize(
                zip(Map.of("model.obj", OBJ, "materials/paint.mtl", MTL, "textures/paint.png", png)));
        assertArrayEquals(packaged, normalized);
        assertEquals(GlbSurfaceReader.read(packaged), GlbSurfaceReader.read(normalized));
        assertEquals(2, f.requests.size());
        assertTrue(f.requests.get(0).title().contains("paint.mtl"));
        assertTrue(f.requests.get(1).title().contains("paint.png"));
        assertEquals(List.of("mtl"), f.requests.get(0).allowedExtensions());
        assertEquals(List.of("png", "jpg", "jpeg"), f.requests.get(1).allowedExtensions());
        assertTrue(f.handles.stream().allMatch(h -> h.closed));
    }

    @Test
    void sharedTextureIsRequestedOnlyOnce() throws Exception {
        byte[] mtl = text("newmtl paint\nmap_Kd ../textures/paint.png\nnewmtl other\nmap_Kd ../textures/paint.png\n");
        var f = new FilesService(mtl, image());
        load(OBJ, f).orElseThrow();
        assertEquals(2, f.reads);
    }

    @Test
    void materialBackslashPathResolvesTextureRelativeToMaterialDirectory() throws Exception {
        byte[] obj =
                text(new String(OBJ, StandardCharsets.UTF_8).replace("materials/paint.mtl", "materials\\paint.mtl"));
        var f = new FilesService(MTL, image());
        assertArrayEquals(
                load(OBJ, new FilesService(MTL, image())).orElseThrow(),
                load(obj, f).orElseThrow());
        assertEquals(2, f.reads);
    }

    @Test
    void cancellationAfterMaterialStopsWithoutAnotherRead() throws Exception {
        var f = new FilesService(MTL);
        f.select = q -> CompletableFuture.completedFuture(
                f.requests.size() == 1
                        ? f.grant()
                        : new UserFileRequestResult(
                                UserFileRequestStatus.CANCELED, Optional.empty(), Optional.empty()));
        assertTrue(load(OBJ, f).isEmpty());
        assertEquals(1, f.reads);
        assertTrue(f.handles.get(0).closed);
    }

    @Test
    void disablingWhileChooserPendingClosesGrantedHandleWithoutReading() throws Exception {
        var f = new FilesService();
        var choice = new CompletableFuture<UserFileRequestResult>();
        var pending = new CountDownLatch(1);
        var active = new AtomicBoolean(true);
        f.select = q -> {
            pending.countDown();
            return choice;
        };
        var output = ModelInputFiles.normalize(OBJ, f.sdk(), "model-test", active::get);
        assertTrue(pending.await(5, TimeUnit.SECONDS));
        active.set(false);
        choice.complete(f.grant());
        assertTrue(output.toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());
        assertEquals(0, f.reads);
        assertTrue(f.handles.get(0).closed);
    }

    @Test
    void disablingDuringReadClosesAndDoesNotRequestTexture() throws Exception {
        var f = new FilesService();
        var reading = new CompletableFuture<UserFileReadResult<byte[]>>();
        var pending = new CountDownLatch(1);
        var active = new AtomicBoolean(true);
        f.read = i -> {
            pending.countDown();
            return reading;
        };
        var output = ModelInputFiles.normalize(OBJ, f.sdk(), "model-test", active::get);
        assertTrue(pending.await(5, TimeUnit.SECONDS));
        active.set(false);
        reading.complete(result(MTL, false));
        assertTrue(output.toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());
        assertEquals(1, f.requests.size());
        assertTrue(f.handles.get(0).closed);
    }

    @Test
    void truncationAndTotalByteLimitCloseHandlesAndRefusePreparation() throws Exception {
        var f = new FilesService();
        f.read = i -> CompletableFuture.completedFuture(result(new byte[0], true));
        assertTrue(failure(OBJ, f).getMessage().contains("16 MiB"));
        assertTrue(f.handles.get(0).closed);
        f = new FilesService(new byte[GlbSurfaceReader.MAX_BYTES]);
        assertTrue(failure(OBJ, f).getMessage().contains("合计"));
        assertTrue(f.handles.get(0).closed);
    }

    @Test
    void deniedResourceFailsWithoutReading() throws Exception {
        var f = new FilesService();
        f.select = q -> CompletableFuture.completedFuture(new UserFileRequestResult(
                UserFileRequestStatus.DENIED,
                Optional.empty(),
                Optional.of(new UserFileError(UserFileErrorCode.PERMISSION_DENIED, "denied"))));
        assertTrue(failure(OBJ, f).getMessage().contains("DENIED"));
        assertEquals(0, f.reads);
    }

    @Test
    void archiveMissingResourceDoesNotAskToReadOutsidePackage() throws Exception {
        var f = new FilesService();
        assertInstanceOf(ModelInputNormalizer.MissingResource.class, failure(zip(Map.of("model.obj", OBJ)), f));
        assertTrue(f.requests.isEmpty());
    }

    @Test
    void externalBufferLengthAndNetworkReferencesRemainValidated() throws Exception {
        var doc = new LinkedHashMap<String, Object>(Map.of(
                "asset", Map.of("version", "2.0"), "buffers", List.of(Map.of("uri", "mesh.bin", "byteLength", 4))));
        var f = new FilesService(new byte[3]);
        assertTrue(failure(Json.bytes(doc), f).getMessage().contains("长度"));
        assertTrue(f.handles.get(0).closed);
        doc.put("buffers", List.of(Map.of("uri", "https://example.com/buffer.bin", "byteLength", 4)));
        f = new FilesService();
        assertTrue(failure(Json.bytes(doc), f).getMessage().contains("相对路径"));
        assertTrue(f.requests.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void externalGlbTextureAndGltfBufferPreserveRealTexelsAndGeometry() throws Exception {
        byte[] png = image();
        byte[] original = ModelInputNormalizer.normalize(
                zip(Map.of("model.obj", OBJ, "materials/paint.mtl", MTL, "textures/paint.png", png)));
        var b = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt(12);
        byte[] json = Arrays.copyOfRange(original, 20, 20 + n);
        Map<String, Object> doc = (Map<String, Object>) Json.parseObject(json);
        byte[] bin = Arrays.copyOfRange(original, 28 + n, original.length);
        var image = ((List<Map<String, Object>>) doc.get("images")).get(0);
        image.remove("bufferView");
        image.put("uri", "textures/paint%20color.png");
        byte[] text = Json.bytes(doc);
        int length = (text.length + 3) & ~3;
        var glb = ByteBuffer.allocate(28 + length + bin.length).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546c67)
                .putInt(2)
                .putInt(glb.capacity())
                .putInt(length)
                .putInt(0x4e4f534a)
                .put(text);
        while (glb.position() < 20 + length) glb.put((byte) ' ');
        glb.putInt(bin.length).putInt(0x004e4942).put(bin);
        var f = new FilesService(png);
        assertEquals(
                GlbSurfaceReader.read(original),
                GlbSurfaceReader.read(load(glb.array(), f).orElseThrow()));
        assertEquals(1, f.reads);
        assertTrue(f.requests.get(0).title().contains("paint color.png"));
        doc.put("buffers", List.of(Map.of("uri", "mesh.bin", "byteLength", bin.length)));
        f = new FilesService(bin, png);
        assertEquals(
                GlbSurfaceReader.read(original),
                GlbSurfaceReader.read(load(Json.bytes(doc), f).orElseThrow()));
        assertEquals(2, f.reads);
    }

    @Test
    void originalCatSeparateFilesEqualTheOriginalZipWithoutManualConversion() throws Exception {
        byte[] packed = ModelImportTestFixtures.readBytes("artifacts/cat-textured/package-source-r1/cat.zip");
        var entries = new TreeMap<String, byte[]>();
        try (var z = new ZipInputStream(new ByteArrayInputStream(packed))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; )
                if (!e.isDirectory()) entries.put(e.getName(), z.readAllBytes());
        }
        String obj = entries.keySet().stream()
                .filter(k -> k.toLowerCase(Locale.ROOT).endsWith(".obj"))
                .findFirst()
                .orElseThrow();
        String base = obj.substring(0, obj.lastIndexOf('/') + 1);
        var f = new FilesService();
        f.read = i -> {
            String ref = f.requests.get(i).title().substring("选择模型引用的资源：".length());
            byte[] bytes = entries.get(ModelInputNormalizer.packagePath(base, ref));
            assertNotNull(bytes, ref);
            return CompletableFuture.completedFuture(result(bytes, false));
        };
        assertArrayEquals(
                ModelInputNormalizer.normalize(packed),
                load(entries.get(obj), f).orElseThrow());
        assertTrue(f.reads >= 2);
        assertTrue(f.handles.stream().allMatch(h -> h.closed));
    }

    @Test
    void missingFixtureSkipsByDefaultAndFailsWhenRequired() {
        String missing = "artifacts/nonexistent-fixture-" + UUID.randomUUID() + "/missing.glb";
        String previous = System.getProperty("turboism.requireImport3dFixtures");
        try {
            System.setProperty("turboism.requireImport3dFixtures", "false");
            var skipped = assertThrows(
                    org.opentest4j.TestAbortedException.class, () -> ModelImportTestFixtures.fixture(missing));
            assertTrue(skipped.getMessage().contains(missing));
            assertTrue(skipped.getMessage().contains("turboism.requireImport3dFixtures"));

            System.setProperty("turboism.requireImport3dFixtures", "true");
            var failed = assertThrows(
                    org.opentest4j.AssertionFailedError.class, () -> ModelImportTestFixtures.fixture(missing));
            assertTrue(failed.getMessage().contains(missing));
        } finally {
            if (previous == null) {
                System.clearProperty("turboism.requireImport3dFixtures");
            } else {
                System.setProperty("turboism.requireImport3dFixtures", previous);
            }
        }
    }
}
