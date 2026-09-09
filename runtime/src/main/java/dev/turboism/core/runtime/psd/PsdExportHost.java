package dev.turboism.core.runtime.psd;

import dev.turboism.sdk.cubism.id.RawImageId;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Runtime-only export port implemented by a current-model texture projection, never exposed by
 * the permission-checked SDK wrapper. Paths and admission callbacks are trusted runtime inputs.
 */
public interface PsdExportHost {
    /** Runs current-model export with policy revalidation on the native host thread. */
    Observation exportPsdTo(RawImageId source, Path destination, Runnable admission);

    /** Factual native observation, not authorization to issue an SDK file handle. */
    record Observation(String nativeStatus, String integrityStatus, boolean readable, boolean structureMatches) {
        public Observation {
            Objects.requireNonNull(nativeStatus, "nativeStatus");
            Objects.requireNonNull(integrityStatus, "integrityStatus");
        }

        /** Unsupported runtime projections must not invoke native operations. */
        public static Observation unavailable() {
            return new Observation("UNAVAILABLE", "UNAVAILABLE", false, false);
        }
    }
}
