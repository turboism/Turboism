package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic Part rename event family. */
@PreviewApi
public sealed interface PartNameEvent extends TurboismEvent
    permits PartNameEvent.Before, PartNameEvent.On, PartNameEvent.After {

    Part part();

    /** Synchronous state published before the host rename. */
    @PreviewApi
    final class Before implements PartNameEvent {
        private final Part part;
        private final String requestedName;
        private final CallbackScope callbackScope;
        private String name;

        public Before(final Part part, final String requestedName, final String name) {
            this(part, requestedName, name, null);
        }

        private Before(
            final Part part,
            final String requestedName,
            final String name,
            final CallbackScope callbackScope
        ) {
            this.part = Objects.requireNonNull(part, "part");
            this.requestedName = Objects.requireNonNull(requestedName, "requestedName");
            this.name = Objects.requireNonNull(name, "name");
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted name edit. */
        public static Callback openCallback(
            final Part part,
            final String requestedName,
            final String name
        ) {
            return new Callback(part, requestedName, name);
        }

        @Override public Part part() { return part; }
        public String requestedName() { return requestedName; }
        /** Returns the candidate name value that will be applied. */
        public String name() { return name; }

        /** Replaces the candidate name value for the current callback. */
        public void setName(final String name) {
            if (callbackScope != null) {
                callbackScope.requireOpen();
            }
            this.name = Objects.requireNonNull(name, "name");
        }

        /** One Runtime-owned mutable callback scope. */
        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Part part,
                final String requestedName,
                final String name
            ) {
                event = new Before(part, requestedName, name, scope);
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

            private CallbackScope(final Thread ownerThread) {
                this.ownerThread = ownerThread;
            }

            private void requireOpen() {
                if (!open || Thread.currentThread() != ownerThread) {
                    throw new IllegalStateException(
                        "Part name before-event mutation is outside its callback scope."
                    );
                }
            }

            private void close() {
                requireOpen();
                open = false;
            }
        }
    }

    /** State published after a successful rename that changed the name. */
    @PreviewApi
    record On(Part part, String oldName, String newName) implements PartNameEvent {
        public On {
            part = Objects.requireNonNull(part, "part");
            oldName = Objects.requireNonNull(oldName, "oldName");
            newName = Objects.requireNonNull(newName, "newName");
        }
    }

    /** State published after every successful rename. */
    @PreviewApi
    record After(Part part, String finalName) implements PartNameEvent {
        public After {
            part = Objects.requireNonNull(part, "part");
            finalName = Objects.requireNonNull(finalName, "finalName");
        }
    }
}
