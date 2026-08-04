package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedCoreSelectorContractTest {

    @Test
    void generatedContractBindsTheExactProfileSets() {
        assertEquals(
            "a0f14a73201282f50e6970181d2277ad2ce8fa9c121e7b45fc525bcaa474e699",
            CorePublicApiSelectorContract.SELECTOR_ROSTER_SHA256
        );
        assertEquals(9, CorePublicApiSelectorContract.VERSION_PROBE_ALIASES.size());
        assertEquals(
            62,
            CorePublicApiSelectorContract.COMMON_STRUCTURAL_ALIASES.size()
        );
        assertEquals(
            72,
            CorePublicApiSelectorContract.requiredAliasesFor("5.2")
                .orElseThrow()
                .size()
        );
        assertEquals(
            74,
            CorePublicApiSelectorContract.requiredAliasesFor("5.3.02")
                .orElseThrow()
                .size()
        );
        assertEquals(
            55,
            CorePublicApiSelectorContract.structuralMethodAliasesFor("5.2")
                .orElseThrow()
                .size()
        );
        assertEquals(
            57,
            CorePublicApiSelectorContract.structuralMethodAliasesFor("5.3.02")
                .orElseThrow()
                .size()
        );
        assertTrue(
            CorePublicApiSelectorContract.requiredAliasesFor("5.3.02")
                .orElseThrow()
                .contains(CorePublicApiSelectorContract.PARAMETERS_GET_REPEATS)
        );
        assertTrue(
            CorePublicApiSelectorContract.requiredAliasesFor("5.3.02")
                .orElseThrow()
                .contains(CorePublicApiSelectorContract.MODEL_GET_RENDER_ORDERS)
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
    void structuralCallSitesUseOneGeneratedAliasMap() throws Exception {
        assertEquals(
            Set.of("callSites", "closed"),
            Arrays.stream(CoreCallSiteTable.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet())
        );
        assertEquals(
            Map.class,
            CoreCallSiteTable.class.getDeclaredField("callSites").getType()
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
