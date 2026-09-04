package dev.turboism.ui.toolbar;

import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.ReviewedSliceRecord;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteToolbarContributionProviderTest {

    @Test
    void routesLogActionsSkipsUnsupportedPalettesAndCleansIdempotently() {
        final RecordingHost host = new RecordingHost();
        final List<String> actions = new ArrayList<>();
        final PaletteToolbarContributionProvider provider = new PaletteToolbarContributionProvider(
            admission(3, EditorModelVerificationManifest.RECORD_5_3_02),
            host,
            (pluginId, actionId) -> actions.add(pluginId + ":" + actionId)
        );

        final Registration registration = provider.apply(3, List.of(
            contribution("plugin-a", "log", "LOG", 0),
            contribution("plugin-demo", "parameter", "parameters", 10)
        ));

        assertEquals(List.of("plugin-a:log"), host.nativeIds());
        host.buttons.get(0).action().run();
        assertEquals(List.of("plugin-a:action.log"), actions);
        registration.close();
        registration.close();
        assertEquals(1, host.clearCount);
        assertFalse(host.hasLiveButtons());
    }

    @Test
    void admitsEveryCurrentExactEditorModelRecord() {
        long generation = 10;
        for (ReviewedSliceRecord record : List.of(
            EditorModelVerificationManifest.RECORD_5_2_03,
            EditorModelVerificationManifest.RECORD_5_3_02,
            EditorModelVerificationManifest.RECORD_5_3_03
        )) {
            final PaletteToolbarContributionProvider provider = new PaletteToolbarContributionProvider(
                admission(generation, record),
                new RecordingHost(),
                (pluginId, actionId) -> { }
            );
            assertTrue(provider.admission().isAdmittedTo(generation), record.cubismVersion());
            generation++;
        }
    }

    @Test
    void staleGenerationAndWrongFamilyFailClosed() {
        final PaletteToolbarContributionProvider provider = new PaletteToolbarContributionProvider(
            admission(3, EditorModelVerificationManifest.RECORD_5_3_03),
            new RecordingHost(),
            (pluginId, actionId) -> { }
        );
        assertThrows(IllegalStateException.class, () -> provider.apply(
            4,
            List.of(contribution("plugin-a", "log", "LOG", 0))
        ));
        assertThrows(IllegalArgumentException.class, () -> new PaletteToolbarContributionProvider(
            EditorUiProviderAdmission.admitted(
                EditorUiFamily.MAIN_TOOLBAR,
                3,
                evidence(EditorModelVerificationManifest.RECORD_5_3_03)
            ),
            new RecordingHost(),
            (pluginId, actionId) -> { }
        ));
    }

    @Test
    void hostFailureClearsPartialNativeState() {
        final RecordingHost host = new RecordingHost();
        host.failOnSet = true;
        final PaletteToolbarContributionProvider provider = new PaletteToolbarContributionProvider(
            admission(3, EditorModelVerificationManifest.RECORD_5_3_02),
            host,
            (pluginId, actionId) -> { }
        );

        assertThrows(IllegalStateException.class, () -> provider.apply(
            3,
            List.of(contribution("plugin-a", "log", "LOG", 0))
        ));
        assertEquals(1, host.clearCount);
    }

    private static EditorUiContribution<PaletteToolbarRegistry.PaletteToolbarContribution> contribution(
        final String pluginId,
        final String id,
        final String paletteId,
        final int order
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(pluginId, EditorUiFamily.PALETTE_TOOLBAR, id),
            order,
            new PaletteToolbarRegistry.PaletteToolbarContribution(
                id,
                "action." + id,
                "label." + id,
                "icons/" + id + ".svg",
                paletteId,
                "end",
                order
            )
        );
    }

    private static EditorUiProviderAdmission admission(
        final long generation,
        final ReviewedSliceRecord record
    ) {
        return EditorUiProviderAdmission.admitted(
            EditorUiFamily.PALETTE_TOOLBAR,
            generation,
            evidence(record)
        );
    }

    private static EditorUiProviderAdmission.VerificationEvidence evidence(
        final ReviewedSliceRecord record
    ) {
        return new EditorUiProviderAdmission.VerificationEvidence(
            record.cubismVersion(),
            record.artifact().size(),
            record.artifact().sha256(),
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            record.recordSha256()
        );
    }

    private static final class RecordingHost implements PaletteToolbarHostOperations {
        private List<ButtonContribution> buttons = List.of();
        private int clearCount;
        private boolean failOnSet;

        @Override
        public void setContributions(final List<ButtonContribution> contributions) {
            buttons = List.copyOf(contributions);
            if (failOnSet) {
                throw new IllegalStateException("log-palette-root-not-found");
            }
        }

        @Override
        public void reconcileNow() {
        }

        @Override
        public void clearContributions() {
            clearCount++;
            buttons = List.of();
        }

        @Override
        public boolean hasLiveButtons() {
            return !buttons.isEmpty();
        }

        private List<String> nativeIds() {
            return buttons.stream().map(value -> value.descriptor().nativeId()).toList();
        }
    }
}
