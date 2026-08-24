package dev.turboism.graalhost;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reflective Graal Polyglot adapter.
 *
 * <p>The module deliberately has no compile-time Polyglot dependency. This keeps the
 * Turboism repository and protocol implementation Java 17 compatible while the actual
 * host process can be launched with a modern GraalVM runtime. Polyglot and JavaScript
 * artifacts are runtime-only dependencies of this module.</p>
 */
final class ReflectiveGraalJsRuntime implements AutoCloseable {

    private static final String LANGUAGE = "js";
    private static final String BOOTSTRAP = """
        (() => {
          'use strict';
          const invoke = (operation, payload = {}) => {
            const raw = __turboismCall(operation, JSON.stringify(payload));
            return JSON.parse(raw);
          };
          const ids = (values) => Array.from(values, value => String(value));
          const numericValue = (value) => {
            if (typeof value !== 'number' || !Number.isFinite(value)) {
              throw new TypeError('Parameter value must be a finite number.');
            }
            return value;
          };
          const parameters = Object.freeze({
            list: () => invoke('cubism.parameters.list'),
            snapshot: () => invoke('cubism.parameters.snapshot'),
            get: (id) => invoke('cubism.parameters.get', { id: String(id) }),
            getMany: (values) => invoke('cubism.parameters.getMany', { ids: ids(values) }),
            set: (id, value) => invoke('cubism.parameters.set', {
              id: String(id),
              value: numericValue(value)
            }),
            setMany: (changes) => invoke('cubism.parameters.setMany', {
              changes: Array.from(changes, change => ({
                id: String(change.id),
                value: numericValue(change.value)
              }))
            }),
            reset: (id) => invoke('cubism.parameters.reset', { id: String(id) }),
            resetMany: (values) => invoke('cubism.parameters.resetMany', { ids: ids(values) })
          });
          const model = Object.freeze({
            snapshot: () => invoke('cubism.model.snapshot')
          });
          globalThis.turboism = Object.freeze({
            args: Object.freeze(JSON.parse(__turboismArgsJson)),
            cubism: Object.freeze({
              status: () => invoke('cubism.status'),
              model,
              parameters
            })
          });
        })();
        """;

    private final ObjectMapper mapper;
    /**
     * Engine metadata and compiled guest code are shared for the lifetime of this
     * isolated host process. Guest globals never live here: every execution still
     * receives a new sandboxed Context and fresh bindings.
     */
    private final Object engine;
    private final Availability availability;
    private final AtomicInteger contextsCreated = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ReflectiveGraalJsRuntime(final ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Object created = null;
        Availability detected;
        try {
            created = newEngine();
            probeContext(created);
            detected = new Availability(true, System.getProperty("java.vm.name", "unknown") + " / "
                + System.getProperty("java.version", "unknown"));
        } catch (Throwable failure) {
            closeEngine(created);
            created = null;
            detected = new Availability(false, "UNTRUSTED GraalJS context is unavailable: "
                + safeMessage(unwrap(failure), "unknown error"));
        }
        this.engine = created;
        this.availability = detected;
    }

    Availability availability() {
        return availability;
    }

    ExecutionResult execute(
        final String source,
        final Map<String, String> arguments,
        final HostCall hostCall,
        final ExecutionControl control
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(hostCall, "hostCall");
        Objects.requireNonNull(control, "control");
        if (closed.get()) {
            return ExecutionResult.failed(
                "GRAAL_RUNTIME_CLOSED",
                "GraalJS runtime is closed.",
                ""
            );
        }
        if (!availability.available()) {
            return ExecutionResult.failed("GRAAL_RUNTIME_UNAVAILABLE", availability.detail(), "");
        }

        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Object context = null;
        try {
            context = newContext(stdout, stderr);
            control.attach(context);
            installBindings(context, arguments, hostCall);
            eval(context, BOOTSTRAP);
            eval(context, source);
            return ExecutionResult.success(text(stdout));
        } catch (Throwable failure) {
            final Throwable cause = unwrap(failure);
            final String output = text(stdout) + text(stderr);
            if (control.cancelRequested()) {
                return ExecutionResult.cancelled("Script execution was cancelled.", output);
            }
            if (polyglotBoolean(cause, "isResourceExhausted")) {
                return ExecutionResult.timedOut(safeMessage(cause, "Script resource limit exceeded."), output);
            }
            if (polyglotBoolean(cause, "isCancelled")) {
                return ExecutionResult.cancelled(safeMessage(cause, "Script execution was cancelled."), output);
            }
            final GraalHostMain.HostCallException hostFailure = hostFailure(cause);
            if (hostFailure != null) {
                return ExecutionResult.failed(
                    hostFailure.code(),
                    safeMessage(hostFailure, "Script host call failed."),
                    output
                );
            }
            return ExecutionResult.failed(
                "SCRIPT_EVALUATION_FAILED",
                safeMessage(cause, "Script evaluation failed."),
                output
            );
        } finally {
            control.detach(context);
            closeContext(context, false);
        }
    }

