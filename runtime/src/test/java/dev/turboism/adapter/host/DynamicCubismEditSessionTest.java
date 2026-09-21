package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.edit.RuntimeEditSessionProvider;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.edit.EditSessionService;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the dynamic wrapper's editing-session delegation: the services factory
 * receives this wrapper, not the editor-backed access directly, so {@link RuntimeEditSessionProvider}
 * must be implemented here or {@code CubismFacade.edit()} stays unavailable on every host.
 */
final class DynamicCubismEditSessionTest {

    private static final PluginContext CONTEXT = new PluginContext() {
        @Override public dev.turboism.sdk.plugin.PluginDescriptor descriptor() {
            return null;
        }
        @Override public dev.turboism.sdk.plugin.PluginLogger logger() {
            return null;
        }
        @Override public dev.turboism.sdk.plugin.PluginPaths paths() {
            return null;
        }
        @Override public dev.turboism.sdk.cubism.CubismFacade cubism() {
            return null;
        }
        @Override public dev.turboism.sdk.plugin.DisposableScope disposableScope() {
            return new dev.turboism.sdk.plugin.DisposableScope();
        }
        @Override public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
            return null;
        }
        @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
            return null;
        }
        @Override public java.util.List<dev.turboism.sdk.permission.PluginPermission> permissions() {
            return java.util.List.of();
        }
        @Override public dev.turboism.sdk.event.EventBus eventBus() {
            return null;
        }
        @Override public dev.turboism.sdk.action.ActionRegistry actions() {
            return null;
        }
        @Override public dev.turboism.sdk.menu.MenuRegistry menus() {
            return null;
        }
    };

    private static final Supplier<Optional<DocumentId>> DOCUMENTS =
        () -> Optional.of(new DocumentId("document-session-2"));

    @Test
    void forwardsThePluginIdentityAndDocumentSupplierToTheCurrentLease()
        throws dev.turboism.sdk.cubism.edit.EditSessionException {
        final DynamicCubismModelAccess dynamic = new DynamicCubismModelAccess();
        final RecordingEditProvider provider = new RecordingEditProvider(true);
        dynamic.connect(provider);

        final RuntimeEditSessionProvider surface = (RuntimeEditSessionProvider) dynamic;
        assertTrue(surface.editSessions("plugin.alpha", DOCUMENTS).isEditApproved(CONTEXT));

        final EditSession session = surface.editSessions("plugin.alpha", DOCUMENTS)
            .open(CONTEXT, new DocumentId("document-session-2"), EditSessionOptions.defaults());
        assertEquals("plugin.alpha", provider.pluginId.get());
        assertEquals("document-session-2", provider.documentId.get());
        assertSame(EditSession.unavailable(), session);
    }

    @Test
    void failsClosedAfterDisconnectWithoutTouchingTheProvider() {
        final DynamicCubismModelAccess dynamic = new DynamicCubismModelAccess();
        final RecordingEditProvider provider = new RecordingEditProvider(true);
        dynamic.connect(provider);
        final EditSessionService captured =
            ((RuntimeEditSessionProvider) dynamic).editSessions("plugin.alpha", DOCUMENTS);
        dynamic.deactivate();

        final EditUnavailableException approved = assertThrows(
            EditUnavailableException.class,
            () -> {
                try {
                    captured.isEditApproved(CONTEXT);
                } catch (dev.turboism.sdk.cubism.edit.EditSessionException failure) {
                    if (failure instanceof EditUnavailableException unavailable) {
                        throw unavailable;
                    }
                    throw new AssertionError(failure);
                }
            }
        );
        assertEquals("cubism.edit.unavailable", approved.code());
        final EditUnavailableException opened = assertThrows(
            EditUnavailableException.class,
            () -> {
                try {
                    captured.open(CONTEXT, new DocumentId("document-session-2"), EditSessionOptions.defaults());
                    throw new AssertionError("open must fail closed after disconnect");
                } catch (dev.turboism.sdk.cubism.edit.EditSessionException failure) {
                    if (failure instanceof EditUnavailableException unavailable) {
                        throw unavailable;
                    }
                    throw new AssertionError(failure);
                }
            }
        );
        assertEquals("cubism.edit.unavailable", opened.code());
        assertFalse(provider.openCalled.get());
    }

    @Test
    void hostWithoutTheEditProviderStaysTypedUnavailable() {
        final DynamicCubismModelAccess dynamic = new DynamicCubismModelAccess();
        dynamic.connect(new PlainModelAccess());

        final EditUnavailableException approved = assertThrows(
            EditUnavailableException.class,
            () -> ((RuntimeEditSessionProvider) dynamic)
                .editSessions("plugin.alpha", DOCUMENTS)
                .isEditApproved(CONTEXT)
        );
        assertEquals("Editor edit sessions are unavailable on this host", approved.getMessage());
    }

    private static final class RecordingEditProvider
        implements CubismModelAccess, RuntimeEditSessionProvider {

        private final boolean approved;
        final AtomicBoolean openCalled = new AtomicBoolean();
        final AtomicReference<String> pluginId = new AtomicReference<>();
        final AtomicReference<String> documentId = new AtomicReference<>();

        private RecordingEditProvider(final boolean approved) {
            this.approved = approved;
        }

        @Override
        public CubismModel active() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public EditSessionService editSessions(
            final String requestedPluginId,
            final Supplier<Optional<DocumentId>> activeDocumentId
        ) {
            final String pluginIdValue = requestedPluginId;
            return new EditSessionService() {
                @Override
                public boolean isEditApproved(final dev.turboism.sdk.plugin.PluginContext context) {
                    RecordingEditProvider.this.pluginId.set(pluginIdValue);
                    RecordingEditProvider.this.documentId.set(
                        activeDocumentId.get().map(DocumentId::value).orElse(null)
                    );
                    return approved;
                }

                @Override
                public EditSession open(
                    final dev.turboism.sdk.plugin.PluginContext context,
                    final DocumentId document,
                    final EditSessionOptions options
                ) {
                    openCalled.set(true);
                    RecordingEditProvider.this.pluginId.set(requestedPluginId);
                    RecordingEditProvider.this.documentId.set(document.value());
                    return EditSession.unavailable();
                }
            };
        }
    }


    private static final class PlainModelAccess implements CubismModelAccess {
        @Override
        public CubismModel active() {
            throw new UnsupportedOperationException("not used");
        }
    }
}
