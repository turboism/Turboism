package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AuthorizedMeshEditParticipationTest {

    @Test
    void closedScopeDoesNotLeakParticipantIntoRuntimeRegistry() throws Exception {
        final RuntimeMeshEditParticipation delegate = new RuntimeMeshEditParticipation();
        final DisposableScope scope = new DisposableScope();
        scope.close();
        final AuthorizedMeshEditParticipation service = new AuthorizedMeshEditParticipation(
            delegate, PermissionChecker.allowAll(), scope
        );

        assertThrows(IllegalStateException.class, () -> service.participate(deletion -> null));

        assertFalse(delegate.hasParticipants());
    }
}
