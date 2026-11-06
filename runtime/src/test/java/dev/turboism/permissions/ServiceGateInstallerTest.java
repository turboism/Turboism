package dev.turboism.permissions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterpartResolver;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the invariants of the one service gate: the table covers every declared method, a
 * denied or fenced call never reaches the delegate, adoption releases exactly once, and the
 * terminal verbs stay reachable after the fence.
 */
final class ServiceGateInstallerTest {

    /** Release verbs admitted without a row, mirroring {@code PluginGenerationGuard}. */
    private static final Set<String> TERMINAL_VERBS = Set.of("close", "cancel", "dispose", "release", "unregister");

    private static final List<ServiceGate> REGISTRABLE_ROWS =
            List.of(new ServiceGate(Registrable.class, "start", null, "test.start", ServiceGate.Access.READ));

    static Stream<Class<?>> registeredApis() {
        return ServiceGateInstaller.rows().stream().map(ServiceGate::api).distinct();
    }

    // ------------------------------------------------------------------
    // Table completeness
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("registeredApis")
    void tableCoversEveryGatedMethodOfEachRegisteredApi(final Class<?> api) {
        final Set<String> declared = gatedMethodNames(api);
        final Set<String> rows = new HashSet<>();
        ServiceGateInstaller.rowsFor(api).forEach(row -> {
            assertSame(api, row.api(), "rowsFor must only return rows of the requested interface");
            assertTrue(rows.add(row.method()), "duplicate row for " + api.getName() + "." + row.method());
            assertTrue(
                    methodsNamed(api, row.method()).size() > 0,
                    "row " + api.getName() + "." + row.method() + " names an undeclared method");
        });
        assertEquals(declared, rows, "table does not cover every gated method of " + api.getName());
    }

    @ParameterizedTest
    @MethodSource("registeredApis")
    void installAcceptsTheRegisteredTableAndWrapsTheDelegate(final Class<?> api) {
        final RecordingDelegate delegate = new RecordingDelegate(api);

        final Object service =
                install(api, delegate.proxy(), PermissionChecker.allowAll(), new DisposableScope(), null);

        assertNotSame(delegate.proxy(), service, "install must return the gate, never the delegate");
        assertTrue(Proxy.isProxyClass(service.getClass()), "install must return a proxy implementing the interface");
        assertTrue(api.isInstance(service));
    }

    @ParameterizedTest
    @MethodSource("registeredApis")
    void installRefusesATableThatLeavesOneMethodUncovered(final Class<?> api) {
        final ServiceGate dropped = ServiceGateInstaller.rowsFor(api).stream()
                .filter(row -> !TERMINAL_VERBS.contains(row.method()))
                .findFirst()
                .orElseThrow();
        final List<ServiceGate> incomplete = new ArrayList<>(ServiceGateInstaller.rowsFor(api));
        incomplete.remove(dropped);
        final RecordingDelegate delegate = new RecordingDelegate(api);

        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> install(api, delegate.proxy(), PermissionChecker.allowAll(), null, null, incomplete));

