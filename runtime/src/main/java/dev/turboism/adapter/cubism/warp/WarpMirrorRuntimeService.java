package dev.turboism.adapter.cubism.warp;

import dev.turboism.sdk.cubism.mirror.WarpMirrorBlocker;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.mirror.WarpMirrorService;

import java.util.List;
import java.util.Objects;

/**
 * Runtime boundary for the whole-object Warp mirror. Converts unexpected port failures into
 * a typed {@link WarpMirrorResult#blocked blocked} result so host exceptions never leak
 * across the SDK surface.
 */
public final class WarpMirrorRuntimeService implements WarpMirrorService {

    private static final System.Logger LOGGER =
        System.getLogger(WarpMirrorRuntimeService.class.getName());

    private final Port port;

    public WarpMirrorRuntimeService(final Port port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    @Override
    public WarpMirrorResult apply(final WarpMirrorRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return Objects.requireNonNull(port.apply(request), "port result");
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.ERROR,
                "Warp mirror apply failed with an unclassified runtime error", failure);
            return WarpMirrorResult.blocked(List.of(new WarpMirrorBlocker(
                WarpMirrorBlockerCode.WRITE_FAILED,
                "The mirror operation failed with an unclassified runtime error.")));
        }
    }

    /** Editor-side operation port; keeps native handles behind the SDK boundary. */
    public interface Port {

        /**
         * Executes one whole-object mirror for the request.
         *
         * @param request validated request
         * @return typed outcome; never {@code null}
         */
        WarpMirrorResult apply(WarpMirrorRequest request);
    }
}
