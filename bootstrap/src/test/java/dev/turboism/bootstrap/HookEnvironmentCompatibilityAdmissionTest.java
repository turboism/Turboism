package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.CompatibilityResolution;
import dev.turboism.mapping.verification.CubismHostIdentity;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.HostIdentityProbe;
import dev.turboism.mapping.verification.SliceContract;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HookEnvironmentCompatibilityAdmissionTest {

    @Test
    void missingReviewedProfileCannotAuthorizeRuntimeHooks() {
        final var environment =
                HookEnvironment.builder().fullRuntimeAdmission(true).build();

        assertFalse(environment.ordinaryReviewedRuntimeAdmitted());
        assertFalse(environment.runtimeSliceAdmitted("editor-model"));
        assertFalse(environment.hookRuntimeAdmitted());
        assertTrue(environment.admittedRuntimeGeneration().isEmpty());
    }

    @Test
    void independentCoreGenerationDoesNotDisableRepackedEditorsHooks() {
        final var environment = environment("5.3.03", 503030001, "5.3.03", true);

        assertFalse(environment.declaredGenerationBound(), "the Core source is intentionally different");
        assertTrue(environment.hookRuntimeAdmitted());
        assertEquals(Optional.of("5.3.03"), environment.admittedRuntimeGeneration());
    }

    @Test
    void unrelatedSliceBindingDoesNotAuthorizeAnUnmatchedEditorGeneration() {
        assertFalse(environment("5.3.03", 503030001, "5.3.02", true).hookRuntimeAdmitted());
        assertFalse(environment("5.3.99", 503990001, "5.3.02", true).hookRuntimeAdmitted());
        assertFalse(environment("5.3.03", 503030002, "5.3.03", true).hookRuntimeAdmitted());
        assertFalse(environment("5.3.03", 503030001, "5.3.03", false).hookRuntimeAdmitted());
    }

    @Test
    void targetProvingHooksMayAttemptAMatchedUnknownGeneration() {
        final var compatible = environment("5.3.99", 503990001, "5.3.02", true);
        assertTrue(compatible.runtimeSliceAdmitted("editor-model"));
        assertFalse(compatible.runtimeSliceAdmitted("missing-slice"));
        assertFalse(environment("5.3.99", 503990001, "5.3.02", false).runtimeSliceAdmitted("editor-model"));
        assertTrue(new NativeEditBeginHookContributor().admitted(compatible));
        assertTrue(new EditApiDispatchHookContributor().admitted(compatible));
        assertFalse(compatible.hookRuntimeAdmitted(), "unmigrated hooks retain their own gate");
    }

    private static HookEnvironment environment(
            final String version, final int build, final String editorSource, final boolean baseAdmitted) {
        final HostArtifactDigest artifact = new HostArtifactDigest(1, "a".repeat(64));
        final var identity = new CubismHostIdentity(
                "Live2D Cubism Editor", version, Optional.empty(), build, "com/live2d/cubism/h", artifact);
        final var slices = Map.of(
                "editor-model", slice("editor-model", editorSource, identity),
                "core-model-read", slice("core-model-read", "5.3.02", identity));
        final var resolution = CompatibilityResolution.of(
                CompatibilityResolution.Mode.COMPATIBLE,
                new HostIdentityProbe(HostIdentityProbe.Status.DECLARED, Optional.of(identity), "fixture"),
                slices,
                baseAdmitted,
                "fixture");
        return HookEnvironment.builder()
                .profile(version)
                .hostResolution(resolution)
                .build();
    }

    private static CompatibilityResolution.SliceResolution slice(
            final String id, final String source, final CubismHostIdentity identity) {
        return new CompatibilityResolution.SliceResolution(
                id,
                CompatibilityResolution.SliceStatus.ADMITTED,
                Optional.of(new SliceContract(
                        id,
                        source,
                        "record.json",
                        "b".repeat(64),
                        "verification",
                        "adapter",
                        identity.version(),
                        identity.build(),
                        identity.artifact(),
                        true,
                        Set.of(),
                        Map.of())),
                List.of(source),
                "fixture");
    }
}
