package dev.turboism.sdk.plugin;

import dev.turboism.sdk.Incubating;
import java.util.Objects;
import java.util.Set;

/**
 * Factory for the {@link PluginServiceDirectory} every {@link PluginContext} exposes through
 * {@link PluginContext#services()}. The returned directory bridges each {@link PluginService}
 * member to the context's existing optional accessors, so contexts written before the
 * directory existed behave identically without an override.
 */
@SuppressWarnings("deprecation") // Bridges the deprecated pre-directory accessors.
@Incubating
public final class PluginServices {

    private PluginServices() {}

    /**
     * Builds a directory that resolves services through {@code context}'s optional accessors.
     *
     * @param context the plugin context to bridge, never {@code null}
     * @return a directory whose reads delegate to {@code context}
     */
    public static PluginServiceDirectory of(final PluginContext context) {
        Objects.requireNonNull(context, "context");
        return new PluginServiceDirectory() {

            @Override
            public Set<PluginService> installed() {
                return context.availableServices();
            }

            @Override
            public <T> java.util.Optional<T> find(final Class<T> serviceType) {
                Objects.requireNonNull(serviceType, "serviceType");
                for (PluginService member : PluginService.values()) {
                    if (member.type() == serviceType) {
                        return java.util.Optional.ofNullable(serviceType.cast(member.resolve(context)));
                    }
                }
                return java.util.Optional.empty();
            }
        };
    }
}
