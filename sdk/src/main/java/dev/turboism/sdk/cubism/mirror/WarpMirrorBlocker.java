package dev.turboism.sdk.cubism.mirror;

import java.util.Objects;

/**
 * One typed rejection detail of a {@link WarpMirrorResult}.
 *
 * @param code machine-readable rejection code
 * @param reason human-readable diagnostic, never {@code null}
 */
public record WarpMirrorBlocker(WarpMirrorBlockerCode code, String reason) {
    public WarpMirrorBlocker {
        code = Objects.requireNonNull(code, "code");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
