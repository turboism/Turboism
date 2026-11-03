package dev.turboism.adapter.cubism.optimization.modelinstances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelInstanceCoalesceBridgeTest {

    private static final Object DOCUMENT = new Object();

    private static final class EditMode {
        private boolean editing;
        private Undo undo;
    }

    private static final class Undo {
        private int editCount;
    }

    private static final class Source {}

    private static class Host implements ModelInstanceCoalesceBridge.Host {
        private final EditMode edit = new EditMode();

        @Override
        public Object document(final Object source) {
            return DOCUMENT;
        }

        @Override
        public Object editMode(final Object document) {
            return edit;
        }

        @Override
        public boolean editing(final Object editMode) {
            return edit.editing;
        }

        @Override
        public Object currentUndo(final Object editMode) {
            return edit.undo;
        }

        @Override
        public int editCount(final Object undo) {
            return ((Undo) undo).editCount;
        }

        @Override
        public Object editDocument(final Object editMode) {
            return DOCUMENT;
        }
    }

    private static final class FailingHost extends Host {
        @Override
        public Object document(final Object source) {
            throw new IllegalStateException("unavailable");
        }
    }

    private final Host host = new Host();
    private final ModelInstanceCoalesceBridge bridge = new ModelInstanceCoalesceBridge(host, runnable -> {});
    private final Source source = new Source();

    @BeforeEach
    void install() {
        bridge.install();
    }

    @AfterEach
    void close() {
        bridge.close();
        System.getProperties().remove(ModelInstanceCoalesceBridge.ENABLE_PROPERTY);
    }

    @Test
    void exactDuplicateInsideOneEditGroupIsSkipped() {
        final Undo undo = begin(3);

        assertFalse(bridge.beforeUpdate(source));
        bridge.afterUpdate(source);
        assertTrue(bridge.beforeUpdate(source), "same group and edit count is an exact repeat");

        undo.editCount = 4;
        assertFalse(bridge.beforeUpdate(source), "a new undo entry is a new model state");
        assertEquals(1L, bridge.snapshot().get("skipped"));
    }

    @Test
    void committedGroupSuppressesThePostEndDuplicate() {
        final Undo undo = begin(5);
        assertFalse(bridge.beforeUpdate(source));
        bridge.afterUpdate(source);

        bridge.beforeCommit(host.edit);
        bridge.afterCommit(host.edit);
        host.edit.editing = false;
        host.edit.undo = null;

        assertTrue(bridge.beforeUpdate(source), "the edit just committed at the same count");
        assertEquals(1L, bridge.snapshot().get("skipped"));
        assertEquals(1L, bridge.snapshot().get("committedTokens"));
    }

    @Test
    void theEventBoundaryEndsPostCommitCoalescing() {
        bridge.close();
        final java.util.List<Runnable> boundaries = new java.util.ArrayList<>();
        final ModelInstanceCoalesceBridge scoped = new ModelInstanceCoalesceBridge(host, boundaries::add);
        scoped.install();
        try {
            begin(1);
            scoped.beforeUpdate(source);
            scoped.afterUpdate(source);
            scoped.beforeCommit(host.edit);
            scoped.afterCommit(host.edit);
            host.edit.editing = false;
            host.edit.undo = null;
            assertTrue(scoped.beforeUpdate(source));
            assertEquals(1, boundaries.size());
            boundaries.get(0).run();
            assertFalse(scoped.beforeUpdate(source), "the next event must not inherit the commit token");
        } finally {
            scoped.close();
        }
    }

    @Test
    void laterCommitOrUndoInvalidatesTheCommittedToken() {
        final Undo first = begin(2);
        bridge.beforeUpdate(source);
        bridge.afterUpdate(source);
        bridge.beforeCommit(host.edit);
        bridge.afterCommit(host.edit);
        host.edit.editing = false;
        host.edit.undo = null;

        final Undo second = begin(1);
        bridge.beforeCommit(host.edit);
        bridge.afterCommit(host.edit);
        host.edit.editing = false;
        host.edit.undo = null;
        assertFalse(bridge.beforeUpdate(source), "a newer commit must republish");

        bridge.afterUpdate(source);
        bridge.invalidate();
        assertFalse(bridge.beforeUpdate(source), "undo/redo restore a different model state");
        assertTrue(first != second);
    }

    @Test
    void editsWithoutAGroupFallThroughToNative() {
        host.edit.editing = true;
        host.edit.undo = null;
        assertFalse(bridge.beforeUpdate(source));
        assertFalse(bridge.beforeUpdate(source));
        assertEquals(0L, bridge.snapshot().get("skipped"));
    }

    @Test
    void hostFailuresFallThroughToNative() {
        bridge.close();
        final ModelInstanceCoalesceBridge failing = new ModelInstanceCoalesceBridge(new FailingHost());
        failing.install();
        try {
            assertFalse(failing.beforeUpdate(source));
            assertEquals(1L, failing.snapshot().get("failures"));
        } finally {
            failing.close();
        }
    }

    @Test
    void inheritedIncrementIsNoLongerSkippedWithoutThePreference() {
        begin(1);
        bridge.beforeUpdate(source);
        bridge.afterUpdate(source);
        System.getProperties().put(ModelInstanceCoalesceBridge.ENABLE_PROPERTY, "false");
        assertFalse(bridge.beforeUpdate(source), "disabled coalescing always runs native");
    }

    @Test
    void installOccupiesAndCloseReleasesEverySlot() {
        bridge.close();
        final ModelInstanceCoalesceBridge installed = new ModelInstanceCoalesceBridge(host);
        try {
            installed.install();
            assertThrows(IllegalStateException.class, installed::install);
            assertTrue(System.getProperties().containsKey(ModelInstanceCoalesceBridge.UPDATE_CALLBACK_PROPERTY));
            assertTrue(System.getProperties().containsKey(ModelInstanceCoalesceBridge.INVALIDATE_CALLBACK_PROPERTY));
            assertEquals(
                    Map.of(
                            "updates", 0L,
                            "skipped", 0L,
                            "committedTokens", 0L,
                            "invalidations", 0L,
                            "failures", 0L),
                    installed.snapshot());
        } finally {
            installed.close();
        }
        assertFalse(System.getProperties().containsKey(ModelInstanceCoalesceBridge.UPDATE_CALLBACK_PROPERTY));
        assertFalse(System.getProperties().containsKey(ModelInstanceCoalesceBridge.STATS_PROPERTY));
    }

    private Undo begin(final int editCount) {
        final Undo undo = new Undo();
        undo.editCount = editCount;
        host.edit.editing = true;
        host.edit.undo = undo;
        return undo;
    }
}
