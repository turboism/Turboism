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
            "add:true",
            "retransform:" + Target.class.getName(),
            "retransform:" + Target.class.getName(),
            "retransform:" + Target.class.getName(),
            "remove",
            "remove",
            "remove",
            "retransform:" + Target.class.getName(),
            "retransform:" + Target.class.getName(),
            "retransform:" + Target.class.getName()
        ), calls);
    }

    @Test
    void admits5303AndRestoresDistinctRetransformedDialogAndStatisticsTargetsOnClose() throws Exception {
        final List<String> calls = new ArrayList<>();
        final Instrumentation instrumentation = instrumentation(
            calls,
            new Class<?>[][]{{
                Target.class, DialogTarget.class, StatisticsTarget.class
            }}
        );

        try (VerifiedTextureAtlasAutoLayoutHookInstaller installer =
                 VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
                     instrumentation,
                     resolver(
                         "5.3.03",
                         VerifiedCubism5303TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                         Set.of(VerifiedTextureAtlasAutoLayoutHookInstaller.CAPABILITY_ID),
                         DialogTarget.class.getName().replace('.', '/'),
                         StatisticsTarget.class.getName().replace('.', '/')
                     ),
                     Target.class.getClassLoader()
                 )) {
            installer.install();
        }

        assertEquals(List.of(
            "add:true",
            "add:true",
            "add:true",
            "retransform:" + Target.class.getName(),
            "retransform:" + DialogTarget.class.getName(),
            "retransform:" + StatisticsTarget.class.getName(),
            "remove",
            "remove",
            "remove",
            "retransform:" + Target.class.getName(),
            "retransform:" + DialogTarget.class.getName(),
            "retransform:" + StatisticsTarget.class.getName()
        ), calls);
    }

    @Test
    void admitsExact52AndRejectsUnsupportedVersion() {
        final Instrumentation instrumentation = instrumentation(new ArrayList<>());
        VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
            instrumentation,
            resolver(
                "5.2.03",
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
        return resolver(version, adapterSliceId, capabilities, owner, owner);
    }

    private VerifiedMemberResolver resolver(
        final String version,
        final String adapterSliceId,
        final Set<String> capabilities,
        final String dialogOwner,
        final String statisticsOwner
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
        for (String alias : nativeInvocationAliases(version)) {
            selectors.add(StaticSelector.classSelector(alias, owner));
        }
        for (String alias : dialogInjectionAliases(version)) {
            if (alias.equals(VerifiedTextureAtlasAutoLayoutHookInstaller.DIALOG_INIT_ALIAS)) {
                selectors.add(StaticSelector.method(
                    alias, dialogOwner, "openDialog", "(Ljava/lang/Object;)V",
                    StaticSelector.ACCESS_PUBLIC
                ));
            } else {
                selectors.add(StaticSelector.classSelector(alias, dialogOwner));
            }
        }
        for (String alias : statisticsAliases(version)) {
            if (alias.equals(VerifiedTextureAtlasAutoLayoutHookInstaller.STATISTICS_VIEW_INIT_ALIAS)) {
                selectors.add(StaticSelector.method(
                    alias, statisticsOwner, "openView", "(Ljava/lang/Object;)V",
                    StaticSelector.ACCESS_PUBLIC
                ));
            } else {
                selectors.add(StaticSelector.classSelector(alias, statisticsOwner));
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

    private static Set<String> nativeInvocationAliases(final String version) {
        return switch (version) {
            case "5.2.03" -> VerifiedCubism520TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES;
            case "5.3.03" -> VerifiedCubism5303TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES;
            default -> VerifiedCubism5302TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES;
        };
    }

    private static Set<String> dialogInjectionAliases(final String version) {
        return switch (version) {
            case "5.2.03" -> VerifiedCubism520TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES;
            case "5.3.03" -> VerifiedCubism5303TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES;
            default -> VerifiedCubism5302TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES;
        };
    }

    private static Set<String> statisticsAliases(final String version) {
        return switch (version) {
            case "5.2.03" -> Set.of();
            case "5.3.03" -> VerifiedCubism5303TextureAtlasSelectorContract.STATISTICS_ALIASES;
            default -> VerifiedCubism5302TextureAtlasSelectorContract.STATISTICS_ALIASES;
        };
    }

    private Instrumentation instrumentation(final List<String> calls) {
        return instrumentation(calls, new Class<?>[][]{{
            Target.class, DialogTarget.class, StatisticsTarget.class
        }});
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
        public boolean a(final Object cancellation) { return false; }
    }

    public static final class DialogTarget {
        public void openDialog(final Object settings) { }
    }

    public static final class StatisticsTarget {
        public void openView(final Object model) { }
    }
}
