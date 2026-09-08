package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorHistorySemanticSelectorContract;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorHistorySemanticVerificationManifestTest {

    @Test
    void semanticFamiliesArePinnedFor5203And5302ButNotClaimedFor5303() {
        final Set<String> semanticAliases = semanticAliases();

        assertTrue(EditorModelVerificationManifest.cubism52Capabilities().contains(
            EditorHistorySemanticSelectorContract.CAPABILITY_ID
        ));
        assertTrue(EditorModelVerificationManifest.CAPABILITY_IDS.contains(
            EditorHistorySemanticSelectorContract.CAPABILITY_ID
        ));
        assertTrue(
            EditorModelVerificationManifest.cubism52Aliases().containsAll(semanticAliases),
            () -> "5.2.03 missing " + missing(EditorModelVerificationManifest.cubism52Aliases(), semanticAliases)
        );
        assertTrue(
            EditorModelVerificationManifest.cubism5302Aliases().containsAll(semanticAliases),
            () -> "5.3.02 missing " + missing(EditorModelVerificationManifest.cubism5302Aliases(), semanticAliases)
        );

        assertFalse(EditorModelVerificationManifest.cubism5303Capabilities().contains(
            EditorHistorySemanticSelectorContract.CAPABILITY_ID
        ));
        assertTrue(java.util.Collections.disjoint(
            EditorModelVerificationManifest.cubism5303StaticAliases(),
            semanticAliases.stream()
                .filter(alias -> alias.startsWith("cubism.editor-history.semantic."))
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
        ));
        final PinnedVerifiedResolverWorkflow.RuntimeScope runtimeScope =
            EditorModelVerificationManifest.cubism5303RuntimeScope();
        assertFalse(runtimeScope.capabilityIds().contains(EditorHistorySemanticSelectorContract.CAPABILITY_ID));
        assertTrue(java.util.Collections.disjoint(
            runtimeScope.requiredAliases(),
            semanticAliases.stream()
                .filter(alias -> alias.startsWith("cubism.editor-history.semantic."))
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
        ));
    }

    @Test
    void eachDecoderFamilyRetainsAnIndependentAuthorizationSet() {
        final List<Set<String>> baseFamilies = List.of(
            EditorHistorySemanticSelectorContract.GROUP_REQUIRED_ALIASES,
            EditorHistorySemanticSelectorContract.PROPERTY_REQUIRED_ALIASES,
            EditorHistorySemanticSelectorContract.SIMPLE_REQUIRED_ALIASES,
            EditorHistorySemanticSelectorContract.LIST_REQUIRED_ALIASES,
            EditorHistorySemanticSelectorContract.ADD_REMOVE_REQUIRED_ALIASES
        );
        for (int left = 0; left < baseFamilies.size(); left++) {
            for (int right = left + 1; right < baseFamilies.size(); right++) {
                assertTrue(
                    java.util.Collections.disjoint(baseFamilies.get(left), baseFamilies.get(right)),
                    "decoder base families must not share authorization aliases"
                );
            }
        }
        assertFalse(EditorHistorySemanticSelectorContract.ADD_REMOVE_PARAMETER_REQUIRED_ALIASES.isEmpty());
        assertFalse(EditorHistorySemanticSelectorContract.ADD_REMOVE_PART_REQUIRED_ALIASES.isEmpty());
        assertFalse(EditorHistorySemanticSelectorContract.ADD_REMOVE_DRAWABLE_REQUIRED_ALIASES.isEmpty());
        assertFalse(EditorHistorySemanticSelectorContract.ADD_REMOVE_DEFORMER_REQUIRED_ALIASES.isEmpty());
        assertFalse(EditorHistorySemanticSelectorContract.ADD_REMOVE_PARAMETER_GROUP_REQUIRED_ALIASES.isEmpty());
        assertTrue(
            EditorHistorySemanticSelectorContract.ART_MESH_FORM_REQUIRED_ALIASES.containsAll(
                Set.of(
                    "cubism.editor-history.semantic.art-mesh-form.class",
                    "cubism.editor-model.drawable-form.multiply-color"
                )
            )
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
        aliases.addAll(EditorHistorySemanticSelectorContract.ART_MESH_FORM_REQUIRED_ALIASES);
        return Set.copyOf(aliases);
    }

    private static Set<String> missing(final Set<String> actual, final Set<String> expected) {
        final LinkedHashSet<String> result = new LinkedHashSet<>(expected);
        result.removeAll(actual);
        return Set.copyOf(result);
    }
}