    void cancel(final ExecutionControl control) {
        Objects.requireNonNull(control, "control");
        control.requestCancel();
        closeContext(control.context(), true);
    }

    private Object newEngine() throws ReflectiveOperationException {
        final Class<?> engineClass = Class.forName("org.graalvm.polyglot.Engine");
        final Object builder = engineClass.getMethod("newBuilder", String[].class)
            .invoke(null, (Object) new String[] {LANGUAGE});
        final Class<?> sandboxClass = Class.forName("org.graalvm.polyglot.SandboxPolicy");
        @SuppressWarnings({"unchecked", "rawtypes"})
        final Object untrusted = Enum.valueOf(
            (Class<? extends Enum>) sandboxClass.asSubclass(Enum.class), "UNTRUSTED"
        );
        invokeBuilder(builder, "sandbox", new Class<?>[] {sandboxClass}, untrusted);
        invokeBuilder(
            builder, "out", new Class<?>[] {java.io.OutputStream.class},
            java.io.OutputStream.nullOutputStream()
        );
        invokeBuilder(
            builder, "err", new Class<?>[] {java.io.OutputStream.class},
            java.io.OutputStream.nullOutputStream()
        );
        option(builder, "engine.MaxIsolateMemory", "256MB");
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private Object newContext(
        final ByteArrayOutputStream stdout,
        final ByteArrayOutputStream stderr
    ) throws ReflectiveOperationException {
        return newContext(engine, stdout, stderr);
    }

    private Object newContext(
        final Object selectedEngine,
        final ByteArrayOutputStream stdout,
        final ByteArrayOutputStream stderr
    ) throws ReflectiveOperationException {
        final Class<?> contextClass = Class.forName("org.graalvm.polyglot.Context");
        final Object builder = contextClass
            .getMethod("newBuilder", String[].class)
            .invoke(null, (Object) new String[] {LANGUAGE});

        final Class<?> engineClass = Class.forName("org.graalvm.polyglot.Engine");
        invokeBuilder(builder, "engine", new Class<?>[] {engineClass}, selectedEngine);
        invokeBuilder(builder, "out", new Class<?>[] {java.io.OutputStream.class}, stdout);
        invokeBuilder(builder, "err", new Class<?>[] {java.io.OutputStream.class}, stderr);

        // A fresh Context owns guest globals and bindings for one execution. The shared
        // Engine retains only engine-level metadata/code and remains in this OS process.
        option(builder, "sandbox.MaxHeapMemory", "128MB");
        option(builder, "sandbox.MaxCPUTime", "10s");
        option(builder, "sandbox.MaxASTDepth", "128");
        option(builder, "sandbox.MaxThreads", "1");
        // Each stream is capped below the 4 MiB protocol envelope's worst-case
        // six-character JSON escaping expansion; stdout and stderr may be combined.
        option(builder, "sandbox.MaxOutputStreamSize", "256KB");
        option(builder, "sandbox.MaxErrorStreamSize", "256KB");
        final Object context = builder.getClass().getMethod("build").invoke(builder);
        contextsCreated.incrementAndGet();
        return context;
    }

    private static void option(final Object builder, final String key, final String value)
        throws ReflectiveOperationException {
        invokeBuilder(builder, "option", new Class<?>[] {String.class, String.class}, key, value);
    }

    private static void invokeBuilder(
        final Object builder,
        final String method,
        final Class<?>[] parameterTypes,
        final Object... arguments
    ) throws ReflectiveOperationException {
        builder.getClass().getMethod(method, parameterTypes).invoke(builder, arguments);
    }

    private void installBindings(
        final Object context,
        final Map<String, String> arguments,
        final HostCall hostCall
    ) throws Exception {
        final Class<?> contextClass = context.getClass();
        final Object bindings = contextClass.getMethod("getBindings", String.class).invoke(context, LANGUAGE);
        final Method putMember = bindings.getClass().getMethod("putMember", String.class, Object.class);
        putMember.invoke(bindings, "__turboismArgsJson", mapper.writeValueAsString(arguments));

        final Class<?> executableClass = Class.forName("org.graalvm.polyglot.proxy.ProxyExecutable");
        final Object executable = Proxy.newProxyInstance(
            executableClass.getClassLoader(),
            new Class<?>[] {executableClass},
            (proxy, method, invocationArguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "TurboismHostCall";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == invocationArguments[0];
                        default -> null;
                    };
                }
                if (!"execute".equals(method.getName())) {
                    throw new UnsupportedOperationException(method.getName());
                }
                final Object values = invocationArguments == null || invocationArguments.length == 0
                    ? null
                    : invocationArguments[0];
                if (values == null || Array.getLength(values) != 2) {
                    throw new IllegalArgumentException("Turboism host call requires operation and JSON payload");
                }
                final String operation = valueAsString(Array.get(values, 0));
                final String payload = valueAsString(Array.get(values, 1));
                return hostCall.call(operation, payload);
            }
        );
        putMember.invoke(bindings, "__turboismCall", executable);
    }

    private static String valueAsString(final Object value) throws ReflectiveOperationException {
        return (String) value.getClass().getMethod("asString").invoke(value);
    }

    private static void eval(final Object context, final String source) throws ReflectiveOperationException {
        context.getClass().getMethod("eval", String.class, CharSequence.class)
            .invoke(context, LANGUAGE, source);
    }

    private static void closeContext(final Object context, final boolean cancelIfExecuting) {
        if (context == null) {
            return;
        }
        try {
            context.getClass().getMethod("close", boolean.class).invoke(context, cancelIfExecuting);
        } catch (ReflectiveOperationException ignored) {
            try {
                context.getClass().getMethod("close").invoke(context);
            } catch (ReflectiveOperationException ignoredAgain) {
                // Process supervision remains the final cleanup boundary.
            }
        }
    }

    private void probeContext(final Object candidateEngine) throws ReflectiveOperationException {
        Class.forName("org.graalvm.polyglot.Context");
        Class.forName("org.graalvm.polyglot.SandboxPolicy");
        Class.forName("org.graalvm.polyglot.proxy.ProxyExecutable");
        if (candidateEngine == null) {
            throw new IllegalStateException("Graal Engine is unavailable");
        }
        Object context = null;
        try {
            context = newContext(
                candidateEngine, new ByteArrayOutputStream(), new ByteArrayOutputStream()
            );
        } finally {
            closeContext(context, false);
        }
    }

    private static void closeEngine(final Object engine) {
        if (engine == null) {
            return;
        }
        try {
            engine.getClass().getMethod("close").invoke(engine);
        } catch (ReflectiveOperationException ignored) {
            // The host process remains the final cleanup boundary.
        }
    }

    Object engineForTest() {
        return engine;
    }

    int contextsCreatedForTest() {
        return contextsCreated.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeEngine(engine);
        }
    }

    private static Throwable unwrap(final Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    private static GraalHostMain.HostCallException hostFailure(final Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof GraalHostMain.HostCallException hostFailure) {
                return hostFailure;
            }
            final Throwable hostException = polyglotHostException(current);
            if (hostException instanceof GraalHostMain.HostCallException hostFailure) {
                return hostFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable polyglotHostException(final Throwable failure) {
        try {
            final Method isHostException = failure.getClass().getMethod("isHostException");
            if (!Boolean.TRUE.equals(isHostException.invoke(failure))) {
                return null;
            }
            final Object hostException = failure.getClass().getMethod("asHostException").invoke(failure);
            return hostException instanceof Throwable throwable ? throwable : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean polyglotBoolean(final Throwable failure, final String methodName) {
        try {
            final Method method = failure.getClass().getMethod(methodName);
            final Object value = method.invoke(failure);
            return Boolean.TRUE.equals(value);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static String safeMessage(final Throwable failure, final String fallback) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return fallback;
        }
        final String value = failure.getMessage().replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private static String text(final ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8);
    }

    record Availability(boolean available, String detail) {
    }

    record ExecutionResult(Status status, String code, String message, String output) {
        static ExecutionResult success(final String output) {
            return new ExecutionResult(Status.SUCCEEDED, "", "", output);
        }

        static ExecutionResult failed(final String code, final String message, final String output) {
            return new ExecutionResult(Status.FAILED, code, message, output);
        }

        static ExecutionResult cancelled(final String message, final String output) {
            return new ExecutionResult(Status.CANCELLED, "SCRIPT_CANCELLED", message, output);
        }

        static ExecutionResult timedOut(final String message, final String output) {
            return new ExecutionResult(Status.TIMED_OUT, "SCRIPT_RESOURCE_LIMIT", message, output);
        }
    }

    enum Status {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    static final class ExecutionControl {
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private volatile Object context;

        void attach(final Object context) {
            this.context = context;
            if (cancelRequested.get()) {
                closeContext(context, true);
            }
        }

        void detach(final Object expected) {
            if (this.context == expected) {
                this.context = null;
            }
        }

        Object context() {
            return context;
        }

        void requestCancel() {
            cancelRequested.set(true);
        }

        boolean cancelRequested() {
            return cancelRequested.get();
        }
    }

    @FunctionalInterface
    interface HostCall {
        String call(String operation, String payloadJson) throws Exception;
    }
}
