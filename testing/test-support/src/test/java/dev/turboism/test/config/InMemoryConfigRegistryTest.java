package dev.turboism.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryConfigRegistryTest {

    private InMemoryConfigRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryConfigRegistry();
    }

    @Test
    void readDefaultValueWhenUnset() throws Exception {
        final ConfigKey<String> key =
                new ConfigKey<>("test.config", "my-key", "default-val", ConfigCodecs.stringValue(128));
        final ConfigReadResult<String> read =
                registry.read(key).toCompletableFuture().get();
        assertEquals("default-val", read.value().value());
        assertEquals(ConfigValueSource.DEFAULT_MISSING, read.value().source());
    }

    @Test
    void writeAndReadTypedValue() throws Exception {
        final ConfigKey<Integer> key = new ConfigKey<>("test.config", "count", 0, ConfigCodecs.boundedInt(0, 100));
        final ConfigWriteResult write1 =
                registry.write(key, 42, 0).toCompletableFuture().get();
        assertTrue(write1.written());
        assertEquals(1, write1.revision());

        final ConfigReadResult<Integer> read =
                registry.read(key).toCompletableFuture().get();
        assertEquals(42, read.value().value());
        assertEquals(ConfigValueSource.STORED, read.value().source());
        assertEquals(1, read.value().revision());

        // Optimistic concurrency failure on stale revision
        final ConfigWriteResult staleWrite =
                registry.write(key, 99, 0).toCompletableFuture().get();
        assertFalse(staleWrite.written());
    }

    @Test
    void registerSchema() throws Exception {
        final ConfigKey<String> key = new ConfigKey<>("test.config", "theme", "light", ConfigCodecs.stringValue(128));
        final ConfigSchema schema = new ConfigSchema("test.config", "settings.json", 1, List.of(key));
        registry.registerSchema(schema, List.of()).toCompletableFuture().get();
        assertEquals(schema, registry.lastSchema());
    }

    @Test
    void scopedStringReadAndWrite() throws Exception {
        registry.writeString("settings.json", "theme", "dark");
        assertEquals("dark", registry.readString("settings.json", "theme").orElseThrow());

        registry.setFailWriteStringKey("theme");
        assertThrows(PluginConfigException.class, () -> registry.writeString("settings.json", "theme", "blue"));
    }
}
