package dev.turboism.adapter.cubism.editor;

/** Seam for SDK object views to expose their generation-bound native source. */
interface EditorNativeObjectRef {

    /** Returns the verified native host source object backing this SDK view. */
    Object nativeSource();
}
