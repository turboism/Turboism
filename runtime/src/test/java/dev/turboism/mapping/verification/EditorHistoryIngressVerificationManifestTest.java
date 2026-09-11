package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorHistoryIngressSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorHistoryIngressVerificationManifestTest {

    @Test
    void nativeUndoStateListenerIsAdmittedFor5203And5302Only() {
        final Set<String> ingressAliases = EditorHistoryIngressSelectorContract.REQUIRED_ALIASES;

        assertFalse(ingressAliases.isEmpty());
        assertTrue(
            EditorModelVerificationManifest.cubism52Capabilities().contains(
                EditorHistoryIngressSelectorContract.CAPABILITY_ID
            )
        );
        assertTrue(
            EditorModelVerificationManifest.cubism52Aliases().containsAll(ingressAliases),
            () -> "5.2.03 missing " + missing(
                EditorModelVerificationManifest.cubism52Aliases(), ingressAliases
            )
        );
        assertTrue(
            EditorModelVerificationManifest.cubism5302Aliases().containsAll(ingressAliases),
            () -> "5.3.02 missing " + missing(
                EditorModelVerificationManifest.cubism5302Aliases(), ingressAliases
            )
        );

        assertTrue(Collections.disjoint(
            EditorModelVerificationManifest.cubism5303StaticAliases(), ingressAliases
        ), "5.3.03 must not admit the native undo state listener without its own reviewed evidence");
        assertTrue(Collections.disjoint(
            EditorModelVerificationManifest.cubism5303RuntimeScope().requiredAliases(), ingressAliases
        ));
        assertTrue(Collections.disjoint(
            EditorModelVerificationManifest.cubism5303ReadAliases(), ingressAliases
        ));
    }

    @Test
    void theInheritedEditEntryIsAdmittedExactlyWhereTheIngressIs() {
        // CModelingEditMode_Main.beginEdit is already part of the mature Editor surface under its
        // own alias; the ingress adds only the inherited ACEditMode entry, which the scene editor
        // and the game-data editor resolve their beginEdit to.
        assertTrue(
            EditorModelVerificationManifest.REQUIRED_ALIASES.containsAll(
                EditorHistoryIngressSelectorContract.REQUIRED_ALIASES
            )
        );
        assertTrue(
            EditorModelVerificationManifest.cubism52Aliases().contains(
                EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS
            )
        );
        assertTrue(
            EditorModelVerificationManifest.cubism5302Aliases().contains(
                EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS
            )
        );
        assertFalse(
            EditorModelVerificationManifest.cubism5303StaticAliases().contains(
                EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS
            ),
            "5.3.03 must not admit the entry hook without its own reviewed evidence"
        );
        assertTrue(
            EditorModelVerificationManifest.REQUIRED_ALIASES.contains(
                "cubism.editor-model.edit-mode.begin"
            ),
            "the modeling entry keeps the alias the mature Editor surface already uses"
        );
        assertFalse(
            EditorHistoryIngressSelectorContract.REQUIRED_ALIASES.contains(
                "cubism.editor-model.edit-mode.begin"
            ),
            "the ingress admits only its own base entry, not the modeling override"
        );
    }

    @Test
    void observerRegistrationKeepsItsOwnAuthorizationSet() {
        assertTrue(
            Collections.disjoint(
                EditorHistoryIngressSelectorContract.REQUIRED_ALIASES,
                EditorHistoryReadSelectorContract.REQUIRED_ALIASES
            ),
            "observation registration must not widen the immutable snapshot read contract"
        );
        assertTrue(EditorHistoryIngressSelectorContract.ADAPTER_SLICE_ID.equals(
            EditorModelVerificationManifest.ADAPTER_SLICE_ID
        ));
    }

    private static Set<String> missing(final Set<String> actual, final Set<String> expected) {
        final java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(expected);
        result.removeAll(actual);
        return Set.copyOf(result);
    }
}