        assertTrue(
                refused.getMessage().contains(dropped.method()),
                "refusal must name the uncovered method, was: " + refused.getMessage());
    }

    @Test
    void installRefusesDuplicateForeignAndUndeclaredRows() {
        final RecordingDelegate delegate = new RecordingDelegate(Registrable.class);
        final ServiceGate row =
                new ServiceGate(Registrable.class, "start", null, "test.start", ServiceGate.Access.READ);
        final ServiceGate foreign = new ServiceGate(Fenced.class, "start", null, "test.start", ServiceGate.Access.READ);
        final ServiceGate undeclared =
                new ServiceGate(Registrable.class, "stop", null, "test.stop", ServiceGate.Access.READ);

        assertThrows(
                IllegalArgumentException.class,
                () -> install(
                        Registrable.class,
                        delegate.proxy(),
                        PermissionChecker.allowAll(),
                        null,
                        null,
                        List.of(row, row)));
        assertThrows(
                IllegalArgumentException.class,
                () -> install(
                        Registrable.class,
                        delegate.proxy(),
                        PermissionChecker.allowAll(),
                        null,
                        null,
                        List.of(foreign)));
        assertThrows(
                IllegalArgumentException.class,
                () -> install(
                        Registrable.class,
                        delegate.proxy(),
                        PermissionChecker.allowAll(),
                        null,
                        null,
                        List.of(undeclared)));
    }

    @Test
    void rowInvariantsRejectIncompleteOrContradictoryDeclarations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServiceGate(
                        Registrable.class,
                        "start",
                        PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                        "test.start",
                        ServiceGate.Access.TERMINAL));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServiceGate(Registrable.class, " ", null, "test.start", ServiceGate.Access.READ));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServiceGate(Registrable.class, "start", " ", "test.start", ServiceGate.Access.READ));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServiceGate(Registrable.class, "start", null, null, ServiceGate.Access.READ));
        assertThrows(NullPointerException.class, () -> new ServiceGate(null, "start", null, "test.start", null));
    }

    // ------------------------------------------------------------------
    // Denied and fenced calls never reach the delegate
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("registeredApis")
    void deniedRowsNeverReachTheDelegate(final Class<?> api) {
        for (final ServiceGate row : ServiceGateInstaller.rowsFor(api)) {
            final RecordingDelegate delegate = new RecordingDelegate(api);
            final List<String> checks = new ArrayList<>();
            final PermissionChecker deny = (permissionId, operation) -> {
                checks.add(permissionId + " " + operation);
                throw new CubismPermissionException("denied for the test");
            };
            final Object service = install(api, delegate.proxy(), deny, new DisposableScope(), null);

            for (final Method method : methodsNamed(api, row.method())) {
                final String subject = api.getName() + "." + row.method() + print(method);
                checks.clear();
                if (row.permissionId() == null) {
                    final Object result = invoke(service, method);
                    assertEquals(
                            1, delegate.calls(row.method()), subject + " declares no permission and must be admitted");
                    assertEquals(List.of(), checks, subject + " must not check a permission it does not declare");
                    if (method.getReturnType() == Registration.class) {
                        assertTrue(result instanceof Registration, subject + " must keep returning a registration");
                    }
                } else {
                    assertThrows(
                            CubismPermissionException.class,
                            () -> invoke(service, method),
                            subject + " must be refused");
                    assertEquals(0, delegate.calls(row.method()), subject + " reached the delegate while denied");
                    assertEquals(
                            List.of(row.permissionId() + " " + row.operationId()),
                            checks,
                            subject + " must check exactly its declared row");
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("registeredApis")
    void fencedWriteRowsAreRefusedBeforeThePermissionCheckAndReadRowsAreNot(final Class<?> api) {
        for (final ServiceGate row : ServiceGateInstaller.rowsFor(api)) {
            final RecordingDelegate delegate = new RecordingDelegate(api);
            final List<String> checks = new ArrayList<>();
            final PermissionChecker allow = (permissionId, operation) -> checks.add(permissionId);
            final Object service = install(api, delegate.proxy(), allow, new DisposableScope(), () -> false);

            for (final Method method : methodsNamed(api, row.method())) {
                final String subject = api.getName() + "." + row.method();
                if (row.access() == ServiceGate.Access.WRITE) {
                    final IllegalStateException refused = assertThrows(
                            IllegalStateException.class, () -> invoke(service, method), subject + " is fenced");
                    assertTrue(
                            refused.getMessage().contains(subject),
                            "fence message must name the refused method, was: " + refused.getMessage());
                    assertEquals(List.of(), checks, subject + " must fail before the permission check");
                    assertEquals(0, delegate.calls(row.method()), subject + " reached the delegate while fenced");
                } else {
                    invoke(service, method);
                    assertEquals(1, delegate.calls(row.method()), subject + " is not fenced and must be forwarded");
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("registeredApis")
    void admittedRowsForwardAndAdoptTheirRegistration(final Class<?> api) throws Exception {
        for (final ServiceGate row : ServiceGateInstaller.rowsFor(api)) {
            for (final Method method : methodsNamed(api, row.method())) {
                final String subject = api.getName() + "." + row.method();
                final RecordingDelegate delegate = new RecordingDelegate(api);
                final DisposableScope scope = new DisposableScope();
                final Object service = install(api, delegate.proxy(), PermissionChecker.allowAll(), scope, () -> true);

                final Object result = invoke(service, method);

                assertEquals(1, delegate.calls(row.method()), subject + " was not forwarded");
                if (method.getReturnType() == Registration.class) {
                    assertTrue(result instanceof Registration, subject + " must keep returning a registration");
                    assertEquals(
                            0, delegate.registration(row.method()).closes, subject + " must stay open until released");
                    ((Registration) result).close();
                    assertEquals(1, delegate.registration(row.method()).closes, subject + " must release exactly once");
                }
                scope.close();
                if (method.getReturnType() == Registration.class) {
                    assertEquals(1, delegate.registration(row.method()).closes, subject + " must never release twice");
                }
            }
        }
    }

    @Test
    void overloadedRowsForwardToTheDelegateInsteadOfAResolvedInterfaceDefault() {
        final List<Integer> orders = new ArrayList<>();
        final ViewContextMenuRegistry delegate = new ViewContextMenuRegistry() {
            @Override
            public Registration contributeStateButtons(final StateButtonContribution contribution) {
                orders.add(0);
                return () -> {};
            }

            @Override
            public Registration contributeStateButtons(final StateButtonContribution contribution, final int order) {
                orders.add(order);
                return () -> {};
            }

            @Override
            public void updateButtonState(final String contributionId, final int axis) {}
        };
        final ViewContextMenuRegistry service = install(
                ViewContextMenuRegistry.class, delegate, PermissionChecker.allowAll(), new DisposableScope(), null);

        service.contributeStateButtons(null);
        service.contributeStateButtons(null, 10);

        assertEquals(List.of(0, 10), orders, "both overloads must reach the delegate with their own arguments");
    }

    // ------------------------------------------------------------------
    // Registration adoption
    // ------------------------------------------------------------------

    @Test
    void adoptedRegistrationIsReleasedExactlyOnceAcrossHandleAndScope() throws Exception {
        final RecordingDelegate delegate = new RecordingDelegate(Registrable.class);
        final DisposableScope scope = new DisposableScope();
        final Registrable service =
                install(Registrable.class, delegate.proxy(), PermissionChecker.allowAll(), scope, null);

        final Registration handle = service.start("first");
        handle.close();
        handle.close();
        scope.close();
        handle.close();

        assertEquals(1, delegate.registration("start").closes);
    }

    @Test
    void closedScopeClosesTheJustCreatedRegistrationInsteadOfLeakingIt() throws Exception {
        final RecordingDelegate delegate = new RecordingDelegate(Registrable.class);
        final DisposableScope scope = new DisposableScope();
        scope.close();
        final Registrable service =
                install(Registrable.class, delegate.proxy(), PermissionChecker.allowAll(), scope, null);

        final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> service.start("first"));

        assertEquals("DisposableScope is already closed", refused.getMessage());
        assertEquals(1, delegate.calls("start"), "the delegate is reached before adoption is attempted");
        assertEquals(1, delegate.registration("start").closes, "the rejected registration must be closed, not leaked");
    }

    @Test
    void withoutAScopeTheRegistrationIsHandedBackUntouched() {
        final RecordingDelegate delegate = new RecordingDelegate(Registrable.class);
        final Registrable service =
                install(Registrable.class, delegate.proxy(), PermissionChecker.allowAll(), null, null);

        final Registration handle = service.start("first");

        assertSame(delegate.registration("start"), handle, "no scope means the plugin owns the registration itself");
        handle.close();
        assertEquals(1, delegate.registration("start").closes);
    }

    // ------------------------------------------------------------------
    // Terminal verbs
    // ------------------------------------------------------------------

    @Test
    void terminalVerbsBypassTheFenceAndThePermissionCheck() {
        final RecordingDelegate delegate = new RecordingDelegate(Terminal.class);
        final List<String> checks = new ArrayList<>();
        final PermissionChecker deny = (permissionId, operation) -> {
            checks.add(permissionId);
            throw new CubismPermissionException("denied for the test");
        };
        final List<ServiceGate> rows = List.of(new ServiceGate(
                Terminal.class,
                "start",
                PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                "test.start",
                ServiceGate.Access.WRITE));
        final Terminal fenced =
                install(Terminal.class, delegate.proxy(), deny, new DisposableScope(), () -> false, rows);
        final Terminal checked =
                install(Terminal.class, delegate.proxy(), deny, new DisposableScope(), () -> true, rows);

        assertThrows(IllegalStateException.class, fenced::start, "a WRITE row is fenced before the permission check");
        assertThrows(CubismPermissionException.class, checked::start, "an unfenced WRITE row checks its permission");
        fenced.close();
        fenced.cancel();
        fenced.dispose();
        fenced.release();
        fenced.unregister();
        checked.close();
        checked.cancel();
        checked.dispose();
        checked.release();
        checked.unregister();

        assertEquals(0, delegate.calls("start"), "neither write reached the delegate");
        assertEquals(2, delegate.calls("close"), "terminal verbs stay reachable after the fence");
        assertEquals(2, delegate.calls("cancel"));
        assertEquals(2, delegate.calls("dispose"));
        assertEquals(2, delegate.calls("release"));
        assertEquals(2, delegate.calls("unregister"));
        assertEquals(1, checks.size(), "only the unfenced write checked a permission");
    }

    void aTerminalRowBypassesChecksEvenForANonReleaseVerb() {
        final RecordingDelegate delegate = new RecordingDelegate(TerminalRow.class);
        final List<ServiceGate> rows = List.of(
                new ServiceGate(TerminalRow.class, "purge", null, "test.purge", ServiceGate.Access.TERMINAL),
                new ServiceGate(
                        TerminalRow.class,
                        "start",
                        PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                        "test.start",
                        ServiceGate.Access.WRITE));
        final TerminalRow service = install(
                TerminalRow.class, delegate.proxy(), denyEverything(), new DisposableScope(), () -> false, rows);

        assertDoesNotThrow(service::purge);
        assertThrows(IllegalStateException.class, service::start);

        assertEquals(1, delegate.calls("purge"));
        assertEquals(0, delegate.calls("start"));
    }

    @Test
    void aWriteRowOnATerminalVerbIsRefusedAtInstallTime() {
        final RecordingDelegate delegate = new RecordingDelegate(Terminal.class);
        final List<ServiceGate> contradiction =
                List.of(new ServiceGate(Terminal.class, "close", null, "test.close", ServiceGate.Access.WRITE));

        assertThrows(
                IllegalArgumentException.class,
                () -> install(
                        Terminal.class, delegate.proxy(), PermissionChecker.allowAll(), null, null, contradiction));
    }

    // ------------------------------------------------------------------
    // Delegate failures and Object methods
    // ------------------------------------------------------------------

    @Test
    void delegateFailuresReachTheCallerUnwrapped() {
        final RecordingDelegate delegate = new RecordingDelegate(Registrable.class);
        final Registrable service =
                install(Registrable.class, delegate.proxy(), PermissionChecker.allowAll(), null, null);
        final IllegalStateException failure = new IllegalStateException("delegate refused the call");
        delegate.failure = failure;

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.start("first")));

        final StackOverflowError fatal = new StackOverflowError("delegate failed fatally");
        delegate.failure = fatal;
        assertSame(fatal, assertThrows(StackOverflowError.class, () -> service.start("first")));
    }

    @Test
    void objectMethodsAreAnsweredWithoutReachingTheDelegate() {
        final RecordingDelegate delegate = new RecordingDelegate(Registrable.class);
        final Registrable service =
                install(Registrable.class, delegate.proxy(), PermissionChecker.allowAll(), null, null);

        assertEquals(System.identityHashCode(service), service.hashCode());
        assertFalse(service.equals(delegate.proxy()), "a gate is never equal to the delegate it protects");
        assertTrue(service.equals(service));
        assertTrue(service.toString().contains(Registrable.class.getName()));
        assertEquals(0, delegate.totalCalls());
    }

    // ------------------------------------------------------------------
    // The absorbed per-wrapper behaviour: per-plugin resolver ownership
    // ------------------------------------------------------------------

    @Test
    void resolverOwnershipStaysPerPluginAcrossTheGate() throws Exception {
        final DisposableScope firstScope = new DisposableScope();
        final DisposableScope secondScope = new DisposableScope();
        final MeshMirrorCounterparts first = install(
                MeshMirrorCounterparts.class,
                NativeMeshMirrorBridge.pluginCounterparts(),
                PermissionChecker.allowAll(),
                firstScope,
                null);
        final MeshMirrorCounterparts second = install(
                MeshMirrorCounterparts.class,
                NativeMeshMirrorBridge.pluginCounterparts(),
                PermissionChecker.allowAll(),
                secondScope,
                null);
        final MeshMirrorCounterpartResolver firstResolver = (source, mesh, axis) -> Optional.empty();
        final MeshMirrorCounterpartResolver secondResolver = (source, mesh, axis) -> Optional.empty();

        assertDoesNotThrow(() -> first.overrideResolver(firstResolver));
        assertDoesNotThrow(() -> second.overrideResolver(secondResolver));
        assertThrows(IllegalStateException.class, () -> first.overrideResolver(firstResolver));

        firstScope.close();
        assertDoesNotThrow(() -> first.mirrorOf(null));
        assertDoesNotThrow(() -> second.mirrorOf(null));
        assertThrows(
                IllegalStateException.class,
                () -> second.overrideResolver(secondResolver),
                "closing another plugin's scope must not free this plugin's resolver slot");
        secondScope.close();
    }

    @Test
    void closedScopeDoesNotRetainARejectedResolver() throws Exception {
        final DisposableScope scope = new DisposableScope();
        scope.close();
        final MeshMirrorCounterparts service = install(
                MeshMirrorCounterparts.class,
                NativeMeshMirrorBridge.pluginCounterparts(),
                PermissionChecker.allowAll(),
                scope,
                null);
        final MeshMirrorCounterpartResolver resolver = (source, mesh, axis) -> Optional.empty();

        final IllegalStateException first =
                assertThrows(IllegalStateException.class, () -> service.overrideResolver(resolver));
        final IllegalStateException second =
                assertThrows(IllegalStateException.class, () -> service.overrideResolver(resolver));

        assertEquals("DisposableScope is already closed", first.getMessage());
        assertEquals(first.getMessage(), second.getMessage(), "the rejected resolver slot must have been released");
    }

    // ------------------------------------------------------------------
    // Test doubles and helpers
    // ------------------------------------------------------------------

    /** Interface with one registration-returning method, used for the adoption cases. */
    interface Registrable {
        Registration start(String name);
    }

    /** Second registration-returning interface, used to prove foreign rows are refused. */
    interface Fenced {
        Registration start(String name);
    }

    /** Interface carrying the SDK's terminal release verbs. */
    interface Terminal {
        void start();

        void close();

        void cancel();

        void dispose();

        void release();

        void unregister();
    }

    /** Interface whose non-release verb is declared a terminal row. */
    interface TerminalRow {
        void purge();

        void start();
    }

    private static PermissionChecker denyEverything() {
        return (permissionId, operation) -> {
            throw new CubismPermissionException("denied for the test");
        };
    }

    private static <T> T install(
            final Class<T> api,
            final Object delegate,
            final PermissionChecker checker,
            final DisposableScope scope,
            final BooleanSupplier scopeActive) {
        final List<ServiceGate> rows = api == Registrable.class ? REGISTRABLE_ROWS : ServiceGateInstaller.rowsFor(api);
        return install(api, delegate, checker, scope, scopeActive, rows);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T install(
            final Class<T> api,
            final Object delegate,
            final PermissionChecker checker,
            final DisposableScope scope,
            final BooleanSupplier scopeActive,
            final List<ServiceGate> rows) {
        return (T) ServiceGateInstaller.install((Class) api, delegate, checker, scope, scopeActive, rows);
    }

    private static Set<String> gatedMethodNames(final Class<?> api) {
        final Set<String> names = new HashSet<>();
        for (final Method method : api.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() == 0 && TERMINAL_VERBS.contains(method.getName())) continue;
            names.add(method.getName());
        }
        return names;
    }

    private static String print(final Method method) {
        final StringBuilder text = new StringBuilder("(");
        final Class<?>[] types = method.getParameterTypes();
        for (int index = 0; index < types.length; index++) {
            if (index > 0) text.append(",");
            text.append(types[index].getSimpleName());
        }
        return text.append(")").toString();
    }

    private static List<Method> methodsNamed(final Class<?> api, final String name) {
        final List<Method> named = new ArrayList<>();
        for (final Method method : api.getMethods()) {
            if (method.getName().equals(name) && method.getDeclaringClass() != Object.class) named.add(method);
        }
        return named;
    }

    private static Object invoke(final Object service, final Method method) {
        try {
            return method.invoke(service, defaultValueArguments(method));
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object[] defaultValueArguments(final Method method) {
        final Class<?>[] types = method.getParameterTypes();
        final Object[] arguments = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            arguments[index] = defaultValue(types[index]);
        }
        return arguments;
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    /** Counts delegate invocations and hands out counted registrations. */
    private static final class RecordingDelegate implements InvocationHandler {

        private final Class<?> api;
        private final Map<String, Integer> calls = new HashMap<>();
        private final Map<String, CountedRegistration> registrations = new LinkedHashMap<>();
        private final Object proxy;
        Throwable failure;

        RecordingDelegate(final Class<?> api) {
            this.api = api;
            this.proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, this);
        }

        Object proxy() {
            return proxy;
        }

        int calls(final String method) {
            return calls.getOrDefault(method, 0);
        }

        int totalCalls() {
            return calls.values().stream().mapToInt(Integer::intValue).sum();
        }

        CountedRegistration registration(final String method) {
            return registrations.get(method);
        }

        @Override
        public Object invoke(final Object ignored, final Method method, final Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RecordingDelegate[" + api.getName() + "]";
                    default -> throw new IllegalStateException("Unexpected Object method: " + method);
                };
            }
            calls.merge(method.getName(), 1, Integer::sum);
            if (failure != null) throw failure;
            if (method.getReturnType() == Registration.class) {
                final CountedRegistration registration = new CountedRegistration();
                registrations.putIfAbsent(method.getName(), registration);
                return registration;
            }
            return defaultValue(method.getReturnType());
        }
    }

    /** Registration that records how often it was closed. */
    private static final class CountedRegistration implements Registration {

        private int closes;

        @Override
        public synchronized void close() {
            closes++;
        }
    }
}
