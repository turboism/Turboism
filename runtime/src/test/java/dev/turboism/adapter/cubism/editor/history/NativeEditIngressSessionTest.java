package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.turboism.adapter.cubism.editor.history.NativeUndoIngressObserverTest.internal;
import static dev.turboism.adapter.cubism.editor.history.NativeUndoIngressObserverTest.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the ingress session attaches, drains on the host event thread, and detaches without
 * turning a temporary disconnect into a permanent one.
 */
class NativeEditIngressSessionTest {

    @org.junit.jupiter.api.AfterEach
    void releaseSharedHook() {
        // The hook bridge is process-wide by design, so a test must not leave it bound.
        NativeEditBeginBridge.reset();
    }

    @Test
    void aHostEditIsPublishedAfterTheDrainMovesOffTheHostThread() {
        final Fixture fixture = new Fixture();
        assertTrue(fixture.session.bind(1L, fixture.resolver));

        fixture.manager.commit(new PartMembershipEntry("BodyMesh", true));

        assertEquals(1, fixture.posted.size(), "the callback only posts, it does not drain");
        assertEquals(0, fixture.published.size(), "nothing is published inside the host listener loop");
        fixture.runPosted();

        assertEquals(1, fixture.published.size());
        assertEquals(CubismOperation.SET_HIERARCHY_PARENT, fixture.published.get(0).operation());
        assertEquals(CubismOperationOrigin.HOST_UI, fixture.published.get(0).origin());
        assertEquals(1, fixture.session.drainCount());
    }

    @Test
    void severalHostTransitionsPostAtMostOneDrain() {
        final Fixture fixture = new Fixture();
        fixture.session.bind(1L, fixture.resolver);

        fixture.manager.commit(new PartMembershipEntry("BodyMesh", true));
        fixture.manager.commit(new PartMembershipEntry("OtherMesh", true));
        fixture.manager.undo();

        assertEquals(3, fixture.session.notificationCount());
        assertEquals(1, fixture.session.drainRequestCount());
        assertEquals(1, fixture.posted.size(), "a burst of host callbacks must not post per callback");

        fixture.runPosted();
        assertEquals(1, fixture.session.drainCount());
        assertEquals(1, fixture.published.size(), "the burst collapses into one net observation");
    }

    @Test
    void rebindingToTheSameManagerAndGenerationIsANoOp() {
        final Fixture fixture = new Fixture();

        assertTrue(fixture.session.bind(1L, fixture.resolver));
        assertTrue(fixture.session.bind(1L, fixture.resolver));

        assertEquals(1, fixture.session.bindCount());
        assertEquals(1, fixture.manager.listenerCount());
    }

    @Test
    void aDocumentSwitchDetachesTheOldListenerBeforeAttachingTheNewOne() {
        final Fixture fixture = new Fixture();
        fixture.session.bind(1L, fixture.resolver);
        final NativeUndoIngressObserverTest.Manager previous = fixture.manager;

        fixture.replaceManager();
        assertTrue(fixture.session.bind(1L, fixture.resolver));

        assertEquals(2, fixture.session.bindCount());
        assertEquals(0, previous.listenerCount(), "the previous listener must be gone");
        assertEquals(1, fixture.manager.listenerCount());
    }

    @Test
    void deactivatingForATemporaryDisconnectStillAllowsARebind() {
        final Fixture fixture = new Fixture();
        fixture.session.bind(1L, fixture.resolver);

        fixture.session.deactivate();
        assertFalse(fixture.session.isAttached());

        assertTrue(
            fixture.session.bind(2L, fixture.resolver),
            "safe-mode cleanup must not permanently disable observation"
        );
        assertEquals(2, fixture.session.bindCount(), "the rebind is a real binding, not a no-op");
    }

    @Test
    void closingIsTerminalAndRefusesLaterBindings() {
        final Fixture fixture = new Fixture();
        fixture.session.bind(1L, fixture.resolver);

        fixture.session.close();
        fixture.session.close();

        assertFalse(fixture.session.bind(2L, fixture.resolver));
        assertEquals(0, fixture.manager.listenerCount());
    }

    @Test
    void aHostWithoutAUsableResolverLeavesTheSessionInactiveWithoutThrowing() {
        final Fixture fixture = new Fixture();

        assertFalse(fixture.session.bind(1L, fixture.resolverWithoutManager()));
        assertFalse(fixture.session.isAttached());
        assertEquals(0, fixture.manager.listenerCount());
        fixture.session.close();
    }

