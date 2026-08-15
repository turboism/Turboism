package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.List;
import java.util.Optional;

/** Minimal {@link PluginDescriptor} test double for the PSD clip-mask import plugin. */
final class TestPluginDescriptor implements PluginDescriptor {

    @Override public String id() { return "dev.turboism.plugin.psd-clip-mask-import"; }
    @Override public String name() { return "PSD Clip Mask Import Plugin"; }
    @Override public String version() { return "0.1.0"; }
    @Override public String description() { return "test"; }
    @Override public List<String> entrypoints() { return List.of(); }
    @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
    @Override public List<Author> authors() { return List.of(); }
    @Override public String license() { return "Project License"; }
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
            @Override public boolean requiresCubism() { return true; }
            @Override public String ui() { return "embedded"; }
        };
    }
}
