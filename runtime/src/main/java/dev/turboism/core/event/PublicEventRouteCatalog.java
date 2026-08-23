package dev.turboism.core.event;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Resolves descriptor-declared provider-owned event contracts without ClassLoader delegation. */
final class PublicEventRouteCatalog {

    private final ConcurrentMap<String, Provider> providers = new ConcurrentHashMap<>();

    void preflight(final PluginDescriptor descriptor) {
        final PluginDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
        value.eventExports().forEach(exported ->
            PublicEventAbi.resolve(exported.eventType(), exported.abiSha256())
        );
        value.eventImports().forEach(imported ->
            PublicEventAbi.resolve(imported.eventType(), imported.abiSha256())
        );
    }

    void admit(final PluginEventOwnerKey owner, final PluginDescriptor descriptor) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        final PluginDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
        if (!key.pluginId().equals(value.id())) {
            throw new IllegalArgumentException("Event owner does not match plugin descriptor");
        }
        final Map<String, Export> exports = new HashMap<>();
        for (PluginDescriptor.EventExport exported : value.eventExports()) {
            PublicEventAbi.resolve(exported.eventType(), exported.abiSha256());
            final Export contract = Export.of(value.id(), exported);
            if (exports.put(contract.id(), contract) != null) {
                throw new IllegalArgumentException(
                    "Duplicate public event export: " + value.id() + ":" + contract.id()
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
        for (Export exported : exports.values()) {
            final boolean collision = providers.values().stream()
                .filter(current -> !current.owner().pluginId().equals(value.id()))
                .flatMap(current -> current.exports().values().stream())
                .anyMatch(current -> current.eventType().equals(exported.eventType()));
            if (collision) {
                throw new IllegalArgumentException(
                    "Public event type is already exported by another provider: "
                        + exported.eventType()
                );
            }
        }
        final Provider provider = new Provider(
            key,
            value.version(),
            Map.copyOf(exports),
            Map.copyOf(imports),
            Map.copyOf(dependencies)
        );
        validateImports(provider);
        providers.compute(value.id(), (pluginId, current) -> {
            if (current != null && current.owner().generation() >= key.generation()) {
                throw new IllegalStateException(
                    "Public event provider generation is not newer: " + key
                );
            }
            return provider;
        });
    }

    List<String> remove(final PluginEventOwnerKey owner) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        final java.util.concurrent.atomic.AtomicReference<List<String>> removed =
            new java.util.concurrent.atomic.AtomicReference<>(List.of());
        providers.computeIfPresent(key.pluginId(), (ignored, current) -> {
            if (!current.owner().equals(key)) {
                return current;
            }
            removed.set(current.exports().values().stream().map(Export::eventType).toList());
            return null;
        });
        return removed.get();
    }

    void requirePublication(
        final PluginEventOwnerKey publisher,
        final EventBus.TurboismEvent event
    ) {
        final PluginEventOwnerKey owner = Objects.requireNonNull(publisher, "publisher");
        final EventBus.TurboismEvent value = Objects.requireNonNull(event, "event");
        final List<Export> matching = providers.values().stream()
            .flatMap(provider -> provider.exports().values().stream())
            .filter(exported -> exported.eventType().equals(value.getClass().getName()))
            .toList();
        if (matching.isEmpty()) {
            return;
        }
        if (matching.size() != 1 || !matching.get(0).providerId().equals(owner.pluginId())) {
            throw new IllegalArgumentException(
                "Only the declared provider may publish public event type "
                    + value.getClass().getName()
            );
        }
        final Provider provider = requireProvider(owner.pluginId());
        if (!provider.owner().equals(owner)) {
            throw new IllegalStateException(
                "Stale provider generation cannot publish public events: " + owner
            );
        }
    }

    void requireSubscription(
        final PluginEventOwnerKey subscriber,
        final Class<? extends EventBus.TurboismEvent> eventType
    ) {
        final PluginEventOwnerKey owner = Objects.requireNonNull(subscriber, "subscriber");
        final Class<?> type = Objects.requireNonNull(eventType, "eventType");
        final List<Export> matching = providers.values().stream()
            .flatMap(provider -> provider.exports().values().stream())
            .filter(exported -> exported.eventType().equals(type.getName()))
            .toList();
        if (matching.isEmpty()) {
            return;
        }
        if (matching.size() != 1) {
            throw new IllegalArgumentException(
                "Public event type is exported by multiple providers: " + type.getName()
            );
        }
        final Export exported = matching.get(0);
        if (exported.providerId().equals(owner.pluginId())) {
            return;
        }
        final Provider consumer = requireProvider(owner.pluginId());
        final Import imported = consumer.imports().get(
            new RouteKey(exported.providerId(), exported.id())
        );
        if (imported == null) {
            throw new IllegalArgumentException(
                "Plugin has not imported public event " + exported.providerId() + ":" + exported.id()
            );
        }
        requireCompatible(consumer, imported, exported, type);
    }

    boolean isPublicType(final Class<?> eventType) {
        final String typeName = Objects.requireNonNull(eventType, "eventType").getName();
        return providers.values().stream()
            .flatMap(provider -> provider.exports().values().stream())
            .anyMatch(exported -> exported.eventType().equals(typeName));
    }

    boolean mayReceive(
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
            final Provider provider = providers.get(imported.route().providerId());
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
            requireCompatible(consumer, imported, exported, PublicEventAbi.resolve(
                imported.eventType(),
                imported.abiSha256()
            ));
        }
    }

    private Provider requireProvider(final String pluginId) {
        final Provider provider = providers.get(pluginId);
        if (provider == null) {
            throw new IllegalStateException("Public event plugin is not admitted: " + pluginId);
        }
        return provider;
    }

    private void requireCompatible(
        final Provider consumer,
        final Import imported,
        final Export exported,
        final Class<?> eventType
    ) {
        final PluginDescriptor.DependencyRef dependency = consumer.dependencies().get(
            exported.providerId()
        );
        final Provider provider = requireProvider(exported.providerId());
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
