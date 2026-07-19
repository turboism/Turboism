package dev.turboism.plugin.boundingbox.b1.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.plugin.boundingbox.b1.domain.BoundingBoxFeatureSettings;
import dev.turboism.sdk.config.ConfigError;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.Registration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class BoundingBoxSettingsBindingTest {
    @Test
    void hydratesAndPersistsInFrozenOrder() {
        final FakeRegistry registry = new FakeRegistry();
        registry.values.put("overlayButtonsEnabled", false);
        registry.values.put("workspaceButtonsEnabled", true);
        registry.values.put("mirrorAndShrinkSuppressed", true);
        registry.revision = 5;
        final BoundingBoxSettingsBinding binding = new BoundingBoxSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        assertEquals(ConfigBindingResult.APPLIED, binding.enable().toCompletableFuture().join());
        assertEquals(new BoundingBoxFeatureSettings(false, true, true), binding.confirmed());
        final BoundingBoxFeatureSettings update = new BoundingBoxFeatureSettings(true, false, false);
        assertEquals(ConfigBindingResult.APPLIED, binding.update(update).toCompletableFuture().join());
        assertEquals(List.of("overlayButtonsEnabled@5", "workspaceButtonsEnabled@6", "mirrorAndShrinkSuppressed@7"), registry.writes);
    }

    @Test
    void partialPersistenceReconcilesButRetainsLastConfirmedState() {
        final FakeRegistry registry = new FakeRegistry();
        final BoundingBoxSettingsBinding binding = new BoundingBoxSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        binding.enable().toCompletableFuture().join();
        registry.writeResults.add(FakeRegistry.SUCCESS);
        registry.writeResults.add(new ConfigWriteResult(false, 1, Optional.of(
            new ConfigError(ConfigErrorCode.PERSISTENCE_FAILED, "failed", "workspaceButtonsEnabled")
        )));
        final BoundingBoxFeatureSettings defaults = binding.confirmed();
        assertEquals(ConfigBindingResult.PARTIAL_PERSISTENCE, binding.update(
            new BoundingBoxFeatureSettings(false, false, true)
        ).toCompletableFuture().join());
        assertEquals(defaults, binding.confirmed());
        assertEquals(6, registry.reads);
    }

    private static final class FakeRegistry implements PluginConfigRegistry {
        static final ConfigWriteResult SUCCESS = new ConfigWriteResult(true, 1, Optional.empty());
        final Map<String,Object> values=new HashMap<>(); final List<String>writes=new java.util.ArrayList<>(); final Queue<ConfigWriteResult> writeResults=new ArrayDeque<>(); long revision; int reads;
        @Override public CompletionStage<Void> registerSchema(ConfigSchema schema,List<dev.turboism.sdk.config.ConfigMigration> migrations){return CompletableFuture.completedFuture(null);}
        @SuppressWarnings("unchecked") @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key){reads++;T v=(T)values.getOrDefault(key.name(),key.defaultValue());return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(v,ConfigValueSource.STORED,revision),Optional.empty()));}
        @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key,T value,long expected){writes.add(key.name()+"@"+expected);if(!writeResults.isEmpty()){ConfigWriteResult queued=writeResults.remove();if(queued!=SUCCESS)return CompletableFuture.completedFuture(queued);}values.put(key.name(),value);revision=expected+1;return CompletableFuture.completedFuture(new ConfigWriteResult(true,revision,Optional.empty()));}
        @Override public Registration readScope(String p){return ()->{};} @Override public Registration writeScope(String p){return ()->{};} @Override public Optional<String> readString(String p,String k){return Optional.empty();} @Override public void writeString(String p,String k,String v)throws PluginConfigException{}
    }
}
