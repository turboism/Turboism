package dev.turboism.sdk.cubism.recentfile;

import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecentFileServiceContractTest {

    @Test
    void pluginContextDefaultsToTheTypedUnavailableRecentFileSingleton() throws Exception {
        final Method accessor = Arrays.stream(PluginContext.class.getMethods())
            .filter(method -> method.getName().equals("recentFiles"))
            .findFirst()
            .orElseThrow();

        assertTrue(accessor.isDefault());
        assertEquals(RecentFileService.class, accessor.getReturnType());
        assertEquals(0, accessor.getParameterCount());

        final PluginContext context = (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[]{PluginContext.class},
            (proxy, method, args) -> method.isDefault()
                ? invokeDefault(proxy, method, args)
                : null
        );
        assertSame(RecentFileService.unavailable(), context.recentFiles());

        assertEquals(
            List.of("list", "unavailable"),
            Arrays.stream(RecentFileService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList()
        );
        assertEquals(List.class, RecentFileService.class.getDeclaredMethod("list").getReturnType());

        final Instant modified = Instant.parse("2026-08-05T00:00:00Z");
        final RecentFileSummary summary = new RecentFileSummary(
            new RecentFileId("recent-1"),
            "model.cmo3",
            Optional.of(modified),
            Optional.of("C:/models/demo/model.cmo3")
        );
        assertEquals("recent-1", summary.id().value());
        assertEquals("model.cmo3", summary.displayName());
        assertEquals(Optional.of(modified), summary.lastModified());
        assertEquals(Optional.of("C:/models/demo/model.cmo3"), summary.path());
        // Missing files are represented by empty optionals, never fabricated values.
        final RecentFileSummary missing = new RecentFileSummary(new RecentFileId("recent-2"), "gone.cmo3");
        assertTrue(missing.lastModified().isEmpty());
        assertTrue(missing.path().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new RecentFileId(" "));
        assertThrows(IllegalArgumentException.class, () -> new RecentFileSummary(
            new RecentFileId("recent-3"), " "
        ));
        assertThrows(NullPointerException.class, () -> new RecentFileSummary(
            new RecentFileId("recent-4"), "model.cmo3", null, Optional.empty()
        ));
        assertThrows(NullPointerException.class, () -> new RecentFileSummary(
            new RecentFileId("recent-5"), "model.cmo3", Optional.empty(), null
        ));

        final Set<String> forbidden = Set.of(
            Path.class.getName(),
            "java.io.File",
            "com.live2d.",
            "dev.turboism.adapter.",
            "dev.turboism.core."
        );
        for (Method method : RecentFileService.class.getMethods()) {
            assertAllowed(method.getReturnType(), forbidden, method.toGenericString());
            for (Class<?> parameter : method.getParameterTypes()) {
                assertAllowed(parameter, forbidden, method.toGenericString());
            }
            for (String prefix : forbidden) {
                assertFalse(method.toGenericString().contains(prefix));
            }
        }
    }

    private static void assertAllowed(
        final Class<?> type,
        final Set<String> forbidden,
        final String source
    ) {
        final String name = type.getName();
        for (String value : forbidden) {
            assertFalse(name.equals(value) || name.startsWith(value), source);
        }
    }

    private static Object invokeDefault(
        final Object proxy,
        final Method method,
        final Object[] args
    ) throws Throwable {
        final java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(
            method.getDeclaringClass(),
            java.lang.invoke.MethodHandles.lookup()
        );
        return lookup.unreflectSpecial(method, method.getDeclaringClass())
            .bindTo(proxy)
            .invokeWithArguments(args == null ? new Object[0] : args);
    }
}
