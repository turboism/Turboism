package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedTextureAtlasDataModelHookInstallerTest {

    @Test
    void requiresDedicatedAuthorizationAndRestoresTheRetransformedClassOnClose() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls);
        final TextureAtlasDataModelCapture capture = new TextureAtlasDataModelCapture();

        assertThrows(IllegalArgumentException.class, () ->
            VerifiedTextureAtlasDataModelHookInstaller.fromVerifiedResolver(
                instrumentation,
                resolver(Set.of("cubism.texture-atlas.layout.write")),
                Target.class.getClassLoader(),
                capture
            )
        );
        assertEquals(List.of(), calls);

        try (VerifiedTextureAtlasDataModelHookInstaller installer =
                 VerifiedTextureAtlasDataModelHookInstaller.fromVerifiedResolver(
                     instrumentation,
                     resolver(Set.of(VerifiedTextureAtlasDataModelHookInstaller.CAPABILITY_ID)),
                     Target.class.getClassLoader(),
                     capture
                 )) {
            installer.install();
        }

        assertEquals(List.of(
            "add:true",
            "retransform:" + Target.class.getName(),
            "remove",
            "retransform:" + Target.class.getName()
        ), calls);
    }

    @Test
    void restoresACompatibleTargetLoadedAfterInstallation() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Class<?>[][] loaded = {new Class<?>[0]};
        final Instrumentation instrumentation = instrumentation(calls, loaded);
        final VerifiedTextureAtlasDataModelHookInstaller installer =
            VerifiedTextureAtlasDataModelHookInstaller.fromVerifiedResolver(
                instrumentation,
                resolver(Set.of(VerifiedTextureAtlasDataModelHookInstaller.CAPABILITY_ID)),
                Target.class.getClassLoader(),
                new TextureAtlasDataModelCapture()
            );

        installer.install();
        loaded[0] = new Class<?>[]{Target.class};
        installer.close();

        assertEquals(List.of(
            "add:true",
            "remove",
            "retransform:" + Target.class.getName()
        ), calls);
    }

    @Test
    void closeRetriesAfterUnacknowledgedRemoval() throws Exception {
        final List<String> calls = new ArrayList<>();
        final int[] removals = {0};
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> { calls.add("add"); yield null; }
                case "getAllLoadedClasses" -> new Class<?>[]{Target.class};
                case "isModifiableClass" -> true;
                case "retransformClasses" -> { calls.add("retransform"); yield null; }
                case "removeTransformer" -> {
                    removals[0]++;
                    calls.add("remove:" + removals[0]);
                    yield removals[0] > 1;
                }
                default -> defaultValue(method.getReturnType());
            }
        );
        final VerifiedTextureAtlasDataModelHookInstaller installer =
            VerifiedTextureAtlasDataModelHookInstaller.fromVerifiedResolver(
                instrumentation,
                resolver(Set.of(VerifiedTextureAtlasDataModelHookInstaller.CAPABILITY_ID)),
                Target.class.getClassLoader(),
                new TextureAtlasDataModelCapture()
            );
        installer.install();

        assertThrows(IllegalStateException.class, installer::close);
        installer.close();

        assertEquals(List.of("add", "retransform", "remove:1", "remove:2", "retransform"), calls);
    }

    @Test
    void preparesAnIndependentExact5303HookProfile() {
        VerifiedTextureAtlasDataModelHookInstaller.fromVerifiedResolver(
            instrumentation(new ArrayList<>()),
            resolver(
                "5.3.03",
                VerifiedCubism5303TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                Set.of(VerifiedTextureAtlasDataModelHookInstaller.CAPABILITY_ID)
            ),
            Target.class.getClassLoader(),
            new TextureAtlasDataModelCapture()
        ).close();
    }

    private VerifiedMemberResolver resolver(final Set<String> capabilities) {
        return resolver(
            "5.3.02",
            VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            capabilities
        );
    }

    private VerifiedMemberResolver resolver(
        final String version,
        final String adapterSliceId,
        final Set<String> capabilities
    ) {
        final String owner = Target.class.getName().replace('.', '/');
        return TestVerifiedResolvers.create(
            version,
            adapterSliceId,
            capabilities,
            List.of(
                StaticSelector.classSelector(
                    "cubism.texture-atlas.model-image-list.class", owner
                ),
                StaticSelector.method(
                    "cubism.texture-atlas.model-image-list.init", owner, "initGui", "()V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.texture-atlas.model-image-list.data-model", owner,
                    "getTaeDataModel", "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC
                )
            ),
            Target.class.getClassLoader()
        );
    }

    private Instrumentation instrumentation(final List<String> calls) {
        return instrumentation(calls, new Class<?>[][]{{Target.class}});
    }

    private Instrumentation instrumentation(
        final List<String> calls,
        final Class<?>[][] loaded
    ) {
        return (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> { calls.add("add:" + arguments[1]); yield null; }
                case "getAllLoadedClasses" -> loaded[0];
                case "isModifiableClass" -> true;
                case "retransformClasses" -> {
                    calls.add("retransform:" + ((Class<?>[]) arguments[0])[0].getName());
                    yield null;
                }
                case "removeTransformer" -> { calls.add("remove"); yield true; }
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    public static final class Target {
        public void initGui() {}
        public Object getTaeDataModel() { return this; }
    }
}
