package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateVersionOrderingTest {

    @Test
    void ordersReleasesAndPreReleasesBySemVerPrecedence() {
        assertTrue(UpdateVersionOrdering.compare("0.70.3", "0.70.2") > 0);
        assertTrue(UpdateVersionOrdering.compare("0.70.2", "0.70.3") < 0);
        assertEquals(0, UpdateVersionOrdering.compare("0.70.3", "0.70.3"));

        assertTrue(UpdateVersionOrdering.compare("0.70.3", "0.70.3-beta.1") > 0);
        assertTrue(UpdateVersionOrdering.compare("0.70.3-beta.2", "0.70.3-beta.1") > 0);
        assertTrue(UpdateVersionOrdering.compare("0.70.3-beta.10", "0.70.3-beta.2") > 0);
        assertTrue(UpdateVersionOrdering.compare("0.70.3-nightly.20261124", "0.70.3-beta.9") > 0);
    }

    @Test
    void rejectsMalformedOrOversizedVersions() {
        assertTrue(UpdateVersionOrdering.isValid("0.70.3"));
        assertTrue(UpdateVersionOrdering.isValid("0.70.3-SNAPSHOT"));
        assertTrue(UpdateVersionOrdering.isValid("0.70.3-nightly.20261124"));

        assertFalse(UpdateVersionOrdering.isValid(null));
        assertFalse(UpdateVersionOrdering.isValid(""));
        assertFalse(UpdateVersionOrdering.isValid("01.2.3"));
        assertFalse(UpdateVersionOrdering.isValid("0.70"));
        assertFalse(UpdateVersionOrdering.isValid("v0.70.3"));
        assertFalse(UpdateVersionOrdering.isValid("0.70.3+" + "a".repeat(130)));
        assertThrows(IllegalArgumentException.class, () -> UpdateVersionOrdering.compare("invalid", "0.70.3"));
    }
}
