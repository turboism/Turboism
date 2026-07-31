package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.plugin.context.CorePluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSessionCompositionApiTest {

    @Test
    void publicCompositionCannotInjectArbitraryConnectorOrAdapterBundle() throws ReflectiveOperationException {
        assertEquals(
            Set.of(signature(HostInstanceSource.class)),
            publicConstructorSignatures(HostSession.class)
        );
        assertEquals(Set.of(signature()), publicConstructorSignatures(HostRuntimeIngress.class));
        assertConstructorIsNonPublic(
            HostSession.class.getDeclaredConstructor(HostInstanceSource.class, HostAdapterConnector.class)
        );
        assertConstructorIsNonPublic(HostRuntimeIngress.class.getDeclaredConstructor(Function.class));
        for (Constructor<?> constructor : VerifiedHostAdapterConnector.class.getDeclaredConstructors()) {
            assertConstructorIsNonPublic(constructor);
        }
        assertFalse(hasPublicConstructor(CorePluginContext.class, CorePluginContext.Dependencies.class, RuntimeHostAdapters.class));
        assertFalse(Modifier.isPublic(HostAdapterConnector.class.getModifiers()));
        assertFalse(Modifier.isPublic(HostAdapterConnection.class.getModifiers()));
        assertTrue(RuntimeHostAdapterAccess.class.isSealed());
        assertFalse(AutoCloseable.class.isAssignableFrom(RuntimeHostAdapterAccess.class));
        assertEquals(
            Set.of(HostSession.class, SessionRuntimeHostAdapterAccess.class),
            Set.of(RuntimeHostAdapterAccess.class.getPermittedSubclasses())
        );
        assertEquals(
            1,
            Arrays.stream(RuntimeHostAdapterAccess.class.getPermittedSubclasses())
                .filter(AutoCloseable.class::isAssignableFrom)
                .count()
        );
        assertTrue(AutoCloseable.class.isAssignableFrom(HostSession.class));
        assertFalse(Modifier.isPublic(SessionRuntimeHostAdapterAccess.class.getModifiers()));

        assertEquals(
            Set.of(
                "refresh():dev.turboism.adapter.host.HostSession$State",
                "state():dev.turboism.adapter.host.HostSession$State",
                "lastFailure():java.util.Optional",
                "adapters():dev.turboism.adapter.RuntimeHostAdapters",
                "modelAccess():dev.turboism.sdk.cubism.model.CubismModelAccess",
                "parameterLifecycle():dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator",
                "partLifecycle():dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator",
                "editorObjectLifecycle():dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator",
                "physicsEditorCoordinator():dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator",
                "controlAppearanceCoordinator():dev.turboism.ui.appearance.control.ControlAppearanceCoordinator",
                "editorUiLifecycle():dev.turboism.ui.host.EditorUiHostLifecycle",
                "editorUiContributions():dev.turboism.ui.contribution.EditorUiContributionAuthority",
                "embeddedPanelActivation():dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator",
                "editorUiActionRouter():dev.turboism.ui.action.RuntimeEditorUiActionRouter",
                "editorUiPluginResources():dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry",
                "dockMaintenance():dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator",
                "appearanceCoordinator():dev.turboism.ui.appearance.AppearanceCoordinator",
                "sceneTable():dev.turboism.sdk.ui.table.SceneTableService",
                "editorModelResolver():dev.turboism.mapping.verification.VerifiedMemberResolver",
                "boundingBoxOverlayResolver():java.util.Optional",
                "adapterAccess():dev.turboism.adapter.host.RuntimeHostAdapterAccess",
                "close():void"
            ),
            publicMethodSignatures(HostSession.class)
        );
        assertEquals(
            Set.of(
                "publish(dev.turboism.adapter.host.HostInstanceDescriptor):dev.turboism.adapter.host.HostSession$State",
                "clear():dev.turboism.adapter.host.HostSession$State",
                "state():dev.turboism.adapter.host.HostSession$State",
                "lastFailure():java.util.Optional",
                "adapters():dev.turboism.adapter.RuntimeHostAdapters",
                "modelAccess():dev.turboism.sdk.cubism.model.CubismModelAccess",
                "editorModelResolver():dev.turboism.mapping.verification.VerifiedMemberResolver",
                "adapterAccess():dev.turboism.adapter.host.RuntimeHostAdapterAccess",
                "close():void"
            ),
            publicMethodSignatures(HostRuntimeIngress.class)
        );
        assertExactAdapterAccessReturn(HostSession.class);
        assertExactAdapterAccessReturn(HostRuntimeIngress.class);
    }

    private static Set<String> publicConstructorSignatures(final Class<?> owner) {
        return Arrays.stream(owner.getConstructors())
            .map(Constructor::getParameterTypes)
            .map(HostSessionCompositionApiTest::signature)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> publicMethodSignatures(final Class<?> owner) {
        return Arrays.stream(owner.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(method -> method.getName() + signature(method.getParameterTypes()) + ":" + method.getReturnType().getName())
            .collect(Collectors.toUnmodifiableSet());
    }

    private static String signature(final Class<?>... parameterTypes) {
        return Arrays.stream(parameterTypes)
            .map(Class::getName)
            .collect(Collectors.joining(",", "(", ")"));
    }

    private static void assertExactAdapterAccessReturn(final Class<?> owner) throws NoSuchMethodException {
        final Method method = owner.getDeclaredMethod("adapterAccess");
        assertEquals(RuntimeHostAdapterAccess.class, method.getReturnType());
    }

    private static void assertConstructorIsNonPublic(final Constructor<?> constructor) {
        assertFalse(Modifier.isPublic(constructor.getModifiers()), () -> constructor + " must not be public");
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
