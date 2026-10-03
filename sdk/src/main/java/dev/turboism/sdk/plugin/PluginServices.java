package dev.turboism.sdk.plugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Factories for the {@link PluginServiceDirectory} a {@link PluginContext} exposes through
 * {@link PluginContext#services()}.
 *
 * <p>{@link #of(PluginContext)} backs the {@link PluginContext#services()} default: it
 * resolves the context's remaining optional accessors ({@code localization},
 * {@code tasks}, {@code hostReads}, {@code storage}, {@code scripts}, {@code userFiles},
 * {@code selectionQuery}, {@code modelHierarchyQuery},
 * {@code cubismRead}, {@code modelObjects} and {@code config}) so contexts that only
 * override those accessors resolve them through the directory too. Services that have no
 * {@link PluginContext} accessor resolve to empty here; a context that installs them must
 * override {@link PluginContext#services()} with its own directory.</p>
 *
 * <p>{@link #builder()} builds a fixed directory from explicitly registered services — the
 * shape test doubles and adapters use to install directory-only services.</p>
 */
public final class PluginServices {

    private PluginServices() {}

    /**
     * Returns a directory that installs nothing.
     *
     * @return the empty directory, never {@code null}
     */
    public static PluginServiceDirectory empty() {
        return builder().build();
    }

    /**
     * Builds a directory that resolves services through {@code context}'s optional
     * accessors. Accessors returning their {@code unavailable()} sentinel resolve to
     * {@link Optional#empty()}; sentinel detection compares implementation classes, so
     * services whose {@code unavailable()} factory returns a fresh instance per call are
     * still recognized.
     *
     * @param context the plugin context to bridge, never {@code null}
     * @return a directory whose reads delegate to {@code context}
     */
    public static PluginServiceDirectory of(final PluginContext context) {
        Objects.requireNonNull(context, "context");
        return new PluginServiceDirectory() {

            @Override
            public Set<PluginService> installed() {
                final EnumSet<PluginService> installed = EnumSet.noneOf(PluginService.class);
                for (final PluginService member : PluginService.values()) {
                    if (resolveAccessor(context, member) != null) {
                        installed.add(member);
                    }
                }
                return Collections.unmodifiableSet(installed);
            }

            @Override
            public <T> Optional<T> find(final Class<T> serviceType) {
                Objects.requireNonNull(serviceType, "serviceType");
                final PluginService member = PluginService.forType(serviceType).orElse(null);
                if (member == null) {
                    return Optional.empty();
                }
                return Optional.ofNullable(serviceType.cast(resolveAccessor(context, member)));
            }
        };
    }

    /**
     * Starts a fixed directory. Registered services resolve by interface through
     * {@link PluginServiceDirectory#find(Class)}; a supplier that yields {@code null}
     * reports the service as absent.
     *
     * @return a new builder, never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates the services one fixed {@link PluginServiceDirectory} resolves. */
    public static final class Builder {

        private final Map<Class<?>, Supplier<?>> services = new LinkedHashMap<>();
        private PluginServiceDirectory fallback;

        private Builder() {}

        /**
         * Registers {@code service} for {@code serviceType}. Registering {@code null}
         * removes any earlier registration for the type.
         *
         * @param <T> the service interface type
         * @param serviceType the interface the service resolves under, never {@code null}
         * @param service the installed instance, or {@code null} to leave the slot absent
         * @return this builder
         */
        public <T> Builder install(final Class<T> serviceType, final T service) {
            Objects.requireNonNull(serviceType, "serviceType");
            if (service == null) {
                services.remove(serviceType);
            } else {
                services.put(serviceType, () -> service);
            }
            return this;
        }

        /**
         * Registers a lazily resolved service for {@code serviceType}. The supplier runs
         * on every directory read; yielding {@code null} reports the service as absent.
         *
         * @param <T> the service interface type
         * @param serviceType the interface the service resolves under, never {@code null}
         * @param service the service source, never {@code null}
         * @return this builder
         */
        public <T> Builder supply(final Class<T> serviceType, final Supplier<? extends T> service) {
            Objects.requireNonNull(serviceType, "serviceType");
            Objects.requireNonNull(service, "service");
            services.put(serviceType, service);
            return this;
        }

        /**
         * Resolves unregistered types through {@code delegate}. Registered types always
         * win, so a registration resolving to {@code null} reports absent rather than
         * delegating. {@link PluginServiceDirectory#installed()} on the built directory
         * reports the union.
         *
         * @param delegate the directory queried for unregistered types, never {@code null}
         * @return this builder
         */
        public Builder fallback(final PluginServiceDirectory delegate) {
            this.fallback = Objects.requireNonNull(delegate, "delegate");
            return this;
        }

        /**
         * Returns a directory resolving the registrations captured at this call.
         *
         * @return the assembled directory, never {@code null}
         */
        public PluginServiceDirectory build() {
            final Map<Class<?>, Supplier<?>> snapshot = new LinkedHashMap<>(services);
            final PluginServiceDirectory delegate = fallback;
            return new PluginServiceDirectory() {

                @Override
                public Set<PluginService> installed() {
                    final EnumSet<PluginService> installed = EnumSet.noneOf(PluginService.class);
                    if (delegate != null) {
                        installed.addAll(delegate.installed());
                    }
                    snapshot.forEach((type, source) -> {
                        if (source.get() != null) {
                            PluginService.forType(type).ifPresent(installed::add);
                        }
                    });
                    return Collections.unmodifiableSet(installed);
                }

                @Override
                public <T> Optional<T> find(final Class<T> serviceType) {
                    Objects.requireNonNull(serviceType, "serviceType");
                    final Supplier<?> source = snapshot.get(serviceType);
                    if (source != null) {
                        return Optional.ofNullable(serviceType.cast(source.get()));
                    }
                    return delegate != null ? delegate.find(serviceType) : Optional.empty();
                }
            };
        }
    }

    /**
     * Resolves the members backed by {@link PluginContext}'s remaining optional accessors.
     * Directory-only members have no accessor to bridge and resolve to {@code null}.
     */
    private static Object resolveAccessor(final PluginContext context, final PluginService member) {
        return switch (member) {
            case LOCALIZATION ->
                available(context.localization(), dev.turboism.sdk.i18n.PluginLocalization.unavailable());
            case TASKS -> available(context.tasks(), dev.turboism.sdk.task.PluginTaskScheduler.unavailable());
            case HOST_READS ->
                available(context.hostReads(), dev.turboism.sdk.hostread.AsyncHostReadService.unavailable());
            case STORAGE -> available(context.storage(), dev.turboism.sdk.storage.PluginStorage.unavailable());
            case SCRIPTS -> available(context.scripts(), dev.turboism.sdk.script.ScriptService.unavailable());
            case USER_FILES -> available(context.userFiles(), dev.turboism.sdk.ui.UserFileAccessService.unavailable());
            case SELECTION_QUERY ->
                available(
                        context.selectionQuery(),
                        dev.turboism.sdk.cubism.service.query.SelectionQueryService.unavailable());
            case MODEL_HIERARCHY_QUERY ->
                available(
                        context.modelHierarchyQuery(),
                        dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService.unavailable());
            case CUBISM_READ ->
                available(
                        context.cubismRead(),
                        dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService.unavailable());
            case MODEL_OBJECTS ->
                available(context.modelObjects(), dev.turboism.sdk.cubism.model.ModelObjectService.unavailable());
            case CONFIG -> available(context.config(), dev.turboism.sdk.config.PluginConfigRegistry.unavailable());
            default -> null;
        };
    }

    private static <T> T available(final T service, final T unavailableSentinel) {
        if (service == null || service == unavailableSentinel || service.getClass() == unavailableSentinel.getClass()) {
            return null;
        }
        return service;
    }
}
