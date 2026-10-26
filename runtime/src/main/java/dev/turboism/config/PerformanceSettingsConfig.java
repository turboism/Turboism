package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.turboism.runtime.env.ProtonEnvironment;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** Canonical field definitions and immutable startup snapshot for the entire Performance page. */
public final class PerformanceSettingsConfig {
    /** Existing global paths are retained so old launchers and configuration files remain compatible. */
    public enum Field {
        MODEL_UPDATE_SKIP("modelUpdateSkip", true, "false"),
        INCREMENTAL_UPDATE("incrementalUpdate", true, "false"),
        UNIFORM_LOCATION_CACHE("uniformLocationCache", true, "false"),
        UPLOAD_ELISION("uploadElision", true, "false"),
        INPUT_PATH_ELISION("inputPathElision", true, "false"),
        MESA_GL_THREAD("mesaGlThread", true, "false"),
        MESH_TRIANGULATION_HASH("meshTriangulationHashFix", false, "false"),
        TRIANGULATION_EDGE_INDEX("meshTriangulationEdgeIndex", false, "false"),
        ATLAS_TILE_BBOX("atlasTileBbox", false, "false"),
        ATLAS_CACHE_REUSE("atlasCacheReuse", false, "false"),
        CUBISM_JVM("cubismJvm", true, "graalvm"),
        GRAAL_VM_PATH("graalVmPath", true, ""),
        REDUCE_AUTO_BACKUP("reduceAutoBackup", false, "false"),
        ZGC("zgc", true, "true"),
        MEMORY_PROFILE("memoryProfile", true, "system");

        private final String key;
        private final boolean launcher;
        private final String defaultValue;

        Field(String key, boolean launcher, String defaultValue) {
            this.key = key;
            this.launcher = launcher;
            this.defaultValue = defaultValue;
        }

        /** Existing JSON field name. */
        public String key() {
            return key;
        }
        /** Whether this field is nested in launcher. */
        public boolean launcher() {
            return launcher;
        }
        /** Whether this is one of the ten native optimization switches. */
        public boolean nativeOptimization() {
            return switch (this) {
                case CUBISM_JVM, GRAAL_VM_PATH, REDUCE_AUTO_BACKUP, ZGC, MEMORY_PROFILE -> false;
                default -> true;
            };
        }
        /** Whether this field stores a boolean. */
        public boolean booleanValue() {
            return nativeOptimization() || this == REDUCE_AUTO_BACKUP || this == ZGC;
        }
        /** Platform default used only when the user has not made an explicit choice. */
        public String defaultValue(boolean proton) {
            return defaultValue;
        }
        /** Looks up a supported performance field. */
        public static Field forKey(String key) {
            for (Field field : values()) if (field.key.equals(key)) return field;
            throw new IllegalArgumentException("Unknown performance setting: " + key);
        }
    }

    /** Startup request and its source, separate from the subsequently edited saved value. */
    public record Value(String value, String source) {}

    private final Map<Field, Value> values;

    private PerformanceSettingsConfig(Map<Field, Value> values) {
        this.values = Map.copyOf(values);
    }

    /** Reads all fields once without creating a missing global file. Invalid files disable native optimizations. */
    public static PerformanceSettingsConfig load(Path home, Consumer<String> diagnostic) {
        JsonNode root = JsonNodeFactory.instance.objectNode();
        boolean valid = true;
        if (home != null && Files.exists(home.resolve("config.json"), LinkOption.NOFOLLOW_LINKS)) {
            try {
                root = new RuntimeConfigRepository(home, diagnostic).read();
            } catch (RuntimeException failure) {
                valid = false;
                diagnostic.accept("PERFORMANCE_CONFIG_INVALID");
            }
        }
        boolean proton = ProtonEnvironment.underWineOrProton();
        Map<Field, Value> values = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            JsonNode node = (field.launcher ? root.path("launcher") : root).get(field.key);
            String value = saved(root, field, proton);
            String source = node == null ? "platform-default" : "global-config";
            if (!valid) {
                value = field.nativeOptimization() ? "false" : field.defaultValue(proton);
                source = "invalid-config";
            } else if (field.nativeOptimization() && System.getProperty("turboism.optimization." + field.key) != null) {
                value = Boolean.toString(
                        Boolean.parseBoolean(System.getProperty("turboism.optimization." + field.key)));
                source = "jvm-override";
            } else if (field == Field.REDUCE_AUTO_BACKUP && System.getenv("TURBOISM_REDUCE_AUTO_BACKUP") != null) {
                String override = System.getenv("TURBOISM_REDUCE_AUTO_BACKUP").trim();
                if (!override.isEmpty()) {
                    value = Boolean.toString(java.util.Set.of("1", "true", "yes", "on")
                            .contains(override.toLowerCase(java.util.Locale.ROOT)));
                    source = "environment-override";
                }
            }
            values.put(field, new Value(value, source));
        }
        return new PerformanceSettingsConfig(values);
    }

    /** Reads an editable preference using the same field defaults as startup, without diagnostic overrides. */
    public static String saved(JsonNode root, Field field, boolean proton) {
        JsonNode node = (field.launcher ? root.path("launcher") : root).get(field.key);
        return node == null || node.isNull() ? field.defaultValue(proton) : node.asText();
    }

    /** Explicitly persists a selection through the validated atomic global repository. */
    public static void save(RuntimeConfigRepository repository, Field field, String value) {
        repository.update(root -> {
            var parent = field.launcher ? root.withObject("launcher") : root;
            if (field == Field.GRAAL_VM_PATH && value.isEmpty()) {
                parent.remove(field.key);
            } else if (field.booleanValue()) {
                if (!"true".equals(value) && !"false".equals(value))
                    throw new IllegalArgumentException("Expected boolean");
                parent.put(field.key, Boolean.parseBoolean(value));
            } else {
                parent.put(field.key, value);
            }
            return root;
        });
    }

    /** Immutable startup choice for a field. */
    public Value value(Field field) {
        return values.get(field);
    }
}
