package dev.turboism.core.dependency;

import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    private final DependencyResolver resolver = new DependencyResolver();

    private PluginDescriptor descriptor(String id, String version, List<PluginDescriptor.DependencyRef> deps) {
        return new StubDescriptor(id, version, deps);
    }

    private PluginDescriptor.DependencyRef dep(String id, String version) {
        return new StubDependencyRef(id, version);
    }

    @Test
    void resolvesIndependentPlugins() {
        PluginDescriptor a = descriptor("a", "0.1.0", List.of());
        PluginDescriptor b = descriptor("b", "0.1.0", List.of());
        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b));
        assertEquals(2, result.loadOrder().size());
        assertTrue(result.disabledIds().isEmpty());
    }

    @Test
    void missingDependencyDisablesPlugin() {
        PluginDescriptor a = descriptor("a", "0.1.0", List.of(dep("missing", "0.1.0")));
        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a));
        assertTrue(result.loadOrder().isEmpty());
        assertEquals(List.of("a"), result.disabledIds());
    }

    @Test
    void cyclicDependencyReportsCycle() {
        PluginDescriptor a = descriptor("a", "0.1.0", List.of(dep("b", "0.1.0")));
        PluginDescriptor b = descriptor("b", "0.1.0", List.of(dep("a", "0.1.0")));
        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b));
        assertFalse(result.cycles().isEmpty());
    }

    @Test
    void cyclicDependencyDisablesCycleMembers() {
        PluginDescriptor a = descriptor("dev.turboism.plugin.a", "0.1.0", List.of(dep("dev.turboism.plugin.b", "0.1.0")));
        PluginDescriptor b = descriptor("dev.turboism.plugin.b", "0.1.0", List.of(dep("dev.turboism.plugin.a", "0.1.0")));

        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b));

        assertTrue(result.cycles().stream().anyMatch(cycle -> cycle.contains("dev.turboism.plugin.a")));
        assertTrue(result.disabledIds().containsAll(List.of("dev.turboism.plugin.a", "dev.turboism.plugin.b")));
        assertTrue(result.loadOrder().isEmpty());
    }

    @Test
    void transitiveDependencyFailureDisablesDependentPlugin() {
        PluginDescriptor a = descriptor("dev.turboism.plugin.a", "0.1.0", List.of(dep("dev.turboism.plugin.b", "0.1.0")));
        PluginDescriptor b = descriptor("dev.turboism.plugin.b", "0.1.0", List.of(dep("dev.turboism.plugin.missing", "0.1.0")));

        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b));

        assertTrue(result.disabledIds().containsAll(List.of("dev.turboism.plugin.a", "dev.turboism.plugin.b")));
        assertTrue(result.loadOrder().isEmpty());
    }

    @Test
    void cycleFailureDisablesTransitiveDependents() {
        PluginDescriptor a = descriptor("dev.turboism.plugin.a", "0.1.0", List.of(dep("dev.turboism.plugin.b", "0.1.0")));
        PluginDescriptor b = descriptor("dev.turboism.plugin.b", "0.1.0", List.of(dep("dev.turboism.plugin.c", "0.1.0")));
        PluginDescriptor c = descriptor("dev.turboism.plugin.c", "0.1.0", List.of(dep("dev.turboism.plugin.b", "0.1.0")));

        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b, c));

        assertFalse(result.cycles().isEmpty());
        assertTrue(result.disabledIds().containsAll(List.of(
            "dev.turboism.plugin.a",
            "dev.turboism.plugin.b",
            "dev.turboism.plugin.c"
        )));
        assertTrue(result.loadOrder().isEmpty());
    }

    @Test
    void versionMismatchDisablesPlugin() {
        PluginDescriptor a = descriptor("a", "0.1.0", List.of(dep("b", "[0.2.0,0.3.0)")));
        PluginDescriptor b = descriptor("b", "0.1.0", List.of());
        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b));
        assertEquals(List.of("a"), result.disabledIds());
    }

    private record StubDescriptor(String id, String version, Map<String, String> entrypoints,
                                  String turboismApi, List<DependencyRef> dependencies,
                                  List<PermissionRef> permissions, Environment environment) implements PluginDescriptor {

        StubDescriptor(String id, String version, List<DependencyRef> deps) {
            this(id, version, Map.of("plugin", id + ".Plugin"), "[0.1.0,0.2.0)", deps,
                List.of(), new StubEnvironment());
        }

        @Override public String name() { return id; }
        @Override public String description() { return ""; }
        @Override public List<Author> authors() { return List.of(); }
        @Override public String license() { return "UNSPECIFIED"; }
        @Override public Optional<String> homepage() { return Optional.empty(); }
        @Override public List<String> capabilities() { return List.of(); }
    }

    private record StubDependencyRef(String id, String version) implements PluginDescriptor.DependencyRef {
        @Override public String type() { return "required"; }
        @Override public String ordering() { return "none"; }
        @Override public Optional<String> reason() { return Optional.empty(); }
    }

    private static class StubEnvironment implements PluginDescriptor.Environment {
        @Override public boolean requiresCubism() { return false; }
        @Override public String ui() { return "none"; }
    }
}
