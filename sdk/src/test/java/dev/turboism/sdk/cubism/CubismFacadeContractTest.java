package dev.turboism.sdk.cubism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.PluginContext;
import java.lang.reflect.Method;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CubismFacadeContractTest {

    @Test
    void pluginContextExposesCubismFacadeAccessors() throws Exception {
        final Method accessor = PluginContext.class.getMethod("cubism");

        assertEquals(CubismFacade.class, accessor.getReturnType());
        assertTrue(Modifier.isAbstract(accessor.getModifiers()));
    }

    @Test
    void cubismFacadeContractUsesSdkSnapshotTypes() throws Exception {
        assertEquals(CubismRuntimeSnapshot.class, CubismFacade.class.getMethod("runtime").getReturnType());
        assertEquals(Optional.class, CubismFacade.class.getMethod("activeProject").getReturnType());
        assertEquals(Optional.class, CubismFacade.class.getMethod("activeDocument").getReturnType());
        assertEquals(Optional.class, CubismFacade.class.getMethod("activeModel").getReturnType());
        assertEquals(boolean.class, CubismFacade.class.getMethod("hasActiveProject").getReturnType());
        assertEquals(boolean.class, CubismFacade.class.getMethod("hasActiveDocument").getReturnType());
        assertEquals(boolean.class, CubismFacade.class.getMethod("hasActiveModel").getReturnType());
        assertEquals(boolean.class, CubismFacade.class.getMethod("isHostPresent").getReturnType());
    }

    @Test
    void noOpFacadeReturnsEmptySnapshotsAndFalsePresence() {
        final CubismFacade facade = new NoOpCubismFacade();

        assertFalse(facade.isHostPresent());
        assertFalse(facade.hasActiveProject());
        assertFalse(facade.hasActiveDocument());
        assertFalse(facade.hasActiveModel());
        assertEquals(Optional.empty(), facade.activeProject());
        assertEquals(Optional.empty(), facade.activeDocument());
        assertEquals(Optional.empty(), facade.activeModel());
        assertSame(Optional.empty(), facade.activeProject());
    }

    private static final class NoOpCubismFacade implements CubismFacade {

        @Override
        public CubismRuntimeSnapshot runtime() {
            return new CubismRuntimeSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            return Optional.empty();
        }

        @Override
        public boolean isHostPresent() {
            return false;
        }

        @Override
        public TransactionManager transactionManager() {
            return null;
        }
    }
}
