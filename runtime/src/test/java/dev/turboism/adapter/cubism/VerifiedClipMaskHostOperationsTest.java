package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedClipMaskHostOperationsTest {

    @AfterEach
    void clearSyntheticSingleton() {
        SyntheticApp.instance = null;
    }

    @Test
    void convertsClipMasksPreservingOrderDuplicatesAndInverted() {
        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(new ModelSource(List.of(
            new ArtMesh(new DrawableGuid("target-a"), List.of(
                new DrawableGuid("mask-2"), new DrawableGuid("mask-1"), new DrawableGuid("mask-2")
            ), true),
            new ArtMesh(new DrawableGuid("skipped"), List.of(), false),
            new ArtMesh(new DrawableGuid("target-b"), List.of(new DrawableGuid("mask-3")), false)
        ))));
        ClipMaskReadAdapter adapter = adapter(resolver());

        var result = adapter.clipMasks();

        assertTrue(result.isAvailable());
        var snapshots = result.value().orElseThrow();
        assertEquals(2, snapshots.size());
        assertEquals("target-a", snapshots.get(0).targetMeshId());
        assertEquals(List.of("mask-2", "mask-1", "mask-2"), snapshots.get(0).orderedMaskSourceIds());
        assertTrue(snapshots.get(0).inverted());
        assertEquals("target-b", snapshots.get(1).targetMeshId());
        assertFalse(snapshots.get(1).inverted());
    }

    @Test
    void returnsAvailableEmptyForAbsentOrNonModelingDocumentAndEmptySources() {
        SyntheticApp.instance = new SyntheticApp(null);
        assertEquals(List.of(), adapter(resolver()).clipMasks().value().orElseThrow());

        SyntheticApp.instance = new SyntheticApp(new OtherDocument());
        assertEquals(List.of(), adapter(resolver()).clipMasks().value().orElseThrow());

        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(null));
        assertEquals(List.of(), adapter(resolver()).clipMasks().value().orElseThrow());

        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(new ModelSource(List.of())));
        assertEquals(List.of(), adapter(resolver()).clipMasks().value().orElseThrow());
    }

    @Test
    void wrongSingletonTypeIsValidationFailureAndMissingClassAliasIsMappingNotVerified() {
        SyntheticApp.instance = new SyntheticApp(null);
        VerifiedMemberResolver wrongSingletonResolver = resolver(
            StaticSelector.staticMethod(
                "cubism.clipmask.app-controller.instance",
                name(WrongSingletonProvider.class),
                "instance",
                "()Ljava/lang/Object;",
                StaticSelector.ACCESS_PUBLIC
            )
        );
        assertDiagnostic(SafeModeDiagnostic.Code.VALIDATION_FAILURE, adapter(wrongSingletonResolver));

        assertDiagnostic(
            SafeModeDiagnostic.Code.MAPPING_NOT_VERIFIED,
            adapter(resolverWithout("cubism.clipmask.app-controller.class"))
        );
    }

    @Test
    void wrongDocumentTypeIsValidationFailure() {
        SyntheticApp.instance = new SyntheticApp(new Object());
        assertDiagnostic(SafeModeDiagnostic.Code.VALIDATION_FAILURE, adapter(resolver()));
    }

    @Test
    void wrongMeshNullBlankGuidAndNonIterableClipListFailWithoutPartialResults() {
        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(new ModelSource(List.of(
            new ArtMesh(new DrawableGuid("valid"), List.of(new DrawableGuid("mask")), false),
            new WrongMesh()
        ))));
        assertDiagnostic(SafeModeDiagnostic.Code.VALIDATION_FAILURE, adapter(resolver()));

        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(new ModelSource(List.of(
            new ArtMesh(new DrawableGuid("valid"), java.util.Arrays.asList((DrawableGuid) null), false)
        ))));
        assertDiagnostic(SafeModeDiagnostic.Code.VALIDATION_FAILURE, adapter(resolver()));

        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(new ModelSource(List.of(
            new ArtMesh(new DrawableGuid(" "), List.of(new DrawableGuid("mask")), false)
        ))));
        assertDiagnostic(SafeModeDiagnostic.Code.VALIDATION_FAILURE, adapter(resolver()));

        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(new ModelSource(List.of(
            new BadClipListMesh()
        ))));
        assertDiagnostic(SafeModeDiagnostic.Code.VALIDATION_FAILURE, adapter(resolver()));
    }

    @Test
    void hostInvocationFailureIsValidationFailureAndMissingAliasIsMappingNotVerified() {
        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(new ModelSource(List.of(
            new ThrowingMesh()
        ))));
        assertDiagnostic(SafeModeDiagnostic.Code.VALIDATION_FAILURE, adapter(resolver()));

        SyntheticApp.instance = new SyntheticApp(new ModelingDocument(new ModelSource(List.of(
            new ArtMesh(new DrawableGuid("target"), List.of(new DrawableGuid("mask")), false)
        ))));
        assertDiagnostic(
            SafeModeDiagnostic.Code.MAPPING_NOT_VERIFIED,
            adapter(resolverWithout("cubism.clipmask.drawable-source.guid"))
        );
    }

    private static void assertDiagnostic(
        final SafeModeDiagnostic.Code code,
        final ClipMaskReadAdapter adapter
    ) {
        var result = adapter.clipMasks();
        assertFalse(result.isAvailable());
        assertTrue(result.value().isEmpty());
        assertEquals(code, result.diagnostic().orElseThrow().code());
    }

    private static ClipMaskReadAdapter adapter(final VerifiedMemberResolver resolver) {
        return ClipMaskReadAdapter.Impl.connected(new VerifiedClipMaskHostOperations(resolver, "5.3.02"));
    }

    private static VerifiedMemberResolver resolver() {
        return resolverWithout("");
    }

    private static VerifiedMemberResolver resolver(final StaticSelector appInstanceSelector) {
        return resolverWithout("", appInstanceSelector);
    }

    private static VerifiedMemberResolver resolverWithout(final String omittedAlias) {
        final String app = name(SyntheticApp.class);
        return resolverWithout(
            omittedAlias,
            StaticSelector.staticMethod(
                "cubism.clipmask.app-controller.instance",
                app,
                "instance",
                "()L" + app + ";",
                StaticSelector.ACCESS_PUBLIC
            )
        );
    }

    private static VerifiedMemberResolver resolverWithout(
        final String omittedAlias,
        final StaticSelector appInstanceSelector
    ) {
        final String app = name(SyntheticApp.class);
        final String document = name(Document.class);
        final String modeling = name(ModelingDocument.class);
        final String modelSource = name(ModelSource.class);
        final String artMesh = name(ArtMesh.class);
        final String drawable = name(DrawableSource.class);
        final String drawableGuid = name(DrawableGuid.class);
        final String guid = name(Guid.class);
        return TestVerifiedResolvers.create(
            ClipMaskReadAdapter.ADAPTER_SLICE_ID,
            java.util.Set.of(ClipMaskReadAdapter.CAPABILITY_ID),
            List.of(
                StaticSelector.classSelector("cubism.clipmask.app-controller.class", app),
                appInstanceSelector,
                StaticSelector.method("cubism.clipmask.app-controller.current-document", app, "currentDocument", "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.clipmask.document.class", document),
                StaticSelector.classSelector("cubism.clipmask.modeling-document.class", modeling),
                StaticSelector.method("cubism.clipmask.modeling-document.model-source", modeling, "modelSource", "()L" + modelSource + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.clipmask.model-source.class", modelSource),
                StaticSelector.method("cubism.clipmask.model-source.all-art-meshes", modelSource, "allArtMeshes", "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.clipmask.art-mesh-source.class", artMesh),
                StaticSelector.classSelector("cubism.clipmask.drawable-source.class", drawable),
                StaticSelector.method("cubism.clipmask.drawable-source.guid", drawable, "guid", "()L" + drawableGuid + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.clipmask.drawable-source.clip-guid-list", drawable, "clipGuidList", "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.clipmask.drawable-source.invert-clipping-mask", drawable, "inverted", "()Z", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.clipmask.drawable-guid.class", drawableGuid),
                StaticSelector.classSelector("cubism.clipmask.guid.class", guid),
                StaticSelector.method("cubism.clipmask.guid.value", guid, "uuidString", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC)
            ).stream().filter(selector -> !selector.alias().equals(omittedAlias)).toList(),
            SyntheticApp.class.getClassLoader()
        );
    }

    private static String name(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public interface Document { }

    public static final class OtherDocument implements Document { }

    public static final class WrongSingletonProvider {
        public static Object instance() { return new Object(); }
    }

    public static final class SyntheticApp {
        private static SyntheticApp instance;
        private final Object document;
        SyntheticApp(Object document) { this.document = document; }
        public static SyntheticApp instance() { return instance; }
        public Object currentDocument() { return document; }
    }

    public static final class ModelingDocument implements Document {
        private final ModelSource modelSource;
        ModelingDocument(ModelSource modelSource) { this.modelSource = modelSource; }
        public ModelSource modelSource() { return modelSource; }
    }

    public static final class ModelSource {
        private final List<?> allArtMeshes;
        ModelSource(List<?> allArtMeshes) { this.allArtMeshes = allArtMeshes; }
        public List<?> allArtMeshes() { return allArtMeshes; }
    }

    public static class DrawableSource {
        private final DrawableGuid guid;
        private final Object clipGuidList;
        private final boolean inverted;
        DrawableSource(DrawableGuid guid, Object clipGuidList, boolean inverted) {
            this.guid = guid;
            this.clipGuidList = clipGuidList;
            this.inverted = inverted;
        }
        public DrawableGuid guid() { return guid; }
        public Object clipGuidList() { return clipGuidList; }
        public boolean inverted() { return inverted; }
    }

    public static class ArtMesh extends DrawableSource {
        ArtMesh(DrawableGuid guid, Object clipGuidList, boolean inverted) {
            super(guid, clipGuidList, inverted);
        }
    }

    public static final class WrongMesh { }

    public static final class BadClipListMesh extends ArtMesh {
        BadClipListMesh() { super(new DrawableGuid("target"), new Object(), false); }
    }

    public static final class ThrowingMesh extends ArtMesh {
        ThrowingMesh() { super(new DrawableGuid("target"), List.of(new DrawableGuid("mask")), false); }
        @Override public DrawableGuid guid() { throw new IllegalStateException("private-host-detail"); }
    }

    public static class Guid {
        private final String uuidString;
        Guid(String uuidString) { this.uuidString = uuidString; }
        public String uuidString() { return uuidString; }
    }

    public static final class DrawableGuid extends Guid {
        DrawableGuid(String uuidString) { super(uuidString); }
    }
}
