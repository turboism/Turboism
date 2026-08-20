package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterpartResolver;
import dev.turboism.sdk.plugin.DisposableScope;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AuthorizedMeshMirrorCounterpartsTest {

    @Test
    void closedScopeDoesNotRetainRejectedResolver() throws Exception {
        final RuntimeMeshMirrorCounterparts delegate = new RuntimeMeshMirrorCounterparts();
        final DisposableScope scope = new DisposableScope();
        scope.close();
        final AuthorizedMeshMirrorCounterparts service = new AuthorizedMeshMirrorCounterparts(
            delegate, PermissionChecker.allowAll(), scope
        );
        final MeshMirrorCounterpartResolver resolver = (source, mesh, axis) -> Optional.empty();

        final IllegalStateException first = assertThrows(
            IllegalStateException.class, () -> service.overrideResolver(resolver)
        );
        final IllegalStateException second = assertThrows(
            IllegalStateException.class, () -> service.overrideResolver(resolver)
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            "DisposableScope is already closed", first.getMessage()
        );
        org.junit.jupiter.api.Assertions.assertEquals(first.getMessage(), second.getMessage());
    }

    @Test
    void resolverOwnershipIsPerPluginFacade() throws Exception {
        final RuntimeMeshMirrorCounterparts delegate = new RuntimeMeshMirrorCounterparts();
        final DisposableScope firstScope = new DisposableScope();
        final DisposableScope secondScope = new DisposableScope();
        final AuthorizedMeshMirrorCounterparts first = new AuthorizedMeshMirrorCounterparts(
            delegate, PermissionChecker.allowAll(), firstScope
        );
        final AuthorizedMeshMirrorCounterparts second = new AuthorizedMeshMirrorCounterparts(
            delegate, PermissionChecker.allowAll(), secondScope
        );
        final MeshMirrorCounterpartResolver firstResolver = (source, mesh, axis) -> Optional.empty();
        final MeshMirrorCounterpartResolver secondResolver = (source, mesh, axis) -> Optional.empty();

        assertDoesNotThrow(() -> first.overrideResolver(firstResolver));
        assertDoesNotThrow(() -> second.overrideResolver(secondResolver));
        assertThrows(IllegalStateException.class, () -> first.overrideResolver(firstResolver));

        firstScope.close();
        assertDoesNotThrow(() -> first.mirrorOf(null));
        assertDoesNotThrow(() -> second.mirrorOf(null));
        secondScope.close();
    }
}
