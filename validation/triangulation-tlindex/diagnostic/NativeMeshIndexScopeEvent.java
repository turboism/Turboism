package dev.turboism.adapter.cubism.mesh;

import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Diagnostic overlay only. Never package this event in a performance candidate. */
@Name("dev.turboism.validation.NativeMeshIndexScope")
@Enabled(true)
@StackTrace(false)
@Threshold("0 ns")
final class NativeMeshIndexScopeEvent extends Event {
    long tableCalls;
    long hits;
    long absent;
    long unknown;
    long appended;
    boolean discarded;
    boolean released;
}
