package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MeshToolCoordinatorReentrancyTest {
    @Test
    void activationCallbackCanReadModelAndQueueAnotherActivationWithoutCorruptingOrder() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        List<String> calls = new ArrayList<>();
        coordinator.register(
                "plugin",
                1L,
                new MeshTool() {
                    @Override
                    public String id() {
                        return "a";
                    }

                    @Override
                    public String label() {
                        return "A";
                    }

                    @Override
                    public String iconResourcePath() {
                        return "icons/a.png";
                    }

                    @Override
                    public void activate(MeshToolContext context) {
                        calls.add("activate:a");
                        context.editor().selection();
                        coordinator.activate("plugin", 1L, "b");
                    }

                    @Override
                    public void deactivate() {
                        calls.add("deactivate:a");
                    }
                },
                allow());
        coordinator.register("plugin", 1L, tool("b", calls), allow());
        coordinator.beginSession(new Session(1L));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> coordinator.activate("plugin", 1L, "a"));

        assertEquals(List.of("activate:a", "deactivate:a", "activate:b"), calls);
        assertEquals("b", coordinator.activeTool().orElseThrow().tool().id());
    }

    @Test
    void activationCallbackCanCloseItsOwnRegistrationAndDeactivatesAtMostOnce() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        List<String> calls = new ArrayList<>();
        AtomicReference<Registration> registration = new AtomicReference<>();
        registration.set(coordinator.register(
                "plugin",
                1L,
                new MeshTool() {
                    @Override
                    public String id() {
                        return "self";
                    }

                    @Override
                    public String label() {
                        return "Self";
                    }

                    @Override
                    public String iconResourcePath() {
                        return "icons/self.png";
                    }

                    @Override
                    public void activate(MeshToolContext context) {
                        calls.add("activate");
                        registration.get().close();
                        registration.get().close();
                    }

                    @Override
                    public void deactivate() {
                        calls.add("deactivate");
                    }
                },
                allow()));
        coordinator.beginSession(new Session(1L));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> coordinator.activate("plugin", 1L, "self"));

        assertEquals(List.of("activate", "deactivate"), calls);
        assertTrue(coordinator.activeTool().isEmpty());
        assertTrue(coordinator.snapshot().isEmpty());
    }

    @Test
    void throwingActivationAfterQueuedReplacementPreservesReplacementState() {
        MeshToolCoordinator coordinator = new MeshToolCoordinator();
        List<String> calls = new ArrayList<>();
        coordinator.register(
                "plugin",
                1L,
                new MeshTool() {
                    @Override
                    public String id() {
                        return "bad";
                    }

                    @Override
                    public String label() {
                        return "Bad";
                    }

                    @Override
                    public String iconResourcePath() {
                        return "icons/bad.png";
                    }

                    @Override
                    public void activate(MeshToolContext context) {
                        calls.add("activate:bad");
                        coordinator.activate("plugin", 1L, "good");
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public void deactivate() {
                        calls.add("deactivate:bad");
                    }
                },
                allow());
        coordinator.register("plugin", 1L, tool("good", calls), allow());
        coordinator.beginSession(new Session(1L));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> coordinator.activate("plugin", 1L, "bad"));

        assertEquals("good", coordinator.activeTool().orElseThrow().tool().id());
        assertEquals(List.of("activate:bad", "activate:good"), calls);
    }

    private static dev.turboism.permissions.PermissionChecker allow() {
        return (permission, operation) -> {};
    }

    private static MeshTool tool(String id, List<String> calls) {
        return new MeshTool() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public String iconResourcePath() {
                return "icons/" + id + ".png";
            }

            @Override
            public void activate(MeshToolContext context) {
                calls.add("activate:" + id);
            }

            @Override
            public void deactivate() {
                calls.add("deactivate:" + id);
            }
        };
    }

    private static final class Session implements MeshToolCoordinator.Session {
        private final long generation;

        private Session(long generation) {
            this.generation = generation;
        }

        @Override
        public long hostGeneration() {
            return generation;
        }

        @Override
        public boolean revalidate() {
            return true;
        }

        @Override
        public dev.turboism.sdk.cubism.model.Drawable drawable() {
            return null;
        }

        @Override
        public VertexSelection selection() {
            return VertexSelection.empty();
        }

        @Override
        public void select(VertexSelection selection, SelectionMode mode) {}
    }
}
