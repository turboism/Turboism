package dev.turboism.core.schema.runtimeconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniformLocationPreferenceSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeConfigValidator validator = new RuntimeConfigValidator();

    @Test
    void acceptsBothExplicitBooleanPreferences() throws Exception {
        for (boolean enabled : new boolean[] {true, false}) {
            var root = mapper.readTree("{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,"
                + "\"worktreeId\":\"uniform-settings\",\"launcher\":{\"uniformLocationCache\":" + enabled + "}}");
            assertTrue(validator.validate(root).isEmpty(), "boolean launcher preference must round trip");
        }
    }

    @Test
    void rejectsNonBooleanPreferences() throws Exception {
        for (String value : new String[] {"null", "0", "\"true\"", "{}", "[]"}) {
            var root = mapper.readTree("{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,"
                + "\"worktreeId\":\"uniform-settings\",\"launcher\":{\"uniformLocationCache\":" + value + "}}");
            assertFalse(validator.validate(root).isEmpty(), "malformed preference must not be coerced");
        }
    }
}
