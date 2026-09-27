package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorHistoryIngressSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryMoveSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistorySemanticSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterValueWriteSelectorContract;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SemanticHistory5303ValidationScopeTest {

    @Test
    void exact5303ValidationCandidateRequiresTheCompoundRunnerIdentity() {
        assertTrue(VerifiedEditorModelResolverFactory.admitsSemanticHistoryValidation(
            "EXACT_5303_SEMANTIC_HISTORY_CANDIDATE",
            "semantic-history-5303",
            "semantic-history-5303-r1"
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsSemanticHistoryValidation(
            null, "semantic-history-5303", "semantic-history-5303-r1"
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsSemanticHistoryValidation(
            "EXACT_5303_SEMANTIC_HISTORY_CANDIDATE", "edit-level-write-5303", "semantic-history-5303-r1"
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsSemanticHistoryValidation(
            "EXACT_5303_EDIT_LEVEL_WRITE_CANDIDATE", "semantic-history-5303", "semantic-history-5303-r1"
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsSemanticHistoryValidation(
            "EXACT_5303_SEMANTIC_HISTORY_CANDIDATE", "semantic-history-5303", ""
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsSemanticHistoryValidation(
            "EXACT_5303_SEMANTIC_HISTORY_CANDIDATE", "semantic-history-5303", null
        ));
    }

    @Test
    void validationScopeAdmitsEveryHistoryReadFamilyButNoWriteCapability() {
        final PinnedVerifiedResolverWorkflow.RuntimeScope scope =
            EditorModelVerificationManifest.cubism5303SemanticHistoryValidationScope();

        assertTrue(scope.capabilityIds().containsAll(List.of(
            EditorHistoryReadSelectorContract.CAPABILITY_ID,
            EditorHistorySemanticSelectorContract.CAPABILITY_ID,
            EditorHistoryMoveSelectorContract.CAPABILITY_ID
        )));
        assertFalse(scope.capabilityIds().contains(
            EditorParameterValueWriteSelectorContract.CAPABILITY_ID
        ));

        final Set<String> expected = semanticAliases();
        expected.addAll(EditorHistoryReadSelectorContract.REQUIRED_ALIASES);
        expected.addAll(EditorHistoryMoveSelectorContract.REQUIRED_ALIASES);
        expected.addAll(EditorHistoryIngressSelectorContract.REQUIRED_ALIASES);
        assertTrue(
            scope.requiredAliases().containsAll(expected),
            () -> "5.3.03 semantic-history candidate missing "
                + missing(scope.requiredAliases(), expected)
        );
    }

    private static Set<String> semanticAliases() {
        final LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.addAll(EditorHistorySemanticSelectorContract.GROUP_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.PROPERTY_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.SIMPLE_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.LIST_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.ADD_REMOVE_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.ADD_REMOVE_PARAMETER_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.ADD_REMOVE_PART_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.ADD_REMOVE_DRAWABLE_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.ADD_REMOVE_DEFORMER_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.ADD_REMOVE_PARAMETER_GROUP_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.PART_MEMBERSHIP_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.ART_MESH_FORM_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.WARP_FORM_REQUIRED_ALIASES);
        aliases.addAll(EditorHistorySemanticSelectorContract.ROTATION_FORM_REQUIRED_ALIASES);
        return aliases;
    }

    private static Set<String> missing(final Set<String> actual, final Set<String> expected) {
        final LinkedHashSet<String> result = new LinkedHashSet<>(expected);
        result.removeAll(actual);
        return Set.copyOf(result);
    }
}
