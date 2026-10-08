package dev.turboism.core.plugin.context;

import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The runtime-owned {@link PluginServiceDirectory}: a mutable registry keyed by
 * {@link PluginService} whose entries resolve lazily through the context's service fields.
 * Installing a new optional service registers one entry here; no {@link PluginContext}
 * accessor is ever required.
 *
 * <p>An entry is installed when its probe passes: the default probe checks the resolved
 * value against the service's {@code unavailable()} sentinel (by implementation class,
 * looking through version-gating proxies), and {@link #installAlways} marks slots that are
 * unconditional once registered — used where probing itself would be expensive, such as
 * the lazily constructed performance probe.</p>
 */
final class RuntimePluginServiceDirectory implements PluginServiceDirectory {

    private final Map<PluginService, Entry> entries = new EnumMap<>(PluginService.class);

    private record Entry(Supplier<Object> source, BooleanSupplier installed) {
        Object resolve() {
            return installed.getAsBoolean() ? source.get() : null;
        }
    }

    /**
     * Registers a service resolved through {@code source}; the slot is installed when the
     * resolved value is neither {@code null} nor the class of {@code unavailableSentinel}.
     */
    void install(
            final PluginService member, final Supplier<Object> source, final Supplier<Object> unavailableSentinel) {
        entries.put(
                Objects.requireNonNull(member, "member"),
                new Entry(
                        Objects.requireNonNull(source, "source"),
                        () -> installed(source.get(), unavailableSentinel.get())));
    }

    /** Registers a service that reports installed whenever {@code source} is non-null. */
    void installWhenPresent(final PluginService member, final Supplier<Object> source) {
        entries.put(
                Objects.requireNonNull(member, "member"),
                new Entry(Objects.requireNonNull(source, "source"), () -> source.get() != null));
    }

    /** Registers a service whose slot is installed unconditionally once registered. */
    void installAlways(final PluginService member, final Supplier<Object> source) {
        entries.put(
                Objects.requireNonNull(member, "member"),
                new Entry(Objects.requireNonNull(source, "source"), () -> true));
    }

    /** Registers a service under an explicit install probe. */
    void installIf(final PluginService member, final Supplier<Object> source, final BooleanSupplier installed) {
        entries.put(
                Objects.requireNonNull(member, "member"),
                new Entry(Objects.requireNonNull(source, "source"), Objects.requireNonNull(installed, "installed")));
    }

    /** Drops a registration; the member reports absent afterwards. */
    void remove(final PluginService member) {
        entries.remove(member);
    }

    @Override
    public Set<PluginService> installed() {
        final EnumSet<PluginService> installed = EnumSet.noneOf(PluginService.class);
        entries.forEach((member, entry) -> {
            if (entry.installed().getAsBoolean()) {
                installed.add(member);
            }
        });
        return Collections.unmodifiableSet(installed);
    }

    @Override
    public <T> java.util.Optional<T> find(final Class<T> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType");
        final PluginService member = PluginService.forType(serviceType).orElse(null);
        if (member == null) {
            return java.util.Optional.empty();
        }
        final Entry entry = entries.get(member);
        if (entry == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(serviceType.cast(entry.resolve()));
    }

    /**
     * Whether a service slot resolves to a usable instance: non-null and not an instance of
     * the {@code unavailable()} sentinel's implementation class, looking through the
     * version-gating proxy. Sentinel implementations are compared by class because several
     * {@code unavailable()} factories return a fresh anonymous instance per call rather
     * than a singleton.
     */
    static boolean installed(final Object service, final Object unavailableSentinel) {
        final Object resolved = CubismEditorApiAvailabilityInterceptor.unwrap(service);
        return resolved != null
                && (unavailableSentinel == null || resolved.getClass() != unavailableSentinel.getClass());
    }
}
