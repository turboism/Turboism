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

class VerifiedTextureAtlasAutoLayoutHookInstallerTest {

    @Test
    void requiresDedicatedAuthorizationAndRestoresTheRetransformedClassOnClose() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(calls);

        assertThrows(IllegalArgumentException.class, () ->
            VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
                instrumentation,
                resolver(Set.of("cubism.texture-atlas.layout.write")),
                Target.class.getClassLoader()
            )
        );
        assertEquals(List.of(), calls);

        try (VerifiedTextureAtlasAutoLayoutHookInstaller installer =
                 VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
                     instrumentation,
                     resolver(
                         "5.3.02",
                         VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                         Set.of(VerifiedTextureAtlasAutoLayoutHookInstaller.CAPABILITY_ID)
                     ),
                     Target.class.getClassLoader()
                 )) {
            installer.install();
        }

        assertEquals(List.of(
            "add:true",
            "add:true",
            "retransform:" + Target.class.getName(),
            "retransform:" + Target.class.getName(),
            "remove",
            "remove",
            "retransform:" + Target.class.getName(),
            "retransform:" + Target.class.getName()
        ), calls);
    }

    @Test
    void admitsExact52AndRejectsUnsupportedVersion() {
        final Instrumentation instrumentation = instrumentation(new ArrayList<>());
        VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
            instrumentation,
            resolver(
                "5.2.0",
                VerifiedCubism520TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                Set.of(VerifiedTextureAtlasAutoLayoutHookInstaller.CAPABILITY_ID)
            ),
            Target.class.getClassLoader()
        ).close();

        assertThrows(IllegalArgumentException.class, () ->
            VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
                instrumentation,
                resolver(
                    "5.3.01",
                    VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                    Set.of(VerifiedTextureAtlasAutoLayoutHookInstaller.CAPABILITY_ID)
                ),
                Target.class.getClassLoader()
            )
        );
    }

    @Test
    void rejectsAuthorizedVoidUiEntry() {
        final Instrumentation instrumentation = instrumentation(new ArrayList<>());
        final String owner = Target.class.getName().replace('.', '/');
        final VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
            "5.3.02",
            VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            Set.of(VerifiedTextureAtlasAutoLayoutHookInstaller.CAPABILITY_ID),
            List.of(StaticSelector.method(
                VerifiedTextureAtlasAutoLayoutHookInstaller.AUTO_LAYOUT_ALIAS,
                owner,
                "openDialog",
                "(Ljava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC
            )),
            Target.class.getClassLoader()
        );

        assertThrows(IllegalArgumentException.class, () ->
            VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
                instrumentation,
                resolver,
                Target.class.getClassLoader()
            )
        );
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
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.method(
            VerifiedTextureAtlasAutoLayoutHookInstaller.AUTO_LAYOUT_ALIAS,
            owner,
            "a",
            "(Ljava/lang/Object;)Z",
            StaticSelector.ACCESS_PUBLIC
        ));
        for (String alias : version.equals("5.2.0")
            ? VerifiedCubism520TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES
            : VerifiedCubism5302TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES) {
            selectors.add(StaticSelector.classSelector(alias, owner));
        }
        for (String alias : version.equals("5.2.0")
            ? VerifiedCubism520TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES
            : VerifiedCubism5302TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES) {
            if (alias.equals(VerifiedTextureAtlasAutoLayoutHookInstaller.DIALOG_INIT_ALIAS)) {
                selectors.add(StaticSelector.method(
                    alias, owner, "openDialog", "(Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC
                ));
            } else {
                selectors.add(StaticSelector.classSelector(alias, owner));
            }
        }
        return TestVerifiedResolvers.create(
            version,
            adapterSliceId,
            capabilities,
            selectors,
            Target.class.getClassLoader()
        );
    }

    private Instrumentation instrumentation(final List<String> calls) {
        return (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "addTransformer" -> { calls.add("add:" + arguments[1]); yield null; }
                case "getAllLoadedClasses" -> new Class<?>[]{Target.class};
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
        public boolean a(final Object cancellation) { return false; }
        public void openDialog(final Object settings) { }
    }
}
