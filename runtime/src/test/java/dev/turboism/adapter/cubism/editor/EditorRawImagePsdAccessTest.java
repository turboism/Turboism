package dev.turboism.adapter.cubism.editor;

import dev.turboism.sdk.cubism.id.RawImageId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Synthetic identity guard; it does not claim real-host or native behavior. */
class EditorRawImagePsdAccessTest {
    @Test
    void selectsTheRequestedRawImageByIdWhenNamesAreTheSame() {
        final Object oldNativeImage = new Object();
        final Object sameNameDifferentResource = new Object();
        final var oldTarget = new EditorRawImagePsdAccess.RawImageCandidate<>(
            new RawImageId("raw-old"),
            "shared-source.psd",
            oldNativeImage
        );
        final var otherTarget = new EditorRawImagePsdAccess.RawImageCandidate<>(
            new RawImageId("raw-other"),
            "shared-source.psd",
            sameNameDifferentResource
        );

        final var selected = EditorRawImagePsdAccess.selectByRawImageId(
            List.of(otherTarget, oldTarget),
            new RawImageId("raw-old")
        );

        assertEquals(EditorRawImagePsdAccess.SelectionStatus.MATCHED, selected.status());
        assertSame(oldTarget, selected.candidate());
        assertSame(oldNativeImage, selected.candidate().nativeSource());
    }

    @Test
    void doesNotGuessWhenTheIdIsMissingOrDuplicated() {
        final var first = new EditorRawImagePsdAccess.RawImageCandidate<>(
            new RawImageId("raw-duplicate"),
            "same-name.psd",
            new Object()
        );
        final var second = new EditorRawImagePsdAccess.RawImageCandidate<>(
            new RawImageId("raw-duplicate"),
            "same-name.psd",
            new Object()
        );

        final var missing = EditorRawImagePsdAccess.selectByRawImageId(
            List.of(first),
            new RawImageId("raw-missing")
        );
        final var duplicate = EditorRawImagePsdAccess.selectByRawImageId(
            List.of(first, second),
            new RawImageId("raw-duplicate")
        );

        assertEquals(EditorRawImagePsdAccess.SelectionStatus.NOT_FOUND, missing.status());
        assertNull(missing.candidate());
        assertEquals(EditorRawImagePsdAccess.SelectionStatus.DUPLICATE, duplicate.status());
        assertNull(duplicate.candidate());
    }
}