    @Test
    void anEditorThatIsStillStartingIsBoundOnceItsDocumentAppears() throws Exception {
        // The runtime admits a host as soon as its classes are available, which is before the
        // Editor has built its app controller. The first resolve therefore finds nothing, and the
        // session must not treat that as a host that will never have history.
        final Fixture fixture = new Fixture(new NativeEditIngressSession.RetryPolicy(25L, 40));
        App.current = null;

        assertFalse(fixture.session.bind(1L, fixture.resolver));
        assertFalse(fixture.session.isAttached(), "nothing is attached while the Editor is starting");

        fixture.makeEditorReady();

        awaitAttached(fixture);
        assertEquals(1, fixture.session.bindCount());
        assertEquals(1, fixture.manager.listenerCount(), "the real listener is registered");
    }

    @Test
    void aDeferredRetryStopsWhenTheDisconnectCleansUp() throws Exception {
        final Fixture fixture = new Fixture(new NativeEditIngressSession.RetryPolicy(25L, 40));
        App.current = null;
        assertFalse(fixture.session.bind(1L, fixture.resolver));

        fixture.session.deactivate();
        fixture.makeEditorReady();
        Thread.sleep(300L);

        assertFalse(
            fixture.session.isAttached(),
            "cleanup must not be undone by a retry that was already in flight"
        );
        assertEquals(0, fixture.manager.listenerCount());
    }

    @Test
    void closingStopsTheDeferredRetryPermanently() throws Exception {
        final Fixture fixture = new Fixture(new NativeEditIngressSession.RetryPolicy(25L, 40));
        App.current = null;
        assertFalse(fixture.session.bind(1L, fixture.resolver));

        fixture.session.close();
        fixture.makeEditorReady();
        Thread.sleep(300L);

        assertFalse(fixture.session.isAttached());
        assertFalse(fixture.session.bind(2L, fixture.resolver), "a closed session stays closed");
        assertEquals(0, fixture.manager.listenerCount());
    }

    @Test
    void aDeferredRetryIsBounded() throws Exception {
        final Fixture fixture = new Fixture(new NativeEditIngressSession.RetryPolicy(10L, 3));
        App.current = null;

        assertFalse(fixture.session.bind(1L, fixture.resolver));
        Thread.sleep(300L);

        assertFalse(fixture.session.isAttached(), "a host that never becomes ready stays inactive");
        assertFalse(fixture.retryStillRunning(), "the retry thread must not outlive its budget");
    }

