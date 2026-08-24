package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the Rotation Deformer form replacement event family. */
public sealed interface RotationDeformerFormEvent extends TurboismEvent
    permits RotationDeformerFormEvent.Before,
            RotationDeformerFormEvent.On,
            RotationDeformerFormEvent.After {

    RotationDeformer deformer();

    final class Before implements RotationDeformerFormEvent {
        private final RotationDeformer deformer;
        private final RotationDeformerForm requestedForm;
        private final CallbackScope callbackScope;
        private RotationDeformerForm form;

        public Before(
            final RotationDeformer deformer,
            final RotationDeformerForm requestedForm,
            final RotationDeformerForm form
        ) {
            this(deformer, requestedForm, form, null);
        }

        private Before(
            final RotationDeformer deformer,
            final RotationDeformerForm requestedForm,
            final RotationDeformerForm form,
            final CallbackScope callbackScope
        ) {
            this.deformer = Objects.requireNonNull(deformer, "deformer");
            this.requestedForm = Objects.requireNonNull(requestedForm, "requestedForm");
            this.form = Objects.requireNonNull(form, "form");
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted form edit. */
        public static Callback openCallback(
            final RotationDeformer deformer,
            final RotationDeformerForm requestedForm,
            final RotationDeformerForm form
        ) {
            return new Callback(deformer, requestedForm, form);
        }

        @Override public RotationDeformer deformer() { return deformer; }
        public RotationDeformerForm requestedForm() { return requestedForm; }
        /** Returns the candidate form value that will be applied. */
        public RotationDeformerForm form() { return form; }

        /** Replaces the candidate form value for the current callback. */
        public void setForm(final RotationDeformerForm form) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.form = Objects.requireNonNull(form, "form");
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final RotationDeformer deformer,
                final RotationDeformerForm requestedForm,
                final RotationDeformerForm form
            ) {
                event = new Before(deformer, requestedForm, form, scope);
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
                        "Rotation form before-event mutation is outside its callback scope."
                    );
                }
            }

            private void close() {
                requireOpen();
                open = false;
            }
        }
    }

    record On(
        RotationDeformer deformer,
        RotationDeformerForm oldForm,
        RotationDeformerForm newForm
    ) implements RotationDeformerFormEvent {
        public On {
            deformer = Objects.requireNonNull(deformer, "deformer");
            oldForm = Objects.requireNonNull(oldForm, "oldForm");
            newForm = Objects.requireNonNull(newForm, "newForm");
        }
    }

    record After(RotationDeformer deformer, RotationDeformerForm finalForm)
        implements RotationDeformerFormEvent {
        public After {
            deformer = Objects.requireNonNull(deformer, "deformer");
            finalForm = Objects.requireNonNull(finalForm, "finalForm");
        }
    }
}
