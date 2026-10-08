package com.live2d.doc.selection;

/**
 * Test stub of the reviewed host selection-space singleton holder
 * ({@code com.live2d.doc.selection.d}). The bridge resolves the local space via
 * the static companion field {@code b} (host type {@code d$a}) and its {@code a()}
 * accessor; only the member shapes are contract-relevant.
 */
public final class d {

    public static final Companion b = new Companion();

    private d() {}

    /** Stub of the host companion ({@code d$a}) exposing {@code a()}. */
    public static final class Companion {
        public d a() {
            return new d();
        }
    }
}
