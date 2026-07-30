package dev.turboism.sdk.cubism.physics;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsEditorApiContractTest {

    @Test
    void pluginContextExposesOnlyTheBoundedPhysicsEditorService() throws Exception {
        final Method accessor = PluginContext.class.getMethod("physicsEditor");
        assertEquals(PhysicsEditorService.class, accessor.getReturnType());
        assertTrue(accessor.isDefault());
        assertEquals(
            Registration.class,
            PhysicsEditorService.class.getMethod("contribute", PhysicsEditorContribution.class).getReturnType()
        );
        assertEquals(2, PhysicsEditorContribution.class.getRecordComponents().length);
    }
}
