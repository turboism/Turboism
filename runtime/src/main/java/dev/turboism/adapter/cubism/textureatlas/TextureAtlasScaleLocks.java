package dev.turboism.adapter.cubism.textureatlas;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.PathLocks;
import dev.turboism.runtime.json.RuntimeJson;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Keeps draft scales per open document and stores saved scales by model and image GUID. */
final class TextureAtlasScaleLocks {
    static final double ORIGINAL_SCALE = 1D;
    private static final String FILE_NAME = "texture-atlas-scale-locks.json";
    private static final String LOG_CATEGORY = "texture-atlas";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final TypeReference<Map<String, Map<String, Double>>> LOCK_TYPE = new TypeReference<>() {};

    private final Path file;
    private final Object lock;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private Map<String, Map<String, Double>> cache;

    TextureAtlasScaleLocks(final Path home) {
        file = home.toAbsolutePath().normalize().resolve(FILE_NAME);
        lock = PathLocks.forPath(file);
    }

    Map<String, Double> snapshot(final Document document) {
        synchronized (lock) {
            final Pending changes = pending.get(document.contentId());
            return changes == null ? savedScales(document.modelGuid()) : changes.after();
        }
    }

    void update(final Document document, final Map<String, Double> additions, final Set<String> removals) {
        synchronized (lock) {
            final Pending previous = pending.get(document.contentId());
            final Map<String, Double> before = previous == null ? savedScales(document.modelGuid()) : previous.before();
            final var scales = new LinkedHashMap<>(previous == null ? before : previous.after());
            removals.forEach(scales::remove);
            additions.forEach((image, value) -> scales.put(image, validScale(value)));
            pending.put(document.contentId(), new Pending(document.modelGuid(), before, Map.copyOf(scales)));
        }
    }

    void projectFileCompleted(final ProjectFileOperationResult result) {
        if (!result.succeeded() || result.request().kind() != ProjectContentKind.MODEL) return;
        final var operation = result.request().operation();
        final String contentId = result.request().contentId().orElse(null);
        if (contentId == null) return;
        synchronized (lock) {
            if (operation == ProjectFileOperationType.CLOSE) pending.remove(contentId);
            if (operation != ProjectFileOperationType.SAVE) return;
            final Pending changes = pending.get(contentId);
            if (changes == null) return;
            try {
                if (!changes.before().equals(changes.after())) save(changes);
                pending.remove(contentId);
            } catch (IOException | RuntimeException failure) {
                RuntimeDiagnostics.error(LOG_CATEGORY, "Cannot save texture atlas scale locks.", failure);
            }
        }
    }

    private Map<String, Double> savedScales(final String model) {
        if (cache == null) cache = read();
        return cache.getOrDefault(model, Map.of());
    }

    private void save(final Pending changes) throws IOException {
        final var saved = new LinkedHashMap<>(read());
        final String model = changes.modelGuid();
        final var scales = new LinkedHashMap<>(saved.getOrDefault(model, Map.of()));
        changes.before().keySet().stream()
                .filter(image -> !changes.after().containsKey(image))
                .forEach(scales::remove);
        changes.after().forEach((image, scale) -> {
            if (!scale.equals(changes.before().get(image))) scales.put(image, scale);
        });
        if (scales.isEmpty()) saved.remove(model);
        else saved.put(model, Map.copyOf(scales));
        final byte[] bytes = RuntimeJson.strict().writeValueAsBytes(saved);
        if (bytes.length > MAX_BYTES) throw new IOException("Scale lock file exceeds its size limit.");
        DurableFile.at(file).guardedBy((staged, target) -> rejectLink(target)).replace(bytes);
        cache = Map.copyOf(saved);
    }

    private Map<String, Map<String, Double>> read() {
        try {
            rejectLink(file);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Scale lock storage requires a regular file.");
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                final byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Scale lock file exceeds its size limit.");
                final var document = RuntimeJson.strict().readValue(bytes, LOCK_TYPE);
                final var checked = new LinkedHashMap<String, Map<String, Double>>();
                document.forEach((model, scales) -> {
                    scales.values().forEach(TextureAtlasScaleLocks::validScale);
                    checked.put(model, Map.copyOf(scales));
                });
                return Map.copyOf(checked);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read texture atlas scale locks.", failure);
        }
    }

    private static void rejectLink(final Path target) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("The scale lock file is a symbolic link.");
    }

    static double scale(final AffineTransform transform) {
        return validScale(Math.sqrt(Math.abs(transform.getDeterminant())));
    }

    private static double validScale(final double value) {
        if (!Double.isFinite(value) || value <= 0D) {
            throw new IllegalArgumentException("Invalid texture atlas image scale.");
        }
        return value;
    }

    record Document(String contentId, String modelGuid) {}

    private record Pending(String modelGuid, Map<String, Double> before, Map<String, Double> after) {}
}
