package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthorizedMeshMirrorMoveParticipationTest {

    @Test
    void registrationIsBoundToThePluginScope() throws Exception {
        final RuntimeMeshMirrorMoveParticipation delegate = new RuntimeMeshMirrorMoveParticipation();
        final DisposableScope scope = new DisposableScope();
        final AuthorizedMeshMirrorMoveParticipation service =
            new AuthorizedMeshMirrorMoveParticipation(delegate, PermissionChecker.allowAll(), scope);

        service.participate();
        assertTrue(delegate.hasParticipants());

        scope.close();
        assertFalse(delegate.hasParticipants());
    }

    @Test
    void closedScopeDoesNotLeakMovementPolicy() throws Exception {
        final RuntimeMeshMirrorMoveParticipation delegate = new RuntimeMeshMirrorMoveParticipation();
        final DisposableScope scope = new DisposableScope();
        scope.close();
        final AuthorizedMeshMirrorMoveParticipation service =
            new AuthorizedMeshMirrorMoveParticipation(delegate, PermissionChecker.allowAll(), scope);

        assertThrows(IllegalStateException.class, service::participate);
        assertFalse(delegate.hasParticipants());
    }
}
