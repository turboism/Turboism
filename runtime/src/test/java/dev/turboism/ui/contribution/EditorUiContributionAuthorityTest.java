package dev.turboism.ui.contribution;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorUiContributionAuthorityTest {

    @Test
    void recordsBeforeReadyAndReconcilesInDeterministicOrder() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);

        authority.contribute(contribution("plugin-b", "second", 10));
        authority.contribute(contribution("plugin-a", "first", 10));
        authority.contribute(contribution("plugin-a", "leading", 0));

        assertTrue(provider.snapshots.isEmpty());
        long generation = lifecycle.connecting().generation();
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));

        assertEquals(1, provider.snapshots.size());
        assertEquals(
            List.of("plugin-a:leading", "plugin-a:first", "plugin-b:second"),
            provider.snapshots.get(0)
        );
    }

    @Test
    void contributionChangesReplaceNativeRegistrationExactlyOnce() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);

        Registration first = authority.contribute(contribution("plugin-a", "first", 0));
        Registration second = authority.contribute(contribution("plugin-a", "second", 1));
        second.close();
        second.close();
        first.close();

        assertEquals(3, provider.snapshots.size());
        assertEquals(3, provider.closedRegistrations);
        assertTrue(authority.contributions(EditorUiFamily.MENU).isEmpty());
    }

    @Test
    void hostReplacementRequiresFreshGenerationAdmission() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);
        authority.contribute(contribution("plugin-a", "first", 0));
        long first = lifecycle.connecting().generation();
        provider.admit(first);
        lifecycle.ready(first, Set.of(EditorUiFamily.MENU));

        lifecycle.replacing();
        long second = lifecycle.connecting().generation();
        lifecycle.ready(second, Set.of(EditorUiFamily.MENU));

        assertEquals(List.of(first), provider.generations);
        assertEquals(1, provider.closedRegistrations);
        assertEquals(
            EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED,
            authority.lastFailure(EditorUiFamily.MENU).orElseThrow().code()
        );

        authority.removeProvider(provider);
        provider.admit(second);
        authority.installProvider(provider);

        assertEquals(List.of(first, second), provider.generations);
    }

    @Test
    void duplicateIdentityIsRejectedWithoutReplacingOriginal() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.contribute(contribution("plugin-a", "same", 0));

        assertThrows(
            IllegalStateException.class,
            () -> authority.contribute(contribution("plugin-a", "same", 1))
        );
        assertEquals(1, authority.contributions(EditorUiFamily.MENU).size());
    }

    @Test
    void unavailableProviderDoesNotApplyAndRecordsFailure() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);
        authority.contribute(contribution("plugin-a", "first", 0));

        assertTrue(provider.snapshots.isEmpty());
        assertEquals(
            EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED,
            authority.lastFailure(EditorUiFamily.MENU).orElseThrow().code()
        );
    }

    @Test
    void mismatchedAdmissionFamilyIsRejected() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        provider.admission = EditorUiProviderAdmission.safeMode(
            EditorUiFamily.MAIN_TOOLBAR,
            "ui.toolbar.mapping-not-verified"
        );

        assertThrows(IllegalArgumentException.class, () -> authority.installProvider(provider));
    }

    @Test
    void closeDisposesNativeAndRejectsNewContributions() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);
        authority.contribute(contribution("plugin-a", "first", 0));

        authority.close();
        authority.close();

        assertEquals(1, provider.closedRegistrations);
        assertThrows(
            IllegalStateException.class,
            () -> authority.contribute(contribution("plugin-a", "late", 0))
        );
    }

    private static EditorUiContribution<String> contribution(
        final String pluginId,
        final String id,
        final int order
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(pluginId, EditorUiFamily.MENU, id),
            order,
            pluginId + ":" + id
        );
    }

    private static final class RecordingProvider implements EditorUiContributionProvider {
        private static final String ARTIFACT_SHA256 = "a".repeat(64);
        private static final String RECORD_SHA256 = "b".repeat(64);

        private final EditorUiFamily family;
        private final List<Long> generations = new ArrayList<>();
        private final List<List<String>> snapshots = new ArrayList<>();
        private EditorUiProviderAdmission admission;
        private int closedRegistrations;

        private RecordingProvider(final EditorUiFamily family) {
            this.family = family;
            this.admission = EditorUiProviderAdmission.safeMode(
                family,
                "ui.provider.mapping-not-verified"
            );
        }

        private void admit(final long generation) {
            admission = EditorUiProviderAdmission.admitted(
                family,
                generation,
                new EditorUiProviderAdmission.VerificationEvidence(
                    "5.3.02",
                    42,
                    ARTIFACT_SHA256,
                    "adapter.editor-ui." + family.name().toLowerCase(java.util.Locale.ROOT),
                    RECORD_SHA256
                )
            );
        }

        @Override
        public EditorUiFamily family() {
            return family;
        }

        @Override
        public EditorUiProviderAdmission admission() {
            return admission;
        }

        @Override
        public Registration apply(
            final long hostGeneration,
            final List<EditorUiContribution<?>> contributions
        ) {
            generations.add(hostGeneration);
            snapshots.add(contributions.stream().map(value -> (String) value.descriptor()).toList());
            return () -> closedRegistrations++;
        }
    }
}
