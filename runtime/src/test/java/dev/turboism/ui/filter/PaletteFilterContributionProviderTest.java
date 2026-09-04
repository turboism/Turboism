package dev.turboism.ui.filter;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteFilterContributionProviderTest {

    @Test
    void appliesAuthoritySnapshotAndCleansOnlyFilterSlot() {
        final PaletteFilterHostOperations host = new PaletteFilterHostOperations(kind -> null);
        final PaletteFilterContributionProvider provider = new PaletteFilterContributionProvider(admission(7), host);
        final Registration registration = provider.apply(7, List.of(contribution("plugin-a", "filter", "LOG", 10)));

        assertTrue(provider.supportsIncrementalReconcile());
        assertEquals(1, host.filterContributionCount(PaletteFilterHostOperations.PaletteKind.LOG));
        registration.close();
        registration.close();
        assertEquals(0, host.filterContributionCount(PaletteFilterHostOperations.PaletteKind.LOG));
    }

    @Test
    void staleGenerationUnknownPaletteAndWrongFamilyFailClosed() {
        final PaletteFilterHostOperations host = new PaletteFilterHostOperations(kind -> null);
        final PaletteFilterContributionProvider provider = new PaletteFilterContributionProvider(admission(7), host);
        assertThrows(IllegalStateException.class, () -> provider.apply(8, List.of()));
        assertThrows(IllegalArgumentException.class, () -> provider.apply(
            7,
            List.of(contribution("plugin-a", "filter", "UNKNOWN", 10))
        ));
        assertThrows(IllegalArgumentException.class, () -> new PaletteFilterContributionProvider(
            EditorUiProviderAdmission.admitted(EditorUiFamily.PALETTE_TOOLBAR, 7, evidence()),
            host
        ));
    }

    private static EditorUiContribution<PaletteFilterRegistry.PaletteFilterContribution> contribution(
        final String pluginId,
        final String id,
        final String paletteId,
        final int order
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(pluginId, EditorUiFamily.PALETTE_FILTER, id),
            order,
            new PaletteFilterRegistry.PaletteFilterContribution(id, paletteId, "Filter", order)
        );
    }

    private static EditorUiProviderAdmission admission(final long generation) {
        return EditorUiProviderAdmission.admitted(EditorUiFamily.PALETTE_FILTER, generation, evidence());
    }

    private static EditorUiProviderAdmission.VerificationEvidence evidence() {
        return new EditorUiProviderAdmission.VerificationEvidence(
            "5.3.02", 1, "a".repeat(64), "editor-model", "b".repeat(64)
        );
    }
}
