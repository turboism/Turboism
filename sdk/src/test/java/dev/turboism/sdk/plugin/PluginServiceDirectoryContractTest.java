package dev.turboism.sdk.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@link PluginServiceDirectory} contract: the directory every context exposes through
 * {@link PluginContext#services()} bridges each {@link PluginService} member to the matching
 * optional accessor — installed members resolve to that accessor's instance, absent members
 * resolve to {@code null}, and {@link PluginService#type()} names the accessor's return type.
 */
class PluginServiceDirectoryContractTest {

    private final PluginContext context = new PluginContext() {
        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return null;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return null;
        }

        @Override
        public ActionRegistry actions() {
            return null;
        }

        @Override
        public MenuRegistry menus() {
            return null;
        }

        @Override
        public UiScheduler uiScheduler() {
            return null;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return null;
        }
    };

    @Test
    void everyMemberTypeNamesItsAccessorReturnType() throws Exception {
        for (PluginService service : PluginService.values()) {
            if (service == PluginService.MESH_TOOLS) {
                assertThrows(NoSuchMethodException.class, () -> PluginContext.class.getDeclaredMethod("meshTools"));
                assertSame(dev.turboism.sdk.cubism.mesh.MeshToolRegistry.class, service.type());
                continue;
            }
            if (service == PluginService.MODELING_TOOLS) {
                assertThrows(NoSuchMethodException.class, () -> PluginContext.class.getDeclaredMethod("modelingTools"));
                assertSame(dev.turboism.sdk.cubism.modeling.ModelingToolRegistry.class, service.type());
                continue;
            }
            final Method accessor = PluginContext.class.getDeclaredMethod(accessorName(service.name()));
            assertSame(
                    accessor.getReturnType(),
                    service.type(),
                    "PluginService." + service.name() + ".type() must name " + accessor.getName() + "()'s return type");
        }
    }

    @Test
    void absentServicesResolveToNullNotTheUnavailableSentinel() {
        final PluginServiceDirectory directory = context.services();
        assertTrue(directory.installed().isEmpty());
        for (PluginService service : PluginService.values()) {
            assertNull(
                    directory.get(service.type()),
                    service + " resolved on a context exposing only unavailable sentinels");
            assertNull(service.resolve(context), service + ".resolve() must return null for the unavailable sentinel");
        }
    }

    @Test
    void unknownServiceTypesResolveToNull() {
        assertNull(context.services().get(PluginServiceDirectory.class));
    }

    @Test
    void requireThrowsStructuredExceptionForAbsentServices() {
        final PluginServiceDirectory directory = context.services();
        for (PluginService service : PluginService.values()) {
            final PluginServiceUnavailableException failure = assertThrows(
                    PluginServiceUnavailableException.class,
                    () -> directory.require(service.type()),
                    service + ".require() must fail structurally on an empty directory");
            assertSame(service.type(), failure.serviceType(), service + " failure must carry the requested type");
            assertSame(service, failure.service(), service + " failure must carry the catalog member");
        }
    }

    @Test
    void requireReturnsInstalledServices() {
        final PluginServiceDirectory directory = new PluginServiceDirectory() {
            @Override
            public java.util.Set<PluginService> installed() {
                return java.util.Set.of();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(final Class<T> serviceType) {
                return serviceType == UiScheduler.class
                        ? (T) new UiScheduler() {
                            @Override
                            public dev.turboism.sdk.plugin.Registration runOnUiThread(final Runnable work) {
                                return null;
                            }

                            @Override
                            public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(
                                    final Runnable work, final java.time.Duration delay) {
                                return null;
                            }
                        }
                        : null;
            }
        };
        final UiScheduler scheduler = directory.require(UiScheduler.class);
        assertTrue(scheduler != null);
        final PluginServiceUnavailableException failure = assertThrows(
                PluginServiceUnavailableException.class, () -> directory.require(PluginServiceDirectory.class));
        assertEquals(PluginServiceDirectory.class, failure.serviceType());
        assertNull(failure.service());
    }

    private static String accessorName(final String memberName) {
        final StringBuilder name = new StringBuilder();
        for (String segment : memberName.toLowerCase(Locale.ROOT).split("_")) {
            if (name.isEmpty()) {
                name.append(segment);
            } else {
                name.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
            }
        }
        return name.toString();
    }
}
