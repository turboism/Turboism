package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.core.plugin.context.CorePluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSessionCompositionApiTest {

    @Test
    void publicCompositionCannotInjectArbitraryConnectorOrAdapterBundle() {
        assertFalse(hasPublicConstructor(HostSession.class, HostInstanceSource.class, HostAdapterConnector.class));
        assertFalse(hasPublicConstructor(CorePluginContext.class, CorePluginContext.Dependencies.class, RuntimeHostAdapters.class));
        assertFalse(Modifier.isPublic(HostAdapterConnector.class.getModifiers()));
        assertFalse(Modifier.isPublic(HostAdapterConnection.class.getModifiers()));
        assertTrue(RuntimeHostAdapterAccess.class.isSealed());
        assertTrue(Arrays.equals(
            new Class<?>[]{HostSession.class, SessionRuntimeHostAdapterAccess.class},
            RuntimeHostAdapterAccess.class.getPermittedSubclasses()
        ));
        assertFalse(Modifier.isPublic(SessionRuntimeHostAdapterAccess.class.getModifiers()));
    }

    private static boolean hasPublicConstructor(
        final Class<?> type,
        final Class<?>... parameterTypes
    ) {
        return Arrays.stream(type.getConstructors())
            .map(Constructor::getParameterTypes)
            .anyMatch(parameters -> Arrays.equals(parameters, parameterTypes));
    }
}
