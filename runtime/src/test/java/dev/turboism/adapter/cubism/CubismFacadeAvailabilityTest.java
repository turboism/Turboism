package dev.turboism.adapter.cubism;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutProvider;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.permission.PluginPermission;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Availability-probe contract for the permission-checked facade views and the
 * runtime's own unavailable implementations (sdk-contract-convergence review
 * follow-up): every view forwards {@code delegate.isAvailable()} instead of
 * inheriting the default {@code true}, and every fail-closed implementation
 * reports {@code false}.
 */
class CubismFacadeAvailabilityTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void facadeViewsForwardUnavailableDelegates() {
        final CubismFacadeImpl facade = facade(
                CubismModelAccess.unavailable(),
                dev.turboism.sdk.cubism.core.CoreRuntimeInfo.unavailable(),
                new TextureAtlasLayoutCoordinator(),
                new TextureAtlasNativeInvocationCoordinator(),
                () -> true,
                new RuntimeTextureAtlasEditorUi(),
                RuntimeTextureAtlasEditorSession.unavailable(),
                new RuntimeTextureAtlasLayoutAlgorithmRegistry());

        assertFalse(facade.model().isAvailable(), "model view must forward the unavailable delegate");
        assertFalse(facade.coreRuntime().isAvailable(), "core runtime view must forward the unavailable delegate");
        assertFalse(
                facade.textureAtlasLayouts().isAvailable(),
                "layout view must report false while no provider is connected");
        assertFalse(
                facade.textureAtlasPolygonLayouts().isAvailable(),
                "polygon layout view must forward the layout delegate's availability");
        assertFalse(
                facade.textureAtlasEditorSession().isAvailable(),
                "editor session view must forward the detached session");
        assertTrue(
                facade.textureAtlasAlgorithms().isAvailable(),
                "the registry view is a live runtime registry and stays available");
    }

    @Test
    void facadeViewsForwardAvailableDelegates() {
        final TextureAtlasLayoutCoordinator coordinator = new TextureAtlasLayoutCoordinator();
        coordinator.connect(new TextureAtlasLayoutProvider() {
            @Override
            public Optional<dev.turboism.adapter.cubism.textureatlas.TextureAtlasAuthoringState> current() {
                return Optional.empty();
            }

            @Override
            public ApplyOutcome apply(
                    final dev.turboism.adapter.cubism.textureatlas.TextureAtlasAuthoringState expected,
                    final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan) {
                return ApplyOutcome.REJECTED;
            }
        });
        final RuntimeTextureAtlasEditorSession session =
                new RuntimeTextureAtlasEditorSession(() -> new RuntimeTextureAtlasEditorSession.GenerationBinding(
                        1,
                        dev.turboism.mapping.verification.TestVerifiedResolvers.create(
                                "slice",
                                java.util.Set.of("test.capability"),
                                List.of(dev.turboism.mapping.verification.StaticSelector.classSelector(
                                        "test.ui", Object.class.getName().replace('.', '/'))),
                                getClass().getClassLoader()),
                        new Object()));
        final CubismFacadeImpl facade = facade(
                () -> {
                    throw new UnsupportedOperationException();
                },
                new dev.turboism.sdk.cubism.core.CoreRuntimeInfo() {
                    @Override
                    public dev.turboism.sdk.cubism.core.CoreVersion version() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public dev.turboism.sdk.cubism.core.MocInspector mocInspector() {
                        throw new UnsupportedOperationException();
                    }
                },
                coordinator,
                new TextureAtlasNativeInvocationCoordinator(),
                () -> true,
                new RuntimeTextureAtlasEditorUi(),
                session,
                new RuntimeTextureAtlasLayoutAlgorithmRegistry());

        assertTrue(facade.model().isAvailable(), "model view must forward a live delegate");
        assertTrue(facade.coreRuntime().isAvailable(), "core runtime view must forward a live delegate");
        assertTrue(facade.textureAtlasLayouts().isAvailable(), "layout view must report a connected provider");
        assertTrue(facade.textureAtlasPolygonLayouts().isAvailable(), "polygon view must follow the layout delegate");
        assertTrue(facade.textureAtlasEditorSession().isAvailable(), "session view must report a live binding");
        assertTrue(facade.textureAtlasEditorUi().isAvailable(), "editor UI view must report an open surface");
    }

    @Test
    void editorUiViewReportsClosedDelegate() {
        final RuntimeTextureAtlasEditorUi ui = new RuntimeTextureAtlasEditorUi();
        final CubismFacadeImpl facade = facade(
                CubismModelAccess.unavailable(),
                dev.turboism.sdk.cubism.core.CoreRuntimeInfo.unavailable(),
                new TextureAtlasLayoutCoordinator(),
                new TextureAtlasNativeInvocationCoordinator(),
                () -> true,
                ui,
                RuntimeTextureAtlasEditorSession.unavailable(),
                new RuntimeTextureAtlasLayoutAlgorithmRegistry());

        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi view = facade.textureAtlasEditorUi();
        assertTrue(view.isAvailable());
        ui.close();
        assertFalse(view.isAvailable(), "closing the backing UI must flip the view's probe");
    }

    /**
     * Generic guard: every {@code CubismFacadeAdapters} factory that wraps an
     * SDK sentinel-capable interface must yield a view whose {@code isAvailable()}
     * reports {@code false} for the sentinel delegate — so a future view that
     * forgets to forward fails this test instead of silently reporting true in
     * production.
     */
    @Test
    void everySentinelBackedViewForwardsIsAvailable() throws Exception {
        final CubismFacadeImpl facade = facade(
                CubismModelAccess.unavailable(),
                dev.turboism.sdk.cubism.core.CoreRuntimeInfo.unavailable(),
                new TextureAtlasLayoutCoordinator(),
                new TextureAtlasNativeInvocationCoordinator(),
                () -> true,
                new RuntimeTextureAtlasEditorUi(),
                RuntimeTextureAtlasEditorSession.unavailable(),
                new RuntimeTextureAtlasLayoutAlgorithmRegistry());
        final List<String> violations = new ArrayList<>();
        for (final Method factory : CubismFacadeAdapters.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(factory.getModifiers())) {
                continue;
            }
            final Class<?>[] parameters = factory.getParameterTypes();
            // Only surfaces that expose the isAvailable() probe are checked:
            // interfaces without it carry no forwarding contract.
            final Method isAvailable;
            try {
                isAvailable = factory.getReturnType().getMethod("isAvailable");
            } catch (NoSuchMethodException missing) {
                continue;
            }
            if (parameters.length == 0) {
                // No-arg unavailable factories return the SDK sentinel directly.
                final Object view = factory.invoke(null);
                if (probe(view, isAvailable)) {
                    violations.add(factory.getName() + "() must return a sentinel reporting isAvailable()==false");
                }
                continue;
            }
            final Class<?> delegateType = parameters[parameters.length - 1];
            if (!delegateType.isInterface()) {
                continue;
            }
            final Method unavailable;
            try {
                unavailable = delegateType.getMethod("unavailable");
            } catch (NoSuchMethodException missing) {
                continue;
            }
            if (!Modifier.isStatic(unavailable.getModifiers())) {
                continue;
            }
            final Object delegate = unavailable.invoke(null);
            final Object[] arguments = new Object[parameters.length];
            for (int index = 0; index < parameters.length - 1; index++) {
                arguments[index] = parameters[index] == CubismFacadeImpl.class ? facade : null;
            }
            arguments[parameters.length - 1] = delegate;
            final Object view = factory.invoke(null, arguments);
            if (probe(view, isAvailable)) {
                violations.add(factory.getName() + "(" + delegateType.getSimpleName()
                        + ".unavailable()) must report isAvailable()==false");
            }
        }
        assertTrue(
                violations.isEmpty(),
                "view factories that do not forward isAvailable():\n  " + String.join("\n  ", violations));
    }

    private static boolean probe(final Object view, final Method isAvailable) throws Exception {
        isAvailable.setAccessible(true);
        return Boolean.TRUE.equals(isAvailable.invoke(view));
    }

    private static CubismFacadeImpl facade(
            final CubismModelAccess modelAccess,
            final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntime,
            final TextureAtlasLayoutCoordinator layouts,
            final TextureAtlasNativeInvocationCoordinator nativeInvocations,
            final java.util.function.BooleanSupplier activeScope,
            final RuntimeTextureAtlasEditorUi editorUi,
            final RuntimeTextureAtlasEditorSession editorSession,
            final RuntimeTextureAtlasLayoutAlgorithmRegistry algorithms) {
        return new CubismFacadeImpl(
                source(),
                new CubismPermissionGate(
                        "plugin.demo",
                        List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
                        ignored -> {},
                        FIXED_CLOCK),
                modelAccess,
                coreRuntime,
                new ParameterLifecycleCoordinator(),
                new PartLifecycleCoordinator(),
                layouts,
                nativeInvocations,
                new EditorObjectLifecycleCoordinator(),
                activeScope,
                editorUi,
                editorSession,
                algorithms);
    }

    private static HostSnapshotSource source() {
        return new HostSnapshotSource() {
            @Override
            public Optional<HostProject> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<HostDocument> activeDocument() {
                return Optional.empty();
            }

            @Override
            public Optional<HostModel> activeModel() {
                return Optional.empty();
            }

            @Override
            public HostSelection selection() {
                return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }

            @Override
            public boolean isHostPresent() {
                return false;
            }

            @Override
            public long invalidationToken() {
                return 0L;
            }
        };
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "test";
            }

            @Override
            public String reason() {
                return "test";
            }
        };
    }
}
