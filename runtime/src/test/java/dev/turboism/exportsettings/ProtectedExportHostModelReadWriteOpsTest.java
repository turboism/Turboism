package dev.turboism.exportsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.VerifiedAccessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Behavioural pins for {@link ProtectedExportHostModelReadWriteOps}.
 *
 * <p>The model half is the one place protected export rewrites an authoring document's disposable
 * copy, so the cases here are the two properties the extraction could have cost: an alias the
 * verified plan does not carry must fail closed instead of reading as "not this type", and the
 * census family classification must stay a bounded {@code getClass()} read that never touches the
 * host.</p>
 */
class ProtectedExportHostModelReadWriteOpsTest {

    @Test
    void censusReadsFailClosedWhenThePlanDoesNotCarryTheAlias() {
        final ProtectedExportHostModelReadWriteOps ops = ops();

        final VerifiedAccessException failure =
                assertThrows(VerifiedAccessException.class, () -> ops.allDeformers(null));
        assertEquals("cubism.protected-export.model-source.class", failure.alias());

        assertThrows(VerifiedAccessException.class, () -> ops.allParameters(null));
        assertThrows(VerifiedAccessException.class, () -> ops.allPhysicsSettings(null));
        assertThrows(VerifiedAccessException.class, () -> ops.rootPart(null));
    }

    @Test
    void theRewritesFailClosedWhenThePlanDoesNotCarryTheAlias() {
        final ProtectedExportHostModelReadWriteOps ops = ops();

        assertThrows(VerifiedAccessException.class, () -> ops.setDrawableId(new Object(), "id"));
        assertThrows(VerifiedAccessException.class, () -> ops.setObjectLocalName(new Object(), "name"));
    }

    @Test
    void theExtendedInterpolationGateWalksTheCensusThroughTheSameGuard() {
        final ProtectedExportHostModelReadWriteOps ops = ops();

        assertThrows(VerifiedAccessException.class, () -> ops.usesExtendedInterpolation(null));
    }

    @Test
    void censusFamilyClassifiesWithoutAnyHostMemberResolution() {
        final ProtectedExportHostModelReadWriteOps ops = ops();

        assertEquals("unknown", ops.censusFamily(null));
        assertEquals("unknown:CensusFamilyProbe", ops.censusFamily(new CensusFamilyProbe()));
    }

    @Test
    void censusFamilyClipsAnUnknownClassClueToTheBoundedLength() {
        final ProtectedExportHostModelReadWriteOps ops = ops();

        final String family = ops.censusFamily(new ANamedTypeLongEnoughToForceTheBoundedClueToBeTrimmed());

        assertTrue(family.startsWith("unknown:"), family);
        assertEquals("unknown:".length() + 48, family.length(), family);
    }

    private static ProtectedExportHostModelReadWriteOps ops() {
        return new ProtectedExportHostModelReadWriteOps(
                ProtectedExportHostOpsTestSupport.resolverWithoutProtectedExportAliases(),
                false,
                Map.of("cubism.protected-export.model-source.contain-alias", "alias"));
    }

    /** Short, unremarkable type used to pin the bounded "unknown family" clue. */
    private static final class CensusFamilyProbe {}

    /**
     * A type whose simple name exceeds the 48-character clue bound, so the classifier has to
     * trim instead of echoing an unbounded internal name into a census token.
     */
    private static final class ANamedTypeLongEnoughToForceTheBoundedClueToBeTrimmed {}
}
