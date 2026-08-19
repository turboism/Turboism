package dev.turboism.adapter.host;

import dev.turboism.mapping.verification.selector.CoreMocInfoSelectorContract;
import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.core.CoreEvaluatedJoin;
import dev.turboism.adapter.cubism.core.CoreProviderResult;
import dev.turboism.adapter.cubism.core.CoreVersionExpectation;
import dev.turboism.adapter.cubism.core.RuntimeCoreModelBackend;
import dev.turboism.adapter.cubism.core.TestCoreApiFixture;
import dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.core.MocVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editor→Core borrowed-model publish wiring at connect time.
 *
 * <p>The Core backend is fixture-backed and installed through the injectable
 * {@code CoreBackendFactory} seam: admission runs against the synthetic Core surface (no native
 * library), so the full publish → Core read chain is exercisable on a pure JVM. The Editor
 * resolver is a fixture carrying the publish chain's eight existing aliases plus the two class
 * selectors. Connect must publish the resolved current document model exactly once, skip
 * publish whenever the resolver chain yields nothing, and never reject connect because of it.
 */
class VerifiedHostAdapterConnectorBorrowedModelPublishTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();

    private static final VerifiedHostAdapterConnector.EditorAccessFactory PRODUCTION_ACCESS =
        (resolver, sessionId, coreBackend) -> new EditorBackedCubismModelAccess(
            resolver, sessionId, coreBackend == null ? null : coreBackend.evaluatedJoin()
        );

    @BeforeEach
    void resetFixture() {
        EditorFixture.Host.currentDocument = null;
        EditorFixture.ModelSource.guidValue = "model-1";
    }

    @Test
    void connectPublishesTheCurrentEditorDocumentModelToTheAdmittedCoreBackend() throws Exception {
        EditorFixture.Host.currentDocument = new EditorFixture.Document();
        final HostVerificationEvidence evidence = evidence();
        try (HostAdapterConnection connection = connector(PRODUCTION_ACCESS, coreBackendFactory())
            .connect(new HostInstanceDescriptor("session-a", evidence))) {

            final var model = connection.modelAccess().active();
            assertEquals("model-1", model.id().value());

            // The fixture-backed Core backend admitted at connect and the publish chain
            // installed the resolved Editor document model: the Core MOC read chain works
            // end to end on a pure JVM (no native library involved).
            final MocInfo mocInfo = model.mocInfo();
            assertNotEquals(MocVersion.UNKNOWN, mocInfo.version());
        }
    }

    @Test
    void connectWithoutAnEditorSliceKeepsTheUnavailableModelAccessPath() throws Exception {
        final HostVerificationEvidence evidence = HostVerificationEvidence.projectOnly(
            new HostVerificationEvidence.Slice(
                Path.of("project.json"), Path.of("host.jar"), getClass().getClassLoader()
            )
        );
        try (HostAdapterConnection connection = connector(PRODUCTION_ACCESS)
            .connect(new HostInstanceDescriptor("session-b", evidence))) {

            assertThrows(IllegalStateException.class, () -> connection.modelAccess().active());
            assertThrows(UnsupportedOperationException.class, connection.coreRuntimeInfo()::version);
        }
    }

    @Test
    void connectWithoutACurrentDocumentSkipsPublishAndKeepsTheJoinFailClosed() throws Exception {
        // No document installed: the resolver chain yields null before the model.
        final HostVerificationEvidence evidence = evidence();
        final AtomicReference<CoreEvaluatedJoin> joinRef = new AtomicReference<>();
        final VerifiedHostAdapterConnector.EditorAccessFactory capturingAccess =
            (resolver, sessionId, coreBackend) -> {
                final CoreEvaluatedJoin join = coreBackend == null
                    ? null : coreBackend.evaluatedJoin();
                joinRef.set(join);
                return new EditorBackedCubismModelAccess(resolver, sessionId, join);
            };
        try (HostAdapterConnection connection = connector(capturingAccess, coreBackendFactory())
            .connect(new HostInstanceDescriptor("session-c", evidence))) {

            // connect() succeeded and nothing was published: the join read still
            // reports the un-published "No verified active Core model" failure.
            final IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> joinRef.get().mocInfo()
            );
            assertTrue(
                failure.getMessage().contains("No verified active Core model"),
                failure.getMessage()
            );
        }
    }

    @Test
    void resolveBorrowedModelIsStableForTheSameResolverStateAndFormatsSessionAndModelId() {
        EditorFixture.Host.currentDocument = new EditorFixture.Document();
        final VerifiedMemberResolver resolver = editorResolver();

        final VerifiedHostAdapterConnector.BorrowedModel first =
            VerifiedHostAdapterConnector.resolveBorrowedModel(resolver, "session-t4").orElseThrow();
        final VerifiedHostAdapterConnector.BorrowedModel second =
            VerifiedHostAdapterConnector.resolveBorrowedModel(resolver, "session-t4").orElseThrow();

        assertEquals("session-t4:model-1", first.identity());
        assertEquals(first.identity(), second.identity());
        assertSame(first.model(), second.model());
    }

    @Test
    void resolveBorrowedModelIdentityChangesWithTheDocumentGuid() {
        EditorFixture.Host.currentDocument = new EditorFixture.Document();
        final VerifiedMemberResolver resolver = editorResolver();

        final String before = VerifiedHostAdapterConnector
            .resolveBorrowedModel(resolver, "session-t4").orElseThrow().identity();
        EditorFixture.ModelSource.guidValue = "model-2";
        final String after = VerifiedHostAdapterConnector
            .resolveBorrowedModel(resolver, "session-t4").orElseThrow().identity();

        assertEquals("session-t4:model-1", before);
        assertEquals("session-t4:model-2", after);
        assertNotEquals(before, after);
    }

    @Test
    void resolveBorrowedModelFailsClosedForMissingResolvedValues() {
        // No current document: the chain yields null before the model.
        final VerifiedMemberResolver resolver = editorResolver();
        assertTrue(VerifiedHostAdapterConnector.resolveBorrowedModel(resolver, "session-t4").isEmpty());

        // Non-modeling document: the class guard rejects it.
        EditorFixture.Host.currentDocument = new Object() {
            @Override public String toString() { return "not a modeling document"; }
        };
        assertTrue(VerifiedHostAdapterConnector.resolveBorrowedModel(resolver, "session-t4").isEmpty());

        // Blank model id: the identity guard rejects it.
        EditorFixture.Host.currentDocument = new EditorFixture.Document();
        EditorFixture.ModelSource.guidValue = "  ";
        assertTrue(VerifiedHostAdapterConnector.resolveBorrowedModel(resolver, "session-t4").isEmpty());
    }

    private static VerifiedHostAdapterConnector connector(
        final VerifiedHostAdapterConnector.EditorAccessFactory accessFactory
    ) {
        return new VerifiedHostAdapterConnector(
            ignored -> RuntimeHostAdapters.safeMode(),
            ignored -> editorResolver(),
            accessFactory
        );
    }

    private static VerifiedHostAdapterConnector connector(
        final VerifiedHostAdapterConnector.EditorAccessFactory accessFactory,
        final VerifiedHostAdapterConnector.CoreBackendFactory coreBackendFactory
    ) {
        return new VerifiedHostAdapterConnector(
            ignored -> RuntimeHostAdapters.safeMode(),
            ignored -> editorResolver(),
            accessFactory,
            coreBackendFactory
        );
    }

    private static HostVerificationEvidence evidence() {
        final ClassLoader loader = EditorFixture.Host.class.getClassLoader();
        final HostVerificationEvidence.Slice project = new HostVerificationEvidence.Slice(
            Path.of("project.json"), Path.of("host.jar"), loader
        );
        final HostVerificationEvidence.Slice editor = new HostVerificationEvidence.Slice(
            Path.of("editor.json"), Path.of("host.jar"), loader
        );
        final HostVerificationEvidence.Slice core = new HostVerificationEvidence.Slice(
            Path.of("core.json"), Path.of("core.jar"), loader
        );
        return HostVerificationEvidence.withEditorModel(project, editor).addingCoreRuntime(core);
    }

    /**
     * Fixture-backed Core backend: admission runs against the synthetic Core surface (no native
     * library), so the connector's publish wiring is fully exercisable on the pure JVM. The MOC
     * selectors point at the Editor fixture's model because that is the object the publish chain
     * resolves and the Core read chain must read it back directly.
     */
    private static VerifiedHostAdapterConnector.CoreBackendFactory coreBackendFactory() {
        return evidence -> {
        final CoreProviderResult<RuntimeCoreModelBackend> admission = RuntimeCoreModelBackend.admitForTesting(
            TestCoreApiFixture.resolverWithExtras(
                "5.3.02",
                mocSelectors(),
                java.util.Set.of(CoreMocInfoSelectorContract.CAPABILITY_ID)
            ),
            CoreVersionExpectation.exact(11, 12, 13)
        );
        if (!admission.isSuccess()) {
            throw new IllegalStateException(
                "fixture-backed Core admission failed: " + admission.failure().orElseThrow()
            );
        }
        return admission.value().orElseThrow();
        };
    }

    private static List<StaticSelector> mocSelectors() {
        return List.of(
            StaticSelector.method(
                CoreMocInfoSelectorContract.MODEL_GET_MOC,
                internal(EditorFixture.Model.class),
                "getMoc",
                "()L" + internal(EditorFixture.Moc.class) + ";",
                StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.classSelector(
                CoreMocInfoSelectorContract.MOC_CLASS,
                internal(EditorFixture.Moc.class)
            ),
            StaticSelector.method(
                CoreMocInfoSelectorContract.MOC_GET_MOC_VERSION,
                internal(EditorFixture.Moc.class),
                "getMocVersion",
                "()I",
                StaticSelector.ACCESS_PUBLIC
            )
        );
    }

    private static VerifiedMemberResolver editorResolver() {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.read",
            java.util.Set.of("cubism.editor-model.read"),
            selectors(),
            EditorFixture.Host.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        return List.of(
            StaticSelector.classSelector(
                "cubism.editor-model.app-controller.class", internal(EditorFixture.Host.class)
            ),
            StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(EditorFixture.Host.class),
                "access$get_instance$cp",
                "()L" + internal(EditorFixture.Host.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
            ),
            method(
                "cubism.editor-model.app-controller.current-document",
                EditorFixture.Host.class, "getCurrentDoc",
                "()Ljava/lang/Object;"
            ),
            StaticSelector.classSelector(
                "cubism.editor-model.modeling-document.class", internal(EditorFixture.Document.class)
            ),
            method(
                "cubism.editor-model.modeling-document.model-source",
                EditorFixture.Document.class, "getModelSource",
                "()L" + internal(EditorFixture.ModelSource.class) + ";"
            ),
            method(
                "cubism.editor-model.model-source.current-instance",
                EditorFixture.ModelSource.class, "getCurrentInstance",
                "()L" + internal(EditorFixture.Model.class) + ";"
            ),
            method(
                "cubism.editor-model.model-source.guid",
                EditorFixture.ModelSource.class, "getGuid",
                "()L" + internal(EditorFixture.Guid.class) + ";"
            ),
            method(
                "cubism.editor-model.guid.value",
                EditorFixture.Guid.class, "getUuidString",
                "()Ljava/lang/String;"
            ),
            StaticSelector.classSelector(
                "cubism.editor-model.model.class", internal(EditorFixture.Model.class)
            )
        );
    }

    private static StaticSelector method(
        final String alias, final Class<?> owner, final String name, final String descriptor
    ) {
        return StaticSelector.method(
            alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static Path locateProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("could not locate Turboism project root");
        }
        return current;
    }

    /**
     * Minimal Editor document-model stand-ins mirroring the decompiled host member names of the
     * verified publish chain (CModelAppController.access$get_instance$cp, CModelDoc.getCurrentDoc,
     * CModelSource.getModelSource/getCurrentInstance/getGuid, CModelGuid.getUuidString).
     */
    static final class EditorFixture {

        static final class Host {
            static final Host INSTANCE = new Host();
            static Object currentDocument;

            public static Host access$get_instance$cp() {
                return INSTANCE;
            }

            public Object getCurrentDoc() {
                return currentDocument;
            }
        }

        static final class Document {
            final ModelSource source = new ModelSource();

            public ModelSource getModelSource() {
                return source;
            }
        }

        static final class ModelSource {
            static String guidValue = "model-1";
            final Model model = new Model();

            public Model getCurrentInstance() {
                return model;
            }

            public Guid getGuid() {
                return new Guid(guidValue);
            }
        }

        static final class Model {
            public Moc getMoc() {
                return new Moc();
            }
        }

        /** Minimal MOC stand-in for the verified Core MOC-version read (constant 6 = V5_3). */
        static final class Moc {
            public int getMocVersion() {
                return 6;
            }
        }

        static final class Guid {
            private final String uuid;

            Guid(final String uuid) {
                this.uuid = uuid;
            }

            public String getUuidString() {
                return uuid;
            }
        }
    }
}
