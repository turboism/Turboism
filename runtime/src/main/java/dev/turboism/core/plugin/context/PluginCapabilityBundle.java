package dev.turboism.core.plugin.context;

/**
 * Common contract for immutable capability bundles that register plugin-scoped services
 * into the {@link RuntimePluginServiceDirectory}.
 *
 * <h3>How to add a capability</h3>
 * <ol>
 *   <li>Add the capability service interface and its {@code unavailable()} sentinel to the SDK
 *       (or locate the existing one).</li>
 *   <li>Add a member to {@link dev.turboism.sdk.plugin.PluginService} identifying the slot and type.</li>
 *   <li>Add the service to the appropriate capability bundle (e.g. {@link CubismContextServices},
 *       {@link MeshCapabilityBundle}, {@link UiCapabilityBundle}, {@link HostCapabilityBundle},
 *       or create a new bundle implementing {@link PluginCapabilityBundle}).</li>
 *   <li>In the bundle's factory/constructor and {@link #registerServices(RuntimePluginServiceDirectory)},
 *       construct the service with proper permission checks and disposable registrations, and register
 *       it into the directory via {@code directory.install(...)} or {@code directory.installWhenPresent(...)}.</li>
 * </ol>
 * Neither {@link CorePluginContext}'s fields nor its constructors need to change.
 */
public interface PluginCapabilityBundle {

    /**
     * Registers all services provided by this bundle into the given runtime service directory.
     *
     * @param directory the runtime service directory to register into
     */
    void registerServices(RuntimePluginServiceDirectory directory);
}
