package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MeshToolSessionResolverTest {
    @Test
    void resolvesFreshCurrentIdentitiesWithoutCachingLiveHostObjects() {
        final Object mode = new Object();
        final AtomicReference<MeshToolSessionResolver.Snapshot> current = new AtomicReference<>(snapshot(mode));
        final MeshToolSessionResolver resolver =
                new MeshToolSessionResolver((ignoredMode, ignoredEntries) -> current.get());

        final NativeMeshToolSession first = resolver.resolve(mode, List.of()).orElseThrow();
        final MeshToolSessionResolver.Snapshot replacement = snapshot(mode);
        current.set(replacement);
        final NativeMeshToolSession second = resolver.resolve(mode, List.of()).orElseThrow();

        assertNotSame(first, second);
        assertFalse(first.revalidate());
        assertTrue(second.revalidate());
        assertSame(replacement.artMesh(), second.identity().artMesh());
        assertSame(replacement.pointSelector(), second.identity().pointSelector());
        assertSame(replacement.modelingView(), second.identity().modelingView());
        assertSame(replacement.component(), second.identity().component());
        assertSame(replacement.camera(), second.identity().camera());
    }

    @Test
    void failsClosedWhenTheModeIdentityDoesNotAgree() {
        // ArtMesh corroboration lives in the production reader, which derives it from the start
        // entries and re-checks it through the mode's own edit-data accessor with correctly typed
        // values; a raw entry comparison against an ArtMesh source was the wrong type and has been
        // removed. This test only covers the snapshot-level mode identity rule.
        final Object mode = new Object();
        final MeshToolSessionResolver.Snapshot snapshot = snapshot(mode);
        final MeshToolSessionResolver wrongMode =
                new MeshToolSessionResolver((ignoredMode, ignoredEntries) -> snapshot.withMode(new Object()));

        assertTrue(wrongMode.resolve(mode, List.of()).isEmpty());
        assertTrue(wrongMode.resolve(mode, List.of(snapshot.artMesh())).isEmpty());
    }

    private static MeshToolSessionResolver.Snapshot snapshot(final Object mode) {
        final Object artMesh = new Object();
        return new MeshToolSessionResolver.Snapshot(
                mode,
                new Object(),
                new Object(),
                artMesh,
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object(),
                new Object());
    }
}
