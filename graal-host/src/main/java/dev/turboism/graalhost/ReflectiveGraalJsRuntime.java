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

/**
 * Reflective Graal Polyglot adapter.
 *
 * <p>The module deliberately has no compile-time Polyglot dependency. This keeps the
 * Turboism repository and protocol implementation Java 17 compatible while the actual
 * host process can be launched with a modern GraalVM runtime. Polyglot and JavaScript
 * artifacts are runtime-only dependencies of this module.</p>
 */
final class ReflectiveGraalJsRuntime {

    private static final String LANGUAGE = "js";
    private static final String BOOTSTRAP = """
        (() => {
          'use strict';
          const invoke = (operation, payload = {}) => {
            const raw = __turboismCall(operation, JSON.stringify(payload));
            return JSON.parse(raw);
          };
          const parameters = Object.freeze({
            list: () => invoke('cubism.parameters.list'),
            get: (id) => invoke('cubism.parameters.get', { id: String(id) }),
            set: (id, value) => invoke('cubism.parameters.set', { id: String(id), value: Number(value) })
          });
          globalThis.turboism = Object.freeze({
            args: Object.freeze(JSON.parse(__turboismArgsJson)),
            cubism: Object.freeze({
              status: () => invoke('cubism.status'),
              parameters
            })
          });
        })();
        """;

    private final ObjectMapper mapper;
    private final Availability availability;

    ReflectiveGraalJsRuntime(final ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.availability = probeAvailability();
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

    private Object newContext(
        final ByteArrayOutputStream stdout,
        final ByteArrayOutputStream stderr
    ) throws ReflectiveOperationException {
        final Class<?> contextClass = Class.forName("org.graalvm.polyglot.Context");
        final Object builder = contextClass
            .getMethod("newBuilder", String[].class)
            .invoke(null, (Object) new String[] {LANGUAGE});

        final Class<?> sandboxClass = Class.forName("org.graalvm.polyglot.SandboxPolicy");
        @SuppressWarnings({"unchecked", "rawtypes"})
        final Object untrusted = Enum.valueOf((Class<? extends Enum>) sandboxClass.asSubclass(Enum.class), "UNTRUSTED");
        invokeBuilder(builder, "sandbox", new Class<?>[] {sandboxClass}, untrusted);
        invokeBuilder(builder, "out", new Class<?>[] {java.io.OutputStream.class}, stdout);
        invokeBuilder(builder, "err", new Class<?>[] {java.io.OutputStream.class}, stderr);

        // UNTRUSTED requires explicit bounded resources. The whole engine also lives in
        // a separate Turboism-owned OS process, providing a second failure boundary.
        option(builder, "engine.MaxIsolateMemory", "256MB");
        option(builder, "sandbox.MaxHeapMemory", "128MB");
        option(builder, "sandbox.MaxCPUTime", "10s");
        option(builder, "sandbox.MaxASTDepth", "128");
        option(builder, "sandbox.MaxThreads", "1");
        option(builder, "sandbox.MaxOutputStreamSize", "1MB");
        option(builder, "sandbox.MaxErrorStreamSize", "1MB");
        return builder.getClass().getMethod("build").invoke(builder);
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

    private Availability probeAvailability() {
        Object context = null;
        try {
            Class.forName("org.graalvm.polyglot.Context");
            Class.forName("org.graalvm.polyglot.SandboxPolicy");
            Class.forName("org.graalvm.polyglot.proxy.ProxyExecutable");
            context = newContext(new ByteArrayOutputStream(), new ByteArrayOutputStream());
            return new Availability(true, System.getProperty("java.vm.name", "unknown") + " / "
                + System.getProperty("java.version", "unknown"));
        } catch (Throwable failure) {
            return new Availability(false, "UNTRUSTED GraalJS context is unavailable: "
                + safeMessage(unwrap(failure), "unknown error"));
        } finally {
            closeContext(context, false);
        }
    }

    private static Throwable unwrap(final Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
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
