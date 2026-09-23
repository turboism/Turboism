package dev.turboism.adapter.cubism.optimization.modelupdate.incremental;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalOptInDefaultTest {
    @Test void onlyExplicitTrueEnablesExperimentalIncrementalUpdates() {
        String property = IncrementalUpdateBridge.ENABLE_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            assertFalse(IncrementalUpdateBridge.flagEnabled(), "unreviewed incremental correctness must not become the default");
            for (String disabled : new String[] {"false", "", "invalid", "1"}) {
                System.setProperty(property, disabled);
                assertFalse(IncrementalUpdateBridge.flagEnabled(), disabled);
            }
            System.setProperty(property, "true");
            assertTrue(IncrementalUpdateBridge.flagEnabled());
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }
}
