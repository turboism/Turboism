package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ArtMeshMirrorServiceTest {
    @AfterEach
    void cleanup() {
        NativeWarpAltMirrorBridge.uninstall();
    }

    @Test
    void warpModeAndParticipantLifetimeDoNotChangeArtMeshMode() {
        var mesh = NativeArtMeshSymmetryBridge.service();
        var warp = NativeWarpAltMirrorBridge.moveParticipation();
        var meshRegistration = mesh.participate();
        var warpRegistration = warp.participate();
        warp.setArmedAxis(1);
        assertFalse(mesh.isEnabled());
        mesh.setEnabled(true);
        warp.setArmedAxis(2);
        warpRegistration.close();
        assertTrue(mesh.isEnabled());
        meshRegistration.close();
        meshRegistration.close();
        assertFalse(mesh.isEnabled());
        assertFalse(mesh.hasParticipants());
    }

    @Test
    void pluginScopeDisarmsArtMeshAndClosedScopeCannotLeakParticipation() throws Exception {
        var delegate = NativeArtMeshSymmetryBridge.service();
        var scope = new DisposableScope();
        var service = new AuthorizedArtMeshMirrorService(delegate, PermissionChecker.allowAll(), scope);
        service.participate();
        service.setEnabled(true);
        scope.close();
        assertFalse(delegate.hasParticipants());
        assertFalse(delegate.isEnabled());
        assertThrows(IllegalStateException.class, service::participate);
        assertFalse(delegate.hasParticipants());
    }

    @Test
    void mutationsRequireModelWritePermission() {
        var delegate = NativeArtMeshSymmetryBridge.service();
        var service = new AuthorizedArtMeshMirrorService(
                delegate, PermissionChecker.from(java.util.List.of()), new DisposableScope());
        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class, service::participate);
        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class, () -> service.setEnabled(true));
        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class, service::resetAxis);
        assertFalse(delegate.hasParticipants());
        assertFalse(delegate.isEnabled());
    }
}
