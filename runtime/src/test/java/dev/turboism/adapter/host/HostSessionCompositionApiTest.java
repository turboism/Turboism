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
            Set.of(
                signature(HostInstanceSource.class),
                signature(HostInstanceSource.class, java.util.Locale.class)
            ),
            publicConstructorSignatures(HostSession.class)
        );
        assertEquals(
            Set.of(signature(), signature(java.util.Locale.class)),
            publicConstructorSignatures(HostRuntimeIngress.class)
        );
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
                "history():dev.turboism.sdk.cubism.history.CubismHistory",
                "coreRuntimeInfo():dev.turboism.sdk.cubism.core.CoreRuntimeInfo",
                "modelAppearanceSource():dev.turboism.adapter.cubism.HostSnapshotSource",
                "parameterLifecycle():dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator",
                "partLifecycle():dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator",
                "textureAtlasLayouts():dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator",
                "textureAtlasNativeInvocations():dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator",
                "editorObjectLifecycle():dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator",
                "projectFileLifecycle():dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator",
                "editorLifecycleEvents():dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator",
                "physicsEditorCoordinator():dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator",
                "meshMirrorAxisService():dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService",
                "meshEditUiService():dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService",
                "paletteAppearanceCoordinator():dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator",
                "editorUiLifecycle():dev.turboism.ui.host.EditorUiHostLifecycle",
                "editorUiContributions():dev.turboism.ui.contribution.EditorUiContributionAuthority",
                "embeddedPanelActivation():dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator",
                "editorUiActionRouter():dev.turboism.ui.action.RuntimeEditorUiActionRouter",
                "editorUiPluginResources():dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry",
                "editorCommands():dev.turboism.adapter.cubism.command.EditorCommandAdapter",
                "dockMaintenance():dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator",
                "appearanceCoordinator():dev.turboism.ui.appearance.AppearanceCoordinator",
                "sceneTable():dev.turboism.sdk.ui.table.SceneTableService",
                "paletteFilterSink():dev.turboism.ui.filter.PaletteFilterVisibilitySink",
                "cubismLog():dev.turboism.sdk.runtime.CubismLogService",
                "workspaceCoordinator():dev.turboism.ui.workspace.WorkspaceCoordinator",
                "workspaceLayoutCoordinator():dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator",
                "editorModelResolver():dev.turboism.mapping.verification.VerifiedMemberResolver",
                "textureAtlasDataModelCapture():dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture",
                "textureAtlasEditorUi():dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi",
                "textureAtlasEditorSession():dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession",
                "textureAtlasAlgorithms():dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry",
                "boundingBoxOverlayResolver():java.util.Optional",
                "adapterAccess():dev.turboism.adapter.host.RuntimeHostAdapterAccess",
                "objectContextMenuHandler():dev.turboism.ui.context.NativeObjectContextMenuBridge$Handler",
                "parameterPointMenuHandler():dev.turboism.ui.context.NativeParameterPointContextMenuBridge$Handler",
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
                "textureAtlasDataModelCapture():dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture",
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
