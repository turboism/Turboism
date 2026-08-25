package dev.turboism.plugin.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorePluginManagementDetailsTest {

    @Test
    void defaultDetailsProjectsExistingPluginRow() {
        final CorePluginManagement.PluginInfo plugin = plugin();
        final CorePluginManagement management = new CorePluginManagement() {
            @Override public List<PluginInfo> plugins() { return List.of(plugin); }
            @Override public OperationResult install() { return rejected(); }
            @Override public OperationResult uninstall(final String pluginId) { return rejected(); }
            @Override public OperationResult setEnabled(final String pluginId, final boolean enabled) { return rejected(); }
        };

        final CorePluginManagement.PluginDetails details = management.details(plugin.id()).orElseThrow();

        assertEquals(plugin, details.plugin());
        assertEquals(List.of(), details.authors());
        assertEquals(Optional.empty(), details.readme());
    }

    @Test
    void detailsCollectionsAreDefensivelyCopied() {
        final ArrayList<CorePluginManagement.Author> authors = new ArrayList<>(List.of(
            new CorePluginManagement.Author("Author", Optional.empty())
        ));
        final ArrayList<String> capabilities = new ArrayList<>(List.of("example"));

        final CorePluginManagement.PluginDetails details = new CorePluginManagement.PluginDetails(
            plugin(), "[0.1.0,0.2.0)", authors, "MIT", Optional.empty(), List.of(), List.of(),
            capabilities, false, "none", List.of(), List.of(), "", List.of(), List.of(), List.of(),
            Optional.of("# README")
        );

        authors.clear();
        capabilities.clear();
        assertNotSame(authors, details.authors());
        assertEquals(1, details.authors().size());
        assertEquals(List.of("example"), details.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> details.capabilities().add("mutated"));
    }

    private static CorePluginManagement.PluginInfo plugin() {
        return new CorePluginManagement.PluginInfo(
            "example.plugin", "Example", "1.0.0", "Description", "ENABLED", "ENABLED", false,
            Optional.empty(), "development", List.of("example")
        );
    }

    private static CorePluginManagement.OperationResult rejected() {
        return CorePluginManagement.OperationResult.rejected("not used");
    }
}
