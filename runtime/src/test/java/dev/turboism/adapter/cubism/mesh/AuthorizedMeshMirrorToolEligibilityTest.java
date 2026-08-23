package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshEditTool;
import dev.turboism.sdk.plugin.DisposableScope;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AuthorizedMeshMirrorToolEligibilityTest {

    @Test
    void closedScopeDoesNotLeakEligibilityIntoRuntimeRegistry() throws Exception {
        final RuntimeMeshMirrorToolEligibility delegate = new RuntimeMeshMirrorToolEligibility();
        final DisposableScope scope = new DisposableScope();
        scope.close();
        final AuthorizedMeshMirrorToolEligibility service = new AuthorizedMeshMirrorToolEligibility(
            delegate, PermissionChecker.allowAll(), scope
        );

        assertThrows(
            IllegalStateException.class,
            () -> service.extendEligibleTools(Set.of(MeshEditTool.ARROW))
        );
        assertFalse(delegate.isExtended(MeshEditTool.ARROW));
    }
}
