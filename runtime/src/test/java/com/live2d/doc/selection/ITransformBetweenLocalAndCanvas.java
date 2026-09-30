package com.live2d.doc.selection;

/**
 * Test stub of the reviewed host transform contract
 * ({@code ITransformBetweenLocalAndCanvas}). The bridge resolves the singleton
 * via the {@code Companion} field's {@code a()} accessor.
 */
public interface ITransformBetweenLocalAndCanvas {

    Companion Companion = new Companion();

    /** Stub of the host companion object ({@code ITransformBetweenLocalAndCanvas$a}). */
    final class Companion {
        private final ITransformBetweenLocalAndCanvas instance = new ITransformBetweenLocalAndCanvas() {};

        public ITransformBetweenLocalAndCanvas a() {
            return instance;
        }
    }
}
