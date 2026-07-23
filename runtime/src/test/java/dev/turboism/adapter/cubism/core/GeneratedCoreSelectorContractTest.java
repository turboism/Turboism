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
            "c78708fe6953a9ce32928b95678d4abbeeb3e2aff2d42fafc5fce8ad02cd1579",
            CorePublicApiSelectorContract.SELECTOR_ROSTER_SHA256
        );
        assertEquals(6, CorePublicApiSelectorContract.VERSION_PROBE_ALIASES.size());
        assertEquals(
            62,
            CorePublicApiSelectorContract.COMMON_STRUCTURAL_ALIASES.size()
        );
        assertEquals(
            69,
            CorePublicApiSelectorContract.requiredAliasesFor("5.2")
                .orElseThrow()
                .size()
        );
        assertEquals(
            70,
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
            56,
            CorePublicApiSelectorContract.structuralMethodAliasesFor("5.3.02")
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
