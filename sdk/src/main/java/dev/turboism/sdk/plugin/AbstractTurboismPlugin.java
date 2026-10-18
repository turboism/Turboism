package dev.turboism.sdk.plugin;

import java.util.Objects;

/**
 * Opt-in abstract base class for {@link TurboismPlugin} implementations.
 *
 * <p>Provides standardized lifecycle state management:
 * <ul>
 *   <li>Permission-scoped runtime context storage via {@link #context()}, rejecting access
 *       with {@link IllegalStateException} before {@link #init(PluginContext)} or after
 *       {@link #shutdown()}.</li>
 *   <li>An owned {@link #disposalScope()} that tracks registrations (such as actions, menus,
 *       toolbars, and listeners) and automatically closes them in reverse registration order
 *       upon {@link #disable()} and {@link #shutdown()}. Exceptions thrown during resource
 *       closure are captured and suppressed so that a failure in one closer does not prevent
 *       closing remaining resources, matching SDK and runtime conventions.</li>
 * </ul>
 *
 * <h2>Threading contract</h2>
 * <p>The runtime invokes plugin lifecycle methods ({@code init}, {@code enable}, {@code disable},
 * {@code shutdown}) on dedicated worker threads from the runtime's lifecycle lane (such as
 * {@code PreviewPluginLoader} and {@code PluginLifecycleLane}). Different lifecycle transitions
 * may execute on different worker threads over time. Furthermore, plugin callbacks, event handlers,
 * and UI actions may access {@link #context()} or {@link #disposalScope()} concurrently across
 * threads (such as the Swing Event Dispatch Thread or plugin task workers). This class guards its
 * internal state with a dedicated lock while executing user cleanup outside the monitor to
 * prevent deadlocks.</p>
 */
public abstract class AbstractTurboismPlugin implements TurboismPlugin {

    private final Object lock = new Object();
    private volatile PluginContext context;
    private volatile boolean shutDown;
    private DisposableScope disposalScope;

    /**
     * Constructs a new abstract plugin instance.
     */
    protected AbstractTurboismPlugin() {}

    /**
     * Initializes this plugin with the provided context.
     *
     * @param context the plugin's runtime view, never {@code null}
     * @throws NullPointerException when {@code context} is null
     * @throws IllegalStateException when this plugin is already initialized or has been shut down
     * @throws Exception when initialization fails
     */
    @Override
    public void init(final PluginContext context) throws Exception {
        Objects.requireNonNull(context, "context");
        synchronized (lock) {
            if (shutDown) {
                throw new IllegalStateException("Plugin is already shut down");
            }
            if (this.context != null) {
                throw new IllegalStateException("Plugin is already initialized");
            }
            this.context = context;
            this.disposalScope = new DisposableScope();
        }
    }

    /**
     * Called when the plugin transitions to the enabled state.
     *
     * <p>The default implementation validates that the plugin is initialized and not shut down.
     * Subclasses overriding this method should typically register resources with
     * {@link #disposalScope()}.</p>
     *
     * @throws IllegalStateException when called before {@link #init(PluginContext)}
     *     or after {@link #shutdown()}
     * @throws Exception when enabling fails
     */
    @Override
    public void enable() throws Exception {
        context();
    }

    /**
     * Called when the plugin transitions to the disabled state.
     *
     * <p>Closes all resources registered in {@link #disposalScope()} in reverse order.
     * Exceptions from one closer do not prevent closing remaining resources. After closure,
     * a fresh disposal scope is prepared so that subsequent {@link #enable()} calls can
     * register new resources.</p>
     *
     * @throws Exception when closing registered resources fails
     */
    @Override
    public void disable() throws Exception {
        final DisposableScope scopeToClose;
        synchronized (lock) {
            scopeToClose = disposalScope;
            if (!shutDown) {
                disposalScope = new DisposableScope();
            }
        }
        if (scopeToClose != null) {
            scopeToClose.close();
        }
    }

    /**
     * Called when the plugin is being unloaded.
     *
     * <p>Closes all remaining resources in {@link #disposalScope()} in reverse order,
     * sets the stored context to {@code null}, and marks this plugin as shut down.</p>
     *
     * @throws Exception when closing registered resources fails
     */
    @Override
    public void shutdown() throws Exception {
        final DisposableScope scopeToClose;
        synchronized (lock) {
            scopeToClose = disposalScope;
            disposalScope = null;
            shutDown = true;
            context = null;
        }
        if (scopeToClose != null) {
            scopeToClose.close();
        }
    }

    /**
     * Returns the active plugin context.
     *
     * @return the active plugin context, never {@code null}
     * @throws IllegalStateException when called before {@link #init(PluginContext)}
     *     or after {@link #shutdown()}
     */
    public final PluginContext context() {
        if (shutDown) {
            throw new IllegalStateException("Plugin is already shut down");
        }
        final PluginContext activeContext = context;
        if (activeContext == null) {
            throw new IllegalStateException("Plugin is not initialized");
        }
        return activeContext;
    }

    /**
     * Returns the owned disposal scope for this plugin.
     *
     * <p>Resources registered into this scope are closed in reverse order
     * when the plugin transitions to disabled or shut down.</p>
     *
     * @return the owned disposal scope, never {@code null}
     * @throws IllegalStateException when called before {@link #init(PluginContext)}
     *     or after {@link #shutdown()}
     */
    public final DisposableScope disposalScope() {
        synchronized (lock) {
            if (shutDown) {
                throw new IllegalStateException("Plugin is already shut down");
            }
            if (context == null) {
                throw new IllegalStateException("Plugin is not initialized");
            }
            if (disposalScope == null) {
                disposalScope = new DisposableScope();
            }
            return disposalScope;
        }
    }

    /**
     * Alias for {@link #disposalScope()}.
     *
     * @return the owned disposal scope, never {@code null}
     * @throws IllegalStateException when called before {@link #init(PluginContext)}
     *     or after {@link #shutdown()}
     */
    public final DisposableScope disposableScope() {
        return disposalScope();
    }
}
