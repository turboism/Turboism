package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.event.EventSubscriberHandler;
import dev.turboism.sdk.event.EventSubscriberRegistrar;
import dev.turboism.sdk.event.GeneratedSubscriberCatalog;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Loads generated catalogs from one plugin artifact and falls back to reviewed reflection. */
public final class GeneratedSubscriberCatalogLoader {

    private final EntrypointSubscriberCatalog fallback = new EntrypointSubscriberCatalog();

    public List<EventSubscriberDescriptor> inspect(
        final List<?> entrypoints,
        final ClassLoader pluginClassLoader
    ) {
        final List<?> values = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
        final Map<Class<?>, GeneratedSubscriberCatalog> catalogs = pluginClassLoader == null
            ? Map.of()
            : load(pluginClassLoader);
        final List<EventSubscriberDescriptor> descriptors = new ArrayList<>();
        for (int entrypointOrdinal = 0; entrypointOrdinal < values.size(); entrypointOrdinal++) {
            final Object entrypoint = Objects.requireNonNull(values.get(entrypointOrdinal), "entrypoint");
            final GeneratedSubscriberCatalog catalog = catalogs.get(entrypoint.getClass());
            if (catalog == null) {
                descriptors.addAll(fallback.inspectOne(entrypoint, entrypointOrdinal));
                continue;
            }
            final int ordinal = entrypointOrdinal;
            final List<EventSubscriberDescriptor> generated = new ArrayList<>();
            catalog.register(entrypoint, new DescriptorRegistrar(entrypoint, ordinal, generated));
            generated.sort(java.util.Comparator
                .comparingInt(EventSubscriberDescriptor::methodOrdinal)
                .thenComparing(EventSubscriberDescriptor::canonicalSignature));
            validateGenerated(generated, entrypoint.getClass());
            descriptors.addAll(generated);
        }
        return List.copyOf(descriptors);
    }

    private static void validateGenerated(
        final List<EventSubscriberDescriptor> descriptors,
        final Class<?> entrypointType
    ) {
        int expectedOrdinal = 0;
        final java.util.Set<String> signatures = new java.util.HashSet<>();
        for (EventSubscriberDescriptor descriptor : descriptors) {
            if (descriptor.methodOrdinal() != expectedOrdinal) {
                throw new IllegalArgumentException(
                    "Generated subscriber method ordinals must be contiguous for "
                        + entrypointType.getName()
                );
            }
            if (!signatures.add(descriptor.canonicalSignature())) {
                throw new IllegalArgumentException(
                    "Duplicate generated subscriber signature for " + entrypointType.getName()
                );
            }
            expectedOrdinal++;
        }
    }

    private static Map<Class<?>, GeneratedSubscriberCatalog> load(final ClassLoader loader) {
        final Map<Class<?>, GeneratedSubscriberCatalog> catalogs = new IdentityHashMap<>();
        try {
            for (GeneratedSubscriberCatalog catalog : ServiceLoader.load(
                GeneratedSubscriberCatalog.class,
                loader
            )) {
                final Class<?> catalogType = catalog.getClass();
                final Class<?> entrypointType = Objects.requireNonNull(
                    catalog.entrypointType(),
                    "generated catalog entrypointType"
                );
                if (catalogType.getClassLoader() != loader
                    || entrypointType.getClassLoader() != loader) {
                    throw new IllegalArgumentException(
                        "Generated subscriber catalog must belong to the plugin artifact: "
                            + catalogType.getName()
                    );
                }
                if (catalogs.put(entrypointType, catalog) != null) {
                    throw new IllegalArgumentException(
                        "Duplicate generated subscriber catalog for " + entrypointType.getName()
                    );
                }
            }
        } catch (ServiceConfigurationError failure) {
            throw new IllegalArgumentException("Generated subscriber catalog is invalid", failure);
        }
        return catalogs;
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private static final class DescriptorRegistrar implements EventSubscriberRegistrar {
        private final Object entrypoint;
        private final int entrypointOrdinal;
        private final List<EventSubscriberDescriptor> descriptors;

        private DescriptorRegistrar(
            final Object entrypoint,
            final int entrypointOrdinal,
            final List<EventSubscriberDescriptor> descriptors
        ) {
            this.entrypoint = entrypoint;
            this.entrypointOrdinal = entrypointOrdinal;
            this.descriptors = descriptors;
        }

        @Override
        public <T extends EventBus.TurboismEvent> void register(
            final Class<T> eventType,
            final EventPriority priority,
            final int methodOrdinal,
            final String canonicalSignature,
            final EventSubscriberHandler<T> handler
        ) {
            final Class<T> type = Objects.requireNonNull(eventType, "eventType");
            if (type.getClassLoader() != entrypoint.getClass().getClassLoader()
                && type.getClassLoader() != EventBus.class.getClassLoader()) {
                throw new IllegalArgumentException(
                    "Generated subscriber event type must belong to the plugin artifact or SDK: "
                        + type.getName()
                );
            }
            if (methodOrdinal < 0) {
                throw new IllegalArgumentException("methodOrdinal must not be negative");
            }
            descriptors.add(EventSubscriberDescriptor.generated(
                entrypoint,
                type,
                Objects.requireNonNull(priority, "priority"),
                entrypointOrdinal,
                methodOrdinal,
                requireText(canonicalSignature, "canonicalSignature"),
                Objects.requireNonNull(handler, "handler")
            ));
        }
    }
}
