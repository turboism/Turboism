package dev.turboism.ui.contribution;

import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorUiProviderAdmissionTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void admittedProviderRequiresExactEvidenceAndCurrentHostGeneration() {
        EditorUiProviderAdmission.VerificationEvidence evidence =
            new EditorUiProviderAdmission.VerificationEvidence(
                "5.3.02",
                42,
                SHA_A,
                "adapter.editor-ui.menu",
                SHA_B
            );
        EditorUiProviderAdmission admission = EditorUiProviderAdmission.admitted(
            EditorUiFamily.MENU,
            7,
            evidence
        );

        assertEquals(7, admission.hostGeneration());
        assertEquals(evidence, admission.verificationEvidence().orElseThrow());
        assertTrue(admission.isAdmittedTo(7));
        assertFalse(admission.isAdmittedTo(8));
        assertThrows(IllegalArgumentException.class, () -> new EditorUiProviderAdmission(
            EditorUiFamily.MENU,
            EditorUiProviderAdmission.Status.ADMITTED,
            0,
            Optional.of(evidence),
            Optional.empty(),
            Optional.empty()
        ));
    }

    @Test
    void safeModeProviderNeverReconcilesReadyFamily() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        SafeModeEditorUiContributionProvider provider = new SafeModeEditorUiContributionProvider(
            EditorUiFamily.MENU,
            "ui.menu.mapping-not-verified"
        );
        authority.installProvider(provider);
        long generation = lifecycle.connecting().generation();
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        authority.contribute(new EditorUiContribution<>(
            new EditorUiContributionIdentity("plugin", EditorUiFamily.MENU, "menu"),
            0,
            "menu"
        ));

        assertFalse(provider.admission().isAdmitted());
        assertEquals(
            EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED,
            authority.lastFailure(EditorUiFamily.MENU).orElseThrow().code()
        );
    }

    @Test
    void safeModeDiagnosticMustBeBoundedAndSanitized() {
        assertThrows(
            IllegalArgumentException.class,
            () -> EditorUiProviderAdmission.safeMode(
                EditorUiFamily.MENU,
                EditorUiContributionFailure.Code.HOST_UNSUPPORTED,
                "private path: /host/user/file"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> EditorUiProviderAdmission.safeMode(
                EditorUiFamily.MENU,
                EditorUiContributionFailure.Code.HOST_UNSUPPORTED,
                "x".repeat(129)
            )
        );
    }
}