    private static void awaitAttached(final Fixture fixture) throws Exception {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (fixture.session.isAttached()) return;
            Thread.sleep(25L);
        }
        assertTrue(fixture.session.isAttached(), "the deferred bind never attached");
    }

    @Test
    void anObservedNativeEditStartIsPublishedBeforeItsConfirmedOperation() {
        final Fixture fixture = new Fixture();
        fixture.session.bind(1L, fixture.resolver);

        // The host enters beginEdit, which queues a start and asks for a drain, and then commits,
        // which the listener reports on the same thread before beginEdit returns.
        NativeEditBeginBridge.ingress().accept("Add Part");
        fixture.manager.commit(new PartMembershipEntry("BodyMesh", true));

        assertEquals(1, fixture.posted.size(), "both observations share one coalesced drain");
        assertTrue(fixture.starts.isEmpty(), "the host entry publishes nothing itself");

        fixture.runPosted();

        assertEquals(List.of(Optional.of("Add Part")), fixture.starts);
        assertEquals(1, fixture.published.size(), "the commit is published after the start");
        assertEquals(CubismOperation.SET_HIERARCHY_PARENT, fixture.published.get(0).operation());
    }

    @Test
    void deactivatingReleasesTheHookSoAnEditIsNotQueuedForAClosedSession() {
        final Fixture fixture = new Fixture();
        fixture.session.bind(1L, fixture.resolver);
        fixture.session.deactivate();

        NativeEditBeginBridge.ingress().accept("Add Part");

        assertTrue(fixture.posted.isEmpty(), "no drain is posted for a detached session");
        assertEquals(1, NativeEditBeginBridge.unboundCount());
    }

    @Test
    void aPublisherFailureDoesNotEscapeTheHostEventThread() {
        final Fixture fixture = new Fixture();
        fixture.failPublications = true;
        fixture.session.bind(1L, fixture.resolver);

        fixture.manager.commit(new PartMembershipEntry("BodyMesh", true));
        fixture.runPosted();

        assertTrue(fixture.published.isEmpty());
    }

    /** One session wired to a host double, with the posted work kept for the test to run. */
    private final class Fixture {
        private final List<Runnable> posted = new ArrayList<>();
        private final List<Published> published = new ArrayList<>();
        private final List<Optional<String>> starts = new ArrayList<>();
        private final NativeEditIngressSession session;
        private NativeUndoIngressObserverTest.Manager manager =
            new NativeUndoIngressObserverTest.Manager();
        private VerifiedMemberResolver resolver = resolver(manager);
        private boolean failPublications;

        Fixture() {
            this(NativeEditIngressSession.DEFAULT_RETRY);
        }

        Fixture(final NativeEditIngressSession.RetryPolicy retry) {
            this.session = new NativeEditIngressSession(
                (operation, origin, subject, label) -> {
                    if (failPublications) throw new IllegalStateException("publication failure");
                    published.add(new Published(operation, origin, subject, label));
                },
                starts::add,
                posted::add,
                retry
            );
        }

        /** Simulates the Editor finishing its own startup and publishing its app controller. */
        void makeEditorReady() {
            new App(manager);
        }

        boolean retryStillRunning() {
            for (final Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().equals("turboism-native-edit-ingress-bind")
                    && thread.isAlive()) {
                    return true;
                }
            }
            return false;
        }

        void runPosted() {
            final List<Runnable> pending = List.copyOf(posted);
            posted.clear();
            for (final Runnable runnable : pending) runnable.run();
        }

        void replaceManager() {
            manager = new NativeUndoIngressObserverTest.Manager();
            resolver = resolver(manager);
        }

        VerifiedMemberResolver resolverWithoutManager() {
            return TestVerifiedResolvers.create(
                "5.3.02",
                "adapter.editor-model.readwrite",
                Set.of("cubism.editor-history.read"),
                List.of(method(
                    "cubism.editor-history.manager.entries",
                    NativeUndoIngressObserverTest.Manager.class,
                    "entries",
                    "()Ljava/util/List;"
                )),
                NativeEditIngressSessionTest.class.getClassLoader()
            );
        }
    }

    static VerifiedMemberResolver resolverForTest(final NativeUndoIngressObserverTest.Manager m) {
        return resolver(m);
    }

    private static VerifiedMemberResolver resolver(
        final NativeUndoIngressObserverTest.Manager manager
    ) {
        final ArrayList<StaticSelector> all = new ArrayList<>();
        all.add(method(
            "cubism.editor-model.app-controller.current-document",
            App.class,
            "currentDocument",
            "()L" + internal(Document.class) + ";"
        ));
        all.add(method(
            "cubism.editor-history.document.undo-manager",
            Document.class,
            "undoManager",
            "()L" + internal(NativeUndoIngressObserverTest.Manager.class) + ";"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.modeling-document.class",
            internal(Document.class)
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-history.manager.class",
            internal(NativeUndoIngressObserverTest.Manager.class)
        ));
        all.add(StaticSelector.staticMethod(
            "cubism.editor-model.app-controller.instance",
            internal(App.class),
            "instance",
            "()L" + internal(App.class) + ";",
            StaticSelector.ACCESS_PUBLIC
        ));
        all.add(method(
            "cubism.editor-history.manager.entries",
            NativeUndoIngressObserverTest.Manager.class,
            "entries",
            "()Ljava/util/List;"
        ));
        all.add(method(
            "cubism.editor-history.manager.position",
            NativeUndoIngressObserverTest.Manager.class,
            "position",
            "()I"
        ));
        all.add(method(
            "cubism.editor-history.entry.presentation-name",
            NativeUndoIngressObserverTest.Entry.class,
            "presentationName",
            "()Ljava/lang/String;"
        ));
        all.add(StaticSelector.classSelector(
            NativeUndoIngressObserver.LISTENER_CLASS_ALIAS,
            internal(NativeUndoIngressObserverTest.Listener.class)
        ));
        all.add(method(
            NativeUndoIngressObserver.LISTENER_ADD_ALIAS,
            NativeUndoIngressObserverTest.Manager.class,
            "addUndoStateChangeListener",
            "(L" + internal(NativeUndoIngressObserverTest.Listener.class) + ";)V"
        ));
        all.add(method(
            NativeUndoIngressObserver.LISTENER_REMOVE_ALIAS,
            NativeUndoIngressObserverTest.Manager.class,
            "removeUndoStateChangeListener",
            "(L" + internal(NativeUndoIngressObserverTest.Listener.class) + ";)V"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-history.semantic.part-membership.class",
            internal(PartMembershipEntry.class)
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.part",
            PartMembershipEntry.class,
            "getPart",
            "()L" + internal(PartSourceDouble.class) + ";"
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.child",
            PartMembershipEntry.class,
            "getChild",
            "()L" + internal(ArtMeshDouble.class) + ";"
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.index",
            PartMembershipEntry.class,
            "getInsertIndex",
            "()I"
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.is-add",
            PartMembershipEntry.class,
            "isAdd",
            "()Z"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.part-source.class",
            internal(PartSourceDouble.class)
        ));
        all.add(method(
            "cubism.editor-model.part-source.id",
            PartSourceDouble.class,
            "id",
            "()L" + internal(PartId.class) + ";"
        ));
        all.add(method(
            "cubism.editor-model.part-id.value",
            PartId.class,
            "value",
            "()Ljava/lang/String;"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.art-mesh-source.class",
            internal(ArtMeshDouble.class)
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.warp-source.class",
            internal(WarpDouble.class)
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.rotation-source.class",
            internal(RotationDouble.class)
        ));
        all.add(method(
            "cubism.editor-model.parameter-controllable-source.id",
            ControllableSource.class,
            "id",
            "()L" + internal(SourceId.class) + ";"
        ));
        all.add(method(
            "cubism.editor-model.parameter-controllable-source.local-name",
            SourceBase.class,
            "getLocalName",
            "()Ljava/lang/String;"
        ));
        all.add(method(
            "cubism.editor-model.id.value",
            SourceId.class,
            "value",
            "()Ljava/lang/String;"
        ));
        // The host double is wired to the resolver through a static entry point, exactly like the
        // real app controller, so the test exercises the same binding path production uses.
        new App(manager);
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-history.read", "cubism.editor-history.semantic-read"),
            all,
            NativeEditIngressSessionTest.class.getClassLoader()
        );
    }

    private record Published(
        CubismOperation operation,
        CubismOperationOrigin origin,
        Optional<String> subjectId,
        Optional<String> label
    ) {
    }

    /** Static host entry point double. */
    public static final class App {
        private static App current;

        App(final NativeUndoIngressObserverTest.Manager manager) {
            current = this;
            this.document = new Document(manager);
        }

        private final Document document;

        public static App instance() {
            return current;
        }

        public Document currentDocument() {
            return document;
        }
    }

    /** Modeling document double exposing its native undo manager. */
    public static final class Document {
        private final NativeUndoIngressObserverTest.Manager manager;

        Document(final NativeUndoIngressObserverTest.Manager manager) {
            this.manager = manager;
        }

        public NativeUndoIngressObserverTest.Manager undoManager() {
            return manager;
        }
    }

    /** Test double for {@code Editor_Part$Undo_AddOrRemovePartChild}. */
    public static final class PartMembershipEntry extends NativeUndoIngressObserverTest.Entry {
        private final PartSourceDouble part;
        private final ArtMeshDouble child;
        private final boolean add;

        PartMembershipEntry(final String childName, final boolean add) {
            super("Add Part");
            this.part = new PartSourceDouble(add ? "PartB" : "PartA");
            this.child = new ArtMeshDouble(childName);
            this.add = add;
        }

        public PartSourceDouble getPart() {
            return part;
        }

        public ArtMeshDouble getChild() {
            return child;
        }

        public int getInsertIndex() {
            return 0;
        }

        public boolean isAdd() {
            return add;
        }
    }

    public static class SourceBase {
        private final String localName;

        SourceBase(final String localName) {
            this.localName = localName;
        }

        public String getLocalName() {
            return localName;
        }
    }

    public static class ControllableSource extends SourceBase {
        ControllableSource(final String localName) {
            super(localName);
        }

        public SourceId id() {
            return new SourceId("mesh-1");
        }
    }

    public static final class ArtMeshDouble extends ControllableSource {
        ArtMeshDouble(final String localName) {
            super(localName);
        }
    }

    public static final class WarpDouble extends ControllableSource {
        WarpDouble(final String localName) {
            super(localName);
        }
    }

    public static final class RotationDouble extends ControllableSource {
        RotationDouble(final String localName) {
            super(localName);
        }
    }

    public static final class PartSourceDouble extends SourceBase {
        PartSourceDouble(final String localName) {
            super(localName);
        }

        public PartId id() {
            return new PartId(getLocalName());
        }
    }

    public record SourceId(String value) {
    }

    public record PartId(String value) {
    }
}
