package dev.turboism.mapping.draft;

import java.nio.file.Path;
import java.util.Objects;

/** Performs the final input revalidation and atomic mapping-pack replacement as one responsibility. */
@FunctionalInterface
interface AtomicReplacement {
    void replace(
        Path temporary,
        Path target,
        Path artifact,
        String expectedArtifactHash,
        String expectedBaseHash
    );

    static AtomicReplacement system(final Path root, final AtomicMover mover) {
        final Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        final AtomicMover checkedMover = Objects.requireNonNull(mover, "mover");
        return (temporary, target, artifact, expectedArtifactHash, expectedBaseHash) -> {
            final FileSafety.Digest artifactDigest = FileSafety.digest(artifact, "ARTIFACT_READ_FAILED");
            if (!artifactDigest.sha256().equals(expectedArtifactHash)) {
                fail("ARTIFACT_MISMATCH", "artifact changed before mapping pack replacement");
            }
            final Path relative = normalizedRoot.relativize(target.toAbsolutePath().normalize());
            final Path verifiedTarget = MappingReviewService.resolveTracked(normalizedRoot, relative.toString().replace('\\', '/'));
            final FileSafety.Digest targetDigest = FileSafety.digest(verifiedTarget, "PACK_READ_FAILED");
            if (!targetDigest.sha256().equals(expectedBaseHash)) {
                fail("PACK_CHANGED_BEFORE_MOVE", "mapping pack changed immediately before atomic replacement");
            }
            try {
                checkedMover.move(temporary, verifiedTarget);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                throw new DraftMappingException(
                    "ATOMIC_MOVE_UNSUPPORTED", "filesystem does not support atomic replacement", exception);
            } catch (java.io.IOException exception) {
                throw new DraftMappingException("ATOMIC_WRITE_FAILED", "could not atomically replace mapping pack", exception);
            }
        };
    }

    private static void fail(final String code, final String message) {
        throw new DraftMappingException(code, message);
    }
}
