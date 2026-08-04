package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.PreviewApi;

/** Best-known source of a semantic Cubism operation. */
@PreviewApi
public enum CubismOperationOrigin {
    /** No reliable source attribution is available. */
    UNKNOWN,
    /** The operation entered through a Turboism SDK call. */
    TURBOISM_API,
    /** The operation was initiated by Cubism's user interface. */
    HOST_UI,
    /** The operation was initiated by Cubism internals. */
    HOST_INTERNAL,
    /** The operation was replayed by native Undo. */
    UNDO,
    /** The operation was replayed by native Redo. */
    REDO
}
