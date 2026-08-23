package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic Warp Deformer grid replacement event family. */
@PreviewApi
public sealed interface WarpDeformerGridEvent extends TurboismEvent
    permits WarpDeformerGridEvent.Before,
            WarpDeformerGridEvent.On,
            WarpDeformerGridEvent.After {

    WarpDeformer deformer();

    @PreviewApi
    final class Before implements WarpDeformerGridEvent {
        private final WarpDeformer deformer;
        private final WarpGrid requestedGrid;
        private final CallbackScope callbackScope;
        private WarpGrid grid;

        public Before(
            final WarpDeformer deformer,
            final WarpGrid requestedGrid,
            final WarpGrid grid
        ) {
            this(deformer, requestedGrid, grid, null);
        }

        private Before(
            final WarpDeformer deformer,
            final WarpGrid requestedGrid,
            final WarpGrid grid,
            final CallbackScope callbackScope
        ) {
            this.deformer = Objects.requireNonNull(deformer, "deformer");
            this.requestedGrid = Objects.requireNonNull(requestedGrid, "requestedGrid");
            this.grid = Objects.requireNonNull(grid, "grid");
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted grid edit. */
        public static Callback openCallback(
            final WarpDeformer deformer,
            final WarpGrid requestedGrid,
            final WarpGrid grid
        ) {
            return new Callback(deformer, requestedGrid, grid);
        }

        @Override public WarpDeformer deformer() { return deformer; }
        public WarpGrid requestedGrid() { return requestedGrid; }
        /** Returns the candidate grid value that will be applied. */
        public WarpGrid grid() { return grid; }

        /** Replaces the candidate grid value for the current callback. */
        public void setGrid(final WarpGrid grid) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.grid = Objects.requireNonNull(grid, "grid");
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final WarpDeformer deformer,
                final WarpGrid requestedGrid,
                final WarpGrid grid
            ) {
                event = new Before(deformer, requestedGrid, grid, scope);
            }

            /** Returns the mutable event while this callback scope remains open. */
            public Before event() {
                scope.requireOpen();
                return event;
            }

            @Override public void close() { scope.close(); }
        }

        private static final class CallbackScope {
            private final Thread ownerThread;
            private boolean open = true;

            private CallbackScope(final Thread ownerThread) { this.ownerThread = ownerThread; }

            private void requireOpen() {
                if (!open || Thread.currentThread() != ownerThread) {
                    throw new IllegalStateException(
                        "Warp grid before-event mutation is outside its callback scope."
                    );
                }
            }

            private void close() {
                requireOpen();
                open = false;
            }
        }
    }

    @PreviewApi
    record On(WarpDeformer deformer, WarpGrid oldGrid, WarpGrid newGrid)
        implements WarpDeformerGridEvent {
        public On {
            deformer = Objects.requireNonNull(deformer, "deformer");
            oldGrid = Objects.requireNonNull(oldGrid, "oldGrid");
            newGrid = Objects.requireNonNull(newGrid, "newGrid");
        }
    }

    @PreviewApi
    record After(WarpDeformer deformer, WarpGrid finalGrid)
        implements WarpDeformerGridEvent {
        public After {
            deformer = Objects.requireNonNull(deformer, "deformer");
            finalGrid = Objects.requireNonNull(finalGrid, "finalGrid");
        }
    }
}
