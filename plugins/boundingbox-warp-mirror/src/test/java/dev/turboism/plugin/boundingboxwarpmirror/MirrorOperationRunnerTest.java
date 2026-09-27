package dev.turboism.plugin.boundingboxwarpmirror;

import dev.turboism.plugin.boundingboxwarpmirror.mirror.MirrorOperationRunner;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlocker;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorDirection;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.mirror.WarpMirrorService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MirrorOperationRunnerTest {

    private static final DeformerId A = new DeformerId("warp-a");
    private static final DeformerId B = new DeformerId("warp-b");
    private static final DeformerId C = new DeformerId("warp-c");

    @Test
    void appliesEveryTargetInOrder() {
        final List<WarpMirrorRequest> requests = new ArrayList<>();
        final WarpMirrorService service = request -> {
            requests.add(request);
            return WarpMirrorResult.applied(4, 2);
        };
        final MirrorOperationRunner.Summary summary = new MirrorOperationRunner().apply(
            service, List.of(A, B), WarpMirrorDirection.LEFT_TO_RIGHT, true);

        assertEquals(2, summary.applied());
        assertEquals(0, summary.noChange());
        assertFalse(summary.hasFailures());
        assertEquals(List.of(A, B), requests.stream().map(WarpMirrorRequest::target).toList());
        assertTrue(requests.stream().allMatch(r -> r.preserveDescendants()));
        assertTrue(requests.stream()
            .allMatch(r -> r.direction() == WarpMirrorDirection.LEFT_TO_RIGHT));
    }

    @Test
    void continuesPastBlockedTargets() {
        final WarpMirrorService service = request -> request.target().equals(B)
            ? WarpMirrorResult.blocked(List.of(
                new WarpMirrorBlocker(WarpMirrorBlockerCode.TARGET_LOCKED, "locked")))
            : WarpMirrorResult.applied(1, 0);
        final MirrorOperationRunner.Summary summary = new MirrorOperationRunner().apply(
            service, List.of(A, B, C), WarpMirrorDirection.TOP_TO_BOTTOM, false);

        assertEquals(2, summary.applied());
        assertEquals(1, summary.failures().size());
        assertEquals("warp-b", summary.failures().get(0).targetId());
        assertEquals(List.of(WarpMirrorBlockerCode.TARGET_LOCKED),
            summary.failures().get(0).blockerCodes());
        assertFalse(summary.recoveryFailed());
    }

    @Test
    void reportsRecoveryFailureSeparately() {
        final WarpMirrorService service = request -> request.target().equals(A)
            ? WarpMirrorResult.recoveryFailed("rollback unverified")
            : WarpMirrorResult.noChange();
        final MirrorOperationRunner.Summary summary = new MirrorOperationRunner().apply(
            service, List.of(A, B), WarpMirrorDirection.BOTTOM_TO_TOP, true);

        assertEquals(0, summary.applied());
        assertEquals(1, summary.noChange());
        assertTrue(summary.recoveryFailed());
    }
}
