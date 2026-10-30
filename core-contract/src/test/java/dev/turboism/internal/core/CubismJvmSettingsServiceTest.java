package dev.turboism.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CubismJvmSettingsService.CubismJvm;
import dev.turboism.internal.core.CubismJvmSettingsService.ManagedRuntimeState;
import dev.turboism.internal.core.CubismJvmSettingsService.ManagedRuntimeStatus;
import dev.turboism.internal.core.CubismJvmSettingsService.MemoryProfile;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CubismJvmSettingsServiceTest {

    private final CubismJvmSettingsService service = CubismJvmSettingsService.unavailable();

    @Test
    void unavailableReadsSafeDefaults() {
        assertEquals(CubismJvm.GRAALVM, service.read());
        assertEquals("", service.graalVmPath());
        assertEquals(Optional.empty(), service.graalVmJava());
        assertFalse(service.graalVmAvailable());
        assertEquals(MemoryProfile.SYSTEM, service.memoryProfile());
        assertTrue(service.zgc());
        assertFalse(service.modelUpdateSkip());
        assertFalse(service.uniformLocationCache());
        assertFalse(service.reduceAutoBackup());
        assertFalse(service.uploadElision());
        assertFalse(service.inputPathElision());
        assertFalse(service.mesaGlThread());
        assertFalse(service.incrementalUpdate());
        assertFalse(service.shaderUniform());
        assertFalse(service.matrixScratch());
        assertFalse(service.imageArchiveReuse());
        assertFalse(service.floatArrayParseCache());
        assertFalse(service.textureUploadPreparation());
        assertFalse(service.warpPositionProjection());
    }

    @Test
    void unavailableRefusesEveryWrite() {
        assertThrows(IllegalStateException.class, () -> service.save(CubismJvm.BUNDLED));
        assertThrows(IllegalStateException.class, () -> service.saveGraalVmPath("x"));
        assertThrows(IllegalStateException.class, () -> service.saveReduceAutoBackup(true));
        assertThrows(IllegalStateException.class, () -> service.saveZgc(true));
        assertThrows(IllegalStateException.class, () -> service.saveMemoryProfile(MemoryProfile.SYSTEM));
        assertThrows(IllegalStateException.class, () -> service.saveUploadElision(true));
        assertThrows(IllegalStateException.class, () -> service.saveInputPathElision(true));
        assertThrows(IllegalStateException.class, () -> service.saveMesaGlThread(true));
        assertThrows(IllegalStateException.class, () -> service.saveUniformLocationCache(true));
        assertThrows(IllegalStateException.class, () -> service.saveModelUpdateSkip(true));
        assertThrows(IllegalStateException.class, () -> service.saveIncrementalUpdate(true));
        assertThrows(IllegalStateException.class, () -> service.saveShaderUniform(true));
        assertThrows(IllegalStateException.class, () -> service.saveMatrixScratch(true));
        assertThrows(IllegalStateException.class, () -> service.saveImageArchiveReuse(true));
        assertThrows(IllegalStateException.class, () -> service.saveFloatArrayParseCache(true));
        assertThrows(IllegalStateException.class, () -> service.saveTextureUploadPreparation(true));
        assertThrows(IllegalStateException.class, () -> service.saveWarpPositionProjection(true));
        assertThrows(IllegalStateException.class, service::installManagedRuntime);
        assertThrows(IllegalStateException.class, service::removeManagedRuntime);
    }

    @Test
    void customGraalPathCompatibilityDefaultAcceptsOnlyBlank() {
        assertTrue(service.graalVmPathCompatible(null));
        assertTrue(service.graalVmPathCompatible("  "));
        assertFalse(service.graalVmPathCompatible("/opt/graal"));
    }

    @Test
    void managedRuntimeDefaultsReportUnsupported() {
        final ManagedRuntimeStatus status = service.managedRuntimeStatus();
        assertEquals(ManagedRuntimeState.UNSUPPORTED, status.state());
        assertEquals("GRAAL_RUNTIME_UNAVAILABLE", status.code());
        assertEquals(status.state(), service.verifyManagedRuntime().state());
    }

    @Test
    void managedRuntimeStatusNormalizesAndValidates() {
        final ManagedRuntimeStatus status =
                new ManagedRuntimeStatus(ManagedRuntimeState.READY, null, null, Optional.empty(), 1, 2, null, null);
        assertEquals("", status.version());
        assertEquals("", status.javaVersion());
        assertEquals("", status.code());
        assertEquals("", status.message());
        assertThrows(
                NullPointerException.class,
                () -> new ManagedRuntimeStatus(null, "", "", Optional.empty(), 0, 0, "", ""));
        assertThrows(
                NullPointerException.class,
                () -> new ManagedRuntimeStatus(ManagedRuntimeState.READY, "", "", null, 0, 0, "", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagedRuntimeStatus(
                        ManagedRuntimeState.INSTALLING, "", "", Optional.of(Path.of("j")), -1, 0, "", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagedRuntimeStatus(ManagedRuntimeState.INSTALLING, "", "", Optional.empty(), 5, 4, "", ""));
    }

    @Test
    void cubismJvmParsesConfigCaseAndWhitespaceInsensitively() {
        assertEquals(CubismJvm.GRAALVM, CubismJvm.fromConfig(" GraalVM "));
        assertEquals(CubismJvm.BUNDLED, CubismJvm.fromConfig("bundled"));
        assertThrows(IllegalArgumentException.class, () -> CubismJvm.fromConfig("temurin"));
        assertThrows(IllegalArgumentException.class, () -> CubismJvm.fromConfig(null));
    }

    @Test
    void memoryProfileParsingIsStrictWithLenientFallback() {
        for (MemoryProfile profile : MemoryProfile.values()) {
            assertEquals(profile, MemoryProfile.fromConfig(profile.configValue()));
        }
        assertEquals(MemoryProfile.BALANCED_4G, MemoryProfile.fromConfig(" balanced4g "));
        assertThrows(IllegalArgumentException.class, () -> MemoryProfile.fromConfig("BALANCED4G"));
        assertEquals(MemoryProfile.SYSTEM, MemoryProfile.fromConfigOrSystem("nonsense"));
        assertEquals(MemoryProfile.SYSTEM, MemoryProfile.fromConfigOrSystem(null));
        assertEquals(MemoryProfile.BALANCED_4G_FAST_SOFT, MemoryProfile.fromConfigOrSystem("balanced4gFastSoft"));
    }
}
