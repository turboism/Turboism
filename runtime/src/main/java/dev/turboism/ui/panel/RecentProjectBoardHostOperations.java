package dev.turboism.ui.panel;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.ui.action.EditorUiActionRouter;
import dev.turboism.ui.host.EdtDispatch;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JComponent;

/**
 * Installs a plugin's recent-project board over the native modeling viewport.
 *
 * <p>This host deliberately bypasses the dock-palette provider: a viewport board is not a
 * palette, must not enter the workspace dock tree, and must remain usable during the early
 * startup window before the dock provider has finished binding. The verified recent-preview
 * adapter slice already carries the main-frame resolver used here.</p>
 */
public final class RecentProjectBoardHostOperations {

    private static final String VIEWPORT_PLACEMENT = "viewport";
    private static final String APP_INSTANCE = "cubism.ui-panel.app-controller.instance";

    private final VerifiedMemberResolver resolver;
    private final EditorUiActionRouter actionRouter;
    private final Supplier<java.util.Locale> locale;
    private final ReplacingPanelRegistry boards = new ReplacingPanelRegistry();

    public RecentProjectBoardHostOperations(
            final VerifiedMemberResolver resolver,
            final EditorUiActionRouter actionRouter,
            final Supplier<java.util.Locale> locale) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    /**
     * Dispatches the host's native New Animation action. The native entry point opens
     * Cubism's own New Animation flow (usually a dialog); the caller only owns the dispatch.
     *
     * @return {@code true} when the native action was invoked
     */
    public boolean createNewAnimation() {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        if (app == null) {
            return false;
        }
        final Object pack;
        try {
            pack = MethodHandleCache.method(app.getClass(), "getCompletePack").invoke(app);
        } catch (ReflectiveOperationException failure) {
            return false;
        }
        if (pack == null) {
            return false;
        }
        try {
            final java.lang.reflect.Method action =
                    pack.getClass().getMethod("actionPerformed", String.class, Map.class);
            final Runnable dispatch = () -> {
                try {
                    action.invoke(pack, "CMD_NEW_ANIMATION", Map.of());
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cubism New Animation action failed", failure);
                }
            };
            EdtDispatch.call("recent project board new animation", () -> {
                dispatch.run();
                return null;
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Installs one board.
     *
     * @param pluginId the contributing plugin, used to route card actions
     * @param contribution the board content
     * @return a registration that removes the board when closed
     */
    public Registration install(final String pluginId, final EmbeddedPanelContribution contribution) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(contribution, "contribution");
        final EmbeddedPanelContributionDescriptor descriptor = new EmbeddedPanelContributionDescriptor(
                pluginId,
                contribution.id(),
                contribution.title(),
                VIEWPORT_PLACEMENT,
                contribution.priority(),
                contribution.content(),
                false);
        return boards.replace(descriptor, () -> {
            final AtomicReference<JComponent> content = new AtomicReference<>();
            final Function<EmbeddedPanelContributionDescriptor, JComponent> contentBuilder = updated -> {
                final JComponent previous = content.get();
                final JComponent next = previous == null
                        ? SwingPanelViewRenderer.render(
                                updated.content(),
                                (actionId, event) -> actionRouter.invoke(pluginId, actionId, event),
                                locale.get())
                        : SwingPanelViewRenderer.update(
                                previous,
                                updated.content(),
                                (actionId, event) -> actionRouter.invoke(pluginId, actionId, event),
                                locale.get());
                content.set(next);
                return next;
            };
            return ViewportPanelInstaller.install(resolver, descriptor, () -> true, contentBuilder);
        });
    }
}
