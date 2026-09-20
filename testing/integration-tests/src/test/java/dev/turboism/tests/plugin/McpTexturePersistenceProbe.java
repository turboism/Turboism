package dev.turboism.tests.plugin;

import dev.turboism.protocol.json.StrictJson;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Test-only texture state and persistence acceptance. Texture writes and history use the SDK.
 * Exact-hash-gated native reads/save/open fill verification gaps without exposing host objects,
 * arbitrary paths or new file operations to production MCP. Never package in a release artifact.
 */
public final class McpTexturePersistenceProbe {
    public static final String REQUEST = "mcp-texture-roundtrip-request.properties";
    public static final String RESULT = "mcp-texture-roundtrip-result.properties";
    private static final long MAX_PIXELS = 64L * 1024 * 1024;
    private McpTexturePersistenceProbe() { }

    public static boolean runIfRequested(final PluginContext context, final Path stateRoot) {
        final String runId = System.getProperty("turboism.validation.runId", "");
        final Path request = stateRoot.resolve(REQUEST);
        try {
            if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS) || Files.size(request) > 2048
                || !acceptsRequest(Files.readAllLines(request), runId)) return false;
            final Properties result = new Properties();
            result.setProperty("schemaVersion", "1");
            result.setProperty("runId", runId);
            result.setProperty("status", "FAIL");
            try {
                new Engine(context, stateRoot, runId, result).run();
                result.setProperty("status", "PASS");
            } catch (Exception failure) {
                result.setProperty("error", failure.getClass().getSimpleName() + ": "
                    + String.valueOf(failure.getMessage()).replace('\n', ' ').replace('\r', ' '));
                context.logger().error("MCP texture native round-trip failed", failure);
            }
            final Path pending = stateRoot.resolve(RESULT + ".pending");
            try (var stream = Files.newOutputStream(pending)) { result.store(stream, "Task-local texture evidence"); }
            Files.move(pending, stateRoot.resolve(RESULT), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception failure) {
            context.logger().error("MCP texture probe could not publish evidence", failure);
            return true;
        }
    }

    static Path requireTaskPaths(final Path home, final Path fixture, final String runId) throws Exception {
        if (runId == null || !runId.matches("[a-zA-Z0-9-]{8,96}")
            || !fixture.getFileName().toString().equals(runId + ".cmo3")
            || !fixture.getParent().getFileName().toString().equals(runId)
            || Files.isSymbolicLink(home) || Files.isSymbolicLink(fixture)
            || !home.toRealPath().getParent().equals(fixture.toRealPath().getParent())) {
            throw new IllegalArgumentException("Fixture and home must belong to this exact task");
        }
        Path current = home.toRealPath();
        for (String name : List.of("state", "texture-persistence")) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symlinked persistence directory");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current)) {
                throw new IllegalArgumentException("Persistence parent is not a directory");
            }
        }
        final Path output = current.resolve(runId + "-persist.cmo3");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Persistence output must be new");
        }
        return output;
    }

    static String expectedJarHash(final String version) {
        final String exact = switch (version) {
            case "5203" -> "5.2.03";
            case "5302" -> "5.3.02";
            case "5303" -> "5.3.03";
            default -> throw new IllegalArgumentException("Unreviewed texture host version");
        };
        final String resource = "/META-INF/turboism/verification/cubism-" + exact + "-editor-model.json";
        try (var stream = McpTexturePersistenceProbe.class.getResourceAsStream(resource)) {
            require(stream != null, "Canonical host verification record is not packaged");
            final byte[] bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
            require(bytes.length <= 2 * 1024 * 1024, "Host verification record is too large");
            final Map<String, Object> record = StrictJson.parse(bytes);
            require(exact.equals(record.get("cubismVersion")), "Host record version mismatch");
            final Map<?, ?> artifact = (Map<?, ?>) record.get("artifact");
            require("Live2D_Cubism.jar".equals(artifact.get("name")), "Unexpected host artifact");
            final String hash = (String) artifact.get("sha256");
            require(hash != null && hash.matches("[a-f0-9]{64}"), "Invalid canonical host digest");
            return hash;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot read canonical host verification record", failure);
        }
    }

    static boolean acceptsRequest(final List<String> lines, final String runId) {
        return runId != null && !runId.isBlank() && lines.size() == 2
            && lines.stream().filter(line -> line.equals("runId=" + runId)).count() == 1
            && lines.stream().filter(line -> line.equals("status=REQUESTED")).count() == 1;
    }

    static String pixelDigest(final BufferedImage image) throws Exception {
        if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
            throw new IllegalArgumentException("Pixel evidence exceeds its bound");
        }
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight()).array());
        final int[] row = new int[image.getWidth()];
        final ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(image.getWidth(), 4));
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
            bytes.clear();
            for (int pixel : row) bytes.putInt(pixel);
            digest.update(bytes.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fileHash(final Path file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            final byte[] bytes = new byte[65536];
            int count;
            while ((count = stream.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String mapHash(final Map<String, String> map) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (var entry : new TreeMap<>(map).entrySet()) {
            for (String text : List.of(entry.getKey(), entry.getValue())) {
                final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Object call(final Object target, final String name) throws Exception {
        return invoke(target.getClass().getMethod(name), target);
    }

    private static Object invoke(final Method method, final Object target, final Object... arguments) throws Exception {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    private static String guid(final Object object) throws Exception {
        return (String) call(object, "getUuidString");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Snapshot(Map<String, String> library, Map<String, String> inputs, Map<String, String> pixels) {
        String digest() throws Exception {
            return mapHash(Map.of("library", mapHash(library), "inputs", mapHash(inputs), "pixels", mapHash(pixels)));
        }
    }

    private static final class Engine {
        private final PluginContext context;
        private final Path fixture;
        private final Path output;
        private final String runId;
        private final Properties result;
        private Object app;
        private Object originalDocument;
        private Object savedDocument;
        private Snapshot baseline;
        private String originalHash;
        private volatile boolean nativeTimedOut;

        Engine(final PluginContext context, final Path stateRoot, final String runId, final Properties result)
            throws Exception {
            this.context = context;
            this.runId = runId;
            this.result = result;
            fixture = Path.of(System.getProperty("turboism.validation.fixture", ""));
            output = requireTaskPaths(stateRoot.getParent(), fixture, runId);
        }

        private <T> T edt(final Callable<T> action) throws Exception {
            require(!nativeTimedOut, "No further host actions after an uncertain native timeout");
            if (SwingUtilities.isEventDispatchThread()) return action.call();
            final FutureTask<T> task = new FutureTask<>(action);
            SwingUtilities.invokeLater(task);
            try { return task.get(120, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) { nativeTimedOut = true; throw timeout; }
        }

        void run() throws Exception {
            originalHash = fileHash(fixture);
            final String version = System.getProperty("turboism.validation.hostVersion", "");
            result.setProperty("hostVersion", version);
            edt(() -> {
                final Set<ClassLoader> loaders = new LinkedHashSet<>();
                for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
                    loaders.add(loader);
                }
                loaders.add(ClassLoader.getSystemClassLoader());
                for (Frame frame : Frame.getFrames()) loaders.add(frame.getClass().getClassLoader());
                Class<?> appClass = null;
                for (ClassLoader loader : loaders) {
                    if (loader == null) continue;
                    try { appClass = Class.forName("com.live2d.cubism.CEAppCtrl", false, loader); break; }
                    catch (ClassNotFoundException absent) { /* Try another already-present host loader. */ }
                }
                require(appClass != null, "Exact Cubism application class is unavailable");
                final Path jar = Path.of(appClass.getProtectionDomain().getCodeSource().getLocation().toURI());
                require(expectedJarHash(version).equals(fileHash(jar)), "Native class artifact hash mismatch");
                app = invoke(appClass.getMethod("access$get_instance$cp"), null);
                originalDocument = currentDocument();
                requireFile(originalDocument, fixture);
                originalDocument.getClass().getMethod("saveDocument", File.class, boolean.class);
                originalDocument.getClass().getMethod("closeFile", boolean.class, boolean.class);
                appClass.getMethod("command_open", File.class, boolean.class);
                baseline = snapshot();
                result.setProperty("baselineInputCount", Integer.toString(baseline.inputs().size()));
                result.setProperty("baselinePixelLayerCount", Integer.toString(baseline.pixels().size()));
                require(!baseline.inputs().isEmpty() && !baseline.pixels().isEmpty(),
                    "Fixture lacks native evidence: inputs=" + baseline.inputs().size()
                        + ", pixelLayers=" + baseline.pixels().size());
                return null;
            });
            try {
                verifyNativeRawUndo();
                verifyPersistence();
            } finally {
                if (!nativeTimedOut) restoreOriginalDocument();
                require(fileHash(fixture).equals(originalHash), "Task fixture bytes changed");
            }
            result.setProperty("fixtureUnchanged", "true");
            result.setProperty("originalReopened", "true");
            result.setProperty("restoredFingerprint", edt(() -> snapshot().digest()));
        }

        private Object currentDocument() throws Exception { return call(app, "getCurrentDoc"); }
        private ModelTextures textures() { return context.cubism().model().active().textures(); }
        private int position() { return context.cubism().history().snapshot().position(); }

        private void requireFile(final Object document, final Path expected) throws Exception {
            require(document != null && document.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingDocument"),
                "Active document is not the owned modeling document");
            final Object file = call(document, "getFile");
            require(file instanceof File && Files.isSameFile(((File) file).toPath(), expected),
                "Active document file does not match the owned target");
        }

        private void move(final int target) {
            final HistorySnapshot history = context.cubism().history().snapshot();
            final var moved = context.cubism().history().moveTo(history.generation(), history.revision(), target);
            require(moved.outcome() == HistoryMoveResult.Outcome.MOVED
                || moved.outcome() == HistoryMoveResult.Outcome.NO_CHANGE, "Native history restoration failed");
            require(position() == target, "Native history position mismatch");
        }

        private void verifyNativeRawUndo() throws Exception {
            edt(() -> {
                requireFile(currentDocument(), fixture);
                final int beforePosition = position();
                final String target = textures().rawImages().stream().map(raw -> raw.id().value())
                    .filter(id -> baseline.inputs().keySet().stream().anyMatch(key -> key.endsWith("|" + id)))
                    .findFirst().orElseThrow();
                try {
                    textures().removeRawImage(new RawImageId(target));
                    require(position() == beforePosition + 1, "Raw deletion must have one native Undo root");
                    final Snapshot changed = snapshot();
                    final Map<String, String> expectedInputs = new TreeMap<>(baseline.inputs());
                    expectedInputs.keySet().removeIf(key -> key.endsWith("|" + target));
                    require(changed.inputs().equals(expectedInputs), "Raw deletion changed unrelated native layer inputs");
                    result.setProperty("nativeInputsBefore", mapHash(baseline.inputs()));
                    result.setProperty("nativeInputsAfterDelete", mapHash(changed.inputs()));
                    result.setProperty("rawPixelsBefore", mapHash(baseline.pixels()));
                    move(beforePosition);
                    require(snapshot().equals(baseline), "Native Undo did not restore layer inputs and exact pixels");
                    move(beforePosition + 1);
                    require(snapshot().equals(changed), "Native Redo differs from the first raw deletion");
                    move(beforePosition);
                    require(snapshot().equals(baseline), "Final native Undo did not restore exact baseline");
                    result.setProperty("rawPixelsRestored", mapHash(snapshot().pixels()));
                    result.setProperty("nativeLayerPixelUndoRedo", "PASS");
                } finally {
                    if (position() == beforePosition + 1) move(beforePosition);
                    require(position() == beforePosition && snapshot().equals(baseline), "Raw evidence cleanup failed");
                }
                return null;
            });
        }

        private void verifyPersistence() throws Exception {
            Files.createDirectories(output.getParent());
            final Snapshot expected = edt(() -> {
                requireFile(currentDocument(), fixture);
                final ModelTextures library = textures();
                final String rawId = library.rawImages().get(0).id().value();
                final String imageId = library.modelImageGroups().stream().flatMap(group -> group.modelImages().stream())
                    .findFirst().orElseThrow().id().value();
                final TextureAtlasId removeAtlas = library.textureAtlases().isEmpty()
                    ? library.addTextureAtlas("TexturePersistenceTemporary", 64, 64)
                    : library.textureAtlases().get(0).id();
                library.addModelImageGroup("TexturePersistence-" + runId.substring(runId.length() - 8));
                final TextureAtlasId retainedAtlas = library.addTextureAtlas("TexturePersistenceRetained", 64, 64);
                library.removeTextureAtlas(removeAtlas);
                library.removeModelImage(new ModelImageId(imageId));
                library.removeRawImage(new RawImageId(rawId));
                final Snapshot value = snapshot();
                result.setProperty("persistedAtlasId", retainedAtlas.value());
                result.setProperty("persistenceOperationKinds", "5");
                require(Boolean.TRUE.equals(invoke(originalDocument.getClass().getMethod("saveDocument", File.class,
                    boolean.class), originalDocument, output.toFile(), false)), "Native save rejected");
                return value;
            });
            awaitSaved();
            result.setProperty("savedFileSha256", fileHash(output));
            result.setProperty("savedFingerprint", expected.digest());
            edt(() -> { closeOwned(originalDocument); return null; });
            openOwned(output);
            edt(() -> {
                savedDocument = currentDocument();
                require(savedDocument != originalDocument, "Persistence must deserialize a fresh native document");
                require(snapshot().equals(expected), "Reopened native texture state differs from the saved state");
                result.setProperty("reopenedFingerprint", snapshot().digest());
                result.setProperty("saveReopen", "PASS");
                closeOwned(savedDocument);
                return null;
            });
            openOwned(fixture);
            edt(() -> {
                require(snapshot().equals(baseline), "Original fixture reopen did not restore full native state");
                return null;
            });
        }

        private void awaitSaved() throws Exception {
            String previous = "";
            int stable = 0;
            for (int attempt = 0; attempt < 120; attempt++) {
                if (Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) && Files.size(output) > 0) {
                    final String current = fileHash(output);
                    stable = current.equals(previous) ? stable + 1 : 0;
                    previous = current;
                    if (stable >= 3) return;
                }
                Thread.sleep(250);
            }
            throw new IllegalStateException("Native save did not produce a stable task output");
        }

        private void closeOwned(final Object document) throws Exception {
            require(document == currentDocument(), "Refusing to close a different active document");
            final Object value = call(document, "getFile");
            require(value instanceof File, "Owned document has no file");
            final Path file = ((File) value).toPath();
            require(Files.isSameFile(file, fixture) || (Files.exists(output) && Files.isSameFile(file, output)),
                "Refusing to close a foreign document");
            require(Boolean.TRUE.equals(invoke(document.getClass().getMethod("closeFile", boolean.class, boolean.class),
                document, false, false)), "Native task document close failed");
        }

        private void openOwned(final Path path) throws Exception {
            require(path.equals(fixture) || path.equals(output), "Refusing to open a foreign path");
            edt(() -> {
                require(currentDocument() == null, "Cannot open while another document is active");
                invoke(app.getClass().getMethod("command_open", File.class, boolean.class), app, path.toFile(), false);
                return null;
            });
            for (int attempt = 0; attempt < 120; attempt++) {
                final boolean ready = edt(() -> {
                    if (currentDocument() == null) return false;
                    requireFile(currentDocument(), path);
                    return context.cubism().activeModel().isPresent();
                });
                if (ready) return;
                Thread.sleep(250);
            }
            throw new IllegalStateException("Native reopen did not make the owned model ready");
        }

        private void restoreOriginalDocument() throws Exception {
            final boolean alreadyRestored = edt(() -> {
                final Object document = currentDocument();
                if (document == null) return false;
                final Object value = call(document, "getFile");
                if (value instanceof File && Files.isSameFile(((File) value).toPath(), fixture)
                    && snapshot().equals(baseline)) return true;
                require(document == originalDocument || document == savedDocument,
                    "Unknown active document prevents recovery");
                closeOwned(document);
                return false;
            });
            if (!alreadyRestored) openOwned(fixture);
            edt(() -> { require(snapshot().equals(baseline), "Recovery did not restore the original native state"); return null; });
        }

        private Snapshot snapshot() throws Exception {
            final Map<String, String> library = new TreeMap<>();
            final ModelTextures textures = textures();
            for (var raw : textures.rawImages()) library.put("raw/" + raw.id().value(),
                raw.name() + "/" + raw.width() + "/" + raw.height());
            int groupIndex = 0;
            for (var group : textures.modelImageGroups()) {
                final String groupKey = "group/" + groupIndex++;
                library.put(groupKey, group.groupName() + "/" + group.memo());
                int imageIndex = 0;
                for (var image : group.modelImages()) library.put("image/" + image.id().value(),
                    groupKey + "/" + imageIndex++ + "/" + image.name() + "/" + image.width() + "/" + image.height());
            }
            for (var atlas : textures.textureAtlases()) library.put("atlas/" + atlas.id().value(),
                atlas.name() + "/" + atlas.width() + "/" + atlas.height() + "/" + atlas.modelImageCount());
            final Object manager = call(call(currentDocument(), "getModelSource"), "getTextureManager");
            final Map<String, String> inputs = new TreeMap<>();
            for (Object image : (List<?>) call(manager, "getAllModelImages")) {
                final String imageId = guid(call(image, "getGuid"));
                final Object map = call(call(image, "getInputFilterEnv"), "getLayerInputData");
                if (map == null) continue;
                for (var entry : ((Map<?, ?>) call(map, "getImageToLayerInput")).entrySet()) {
                    final List<String> layers = new ArrayList<>();
                    for (Object input : (List<?>) entry.getValue()) {
                        layers.add(guid(call(call(input, "getLayer"), "getGuid")) + ":"
                            + Arrays.toString((float[]) call(call(input, "getAffine"), "getMatrix")));
                    }
                    inputs.put(imageId + "|" + guid(entry.getKey()), layers.toString());
                }
            }
            final Map<String, String> pixels = new TreeMap<>();
            long pixelCount = 0;
            for (Object wrapper : (List<?>) call(manager, "getRawImages")) {
                final Object raw = call(wrapper, "getImage");
                final String rawId = guid(call(raw, "getGuid"));
                for (Object layer : (Iterable<?>) call(raw, "layerIterable")) {
                    if (!layer.getClass().getName().equals("com.live2d.cubism.doc.resources.CLayer")) continue;
                    final Object resource = call(layer, "getImageResource");
                    final BufferedImage image = (BufferedImage) call(call(resource, "getImage"), "getJBufferedImage");
                    pixelCount += (long) image.getWidth() * image.getHeight();
                    require(pixelCount <= MAX_PIXELS, "Total native pixel evidence exceeds its bound");
                    pixels.put(rawId + "/" + guid(call(layer, "getGuid")), pixelDigest(image));
                }
            }
            return new Snapshot(Map.copyOf(library), Map.copyOf(inputs), Map.copyOf(pixels));
        }
    }
}
