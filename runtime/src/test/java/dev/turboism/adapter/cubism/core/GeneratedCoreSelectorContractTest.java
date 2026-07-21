package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedCoreSelectorContractTest {

    @Test
    void generatedContractBindsTheExactProfileSets() {
        assertEquals(
            "4018a437aaec2b65a3c46b7c8b9715f900f93a7c15f3bdfd95012e5c212e227c",
            CorePublicApiSelectorContract.SELECTOR_ROSTER_SHA256
        );
        assertEquals(6, CorePublicApiSelectorContract.VERSION_PROBE_ALIASES.size());
        assertEquals(
            19,
            CorePublicApiSelectorContract.COMMON_STRUCTURAL_ALIASES.size()
        );
        assertEquals(
            25,
            CorePublicApiSelectorContract.requiredAliasesFor("5.2")
                .orElseThrow()
                .size()
        );
        assertEquals(
            26,
            CorePublicApiSelectorContract.requiredAliasesFor("5.3.02")
                .orElseThrow()
                .size()
        );
        assertTrue(
            CorePublicApiSelectorContract.requiredAliasesFor("5.3.02")
                .orElseThrow()
                .contains(CorePublicApiSelectorContract.PARAMETERS_GET_REPEATS)
        );
        assertFalse(
            CorePublicApiSelectorContract.requiredAliasesFor("5.2")
                .orElseThrow()
                .contains(CorePublicApiSelectorContract.PARAMETERS_GET_REPEATS)
        );
    }

    @Test
    void generatedContractRoutesOnlySupportedExactProfiles() {
        assertEquals(
            "cubism-core-public-5.2",
            CorePublicApiSelectorContract.providerIdFor("5.2").orElseThrow()
        );
        assertEquals(
            "cubism-core-public-5.3.02",
            CorePublicApiSelectorContract.providerIdFor("5.3.02").orElseThrow()
        );
        assertTrue(
            CorePublicApiSelectorContract.providerIdFor("5.3").isEmpty()
        );
        assertTrue(
            CorePublicApiSelectorContract.requiredAliasesFor("5.4").isEmpty()
        );
    }

    @Test
    void generatedSetsRemainImmutable() {
        assertThrows(
            UnsupportedOperationException.class,
            () -> CorePublicApiSelectorContract.REQUIRED_ALIASES_5_2.add("bad")
        );
    }
}
