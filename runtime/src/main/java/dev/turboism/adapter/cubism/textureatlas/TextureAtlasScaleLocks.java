package dev.turboism.adapter.cubism.textureatlas;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.PathLocks;
import dev.turboism.runtime.json.RuntimeJson;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Stores absolute scales by model and image GUID, independent of atlas pages. */
final class TextureAtlasScaleLocks {
    static final double ORIGINAL_SCALE = 1D;
    private static final String FILE_NAME = "texture-atlas-scale-locks.json";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final TypeReference<Map<String, Map<String, Double>>> LOCK_TYPE = new TypeReference<>() {};

    private final Path file;
    private final Object lock;
    private Map<String, Map<String, Double>> cache;

    TextureAtlasScaleLocks(final Path home) {
        file = home.toAbsolutePath().normalize().resolve(FILE_NAME);
        lock = PathLocks.forPath(file);
    }

    Map<String, Double> snapshot(final String model) {
        synchronized (lock) {
            if (cache == null) cache = read();
            return cache.getOrDefault(model, Map.of());
        }
    }

    void update(final String model, final Map<String, Double> additions, final Set<String> removals) {
        synchronized (lock) {
            final var document = new LinkedHashMap<>(read());
            final var scales = new LinkedHashMap<>(document.getOrDefault(model, Map.of()));
            removals.forEach(scales::remove);
            additions.forEach((image, value) -> scales.put(image, validScale(value)));
            if (scales.isEmpty()) document.remove(model);
            else document.put(model, Map.copyOf(scales));
            try {
                final byte[] bytes = RuntimeJson.strict().writeValueAsBytes(document);
                if (bytes.length > MAX_BYTES) throw new IOException("Scale lock file exceeds its size limit.");
                DurableFile.at(file)
                        .guardedBy((staged, target) -> rejectLink(target))
                        .replace(bytes);
                cache = Map.copyOf(document);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot save texture atlas scale locks.", failure);
            }
        }
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
}
