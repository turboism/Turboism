package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecentPreviewContributionServiceContractTest {

    @Test
    void pluginContextDefaultsToTheTypedUnavailableRecentPreviewSingleton() throws Exception {
        final Method accessor = Arrays.stream(PluginContext.class.getMethods())
            .filter(method -> method.getName().equals("recentPreviews"))
            .findFirst()
            .orElseThrow();

        assertTrue(accessor.isDefault());
        assertEquals(RecentPreviewContributionService.class, accessor.getReturnType());
        assertEquals(0, accessor.getParameterCount());

        final PluginContext context = (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[]{PluginContext.class},
            (proxy, method, args) -> method.isDefault() ? invokeDefault(proxy, method, args) : null
        );
        assertSame(RecentPreviewContributionService.unavailable(), context.recentPreviews());

        assertEquals(
            List.of("contribute", "refresh", "unavailable"),
            Arrays.stream(RecentPreviewContributionService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList()
        );
        assertEquals(Registration.class,
            RecentPreviewContributionService.class.getDeclaredMethod("contribute", RecentPreviewRenderer.class)
                .getReturnType());
        assertTrue(
            RecentPreviewContributionService.class.getDeclaredMethod("refresh").isDefault()
        );

        // Renderer contract: functional interface taking only SDK types.
        assertTrue(RecentPreviewRenderer.class.isAnnotationPresent(java.lang.FunctionalInterface.class));
        assertEquals(Optional.class,
            RecentPreviewRenderer.class.getDeclaredMethod("render", RecentFileSummary.class).getReturnType());

        // Content/identity validation.
        final RecentFileId id = new RecentFileId("recent-1");
        final PanelView view = PanelView.column(PanelView.text("model.cmo3"));
        final RecentPreviewContent content = new RecentPreviewContent(id, view);
        assertEquals(id, content.id());
        assertThrows(NullPointerException.class, () -> new RecentPreviewContent(null, view));
        assertThrows(NullPointerException.class, () -> new RecentPreviewContent(id, null));

        // Safe mode refuses contribution and tolerates refresh.
        final RecentPreviewContributionService safe = RecentPreviewContributionService.unavailable();
        assertThrows(UnsupportedOperationException.class, () -> safe.contribute(summary -> Optional.empty()));
        safe.refresh();

        // Default refresh is a no-op on a minimal implementation.
        final AtomicBoolean refreshed = new AtomicBoolean(false);
        final RecentPreviewContributionService minimal = new RecentPreviewContributionService() {
            @Override
            public Registration contribute(final RecentPreviewRenderer renderer) {
                return () -> {
                };
            }

            @Override
            public void refresh() {
                refreshed.set(true);
            }
        };
        minimal.refresh();
        assertTrue(refreshed.get());

        final Set<String> forbidden = Set.of(
            Path.class.getName(),
            "java.io.File",
            "java.awt.",
            "javax.swing.",
            "com.live2d.",
            "dev.turboism.adapter.",
            "dev.turboism.core."
        );
        for (Class<?> type : Set.of(
            RecentPreviewContributionService.class,
            RecentPreviewRenderer.class,
            RecentPreviewContent.class
        )) {
            for (Method method : type.getMethods()) {
                for (String prefix : forbidden) {
                    assertFalse(method.toGenericString().contains(prefix), method.toGenericString());
                }
            }
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
