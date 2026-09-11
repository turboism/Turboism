package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeUndoIngressObserverTest {

    @Test
    void classifiesNativeEditUndoAndRedoWithoutReadingTheEventBody() {
        final Manager manager = new Manager();
        final List<NativeUndoIngressObserver.Event> observed = new ArrayList<>();
        final NativeUndoIngressObserver observer =
            new NativeUndoIngressObserver(resolver(), manager, observed::add);
        observer.attach();

        assertEquals(1, manager.listenerCount());
        assertEquals(0, observer.drain(), "attaching must not report the pre-existing state");

        final Entry admitted = new Entry("Set Parent Drawable");
        manager.commit(admitted);
        assertEquals(1, observer.notificationCount());
        assertEquals(1, observer.drain());
        assertEquals(NativeUndoIngressObserver.Kind.COMMITTED, observed.get(0).kind());
        assertEquals(Optional.of("Set Parent Drawable"), observed.get(0).label());
        assertEquals(
            Optional.of(admitted),
            observed.get(0).entry(),
            "the consumer needs the admitted entry identity, not just its label"
        );

        manager.undo();
        assertEquals(1, observer.drain());
        assertEquals(NativeUndoIngressObserver.Kind.UNDO, observed.get(1).kind());
        assertEquals(Optional.empty(), observed.get(1).label());

        manager.redo();
        assertEquals(1, observer.drain());
        assertEquals(NativeUndoIngressObserver.Kind.REDO, observed.get(2).kind());

        assertEquals(3, observed.size());
        assertEquals(0, observer.drain(), "an unchanged state must not emit again");
    }

    @Test
    void commitAfterUndoIsNotMisreportedAsRedo() {
        final Manager manager = new Manager();
        final List<NativeUndoIngressObserver.Event> observed = new ArrayList<>();
        final NativeUndoIngressObserver observer =
            new NativeUndoIngressObserver(resolver(), manager, observed::add);
        observer.attach();

        manager.commit(new Entry("First"));
        manager.commit(new Entry("Second"));
        observer.drain();
        observed.clear();

        manager.undo();
        observer.drain();
        manager.commit(new Entry("Forked"));
        observer.drain();

        assertEquals(2, observed.size());
        assertEquals(NativeUndoIngressObserver.Kind.UNDO, observed.get(0).kind());
        assertEquals(NativeUndoIngressObserver.Kind.COMMITTED, observed.get(1).kind());
        assertEquals(Optional.of("Forked"), observed.get(1).label());
        assertEquals(2, manager.entries().size(), "the redo tail must have been trimmed");
    }

    @Test
    void coalescesSeveralHostTransitionsIntoOneNetObservation() {
        final Manager manager = new Manager();
        final List<NativeUndoIngressObserver.Event> observed = new ArrayList<>();
        final NativeUndoIngressObserver observer =
            new NativeUndoIngressObserver(resolver(), manager, observed::add);
        observer.attach();

        manager.commit(new Entry("First"));
        manager.commit(new Entry("Second"));
        assertEquals(2, observer.notificationCount());
        assertEquals(1, observer.drain(), "callbacks before a drain collapse into one observation");
        assertEquals(Optional.of("Second"), observed.get(0).label());
    }

    @Test
    void clearingTheWholeHistoryIsNotReportedAsAUserEdit() {
        final Manager manager = new Manager();
        final List<NativeUndoIngressObserver.Event> observed = new ArrayList<>();
        final NativeUndoIngressObserver observer =
            new NativeUndoIngressObserver(resolver(), manager, observed::add);
        observer.attach();

        manager.commit(new Entry("First"));
        observer.drain();
        observed.clear();

        manager.clear();
        assertEquals(0, observer.drain(), "dropping the whole history is not a native edit");
        assertTrue(observed.isEmpty());
    }

    @Test
    void sinkFailuresAreRaisedToTheOwnerAndNotIntoTheHost() {
        final Manager manager = new Manager();
        final NativeUndoIngressObserver observer =
            new NativeUndoIngressObserver(resolver(), manager, ignored -> {
                throw new IllegalStateException("sink failure");
            });
        observer.attach();

        // The host fires listeners inline without exception isolation: this must not throw.
        manager.commit(new Entry("First"));
        assertEquals(1, observer.notificationCount());
        assertEquals(0, observer.callbackFailureCount());

        // The sink belongs to the owner, so its failure is raised where the owner can see it.
        assertThrows(IllegalStateException.class, observer::drain);
    }

    @Test
    void closeDetachesTheListenerAndIgnoresLaterCallbacks() {
        final Manager manager = new Manager();
        final List<NativeUndoIngressObserver.Event> observed = new ArrayList<>();
        final NativeUndoIngressObserver observer =
            new NativeUndoIngressObserver(resolver(), manager, observed::add);
        observer.attach();
        assertEquals(1, manager.listenerCount());

        observer.close();
        observer.close();

        assertEquals(0, manager.listenerCount());
        manager.commit(new Entry("After close"));
        assertEquals(0, observer.notificationCount(), "the listener is gone, so nothing is delivered");
        assertEquals(0, observer.drain());
        assertTrue(observed.isEmpty());
    }

    @Test
    void aCallbackThatArrivesAfterCloseIsRejectedInsteadOfReachingTheSink() {
        final Manager manager = new Manager();
        final List<NativeUndoIngressObserver.Event> observed = new ArrayList<>();
        final NativeUndoIngressObserver observer =
            new NativeUndoIngressObserver(resolver(), manager, observed::add);
        observer.attach();
        // The host may keep the listener until the document closes, so a callback can still arrive
        // after the observer has been closed; that must not reach the sink.
        manager.refuseRemoval();

        observer.close();
        manager.commit(new Entry("After close"));

        assertEquals(1, manager.listenerCount());
        assertEquals(1, observer.rejectedWhileClosedCount());
        assertEquals(0, observer.drain());
        assertTrue(observed.isEmpty());
    }

    @Test
    void attachFailsClosedWhenTheListenerSelectorsAreNotAdmitted() {
        final Manager manager = new Manager();
        final NativeUndoIngressObserver observer =
            new NativeUndoIngressObserver(resolverWithoutIngress(), manager, ignored -> { });

        assertThrows(RuntimeException.class, observer::attach);
        assertEquals(0, manager.listenerCount());
    }

    static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-history.read"),
            List.of(
                method("cubism.editor-history.manager.entries", Manager.class, "entries", "()Ljava/util/List;"),
                method("cubism.editor-history.manager.position", Manager.class, "position", "()I"),
                StaticSelector.classSelector(
                    "cubism.editor-history.entry.class", internal(Entry.class)
                ),
                method(
                    "cubism.editor-history.entry.presentation-name",
                    Entry.class,
                    "presentationName",
                    "()Ljava/lang/String;"
                ),
                StaticSelector.classSelector(
                    NativeUndoIngressObserver.LISTENER_CLASS_ALIAS, internal(Listener.class)
                ),
                method(
                    NativeUndoIngressObserver.LISTENER_ADD_ALIAS,
                    Manager.class,
                    "addUndoStateChangeListener",
                    "(L" + internal(Listener.class) + ";)V"
                ),
                method(
                    NativeUndoIngressObserver.LISTENER_REMOVE_ALIAS,
                    Manager.class,
                    "removeUndoStateChangeListener",
                    "(L" + internal(Listener.class) + ";)V"
                )
            ),
            Manager.class.getClassLoader()
        );
    }

    private static VerifiedMemberResolver resolverWithoutIngress() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-history.read"),
            List.of(
                method("cubism.editor-history.manager.entries", Manager.class, "entries", "()Ljava/util/List;"),
                method("cubism.editor-history.manager.position", Manager.class, "position", "()I")
            ),
            Manager.class.getClassLoader()
        );
    }

    static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC
        );
    }

    static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** The verified single-abstract-method host listener shape. */
    public interface Listener {
        void onStateChanged(Object event);
    }

    /** Test double for {@code com.live2d.undo.CUndoManager}. */
    public static final class Manager {
        private final List<Entry> entries = new ArrayList<>();
        private final List<Listener> listeners = new ArrayList<>();
        private int position;
        private boolean refusesRemoval;

        public List<Entry> entries() {
            return entries;
        }

        public int position() {
            return position;
        }

        public void addUndoStateChangeListener(final Listener listener) {
            listeners.add(listener);
        }

        public void removeUndoStateChangeListener(final Listener listener) {
            if (refusesRemoval) return;
            listeners.remove(listener);
        }

        /** Simulates a host that keeps its listeners until the document closes. */
        public void refuseRemoval() {
            refusesRemoval = true;
        }

        /** Mirrors {@code addEdit}: trims the redo tail, appends, then notifies. */
        public void commit(final Entry entry) {
            if (position < entries.size()) {
                entries.subList(position, entries.size()).clear();
            }
            entries.add(entry);
            position++;
            fire();
        }

        /** Mirrors {@code undo()}. */
        public void undo() {
            position--;
            fire();
        }

        /** Mirrors {@code redo()}. */
        public void redo() {
            position++;
            fire();
        }

        /** Mirrors {@code clear()}. */
        public void clear() {
            entries.clear();
            position = 0;
            fire();
        }

        public int listenerCount() {
            return listeners.size();
        }

        private void fire() {
            // The host iterates its listener list inline with no exception isolation.
            for (final Listener listener : List.copyOf(listeners)) {
                listener.onStateChanged(new Object());
            }
        }
    }

    /** Test double for one {@code com.live2d.undo.ACUndoable}. */
    public static class Entry {
        private final String label;

        Entry(final String label) {
            this.label = label;
        }

        public String presentationName() {
            return label;
        }
    }
}
