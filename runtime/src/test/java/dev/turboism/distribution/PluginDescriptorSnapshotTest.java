package dev.turboism.distribution;

import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Classification must be defensively copied into distribution snapshots. */
class PluginDescriptorSnapshotTest {

    @Test
    void snapshotCarriesParsedClassificationAndIsImmutable() {
        final List<String> tags = new ArrayList<>(List.of("parameter", "batch-edit"));
        final PluginDescriptor source = descriptor("modeling", tags);

        final PluginDescriptorSnapshot snapshot = PluginDescriptorSnapshot.copyOf(source);

        assertEquals(Optional.of("modeling"), snapshot.category());
        assertEquals(List.of("parameter", "batch-edit"), snapshot.tags());
        assertNotSame(tags, snapshot.tags());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.tags().add("mutated"));
    }

    @Test
    void snapshotDoesNotChangeWhenSourceCollectionsChange() {
        final List<String> tags = new ArrayList<>(List.of("parameter"));
        final PluginDescriptor source = descriptor("modeling", tags);

        final PluginDescriptorSnapshot snapshot = PluginDescriptorSnapshot.copyOf(source);
        tags.add("mutated-after-copy");

        assertEquals(List.of("parameter"), snapshot.tags());
        assertEquals(Optional.of("modeling"), snapshot.category());
    }

    @Test
    void v2DescriptorNormalizesToEmptyClassificationInSnapshot() {
        final PluginDescriptor source = descriptor(null, new ArrayList<>());

        final PluginDescriptorSnapshot snapshot = PluginDescriptorSnapshot.copyOf(source);

        assertEquals(Optional.empty(), snapshot.category());
        assertEquals(List.of(), snapshot.tags());
    }

    private static PluginDescriptor descriptor(final String category, final List<String> tags) {
        return new PluginDescriptor() {
            @Override public String id() { return "dev.turboism.plugin.snapshot"; }
            @Override public String name() { return "Snapshot"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return ""; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.plugin.snapshot.SnapshotPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return ""; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() {
                return new I18n() {
                    @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
                    @Override public List<String> locales() { return List.of(); }
                };
            }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                };
            }
            @Override public Optional<String> category() { return Optional.ofNullable(category); }
            @Override public List<String> tags() { return tags; }
        };
    }
}
