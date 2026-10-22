/**
 * Public contracts for Turboism plugins.
 *
 * <p>Compile against the SDK without bundling it. The runtime supplies permission-scoped
 * services through {@link dev.turboism.sdk.plugin.PluginContext}; installed services,
 * declared permissions and Cubism Editor version availability are separate checks.
 * Lifecycle methods run on worker threads. Use {@link dev.turboism.sdk.ui.UiScheduler}
 * for UI work and {@link dev.turboism.sdk.task.PluginTaskScheduler} for background work.
 * Close registrations when disabling a plugin.</p>
 *
 * <p>The source distribution's {@code sdk/plugin-author-guide.md} links the standalone
 * template, declarative settings, storage/configuration, events and testing guidance.
 * {@link dev.turboism.sdk.CubismEditor} declares host-version availability;
 * {@link dev.turboism.sdk.Incubating} identifies APIs excluded from exact compatibility
 * baselines. The first stable release establishes the normal additive SDK policy.</p>
 *
 * @see dev.turboism.sdk.plugin.TurboismPlugin
 * @see dev.turboism.sdk.plugin.PluginServiceDirectory
 * @see dev.turboism.sdk.ui.settings.SettingsControl
 */
package dev.turboism.sdk;
