package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The list handed to native {@code startMode(List)} carries mesh-edit-data entries, not ArtMesh
 * sources, so the entry-to-ArtMesh derivation must go through the edit-data accessor.
 */
class MeshToolSessionArtMeshDerivationTest {
    private static final String ADAPTER_SLICE =
            MeshToolSessionSelectorContract.class.getPackageName().isEmpty()
                    ? "adapter.editor-model.readwrite"
                    : "adapter.editor-model.readwrite";

    @Test
    void derivesTheArtMeshFromMeshEditDataEntries() {
        final Host host = new Host();
        final VerifiedMemberResolver resolver = resolver();

        final Object derived =
                MeshToolSessionResolver.singleArtMesh(resolver, List.of(host.editData), List.of(host.source));

        assertSame(host.source, derived);
    }

    @Test
    void stillAcceptsADirectArtMeshSourceEntry() {
        final Host host = new Host();
        final VerifiedMemberResolver resolver = resolver();

        assertSame(
                host.source,
                MeshToolSessionResolver.singleArtMesh(resolver, List.of(host.source), List.of(host.source)));
    }

    @Test
    void refusesEntriesWhoseArtMeshIsNotInTheModel() {
        final Host host = new Host();
        final VerifiedMemberResolver resolver = resolver();

        assertNull(MeshToolSessionResolver.singleArtMesh(resolver, List.of(host.editData), List.of(new Object())));
    }

    @Test
    void refusesAmbiguousOrUnrelatedEntries() {
        final Host host = new Host();
        final Host other = new Host();
        final VerifiedMemberResolver resolver = resolver();

        // Two distinct ArtMeshes in one start list is ambiguous.
        assertNull(MeshToolSessionResolver.singleArtMesh(
                resolver, List.of(host.editData, other.editData), List.of(host.source, other.source)));
        // An unrelated object is not an ArtMesh source and carries no edit data.
        assertNull(MeshToolSessionResolver.singleArtMesh(resolver, List.of(new Object()), List.of(host.source)));
        assertNull(MeshToolSessionResolver.singleArtMesh(resolver, List.of(), List.of(host.source)));
    }

    private static VerifiedMemberResolver resolver() {
        return TestVerifiedResolvers.create(
                ADAPTER_SLICE,
                java.util.Set.of("cubism.editor-model.read"),
                List.of(
                        StaticSelector.classSelector(
                                MeshToolSessionSelectorContract.ARTMESH_SOURCE_CLASS,
                                internalName(ArtMeshSource.class)),
                        StaticSelector.classSelector(
                                MeshToolSessionSelectorContract.ARTMESH_EDIT_DATA_CLASS, internalName(EditData.class)),
                        StaticSelector.method(
                                MeshToolSessionSelectorContract.ARTMESH_EDIT_DATA_SOURCE,
                                internalName(EditData.class),
                                "source",
                                "()L" + internalName(ArtMeshSource.class) + ";",
                                StaticSelector.ACCESS_PUBLIC)),
                MeshToolSessionArtMeshDerivationTest.class.getClassLoader());
    }

    private static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** Host fixture: the edit-data type exposes the ArtMesh source it edits. */
    public static final class ArtMeshSource {}

    /** Host fixture mirroring the host's mesh-edit-data entry. */
    public static final class EditData {
        private final ArtMeshSource source;

        EditData(final ArtMeshSource source) {
            this.source = source;
        }

        public ArtMeshSource source() {
            return source;
        }
    }

    private static final class Host {
        final ArtMeshSource source = new ArtMeshSource();
        final EditData editData = new EditData(source);
    }
}
