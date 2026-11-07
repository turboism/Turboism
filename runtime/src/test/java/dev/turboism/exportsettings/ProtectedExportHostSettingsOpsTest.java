package dev.turboism.exportsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.mapping.verification.VerifiedAccessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Behavioural pins for {@link ProtectedExportHostSettingsOps}.
 *
 * <p>Settings reads decide whether a physics or motion-sync object is census-pinnable, so the
 * properties worth pinning are that an alias the verified plan does not carry fails closed
 * instead of reading as "not this family", and that each rewrite keeps its stated probe ordering
 * \u2014 the extraction moved both halves and a reordered guard would silently let an unpinnable
 * settings object through.</p>
 */
class ProtectedExportHostSettingsOpsTest {

    private static final String PHYSICS_SETTINGS_CLASS = "cubism.protected-export.physics-settings-source.class";

    @Test
    void familyProbesFailClosedWhenThePlanDoesNotCarryTheAlias() {
        final ProtectedExportHostSettingsOps ops = ops();

        final VerifiedAccessException failure =
                assertThrows(VerifiedAccessException.class, () -> ops.isPhysicsSettingsSource(new Object()));
        assertEquals(PHYSICS_SETTINGS_CLASS, failure.alias());
        assertThrows(VerifiedAccessException.class, () -> ops.isMotionSyncSettingSource(new Object()));
    }

    @Test
    void theIdentityReadsFailClosedInsteadOfAnsweringForAnUnknownFamily() {
        final ProtectedExportHostSettingsOps ops = ops();

        assertThrows(VerifiedAccessException.class, () -> ops.settingsGuid(null));
        assertThrows(VerifiedAccessException.class, () -> ops.settingsIdString(null));
        assertThrows(VerifiedAccessException.class, () -> ops.settingsName(null));
        assertThrows(VerifiedAccessException.class, () -> ops.parameterSourceGuid(new Object()));
    }

    @Test
    void theSignaturesFailClosedInsteadOfReturningAnEmptySignature() {
        final ProtectedExportHostSettingsOps ops = ops();

        assertThrows(VerifiedAccessException.class, () -> ops.settingsSignature(null, new Object()));
        assertThrows(VerifiedAccessException.class, () -> ops.physicsSettingsSetSignature(null));
    }

    @Test
    void theNameRewriteProbesTheFamilyBeforeRejectingANullName() {
        final ProtectedExportHostSettingsOps ops = ops();

        assertThrows(VerifiedAccessException.class, () -> ops.setSettingsName(new Object(), null));
    }

    @Test
    void theIdRewriteRejectsANullIdBeforeAnyHostAccess() {
        final ProtectedExportHostSettingsOps ops = ops();

        final NullPointerException failure =
                assertThrows(NullPointerException.class, () -> ops.setSettingsId(new Object(), null));
        assertEquals("idString", failure.getMessage());
    }

    @Test
    void theIdRewriteFailsClosedForANonSettingsSourceOnceTheIdIsPresent() {
        final ProtectedExportHostSettingsOps ops = ops();

        assertThrows(VerifiedAccessException.class, () -> ops.setSettingsId(new Object(), "id"));
    }

    private static ProtectedExportHostSettingsOps ops() {
        final var resolver = ProtectedExportHostOpsTestSupport.resolverWithoutProtectedExportAliases();
        return new ProtectedExportHostSettingsOps(
                resolver, new ProtectedExportHostModelReadWriteOps(resolver, false, Map.of()));
    }
}
