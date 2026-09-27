package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.CancelSource;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionService;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.plugin.PluginContext;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Plugin-scoped {@link EditSessionService} over one {@link RuntimeEditSessionManager}.
 *
 * <p>The service is bound to one owning plugin at wiring time; the {@code PluginContext}
 * argument is validated but identity comes from the wiring, never from the argument, so a
 * leaked or foreign context cannot borrow another plugin's ownership.</p>
 */
public final class RuntimeEditSessionService implements EditSessionService {

    private static final String CODE_BINDING = "cubism.edit.binding-unavailable";

    private final RuntimeEditSessionManager manager;
    private final EditorEditSessionHost host;
    private final String pluginId;
    private final Supplier<Optional<DocumentId>> activeDocumentId;

    public RuntimeEditSessionService(
        final RuntimeEditSessionManager manager,
        final EditorEditSessionHost host,
        final String pluginId,
        final Supplier<Optional<DocumentId>> activeDocumentId
    ) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.host = Objects.requireNonNull(host, "host");
        this.pluginId = requireText(pluginId, "pluginId");
        this.activeDocumentId = Objects.requireNonNull(activeDocumentId, "activeDocumentId");
    }

    @Override
    public boolean isEditApproved(final PluginContext context) throws EditSessionException {
        Objects.requireNonNull(context, "context");
        final EditorAuthoringTransactionCoordinator.Binding binding = currentBinding();
        return host.admits(binding);
    }

    @Override
    public EditSession open(
        final PluginContext context,
        final DocumentId document,
        final EditSessionOptions options
    ) throws EditSessionException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(options, "options");
        final EditorAuthoringTransactionCoordinator.Binding binding = currentBinding();
        final Optional<DocumentId> active;
        try {
            active = Objects.requireNonNull(activeDocumentId.get(), "activeDocumentId result");
        } catch (RuntimeException failure) {
            throw new EditUnavailableException(
                CODE_BINDING,
                "The active Cubism document cannot be determined"
            );
        }
        if (active.isEmpty() || !active.orElseThrow().equals(document)) {
            throw new EditUnavailableException(
                CODE_BINDING,
                "The requested document is not the active modeling document"
            );
        }
        return manager.open(binding, document, options);
    }

    /**
     * Forcibly cancels this plugin's session, if one is active — the plugin-disable and
     * project-close cleanup hook registered on the plugin's disposable scope.
     */
    public void shutdown() {
        try {
            manager.forceCancelFor(pluginId, CancelSource.HOST);
        } catch (EditSessionException | RuntimeException ignored) {
            // cleanup must never throw into the disposing scope
        }
    }

    private EditorAuthoringTransactionCoordinator.Binding currentBinding()
        throws EditSessionException {
        try {
            final Optional<EditorAuthoringTransactionCoordinator.Binding> binding =
                host.currentBinding(pluginId);
            if (binding.isPresent()) {
                return binding.orElseThrow();
            }
        } catch (RuntimeException failure) {
            // falls through to the typed unavailable failure below
        }
        throw new EditUnavailableException(
            CODE_BINDING,
            "No stable active modeling document is available"
        );
    }

    private static String requireText(final String value, final String name) {
        final String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
