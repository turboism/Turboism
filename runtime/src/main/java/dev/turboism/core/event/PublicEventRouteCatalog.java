package dev.turboism.core.event;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves descriptor-declared provider-owned event contracts without ClassLoader delegation. */
final class PublicEventRouteCatalog {

    private final Map<PluginEventOwnerKey, Provider> admitted = new HashMap<>();
    private final Map<String, Provider> active = new HashMap<>();
    private final Map<String, Provider> retained = new HashMap<>();

    void preflight(final PluginDescriptor descriptor) {
        final PluginDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
        value.eventExports().forEach(exported ->
            PublicEventAbi.resolve(exported.eventType(), exported.abiSha256())
        );
        value.eventImports().forEach(imported ->
            PublicEventAbi.resolve(imported.eventType(), imported.abiSha256())
        );
    }

    synchronized void admit(final PluginEventOwnerKey owner, final PluginDescriptor descriptor) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        final PluginDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
        if (!key.pluginId().equals(value.id())) {
            throw new IllegalArgumentException("Event owner does not match plugin descriptor");
        }
        final Map<String, Export> exports = new HashMap<>();
        final Set<String> exportedTypes = new HashSet<>();
        for (PluginDescriptor.EventExport exported : value.eventExports()) {
            PublicEventAbi.resolve(exported.eventType(), exported.abiSha256());
            final Export contract = Export.of(value.id(), exported);
            if (exports.put(contract.id(), contract) != null) {
                throw new IllegalArgumentException(
                    "Duplicate public event export: " + value.id() + ":" + contract.id()
                );
            }
            if (!exportedTypes.add(contract.eventType())) {
                throw new IllegalArgumentException(
                    "Public event type is exported more than once: " + contract.eventType()
                );
            }
        }
        final Map<RouteKey, Import> imports = new HashMap<>();
        for (PluginDescriptor.EventImport imported : value.eventImports()) {
            PublicEventAbi.resolve(imported.eventType(), imported.abiSha256());
            final Import contract = Import.of(imported);
            if (imports.put(contract.route(), contract) != null) {
                throw new IllegalArgumentException(
                    "Duplicate public event import: " + contract.route()
                );
            }
        }
        final Map<String, PluginDescriptor.DependencyRef> dependencies = new HashMap<>();
        for (PluginDescriptor.DependencyRef dependency : value.dependencies()) {
            dependencies.put(dependency.id(), dependency);
        }
        for (Import imported : imports.values()) {
            if (!dependencies.containsKey(imported.route().providerId())) {
                throw new IllegalArgumentException(
                    "Public event import requires a declared plugin dependency: "
                        + imported.route().providerId()
                );
            }
        }
        final long newestGeneration = admitted.keySet().stream()
            .filter(current -> current.pluginId().equals(key.pluginId()))
            .mapToLong(PluginEventOwnerKey::generation)
            .max()
            .orElse(0L);
        if (newestGeneration >= key.generation()) {
            throw new IllegalStateException(
                "Public event provider generation is not newer: " + key
            );
        }
        final Provider provider = new Provider(
            key,
            value.version(),
            Map.copyOf(exports),
            Map.copyOf(imports),
            Map.copyOf(dependencies)
        );
        requireUnambiguousClaims(provider);
        validateImports(provider);
        admitted.put(key, provider);
    }

    synchronized void activate(final PluginEventOwnerKey owner) {
        final Provider provider = admitted.get(Objects.requireNonNull(owner, "owner"));
        if (provider == null) {
            return;
        }
        validateImports(provider);
        validateConsumers(provider);
        active.put(owner.pluginId(), provider);
        retained.put(owner.pluginId(), provider);
    }

    synchronized List<String> remove(final PluginEventOwnerKey owner) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        final Provider removed = admitted.remove(key);
        active.computeIfPresent(key.pluginId(), (ignored, current) ->
            current.owner().equals(key) ? null : current
        );
        if (removed == null) {
            return List.of();
        }
        return removed.exports().values().stream().map(Export::eventType).toList();
    }

    synchronized void requirePublication(
        final PluginEventOwnerKey publisher,
        final EventBus.TurboismEvent event
    ) {
        final PluginEventOwnerKey owner = Objects.requireNonNull(publisher, "publisher");
        final EventBus.TurboismEvent value = Objects.requireNonNull(event, "event");
        final PublicRoute route = route(value.getClass().getName());
        if (route == null) {
            return;
        }
        if (!route.key().providerId().equals(owner.pluginId())) {
            throw new IllegalArgumentException(
                "Only the declared provider may publish public event type "
                    + value.getClass().getName()
            );
        }
        final Provider provider = active.get(owner.pluginId());
        if (provider == null || !provider.owner().equals(owner)) {
            throw new IllegalStateException(
                "Inactive or stale provider generation cannot publish public events: " + owner
            );
        }
        final Export exported = provider.exports().get(route.key().eventId());
        if (exported == null || !exported.eventType().equals(value.getClass().getName())) {
            throw new IllegalStateException(
                "Active provider does not export public event " + route.key()
            );
        }
    }

    synchronized void requireSubscription(
        final PluginEventOwnerKey subscriber,
        final Class<? extends EventBus.TurboismEvent> eventType
    ) {
        final PluginEventOwnerKey owner = Objects.requireNonNull(subscriber, "subscriber");
        final Class<?> type = Objects.requireNonNull(eventType, "eventType");
        final PublicRoute route = route(type.getName());
        if (route == null) {
            return;
        }
        if (route.key().providerId().equals(owner.pluginId())) {
            return;
        }
        final Provider consumer = admitted.get(owner);
        if (consumer == null) {
            throw new IllegalArgumentException(
                "Plugin has not imported public event " + route.key()
            );
        }
        final Import imported = consumer.imports().get(route.key());
        if (imported == null) {
            throw new IllegalArgumentException(
                "Plugin has not imported public event " + route.key()
            );
        }
        if (!imported.eventType().equals(type.getName())
            || !imported.abiSha256().equals(route.abiSha256())) {
            throw new IllegalArgumentException(
                "Public event contract ABI does not match: " + imported.route()
            );
        }
        final Provider provider = effectiveProvider(route.key().providerId());
        if (provider != null) {
            final Export exported = provider.exports().get(route.key().eventId());
            if (exported != null) {
                requireCompatible(consumer, imported, exported, provider, type);
            }
        }
    }

    synchronized boolean isPublicType(final Class<?> eventType) {
        return route(Objects.requireNonNull(eventType, "eventType").getName()) != null;
    }

    synchronized boolean mayReceive(
        final PluginEventOwnerKey subscriber,
        final Class<?> concreteEventType
    ) {
        if (!isPublicType(concreteEventType)) {
            return true;
        }
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends EventBus.TurboismEvent> type =
                (Class<? extends EventBus.TurboismEvent>) concreteEventType;
            requireSubscription(subscriber, type);
            return true;
        } catch (IllegalArgumentException | IllegalStateException denied) {
            return false;
        }
    }

    private void validateImports(final Provider consumer) {
        for (Import imported : consumer.imports().values()) {
            final Provider provider = effectiveProvider(imported.route().providerId());
            if (provider == null) {
                if (imported.required()) {
                    throw new IllegalArgumentException(
                        "Required public event provider is not admitted: "
                            + imported.route().providerId()
                    );
                }
                continue;
            }
            final Export exported = provider.exports().get(imported.route().eventId());
            if (exported == null) {
                if (imported.required()) {
                    throw new IllegalArgumentException(
                        "Required public event export is absent: " + imported.route()
                    );
                }
                continue;
            }
            requireCompatible(consumer, imported, exported, provider, PublicEventAbi.resolve(
                imported.eventType(),
                imported.abiSha256()
            ));
        }
    }

    private void validateConsumers(final Provider provider) {
        for (Provider consumer : active.values()) {
            if (consumer.owner().pluginId().equals(provider.owner().pluginId())) {
                continue;
            }
            for (Import imported : consumer.imports().values()) {
                if (!imported.route().providerId().equals(provider.owner().pluginId())) {
                    continue;
                }
                final Export exported = provider.exports().get(imported.route().eventId());
                if (exported == null) {
                    if (imported.required()) {
                        throw new IllegalArgumentException(
                            "Required public event export is absent: " + imported.route()
                        );
                    }
                    continue;
                }
                requireCompatible(consumer, imported, exported, provider, PublicEventAbi.resolve(
                    imported.eventType(),
                    imported.abiSha256()
                ));
            }
        }
    }

    private void requireUnambiguousClaims(final Provider candidate) {
        final List<PublicClaim> existing = claims();
        for (PublicClaim claim : claims(candidate)) {
            for (PublicClaim current : existing) {
                if (current.owner().equals(candidate.owner())) {
                    continue;
                }
                if (current.eventType().equals(claim.eventType())
                    && (!current.route().equals(claim.route())
                        || !current.abiSha256().equals(claim.abiSha256()))) {
                    throw new IllegalArgumentException(
                        "Public event type is already claimed by " + current.route()
                            + ": " + claim.eventType()
                    );
                }
                if (current.route().equals(claim.route())
                    && (!current.eventType().equals(claim.eventType())
                        || !current.abiSha256().equals(claim.abiSha256()))) {
                    throw new IllegalArgumentException(
                        "Public event route has conflicting contracts: " + claim.route()
                    );
                }
            }
        }
    }

    private List<PublicClaim> claims() {
        final List<PublicClaim> claims = new ArrayList<>();
        retained.values().forEach(provider -> claims.addAll(claims(provider)));
        admitted.values().forEach(provider -> claims.addAll(claims(provider)));
        return claims;
    }

    private static List<PublicClaim> claims(final Provider provider) {
        final List<PublicClaim> claims = new ArrayList<>();
        provider.exports().values().forEach(exported -> claims.add(new PublicClaim(
            provider.owner(),
            new RouteKey(exported.providerId(), exported.id()),
            exported.eventType(),
            exported.abiSha256()
        )));
        provider.imports().values().forEach(imported -> claims.add(new PublicClaim(
            provider.owner(),
            imported.route(),
            imported.eventType(),
            imported.abiSha256()
        )));
        return claims;
    }

    private PublicRoute route(final String eventType) {
        PublicRoute matched = null;
        for (Provider provider : effectiveProviders()) {
            for (Export exported : provider.exports().values()) {
                if (!exported.eventType().equals(eventType)) {
                    continue;
                }
                matched = mergeRoute(matched, new PublicRoute(
                    new RouteKey(exported.providerId(), exported.id()),
                    exported.eventType(),
                    exported.abiSha256()
                ));
            }
        }
        for (Provider consumer : admitted.values()) {
            for (Import imported : consumer.imports().values()) {
                if (!imported.eventType().equals(eventType)) {
                    continue;
                }
                matched = mergeRoute(matched, new PublicRoute(
                    imported.route(),
                    imported.eventType(),
                    imported.abiSha256()
                ));
            }
        }
        return matched;
    }

    private static PublicRoute mergeRoute(
        final PublicRoute current,
        final PublicRoute candidate
    ) {
        if (current == null) {
            return candidate;
        }
        if (!current.equals(candidate)) {
            throw new IllegalStateException(
                "Public event type has ambiguous routes: " + candidate.eventType()
            );
        }
        return current;
    }

    private List<Provider> effectiveProviders() {
        final Set<String> pluginIds = new HashSet<>();
        pluginIds.addAll(retained.keySet());
        admitted.keySet().forEach(owner -> pluginIds.add(owner.pluginId()));
        return pluginIds.stream()
            .map(this::effectiveProvider)
            .filter(Objects::nonNull)
            .toList();
    }

    private Provider effectiveProvider(final String pluginId) {
        final Provider activeProvider = active.get(pluginId);
        if (activeProvider != null) {
            return activeProvider;
        }
        final Provider retainedProvider = retained.get(pluginId);
        if (retainedProvider != null) {
            return retainedProvider;
        }
        return admitted.values().stream()
            .filter(provider -> provider.owner().pluginId().equals(pluginId))
            .min(java.util.Comparator.comparingLong(provider -> provider.owner().generation()))
            .orElse(null);
    }

    private void requireCompatible(
        final Provider consumer,
        final Import imported,
        final Export exported,
        final Provider provider,
        final Class<?> eventType
    ) {
        final PluginDescriptor.DependencyRef dependency = consumer.dependencies().get(
            exported.providerId()
        );
        if (dependency == null || !VersionRange.parse(dependency.version()).contains(
            PluginVersion.parse(provider.pluginVersion())
        )) {
            throw new IllegalArgumentException(
                "Public event provider version is outside the declared dependency range: "
                    + exported.providerId()
            );
        }
        if (!VersionRange.parse(imported.contractVersion()).contains(
            PluginVersion.parse(exported.contractVersion())
        )) {
            throw new IllegalArgumentException(
                "Public event contract version is incompatible: " + imported.route()
            );
        }
        if (!imported.eventType().equals(exported.eventType())
            || !imported.eventType().equals(eventType.getName())
            || !imported.abiSha256().equals(exported.abiSha256())) {
            throw new IllegalArgumentException(
                "Public event contract ABI does not match: " + imported.route()
            );
        }
        if (eventType.getClassLoader() != EventBus.class.getClassLoader()) {
            throw new IllegalArgumentException(
                "Public event payload type must be owned by the shared SDK ClassLoader: "
                    + eventType.getName()
            );
        }
    }

    private record Provider(
        PluginEventOwnerKey owner,
        String pluginVersion,
        Map<String, Export> exports,
        Map<RouteKey, Import> imports,
        Map<String, PluginDescriptor.DependencyRef> dependencies
    ) {
    }

    private record Export(
        String providerId,
        String id,
        String contractVersion,
        String eventType,
        String abiSha256
    ) {
        private static Export of(
            final String providerId,
            final PluginDescriptor.EventExport exported
        ) {
            return new Export(
                providerId,
                exported.id(),
                exported.contractVersion(),
                exported.eventType(),
                exported.abiSha256()
            );
        }
    }

    private record Import(
        RouteKey route,
        String contractVersion,
        String eventType,
        String abiSha256,
        boolean required
    ) {
        private static Import of(final PluginDescriptor.EventImport imported) {
            return new Import(
                new RouteKey(imported.providerId(), imported.eventId()),
                imported.contractVersion(),
                imported.eventType(),
                imported.abiSha256(),
                imported.required()
            );
        }
    }

    private record PublicClaim(
        PluginEventOwnerKey owner,
        RouteKey route,
        String eventType,
        String abiSha256
    ) {
    }

    private record PublicRoute(RouteKey key, String eventType, String abiSha256) {
    }

    private record RouteKey(String providerId, String eventId) {
        private RouteKey {
            providerId = Objects.requireNonNull(providerId, "providerId");
            eventId = Objects.requireNonNull(eventId, "eventId");
        }

        @Override public String toString() {
            return providerId + ":" + eventId;
        }
    }
}
