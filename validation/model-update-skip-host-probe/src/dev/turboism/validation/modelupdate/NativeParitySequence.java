package dev.turboism.validation.modelupdate;

import java.awt.EventQueue;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Test-only scheduling of native/native/cached/native parity outside timed windows. */
final class NativeParitySequence {
    @FunctionalInterface interface Capture<T> { T capture(boolean enabled) throws Exception; }
    private NativeParitySequence() { }

    static <T> List<T> capture(Capture<T> source) throws Exception {
        return onEdt(() -> List.of(source.capture(false), source.capture(false),
            source.capture(true), source.capture(false)));
    }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (EventQueue.isDispatchThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        EventQueue.invokeLater(task);
        try { return task.get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { task.cancel(false); throw failure; }
    }
}
