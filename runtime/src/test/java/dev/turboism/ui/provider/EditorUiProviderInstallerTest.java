package dev.turboism.ui.provider;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorUiProviderInstallerTest {

    @Test
    void installsOnlyProvidersAdmittedForCurrentGeneration() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        RecordingProvider admitted = new RecordingProvider(EditorUiFamily.MENU, admission(
            EditorUiFamily.MENU,
            4
        ));
        RecordingProvider stale = new RecordingProvider(EditorUiFamily.MAIN_TOOLBAR, admission(
            EditorUiFamily.MAIN_TOOLBAR,
            3
        ));

        EditorUiProviderInstaller.Installation installation = EditorUiProviderInstaller.install(
            4,
            authority,
            List.of(admitted, stale)
        );

        assertEquals(Set.of(EditorUiFamily.MENU), installation.readyFamilies());
        installation.close();
        installation.close();
        authority.installProvider(admitted);
        authority.removeProvider(admitted);
        authority.installProvider(stale);
    }

    @Test
    void duplicateFamilyRollsBackAlreadyInstalledProvider() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        RecordingProvider first = new RecordingProvider(EditorUiFamily.MENU, admission(
            EditorUiFamily.MENU,
            4
        ));
        RecordingProvider duplicate = new RecordingProvider(EditorUiFamily.MENU, admission(
            EditorUiFamily.MENU,
            4
        ));

        assertThrows(
            IllegalArgumentException.class,
            () -> EditorUiProviderInstaller.install(4, authority, List.of(first, duplicate))
        );
        authority.installProvider(first);
    }

    private static EditorUiProviderAdmission admission(
        final EditorUiFamily family,
        final long generation
    ) {
        return EditorUiProviderAdmission.admitted(
            family,
            generation,
            new EditorUiProviderAdmission.VerificationEvidence(
                "5.3.02",
                42,
                "a".repeat(64),
                "adapter.editor-ui." + family.name().toLowerCase(java.util.Locale.ROOT),
                "b".repeat(64)
            )
        );
    }

    private static final class RecordingProvider implements EditorUiContributionProvider {
        private final EditorUiFamily family;
        private final EditorUiProviderAdmission admission;

        private RecordingProvider(
            final EditorUiFamily family,
            final EditorUiProviderAdmission admission
        ) {
            this.family = family;
            this.admission = admission;
        }

        @Override public EditorUiFamily family() { return family; }
        @Override public EditorUiProviderAdmission admission() { return admission; }
        @Override public Registration apply(
            final long hostGeneration,
            final List<EditorUiContribution<?>> contributions
        ) {
            return () -> { };
        }
    }
}
