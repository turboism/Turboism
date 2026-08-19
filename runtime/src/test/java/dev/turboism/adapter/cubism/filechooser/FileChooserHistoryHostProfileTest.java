package dev.turboism.adapter.cubism.filechooser;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChooserHistoryHostProfileTest {

    private static final HostArtifactDigest CUBISM_52 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53 = ReviewedHostArtifacts.CUBISM_5_3_02;

    @Test
    void fiveTwoProfileCarriesReviewedSelectors() {
        final FileChooserHistoryHostProfile profile =
            FileChooserHistoryHostProfile.forArtifact(CUBISM_52).orElseThrow();

        assertEquals("5.2.0", profile.hostVersion());
        assertEquals("com/live2d/ui/window/n", profile.fileChooserClassInternalName());
        assertEquals(
            List.of(
                new FileChooserHistoryHostProfile.SaveDialogMethod(
                    "c", "(Lcom/live2d/ui/window/V;)Ljava/io/File;"
                ),
                new FileChooserHistoryHostProfile.SaveDialogMethod(
                    "a", "(Ljava/awt/Component;Z)Ljava/io/File;"
                )
            ),
            profile.saveDialogMethods()
        );
        assertEquals(
            List.of(
                "com.live2d.cubism.doc.model.exporter.b",
                "com.live2d.cubism.appCtrlImpl.a"
            ),
            profile.exportContextClassNames()
        );
    }

    @Test
    void fiveThreeProfileSwapsAppCtrlContextClass() {
        final FileChooserHistoryHostProfile profile =
            FileChooserHistoryHostProfile.forArtifact(CUBISM_53).orElseThrow();

        assertEquals("5.3.02", profile.hostVersion());
        assertEquals(
            List.of(
                "com.live2d.cubism.doc.model.exporter.b",
                "com.live2d.cubism.appCtrlImpl.al"
            ),
            profile.exportContextClassNames()
        );
        assertEquals(
            new FileChooserHistoryHostProfile.SaveDialogMethod(
                "c", "(Lcom/live2d/ui/window/V;)Ljava/io/File;"
            ),
            profile.saveDialogMethods().get(0)
        );
    }

    @Test
    void unknownArtifactIsRejected() {
        final HostArtifactDigest unknown = new HostArtifactDigest(
            1L,
            "0000000000000000000000000000000000000000000000000000000000000000"
        );
        assertTrue(FileChooserHistoryHostProfile.forArtifact(unknown).isEmpty());
        assertTrue(FileChooserHistoryHostProfile.forArtifact(
            new HostArtifactDigest(CUBISM_52.size(), CUBISM_53.sha256())
        ).isEmpty());
    }

    @Test
    void nullArtifactIsRejected() {
        assertThrows(NullPointerException.class,
            () -> FileChooserHistoryHostProfile.forArtifact(null));
    }

    @Test
    void profileRejectsEmptyMethodOrContextLists() {
        assertThrows(IllegalArgumentException.class, () -> new FileChooserHistoryHostProfile(
            "5.3.02", List.of(), List.of("com.example.ExportContext")
        ));
        assertThrows(IllegalArgumentException.class, () -> new FileChooserHistoryHostProfile(
            "5.3.02",
            List.of(new FileChooserHistoryHostProfile.SaveDialogMethod("c", "()Ljava/io/File;")),
            List.of()
        ));
    }

    @Test
    void saveDialogMethodRequiresNonBlankParts() {
        assertThrows(IllegalArgumentException.class,
            () -> new FileChooserHistoryHostProfile.SaveDialogMethod("", "()Ljava/io/File;"));
        assertThrows(IllegalArgumentException.class,
            () -> new FileChooserHistoryHostProfile.SaveDialogMethod("c", " "));
    }

    @Test
    void forArtifactReturnsSameSelectorsForEqualDigests() {
        assertEquals(
            FileChooserHistoryHostProfile.forArtifact(CUBISM_53),
            FileChooserHistoryHostProfile.forArtifact(new HostArtifactDigest(
                CUBISM_53.size(), CUBISM_53.sha256()
            ))
        );
    }

    @Test
    void emptyArtifactLookupDoesNotThrow() {
        final Optional<FileChooserHistoryHostProfile> result =
            FileChooserHistoryHostProfile.forArtifact(new HostArtifactDigest(
                2L,
                "1111111111111111111111111111111111111111111111111111111111111111"
            ));
        assertTrue(result.isEmpty());
    }
}
