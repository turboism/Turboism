package dev.turboism.core.dependency;

import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    private final DependencyResolver resolver = new DependencyResolver();

    private PluginDescriptor descriptor(String id, String version, List<PluginDescriptor.DependencyRef> deps) {
        return new StubDescriptor(id, version, deps);
    }

    private PluginDescriptor.DependencyRef dep(String id, String version) {
        return dep(id, version, "required", "none");
    }

    private PluginDescriptor.DependencyRef dep(String id, String version, String type, String ordering) {
        return new StubDependencyRef(id, version, type, ordering);
    }

    private static List<String> ids(DependencyResolver.ResolutionResult result) {
        return result.loadOrder().stream().map(DependencyResolver.ResolvedPlugin::id).toList();
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
        assertTrue(result.disabledReasons().get("a").contains("missing"));
    }

    @Test
    void cyclicOrderingReportsCycle() {
        PluginDescriptor a = descriptor("a", "0.1.0", List.of(dep("b", "0.1.0", "required", "after")));
        PluginDescriptor b = descriptor("b", "0.1.0", List.of(dep("a", "0.1.0", "required", "after")));
        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b));
        assertFalse(result.cycles().isEmpty());
    }

    @Test
    void cyclicOrderingDisablesCycleMembers() {
        PluginDescriptor a = descriptor("dev.turboism.plugin.a", "0.1.0", List.of(dep("dev.turboism.plugin.b", "0.1.0", "required", "after")));
        PluginDescriptor b = descriptor("dev.turboism.plugin.b", "0.1.0", List.of(dep("dev.turboism.plugin.a", "0.1.0", "required", "after")));

        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b));

        assertTrue(result.cycles().stream().anyMatch(cycle -> cycle.contains("dev.turboism.plugin.a")));
        assertTrue(result.disabledIds().containsAll(List.of("dev.turboism.plugin.a", "dev.turboism.plugin.b")));
        assertTrue(result.loadOrder().isEmpty());
    }

    @Test
    void mutualRequiredWithoutOrderingIsNotACycle() {
        // Required refs gate on existence and version, not order: with no declared ordering
        // edge the plugins load in discovery order and neither is disabled.
        PluginDescriptor a = descriptor("dev.turboism.plugin.a", "0.1.0", List.of(dep("dev.turboism.plugin.b", "0.1.0")));
        PluginDescriptor b = descriptor("dev.turboism.plugin.b", "0.1.0", List.of(dep("dev.turboism.plugin.a", "0.1.0")));

        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b));

        assertTrue(result.cycles().isEmpty());
        assertTrue(result.disabledIds().isEmpty());
        assertEquals(List.of("dev.turboism.plugin.a", "dev.turboism.plugin.b"), ids(result));
    }

    @Test
    void optionalOrderingCycleDisablesMembers() {
        // A ordering-only cycle through optional refs is unsatisfiable and diagnosed the same way.
        PluginDescriptor a = descriptor("dev.turboism.plugin.a", "0.1.0", List.of(dep("dev.turboism.plugin.b", "0.1.0", "optional", "before")));
        PluginDescriptor b = descriptor("dev.turboism.plugin.b", "0.1.0", List.of(dep("dev.turboism.plugin.a", "0.1.0", "optional", "before")));
        PluginDescriptor free = descriptor("dev.turboism.plugin.free", "0.1.0", List.of());

        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b, free));

        assertFalse(result.cycles().isEmpty());
        assertTrue(result.disabledIds().containsAll(List.of("dev.turboism.plugin.a", "dev.turboism.plugin.b")));
        assertEquals(List.of("dev.turboism.plugin.free"), ids(result));
    }

    @Test
    void overlappingOptionalOrderingCyclesAllDisable() {
        // Two cycles sharing a member: both must be diagnosed and every member disabled, in any
        // discovery order.
        PluginDescriptor a = descriptor("a", "0.1.0", List.of(dep("b", "0.1.0", "optional", "before")));
        PluginDescriptor b = descriptor("b", "0.1.0", List.of(
            dep("a", "0.1.0", "optional", "before"),
            dep("c", "0.1.0", "optional", "before")
        ));
        PluginDescriptor c = descriptor("c", "0.1.0", List.of(dep("b", "0.1.0", "optional", "before")));
        PluginDescriptor free = descriptor("free", "0.1.0", List.of());

        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b, c, free));

        assertFalse(result.cycles().isEmpty());
        assertTrue(result.disabledIds().containsAll(List.of("a", "b", "c")));
        assertEquals(List.of("free"), ids(result));
    }

    @Test
    void lateCyclePropagationLeavesNoDeadEntriesInLoadOrder() {
        // Regression: a requires b (none); b and c form an optional ordering cycle; d declares
        // optional-after on cyclic b. Cycle members and the required dependent are disabled while
        // the unconstrained d still loads — nothing may be both emitted and disabled.
        PluginDescriptor a = descriptor("a", "0.1.0", List.of(dep("b", "0.1.0")));
        PluginDescriptor b = descriptor("b", "0.1.0", List.of(dep("c", "0.1.0", "optional", "before")));
        PluginDescriptor c = descriptor("c", "0.1.0", List.of(dep("b", "0.1.0", "optional", "before")));
        PluginDescriptor d = descriptor("d", "0.1.0", List.of(dep("b", "0.1.0", "optional", "after")));

        DependencyResolver.ResolutionResult result = resolver.resolve(List.of(a, b, c, d));

        assertFalse(result.cycles().isEmpty());
        assertTrue(result.disabledIds().containsAll(List.of("a", "b", "c")));
        assertEquals(List.of("d"), ids(result));
        assertTrue(ids(result).stream().noneMatch(result.disabledIds()::contains));
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
        PluginDescriptor b = descriptor("dev.turboism.plugin.b", "0.1.0", List.of(dep("dev.turboism.plugin.c", "0.1.0", "required", "after")));
        PluginDescriptor c = descriptor("dev.turboism.plugin.c", "0.1.0", List.of(dep("dev.turboism.plugin.b", "0.1.0", "required", "after")));

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

    @Test
    void requiredOrderingControlsLoadDirection() {
        // ordering is the declarer's position relative to the dependency target: consumer
        // declaring "after" provider loads behind it, "before" ahead of it, "none" unconstrained.
        PluginDescriptor consumerAfter = descriptor("consumer", "0.1.0", List.of(dep("provider", "0.1.0", "required", "after")));
        PluginDescriptor consumerBefore = descriptor("consumer", "0.1.0", List.of(dep("provider", "0.1.0", "required", "before")));
        PluginDescriptor consumerNone = descriptor("consumer", "0.1.0", List.of(dep("provider", "0.1.0", "required", "none")));
        PluginDescriptor provider = descriptor("provider", "0.1.0", List.of());

        assertEquals(List.of("provider", "consumer"), ids(resolver.resolve(List.of(consumerAfter, provider))));
        assertEquals(List.of("consumer", "provider"), ids(resolver.resolve(List.of(consumerBefore, provider))));
        assertEquals(List.of("consumer", "provider"), ids(resolver.resolve(List.of(consumerNone, provider))));
        // "after" keeps provider-first even when discovery finds the provider first.
        assertEquals(List.of("provider", "consumer"), ids(resolver.resolve(List.of(provider, consumerAfter))));
    }

    @Test
    void requiredBeforeStillGatesOnExistenceAndVersion() {
        PluginDescriptor missing = descriptor("consumer", "0.1.0", List.of(dep("absent", "0.1.0", "required", "before")));
        PluginDescriptor incompatible = descriptor("consumer", "0.1.0", List.of(dep("provider", "[0.2.0,0.3.0)", "required", "before")));
        PluginDescriptor provider = descriptor("provider", "0.1.0", List.of());

        assertEquals(List.of("consumer"), resolver.resolve(List.of(missing)).disabledIds());
        assertEquals(List.of("consumer"), resolver.resolve(List.of(incompatible, provider)).disabledIds());
    }

    @Test
    void optionalOrderingAppliesOnlyToApplicableMatchingTarget() {
        // An optional ref never disables its declarer; ordering applies only while the target is
        // present, resolvable, and inside the declared range.
        PluginDescriptor provider = descriptor("provider", "0.1.0", List.of());
        PluginDescriptor absent = descriptor("consumer", "0.1.0", List.of(dep("absent", "0.1.0", "optional", "before")));
        PluginDescriptor after = descriptor("consumer", "0.1.0", List.of(dep("provider", "0.1.0", "optional", "after")));
        PluginDescriptor before = descriptor("consumer", "0.1.0", List.of(dep("provider", "0.1.0", "optional", "before")));
        PluginDescriptor mismatched = descriptor("consumer", "0.1.0", List.of(dep("provider", "[0.2.0,0.3.0)", "optional", "after")));

        assertEquals(List.of("consumer"), ids(resolver.resolve(List.of(absent))));
        assertEquals(List.of("provider", "consumer"), ids(resolver.resolve(List.of(after, provider))));
        assertEquals(List.of("consumer", "provider"), ids(resolver.resolve(List.of(provider, before))));
        // Present but version-incompatible: inert like an absent target — no edge, no disable.
        DependencyResolver.ResolutionResult inert = resolver.resolve(List.of(mismatched, provider));
        assertTrue(inert.disabledIds().isEmpty());
        assertEquals(List.of("consumer", "provider"), ids(inert));
    }

    @Test
    void discoveryOrderBreaksTiesWithoutOverridingDeclaredOrdering() {
        PluginDescriptor a = descriptor("a", "0.1.0", List.of());
        PluginDescriptor b = descriptor("b", "0.1.0", List.of());
        PluginDescriptor c = descriptor("c", "0.1.0", List.of(dep("a", "0.1.0", "optional", "after")));

        assertEquals(List.of("a", "b", "c"), ids(resolver.resolve(List.of(a, b, c))));
        assertEquals(List.of("b", "a", "c"), ids(resolver.resolve(List.of(b, a, c))));
        // The declared optional-after edge holds under permuted input, while the unconstrained
        // plugins keep discovery order.
        assertEquals(List.of("b", "a", "c"), ids(resolver.resolve(List.of(c, b, a))));
    }

    @Test
    void unsupportedOrderingTokenDisablesDeclarerWithReason() {
        // Ordering tokens outside before/after/none fail closed like the admission-time schema
        // check rather than silently picking a direction.
        PluginDescriptor consumer = descriptor("consumer", "0.1.0", List.of(dep("provider", "0.1.0", "required", "sideways")));
        PluginDescriptor optional = descriptor("consumer", "0.1.0", List.of(dep("provider", "0.1.0", "optional", "sideways")));
        PluginDescriptor provider = descriptor("provider", "0.1.0", List.of());

        DependencyResolver.ResolutionResult required = resolver.resolve(List.of(consumer, provider));
        assertEquals(List.of("consumer"), required.disabledIds());
        assertTrue(required.disabledReasons().get("consumer").contains("sideways"));
        assertEquals(List.of("provider"), ids(required));

        DependencyResolver.ResolutionResult optionalResult = resolver.resolve(List.of(optional, provider));
        assertEquals(List.of("consumer"), optionalResult.disabledIds());
        assertEquals(List.of("provider"), ids(optionalResult));
    }

    private record StubDescriptor(String id, String version, List<String> entrypoints,
                                  String turboismApi, List<DependencyRef> dependencies,
                                  List<PermissionRef> permissions, Environment environment) implements PluginDescriptor {

        StubDescriptor(String id, String version, List<DependencyRef> deps) {
            this(id, version, List.of(id + ".Plugin"), "[0.1.0,0.2.0)", deps,
                List.of(), new StubEnvironment());
        }

        @Override public String name() { return id; }
        @Override public String description() { return ""; }
        @Override public List<Author> authors() { return List.of(); }
        @Override public String license() { return "UNSPECIFIED"; }
        @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
        @Override public List<String> resources() { return List.of(); }
        @Override public I18n i18n() { return new StubI18n(); }
        @Override public List<String> capabilities() { return List.of(); }
    }

    private record StubI18n() implements PluginDescriptor.I18n {
        @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
        @Override public List<String> locales() { return List.of(); }
    }

    private record StubDependencyRef(String id, String version, String type, String ordering)
        implements PluginDescriptor.DependencyRef {
        @Override public Optional<String> reason() { return Optional.empty(); }
    }

    private static class StubEnvironment implements PluginDescriptor.Environment {
        @Override public boolean requiresCubism() { return false; }
        @Override public String ui() { return "none"; }
    }
}
