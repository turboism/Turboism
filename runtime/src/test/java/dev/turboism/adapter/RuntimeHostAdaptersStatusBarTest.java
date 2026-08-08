package dev.turboism.adapter;

import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.VerifiedCxStatusBarHostAccessTest;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.StatusBarVerificationManifest;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeHostAdaptersStatusBarTest {

    @Test
    void withVerifiedStatusBarReplacesOnlyTheStatusSlotOfTheGivenBundle() {
        RuntimeHostAdapters base = RuntimeHostAdapters.safeMode();
        RuntimeHostAdapters adapters = RuntimeHostAdapters.withVerifiedStatusBar(
            base, VerifiedCxStatusBarHostAccessTest.statusResolver()
        );

        assertSame(base.themeStatus(), adapters.themeStatus());
        assertSame(base.renderStatus(), adapters.renderStatus());
        assertSame(base.projectWorkspace(), adapters.projectWorkspace());
        assertSame(base.clipMaskRead(), adapters.clipMaskRead());
        assertSame(base.uiSurface(), adapters.uiSurface());

        StatusToolbarAdapter.AdapterResult<Registration> result =
            adapters.statusToolbar().notifyStatus(new StatusNotification("status", "INFO", "Ready"));
        assertFalse(result.isAvailable());
        assertEquals(
            SafeModeDiagnostic.Code.VALIDATION_FAILURE,
            result.diagnostic().orElseThrow().code()
        );
    }

    @Test
    void withVerifiedStatusBarRejectsForeignOrWrongVersionResolvers() {
        RuntimeHostAdapters base = RuntimeHostAdapters.safeMode();

        VerifiedMemberResolver projectResolver = TestVerifiedResolvers.create(
            dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            List.of(StaticSelector.classSelector(
                "cubism.app-controller.class", "com/live2d/cubism/CEAppCtrl"
            )),
            getClass().getClassLoader()
        );
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeHostAdapters.withVerifiedStatusBar(base, projectResolver),
            "a non-status resolver must not be accepted as the status trust root");

        VerifiedMemberResolver wrongVersion = TestVerifiedResolvers.create(
            "5.2",
            StatusBarVerificationManifest.ADAPTER_SLICE_ID,
            StatusBarVerificationManifest.CAPABILITY_IDS,
            statusSelectors(),
            getClass().getClassLoader()
        );
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeHostAdapters.withVerifiedStatusBar(base, wrongVersion),
            "5.2 must keep failing closed");
    }

    @Test
    void withVerifiedStatusBarRejectsIncompleteAliasCoverage() {
        VerifiedMemberResolver partial = TestVerifiedResolvers.create(
            StatusBarVerificationManifest.ADAPTER_SLICE_ID,
            StatusBarVerificationManifest.CAPABILITY_IDS,
            statusSelectors().subList(0, 3),
            getClass().getClassLoader()
        );
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeHostAdapters.withVerifiedStatusBar(RuntimeHostAdapters.safeMode(), partial));
    }

    private static List<StaticSelector> statusSelectors() {
        String appCtrl = "com/live2d/cubism/CEAppCtrl";
        return StatusBarVerificationManifest.REQUIRED_ALIASES.stream().sorted().map(alias ->
            alias.endsWith(".class")
                ? StaticSelector.classSelector(alias, appCtrl)
                : StaticSelector.method(alias, appCtrl, "placeholder", "()Ljava/lang/Object;")
        ).toList();
    }
}
