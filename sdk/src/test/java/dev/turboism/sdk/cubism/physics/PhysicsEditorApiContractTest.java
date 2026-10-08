package dev.turboism.sdk.cubism.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

class PhysicsEditorApiContractTest {

    @Test
    void pluginContextExposesOnlyTheBoundedPhysicsEditorService() throws Exception {
        assertThrows(NoSuchMethodException.class, () -> PluginContext.class.getMethod("physicsEditor"));
        assertEquals(
                Registration.class,
                PhysicsEditorService.class
                        .getMethod("contribute", PhysicsEditorContribution.class)
                        .getReturnType());
        assertEquals(2, PhysicsEditorContribution.class.getRecordComponents().length);
    }
}
